package com.eleventv.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.eleventv.tv.ElevenApp
import com.eleventv.tv.ui.design.ElevenTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Method

/**
 * 播放页容器。
 *
 * 只做三件事：从 Intent 找回要播的文件、把按键转给 [PlayerState]、随生命周期释放播放器。
 * 播放逻辑一行都不在这里——TV 播放器最容易烂的地方就是把状态散在 Activity、View、Service 三处。
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    private var state: PlayerState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /**
         * 播放器必须自己扛住屏幕。
         *
         * 电视盒子只要几分钟没收到按键，就会熄灭屏幕或者拉起系统屏保——用户只是
         * 在看片，什么都没按。以前这里一行都没写，看到一半画面就黑掉。
         *
         * 这个标志是屏幕常亮的标准做法：只要这个 Activity 可见，系统就不会
         * 因为超时熄屏、也不会启动 Daydream 屏保。离开（onPause 后不可见）时
         * 窗口不再可见，标志自然失效，不需要手动清。
         */
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val repo = (application as ElevenApp).repository
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val fileId = intent.getStringExtra(EXTRA_FILE_ID)

        val entry = entryId?.let { repo.entry(it) }
        val requestedFile = entry?.files?.firstOrNull { it.id == fileId }
        val file = requestedFile ?: entry?.primaryFile

        // 重新扫描后 fileId 可能已变；原来静默回退到第一集，用户会以为进度丢了。
        if (requestedFile == null && fileId != null && entry != null) {
            android.widget.Toast
                .makeText(this, "该集已变更，将播放默认集", android.widget.Toast.LENGTH_SHORT)
                .show()
        }

        if (entry == null || file == null) {
            // 媒体库可能刚被重新扫描过，条目不在了。
            // 以前是静默 finish()：用户点了播放，界面闪一下就回首页，看起来像按键失灵。
            // 一句话说明白「不是播放器坏了」，比沉默有用。
            android.widget.Toast
                .makeText(this, "这条内容已不在媒体库里，请重新扫描", android.widget.Toast.LENGTH_SHORT)
                .show()
            finish()
            return
        }

        val player = PlayerState(
            context = this,
            repo = repo,
            entry = entry,
            initialFile = file,
            settings = repo.settings.value,
            onExit = { finish() },
            // 外挂字幕的列目录交给 PlayerState 在起播前于 IO 线程做：
            // 这里只提供「怎么列」，不在此处 await。setContent 立即执行，
            // 查字幕期间界面显示的是 buffering，不会黑屏。
            // 按目录缓存 + 硬超时都在 PlayerState 内，换集到别的季目录会重新列。
            subtitleSiblings = { target ->
                val srv = repo.serverOf(target.serverId)
                if (srv == null) emptyList() else listSiblingNames(srv, repo.smb, target.path)
            },
            // 播放器里切了解码方式就写回设置：下次进来（含其它片）沿用这个选择
            onDecoderChosen = { chosen ->
                lifecycleScope.launch { repo.updateSettings { it.copy(decoder = chosen) } }
            },
        )
        state = player

        startScreenGuard(player)

        // 返回键单独接管：Android 13+ 的返回手势不经过 onKeyDown
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (player.handleBack()) return
                    finish()
                }
            },
        )

        setContent {
            ElevenTheme {
                PlayerScreen(state = player)
            }
        }
    }

    /**
     * 播放期间持续压制系统屏保。
     *
     * [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] 只是第一步。不少电视盒子
     * （尤其国产 ROM 自带的那套「屏保 / 环境屏」）并不看这个标志，而是盯
     * `PowerManager` 的空闲计时：只要用户超过设定时间没操作，就把自己的屏保拉起来，
     * 播放器照样被盖住。所以要周期性地上报一次用户活动，把空闲计时器顶回去。
     *
     * 三个刻意的取舍：
     *
     * - **只在真正播放时上报**。暂停后用户可能就是想离开一会儿，屏保照常启动才对；
     * - **绑在 RESUMED 上**。Activity 不可见（切后台、系统弹窗）时立刻停，不在后台
     *   偷偷把系统唤起来，那是耗电和「屏幕自己亮了」的来源；
     * - **间隔 45 秒**。系统屏保最短也要一分钟才触发，这个频率足够，代价接近于零。
     */
    private fun startScreenGuard(player: PlayerState) {
        val power = getSystemService(PowerManager::class.java) ?: return

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (player.isPlaying) pokeUserActivity(power)
                    delay(SCREEN_GUARD_INTERVAL)
                }
            }
        }
    }

    /**
     * 上报一次用户活动。
     *
     * `noChangeLights = true`：屏幕本来就是亮的，这里只需要重置空闲计时，
     * 不去动背光和键盘灯——传 false 会让部分机型误以为要亮屏。
     *
     * **为什么用反射**：`PowerManager.userActivity(long, boolean)` 从 API 33 起被废弃，
     * 到 compileSdk 36 已经从 android.jar 里彻底移除，直接调用编译不过（Unresolved
     * reference）；而目标机型是 Android 9，只有这一个重载。运行时找一次并缓存，
     * 找不到就静默放弃——主路径的 `FLAG_KEEP_SCREEN_ON` 仍然生效，只是少了这层兜底。
     *
     * **兼容边界（需真机回归）**：API 33+ 上该隐藏方法通常已移除，反射返回 null，
     * 这层「压制国产 ROM 屏保」的兜底实际不生效，只剩 FLAG_KEEP_SCREEN_ON。
     * 这里不猜测其它系统私有 API、也不因此抛异常；需在 Android 13+ 盒子上静置超过
     * 系统屏保时间做一次回归，确认画面是否被屏保覆盖，再决定是否另做适配。
     */
    private fun pokeUserActivity(power: PowerManager) {
        val method = userActivityMethod ?: return
        runCatching { method.invoke(power, SystemClock.uptimeMillis(), true) }
    }

    private val userActivityMethod: Method? by lazy {
        runCatching {
            PowerManager::class.java.getMethod(
                "userActivity",
                Long::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            )
        }.getOrNull()
    }

    /**
     * 按键统一走这里分发。
     *
     * 不用 Compose 的 focus/onKeyEvent：OSD 一旦有可聚焦控件，左右键就会被焦点搜索吃掉，
     * seek 手感和进度条都会失灵。
     *
     * 也不用 `dispatchKeyEvent`：AndroidX 在 ComponentActivity 上把它标成了
     * `@RestrictTo(LIBRARY_GROUP_PREFIX)`，应用层覆写会被 lint 拦下。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (state?.handleKey(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (state?.handleKey(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // 从后台回来时再声明一次。个别 ROM 会在窗口重新可见时清掉窗口标志，
        // 而 addFlags 本身是幂等的，重复调用没有代价。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        state?.onHostPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        state?.release()
        state = null
    }

    companion object {
        private const val EXTRA_ENTRY_ID = "entryId"
        private const val EXTRA_FILE_ID = "fileId"

        /** 压制屏保的心跳间隔；系统屏保最短也要一分钟才触发 */
        private const val SCREEN_GUARD_INTERVAL = 45_000L

        fun intent(context: Context, entryId: String, fileId: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ENTRY_ID, entryId)
                .putExtra(EXTRA_FILE_ID, fileId)
    }
}
