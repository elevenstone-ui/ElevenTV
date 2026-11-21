package com.eleventv.tv.data.scrape

import android.util.Log
import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.scan.FilenameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            val outcome = runCatching {
                TmdbSource.search(query, entry.year, entry.kind, settings)
            }.getOrNull() ?: SearchOutcome(
                hits = emptyList(), requestFailed = true, error = "TMDB 请求异常",
            )
            val hits = outcome.hits

            if (hits.isEmpty()) {
                // 判据来自「本次搜索是否请求失败」，不再是 TmdbSource.lastError()。
                // 那个全局字段会被并发协程的 2xx 冲成 null，401/429/连不上因此
                // 全被误报成「检查网络」，整库失败时用户根本不知道该修什么。
                reason = if (outcome.requestFailed) {
                    "网络/凭证问题：${outcome.error}"
                } else {
                    "TMDB 搜不到「$query」"
                }
                // 网络层问题换搜索词也没用，别把每个搜索词都拖一个超时
                if (outcome.requestFailed) break
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
                // 把数据源记录的原因带上（429 / 401 / 连不上 / 解析失败）。
                // 只写片名的话，清单里 71 条全是「详情拉取失败」，仍然分不出
                // 「配额耗尽」和「接口抖动」，只能继续靠猜。
                reason = "详情拉取失败（${hit.title}）：${TmdbSource.lastError() ?: "数据源未给出原因"}"
            }

            // 详情全失败：用搜索结果自带的海报/评分/年份兜底，别让整条空着
            val fallback = fromHit(ranked.first())
            if (fallback.posterUrl != null || fallback.rating != null) {
                Log.i(TAG, "详情失败，改用搜索结果兜底：$query → ${ranked.first().title}")
                return applyResult(entry, fallback)
            }

            // 这个搜索词命中了却拿不到可用字段，不代表换个词也拿不到。
            // 以前直接 break，等于把「命中但无海报」当成整条放弃，连豆瓣降级都不给机会。
            reason = "TMDB 命中「${ranked.first().title}」但没有海报或评分"
            continue
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
     * 七级退化，命中率优先但每一级都可能赔上错配，顺序不能乱：
     * 1) 清洗版（去分辨率/编码/发布组/季号/年份/片源标记，最准，也是豆瓣降级用的词）；
     * 2) 主片名（截到第一个年份或片源标记之前，救 `片名.2026.AMZN.KyoGo` 这类）；
     * 3) 原始标题；
     * 4) 标题里的中文段——中英混排成「中文 英文原名」时，中文段单独就能搜到；
     * 5) 剧集尾集号（`Shingeki no Kyojin 67` → `Shingeki no Kyojin`，仅 SERIES）；
     * 6) 括号内标题段（`[压制组][Kimetsu no Yaiba][30]` → `Kimetsu no Yaiba`，
     *    整串关在方括号里的动漫命名）；
     * 7) 目录名（从文件路径现算，只在前面全落空时兜底；散放/容器/季目录时无此级）。
     *
     * 中文段是**整段连续中文**而非「最长的那个单词」：以前取最长单词，于是
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
        // 主片名：NAS 命名常把「年份 + 片源 + 压制组」一股脑跟在片名后面，
        // 例如 `Resident.Evil.2026.AMZN.KyoGo` → cleanQuery 剥掉年份后仍带着
        // AMZN / KyoGo，TMDB 短语搜整串必然 0 命中，豆瓣也搜不到 → 成片失败。
        // 截到第一个年份或片源标记之前，只留「Resident Evil」再搜一次。
        // 这是**额外一级**，前面的清洗版与后面的原始标题都照旧，不会让任一命名变差。
        primaryTitle(raw)
            .takeIf { it.length >= 3 && it.any { c -> c.isLetter() } && it !in out }
            ?.let { out += it }
        out += raw
        CJK_RUN.findAll(raw).maxByOrNull { it.value.length }?.value
            ?.takeIf { it.length >= 2 && it != raw }
            ?.let { out += it }
        // 剧集标题尾巴挂集号：`Shingeki no Kyojin 67`（数字集号目录统计失败时
        // rawTitle 会带着集号）搜 `search/tv` 必然 0 命中。剥掉结尾数字再搜一次。
        // 只剥「1–3 位 + 前置分隔」，Blade Runner 2049 这类 4 位年份不受影响；
        // 电影不参与（它的尾部数字多是年份）。
        if (entry.kind != MediaKind.MOVIE) {
            TRAILING_EP.replace(raw.trim(), "")
                .trim(' ', '-', '.', '_', '·')
                .takeIf { it.length >= 3 && it.any { c -> c.isLetter() } && it !in out }
                ?.let { out += it }
        }
        // 括号内标题段：动漫「[压制组][片名][集号][规格]」整串关在方括号里，
        // 清洗版会把所有括号段一起剥掉、干净词落空。专挑「含空格的多词段」当标题
        // （`[Kimetsu no Yaiba]` → Kimetsu no Yaiba）；压制组（BeanSub&FZSD 含 &）、
        // 纯数字集号、1080P/x264.AAC 这类无空格规格段都不会被选中，误伤面极小。
        bracketTitle(raw)
            ?.takeIf { it.length >= 4 && it !in out }
            ?.let { out += it }
        // 目录名候选必须放最后：
        // 1) queries.first() 是豆瓣降级用的搜索词，必须是上面最干净的清洗版；
        // 2) 不少 NAS 的「每片一文件夹」目录名本身就是完整压制串
        //    （Black.Panther.2018.2160p.UHD.BluRay.x265-EMERALD），剥完仍带压制组名，
        //    比文件名还脏。放第 0 位会把豆瓣降级直接喂成 0 命中。
        // 只在其它词都落空时才拿它兜底；rawTitle 是扫描时算好的，这里从路径现算，
        // 不依赖重新扫描。散放/容器/季目录返回 null，自然回落。
        entry.files.firstOrNull()?.path
            ?.let { FilenameParser.directoryTitle(it, entry.files.size) }
            ?.takeIf { it.length >= 2 && it.any { c -> c.isLetter() } }
            ?.let { out += it }

        return out.toList()
    }

    /**
     * 从「[压制组][片名][集号][规格]」这类整串关在方括号里的命名里，挑出最可能是
     * 片名的那一格。判据：含空格的多词段（`[Kimetsu no Yaiba]` → Kimetsu no Yaiba），
     * 压制组（BeanSub&FZSD 带 &、无空格）、纯数字集号（[30]）、规格（[1080P]/[x264.AAC]）
     * 都不含空格，被天然排除。普通命名没有方括号时返回 null，零额外搜索请求。
     */
    private fun bracketTitle(raw: String): String? =
        BRACKET_SEG.findAll(raw)
            .map { it.value.substring(1, it.value.length - 1).trim() } // 剥掉首尾括号
            .filter { seg -> seg.contains(' ') && seg.any { c -> c.isLetter() } }
            .maxByOrNull { it.length }
            ?.takeIf { it.length >= 4 }

    /** 截到第一个年份/片源标记之前的主片名（压制组名无法穷举，靠截断兜住） */
    private fun primaryTitle(raw: String): String {
        val cutAt = (YEAR_TOKEN.findAll(raw) + SOURCE_TAG.findAll(raw))
            .minOfOrNull { it.range.first } ?: return ""
        return cleanQuery(raw.substring(0, cutAt))
    }

    private fun cleanQuery(raw: String): String =
        FilenameParser.cleanTitle(raw)
            .replace(YEAR_TOKEN, " ")
            .replace(SEASON_TOKEN, " ")
            .replace(SOURCE_TAG, " ")
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

    /** 详情拿到后补齐中文标题/简介与背景图。两个补充源互不依赖，并行发起。 */
    private suspend fun enrich(entry: LibraryEntry, detail: ScrapeResult, query: String): ScrapeResult =
        coroutineScope {
            var result = detail

            // 中文缺失时用豆瓣补标题 / 简介；开了 preferDoubanPoster 时还要拿豆瓣海报——
            // 国内部分网络对 image.tmdb.org 做 SNI 阻断（DNS 正常但 TLS 握手被掐），
            // TMDB 海报 URL 明明写进库却全加载不出来，而豆瓣图片 CDN 实测可达。
            val needDouban = result.overview.isNullOrBlank() ||
                result.title == result.originalTitle ||
                settings.preferDoubanPoster
            val douban = if (needDouban) {
                async(Dispatchers.IO) {
                    runCatching { DoubanSource.searchAndFetch(query, entry.year) }.getOrNull()
                }
            } else null

            // 背景图缺失时用 fanart
            val fanart = if (result.backdropUrl.isNullOrBlank() && settings.scrapeBackdrop) {
                val key = settings.fanartApiKey.ifBlank { com.eleventv.tv.BuildConfig.FANART_API_KEY }
                result.tmdbId?.let { id ->
                    async(Dispatchers.IO) {
                        runCatching {
                            FanartSource.backdrop(id, entry.kind == MediaKind.MOVIE, key)
                        }.getOrNull()
                    }
                }
            } else null

            douban?.await()?.let { db ->
                if (result.overview.isNullOrBlank() && !db.overview.isNullOrBlank()) {
                    result = result.copy(overview = db.overview)
                }
                if (result.rating == null) result = result.copy(rating = db.rating)
                if (db.title != null && settings.preferChineseTitle) {
                    result = result.copy(title = db.title)
                }
                // 豆瓣海报优先：TMDB 的 image.tmdb.org 在国内可能被 SNI 阻断，
                // 明明写进库却加载不出来。豆瓣 img*.doubanio.com 实测可达，
                // 这里直接覆盖（applyResult 是「只填空」，靠它补不上已有值）。
                // 关掉开关、或豆瓣没搜到时，保留 TMDB 海报走反代/直连。
                if (settings.preferDoubanPoster && !db.posterUrl.isNullOrBlank()) {
                    result = result.copy(posterUrl = db.posterUrl)
                }
            }

            // 评分缺失不再用 IMDb 兜底：suggestion 接口没有评分字段，之前把
            // 「热度排名 rank/10」当分数展示是假数据；等有可靠评分源再接回来

            fanart?.await()?.let { result = result.copy(backdropUrl = it) }
            result
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
            // 总集数：TMDB 给得出，豆瓣常常给不出；拿不到时保留上一次刮削到的值，
            // 别因为一次豆瓣降级就把已经显示的「共 N 集」抹掉。
            totalEpisodes = r.totalEpisodes ?: entry.totalEpisodes,
            // 每季集数：同 totalEpisodes 的语义——本次刮到就覆盖，没刮到（豆瓣源、或两季级
            // 开关都关导致没请求季详情）就保留上一次的值，不抹掉已有的按季统计。
            // 用 Map 的 `+`（右侧优先）合并：entry 旧值补缺，r 新值覆盖。
            seasonEpisodeCounts = entry.seasonEpisodeCounts + r.seasonEpisodeCounts,
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

        /**
         * 片源/流媒体平台标记。只列不会出现在片名里的明确缩写，且要求词边界。
         * 压制组名（KyoGo、RARBG 之类）无法穷举，交给 [primaryTitle] 的截断处理。
         * 注意：这些词只用于「生成搜索词」，绝不参与 mergeKey / stableId。
         */
        private val SOURCE_TAG = Regex(
            """(?i)\b(amzn|amazon|nf|netflix|dsnp|dsny|hmax|hbomax|atvp|atvplus|""" +
                """pcok|peacock|hulu|stan|crav|bili|iqiyi|youku|tencent|""" +
                """web-?dl|webrip|bdrip|brrip|bluray|blu-ray|remux|hdtv|dvdrip|hdrip)\b""",
        )

        /** 标题里的连续中文段，用于「中文 + 英文原名」这种命名的退化搜索 */
        private val CJK_RUN = Regex("""[\u4e00-\u9fff·]{2,}""")

        /**
         * 剧集标题尾巴上挂着的集号：`Shingeki no Kyojin 67`、`X 1x02`。
         * 只吃「结尾 1–3 位数字」且前面有分隔；`Blade Runner 2049` 的 2049 是 4 位，
         * 不会被误当集号剥掉。仅 SERIES 使用（电影标题尾部数字多为年份，不能剥）。
         */
        private val TRAILING_EP = Regex("""\s+[._-]*\d{1,3}$""")

        /** 方括号里的单个分段（不跨括号）：`[Kimetsu no Yaiba]` 取 `Kimetsu no Yaiba` */
        private val BRACKET_SEG = Regex("""[（(\[【][^（）(\)\]】]+[）)\]】]""")
    }
}
