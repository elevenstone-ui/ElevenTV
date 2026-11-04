package com.eleventv.tv.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eleventv.tv.R
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.G
import com.eleventv.tv.ui.design.M
import com.eleventv.tv.ui.design.T
import kotlinx.coroutines.delay

/**
 * 品牌标志（矢量）+ 可选字标。
 *
 * 标志是设计交付的 `drawable/ic_logo_mark`（512 视口：圆角底 + 双竖条 + 品牌渐变），
 * 这里只负责摆放尺寸，不重绘、不改色、不拉伸。用矢量而非位图，是因为
 * 电视 UI 会被系统上采样 2 倍，小尺寸位图的胶囊形竖条边缘会出锯齿。
 *
 * @param markSize 标志边长。导航栏 32dp，空态 40dp，关于页 / 引导页 28dp。
 *
 * 字标字号与间距都不再由调用方决定：字号 = 边长 ÷ [D.LockRatio]（3:2），
 * 间距 = 边长 × 0.25，与设计稿 `--lock-ratio` / `--lock-gap` 同一条法则。
 * 这样任何一处改了标志大小，字标都会自己跟上，不会再出现「图标改了、字没改」。
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    showWordmark: Boolean = true,
    markSize: Dp = D.LogoMark,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.lockGap(markSize)),
        modifier = modifier,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo_mark),
            // 紧邻就是 ElevenTV 文字，标志再朗读一遍只是噪音
            contentDescription = null,
            modifier = Modifier.size(markSize),
        )
        if (showWordmark) {
            BrandWordmark(markSize)
        }
    }
}

/**
 * 只排字标、不排标志。
 *
 * 给「标志在左、右边两行字」的布局用（关于页就是这种）：字号仍由图形成立，
 * 免得那里又冒出一个手填的 `T.Title1`，把刚统一的比例重新拆开。
 */
@Composable
fun BrandWordmark(
    markSize: Dp = D.LogoMark,
    modifier: Modifier = Modifier,
) {
    TvText(
        text = "ElevenTV",
        modifier = modifier,
        style = T.brandWordmark(markSize, LocalDensity.current),
        color = C.Text1,
    )
}

/**
 * 启动层：深紫底 + 矢量标志，带进场动画。
 *
 * A9F 是 Android 9，没有 API 31 的启动画面机制，冷启动只显示 windowBackground。
 * 入口页的窗口背景（`drawable/splash_brand`）已经画了同一颗标志、同一位置，
 * 这一层负责接过画面继续做动画——所以**交接那一刻**标志必须还是 96dp、还是居中。
 * 左移是交接之后才发生的事，`splash_brand.xml` 不需要跟着改。
 *
 * ## 编排
 *
 * 标志单独居中 → 向右让出字标的位置（看上去就是标志向左滑）→ "ElevenTV" 逐字母淡入上浮。
 *
 *     0.00 ── 0.06   LOGO 居中（只剩 60ms，交接用，不是"停留"）
 *     0.06 ── 0.74   整组左移（easeOutCubic：起步快、收尾贴住）
 *     0.10 ── 0.71   8 个字母依次淡入，每字窗口 0.11、间隔 0.072
 *
 * 位移比第一个字母早 0.04 起步、比最后一个字母早 0.05 收尾：动作先起、字母后到，
 * 收尾几乎同时。这样看起来是"标志滑开、名字跟着长出来"的一件事，
 * 而不是两段各走各的动画。
 *
 * ## 为什么只有一个时间轴状态
 *
 * 位移、字母、光晕、缩放、进度线全部从 [timeline] 派生（公式在 [SplashMotion]）。
 * 只有一个值在变，主线程再忙也不会出现"字母已经开始冒、标志还没动"。
 *
 * ## 为什么按帧推进，而不是 `animateTo` + `tween`
 *
 * 这是修过的一个真问题。首页挂载那一帧要组海报墙、量几十张卡片，电视盒子上常要一两秒。
 * 时间驱动的动画在第一帧记下起点，第二帧醒来时早已超时，于是直接落到终点——现象就是
 * 「LOGO 停一下，下一个画面已经是最后一帧」。
 *
 * 现在每帧只推进 `SplashMotion.step()`，单帧最多计 100ms，**且没有例外**：
 * 掉帧只会把动画抻长，绝不会跳过。
 *
 * 中间踩过一次坑：曾经为了让极慢设备别拖太久，加了"真实耗时超预算 2.5 倍就放开限幅"。
 * 结果主线程卡得最狠的那一帧恰好满足条件，`2000ms / 1400ms` 一步到底——
 * 卡得越久越容易被跳过。那个口子已经删掉，时长改由墙钟兜底负责。
 *
 * ## 为什么首页要等主相位结束才挂载
 *
 * 会回调 [onMainPhaseEnd]。位移和逐字母都在进度 0.70 前结束，之后只剩光晕和进度线；
 * 这时候再让主线程去量海报墙，掉帧也看不出来。早一步挂载，动画就会被挤成幻灯片。
 *
 * ## 为什么标志不靠动画才可见
 *
 * 早先版本把标志透明度交给 `Animatable(0f) -> 1f`。主线程一旦被首帧的
 * 图片请求、列表测量占住，帧就出不来，透明度停在 0：屏幕上只剩一块紫底，
 * 两秒后淡出的那一刻才看到标志闪一下。
 * 现在标志**常亮**，动画只做位移、缩放、光晕、字标和进度线——掉帧也不会黑屏，
 * 最坏情况是"停在居中的标志上"，和窗口背景长得一模一样。
 *
 * @param visible 是否允许退场（配置读完且已停留够）
 * @param onMainPhaseEnd 动画主相位结束（位移停稳、字母落位），调用方可以开始搭首页。
 *   可能因墙钟兜底而被提前调用，实现方按幂等处理
 * @param onHoldEnd 停留结束回调，可能被重复调用，实现方按幂等处理
 */
@Composable
fun BrandSplash(
    visible: Boolean,
    onMainPhaseEnd: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val exitAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(M.PAGE_ENTER, easing = M.Standard),
        label = "splashAlpha",
    )
    if (exitAlpha <= 0.001f) return

    // 动画预算是固定的 1.2 秒，不按「点图标后 2 秒」倒推。
    // 倒推会让快机器上的动画被拉到 1.8 秒、慢机器上又撞下限，同一份节奏两种观感。
    val budget = M.SPLASH_ANIM

    // 唯一的动画状态：0 → 1 的线性进度。位移、逐字母、光晕、缩放、进度线全部由它派生
    // （公式见 SplashMotion）。只有一个值在变，就不可能出现「字母已经开始冒、标志还没动」。
    val timeline = remember { Animatable(0f) }
    // 整组宽度要等第一次 layout 才知道。这里只存原始宽度，
    // 换算成位移放在 draw 里做（lambda 里读 groupWidth）：写状态发生在 layout 阶段，
    // 同帧 draw 读到的就是新值，不会出现"第一帧按 0 偏移画、第二帧再跳回来"。
    var groupWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val logoPx = with(density) { D.LogoMarkSplash.toPx() }
    val risePx = with(density) { LETTER_RISE.toPx() }

    // 整组（标志 + 字标 + 光晕）共用的位移量。光晕和标志必须用同一个值，
    // 否则标志左移后光还留在屏幕中间。
    val translateAt: () -> Float = { (1f - SplashMotion.slide(timeline.value)) * shiftFor(groupWidth, logoPx) }

    // 回调可能随重组变化，但 LaunchedEffect(Unit) 只跑一次，取最新值
    val end = rememberUpdatedState(onHoldEnd)
    val onPhase = rememberUpdatedState(onMainPhaseEnd)

    LaunchedEffect(Unit) {
        // 按帧推进，而不是 `timeline.animateTo(1f, tween(budget))`。
        //
        // 首页的挂载会让主线程停顿一两秒（组海报墙、量几十张卡片），
        // 时间驱动的动画在第一帧记下起点，第二帧醒来时早已超时，于是直接落到终点——
        // 用户看到的就是「LOGO 停一下，下一帧已经是最后画面」。
        // 改成每帧只推 SplashMotion.step()：单帧最多计 100ms，掉帧只会把动画抻长，绝不会跳过。
        var last = withFrameNanos { it }
        val startedAt = last
        var phaseReported = false
        while (timeline.value < 1f) {
            val now = withFrameNanos { it }
            val deltaMs = (now - last) / 1_000_000f
            last = now

            // 墙钟兜底：设备真的慢到底（整段帧率都在个位数），宁可截断也不能让用户
            // 盯着 LOGO 干等。正常冷启动 1.4 秒就走完，走不到这里。
            if ((now - startedAt) / 1_000_000f > M.SPLASH_CEILING) break

            val next = (timeline.value + SplashMotion.step(deltaMs, budget.toFloat()))
                .coerceAtMost(1f)
            timeline.snapTo(next)

            // 主相位结束就放行首页。此时位移已停、字母已全部落位，
            // 后面挂载海报墙引起的卡顿不会再被看出来。
            if (!phaseReported && next >= SplashMotion.MAIN_PHASE_END) {
                phaseReported = true
                onPhase.value()
            }
        }
        // 兜底路径（墙钟截断）也要把终态补齐，否则会停在半路且永不回调
        timeline.snapTo(1f)
        if (!phaseReported) onPhase.value()
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
                    val g = SplashMotion.glow(timeline.value)
                    alpha = g * 0.6f
                    val s = 0.72f + g * 0.5f
                    scaleX = s
                    scaleY = s
                    // 光晕跟着标志走，否则标志左移后光还留在屏幕中间
                    translationX = translateAt()
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

        // 标志与字标作为**一组**参与布局，整组连同一份位移一起交给 [SplashLockup]：
        // 整组右移半个字标宽度时，标志正好落在屏幕正中（与 splash_brand.xml 的
        // gravity=center 对齐）；右移量随 timeline 归零，整组回到居中，
        // 视觉上就是"标志向左滑开、名字从右侧长出来"。
        SplashLockup(
            translateAt = translateAt,
            scaleAt = { SplashMotion.scale(timeline.value) },
            letterAt = { i -> SplashMotion.letter(timeline.value, i) },
            risePx = risePx,
            onWidthChanged = { groupWidth = it },
        )

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
                    .fillMaxSize()
                    .graphicsLayer {
                        // 用 scaleX 而不是 fillMaxWidth：后者每帧都要重布局。
                        // 启动页只有这一条线在动，没必要让布局系统跟着跑 2 秒。
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        scaleX = timeline.value
                    }
                    .background(G.Brand),
            )
        }
    }
}

/**
 * 启动页的品牌组合：标志 + 逐字母出现的「ElevenTV」。
 *
 * 拆出来不是为了好看：[BrandSplash] 里已经有预算计算、协程编排、光晕、进度线四件事，
 * 再加排版就 170 行往上，reviewer 找不到重点。
 *
 * 三个取值回调都在 `graphicsLayer` 里调用，属 draw 阶段读取，不触发重组。
 *
 * @param translateAt 整组位移（px）：正值 = 标志居中，0 = 整组居中
 * @param scaleAt 标志缩放。起止都是 1.0，与启动窗口交接时不跳
 * @param letterAt 第 i 个字母的浮现进度 0..1
 * @param risePx 字母未落位时的下移量（px）
 * @param onWidthChanged layout 完成后回报整组宽度，供位移换算
 */
@Composable
private fun SplashLockup(
    translateAt: () -> Float,
    scaleAt: () -> Float,
    letterAt: (Int) -> Float,
    risePx: Float,
    onWidthChanged: (Int) -> Unit,
) {
    // 字标与间距也由图形推出：96dp 标志 → 64sp 字标、24dp 间距（见 D.LockRatio）。
    // 启动页是全屏最大的一处组合，之前按 T.Headline(41sp) 排，比例 2.34，
    // 字标在标志旁边只剩注脚的份量。
    val wordmark = T.brandWordmark(D.LogoMarkSplash, LocalDensity.current)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.lockGap(D.LogoMarkSplash)),
        modifier = Modifier
            .onSizeChanged { onWidthChanged(it.width) }
            .graphicsLayer { translationX = translateAt() },
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo_mark),
            contentDescription = null,
            modifier = Modifier
                .size(D.LogoMarkSplash)
                .graphicsLayer {
                    val s = scaleAt()
                    scaleX = s
                    scaleY = s
                },
        )

        // 拆成单个字符排布，才能给每个字母各一个淡入窗口。
        // 字母始终占位、只改 alpha，所以整组宽度从头到尾不变：
        // 字标长出来时标志不会跟着二次跳动。
        Row(
            modifier = Modifier.clearAndSetSemantics {
                // 8 个字符各成一个节点的话，读屏会把名字拆成 8 段念
                contentDescription = SplashMotion.WORDMARK
            },
        ) {
            SplashMotion.WORDMARK.forEachIndexed { i, ch ->
                TvText(
                    text = ch.toString(),
                    style = wordmark,
                    color = C.Text1,
                    modifier = Modifier
                        // 按基线对齐：字母盒高随字形变化，居中会让 e 和 l 上下错位
                        .alignByBaseline()
                        .graphicsLayer {
                            val e = letterAt(i)
                            alpha = e
                            translationY = (1f - e) * risePx
                        },
                )
            }
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

/**
 * 单个字母的上浮距离：够看出「落位」，又不至于把字甩飘。节奏见 [SplashMotion]。
 *
 * 从 12dp 提到 18dp，是因为字标跟着图形放大到了 64sp：上浮量得按字号算（约 0.28em），
 * 否则字变大了、位移还是原来的 12dp，看起来就只剩淡入没有落位。
 */
private val LETTER_RISE = 18.dp

/**
 * 「标志居中」相对「整组居中」要右移的像素 = (整组宽 - 标志宽) / 2。
 *
 * 必须在 draw 阶段调用（参数是当时的实测宽度），不要在组合期预先算好存进变量——
 * 那会把首次 layout 之前的值固化进 lambda，第一帧位置差半个字标。
 */
private fun shiftFor(groupWidth: Int, logoWidthPx: Float): Float =
    ((groupWidth - logoWidthPx) / 2f).coerceAtLeast(0f)
