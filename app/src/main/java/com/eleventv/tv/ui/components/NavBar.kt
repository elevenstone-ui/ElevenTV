package com.eleventv.tv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.M
import com.eleventv.tv.ui.design.T

/** 导航项 */
data class NavItem(val id: String, val label: String)

/** 顶部菜单：首页 / 电影 / 剧集 / 动漫 / 综艺 / 纪录片 / 设置 */
val DefaultNavItems = listOf(
    NavItem("home", "首页"),
    NavItem("movie", "电影"),
    NavItem("series", "剧集"),
    NavItem("anime", "动漫"),
    NavItem("variety", "综艺"),
    NavItem("documentary", "纪录片"),
    NavItem("settings", "设置"),
)

/**
 * 顶部导航栏（高 64dp，左右 48dp）。
 *
 * 左端是品牌标志（矢量 32dp + 21.3sp 字标，比例 3:2），其后是菜单项。
 * 标志放大后菜单由 Row 自动右推，两者边缘间距不变（[D.X2l] + 内层 [D.Lg]）。
 *
 * ## 焦点视觉为什么和全应用不一样
 *
 * 其余组件统一用 [com.eleventv.tv.ui.design.focusRing]（白描边 + 品牌光晕），
 * 但顶部菜单是**文字行**：套一个圆角框会在字与字之间画出一堆互不相连的方块，
 * 视觉噪音比信息还多。这里改成「聚焦即放大」——文字放大 1.5 倍并转纯白，
 * 靠尺寸与亮度说话，栏内不再出现任何描边。
 *
 * 选中态（当前所在页面）保持白字 + 下方 2dp 薄荷短线，与焦点态正交：
 * 焦点移到别处后，用户依然知道自己在哪一屏。
 *
 * 导航栏叠在内容之上，调用方需要在它下面垫一层渐变遮罩（[com.eleventv.tv.ui.design.G.NavScrim]），
 * 否则浅色海报上的白字读不清。
 */
@Composable
fun TvNavBar(
    selectedId: String,
    onSelect: (NavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.X2l),
        modifier = modifier
            .fillMaxWidth()
            .height(D.NavHeight)
            .padding(horizontal = D.ScreenPaddingX),
    ) {
        // 字标字号（32dp → 21.3sp）与间距（32dp → 8dp）由 BrandLogo 按图形推出，
        // 这里不再传。间距不从 0.25H 脱钩：手调成 12dp 之后，下次改标志尺寸，
        // 间距就成了没人管的孤儿值。菜单也不必手动位移：
        // 这一行是 Row，品牌块变宽后菜单自然被推到右边，边缘间距保持不变。
        BrandLogo(showWordmark = true, markSize = D.LogoMark)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.X2l),
            modifier = Modifier.padding(start = D.Lg),
        ) {
            DefaultNavItems.forEach { item ->
                NavTab(
                    item = item,
                    selected = item.id == selectedId,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    // 缩放走 graphicsLayer：改字号会让整条导航重新测量，遥控器连按时表现为抖动
    val scale by animateFloatAsState(
        targetValue = if (focused) FOCUS_SCALE else 1f,
        animationSpec = tween(
            durationMillis = if (focused) M.FOCUS_IN else M.FOCUS_OUT,
            easing = M.Standard,
        ),
        label = "navFocusScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            // clickable 自带焦点语义，不能再叠 focusable（会产生两个焦点目标，OK 要按两次）
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // 以自身中心放大：左右各溢出相同的量，间距看起来才不会偏向一边
                transformOrigin = TransformOrigin(0.5f, 0.5f)
            }
            .padding(horizontal = D.Md, vertical = D.Xs),
    ) {
        TvText(
            text = item.label,
            style = T.Nav.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
            color = if (focused || selected) C.Text1 else C.Text2,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .width(14.dp)
                .height(2.dp)
                .background(if (selected) C.Mint else Color.Transparent, CircleShape),
        )
    }
}

/**
 * 聚焦放大倍数。
 *
 * 1.5 倍是「一眼能看出焦点在哪」与「不挤到相邻项」的平衡点：T.Nav 是 16sp，
 * 放大后 24sp，两字标签约 48dp；相邻项间距 24dp + 两侧各 8dp 内边距，
 * 单侧最多吃掉 12dp，仍有富余。再大就要顶到邻居的字了。
 */
private const val FOCUS_SCALE = 1.5f
