package com.eleventv.tv.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder as JsonDecoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 数据模型。
 *
 * 前半部分按 `spec/api-contract.yaml` 的结构建立（字段名与契约一致，客户端可忽略未知字段）；
 * 后半部分是本地 SMB 媒体库的实体——本应用没有服务端，这些实体是契约结构在客户端的落地形态。
 */

// ==================== 契约结构 ====================

@Serializable
data class RemoteImage(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val blurhash: String? = null,
    @SerialName("dominantColor") val dominantColor: String? = null,
)

@Serializable
data class ImageSet(
    val poster: RemoteImage? = null,
    val backdrop: RemoteImage? = null,
    val thumbnail: RemoteImage? = null,
)

@Serializable
data class PlaybackHint(
    val episodeId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * 海报角标。
 *
 * [HDR] / [DOLBY_VISION] 在卡片上画成图形标识（矢量字标），
 * [UHD] / [ANIME] 仍然用文字——「4K」两个字本身就是行业通用写法，做成图标反而认不出。
 */
@Serializable
enum class PosterBadge {
    /** 4K / UHD */
    UHD,

    /** 任意 HDR 规格（HDR / HDR10 / HDR10+ / HLG） */
    HDR,

    /** 杜比视界 */
    DOLBY_VISION,

    /** 动漫（没有技术角标时才显示） */
    ANIME,
}

/** 行/网格里的条目。 */
@Serializable
data class TitleSummary(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val images: ImageSet = ImageSet(),
    val rating: Double? = null,
    /**
     * 海报左上角角标，按显示顺序给出。
     *
     * 分辨率与动态范围是两条独立信息，二选一必然丢掉用户想看的那条；
     * 具体画文字还是画图标由 UI 决定，模型只表达「是什么」。
     */
    val badges: List<PosterBadge> = emptyList(),
    /** 0..1 */
    val progress: Float? = null,
    val playback: PlaybackHint? = null,
    /** 本地扩展：媒体类型，客户端据此决定进哪一屏 */
    val kind: MediaKind = MediaKind.MOVIE,
)

/** 首页一行。 */
@Serializable
data class Row(
    val id: String,
    val title: String,
    val cardType: CardType = CardType.Poster,
    val items: List<TitleSummary> = emptyList(),
)

/**
 * 卡片形态。
 *
 * 只有两种：竖版海报（网格/海报墙）与横版（「继续播放」）。曾经的 `Text` 分支
 * 在渲染层从来没有实现过，留着只会让人以为「有个纯文字行的功能」。
 */
@Serializable
enum class CardType { Poster, Wide }

@Serializable
data class HomeResponse(val rows: List<Row> = emptyList())

@Serializable
data class PageInfo(val cursor: String? = null, val hasMore: Boolean = false)

@Serializable
data class PagedTitles(val items: List<TitleSummary> = emptyList(), val page: PageInfo = PageInfo())

/** 错误结构：code 决定展示哪个错误态，retryable 决定是否给「重试」。 */
@Serializable
data class Problem(
    val code: String,
    val message: String,
    val retryable: Boolean = true,
    val retryAfterMs: Long? = null,
    val traceId: String? = null,
)

// ==================== 本地媒体库实体 ====================

/** 媒体类型。首页与分类页一一对应。 */
@Serializable
enum class MediaKind {
    MOVIE, SERIES, ANIME, VARIETY, DOCUMENTARY;

    val label: String
        get() = when (this) {
            MOVIE -> "电影"
            SERIES -> "剧集"
            ANIME -> "动漫"
            VARIETY -> "综艺"
            DOCUMENTARY -> "纪录片"
        }

    companion object {
        /**
         * 按刮削到的类型标签判定归类，命中不了返回 null（沿用扫描阶段的判断）。
         *
         * 扫描只能看文件名和目录名，`/剧集/进击的巨人/` 这种命名注定被猜成剧集；
         * TMDB 的类型是这部片**实际是什么**，所以刮完以它为准。
         * 顺序即优先级：动画 > 综艺 > 纪录片（「动画纪录片」这种极少，动画标签更具体）。
         */
        fun fromGenres(genres: List<String>): MediaKind? = when {
            genres.matchesAny("动画", "动漫", "animation", "anime") -> ANIME
            genres.matchesAny("真人秀", "脱口秀", "综艺", "reality", "talk", "variety") -> VARIETY

            genres.matchesAny("纪录片", "记录片", "纪实", "documentary") -> DOCUMENTARY
            else -> null
        }

        private fun List<String>.matchesAny(vararg keys: String): Boolean =
            any { genre -> keys.any { genre.contains(it, ignoreCase = true) } }
    }
}

/**
 * 一个媒体库根目录：共享内相对路径 + 用户指定的类型。
 *
 * [kind] 为 null 表示「自动」——由文件名/目录名解析推断；
 * 用户明确指定（如 Movie → 电影、Tv → 剧集）时扫描器直接采用，不再猜。
 */
@Serializable(with = MediaRootSerializer::class)
data class MediaRoot(
    val path: String,
    val kind: MediaKind? = null,
)

/**
 * [MediaRoot] 的兼容序列化。
 *
 * 旧版本把根目录存成纯字符串列表，升级后不能让用户的配置变成 `.corrupt`：
 * 反序列化时字符串与对象两种形态都接受；序列化恒写对象。
 */
object MediaRootSerializer : KSerializer<MediaRoot> {

    private val delegate = JsonElement.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: JsonDecoder): MediaRoot {
        val elem = decoder.decodeSerializableValue(delegate)
        return when (elem) {
            is JsonPrimitive -> MediaRoot(path = elem.jsonPrimitive.content.trim('/'), kind = null)
            is JsonObject -> MediaRoot(
                path = elem["path"]?.jsonPrimitive?.content?.trim('/') ?: "",
                kind = elem["kind"]?.jsonPrimitive?.content?.let { name ->
                    MediaKind.entries.firstOrNull { it.name == name }
                },
            )
            else -> MediaRoot("", null)
        }
    }

    override fun serialize(encoder: Encoder, value: MediaRoot) {
        encoder.encodeSerializableValue(
            delegate,
            buildJsonObject {
                put("path", value.path)
                value.kind?.let { put("kind", it.name) }
            },
        )
    }
}

/**
 * 连接协议。
 *
 * [SMB] 走 smbj 私有栈；[WEBDAV] 走标准 HTTP，取流交给 Media3 自带的 `HttpDataSource`。
 * 后者的价值不在「功能更多」，而在**连接管理是别人写的**：重连、退避、Range 断点续传
 * 都不需要自己维护——SMB 那边每一条都要手写，且每次 NAS 抖动都要重新调参。
 */
@Serializable
enum class StorageProtocol {
    SMB,
    WEBDAV;

    val label: String
        get() = when (this) {
            SMB -> "SMB"
            WEBDAV -> "WebDAV"
        }

    /** 该协议的默认端口 */
    val defaultPort: Int
        get() = when (this) {
            SMB -> 445
            WEBDAV -> 5005
        }
}

/** 网络存储服务器配置（SMB 或 WebDAV）。 */
@Serializable
data class SmbServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 445,
    /** 共享名，如 `media`、`video`；WebDAV 下不使用（恒为空） */
    val share: String,
    val username: String = "",
    val password: String = "",
    /** 共享内的起始路径，空 = 根（旧字段，保留兼容；新配置请用 [mediaRoots]） */
    val rootPath: String = "",
    /** 旧版多目录字段：纯路径字符串，无类型信息。保留兼容旧数据 */
    val rootPaths: List<String> = emptyList(),
    /**
     * 媒体库根目录列表：路径 + 类型映射（Movie → 电影、Tv → 剧集）。
     * 一个 NAS 上多类内容分目录存放时逐个选，扫描器逐根遍历、按指定类型归类。
     * 空表示沿用 [rootPaths] / [rootPath]。
     */
    val mediaRoots: List<MediaRoot> = emptyList(),
    val enabled: Boolean = true,
    // ---- 以下仅 WebDAV 使用 ----
    /** 连接协议。默认 SMB：旧配置反序列化后行为完全不变 */
    val protocol: StorageProtocol = StorageProtocol.SMB,
    /** 走 HTTPS。仅 WebDAV 有效；局域网自签证书无法验证，通常直接用 HTTP */
    val tls: Boolean = false,
    /** WebDAV 路径前缀（如 `/media`），空 = 服务器根 */
    val basePath: String = "",
) {
    val display: String get() = if (port == 445) host else "$host:$port"

    /**
     * WebDAV 根 URL。
     *
     * 端口按协议补默认值：从 SMB 切到 WebDAV 却没改端口时，`port` 还停在 445，
     * 直接拼进 URL 就是个必然连不上的地址——所以协议与端口不匹配时按标准端口算。
     */
    val baseUrl: String
        get() {
            val scheme = if (tls) "https" else "http"
            val standard = if (tls) 443 else 80
            val p = if (port == 445) standard else port
            val root = encodeUrlPath(basePath.trim('/'))
            return buildString {
                append(scheme).append("://").append(host.trim())
                if (p != standard) append(':').append(p)
                if (root.isNotEmpty()) append('/').append(root)
            }
        }

    /** 共享内相对路径 → 完整的 WebDAV 文件 URL */
    fun davUrl(relPath: String): String {
        val clean = encodeUrlPath(relPath.trim('/'))
        return if (clean.isEmpty()) "$baseUrl/" else "$baseUrl/$clean"
    }

    /** 实际生效的扫描根：mediaRoots 优先，依次回落到旧字段 */
    val roots: List<MediaRoot>
        get() = mediaRoots.map { it.copy(path = it.path.trim('/')) }
            .filter { it.path.isNotBlank() }
            .ifEmpty {
                rootPaths.map { MediaRoot(it.trim('/')) }.filter { it.path.isNotBlank() }
            }
            .ifEmpty { listOf(MediaRoot(rootPath.trim('/'))) }

    companion object {
        /**
         * 逐段百分号编码。
         *
         * 不能整串 `URLEncoder.encode`：那会把 `/` 也编成 `%2F`，所有多级目录全部 404。
         * 也不能完全不编：中文剧名、空格、`#`、`?` 在文件名里都很常见，不编就是 400 或串味。
         */
        internal fun encodeUrlPath(path: String): String =
            path.split('/').joinToString("/") { seg ->
                java.net.URLEncoder.encode(seg, "UTF-8")
                    .replace("+", "%20")
                    .replace("%2F", "/")
            }
    }
}

/** 单个媒体文件（一集或一部电影）。 */
@Serializable
data class MediaFile(
    /** 形如 `serverId|share|path`，全局唯一 */
    val id: String,
    val serverId: String,
    val share: String,
    val path: String,
    val fileName: String,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    /** 所在媒体根目录的类型覆写（用户在引导/设置里指定的），null = 按文件名自动推断 */
    val kindOverride: MediaKind? = null,
    val durationMs: Long = 0L,
    /** 单集标题（刮削后） */
    val episodeTitle: String? = null,
    /** 单集简介 */
    val episodeOverview: String? = null,
    /** 单集剧照 */
    val stillUrl: String? = null,
    /** 单集评分 */
    val rating: Double? = null,
    val quality: String? = null,
)

/**
 * 参演艺人。
 *
 * 只存展示与筛选需要的三个字段：名字（同时是筛选键）、角色名、头像。
 * 不存 TMDB 人物 id 之外的扩展信息——本地库没有人物详情页，
 * 点进去是「这个人在我库里还有哪些片」。
 */
@Serializable
data class CastMember(
    val name: String,
    /** 角色名，剧集可能为空（客串、配音） */
    val character: String? = null,
    val profileUrl: String? = null,
    val personId: Int? = null,
)

/** 媒体库条目：一部电影，或一部剧（含全部季与集）。 */
@Serializable
data class LibraryEntry(
    val id: String,
    val kind: MediaKind,
    /** 原始标题（来自文件名/文件夹名），刮削前显示它 */
    val rawTitle: String,
    val year: Int? = null,
    val files: List<MediaFile> = emptyList(),
    /** 最近添加时间（取文件修改时间最大值），用于「最近添加」排序 */
    val addedAt: Long = 0L,
    // ---- 以下由刮削填充 ----
    val title: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    /**
     * 参演艺人，按演职员表（billing）顺序。
     *
     * 刮削填充；老库没有这个字段，反序列化时按空表处理（`ignoreUnknownKeys` + 默认值），
     * 重新刮一次就会出现。
     */
    val cast: List<CastMember> = emptyList(),
    val runtimeMin: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val doubanId: String? = null,
    /** 季简介：seasonNumber → overview */
    val seasonOverviews: Map<Int, String> = emptyMap(),
    val seasonPosters: Map<Int, String> = emptyMap(),
    val scrapedAt: Long = 0L,
    val scrapeSource: String? = null,
) {
    val displayTitle: String get() = title ?: rawTitle

    /**
     * 菜单归类用的类型：**刮削到的标签优先**，没标签才用扫描阶段按文件名/目录猜的 [kind]。
     *
     * 只影响「归到哪个顶部菜单」，不影响播放形态——电影被归进动漫/纪录片时，
     * 详情页仍然按电影渲染（单文件、片长），所以这里的判断都留在 [kind] 上。
     * 用派生属性而不是在刮削时改写 [kind]：老库已经存了 genres，改完立即生效，
     * 不需要全库重新扫描或重新刮削。
     */
    val effectiveKind: MediaKind get() = MediaKind.fromGenres(genres) ?: kind

    /** 是否已刮削（有海报或简介即算） */
    val scraped: Boolean get() = scrapedAt > 0L

    val seasons: List<Int> get() = files.map { it.seasonNumber }.distinct().sorted()

    val episodeCount: Int get() = files.size

    /** 主文件：电影用第一个，剧集用第一集 */
    val primaryFile: MediaFile? get() = files.minByOrNull { it.seasonNumber * 1000 + it.episodeNumber }

    fun filesOfSeason(season: Int): List<MediaFile> =
        files.filter { it.seasonNumber == season }.sortedBy { it.episodeNumber }
}

/** 播放记录：续播与「继续播放」行的数据来源。 */
@Serializable
data class PlaybackRecord(
    val entryId: String,
    val fileId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 0..1 */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** 看到 95% 以上算看完，「继续播放」行不再显示 */
    val finished: Boolean get() = progress >= 0.95f
}

/** 扫描 / 刮削任务状态。 */
enum class JobStatus { Idle, Running, Done, Failed }

data class JobState(
    val status: JobStatus = JobStatus.Idle,
    val message: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val error: String? = null,
) {
    val fraction: Float
        get() = if (total <= 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f)
}

/** 应用设置。 */
@Serializable
data class AppSettings(
    // 刮削
    val scrapeEnabled: Boolean = true,
    val tmdbApiKey: String = "",
    val tmdbReadToken: String = "",
    val fanartApiKey: String = "",
    val scrapePoster: Boolean = true,
    val scrapeBackdrop: Boolean = true,
    val scrapeSeasonOverview: Boolean = true,
    val scrapeEpisodeOverview: Boolean = true,
    val preferChineseTitle: Boolean = true,
    val autoScrapeAfterScan: Boolean = true,
    // 扫描
    val autoScanOnStart: Boolean = true,
    val minFileSizeMb: Int = 50,
    val videoExtensions: List<String> = listOf("mkv", "mp4", "avi", "ts", "m2ts", "mov", "wmv", "flv", "rmvb", "iso"),
    // 播放
    val decoder: Decoder = Decoder.Auto,
    val resumePlayback: Boolean = true,
    val subtitleSizeScale: Float = 1f,
    val subtitleBgAlpha: Float = 0.6f,
    /** 自动下一集 */
    val autoNextEpisode: Boolean = true,
    /**
     * 启动音效。冷启动时播放一条与启动动画对齐的提示音
     *（逐字母「叮」+ 落位音），与 [M.SPLASH_ANIM] 同步。
     * 默认开；关掉后不再打扰。老设置向后兼容（旧 json 没此字段时走默认值 true）。
     */
    val splashSound: Boolean = true,
)

@Serializable
enum class Decoder {
    /** ExoPlayer 默认：能硬解就硬解，失败自动回落软解 */
    Auto,
    /** 强制硬解（MediaCodec） */
    Hardware,
    /** 强制软解（FFmpeg 扩展未打包时等价于 Audio/Video 软解不可用，仅降级首选） */
    Software;

    val label: String
        get() = when (this) {
            Auto -> "自动"
            Hardware -> "硬件解码"
            Software -> "软件解码"
        }
}

/**
 * 媒体库快照。
 *
 * 只装条目本身：`entries` 是唯一真相，UI 通过 StateFlow 感知变化。
 * 曾经还有一个 `scannedAt` 字段，但从来没有被读过（init 里就被重置成 0），
 * 留着只会让人以为「界面能显示上次扫描时间」。
 */
data class LibrarySnapshot(
    val entries: List<LibraryEntry> = emptyList(),
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}
