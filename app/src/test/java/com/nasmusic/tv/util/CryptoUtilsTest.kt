package com.nasmusic.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CryptoUtils 解密入口的兼容路径回归（2026-10-20 真机日志刷屏修复）。
 *
 * 背景：`AppPreferences.serverConfig` Flow 每次 DataStore 排放都会对 apiToken /
 * password / accessCode 调 `decrypt`，多数后端没有 apiToken ⇒ `decrypt("")` 高频；
 * 百度未登录时 `getBaiduTokens` 也走 `decrypt("")`。旧实现空串必然「全密钥失败」
 * 并刷一条 W（[AppLog.w] 始终落盘）⇒ 电视日志页被这条挤爆。本轮给 `decrypt`
 * 加了**返回结果完全一致**的提前返回，本组用例锁死这个等价性：
 *
 * 1. 密文往返照旧（提前返回没有把真密文误判成明文）；
 * 2. 空串 / 非 Base64 明文 / 合法 Base64 但不足 IV 长度的明文 —— 三条旧实现就
 *    「解不开、原样返回」的路径，返回值不变；
 * 3. `isEncrypted` 的分界线（解码后长度 > IV 长度 12）不变。
 *
 * ⚠️ Robolectric：`android.util.Base64` 是 Android SDK 类，纯 JVM 单测下是桩。
 * ⚠️ 本组用例全部走「不触警」路径（不产生 W/落盘记录），与修复目的一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CryptoUtilsTest {

    @Test
    fun `密文往返不受提前返回影响`() {
        val plain = "super-secret-token-42"
        assertEquals(plain, CryptoUtils.decrypt(CryptoUtils.encrypt(plain)))
    }

    @Test
    fun `空串原样返回（不再触发全密钥失败告警）`() {
        assertEquals("", CryptoUtils.decrypt(""))
    }

    @Test
    fun `非 Base64 字符集的明文原样返回`() {
        val plain = "MyP@ss w0rd!"
        assertEquals(plain, CryptoUtils.decrypt(plain))
    }

    @Test
    fun `合法 Base64 但解码后不足 IV 长度的明文原样返回`() {
        // "aGVsbG8=" 解码后仅 5 字节（≤ IV 长度 12）：旧实现任何密钥都解不开、原样返回
        val plain = "aGVsbG8="
        assertEquals(plain, CryptoUtils.decrypt(plain))
    }

    @Test
    fun `isEncrypted 分界线仍是解码后长度大于 IV 长度`() {
        assertTrue(CryptoUtils.isEncrypted(CryptoUtils.encrypt("x")))
        assertFalse(CryptoUtils.isEncrypted("aGVsbG8="))
        assertFalse(CryptoUtils.isEncrypted("!!!not-base64!!!"))
    }
}
