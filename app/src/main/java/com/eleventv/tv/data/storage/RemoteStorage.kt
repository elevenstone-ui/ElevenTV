package com.eleventv.tv.data.storage

import java.io.Closeable
import java.io.IOException

/** 目录项：SMB 与 WebDAV 能给出的公共字段 */
data class RemoteEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
    /** 创建时间：代表「这个文件什么时候进的库」，比 mtime 更接近「最近添加」 */
    val createdAt: Long = 0L,
)

/**
 * 只读的远程目录访问。
 *
 * 扫描与「浏览目录」只需要列目录这一件事，取流不走这里——
 * SMB 用 `SmbDataSource`，WebDAV 直接交给 Media3 的 `HttpDataSource`。
 * 把两者都塞进同一个接口只会让播放链路多一层没必要的抽象。
 */
interface RemoteStorage : Closeable {

    /** 日志用：`smb://nas/media` / `http://nas:5005` */
    val label: String

    /**
     * 列出目录。
     *
     * @param path 共享/服务根下的相对路径，空串表示根
     * @throws IOException 目录不存在、权限不足、连接失败
     */
    fun list(path: String): List<RemoteEntry>
}
