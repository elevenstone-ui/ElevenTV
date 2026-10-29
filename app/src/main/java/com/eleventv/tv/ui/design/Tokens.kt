package com.eleventv.tv.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
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

    /** 导航栏标志 16dp（与 T.Nav 14→16sp 的放大同步调整，原 10.5dp 偏小），走矢量 */
    val LogoMark = 16.dp
    val LogoMarkLarge = 40.dp
    val LogoMarkAbout = 28.dp

    /** 启动页标志：三米外一眼能认出，又不至于顶到画面边缘 */
    val LogoMarkSplash = 96.dp

    /** 启动页标志背后的光晕直径（约标志的 3.6 倍，渐变自带软边） */
    val LogoGlow = 360.dp

    /**
     * 启动页字标相对屏幕中心的纵向偏移。
     *
     * LOGO 必须严格居中——系统启动窗口里那颗标志是 gravity=center，
     * 两边位置对不上，窗口交接的瞬间就会跳一下。
     * 所以字标不参与居中，靠这个偏移挂到 LOGO 下方：
     * 标志半高 48 + 间距 24 + 字标块半高约 36。
     */
    val WordmarkOffset = 108.dp
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
     * 启动页总停留时长（从点图标算起，不是从首帧算起）。
     *
     * 读本地配置只要几十毫秒，纯按"加载完成"关掉的话 LOGO 一闪而过，像闪屏故障；
     * 2 秒既能把进场动画走完，也不至于让用户等得烦。
     */
    const val SPLASH_HOLD = 2000L

    /**
     * 进场动画的最短时长。
     *
     * 冷启动慢的机型，首帧出来时 [SPLASH_HOLD] 可能已经用掉大半，
     * 剩余时间不足时按这个值兜底：宁可整体超时几百毫秒，也不要让动画"啪"一下闪完。
     */
    const val SPLASH_MIN_ANIM = 700L

    /**
     * 启动页绝对上限。
     *
     * 谁也不能把用户按在 LOGO 上。两件事都可能拖住启动页：
     * 动画因为主线程忙而迟迟走不完（电视盒子上很常见），
     * 以及读配置卡死（Keystore 异常、文件损坏）导致 `ready` 永不为真。
     * 到这个点一律放行，剩下的交给首页自己去加载——停在 LOGO 上连设置页都进不去，
     * 是最糟的失败形态。
     */
    const val SPLASH_CEILING = 3500L
}
