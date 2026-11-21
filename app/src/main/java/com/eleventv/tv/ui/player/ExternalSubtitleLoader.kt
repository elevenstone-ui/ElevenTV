package com.eleventv.tv.ui.player

import com.eleventv.tv.data.model.SmbServer
import com.eleventv.tv.data.model.StorageProtocol
import com.eleventv.tv.data.smb.SmbPool
import com.eleventv.tv.data.smb.SmbStorage
import com.eleventv.tv.data.webdav.WebDavStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 列出视频所在目录的文件名，供 [matchExternalSubtitles] 找外挂字幕。
 *
 * 只在**起播前**调用一次，结果是纯字符串列表，传给 PlayerState 后换集时
 * 直接复用（同一剧集各集通常同目录），不产生额外网络往返。
 *
 * 必须在 IO 线程调用：SMB 列目录走连接池、WebDAV 走一次 PROPFIND，
 * 都是网络 IO，放主线程就是 ANR。
 */
suspend fun listSiblingNames(
    server: SmbServer,
    pool: SmbPool,
    mediaPath: String,
): List<String> {
    val dir = mediaPath.substringBeforeLast('/', "")
    return withContext(Dispatchers.IO) {
        when (server.protocol) {
            StorageProtocol.SMB -> pool.io(server) { conn ->
                SmbStorage(conn).list(dir).map { it.name }
            }
            StorageProtocol.WEBDAV -> WebDavStorage(server).use { dav ->
                dav.list(dir).map { it.name }
            }
        }
    }
}
