package com.eleventv.tv.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 设计令牌 · 颜色
 *
 * 逐值对应 `spec/tokens.json → color`。界面必须比内容暗：中性色全部压在极暗区间，
 * 只用一个品牌色与一个功能色提亮。
 */
object C {
    // ink：背景层级
    val Ink0 = Color(0xFF05060A)
    val Ink1 = Color(0xFF0B0D14)
    val Ink2 = Color(0xFF12141F)
    val Ink3 = Color(0xFF1B1E2D)

    // text：文字层级（不用纯白，OLED 上过曝）
    val Text1 = Color(0xFFF2F4FA)
    val Text2 = Color(0xFFA7ADC2)
    val Text3 = Color(0xFF6B7288)

    // brand
    val Brand = Color(0xFF7C5CFF)
    val BrandMid = Color(0xFFA04AF0)
    val Brand2 = Color(0xFFFF4E9B)

    // accent
    val Mint = Color(0xFF22E0C8)
    val Gold = Color(0xFFFFB35C)
    val Error = Color(0xFFFF5C7A)

    // overlay / 玻璃拟态（A9F 不做真实背景模糊）
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x1AFFFFFF)
    val StrokeGlass = Color(0x24FFFFFF)
    val StrokeHairline = Color(0x14FFFFFF)
    val Pressed = Color(0x1FFFFFFF)
    val SelectedTint = Color(0x2922E0C8)
    val Scrim = Color(0xCC000000)

    /** 启动底色：与 banner 深紫底、主题 colorPrimary 三处同值 */
    val Splash = Color(0xFF2B1240)

    /** 焦点环与光晕 */
    val FocusRing = Color(0xFFFFFFFF)
    val FocusGlow = Color(0xE67C5CFF)

    /** 海报缺图时的占位色（按 id 稳定取色，避免一片死灰） */
    fun placeholder(seed: String): Color {
        val palette = listOf(
            Color(0xFF2A1B4A), Color(0xFF1B2A3F), Color(0xFF3A1B2E),
            Color(0xFF1E2C24), Color(0xFF2C2418), Color(0xFF241C36),
        )
        return palette[(seed.hashCode() and 0x7FFFFFFF) % palette.size]
    }
}

/** 间距 / 尺寸 / 圆角，对应 `tokens.json → spacing / radius / component`。 */
object D {
    val Xs = 4.dp
    val Sm = 6.dp
    val Md = 8.dp
    val Lg = 12.dp
    val Xl = 16.dp
    val X2l = 24.dp
    val X3l = 32.dp
    val Safe = 48.dp

    val ScreenPaddingX = 48.dp
    val ScreenPaddingY = 27.dp
    val NavHeight = 64.dp
    val RowGap = 13.dp
    val RowSpacing = 24.dp
    const val CardPeekRatio = 0.30f

    val RadiusPoster = 8.dp
    val RadiusWide = 7.dp
    val RadiusEpisode = 6.dp
    val RadiusField = 8.dp
    val RadiusBadge = 4.dp
    val RadiusTag = 3.dp

    val PosterW = 120.dp
    val PosterH = 180.dp
    val WideW = 190.dp
    val WideH = 107.dp

    /**
     * 海报与卡片标题之间的间距。
     *
     * 标题从海报里搬到了海报下方（压在图上时和剧照细节糊在一起，三米外完全读不出剧名），
     * 焦点放大是纯绘制缩放，会向下多吃约 3.6dp，所以这里要留出比视觉间距更大的余量。
     */
    val CardTextGap = 8.dp

    /** 详情页选集卡：16:9 剧照，横向排布 */
    val EpisodeW = 208.dp
    val EpisodeH = 117.dp

    /**
     * 详情页演员卡：圆形头像 + 两行文字。
     *
     * 头像 76dp 是「三米外能看清一张脸」的下限；再小就只剩色块，
     * 认人这件事必须靠脸，不能靠名字。
     */
    val CastAvatar = 76.dp
    val CastW = 84.dp

    val ButtonH = 32.dp
    val ButtonPadH = 17.dp
    val ButtonIcon = 11.dp
    val ButtonGap = 6.dp

    val ChipH = 23.dp
    val ChipPadH = 12.dp

    val ProgressTrack = 4.dp
    val ProgressKnob = 13.dp
    val CardBar = 2.5.dp

    val SwitchTrackW = 24.dp
    val SwitchTrackH = 13.dp
    val SwitchKnob = 10.dp

    val KeySize = 33.dp
    val KeyGap = 5.dp

    val FocusRing = 2.dp
    val FocusGlow = 2.5.dp

    /**
     * 导航栏标志 32dp。
     *
     * 字标字号不在这里定，由 [T.brandWordmark] 按 [LockRatio] 推出（32dp → 21.3sp）。
     * 标志与字标必须成对改，否则左上角会变成「一个大图标 + 一行小字」。
     */
    val LogoMark = 32.dp
    val LogoMarkLarge = 40.dp
    val LogoMarkAbout = 28.dp

    /** 启动页标志：三米外一眼能认出，又不至于顶到画面边缘 */
    val LogoMarkSplash = 96.dp

    /**
     * 品牌组合法则（与设计稿 `ElevenTV-UI.html` 的 `--lock-ratio` / `--lock-gap` 同源）。
     *
     *     · 字标字号 = 图形高度 ÷ [LockRatio]   → 图形 H : 字号 = 3 : 2
     *     · 间距     = 图形高度 × [LockGapRatio]
     *
     * 图形是带圆角的实心块，同高度下视觉重量远大于字母：
     * 比例小于 1.4 时字标压图形（旧封面 1.27），大于 1.6 时字标沦为图形的注脚
     * （旧规范面板 1.68 / 旧横幅 1.69 / 旧 App 1.33 与 1.62 各说各话）。
     * 1.5 让 cap-height 正好占图形高度的一半，三米视距下最稳。
     *
     * **改比例只改这一个常量**，其余四处（导航栏 / 空态 / 引导页 / 启动页）自动跟随。
     */
    const val LockRatio = 1.5f

    /** 间距占比。标志多大，间距就多宽——不要再写死 8dp / 12dp */
    const val LockGapRatio = 0.25f

    /** 按法则推出的间距 */
    fun lockGap(mark: Dp): Dp = mark * LockGapRatio

    /** 启动页标志背后的光晕直径（约标志的 3.6 倍，渐变自带软边） */
    val LogoGlow = 360.dp

    // 原 WordmarkOffset（字标纵向偏移 108dp）已删除：字标现在与标志同行水平排列，
    // 标志先居中、随后左滑让位，不再需要把字标往下推。
}

/**
 * 字阶，对应 `tokens.json → typography.ramp` 的 `spDevice`（已按 A9F 上采样 ×1.2）。
 *
 * 字号一律用 spDevice：A9F 的 UI 在 1080p 合成再放大 2 倍，
 * 忠实换算的 10.5–11sp 在三米外会发虚。
 */
object T {
    private val family = FontFamily.SansSerif

    private fun style(
        size: Float,
        weight: Int,
        lineHeight: Float,
        tracking: Float,
    ) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        fontWeight = FontWeight(weight),
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.em,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

    val Display = style(49f, 750, 50f, -0.035f)
    val Headline = style(41f, 750, 43f, -0.030f)
    val Title1 = style(17f, 650, 20f, 0.01f)
    val Title2 = style(14f, 700, 18f, 0.01f)

    /**
     * 顶部导航项。
     *
     * 比正文大、比 Title1 小：导航是常驻元素，太大会挤压内容区，
     * 太小在 3 米距离上又认不得字。16sp 是上机反馈后的调整值。
     */
    val Nav = style(16f, 700, 20f, 0.01f)

    /**
     * 顶部品牌字标，按图形高度推导（见 [D.LockRatio]）。
     *
     * 不是常规字阶，也不该被当成标题用：它是「标志 + 品牌名」这一组的另一半，
     * 尺寸由标志决定——32dp 标志 → 21.3sp，96dp 标志 → 64sp。
     *
     * 用 [Density.toSp] 而不是直接 `.sp`：系统字体缩放变化时，字标仍与图形等高，
     * 不会出现「图标不动、字越调越大」把组合撑散的情况。
     *
     * @param markSize 配套图形的边长（dp）
     */
    fun brandWordmark(markSize: Dp, density: Density): TextStyle {
        val size = with(density) { (markSize.toPx() / D.LockRatio).toSp() }
        return style(
            size = size.value,
            weight = 750,
            lineHeight = size.value * BRAND_LINE,
            tracking = -0.005f,
        )
    }

    /** 品牌字标的行高倍数。比正文紧：字标只有一行，行高只是给字母留呼吸 */
    private const val BRAND_LINE = 1.25f

    val Body1 = style(13f, 400, 22f, 0.01f)
    val Body2 = style(12.5f, 400, 20f, 0.01f)
    val Label = style(11f, 600, 13f, 0.04f)
    val Caption = style(10f, 400, 14f, 0.06f)
    val Micro = style(7f, 700, 9f, 0.08f)

    /**
     * 海报角标（`4K` `HDR10` `杜比视界`）。
     *
     * 比 [Micro] 大一号：7sp 在 120dp 的窄海报上确实排得下，但三米外认不出「杜比视界」
     * 这四个字，角标就失去意义了。再大又会撞上右上角的评分，8.5sp 是两者的平衡点。
     */
    val Badge = style(8.5f, 700, 11f, 0.06f)
}

/** 渐变与遮罩，对应 `tokens.json → color.gradient / scrim`。 */
object G {
    /** 品牌渐变：**只用于 Logo 与焦点光晕**，不做大面积背景 */
    val Brand: Brush = Brush.linearGradient(listOf(C.Brand, C.BrandMid, C.Brand2))

    /** 播放进度已播段 */
    val Progress: Brush = Brush.horizontalGradient(listOf(C.Brand, C.Mint))

    /** 首页 Hero 左侧遮罩 */
    val EdgeLeft: Brush = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to C.Ink0,
            0.26f to C.Ink0.copy(alpha = 0.94f),
            0.48f to C.Ink0.copy(alpha = 0.55f),
            0.72f to Color.Transparent,
        ),
    )

    /** Hero 底部遮罩 */
    val EdgeBottom: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to C.Ink0,
            0.22f to C.Ink0.copy(alpha = 0.35f),
            0.46f to Color.Transparent,
        ),
    )

    /** 卡片底部遮罩（标题压在图上） */

    /** OSD 顶部 / 底部遮罩 */
    val OsdTop: Brush = Brush.verticalGradient(
        colorStops = arrayOf(0f to Color.Black.copy(alpha = 0.82f), 1f to Color.Transparent),
    )
    val OsdBottom: Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Black.copy(alpha = 0.92f),
            0.55f to Color.Black.copy(alpha = 0.50f),
            1f to Color.Transparent,
        ),
    )

    /** 导航栏顶部遮罩 */
    val NavScrim: Brush = Brush.verticalGradient(
        colorStops = arrayOf(0f to Color.Black.copy(alpha = 0.60f), 1f to Color.Transparent),
    )
}

/** 动效，对应 `tokens.json → motion`。 */
object M {
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    const val FOCUS_IN = 150
    const val FOCUS_OUT = 120
    const val ROW_SCROLL = 250
    const val PAGE_ENTER = 300
    const val OSD_IN = 200
    const val OSD_OUT = 300
    const val DIALOG_IN = 220
    const val TOAST_IN = 180
    const val SKELETON = 1200

    /** OSD 无操作自动隐藏 */
    const val OSD_AUTO_HIDE = 4000L

    /** 按键提示条显示时长（OLED 防烧屏，硬性要求） */
    const val KEY_HINT = 5000L

    /**
     * 启动动画的固定时长，**从首帧上屏算起**。
     *
     * 上一版按「点图标后的 2 秒」倒推（`2 秒 - 首帧耗时`），结果同一份节奏有两种观感：
     * 机器快时首帧只花 200ms，动画被拉到 1.8 秒；机器慢时又撞到 1.4 秒下限。
     * 而"首帧之前"那段是系统启动窗口（静态 LOGO），时长由设备冷启动决定，
     * 应用这边既控制不了、也不该拿它去调动画。
     *
     * 现在固定 1 秒：位移在进度 0.06 就起步（约 60ms），8 个字母间隔 72ms 依次长出，
     * 约 730ms 时整组落位。比上一版短 400ms，且不再有开头那段"LOGO 干等着"。
     */
    const val SPLASH_ANIM = 1000L

    /**
     * 启动页绝对上限，**只对「配置读不出来」生效**。
     *
     * 读配置卡死（Keystore 异常、文件损坏）会让 `ready` 永不为真，
     * 停在 LOGO 上连设置页都进不去，是最糟的失败形态，到点一律放行。
     *
     * 它不负责卡动画时长：动画现在按帧推进，慢设备上会被抻长（见 `SplashMotion.step`），
     * 再用一个秒数把两边一起兜住，会把没播完的动画直接切掉——那正是要修的毛病。
     * 这里只做最后一道硬闸：动画循环超过它一律收尾，绝不把 LOGO 挂在屏幕上等。
     */
    const val SPLASH_CEILING = 3500L
}
