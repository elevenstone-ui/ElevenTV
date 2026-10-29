package com.eleventv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.eleventv.tv.R
import com.eleventv.tv.data.model.JobState
import com.eleventv.tv.data.model.JobStatus
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.model.Row
import com.eleventv.tv.data.model.TitleSummary
import com.eleventv.tv.ui.components.StateView
import com.eleventv.tv.ui.components.TvButton
import com.eleventv.tv.ui.components.TvButtonVariant
import com.eleventv.tv.ui.components.TvRowSection
import com.eleventv.tv.ui.components.TvText
import com.eleventv.tv.ui.components.VSpace
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T

/**
 * 首页。
 *
 * 结构按需求固定：**继续播放 → 电影 → 剧集 → 动漫 → 综艺 → 纪录片**，
 * 空行整行隐藏（不是显示"暂无内容"）。行数据由仓储计算，页面不硬编码行结构。
 */
@Composable
fun HomeScreen(
    rows: List<Row>,
    libraryEmpty: Boolean,
    scanning: JobState,
    scraping: JobState,
    hasServers: Boolean,
    onOpenTitle: (TitleSummary) -> Unit,
    onViewAll: (MediaKind) -> Unit,
    onScan: () -> Unit,
    onScrape: () -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
    focusId: String? = null,
    onFocusHandled: () -> Unit = {},
) {
    val busy = scanning.status == JobStatus.Running || scraping.status == JobStatus.Running
    val firstFocus = remember { FocusRequester() }
    var focusRequested by remember { mutableStateOf(false) }

    // 首次进入且没有内容时，焦点必须落在唯一可用的出口上（否则用户被卡在原地）
    LaunchedEffect(libraryEmpty, hasServers, rows.size) {
        if (!focusRequested && (libraryEmpty || rows.isNotEmpty())) {
            runCatching { firstFocus.requestFocus() }
            focusRequested = true
        }
    }

    // 从详情页返回时要回到原来那张卡：先让竖向列表滚到它所在的行，
    // 行内的横向列表再自己滚到那张卡（见 TvRowSection 的 focusId）
    val rowsState = rememberLazyListState()
    val focusRow = remember(rows, focusId) {
        focusId?.let { id -> rows.indexOfFirst { row -> row.items.any { it.id == id } } } ?: -1
    }
    LaunchedEffect(focusRow) {
        if (focusRow >= 0) runCatching { rowsState.scrollToItem(focusRow) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            // 没有服务器：引导去设置
            libraryEmpty && !hasServers -> StateView(
                title = "还没有配置媒体库",
                description = "ElevenTV 只读取局域网 NAS 上的视频。先在设置里添加 SMB 服务器，再扫描媒体库。",
                iconRes = R.drawable.ic_storage,
                brand = true,
                action = {
                    Box(Modifier.focusRequester(firstFocus)) {
                        TvButton(
                            onClick = onAddServer,
                            label = "添加 NAS",
                            icon = R.drawable.ic_plus,
                            variant = TvButtonVariant.Primary,
                        )
                    }
                },
            )

            // 有服务器但库是空的：扫描中显示进度，否则给扫描入口
            libraryEmpty && scanning.status == JobStatus.Running -> ScanProgressView(scanning)

            libraryEmpty -> StateView(
                // 上一次扫描失败时必须把原因说出来。只写「媒体库是空的」，用户会以为
                // 片子被删了/库坏了，而真正的原因可能是 NAS 没连上
                title = if (scanning.status == JobStatus.Failed) "扫描失败" else "媒体库是空的",
                description = scanning.error
                    ?: "服务器已配置，但还没有扫描到视频。点下面的按钮开始扫描，扫完会自动刮削。",
                iconRes = R.drawable.ic_scan,
                action = {
                    Box(Modifier.focusRequester(firstFocus)) {
                        TvButton(
                            onClick = onScan,
                            label = if (scanning.status == JobStatus.Failed) "重新扫描" else "扫描媒体库",
                            icon = R.drawable.ic_scan,
                            variant = TvButtonVariant.Primary,
                        )
                    }
                },
            )

            else -> Column(Modifier.fillMaxSize()) {
                if (busy) {
                    JobBanner(scanning = scanning, scraping = scraping)
                }

                LazyColumn(
                    state = rowsState,
                    verticalArrangement = Arrangement.spacedBy(D.RowSpacing),
                    contentPadding = PaddingValues(top = D.Xl, bottom = 72.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
                        // 「继续播放」行没有对应的分类页，不能给它一个点了没反应的「查看全部」
                        val kind = MediaKind.entries.firstOrNull { it.name.lowercase() == row.id }
                        TvRowSection(
                            row = row,
                            onItemClick = onOpenTitle,
                            onViewAll = kind?.let { k -> { onViewAll(k) } },
                            // 只有包含目标卡的那一行才接收恢复意图，否则每行都会去抢焦点
                            focusId = focusId.takeIf { index == focusRow },
                            onFocusHandled = onFocusHandled,
                        )
                    }
                }
            }
        }
    }
}

/** 扫描中的整页进度（库为空时没有列表可看，进度就是唯一的反馈） */
@Composable
private fun ScanProgressView(job: JobState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = C.Mint, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            VSpace(D.X2l)
            TvText("正在扫描媒体库", style = T.Title1, color = C.Text1)
            VSpace(D.Md)
            TvText(
                text = job.message.ifBlank { "正在连接服务器…" },
                style = T.Body2,
                color = C.Text2,
                maxLines = 1,
            )
            if (job.current > 0) {
                VSpace(D.Sm)
                TvText("已扫描 ${job.current} 个文件", style = T.Caption, color = C.Text3)
            }
        }
    }
}

/**
 * 扫描 / 刮削进行中的顶部横幅。
 *
 * 不阻塞浏览：用户可以继续在下面翻内容，横幅只提供状态与手动重试入口。
 */
@Composable
private fun JobBanner(
    scanning: JobState,
    scraping: JobState,
) {
    val active = if (scanning.status == JobStatus.Running) scanning else scraping
    val isScrape = scanning.status != JobStatus.Running

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(D.Lg),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = D.ScreenPaddingX, vertical = D.Sm)
            .background(C.Ink1, RoundedCornerShape(D.RadiusPoster))
            .padding(horizontal = D.Xl, vertical = D.Md),
    ) {
        CircularProgressIndicator(
            color = if (isScrape) C.Brand else C.Mint,
            strokeWidth = 1.5.dp,
            modifier = Modifier.size(14.dp),
        )
        Column(Modifier.weight(1f)) {
            TvText(
                text = if (isScrape) "正在刮削" else "正在扫描",
                style = T.Label,
                color = C.Text1,
                maxLines = 1,
            )
            TvText(
                text = buildString {
                    append(active.message.ifBlank { "准备中…" })
                    if (active.total > 0) append("　${active.current}/${active.total}")
                },
                style = T.Caption,
                color = C.Text2,
                maxLines = 1,
            )
        }

        TvText(
            text = "设置页可手动触发",
            style = T.Caption,
            color = C.Text3,
            maxLines = 1,
        )
    }
}
