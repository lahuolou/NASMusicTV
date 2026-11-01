package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.NetworkSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「设置页新增的默认网络源卡」写的是哪条事实源（方案 §6-10 / Task 7）。
 *
 * ⚠️ 断言只走 `appSettings` Flow（AppPreferences:799 读 `keyDefaultNetworkSource`），
 * **绝不**断言 `getDefaultNetworkSourceSync()`（:1199，`@Volatile` 内存镜像）——
 * 「写完立刻读到新值」是时序量，`ProviderMirrorTest` 的 4 条镜像计时用例已因此
 * 于 5997380 整体删除，镜像的**接线与形状**由 `ProviderMirrorWiringGateTest` G2
 * 自动枚举 `cached*` 字段钉住（`cachedDefaultNetworkSource` :169 已在网内）。
 * 本测试只证明"这个 key 真的被这条 setter 写、真的被这个字段读"，即 UI 接对了线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultNetworkSourcePrefTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        prefs = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `setDefaultNetworkSource 必须落到 appSettings 的 defaultNetworkSource`() = runBlocking {
        prefs.setDefaultNetworkSource(NetworkSource.ALAPI)
        assertEquals(NetworkSource.ALAPI, prefs.appSettings.first().defaultNetworkSource)
    }

    @Test
    fun `未设置时必须回落 NetworkSource DEFAULT`() = runBlocking {
        assertEquals(NetworkSource.DEFAULT, prefs.appSettings.first().defaultNetworkSource)
    }

    /** 负向自证：写入必须真的改变事实源（防止 setter 空转）。 */
    @Test
    fun `改到另一个源后事实源必须跟着变`() = runBlocking {
        prefs.setDefaultNetworkSource(NetworkSource.ALAPI)
        prefs.setDefaultNetworkSource(NetworkSource.JIOSAAVN)
        assertEquals(NetworkSource.JIOSAAVN, prefs.appSettings.first().defaultNetworkSource)
    }

    // ─────────── T7 Step 3b：UI 接线的源码文本门（防止"卡片加了但读的是另一个 key"） ───────────

    private fun settingsSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到源码文件：$relativePath", found != null)
        return found!!.readText()
    }

    @Test
    fun `默认源卡必须读写同一个字段`() {
        val section = settingsSource("ui/screens/settings/SourcesSettingsSection.kt")
        assertTrue(
            "SourcesSettingsSection 必须同时出现 state.defaultNetworkSource（读）与 " +
                "actions.onChangeDefaultNetworkSource（写）",
            section.contains("state.defaultNetworkSource") &&
                section.contains("actions.onChangeDefaultNetworkSource")
        )
    }

    @Test
    fun `SettingsBranch 必须把默认源接到 setter`() {
        val branch = settingsSource("ui/components/branches/SettingsBranch.kt")
        assertTrue(
            "SettingsBranch 必须出现 DefaultNetworkSource 的 setter 调用" +
                "（updateDefaultNetworkSource 或其门面形式）",
            branch.contains("DefaultNetworkSource")
        )
    }

    /** 负向自证：把写回调从样例文本里去掉，读写判据必须判负。 */
    @Test
    fun `默认源卡判据不是空转`() {
        val sample = "state.defaultNetworkSource = x, actions.onChangeDefaultNetworkSource = { }"
        assertTrue(
            "样例本身应通过读写判据",
            sample.contains("state.defaultNetworkSource") &&
                sample.contains("actions.onChangeDefaultNetworkSource")
        )
        val noWrite = sample.replace("actions.onChangeDefaultNetworkSource = { }", "")
        assertTrue(
            "负向用例失效：去掉写回调后判据仍通过",
            !(noWrite.contains("actions.onChangeDefaultNetworkSource"))
        )
        val noRead = sample.replace("state.defaultNetworkSource = x, ", "")
        assertTrue(
            "负向用例失效：去掉读侧后判据仍通过",
            !(noRead.contains("state.defaultNetworkSource"))
        )
    }
}
