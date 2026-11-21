package com.eleventv.tv.ui.player

import java.util.Locale

/**
 * 字幕轨的「简体中文」识别，供自动选轨使用。
 *
 * ## 为什么不用 Media3 的 setPreferredTextLanguage("zh")
 *
 * MKV / MP4 里简体与繁体字幕的语言标签经常都写成 `chi` / `zho` / `zh`（ISO 639-2/T），
 * 语言字段根本不带简繁信息，真正的区分写在 label 上（「简体中文」/「繁體中文」、
 * `CHS` / `CHT`）。`preferredTextLanguage` 只会按顺序挑第一条匹配语言的中文字幕，
 * 「简体 + 繁体」并存的片源十有八九默认落到繁体轨上。
 *
 * 所以这里把 label 与 language 一起打分，由调用方用 `TrackSelectionOverride`
 * 精确锁定分数最高的那条轨。函数是纯 Kotlin、不依赖 Android/media3，便于 JVM 单测钉住规则。
 *
 * @return > 0 表示该轨可作为「简体中文」自动选中，数值越大越优先；0 = 不要选它。
 */
internal fun simplifiedChineseScore(label: String?, language: String?): Int {
    val l = (label ?: "").lowercase(Locale.ROOT)
    val lang = (language ?: "").lowercase(Locale.ROOT)
    val text = "$l $lang"
    if (text.isBlank()) return 0

    // 明确繁体：一票否决。必须放在通用中文判断之前——
    // 「繁體中文」也含「中文」、「zh-Hant」也含「zh」，先判通用就会误选繁体。
    if (TRADITIONAL_MARKERS.any { text.contains(it) }) return 0

    val base = when {
        // 明确简体：最高优先（简体中文 / CHS / Simplified / zh-Hans / zh-CN …）
        SIMPLIFIED_MARKERS.any { text.contains(it) } -> SCORE_EXPLICIT
        // 只写「中文 / zh / chi / zho」没区分简繁：NAS 片源里多数就是简体，
        // 给较低分，让它在「没有明确简体轨」时也能被选上，而不是干脆不出字幕。
        GENERIC_LABEL_MARKERS.any { l.contains(it) } ||
            lang in ISO_CHINESE ||
            lang.startsWith("zh-") -> SCORE_GENERIC
        else -> 0
    }
    if (base == 0) return 0

    // Forced 轨只含少量外语对白，别让它压过同语言的完整字幕轨；
    // 但仍保留正数——片源只有一条 Forced 简中轨时，选它总比不出字幕好。
    return if (FORCED_MARKERS.any { l.contains(it) }) base - FORCED_PENALTY else base
}

private const val SCORE_EXPLICIT = 100
private const val SCORE_GENERIC = 50
private const val FORCED_PENALTY = 25

private val TRADITIONAL_MARKERS = listOf(
    "繁体", "繁體", "繁中", "traditional", "cht",
    "zh-hant", "zh-tw", "zh-hk", "zh-mo", "big5",
)

private val SIMPLIFIED_MARKERS = listOf(
    "简体", "簡體", "简中", "簡中", "simplified", "chs",
    "zh-hans", "zh-cn", "zh-sg", "gbk", "gb2312",
)

/** 只用于 label：短语言码交给 [ISO_CHINESE] 精确匹配，避免 "Chicago" 里的 "chi" 误命中 */
private val GENERIC_LABEL_MARKERS = listOf("中文", "chinese", "国语", "國語")

/** 语言字段的精确取值。注意不含 "und"（未指定） */
private val ISO_CHINESE = setOf("zh", "chi", "zho", "cmn", "zh-hans", "zh-cn", "zh-sg")

private val FORCED_MARKERS = listOf("forced", "强制", "強制", "仅外语", "只外语")
