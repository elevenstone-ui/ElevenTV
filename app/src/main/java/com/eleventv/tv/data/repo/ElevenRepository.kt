package com.eleventv.tv.data.repo

import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.JobState
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.LibrarySnapshot
import com.eleventv.tv.data.model.MediaFile
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.model.PlaybackRecord
import com.eleventv.tv.data.model.Row
import com.eleventv.tv.data.model.SmbServer
import com.eleventv.tv.data.smb.SmbEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * 数据层接口。
 *
 * 页面只依赖这个接口，不认识 SMB、TMDB 或 JSON 文件——换成别的数据源只需要换实现。
 */
interface ElevenRepository {

    // ---- 状态 ----

    val servers: StateFlow<List<SmbServer>>
    val settings: StateFlow<AppSettings>
    val library: StateFlow<LibrarySnapshot>
    val playback: StateFlow<List<PlaybackRecord>>
    val scanJob: StateFlow<JobState>
    val scrapeJob: StateFlow<JobState>

    /**
     * 本地配置是否已载入完成。
     *
     * 启动时 servers 一开始是空列表，如果不区分「尚未载入」与「确实没服务器」，
     * 界面会先闪一下「添加服务器」引导再跳回首页。
     */
    val ready: StateFlow<Boolean>

    /** 启动时载入本地配置与上次的媒体库快照 */
    suspend fun init()

    // ---- 服务器 ----

    suspend fun testServer(server: SmbServer): Result<Unit>

    /** 浏览目录，用于添加服务器时选择根路径 */
    suspend fun browse(server: SmbServer, path: String): Result<List<SmbEntry>>

    suspend fun saveServer(server: SmbServer)
    suspend fun deleteServer(id: String)

    // ---- 设置 ----

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings)

    // ---- 扫描 / 刮削 ----

    suspend fun scan()
    suspend fun scrapeAll(force: Boolean = false)
    suspend fun scrapeOne(entryId: String)
    suspend fun clearScraped()

    // ---- 查询 ----

    /** 首页行：继续播放 + 五个类型行，空行自动隐藏 */
    fun homeRows(): List<Row>

    /** 分类页：某类型的全部条目 */
    fun titlesOf(kind: MediaKind): List<LibraryEntry>

    /**
     * 同一类型标签的全部条目。
     *
     * [moviesOnly] 把电影与剧集分开：用户点详情页里的「科幻」想看的是「还有哪些科幻**片**」，
     * 把 5 部电影和 30 部剧混在一个海报墙里，等于把筛选当成了噪音。
     */
    fun titlesOfGenre(genre: String, moviesOnly: Boolean): List<LibraryEntry>

    /** 同一艺人参演的全部条目，同样按电影 / 剧集分组 */
    fun titlesOfCast(name: String, moviesOnly: Boolean): List<LibraryEntry>

    fun entry(id: String): LibraryEntry?

    fun recordOf(entryId: String): PlaybackRecord?

    /** 该条目上次看到哪一集 */
    fun resumeFile(entryId: String): MediaFile?

    // ---- 播放记录 ----

    fun saveProgress(entryId: String, file: MediaFile, positionMs: Long, durationMs: Long)
}
