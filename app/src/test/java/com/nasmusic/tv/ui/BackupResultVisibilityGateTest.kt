package com.nasmusic.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「导入/恢复的结果必须看得见」的接线门禁（v2.39.4）。
 *
 * ## 为什么需要（真机反馈 2026-10-21）
 * 手机从「从本地文件导入」选完文件回到设置页，**恢复确实生效了，但屏幕上没有任何提示**。
 * 提示本身是有的（`BackupViewModel` 会把成功文案写进 Flow），问题出在呈现位置：
 * 它渲染在**分区内容的最上方**，而用户的视线与滚动位置停在卡①中部的按钮/列表处；
 * 唯一的补救是 `animateScrollToItem(0)`，而它包在 `runCatching` 里 —— SAF 返回那一刻
 * 列表往往还在惯性滚动，这次滚动会被取消并**被静默吞掉**，消息随后 4s 自动消费 ⇒
 * 用户看到的就是「什么都没发生」。
 *
 * 所以结果消息除了分区内那一行，还必须在**屏幕级**（Toast，不随列表滚动）出现一次。
 * 这类接线编译永远过得去（少写一个 Toast 只是少个提示），只能靠源码门禁钉住。
 *
 * ## 断言口径
 *
 * | # | 内容 |
 * |---|---|
 * | G1 | 备份结果消息在 `SettingsScreen` 里既进分区行、又进 Toast |
 * | G2 | 歌单导入结果消息同样两路呈现（同一个缺陷类） |
 * | G3 | 本地选择器那条回调必须以 `fromLocalFile = true` 调 `importBackup`（否则既不登记也不带文件名） |
 * | G4 | 负向自证：同一谓词喂「只有延时消费、没有 Toast」的反例必须判负 |
 */
class BackupResultVisibilityGateTest {

    /** 在 `LaunchedEffect(<key>)` 的块体前 600 字符里找 Toast 落点 */
    private fun surfacedByToast(src: String, key: String): Boolean {
        val effect = Regex("LaunchedEffect\\($key\\)\\s*\\{").find(src) ?: return false
        val body = src.substring(effect.range.last).take(600)
        return Regex("""Toast\.makeText\([^;]*(?:\.text|Message)""").containsMatchIn(body)
    }

    @Test
    fun `G1 backup result must also be shown as a toast`() {
        val src = stripComments(mainSource("ui/screens/SettingsScreen.kt"))
        assertTrue(
            "备份结果消息没有屏幕级呈现（分区顶行会被列表滚动甩出视野）—— 需在 LaunchedEffect(backupMessage) 里 Toast",
            surfacedByToast(src, "backupMessage")
        )
    }

    @Test
    fun `G2 playlist import result must also be shown as a toast`() {
        val src = stripComments(mainSource("ui/screens/SettingsScreen.kt"))
        assertTrue(
            "歌单导入结果消息没有屏幕级呈现 —— 需在 LaunchedEffect(playlistImportMessage) 里 Toast",
            surfacedByToast(src, "playlistImportMessage")
        )
    }

    @Test
    fun `G3 local picker callback must flag the import as local`() {
        val src = stripComments(mainSource("ui/components/branches/SettingsBranch.kt"))
        assertTrue(
            "选择器回调没带 fromLocalFile = true ⇒ 结果提示不带文件名、文件也不进列表",
            Regex("""importBackup\(\s*picked\s*,\s*fromLocalFile\s*=\s*true\s*\)""").containsMatchIn(src)
        )
    }

    @Test
    fun `G4 the toast predicate must reject a source without a toast`() {
        val negative = """
            LaunchedEffect(backupMessage) {
                val msg = backupMessage
                if (msg != null && !msg.isError) {
                    kotlinx.coroutines.delay(4000)
                    onConsumeBackupMessage?.invoke()
                }
            }
        """.trimIndent() + "\n"
        assertFalse("谓词空转：反例（只有延时消费）也被判成有提示", surfacedByToast(negative, "backupMessage"))
        val positive = negative +
            "LaunchedEffect(backupMessage) { backupMessage?.let { Toast.makeText(ctx, it.text, Toast.LENGTH_SHORT).show() } }\n"
        assertTrue("谓词过严：正例没被认出", surfacedByToast(positive, "backupMessage"))
    }

    // ───────────────────────── 源码读取与剥注释 ─────────────────────────

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $relativePath（user.dir=${File("").absolutePath}）", found != null)
        return found!!.readText(Charsets.UTF_8)
    }

    /** 剥注释（引号/字符字面量感知，保留换行）—— 与 MainViewModelConstructionOrderTest 同口径 */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var inString = false
        var inChar = false
        while (i < src.length) {
            val c = src[i]
            val next = if (i + 1 < src.length) src[i + 1] else ' '
            if (inString) {
                out.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    out.append(next); i += 2; continue
                }
                if (c == '"') inString = false
                i++; continue
            }
            if (inChar) {
                out.append(c)
                if (c == '\\' && i + 1 < src.length) {
                    out.append(next); i += 2; continue
                }
                if (c == '\'') inChar = false
                i++; continue
            }
            if (c == '/' && next == '/') {
                while (i < src.length && src[i] != '\n') i++
                continue
            }
            if (c == '/' && next == '*') {
                i += 2
                while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(src.length)
                continue
            }
            if (c == '"') inString = true
            if (c == '\'') inChar = true
            out.append(c)
            i++
        }
        return out.toString()
    }
}
