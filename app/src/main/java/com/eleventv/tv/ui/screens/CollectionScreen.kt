package com.eleventv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eleventv.tv.R
import com.eleventv.tv.data.model.TitleSummary
import com.eleventv.tv.ui.components.StateView
import com.eleventv.tv.ui.components.TvButton
import com.eleventv.tv.ui.components.TvButtonVariant
import com.eleventv.tv.ui.components.TvPosterGrid
import com.eleventv.tv.ui.components.TvText
import com.eleventv.tv.ui.components.VSpace
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T
import com.eleventv.tv.ui.design.focusRing

/**
 * 同标签海报墙：某个类型（科幻 / 爱情…）或某位演员的全部作品。
 *
 * ## 电影与剧集为什么互不相通
 *
 * 两类内容的观看场景完全不同（两小时一次 vs 几十集连载），混在一面墙里排序只能按
 * 添加时间，结果是「点科幻，前 6 张里有 5 张是剧」——筛选等于没做。所以两种分组
 * 各占一面墙：默认落在来源条目所在的那一组，两边都有内容时才给切换标签。
 *
 * ## 数据范围
 *
 * 只查本地库（[com.eleventv.tv.data.repo.ElevenRepository.titlesOfGenre] /
 * [titlesOfCast]）：点标签的诉求是「我库里还有哪些同类」，不能引入任何在线结果，
 * 否则点进去全是没下载的片子。
 *
 * @param label 显示名：类型名或艺人名
 * @param kindLabel 「类型」或「演员」，用于副标题，让用户知道这一墙是按什么筛出来的
 * @param initialMovies 初始分组，由来源条目的形态决定
 */
@Composable
fun CollectionScreen(
    label: String,
    kindLabel: String,
    movieItems: List<TitleSummary>,
    seriesItems: List<TitleSummary>,
    initialMovies: Boolean,
    onOpenTitle: (TitleSummary) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusId: String? = null,
    onFocusHandled: () -> Unit = {},
    onGroupChange: (Boolean) -> Unit = {},
) {
    // 分组只有一份真相：调用方的 `initialMovies` + 两组是否有内容。
    // 以前在这里放了一份本地 remembered 状态，并在目标组为空时兜底到另一组——兜底只改了
    // 本地值、没通知调用方，于是从详情页退回来时页面重建、按 initialMovies 又算一遍，
    // 用户刚切的分组被拉回去。
    val movies = when {
        initialMovies && movieItems.isNotEmpty() -> true
        !initialMovies && seriesItems.isNotEmpty() -> false
        movieItems.isNotEmpty() -> true
        else -> false
    }

    fun switchTo(target: Boolean) {
        if (target != movies) onGroupChange(target)
    }

    // 两组都有内容才给切换：只有一组时多两个按钮纯属噪音
    val switchable = movieItems.isNotEmpty() && seriesItems.isNotEmpty()
    val items = if (movies) movieItems else seriesItems

    // 进页面时把焦点交给第一张海报（否则会落在最先组合出来的「返回」按钮上）；
    // 之后手动切分组不再抢焦点，用户按「下」自己进墙
    var autoFocus by remember(label) { mutableStateOf(true) }

    // 空结果页没有列表可以接收焦点：必须在这里把「待恢复的焦点」消费掉，
    // 否则 returnFocusId 会一直挂着，下次回首页时把焦点还给一条已不在筛选里的旧卡片
    LaunchedEffect(items.isEmpty()) {
        if (items.isEmpty()) onFocusHandled()
    }

    // 从详情页返回时要还焦点给「刚点开的那张」；但它未必在筛选结果里（比如点了演员，
    // 刚看的那部其实是剧集，而当前停在电影分组），这时不能把焦点请求交给不存在的下标——
    // 那样谁都拿不到焦点，最后会落到「返回」按钮上
    val restoredId = focusId?.takeIf { id -> items.any { it.id == id } }
    val firstId = items.firstOrNull()?.id

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(C.Ink0),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Lg),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = D.ScreenPaddingX, end = D.ScreenPaddingX, top = D.Xl, bottom = D.Md),
        ) {
            TvText(label, style = T.Headline, color = C.Text1, maxLines = 1)
            TvText(
                text = "$kindLabel · ${if (movies) "电影" else "剧集"} · 共 ${items.size} 部",
                style = T.Caption,
                color = C.Text3,
                maxLines = 1,
            )

            Box(Modifier.weight(1f))

            if (switchable) {
                GroupChip(
                    label = "电影 ${movieItems.size}",
                    selected = movies,
                    onClick = { switchTo(true) },
                )
                GroupChip(
                    label = "剧集 ${seriesItems.size}",
                    selected = !movies,
                    onClick = { switchTo(false) },
                )
            }

            TvButton(
                onClick = onBack,
                label = "返回",
                icon = R.drawable.ic_chevron_left,
                variant = TvButtonVariant.Ghost,
            )
        }

        if (items.isEmpty()) {
            StateView(
                title = "没有更多$label",
                description = "媒体库里只有当前这一部。换个标签，或等新片下载完再扫描一次。",
                iconRes = R.drawable.ic_library,
                action = {
                    TvButton(
                        onClick = onBack,
                        label = "返回",
                        icon = R.drawable.ic_chevron_left,
                        variant = TvButtonVariant.Primary,
                    )
                },
            )
        } else {
            VSpace(D.Sm)
            val gridModifier = Modifier.weight(1f)
            // 按分组重建网格：切到「剧集」时若沿用电影的滚动位置，会从第 20 张开始显示
            key(movies) {
                TvPosterGrid(
                    items = items,
                    onItemClick = onOpenTitle,
                    modifier = gridModifier,
                    focusId = restoredId ?: if (autoFocus) firstId else null,
                    onFocusHandled = {
                        autoFocus = false
                        onFocusHandled()
                    },
                )
            }
        }
    }
}

/** 分组切换标签（电影 / 剧集），视觉与详情页的季标签一致 */
@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(D.ChipH + 5.dp)
            .background(
                if (selected) C.Text1 else C.Glass,
                CircleShape,
            )
            .focusRing(onClick = onClick, scale = 1.03f, shape = CircleShape)
            .padding(horizontal = D.Lg),
    ) {
        TvText(
            text = label,
            style = T.Caption.copy(fontWeight = if (selected) FontWeight(700) else FontWeight(400)),
            color = if (selected) C.Ink0 else C.Text2,
            maxLines = 1,
        )
    }
}
