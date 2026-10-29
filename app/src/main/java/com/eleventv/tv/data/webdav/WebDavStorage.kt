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
 * 把「大小写写错的路径」修成服务器上真实存在的写法，修不出来返回 null。
 *
 * ## 为什么值得专门做一层
 *
 * WebDAV 的路径**区分大小写**，SMB **不区分**。用户在 Windows 上敲惯了
 * `\\nas\multimedia`，切到 WebDAV 时路径前缀自然还是小写，然后收到一个 404——
 * 和「这个共享根本不存在」一模一样的报错。而 SMB 那边一切正常，所以几乎没人
 * 会想到是大小写。这一层就是把「猜」变成「试」：原样不通就换大小写变体各试一次，
 * 哪个能列出目录就是它。
 *
 * 只修首段（共享名）与起始目录首段的**写法**，不改层级、不做模糊匹配；
 * 修完必须由调用方再用完整根目录列表验证一遍。
 */
fun SmbServer.davRepairCase(): SmbServer? {
    val base = basePath.trim('/')
    if (base.isBlank()) return null
    val first = base.substringBefore('/')
    if (first.isBlank()) return null

    val actual = probeShareCase(first) ?: return null
    var fixed = if (actual == first) this else copy(basePath = base.replaceFirst(first, actual))

    // 共享名通了，再看「起始目录」：列出真实子目录名，逐段纠正大小写
    val names = runCatching { WebDavStorage(fixed).use { it.list("") } }
        .getOrNull()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        ?: return null

    val fixedRoots = roots.map { root ->
        val segs = root.path.split('/').filter { it.isNotBlank() }
        if (segs.isEmpty()) return@map root
        // 完全一致优先，其次是忽略大小写后唯一匹配的那个
        val real = names.firstOrNull { it == segs[0] }
            ?: names.firstOrNull { it.equals(segs[0], ignoreCase = true) }
            ?: return@map root
        if (real == segs[0]) root else root.copy(path = (listOf(real) + segs.drop(1)).joinToString("/"))
    }
    if (fixedRoots != roots) fixed = fixed.copy(mediaRoots = fixedRoots)

    return if (fixed == this) null else fixed
}

/** 依次试 `Media` / `media` / `MEDIA` 这类写法，返回第一个能列出目录的 */
private fun SmbServer.probeShareCase(wanted: String): String? {
    val candidates = listOf(
        wanted,
        wanted.lowercase(),
        wanted.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.lowercase() },
        wanted.uppercase(),
    ).distinct()
    for (candidate in candidates) {
        val reachable = runCatching {
            WebDavStorage(copy(basePath = candidate)).use { it.list("") }
        }.isSuccess
        if (reachable) return candidate
    }
    return null
}

/**
 * 404 的自诊断：把「到底哪一层错了」写进错误信息。
 *
 * 404 有四种完全不同的成因，只给一句「路径不存在」，用户只能反复改、反复试：
 *
 * 1. **共享名大小写不对**——WebDAV 的路径区分大小写，SMB 不区分。
 *    SMB 里 `\\nas\multimedia` 能进，WebDAV 里 `/multimedia` 未必存在（真名可能是 `Multimedia`）。
 *    这是从 SMB 切过来最容易踩的一个坑，而且报错完全一样。
 * 2. **共享没开 WebDAV 访问权**——威联通默认不勾，表现出来也是 404。
 * 3. **共享对，是「起始目录」写错了**——这种情况能列出真实子目录名，比任何文案都管用。
 * 4. 端口打到了别的东西上（那种一般是 405 或返回 HTML，走不到这里）。
 *
 * 只在「测试并保存」失败时调用，扫描路径上不做这些额外请求。
 */
fun SmbServer.davNotFoundHint(): String {
    val base = basePath.trim('/')
    if (base.isBlank()) return ""

    // 第一种：共享目录本身能列 → 错的只是「起始目录」。把真实子目录摊给用户
    val children = runCatching { WebDavStorage(this).use { it.list("") } }.getOrNull()
    if (children != null) {
        val dirs = children.filter { it.isDirectory }.map { it.name }.sorted()
        if (dirs.isEmpty()) return "；共享目录「$base」下没有子目录，「起始目录」应该留空"
        val shown = dirs.take(8).joinToString("、") + if (dirs.size > 8) " 等 ${dirs.size} 个" else ""
        val wrong = roots.map { it.path }.filter { it.isNotBlank() && dirs.none { d -> d == it } }
        return if (wrong.isEmpty()) {
            "；共享目录「$base」可访问，子目录：$shown"
        } else {
            "；共享目录「$base」可访问，但没有 ${wrong.joinToString("、")}。实际的子目录：$shown"
        }
    }

    // 第二种：换大小写能通 → 就是名字写法的问题
    val first = base.substringBefore('/')
    val variants = listOf(
        first.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.lowercase() },
        first.lowercase(),
        first.uppercase(),
    ).distinct().filter { it != first }

    for (candidate in variants) {
        val probe = copy(basePath = base.replaceFirst(first, candidate))
        val ok = runCatching { WebDavStorage(probe).use { it.list("") } }.isSuccess
        if (ok) {
            return "；但「$candidate」可以连上——WebDAV 路径区分大小写（SMB 不区分），" +
                "把路径前缀改成「$candidate」即可"
        }
    }

    // 第三种：同名的都连不上 → 共享没开 WebDAV 访问权
    return "；共享目录「$first」无法通过 WebDAV 访问：威联通需在" +
        "「控制台 → 权限 → 共享文件夹 → 编辑共享文件夹 → WebDAV 访问权限」勾选该共享" +
        "（只开 WebDAV 服务、不给共享授权，表现出来就是 404）"
}

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
