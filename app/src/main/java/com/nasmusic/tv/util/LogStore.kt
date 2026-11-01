package com.nasmusic.tv.util

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 一条持久化的运行日志。
 *
 * @param timestamp 毫秒时间戳（`System.currentTimeMillis()`）
 * @param level [LogStore.LEVEL_DEBUG] / [LogStore.LEVEL_INFO] / [LogStore.LEVEL_WARN] / [LogStore.LEVEL_ERROR]
 * @param tag 模块标签（与 logcat 的 tag 一致）
 * @param message 正文（已反转义，可含换行）
 * @param stackTrace 异常堆栈全文（无异常时为 null）
 * @param truncated **本条记录在写入时被打断**（文件末尾没有换行结尾），
 *   内容可能只写了前半截。由 [LogStore.snapshot] 判定，⛔ 不从文件里解码。
 */
data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val stackTrace: String?,
    val truncated: Boolean = false,
) {
    /** 正文 + 堆栈的总行数（详情页「共 N 行」用；纯函数，便于单测） */
    val totalLines: Int
        get() = countLines(message) + (stackTrace?.let { countLines(it) } ?: 0)

    /** 正文 + 堆栈的总字符数（详情页「共 N 字符」用；纯函数，便于单测） */
    val totalChars: Int
        get() = message.length + (stackTrace?.length ?: 0)

    private fun countLines(text: String): Int =
        if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
}

/**
 * 日志快照：最近若干条 + 文件占用字节数。
 *
 * @param entries **按时间倒序**（最新在前），条数受 `limit` 约束
 * @param fileSizeBytes 日志文件当前字节数（清空后为 0）
 */
data class LogSnapshot(
    val entries: List<LogEntry> = emptyList(),
    val fileSizeBytes: Long = 0L,
)

/**
 * 运行日志落盘存储（设置页「日志」tab 的数据源）。
 *
 * ## 为什么要落盘
 * 电视上没法连着 adb 看 logcat，用户报「刚才闪退了/一直加载不出来」时，
 * 现场早就没了。这里把日志追加到 `filesDir/logs/app.log`，重启后仍可回看；
 * 崩溃（未捕获异常）走同步写，尽量保证最后一条留得下来。
 *
 * ## 级别与 release
 * - **W / E 始终落盘**（release 日志页只应有这两级）；
 * - **D / I 只在 Debug 构建落盘**（由 [AppLog] 在入口处按 `BuildConfig.DEBUG` 拦截）——
 *   release 既不打 logcat 也不写盘，调试信息不会把现场挤掉；
 * - 页面按「最低级别」过滤（[filterAtLeast]），级别是字符串而非枚举，保持文格式向后兼容。
 *
 * ## 格式（单行 TSV + 转义）
 * `时间戳 \t 级别 \t tag \t 正文 [\t 堆栈]`，其中字段内的
 * `\` `\t` `\n` `\r` 分别转义为 `\\` `\t` `\n` `\r`（字面量）。
 * 单行协议让「追加」永远是 O(1)、损坏一行不影响其余行。
 *
 * ## 容量与轮转
 * 单文件上限 [MAX_BYTES]（256KB），超限时**保留最新** [KEEP_LINES] 行重写
 * —— 宁可丢老日志，也不能丢刚发生的崩溃现场。
 *
 * ## 线程模型
 * - 普通写入走单线程执行器（日志调用点可能在主线程，不能做同步磁盘 I/O）；
 * - 崩溃路径用 [appendBlocking]（进程马上要死，异步任务等不到执行）；
 * - [snapshot] / [clear] 会先排空队列，保证「刚记的能读到、刚清的真没了」。
 *
 * ⚠️ **不得依赖 Android 类型**（只收 `File`）：这样单测能在免 Gradle 通道直接跑。
 * ⚠️ **不得调用 [AppLog]**：否则形成 写入→打印→再写入 的无限递归（门禁 `LogsTabWiringGateTest` 锁死）。
 */
object LogStore {

    /** 调试级（仅 Debug 构建落盘） */
    const val LEVEL_DEBUG = "D"

    /** 信息级（仅 Debug 构建落盘） */
    const val LEVEL_INFO = "I"

    /** 警告级（始终落盘） */
    const val LEVEL_WARN = "W"

    /** 错误级（含崩溃，始终落盘） */
    const val LEVEL_ERROR = "E"

    /** 全部级别（日志页「全部」筛选项的取值） */
    val ALL_LEVELS: Set<String> = setOf(LEVEL_DEBUG, LEVEL_INFO, LEVEL_WARN, LEVEL_ERROR)

    /**
     * 级别严重度：D=0 < I=1 < W=2 < E=3。未知级别按 D 处理——
     * 旧文件里若出现未识别字母，宁可显示也不要整条丢掉（decode 已挡掉，这里是兜底）。
     */
    fun severityOf(level: String): Int = when (level) {
        LEVEL_ERROR -> 3
        LEVEL_WARN -> 2
        LEVEL_INFO -> 1
        LEVEL_DEBUG -> 0
        else -> 0
    }

    /** 过滤出「严重度 ≥ [minLevel]」的条目；顺序保持不变。 */
    fun filterAtLeast(entries: List<LogEntry>, minLevel: String): List<LogEntry> {
        val floor = severityOf(minLevel)
        if (floor <= 0) return entries
        return entries.filter { severityOf(it.level) >= floor }
    }

    /** 校验级别取值：不认识的级别回落「全部」（D），避免持久化的脏值把日志页滤成空白。 */
    fun normalizeLevel(level: String): String =
        if (level in ALL_LEVELS) level else LEVEL_DEBUG

    /** 单文件上限：256KB（约 2000 条短日志） */
    internal const val MAX_BYTES = 256L * 1024L

    /** 轮转后保留的最新行数 */
    internal const val KEEP_LINES = 400

    /** 默认读取条数（UI 一次渲染太多会拖慢低端电视） */
    const val READ_LIMIT = 100

    private const val DIR_NAME = "logs"
    private const val FILE_NAME = "app.log"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-log-writer").apply { isDaemon = true }
    }

    /** 串行化「追加 / 轮转 / 删除」三种文件操作（异步线程与崩溃同步写共用） */
    private val lock = Any()

    @Volatile
    private var logDir: File? = null

    /**
     * 初始化存储目录（`<filesDir>/logs`）。
     *
     * 幂等：每次 Application 启动调用一次即可；测试可传临时目录。
     */
    fun init(filesDir: File) {
        val dir = File(filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
    }

    private fun logFile(): File? = logDir?.let { File(it, FILE_NAME) }

    /**
     * 异步追加一条日志（[LogStore.LEVEL_DEBUG] / [LogStore.LEVEL_INFO] /
     * [LogStore.LEVEL_WARN] / [LogStore.LEVEL_ERROR]）。
     *
     * 未初始化时静默丢弃 —— 应用启动最早期（`LogStore.init` 之前）的日志没有落点，
     * 此时 `AppLog` 仍照常输出 logcat。
     */
    fun append(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (logDir == null) return
        val line = encode(level, tag, message, throwable?.stackTraceToString())
        io.execute { runCatching { writeLine(line) } }
    }

    /**
     * 同步追加（崩溃路径专用）。
     *
     * ⚠️ 只给 `UncaughtExceptionHandler` 用：进程随即终止，执行器里的任务来不及跑。
     */
    fun appendBlocking(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (logDir == null) return
        val line = encode(level, tag, message, throwable?.stackTraceToString())
        synchronized(lock) { runCatching { writeLine(line, durable = true) } }
    }

    /**
     * 读取最近日志（倒序）+ 文件占用。
     *
     * @param limit 最多返回多少条（默认 [READ_LIMIT]）
     */
    fun snapshot(limit: Int = READ_LIMIT): LogSnapshot {
        drainQueue()
        val file = logFile() ?: return LogSnapshot()
        val size = if (file.exists()) file.length() else 0L
        val entries = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val text = file.readText(Charsets.UTF_8)
            if (text.isEmpty()) return@runCatching emptyList()
            // ⚠️ 最后一行没有以换行结尾 ⇒ 那次写入被打断（进程被杀 / 断电 / 存储满），
            //    这一条**末尾可能缺内容**。不丢弃（崩溃现场最有价值），但必须**标记出来**：
            //    否则用户只会看到「复制出来的比预期的少」，却不知道为什么（v2.39.2 真机反馈）。
            val endsWithNewline = text.endsWith("\n")
            val rawLines = text.split('\n').filter { it.isNotBlank() }
            val lastIndex = rawLines.lastIndex
            rawLines.mapIndexedNotNull { idx, line ->
                decode(line)?.let { entry ->
                    if (!endsWithNewline && idx == lastIndex) entry.copy(truncated = true) else entry
                }
            }.asReversed().take(limit.coerceAtLeast(0))
        }.getOrDefault(emptyList())
        return LogSnapshot(entries = entries, fileSizeBytes = size)
    }

    /** 清空全部日志（等异步队列排空后删除文件，避免「清完又冒出一条」）。 */
    fun clear() {
        runCatching {
            io.submit { synchronized(lock) { logFile()?.delete() } }.get(2, TimeUnit.SECONDS)
        }
    }

    /** 当前日志文件字节数（未初始化/不存在时为 0）。 */
    fun fileSizeBytes(): Long = logFile()?.takeIf { it.exists() }?.length() ?: 0L

    // ───────────────────── 内部实现 ─────────────────────

    /** 排空已入队的写任务：测试与「刚记就读」都依赖这个可见性保证。 */
    private fun drainQueue() {
        runCatching { io.submit { }.get(2, TimeUnit.SECONDS) }
    }

    /**
     * 追加一行（调用方负责持锁）。
     *
     * @param durable 崩溃路径必须为 true：进程随即被系统杀掉，只写进页缓存不够，
     *   必须 `fd.sync()` 真正落盘。普通日志走 [File.appendText]（关闭时已 flush）。
     */
    private fun writeLine(line: String, durable: Boolean = false) {
        synchronized(lock) {
            val file = logFile() ?: return
            if (durable) {
                FileOutputStream(file, true).use { out ->
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
            } else {
                file.appendText(line + "\n", Charsets.UTF_8)
            }
            if (file.length() > MAX_BYTES) rotate(file)
        }
    }

    /** 超限重写：只保留最新 [KEEP_LINES] 行（先写临时文件再替换，避免半截文件）。 */
    private fun rotate(file: File) {
        val kept = runCatching { file.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            .takeLast(KEEP_LINES)
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        runCatching {
            tmp.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { runCatching { tmp.delete() } }
    }

    /** 编码一行（字段转义，保证单行协议）。 */
    internal fun encode(level: String, tag: String, message: String, stackTrace: String?): String {
        val base = "${System.currentTimeMillis()}\t$level\t${escape(tag)}\t${escape(message)}"
        return if (stackTrace.isNullOrEmpty()) base else base + "\t" + escape(stackTrace)
    }

    /** 解码一行；畸形行返回 null（损坏一行不得拖垮整个日志页）。 */
    internal fun decode(line: String): LogEntry? {
        if (line.isBlank()) return null
        val parts = line.split('\t')
        if (parts.size < 4) return null
        val ts = parts[0].toLongOrNull() ?: return null
        val level = parts[1]
        if (level !in ALL_LEVELS) return null
        return LogEntry(
            timestamp = ts,
            level = level,
            tag = unescape(parts[2]),
            message = unescape(parts[3]),
            stackTrace = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let { unescape(it) },
        )
    }

    private fun escape(src: String): String {
        val sb = StringBuilder(src.length + 16)
        for (ch in src) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescape(src: String): String {
        if (!src.contains('\\')) return src
        val sb = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            if (ch == '\\' && i + 1 < src.length) {
                when (src[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    else -> { sb.append(ch); i++ }
                }
            } else {
                sb.append(ch); i++
            }
        }
        return sb.toString()
    }
}

/**
 * 详情「复制」按钮写进剪贴板的全文（v2.39.2）。
 *
 * ## 为什么要一个函数而不是在 UI 里拼字符串
 * ① 电视上**没法长按选中文本**（真机反馈：「报错信息文本无法长按选择进行复制」），
 *    一键复制是唯一可用的出口，复制内容就必须**完整**（含堆栈），不能只是屏幕上那几行；
 * ② 拼装规则要能被单测直接调用（本文件是纯 JVM），UI 里拼就只能靠源码门禁猜。
 *
 * 时间固定用 [Locale.US]：这段文本是要粘到别处（聊天窗口 / issue）的**数据**，
 * 跟着系统 Locale 走会变成「2026/10/20 上午12:17」这种没法 grep 的形态。
 */
fun LogEntry.toClipboardText(): String = buildString {
    append(level).append(' ')
    append(formatLogTimestamp(timestamp)).append(' ')
    append(tag).append('\n')
    append(message)
    val stack = stackTrace
    if (!stack.isNullOrBlank()) {
        append('\n').append(stack)
    }
}

/** 日志时间戳格式化（`yyyy-MM-dd HH:mm:ss`，固定 [Locale.US]）。 */
internal fun formatLogTimestamp(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))

