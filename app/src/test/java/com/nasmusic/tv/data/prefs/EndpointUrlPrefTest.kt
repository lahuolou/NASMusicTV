package com.nasmusic.tv.data.prefs

import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.backend.network.MetingApiService
import com.nasmusic.tv.backend.network.mv.BilibiliMvService
import com.nasmusic.tv.data.model.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 端点配置的「空串 ≠ 端点」语义（v2.39.4 真机日志回归）。
 *
 * 现场：设置清空 MV 端点 / 从备份导入空字段后，`BilibiliMvService` 对
 * `/x/web-interface/nav` 发请求，`IllegalArgumentException: Expected URL scheme`。
 * 根因是 DataStore 里存了空串、读取端只兜 `null`，空串直接拼出相对路径。
 *
 * 两条防线各自有测试：
 *  - 写入端：清空 → 删除 key（不是写空串）；
 *  - 读取端/恢复端：历史遗留的空串、备份里的空字段 → 回落默认。
 *
 * ⚠️ 不断言 `@Volatile` 镜像（计时不是可断言量），只断言 `appSettings` 这个事实源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EndpointUrlPrefTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        prefs = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `清空端点必须回落默认而不是保存空串`() = runBlocking {
        prefs.setMvApiBaseUrl("https://mv.example.com")
        prefs.setMetingApiBaseUrl("https://meting.example.com")
        assertEquals("https://mv.example.com", prefs.appSettings.first().mvApiBaseUrl)

        prefs.setMvApiBaseUrl("   ")
        prefs.setMetingApiBaseUrl("")

        val settings = prefs.appSettings.first()
        assertEquals(BilibiliMvService.DEFAULT_BASE_URL, settings.mvApiBaseUrl)
        assertEquals(MetingApiService.DEFAULT_BASE_URL, settings.metingApiBaseUrl)
    }

    @Test
    fun `备份里的空端点必须回落默认`() = runBlocking {
        prefs.importBackupData(
            AppPreferences.BackupData(
                appSettings = AppSettings(metingApiBaseUrl = "", mvApiBaseUrl = ""),
            )
        )
        val settings = prefs.appSettings.first()
        assertEquals(MetingApiService.DEFAULT_BASE_URL, settings.metingApiBaseUrl)
        assertEquals(BilibiliMvService.DEFAULT_BASE_URL, settings.mvApiBaseUrl)
    }

    @Test
    fun `备份里的非法端点必须回落默认`() = runBlocking {
        prefs.importBackupData(
            AppPreferences.BackupData(
                appSettings = AppSettings(
                    metingApiBaseUrl = "ftp://bad/only",
                    mvApiBaseUrl = "not-a-url",
                ),
            )
        )
        val settings = prefs.appSettings.first()
        assertEquals(MetingApiService.DEFAULT_BASE_URL, settings.metingApiBaseUrl)
        assertEquals(BilibiliMvService.DEFAULT_BASE_URL, settings.mvApiBaseUrl)
    }

    @Test
    fun `合法自定义端点必须按原样恢复`() = runBlocking {
        prefs.importBackupData(
            AppPreferences.BackupData(
                appSettings = AppSettings(
                    metingApiBaseUrl = "https://mirror.example.com/api",
                    mvApiBaseUrl = "http://192.168.0.10:8080",
                ),
            )
        )
        val settings = prefs.appSettings.first()
        assertEquals("https://mirror.example.com/api", settings.metingApiBaseUrl)
        assertEquals("http://192.168.0.10:8080", settings.mvApiBaseUrl)
    }
}
