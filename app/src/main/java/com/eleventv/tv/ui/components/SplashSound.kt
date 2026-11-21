package com.eleventv.tv.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.eleventv.tv.R

/**
 * 启动音效播放。
 *
 * 用 ExoPlayer（而非 SoundPool）：音效是 AAC（`.m4a`），SoundPool 对 AAC 在 Android 9 上支持不一致；
 * ExoPlayer 走 Media3 的解码器，项目已依赖 `media3-exoplayer`，不引入新组件。
 *
 * 声音设计与 [SplashMotion] 的动画时间表逐一对齐（见 `tools/make_splash_sound_alt.py`，B 律动版）：
 *
 *     0ms    低频脉冲起音（心跳律动，铺底）
 *     100ms  第一个「亮头 tick」（字母 E 淡入）
 *     172ms  第二个 tick（l）
 *     ...    每 72ms 一个，共 8 个，音高从低到高（440→1174Hz，越靠后越亮越响）
 *     740ms  收尾最高音（1568Hz）+ 低频「咚」（整组停稳的时刻 SLIDE_END）
 *     1000ms 自然收边（M.SPLASH_ANIM）
 *
 * 与旧版「8 个叮 + 咚」相比：每个字母位置叠加了中高音亮头 tick（电视音箱低音弱，
 * 纯低频脉冲听不清），整体响度翻倍，收尾更实。
 * 动画预算内音效全部结束，不会拖到首页挂载。
 *
 * ## raw 资源的 URI 必须由 Media3 生成
 *
 * Media3 1.11.1 的 `DefaultDataSource` 只认两种 raw 资源 scheme：`rawresource` 与
 * `android.resource`（两者都路由到 `RawResourceDataSource`，已由 aar 字节码核对），
 * 路径必须是**数字资源 ID**。
 *
 * 旧实现手写了 `android-resource://com.eleventv.tv/raw/splash_audio`：scheme 是连字符，
 * 没有任何 DataSource 认得，加载直接失败；又因为没挂错误监听，整个过程完全静默，
 * 表现就是「音效加上了却听不到」。
 *
 * 这里用平台标准的 `android.resource://<package>/<rawId>`：不硬编码包名、不拼资源名，
 * 也不用 Media3 1.11.1 里已废弃的 `buildRawResourceUri()`。
 */
class SplashSound(private val context: Context) {

    private var player: ExoPlayer? = null
    /** 防重复播放（onHoldEnd 可能被回调两次） */
    private var active = false

    /**
     * 启动并播放。幂等：已播放过或已释放则忽略。
     */
    fun play() {
        if (active || player != null) return
        val exo = try {
            ExoPlayer.Builder(context)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ false,
                )
                .build()
        } catch (e: Exception) {
            // 创建失败也要留痕，并且不能把 active 置成 true，否则连重试的机会都没有
            Log.w(TAG, "启动音效播放器创建失败", e)
            return
        }
        player = exo
        active = true

        // 加载失败必须可见：以前没有 listener，URI 写错时 logcat 里一个字都没有，
        // 只能靠「怎么没声音」猜。
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "启动音效播放失败：code=${error.errorCode} ${error.message}", error)
            }
        })
        val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.splash_audio}")
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.prepare()
        exo.playWhenReady = true
    }

    /** 停止并释放。Activity 销毁时调用。 */
    fun release() {
        active = false
        player?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {
            }
        }
        player = null
    }

    private companion object {
        const val TAG = "ElevenTV"
    }
}
