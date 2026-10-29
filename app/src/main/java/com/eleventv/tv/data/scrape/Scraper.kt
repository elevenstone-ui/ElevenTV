package com.eleventv.tv.data.scrape

import android.util.Log
import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.scan.FilenameParser
import java.util.concurrent.ConcurrentHashMap

/**
 * 刮削编排。
 *
 * 顺序：TMDB（结构主源）→ 豆瓣（中文补充）→ fanart.tv（背景图补充）。
 *
 * ## 为什么 TMDB 必须是主源
 *
 * 只有它同时提供「季简介 + 单集简介 + 单集剧照 + 单集评分」。豆瓣与 IMDb 只能补
 * 标题与评分；fanart 只补图。所以 TMDB 失败时才降级到豆瓣单源（此时没有分集元数据，
 * 分集标题回落到文件名）。
 *
 * ## 为什么要有「搜索词阶梯 + 候选重试」
 *
 * 一次搜索没命中有很多种原因：文件名带的年份是单集年、媒体类型被判错、标题里混着
 * 季号、TMDB 命中但详情接口抖了一下。任何一条都足以让整部剧刮成空白。
 * 所以这里逐级退化：换搜索词 → 换候选 → 详情失败用搜索结果兜底。
 * 宁可少几个字段，也不要整条空着——海报和评分是用户第一眼看到的。
 *
 * ## 为什么每个源都吞异常
 *
 * 刮削是「尽力而为」：一条刮不到不该让整次批量刮削中断，也不该弹错误给用户。
 */
class Scraper(private val settings: AppSettings) {

    /** 本次刮削的失败原因（entryId → 人话原因），供任务栏汇总展示 */
    val failures = ConcurrentHashMap<String, String>()

    suspend fun scrape(entry: LibraryEntry): LibraryEntry {
        if (!settings.scrapeEnabled) return entry

        val queries = searchQueries(entry)
        if (queries.isEmpty()) {
            failures[entry.id] = "文件名里没有可用作搜索的标题"
            return entry
        }

        val seasons = if (entry.kind == MediaKind.MOVIE) {
            emptyList()
        } else {
            entry.seasons.ifEmpty { listOf(1) }
        }

        var reason: String? = null

        for (query in queries) {
            val hits = runCatching {
                TmdbSource.search(query, entry.year, entry.kind, settings)
            }.getOrNull().orEmpty()

            if (hits.isEmpty()) {
                val err = TmdbSource.lastError()
                reason = err?.let { "网络/凭证问题：$it" } ?: "TMDB 搜不到「$query」"
                // 网络层问题换搜索词也没用，别把每个搜索词都拖一个超时
                if (err != null) break
                continue
            }

            val ranked = rank(hits, query, entry.year)

            // 候选重试：第一个候选的详情失败（下架、接口抖动、id 失效）不代表整条没救
            for (hit in ranked.take(3)) {
                val detail = runCatching {
                    TmdbSource.detail(hit, entry.kind, settings, seasons)
                }.getOrNull()
                if (detail != null) {
                    return applyResult(entry, enrich(entry, detail, query))
                }
                reason = "详情拉取失败（${hit.title}）"
            }

            // 详情全失败：用搜索结果自带的海报/评分/年份兜底，别让整条空着
            val fallback = fromHit(ranked.first())
            if (fallback.posterUrl != null || fallback.rating != null) {
                Log.i(TAG, "详情失败，改用搜索结果兜底：$query → ${ranked.first().title}")
                return applyResult(entry, fallback)
            }

            reason = "TMDB 命中「${ranked.first().title}」但没有海报或评分"
            break
        }

        failures[entry.id] = reason ?: "未命中"

        // TMDB 完全没救时才降级豆瓣：它只能给中文标题/简介/评分，没有分集元数据
        val doubanQuery = queries.first()
        Log.i(TAG, "TMDB 未命中（$reason），改用豆瓣：$doubanQuery")
        val douban = runCatching { DoubanSource.searchAndFetch(doubanQuery, entry.year) }.getOrNull()
        return if (douban != null) {
            failures.remove(entry.id)
            applyResult(entry, douban)
        } else {
            entry
        }
    }

    /**
     * 搜索词阶梯。
     *
     * 先用清洗版（去掉分辨率/编码/发布组/季号/年份，命中最准），再退到原始标题。
     * 最后补一个「标题里的中文段」——中英混排成「中文 英文原名」时，中文段单独就能搜到。
     *
     * 第三级**只在中英混排时启用**：以前取「最长的那个单词」，于是
     * `The Lord of the Rings` 会被切成 `Rings`（搜到 2017 年那部恐怖片）、
     * `The Office` 切成 `Office`。命中率是上去了，错配也跟着上来了。
     */
    private fun searchQueries(entry: LibraryEntry): List<String> {
        val raw = entry.rawTitle.ifBlank {
            entry.files.firstOrNull()?.fileName?.substringBeforeLast('.') ?: ""
        }.trim()
        if (raw.isBlank()) return emptyList()

        val out = LinkedHashSet<String>()
        cleanQuery(raw).takeIf { it.length >= 2 && it != raw }?.let { out += it }
        out += raw
        CJK_RUN.findAll(raw).maxByOrNull { it.value.length }?.value
            ?.takeIf { it.length >= 2 && it != raw }
            ?.let { out += it }

        return out.toList()
    }

    private fun cleanQuery(raw: String): String =
        FilenameParser.cleanTitle(raw)
            .replace(YEAR_TOKEN, " ")
            .replace(SEASON_TOKEN, " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '.', '_', '·')

    /**
     * 排序：标题完全相等 > 年份接近 > 标题包含 > 热度。
     *
     * 年份接近必须排在热度前面：清洗掉年份后「Blade Runner 2049」会退化成
     * 「Blade Runner」，只按热度会挑回 1982 年那部。
     */
    private fun rank(hits: List<ScrapeHit>, query: String, year: Int?): List<ScrapeHit> {
        val q = query.squash()
        return hits.sortedWith(
            compareByDescending<ScrapeHit> { it.title.squash() == q || it.originalTitle?.squash() == q }
                .thenByDescending { h -> year != null && h.year != null && kotlin.math.abs(h.year - year) <= 1 }
                .thenByDescending { it.title.squash().contains(q) || q.contains(it.title.squash()) }
                .thenByDescending { it.popularity },
        )
    }

    private fun String.squash(): String =
        lowercase().replace(Regex("""[\s._\-:：·'’!！?？,，]"""), "")

    /** 详情拿到后补齐中文标题/简介与背景图。 */
    private suspend fun enrich(entry: LibraryEntry, detail: ScrapeResult, query: String): ScrapeResult {
        var result = detail

        // 中文缺失时用豆瓣补标题 / 简介
        if (result.overview.isNullOrBlank() || result.title == result.originalTitle) {
            runCatching { DoubanSource.searchAndFetch(query, entry.year) }.getOrNull()?.let { db ->
                if (result.overview.isNullOrBlank() && !db.overview.isNullOrBlank()) {
                    result = result.copy(overview = db.overview)
                }
                if (result.rating == null) result = result.copy(rating = db.rating)
                if (db.title != null && settings.preferChineseTitle) {
                    result = result.copy(title = db.title)
                }
            }
        }

        // 评分缺失不再用 IMDb 兜底：suggestion 接口没有评分字段，之前把
        // 「热度排名 rank/10」当分数展示是假数据；等有可靠评分源再接回来

        // 背景图缺失时用 fanart
        if (result.backdropUrl.isNullOrBlank() && settings.scrapeBackdrop) {
            val key = settings.fanartApiKey.ifBlank { com.eleventv.tv.BuildConfig.FANART_API_KEY }
            result.tmdbId?.let { id ->
                runCatching {
                    FanartSource.backdrop(id, entry.kind == MediaKind.MOVIE, key)
                }.getOrNull()?.let { result = result.copy(backdropUrl = it) }
            }
        }

        return result
    }

    /** 详情接口失败时的兜底：搜索结果里本来就有标题、年份、海报、均分。 */
    private fun fromHit(hit: ScrapeHit): ScrapeResult = ScrapeResult(
        title = hit.title,
        originalTitle = hit.originalTitle,
        year = hit.year,
        rating = hit.voteAverage?.takeIf { it > 0 },
        posterUrl = hit.posterUrl,
        tmdbId = hit.id,
        imdbId = hit.imdbId,
        source = "TMDB",
    )

    /** 把刮削结果写回条目：只填空，不覆盖已有值（手改过的元数据不该被冲掉）。 */
    private fun applyResult(entry: LibraryEntry, r: ScrapeResult): LibraryEntry {
        val files = entry.files.map { file ->
            if (!settings.scrapeEpisodeOverview) return@map file
            val scraped = r.episodes["${file.seasonNumber}-${file.episodeNumber}"] ?: return@map file
            file.copy(
                episodeTitle = scraped.title ?: file.episodeTitle,
                episodeOverview = scraped.overview ?: file.episodeOverview,
                stillUrl = scraped.stillUrl ?: file.stillUrl,
                rating = scraped.rating ?: file.rating,
            )
        }

        return entry.copy(
            title = r.title ?: entry.title,
            originalTitle = r.originalTitle ?: entry.originalTitle,
            // 年份以刮削结果为准：扫描只能从文件名/目录名猜，`Blade Runner 2049` 会被猜成
            // 2049、目录写错年份更是常事。以前用 `entry.year ?: r.year`，于是这些错误年份
            // 永远覆盖不掉，详情页一直显示错的。
            year = r.year ?: entry.year,
            overview = r.overview ?: entry.overview,
            rating = r.rating ?: entry.rating,
            posterUrl = if (settings.scrapePoster) r.posterUrl ?: entry.posterUrl else entry.posterUrl,
            backdropUrl = if (settings.scrapeBackdrop) r.backdropUrl ?: entry.backdropUrl else entry.backdropUrl,
            genres = r.genres.ifEmpty { entry.genres },
            tags = r.tags.ifEmpty { entry.tags },
            cast = r.cast.ifEmpty { entry.cast },
            runtimeMin = r.runtimeMin ?: entry.runtimeMin,
            tmdbId = r.tmdbId ?: entry.tmdbId,
            imdbId = r.imdbId ?: entry.imdbId,
            doubanId = r.doubanId ?: entry.doubanId,
            seasonOverviews = if (settings.scrapeSeasonOverview) {
                r.seasonOverviews.ifEmpty { entry.seasonOverviews }
            } else {
                entry.seasonOverviews
            },
            seasonPosters = r.seasonPosters.ifEmpty { entry.seasonPosters },
            files = files,
            scrapedAt = System.currentTimeMillis(),
            scrapeSource = r.source,
        )
    }

    private companion object {
        const val TAG = "ElevenTV"

        private val YEAR_TOKEN = Regex("""(?<![0-9])(?:19|20)\d{2}(?![0-9])""")
        private val SEASON_TOKEN = Regex(
            """(?i)(第\s*[0-9一二三四五六七八九十]+\s*[季部]|season\s*\d{1,2}|complete|合集)""",
        )

        /** 标题里的连续中文段，用于「中文 + 英文原名」这种命名的退化搜索 */
        private val CJK_RUN = Regex("""[\u4e00-\u9fff·]{2,}""")
    }
}
