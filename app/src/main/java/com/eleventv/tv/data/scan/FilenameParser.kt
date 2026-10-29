package com.eleventv.tv.data.scan

import com.eleventv.tv.data.model.MediaKind

/**
 * 文件名解析结果。
 *
 * @param mergeKey 归并键：归一化剧名 + 类型大类，同一部剧的不同季在这里一定相同。
 *   条目 id 就由它派生（见 [Scanner.groupEntries]），改动等于所有播放记录失联。
 */
data class ParsedName(
    val title: String,
    val year: Int?,
    val kind: MediaKind,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val quality: String?,
    val mergeKey: String,
)

/**
 * 文件名解析。
 *
 * NAS 上的命名千奇百怪，这里只做「大多数情况对」的规则，剩下的交给刮削纠正：
 * 解析出的标题拿去搜 TMDB，搜到的标题又反过来覆盖显示名。
 */
object FilenameParser {

    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mkv", "mp4", "avi", "ts", "m2ts", "mov", "wmv", "flv",
        "rmvb", "rm", "iso", "mpg", "mpeg", "webm", "m4v", "divx",
    )

    // S01E02 / s1e2 / S01.E02 / S01 EP02
    private val SE = Regex("""(?i)\bS(\d{1,2})[\s._-]*(?:E|EP)(\d{1,4})\b""")
    // 1x02
    private val X = Regex("""(?i)(?<![0-9a-z])(\d{1,2})x(\d{1,3})(?![0-9a-z])""")
    // 第2季第5集 / 第二季第五集
    private val CN_SEASON_EP = Regex("""第\s*([0-9一二三四五六七八九十]+)\s*季.*?第\s*([0-9一二三四五六七八九十]+)\s*[集话話期]""")
    // 第5集 / 第05话 / EP05 / E05 / 05集
    private val CN_EP = Regex("""第\s*([0-9一二三四五六七八九十]+)\s*[集话話期]""")
    private val EP_ONLY = Regex("""(?i)(?<![a-z0-9])(?:EP?|Episode)[\s._-]*(\d{1,4})(?![0-9])""")
    private val TRAILING_NUM_EP = Regex("""(?i)[\s._-]+(\d{1,4})\s*(?:v\d)?$""")
    // 字幕组命名：[Group] Show - 01 [1080p] / [Group] Show - 12v2
    // 只在文件名以方括号组开头时启用，避免把「Kill Bill - 2」这类片名误判成第 2 集
    private val DASH_EP = Regex("""(?i)[\s._]+-[\s._]+(\d{1,4})(?:v\d)?(?=\s*[\[\(【（]|\s*$)""")
    /**
     * 集号后面直接跟单集标题：`[Group] Show - 01 标题`。
     *
     * 必须要求**前导零**：放宽成任意数字会立刻误伤 `[Group] Kill Bill - 2 复仇`
     * 这类「片名里带数字 + 后面跟中文」的命名，把电影当成第 2 集。
     */
    private val DASH_EP_TITLE = Regex("""(?i)[\s._]+-[\s._]+0(\d{1,3})(?:v\d)?\s+\S""")
    /**
     * 同上，但集号**不带前导零、带版本号**：`[Group] Show - 12v2 标题`。
     *
     * 版本号后缀本身就够有辨识度（片名里写 `- 2v2` 的情况几乎不存在），
     * 所以这里可以放开前导零要求，不会像裸数字那样误伤。
     */
    private val DASH_EP_VER = Regex("""(?i)[\s._]+-[\s._]+(\d{1,3})v\d\s+\S""")

    // 季目录：Season 1 / S01 / 第一季
    private val SEASON_DIR = Regex("""(?i)^(?:season|s)[\s._-]*(\d{1,2})$""")
    private val CN_SEASON_DIR = Regex("""^第\s*([0-9一二三四五六七八九十]+)\s*季$""")
    // 混在剧名里的季标记：`绝命毒师 第一季`、`Breaking Bad Season 2 (2009)`、`Show S02 [1080p]`
    private val CN_SEASON_ANY = Regex("""第\s*([0-9一二三四五六七八九十]+)\s*[季部]""")
    private val EN_SEASON_ANY = Regex("""(?i)(?<![a-z0-9])(?:season|s)[\s._-]*(\d{1,2})(?![0-9a-z])""")

    private val YEAR = Regex("""(?<![0-9])((?:19|20)\d{2})(?![0-9])""")

    private val Q_2160 = Regex("""(?i)\b(2160p|4k|uhd)\b""")
    private val Q_1080 = Regex("""(?i)\b(1080[pi]|fhd)\b""")
    private val Q_720 = Regex("""(?i)\b(720p)\b""")
    private val Q_480 = Regex("""(?i)\b(480p|sd)\b""")

    /**
     * 高动态范围标记。
     *
     * 顺序即优先级：杜比视界 > HDR10+ > HDR10 > HDR/HLG。
     * `DV` 前后都要卡边界，否则 `DVDRip` 会被当成杜比视界（两者都以 DV 开头）。
     * `杜比视界`/`杜比全景声` 是中文压制组常见的写法，杜比视界才影响画质角标。
     */
    private val Q_DV = Regex("""(?i)(\bdovi\b|\bdv\b|dolby[ ._-]?vision|杜比视界)""")
    private val Q_HDR10P = Regex("""(?i)(hdr10\+|hdr10plus|hdr10 ?plus)""")
    private val Q_HDR10 = Regex("""(?i)hdr10(?!\+)""")
    private val Q_HDR = Regex("""(?i)(\bhdr\b|hlg)""")

    /** 发布组/编码/音轨之类，出现即从标题里剔除 */
    private val NOISE = Regex(
        """(?i)\b(2160p|1080[pi]|720p|480p|4k|uhd|fhd|sd|""" +
            """bluray|blu-ray|bdrip|web-?dl|webrip|hdtv|dvdrip|remux|hdr10\+?|hdr|dv|dolby|vision|""" +
            """x264|x265|h\.?264|h\.?265|hevc|avc|10bit|8bit|aac|ac3|ddp?5\.1|dts|truehd|atmos|flac|""" +
            """chs|cht|chinese|eng|jpn|gb|big5|简中|繁中|中字|双语|内嵌|外挂|国语|日语|英语|""" +
            """ova|oad|ncop|nced|featurette|trailer|sample|""" +
            """proper|repack|extended|uncut|complete|collection)\b""",
    )
    /** 动漫目录：字幕组常用绝对集号，裸数字在这里可信 */
    private val ANIME_PATH = Regex("""(?i)(动漫|动画|番剧|新番|anime|animation)""")
    private val BRACKETS = Regex("""[\[\(\{【（][^\[\]\(\)\{\}【】（）]*[\]\)\}】）]""")
    private val SEPARATORS = Regex("""[._+\-–—|/\\]+""")
    private val SPACES = Regex("""\s+""")
    /** 结尾的 "-GROUP" 发布组标记 */
    private val GROUP_SUFFIX = Regex("""[\s._-]+[A-Za-z0-9]{2,12}$""")

    /**
     * 季号标记：`第二季` `第 2 部` `Season 2` `S02` `Complete` `全集`。
     *
     * 归一化剧名时必须先去掉它——`Show 第二季` 和 `Show` 在字符串层面是两个剧名，
     * 于是同一部剧被拆成两条，这正是「多季有的合并了有的没合并」的主因。
     * `S\d{1,2}` 后面要求不接字母数字，避免把 `S1m0ne` 这类片名切掉。
     */
    private val SEASON_MARK = Regex(
        """(?i)(第\s*[0-9一二三四五六七八九十]+\s*[季部期]|season\s*\d{1,2}|(?<![a-z0-9])s\d{1,2}(?![0-9a-z])|complete|全集|合集)""",
    )

    /** 结尾的独立年份（`Show 2021` / `Show.2021`） */
    private val TRAILING_YEAR = Regex("""[\s._-]+((?:19|20)\d{2})$""")

    private val DOC_WORDS = Regex("""(?i)\b(documentary|docu|bbc)\b""")
    private val DOC_CJK = listOf("纪录片", "记录片", "纪实")
    private val VARIETY_WORDS = Regex("""(?i)\b(variety|talkshow|reality)\b""")
    private val VARIETY_CJK = listOf("综艺", "脱口秀", "真人秀", "访谈")
    private val ANIME_WORDS = Regex("""(?i)\b(anime|animation|animated|cartoon|ova|oad)\b""")
    private val ANIME_CJK = listOf("动漫", "动画", "番剧", "新番", "剧场版")

    fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }

    /**
     * 解析一个视频文件。
     *
     * @param fileName 文件名（不含目录）
     * @param relPath 共享内相对路径（`/` 分隔），用于从目录名补全剧名与季号
     * @param numericEpisode 该文件所在目录被判定为「用 01/02/03 当集号」。
     *   纯数字文件名单看无从判断是片名还是集号，只能靠同目录的兄弟文件佐证，
     *   由 [Scanner] 统计完再传进来。
     */
    fun parse(
        fileName: String,
        relPath: String = "",
        numericEpisode: Boolean = false,
    ): ParsedName {
        val base = fileName.substringBeforeLast('.')
        val pathText = relPath.lowercase()
        val quality = detectQuality(base)

        // ---- 季 / 集 ----
        var season: Int? = null
        var episode: Int? = null
        var titlePart = base
        // 集号在文件名里的结束位置：单集标题从这里往后取。
        // 之前只认 SE / CN_EP 两种写法，于是 `[Group] Show - 01 标题`（DASH_EP）
        // 与 `Show 1x01 标题`（X）的单集标题永远取不到。
        var episodeEnd: Int? = null

        SE.find(base)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            titlePart = base.substring(0, m.range.first)
            episodeEnd = m.range.last + 1
        }
        if (episode == null) {
            X.find(base)?.let { m ->
                season = m.groupValues[1].toIntOrNull()
                episode = m.groupValues[2].toIntOrNull()
                titlePart = base.substring(0, m.range.first)
                episodeEnd = m.range.last + 1
            }
        }
        if (episode == null) {
            CN_SEASON_EP.find(base)?.let { m ->
                season = chineseToInt(m.groupValues[1])
                episode = chineseToInt(m.groupValues[2])
                titlePart = base.substring(0, m.range.first)
                episodeEnd = m.range.last + 1
            }
        }
        if (episode == null) {
            CN_EP.find(base)?.let { m ->
                episode = chineseToInt(m.groupValues[1])
                titlePart = base.substring(0, m.range.first)
                episodeEnd = m.range.last + 1
            }
        }
        if (episode == null) {
            EP_ONLY.find(base)?.let { m ->
                episode = m.groupValues[1].toIntOrNull()
                titlePart = base.substring(0, m.range.first)
                episodeEnd = m.range.last + 1
            }
        }
        // 兜底：末尾的裸数字（"某剧 05.mkv"）。
        // 裸数字很危险——「Kill Bill - 2」「Ocean's 11」都不是集号，误判成剧集后会去
        // search/tv，必然 0 命中。所以只有能确认剧集上下文时才采信：
        // 目录里有季号 / 数字带前导零 / 路径在动漫目录下（字幕组用绝对集号）。
        // 注意这里不能再看 `season`：走到这一步 `episode == null`，而 `season` 只在
        // 与 episode 同时赋值的那几个分支里被设置过，所以它必然是 null——曾经写在这里的
        // `season != null` 是一个永假条件，等于白写。
        if (episode == null) {
            TRAILING_NUM_EP.find(base)?.let { m ->
                val digits = m.groupValues[1]
                val n = digits.toIntOrNull()
                val looksLikeEpisode = seasonFromPath(relPath) != null ||
                    (digits.length > 1 && digits.startsWith("0")) ||
                    ANIME_PATH.containsMatchIn(pathText)
                if (n != null && n in 1..999 && looksLikeEpisode) {
                    episode = n
                    titlePart = base.substring(0, m.range.first)
                    episodeEnd = m.range.last + 1
                }
            }
        }
        // 字幕组绝对集号：[Group] Show - 01 [1080p] / [Group] Show - 01 标题
        if (episode == null && (base.trimStart().startsWith("[") || base.trimStart().startsWith("【"))) {
            // 先试「集号后面还有内容」的写法，再试严格写法：
            // DASH_EP 的 lookahead 只认「紧跟方括号」或「行尾」，`- 01 标题` 会被漏掉
            val m = DASH_EP_TITLE.find(base) ?: DASH_EP_VER.find(base) ?: DASH_EP.find(base)
            if (m != null) {
                // 从匹配起点往后扫第一段数字（含可选的 v2 版本后缀）。
                // 不用正则分组取位置：DASH_EP_TITLE 的捕获组只含前导零之后的部分，
                // 拿它去 indexOf 容易差一位，直接扫字符最稳。
                var i = m.range.first
                while (i < base.length && !base[i].isDigit()) i++
                var j = i
                while (j < base.length && base[j].isDigit()) j++
                // 数字段的结束位置要单独记：版本号后缀（v2）不属于集号，
                // 直接拿 j 去截会得到 "12v2" → toIntOrNull() 返回 null，集号丢失。
                val numEnd = j
                if (j < base.length && (base[j] == 'v' || base[j] == 'V')) {
                    var k = j + 1
                    while (k < base.length && base[k].isDigit()) k++
                    if (k > j + 1) j = k
                }
                val n = base.substring(i, numEnd).toIntOrNull()
                if (n != null && n in 1..999) {
                    episode = n
                    titlePart = base.substring(0, m.range.first)
                    episodeEnd = j
                }
            }
        }
        // 目录里全是 01/02/03 这种命名（[Scanner] 统计同目录兄弟文件后判定）。
        // 到这一步文件名已经证明自己没有季集标记，开头的数字就是集号；
        // `01 婚礼.mkv` 这种还能顺带把单集标题取出来。
        if (episode == null && numericEpisode) {
            val digits = base.takeWhile { it.isDigit() }
            val n = digits.takeIf { it.length in 1..3 }?.toIntOrNull()
            if (n != null && n in 1..999) {
                episode = n
                // 文件名里只有编号，剧名完全由目录提供
                titlePart = ""
                var end = digits.length
                while (end < base.length && base[end] in " ._-") end++
                episodeEnd = end
            }
        }
        // 每集一个文件夹：`/Show/S01E02/xxx.mkv`、`/Show/第 3 集/xxx.mkv`。
        // BDRip/原盘常这么放，文件名干脆是 `01.mkv` 甚至 `feature.mkv`，
        // 只看文件名永远认不出集号，整部剧就成了一堆独立"电影"。
        if (episode == null) {
            episodeFromPath(relPath)?.let { (s, e) ->
                episode = e
                if (season == null && s > 0) season = s
                // 集号来自目录，文件名里的部分多半是 `01`、`feature` 之类的噪声
                titlePart = ""
                episodeEnd = null
            }
        }

        // ---- 季号补全：来自目录 ----
        if (season == null) season = seasonFromPath(relPath)

        // ---- 年份 ----
        // 剧集的年份取「剧目录」上的那个：文件名里的年份基本是单集播出年或压制年，
        // 跟首播年不是一个东西（TMDB 的年份参数又是精确相等，差一年就 0 命中）。
        // 电影反过来——文件名里的年份就是上映年，更准。
        val year = if (episode != null) {
            seriesYearFromPath(relPath)
                ?: YEAR.find(base)?.groupValues?.get(1)?.toIntOrNull()
                ?: parentYear(relPath)
        } else {
            // 电影：文件名里可能既含片名数字又含上映年（Blade.Runner.2049.2017），上映年在后面
            YEAR.findAll(base).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                ?: parentYear(relPath)
        }

        // ---- 标题 ----
        // 文件名/目录名里常把季号与年份写进去（"Show 第二季"、"Show 2021"）。
        // 原样留着既显示难看，也会让同一部剧的不同季在归并时被拆成两个剧名。
        val pathTitle = seriesTitleFromPath(relPath)
        val rawTitle = cleanTitle(titlePart)

        var title = stripSeasonAndYear(rawTitle)
        // 只对「空 / 纯数字」回落到目录名，**不再因为太短就回落**：
        // 《X》《9》这类短剧名原先被长度检查驳回，`/动漫/X/OVA/` 里顶上来的
        // 就是 `OVA`，整部剧被拆成「X」和「OVA」两条。
        if (title.isBlank() || title.all { it.isDigit() }) {
            title = pathTitle?.let { stripSeasonAndYear(it) } ?: title
        }
        if (title.isBlank()) title = base

        // ---- 类型 ----
        val kind = detectKind(base, pathText)

        // ---- 单集标题（文件名在集号之后还有实义文字时） ----
        // 用「集号 token 的结束位置」往后取，而不是重新跑一遍 SE/CN_EP：
        // 后者只能覆盖这两种写法，`[Group] Show - 01 标题` 与 `Show 1x01 标题` 都漏。
        val afterEpisode = episodeEnd?.let { end -> base.substring(end.coerceAtMost(base.length)) }
        val episodeTitle = afterEpisode
            // 先去掉方括号组与压制噪声，再判断「还剩不剩实义文字」。
            // 不能直接看 cleanTitle 的结果：它在清洗后为空时会回落成原文，
            // 于是 `[Group] Show - 01 [1080p]` 的单集标题会变成 "[1080p]"。
            ?.let { rest -> NOISE.replace(BRACKETS.replace(rest, " "), " ") }
            ?.takeIf { rest -> rest.any { it.isLetter() } }
            ?.let { cleanTitle(it) }
            ?.takeIf { it.length >= 2 }

        // 有集号就是剧集。`kind != MOVIE` 这个条件恒为真（detectKind 只会返回
        // SERIES/ANIME/VARIETY/DOCUMENTARY），写在这里只是表达意图
        val isSeriesLike = episode != null && kind != MediaKind.MOVIE
        // 归并键：只认归一化剧名 + 类型大类，同一部剧的多季必然相同 → 合成一条
        //
        // 类型只保留大类：动漫与剧集都算 show。同一部剧里个别的文件如果因为
        // 目录名带 `OVA`/`动画` 字样被判成 ANIME，精确 kind 会让整部剧拆成
        // 「剧集」和「动漫」两条——这比类型混淆常见得多，而归类本来就有
        // `LibraryEntry.effectiveKind` 兜底。纪录片/综艺不并：同名综艺与剧集确实并存。
        val mergeKey = if (isSeriesLike) {
            val normalized = normalizeForGroup(title).ifBlank { title.lowercase() }
            "series|$normalized|${kindFamily(kind)}"
        } else {
            "file|$relPath"
        }

        return ParsedName(
            title = title,
            year = year,
            kind = if (isSeriesLike) kind else MediaKind.MOVIE,
            season = if (isSeriesLike) (season ?: 1) else null,
            episode = if (isSeriesLike) episode else null,
            episodeTitle = if (isSeriesLike) episodeTitle else null,
            quality = quality,
            mergeKey = mergeKey,
        )
    }

    /**
     * 归并用的类型大类。
     *
     * 动漫并入剧集：两者在归类上都属于「剧集」，而扫描阶段只能看目录名，
     * 同一部剧的个别文件很容易被判成 ANIME（目录里带了 `OVA`、`动画`），
     * 精确 kind 会把整部剧拆成两条。归类本身由 LibraryEntry.effectiveKind 兜底，
     * 不影响顶部菜单。
     */
    private fun kindFamily(kind: MediaKind): String = when (kind) {
        MediaKind.ANIME -> MediaKind.SERIES.name
        else -> kind.name
    }

    /**
     * 归一化剧名：去掉季号、年份、压制噪声与所有标点，只留「是哪部剧」这个信息。
     *
     * 只用于判断归属，不用于显示、也不用于搜索——它把年份也删了，
     * 拿去搜会把《Blade Runner 2049》搜成《Blade Runner》。
     */
    fun normalizeForGroup(raw: String): String {
        var s = SEASON_MARK.replace(raw, " ")
        s = YEAR.replace(s, " ")
        s = cleanTitle(s)
        // 只留字母数字（汉字也算字母）：标点、空格、全角括号都不该影响「是不是同一部剧」
        return s.lowercase().filter { it.isLetterOrDigit() }
    }

    /** 显示用：剥掉标题里的季号与结尾年份（`Show 第二季 2021` → `Show`） */
    private fun stripSeasonAndYear(raw: String): String {
        var s = SEASON_MARK.replace(raw, " ")
        TRAILING_YEAR.find(s)?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val rest = s.substring(0, m.range.first).trim()
            // 2049 > 2035 说明那是片名的一部分（Blade Runner 2049）；
            // rest 太短说明年份本身就是片名（1917、2012）
            if (y != null && y <= 2035 && rest.length >= 2) s = rest
        }
        s = SPACES.replace(s, " ").trim(' ', '-', '_', '.', '·')
        return if (s.length >= 2) s else raw
    }

    /**
     * 从相对路径里找季号。
     *
     * 季标记**混在剧名里也要认**（`绝命毒师 第一季`、`Breaking Bad Season 2 (2009)`）：
     * 只按整段目录名精确匹配时，这些目录会被当成普通剧名，季号全落到默认的 1，
     * 同一部剧的多季集号就会叠在一起（第 1 季第 5 集和第三季第 5 集互相覆盖）。
     */
    fun seasonFromPath(relPath: String): Int? {
        for (dir in relPath.split('/').dropLast(1).asReversed()) {
            seasonInName(dir.trim())?.let { return it }
        }
        return null
    }

    /** 目录名里的季号：`第二季` / `Season 2` / `S02`，允许与其他文字混排 */
    fun seasonInName(name: String): Int? {
        if (name.isEmpty()) return null
        CN_SEASON_ANY.find(name)?.let { return chineseToInt(it.groupValues[1]) }
        EN_SEASON_ANY.find(name)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /**
     * 从目录名取集号：`/Show/S01E02/xxx.mkv`、`/Show/第 3 集/xxx.mkv`。
     *
     * BDRip/原盘常把每集放一个文件夹，文件名干脆是 `01.mkv` 甚至 `feature.mkv`，
     * 只看文件名永远认不出集号——整部剧会变成一堆独立「电影」条目。
     *
     * @return (季号, 集号)；季号 0 表示目录里没写季
     */
    fun episodeFromPath(relPath: String): Pair<Int, Int>? {
        if (relPath.isBlank()) return null
        for (dir in relPath.split('/').dropLast(1).asReversed()) {
            val name = dir.trim()
            if (name.isEmpty()) continue

            SE.find(name)?.let { m ->
                val e = m.groupValues[2].toIntOrNull()
                if (e != null && e in 1..999) {
                    return (m.groupValues[1].toIntOrNull() ?: 0) to e
                }
            }
            CN_SEASON_EP.find(name)?.let { m ->
                val e = chineseToInt(m.groupValues[2])
                if (e != null && e in 1..999) {
                    return (chineseToInt(m.groupValues[1]) ?: 0) to e
                }
            }
            CN_EP.find(name)?.let { m ->
                val e = chineseToInt(m.groupValues[1])
                if (e != null && e in 1..999) return 0 to e
            }
            X.find(name)?.let { m ->
                val e = m.groupValues[2].toIntOrNull()
                if (e != null && e in 1..999) {
                    return (m.groupValues[1].toIntOrNull() ?: 0) to e
                }
            }
        }
        return null
    }

    /** 目录名本身写着季集编号（`S01E01`、`1x03`、`第 2 集`），不是剧名 */
    private fun isEpisodeDirName(name: String): Boolean =
        SE.containsMatchIn(name) ||
            CN_SEASON_EP.containsMatchIn(name) ||
            CN_EP.containsMatchIn(name) ||
            X.containsMatchIn(name)

    /**
     * 从相对路径取剧名：跳过季目录、碟号目录与常见容器目录。
     *
     * 只要求「清洗后还剩字母」，不再要求长度 ≥2：`/动漫/X/Season 1/` 里的 `X`
     * 因为是单字符被驳回，剧名就变成上层的 `OVA`/`Season`，整部剧被拆条。
     */
    fun seriesTitleFromPath(relPath: String): String? {
        if (relPath.isBlank()) return null
        val dirs = relPath.split('/').dropLast(1).asReversed()
        for (dir in dirs) {
            val name = dir.trim()
            if (name.isEmpty()) continue
            if (SEASON_DIR.matches(name) || CN_SEASON_DIR.matches(name)) continue
            // 纯数字目录是季号/碟号（`/Show/1/`、`/Show/02/`），不是剧名
            if (name.all { it.isDigit() }) continue
            if (DISC_DIR.matches(name)) continue
            // 目录名本身写着集号（`S01E01/`、`第2集/`）：每集一个文件夹的结构里，
            // 这些目录一旦被当成剧名，整部剧就变成「S01E01」「S01E02」… 若干条
            if (isEpisodeDirName(name)) continue
            if (name.lowercase() in CONTAINER_DIRS) continue
            // 整个目录名就是个方括号发布组（`[Group]`）时 cleanTitle 会回落成原文，
            // 不能把它当剧名
            if (BRACKETS.replace(name, " ").isBlank()) continue
            val cleaned = cleanTitle(name)
            if (cleaned.any { it.isLetter() }) return cleaned
        }
        return null
    }

    /**
     * 从相对路径取剧年份：跳过季目录与容器目录后的第一段目录里的年份。
     * `/剧集/Show (2019)/Season 2/xxx.mkv` → 2019
     */
    fun seriesYearFromPath(relPath: String): Int? {
        val dirs = relPath.split('/').dropLast(1).asReversed()
        for (dir in dirs) {
            val name = dir.trim()
            if (name.isEmpty()) continue
            // 带季标记的目录（`Season 2 (2009)`）里的年份是这一季的年份，不是首播年
            if (seasonInName(name) != null) continue
            YEAR.find(name)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 上级目录里的年份（`/电影/Inception (2010)/xxx.mkv`）。
     *
     * 必须**从离文件最近的一级目录往上找**：以前对整个父路径跑一次 `YEAR.find`，
     * 命中的是层级最高、最左边的那个年份——`/电影/2012 (2009)/xxx.mkv` 会取到 2012
     * （那是《2012》的片名，不是上映年 2009）。
     */
    private fun parentYear(relPath: String): Int? {
        for (dir in relPath.split('/').dropLast(1).asReversed()) {
            YEAR.find(dir)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /**
     * 不是剧名的容器目录。
     *
     * 漏掉一个，同一部剧就会被拆成两条：mergeKey 里的剧名变成 `OVA`、`花絮`，
     * 正片和它们再也合不到一起，详情页只剩半部剧。
     */
    private val CONTAINER_DIRS = setOf(
        "video", "videos", "movie", "movies", "tv", "tv shows", "tvshows",
        "media", "downloads", "download", "电影", "剧集", "电视剧", "动漫",
        "综艺", "纪录片", "影片", "视频", "nas", "share", "shared",
        // 正片之外的附属内容
        "specials", "special", "extra", "extras", "featurette", "featurettes",
        "trailer", "trailers", "sample", "samples", "ncop", "nced", "pv", "cm",
        "字幕", "subtitles", "subs", "sub", "花絮", "特典", "映像特典",
        "特别篇", "番外", "番外篇", "预告", "预告片", "幕后", "ost", "scans", "artwork",
    )

    /** 碟号目录：`CD1` / `Disc 2` / `Part 1`。里面装的是同一集的分片，不是剧名 */
    private val DISC_DIR = Regex("""(?i)^(?:cd|disc|disk|dvd|part)[\s._-]*\d{1,2}$""")

    /** 分辨率角标。供 UI 从已入库的文件名回算——旧库没有这个字段时也能显示。 */
    fun qualityOf(fileName: String): String? = detectQuality(fileName.substringBeforeLast('.', fileName))

    /**
     * 动态范围角标：`DV` / `HDR10+` / `HDR10` / `HDR`，无则 null。
     *
     * 只从文件名推断，不读媒体流——TV 上逐文件解封装开 Profile 的代价远超收益，
     * 而且片源命名里几乎一定写了 HDR/DV（没写的片基本也不是 HDR 源）。
     */
    fun dynamicRangeOf(fileName: String): String? = detectDynamicRange(fileName.substringBeforeLast('.', fileName))

    private fun detectDynamicRange(text: String): String? = when {
        Q_DV.containsMatchIn(text) -> "DV"
        Q_HDR10P.containsMatchIn(text) -> "HDR10+"
        Q_HDR10.containsMatchIn(text) -> "HDR10"
        Q_HDR.containsMatchIn(text) -> "HDR"
        else -> null
    }

    private fun detectQuality(text: String): String? = when {
        Q_2160.containsMatchIn(text) -> "4K"
        Q_1080.containsMatchIn(text) -> "1080P"
        Q_720.containsMatchIn(text) -> "720P"
        Q_480.containsMatchIn(text) -> "480P"
        else -> null
    }

    private fun detectKind(fileName: String, pathText: String): MediaKind {
        val all = "$pathText/$fileName"
        return when {
            DOC_WORDS.containsMatchIn(all) || DOC_CJK.any { all.contains(it) } -> MediaKind.DOCUMENTARY
            VARIETY_WORDS.containsMatchIn(all) || VARIETY_CJK.any { all.contains(it) } -> MediaKind.VARIETY
            ANIME_WORDS.containsMatchIn(all) || ANIME_CJK.any { all.contains(it) } -> MediaKind.ANIME
            else -> MediaKind.SERIES
        }
    }

    /** 清洗标题：去括号组、发布组、编码噪声、多余分隔符 */
    fun cleanTitle(raw: String): String {
        var s = raw
        s = BRACKETS.replace(s, " ")
        s = NOISE.replace(s, " ")
        // 末尾的 "-GROUP" 发布组标记：只有全大写或带数字才认。
        // 原来只要「含大写字母」就切，结果 "Sousou no Frieren" 被切成 "Sousou no"，
        // 查询直接废掉——这是动漫/英文片名刮不到的常见原因。
        // 另外要求这个 token 里得有字母：纯数字是片名的一部分（"Blade Runner 2049"、
        // "Ocean's 11"），当成发布组切掉会让搜索命中同名老片。
        s = GROUP_SUFFIX.find(s)
            ?.takeIf { m ->
                val token = m.value.dropWhile { it in " ._-" }
                token.length in 2..12 &&
                    token.any { it.isLetter() } &&
                    (token.none { it.isLowerCase() } || token.any { it.isDigit() })
            }
            ?.let { s.substring(0, it.range.first) } ?: s
        s = SEPARATORS.replace(s, " ")
        s = SPACES.replace(s, " ").trim()
        s = s.trim(' ', '-', '_', '.', '[', ']', '【', '】', '(', ')')
        return s.ifBlank { raw.trim() }
    }

    private fun chineseToInt(text: String): Int? {
        val t = text.trim()
        t.toIntOrNull()?.let { return it }
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )
        if (t == "十") return 10
        if (t.length == 1) return digits[t[0]]
        // 十一 / 二十 / 二十三
        val idx = t.indexOf('十')
        if (idx >= 0) {
            val tens = if (idx == 0) 1 else digits[t[idx - 1]] ?: return null
            val ones = if (idx == t.lastIndex) 0 else digits[t[idx + 1]] ?: return null
            return tens * 10 + ones
        }
        return null
    }

    private fun <T> T?.orNull(): T? = this
}
