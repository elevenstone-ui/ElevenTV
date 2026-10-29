package com.eleventv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eleventv.tv.R
import com.eleventv.tv.data.model.MediaKind
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

/**
 * 分类页：某一类型的全部海报。
 *
 * 只做浏览，不做筛选排序——本地库通常几十到几百部，遥控器上多一层筛选
 * 反而比多翻两行更费劲。
 */
@Composable
fun BrowseScreen(
    kind: MediaKind,
    items: List<TitleSummary>,
    onOpenTitle: (TitleSummary) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    focusId: String? = null,
    onFocusHandled: () -> Unit = {},
) {
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
            TvText(kind.label, style = T.Headline, color = C.Text1)
            Box(Modifier.padding(top = D.Md)) {
                TvText(
                    text = "共 ${items.size} 部",
                    style = T.Caption,
                    color = C.Text3,
                )
            }
            Box(Modifier.weight(1f))
            TvButton(
                onClick = onBack,
                label = "首页",
                icon = R.drawable.ic_home,
                variant = TvButtonVariant.Ghost,
            )
        }

        if (items.isEmpty()) {
            StateView(
                title = "暂无${kind.label}",
                description = "媒体库里还没有识别为${kind.label}的条目。可以在设置页重新扫描，或调整刮削后的分类。",
                iconRes = R.drawable.ic_library,
                action = {
                    TvButton(
                        onClick = onBack,
                        label = "回首页",
                        icon = R.drawable.ic_chevron_left,
                        variant = TvButtonVariant.Primary,
                    )
                },
            )
        } else {
            VSpace(D.Sm)
            TvPosterGrid(
                items = items,
                onItemClick = onOpenTitle,
                modifier = Modifier.weight(1f),
                focusId = focusId,
                onFocusHandled = onFocusHandled,
            )
        }
    }
}
