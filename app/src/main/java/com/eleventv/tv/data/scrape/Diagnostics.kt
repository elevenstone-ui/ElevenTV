package com.eleventv.tv.data.scrape

import com.eleventv.tv.data.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 刮削网络诊断。
 *
 * 「刮削失败」一句提示没法定位问题——DNS、TMDB、海报 CDN、fanart、豆瓣
 * 是五段独立的链路，任何一段断都会表现成同一个样子。这里逐项实测并给出
 * 人话结论，用户跑一遍就知道断在哪、该修什么（换 Key / 等限流过去）。
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

        // 3. 海报 CDN：用一张确定存在的官方图
        val (imgCode, _) = Http.getWithStatus("https://image.tmdb.org/t/p/w92/wwemzKWzjKYJFfCeiB57q3r4Bcm.png")
        items += Item(
            "TMDB 海报 CDN",
            imgCode in 200..299,
            when {
                imgCode in 200..299 -> "正常"
                imgCode == 0 -> "连不上（SNI 阻断的典型表现，DNS 正常也白搭）"
                else -> "HTTP $imgCode"
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
