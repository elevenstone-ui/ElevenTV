package com.eleventv.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WebDAV 地址拼接。
 *
 * 这部分出错的表现是「扫描能连上、播任何一集都 404」或者「中文剧名整季消失」，
 * 在电视上排查成本极高，所以把边界用例钉死在这里。
 */
class ServerUrlTest {

    private fun dav(
        host: String = "192.168.1.10",
        port: Int = 5005,
        basePath: String = "",
        tls: Boolean = false,
        share: String = "media",
    ) = SmbServer(
        id = "srv-1",
        name = "NAS",
        host = host,
        port = port,
        share = share,
        protocol = StorageProtocol.WEBDAV,
        basePath = basePath,
        tls = tls,
    )

    @Test
    fun `默认端口 5005 会出现在 URL 里`() {
        assertEquals("http://192.168.1.10:5005", dav().baseUrl)
    }

    @Test
    fun `路径前缀进 URL 且不带多余斜杠`() {
        assertEquals("http://192.168.1.10:5005/media", dav(basePath = "/media/").baseUrl)
    }

    @Test
    fun `中文与空格逐段编码 斜杠保持原样`() {
        val url = dav().davUrl("剧集/Show 01/EP01.mkv")
        // `/` 必须保留：整串 URLEncoder.encode 会把它编成 %2F，所有多级目录直接 404
        assertEquals(
            "http://192.168.1.10:5005/%E5%89%A7%E9%9B%86/Show%2001/EP01.mkv",
            url,
        )
    }

    @Test
    fun `文件名里的加号不会被当成空格`() {
        assertEquals(
            "http://192.168.1.10:5005/EP01%2BSP.mkv",
            dav().davUrl("EP01+SP.mkv"),
        )
    }

    @Test
    fun `井号与问号会编码 避免被当成 URL 语法`() {
        assertEquals(
            "http://192.168.1.10:5005/a%23b%3Fc.mkv",
            dav().davUrl("a#b?c.mkv"),
        )
    }

    @Test
    fun `根路径带结尾斜杠`() {
        // PROPFIND Depth:1 打在没有结尾斜杠的目录上，部分 DAW 服务端会回 301 到带斜杠的地址
        assertEquals("http://192.168.1.10:5005/", dav().davUrl(""))
    }

    @Test
    fun `前缀与相对路径拼接不重复斜杠`() {
        assertEquals(
            "http://192.168.1.10:5005/media/a.mkv",
            dav(basePath = "media").davUrl("/a.mkv/"),
        )
    }

    @Test
    fun `误留 SMB 端口 445 时退回标准端口`() {
        // 从 SMB 切到 WebDAV 但端口没跟着改：拼出 :445 是必然连不上的地址
        assertEquals("http://192.168.1.10", dav(port = 445).baseUrl)
    }

    @Test
    fun `HTTPS 走标准 443 时不写端口`() {
        assertEquals("https://192.168.1.10", dav(port = 443, tls = true).baseUrl)
        assertEquals("https://192.168.1.10:5006", dav(port = 5006, tls = true).baseUrl)
    }

    @Test
    fun `两种协议的默认端口`() {
        assertEquals(445, StorageProtocol.SMB.defaultPort)
        assertEquals(5005, StorageProtocol.WEBDAV.defaultPort)
    }
}
