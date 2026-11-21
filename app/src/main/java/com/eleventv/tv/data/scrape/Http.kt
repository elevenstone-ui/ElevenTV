package com.eleventv.tv.data.scrape

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 刮削用的 HTTP 客户端。
 *
 * ## 网络现实（2026-10-28 在用户真实网络用 curl 逐项实测的结论）
 *
 * - `api.themoviedb.org`：**国内 DoH 也全线投毒**——阿里 DoH 返回 31.13.76.65（Facebook 段）、
 *   360 DoH 返回 202.160.130.117、系统 DNS 返回 199.59.149.234（Twitter 段），全是 GFW 注入的
 *   伪造 IP。DoH 是加密的没错，但国内公共 DoH 服务商自己在出口做了过滤，答案本身就是假的。
 *   这就是「DoH 显示解析成功、TMDB API 却连不上」的根因。
 * - `api.tmdb.org`：TMDB **官方备用 API 域名**（与 themoviedb.org 同一套服务、同一套凭证）。
 *   实测阿里 / 腾讯 / 360 DoH 与系统 DNS 返回完全一致的真实 CloudFront IP，直连
 *   `/3/search/movie` 带真实 Key 200 / 0.9s。**TMDB API 一律走这个域名**。
 * - `image.tmdb.org`：CNAME 到 bunny CDN，各 DoH 应答干净（海报 CDN 实测正常）。
 *
 * DoH 解析逻辑沿用 1.7.6 的骨架（按序试端点、失败回落系统 DNS + 60 秒熔断），
 * 但**新增投毒识别**：应答 IP 落在已知伪造段（Facebook / Twitter / Dropbox 等 GFW 固定
 * 注入池）时视为该端点被污染，换下一个端点重试——不再「首个应答无条件采信」。
 */
object Http {

    private val dohHosts = setOf("api.tmdb.org", "api.themoviedb.org", "image.tmdb.org")

    /** TMDB 官方图片前缀，重写时按它识别 */
    private const val IMAGE_PREFIX = "https://image.tmdb.org/t/p"

    /** 与 DoubanSource.headers 一致：豆瓣图片 CDN 对裸请求返回 418 */
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    /**
     * 图片 CDN 反代前缀，由 [com.eleventv.tv.ElevenApp] 在设置变化时同步。
     *
     * 放在网络层而不是「拼 URL 时替换」：媒体库里已经存了几百个完整的
     * image.tmdb.org URL（海报/背景/演员头像/季海报/剧照），在生成处替换
     * 意味着用户必须全库重刮才能生效。拦截器对所有请求统一重写，
     * 老数据、Coil 异步加载、诊断探测全部覆盖，清空设置即可回滚。
     */
    @Volatile
    var imageBaseUrl: String = ""
        set(value) {
            field = value.trim().trimEnd('/')
        }

    /** 与 1.7.6 完全一致的端点列表：注意 doh.pub 用 /resolve（JSON API 路径）。 */
    private val dohEndpoints = listOf(
        "https://dns.alidns.com/resolve",
        "https://doh.pub/resolve",
        "https://doh.360.cn/resolve",
    )

    /**
     * GFW DNS 注入使用的固定伪造 IP 段（多为 Facebook / Twitter / Dropbox 的地址）。
     *
     * 依据：1.7.6 DohDns 注释里记录的历史投毒样本（108.160.169.185、199.59.150.40、
     * 108.160.165.139）+ 本次实测（31.13.76.65、162.125.83.1、199.59.149.234、202.160.130.117）。
     * 正常 CDN IP 几乎不可能落进这些段，误判风险极低。
     */
    private val poisonRanges = listOf(
        "31.13.0.0/16",       // Facebook
        "66.220.144.0/20",    // Facebook
        "69.63.176.0/20",     // Facebook
        "69.171.224.0/19",    // Facebook
        "129.134.0.0/17",     // Facebook
        "157.240.0.0/16",     // Facebook
        "173.252.64.0/18",    // Facebook
        "179.60.192.0/22",    // Facebook
        "185.60.216.0/22",    // Facebook
        "204.15.20.0/22",     // Facebook
        "199.59.148.0/22",    // Twitter
        "104.244.40.0/21",    // Twitter
        "192.133.76.0/22",    // Twitter
        "209.237.192.0/19",   // Twitter
        "162.125.0.0/16",     // Dropbox
        "108.160.160.0/20",   // Dropbox
    ).map { cidr ->
        val (addr, prefix) = cidr.split('/')
        val base = InetAddress.getByName(addr).address
        Triple(base, prefix.toInt(), cidr)
    }

    /** 判断某 IP 是否落在已知伪造段内。 */
    private fun isPoisoned(ip: InetAddress): Boolean {
        val bytes = ip.address
        // 投毒样本目前只有 IPv4 段；IPv6 应答一律放行。
        // 这不是「IPv6 一定干净」，而是没有可核验的实测样本前不做猜测。
        if (bytes.size != 4) return false
        for ((base, prefix, _) in poisonRanges) {
            var fullMatch = true
            val bits = prefix
            var i = 0
            while (i * 8 < bits) {
                val mask = if ((i + 1) * 8 <= bits) 0xFF else (0xFF shl (8 - bits % 8)) and 0xFF
                if ((bytes[i].toInt() and mask) != (base[i].toInt() and mask)) {
                    fullMatch = false
                    break
                }
                i++
            }
            if (fullMatch) return true
        }
        return false
    }

    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    private val failureUntil = ConcurrentHashMap<String, Long>()

    /** 查询 DoH 本身的客户端：固定走系统 DNS + 短超时，避免自我递归。 */
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun buildClient(): OkHttpClient =
        OkHttpClient.Builder()
            .dns(DohDns)
            // 图片层统一处理：见 [imageBaseUrl]。必须挂在网络拦截器之前的
            // 应用拦截器里，才能同时覆盖 Coil 的异步图片请求与诊断探测。
            .addInterceptor(Interceptor { chain ->
                val original = chain.request()
                val url = original.url.toString()
                var req = original

                // 豆瓣图片 CDN 会拦裸请求（418）：OkHttp 默认 UA 不是浏览器，
                // 也不带 Referer。补上这两个头，豆瓣海报才显示得出来。
                if (original.url.host.endsWith("doubanio.com")) {
                    req = req.newBuilder()
                        .header("User-Agent", BROWSER_UA)
                        .header("Referer", "https://movie.douban.com/")
                        .build()
                }

                // TMDB 图片 CDN 反代：SNI 阻断时换前缀，已入库的旧 URL 也生效。
                val base = imageBaseUrl
                if (base.isNotBlank() && url.startsWith(IMAGE_PREFIX)) {
                    req = runCatching {
                        req.newBuilder()
                            .url(base + url.removePrefix(IMAGE_PREFIX))
                            .build()
                    }.getOrElse {
                        // 前缀填错了也不能让整张图挂掉：退回原始请求
                        Log.w(TAG, "图片反代前缀无效，使用官方直连：$base")
                        req
                    }
                }
                chain.proceed(req)
            })
            // DoH 首次解析 + TLS + 弱网下 8s 连接超时经常不够
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    @Volatile
    private var _client: OkHttpClient? = null

    /** 业务客户端：TMDB / 豆瓣 / fanart / 海报都走它。 */
    val client: OkHttpClient
        get() = _client ?: synchronized(this) {
            _client ?: buildClient().also { _client = it }
        }

    /** 发起 GET，返回 `状态码 to 响应体`；异常时状态码为 0（供凭证轮换与网络诊断区分失败类型）。 */
    suspend fun getWithStatus(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String?> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        runCatching {
            client.newCall(builder.build()).execute().use { resp ->
                resp.code to resp.body?.string()
            }
        }.getOrElse { e ->
            Log.w(TAG, "请求失败 ${url.redact()}：${e.message}")
            0 to null
        }
    }

    /** 发起 GET，返回响应体；非 2xx 返回 null 并记日志（刮削是尽力而为，不该抛到 UI）。 */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String? {
        val (code, body) = getWithStatus(url, headers)
        if (code in 200..299) return body
        if (code != 0) Log.w(TAG, "HTTP $code ${url.redact()}")
        return null
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement? =
        get(url, headers)?.let { body ->
            runCatching { json.parseToJsonElement(body) }.getOrNull()
        }

    // ---- 取值小工具：刮削接口字段高度可选，用 JsonElement 手取比定义上百个 data class 稳 ----

    /**
     * 打日志前把凭证抹掉。
     *
     * `tmdbGet` 把 `api_key` 拼在 URL 上，直接打印等于把凭证写进 logcat——部分电视会
     * 持久化日志，截图求助时也会一起泄露。这里只保留「路径 + 参数名」。
     */
    private fun String.redact(): String = REDACT.replace(this) { m ->
        m.groupValues[1] + "***"
    }

    private val REDACT = Regex("""(api_key=|Authorization[=: ]\s*Bearer\s+)([^&\s]+)""", RegexOption.IGNORE_CASE)

    /** 供网络诊断用：返回某域名经 DoH/系统 DNS 解析出的首个 IP，失败返回 null。 */
    fun resolveHost(host: String): String? =
        runCatching { DohDns.lookup(host).firstOrNull()?.hostAddress }.getOrNull()

    fun JsonElement?.obj(): JsonObject? = runCatching { this?.jsonObject }.getOrNull()

    fun JsonElement?.arr(): JsonArray? = runCatching { this?.jsonArray }.getOrNull()

    fun JsonObject?.str(key: String): String? =
        runCatching { this?.get(key)?.jsonPrimitive?.content }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != "null" }

    fun JsonObject?.int(key: String): Int? =
        runCatching { this?.get(key)?.jsonPrimitive?.intOrNull }.getOrNull()

    fun JsonObject?.dbl(key: String): Double? =
        runCatching { this?.get(key)?.jsonPrimitive?.doubleOrNull }.getOrNull()

    fun JsonObject?.bool(key: String): Boolean? =
        runCatching { this?.get(key)?.jsonPrimitive?.content?.toBooleanStrictOrNull() }.getOrNull()

    fun JsonObject?.obj(key: String): JsonObject? = this?.get(key).obj()

    fun JsonObject?.arr(key: String): JsonArray? = this?.get(key).arr()

    private const val TAG = "ElevenTV"

    /** 带缓存、熔断与投毒识别的 DoH 解析器（骨架沿用 1.7.6 DohDns） */
    private object DohDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val host = hostname.lowercase()
            if (host !in dohHosts) return Dns.SYSTEM.lookup(hostname)

            // 缓存命中优先于熔断是刻意的：只要手里还有一份刚验证过的干净应答，
            // 就该继续用它，不该因为「上一次查询失败」把有效结果也丢掉。
            // 熔断只拦「发起新的 DoH 查询」这一步。
            cache[host]?.let { (addresses, expireAt) ->
                if (System.currentTimeMillis() < expireAt) return addresses
            }

            // 熔断期内不再尝试 DoH：上次已证明它此刻不可用，直接走系统 DNS
            val now = System.currentTimeMillis()
            if ((failureUntil[host] ?: 0L) > now) return Dns.SYSTEM.lookup(hostname)

            // 按序试端点，但**首个应答不再无条件采信**：含伪造 IP 的应答整批丢弃，
            // 换下一个端点。全部端点都被污染时回落系统 DNS（行为退化为 1.7.6）。
            for (endpoint in dohEndpoints) {
                val answers = queryEndpoint(endpoint, host)
                val clean = answers.filter { !isPoisoned(it) }
                if (answers.isNotEmpty() && clean.size < answers.size) {
                    Log.w(
                        TAG,
                        "DoH 应答含伪造 IP（$endpoint）：$host → " +
                            answers.filter { isPoisoned(it) }.joinToString { it.hostAddress ?: "?" } +
                            "，已剔除",
                    )
                }
                if (clean.isNotEmpty()) {
                    cache[host] = clean to (now + CACHE_MS)
                    failureUntil.remove(host)
                    Log.i(
                        TAG,
                        "DoH 解析 $host → ${clean.joinToString { it.hostAddress ?: "?" }} ($endpoint)",
                    )
                    return clean
                }
            }

            failureUntil[host] = now + BACKOFF_MS
            Log.w(TAG, "DoH 全部端点失败或被污染，60 秒内改用系统 DNS：$host")
            return Dns.SYSTEM.lookup(hostname)
        }

        private fun queryEndpoint(endpoint: String, host: String): List<InetAddress> = runCatching {
            val request = Request.Builder()
                .url("$endpoint?name=$host&type=A")
                .header("Accept", "application/dns-json")
                .build()
            dohClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<InetAddress>()
                parseAnswers(resp.body?.string().orEmpty())
            }
        }.getOrElse { emptyList() }

        private fun parseAnswers(body: String): List<InetAddress> {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()?.obj() ?: return emptyList()
            return root.arr("Answer").orEmpty().mapNotNull { item ->
                val o = item.obj() ?: return@mapNotNull null
                // 只取 A 记录（type 1）；CNAME 由服务端顺带给出最终 A 记录，无需再追
                if (o.int("type") != 1) return@mapNotNull null
                o.str("data")?.let { ip -> runCatching { InetAddress.getByName(ip) }.getOrNull() }
            }.distinctBy { it.hostAddress }
        }

        private const val CACHE_MS = 10 * 60 * 1000L
        private const val BACKOFF_MS = 60_000L
    }
}
