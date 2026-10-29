package com.eleventv.tv.data.webdav

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** 一次 HTTP 响应。头名统一小写，避免 `Content-Length` / `content-length` 两种写法各查一遍 */
internal class DavResponse(
    val code: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/**
 * 原始 Socket 上的最小 HTTP/1.1 客户端。
 *
 * ## 为什么必须自己写
 *
 * `PROPFIND` 是 WebDAV 的核心方法，但**安卓自带的 `HttpURLConnection` 发不出去**：
 * 它的 `setRequestMethod` 是一个硬编码白名单，遇到别的方法直接抛
 *
 * ```
 * ProtocolException: Expected one of [OPTIONS, GET, HEAD, POST, PUT, DELETE, TRACE, PATCH]
 *                    but was PROPFIND
 * ```
 *
 * 这条报错和「服务器不支持 WebDAV」长得极像，实际连一个字节都没发出去——排查时
 * 很容易往 NAS 配置上找，越找越远。
 *
 * OkHttp 本身允许任意方法，但它的 `HttpURLConnection` 实现是同一套白名单；要绕开就得
 * 直接依赖 OkHttp，而本项目是离线构建、缓存里没有它。一个 PROPFIND 只需要一次请求，
 * 连接复用、HTTP/2、连接池全都用不上——手写这 180 行比引入一个依赖划算。
 *
 * ## 只支持什么
 *
 * 一次连接一次请求、`Connection: close`、`Content-Length` / `chunked` / 读到 EOF 三种
 * 响应体形态、1xx 跳过、HTTPS（系统信任库，自签证书依然会抛 `SSLHandshakeException`）。
 * 不做：连接复用、重定向（由调用方按 Location 自己跟，见 [WebDavStorage]）、认证协商。
 */
internal object DavHttp {

    /** 单次响应体上限。几千集的目录 PROPFIND 也就几 MB，32MB 足够且不至于 OOM */
    private const val MAX_BODY_BYTES = 32 * 1024 * 1024

    private const val MAX_HEAD_BYTES = 64 * 1024

    fun exchange(
        url: String,
        method: String,
        body: ByteArray?,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): DavResponse {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: "http"
        val host = uri.host ?: throw IOException("URL 缺少主机名：$url")
        val port = if (uri.port > 0) uri.port else if (scheme == "https") 443 else 80

        // 请求行里的 target 必须是「已编码」的路径：davUrl 已经把中文/空格转成 %XX，
        // 这里再走一次解码会把 %2F 之类的序列毁掉。
        val target = buildString {
            val path = uri.rawPath
            append(if (path.isNullOrEmpty()) "/" else path)
            uri.rawQuery?.let { append('?').append(it) }
        }
        val hostHeader = if (uri.port > 0) "$host:$port" else host

        val socket = openSocket(scheme)
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            (socket as? SSLSocket)?.let { ssl ->
                // 保持主机名校验：宁可报证书错，也不要静默连到别的主机上
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.startHandshake()
            }

            val head = buildString {
                append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostHeader).append("\r\n")
                for ((k, v) in headers) {
                    append(k).append(": ").append(v).append("\r\n")
                }
                if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                body?.let { write(it) }
                flush()
            }

            val input = socket.getInputStream().buffered()
            return readResponse(input)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun openSocket(scheme: String): Socket =
        if (scheme == "https") {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket()
        } else {
            Socket()
        }

    private fun readResponse(input: InputStream): DavResponse {
        while (true) {
            val statusLine = readLine(input) ?: throw IOException("服务器没有返回任何响应（连接被立即关闭）")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw IOException("无法解析状态行：$statusLine")
            val headers = readHeaders(input)

            // 100 Continue / 102 Processing 这类中间响应没有 body，直接读下一段
            if (code in 100..199) continue

            val body = when {
                code == 204 || code == 304 -> ByteArray(0)
                headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                    readChunked(input)
                headers["content-length"]?.toIntOrNull() != null ->
                    readFixed(input, headers.getValue("content-length").toInt())
                else -> readUntilEof(input)
            }
            return DavResponse(code, headers, body)
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HEAD_BYTES) throw IOException("响应头超过 ${MAX_HEAD_BYTES / 1024}KB")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            // 同名头按规范是逗号拼接，实际只有 Set-Cookie 会重复，这里合并即可
            out[name] = out[name]?.let { "$it, $value" } ?: value
        }
        return out
    }

    /** 逐字节读到换行。响应头不大，不值得为此再包一层缓冲区 */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            if (sb.length > MAX_HEAD_BYTES) throw IOException("响应行过长")
            sb.append(c.toChar())
        }
    }

    private fun readFixed(input: InputStream, length: Int): ByteArray {
        if (length < 0) throw IOException("Content-Length 不合法：$length")
        if (length > MAX_BODY_BYTES) throw IOException("响应体过大（${length / 1024 / 1024}MB）")
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n == -1) throw IOException("响应提前结束：期望 $length 字节，只收到 $read")
            read += n
        }
        return buf
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(8 * 1024)
        while (true) {
            val sizeLine = readLine(input) ?: throw IOException("chunked 响应提前结束")
            // chunk 扩展（`1a;foo=bar`）直接丢掉
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("chunk 长度不合法：$sizeLine")
            if (size == 0) {
                // 跳过 trailer
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                return out.toByteArray()
            }
            if (out.size() + size > MAX_BODY_BYTES) throw IOException("响应体过大，超过 ${MAX_BODY_BYTES / 1024 / 1024}MB")
            out.write(readFixed(input, size))
            readLine(input) // 每个 chunk 后面跟一个 CRLF
        }
    }

    private fun readUntilEof(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(8 * 1024)
        val buf = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n == -1) return out.toByteArray()
            if (out.size() + n > MAX_BODY_BYTES) throw IOException("响应体过大，超过 ${MAX_BODY_BYTES / 1024 / 1024}MB")
            out.write(buf, 0, n)
        }
    }
}
