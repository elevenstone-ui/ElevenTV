package com.eleventv.tv.data.webdav

import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/** 一次 HTTP 响应。头名统一小写，避免 `Content-Length` / `content-length` 两种写法各查一遍 */
internal class DavResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/**
 * WebDAV 的 PROPFIND 传输层。
 *
 * ## 为什么换成 OkHttp
 *
 * 旧实现在裸 Socket 上一次连接一个请求 + `Connection: close`。扫描目录树时每个目录
 * 都要重新 TCP 握手（HTTPS 还各做一次 TLS 握手），几百个目录就是几百次串行 RTT，
 * 这是 WebDAV 扫描慢的主因。旧文件注释里「本项目缓存里没有 OkHttp，手写更划算」的前提
 * 并不成立：`app/build.gradle.kts` 已声明 `implementation(libs.okhttp)`，
 * `data/scrape/Http.kt` 也一直在用它。此处复用项目已有依赖，不新增任何依赖或技术栈。
 *
 * ## 对外契约保持不变
 *
 * - [exchange] 的签名、[DavResponse] 结构、响应头名小写约定原样保留；
 * - 关闭自动重定向与自动重试：跳转仍由 [WebDavStorage.request] 按 Location 自己跟，
 *   重试仍由 [WebDavStorage.list] 按 `retryable` 判定，避免双层重试改变行为；
 * - 显式 `Accept-Encoding: identity`：旧实现不发该头，服务器按未压缩返回；
 *   不关掉 OkHttp 的自动 gzip 会让响应体语义悄悄变化；
 * - 把 OkHttp 包在 cause 链里的具体 IO 异常解包后原样抛出，
 *   保证 [WebDavStorage.request] 里那串按异常类型区分的排障文案继续命中。
 *
 * 顺带去掉了逐字节 `readLine`、chunked / EOF 三套手写解析与它们的中间拷贝。
 */
internal object DavHttp {

    /** 单次响应体上限。几千集的目录 PROPFIND 也就几 MB，16MB 足够且不至于在低端盒子 OOM */
    private const val MAX_BODY_BYTES = 16 * 1024 * 1024

    private val connectionPool = ConnectionPool()

    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    private val xmlMediaType = "application/xml; charset=utf-8".toMediaType()

    fun exchange(
        url: String,
        method: String,
        body: ByteArray?,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): DavResponse {
        // newBuilder() 共享同一个 ConnectionPool / Dispatcher，只覆盖本次超时，
        // 因此同一目录树里的连续请求能复用已建立的连接。
        val client = baseClient.newBuilder()
            .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        val builder = Request.Builder().url(url)
        for ((k, v) in headers) builder.header(k, v)
        if (!headers.keys.any { it.equals("Accept-Encoding", ignoreCase = true) }) {
            builder.header("Accept-Encoding", "identity")
        }
        builder.method(method, body?.toRequestBody(xmlMediaType))

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val merged = LinkedHashMap<String, String>(resp.headers.size)
                for (i in 0 until resp.headers.size) {
                    val name = resp.headers.name(i).lowercase()
                    val value = resp.headers.value(i)
                    // 同名头按规范逗号拼接，与旧实现的 readHeaders 行为一致
                    merged[name] = merged[name]?.let { "$it, $value" } ?: value
                }
                // OkHttp 已完成响应体读取与边界判定，这里一次取出并做上限检查
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (bytes.size > MAX_BODY_BYTES) {
                    throw IOException("响应体过大，超过 ${MAX_BODY_BYTES / 1024 / 1024}MB")
                }
                DavResponse(resp.code, merged, bytes)
            }
        } catch (e: IOException) {
            throw unwrap(e)
        }
    }

    /**
     * OkHttp 会把底层异常包在 cause 链里。调用方按
     * SSLHandshakeException / SocketTimeoutException / ConnectException / UnknownHostException
     * 分别给出排障文案，这里解包后原样抛出，保证那套文案继续生效。
     */
    private fun unwrap(e: IOException): IOException {
        var t: Throwable? = e.cause
        var depth = 0
        while (t != null && depth < 8) {
            if (t is SSLHandshakeException || t is SocketTimeoutException ||
                t is ConnectException || t is UnknownHostException
            ) {
                return t
            }
            t = t.cause
            depth++
        }
        return e
    }
}
