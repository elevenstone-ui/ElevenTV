package com.eleventv.tv.ui.player

import java.util.Locale

/**
 * 外挂字幕的发现与匹配规则（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * ## 为什么要自己匹配而不是让 Media3 猜
 * Media3 只认显式传入的 SubtitleConfiguration，不会自己去目录里找同名字幕。
 * NAS 上的命名五花八门：完全同名、带语言后缀（.chs/.zh-Hans/简体）、
 * 字幕名比视频名少一串压制标记（.1080p.x264）、甚至整组文件名前后带发布组前缀。
 * 所以先把文件名归一化成 token 序列再比，避免把第 1 集的字幕挂到第 2 集上。
 *
 * ## 为什么返回多个而不是只挑一个
 * 一个视频常同时带简体/繁体/英文外挂字幕。全部挂上去，播放器的字幕面板里
 * 就能切换；默认显示哪一条交给 SubtitleLanguage 的简中打分逻辑决定——
 * 这样内嵌字幕与外挂字幕共用同一套「自动选简体中文」规则。
 */
data class ExternalSubtitleMatch(
    /** 字幕文件在目录里的原始文件名（用于拼 URI，不能改写） */
    val fileName: String,
    /** Media3 的 MIME，见 MIME_BY_EXT */
    val mimeType: String,
    /**
     * 传进 SubtitleConfiguration 的语言标签。无法从文件名推断时给 "zh"
     * （中文片库里未标注的外挂字幕大概率就是中文），让自动选轨能选上它；
     * 明确是其它语言时给对应标签，避免误当中文。
     */
    val language: String,
    /** 字幕面板里显示的名字 */
    val label: String,
    /** 匹配分，仅用于排序/限制数量 */
    val score: Int,
)

/** 只支持 Media3 能直接作为 SingleSample 文本字幕解析的格式 */
private val MIME_BY_EXT = mapOf(
    "srt" to "application/x-subrip",
    "ass" to "text/x-ssa",
    "ssa" to "text/x-ssa",
    "vtt" to "text/vtt",
    "ttml" to "application/ttml+xml",
    "dfxp" to "application/ttml+xml",
)

/** 字幕文件名尾部的语言标记：匹配时要剥掉，否则 `Movie.chs.srt` 配不上 `Movie.mkv` */
private val LANGUAGE_TAGS = mapOf(
    "chs" to "zh-Hans", "zh-hans" to "zh-Hans", "zh-cn" to "zh-Hans",
    "zh-sg" to "zh-Hans", "sc" to "zh-Hans", "gb" to "zh-Hans",
    "简体" to "zh-Hans", "簡體" to "zh-Hans", "简中" to "zh-Hans", "簡中" to "zh-Hans",
    "simplified" to "zh-Hans", "zh" to "zh",
    "cht" to "zh-Hant", "zh-hant" to "zh-Hant", "zh-tw" to "zh-Hant",
    "zh-hk" to "zh-Hant", "tc" to "zh-Hant", "big5" to "zh-Hant",
    "繁体" to "zh-Hant", "繁體" to "zh-Hant", "繁中" to "zh-Hant", "traditional" to "zh-Hant",
    "中文" to "zh", "chinese" to "zh", "chi" to "zh", "zho" to "zh",
    "eng" to "en", "en" to "en", "english" to "en", "英文" to "en",
    "jpn" to "ja", "jp" to "ja", "ja" to "ja", "日文" to "ja", "日本語" to "ja",
    "kor" to "ko", "ko" to "ko", "韩文" to "ko",
    "default" to null, "forced" to null, "默认" to null, "双语" to null, "bilingual" to null,
)

/** 压制/片源标记：既不算身份 token，也不影响匹配 */
private val NOISE_TOKENS = setOf(
    "1080p", "1080i", "720p", "2160p", "480p", "4k", "uhd", "fhd", "sd",
    "x264", "x265", "h264", "h265", "hevc", "avc", "10bit", "8bit",
    "bluray", "blu-ray", "bdrip", "brrip", "web-dl", "webdl", "webrip", "remux",
    "hdtv", "dvdrip", "hdr", "hdr10", "dv", "dolby", "vision", "atmos",
    "aac", "ac3", "dts", "flac", "truehd", "ddp5", "dd5",
    "amzn", "nf", "dsnp", "hmax", "atvp", "hulu", "bili", "iqiyi",
    "proper", "repack", "extended", "uncut", "complete",
)

private val SEPARATORS = Regex("""[._+\-–—/\\]+""")
private val BRACKETS = Regex("""[\[\(\{【（][^\[\]\(\)\{\}【】（）]*[\]\)\}】）]""")
private val SPACES = Regex("""\s+""")

/** 归一化成小写 token 序列：去括号组、把分隔符变空格 */
private fun tokensOf(stem: String): List<String> =
    SPACES.replace(
        SEPARATORS.replace(BRACKETS.replace(stem.lowercase(Locale.ROOT), " "), " "),
        " ",
    ).trim().split(' ').filter { it.isNotEmpty() }

/** 语言标记优先级：明确的简繁/外语 > 泛化的 "zh" > null */
private fun preferLanguage(current: String?, candidate: String?): String? = when {
    candidate == null -> current
    current == null -> candidate
    current == "zh" && candidate != "zh" -> candidate
    else -> current
}

/** 从括号组与 token 尾部剥掉语言标记，返回 (身份 tokens, 推断出的语言) */
private fun identityOf(stem: String): Pair<List<String>, String?> {
    val lower = stem.lowercase(Locale.ROOT)
    var language: String? = null

    // 字幕组惯用 `[chs]` / `【简体】`：括号组在归一化时会被整段剥掉，
    // 语言信息必须在这之前单独捞出来，否则只能退化成泛化的 zh。
    for (m in BRACKETS.findAll(lower)) {
        val inner = m.value.trim('[', ']', '(', ')', '{', '}', '【', '】', '（', '）')
        for (t in tokensOf(inner)) {
            language = preferLanguage(language, LANGUAGE_TAGS[t])
        }
    }

    val tokens = tokensOf(lower).toMutableList()
    // 尾部可能连着两个语言标记（如 `.zh-Hans.forced`），最多剥 2 个
    repeat(2) {
        val last = tokens.lastOrNull() ?: return@repeat
        if (LANGUAGE_TAGS.containsKey(last)) {
            language = preferLanguage(language, LANGUAGE_TAGS[last])
            tokens.removeAt(tokens.lastIndex)
        }
    }
    // 身份部分只去掉压制噪声；集号类 token（E01 / 01 / 第2集）必须保留，
    // 正是它用来防止「第 1 集的字幕挂到第 2 集」
    val identity = tokens.filter { it !in NOISE_TOKENS }
    return identity to language
}

/** 身份序列里出现的数字（集号信号） */
private fun episodeDigits(identity: List<String>): Set<String> =
    identity.flatMap { Regex("""\d+""").findAll(it).map { m -> m.value.trimStart('0').ifEmpty { "0" } }.toList() }
        .toSet()

/**
 * 在同级目录的文件名里找出「与视频同名或相似」的外挂字幕。
 *
 * @param videoFileName 视频文件名（含扩展名）
 * @param siblingNames 同目录下所有文件名（含视频自己）
 * @param maxResults 最多挂几条，避免几十条字幕一起拖慢起播
 * @return 按匹配分降序、已去重的结果；没有匹配返回空列表
 */
fun matchExternalSubtitles(
    videoFileName: String,
    siblingNames: List<String>,
    maxResults: Int = 8,
): List<ExternalSubtitleMatch> {
    val videoStem = videoFileName.substringBeforeLast('.')
    val (videoIdentity, _) = identityOf(videoStem)
    if (videoIdentity.isEmpty()) return emptyList()
    val videoKey = videoIdentity.joinToString(" ")
    val videoDigits = episodeDigits(videoIdentity)

    val subtitles = siblingNames.filter { name ->
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        // 视频扩展名不在这里判（由调用方保证 siblingNames 是全目录文件），只认字幕格式
        MIME_BY_EXT.containsKey(ext) && !name.equals(videoFileName, ignoreCase = true)
    }

    val out = mutableListOf<ExternalSubtitleMatch>()
    for (name in subtitles) {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val (identity, language) = identityOf(name.substringBeforeLast('.'))
        if (identity.isEmpty()) continue
        val key = identity.joinToString(" ")

        // 集号不一致一律不匹配：E01.mkv 绝不能挂 E02.chs.srt
        val digits = episodeDigits(identity)
        if (videoDigits.isNotEmpty() && digits.isNotEmpty() && digits != videoDigits) continue

        val score = when {
            key == videoKey -> 100
            // 一方是另一方的前缀，且多出来的部分只是压制噪声/语言标记
            key.startsWith(videoKey) || videoKey.startsWith(key) -> 80
            // 「相似」：归一化后有足够长的公共前缀
            commonPrefix(key, videoKey) >= 6 -> 55
            else -> 0
        }
        if (score == 0) continue

        out += ExternalSubtitleMatch(
            fileName = name,
            mimeType = MIME_BY_EXT.getValue(ext),
            // 没推断出语言就按中文兜底，保证自动选轨能选上；
            // 明确英文/日文等则原样保留，不会被误当简中
            language = language ?: "zh",
            label = "外挂·${name.substringBeforeLast('.')}",
            score = score,
        )
    }

    // 兜底：目录里只有这一个字幕、且只有一个视频，名字完全不同也挂上
    // （有些压制组把字幕统一命名成 subs.srt）。
    // 但「视频带集号」说明这是多集目录——此时名字对不上就是别的集的字幕，
    // 绝不能兜底，否则第 1 集会挂上第 2 集（或完全无关）的字幕。
    if (out.isEmpty() && subtitles.size == 1 && videoDigits.isEmpty()) {
        val videoCount = siblingNames.count { n ->
            val e = n.substringAfterLast('.', "").lowercase(Locale.ROOT)
            e !in MIME_BY_EXT && e.isNotEmpty()
        }
        if (videoCount <= 1) {
            val name = subtitles.first()
            val (_, language) = identityOf(name.substringBeforeLast('.'))
            out += ExternalSubtitleMatch(
                fileName = name,
                mimeType = MIME_BY_EXT.getValue(name.substringAfterLast('.').lowercase(Locale.ROOT)),
                language = language ?: "zh",
                label = "外挂·${name.substringBeforeLast('.')}",
                score = 40,
            )
        }
    }

    return out.sortedByDescending { it.score }.take(maxResults)
}

private fun commonPrefix(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}
