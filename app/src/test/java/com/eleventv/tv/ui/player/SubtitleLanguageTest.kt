package com.eleventv.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕自动选轨的识别规则回归用例。
 *
 * 这些分支在电视上要凑齐「简体 + 繁体 + 英文」多轨片源手工验证，代价太高；
 * 而「默认选成繁体」正是最常见的用户抱怨，所以在 JVM 上钉死。
 */
class SubtitleLanguageTest {

    @Test
    fun `明确简体中文优先于繁体`() {
        val simplified = simplifiedChineseScore("简体中文", "chi")
        val traditional = simplifiedChineseScore("繁體中文", "chi")
        assertTrue("简体应可选", simplified > 0)
        assertEquals("繁体必须一票否决", 0, traditional)
        assertTrue("简体得分应高于繁体", simplified > traditional)
    }

    @Test
    fun `CHS 与 CHT 标签区分`() {
        assertTrue(simplifiedChineseScore("CHS", "zho") > 0)
        assertEquals(0, simplifiedChineseScore("CHT", "zho"))
    }

    @Test
    fun `语言标签带简繁后缀时精确区分`() {
        assertTrue(simplifiedChineseScore(null, "zh-Hans") > 0)
        assertTrue(simplifiedChineseScore(null, "zh-CN") > 0)
        assertEquals(0, simplifiedChineseScore(null, "zh-Hant"))
        assertEquals(0, simplifiedChineseScore(null, "zh-TW"))
        assertEquals(0, simplifiedChineseScore(null, "zh-HK"))
    }

    @Test
    fun `Simplified 与 Traditional 英文标签`() {
        assertTrue(simplifiedChineseScore("Chinese (Simplified)", "zh") > 0)
        assertEquals(0, simplifiedChineseScore("Chinese (Traditional)", "zh"))
        assertTrue(simplifiedChineseScore("Simplified Chinese", "chi") > 0)
        assertEquals(0, simplifiedChineseScore("Traditional Chinese", "chi"))
    }

    @Test
    fun `只标通用中文语言时也能选上`() {
        // NAS 片源里最常被漏掉的形态：label 为空，language 只有 chi/zho/zh
        listOf("zh", "chi", "zho", "cmn").forEach { lang ->
            assertTrue("$lang 应可选", simplifiedChineseScore(null, lang) > 0)
        }
        assertTrue(simplifiedChineseScore("中文字幕", null) > 0)
    }

    @Test
    fun `非中文与未指定语言一律不选`() {
        assertEquals(0, simplifiedChineseScore("English", "eng"))
        assertEquals(0, simplifiedChineseScore("日本語", "jpn"))
        assertEquals(0, simplifiedChineseScore(null, "und"))
        assertEquals(0, simplifiedChineseScore(null, null))
        assertEquals(0, simplifiedChineseScore("", ""))
    }

    @Test
    fun `Chicago 里的 chi 不会被误当中文`() {
        // 短语言码只在 language 字段精确匹配，label 里的 "chi" 不算
        assertEquals(0, simplifiedChineseScore("Chicago", "eng"))
    }

    @Test
    fun `Forced 轨降权但仍可兜底选中`() {
        val full = simplifiedChineseScore("简体中文", "chi")
        val forced = simplifiedChineseScore("简体中文 Forced", "chi")
        assertTrue("Forced 应比完整轨低", forced < full)
        assertTrue("只有 Forced 时仍应能选中", forced > 0)
    }

    @Test
    fun `明确简体得分高于只写通用中文`() {
        // 多轨片源里若有「简体中文」和另一条只标 chi 的轨，应优先前者
        val explicit = simplifiedChineseScore("简体中文", "chi")
        val generic = simplifiedChineseScore(null, "chi")
        assertTrue(explicit > generic)
    }

    @Test
    fun `外挂字幕轨能被自动选简体逻辑识别`() {
        // matchExternalSubtitles 给外挂轨设的 label/language 必须能被 onTracksChanged
        // 的打分函数认出来，否则字幕挂上了却不会自动显示。
        val ext = matchExternalSubtitles("Movie.mkv", listOf("Movie.mkv", "Movie.chs.srt", "Movie.cht.srt"))
        val chs = ext.first { it.fileName == "Movie.chs.srt" }
        val cht = ext.first { it.fileName == "Movie.cht.srt" }
        assertTrue("外挂简中轨应可选", simplifiedChineseScore(chs.label, chs.language) > 0)
        assertEquals("外挂繁中轨不该被自动选中", 0, simplifiedChineseScore(cht.label, cht.language))
        assertTrue(
            "简体应优先于繁体",
            simplifiedChineseScore(chs.label, chs.language) > simplifiedChineseScore(cht.label, cht.language),
        )
    }

    @Test
    fun `未标注语言的外挂字幕靠 zh 兜底也能自动选上`() {
        val ext = matchExternalSubtitles("Movie.mkv", listOf("Movie.mkv", "Movie.srt"))
        assertTrue(simplifiedChineseScore(ext[0].label, ext[0].language) > 0)
    }
}
