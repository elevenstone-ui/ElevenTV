package com.eleventv.tv.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地小字段加密：目前只用来保护 SMB 密码。
 *
 * ## 为什么用 Keystore 而不是 EncryptedSharedPreferences
 *
 * `androidx.security` 会为了一个字段引入整个 crypto 库（还要吃 Tink），而这个项目
 * 需要加密的只有 `servers.json` 里的 `password`。Keystore 提供的是**硬件保护的非导出密钥**：
 * 密文被拷走也没用，密钥出不了 TEE，这一点比「用固定密钥做 AES」强得多。
 *
 * ## 兼容策略
 *
 * - 写入：`enc1:` + Base64(IV ‖ 密文)；
 * - 读取：没有前缀的按旧版明文处理，照常返回（老库无需迁移）；
 * - 失败：加密失败退回明文（个别廉价盒子没有可用 Keystore，不能因此让人登不上 NAS）；
 *   解密失败返回空串并打日志（密钥被重置只剩这一种可能，只能让用户重填）。
 */
object SecretBox {

    private const val TAG = "ElevenTV"
    private const val PREFIX = "enc1:"
    private const val KEY_ALIAS = "eleventv-local-secret"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun seal(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) return plain
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
        }.getOrElse { e ->
            Log.w(TAG, "凭证加密失败，按明文保存", e)
            plain
        }
    }

    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES)))
            String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
        }.getOrElse { e ->
            Log.w(TAG, "凭证解密失败（Keystore 可能被重置），需要重新填写密码", e)
            ""
        }
    }

    /**
     * 取（或首次生成）本地密钥。
     *
     * 必须同步：两个协程同时走到这里会各自 `generateKey()`，后一次会把前一次生成的密钥
     * 覆盖掉，于是前一次刚加密的密码再也解不开（Keystore 里同一 alias 是覆盖语义）。
     */
    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val KEYSTORE = "AndroidKeyStore"
}
