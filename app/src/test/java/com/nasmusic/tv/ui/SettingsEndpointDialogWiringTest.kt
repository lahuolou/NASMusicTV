package com.nasmusic.tv.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * T16 宿主瘦身门禁（方案 §5.3 端点对话框收敛 + §5.4 网络测试下沉）。
 *
 * ## 为什么需要它
 * `SettingsScreen.kt` 曾同时持有 **9 个** `TextInputDialog(` 宿主（百度两目录回退、
 * 天气 Key、Meting、Jamendo、MV、分离模型自定义源、酷狗/网易云歌词端点），每个都带
 * 自己的 `show*Dialog` 布尔与确认逻辑；网络连通性测试则是一段内联在 `onRunNetworkTest`
 * lambda 里的 `HttpURLConnection` I/O —— UI 层持有网络代码，且离开设置页时
 * `rememberCoroutineScope` 会把进行中的探针直接取消。T16 收敛为：
 * - **1 个通用宿主**：`private data class EndpointEditor(titleRes, hintRes, value, masked, onConfirm)`，
 *   `SettingsScreen` 只持有一个 `endpointEditor: EndpointEditor?`；
 * - **探针下沉**：`MainViewModel.runNetworkProbe()`，UI 只发意图并渲染
 *   `isNetworkTesting` / `networkTestStatus` 两个参数。
 *
 * ## 判据（全部在剥注释后的源码上、按词边界判定）
 * 1. `SettingsScreen.kt` 的 `TextInputDialog(` 调用点**恰好 1 个**（通用宿主）；
 * 2. `SettingsScreen.kt` 不含 `OkHttpClient` / `Request.Builder`（UI 层不持有网络 I/O）；
 * 3. `MainViewModel.kt` 含 `fun runNetworkProbe(`，`SettingsBranch.kt` 含
 *    `viewModel.runNetworkProbe(` 接线（UI 侧 `onRunNetworkTest = onRunNetworkProbe`）；
 * 4. 旧的 9 个 `show*Dialog` 布尔 + 3 个 error 幽灵状态（`metingUrlError` 等只写不读，
 *    T16 随收敛删除）**不得回流**；
 * 5. 两个确认弹窗仍在原地：删除备份（`backupToDelete`）与清空下载（`ConfirmDialog(`）。
 *
 * ## 已知边界（⛔ 别当门没用）
 * 「每个打开对话框的入口都构造 `EndpointEditor`」无法静态断言（需要跨回调追踪），
 * 由判据 1 + 4 组合闭环：没有旧布尔，`TextInputDialog` 又只剩宿主一处，
 * 对话框便只能经 `endpointEditor` 打开。
 *
 * ⚠️ 本文件注释里不写「斜杠 + 星号」序列（块注释提前闭合，见 [SettingsRowWidthScanTest]
 * 类注释同款警告）；负向自证夹具用字符串拼接构造，不嵌套三引号。
 */
class SettingsEndpointDialogWiringTest {

    // ─────────────────── 正向：真实源码 ───────────────────

    /** 判据 1：`SettingsScreen.kt` 的 `TextInputDialog(` 调用点收敛为恰好 1 个。 */
    @Test
    fun `端点编辑对话框必须收敛为恰好一个通用宿主`() {
        val clean = settingsScreenClean()
        val error = checkExactlyOneDialogHost(clean)
        if (error != null) fail(error)
    }

    /** 判据 2：设置页 UI 层不得持有网络 I/O 标识。 */
    @Test
    fun `SettingsScreen 不得包含网络客户端标识`() {
        val clean = settingsScreenClean()
        val found = findForbiddenNetworkIdentifiers(clean)
        assertTrue(
            "设置页 UI 层出现了网络客户端标识（I/O 必须在 MainViewModel.runNetworkProbe）：$found",
            found.isEmpty(),
        )
    }

    /** 判据 3：探针下沉 ViewModel 且两端接线。 */
    @Test
    fun `网络探针必须下沉 MainViewModel 并完成接线`() {
        val vmClean = stripComments(readMainSource("ui/viewmodel/MainViewModel.kt"))
        assertTrue(
            "MainViewModel 缺少 fun runNetworkProbe( —— 探针没有下沉（或被改名没同步门禁）",
            Regex("""\bfun\s+runNetworkProbe\s*\(""").containsMatchIn(vmClean),
        )
        val screenClean = settingsScreenClean()
        assertTrue(
            "SettingsScreen 的 NETWORK 分支应把 onRunNetworkTest 指向 onRunNetworkProbe（UI 只发意图）",
            Regex("""\bonRunNetworkTest\s*=\s*onRunNetworkProbe\b""").containsMatchIn(screenClean),
        )
        val branchClean = stripComments(readMainSource("ui/components/branches/SettingsBranch.kt"))
        assertTrue(
            "SettingsBranch 未接线 viewModel.runNetworkProbe() —— UI 发出的意图没有落到 ViewModel",
            Regex("""\brunNetworkProbe\s*\(""").containsMatchIn(branchClean),
        )
    }

    /** 判据 4：旧对话框状态与幽灵 error 标识防回流（详见 [FORBIDDEN_STATE_IDS]）。 */
    @Test
    fun `旧对话框状态标识不得回流`() {
        val clean = settingsScreenClean()
        val found = findForbiddenStateIds(clean)
        assertTrue(
            "SettingsScreen 又出现了收敛前的对话框状态标识（应改用 endpointEditor）：$found",
            found.isEmpty(),
        )
    }

    /** 判据 5：两个确认弹窗仍在原地（方案「共不超过 3 个对话框」口径的另一半）。 */
    @Test
    fun `删除备份与清空下载两个确认弹窗必须仍在`() {
        val clean = settingsScreenClean()
        val confirmCount = Regex("""\bConfirmDialog\s*\(""").findAll(clean).count()
        assertTrue(
            "清空下载确认弹窗（ConfirmDialog）应为恰好 1 处，实际 $confirmCount",
            confirmCount == 1,
        )
        assertTrue(
            "删除备份确认弹窗（backupToDelete 宿主）不见了",
            Regex("""\bbackupToDelete\s*\?\.\s*let\b""").containsMatchIn(clean),
        )
    }

    /** 通用宿主必须真的有人用（防止 data class 挂着没人构造、判据空转）。 */
    @Test
    fun `通用宿主必须存在且被构造`() {
        val clean = settingsScreenClean()
        assertTrue(
            "SettingsScreen 缺少 private data class EndpointEditor 定义",
            Regex("""\bdata\s+class\s+EndpointEditor\s*\(""").containsMatchIn(clean),
        )
        assertTrue(
            "没有任何入口构造 EndpointEditor —— 对话框已断线（空转防护）",
            Regex("""\bendpointEditor\s*=\s*EndpointEditor\s*\(""").containsMatchIn(clean),
        )
    }

    // ─────────────────── 负向自证：护栏本身必须有效 ───────────────────

    /** 负向 ①：塞回第二个 TextInputDialog 调用点，判据必须转负（同一份谓词）。 */
    @Test
    fun `负向自证 第二个 TextInputDialog 宿主必须被检出`() {
        val fixture = "val probePrefix = 1\n" +
            "TextInputDialog(title = \"x\", hint = \"y\", initialValue = \"\", onConfirm = {}, onDismiss = {})\n" +
            "TextInputDialog(title = \"z\", hint = \"y\", initialValue = \"\", onConfirm = {}, onDismiss = {})\n"
        val error = checkExactlyOneDialogHost(stripComments(fixture))
        assertNotNull("护栏空转：第二个 TextInputDialog 宿主没被判负", error)
    }

    /** 负向 ②：把网络客户端标识塞回文本必须判负（与正向同一份 findForbidden）。 */
    @Test
    fun `负向自证 网络客户端标识必须被检出`() {
        val fixture = "val client = OkHttpClient()\nval req = Request.Builder().url(\"https://x\").build()\n"
        val found = findForbiddenNetworkIdentifiers(stripComments(fixture))
        assertTrue("护栏空转：网络客户端标识没被判负（findForbiddenNetworkIdentifiers 失效）", found.isNotEmpty())
    }

    /** 负向 ③：把旧对话框状态标识塞回文本必须判负（与正向同一份 findForbidden）。 */
    @Test
    fun `负向自证 旧对话框状态标识必须被检出`() {
        val fixture = "var showMetingUrlDialog by remember { mutableStateOf(false) }\n" +
            "var metingUrlError by remember { mutableStateOf<String?>(null) }\n"
        val found = findForbiddenStateIds(stripComments(fixture))
        assertTrue("护栏空转：旧对话框状态标识没被判负（findForbiddenStateIds 失效）", found.isNotEmpty())
    }

    /** 负向 ④：注释里出现敏感标识不得误报（注释剥离生效）。 */
    @Test
    fun `负向自证 注释里的敏感标识不得误报`() {
        val fixture = "// 反例（写在这里只是举例）：showMetingUrlDialog 与 OkHttpClient 都不许出现\nval x = 1\n"
        assertTrue(
            "误报：注释里的举例被判违规（注释剥离失效）",
            findForbiddenStateIds(stripComments(fixture)).isEmpty() &&
                findForbiddenNetworkIdentifiers(stripComments(fixture)).isEmpty(),
        )
    }

    // ─────────────────── 内部实现 ───────────────────

    /**
     * 收敛前允许、收敛后必须消失的对话框状态标识（剥注释 + 词边界）。
     * 7 个 `show*Dialog` 布尔 + 3 个 error 幽灵状态 + 2 个只为旧探针/旧预解析存在的局部量。
     * ⚠️ `showBaiduMusicRootDialog` / `showBaiduMvDirDialog` **不在**禁词里：T16 后它们
     * 只承载 `BaiduDirPickerDialog`（目录树形态，与端点编辑不同构不硬并），
     * 其文本回退分支已并入通用端点宿主。
     */
    private val FORBIDDEN_STATE_IDS = listOf(
        "showMetingUrlDialog",
        "showMvUrlDialog",
        "showWeatherApiKeyDialog",
        "showLyricsKugouDialog",
        "showLyricsNeteaseDialog",
        "showJamendoClientIdDialog",
        "showCustomModelUrlDialog",
        "metingUrlError",
        "mvUrlError",
        "lyricsUrlError",
        "networkTestScope",
        "networkTestContext",
    )

    /** UI 层不得出现的网络 I/O 标识（探针已下沉 ViewModel）。 */
    private val FORBIDDEN_NETWORK_IDS = listOf(
        Regex("""\bOkHttpClient\b"""),
        Regex("""\bRequest\.Builder\b"""),
    )

    private fun wordBoundaryRegex(id: String): Regex = Regex("""\b${Regex.escape(id)}\b""")

    /** 正向判据 1 的谓词：通过返回 null，否则返回人话错误信息（负向共用）。 */
    private fun checkExactlyOneDialogHost(clean: String): String? {
        val count = Regex("""\bTextInputDialog\s*\(""").findAll(clean).count()
        return if (count == 1) null
        else "SettingsScreen 的 TextInputDialog 调用点应恰好 1 个（通用端点编辑宿主），实际 $count"
    }

    private fun findForbiddenStateIds(clean: String): List<String> =
        FORBIDDEN_STATE_IDS.filter { wordBoundaryRegex(it).containsMatchIn(clean) }

    private fun findForbiddenNetworkIdentifiers(clean: String): List<String> =
        FORBIDDEN_NETWORK_IDS.filter { it.containsMatchIn(clean) }.map { it.pattern }

    /** 定位 main 源码根目录（各扫描门各自持一份私有 helper，本项目既有范式）。 */
    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertNotNull(
            "找不到源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found,
        )
        return found!!
    }

    /** 读 main 源集内的相对路径文件；不存在直接失败（这道门不允许被静默禁用）。 */
    private fun readMainSource(relative: String): String {
        val f = File(mainSourceRoot(), relative)
        assertTrue("源码文件不存在：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    private fun settingsScreenClean(): String = stripComments(readMainSource("ui/screens/SettingsScreen.kt"))

    /** 返回与 [text] 的 [start]（值为 [open]）配对的 [close] 下标；找不到返回 -1。字符串/字符字面量内的括号不计。 */
    private fun matchDelimiter(text: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var inStr = false
        var inChar = false
        var inRaw = false
        var i = start
        while (i < text.length) {
            if (inRaw) {
                val run = quoteRun(text, i)
                if (run >= 3) { inRaw = false; i += run } else i++
                continue
            }
            val c = text[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
            } else if (inChar) {
                if (c == '\\') { i += 2; continue }
                if (c == '\'') inChar = false
            } else {
                when {
                    isRawFence(text, i) -> { inRaw = true; i += 3; continue }
                    c == '"' -> inStr = true
                    c == '\'' -> inChar = true
                    c == open -> depth++
                    c == close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun isRawFence(text: String, p: Int): Boolean = quoteRun(text, p) >= 3

    private fun quoteRun(text: String, p: Int): Int {
        var k = 0
        while (p + k < text.length && text[p + k] == '"') k++
        return k
    }

    /**
     * 把注释内容替换成空格（保留长度与换行位置）。必须是状态机：
     * 块注释可嵌套、字符串里的行注释符不是注释、必须认原始字符串三引号且收尾按连续引号串整段吃。
     * 完整推导见 [SettingsRowWidthScanTest] 的同名函数（本文件自持一份，扫描门互不依赖）。
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inStr = false
        var inChar = false
        var inRaw = false
        var inLineComment = false
        var inBlockComment = false
        var blockDepth = 0
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLineComment -> {
                    if (c == '\n') { inLineComment = false; sb.append('\n') } else sb.append(' ')
                    i++
                }
                inBlockComment -> {
                    if (c == '/' && n == '*') {
                        blockDepth++; sb.append("  "); i += 2
                    } else if (c == '*' && n == '/') {
                        blockDepth--; sb.append("  "); i += 2
                        if (blockDepth == 0) inBlockComment = false
                    } else {
                        sb.append(if (c == '\n') '\n' else ' ')
                        i++
                    }
                }
                inRaw -> {
                    val run = quoteRun(src, i)
                    if (run >= 3) { sb.append("\"".repeat(run)); inRaw = false; i += run } else {
                        sb.append(c); i++
                    }
                }
                inStr -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                    if (c == '"') inStr = false
                    i++
                }
                inChar -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                    if (c == '\'') inChar = false
                    i++
                }
                c == '/' && n == '/' -> { inLineComment = true; sb.append("  "); i += 2 }
                c == '/' && n == '*' -> { inBlockComment = true; blockDepth = 1; sb.append("  "); i += 2 }
                isRawFence(src, i) -> { sb.append("\"\"\""); inRaw = true; i += 3 }
                else -> {
                    if (c == '"') inStr = true
                    if (c == '\'') inChar = true
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }
}
