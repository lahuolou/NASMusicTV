package com.nasmusic.tv.util

import android.util.Base64
import com.nasmusic.tv.BuildConfig
import com.nasmusic.tv.util.AppLog
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 加密工具类：AES-256-GCM，用于加密敏感数据（密码、token）。
 *
 * 历史版本基于 AndroidKeyStore 的 KeyGenerator 生成密钥，但部分 TV / 盒子 ROM 的
 * AndroidKeyStore 不提供 AES 的 KeyGenerator（运行时抛
 * `NoSuchAlgorithmException: KeyGenerator AES implementation not found`），
 * 导致解密失败、百度/NAS 等需要解密 token 的功能在电视上失效。
 *
 * 现改为使用由固定口令派生（SHA-256）的软件密钥（SecretKeySpec），无需 KeyGenerator
 * 或 KeyStore，在所有设备（含无密钥库的 TV）上均可稳定加解密。
 * 对旧设备已用 AndroidKeyStore 加密的数据，decrypt 会回退尝试 KeyStore 中的旧密钥，
 * 以兼容手机端存量数据；新写入一律走软件密钥。
 */
object CryptoUtils {
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    // 派生密钥的口令（编译期常量）。注意：此为「混淆级」而非「保密级」——
    // 口令内置于 APK，可被逆向还原，无法抵御有针对性的逆向攻击；
    // 其价值在于避免凭据以明文形式直接暴露在 prefs/备份文件中。
    // 如需更强保护，应改用设备安全硬件（AndroidKeyStore/StrongBox）。
    // S1 修复（2026-09-13）：口令从 BuildConfig 注入,默认值与历史硬编码保持一致,
    // 用户可在 keystore.properties 中设置 cryptoPassphrase 覆盖(不入仓)。
    // 注意:此为「混淆级」而非「保密级」——口令最终仍内置于 APK 的 BuildConfig 字段,
    // 可被逆向还原,无法抵御有针对性的逆向攻击;其价值在于避免凭据以明文形式
    // 直接暴露在源码仓库/prefs/备份文件中。如需更强保护,应改用设备安全硬件
    // (AndroidKeyStore/StrongBox)。
    private val PASSPHRASE: String = BuildConfig.CRYPTO_PASSPHRASE
    private const val KEY_ALIAS = "nasmusic_secret_key"

    private val softwareKey: SecretKey by lazy { deriveSoftwareKey() }

    private fun deriveSoftwareKey(): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256").digest(PASSPHRASE.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest.copyOf(32), "AES")
    }

    // 回退：尝试读取 AndroidKeyStore 中已存在的旧密钥（兼容手机端旧数据）。TV 上通常无此密钥。
    private fun getKeyStoreKey(): SecretKey? = try {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        ks.getKey(KEY_ALIAS, null) as? SecretKey
    } catch (_: Exception) { null }

    private fun tryDecrypt(key: SecretKey, encryptedText: String): String? {
        return try {
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.w("CryptoUtils", "tryDecrypt failed (text prefix=${encryptedText.take(16)}..., len=${encryptedText.length})", e)
            null
        }
    }

    /**
     * 加密明文，返回 Base64 编码的 "iv + ciphertext + tag" 字符串。
     *
     * ⚠️ 必须显式生成 IV 并通过 GCMParameterSpec 传入 cipher.init()——
     * 部分 Android TV ROM 在 ENCRYPT_MODE 不传 IV 时 cipher.iv 返回空数组
     * （0 字节而非 12 字节），导致密文缺少 IV 前缀，decrypt 时提取到错误的
     * IV → GCM tag 验证失败 → 返回密文原样 → 密文被当 token 发出 → API 报 -6。
     */
    fun encrypt(plainText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, softwareKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val combined = iv + encrypted
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            // 安全约束：加密失败绝不能把明文落盘（凭据必须加密存储）。
            // 返回空串，调用方写入空值后由 decrypt 得到 ""，最终表现为鉴权失败而非明文泄露。
            AppLog.e("CryptoUtils", "encrypt failed, returning empty string (plaintext NOT persisted)", e)
            ""
        }
    }

    /**
     * 解密 Base64 编码的 "iv + ciphertext + tag" 字符串。
     * 先尝试通用软件密钥，再回退 AndroidKeyStore 旧密钥；都失败则原样返回（可能是明文，兼容旧版本）。
     *
     * ⚠️ 两条**提前返回**的兼容短路（2026-10-20 真机日志刷屏修复，行为不变）：
     * - 空串：多数后端没有 apiToken、百度未登录时 token 为空串，`AppPreferences.serverConfig`
     *   Flow 每次排放都会走到这里。空串必然「全密钥失败」，此前**每次读取刷一条 W**，且
     *   [AppLog.w] 始终落盘（LogStore）⇒ 电视日志页被这条挤爆。空串原样返回即可。
     * - 非密文形态（非 Base64 或解码后无 IV+密文+tag 空间）：任何密钥都解不开，且多半是
     *   旧版明文值——「原样返回明文」本来就是设计的兼容路径，不该按异常报警。顺带省掉
     *   两次必然失败的 Cipher 初始化。
     * 两者与旧实现**返回结果完全一致**，只是不再刷告警。真正需要留 W 的只剩一种：
     * 看着像密文却所有密钥都解不开（口令变更 / 数据损坏）——这会让密文被当明文 token
     * 发出（表现为 API 报 -6）。
     */
    fun decrypt(encryptedText: String): String {
        if (encryptedText.isEmpty()) return ""
        if (!isEncrypted(encryptedText)) return encryptedText
        tryDecrypt(softwareKey, encryptedText)?.let { return it }
        getKeyStoreKey()?.let { ks -> tryDecrypt(ks, encryptedText)?.let { return it } }
        AppLog.w("CryptoUtils", "decrypt: all keys failed, returning original (len=${encryptedText.length}, prefix=${encryptedText.take(16)}...)")
        return encryptedText
    }

    /**
     * 判断字符串是否已加密（Base64 格式且长度足够）
     */
    fun isEncrypted(text: String): Boolean {
        return try {
            val combined = Base64.decode(text, Base64.NO_WRAP)
            combined.size > GCM_IV_LENGTH
        } catch (e: Exception) {
            false
        }
    }
}
