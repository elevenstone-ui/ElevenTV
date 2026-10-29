package com.eleventv.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.eleventv.tv.data.model.CardType
import com.eleventv.tv.data.model.Row
import com.eleventv.tv.data.model.TitleSummary
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T
import com.eleventv.tv.ui.design.focusRing
import androidx.compose.foundation.shape.CircleShape

/**
 * 横向内容行。
 *
 * ## 30% 露出
 *
 * 行内容宽 = 屏宽 − 2×48dp，卡片宽 120 + 间距 13 → 960dp 屏上正好 6.5 张，
 * 右侧自然露出半张卡，用户才知道还能往右。这是遥控器交互里最重要的可发现性设计，
 * 所以卡片宽度不取整到刚好占满（那会让用户以为到头了）。
 *
 * ## 焦点滚动
 *
 * LazyRow 会在子项获得焦点时自动把它滚进可见区，不需要手写 `animateScrollToItem`；
 * 手写反而会和 Compose 的内置焦点滚动抢，表现为焦点跳动。
 *
 * ## 恢复焦点
 *
 * 唯一的例外是「从详情页退回来」：[focusId] 指定的那张卡可能根本没组合过
 * （在横向列表的可见区之外），也就谈不上让它获得焦点。此时先滚到它的下标，
 * 等一帧让它完成组合，再请求焦点；成功后立刻回调 [onFocusHandled]，
 * 让上层清掉这个一次性意图，之后的滚动不再抢焦点。
 */
@Composable
fun TvRowSection(
    row: Row,
    onItemClick: (TitleSummary) -> Unit,
    modifier: Modifier = Modifier,
    onViewAll: (() -> Unit)? = null,
    focusId: String? = null,
    onFocusHandled: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val targetFocus = remember { FocusRequester() }
    val targetIndex = remember(row.id, focusId) {
        focusId?.let { id -> row.items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    }

    LaunchedEffect(row.id, targetIndex) {
        val index = targetIndex ?: return@LaunchedEffect
        runCatching { listState.scrollToItem(index) }
        // 组合到获得焦点至少隔一帧；失败（目标还没组合出来）就再等一帧重试
        repeat(4) {
            withFrameNanos { }
            if (runCatching { targetFocus.requestFocus() }.isSuccess) {
                onFocusHandled()
                return@LaunchedEffect
            }
        }
        onFocusHandled()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = D.ScreenPaddingX, end = D.ScreenPaddingX, bottom = D.Md),
        ) {
            TvText(
                text = row.title,
                style = T.Title1,
                color = C.Text1,
                modifier = Modifier.weight(1f),
            )
            if (onViewAll != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .focusRing(onClick = onViewAll, scale = 1.0f, shape = CircleShape)
                        .padding(horizontal = D.Md, vertical = D.Xs),
                ) {
                    TvText("查看全部", style = T.Caption, color = C.Text2)
                }
            }
        }

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(D.RowGap),
            // 上下留 10dp：焦点缩放与光晕需要空间，否则会被 LazyRow 裁掉
            contentPadding = PaddingValues(start = D.ScreenPaddingX, end = D.ScreenPaddingX, top = 10.dp, bottom = 10.dp),
        ) {
            items(row.items, key = { it.id }) { item ->
                // 焦点请求器要挂在卡片内部**真正可聚焦**的海报节点上，不能挂在外层列上：
                // 标题搬到海报下方后，外层 Column 不再是焦点目标，挂上去 requestFocus 会抛异常
                val focusModifier = if (item.id == focusId) {
                    Modifier.focusRequester(targetFocus)
                } else {
                    Modifier
                }
                if (row.cardType == CardType.Wide) {
                    WideCard(item = item, onClick = { onItemClick(item) }, focusModifier = focusModifier)
                } else {
                    PosterCard(item = item, onClick = { onItemClick(item) }, focusModifier = focusModifier)
                }
            }
        }
    }
}

/**
 * 海报墙（分类页）。
 *
 * 固定 6 列：960dp 宽的电视上 `(960 − 96) / (120 + 13) ≈ 6.4`，取 6 正好让右侧留白，
 * 卡宽 120dp 是三米观看距离下能看清评分的最小尺寸。屏幕更窄时列数由调用方下调，
 * 不用 [GridCells.Adaptive] 是因为它会在列数变化时留下半列空白，视觉上像渲染错误。
 *
 * [focusId] 与 [TvRowSection] 用法一致：从详情页返回时把焦点还给用户点开的那张海报。
 */
@Composable
fun TvPosterGrid(
    items: List<TitleSummary>,
    onItemClick: (TitleSummary) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 6,
    focusId: String? = null,
    onFocusHandled: () -> Unit = {},
) {
    val gridState = rememberLazyGridState()
    val targetFocus = remember { FocusRequester() }
    val targetIndex = remember(items, focusId) {
        focusId?.let { id -> items.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
    }

    LaunchedEffect(targetIndex) {
        val index = targetIndex ?: return@LaunchedEffect
        runCatching { gridState.scrollToItem(index) }
        repeat(4) {
            withFrameNanos { }
            if (runCatching { targetFocus.requestFocus() }.isSuccess) {
                onFocusHandled()
                return@LaunchedEffect
            }
        }
        onFocusHandled()
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(D.RowGap),
        verticalArrangement = Arrangement.spacedBy(D.RowSpacing),
        contentPadding = PaddingValues(
            start = D.ScreenPaddingX,
            end = D.ScreenPaddingX,
            top = 10.dp,
            bottom = BottomSpace,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(items, key = { it.id }) { item ->
            PosterCard(
                item = item,
                onClick = { onItemClick(item) },
                focusModifier = if (item.id == focusId) Modifier.focusRequester(targetFocus) else Modifier,
            )
        }
    }
}

/** 底部让出按键提示条的高度 */
private val BottomSpace = 64.dp
