package com.eleventv.tv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.eleventv.tv.R
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.MediaFile
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.model.PlaybackRecord
import com.eleventv.tv.data.repo.episodeLabel
import com.eleventv.tv.ui.components.CastCard
import com.eleventv.tv.ui.components.EpisodePosterCard
import com.eleventv.tv.ui.components.HSpace
import com.eleventv.tv.ui.components.StateView
import com.eleventv.tv.ui.components.TvButton
import com.eleventv.tv.ui.components.TvButtonVariant
import com.eleventv.tv.ui.components.TvText
import com.eleventv.tv.ui.components.VSpace
import com.eleventv.tv.ui.components.fmtScore
import com.eleventv.tv.ui.design.C
import com.eleventv.tv.ui.design.D
import com.eleventv.tv.ui.design.T
import com.eleventv.tv.ui.design.focusRing

/**
 * 详情页。
 *
 * 电影只有一条播放按钮；多季剧集按季分组，季标签切换后下面是该季的选集列表。
 * 背景用横版剧照铺满再压一层渐变——海报是 2:3 的，铺不满 16:9 的电视屏，
 * 硬拉会把封面裁得只剩中间一条。
 *
 * ## 布局为什么是「固定头部 + 独立滚动列表」
 *
 * 进页面默认焦点落在播放键，而播放键在标题/元信息之下。若把头部也放进
 * LazyColumn，`requestFocus` 触发的 bringIntoView 会为了「让播放键完整可见」
 * 把整页往上滚——结果就是海报、标题被顶出屏幕，用户看到的是「详情页上部被遮住，
 * 播放键莫名其妙跑到很上面」。
 * 所以头部（海报 + 标题 + 元信息 + 按钮）固定在顶部，只有简介与选集区滚动。
 */
@Composable
fun DetailScreen(
    entry: LibraryEntry?,
    record: PlaybackRecord?,
    onBack: () -> Unit,
    onPlay: (LibraryEntry, MediaFile) -> Unit,
    onRescrape: () -> Unit,
    onOpenGenre: (String) -> Unit,
    onOpenCast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entry == null) {
        StateView(
            title = "找不到这个条目",
            description = "它可能已经被重新扫描移除了。",
            iconRes = R.drawable.ic_alert,
            action = {
                TvButton(onClick = onBack, label = "返回", icon = R.drawable.ic_chevron_left)
            },
        )
        return
    }

    val seasons = entry.seasons
    val currentFile = record?.let { rec -> entry.files.firstOrNull { it.id == rec.fileId } }
    var season by remember(entry.id) {
        mutableIntStateOf(currentFile?.seasonNumber ?: seasons.firstOrNull() ?: 1)
    }

    val episodes = entry.filesOfSeason(season)
    val resumeLabel = when {
        record == null || record.finished -> if (entry.kind == MediaKind.MOVIE) "播放" else "从第 1 集开始"
        // 电影没有集号：不传 isMovie 会显示「继续观看 · E1」（MediaFile 的集号默认是 1）
        else -> "继续观看" + (
            currentFile?.episodeLabel(isMovie = entry.kind == MediaKind.MOVIE)
                ?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
            )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(C.Ink0),
    ) {
        // ---- 背景：铺满整个屏幕 ----
        //
        // 早先这里是 `fillMaxWidth().height(420.dp)`：背景只在顶部占一条，
        // 下面大半屏是纯 Ink0。1080p 电视上看着就像「一张图浮在黑色画布上」，
        // 页面明显没铺满。背景图必须是全屏尺寸，且用 Crop 而不是 Fit——
        // Fit 会在两侧补黑边（16:9 的剧照放进 4:3 屏、或者竖版海报退回时都会露边），
        // 那正是「左右留了黑色遮罩」的典型成因。
        val art = entry.backdropUrl ?: entry.posterUrl
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 没刮削的条目也得有底色，否则整屏漆黑，看着像加载失败
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(C.placeholder(entry.id), C.Ink1, C.Ink0),
                        ),
                    ),
            )
        }

        // 渐变覆盖层同样全屏：上部留一点剧照让小标题可辨，下部收成不透明 Ink0，
        // 保证选集、简介压在上面时对比度足够。
        //
        // 色标在 2.1.33 前后收紧过一次（0.58→92%、0.78→全黑），结果中下部整片被压成纯黑，
        // 用户看到的就是「详情页没全屏、左右两条黑边」——其实页面是铺满的，只是背景剧照
        // 在选集区高度完全透不出来，左右边缘全是 Ink0。现在放回柔曲线：
        // 选集卡行（屏高 55~75%）剧照仍透出 4~6 成，左右边缘有图；
        // 简介（35~45%）压到半实，艺人/源文件清单（80%+）压到近全黑，可读性不丢。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.55f),
                            0.30f to C.Ink0.copy(alpha = 0.45f),
                            0.62f to C.Ink0.copy(alpha = 0.72f),
                            1.00f to C.Ink0,
                        ),
                    ),
                ),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // ---- 固定头部：永远不随列表滚动 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(D.X3l),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = D.ScreenPaddingX, end = D.ScreenPaddingX, top = 84.dp),
            ) {
                Poster(entry)
                Box(Modifier.weight(1f)) {
                    InfoColumn(
                        entry = entry,
                        record = record,
                        resumeLabel = resumeLabel,
                        onPlay = { resumeFileOf(entry, record)?.let { onPlay(entry, it) } },
                        onRescrape = onRescrape,
                        onBack = onBack,
                        onOpenGenre = onOpenGenre,
                    )
                }
            }

            // ---- 滚动区：简介 / 季标签 / 选集 ----
            LazyColumn(
                contentPadding = PaddingValues(
                    start = D.ScreenPaddingX,
                    end = D.ScreenPaddingX,
                    top = D.Xl,
                    bottom = 56.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(D.Md),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                item("overview") {
                    TvText(
                        text = entry.overview?.takeIf { it.isNotBlank() }
                            ?: "还没有简介。点「重新刮削」再试一次，或检查设置里的刮削数据源。",
                        style = T.Body2,
                        color = if (entry.overview.isNullOrBlank()) C.Text3 else C.Text2,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.92f),
                    )
                }

                // 季标签：只有多季时才出现，单季剧集加一排「第 1 季」纯属噪音。
                // 用 LazyRow 而不是 Row：跨季归并后一部剧可能有十几季，
                // Row 放不下的标签会被直接裁掉（遥控器也移不过去）。
                if (seasons.size > 1) {
                    item("seasons") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(D.Md),
                            contentPadding = PaddingValues(vertical = D.Sm),
                            modifier = Modifier.padding(top = D.Sm),
                        ) {
                            items(seasons) { s ->
                                SeasonChip(
                                    label = "第 $s 季",
                                    selected = s == season,
                                    onClick = { season = s },
                                )
                            }
                        }
                    }
                }

                item("seasonOverview") {
                    entry.seasonOverviews[season]?.takeIf { it.isNotBlank() }?.let {
                        TvText(
                            text = it,
                            style = T.Body2,
                            color = C.Text2,
                            maxLines = 3,
                            modifier = Modifier.padding(top = D.Sm),
                        )
                    }
                }

                // 选集：横向一行海报，左右移动选择。
                // 竖排一行一条的列表在几十集的剧集上要点几十次「下」才能到后面，
                // 横向滑动一屏能扫到 4 张，配合季标签定位更快。
                item("episodes") {
                    // 横向列表的滚动位置按季保存：切季时若沿用上一季的偏移，
                    // 会从第 7 集开始显示，看着像"这一季只有后半段"
                    val epState = remember(season) { LazyListState() }
                    // 正在看的那一集放到可见区靠左位置，进页面就能看到「我上次看到哪」
                    val currentIndex = episodes.indexOfFirst { it.id == record?.fileId }
                    LaunchedEffect(season, currentIndex) {
                        if (currentIndex > 0) {
                            runCatching { epState.scrollToItem((currentIndex - 1).coerceAtLeast(0)) }
                        }
                    }
                    val isMovie = entry.kind == MediaKind.MOVIE

                    Column(modifier = Modifier.padding(top = D.Lg)) {
                        // 电影只有一条，标「选集 · 共 1 集」既荒谬又占地方，直接不出标题行
                        if (!isMovie) {
                            // 按季统计：多季显示「第 N 季 · 共 X 集 · 库里 Y 集」，单季显示「共 X 集 · 库里 Y 集」。
                            // 多季时某季的总集数只认 seasonEpisodeCounts——整剧 totalEpisodes 套到单季会误导
                            // （3 季 90 集，某季可能只有 30 集）；单季没有季级数据时才退回整剧 totalEpisodes。
                            // 都拿不到就只报「库里 Y 集」，绝不拿季数冒充集数。
                            val multiSeason = entry.seasons.size > 1
                            val seasonTotal = if (multiSeason) {
                                entry.seasonEpisodeCounts[season]?.takeIf { it > 0 }
                            } else {
                                entry.seasonEpisodeCounts[season]?.takeIf { it > 0 }
                                    ?: entry.totalEpisodes?.takeIf { it > 0 }
                            }
                            val episodeLine = buildList {
                                if (multiSeason) add("第 $season 季")
                                if (seasonTotal != null) add("共 $seasonTotal 集")
                                add("库里 ${episodes.size} 集")
                                add("左右选择")
                            }.joinToString(" · ")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TvText("选集", style = T.Title1, color = C.Text1)
                                HSpace(D.Lg)
                                TvText(
                                    text = episodeLine,
                                    style = T.Caption,
                                    color = C.Text3,
                                    maxLines = 1,
                                )
                            }
                        }

                        LazyRow(
                            state = epState,
                            horizontalArrangement = Arrangement.spacedBy(D.RowGap),
                            contentPadding = PaddingValues(vertical = 10.dp),
                        ) {
                            items(episodes, key = { it.id }) { file ->
                                val isCurrent = record?.fileId == file.id
                                EpisodePosterCard(
                                    // 电影没有集号，角标留空；显示 E01 会让人以为这是剧集
                                    code = if (isMovie) null else episodeCode(file, seasons.size),
                                    title = if (isMovie) {
                                        entry.displayTitle
                                    } else {
                                        file.episodeTitle?.takeIf { it.isNotBlank() }
                                            ?: "第 ${file.episodeNumber} 集"
                                    },
                                    // 没有单集剧照时退回横版剧照：至少能认出是哪部剧，
                                    // 用竖版海报会被 16:9 裁掉大半个封面
                                    stillUrl = file.stillUrl ?: entry.backdropUrl,
                                    watched = isCurrent && record.progress >= 0.95f,
                                    progress = if (isCurrent) record.progress else 0f,
                                    onClick = { onPlay(entry, file) },
                                )
                            }
                        }
                    }
                }

                // 参演艺人：放在最下方，横向一排圆形头像。
                // 用人名做筛选键（而不是 TMDB 人物 id）：同一演员在不同剧里可能是不同 id
                // （剧集与电影分属两套 credits），按名字合并才能把「我库里他演的片」凑齐。
                if (entry.cast.isNotEmpty()) {
                    item("cast") {
                        Column(modifier = Modifier.padding(top = D.Xl)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                TvText("参演", style = T.Title1, color = C.Text1)
                                HSpace(D.Lg)
                                TvText(
                                    text = "共 ${entry.cast.size} 位 · 点开看TA的其它作品",
                                    style = T.Caption,
                                    color = C.Text3,
                                    maxLines = 1,
                                )
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(D.Lg),
                                contentPadding = PaddingValues(vertical = 10.dp),
                            ) {
                                items(entry.cast, key = { it.name }) { member ->
                                    CastCard(
                                        member = member,
                                        onClick = { onOpenCast(member.name) },
                                    )
                                }
                            }
                        }
                    }
                }

                item("footer") {
                    TvText(
                        text = "已识别 ${entry.files.size} 个文件" +
                            (entry.scrapeSource?.let { " · 数据源 $it" } ?: ""),
                        style = T.Caption,
                        color = C.Text3,
                        maxLines = 1,
                    )
                    // 源文件清单：刮削失败/错配时按这里的路径去改文件名或目录名。
                    // file.path 是「扫描根」下的完整相对路径（含文件名）；share 为共享名
                    // （WebDAV 下恒为空，不拼前导斜杠）。文件名原样，不做任何清洗。
                    entry.files.forEach { file ->
                        val sourcePath = if (file.share.isBlank()) file.path else "${file.share}/${file.path}"
                        TvText(
                            text = sourcePath,
                            style = T.Micro,
                            color = C.Text3.copy(alpha = 0.8f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 续播目标：上次看的文件；没有记录就取第一集/正片。 */
private fun resumeFileOf(entry: LibraryEntry, record: PlaybackRecord?): MediaFile? =
    record?.let { rec -> entry.files.firstOrNull { it.id == rec.fileId } }
        ?: entry.primaryFile
        ?: entry.files.firstOrNull()

/**
 * 集号角标：单季 `E03`，多季 `S2E03`。
 *
 * 多季合并成一个条目后，只写 `E03` 会出现两张卡都叫 E03（第 1 季第 3 集、第 2 季第 3 集），
 * 所以只要存在多季就必须带季号。
 */
private fun episodeCode(file: MediaFile, seasonCount: Int): String {
    val ep = file.episodeNumber.toString().padStart(2, '0')
    return if (seasonCount > 1 || file.seasonNumber > 1) "S${file.seasonNumber}E$ep" else "E$ep"
}

@Composable
private fun Poster(entry: LibraryEntry) {
    Box(
        modifier = Modifier
            .width(148.dp)
            .height(222.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(C.placeholder(entry.id)),
    ) {
        if (!entry.posterUrl.isNullOrBlank()) {
            AsyncImage(
                model = entry.posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_library),
                    contentDescription = null,
                    tint = C.Text3,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoColumn(
    entry: LibraryEntry,
    record: PlaybackRecord?,
    resumeLabel: String,
    onPlay: () -> Unit,
    onRescrape: () -> Unit,
    onBack: () -> Unit,
    onOpenGenre: (String) -> Unit,
) {
    // 进详情页焦点直接落到播放按钮：这是用户最可能的下一步动作，
    // 让遥控器多按几次才到播放，比滚动列表更违背直觉。
    // 头部不参与滚动，所以这里聚焦不会把上面的内容顶出屏幕。
    val playFocus = remember(entry.id) { FocusRequester() }
    LaunchedEffect(entry.id) {
        kotlinx.coroutines.delay(80)
        runCatching { playFocus.requestFocus() }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        TvText(
            text = entry.displayTitle,
            style = T.Headline,
            color = C.Text1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        entry.originalTitle?.takeIf { it.isNotBlank() && it != entry.displayTitle }?.let {
            VSpace(D.Xs)
            TvText(it, style = T.Body2, color = C.Text3, maxLines = 1)
        }

        VSpace(D.Md)

        // 元信息一行：年份 · 时长/集数 · 分类
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(D.Lg),
        ) {
            entry.year?.let { MetaText(it.toString()) }
            MetaText(
                if (entry.kind == MediaKind.MOVIE) {
                    entry.runtimeMin?.let { "$it 分钟" } ?: "电影"
                } else {
                    // 「共 N 集」取数据源的全剧总集数（TMDB/豆瓣），「库里 M 集」是本地实际文件数。
                    // 两者放一起，缺多少集一眼可见。总集数还没刮到时只报「库里 M 集」，
                    // 绝不退化成「共 X 季」——季数不能替代集数信息，会让用户误以为统计的是集。
                    val inLibrary = entry.episodeCount
                    val total = entry.totalEpisodes?.takeIf { it > 0 }
                    val multiSeason = entry.seasons.size > 1
                    when {
                        total != null && multiSeason ->
                            "共 $total 集 · 库里 $inLibrary 集 · ${entry.seasons.size} 季"
                        total != null -> "共 $total 集 · 库里 $inLibrary 集"
                        multiSeason -> "库里 $inLibrary 集 · ${entry.seasons.size} 季"
                        else -> "库里 $inLibrary 集"
                    }
                },
            )
            entry.rating?.takeIf { it > 0 }?.let { score ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(D.Xs),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_heart),
                        contentDescription = null,
                        tint = C.Gold,
                        modifier = Modifier.size(11.dp),
                    )
                    TvText(
                        text = fmtScore(score),
                        style = T.Label.copy(fontWeight = FontWeight(700)),
                        color = C.Gold,
                    )
                }
            }
            if (!entry.scraped) {
                Box(
                    modifier = Modifier
                        .background(C.Gold.copy(alpha = 0.16f), RoundedCornerShape(D.RadiusTag))
                        .padding(horizontal = D.Sm, vertical = 2.dp),
                ) {
                    TvText("未刮削", style = T.Micro, color = C.Gold)
                }
            }
        }

        // 类型标签：刮削时按数据源的类型生成（科幻 / 奇幻 / 爱情 …）。
        // 用 FlowRow 换行而不是固定一行取前 4 个：类型多的时候之前直接裁掉，
        // 「科幻奇幻冒险」全被砍成一个「科幻」，等于白刮。最多两行，避免把固定头部撑高。
        //
        // 标签可点：点「科幻」进同类型海报墙。这里必须真的可聚焦（focusRing），
        // 只加 clickable 在遥控器上永远按不到。
        if (entry.genres.isNotEmpty()) {
            VSpace(D.Md)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(D.Md),
                verticalArrangement = Arrangement.spacedBy(D.Sm),
                maxLines = 2,
                modifier = Modifier.fillMaxWidth(),
            ) {
                entry.genres.forEach { genre ->
                    Box(
                        modifier = Modifier
                            .background(C.Glass, CircleShape)
                            .focusRing(
                                onClick = { onOpenGenre(genre) },
                                scale = 1.05f,
                                shape = CircleShape,
                            )
                            .padding(horizontal = D.Lg, vertical = 3.dp),
                    ) {
                        TvText(genre, style = T.Caption, color = C.Text1, maxLines = 1)
                    }
                }
            }
        }

        VSpace(D.Xl)

        Row(horizontalArrangement = Arrangement.spacedBy(D.Lg)) {
            TvButton(
                onClick = onPlay,
                label = resumeLabel,
                icon = if (record != null && !record.finished) R.drawable.ic_replay else R.drawable.ic_play,
                variant = TvButtonVariant.Primary,
                modifier = Modifier.focusRequester(playFocus),
            )
            TvButton(
                onClick = onRescrape,
                label = if (entry.scraped) "重新刮削" else "刮削",
                icon = R.drawable.ic_scrape,
                variant = TvButtonVariant.Ghost,
            )
            TvButton(
                onClick = onBack,
                label = "返回",
                icon = R.drawable.ic_chevron_left,
                variant = TvButtonVariant.Ghost,
            )
        }
    }
}

@Composable
private fun MetaText(text: String) {
    TvText(text, style = T.Label, color = C.Text2, maxLines = 1)
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
