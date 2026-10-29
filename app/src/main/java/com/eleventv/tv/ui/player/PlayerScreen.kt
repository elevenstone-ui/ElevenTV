package com.eleventv.tv.ui.player

import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import com.eleventv.tv.R
import com.eleventv.tv.data.repo.episodeLabel
import com.eleventv.tv.ui.components.StateView
import com.eleventv.tv.ui.components.TvButton
import com.eleventv.tv.ui.components.TvButtonVariant
import com.eleventv.tv.ui.components.TvText
import com.eleventv.tv.ui.components.VSpace
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.G
import com.eleventv.tv.ui.design.M
import com.eleventv.tv.ui.design.T

/**
 * 全屏播放界面。
 *
 * 纯展示：所有状态都在 [PlayerState] 里，按键由 Activity 分发进去。
 * 这样 OSD 的选中项不会被 Compose 焦点抢走——遥控器的左右键要用来 seek，
 * 而不是用来在按钮之间跳。
 */
@UnstableApi
@Composable
fun PlayerScreen(state: PlayerState, modifier: Modifier = Modifier) {
    val osdAlpha by animateFloatAsState(
        targetValue = if (state.osd == OsdMode.Hidden) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (state.osd == OsdMode.Hidden) M.OSD_OUT else M.OSD_IN,
            easing = M.Standard,
        ),
        label = "osdAlpha",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        VideoSurface(state)

        // 字幕压在画面底部，OSD 出现时上移，避免被进度条盖住
        SubtitleLayer(
            state = state,
            bottomPadding = if (osdAlpha > 0.1f) 150.dp else 48.dp,
        )

        if (state.isBuffering && state.error == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = C.Text1.copy(alpha = 0.8f),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(30.dp),
                    )
                    // 缓冲超过 6 秒才出现：把「读到多少字节」亮出来，
                    // 0 字节＝取流/网络问题，数据很多却不出画面＝解码问题
                    state.bufferHint?.let { hint ->
                        VSpace(D.Md)
                        TvText(hint, style = T.Caption, color = C.Text3, maxLines = 2)
                    }
                }
            }
        }

        // OSD 整体淡入淡出，用 graphicsLayer 而不是条件组合，避免隐藏瞬间硬切
        if (osdAlpha > 0.001f) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = osdAlpha },
            ) {
                TopBar(state)
                Box(Modifier.weight(1f))
                if (state.osd == OsdMode.Main || state.osd == OsdMode.Seek) {
                    BottomBar(state)
                }
            }
        }

        if (state.osd == OsdMode.Tracks || state.osd == OsdMode.Episodes) {
            OptionPanel(
                title = if (state.osd == OsdMode.Episodes) "选集" else "轨道",
                options = state.options,
                selected = state.selected,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 64.dp),
            )
        }

        state.toast?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp)
                    .background(C.Scrim, CircleShape)
                    .padding(horizontal = D.X2l, vertical = D.Md),
            ) {
                TvText(text, style = T.Body2, color = C.Text1, maxLines = 1)
            }
        }

        state.error?.let { message ->
            StateView(
                title = "播放失败",
                description = message,
                iconRes = R.drawable.ic_alert,
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
                        TvButton(
                            onClick = { state.retry() },
                            label = "重试",
                            icon = R.drawable.ic_refresh,
                            variant = TvButtonVariant.Primary,
                        )
                        TvButton(
                            onClick = { state.exit() },
                            label = "退出",
                            icon = R.drawable.ic_close,
                            variant = TvButtonVariant.Ghost,
                        )
                    }
                },
            )
        }
    }
}

/**
 * SurfaceView 而不是 Compose 的 Surface：4K 硬解直通需要稳定的原生 Surface。
 *
 * 宽高比已知时按比例居中显示（letterbox），未知时才铺满——
 * SurfaceView 不会自己保持比例，直接 MATCH_PARENT 会把 4:3 老片拉成全屏变形。
 */
@UnstableApi
@Composable
private fun VideoSurface(state: PlayerState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val aspect = state.videoAspect
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    keepScreenOn = true
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                // 读一次 playerToken：切换解码方式会重建播放器实例，靠它触发重新绑定
                view.tag = state.playerToken
                state.surfacePlayer.setVideoSurfaceView(view)
            },
            onRelease = { view ->
                runCatching { state.surfacePlayer.clearVideoSurface() }
                view.tag = null
            },
            // 状态机已经把脏比例过滤成 null；这里再挡一次，因为 aspectRatio(0f) 是
            // 直接抛异常的写法，崩一次就是整页闪退，不值得赌上游
            modifier = aspect?.takeIf { it.isFinite() && it > 0.01f }?.let { Modifier.aspectRatio(it) }
                ?: Modifier.fillMaxSize(),
        )
    }
}

@UnstableApi
@Composable
private fun SubtitleLayer(state: PlayerState, bottomPadding: Dp) {
    val lines = state.cues
    val bitmaps = state.bitmapCues
    if (lines.isEmpty() && bitmaps.isEmpty()) return

    // 字号跟随设置（tokens 的 Title1 是标准档，其他档按倍数缩放）
    //
    // 行高必须跟着一起放大：Title1 的 lineHeight 只有 20sp，而字号放到 1.5× 就已 25.5sp，
    // 两行字幕的行盒比字还矮，会上下叠字——倍数越大叠得越厉害。以前「特大」只放大
    // fontSize，就是这个毛病；再往 2.6× 走会糊成一团，所以这里两个值必须同比例。
    val scale = state.subtitleScale
    val style = T.Title1.copy(
        fontSize = T.Title1.fontSize * scale,
        lineHeight = T.Title1.lineHeight * scale,
    )
    // 两侧留白按倍数收窄：固定 96dp 在巨大档下会把长句挤成四五行，贴上画面上下沿
    val sideInset = (96.dp / scale).coerceIn(32.dp, 96.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = bottomPadding, start = sideInset, end = sideInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 位图字幕（PGS / VOBSUB）：本身就是「已经排版好的画面」，直接当图贴，
            // 不画底衬、不加字距——原图带着描边与阴影，再压一层黑底反而脏。
            // 但它也得跟着档位放大，否则「调大字幕」对蓝光 REMUX 完全无效；
            // 上限 100% 即还原原始尺寸（原图本就按画面宽度排的）。
            if (bitmaps.isNotEmpty()) {
                bitmaps.forEach { bitmap ->
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth((0.72f * scale).coerceIn(0.72f, 1f)),
                    )
                }
            }
            lines.forEach { line ->
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = state.subtitleBgAlpha), RoundedCornerShape(4.dp))
                        .padding(horizontal = D.Md, vertical = 3.dp),
                ) {
                    TvText(
                        text = line,
                        style = style,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun TopBar(state: PlayerState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.Lg),
        modifier = Modifier
            .fillMaxWidth()
            .background(G.OsdTop)
            .padding(horizontal = D.ScreenPaddingX, vertical = D.Xl),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_chevron_left),
            contentDescription = null,
            tint = C.Text1,
            modifier = Modifier.size(16.dp),
        )
        Column(Modifier.weight(1f)) {
            TvText(state.seriesTitle, style = T.Title1, color = C.Text1, maxLines = 1)
            TvText(
                text = buildString {
                    // 电影没有集号：以前只看 seasonNumber > 0（默认值就是 1，恒真），
                    // 于是电影顶栏永远挂着「E1 · 4K · 解码：自动」
                    if (!state.isMovie) append(state.file.episodeLabel())
                    state.file.quality?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                    if (isNotEmpty()) append("　")
                    append("解码：${state.decoder.label}")
                },
                style = T.Caption,
                color = C.Text2,
                maxLines = 1,
            )
        }
    }
}

@UnstableApi
@Composable
private fun BottomBar(state: PlayerState) {
    val seekMode = state.osd == OsdMode.Seek

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(G.OsdBottom)
            .padding(horizontal = D.ScreenPaddingX, vertical = D.X2l),
    ) {
        // 时间 + 进度条
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Xl),
        ) {
            TvText(
                text = fmtTime(state.positionMs),
                style = T.Label,
                color = C.Text1,
                maxLines = 1,
            )
            Box(Modifier.weight(1f)) {
                ProgressTrack(state, highlighted = seekMode)
            }
            TvText(
                text = fmtTime(state.durationMs),
                style = T.Label,
                color = C.Text2,
                maxLines = 1,
            )
        }

        Box(Modifier.height(D.Xl))

        // 操作条
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Md),
        ) {
            state.selected.let { sel ->
                mainActionsOf(state).forEachIndexed { index, action ->
                    ActionPill(
                        action = action,
                        playing = state.isPlaying,
                        selected = !seekMode && index == sel,
                    )
                }
            }
        }
    }
}

/**
 * 主 OSD 的按钮序列。
 *
 * 必须与 [PlayerState.mainActions] 完全一致——两边不一致时，高亮会错位一格。
 * 所以这里不另写一份，而是从状态机取。
 */
@UnstableApi
private fun mainActionsOf(state: PlayerState): List<OsdAction> = state.osdActions

@Composable
private fun ActionPill(action: OsdAction, playing: Boolean, selected: Boolean) {
    val bg = if (selected) C.Text1 else C.GlassStrong
    val fg = if (selected) C.Ink0 else C.Text1

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.ButtonGap),
        modifier = Modifier
            .height(D.ButtonH + 4.dp)
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = D.ButtonPadH),
    ) {
        Icon(
            painter = painterResource(iconOf(action, playing)),
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(D.ButtonIcon + 1.dp),
        )
        TvText(
            text = labelOf(action, playing),
            style = T.Caption.copy(fontWeight = FontWeight(600)),
            color = fg,
            maxLines = 1,
        )
    }
}

private fun iconOf(action: OsdAction, playing: Boolean): Int = when (action) {
    OsdAction.PlayPause -> if (playing) R.drawable.ic_pause else R.drawable.ic_play
    OsdAction.Back10 -> R.drawable.ic_rewind
    OsdAction.Forward10 -> R.drawable.ic_forward
    OsdAction.Audio -> R.drawable.ic_audio
    OsdAction.Subtitle -> R.drawable.ic_subtitle
    OsdAction.Decoder -> R.drawable.ic_speed
    OsdAction.Episodes -> R.drawable.ic_episodes
}

private fun labelOf(action: OsdAction, playing: Boolean): String = when (action) {
    OsdAction.PlayPause -> if (playing) "暂停" else "播放"
    else -> action.label
}

@UnstableApi
@Composable
private fun ProgressTrack(state: PlayerState, highlighted: Boolean) {
    // 时长未知（直播流、还没解析出时长）时不能拿 1L 当分母：position/1 会算出巨大值，
    // 夹到 1f 后进度条直接满格，看着像「已经播完」
    val duration = state.durationMs.takeIf { it > 0 }
    val played = duration?.let { (state.positionMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val buffered = duration?.let { (state.bufferedMs.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val trackHeight = if (highlighted) 6.dp else D.ProgressTrack

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.22f), CircleShape),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(buffered)
                .background(Color.White.copy(alpha = 0.34f), CircleShape),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(played)
                .background(G.Progress, CircleShape),
        )
        if (highlighted) {
            // 拖动时给出明确的落点，否则用户只能靠数字判断
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(played)
                    .height(trackHeight),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(D.ProgressKnob)
                        .background(Color.White, CircleShape),
                )
            }
        }
    }
}

/** 面板单行高度与行间距。滚动定位按「行高 + 间距」的步长算，改这里就够了 */
private val PanelRowH = 46.dp
private val PanelRowGap = 2.dp

/**
 * 轨道 / 选集面板。
 *
 * ## 为什么要自己滚动
 *
 * 多语种字幕的视频常有十几到三十几条文本轨，选集面板在长篇剧里也有几十集。
 * 原来用普通 Column 全部铺开，面板高度随条目数线性增长——超过屏幕的部分
 * 被直接画到屏幕外，**看得见的上限就是屏幕高度**，而且方向键仍能移到不可见的条目上
 * （按 OK 会切到一条看不见的字幕），用户只感觉「面板卡住 / 选中项消失」。
 *
 * 所以：限高到屏高 72%、内部竖直滚动、选中项始终滚进可视区，
 * 标题右侧给出「第 n / 共 m」让位置关系可见。
 */
@Composable
private fun OptionPanel(
    title: String,
    options: List<PanelOption>,
    selected: Int,
    modifier: Modifier = Modifier,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
    val scroll = rememberScrollState()
    var viewportHeight by remember { mutableIntStateOf(0) }

    val density = LocalDensity.current
    val rowPx = with(density) { PanelRowH.toPx() }
    val stepPx = with(density) { (PanelRowH + PanelRowGap).toPx() }

    // 只滚「让选中项刚好看得见」的最小距离，并且不用动画：
    // 遥控器长按会连续触发选中变化，动画会被反复打断，反而不如直接落位利落
    LaunchedEffect(selected, options.size, viewportHeight) {
        if (viewportHeight <= 0 || selected !in options.indices) return@LaunchedEffect
        val top = selected * stepPx
        val bottom = top + rowPx
        val target = when {
            top < scroll.value -> top
            bottom > scroll.value + viewportHeight -> bottom - viewportHeight
            else -> null
        }
        // 不夹到 scroll.maxValue：首次布局时它可能还是 0，scrollTo 内部自己会夹
        target?.let { scroll.scrollTo(it.toInt().coerceAtLeast(0)) }
    }

    Column(
        modifier = modifier
            .width(400.dp)
            .heightIn(max = maxHeight)
            .background(C.Scrim, RoundedCornerShape(12.dp))
            .padding(D.Xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvText(
                text = title,
                style = T.Title2,
                color = C.Text2,
                modifier = Modifier.weight(1f),
            )
            if (options.size > 1) {
                TvText(
                    text = "${(selected + 1).coerceIn(1, options.size)}/${options.size}",
                    style = T.Caption,
                    color = C.Text3,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.height(D.Lg))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // fill = false：条目少时面板收成内容高度，不撑成一大块空白玻璃
                .weight(1f, fill = false)
                .onSizeChanged { viewportHeight = it.height }
                .verticalScroll(scroll),
        ) {
            options.forEachIndexed { index, option ->
                PanelRow(option = option, isSelected = index == selected)
                if (index != options.lastIndex) Box(Modifier.height(PanelRowGap))
            }
        }

        Box(Modifier.height(D.Md))
        TvText(
            text = "上下选择 · OK 确认 · 返回上一级",
            style = T.Micro,
            color = C.Text3,
        )
    }
}

@Composable
private fun PanelRow(option: PanelOption, isSelected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.Md),
        modifier = Modifier
            .fillMaxWidth()
            .height(PanelRowH)
            .clip(RoundedCornerShape(D.RadiusField))
            .background(if (isSelected) C.Text1 else Color.Transparent)
            .padding(horizontal = D.Lg),
    ) {
        Column(Modifier.weight(1f)) {
            TvText(
                text = option.label,
                style = T.Body2,
                color = if (isSelected) C.Ink0 else C.Text1,
                maxLines = 1,
            )
            option.detail?.takeIf { it.isNotBlank() }?.let {
                TvText(
                    text = it,
                    style = T.Caption,
                    color = if (isSelected) C.Ink0.copy(alpha = 0.65f) else C.Text3,
                    maxLines = 1,
                )
            }
        }
        if (option.selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = if (isSelected) C.Ink0 else C.Mint,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** 秒级时间码：超过一小时显示 `H:MM:SS`，否则 `MM:SS` */
private fun fmtTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
