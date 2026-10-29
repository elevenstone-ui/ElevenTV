package com.eleventv.tv.data.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import com.eleventv.tv.data.model.SmbServer
import com.eleventv.tv.data.smb.SmbException
import com.eleventv.tv.data.smb.SmbFile
import com.eleventv.tv.data.smb.SmbPool
import java.util.Locale

/**
 * 本次播放的 SMB 读取统计。
 *
 * 播放「一直转圈不出画面」有两大类完全不同的原因，光看界面分不出来：
 * - `bytesRead == 0` → 一个字节都没拿到，是取流问题（连接/权限/超时）；
 * - `bytesRead` 很大却仍不出画面 → 数据到了，是解封装/解码问题。
 * 把这两个数字显示/记录出来，才不用靠猜。
 */
object SmbIoStats {

    @Volatile var bytesRead = 0L
    @Volatile var readCalls = 0
    @Volatile var openCalls = 0
    @Volatile var openCostMs = 0L
    @Volatile var firstReadCostMs = -1L

    /** 本次播放里「连接断了又接回来」的次数：>0 说明网络/NAS 不稳，但播放已被兜住 */
    @Volatile var reconnects = 0
    @Volatile var lastError: String? = null

    @Synchronized
    fun reset() {
        bytesRead = 0
        readCalls = 0
        openCalls = 0
        openCostMs = 0
        firstReadCostMs = -1
        reconnects = 0
        lastError = null
    }

    fun summary(): String = when {
        bytesRead <= 0 ->
            "本次读取 0 字节（打开 ${openCalls} 次，耗时 ${openCostMs}ms" +
                (lastError?.let { "，最后错误：${it.take(60)}" } ?: "") + "）"
        else -> {
            val avgKb = if (readCalls > 0) bytesRead / 1024 / readCalls.toLong() else 0L
            String.format(
                Locale.CHINA,
                "本次已读 %.1f MB / %d 次（均 %d KB/次），首包 %dms" +
                    (if (reconnects > 0) "，重连 $reconnects 次" else ""),
                bytesRead / 1024.0 / 1024.0,
                readCalls,
                avgKb,
                firstReadCostMs,
            )
        }
    }
}

/**
 * 让 ExoPlayer 从 SMB 共享读取视频。
 *
 * ## 为什么不用 FileDataSource
 *
 * ExoPlayer 自带的 `FileDataSource` 只认 `file://`；SMB 没有可挂载路径，必须自己实现
 * [DataSource]。播放器要反复 seek，所以底层用 [SmbFile] 做随机读，而不是顺序流。
 *
 * ## 为什么必须自带 512KB 预读（「一直加载不出画面」的根因）
 *
 * ExoPlayer 解封装时每次只向 DataSource 要 **8~64KB**，而每一次 read 都是一个 SMB2
 * READ 往返。电视的 SMB 单次往返常在几十毫秒，64KB/50ms ≈ 1.2MB/s——高码率片源
 * 的缓冲永远追不上消耗，表现就是**进度条一直转、画面一直不出**。
 * 1.7.6 用「一次向 NAS 要 512KB、后续小块请求从内存供给」把往返次数降到 1/8~1/64，
 * 2.0 初版把这个细节丢了，于是「能扫库、能出海报、就是播不动」。
 *
 * ## 为什么 read() 要自己重连，而不是把异常抛给 ExoPlayer
 *
 * DataSource 一旦抛 IOException，ExoPlayer 只会做有限次重试，然后终止播放。而 SMB
 * 这条链路上「连接被回收」是常态：SMBJ 的 socket 读超时会直接关掉整条连接，
 * 用户暂停一会儿、缓冲区攒满、或者 NAS 短暂抖动，回来时连接就没了。把这种情况
 * 抛给上面的结果就是「播几分钟进一次加载」「退出再播全失败」。所以这里把
 * 「作废连接 → 按原偏移重新打开 → 接着读」整段收在 [read] 里面，对外表现为
 * 一次稍微慢一点的读。
 *
 * ## URI 约定
 *
 * `smb://<serverId>/<共享内相对路径>`：authority 只标记来源服务器，路径走 [Uri.getPath]。
 */
@UnstableApi
class SmbDataSource(
    private val pool: SmbPool,
    private val server: SmbServer,
) : BaseDataSource(/* isNetwork = */ true) {

    private var handle: SmbFile? = null
    private var openedUri: Uri? = null

    /** 共享内相对路径。断线重连要按原路径重新打开，所以必须留着 */
    private var path: String = ""
    private var readPosition = 0L
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    /** transferStarted 是否已调用：成功才配对 transferEnded，否则监听链会拿到 null DataSpec */
    private var transferring = false

    private var prefetch = ByteArray(0)
    private var prefetchStart = -1L
    private var prefetchLength = 0

    override fun open(dataSpec: DataSpec): Long {
        val beginAt = System.nanoTime()

        val target = dataSpec.uri.path?.trimStart('/').orEmpty()
        if (target.isBlank()) {
            throw DataSourceException(
                IllegalArgumentException("SMB URI 缺少路径：${dataSpec.uri}"),
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            )
        }

        path = target
        openedUri = dataSpec.uri
        val file = openHandle(target)
        handle = file

        val length = file.size
        // 长度未知（有些 NAS 拒绝 FILE_STANDARD_INFORMATION）时绝不能当成 0：
        // 那会让第一次 read 直接返回 EOF，所有 extractor 都认不出容器格式
        val start = when {
            length < 0 -> dataSpec.position
            dataSpec.position > length -> length
            else -> dataSpec.position
        }
        readPosition = start
        bytesRemaining = if (length < 0) Long.MAX_VALUE else length - start
        prefetchStart = -1L
        prefetchLength = 0

        SmbIoStats.openCostMs += (System.nanoTime() - beginAt) / 1_000_000

        transferStarted(dataSpec)
        transferring = true
        return if (length < 0) C.LENGTH_UNSET.toLong() else bytesRemaining
    }

    /**
     * 打开文件句柄，失败时作废连接再试一次。
     *
     * 「打不开」有两种：文件真没了（不重试，直接报找不到），和连接废了（必须重试）。
     * 后者在暂停、缓冲攒满之后很常见——SMBJ 的连接会被自己的 socket 超时关掉，
     * 连接池里那条已经不可用，重试前必须先 [SmbPool.invalidate] 把它摘干净。
     */
    private fun openHandle(target: String): SmbFile {
        var last: Exception? = null
        for (attempt in 0 until OPEN_ATTEMPTS) {
            SmbIoStats.openCalls++
            try {
                return pool.with(server) { conn -> conn.open(target) }
            } catch (e: SmbException) {
                if (e.notFound) {
                    SmbIoStats.lastError = e.message
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
                }
                last = e
            } catch (e: Exception) {
                last = e
            }
            SmbIoStats.lastError = last?.message ?: last?.javaClass?.simpleName
            Log.w(TAG, "打开 ${server.display}/$target 失败，作废连接后重试（第 ${attempt + 1} 次）：${last?.message}")
            pool.invalidate(server)
        }
        throw DataSourceException(
            last ?: SmbException("无法打开 $target"),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        )
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (handle == null) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == Long.MAX_VALUE) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (toRead <= 0) return eof()

        // 命中预读缓冲：后续小块请求零往返
        serveFromPrefetch(target, offset, toRead)?.let { return it }

        // 真正去 NAS 要数据。连接中途死掉时在这里原地重连，对 ExoPlayer 完全透明——
        // 直接把异常抛出去，播放就终止了，用户看到的是「播几分钟就卡住/失败」。
        var attempt = 0
        while (true) {
            val file = handle ?: return C.RESULT_END_OF_INPUT
            var reason: String
            try {
                val n = readFromServer(file, target, offset, toRead)
                if (n > 0) return n
                // 0 / -1 = 服务器说没数据了。已知长度却还有剩余时先怀疑连接半死：
                // 重连再读一次，两次都读不到才认 EOF（否则会把「断线」当成「播完」）。
                if (bytesRemaining == Long.MAX_VALUE || attempt >= RECOVER_ATTEMPTS) return eof()
                reason = "读到 0 字节"
            } catch (e: Exception) {
                SmbIoStats.lastError = e.message ?: e.javaClass.simpleName
                if (e is SmbException && e.notFound) {
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
                }
                if (attempt >= RECOVER_ATTEMPTS) {
                    Log.w(TAG, "读取在 $readPosition 处彻底失败（重连 $attempt 次）", e)
                    throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
                }
                reason = e.message ?: e.javaClass.simpleName
            }
            // 重连本身失败就是终局：再重试也只是把同一个错误多撞几次
            attempt++
            try {
                reattach(reason)
            } catch (re: Exception) {
                throw DataSourceException(re, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
        }
    }

    /** 预读缓冲命中返回字节数；没命中返回 null */
    private fun serveFromPrefetch(target: ByteArray, offset: Int, toRead: Int): Int? {
        if (readPosition < prefetchStart) return null
        if (readPosition >= prefetchStart + prefetchLength) return null
        val inBuffer = (readPosition - prefetchStart).toInt()
        val n = minOf(toRead, prefetchLength - inBuffer)
        if (n <= 0) return null
        System.arraycopy(prefetch, inBuffer, target, offset, n)
        advance(n)
        return n
    }

    /** 一次底层读取（含预读填充），顺带记录统计 */
    private fun readFromServer(file: SmbFile, target: ByteArray, offset: Int, toRead: Int): Int {
        // 调用方要得比预读块还大：直接按请求大小读，不做中间拷贝
        if (toRead >= PREFETCH_BYTES) {
            val n = file.read(readPosition, target, offset, toRead)
            if (n > 0) {
                SmbIoStats.bytesRead += n
                SmbIoStats.readCalls++
                advance(n)
            }
            return n
        }

        // 小块请求：一次向 NAS 要 512KB，再从缓冲里供给本次请求
        if (prefetch.size < PREFETCH_BYTES) prefetch = ByteArray(PREFETCH_BYTES)
        val want = if (bytesRemaining == Long.MAX_VALUE) {
            PREFETCH_BYTES
        } else {
            minOf(PREFETCH_BYTES.toLong(), bytesRemaining).toInt()
        }
        val beginAt = System.nanoTime()
        val filled = file.read(readPosition, prefetch, 0, want)
        if (filled <= 0) return filled
        SmbIoStats.bytesRead += filled
        SmbIoStats.readCalls++
        prefetchStart = readPosition
        prefetchLength = filled

        val n = minOf(toRead, filled)
        System.arraycopy(prefetch, 0, target, offset, n)
        advance(n)
        if (SmbIoStats.firstReadCostMs < 0) {
            SmbIoStats.firstReadCostMs = (System.nanoTime() - beginAt) / 1_000_000
        }
        return n
    }

    /**
     * 连接断了：作废这条连接，按当前偏移重新打开，接着往下供数据。
     *
     * 这是「播到一半进加载 / 退出再回来全播不了」的正面解法。以前 read() 一失败就把
     * 异常抛给 ExoPlayer，播放直接终止；而连接池里那条死连接没人主动作废，下一次
     * open() 还会撞上它——于是「所有视频都播不了」。现在在这里闭环：作废 → 重连 →
     * 同一个偏移继续读，ExoPlayer 只会感觉到这一次 read() 慢了一点。
     */
    private fun reattach(reason: String) {
        Log.w(TAG, "SMB 读取中断（$reason），在 $readPosition 处重连")
        runCatching { handle?.close() }
        handle = null
        // 缓冲里的数据对应的是断线前的连接，偏移虽然还是对的，但保守起见丢掉重读
        prefetchStart = -1L
        prefetchLength = 0
        pool.invalidate(server)
        handle = openHandle(path)
        SmbIoStats.reconnects++
    }

    private fun eof(): Int {
        bytesRemaining = 0
        return C.RESULT_END_OF_INPUT
    }

    private fun advance(n: Int) {
        readPosition += n
        if (bytesRemaining != Long.MAX_VALUE) bytesRemaining -= n
        bytesTransferred(n)
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        try {
            handle?.close()
        } finally {
            handle = null
            openedUri = null
            path = ""
            readPosition = 0L
            bytesRemaining = C.LENGTH_UNSET.toLong()
            prefetchStart = -1L
            prefetchLength = 0
            // 只在真正开启过传输时配对调用：open() 失败后紧接着 close() 时若仍调用，
            // 监听链会拿到 null DataSpec 抛 NPE，把真正的 SMB 错误顶掉
            if (transferring) {
                transferring = false
                runCatching { transferEnded() }
            }
        }
    }

    /** 一个 URI 对应一条连接；ExoPlayer 换片时新建实例，不共享句柄。 */
    @UnstableApi
    class Factory(
        private val pool: SmbPool,
        private val server: SmbServer,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(pool, server)
    }

    private companion object {
        const val TAG = "ElevenTV"

        /**
         * 单次预读大小。
         *
         * NAS 一次 SMB2 READ 通常能给到 512KB~4MB（受 credits 限制），但读得越大，
         * 首包延迟越高、内存占用越多（2GB 内存的电视盒子要注意），512KB 是折中值，
         * 与 [com.eleventv.tv.data.smb.SmbConnection] 的 socket 读缓冲一致。
         */
        const val PREFETCH_BYTES = 512 * 1024

        /**
         * open 失败时最多试几次（含第一次）。
         *
         * 只留 2 次：连接池内部已经带了「掉线→重建」的一次重试，这里再加一次是为了兜住
         * 「重建出来的连接刚好也赶上 NAS 抽风」。再往上加没有收益——NAS 真不在线时，
         * 每次尝试都要等一次 TCP 超时，试得越多，用户盯着黑屏等得越久。
         */
        const val OPEN_ATTEMPTS = 2

        /** 单次 read() 里最多重连几次。够扛过「暂停很久后连接被回收」，又不至于卡死 */
        const val RECOVER_ATTEMPTS = 2
    }
}
