package com.eleventv.tv.data.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件名解析的回归用例。
 *
 * 这些分支无法在电视上手工验证（要造一整套目录、插硬盘、等扫描），
 * 而规则一改就可能把一部剧拆成几十条独立条目——所以在 JVM 上钉住。
 *
 * 命名约定：`run { ... }` 里的第一个参数是文件名，第二个是共享内相对路径，
 * 与 [Scanner] 传给 [FilenameParser.parse] 的形式一致。
 */
class FilenameParserTest {

    private fun parse(fileName: String, relPath: String, numericEpisode: Boolean = false) =
        FilenameParser.parse(fileName, relPath, numericEpisode)

    // ---------------- 纯数字集号（本次修复的核心） ----------------

    @Test
    fun `目录里是 01 02 03 时纯数字文件名被认成集号`() {
        val p = parse("01.mkv", "剧集/Show/01.mkv", numericEpisode = true)
        assertEquals(1, p.episode ?: -1)
        assertEquals("Show", p.title)
    }

    @Test
    fun `没有目录佐证时纯数字文件名不认集号`() {
        // 电影目录里放了个 01.mkv，不能凭空变成「第 1 集」
        val p = parse("01.mkv", "电影/某片/01.mkv")
        assertNull(p.episode)
    }

    @Test
    fun `纯数字加单集标题能取出标题`() {
        val p = parse("01 婚礼.mkv", "剧集/Show/01 婚礼.mkv", numericEpisode = true)
        assertEquals(1, p.episode ?: -1)
        assertEquals("婚礼", p.episodeTitle)
        assertEquals("Show", p.title)
    }

    @Test
    fun `同一部剧的纯数字各集归到同一个键`() {
        val a = parse("01.mkv", "剧集/Show/01.mkv", numericEpisode = true)
        val b = parse("02.mkv", "剧集/Show/02.mkv", numericEpisode = true)
        assertEquals(a.mergeKey, b.mergeKey)
    }

    // ---------------- 每集一个文件夹 ----------------

    @Test
    fun `S01E01 目录里的文件靠目录名补全季集`() {
        val p = parse("01.mkv", "剧集/Show/S01E01/01.mkv")
        assertEquals(1, p.episode ?: -1)
        assertEquals(1, p.season ?: -1)
        assertEquals("Show", p.title)
    }

    @Test
    fun `中文集目录也能补全集号`() {
        val p = parse("feature.mkv", "剧集/Show/第2集/feature.mkv")
        assertEquals(2, p.episode ?: -1)
        assertEquals("Show", p.title)
    }

    @Test
    fun `集目录不会挤进剧名`() {
        val p = parse("01.mkv", "剧集/Show/S01E02/01.mkv")
        assertEquals("Show", p.title)
    }

    // ---------------- 多季合并 ----------------

    @Test
    fun `不同季的英语季目录合并到同一个键`() {
        val s1 = parse("Show.S01E01.mkv", "剧集/Show (2019)/Season 1/Show.S01E01.mkv")
        val s2 = parse("Show.S02E01.mkv", "剧集/Show (2019)/Season 2/Show.S02E01.mkv")
        assertEquals(s1.mergeKey, s2.mergeKey)
    }

    @Test
    fun `中文季目录与英语季目录合并到同一个键`() {
        val a = parse("Show.S01E01.mkv", "剧集/Show/Season 1/Show.S01E01.mkv")
        val b = parse("Show.S02E01.mkv", "剧集/Show 第二季/Show.S02E01.mkv")
        assertEquals(a.mergeKey, b.mergeKey)
    }

    @Test
    fun `特别篇目录不会把剧名变成 Specials`() {
        val main = parse("Show.S01E01.mkv", "剧集/Show/Season 1/Show.S01E01.mkv")
        val sp = parse("Show.S00E01.mkv", "剧集/Show/Specials/Show.S00E01.mkv")
        assertEquals("Show", sp.title)
        assertEquals(main.mergeKey, sp.mergeKey)
    }

    @Test
    fun `花絮目录不会把剧名变成 花絮`() {
        val p = parse("Show.S01E01.mkv", "剧集/Show/花絮/Show.S01E01.mkv")
        assertEquals("Show", p.title)
    }

    // ---------------- 短剧名 ----------------

    @Test
    fun `单字符剧名不会被目录名顶掉`() {
        // 《X》这类片名长度只有 1，以前会因为「太短」回落到目录名
        val p = parse("X.S01E01.mkv", "动漫/X/Season 1/X.S01E01.mkv")
        assertEquals("X", p.title)
    }

    @Test
    fun `OVA 目录不会拆出一个叫 OVA 的条目`() {
        val main = parse("X.S01E01.mkv", "动漫/X/Season 1/X.S01E01.mkv")
        val ova = parse("X.OVA.01.mkv", "动漫/X/OVA/X.OVA.01.mkv")
        assertEquals("X", ova.title)
        assertEquals(main.mergeKey, ova.mergeKey)
    }

    // ---------------- 类型大类 ----------------

    @Test
    fun `动漫与剧集归到同一个键`() {
        // 同一部剧的个别文件因为目录名带「动漫」被判成 ANIME 时，不该拆成两条
        val series = parse("Show.S01E01.mkv", "剧集/Show/Season 1/Show.S01E01.mkv")
        val anime = parse("Show.S01E01.mkv", "动漫/Show/Season 1/Show.S01E01.mkv")
        assertEquals(series.mergeKey, anime.mergeKey)
    }

    // ---------------- 回归：不能被新规则破坏 ----------------

    @Test
    fun `电影不因为文件名是年份被当成剧集`() {
        assertNull(parse("1917.mkv", "电影/1917.mkv").episode)
        assertNull(parse("2012.mkv", "电影/2012.mkv").episode)
        assertTrue(parse("1917.mkv", "电影/1917.mkv").mergeKey.startsWith("file|"))
    }

    @Test
    fun `片名里的数字不被当成集号`() {
        // Kill Bill - 2 复仇：集号必须是前导零或带版本号才认
        assertNull(parse("[Group] Kill Bill - 2 复仇.mkv", "电影/Kill Bill/[Group] Kill Bill - 2 复仇.mkv").episode)
    }

    @Test
    fun `字幕组的 - 01 写法能认出集号与单集标题`() {
        val p = parse("[Group] Show - 01 婚礼 [1080p].mkv", "剧集/Show/[Group] Show - 01 婚礼 [1080p].mkv")
        assertEquals(1, p.episode ?: -1)
        assertEquals("婚礼", p.episodeTitle)
        assertEquals("Show", p.title)
    }

    @Test
    fun `带版本号的集号不会丢`() {
        // 曾经把版本号一起截进数字段，"12v2".toIntOrNull() 返回 null，整集丢失
        val p = parse("[Group] Show - 12v2 标题.mkv", "剧集/Show/[Group] Show - 12v2 标题.mkv")
        assertEquals(12, p.episode ?: -1)
    }

    @Test
    fun `画质标记不会被当成单集标题`() {
        val p = parse("[Group] Show - 01 [1080p].mkv", "剧集/Show/[Group] Show - 01 [1080p].mkv")
        assertEquals(1, p.episode ?: -1)
        assertNull(p.episodeTitle)
    }

    @Test
    fun `标准 SxxExx 与 1x02 仍然可识别`() {
        assertEquals(3, parse("Show.S01E03.mkv", "剧集/Show/Season 1/Show.S01E03.mkv").episode ?: -1)
        assertEquals(3, parse("Show 1x03.mkv", "剧集/Show/Show 1x03.mkv").episode ?: -1)
    }

    // ---------------- 路径工具 ----------------

    @Test
    fun `episodeFromPath 认得出集目录`() {
        assertEquals(1 to 2, FilenameParser.episodeFromPath("剧集/Show/S01E02/01.mkv"))
        assertEquals(0 to 3, FilenameParser.episodeFromPath("剧集/Show/第3集/x.mkv"))
        assertNull(FilenameParser.episodeFromPath("剧集/Show/Season 1/x.mkv"))
    }

    @Test
    fun `seriesTitleFromPath 跳过容器与季目录`() {
        assertEquals("Show", FilenameParser.seriesTitleFromPath("剧集/Show/Season 1/x.mkv"))
        assertEquals("Show", FilenameParser.seriesTitleFromPath("剧集/Show/1/x.mkv"))
        assertEquals("Show", FilenameParser.seriesTitleFromPath("剧集/Show/Extras/x.mkv"))
        assertNull(FilenameParser.seriesTitleFromPath("剧集/x.mkv"))
    }

    @Test
    fun `同名不同剧的归一化键相同但会被集号冲突拆开`() {
        // mergeKey 相同是设计如此（靠 Scanner.splitByIdentity 用集号交集区分年份），
        // 这里只确认两部的键一致，避免有人误以为「键不同才是对的」
        val a = parse("The.Office.2001.S01E01.mkv", "剧集/The Office (2001)/Season 1/The.Office.2001.S01E01.mkv")
        val b = parse("The.Office.2005.S01E01.mkv", "剧集/The Office (2005)/Season 1/The.Office.2005.S01E01.mkv")
        assertEquals(a.mergeKey, b.mergeKey)
        assertEquals(2001, a.year ?: -1)
        assertEquals(2005, b.year ?: -1)
        assertNotEquals(a.year, b.year)
    }
}
