package com.nasmusic.tv.data.prefs

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 把 `settings_model_download_url` 暴露到设置页后必须守住的三条语义（方案 §6-11 / T10）。
 *
 * 该 key 走 `customUrlProvider`（NasMusicApp → ModelDownloadManager），
 * **自定义源同样受 `EXPECTED_SHA256` 约束**：一旦 UI 侧写进一个被改坏或被截断的 URL，
 * 用户看到的症状是"下得下来但验不过"，而 SHA 校验只会报"文件不完整"——最难归因的一类。
 * 因此这里钉的是 `normalizeEndpointUrl` 的三条边界，不是连通性。
 *
 * ⚠️ 断言走 `appSettings.modelDownloadUrl`（读原始 key），
 * **不**断言 `getModelDownloadUrlSync()`（`@Volatile` 镜像）—— 镜像计时不是可断言量，
 * 接线形状已由 provider 镜像门覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadUrlPrefTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        prefs = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `合法 URL 必须以规范化后的原样入库`() = runBlocking {
        prefs.setModelDownloadUrl("  http://192.168.0.10:8080/demucs/htdemucs_fp16.onnx  ")
        assertEquals(
            "http://192.168.0.10:8080/demucs/htdemucs_fp16.onnx",
            prefs.appSettings.first().modelDownloadUrl,
        )
    }

    @Test
    fun `空白必须回落空串即内置默认候选`() = runBlocking {
        prefs.setModelDownloadUrl("http://nas.local:5000/a.onnx")
        prefs.setModelDownloadUrl("")
        assertEquals("", prefs.appSettings.first().modelDownloadUrl)
    }

    @Test
    fun `非法 URL 必须被拒绝并保持旧值`() = runBlocking {
        prefs.setModelDownloadUrl("http://nas.local:5000/a.onnx")
        prefs.setModelDownloadUrl("ftp://bad/only") // scheme 非 http/https → normalize 返回 null
        assertEquals("http://nas.local:5000/a.onnx", prefs.appSettings.first().modelDownloadUrl)
    }

    /**
     * 裸主机名（无点、无端口、非 localhost）必须被拒绝。
     *
     * 期望值是空串取决于"初始就是空"这一事实：`setModelDownloadUrl` 在
     * `url.isNotBlank() && normalized == null` 时**直接 return 不写**，
     * 所以从未成功写过时读到的仍是默认 `""`。
     * ⛔ 若实测发现它会写入空串之外的值，那是实现的缺陷 —— 按实现改测试就是错。
     */
    @Test
    fun `无点无端口非 localhost 的裸主机名必须被拒绝`() = runBlocking {
        prefs.setModelDownloadUrl("http://naslocal/a.onnx")
        assertEquals("", prefs.appSettings.first().modelDownloadUrl)
    }
}
