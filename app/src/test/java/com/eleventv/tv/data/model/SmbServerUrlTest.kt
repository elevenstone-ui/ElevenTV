package com.eleventv.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WebDAV URL 构造。
 *
 * 这些用例都来自真实踩过的坑：整串 `URLEncoder.encode` 会把 `/` 编成 `%2F`（所有多级
 * 目录 404），而 `URLDecoder` 那套 `+` ↔ 空格 的规则会把 `EP01+SP.mkv` 解成带空格的
 * 名字（对不上服务器上的文件）。URL 拼错的表现是「能连上、列表却是空的」，很难排查，
 * 所以固定成测试。
 */
class SmbServerUrlTest {

    private fun dav(
        host: String = "nas.local",
        port: Int = 5005,
        basePath: String = "",
        tls: Boolean = false,
    ) = SmbServer(
        id = "srv-1",
        name = "NAS",
        host = host,
        port = port,
        share = "",
        protocol = StorageProtocol.WEBDAV,
        basePath = basePath,
        tls = tls,
    )

    @Test
    fun `默认端口与协议匹配时不写端口`() {
        assertEquals("http://nas.local", dav(port = 80).baseUrl)
        assertEquals("https://nas.local", dav(port = 443, tls = true).baseUrl)
    }

    @Test
    fun `自定义端口写进 URL`() {
        assertEquals("http://nas.local:5005", dav().baseUrl)
    }

    @Test
    fun `协议切到 WebDAV 但端口还是 445 时按标准端口算`() {
        // 用户在弹窗里只改了协议没改端口，拼出 http://nas:445 是必然连不上的
        assertEquals("http://nas.local", dav(port = 445).baseUrl)
    }

    @Test
    fun `路径前缀参与拼接且去掉多余斜杠`() {
        assertEquals("http://nas.local:5005/media", dav(basePath = "/media/").baseUrl)
        assertEquals("http://nas.local:5005/media", dav(basePath = "media").baseUrl)
    }

    @Test
    fun `文件路径逐段编码并保留斜杠`() {
        val url = dav(basePath = "media").davUrl("剧集/Show 01/01.mkv")
        assertEquals(
            "http://nas.local:5005/media/%E5%89%A7%E9%9B%86/Show%2001/01.mkv",
            url,
        )
    }

    @Test
    fun `文件名里的加号不能被空格化`() {
        val url = dav().davUrl("EP01+SP.mkv")
        assertEquals("http://nas.local:5005/EP01%2BSP.mkv", url)
    }

    @Test
    fun `根目录返回带尾斜杠的地址`() {
        assertEquals("http://nas.local:5005/", dav().davUrl(""))
    }

    @Test
    fun `SMB 配置的默认协议保持兼容`() {
        val legacy = SmbServer(id = "s1", name = "旧配置", host = "192.168.1.10", share = "media")
        assertEquals(StorageProtocol.SMB, legacy.protocol)
        assertEquals("", legacy.basePath)
        assertEquals(false, legacy.tls)
        // 旧 JSON 没有 protocol 字段，反序列化后仍要按 SMB 的默认端口工作
        assertEquals(445, legacy.port)
    }
}
