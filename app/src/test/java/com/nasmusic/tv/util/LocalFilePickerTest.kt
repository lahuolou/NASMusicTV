package com.nasmusic.tv.util

import android.content.Intent
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「从本地文件导入」（备份 json / 歌单 / 分离模型）的统一选择器门禁测试。
 *
 * ## 背景
 *
 * 扫码传输页把本 app 变成服务端，但手机上**一打开浏览器 app 就进后台**，Android 12+ 的
 * cgroup freezer 直接冻住进程 —— 内嵌 server 的 socket 还在 LISTEN 却不再 accept，浏览器
 * 停在 `SYN_SENT` 只会报 `Failed to fetch`（v2.39.3 真机取证：`cgroup.freeze=1`、服务端零日志）。
 * 于是所有「往 app 里送一个文件」的功能都补一条不经过网络的本地入口。
 *
 * ## ⚠️ 为什么会有 P3（v2.39.3 的返工）
 *
 * 上一版先用 `resolveActivity()` 探测「本机有没有选择器」，探测失败就把整行藏掉。MIUI 上
 * `<queries>` 没镜像目标 filter 的 mimeType（DocumentsUI 的 `OPEN_DOCUMENT` 声明了带通配
 * `mimeType` 的 `<data>`），逐字段比对不相等 ⇒ 探测假阴性 ⇒ 用户看到的是
 * 「根本没有这个按钮」。所以本对象**不提供任何能力探测 API**，入口一律显示，起不来由调用方
 * 报错。P3 就是把这条口径钉成门禁，防止有人再加回一个 `hasXxxPicker()`。
 *
 * ## 断言口径
 *
 * | # | 内容 |
 * |---|---|
 * | P1 | 初始目录 = 导出备份用的公共目录（与 `BackupFileUtils.BACKUP_SUBDIR` 同源），且文档 id 能解回原形 |
 * | P2 | 三枚入口的意图形态：OPEN_DOCUMENT + OPENABLE + 带初始目录 + 各自放行 octet-stream |
 * | P3 | 负向自证：`LocalFilePicker` / `BackupFileUtils` 上不存在任何「有没有选择器」的探测函数 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalFilePickerTest {

    @Test
    fun `P1 initial dir is the same dir exports land in`() {
        val uri = LocalFilePicker.initialUri()
        check(uri != null) { "SDK 34 下必须给出初始目录" }
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2F$URLENCODED_SUBDIR",
            uri.toString()
        )
        // 提供者侧真正读的是解回原形的文档 id —— 编码错位只在这里暴露，光比字符串不够
        assertEquals("primary:Download/${BackupFileUtils.BACKUP_SUBDIR}", DocumentsContract.getDocumentId(uri))
    }

    @Test
    fun `P2 every entry is an openable document pick seeded at the export dir`() {
        for (types in listOf(
                LocalFilePicker.BACKUP_MIME_TYPES,
                LocalFilePicker.PLAYLIST_MIME_TYPES,
                LocalFilePicker.ANY_MIME_TYPES
            )) {
            val intent = LocalFilePicker.openIntent(types)
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
            // 走 extras 取值比对字符串：getParcelableExtra 在 33+ 已废弃，没必要为断言引入告警
            assertEquals(
                LocalFilePicker.initialUri()?.toString(),
                intent.extras?.get(DocumentsContract.EXTRA_INITIAL_URI)?.toString()
            )
            val actual = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList()
            assertEquals("放行类型必须与传入的一致，否则选择器会把目标文件变灰", types, actual)
        }
        // 备份与歌单都必须放行 octet-stream：经 MTP / 文件管理器拷进 Download 的文件
        // 提供方报告的类型几乎都是它
        assertTrue(LocalFilePicker.BACKUP_MIME_TYPES.contains("application/octet-stream"))
        assertTrue(LocalFilePicker.PLAYLIST_MIME_TYPES.contains("application/octet-stream"))
    }

    @Test
    fun `P3 no capability probing exists anywhere on the picker`() {
        // 入口常显的硬约束：探测函数一旦加回来，某个 ROM 上的假阴性就会让功能「消失」。
        val probing = (LocalFilePicker::class.java.declaredMethods + BackupFileUtils::class.java.declaredMethods)
            .map { it.name }
            .filter { PROBE_NAME.matches(it) }
        assertTrue("不应存在能力探测函数，实际=$probing", probing.isEmpty())
    }

    private companion object {
        /** `hasDocumentPicker` / `isPickerAvailable` / `canPickFile` 这一类命名一律禁止 */
        val PROBE_NAME = Regex("(?i)^(has|can|is).*(picker|document|open|available)")

        /** 与断言里的 Uri 字符串对齐（`Uri.encode` 会把 `/` 编成 `%2F`，子目录名单独拼） */
        const val URLENCODED_SUBDIR = "NASMusic"
    }
}
