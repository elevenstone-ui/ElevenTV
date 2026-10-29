package com.eleventv.tv.data.store

import android.util.Log
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简 JSON 存储。
 *
 * ## 为什么自己写
 *
 * 全应用只有 4 个需要持久化的东西（设置、服务器列表、媒体库索引、播放记录），
 * 引 Room 要加注解处理器与 schema 迁移，收益为负。
 *
 * ## 为什么必须原子写 + fsync
 *
 * 电视会随时被拔电。直接 `writeText` 覆盖，写到一半断电就是一个截断的 JSON，
 * 下次启动反序列化失败 → 服务器配置与播放记录全丢。因此先写 `.tmp` 再 rename：
 * rename 在同一分区上是原子的，要么是旧文件、要么是新文件，不存在中间态。
 *
 * **但 rename 的原子性只对目录项（元数据）成立**：`writeText` 关闭流只是把数据交给
 * 内核页缓存，此时 rename 先落盘、数据块后落盘是完全可能的。结果就是 rename 成功
 * 但文件是 0 字节——比截断更糟，因为长度合法、看起来「读到了空数组」。
 * 所以写完之后必须 `fd.sync()` 把数据真正刷到块设备，再 rename。
 */
class JsonStore(private val file: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val lock = Any()

    fun <T> read(deserializer: DeserializationStrategy<T>, fallback: T): T = synchronized(lock) {
        if (!file.exists()) return fallback
        val text = runCatching { file.readText() }.getOrNull()
        // 空文件几乎没有可能是「合法的空数据」：写入路径永远是「先写 tmp 再整份 rename」，
        // 真正的空数据也会被序列化成 `[]`。所以 0 字节一律按损坏处理。
        if (text.isNullOrBlank()) {
            quarantine("文件为空（疑似断电截断）")
            return restoreFromBackup(deserializer) ?: fallback
        }
        runCatching { json.decodeFromString(deserializer, text) }
            .getOrElse { e ->
                quarantine(e.message ?: "反序列化失败")
                restoreFromBackup(deserializer) ?: fallback
            }
    }

    /**
     * 另存一份 `.bak`。
     *
     * 只在「整库重建」这种不可逆的时刻调用（扫描完成后覆盖 library.json），
     * 平时刮削每 5 条写一次盘，每次都拷贝一份几百 KB 的 JSON 不值得。
     * 有了它，主文件被写坏时还能把上一次的库捞回来——否则用户看到的就是
     * 「媒体库是空的」，且所有海报与刮削结果一起没了。
     */
    fun backup(): Boolean = synchronized(lock) {
        if (!file.exists()) return false
        runCatching { file.copyTo(File(file.parentFile, "$BACKUP_SUFFIX"), overwrite = true) }
            .onFailure { Log.w(TAG, "备份 ${file.name} 失败", it) }
            .isSuccess
    }

    /** 主文件坏了：用备份顶上，并把它写回主文件，避免下次再走这条路 */
    private fun <T> restoreFromBackup(deserializer: DeserializationStrategy<T>): T? {
        val bak = File(file.parentFile, "$BACKUP_SUFFIX")
        if (!bak.exists()) return null
        val text = runCatching { bak.readText() }.getOrNull()
        if (text.isNullOrBlank()) return null
        val value = runCatching { json.decodeFromString(deserializer, text) }.getOrNull()
        if (value == null) {
            Log.w(TAG, "${bak.name} 也读不出来，只能从空数据开始")
            return null
        }
        Log.w(TAG, "${file.name} 已从 ${bak.name} 恢复")
        runCatching { bak.copyTo(file, overwrite = true) }
        return value
    }

    /** 备份文件名：`library.json` → `library.json.bak` */
    private val BACKUP_SUFFIX: String get() = "${file.name}.bak"

    /**
     * 把损坏的文件挪走留证。
     *
     * 备份名带时间戳而不是固定 `.corrupt`：固定名在第二次损坏时会覆盖第一次的证据，
     * 而「连续两次损坏」恰恰是最需要看现场的情况。
     */
    private fun quarantine(reason: String) {
        val stamp = runCatching {
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        }.getOrDefault(System.currentTimeMillis().toString())
        val backup = File(file.parentFile, "${file.name}.$stamp.corrupt")
        val moved = runCatching { file.renameTo(backup) }.getOrDefault(false)
        Log.w(TAG, "${file.name} 已损坏（$reason），备份到 ${backup.name}：$moved")
    }

    fun <T> write(serializer: SerializationStrategy<T>, value: T): Boolean = synchronized(lock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val ok = runCatching {
            val bytes = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()          // 关键：数据先于 rename 落到块设备
            }
            replace(tmp, file)
        }.getOrElse { e ->
            Log.w(TAG, "写入 ${file.name} 失败", e)
            false
        }
        if (!ok) Log.w(TAG, "写入 ${file.name} 未成功，旧文件保持不变")
        ok
    }

    /**
     * 用 tmp 覆盖目标文件。
     *
     * 优先 `ATOMIC_MOVE`（一步替换目录项，中间不存在「文件不存在」的窗口）；
     * 部分外挂存储不支持原子移动，退回旧的两步写法。
     */
    private fun replace(tmp: File, target: File): Boolean {
        val atomic = runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            true
        }.getOrDefault(false)
        if (atomic) return true

        val renamed = runCatching { tmp.renameTo(target) }.getOrDefault(false)
        if (renamed) return true
        // delete + rename 之间存在极短的「文件不存在」窗口，仅作为兜底
        return runCatching {
            target.delete()
            tmp.renameTo(target)
        }.getOrDefault(false)
    }

    fun delete() = synchronized(lock) { runCatching { file.delete() } }

    private companion object {
        const val TAG = "ElevenTV"
    }
}
