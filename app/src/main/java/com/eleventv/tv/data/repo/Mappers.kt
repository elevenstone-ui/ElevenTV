package com.eleventv.tv.data.repo

import com.eleventv.tv.data.model.ImageSet
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.MediaFile
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.model.PlaybackHint
import com.eleventv.tv.data.model.PlaybackRecord
import com.eleventv.tv.data.model.PosterBadge
import com.eleventv.tv.data.model.RemoteImage
import com.eleventv.tv.data.model.TitleSummary
import com.eleventv.tv.data.scan.FilenameParser

/**
 * 本地实体 → 契约结构。
 *
 * 页面统一用 [TitleSummary] 渲染，不直接碰 [LibraryEntry]，
 * 这样以后换成真服务端下发数据时，UI 一行都不用改。
 */
fun LibraryEntry.toSummary(record: PlaybackRecord? = null): TitleSummary {
    val progressValue = record?.takeIf { it.positionMs > 0 }?.progress
    val currentFile = record?.let { rec -> files.firstOrNull { it.id == rec.fileId } }

    return TitleSummary(
        id = id,
        title = displayTitle,
        subtitle = subtitleText(),
        images = ImageSet(
            poster = posterUrl?.let { RemoteImage(it, 500, 750) },
            backdrop = backdropUrl?.let { RemoteImage(it, 1280, 720) },
        ),
        rating = rating,
        badges = badgeList(),
        progress = progressValue,
        kind = kind,
        playback = record?.let {
            PlaybackHint(
                episodeId = it.fileId,
                season = currentFile?.seasonNumber,
                episode = currentFile?.episodeNumber,
                positionMs = it.positionMs,
                durationMs = it.durationMs,
            )
        },
    )
}

/**
 * 电影显示年份，剧集显示「S1 · 12 集」。用 [LibraryEntry.kind] 而不是 effectiveKind：形态由扫描决定
 */
private fun LibraryEntry.subtitleText(): String = when {
    kind == MediaKind.MOVIE -> listOfNotNull(year?.toString(), runtimeMin?.let { "${it} 分钟" })
        .joinToString(" · ")
        .ifBlank { "电影" }

    seasons.size > 1 -> "共 ${seasons.size} 季 · ${files.size} 集"
    // 只下了最新一季（比如只有第 3 季）时，写死「第 1 季」是错的
    else -> "第 ${seasons.firstOrNull() ?: 1} 季 · ${files.size} 集"
}

/**
 * 海报左上角角标：分辨率 + 动态范围，两者都有就并排显示。
 *
 * 动态范围取所有文件里最好的那个（DV > HDR10+ > HDR10 > HDR）：同一部剧各集基本同规格，
 * 取最大值不会误导，也不会因为 S1 是 SDR、S2 是 HDR 就一个角标都不给。
 * 具体画成什么（矢量标识还是文字）由卡片决定，这里只给语义。
 */
private fun LibraryEntry.badgeList(): List<PosterBadge> = buildList {
    val quality = files.mapNotNull { it.quality ?: FilenameParser.qualityOf(it.fileName) }
    if (quality.any { it == "4K" }) add(PosterBadge.UHD)

    val dynamic = files.mapNotNull { FilenameParser.dynamicRangeOf(it.fileName) }
        .minByOrNull { DYNAMIC_PRIORITY.indexOf(it).takeIf { i -> i >= 0 } ?: DYNAMIC_PRIORITY.size }
        ?: tags.firstOrNull { it.contains("HDR", ignoreCase = true) }?.let { "HDR" }

    when (dynamic) {
        "DV" -> add(PosterBadge.DOLBY_VISION)
        null -> Unit
        else -> add(PosterBadge.HDR)
    }

    // 动漫角标只在没有任何技术角标时出现：两个角标已经占满 120dp 的卡宽
    if (isEmpty() && effectiveKind == MediaKind.ANIME) add(PosterBadge.ANIME)
}

/** 动态范围优先级，越靠前越好 */
private val DYNAMIC_PRIORITY = listOf("DV", "HDR10+", "HDR10", "HDR")

/**
 * 纯集号角标（不含单集标题）：单季 `E3`，多季 `S2E3`。
 *
 * 播放页顶栏第一行已经把单集标题并排在剧名后面，第二行再走 [episodeLabel]
 * 会带出「E3 · 单集标题」，单集标题显示两遍。第二行只保留集号即可。
 * 集号格式与 [episodeLabel] 完全一致（`S${seasonNumber}E${episodeNumber}` 紧凑）。
 */
fun MediaFile.episodeNumberLabel(): String =
    if (seasonNumber > 1) "S${seasonNumber}E$episodeNumber" else "E$episodeNumber"

/**
 * 单集 → 选集卡片标题。
 *
 * 电影必须传 `isMovie = true`：`MediaFile` 的季/集号默认是 1，不传就会给电影
 * 打出「E1」这种集号角标（用户反馈过「电影怎么变成第 1 集了」）。
 */
fun MediaFile.episodeLabel(isMovie: Boolean = false): String {
    if (isMovie) return episodeTitle?.takeIf { it.isNotBlank() } ?: ""
    val number = if (seasonNumber > 1) "S${seasonNumber}E$episodeNumber" else "E$episodeNumber"
    val title = episodeTitle?.takeIf { it.isNotBlank() }
    return if (title != null) "$number · $title" else number
}
