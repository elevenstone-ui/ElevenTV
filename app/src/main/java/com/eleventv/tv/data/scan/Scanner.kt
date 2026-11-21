package com.eleventv.tv.data.scan

import android.util.Log
import com.eleventv.tv.data.model.AppSettings
import com.eleventv.tv.data.model.LibraryEntry
import com.eleventv.tv.data.model.MediaFile
import com.eleventv.tv.data.model.MediaKind
import com.eleventv.tv.data.model.SmbServer
import com.eleventv.tv.data.model.StorageProtocol
import com.eleventv.tv.data.smb.SmbPool
import com.eleventv.tv.data.smb.SmbStorage
import com.eleventv.tv.data.storage.RemoteEntry
import com.eleventv.tv.data.storage.RemoteStorage
import com.eleventv.tv.data.webdav.WebDavStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** 扫描进度 */
data class ScanProgress(
    val scanned: Int = 0,
    val found: Int = 0,
    val currentPath: String = "",
    /** 读不动的目录数：>0 说明这次结果是「不完整」的，必须告诉用户 */
    val failedDirs: Int = 0,
)

/**
 * 扫描结果。
 *
 * 带回 [failedDirs] 是因为之前的 `walk` 用 `runCatching { ... }.getOrElse { return }` 吞掉了
 * 目录读取失败：NAS 权限变更或瞬时断连时，扫描会安静地少一批条目，任务栏还写「扫描完成：123 部」。
 * 用户以为片子没了，实际上是没读到。
 */
data class ScanResult(
    val entries: List<LibraryEntry> = emptyList(),
    val failedDirs: Int = 0,
)

/**
 * 媒体库扫描（SMB / WebDAV 共用）。
 *
 * 按服务器逐个走目录树，把视频文件解析成 [MediaFile]，再按剧名聚合成 [LibraryEntry]。
 * 只读目录，不改动 NAS 上任何文件。
 *
 * 目录访问抽象成 [RemoteStorage]：协议差异（握手、认证、列目录的请求形态）全部留在
 * 各自的实现里，这一层只管「遍历 + 解析 + 归并」。
 */
class Scanner(private val pool: SmbPool) {

    /** 一个文件连同它的解析结果：归并时两个键都要用（见 [groupEntries]） */
    private class Found(val file: MediaFile, val parsed: ParsedName)

    /**
     * 候选视频文件：只记目录项，解析放到遍历全部结束之后。
     *
     * 因为「`01.mkv` 是不是第 1 集」这件事，单看一个文件永远定不了——
     * 必须知道同目录里还有 02、03… 才能确认它是分集而不是片名。
     */
    private class RawFile(
        val id: String,
        val serverId: String,
        val share: String,
        val relPath: String,
        val fileName: String,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val kindOverride: MediaKind?,
    ) {
        /** 所在目录（文件直接放在根目录时为空串） */
        val dir: String get() = relPath.substringBeforeLast('/', "")

        /**
         * 目录的全局身份。
         *
         * 带上 serverId + share：两台 NAS 上都有 `剧集/Show/01.mkv` 时，
         * 按裸路径统计会让「A 服务器只有 1 个数字文件」被 B 服务器的兄弟文件凑够阈值。
         */
        val dirKey: String get() = "$serverId|$share|$dir"
    }

    suspend fun scan(
        servers: List<SmbServer>,
        settings: AppSettings,
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val minBytes = settings.minFileSizeMb.toLong() * 1024 * 1024
        // 扩展名只认设置里那一条：以前还叠了一个 FilenameParser.isVideoFile，
        // 于是用户新增 `strm` / `vob` 之类的扩展名永远不生效（白名单有两份，改一份没用）
        // 与内置白名单取并集：设置项目前只能看不能改（且旧库里的默认值比内置少
        // webm/m4v/divx），只认设置值会让这些文件突然扫不到
        val allowedExt = (settings.videoExtensions + FilenameParser.VIDEO_EXTENSIONS)
            .map { it.trim().lowercase().removePrefix(".") }
            .filter { it.isNotEmpty() }
            .toSet()
        val raw = ArrayList<RawFile>()
        val seenIds = HashSet<String>()
        var scanned = 0
        var failedDirs = 0

        for (server in servers.filter { it.enabled }) {
            val counter = intArrayOf(0)

            val onFile: (RemoteEntry, String, MediaKind?) -> Unit = onFile@{ entry, relPath, kindOverride ->
                coroutineContext.ensureActive()

                // 只扫描视频文件：海报 jpg、元数据 nfo、字幕 srt 之类的附属文件
                // 在遍历回调入口就丢掉，连「已扫描 N」计数与进度路径都不参与。
                // 以前白名单只挡住了「入库」，这些文件仍被计数、还会把 currentPath
                // 显示成 xxx.jpg，看起来像在扫一堆图片，也把进度数字灌水。
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                if (ext !in allowedExt) return@onFile

                counter[0]++
                val accepted = entry.sizeBytes <= 0 || entry.sizeBytes >= minBytes

                if (accepted) {
                    val id = "${server.id}|${server.share}|$relPath"
                    // 同一个文件可能被两个根目录都遍历到（比如根填了 /Video 又填了 /Video/Tv）
                    if (seenIds.add(id)) {
                        raw += RawFile(
                            id = id,
                            serverId = server.id,
                            share = server.share,
                            relPath = relPath,
                            fileName = entry.name,
                            sizeBytes = entry.sizeBytes,
                            modifiedAt = if (entry.modifiedAt > 0) entry.modifiedAt else entry.createdAt,
                            kindOverride = kindOverride,
                        )
                    }
                }

                if (counter[0] % 25 == 0) {
                    onProgress(
                        ScanProgress(
                            scanned = scanned + counter[0],
                            found = raw.size,
                            currentPath = relPath,
                            failedDirs = failedDirs,
                        ),
                    )
                }
            }

            failedDirs += when (server.protocol) {
                // SMB：连接由池管理，用完归还（不关闭）
                StorageProtocol.SMB -> pool.with(server) { conn ->
                    walkRoots(SmbStorage(conn), server, onFile)
                }
                // WebDAV：一次性客户端，用完即弃——它没有需要复用的长连接
                StorageProtocol.WEBDAV -> WebDavStorage(server).use { dav ->
                    walkRoots(dav, server, onFile)
                }
            }

            scanned += counter[0]
            onProgress(ScanProgress(scanned, raw.size, server.display, failedDirs))
        }

        if (failedDirs > 0) Log.w(TAG, "有 $failedDirs 个目录读取失败，本次扫描结果可能不完整")

        // 第二遍：先把「哪些目录靠文件名里的连续数字当集号」统计出来，再解析。
        // 这一步不能省——`/剧集/Show/xxx01xxx.mkv` 这种命名，纯看一个文件
        // 无从判断 `01` 是第 1 集还是片名，于是每集都成了一条独立「电影」。
        val numericDirs = numericEpisodeDirs(raw)
        // 每个目录里有多少视频文件：≥2 说明是「合集/导演文件夹」，目录名不能当片名
        val videoCountByDir = raw.groupingBy { it.dirKey }.eachCount()
        val found = ArrayList<Found>(raw.size)
        for (r in raw) {
            coroutineContext.ensureActive()
            val episodeFromDir = numericDirs[r.dirKey]?.get(r.id)
            val parsed = FilenameParser.parse(
                fileName = r.fileName,
                relPath = r.relPath,
                numericEpisode = episodeFromDir != null,
                numericEpisodeNumber = episodeFromDir,
                sameDirVideoFiles = videoCountByDir[r.dirKey] ?: 1,
            )
            found += Found(
                file = MediaFile(
                    id = r.id,
                    serverId = r.serverId,
                    share = r.share,
                    path = r.relPath,
                    fileName = r.fileName,
                    sizeBytes = r.sizeBytes,
                    modifiedAt = r.modifiedAt,
                    seasonNumber = parsed.season ?: 1,
                    episodeNumber = parsed.episode ?: 1,
                    kindOverride = r.kindOverride,
                    episodeTitle = parsed.episodeTitle,
                    quality = parsed.quality,
                ),
                parsed = parsed,
            )
        }

        ScanResult(
            entries = groupEntries(found).sortedByDescending { it.addedAt },
            failedDirs = failedDirs,
        )
    }

    /**
     * 遍历一个服务器的所有媒体库根。
     *
     * 一个服务器可以有多个根（Movie → 电影、Tv → 剧集），各根独立遍历；
     * 用户给根指定的类型随回调带出去，扫描器据此跳过「按文件名猜类型」。
     *
     * @return 读取失败的目录数
     */
    private fun walkRoots(
        storage: RemoteStorage,
        server: SmbServer,
        onFile: (RemoteEntry, String, MediaKind?) -> Unit,
    ): Int {
        var failed = 0
        for (root in server.roots) {
            // visited 按根独立：两个根存在包含关系时（/Video 与 /Video/Tv），后一个根仍要
            // 按自己的 kindOverride 走一遍，否则类型覆写会丢。防环只针对「单个根内部的目录自引用」。
            val visited = HashSet<String>()
            failed += walk(storage, root.path, depth = 0, visited) { entry, relPath ->
                onFile(entry, relPath, root.kind)
            }
        }
        return failed
    }

    /**
     * 深度优先遍历。深度上限 8，跳过 NAS 元数据目录。
     *
     * @return 读取失败的目录数（含递归下去的）
     */
    private fun walk(
        storage: RemoteStorage,
        path: String,
        depth: Int,
        visited: MutableSet<String>,
        onFile: (RemoteEntry, String) -> Unit,
    ): Int {
        if (depth > MAX_DEPTH) {
            // 超深目录必须计入失败：return 0 会让「零结果保护」和「失败目录增量合并」
            // 两条兜底都不触发，深层目录里的片子被静默丢弃，界面还写着「扫描完成」。
            Log.w(TAG, "目录层级超过 $MAX_DEPTH，已跳过：$path")
            return 1
        }
        // 服务器返回的 href 与请求 URL 编码形式不一致时，目录会把自己列成子项。
        // 不给环加一道闸，walk 会一路递归到深度上限并产生大量重复条目。
        if (!visited.add(path.trimEnd('/'))) {
            Log.w(TAG, "检测到重复目录路径，已跳过：$path")
            return 0
        }
        val items = runCatching { storage.list(path) }.getOrElse { e ->
            Log.w(TAG, "跳过目录 $path：${e.message}")
            return 1
        }
        var failed = 0
        for (item in items) {
            // @eaDir / #recycle / .DS_Store 之类，遍历它们纯属浪费时间
            if (item.name.startsWith(".") || item.name.startsWith("@")) continue
            if (item.name.lowercase() in SKIP_DIRS) continue

            val child = if (path.isBlank()) item.name else "$path/${item.name}"
            if (item.isDirectory) {
                failed += walk(storage, child, depth + 1, visited, onFile)
            } else {
                onFile(item, child)
            }
        }
        return failed
    }

    /**
     * 找出「用 01/02/03 当集号」的目录。
     *
     * `/剧集/Show/01.mkv 02.mkv 03.mkv` 这种命名里，单看一个文件无法判断
     * `01` 是第 1 集还是片名；`TRAILING_NUM_EP` 还要求数字前有分隔符，
     * 纯数字文件名连它都进不去，于是 12 集变成 12 个独立「电影」条目。
     *
     * 放进目录上下文就有强信号：同一目录下有若干个短数字文件，
     * 几乎一定是一部剧的分集——电影不会按 01..12 编号并排摆放。
     * 只认 1~3 位数字，所以 `2012.mkv`、`1917.mkv`（片名就是年份）不会进来；
     * 也要求至少两个，`/电影/某片/1.mkv` 这种孤例不受影响。
     * 计数用 List 而不是 Set：`01.mkv` 与 `1.mkv` 归一化后都是第 1 集，
     * 去重会让「目录里明明有多个编号文件」的信号凭空消失（多版本压制的常见摆放）。
     *
     * 已知取舍：`/电影/诺兰合集/01.mkv 02.mkv` 这类「按序号摆放的电影合集」
     * 会被当成一部剧。相比「每个文件一条、海报墙全是数字标题」，
     * 这个方向的误判更容易被发现和手工整理。
     */
    /**
     * 判定「哪些目录是靠文件名里的连续数字当集号」，并给出**每个文件的集号**。
     *
     * 数字可以在文件名任意位置（`Show_01_xxx.mkv`、`xxx02xxx.mkv`），
     * 不再要求开头。判定用「同目录候选数字构成连续序列」这个强信号：
     * 至少 2 个不同的数字、且排序后是连续递增（允许同集多版本）。
     * 这样 `/电影/合集/01.mkv 02.mkv` 仍会误判成剧集 —— 这是既有取舍，
     * 但比「每个文件一条、海报墙全是数字标题」更容易发现。
     *
     * @return dirKey → (文件 id → 集号)
     */
    private fun numericEpisodeDirs(raw: List<RawFile>): Map<String, Map<String, Int>> {
        val byDir = HashMap<String, MutableList<Pair<RawFile, List<Int>>>>()
        for (r in raw) {
            val nums = FilenameParser.episodeNumbersInName(r.fileName)
            if (nums.isEmpty()) continue
            byDir.getOrPut(r.dirKey) { ArrayList() } += r to nums
        }

        val out = HashMap<String, Map<String, Int>>()
        for ((dirKey, files) in byDir) {
            if (files.size < 2) continue

            // 每个文件只保留一个「能和邻居排成序列」的数字。
            // 多数文件只有一个候选；多个候选时取最小且 >=1 的那个（片名里
            // 的大数字通常是年份/分辨率残留，已被 episodeNumbersInName 剥掉）。
            val picked = files.map { (file, nums) -> file.id to nums.min() }
            val distinct = picked.map { it.second }.distinct().sorted()
            // 连续序列判据：≥2 个不同编号，且跨度不超过「编号个数 + 2」
            // （允许缺一两集，例如 01/02/04；跨度过大说明是片名数字，不是集号）
            if (distinct.size < 2) continue
            if (distinct.last() - distinct.first() > distinct.size + 2) continue
            out[dirKey] = picked.toMap()
        }
        return out
    }

    /**
     * 把解析过的文件归并成条目。
     *
     * ## 为什么按 mergeKey 而不是 groupKey
     *
     * 同一部剧的不同季，目录与文件名写法经常不一致：
     * `/剧集/Show (2008)/Season 1/`、`/剧集/Show/第二季/`、`Show.2021.S03E01.mkv`。
     * 用原始标题 + 年份做键时，这些差异会把它拆成「Show」「Show 第二季」两条，
     * 详情页也只剩半部剧。mergeKey 是**归一化剧名**（去季号、年份、噪声、标点），
     * 只要归一化后同名就合成一条，详情页按季分组展示。
     *
     * ## 为什么还要再按「集号是否撞车」拆一次
     *
     * mergeKey 把年份也剥掉了，于是《The Office (2001)》与《The Office (2005)》归一化后
     * 同名，被合成一条——详情页出现两个 S01E01，刮削只能命中其中一部。
     * 判据是**集号集合是否相交**：同一部剧的不同季不会有两个 `S01E01`，两部同名剧则一定都有。
     * 这样「多季年份不同」（S1 是 2019、S3 是 2021）仍然合并，同名不同剧才拆开。
     */
    private fun groupEntries(found: List<Found>): List<LibraryEntry> {
        val buckets = LinkedHashMap<String, MutableList<Found>>()
        for (f in found) {
            buckets.getOrPut(f.parsed.mergeKey) { mutableListOf() } += f
        }

        val out = ArrayList<LibraryEntry>(buckets.size)
        for ((mergeKey, items) in buckets) {
            val parts = splitByIdentity(items)
            parts.forEach { part ->
                // id 只由「归一化剧名」决定，与文件数量、命名写法无关。
                // 以前取「文件最多的那个原始分组」的键，季数一变（删几集、加一季）
                // 就会翻转 → id 变化 → 播放记录与已刮削数据全部失联。
                // 只有在同名不同剧被拆开时，才追加年份区分。
                val idKey = if (parts.size == 1) {
                    mergeKey
                } else {
                    "$mergeKey|${part.year ?: UNKNOWN_YEAR}"
                }
                out += buildEntry(stableId(idKey), part.items)
            }
        }
        return out
    }

    /** 拆出来的一个簇 */
    private class Part(val items: List<Found>, val year: Int?)

    /**
     * 同名消歧：按年份分簇，再把「集号不冲突」的簇合回去。
     *
     * 步骤：
     * 1. 有年份的文件按年分簇；
     * 2. 没有年份的文件并入最大的那个簇（目录没写年份是常态，不该因此另立一条）；
     * 3. 反复合并集号集合不相交的簇——这一步把「同一部剧、不同季、年份不同」重新并回一条。
     */
    private fun splitByIdentity(items: List<Found>): List<Part> {
        val known = items.filter { it.parsed.year != null }
        if (known.isEmpty()) return listOf(Part(items, null))

        class Cluster(val year: Int, val files: MutableList<Found>, val eps: MutableSet<String>)

        val clusters = known.groupBy { it.parsed.year!! }
            .toSortedMap()
            .map { (year, list) -> Cluster(year, list.toMutableList(), epKeys(list)) }
            .toMutableList()

        items.filter { it.parsed.year == null }
            .takeIf { it.isNotEmpty() }
            ?.let { unknown ->
                clusters.maxByOrNull { it.files.size }?.let { cluster ->
                    cluster.files.addAll(unknown)
                    // 并入文件必须同时并入集号：下面判「是不是同一部剧」完全依赖 eps，
                    // 偏小的集合会让本该拆开的两部同名剧被判成「集号不撞」而错误合并。
                    cluster.eps.addAll(epKeys(unknown))
                }
            }

        var merged = true
        while (merged && clusters.size > 1) {
            merged = false
            loop@ for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    // 集号不撞 → 同一部剧的不同季（S1 与 S3 不会都有 E01）；
                    // 年份差 ≤1 → 同一部剧的压制年/播出年误差，不该拆。
                    // 两条都不满足才认定是「同名不同剧」（The Office 2001 / 2005）。
                    // none 而不是 intersect：后者每比较一对簇就新建一个临时 Set，
                    // 合并循环里会产生大量短命对象；判空语义完全一致。
                    val disjoint = clusters[i].eps.none { it in clusters[j].eps }
                    val closeYear = kotlin.math.abs(clusters[i].year - clusters[j].year) <= 1
                    if (disjoint || closeYear) {
                        clusters[i].files += clusters[j].files
                        clusters[i].eps += clusters[j].eps
                        clusters.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }

        return clusters.map { c ->
            Part(c.files, c.files.mapNotNull { it.parsed.year }.minOrNull())
        }
    }

    /** 集号集合：`季-集`。用于判断两个簇是不是同一部剧 */
    private fun epKeys(items: List<Found>): MutableSet<String> =
        items.mapNotNullTo(HashSet()) { f ->
            f.parsed.episode?.let { ep -> "${f.parsed.season ?: 1}-$ep" }
        }

    private fun buildEntry(id: String, items: List<Found>): LibraryEntry {
        val sorted = items.map { it.file }
            .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }, { it.fileName }))

        // 形态 / 标题 / 年份取「多数票」，而不是「排序后的第一个文件」：
        // 一个 bucket 里混入特别篇、样片时，第一条未必代表整部剧（以前就是这么错的）。
        // 三张票原本各跑一次 groupingBy（三次全量遍历 + 三次临时 Map），合并为一次遍历。
        // 用 LinkedHashMap 保持插入顺序，平票时 maxByOrNull 的取值与 groupingBy 完全一致。
        val kindVotes = LinkedHashMap<MediaKind, Int>()
        val yearVotes = LinkedHashMap<Int, Int>()
        val titleVotes = LinkedHashMap<String, Int>()
        for (f in items) {
            val p = f.parsed
            kindVotes[p.kind] = (kindVotes[p.kind] ?: 0) + 1
            p.year?.let { y -> yearVotes[y] = (yearVotes[y] ?: 0) + 1 }
            if (p.title.isNotBlank()) titleVotes[p.title] = (titleVotes[p.title] ?: 0) + 1
        }
        val kind = sorted.firstNotNullOfOrNull { it.kindOverride }
            ?: kindVotes.maxByOrNull { it.value }?.key
            ?: MediaKind.SERIES
        val year = yearVotes.maxByOrNull { it.value }?.key
        val title = titleVotes.maxByOrNull { it.value }?.key ?: items.first().parsed.title

        return LibraryEntry(
            id = id,
            // 用户给根目录指定的类型优先于文件名推断（Movie 目录下的都按电影处理）
            kind = kind,
            rawTitle = title,
            year = year,
            files = sorted,
            addedAt = sorted.maxOf { it.modifiedAt },
        )
    }

    /** 稳定 id：同一部剧每次扫描得到同一个 id，播放记录才不会失联。 */
    private fun stableId(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return buildString(16) {
            digest.take(8).forEach { b ->
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    companion object {
        private const val TAG = "ElevenTV"
        private const val MAX_DEPTH = 8
        private const val HEX = "0123456789abcdef"
        private const val UNKNOWN_YEAR = 0

        private val SKIP_DIRS = setOf(
            "#recycle", "\$recycle.bin", "recycle", "system volume information",
            "lost+found", ".git", "node_modules", "备份", "backup",
        )
    }
}
