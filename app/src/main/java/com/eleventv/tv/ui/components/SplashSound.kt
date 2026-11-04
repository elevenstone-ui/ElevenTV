package com.eleventv.tv.ui.components

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.eleventv.tv.R

/**
 * 启动音效播放。
 *
 * 用 ExoPlayer（而非 SoundPool）：音效是 AAC（`.m4a`），SoundPool 对 AAC 在 Android 9 上支持不一致；
 * ExoPlayer 走 Media3 的解码器，项目已依赖 `media3-exoplayer`，不引入新组件。
 *
 * 声音设计与 [SplashMotion] 的动画时间表逐一对齐（见 `tools/make_splash_sound.py`）：
 *
 *     0ms    低频 pad 起音（柔和）
 *     100ms  第一个「叮」（字母 E 淡入）
 *     172ms  第二个「叮」（l）
 *     ...    每 72ms 一个，共 8 个，音高从低到高
 *     740ms  收尾最高音 + 低频「咚」（整组停稳的时刻 SLIDE_END）
 *     1000ms 自然收边（M.SPLASH_ANIM）
 *
 * 动画预算内音效全部结束，不会拖到首页挂载。
 *
 * `MediaItem.fromResource()` 需要传 `Uri`，把 `R.raw` 资源 ID 转成本机 URI：
 * 用 `Uri.fromParts` 构造 `"android-resource://" + 资源 ID`，
 * ExoPlayer 内部识别 `android-resource://` scheme 后走 `ResourceDataSource`。
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
        active = true
        val exo = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ false,
            )
            .build()
        player = exo
        // 把 raw 资源包进 android-resource:// URI，ExoPlayer 内置的 DefaultMediaSourceFactory
        // 识别该 scheme 后走 ResourceDataSource，无需任何额外 DataSource 配置
        val uri = "android-resource://com.eleventv.tv/raw/splash_audio"
        exo.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
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
}
