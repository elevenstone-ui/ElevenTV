package com.eleventv.tv.ui.components

import android.os.SystemClock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eleventv.tv.R
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.G
import com.eleventv.tv.ui.design.M
import com.eleventv.tv.ui.design.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 品牌标志（矢量）+ 可选字标。
 *
 * 标志是设计交付的 `drawable/ic_logo_mark`（512 视口：圆角底 + 双竖条 + 品牌渐变），
 * 这里只负责摆放尺寸，不重绘、不改色、不拉伸。用矢量而非位图，是因为
 * 电视 UI 会被系统上采样 2 倍，小尺寸位图的胶囊形竖条边缘会出锯齿。
 *
 * @param markSize 标志边长。导航栏 10.5dp，空态 40dp，关于页 28dp。
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    showWordmark: Boolean = true,
    markSize: Dp = D.LogoMark,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.Md),
        modifier = modifier,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo_mark),
            // 紧邻就是 ElevenTV 文字，标志再朗读一遍只是噪音
            contentDescription = null,
            modifier = Modifier.size(markSize),
        )
        if (showWordmark) {
            TvText("ElevenTV", style = T.Title2, color = C.Text1)
        }
    }
}

/**
 * 启动层：深紫底 + 矢量标志，带进场动画。
 *
 * A9F 是 Android 9，没有 API 31 的启动画面机制，冷启动只显示 windowBackground。
 * 入口页的窗口背景（`drawable/splash_brand`）已经画了同一颗标志、同一位置，
 * 这一层负责接过画面继续做动画——所以接管前后标志的位置和尺寸必须完全一致。
 *
 * ## 为什么标志不靠动画才可见
 *
 * 早先版本把标志透明度交给 `Animatable(0f) -> 1f`。主线程一旦被首帧的
 * 图片请求、列表测量占住，帧就出不来，透明度停在 0：屏幕上只剩一块紫底，
 * 两秒后淡出的那一刻才看到标志闪一下。
 * 现在标志**常亮**，动画只做轻微缩放、光晕、字标和进度线——掉帧也不会黑屏。
 *
 * ## 为什么用动画完成度而不是 delay 计时
 *
 * `delay` 走协程调度，主线程卡住照常计时；动画走帧回调，卡住就停。
 * 两者并跑的结果就是「时间到了、动画还没播」。这里改成等进度线真正跑完，
 * 另设 [M.SPLASH_CEILING] 兜底，不会无限等下去。
 *
 * @param visible 是否允许退场（配置读完且已停留够）
 * @param since 用户点图标的时刻（[SystemClock.uptimeMillis]），用来扣掉首帧耗时
 * @param onHoldEnd 停留结束回调，可能被重复调用，实现方按幂等处理
 */
@Composable
fun BrandSplash(
    visible: Boolean,
    since: Long,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val exitAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(M.PAGE_ENTER, easing = M.Standard),
        label = "splashAlpha",
    )
    if (exitAlpha <= 0.001f) return

    // 进场预算 = 2 秒 - 从点图标到现在已经花掉的时间。
    // 首帧慢的机型不扣的话，总时长会变成「窗口 1 秒 + 动画 2 秒」。
    val budget = remember(since) {
        (M.SPLASH_HOLD - (SystemClock.uptimeMillis() - since))
            .coerceIn(M.SPLASH_MIN_ANIM, M.SPLASH_HOLD)
    }

    val scale = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    val word = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }

    // 回调可能随重组变化，但 LaunchedEffect(Unit) 只跑一次，取最新值
    val end = rememberUpdatedState(onHoldEnd)

    LaunchedEffect(Unit) {
        // 标志的起止缩放都是 1.0，与 `splash_brand.xml` 里的 96dp 完全一致：
        // 窗口交接的那一刻位置和大小都不变，才看不出"换了一层在画"。
        // 中间轻轻涨到 1.03 再落回，替代原来的"放大落位"——那种做法会让 LOGO 在
        // 交接瞬间先跳大一点，再缩回去。
        launch {
            scale.animateTo(
                targetValue = 1f,
                animationSpec = keyframes {
                    durationMillis = (budget * 0.62f).toInt()
                    1.03f at (budget * 0.28f).toInt()
                },
            )
        }
        launch { glow.animateTo(1f, tween((budget * 0.39f).toInt(), easing = M.Decelerate)) }
        // 字标晚一点起步，形成「先标志、后名字」的层次
        launch {
            delay((budget * 0.09f).toLong())
            word.animateTo(1f, tween((budget * 0.17f).toInt(), easing = M.Standard))
        }

        // 进度线跑完 = 停留够了。超时兜底保证主线程再忙也会按时放行。
        val cap = (M.SPLASH_CEILING - (SystemClock.uptimeMillis() - since))
            .coerceAtLeast(M.SPLASH_MIN_ANIM)
        withTimeoutOrNull(cap) {
            bar.animateTo(1f, tween(budget.toInt(), easing = LinearEasing))
        }
        end.value()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = exitAlpha }
            .background(C.Splash),
        contentAlignment = Alignment.Center,
    ) {
        // 标志背后的品牌色光晕。
        // API 26 没有 RenderEffect，做不了真实高斯模糊，用双层径向渐变模拟"散开的光"：
        // 颜色本身是渐隐的，看上去就是软边，代价只有一次绘制。
        Box(
            modifier = Modifier
                .size(D.LogoGlow)
                .graphicsLayer {
                    alpha = glow.value * 0.6f
                    val s = 0.72f + glow.value * 0.5f
                    scaleX = s
                    scaleY = s
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            C.Brand.copy(alpha = 0.50f),
                            C.Brand2.copy(alpha = 0.12f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // 标志：绝对居中，且**不参与透明度动画**。
        // 位置要与 splash_brand.xml 的 gravity=center 严格对齐。
        Image(
            painter = painterResource(R.drawable.ic_logo_mark),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.Center)
                .size(D.LogoMarkSplash)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )

        // 字标不参与居中（参与就把标志顶上去了），用固定偏移挂在下方
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = D.WordmarkOffset)
                .graphicsLayer {
                    alpha = word.value
                    // 位移用 px：这里在 graphicsLayer 里，dp 换算要 density，没必要
                    translationY = (1f - word.value) * 22f
                },
        ) {
            TvText("ElevenTV", style = T.Headline, color = C.Text1)
            VSpace(D.Md)
            TvText("你的私人影院", style = T.Body2, color = C.Text3)
        }

        // 底部细进度线：这 2 秒里唯一的"还在动"的证据，避免用户以为卡死
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = D.Safe)
                .width(168.dp)
                .height(2.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bar.value.coerceIn(0f, 1f))
                    .background(G.Brand),
            )
        }
    }
}

/**
 * 底部按键提示条。
 *
 * 横贯全屏的常亮元素，OLED 烧屏风险最高，**5 秒必须淡出**。
 * 提示的键都必须在 RMF-TX500E 上真实存在（没有 MENU 键）。
 */
@Composable
fun KeyHintBar(
    modifier: Modifier = Modifier,
    hints: List<String> = DEFAULT_HINTS,
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(M.KEY_HINT)
        visible = false
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(M.OSD_OUT, easing = M.Standard),
        label = "hintAlpha",
    )
    if (alpha <= 0.001f) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Xl),
            modifier = Modifier
                .height(28.dp)
                .background(C.Glass, CircleShape)
                .padding(horizontal = D.Xl),
        ) {
            hints.forEach { hint ->
                TvText(hint, style = T.Caption, color = C.Text2)
            }
        }
    }
}

/** 只写遥控器上真实存在的键 */
private val DEFAULT_HINTS = listOf("OK 选择", "←→ 浏览", "↑↓ 切换", "↩ 返回")
