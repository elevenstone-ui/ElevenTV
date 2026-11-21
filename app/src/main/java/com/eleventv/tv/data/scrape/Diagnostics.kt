package com.eleventv.tv.data.scrape

import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 刮削网络诊断。
 *
 * 「刮削失败」一句提示没法定位问题——DNS、TMDB 探活、TMDB 搜索、
 * 海报 CDN、豆瓣图片 CDN、豆瓣 API、fanart 是互相独立的链路，任何一段断
 * 都会表现成同一个样子。这里逐项实测并给出人话结论。
 *
 * 尤其不能只靠 `/configuration` 探活就判定「TMDB 正常」：刮削第一步走的是
 * `/search/movie`，两者凭证池相同但限流/权限/返回结构都可能不同。
 */
object ScrapeDiagnostics {

    data class Item(val name: String, val ok: Boolean, val detail: String)

    suspend fun run(settings: AppSettings): List<Item> = withContext(Dispatchers.IO) {
        val items = mutableListOf<Item>()

        // 1. DNS：两个 TMDB 域名都要能解出 IP
        for (host in listOf("api.tmdb.org", "image.tmdb.org")) {
            val ip = Http.resolveHost(host)
            items += Item(
                "DoH 解析 $host",
                ip != null,
                ip ?: "失败：DoH 与系统 DNS 都没拿到地址",
            )
        }

        // 2. TMDB API（带凭证实发一次 /configuration）
        val tmdbReason = TmdbSource.probe(settings)
        items += Item("TMDB API", tmdbReason == null, tmdbReason ?: "正常（凭证有效）")

        // 2b. 刮削第一步：真正走 /search/movie。/configuration 通不代表搜索能返回条目，
        // 更不代表批量几百条不会 429。这一项把「请求失败」和「0 命中」分开报。
        val (searchOk, searchDetail) = TmdbSource.probeSearch(settings)
        items += Item("TMDB 搜索（刮削第一步）", searchOk, searchDetail)

        // 3. 海报 CDN：用一张确定存在的官方图。
        // Http 的图片拦截器会在配了反代前缀时自动改写这个 URL，所以这里
        // 实测的就是「用户填的那个地址」通不通，不用另写一份探测逻辑。
        val proxy = settings.imageBaseUrl.trim()
        val (imgCode, _) = Http.getWithStatus("https://image.tmdb.org/t/p/w92/wwemzKWzjKYJFfCeiB57q3r4Bcm.png")
        items += Item(
            "TMDB 海报 CDN",
            imgCode in 200..299,
            buildString {
                append(
                    when {
                        imgCode in 200..299 -> "正常"
                        imgCode == 0 -> "连不上（SNI 阻断的典型表现，DNS 正常也白搭）"
                        else -> "HTTP $imgCode"
                    },
                )
                append(if (proxy.isBlank()) "；未配置反代" else "；已走反代 $proxy")
                if (imgCode !in 200..299 && proxy.isBlank()) {
                    append("。可开启「豆瓣海报优先」，或在「图片反代前缀」填入可达地址")
                }
            },
        )

        // 3b. 豆瓣图片 CDN：豆瓣海报兜底依赖它可达；裸请求会被 418 拦，
        // 图片拦截器会补 UA/Referer，这里验证的就是实际加载路径。
        val (dbImgCode, _) = Http.getWithStatus(
            "https://img9.doubanio.com/view/photo/s_ratio_poster/public/p480747492.webp",
        )
        items += Item(
            "豆瓣图片 CDN",
            dbImgCode in 200..299,
            when {
                dbImgCode in 200..299 -> "正常（豆瓣海报兜底可用）"
                dbImgCode == 0 -> "连不上"
                else -> "HTTP $dbImgCode（418 = 反爬拦截）"
            },
        )

        // 4. fanart.tv
        val fanartKey = settings.fanartApiKey.ifBlank { com.eleventv.tv.BuildConfig.FANART_API_KEY }
        if (fanartKey.isBlank()) {
            items += Item("fanart.tv", true, "未配置 Key，该源已跳过")
        } else {
            val (fc, _) = Http.getWithStatus("https://webservice.fanart.tv/v3/movies/27205?api_key=$fanartKey")
            items += Item(
                "fanart.tv",
                fc in 200..299,
                when {
                    fc in 200..299 -> "正常"
                    fc == 0 -> "连不上"
                    else -> "HTTP $fc（401 = Key 无效）"
                },
            )
        }

        // 5. 豆瓣（中文补充源）
        val (dc, _) = Http.getWithStatus("https://movie.douban.com/j/subject_suggest?q=test", DoubanSource.headers)
        items += Item(
            "豆瓣",
            dc in 200..299,
            when {
                dc in 200..299 -> "可达（反爬限流时会返回空结果）"
                dc == 0 -> "连不上"
                else -> "HTTP $dc"
            },
        )

        items
    }
}
