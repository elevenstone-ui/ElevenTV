package com.eleventv.tv.ui.player

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.eleventv.tv.data.player.SmbIoStats
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.Decoder
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.MediaFile
import com.eleventv.tv.data.model.StorageProtocol
import com.eleventv.tv.data.player.SmbDataSource
import com.eleventv.tv.data.repo.LocalRepository
import com.eleventv.tv.data.repo.episodeLabel
import com.eleventv.tv.data.webdav.davRequestHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** OSD 的四种形态。 */
enum class OsdMode { Hidden, Main, Seek, Tracks, Episodes }

/** 主 OSD 上的按钮。顺序即左右移动顺序。 */
enum class OsdAction(val label: String) {
    PlayPause("播放/暂停"),
    Back10("后退 10 秒"),
    Forward10("前进 10 秒"),
    Audio("音轨"),
    Subtitle("字幕"),
    Decoder("解码"),
    Episodes("选集"),
}

/** 面板里的一行。`apply` 由构建时捕获，选中即执行。 */
data class PanelOption(
    val label: String,
    val detail: String?,
    val selected: Boolean,
    val onPick: () -> Unit,
)

/**
 * 播放器状态机。
 *
 * ## 为什么不用 Compose 焦点做遥控器交互
 *
 * 全屏播放器没有滚动内容，按键语义是「句柄式」的（左右 seek、中间暂停），
 * 交给 Compose 的焦点搜索会出现「焦点跑进按钮导致左右变成移动焦点」的问题。
 * 所以这里自己维护一个 `selected` 下标，按键事件由 Activity 的 `dispatchKeyEvent`
 * 统一转进来，UI 只负责画。
 *
 * 所有对外可见字段都是 `mutableStateOf`，Compose 直接读即可重组。
 */
@UnstableApi
class PlayerState(
    private val context: Context,
    private val repo: LocalRepository,
    private val entry: LibraryEntry,
    initialFile: MediaFile,
    private val settings: AppSettings,
    private val onExit: () -> Unit,
    /**
     * 列出目标文件所在目录的文件名（suspend，内部切 IO 线程）。
     * PlayerState 在起播前调用，按目录缓存；列目录失败/超时时退回空列表，
     * 正片照常起播，只是没有外挂字幕。
     */
    private val subtitleSiblings: suspend (MediaFile) -> List<String> = { emptyList() },
    /** 在播放器里切了解码方式后回写设置：否则退出重进又回到旧值，用户以为没生效 */
    private val onDecoderChosen: (Decoder) -> Unit = {},
) : Player.Listener {

    // ---- 可观察状态 ----

    var file by mutableStateOf(initialFile)
        private set

    var osd by mutableStateOf(OsdMode.Hidden)
        private set

    var selected by mutableIntStateOf(0)
        private set

    var positionMs by mutableLongStateOf(0L)
        private set

    var durationMs by mutableLongStateOf(0L)
        private set

    var bufferedMs by mutableLongStateOf(0L)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isBuffering by mutableStateOf(true)
        private set

    /** 缓冲超过 6 秒时给用户看的读取统计（0 字节=取流问题，有数据不出画面=解码问题） */
    var bufferHint by mutableStateOf<String?>(null)
        private set

    var cues by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * 位图字幕（PGS / VOBSUB）。
     *
     * 蓝光 REMUX 里最常见的字幕形态是「只有一张图，没有文字」——`cue.text` 为 null、
     * `cue.bitmap` 有值。以前只收 text，于是菜单里能选中「PGS 中文字幕」，
     * 画面却一个字都不出（用户会以为播放器坏了）。
     */
    var bitmapCues by mutableStateOf<List<android.graphics.Bitmap>>(emptyList())
        private set

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * 错误页当前高亮的按钮：0=重试，1=退出。
     *
     * 播放页遥控器按键不走 Compose 焦点（见 [PlayerActivity.onKeyDown]），错误页的两个
     * 按钮若不靠它驱动高亮，左右键就切不动、OK 也按不到，用户被彻底卡在错误页。
     * 随 [error] 一起重置：start() 清 error 时也归 0。
     */
    var errorSelected by mutableIntStateOf(0)
        private set

    var toast by mutableStateOf<String?>(null)
        private set

    /** 打开面板时快照的选项；面板关闭即清空 */
    var options by mutableStateOf<List<PanelOption>>(emptyList())
        private set

    var decoder by mutableStateOf(settings.decoder)
        private set

    /** 每次重建播放器自增，用来触发 SurfaceView 重新绑定 */
    var playerToken by mutableIntStateOf(0)
        private set

    /**
     * 当前视频的显示宽高比（含像素宽高比校正）。
     *
     * null 表示还不知道尺寸——此时界面铺满全屏（老电视盒子经常拿不到
     * 尺寸就硬拉，宁可信一次）。拿到后界面用它做 letterbox，不再拉伸变形。
     */
    var videoAspect by mutableStateOf<Float?>(null)
        private set

    // ---- 内部 ----

    /**
     * 本次媒体是否已做过「自动挑简体中文字幕」。
     * onTracksChanged 在轨道就绪、以及我们设置 override 之后都会回调；
     * 不先置这个标志就应用 override，会陷入「选轨→回调→再选轨」的循环。
     * 每次 start()（换集 / 重试 / 切解码器）重置，让新文件重新自动选一次。
     */
    private var autoSubtitleApplied = false

    /** 用户在字幕面板手动选过（含「关闭字幕」）：本次媒体不再自动干预 */
    private var subtitleChosenByUser = false

    private val server = repo.serverOf(initialFile.serverId)

    /** 外挂字幕列目录专用：独立于播放器，release() 时取消，避免 Activity 销毁后回调 */
    private val subtitleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 按目录缓存文件名清单：同一季换集不再列目录；换到别的季目录会自动重新列。
     * 用 ConcurrentHashMap：快速换集时会有多个 IO 协程并发查/写缓存，
     * 普通 HashMap 并发写是未定义行为（ART 上可能损坏桶链）。
     */
    private val siblingCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /**
     * 必须在 [init] 之前声明：init 会立刻调用 start()，而 Kotlin 按声明顺序执行
     * 属性初始化器。放在文件末尾的话，start() 里的 playGeneration++ 会被之后的
     * `= 0` 初始化器清零，导致异步起播的「代际校验」永远失配、播放器不 prepare。
     */
    private var released = false

    /** 每次 start() 递增：作废「换集 / 切解码器 / 退出前就已排队」的异步任务 */
    private var playGeneration = 0

    private var player: ExoPlayer = buildPlayer()
    private val handler = Handler(Looper.getMainLooper())

    /** 只给 SurfaceView 绑定画面用；其余控制一律走本类方法，避免 UI 直接改播放器状态 */
    internal val surfacePlayer: ExoPlayer get() = player

    private val ticker = object : Runnable {
        override fun run() {
            syncProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** 所有「让进度同步循环活着」的路径统一走这里，避免重复或漏挂 */
    private fun ensureTicker() {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private val mainActions: List<OsdAction> = buildList {
        add(OsdAction.PlayPause)
        add(OsdAction.Back10)
        add(OsdAction.Forward10)
        add(OsdAction.Audio)
        if (entry.files.size > 1) add(OsdAction.Episodes)
        add(OsdAction.Subtitle)
        add(OsdAction.Decoder)
    }

    init {
        if (server == null) {
            error = "找不到这个文件所属的服务器配置"
        } else {
            start(file, resumePositionOf(file))
        }
    }

    fun release() {
        released = true
        // 列目录可能还在 IO 线程上跑：取消掉，别让它在 Activity 销毁后
        // 回来 post 一个 setMediaItem 到已释放的播放器上
        subtitleScope.cancel()
        handler.removeCallbacksAndMessages(null)
        saveProgress()
        runCatching { player.release() }
    }

    /**
     * Activity 进入后台。
     *
     * 必须在 onPause 就暂停并落库：TV 上按 Home 键后进程可能立刻被回收，
     * 等到 onDestroy 才保存会丢掉最后几分钟的进度。
     */
    fun onHostPause() {
        if (player.isPlaying) player.pause()
        saveProgress()
    }

    // ---------------- 播放控制 ----------------

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0)
            player.play()
            // ENDED 时 ticker 被摘掉，而重播不走 start()；不在这里挂回来，
            // 进度同步、5 秒落盘、12 秒取流超时提示会一直停摆到下次换集。
            ensureTicker()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekBy(deltaMs: Long) {
        val duration = if (durationMs > 0) {
            durationMs
        } else {
            player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        }
        // 时长未知（刚起播/直播源/容器没写 duration）时不能拿 0 当上界：
        // `coerceIn(0, 0)` 会把目标一律压成 0，按一下「前进 10 秒」反而跳回片头。
        val raw = player.currentPosition + deltaMs
        val target = if (duration > 0) raw.coerceIn(0L, duration) else raw.coerceAtLeast(0L)
        player.seekTo(target)
        positionMs = target
    }

    /** 换集（选集面板 / 自动连播）。 */
    fun playFile(target: MediaFile) {
        if (target.id == file.id) return
        saveProgress()
        file = target
        closePanel()
        selected = 0
        start(target, 0L)
    }

    private fun nextEpisode(): MediaFile? {
        val ordered = entry.files.sortedBy { it.seasonNumber * 1000 + it.episodeNumber }
        val index = ordered.indexOfFirst { it.id == file.id }
        return ordered.getOrNull(index + 1)
    }

    /** 切换解码方式：属性名 decoder 已经占用了 setDecoder 的 JVM 签名，这里换个名字 */
    fun switchDecoder(target: Decoder) {
        if (target == decoder) return
        val resumeAt = player.currentPosition
        decoder = target
        // 回写设置：不回写的话退出播放器再进来又回到旧值
        onDecoderChosen(target)
        val wasPlaying = player.isPlaying
        // 先摘 ticker 再释放旧 player，避免释放期间的同步回调把旧状态写入新播放器；
        // ticker 在 start() 里会重新 post
        handler.removeCallbacks(ticker)
        runCatching { player.release() }
        player = buildPlayer()
        playerToken++
        start(file, resumeAt, autoPlay = wasPlaying)
        showToast(target.label)
    }

    fun exit() {
        saveProgress()
        onExit()
    }

    /** 出错后原地重试：不换集、不丢位置 */
    fun retry() {
        errorSelected = 0
        start(file, positionMs)
    }

    /** 错误页：左右切换选中按钮，OK 激活。全消费，不给系统。 */
    private fun onHorizontalError(dir: Int): Boolean {
        errorSelected = (errorSelected + dir + 2) % 2
        return true
    }

    /** 错误页按钮横排，上下无目标；但仍要消费掉，避免漏给系统焦点搜索。 */
    private fun onVerticalError(dir: Int): Boolean = true

    private fun onCenterError(): Boolean {
        if (errorSelected == 0) retry() else exit()
        return true
    }

    /**
     * 设置里的字幕字号倍数，夹在可视范围内防止调出屏外。
     *
     * 上限跟着设置里的最大档走（2.6×），这里留到 3.0 而不是恰好等于它：
     * 以后再加一档时忘了改这里，表现是「选了没反应」，比直接夹死更难查。
     */
    val subtitleScale: Float get() = settings.subtitleSizeScale.coerceIn(0.6f, 3f)

    /** 字幕底衬不透明度 */
    val subtitleBgAlpha: Float get() = settings.subtitleBgAlpha.coerceIn(0f, 1f)

    /** OSD 顶部标题用的剧名 */
    val seriesTitle: String get() = entry.displayTitle

    /** 电影没有季/集：顶栏与选集面板都不该给电影打 E1 角标 */
    val isMovie: Boolean get() = entry.kind == com.eleventv.tv.data.model.MediaKind.MOVIE

    /** 主 OSD 的按钮顺序，界面必须与按键处理用同一份 */
    val osdActions: List<OsdAction> get() = mainActions

    // ---------------- 按键 ----------------

    /**
     * @return true 表示已消费。返回 false 时交给系统（例如音量键）。
     */
    fun handleKey(event: KeyEvent): Boolean {
        // 错误态：页面只剩「重试 / 退出」两个按钮，遥控器按键全在这里消化。
        // 必须放在抬起/OSD 逻辑之前——错误时 OSD 已隐藏，方向键若走系统焦点搜索，
        // 会去搜 Compose 焦点（本播放器按键不走焦点），按钮就永远选不动、OK 也按不到。
        if (error != null) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> onHorizontalError(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> onHorizontalError(1)
                KeyEvent.KEYCODE_DPAD_UP -> onVerticalError(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> onVerticalError(1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    onCenterError()
                else -> false
            }
        }

        // 只处理按下（含长按重复），抬起一律吞掉，避免和 DOWN 的语义重复
        if (event.action != KeyEvent.ACTION_DOWN) {
            return CONSUMED_KEYS.contains(event.keyCode)
        }

        // 任何操作都重新计时：OSD 隐藏靠空闲，不靠按键类型
        if (osd != OsdMode.Hidden) armAutoHide()

        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayPause()
                showOsd(OsdMode.Main)
                true
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(SEEK_STEP)
                showOsd(OsdMode.Seek)
                true
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-SEEK_STEP)
                showOsd(OsdMode.Seek)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                onCenter()

            KeyEvent.KEYCODE_DPAD_LEFT -> onHorizontal(-1)

            KeyEvent.KEYCODE_DPAD_RIGHT -> onHorizontal(1)

            KeyEvent.KEYCODE_DPAD_UP -> onVertical(-1)

            KeyEvent.KEYCODE_DPAD_DOWN -> onVertical(1)

            else -> false
        }
    }

    private fun onCenter(): Boolean = when (osd) {
        OsdMode.Hidden -> {
            togglePlayPause()
            showOsd(OsdMode.Main)
            true
        }

        OsdMode.Main -> {
            activate(mainActions.getOrNull(selected) ?: OsdAction.PlayPause)
            true
        }

        OsdMode.Seek -> {
            // 拖动过程中已经在 seek，OK 只是收起 OSD
            osd = OsdMode.Main
            armAutoHide()
            true
        }

        OsdMode.Tracks, OsdMode.Episodes -> {
            options.getOrNull(selected)?.onPick?.invoke()
            true
        }
    }

    private fun onHorizontal(dir: Int): Boolean = when (osd) {
        OsdMode.Hidden -> {
            seekBy(dir * SEEK_STEP)
            showOsd(OsdMode.Seek)
            true
        }

        OsdMode.Seek -> {
            seekBy(dir * SEEK_STEP)
            armAutoHide()
            true
        }

        OsdMode.Main -> {
            val size = mainActions.size
            selected = (selected + dir + size) % size
            armAutoHide()
            true
        }

        // 面板是纵向列表，左右不参与
        OsdMode.Tracks, OsdMode.Episodes -> true
    }

    private fun onVertical(dir: Int): Boolean {
        // 隐藏状态下上下都只是唤出 OSD：全屏播放器没有可滚动的列表
        if (osd == OsdMode.Hidden) {
            showOsd(OsdMode.Main)
            return true
        }

        when (osd) {
            OsdMode.Main -> {
                if (dir < 0) {
                    osd = OsdMode.Seek
                } else {
                    osd = OsdMode.Hidden
                    handler.removeCallbacks(autoHide)
                }
            }

            OsdMode.Seek -> osd = if (dir > 0) OsdMode.Main else OsdMode.Seek

            OsdMode.Tracks, OsdMode.Episodes -> {
                val size = options.size
                if (size > 0) selected = (selected + dir + size) % size
            }

            OsdMode.Hidden -> Unit
        }
        armAutoHide()
        return true
    }

    /** 返回键由 OnBackPressedDispatcher 转进来：Android 13+ 的返回手势走不到 onKeyDown。 */
    fun handleBack(): Boolean = onBack()

    private fun onBack(): Boolean = when (osd) {
        OsdMode.Hidden -> {
            exit()
            true
        }

        OsdMode.Main, OsdMode.Seek -> {
            osd = OsdMode.Hidden
            handler.removeCallbacks(autoHide)
            true
        }

        OsdMode.Tracks, OsdMode.Episodes -> {
            closePanel()
            selected = 0
            armAutoHide()
            true
        }
    }

    private fun activate(action: OsdAction) {
        when (action) {
            OsdAction.PlayPause -> togglePlayPause()
            OsdAction.Back10 -> seekBy(-SEEK_STEP)
            OsdAction.Forward10 -> seekBy(SEEK_STEP)
            OsdAction.Audio -> openPanel(OsdMode.Tracks, buildAudioOptions())
            OsdAction.Subtitle -> openPanel(OsdMode.Tracks, buildSubtitleOptions())
            OsdAction.Decoder -> {
                val next = when (decoder) {
                    Decoder.Auto -> Decoder.Hardware
                    Decoder.Hardware -> Decoder.Software
                    Decoder.Software -> Decoder.Auto
                }
                switchDecoder(next)
            }

            OsdAction.Episodes -> openPanel(OsdMode.Episodes, buildEpisodeOptions())
        }
        armAutoHide()
    }

    private fun openPanel(mode: OsdMode, items: List<PanelOption>) {
        options = items
        selected = items.indexOfFirst { it.selected }.coerceAtLeast(0)
        osd = mode
        armAutoHide()
    }

    // ---------------- OSD 生命周期 ----------------

    private val autoHide = Runnable {
        osd = OsdMode.Hidden
        options = emptyList()
    }

    private fun armAutoHide() {
        handler.removeCallbacks(autoHide)
        // 面板（音轨/字幕/选集）不收：用户正在做多步选择，被收掉要重来。
        // 主 OSD 与进度条会停留很久不动，必须收，否则静态高亮在 OLED 上会留残影。
        when (osd) {
            OsdMode.Main -> handler.postDelayed(autoHide, AUTO_HIDE_MS)
            OsdMode.Seek -> handler.postDelayed(autoHide, SEEK_HIDE_MS)
            else -> Unit
        }
    }

    /** 关掉面板回到主 OSD：必须同时重置选中项，否则高亮会停在一个不存在的下标上 */
    private fun closePanel() {
        options = emptyList()
        osd = OsdMode.Main
        selected = 0
        armAutoHide()
    }

    private fun showOsd(mode: OsdMode) {
        // 主 OSD 与进度条之间切换时保留选中项：用户把焦点挪到「字幕」，
        // OSD 自动隐藏后再按 OK，焦点不该被弹回「播放/暂停」。
        // 只有打开面板（轨道/选集）时才重置——那是一份全新的列表。
        val keepSelection = mode != OsdMode.Tracks && mode != OsdMode.Episodes &&
            osd != OsdMode.Tracks && osd != OsdMode.Episodes
        if (!keepSelection) selected = 0
        selected = selected.coerceIn(0, (mainActions.size - 1).coerceAtLeast(0))
        osd = mode
        armAutoHide()
    }

    private fun showToast(text: String) {
        toast = text
        handler.postDelayed({ toast = null }, 1600)
    }

    // ---------------- 轨道 ----------------

    private fun buildAudioOptions(): List<PanelOption> {
        val result = mutableListOf<PanelOption>()
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label
                    ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage }
                    ?: "音轨 ${result.size + 1}"
                result += PanelOption(
                    label = label,
                    detail = buildString {
                        format.codecs?.let { append(it) }
                        if (format.channelCount > 0) append(" · ${format.channelCount} 声道")
                        if (format.bitrate > 0) append(" · ${format.bitrate / 1000} kbps")
                    }.trim(' ', '·'),
                    selected = group.isTrackSelected(i),
                    onPick = {
                        applyOverride(group, i)
                        closePanel()
                        showToast("音轨：$label")
                    },
                )
            }
        }
        if (result.isEmpty()) {
            result += PanelOption("没有可选音轨", null, false) { }
        }
        return result
    }

    private fun buildSubtitleOptions(): List<PanelOption> {
        val result = mutableListOf<PanelOption>()

        val subtitleGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        result += PanelOption(
            label = "关闭字幕",
            detail = null,
            selected = disabled || subtitleGroups.none { g -> (0 until g.length).any { g.isTrackSelected(it) } },
            onPick = {
                // 用户主动关掉了字幕，别让自动选轨又把它打开
                subtitleChosenByUser = true
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                closePanel()
                showToast("字幕：关闭")
            },
        )

        subtitleGroups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val label = format.label
                    ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage }
                    ?: "字幕 ${result.size}"
                result += PanelOption(
                    label = label,
                    detail = format.codecs,
                    selected = !disabled && group.isTrackSelected(i),
                    onPick = {
                        // 用户手动选了轨（哪怕是繁体），本次媒体不再自动干预
                        subtitleChosenByUser = true
                        applyOverride(group, i)
                        closePanel()
                        showToast("字幕：$label")
                    },
                )
            }
        }

        if (subtitleGroups.isEmpty()) {
            result += PanelOption(
                label = "没有内嵌字幕",
                detail = "该文件不含字幕轨，暂不支持外挂字幕",
                selected = false,
            ) { }
        }
        return result
    }

    private fun buildEpisodeOptions(): List<PanelOption> =
        entry.files
            .sortedBy { it.seasonNumber * 1000 + it.episodeNumber }
            .map { f ->
                PanelOption(
                    // 电影不分集：不打 E1 角标，直接用片名（没有片名就留空，界面显示 detail）
                    label = f.episodeLabel(isMovie = isMovie).ifBlank { entry.displayTitle },
                    detail = f.quality,
                    selected = f.id == file.id,
                    onPick = { playFile(f) },
                )
            }

    private fun applyOverride(group: Tracks.Group, trackIndex: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
            .build()
    }

    // ---------------- 播放器构建 ----------------

    private fun buildPlayer(): ExoPlayer {
        val renderers = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            if (decoder != Decoder.Auto) {
                setMediaCodecSelector(codecSelector(decoder))
            }
        }

        val builder = ExoPlayer.Builder(context, renderers)
        // 没有服务器时也允许建播放器：构造函数里要先能拿到 player 才能报错，
        // 直接抛异常会让 Activity 起不来，用户连「为什么播不了」都看不到
        server?.let {
            val factory: DataSource.Factory = when (it.protocol) {
                StorageProtocol.SMB -> SmbDataSource.Factory(repo.smb, it)
                // WebDAV 直接用 Media3 的 HTTP 栈：重连、退避、Range 断点续传都由它负责，
                // 数据源这一层不再有任何自研代码
                StorageProtocol.WEBDAV -> DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(it.davRequestHeaders())
                    .setUserAgent(HTTP_USER_AGENT)
                    .setConnectTimeoutMs(HTTP_CONNECT_TIMEOUT_MS)
                    .setReadTimeoutMs(HTTP_READ_TIMEOUT_MS)
            }
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(factory))
        }

        return builder
            .setLoadControl(newLoadControl())
            .setSeekBackIncrementMs(SEEK_STEP)
            .setSeekForwardIncrementMs(SEEK_STEP)
            .build()
            .also { it.addListener(this) }
    }

    /**
     * 缓冲策略。默认值对局域网高码率视频太保守：
     * - 起播要攒 2.5s 媒体量（4K 60Mbps ≈ 16MB）、重缓冲要攒 5s——这是「加载时长过长」的主因；
     * - min/max 50s 让缓冲区在高码率下频繁顶满又清掉。
     * 这里把起播门槛压到 1.2s（点播放到出画面的主要延迟），重缓冲 2.5s，
     * 同时把 max 提到 120s、目标缓冲提到 48MB，后续播放更不容易再次卡住。
     *
     * ## 为什么是函数而不是字段
     *
     * 1. **初始化顺序**：`player` 字段在构造期就会调 [buildPlayer]，引用声明在它之后的字段
     *    拿到的是 null。之前把 LoadControl 写成字段（放在文件末尾）导致每次点播放
     *    构造 PlayerState 时 NPE 闪退——「所有视频一点就退」的根因。
     * 2. **状态隔离**：DefaultLoadControl 内部有分配状态，每个 ExoPlayer 实例应各持一份，
     *    切换解码方式重建播放器时不能复用同一个。
     */
    private fun newLoadControl(): androidx.media3.exoplayer.DefaultLoadControl =
        androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 1_200,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(60_000, true)
            .build()

    /**
     * 按设置挑解码器。
     *
     * `Hardware` 只留硬件解码器（`isHardwareAccelerated`），`Software` 只留系统自带的
     * `c2.android.*` 软解。两者都不涉及打包 FFmpeg 扩展——软解走的是系统实现，
     * 兼容性够用，且不需要额外几十 MB 的 .so。
     */
    private fun codecSelector(mode: Decoder): MediaCodecSelector {
        // API 29 之前拿不到 isHardwareAccelerated，硬/软无法区分，按自动处理
        if (Build.VERSION.SDK_INT < 29) return MediaCodecSelector.DEFAULT

        return MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            val filtered = all.filter { info: MediaCodecInfo ->
                val hardware = info.hardwareAccelerated
                if (mode == Decoder.Hardware) hardware else !hardware
            }
            // 过滤后为空会让播放直接失败，此时退回全部候选由 Media3 自己回退
            filtered.ifEmpty { all }
        }
    }

    private fun start(target: MediaFile, startMs: Long, autoPlay: Boolean = true) {
        val srv = server ?: return
        // 每次换片 / 重试 / 切解码器都作废旧代际，让排队中的自动重试自动失效
        playGeneration++
        error = null
        errorSelected = 0
        isBuffering = true
        durationMs = 0L
        positionMs = startMs
        // 换了媒体项 / 重试 / 重建播放器，轨道都会重新就绪：允许新一轮自动选简体中文。
        // 手动标志也必须重置：TrackSelectionOverride 是绑定具体 TrackGroup 的，
        // 换集后旧 override 本就失效；若不重置，用户在第 1 集手动选过一次，
        // 后面每一集都不再自动选，字幕反而整段消失。
        // 「关闭字幕」不靠这个标志保持——它落在 trackSelectionParameters 的
        // disabledTrackTypes 里，会跨集保留，由 onTracksChanged 再检查一次。
        autoSubtitleApplied = false
        subtitleChosenByUser = false
        cues = emptyList()
        bitmapCues = emptyList()
        // 旧片的宽高比不能留给新片用，否则缓冲期间会按上一部画错
        videoAspect = null
        SmbIoStats.reset(active = srv.protocol == StorageProtocol.SMB)
        stallSince = 0L
        stallReported = false
        errorFromStall = false

        val uri = when (srv.protocol) {
            StorageProtocol.SMB -> android.net.Uri.Builder()
                .scheme("smb")
                .authority(srv.id)
                .path(target.path)
                .build()
            // WebDAV：真实 URL 直接交给 HttpDataSource，路径里的中文/空格已在 davUrl 里编码
            StorageProtocol.WEBDAV -> srv.davUrl(target.path).toUri()
        }

        // 外挂字幕必须在 setMediaItem 之前查到（Media3 事后不能追加字幕轨），
        // 但列目录是网络 IO，不能放主线程等。这里先同步重置完状态（UI 立刻显示
        // buffering，不是黑屏），再在 IO 线程查一次目录，拿到结果回主线程一次起播。
        // 换集到别的季目录会自动重新列；同目录走缓存，几乎零延迟。
        val generation = playGeneration
        subtitleScope.launch {
            val siblings = siblingNamesOf(target)
            handler.post {
                // 用户快速换集 / 重试 / 退出时，旧一代的回调必须丢弃，
                // 否则会把上一集的 MediaItem 设置到当前播放器上
                if (released || generation != playGeneration) return@post
                player.setMediaItem(buildMediaItem(srv, target, uri, siblings))
                player.prepare()
                if (startMs > 0L) player.seekTo(startMs)
                player.playWhenReady = autoPlay
            }
        }
        // 起播/换集/重试都必须让进度同步循环活着：ticker 在「停止自动连播的 ENDED」
        // 与 switchDecoder 里被摘除过，这里重新挂上，保证 5s 落盘 + 缓冲超时提示始终可用
        ensureTicker()
    }

    /**
     * 按目录取文件名清单，带缓存与硬超时。
     * NAS 慢或列目录失败时返回空列表：正片照常起播，只是没有外挂字幕，
     * 绝不让"找字幕"拖住播放。
     */
    private suspend fun siblingNamesOf(target: MediaFile): List<String> {
        val dir = target.path.substringBeforeLast('/', "")
        siblingCache[dir]?.let { return it }
        val names = withTimeoutOrNull(SUBTITLE_SCAN_TIMEOUT_MS) {
            runCatching { subtitleSiblings(target) }
                .onFailure { Log.w(TAG, "外挂字幕列目录失败：${it.message}") }
                .getOrDefault(emptyList())
        } ?: emptyList<String>().also {
            Log.w(TAG, "外挂字幕列目录超时（${SUBTITLE_SCAN_TIMEOUT_MS}ms），跳过")
        }
        siblingCache[dir] = names
        return names
    }

    /**
     * 构造 MediaItem，并挂上同目录的外挂字幕。
     *
     * 字幕必须在 setMediaItem 时一次带上：Media3 事后没有「追加字幕轨」的公开 API，
     * 重新 setMediaItem 会重新 prepare、画面黑一下、播放位置也可能丢。
     *
     * 不设 SELECTION_FLAG_DEFAULT：挂上去的轨交给 onTracksChanged 的
     * 自动选简中逻辑统一挑，避免「默认选中第一条」把繁体轨顶到前面。
     */
    private fun buildMediaItem(
        srv: com.eleventv.tv.data.model.SmbServer,
        target: MediaFile,
        videoUri: android.net.Uri,
        siblings: List<String>,
    ): MediaItem {
        val matched = matchExternalSubtitles(target.fileName, siblings)
        if (matched.isEmpty()) return MediaItem.fromUri(videoUri)

        val dir = target.path.substringBeforeLast('/', "")
        val configs = matched.map { sub ->
            val subPath = if (dir.isEmpty()) sub.fileName else "$dir/${sub.fileName}"
            val subUri = when (srv.protocol) {
                StorageProtocol.SMB -> android.net.Uri.Builder()
                    .scheme("smb")
                    .authority(srv.id)
                    .path(subPath)
                    .build()
                StorageProtocol.WEBDAV -> srv.davUrl(subPath).toUri()
            }
            MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(sub.mimeType)
                .setLanguage(sub.language)
                .setLabel(sub.label)
                .build()
        }
        Log.i(TAG, "挂载 ${configs.size} 条外挂字幕：${matched.map { it.fileName }}")
        return MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(configs)
            .build()
    }

    private fun resumePositionOf(target: MediaFile): Long {
        if (!settings.resumePlayback) return 0L
        val record = repo.recordOf(entry.id) ?: return 0L
        if (record.fileId != target.id) return 0L
        // 快看完了就不要从 99% 接着放，从头开始更符合直觉
        if (record.progress >= 0.95f) return 0L
        return record.positionMs
    }

    private fun saveProgress() {
        // 容器没有时长（TS / m2ts / 部分 ISO）时 duration 记 0 也要把位置落盘，
        // 否则这类片源的续播完全失效；repo 与 PlaybackRecord 已按 0 时长兜底。
        val duration = durationMs.takeIf { it > 0 }
            ?: player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: 0L
        val position = player.currentPosition.takeIf { it > 0 } ?: positionMs
        if (position <= 0) return
        repo.saveProgress(entry.id, file, position, duration)
    }

    private fun syncProgress() {
        durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        positionMs = player.currentPosition.coerceAtLeast(0L)
        bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING

        // 卡在取流阶段时给出可判断的信息，而不是无限转圈：
        // 一个字节都没读到 = 取流/网络问题，12 秒足够判定；读到过数据只是慢，就继续等。
        when {
            player.playbackState != Player.STATE_BUFFERING -> {
                if (stallReported) Log.i(TAG, "恢复播放：${SmbIoStats.summary()}")
                stallSince = 0L
                stallReported = false
                hintAt = 0L
                bufferHint = null
            }

            stallSince == 0L -> stallSince = System.currentTimeMillis()

            else -> {
                val waited = System.currentTimeMillis() - stallSince
                // 每 2 秒刷新一次统计：只在 6 秒那一刻取一次快照的话，屏幕上会一直挂着
                // 「已读 1.2 MB」这种早已过时的数字，用户没法判断到底还在不在读
                val now = System.currentTimeMillis()
                if (waited > 6_000 && now - hintAt > 2_000) {
                    // 提示里必须带协议名：SMB 有字节统计，WebDAV 走 Media3 的 HTTP 栈没有统计。
                    // 不带协议名时，「0.5 MB / 1 次」这种字样看不出走的是哪条链路，
                    // 排查时只能靠猜（曾经把 SMB 旧包误当成 WebDAV 版）。
                    bufferHint = if (SmbIoStats.active) {
                        SmbIoStats.summary().ifBlank { null }?.let { "SMB · $it" }
                    } else {
                        "WebDAV 直连 · 已等待 ${waited / 1000}s"
                    }
                    hintAt = now
                }
                if (!stallReported && waited > STALL_TIMEOUT_MS &&
                    (!SmbIoStats.active || SmbIoStats.bytesRead <= 0)
                ) {
                    stallReported = true
                    // 标记来源：这类错误是「等太久」推断出来的，连接一旦自己恢复就该撤掉。
                    // 播放器自己报的错（onPlayerError）是终局，不能这么撤。
                    errorFromStall = true
                    val tail = if (SmbIoStats.active) {
                        "${SmbIoStats.summary()}。可重试；若反复如此，检查 NAS 是否休眠或网络是否稳定。"
                    } else {
                        "可重试；若反复如此，检查 NAS 是否休眠、WebDAV 服务是否正常。"
                    }
                    error = "读取超时：12 秒没有收到任何数据。$tail"
                    Log.w(TAG, "读取超时：${SmbIoStats.summary()}")
                } else if (waited / 5_000 != (waited - TICK_MS) / 5_000) {
                    Log.i(TAG, "仍在缓冲 ${waited / 1000}s：${SmbIoStats.summary()}")
                }
            }
        }

        // 每 5 秒落一次进度：断电解码、拔电源时最多丢 5 秒
        if (player.playbackState == Player.STATE_READY) {
            ticks += 1
            if (ticks % (SAVE_EVERY_MS / TICK_MS).toInt() == 0) saveProgress()
        }
    }

    private var ticks = 0

    /** 进入缓冲状态的时间点，0 表示当前不在缓冲 */
    private var stallSince = 0L

    /** 上次刷新缓冲统计的时间，用于限制刷新频率（每 2 秒一次） */
    private var hintAt = 0L

    /** 本次缓冲是否已经报过超时，避免每 500ms 覆盖一次用户正在看的错误 */
    private var stallReported = false

    /** 本轮播放是否已经用过自动重试；播到 READY 后复位 */
    private var autoRetried = false

    /** 当前这条错误是「缓冲太久」推断出来的（不是播放器报的），画面恢复后可以撤掉 */
    private var errorFromStall = false

    // 注：released / playGeneration 声明在上方「内部字段区」（init 之前）。
    // Kotlin 按声明顺序执行初始化器：若放在 init 之后，init 里 start() 的自增
    // 会被后面的 `= 0` 初始化器清零，异步起播的代际校验就会永久失配。

    // ---------------- Player.Listener ----------------

    /**
     * 轨道就绪时自动选中简体中文字幕。
     *
     * ## 为什么不用 setPreferredTextLanguage("zh")
     * MKV 里简体/繁体字幕的 language 常常都写成 `chi`/`zho`/`zh`，语言字段
     * 区分不了简繁，Media3 只会按顺序取第一条中文轨——结果经常默认落到繁体。
     * 所以按 label + language 给每条文本轨打分，用 override 精确锁定最优简体轨。
     *
     * ## 为什么在这里而不是 start()
     * setMediaItem/prepare 时轨道还没解析出来，start() 里读 currentTracks 只会拿到
     * 空集合；onTracksChanged 是轨道真正就绪的标准时机。
     *
     * ## 回调循环
     * 下面写 trackSelectionParameters 会再次触发 onTracksChanged，所以必须
     * **先**把 autoSubtitleApplied 置 true 再写，否则会无限「选轨→回调→再选轨」。
     */
    override fun onTracksChanged(tracks: Tracks) {
        if (autoSubtitleApplied || subtitleChosenByUser) return
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textGroups.isEmpty()) return
        // 用户主动关过字幕：disabledTrackTypes 会跨集保留，尊重这个意图，
        // 别让自动选轨通过 applyOverride 的 setTrackTypeDisabled(false) 又把它打开。
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return
        autoSubtitleApplied = true

        var bestGroup: Tracks.Group? = null
        var bestIndex = -1
        var bestScore = 0
        for (group in textGroups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val score = simplifiedChineseScore(format.label, format.language)
                if (score > bestScore) {
                    bestScore = score
                    bestGroup = group
                    bestIndex = i
                }
            }
        }
        // 没有简体中文字幕：保持 Media3 默认，不强行开英文字幕
        val target = bestGroup ?: return
        applyOverride(target, bestIndex)
        Log.i(TAG, "自动选中简体中文字幕轨（score=$bestScore, track=$bestIndex）")
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            // 真正播起来了：把「自动重试」的机会还回去，下次断流还能再救一次
            autoRetried = false
            // 数据源自己重连成功、画面回来了：把「读取超时」那张错误页撤掉，
            // 否则用户会盯着一块写着「播放失败」的板子看一整部片
            if (errorFromStall) {
                errorFromStall = false
                error = null
            }
        }
        if (playbackState == Player.STATE_ENDED) {
            saveProgress()
            val next = nextEpisode()
            if (settings.autoNextEpisode && next != null) {
                playFile(next)
            } else {
                osd = OsdMode.Main
                selected = 0
                armAutoHide()
                // 不再自动连播：ticker 停止空转，避免 500ms 一次的无效 postDelayed 循环
                handler.removeCallbacks(ticker)
            }
        }
    }

    override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
    }

    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
        // 只挡 width/height 不够：部分老平台的 MediaCodec 会报 pixelWidthHeightRatio = 0
        // 或 NaN。算出来的 aspect 就是 0 / NaN，界面 `Modifier.aspectRatio(0f)` 会抛
        // IllegalArgumentException（aspectRatio must be positive），整页直接崩。
        // 异常值一律当作「未知」，界面按铺满处理——宁可拉伸一次，也不要闪退。
        val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        videoAspect = ratio.takeIf { it.isFinite() && it in ASPECT_SANE_RANGE } ?: run {
            if (videoSize.width > 0 || videoSize.height > 0) {
                Log.w(TAG, "异常的宽高比 $ratio（${videoSize.width}x${videoSize.height}），按铺满处理")
            }
            null
        }
    }

    override fun onCues(cueGroup: CueGroup) {
        val texts = ArrayList<String>(cueGroup.cues.size)
        val bitmaps = ArrayList<android.graphics.Bitmap>(0)
        for (cue in cueGroup.cues) {
            cue.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
            cue.bitmap?.let { bitmaps += it }
        }
        // 内容不变时不赋新引用，避免字幕层无谓重组
        if (texts != cues) cues = texts
        if (bitmaps != bitmapCues) bitmapCues = bitmaps
    }

    override fun onPlayerError(e: PlaybackException) {
        // 取流类失败先自己救一次再说话。
        //
        // 这类错误几乎全是「连接没了」：SMBJ 的 socket 超时会关掉整条连接，NAS 也会
        // 主动回收空闲会话。重连之后从原位置接着放就行，没必要让用户对着
        // 「播放失败」去按重试——他还得先判断是网络问题还是文件问题。
        // 只救一次：真连不上（NAS 关机、网线掉了）时，第二次仍会把错误摆到台面上。
        val recoverable = e.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (recoverable && !autoRetried && !released) {
            autoRetried = true
            val generation = playGeneration
            Log.w(TAG, "取流失败，1 秒后自动重试一次：${e.message}")
            handler.postDelayed({
                // 静置期内用户可能已换集或切了解码器（start 会让 generation 递增），
                // 此时不能再拿新的 file / position 去 retry，否则打断用户刚发起的操作。
                if (!released && error == null && generation == playGeneration) retry()
            }, AUTO_RETRY_DELAY_MS)
            return
        }

        // 播放器自己报的错是终局，不跟着「恢复播放」自动消失
        errorFromStall = false
        error = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "找不到文件，可能已被移动或改名"

            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            -> "解码失败，试试在设置里切换解码方式"

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> "连不上 NAS，检查网络与服务器状态"

            // Media3 的 PlaybackException.message 只有一句「Source error」，
            // 真正的原因在 cause 链上（HttpDataSource 的 401/404、SMB 的传输异常）。
            // 直接把最深一层的 message 显示出来，否则用户只能看到一个等于没说的词。
            else -> rootMessage(e) ?: "播放失败"
        }
    }

    /**
     * 取异常链上最有信息量的那条 message。
     *
     * 从外往里走，跳过 Media3 自己的占位文案（`Source error`、`Unexpected error`），
     * 保留最内层的真实原因，例如 `HttpDataSourceException: 401` 或
     * `SmbException: STATUS_NETWORK_NAME_DELETED`。
     */
    private fun rootMessage(e: Throwable): String? {
        var t: Throwable? = e
        var best: String? = null
        var depth = 0
        while (t != null && depth < 8) {
            val m = t.message?.trim()
            if (!m.isNullOrEmpty() && m != "Source error" && m != "Unexpected error") best = m
            t = t.cause
            depth++
        }
        return best
    }

    private companion object {
        const val TICK_MS = 500L
        const val SAVE_EVERY_MS = 5_000L
        const val AUTO_HIDE_MS = 4_000L
        const val SEEK_HIDE_MS = 6_000L
        const val SEEK_STEP = 10_000L
        const val TAG = "ElevenTV"

        /**
         * 外挂字幕列目录的硬超时。局域网 SMB/WebDAV 一次列目录通常几十毫秒；
         * 超时就按「没有外挂字幕」起播，绝不让找字幕拖住正片。
         * 期间 UI 是 buffering 态而不是黑屏，用户仍能看到加载反馈。
         */
        const val SUBTITLE_SCAN_TIMEOUT_MS = 1_200L

        /** 可信的显示宽高比区间：超出这个范围一定是解码器给的脏数据 */
        val ASPECT_SANE_RANGE = 0.2f..6f

        /** 缓冲多久仍一个字节都没读到就判定取流失败 */
        const val STALL_TIMEOUT_MS = 12_000L

        /** 取流类错误自动重试前的静置时间：留一点时间让连接池把死连接收干净 */
        const val AUTO_RETRY_DELAY_MS = 1_000L

        /**
         * WebDAV 的 HTTP 超时。
         *
         * 连接 15 秒：局域网握手正常在 50ms 内，15 秒已经等于「主机不可达」；
         * 读 30 秒：NAS 机械盘唤醒要 5~8 秒，留足余量，又不至于让一次哑读卡死整个播放。
         */
        const val HTTP_CONNECT_TIMEOUT_MS = 15_000
        const val HTTP_READ_TIMEOUT_MS = 30_000
        const val HTTP_USER_AGENT = "ElevenTV/2.0"

        val CONSUMED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND,
        )
    }
}
