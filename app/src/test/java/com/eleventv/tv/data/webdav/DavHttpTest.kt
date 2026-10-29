package com.eleventv.tv.data.webdav

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用本机回环起一个假服务器，验证 [DavHttp] 真的把 `PROPFIND` 发出去了、并且能把
 * 三种响应体（Content-Length / chunked / 空）读完整。
 *
 * 之所以必须测这一层：安卓的 `HttpURLConnection` 对非标准方法是硬白名单，
 * `PROPFIND` 会在**发出前**就抛 `ProtocolException`。这类问题在单元测试里
 * 看不见（JVM 的 HttpURLConnection 不拦），只靠真机才会暴露一次——所以把
 * 「方法真的到达了服务端」变成一条断言。
 */
class DavHttpTest {

    @Test
    fun `PROPFIND 请求行与方法能到达服务端`() {
        var captured = ""
        val port = serve(
            response = "HTTP/1.1 207 Multi-Status\r\n" +
                "Content-Type: application/xml\r\n" +
                "Content-Length: 5\r\n\r\n" +
                "hello",
            capture = { captured = it },
        )

        val resp = DavHttp.exchange(
            url = "http://127.0.0.1:$port/media/Movie",
            method = "PROPFIND",
            body = "<d:propfind/>".toByteArray(),
            headers = mapOf("Depth" to "1", "Authorization" to "Basic eA=="),
            connectTimeoutMs = 2_000,
            readTimeoutMs = 2_000,
        )

        assertEquals(207, resp.code)
        assertEquals("hello", String(resp.body))
        assertTrue("请求行里没有 PROPFIND：$captured", captured.startsWith("PROPFIND /media/Movie HTTP/1.1"))
        assertTrue("Depth 头没发出去", captured.contains("Depth: 1"))
        assertTrue("Authorization 头没发出去", captured.contains("Authorization: Basic eA=="))
        // Content-Length 必须与 body 字节数一致，否则服务端会一直等剩下的字节
        assertTrue("Content-Length 与实际 body 不符", captured.contains("Content-Length: 13"))
    }

    @Test
    fun `chunked 响应体按块拼接`() {
        val port = serve(
            // 故意把标签拆在两块里，模拟真实服务器按缓冲切包
            response = "HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "5\r\n<d:pr\r\n" +
                "7\r\nopfind>\r\n" +
                "0\r\n\r\n",
        )

        val resp = DavHttp.exchange("http://127.0.0.1:$port/", "PROPFIND", ByteArray(0), emptyMap(), 2_000, 2_000)

        assertEquals(207, resp.code)
        assertEquals("<d:propfind>", String(resp.body))
    }

    @Test
    fun `没有响应体时不会卡住等待`() {
        val port = serve("HTTP/1.1 204 No Content\r\n\r\n")

        val resp = DavHttp.exchange("http://127.0.0.1:$port/", "PROPFIND", ByteArray(0), emptyMap(), 2_000, 2_000)

        assertEquals(204, resp.code)
        assertEquals(0, resp.body.size)
    }

    /** 起一个只应答一次的假 HTTP 服务器，返回端口 */
    private fun serve(response: String, capture: (String) -> Unit = {}): Int {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    capture(readHead(socket.getInputStream()))
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                }
            }
            runCatching { server.close() }
        }
        return server.localPort
    }

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val c = input.read()
            if (c == -1) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }
}
