package com.eleventv.tv.data.webdav

import android.util.Base64
import android.util.Log
import android.util.Xml
import com.eleventv.tv.data.model.SmbServer
import com.eleventv.tv.data.storage.RemoteEntry
import com.eleventv.tv.data.storage.RemoteStorage
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** WebDAV 请求失败。区分「配置错了」与「网络抖了」：前者不该重试，后者值得再试一次 */
class DavException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
    val notFound: Boolean = false,
) : IOException(message, cause)

/** Basic 认证头；用户名为空表示匿名访问（不少 NAS 的 WebDAV 允许） */
fun SmbServer.davAuthHeader(): String? {
    if (username.isBlank()) return null
    val raw = "$username:$password".toByteArray(Charsets.UTF_8)
    return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
}

/** 播放端用：Media3 的 HttpDataSource 只需要这一组默认请求头 */
fun SmbServer.davRequestHeaders(): Map<String, String> =
    davAuthHeader()?.let { mapOf("Authorization" to it) } ?: emptyMap()

/**
 * WebDAV 目录访问。
 *
 * ## 只需实现一件事：PROPFIND
 *
 * 播放不需要走这里——`DefaultHttpDataSource` 直接用 GET + Range 读文件，
 * 断线重连、跳转、`Content-Length` 处理全由 Media3 负责。这个类只服务于
 * 「扫描」和「选目录」两个动作，因此保持得足够薄。
 *
 * ## 为什么不用 PROPFIND 的 allprop
 *
 * `allprop` 会让服务器把所有属性都吐一遍（含 `supportedlock` 之类的长 XML），
 * 一部剧几千集时响应体积能到几 MB。只点名要四个属性。
 */
class WebDavStorage(private val server: SmbServer) : RemoteStorage {

    override val label: String get() = server.baseUrl

    override fun list(path: String): List<RemoteEntry> {
        val url = server.davUrl(path)
        var attempt = 0
        while (true) {
            try {
                return request(url, depth = 1)
            } catch (e: DavException) {
                if (!e.retryable || attempt >= 1) throw e
            } catch (e: IOException) {
                // 局域网瞬时抖动：第二次通常就过了。配置类错误已在上面按 notFound/权限分流
                if (attempt >= 1) throw e
            }
            attempt++
            Log.w(TAG, "PROPFIND 重试一次：$url")
        }
    }

    override fun close() = Unit

    // ---------------- HTTP ----------------

    private fun request(url: String, depth: Int, hop: Int = 0): List<RemoteEntry> {
        val body = PROPFIND_BODY.toByteArray(Charsets.UTF_8)
        val headers = buildMap {
            put("Depth", depth.toString())
            put("Content-Type", "application/xml; charset=utf-8")
            put("Accept", "application/xml")
            put("User-Agent", USER_AGENT)
            server.davAuthHeader()?.let { put("Authorization", it) }
        }

        val response = try {
            DavHttp.exchange(url, "PROPFIND", body, headers, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        } catch (e: DavException) {
            throw e
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            throw DavException(
                "HTTPS 证书校验失败：NAS 多为自签证书，改用 HTTP（关掉 TLS）即可",
                e,
            )
        } catch (e: java.net.SocketTimeoutException) {
            throw DavException(
                "WebDAV 连接超时（${READ_TIMEOUT_MS / 1000} 秒无响应）：地址或端口不通；请求 $url",
                e,
                retryable = true,
            )
        } catch (e: java.net.ConnectException) {
            throw DavException(
                "连不上 ${server.host}:${server.port}：检查 NAS 是否在线、该端口是否有服务在监听（或被防火墙拦了）",
                e,
                retryable = true,
            )
        } catch (e: java.net.UnknownHostException) {
            throw DavException("域名解析失败（${server.host}）：局域网里直接填 IP 更稳", e)
        } catch (e: IOException) {
            // 服务器把连接掐了、或者返回了半截响应。局域网抖动居多，值得重试一次
            throw DavException("WebDAV 连接中断：${e.message ?: "连接被关闭"}；请求 $url", e, retryable = true)
        }

        // PROPFIND 不是 GET，Java 的自动跳转不会生效；补 `/` 或 http→https 的跳转得自己跟
        if (response.code in REDIRECTS && hop < 2) {
            val location = response.headers["location"]
            if (!location.isNullOrBlank()) {
                val next = runCatching { URI(url).resolve(location).toString() }.getOrNull()
                if (!next.isNullOrBlank()) return request(next, depth, hop + 1)
            }
        }

        // 报错必须带上真实请求的 URL：用户拿这一行直接粘到电脑浏览器里，
        // 就能把「App 参数错」和「NAS 没开 WebDAV / 端口不对」区分开，
        // 否则只能在电视上反复试，一次一条猜测。
        val where = "；请求 $url"
        when (response.code) {
            in 200..299 -> Unit
            401 -> throw DavException(
                "WebDAV 认证失败（401）：检查用户名与密码" +
                    (if (server.username.isBlank()) "——当前未填写用户名" else "") + where,
            )
            403 -> throw DavException(
                if (server.basePath.isBlank()) {
                    // 浏览器打开 `http://NAS:5005/` 看到 Forbidden 就是这个：服务是通的，
                    // 只是 NAS 一律不允许列出根目录。它不代表账号没权限。
                    val hint = "WebDAV 拒绝列出根目录（403）：「路径前缀」必须填共享名（如 media），" +
                        "留空会去列 NAS 根，任何账号都会被拒"
                    hint + where
                } else {
                    val hint = "WebDAV 无权限（403）：该账号不能访问 ${server.basePath}。" +
                        "威联通要在「控制台 → 权限设定 → 共享文件夹」给该共享开 WebDAV 权限"
                    hint + where
                },
            )
            404 -> throw DavException("WebDAV 路径不存在（404）：检查${PATH_HINT}$where", notFound = true)
            405 -> throw DavException(
                "该地址不支持 WebDAV（405）：端口很可能填成了管理后台。"
                    + "威联通在「控制台 → 网络和文件服务 → Win/Mac/NFS/WebDAV」勾选「启用 WebDAV」，"
                    + "端口以那个页面显示的为准（常见 5005/5006，可自定义）" + where,
            )
            409 -> throw DavException("WebDAV 冲突（409）：路径不是目录" + where)
            423 -> throw DavException("WebDAV 资源被锁定（423）" + where)
            500, 502, 503, 504 -> throw DavException("WebDAV 服务暂不可用（HTTP ${response.code}）$where", retryable = true)
            else -> {
                val desc = statusText(response.code)
                throw DavException(
                    "WebDAV 请求失败（HTTP ${response.code}${if (desc.isEmpty()) "" else " $desc"}）$where",
                )
            }
        }

        return parse(java.io.ByteArrayInputStream(response.body), url)
    }

    /** 只给报错文案用的粗粒度状态描述，避免用户对着一个裸数字查文档 */
    private fun statusText(code: Int): String = when (code) {
        400 -> "请求有误"
        405 -> "方法不被允许"
        406 -> "服务端无法满足 Accept"
        408 -> "服务端读超时"
        413 -> "请求体过大"
        414 -> "URL 过长"
        422 -> "无法处理"
        429 -> "请求过于频繁"
        501 -> "服务端不支持 PROPFIND"
        507 -> "存储空间不足"
        in 300..399 -> "重定向"
        in 500..599 -> "服务端错误"
        else -> ""
    }

    // ---------------- XML ----------------

    private fun parse(input: java.io.InputStream, requestUrl: String): List<RemoteEntry> {
        val selfPath = runCatching { URI(requestUrl).path.orEmpty().trimEnd('/') }.getOrDefault("")

        val parser = Xml.newPullParser()
        // 开命名空间：各家 NAS 的前缀五花八门（`D:`、`d:`、`lp1:`、无前缀），
        // 关掉的话 `parser.name` 会带回前缀，逐个前缀去匹配是不可能的。
        // 打开后拿到的就是本地名（`resourcetype`）。
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)

        val out = ArrayList<RemoteEntry>()
        var href: String? = null
        var isDir = false
        var size = 0L
        var modified = 0L
        var created = 0L
        var inResponse = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        inResponse = true
                        href = null
                        isDir = false
                        size = 0L
                        modified = 0L
                        created = 0L
                    }
                    "href" -> if (inResponse) href = parser.nextText().trim()
                    "collection" -> if (inResponse) isDir = true
                    "getcontentlength" -> if (inResponse) size = parser.nextText().trim().toLongOrNull() ?: 0L
                    "getlastmodified" -> if (inResponse) modified = parseHttpDate(parser.nextText().trim())
                    "creationdate" -> if (inResponse) created = parseIsoDate(parser.nextText().trim())
                }
                XmlPullParser.END_TAG -> if (parser.name.lowercase() == "response" && inResponse) {
                    inResponse = false
                    val link = href
                    if (link != null) {
                        val path = decodedPath(link, requestUrl)
                        // 第一项永远是目录自己（Depth:1 的语义），它不是「子项」
                        val isSelf = path.trimEnd('/') == selfPath
                        val name = path.trimEnd('/').substringAfterLast('/')
                        if (!isSelf && name.isNotEmpty()) {
                            out += RemoteEntry(
                                name = name,
                                isDirectory = isDir || path.endsWith("/"),
                                sizeBytes = size,
                                modifiedAt = modified,
                                createdAt = created,
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /**
     * href → 解码后的路径。
     *
     * 服务器可能给三种形态：绝对 URL、绝对路径、相对名。统一成路径后再取末段当文件名。
     * 用 [URI.getPath] 而不是 `URLDecoder`：后者会把文件名里的 `+` 解码成空格，
     * `EP01+SP.mkv` 这类名字会直接对不上。
     */
    private fun decodedPath(href: String, requestUrl: String): String {
        val raw = runCatching { URI(href).path.orEmpty() }
            .getOrElse { runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href) }
        return when {
            raw.isBlank() -> ""
            raw.startsWith("/") -> raw
            href.contains("://") -> raw
            else -> runCatching { URI(requestUrl).path.orEmpty().trimEnd('/') + "/" + raw }.getOrDefault(raw)
        }
    }

    private fun parseHttpDate(text: String): Long {
        if (text.isBlank()) return 0L
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
        )
        for (pattern in formats) {
            val value = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                    isLenient = false
                }.parse(text)?.time
            }.getOrNull()
            if (value != null) return value
        }
        return 0L
    }

    private fun parseIsoDate(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching { java.time.Instant.parse(text).toEpochMilli() }
            .getOrElse { parseHttpDate(text) }
    }

    private companion object {
        const val TAG = "ElevenTV"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val USER_AGENT = "ElevenTV/2.1 (Android TV)"
        const val PATH_HINT = " 路径前缀（basePath）是否与 NAS 上的共享目录一致"
        val REDIRECTS = setOf(301, 302, 303, 307, 308)

        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:creationdate/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
