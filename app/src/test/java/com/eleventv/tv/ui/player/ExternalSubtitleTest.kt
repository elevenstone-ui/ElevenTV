package com.eleventv.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 外挂字幕匹配规则回归用例：多集目录里挂错集是最难在电视上发现的问题。 */
class ExternalSubtitleTest {

    @Test
    fun `完全同名去扩展名`() {
        val r = matchExternalSubtitles("Movie.mkv", listOf("Movie.mkv", "Movie.srt"))
        assertEquals(1, r.size)
        assertEquals("Movie.srt", r[0].fileName)
        assertEquals("application/x-subrip", r[0].mimeType)
    }

    @Test
    fun `带语言后缀的同名字幕`() {
        val names = listOf("Movie.mkv", "Movie.chs.srt", "Movie.cht.ass", "Movie.eng.srt")
        val r = matchExternalSubtitles("Movie.mkv", names)
        assertEquals(3, r.size)
        assertEquals("zh-Hans", r.first { it.fileName == "Movie.chs.srt" }.language)
        assertEquals("zh-Hant", r.first { it.fileName == "Movie.cht.ass" }.language)
        assertEquals("en", r.first { it.fileName == "Movie.eng.srt" }.language)
    }

    @Test
    fun `字幕名比视频名少压制标记也能挂上`() {
        val r = matchExternalSubtitles("Movie.1080p.x264.mkv", listOf("Movie.1080p.x264.mkv", "Movie.srt"))
        assertEquals(1, r.size)
        assertEquals("Movie.srt", r[0].fileName)
    }

    @Test
    fun `不同集的字幕绝不互挂`() {
        val names = listOf(
            "Show.S01E01.mkv", "Show.S01E02.mkv",
            "Show.S01E01.chs.srt", "Show.S01E02.chs.srt",
        )
        val ep1 = matchExternalSubtitles("Show.S01E01.mkv", names)
        assertTrue("第1集只应挂自己的字幕", ep1.any { it.fileName == "Show.S01E01.chs.srt" })
        assertFalse("不能把第2集字幕挂到第1集", ep1.any { it.fileName.contains("E02") })
        val ep2 = matchExternalSubtitles("Show.S01E02.mkv", names)
        assertFalse("不能把第1集字幕挂到第2集", ep2.any { it.fileName.contains("E01") })
    }

    @Test
    fun `纯数字集号也能区分`() {
        val names = listOf("01.mkv", "02.mkv", "01.srt", "02.srt")
        val r = matchExternalSubtitles("01.mkv", names)
        assertTrue(r.any { it.fileName == "01.srt" })
        assertFalse(r.any { it.fileName == "02.srt" })
    }

    @Test
    fun `集号相同时括号组与发布组前缀不影响匹配`() {
        val r = matchExternalSubtitles(
            "[Group] Show - 01 [1080p].mkv",
            listOf("[Group] Show - 01 [1080p].mkv", "[Group] Show - 01 [chs].srt"),
        )
        assertEquals(1, r.size)
        assertEquals("zh-Hans", r[0].language)
    }

    @Test
    fun `无关字幕不会误挂`() {
        val r = matchExternalSubtitles("Movie.S01E01.mkv", listOf("Movie.S01E01.mkv", "Totally.Other.Thing.srt"))
        assertTrue("名字毫无关联时不该挂", r.isEmpty())
    }

    @Test
    fun `目录里只有一个视频和一个字幕时兜底挂载`() {
        val r = matchExternalSubtitles("movie_final.mkv", listOf("movie_final.mkv", "subs.srt"))
        assertEquals(1, r.size)
        assertEquals("subs.srt", r[0].fileName)
    }

    @Test
    fun `多个视频时不启用唯一字幕兜底`() {
        val names = listOf("E01.mkv", "E02.mkv", "subs.srt")
        val r = matchExternalSubtitles("E01.mkv", names)
        assertTrue(r.isEmpty())
    }

    @Test
    fun `未标注语言的外挂字幕按中文兜底以便自动选轨`() {
        val r = matchExternalSubtitles("Movie.mkv", listOf("Movie.mkv", "Movie.srt"))
        assertEquals("zh", r[0].language)
    }

    @Test
    fun `不支持的格式不挂载`() {
        // 位图字幕 sup / idx 需要成对且 Media3 不能直接当 SingleSample 解析
        val r = matchExternalSubtitles("Movie.mkv", listOf("Movie.mkv", "Movie.sup", "Movie.idx", "Movie.nfo"))
        assertTrue(r.isEmpty())
    }

    @Test
    fun `最多挂载数量受限`() {
        val names = mutableListOf("Movie.mkv")
        repeat(20) { i -> names += "Movie.lang$i.srt" }
        val r = matchExternalSubtitles("Movie.mkv", names)
        assertTrue("应受 maxResults 限制", r.size <= 8)
    }

    @Test
    fun `结果按匹配分降序`() {
        val r = matchExternalSubtitles("Movie.mkv", listOf("Movie.mkv", "Movie.srt", "Movie.chs.srt"))
        assertTrue(r[0].score >= r.last().score)
    }
}
