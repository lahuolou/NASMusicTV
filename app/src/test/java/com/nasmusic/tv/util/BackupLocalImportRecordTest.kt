package com.nasmusic.tv.util

import android.content.Intent
import com.nasmusic.tv.data.model.ImportedBackupRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「从本地文件导入的备份要能出现在下方列表里」的门禁测试（v2.39.4）。
 *
 * ## 缺陷是什么（真机取证 2026-10-21）
 * 手机用「从本地文件导入」选了 `Download/NASMusic/NASMusic_backup_20260920_221903.json`
 * （由电脑经 adb / 小米互传 拷进去的那份），**恢复成功**，但下方备份文件列表里没有它。
 * 设备侧 `content query --uri content://media/external/downloads` 给出成因：
 *
 * | 文件 | `owner_package_name` |
 * |---|---|
 * | app 自己导出的 4 份 | NULL（`_uid` = app ⇒ 自己可见） |
 * | 传进来的 `…221903.json` | `com.android.shell` |
 *
 * `listBackups()` 走 MediaStore，**非本 app 拥有的行对调用方不可见**，而 SAF 选择器是
 * 用户逐次授权 ⇒ 读得到、列不出。所以凡是「用户自己在选择器里挑的文件」都必须另外登记，
 * 否则这条入口在用户眼里就是「导入成功了但文件凭空消失」。
 *
 * ## 断言口径
 *
 * | # | 内容 |
 * |---|---|
 * | M1 | 与 app 导出项同名的登记项不得重复出现在列表里 |
 * | M2 | 授权已失效（文件被移走/重启后掉授权）的登记项必须被剔掉，不能留下点必炸的死行 |
 * | M3 | 合并后仍按修改时间倒序 |
 * | M4 | 登记项要带 `localImported` 标记（UI 与删除路径据此区别对待） |
 * | M5 | 选择器意图必须申请**可持久化**读授权（拿不到它就撑不过重启，M2 永无意义） |
 * | M6 | 本地导入成功的提示必须带上文件名（所有者反馈「成功后没有提示」） |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupLocalImportRecordTest {

    private fun exported(name: String, uri: String, modified: Long) = BackupFileUtils.BackupFile(
        displayName = name,
        uri = android.net.Uri.parse(uri),
        lastModified = modified,
    )

    private fun record(name: String, uri: String, modified: Long) =
        ImportedBackupRecord(uri = uri, displayName = name, lastModified = modified)

    @Test
    fun `M1 imported entry duplicating an app export is dropped`() {
        val exports = listOf(
            exported(
                "NASMusic_backup_20260920_225548.json",
                "content://media/external/downloads/1000019005",
                1_789_916_148_000L
            )
        )
        // 同名但 uri 不同（app 看得见 MediaStore 行，用户又从选择器挑了同一个文件）
        val imported = listOf(
            record(
                "NASMusic_backup_20260920_225548.json",
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2FNASMusic%2FNASMusic_backup_20260920_225548.json",
                1_789_916_148_000L
            )
        )
        val granted = setOf(imported[0].uri)
        val merged = BackupFileUtils.mergeImportedEntries(exports, imported, granted)
        assertEquals("同名文件只能出现一次", 1, merged.size)
        assertFalse(
            "保留的必须是 app 自己的导出项（可正常删除），不是登记项",
            merged[0].localImported
        )
    }

    @Test
    fun `M2 entry whose grant is gone is pruned`() {
        val imported = listOf(
            record("NASMusic_backup_old.json", "content://com.android.externalstorage.documents/document/primary%3Aold", 1L),
            record("NASMusic_backup_live.json", "content://com.android.externalstorage.documents/document/primary%3Alive", 2L)
        )
        val merged = BackupFileUtils.mergeImportedEntries(
            exported = emptyList(),
            imported = imported,
            grantedUriStrings = setOf(imported[1].uri)
        )
        assertEquals(1, merged.size)
        assertEquals("NASMusic_backup_live.json", merged[0].displayName)
    }

    @Test
    fun `M3 merged list stays sorted by modification time desc`() {
        val exports = listOf(
            exported("NASMusic_backup_a.json", "content://media/external/downloads/1", 1_000L),
            exported("NASMusic_backup_b.json", "content://media/external/downloads/2", 5_000L)
        )
        val imported = listOf(
            record("NASMusic_backup_c.json", "content://com.android.externalstorage.documents/document/primary%3Ac", 3_000L),
            record("NASMusic_backup_d.json", "content://com.android.externalstorage.documents/document/primary%3Ad", 9_000L)
        )
        val granted = imported.map { it.uri }.toSet()
        val merged = BackupFileUtils.mergeImportedEntries(exports, imported, granted)
        assertEquals(
            listOf("NASMusic_backup_d.json", "NASMusic_backup_b.json", "NASMusic_backup_c.json", "NASMusic_backup_a.json"),
            merged.map { it.displayName }
        )
    }

    @Test
    fun `M4 imported rows are tagged and keep their own uri`() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FDLManager%2FNASMusic_backup_x.json"
        val merged = BackupFileUtils.mergeImportedEntries(
            exported = emptyList(),
            imported = listOf(record("NASMusic_backup_x.json", uri, 7L)),
            grantedUriStrings = setOf(uri)
        )
        assertEquals(1, merged.size)
        assertTrue("登记项必须标 localImported，否则删除路径会被按 MediaStore 处理而静默失败", merged[0].localImported)
        assertEquals(uri, merged[0].uri.toString())
    }

    @Test
    fun `M5 picker intent requests a persistable read grant`() {
        val intent = LocalFilePicker.openIntent(LocalFilePicker.BACKUP_MIME_TYPES)
        // 没有 PERSISTABLE 位 ⇒ takePersistableUriPermission 抛 SecurityException，登记根本存不下来
        assertTrue(
            "选择器意图必须带 FLAG_GRANT_PERSISTABLE_URI_PERMISSION，实际 flags=${intent.flags}",
            intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        )
        assertTrue(
            "选择器意图必须带 FLAG_GRANT_READ_URI_PERMISSION，实际 flags=${intent.flags}",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
    }

    @Test
    fun `M6 local restore message names the file`() {
        // ⚠️ 本模块没开 `testOptions.unitTests.isIncludeAndroidResources`，单测里 getString(R.string.x)
        // 会抛 Resources$NotFoundException（见 DeviceDiagnosticsTest 的同款说明）。
        // 所以判据落在**资源原文**上：占位符必须恰好一个，格式化后文件名要出现在文案里。
        for (dir in listOf("values", "values-en")) {
            val xml = sourceFile("res/$dir/strings.xml").readText(Charsets.UTF_8)
            val m = Regex("""<string name="backup_restored_from_file">(.*?)</string>""").find(xml)
            assertTrue("$dir/strings.xml 缺 backup_restored_from_file（编译能过、渲染会崩）", m != null)
            val body = m!!.groupValues[1]
            // ⚠️ 这里**不能**用 raw string 写 %1$s：K2 里 `\$` 在 `"""…"""` 中不是转义，
            // `$s` 会被当成模板插值（Unresolved reference 's'）。普通串 + `\$` 才是字面美元符。
            assertEquals("$dir 文案必须只吃一个文件名，实际=$body", 1, body.split("%1\$s").size - 1)
            assertFalse("$dir 文案有多余位置参数：$body", body.contains("%2\$"))
            val rendered = String.format(body, "NASMusic_backup_20260920_221903.json")
            assertTrue("格式化后没带文件名：$rendered", rendered.contains("NASMusic_backup_20260920_221903.json"))
            assertFalse("占位符没被替换（少传参数）：$rendered", rendered.contains("%1"))
        }
        // 只有本地导入那条用带文件名的文案，且必须真的把文件名传进去
        val vm = sourceFile("java/com/nasmusic/tv/ui/viewmodel/BackupViewModel.kt").readText(Charsets.UTF_8)
        assertTrue(
            "BackupViewModel 没把显示名传给 backup_restored_from_file",
            Regex("""getString\(\s*R\.string\.backup_restored_from_file\s*,\s*fileName\s*\)""").containsMatchIn(vm)
        )
    }

    private fun sourceFile(relativeFromMain: String): java.io.File {
        val candidates = listOf(
            java.io.File("src/main/$relativeFromMain"),
            java.io.File("app/src/main/$relativeFromMain"),
            java.io.File("../app/src/main/$relativeFromMain"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $relativeFromMain（user.dir=${java.io.File("").absolutePath}）", found != null)
        return found!!
    }
}

