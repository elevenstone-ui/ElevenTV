package com.eleventv.tv.data.smb

import com.eleventv.tv.data.storage.RemoteEntry
import com.eleventv.tv.data.storage.RemoteStorage

/**
 * 把 [SmbConnection] 适配成通用的 [RemoteStorage]。
 *
 * 连接生命周期仍归连接池管，这里不负责关闭——[close] 是空实现是刻意的：
 * 若在这里关掉连接，池子里会留下一条已被销毁的条目，下一次取用必撞 IO 错误。
 */
class SmbStorage(private val conn: SmbConnection) : RemoteStorage {

    override val label: String
        get() = "smb://${conn.server.display}/${conn.server.share}"

    override fun list(path: String): List<RemoteEntry> =
        conn.list(path).map {
            RemoteEntry(
                name = it.name,
                isDirectory = it.isDirectory,
                sizeBytes = it.sizeBytes,
                modifiedAt = it.modifiedAt,
                createdAt = it.createdAt,
            )
        }

    override fun close() = Unit
}
