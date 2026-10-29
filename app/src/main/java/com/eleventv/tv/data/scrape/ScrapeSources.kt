package com.eleventv.tv.data.scrape

import android.util.Base64
import android.util.Log
import com.eleventv.tv.BuildConfig
import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.CastMember
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.scrape.Http.arr
import com.eleventv.tv.data.scrape.Http.dbl
import com.eleventv.tv.data.scrape.Http.int
import com.eleventv.tv.data.scrape.Http.obj
import com.eleventv.tv.data.scrape.Http.str
import kotlinx.serialization.json.JsonObject

/** 一条刮削到的元数据。 */
data class ScrapeResult(
    val title: String? = null,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /** 参演艺人（按演职员表顺序，最多 [TmdbSource.MAX_CAST] 人） */
    val cast: List<CastMember> = emptyList(),
    val runtimeMin: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val doubanId: String? = null,
    /** 季号 → 季简介 */
    val seasonOverviews: Map<Int, String> = emptyMap(),
    val seasonPosters: Map<Int, String> = emptyMap(),
    /** "季-集" → 单集信息 */
    val episodes: Map<String, EpisodeScrape> = emptyMap(),
    val source: String = "",
)

data class EpisodeScrape(
    val title: String? = null,
    val overview: String? = null,
    val stillUrl: String? = null,
    val rating: Double? = null,
)

/** 搜索命中的候选 */
data class ScrapeHit(
    val id: Int,
    val imdbId: String? = null,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val posterUrl: String? = null,
    val voteAverage: Double? = null,
    val mediaType: String = "movie",
    val popularity: Double = 0.0,
)

private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

/**
 * TMDB（主源）。
 *
 * 提供：标题 / 原名 / 年份 / 简介 / 评分 / 海报 / 背景图 / 类型 / 片长 /
 * 季简介 / 季海报 / 单集标题 / 单集简介 / 单集剧照 / 单集评分。
 *
 * ## 认证
 *
 * 支持两种凭证：v3 `api_key`（查询参数）与 v4 Read Access Token（`Authorization: Bearer`）。
 * Token 必须**结构判定**（三段式 + 首段能解出 JSON 且含 `alg`），不能只看前缀——
 * 用户填进来的可能根本不是 JWT，那种情况必须回落 v3，否则一律 401。
 *
 * ## 域名（2026-10-28 实测结论）
 *
 * `api.themoviedb.org` 在大陆网络被 DNS 投毒，且**国内公共 DoH 的应答同样被污染**
 * （阿里 DoH 实测返回 Facebook 段伪造 IP）。官方备用域名 `api.tmdb.org` 走同一套
 * 服务与凭证，各 DoH 应答干净、直连可用（带真实 Key 实测 /search/movie 200），
 * 所以 API 一律先走 api.tmdb.org；网络层失败时再试 themoviedb.org 兜底。
 */
object TmdbSource {

    private val BASES = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3",
    )

    /** 最近一次 TMDB 请求失败的human-readable原因（供任务栏与网络诊断展示） */
    @Volatile
    private var lastErrorText: String? = null

    fun lastError(): String? = lastErrorText

    private fun isJwt(token: String): Boolean {
        val parts = token.split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return false
        return runCatching {
            val normalized = parts[0].replace('-', '+').replace('_', '/')
            val header = String(Base64.decode(normalized, Base64.DEFAULT), Charsets.UTF_8)
            header.trimStart().startsWith("{") && header.contains("\"alg\"")
        }.getOrDefault(false)
    }

    // ---- 凭证池（照搬 1.7.6 的 TmdbKeys：用户凭证优先，内置备用兜底，401/429 自动轮换） ----

    private class Cred(val value: String, val bearer: Boolean)

    /*
     * 这里曾经内置了 2 个 TMDB v3 Key + 1 个 v4 Read Token 当「备用凭证」。
     *
     * 已全部删除：源码里带 Key 等于把凭证随源码一起发出去（本项目的源码包就会外传），
     * 而凭证一泄露，轻则被别人用尽配额，重则整个账号被封。
     * 需要备用凭证时请通过 local.properties 的 `tmdb.apiKey` / `tmdb.readToken` 注入
     * （build.gradle.kts 会写进 BuildConfig），或让用户自己在设置页填写。
     */

    /**
     * 凭证的冷却表：value → 可再次使用的时间点。
     *
     * 以前 401 会把凭证塞进 `dead` 集合，而且是**进程级永久**的：用户在设置里把 Key 改对
     * 之后，只要进程还活着，这份 Key 就再也用不上了（甚至同一个 Key 改回正确值也一样），
     * 表现为「明明填对了还是刮不到」，必须杀进程重开。
     * 现在 401 也走冷却（30 分钟），429 冷却 60 秒——都是「过一会儿再试」的语义。
     */
    private val blockedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun creds(settings: AppSettings): List<Cred> {
        val list = mutableListOf<Cred>()
        val token = settings.tmdbReadToken.ifBlank { BuildConfig.TMDB_READ_TOKEN }
        if (token.isNotBlank() && isJwt(token)) list += Cred(token, bearer = true)
        val key = settings.tmdbApiKey.ifBlank { BuildConfig.TMDB_API_KEY }
        if (key.isNotBlank()) list += Cred(key, bearer = false)
        return list.distinctBy { it.value }
    }

    /** 判断某凭证当前是否可用（未拉黑、不在限流冷却期）。 */
    private fun usable(c: Cred): Boolean =
        (blockedUntil[c.value] ?: 0L) <= System.currentTimeMillis()

    /**
     * 发一次 TMDB GET：凭证按池序尝试；网络层失败时换备用域名再试一轮。
     *
     * - **401**：凭证当前不可用，冷却 30 分钟后可再用，同时换下一个凭证；
     * - **429**：临时限流，冷却 60 秒后换下一个；
     * - **网络失败（code 0）**：换凭证解决不了网络问题——先试下一个域名
     *   （api.tmdb.org 主用 / api.themoviedb.org 兜底），两个都断才失败；
     * - 其他非 2xx：记录后直接失败（多半是参数或服务端问题）。
     */
    private suspend fun tmdbGet(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
        settings: AppSettings,
    ): JsonObject? {
        val all = creds(settings)
        if (all.isEmpty()) {
            lastErrorText = "未配置 TMDB 凭证"
            return null
        }

        var lastReason: String? = null

        for (base in BASES) {
            var networkFail = false

            // 全都处于冷却时仍然兜底试一遍：宁可多一次 401，也不要「静默地一条都不试」
            for (cred in all.filter { usable(it) }.ifEmpty { all }) {
                val url = buildString {
                    append(base).append(path)
                    append("?language=zh-CN")
                    for ((k, v) in params) append('&').append(k).append('=').append(v)
                    if (!cred.bearer) append("&api_key=").append(cred.value)
                }
                val headers = if (cred.bearer) {
                    mapOf("Authorization" to "Bearer ${cred.value}", "Accept" to "application/json")
                } else {
                    mapOf("Accept" to "application/json")
                }

                val (code, body) = Http.getWithStatus(url, headers)
                when {
                    code in 200..299 -> {
                        lastErrorText = null
                        val parsed = body?.let {
                            runCatching { Http.json.parseToJsonElement(it) }.getOrNull()
                        }
                        return parsed?.obj() ?: run {
                            lastReason = "TMDB 返回了无法解析的内容"
                            null
                        }
                    }

                    code == 401 -> {
                        blockedUntil[cred.value] = System.currentTimeMillis() + AUTH_COOLDOWN_MS
                        lastReason = "TMDB 凭证无效（HTTP 401），已切换备用凭证"
                    }

                    code == 429 -> {
                        blockedUntil[cred.value] = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                        lastReason = "TMDB 请求过于频繁（HTTP 429），已切换备用凭证"
                    }

                    code == 0 -> {
                        // 网络层失败：换凭证无意义，换下一个域名试试
                        networkFail = true
                        lastReason = "连不上 TMDB（网络不通），可稍后重试或运行「网络诊断」"
                        break
                    }

                    else -> {
                        lastReason = "TMDB HTTP $code"
                        lastErrorText = lastReason
                        return null
                    }
                }
            }

            // 凭证原因失败（401/429 耗尽）换域名也没用，直接结束；只有网络断才换域名
            if (!networkFail) {
                lastErrorText = lastReason ?: "TMDB 无可用凭证"
                return null
            }
        }

        lastErrorText = lastReason ?: "TMDB 无可用凭证"
        return null
    }

    /**
     * 搜索。用 1.7.6 验证过的**分类端点**（search/movie、search/tv），
     * 不用 search/multi——multi 混入人物条目且年份参数行为不一致。
     * 动漫/综艺/纪录片在 TMDB 里都是 tv。
     *
     * ## 年份必须当「偏好」而不是「过滤」（2026-10-28 实测结论）
     *
     * TMDB 的 `year` / `first_air_date_year` 是**精确相等**过滤，不是「相近」：
     * - 《绝命毒师》带 `first_air_date_year=2008`（首播年）→ 1 条；带 `2013`（单集年）→ **0 条**；
     * - 《Inception》带 `year=2010` → 3 条；带 `2011`（差一年）→ **0 条**。
     *
     * 而剧集文件名里的年份基本都是单集播出年或压制年，跟首播年基本不可能一致，
     * 所以把年份当过滤条件会让**大部分剧集 0 命中**——这就是「部分能刮、大部分失败」的主因。
     *
     * 现在的顺序：带年份求精确 → 空了去年份重搜 → 还空就换端点（媒体类型判错时）。
     * 换端点的结果必须标题强匹配才采信，否则「三体」会在电影端点命中「我们的身体」。
     */
    suspend fun search(query: String, year: Int?, kind: MediaKind, settings: AppSettings): List<ScrapeHit> {
        val wantedMovie = kind == MediaKind.MOVIE

        // 1) 带年份精确搜索：命中则最准（电影尤其如此）
        if (year != null) {
            // 「连不上 / 401 / 429」不是「没搜到」，继续退化只会把同一个超时再等两遍，
            // 整库刮削能慢到不可用——直接放弃，让上层把真实原因显示出来。
            // 判据必须是「这次请求本身失败」（searchOnce 返回 null），不能看 lastErrorText：
            // 那是进程级共享字段，刮削有 3 个并发协程，别的条目一次成功就会把它清空，
            // 于是这里时灵时不灵，反过来也可能被别的条目的旧错误挡住、白丢一次重搜。
            val exact = searchOnce(query, year, wantedMovie, settings) ?: return emptyList()
            if (exact.isNotEmpty()) return exact
        }

        // 2) 去掉年份重搜：这一步救回绝大多数剧集
        val loose = searchOnce(query, null, wantedMovie, settings) ?: return emptyList()
        if (loose.isNotEmpty()) return loose

        // 3) 换端点：电影放进剧集库、剧集被当成电影都属于常见误判
        return (searchOnce(query, null, !wantedMovie, settings) ?: return emptyList())
            .filter { it.strongMatch(query) }
    }

    /** 标题强匹配（忽略空格与标点）：仅用于「换端点」这种高风险兜底。 */
    private fun ScrapeHit.strongMatch(query: String): Boolean {
        val q = query.squash()
        if (q.length < 2) return false
        val t = title.squash()
        val o = originalTitle?.squash().orEmpty()
        if (t == q || o == q) return true
        if (t.length >= 2 && t.contains(q)) return true
        return o.length >= 2 && o.contains(q)
    }

    private fun String.squash(): String =
        lowercase().replace(Regex("""[\s._\-:：·'’!！?？,，]"""), "")

    /**
     * 单次搜索请求；`year` 为 null 时不带年份参数。
     *
     * @return 命中列表（可能为空 = 确实没搜到）；**null 表示请求本身失败**
     *   （未配置凭证 / 连不上 / 401 / 429 / 5xx），调用方据此决定要不要继续退化搜索
     */
    private suspend fun searchOnce(
        query: String,
        year: Int?,
        isMovie: Boolean,
        settings: AppSettings,
    ): List<ScrapeHit>? {
        val params = buildList {
            add("query" to query.enc())
            add("include_adult" to "false")
            if (year != null) {
                add(if (isMovie) "year" to "$year" else "first_air_date_year" to "$year")
            }
        }
        val body = tmdbGet(if (isMovie) "/search/movie" else "/search/tv", params, settings) ?: return null

        return body.arr("results").orEmpty().mapNotNull { item ->
            val o = item.obj() ?: return@mapNotNull null
            ScrapeHit(
                id = o.int("id") ?: return@mapNotNull null,
                title = o.str("title") ?: o.str("name") ?: return@mapNotNull null,
                originalTitle = o.str("original_title") ?: o.str("original_name"),
                year = (o.str("release_date") ?: o.str("first_air_date"))?.take(4)?.toIntOrNull(),
                posterUrl = o.str("poster_path")?.let { "$IMAGE_BASE/w342$it" },
                voteAverage = o.dbl("vote_average"),
                mediaType = if (isMovie) "movie" else "tv",
                popularity = o.dbl("popularity") ?: 0.0,
            )
        }.sortedByDescending { it.popularity }
    }

    /** 按 id 取详情；剧集会补齐季简介与单集信息。 */
    suspend fun detail(hit: ScrapeHit, kind: MediaKind, settings: AppSettings, seasons: List<Int>): ScrapeResult? {
        val isTv = hit.mediaType == "tv"
        val path = if (isTv) "/tv/${hit.id}" else "/movie/${hit.id}"
        // credits 走 append_to_response 一起取：多一次请求就要多一次握手，
        // 整库刮削时这一跳的代价比多解析几十行 JSON 大得多
        val o = tmdbGet(path, listOf("append_to_response" to "external_ids,credits"), settings) ?: return null

        val genres = o.arr("genres").orEmpty().mapNotNull { it.obj()?.str("name") }
        val cast = o.obj("credits")?.arr("cast").orEmpty()
            .mapNotNull { it.obj() }
            .mapNotNull { c ->
                val name = c.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CastMember(
                    name = name,
                    character = c.str("character")?.takeIf { it.isNotBlank() },
                    // w185 是 TMDB 的头像档位，96dp 圆卡在 1080p 电视上足够
                    profileUrl = c.str("profile_path")?.let { "$IMAGE_BASE/w185$it" },
                    personId = c.int("id"),
                )
            }
            .distinctBy { it.name }
            .take(MAX_CAST)
        val tags = buildList {
            o.obj("external_ids")?.str("imdb_id")?.let { add("IMDb $it") }
            o.dbl("vote_average")?.takeIf { it > 0 }?.let { add("TMDB ${oneDecimal(it)}") }
        }

        var result = ScrapeResult(
            title = o.str("name") ?: o.str("title"),
            originalTitle = o.str("original_name") ?: o.str("original_title"),
            year = (o.str("first_air_date") ?: o.str("release_date"))?.take(4)?.toIntOrNull(),
            overview = o.str("overview"),
            rating = o.dbl("vote_average")?.takeIf { it > 0 },
            posterUrl = o.str("poster_path")?.let { "$IMAGE_BASE/w500$it" },
            backdropUrl = o.str("backdrop_path")?.let { "$IMAGE_BASE/w1280$it" },
            genres = genres,
            tags = tags,
            cast = cast,
            runtimeMin = o.int("runtime") ?: o.arr("episode_run_time").orEmpty()
                .firstOrNull()?.let { runCatching { it.toString().toInt() }.getOrNull() },
            tmdbId = hit.id,
            imdbId = o.obj("external_ids")?.str("imdb_id"),
            source = "TMDB",
        )

        // 季详情是「一季一次请求」，10 季的剧就是 10 次；两个季级开关都关掉时必须一次都不发，
        // 否则「刮削单集简介」关着也照发，一部剧白等十几秒
        val wantSeasonOverview = settings.scrapeSeasonOverview
        val wantEpisodes = settings.scrapeEpisodeOverview
        if (isTv && seasons.isNotEmpty() && (wantSeasonOverview || wantEpisodes)) {
            val overviews = LinkedHashMap<Int, String>()
            val posters = LinkedHashMap<Int, String>()
            val episodes = LinkedHashMap<String, EpisodeScrape>()

            for (season in seasons.take(MAX_SEASON_REQUESTS)) {
                val seasonJson = fetchSeason(hit.id, season, settings) ?: continue
                if (wantSeasonOverview) {
                    seasonJson.str("overview")?.takeIf { it.isNotBlank() }?.let { overviews[season] = it }
                    seasonJson.str("poster_path")?.let { posters[season] = "$IMAGE_BASE/w342$it" }
                }
                if (!wantEpisodes) continue
                seasonJson.arr("episodes").orEmpty().forEach { epItem ->
                    val ep = epItem.obj() ?: return@forEach
                    val number = ep.int("episode_number") ?: return@forEach
                    episodes["$season-$number"] = EpisodeScrape(
                        title = ep.str("name"),
                        overview = ep.str("overview"),
                        stillUrl = ep.str("still_path")?.let { "$IMAGE_BASE/w300$it" },
                        rating = ep.dbl("vote_average")?.takeIf { it > 0 },
                    )
                }
            }

            result = result.copy(
                seasonOverviews = overviews,
                seasonPosters = posters,
                episodes = episodes,
            )
        }

        return result
    }

    private suspend fun fetchSeason(
        tvId: Int,
        season: Int,
        settings: AppSettings,
    ): JsonObject? {
        return tmdbGet("/tv/$tvId/season/$season", settings = settings)
    }

    private fun String.enc(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    /** 网络诊断用：实发一次 /configuration。成功返回 null，失败返回原因。 */
    suspend fun probe(settings: AppSettings): String? {
        tmdbGet("/configuration", settings = settings) ?: return lastErrorText ?: "请求失败"
        return null
    }

    private fun oneDecimal(v: Double): String =
        ((kotlin.math.round(v * 10.0) / 10.0)).toString()

    /**
     * 最多保留多少位演员。
     *
     * 剧集的 credits 是整部剧的聚合名单，动辄上百人；详情页只做「一眼认出熟脸」，
     * 12 位足够，再多横滑几十张卡反而找不到人。
     */
    const val MAX_CAST = 12

    /** 一部剧最多请求多少季的详情：迷你剧 1 季，长番几十季，10 季以上基本是动漫长番 */
    private const val MAX_SEASON_REQUESTS = 12

    /** 401 的冷却时长：凭证「看起来」无效往往有时效性（限流、服务端抖动），不该永久封杀 */
    private const val AUTH_COOLDOWN_MS = 30 * 60 * 1000L

    /** 429 的冷却时长 */
    private const val RATE_LIMIT_COOLDOWN_MS = 60_000L

}

/**
 * 豆瓣（中文补充源）。
 *
 * TMDB 的中文简介经常是空的或机翻，豆瓣能补上中文标题与简介。
 * 只做「搜到就用」，搜不到静默跳过——豆瓣没有稳定公开 API，被限流是常态。
 */
object DoubanSource {

    /** 诊断与请求共用的一组浏览器头。 */
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Referer" to "https://movie.douban.com/",
        "Accept" to "application/json, text/plain, */*",
    )

    suspend fun searchAndFetch(title: String, year: Int?): ScrapeResult? {
        val query = java.net.URLEncoder.encode(title, "UTF-8")
        val suggestions = Http.getJson(
            "https://movie.douban.com/j/subject_suggest?q=$query",
            headers,
        )?.arr() ?: return null

        val best = suggestions.mapNotNull { it.obj() }
            .firstOrNull { item ->
                val itemYear = item.str("year")?.toIntOrNull()
                year == null || itemYear == null || kotlin.math.abs(itemYear - year) <= 1
            } ?: return null

        val id = best.str("id") ?: return null
        val abstract = Http.getJson(
            "https://movie.douban.com/j/subject_abstract?subject_id=$id",
            headers,
        )?.obj()?.obj("subject")

        val rating = abstract?.obj("rating")?.dbl("value")?.takeIf { it > 0 }

        return ScrapeResult(
            title = best.str("title"),
            originalTitle = best.str("sub_title")?.takeIf { it.isNotBlank() },
            year = best.str("year")?.toIntOrNull(),
            overview = abstract?.str("intro")?.takeIf { it.isNotBlank() },
            rating = rating,
            posterUrl = best.str("img")?.takeIf { it.startsWith("http") },
            doubanId = id,
            source = "豆瓣",
        )
    }
}

/**
 * fanart.tv（高清背景图补充源）。
 *
 * 需要单独申请 api key；未配置时整段跳过。
 */
object FanartSource {

    suspend fun backdrop(tmdbId: Int, isMovie: Boolean, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val type = if (isMovie) "movies" else "tv"
        val body = Http.getJson("https://webservice.fanart.tv/v3/$type/$tmdbId?api_key=$apiKey")
            ?.obj() ?: return null

        val keys = if (isMovie) listOf("moviebackground", "movieposter") else listOf("showbackground", "tvbackground")
        for (key in keys) {
            val url = body.arr(key).orEmpty().firstOrNull()?.obj()?.str("url")
            if (!url.isNullOrBlank()) {
                Log.i(TAG, "fanart 命中 $key")
                return url
            }
        }
        return null
    }

    private const val TAG = "ElevenTV"
}
