package com.eleventv.tv.data.smb

import android.util.Log
import com.eleventv.tv.data.model.SmbServer
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbException(
    message: String,
    cause: Throwable? = null,
    /**
     * 服务器明确回了「没有这个文件 / 目录 / 共享」。
     *
     * 这类失败重连多少次都是同一个结果，必须让调用方区分出来：否则播放器每读一次
     * 就重连一次，一个改名的文件能把整条连接反复拆了重建，旁边的扫描跟着抖。
     */
    val notFound: Boolean = false,
) : IOException(message, cause)

/** 目录项 */
data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** 创建时间：代表「这个文件什么时候进的库」，比 mtime 更接近「最近添加」 */
    val createdAt: Long,
)

/**
 * 一个支持随机读取的 SMB 文件句柄。
 *
 * 播放时 ExoPlayer 会反复 seek，必须能按偏移读，不能用顺序流。
 */
class SmbFile internal constructor(
    private val file: com.hierynomus.smbj.share.File,
    val size: Long,
    private val onClosed: () -> Unit,
) : Closeable {

    @Volatile
    private var closed = false

    /**
     * 从 [offset] 读至多 [length] 字节到 [target] 的 [targetOffset] 处，返回实际字节数。
     *
     * ⚠️ SMBJ 0.15.0 的签名是 `read(buffer, fileOffset, bufferOffset, length)`：
     * **fileOffset 在第二位，bufferOffset 在第三位**。写反（把 length 放第三位）
     * 会让 length=0，每次都读到 0 字节且不抛异常——ExoPlayer 会判定流已结束，
     * 报 "None of the available extractors could read the stream"，表现为 NAS 视频全都播不了。
     */
    fun read(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int {
        // 句柄被关掉时必须**抛异常**而不是返回 -1：-1 的语义是「文件读完」，
        // ExoPlayer 收到后会判定流结束，把「连接没了」显示成「播完了」。
        // 抛异常才能让上层识别成「连接失效、可以重连续读」。
        if (closed) throw SmbException("文件句柄已关闭")
        return try {
            file.read(target, offset, targetOffset, length)
        } catch (e: Exception) {
            // 不能只接 IOException：SMBJ 解析异常响应时会抛 BufferException（受检的
            // Exception，不是 IOException）。漏掉它就会有一个裸异常从这里穿到 ExoPlayer，
            // 上层那套「识别成连接失效」的逻辑全部失效。
            throw SmbException("读取失败：${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { file.close() }
        runCatching { onClosed() }
    }
}

/** 一条已认证的连接（含共享目录缓存）。 */
class SmbConnection internal constructor(
    val server: SmbServer,
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
) : Closeable {

    private val shares = ConcurrentHashMap<String, DiskShare>()

    /**
     * 未关闭的文件句柄数。
     *
     * 播放期间句柄被 ExoPlayer 长期持有，期间不会经过连接池，`lastUsed` 不更新。
     * 池子靠这个计数判断「这条连接还在用」，避免把播放中的连接回收掉。
     */
    internal val openHandles = AtomicInteger(0)

    /**
     * 底层 TCP 是否还连着。
     *
     * SMBJ 的收包线程一旦读到 socket 超时/对端关闭，就会 `handleError` 把整条连接标成
     * 断开（此时所有在途请求一起失败）。连接池拿连接前必须问一句：不然拿到一条已经
     * 死了的连接，调用方要先撞一次 IO 错误才轮到重连——这次撞击落在播放器上就是
     * 「点了播放直接失败」。
     */
    val alive: Boolean get() = runCatching { connection.isConnected }.getOrDefault(false)

    private fun diskShare(): DiskShare = shares.getOrPut(server.share) {
        try {
            session.connectShare(server.share) as DiskShare
        } catch (e: Exception) {
            throw SmbException(
                "无法打开共享「${server.share}」：${e.message}",
                e,
                notFound = isNotFound(e),
            )
        }
    }

    /** 列举目录；[path] 是共享内相对路径，`""` 表示共享根。 */
    fun list(path: String): List<SmbEntry> {
        val infos = try {
            diskShare().list(toSmbPath(path), "*")
        } catch (e: Exception) {
            // 目录刚被删掉/改名也会抛异常，那不是连接的问题：标上 notFound，
            // 否则扫描每遇到一个消失的目录就要把整条连接拆了重建一次
            throw SmbException(
                "无法读取目录 ${server.share}\\${toSmbPath(path)}",
                e,
                notFound = isNotFound(e),
            )
        }
        return infos.mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            SmbEntry(
                name = name,
                isDirectory = (info.fileAttributes and ATTR_DIRECTORY) != 0L,
                sizeBytes = info.endOfFile,
                modifiedAt = runCatching { info.lastWriteTime.toEpochMillis() }.getOrDefault(0L),
                createdAt = runCatching { info.creationTime.toEpochMillis() }.getOrDefault(0L),
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /** 打开文件用于随机读。 */
    fun open(path: String): SmbFile {
        val share = diskShare()
        val smbPath = toSmbPath(path)
        val file = try {
            share.openFile(
                smbPath,
                // FILE_READ_ATTRIBUTES 必须一起申请：File.length 会发起一次
                // FILE_STANDARD_INFORMATION 查询，只申请 READ_DATA 时可能被拒，
                // 长度退化为 -1，ExoPlayer 就无法做 seek 与缓冲估算
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
            )
        } catch (e: Exception) {
            throw SmbException("无法打开 $smbPath：${e.message}", e, notFound = isNotFound(e))
        }
        val size = runCatching { file.length }.getOrDefault(-1L)
        openHandles.incrementAndGet()
        return SmbFile(file, size) { openHandles.decrementAndGet() }
    }

    fun exists(path: String): Boolean = runCatching {
        diskShare().fileExists(toSmbPath(path))
    }.getOrDefault(false)

    override fun close() {
        runCatching { shares.values.forEach { runCatching { it.close() } } }
        shares.clear()
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    companion object {
        private const val ATTR_DIRECTORY = 0x10L

        /** 建立连接。阻塞调用，必须在 IO 线程执行。 */
        fun connect(server: SmbServer): SmbConnection {
            val config = SmbConfig.builder()
                // 请求级超时。原来 60 秒太长：NAS 休眠或链路静默丢包时，一个 CREATE
                // 请求能把「起播」按死整整一分钟，用户只看到转圈。局域网里 512KB READ
                // 正常在几十毫秒内完成，20 秒已经是「肯定出问题了」的量级。
                .withReadTimeout(20, TimeUnit.SECONDS)
                .withWriteTimeout(20, TimeUnit.SECONDS)
                // ⚠️ socket 读超时决定了两件事，方向是相反的，这里取折中：
                //
                // - **太小**：SMBJ 的收包线程一超时就把整条连接标成断开（TransportException
                //   → Connection.handleError），而「没有数据包」并不代表连接有问题——
                //   用户暂停、缓冲区攒满、两段连续读之间的间隔，都是安静的。原来写 10 秒，
                //   等于「暂停 10 秒后必掉线」：退出再回来播、播到一半突然失败，都出自这里。
                // - **太大**：一条真的哑掉的连接（NAS 拔电、Wi-Fi 掉线但没发 RST）会把
                //   读取死死按住这么久，播放器就一直转圈。
                //
                // 20 秒是两者之间的界限：单次 512KB 读在局域网里是毫秒级，20 秒不动一定是
                // 出事了，交给 SmbDataSource 就地重连；空闲被回收也只是多一次握手（几十毫秒
                // 的重连，对播放透明）。
                .withSoTimeout(20, TimeUnit.SECONDS)
                // 与播放器 512KB 预读块对齐，避免大块响应被拆成多次系统调用
                .withReadBufferSize(512 * 1024)
                .build()
            val client = SMBClient(config)
            return try {
                val connection = client.connect(server.host, server.port)
                val auth = AuthenticationContext(
                    server.username.ifBlank { "guest" },
                    server.password.toCharArray(),
                    "WORKGROUP",
                )
                val session = connection.authenticate(auth)
                SmbConnection(
                    server = server,
                    client = client,
                    connection = connection,
                    session = session,
                )
            } catch (e: Exception) {
                runCatching { client.close() }
                throw SmbException(friendly(e, server), e)
            }
        }

        /**
         * 服务器是不是明确说了「没有这个东西」。
         *
         * 判据是 NT 状态码而不是异常类型：SMBJ 把它埋在 `SMBApiException` 的 message 里，
         * 不同版本包装层数还不一样，所以顺着 cause 链一路找。
         */
        internal fun isNotFound(e: Throwable): Boolean {
            var t: Throwable? = e
            var depth = 0
            while (t != null && depth < 8) {
                val text = (t.message ?: "").uppercase()
                if ("OBJECT_NAME_NOT_FOUND" in text || "OBJECT_PATH_NOT_FOUND" in text ||
                    "STATUS_NOT_FOUND" in text || "BAD_NETWORK_NAME" in text
                ) {
                    return true
                }
                t = t.cause
                depth++
            }
            return false
        }

        /** 把 SMBJ 的技术异常翻译成用户能看懂的话。 */
        private fun friendly(e: Exception, server: SmbServer): String {
            val text = buildString {
                append(e.toString())
                e.cause?.let { append(' ').append(it.toString()) }
            }.lowercase()
            return when {
                "status_logon_failure" in text || "auth" in text || "access_denied" in text ->
                    "认证失败：请检查用户名与密码（${server.display}）"

                "unknownhost" in text || "connect" in text || "timed out" in text ||
                    "timeout" in text || "refused" in text ->
                    "无法连接到 ${server.display}，请检查地址、端口与网络"

                "bad_network_name" in text || "object_name_not_found" in text ->
                    "找不到共享「${server.share}」，请确认共享名"

                else -> "连接 ${server.display} 失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}

/**
 * 连接池。
 *
 * ## 为什么必须有池
 *
 * 扫描一个几百文件的目录树会发上千次 list，每次新建 SMB 连接要 100–300ms
 * （TCP + 协商 + 认证 + TREE_CONNECT），不池化的话扫描要几十分钟。
 *
 * ## 怎么判断「这条连接还在用」
 *
 * 播放时 [SmbFile] 被 ExoPlayer 长期持有，期间 **不再经过本池**，`lastUsed` 不会更新；
 * 扫描时 `list()` 是同步阻塞调用，也不产生文件句柄。所以两个都要数：
 * - `openHandles`：未关闭的文件句柄（播放中）；
 * - `activeOps`：正在本池里执行的操作（list/exists）。
 *
 * 只数句柄的话，另一个线程正在 `list` 时这条连接会被判定为空闲关掉，扫描随机报错。
 *
 * ## 为什么"退休"而不是直接关
 *
 * 连接失效或服务器被改配置时要作废，但可能还有线程在用。直接 close 会打断它们；
 * 而从 map 里删掉又不关，那条 TCP 就再也没人关（`invalidate` 也找不到它了）。
 * 所以标记 `retired` → 摘出 map → 进 `zombies` → 等到 idle 时再真正 close。
 */
class SmbPool {

    private class Slot(
        val connection: SmbConnection,
        @Volatile var lastUsed: Long,
    ) {
        /** 正在本池里执行的操作数 */
        val activeOps = AtomicInteger(0)

        /** 已被判定失效或被移出池子，新的调用不该再拿到它 */
        @Volatile
        var retired = false

        fun busy(): Boolean = connection.openHandles.get() > 0 || activeOps.get() > 0
    }

    /** 内部信号：当前连接已不可用，外层可以重建一次 */
    private class StaleConnection(cause: Throwable) : RuntimeException(cause)

    private val slots = ConcurrentHashMap<String, Slot>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** 已摘出池子、但还等着空闲后关闭的连接 */
    private val zombies = java.util.Collections.newSetFromMap(ConcurrentHashMap<Slot, Boolean>())

    private fun keyOf(server: SmbServer) = "${server.id}@${server.host}:${server.port}/${server.share}"

    private fun lockOf(key: String): Any = locks.computeIfAbsent(key) { Any() }

    /** 取连接执行 [block]，连接失效时自动重连一次。 */
    fun <T> with(server: SmbServer, block: (SmbConnection) -> T): T {
        val key = keyOf(server)
        sweep()

        slots[key]?.let { slot ->
            // 先问一句「还活着吗」。空闲一段时间后 SMBJ 会自己把连接标成断开
            // （见 SmbConnection.connect 里关于 soTimeout 的说明），这时还把它递出去，
            // 调用方必然先撞一次 IO 错误——在播放器上就是「点播放直接失败」。
            // 主动摘掉再重建，用户只会多等一次握手（局域网 100–300ms），察觉不到。
            if (!slot.connection.alive) {
                Log.i(TAG, "连接已断开，提前重建：${server.display}/${server.share}")
                retire(key, slot)
            } else {
                try {
                    return use(key, slot, block)
                } catch (_: StaleConnection) {
                    // 掉下去了，走下面的重建流程
                }
            }
        }

        synchronized(lockOf(key)) {
            // 拿到锁后必须再看一眼：并发进来时别的线程可能刚建好一条，
            // 原来的写法会直接覆盖 map，那条连接既没人用也没人关
            slots[key]?.let { slot ->
                try {
                    return use(key, slot, block)
                } catch (_: StaleConnection) {
                    // 还是坏的，重建
                }
            }

            val fresh = SmbConnection.connect(server)
            val slot = Slot(fresh, System.currentTimeMillis())
            slots[key] = slot
            try {
                return use(key, slot, block)
            } catch (e: StaleConnection) {
                // 新建的连接也失败：把原始错误抛给调用方，别在这里死循环重试
                throw e.cause ?: e
            }
        }
    }

    /** 在一条连接上执行 [block]；失败即把这条连接退休并抛出 [StaleConnection] 让外层重建 */
    private fun <T> use(key: String, slot: Slot, block: (SmbConnection) -> T): T {
        slot.lastUsed = System.currentTimeMillis()
        slot.activeOps.incrementAndGet()
        try {
            return block(slot.connection)
        } catch (e: SmbException) {
            // 文件不存在不是连接的问题：重连只会在「文件被改名」时反复拆掉整条连接，
            // 连累同时段正在扫描或播放的另一路。原样抛给调用方，由它决定要不要报错。
            if (e.notFound) throw e
            Log.w(TAG, "连接失效，重建：${e.message}")
            retire(key, slot)
            throw StaleConnection(e)
        } finally {
            slot.activeOps.decrementAndGet()
        }
    }

    /** 协程友好版本：切到 IO 线程。 */
    suspend fun <T> io(server: SmbServer, block: (SmbConnection) -> T): T =
        withContext(Dispatchers.IO) { with(server, block) }

    suspend fun <T> io(servers: List<SmbServer>, block: (SmbConnection) -> T): T {
        val server = servers.firstOrNull() ?: throw SmbException("尚未配置服务器")
        return io(server, block)
    }

    /** 主动作废某个服务器的连接（删除/编辑服务器时调用）。有句柄的连接会等它空闲后关 */
    fun invalidate(serverId: String) {
        val keys = slots.keys.filter { it.startsWith("$serverId@") }
        keys.forEach { key -> slots[key]?.let { retire(key, it) } }
    }

    /**
     * 精准作废一台服务器某个共享的连接。
     *
     * 和按 serverId 作废的区别：一台服务器可能挂着多个共享（电影 / 剧集），
     * 播放某个共享时链断了，不该顺手把另一个共享正在用的连接也拆掉。
     */
    fun invalidate(server: SmbServer) {
        val key = keyOf(server)
        slots[key]?.let { retire(key, it) }
    }

    fun closeAll() {
        (slots.values + zombies).forEach { runCatching { it.connection.close() } }
        slots.clear()
        zombies.clear()
    }

    /**
     * 回收空闲连接：没有句柄、也没有正在跑的操作，且空闲超过 [IDLE_MS]。
     * 顺手把已经退休、现在终于空闲的连接真正关掉。
     */
    private fun sweep() {
        val now = System.currentTimeMillis()
        slots.forEach { (key, slot) ->
            if (now - slot.lastUsed > IDLE_MS && !slot.busy()) retire(key, slot)
        }
        zombies.toList().forEach { closeIfIdle(it) }
    }

    private fun retire(key: String, slot: Slot) {
        slot.retired = true
        slots.remove(key, slot)
        zombies += slot
        closeIfIdle(slot)
    }

    private fun closeIfIdle(slot: Slot) {
        if (slot.busy()) return          // 还有人在用：留在 zombies 里，下次 sweep 再关
        if (zombies.remove(slot)) runCatching { slot.connection.close() }
    }

    companion object {
        private const val TAG = "ElevenTV"
        private const val IDLE_MS = 3 * 60 * 1000L
    }
}

/** "/" 分隔的相对路径 → SMB 反斜杠路径；根目录为空串 */
internal fun toSmbPath(path: String): String {
    if (path.isBlank()) return ""
    return path.trim('/').replace("/", "\\")
}
