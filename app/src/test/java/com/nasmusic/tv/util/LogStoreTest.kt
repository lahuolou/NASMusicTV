package com.nasmusic.tv.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 运行日志落盘存储（`LogStore`）的纯 JVM 单测。
 *
 * 设计约束（见 `LogStore` KDoc）：
 *  - **不依赖 Android 类型**（只收 `File`），因此能在免 Gradle 通道里直接跑；
 *  - 写入走单线程执行器，测试用 [LogStore.appendBlocking] 保证顺序与可见性；
 *  - 格式 = TSV + 转义（`\\` / `\t` / `\n` / `\r`），畸形行必须被跳过而不是整文件报废；
 *  - 超限轮转必须**保留最新**（宁可丢老日志，也不能丢刚发生的崩溃现场）。
 */
class LogStoreTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("logstore-test").toFile()
        LogStore.init(root)
    }

    @After
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    // ───────────────────── 编解码 ─────────────────────

    @Test
    fun `编码解码必须原样往返特殊字符`() {
        val message = "含制表符\t与换行\n以及反斜杠\\和回车\r结束"
        val stack = "java.lang.IllegalStateException: boom\n\tat a.b.C(D.kt:1)"
        val line = LogStore.encode(LogStore.LEVEL_ERROR, "Tag\tX", message, stack)

        // 单行协议：编码结果里不得出现真实的 \t / \n（否则解码会错位）
        assertEquals("编码后不得含真实换行", 0, line.count { it == '\n' })
        assertEquals("编码后字段分隔符之外不得含真实制表符", 4, line.count { it == '\t' })

        val entry = LogStore.decode(line)
        assertNotNull("合法行必须能解码", entry)
        entry!!
        assertEquals(LogStore.LEVEL_ERROR, entry.level)
        assertEquals("Tag\tX", entry.tag)
        assertEquals(message, entry.message)
        assertEquals(stack, entry.stackTrace)
    }

    @Test
    fun `无堆栈时 stackTrace 必须为空`() {
        val entry = LogStore.decode(LogStore.encode(LogStore.LEVEL_WARN, "T", "m", null))
        assertNotNull(entry)
        assertNull("没有异常就不该凭空造堆栈", entry!!.stackTrace)
    }

    @Test
    fun `畸形行必须被跳过而不是崩溃`() {
        assertNull("空行", LogStore.decode(""))
        assertNull("字段不足", LogStore.decode("123\tE\tTAG"))
        assertNull("时间戳非法", LogStore.decode("abc\tE\tTAG\tmsg"))
        assertNull("级别非法", LogStore.decode("123\tX\tTAG\tmsg"))
    }

    @Test
    fun `四个级别都必须能落盘并解码`() {
        for (level in LogStore.ALL_LEVELS) {
            LogStore.appendBlocking(level, "L", "msg-$level", null)
        }
        val levels = LogStore.snapshot().entries.map { it.level }
        assertEquals(
            "D/I 曾是非法级别（decode 会丢），现在必须完整保留（倒序）",
            listOf(LogStore.LEVEL_ERROR, LogStore.LEVEL_WARN, LogStore.LEVEL_INFO, LogStore.LEVEL_DEBUG),
            levels,
        )
    }

    @Test
    fun `级别过滤必须按严重度包含更高级别`() {
        val entries = listOf(
            LogStore.LEVEL_DEBUG,
            LogStore.LEVEL_INFO,
            LogStore.LEVEL_WARN,
            LogStore.LEVEL_ERROR,
        ).map { LogStore.decode(LogStore.encode(it, "T", "m", null))!! }

        assertEquals("全部（D）必须一条不少", 4, LogStore.filterAtLeast(entries, LogStore.LEVEL_DEBUG).size)
        assertEquals("I 及以上", 3, LogStore.filterAtLeast(entries, LogStore.LEVEL_INFO).size)
        assertEquals("W 及以上", 2, LogStore.filterAtLeast(entries, LogStore.LEVEL_WARN).size)
        val errors = LogStore.filterAtLeast(entries, LogStore.LEVEL_ERROR)
        assertEquals("E 只留错误", 1, errors.size)
        assertEquals(LogStore.LEVEL_ERROR, errors[0].level)
        assertEquals(
            "过滤不得改变原顺序",
            listOf(LogStore.LEVEL_WARN, LogStore.LEVEL_ERROR),
            LogStore.filterAtLeast(entries, LogStore.LEVEL_WARN).map { it.level },
        )
    }

    @Test
    fun `未知过滤级别必须回落全部而不是滤空`() {
        assertEquals(LogStore.LEVEL_DEBUG, LogStore.normalizeLevel("X"))
        assertEquals(LogStore.LEVEL_WARN, LogStore.normalizeLevel(LogStore.LEVEL_WARN))
        val entries = listOf("D", "W").map { LogStore.decode(LogStore.encode(it, "T", "m", null))!! }
        assertEquals(
            "持久化的脏值不能把日志页滤成空白",
            entries.size,
            LogStore.filterAtLeast(entries, "X").size,
        )
    }

    // ───────────────────── 读写顺序 ─────────────────────

    @Test
    fun `读取必须按时间倒序并受 limit 约束`() {
        repeat(5) { i ->
            LogStore.appendBlocking(LogStore.LEVEL_WARN, "T", "msg-$i", null)
        }
        val snap = LogStore.snapshot(limit = 3)
        assertEquals("limit 必须生效", 3, snap.entries.size)
        assertEquals("最新一条必须在最前", "msg-4", snap.entries[0].message)
        assertEquals("其次", "msg-3", snap.entries[1].message)
        assertEquals("再次", "msg-2", snap.entries[2].message)
        assertTrue("占用字节数应为正", snap.fileSizeBytes > 0)
    }

    @Test
    fun `清空后必须一条不剩`() {
        repeat(3) { LogStore.appendBlocking(LogStore.LEVEL_ERROR, "T", "e$it", null) }
        assertTrue(LogStore.snapshot().entries.isNotEmpty())
        LogStore.clear()
        assertEquals("clear 后必须为空", 0, LogStore.snapshot().entries.size)
        assertEquals("clear 后文件必须归零", 0L, LogStore.fileSizeBytes())
    }

    // ───────────────────── 轮转 ─────────────────────

    @Test
    fun `超过上限必须轮转且保留最新记录`() {
        // 每条约 120 字节 ⇒ 4000 条 ≈ 480KB，必然越过 256KB 上限
        val payload = "x".repeat(100)
        repeat(4000) { i ->
            LogStore.appendBlocking(LogStore.LEVEL_WARN, "ROT", "$i-$payload", null)
        }
        val size = LogStore.fileSizeBytes()
        assertTrue("轮转后文件必须小于上限（实际 $size）", size <= LogStore.MAX_BYTES)
        val entries = LogStore.snapshot(limit = LogStore.KEEP_LINES).entries
        assertTrue("轮转后必须仍有记录", entries.isNotEmpty())
        assertEquals("最新一条必须保住", "3999-$payload", entries[0].message)
        assertTrue(
            "最老一条（0-…）必须已被淘汰，否则说明轮转没生效",
            entries.none { it.message.startsWith("0-") },
        )
    }

    /** 负向自证：上限常量必须真的比"触发轮转的量"小，否则上一条会空转。 */
    @Test
    fun `轮转用例不是空转`() {
        assertTrue("MAX_BYTES 必须小于 4000×120 字节，否则轮转用例测不到东西", LogStore.MAX_BYTES < 4000L * 120L)
        assertTrue("KEEP_LINES 必须小于写入条数", LogStore.KEEP_LINES < 4000)
    }

    // ───────────────────── 详情「复制」全文（v2.39.2） ─────────────────────

    /**
     * 真机反馈「报错信息文本无法长按选择进行复制」⇒ 加了复制按钮。
     * 复制内容必须是**全文**：级别 + 时间 + tag + 正文 + **完整堆栈**。
     * 只要漏了堆栈，粘出去的日志就没法定位问题，等于白复制。
     */
    @Test
    fun `复制全文必须包含级别 时间 tag 正文与完整堆栈`() {
        val stack = "java.lang.IllegalStateException: boom\n\tat a.b.C(D.kt:1)\n\tat e.f.G(H.kt:2)"
        val entry = LogEntry(
            timestamp = 1_766_000_000_000L,
            level = LogStore.LEVEL_ERROR,
            tag = "MetingDiag",
            message = "parseSongs: no songs",
            stackTrace = stack,
        )
        val text = entry.toClipboardText()
        assertTrue("缺级别", text.contains("E"))
        assertTrue("缺 tag", text.contains("MetingDiag"))
        assertTrue("缺正文", text.contains("parseSongs: no songs"))
        assertTrue("缺堆栈首行", text.contains("IllegalStateException: boom"))
        assertTrue("缺堆栈末行（必须完整，不能只截首行）", text.contains("at e.f.G(H.kt:2)"))
        assertTrue(
            "时间戳必须是可 grep 的固定格式：实际 ${text.lineSequence().first()}",
            Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").containsMatchIn(text),
        )
    }

    @Test
    fun `无堆栈的日志复制全文不得出现空堆栈段`() {
        val entry = LogEntry(0L, LogStore.LEVEL_WARN, "T", "只有正文", null)
        val text = entry.toClipboardText()
        assertTrue("正文必须保留", text.contains("只有正文"))
        assertEquals("无堆栈时只能是 2 行（头 + 正文）", 2, text.lines().size)
    }

    // ───────────────────── 完整性与行数自证（v2.39.2 真机「复制不全」） ─────────────────────

    /**
     * 真机反馈「复制粘出来本身就少」⇒ 需要能**量出来**完整性的判据。
     *
     * 这里走完整链路：`encode` → 落盘 → `snapshot()` → `toClipboardText()`，逐字符比对。
     * 只要有一个环节吃掉内容（转义、分行、读取、拼接），这条就红。
     * ⚠️ 不用 `Throwable` 造堆栈：`stackTraceToString()` 会加首行和帧，无法逐字符比对，
     * 真实异常路径由下一条用例单独锁。
     */
    @Test
    fun `超长堆栈必须无损往返`() {
        val stack = buildString {
            append("java.lang.IllegalStateException: boom\t含制表符\r含回车\n")
            for (i in 1..80) append("\tat com.example.C$i.m$i(F$i.kt:$i)\n")
            append("Caused by: java.io.IOException: 反斜杠\\路径\\x\n")
            append("\t... 12 more")
        }
        val message = "消息\t带制表符\n第二行\\结尾"
        val file = File(File(root, "logs"), "app.log")
        file.parentFile?.mkdirs()
        file.writeText(
            LogStore.encode(LogStore.LEVEL_ERROR, "无损\tTAG", message, stack) + "\n",
            Charsets.UTF_8,
        )

        val entry = LogStore.snapshot().entries.first()
        assertEquals("堆栈必须逐字符一致", stack, entry.stackTrace)
        assertEquals("正文必须逐字符一致", message, entry.message)
        assertEquals("tag 必须逐字符一致", "无损\tTAG", entry.tag)
        assertTrue("完整写入的记录不得被标记为截断", !entry.truncated)
        assertTrue("复制全文必须以完整堆栈结尾", entry.toClipboardText().endsWith(stack))
        assertEquals(
            "行数必须与实际一致（正文 2 行 + 堆栈 ${stack.count { it == '\n' } + 1} 行）",
            2 + stack.count { it == '\n' } + 1,
            entry.totalLines,
        )
    }

    /** 真实异常路径（`appendBlocking` + `stackTraceToString()`）也必须整段留下来。 */
    @Test
    fun `真实异常堆栈经同步写入后必须完整`() {
        val throwable = IllegalStateException("boom")
        LogStore.appendBlocking(LogStore.LEVEL_ERROR, "REAL", "出错了", throwable)
        val entry = LogStore.snapshot().entries.first()
        assertEquals(throwable.stackTraceToString(), entry.stackTrace)
        assertTrue("首行异常类型不能丢", entry.stackTrace!!.startsWith("java.lang.IllegalStateException: boom"))
    }

    /** 纯函数：行数/字符数是「全不全」的唯一客观判据，必须单独锁住。 */
    @Test
    fun `行数与字符数按正文加堆栈合计`() {
        val entry = LogEntry(0L, LogStore.LEVEL_ERROR, "T", "a\nb", "c\nd\ne")
        assertEquals(5, entry.totalLines)
        assertEquals(8, entry.totalChars)
        val noStack = LogEntry(0L, LogStore.LEVEL_WARN, "T", "单行", null)
        assertEquals(1, noStack.totalLines)
        assertEquals(2, noStack.totalChars)
        val empty = LogEntry(0L, LogStore.LEVEL_WARN, "T", "", null)
        assertEquals("空正文不得算成 1 行", 0, empty.totalLines)
    }

    /**
     * **写入被打断**的最后一条必须被标记：文件末尾没有换行 ⇒ 那次 `writeLine` 没写完
     * （进程被杀 / 断电 / 存储满）。不标记的话，用户只会看到「复制出来的比预期的少」。
     */
    @Test
    fun `末尾没有换行的最后一条必须标记为截断`() {
        val file = File(File(root, "logs"), "app.log")
        file.parentFile?.mkdirs()
        val l1 = LogStore.encode(LogStore.LEVEL_WARN, "T1", "第一条", null)
        val l2 = LogStore.encode(LogStore.LEVEL_ERROR, "T2", "第二条", "完整堆栈\nat a.b(C.kt:1)")
        val partial = LogStore.encode(LogStore.LEVEL_ERROR, "T3", "第三条", "堆栈前半截")
        // ⚠️ 末尾故意不写 \n —— 模拟写入被中断
        file.writeText("$l1\n$l2\n$partial", Charsets.UTF_8)

        val entries = LogStore.snapshot().entries
        assertEquals(3, entries.size)
        assertEquals("T3", entries[0].tag)
        assertTrue("被打断的最后一条必须标记 truncated", entries[0].truncated)
        assertTrue("前面写完整的记录不得被误标", !entries[1].truncated && !entries[2].truncated)
    }

    /** 负向自证：正常以换行结尾的文件，任何一条都不得被判成截断。 */
    @Test
    fun `负向自证 完整文件不得误判截断`() {
        val file = File(File(root, "logs"), "app.log")
        file.parentFile?.mkdirs()
        val l1 = LogStore.encode(LogStore.LEVEL_WARN, "T1", "第一条", null)
        file.writeText("$l1\n", Charsets.UTF_8)
        val entries = LogStore.snapshot().entries
        assertEquals(1, entries.size)
        assertTrue("护栏空转：完整文件被误判成截断", !entries[0].truncated)
    }
}
