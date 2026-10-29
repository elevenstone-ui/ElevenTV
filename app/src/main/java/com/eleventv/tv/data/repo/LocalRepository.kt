package com.eleventv.tv.data.repo

import android.util.Log
import com.eleventv.tv.BuildConfig
import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.CardType
import com.eleventv.tv.data.model.JobState
import com.eleventv.tv.data.model.JobStatus
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.LibrarySnapshot
import com.eleventv.tv.data.model.MediaFile
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.model.PlaybackRecord
import com.eleventv.tv.data.model.Row
import com.eleventv.tv.data.model.SmbServer
import com.eleventv.tv.data.model.StorageProtocol
import com.eleventv.tv.data.scan.Scanner
import com.eleventv.tv.data.scrape.Scraper
import com.eleventv.tv.data.scrape.TmdbSource
import com.eleventv.tv.data.smb.SmbEntry
import com.eleventv.tv.data.smb.SmbPool
import com.eleventv.tv.data.store.JsonStore
import com.eleventv.tv.data.store.SecretBox
import com.eleventv.tv.data.webdav.WebDavStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地实现：SMB 扫描 + 四源刮削 + 本地 JSON 持久化。
 *
 * 没有服务端，所有数据都在本机 `filesDir/eleventv/` 下，不上传任何内容
 * （刮削时只把片名发给数据源）。
 */
class LocalRepository(
    filesDir: File,
    private val scope: CoroutineScope,
) : ElevenRepository {

    private val dir = File(filesDir, "eleventv").apply { mkdirs() }
    private val serverStore = JsonStore(File(dir, "servers.json"))
    private val settingsStore = JsonStore(File(dir, "settings.json"))
    private val libraryStore = JsonStore(File(dir, "library.json"))
    private val playbackStore = JsonStore(File(dir, "playback.json"))

    private val serversSerializer = ListSerializer(SmbServer.serializer())
    private val entriesSerializer = ListSerializer(LibraryEntry.serializer())
    private val playbackSerializer = ListSerializer(PlaybackRecord.serializer())

    private val pool = SmbPool()

    private val _servers = MutableStateFlow<List<SmbServer>>(emptyList())
    private val _settings = MutableStateFlow(AppSettings())
    private val _library = MutableStateFlow(LibrarySnapshot())
    private val _playback = MutableStateFlow<List<PlaybackRecord>>(emptyList())
    private val _scanJob = MutableStateFlow(JobState())
    private val _scrapeJob = MutableStateFlow(JobState())
    private val _ready = MutableStateFlow(false)

    override val servers: StateFlow<List<SmbServer>> = _servers.asStateFlow()
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    override val library: StateFlow<LibrarySnapshot> = _library.asStateFlow()
    override val playback: StateFlow<List<PlaybackRecord>> = _playback.asStateFlow()
    override val scanJob: StateFlow<JobState> = _scanJob.asStateFlow()
    override val scrapeJob: StateFlow<JobState> = _scrapeJob.asStateFlow()
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val scanMutex = Mutex()
    private val scrapeMutex = Mutex()
    private val settingsMutex = Mutex()

    /** 两个文件的落盘闸门：同一份文件永远只有一个协程在写，旧快照不会覆盖新快照 */
    private val libraryGate = SaveGate()
    private val playbackGate = SaveGate()

    /** 只保护「取快照 + 领版本号」这一步，不参与 IO */
    private val saveLock = Any()

    override suspend fun init() {
        withContext(Dispatchers.IO) {
            // 落盘时加密了 SMB 密码（见 SecretBox）；内存里保持明文，连接时要用
            _servers.value = serverStore.read(serversSerializer, emptyList())
                .map { it.copy(password = SecretBox.open(it.password)) }
            _settings.value = settingsStore.read(AppSettings.serializer(), defaultSettings())
            _library.value = LibrarySnapshot(libraryStore.read(entriesSerializer, emptyList()))
            _playback.value = playbackStore.read(playbackSerializer, emptyList())
            _ready.value = true
            Log.i(TAG, "已载入 ${_servers.value.size} 个服务器、${_library.value.entries.size} 个条目")
        }
    }

    /**
     * 播放器数据源用的连接池。
     *
     * 必须与扫描共用同一个池：播放中的连接带着活动句柄，池会跳过回收；
     * 另起一个池等于让 ExoPlayer 独占一条连接，扫描时又要重建一次握手。
     */
    val smb: SmbPool get() = pool

    /** 播放器按 serverId 找回配置，拿到 host/端口/凭证 */
    fun serverOf(serverId: String): SmbServer? =
        _servers.value.firstOrNull { it.id == serverId }

    /** 构造期凭证来自 BuildConfig，用户填过则以用户为准 */
    private fun defaultSettings() = AppSettings(
        tmdbApiKey = BuildConfig.TMDB_API_KEY,
        tmdbReadToken = BuildConfig.TMDB_READ_TOKEN,
        fanartApiKey = BuildConfig.FANART_API_KEY,
    )

    /** 落盘前把密码加密：`servers.json` 被 root 或备份工具读走时拿不到明文 */
    private fun persistServers() {
        serverStore.write(
            serversSerializer,
            _servers.value.map { it.copy(password = SecretBox.seal(it.password)) },
        )
    }

    // ---------------- 服务器 ----------------

    override suspend fun testServer(server: SmbServer): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 每个媒体库根目录都要实际列一次：某个根填错了当场暴露，不留到扫描时才发现
            when (server.protocol) {
                StorageProtocol.SMB -> server.roots.forEach { root ->
                    pool.with(server) { conn ->
                        val items = conn.list(root.path)
                        Log.i(TAG, "连接成功：${server.display}/${server.share} 根「${root.path}」${items.size} 项")
                    }
                }
                StorageProtocol.WEBDAV -> WebDavStorage(server).use { dav ->
                    server.roots.forEach { root ->
                        val items = dav.list(root.path)
                        Log.i(TAG, "连接成功：${server.baseUrl} 根「${root.path}」${items.size} 项")
                    }
                }
            }
            Unit
        }
    }

    override suspend fun browse(server: SmbServer, path: String): Result<List<SmbEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (server.protocol) {
                    StorageProtocol.SMB -> pool.with(server) { conn -> conn.list(path) }
                    // 引导页的目录选择对两种协议共用：统一成 SmbEntry（只是字段容器）
                    StorageProtocol.WEBDAV -> WebDavStorage(server).use { dav ->
                        dav.list(path).map {
                            SmbEntry(
                                name = it.name,
                                isDirectory = it.isDirectory,
                                sizeBytes = it.sizeBytes,
                                modifiedAt = it.modifiedAt,
                                createdAt = it.createdAt,
                            )
                        }
                    }
                }
            }
        }

    override suspend fun saveServer(server: SmbServer) = withContext(Dispatchers.IO) {
        val list = _servers.value.filterNot { it.id == server.id } + server
        _servers.value = list.sortedBy { it.name }
        persistServers()
        // 连接参数可能变了，旧连接作废
        pool.invalidate(server.id)
    }

    override suspend fun deleteServer(id: String) = withContext(Dispatchers.IO) {
        _servers.value = _servers.value.filterNot { it.id == id }
        persistServers()

        // 该服务器的条目一并下架，否则首页会出现点不开的幽灵卡片。
        //
        // 注意是「裁剪文件」而不是「整条过滤」：同一个条目可能同时有 A、B 两台服务器的
        // 文件（同一部剧分两次下载/两台 NAS 各放几季）。以前只要有一个文件在别的服务器上
        // 就整条保留，于是那些指向已删服务器的集还留在详情页里，点播放必然「找不到文件」。
        val remaining = _library.value.entries.mapNotNull { entry ->
            val kept = entry.files.filterNot { it.serverId == id }
            when {
                kept.isEmpty() -> null                       // 整条都属于这台服务器
                kept.size == entry.files.size -> entry        // 与这台服务器无关
                else -> entry.copy(files = kept)
            }
        }

        // 被裁掉的文件对应的播放记录也要清：entryId 还在，但 fileId 已经不存在了，
        // 详情页会显示「继续观看」却找不到那一集
        val aliveEntryIds = remaining.mapTo(HashSet()) { it.id }
        val aliveFileIds = remaining.flatMapTo(HashSet()) { e -> e.files.map { it.id } }
        val prunedPlayback = _playback.value.filter { rec ->
            rec.entryId in aliveEntryIds && rec.fileId in aliveFileIds
        }

        _library.value = LibrarySnapshot(remaining)
        _playback.value = prunedPlayback
        saveLibrary()
        savePlayback()
        pool.invalidate(id)
    }

    // ---------------- 设置 ----------------

    /**
     * 改设置。
     *
     * 整段（读 → 变换 → 写盘）必须串行：设置页和播放器都会改设置（切换解码器、字幕样式），
     * 并发时「读-改-写」会丢更新，还会出现「旧快照后写盘」把新值盖回去。
     * 设置改动频率极低，用互斥锁的代价可以忽略。
     */
    override suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        withContext(Dispatchers.IO) {
            settingsMutex.withLock {
                val next = transform(_settings.value)
                _settings.value = next
                settingsStore.write(AppSettings.serializer(), next)
            }
        }
    }

    // ---------------- 扫描 ----------------

    override suspend fun scan() {
        if (!scanMutex.tryLock()) return
        try {
            val servers = _servers.value
            if (servers.isEmpty()) {
                _scanJob.value = JobState(JobStatus.Failed, error = "尚未添加 NAS 服务器")
                return
            }

            _scanJob.value = JobState(JobStatus.Running, "正在连接服务器…")
            val previous = _library.value.entries
            // 用「共享文件」而不是 id 来认领上一轮的刮削结果：文件路径是稳定的，
            // 而 id 依赖归并结果（季数、命名写法一变，历史实现算出的 id 就会漂移）
            val previousByFile = HashMap<String, LibraryEntry>()
            for (old in previous) for (f in old.files) previousByFile[f.id] = old

            val result = Scanner(pool).scan(servers, _settings.value) { p ->
                _scanJob.value = JobState(
                    status = JobStatus.Running,
                    message = "扫描中：${p.currentPath.takeLast(48)}",
                    current = p.scanned,
                    total = 0,
                )
            }
            // 扫描是整表重建；刮削结果必须继承下来，否则每扫一次海报就全掉，
            // 又得重刮——网络不稳时就会永远停在「只刮到一部分」的状态
            val fresh = result.entries.map { entry ->
                // 一个 bucket 可能同时与多条旧条目有交集（归并规则变了），取交集最多的那条
                val oldId = entry.files.mapNotNull { previousByFile[it.id] }
                    .groupingBy { it.id }.eachCount()
                    .maxByOrNull { it.value }?.key
                entry.inheritScrape(oldId?.let { id -> previous.firstOrNull { it.id == id } })
            }

            // 到这里为止，「整库重建」还是不可逆的：下面一写盘，上一份媒体库（含全部
            // 刮削结果）就没了。所以先做两件事。
            //
            // 1) 整库清零时不动旧库。启动时会自动扫一次（autoScanOnStart），
            //    NAS 没连上 / 共享名填错 / 权限变了的时候，扫描会「成功」地返回 0 条，
            //    于是在启动瞬间把用户攒了几百部的库覆盖成空——重启一次库就空了，
            //    正是这个原因。读不到东西该报错，不该当成「用户把片子删光了」。
            if (fresh.isEmpty() && previous.isNotEmpty()) {
                _scanJob.value = JobState(
                    status = JobStatus.Failed,
                    error = buildString {
                        append("这次扫描一个视频都没读到")
                        if (result.failedDirs > 0) append("（${result.failedDirs} 个目录读取失败）")
                        append("，已保留上一次的媒体库。请确认 NAS 在线、共享名与媒体库根目录是否正确。")
                    },
                )
                return
            }

            // 2) 有目录读失败时做增量合并：这次没见到的旧条目原样留着。
            //    「网络抖一下，库里少一半」和「真的删了几部片」在扫描结果里长得一模一样，
            //    而前者是可恢复的、后者下次干净扫描就能收敛——所以宁可多留一次。
            val entries = if (result.failedDirs > 0) {
                val seen = fresh.flatMapTo(HashSet()) { e -> e.files.map { it.id } }
                val kept = previous.filter { old ->
                    fresh.none { it.id == old.id } && old.files.none { it.id in seen }
                }
                if (kept.isNotEmpty()) {
                    Log.w(TAG, "${result.failedDirs} 个目录读取失败，保留 ${kept.size} 条上次扫描的条目")
                }
                (fresh + kept).sortedByDescending { it.addedAt }
            } else {
                fresh
            }

            libraryStore.backup()
            _library.value = LibrarySnapshot(entries)
            saveLibrary()
            remapPlayback(entries)

            val scraped = entries.count { it.scraped }
            _scanJob.value = JobState(
                status = JobStatus.Done,
                message = buildString {
                    append("扫描完成：${entries.size} 部（已刮削 $scraped）")
                    // 目录读失败必须说出来：以前静默跳过，用户只看到「片子少了」
                    if (result.failedDirs > 0) {
                        append("；${result.failedDirs} 个目录读取失败，结果可能不完整（已保留上次的结果）")
                    }
                },
            )

            if (_settings.value.autoScrapeAfterScan && entries.isNotEmpty()) {
                scrapeAll(force = false)
            }
        } catch (e: CancellationException) {
            _scanJob.value = JobState(JobStatus.Failed, error = "扫描已取消")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "扫描失败", e)
            _scanJob.value = JobState(JobStatus.Failed, error = e.message ?: "扫描失败")
        } finally {
            scanMutex.unlock()
        }
    }

    // ---------------- 刮削 ----------------

    override suspend fun scrapeAll(force: Boolean) {
        if (!scrapeMutex.tryLock()) return
        try {
            val targets = _library.value.entries.filter { force || !it.scraped }
            if (targets.isEmpty()) {
                _scrapeJob.value = JobState(JobStatus.Done, "全部条目已刮削")
                return
            }

            _scrapeJob.value = JobState(JobStatus.Running, "准备刮削…", 0, targets.size)
            val scraper = Scraper(_settings.value)
            val done = AtomicInteger(0)
            val merged = ConcurrentHashMap<String, LibraryEntry>()
            // 成功判据用「时间戳是否被本次刷新」。不能看 `it.scraped`（= scrapedAt > 0）：
            // force 刮削的目标本来就全都刮过一遍，于是成功数恒等于总数——断网时界面也报
            // 「成功 200/200」，401/429 的失败原因分支永远进不去。
            val startedAt = System.currentTimeMillis()

            coroutineScope {
                val semaphore = Semaphore(MAX_SCRAPE_CONCURRENCY)
                targets.map { entry ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val result = runCatching { scraper.scrape(entry) }
                                .getOrElse { entry }
                            merged[entry.id] = result
                            val n = done.incrementAndGet()
                            _scrapeJob.value = JobState(
                                status = JobStatus.Running,
                                message = "刮削中：${result.displayTitle}",
                                current = n,
                                total = targets.size,
                            )
                            // 每 5 条落一次库：既让 UI 增量看到海报，
                            // 又避免每条都写一遍几百 KB 的 JSON
                            if (n % 5 == 0) mergeAndSave(merged)
                        }
                    }
                }.awaitAll()
            }

            mergeAndSave(merged)
            // 只有 applyResult 会刷新 scrapedAt，而它只在「真的拿到了元数据」时被调用；
            // 所有失败路径都原样返回 entry，时间戳保持上一轮的值
            val success = merged.values.count { it.scrapedAt >= startedAt }
            _scrapeJob.value = if (success == 0) {
                // 全军覆没时把 TMDB 记录的具体原因带上（连不上 / 401 / 429），
                // 「去设置里跑网络诊断」给用户一个明确的下一步
                val reason = TmdbSource.lastError()
                JobState(
                    JobStatus.Failed,
                    error = "没有条目刮削成功" + when {
                        reason != null -> "：$reason（可到设置 → 刮削 → 网络诊断进一步确认）"
                        else -> "：检查网络，或到设置 → 刮削 → 网络诊断进一步确认"
                    },
                )
            } else {
                // 部分失败时把最常见的原因带上：用户能据此判断是网络、还是文件名太离谱
                val reasons = targets.mapNotNull { scraper.failures[it.id] }
                val top = reasons.groupingBy { it.substringBefore('（') }.eachCount()
                    .maxByOrNull { it.value }
                JobState(
                    JobStatus.Done,
                    message = buildString {
                        append("刮削完成：成功 $success / ${targets.size}")
                        if (top != null) append("；未命中 ${top.value} 条，主要因为「${top.key}」")
                    },
                )
            }
        } catch (e: CancellationException) {
            _scrapeJob.value = JobState(JobStatus.Failed, error = "刮削已取消")
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "刮削失败", e)
            _scrapeJob.value = JobState(JobStatus.Failed, error = e.message ?: "刮削失败")
        } finally {
            scrapeMutex.unlock()
        }
    }

    override suspend fun scrapeOne(entryId: String) = withContext(Dispatchers.IO) {
        val entry = _library.value.entries.firstOrNull { it.id == entryId } ?: return@withContext
        val result = runCatching { Scraper(_settings.value).scrape(entry) }.getOrElse { entry }
        val merged = ConcurrentHashMap<String, LibraryEntry>().apply { put(entryId, result) }
        mergeAndSave(merged)
    }

    override suspend fun clearScraped() = withContext(Dispatchers.IO) {
        val cleared = _library.value.entries.map { entry ->
            entry.copy(
                title = null, originalTitle = null, overview = null, rating = null,
                posterUrl = null, backdropUrl = null, genres = emptyList(), tags = emptyList(),
                cast = emptyList(),
                seasonOverviews = emptyMap(), seasonPosters = emptyMap(),
                files = entry.files.map {
                    it.copy(episodeTitle = null, episodeOverview = null, stillUrl = null, rating = null)
                },
                scrapedAt = 0L, scrapeSource = null,
            )
        }
        _library.value = LibrarySnapshot(cleared)
        saveLibrary()
        _scrapeJob.value = JobState(JobStatus.Idle, "已清除刮削结果")
    }

    private fun mergeAndSave(merged: Map<String, LibraryEntry>) {
        if (merged.isEmpty()) return
        _library.update { snapshot ->
            snapshot.copy(
                entries = snapshot.entries.map { merged[it.id] ?: it }
                    .sortedByDescending { it.addedAt },
            )
        }
        saveLibrary()
    }

    /**
     * 把播放记录挂回「当前这条 id」上。
     *
     * 条目 id 由归一化剧名算出，正常情况下扫一百次都不变；但归并规则收敛（同名不同剧被拆开）
     * 或用户改了目录名时确实会变。文件路径是稳定的，所以按 fileId 找归属最可靠——
     * 找不到归属且条目也没了，说明文件真的不在库里了，这条记录才作废。
     */
    private fun remapPlayback(entries: List<LibraryEntry>) {
        val owner = HashMap<String, String>()
        for (e in entries) for (f in e.files) owner[f.id] = e.id
        val entryIds = entries.mapTo(HashSet()) { it.id }
        val current = _playback.value
        val next = current.mapNotNull { rec ->
            val newEntryId = owner[rec.fileId]
            when {
                newEntryId != null && newEntryId != rec.entryId -> rec.copy(entryId = newEntryId)
                newEntryId != null -> rec
                // 文件这次没读到（目录临时不可用），条目还在：先留着，下次扫描再对
                rec.entryId in entryIds -> rec
                else -> null
            }
        }
        if (next != current) {
            _playback.value = next
            savePlayback()
        }
    }

    /**
     * 把上一轮同一 id 条目的刮削结果继承到新条目上。
     *
     * 扫描只回答「有哪些文件」，标题/海报/评分来自网络。不继承的话每次扫描都会把
     * 已刮削的结果清空再重刮——网络抖动或有几条搜不到时，用户看到的就是
     * 「一部分有海报、一部分没有」反复横跳。
     */
    private fun LibraryEntry.inheritScrape(old: LibraryEntry?): LibraryEntry {
        if (old == null || !old.scraped) return this
        val oldFiles = old.files.associateBy { it.id }
        return copy(
            title = old.title,
            originalTitle = old.originalTitle,
            overview = old.overview,
            rating = old.rating,
            posterUrl = old.posterUrl,
            backdropUrl = old.backdropUrl,
            genres = old.genres,
            tags = old.tags,
            cast = old.cast,
            runtimeMin = old.runtimeMin,
            tmdbId = old.tmdbId,
            imdbId = old.imdbId,
            doubanId = old.doubanId,
            seasonOverviews = old.seasonOverviews,
            seasonPosters = old.seasonPosters,
            scrapedAt = old.scrapedAt,
            scrapeSource = old.scrapeSource,
            // 文件列表是这次重新枚举的，只把文件级的刮削信息按 id 贴回去
            files = files.map { f ->
                val o = oldFiles[f.id] ?: return@map f
                f.copy(
                    episodeTitle = o.episodeTitle,
                    episodeOverview = o.episodeOverview,
                    stillUrl = o.stillUrl,
                    rating = o.rating,
                )
            },
        )
    }

    /**
     * 落库。
     *
     * 必须经 [SaveGate]：`scrapeAll` 有 3 个并发协程、每 5 条调一次 `mergeAndSave`，
     * 每次都会起一个独立的写盘任务。若它们各自 `write`，后拿到锁的那个未必是更新的快照
     * ——协程 A 取到 30 条的旧快照、B 取到 55 条的新快照，B 先写、A 后写，磁盘上就只剩
     * 30 条。用户看到的现象是「刮了一晚上，重启少一半海报」。
     */
    private fun saveLibrary() {
        // 取快照和领版本号必须在同一个临界区里：先取快照、后领号的话，
        // 中间可能插进另一份更新的快照并拿到更小的号，于是「旧数据带着更大的版本号」
        // 写进去，把新的覆盖掉
        val (entries, version) = synchronized(saveLock) {
            _library.value.entries to libraryGate.next()
        }
        scope.launch(Dispatchers.IO) {
            libraryGate.write(version) { libraryStore.write(entriesSerializer, entries) }
        }
    }

    private fun savePlayback() {
        val (list, version) = synchronized(saveLock) {
            _playback.value to playbackGate.next()
        }
        scope.launch(Dispatchers.IO) {
            playbackGate.write(version) { playbackStore.write(playbackSerializer, list) }
        }
    }

    // ---------------- 查询 ----------------

    override fun homeRows(): List<Row> {
        val entries = _library.value.entries
        val records = _playback.value.associateBy { it.entryId }

        val rows = ArrayList<Row>()

        // 继续播放：只看「看了但没看完」的，无记录则整行隐藏
        val continueItems = entries.mapNotNull { entry ->
            val record = records[entry.id] ?: return@mapNotNull null
            if (record.finished || record.positionMs < 10_000L) return@mapNotNull null
            entry to record
        }.sortedByDescending { it.second.updatedAt }
            .take(12)
            .map { (entry, record) -> entry.toSummary(record) }

        if (continueItems.isNotEmpty()) {
            rows += Row(
                id = ROW_CONTINUE,
                title = "继续播放",
                cardType = CardType.Wide,
                items = continueItems,
            )
        }

        // 五个类型行，空则隐藏。
        // 归类用 effectiveKind：刮削到的类型标签（动漫/纪录片/综艺）优先于扫描时按目录猜的类型
        for (kind in HOME_KINDS) {
            val items = entries
                .filter { it.effectiveKind == kind }
                .sortedByDescending { it.addedAt }
                .take(16)
                .map { it.toSummary(records[it.id]) }
            if (items.isNotEmpty()) {
                rows += Row(
                    id = kind.name.lowercase(),
                    title = kind.label,
                    cardType = CardType.Poster,
                    items = items,
                )
            }
        }

        return rows
    }

    override fun titlesOf(kind: MediaKind): List<LibraryEntry> =
        _library.value.entries
            .filter { it.effectiveKind == kind }
            .sortedByDescending { it.addedAt }

    override fun titlesOfGenre(genre: String, moviesOnly: Boolean): List<LibraryEntry> {
        val key = genre.trim()
        if (key.isEmpty()) return emptyList()
        return _library.value.entries
            .filter { groupOf(it) == moviesOnly }
            .filter { entry -> entry.genres.any { it.equals(key, ignoreCase = true) } }
            .sortedByDescending { it.addedAt }
    }

    override fun titlesOfCast(name: String, moviesOnly: Boolean): List<LibraryEntry> {
        val key = name.trim()
        if (key.isEmpty()) return emptyList()
        return _library.value.entries
            .filter { groupOf(it) == moviesOnly }
            .filter { entry -> entry.cast.any { it.name.equals(key, ignoreCase = true) } }
            .sortedByDescending { it.addedAt }
    }

    /** 电影 / 剧集两个互不相通的分组；[moviesOnly] 语义的单参数版本 */
    private fun groupOf(entry: LibraryEntry): Boolean = entry.kind == MediaKind.MOVIE

    override fun entry(id: String): LibraryEntry? =
        _library.value.entries.firstOrNull { it.id == id }

    override fun recordOf(entryId: String): PlaybackRecord? =
        _playback.value.firstOrNull { it.entryId == entryId }

    override fun resumeFile(entryId: String): MediaFile? {
        val entry = entry(entryId) ?: return null
        val record = recordOf(entryId) ?: return entry.primaryFile
        return entry.files.firstOrNull { it.id == record.fileId } ?: entry.primaryFile
    }

    override fun saveProgress(entryId: String, file: MediaFile, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L || positionMs < 0L) return
        val record = PlaybackRecord(
            entryId = entryId,
            fileId = file.id,
            positionMs = positionMs.coerceAtMost(durationMs),
            durationMs = durationMs,
            updatedAt = System.currentTimeMillis(),
        )
        val next = _playback.value.filterNot { it.entryId == entryId } + record
        _playback.value = next
        savePlayback()
    }

    /**
     * 「单一写者 + 版本号」的落盘闸门。
     *
     * - `next()` 由调用方在**取快照的同一刻**领取版本号，因此版本号严格对应快照的新旧；
     * - `write()` 在互斥锁内检查「是不是已经有更新的快照写过盘了」，是就直接放弃；
     * - 结果：磁盘上的内容永远是「某一次最新的快照」，而不可能是更旧的那一份。
     *
     * 用 Mutex 而不是 Channel(CONFLATED)：Channel 也需要一个常驻消费者协程，
     * 在 `repo` 的生命周期里多一个永不平息的协程，反而不如闸门直观。
     */
    private class SaveGate {
        private val mutex = Mutex()
        private val requested = AtomicLong(0)
        private val written = AtomicLong(0)

        fun next(): Long = requested.incrementAndGet()

        suspend fun write(version: Long, block: suspend () -> Unit) {
            mutex.withLock {
                // 已经有比这份更新的快照落盘了，本次直接丢弃
                if (version <= written.get()) return
                // 写失败必须留痕：磁盘满 / 权限异常时以前是完全静默的，
                // 用户只会看到「重启后数据回到昨天」。
                runCatching { block() }.exceptionOrNull()?.let {
                    Log.w(TAG, "落盘失败（版本 $version）", it)
                }
                written.set(version)
            }
        }
    }

    private companion object {
        const val TAG = "ElevenTV"
        const val MAX_SCRAPE_CONCURRENCY = 3
        const val ROW_CONTINUE = "continue"

        val HOME_KINDS = listOf(
            MediaKind.MOVIE,
            MediaKind.SERIES,
            MediaKind.ANIME,
            MediaKind.VARIETY,
            MediaKind.DOCUMENTARY,
        )
    }
}
