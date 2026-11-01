package com.nasmusic.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「设置分区里放进 `Row` 的**整行瓦片**必须显式给定宽度」的门禁（方案 §4.8，G8）。
 *
 * ## 为什么需要它
 * `Row` 给每个 wrap-content 子项的 `maxWidth` 是**本行剩余宽度**。默认 `fillMaxWidth()`
 * 的设置组件放进 `Row` 后，第一个吃满整行、后续兄弟被挤成 **0 宽** —— 表现为"选项不见了"
 * （v2.35.0 音质档、v2.37.0 画面适配、以及**当前 main 上仍存在的** v2.38.x 淡入淡出时长档）。
 * 竖屏内容宽只有 TV 的 26%（§4.8 ②），同一段代码在手机上还会把芯片压成高块、互相参差。
 *
 * ## 为什么必须新增这一层
 * 已有的 [ChipContentWidthScanTest] 守的是 `FocusableSurface` 那一层（"内容别用 fillMaxSize"）。
 * 时长档那个调用点里**连 `FocusableSurface` 都没出现**（包在 `SettingActionButton` 内）——
 * 抓手差了一层组件，所以三代都没拦住。G8 守**上一层：组件调用方**。两层缺一不可。
 *
 * ## 判定规则（命中需同时满足）
 * ① 某处 `Row` 调用带**内容块** —— 两种写法都要认：`Row(…) { … }` 与 `Row { … }`
 *    （后者没有左括号，只按 `Row(` 找会漏掉一批手写用例）；
 * ② 该内容块内出现"默认整行宽"的设置组件调用（`SettingActionButton(` / `SettingSwitch(`，
 *    或**不带** `chip = true` 的 `SettingsTile(`）；
 * ③ 该组件调用的**实参区**（括号配对，不含尾随 lambda）不含任何"已给宽度"的修饰符
 *    （`.width(` / `widthIn(` / `weight(` / `.size(`）；
 *    ⚠️ `fillMaxWidth(` **不在放行名单里** —— `Row` 里第一枚 `fillMaxWidth()` 正是本门的病灶，
 *    写它等于承认"这一枚要独占整行"，那就该从 `Column` 直接调用，不要包 `Row`；
 * ④ 该调用所在行（或向前 3 行内）没有豁免标记 `RowWidth-exempt: <理由>`；
 * ⑤ 注释（`//`、`/* … */`）先剥离 —— 注释里举例的写法不算违规。
 *
 * ⛔ **判据必须落在"组件调用"上，不能落在"Row 里有 ≥2 个子项"上**：
 * 时长档那行字面上只有 1 个调用点（`durations.forEach { … }` 生成 5 个子项），
 * 按子项数判会**静默空转** —— 报"0 处违规"却什么都没查。
 *
 * ## 作用域
 * `ui/screens/SettingsScreen.kt` + `ui/screens/settings/` 下的全部 `XxxSection.kt` +
 * `ui/screens/settings/ImportedPlaylistRow.kt`。
 * ⛔ 不含 `SettingsComponents.kt` / `SettingsTokens.kt`：那是组件**定义**，
 * 整行瓦片的默认 `fillMaxWidth()` 与 `SettingsChipRow` 内部唯一那处 `Box(weight(1f))`
 * 都属实现细节（A3），放进作用域会把正解本身判成违规。
 *
 * ⚠️ 本文件注释里**不要写路径通配**（例如「settings 目录下的星号 Section.kt」）：
 * 那个「斜杠 + 星号」在 Kotlin 里是块注释的**开启**符号，会把后半个文件全吞进注释，
 * 报错只会在文件末尾来一句 `Unclosed comment`。同理，正则里的 `/*` 与 `*/`
 * 都要写成 `/\*` 与 `\*\/`。这两条都是本文件第一版踩过的（由独立编译通道当场抓到）。
 */
class SettingsRowWidthScanTest {

    @Test
    fun `Row 内的整行设置瓦片必须显式给定宽度或改走 SettingsChipRow`() {
        val root = mainSourceRoot()
        var files = 0
        var rowBlocks = 0
        var checkedCalls = 0
        val violations = mutableListOf<String>()

        scopedFiles(root).forEach { file ->
            files++
            val raw = file.readText()
            val clean = stripComments(raw)
            val scan = scanRowWidth(clean, raw)
            rowBlocks += scan.rowBlocks
            checkedCalls += scan.checkedCalls
            val rel = file.relativeTo(root).path.replace('\\', '/')
            scan.violations.forEach { violations += "$rel:$it" }
        }

        assertTrue(
            "作用域内没有扫到任何源码文件 —— 目录或文件名规则失配：${root.absolutePath}",
            files > 0,
        )
        // ⚠️ 空转自证分两半，缺一不可：
        //    ① `rowBlocks > 0` 证明「容器扫描」在真实代码上活着；
        //    ② 内联探针证明「组件扫描 + 宽度过滤」活着。
        //
        //    ⛔ 不能写成 `checkedCalls > 0`（方案原文是这么写的，但那是**不可满足**的断言）：
        //    `checkedCalls` 只统计「放进 Row 又没给宽度」的调用，而这条门禁一旦修绿，
        //    该数**必然为 0** —— 拿它当活性证明，等于要求代码永远保持脏。
        //    活性改由探针负责：探针是常量源码，不随生产代码变干净而失效。
        assertTrue(
            "作用域内没有扫到任何带内容块的 `Row(` —— 扫描逻辑失配（空转），结果不可信",
            rowBlocks > 0,
        )
        assertTrue(
            "组件扫描活性自证失败：内联反例没被检出，判据已失效（这道门现在是假绿）",
            scanRowWidth(stripComments(LIVENESS_PROBE), LIVENESS_PROBE).violations.isNotEmpty(),
        )

        if (violations.isNotEmpty()) {
            fail(
                "以下设置组件被放进 `Row` 却没有给定宽度：\n" +
                    violations.joinToString("\n") { "  • $it" } +
                    "\n\n（本次扫到 $files 个文件、$rowBlocks 个 `Row` 内容块、" +
                    "$checkedCalls 个「放进 Row 又没给宽度」的组件调用）\n\n" +
                    "症状：TV 上第一个吃满整行、后续兄弟被挤成 **0 宽**（选项不见了）；" +
                    "竖屏（内容宽只有 TV 的 26%）芯片被压成高块且互相参差。\n" +
                    "修法：一行多枚 → `SettingsChipRow(items, tv = …, phonePortrait = …) { … }`（§4.8）；" +
                    "整行一枚 → 直接从 `Column` 调用，不要再包 `Row`。\n" +
                    "宽度确实由调用方/变量决定、静态判不出来的，在调用上方加 " +
                    "`// $EXEMPT_MARKER: <理由>`。",
            )
        }
    }

    // ─────────────────── 自证：护栏本身必须有效 ───────────────────

    /** ① 负向用例：`PlayerSettingsSection` 时长档**修复前的原文**必须被判违规。 */
    @Test
    fun `时长档原始写法必须被判违规`() {
        val src = """
            if (state.crossfadeEnabled) {
                val durations = listOf(2, 4, 6, 8, 12)
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    durations.forEach { sec ->
                        SettingActionButton(
                            label = "${'$'}{sec}s",
                            description = "",
                            onClick = { actions.onChangeCrossfadeDuration(sec) }
                        )
                    }
                }
            }
        """.trimIndent()
        val scan = scanRowWidth(stripComments(src), src)
        assertTrue(
            "护栏失效：时长档（Row + forEach + 无宽度的 SettingActionButton）没被判违规",
            scan.violations.isNotEmpty(),
        )
    }

    /** ② 压成一行、只有 1 个整行瓦片的 `Row` 同样必须判违规（配对逻辑最容易漏这类）。 */
    @Test
    fun `单行 Row 包一个整行瓦片必须被判违规`() {
        val src = """Row { SettingActionButton(label = "a", description = "", onClick = {}) }"""
        val scan = scanRowWidth(stripComments(src), src)
        assertTrue("护栏失效：单行写法没被判违规", scan.violations.isNotEmpty())
    }

    /** ③ 误报防线 A：调用点自己给了宽度 → 放行。 */
    @Test
    fun `调用点已显式给定宽度必须放行`() {
        val src = """
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingActionButton(
                    label = "a",
                    description = "",
                    onClick = {},
                    modifier = Modifier.width(120.dp)
                )
            }
        """.trimIndent()
        assertTrue(
            "误报：已给 .width() 的调用点被判违规",
            scanRowWidth(stripComments(src), src).violations.isEmpty(),
        )
    }

    /** ③ 误报防线 B：芯片（`chip = true`）本来就按内容 wrap → 放行。 */
    @Test
    fun `芯片必须放行`() {
        val src = """
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsTile(onClick = {}, chip = true) { Text("2s") }
            }
        """.trimIndent()
        assertTrue(
            "误报：chip = true 的瓦片被判违规",
            scanRowWidth(stripComments(src), src).violations.isEmpty(),
        )
    }

    /** ③ 误报防线 C：注释里举例的写法不得被误判（注释剥离生效）。 */
    @Test
    fun `注释里举例的写法不得被误判`() {
        val src = """
            // 反例：Row { SettingActionButton(label = "a", description = "", onClick = {}) }
            val x = 1
        """.trimIndent()
        val scan = scanRowWidth(stripComments(src), src)
        assertTrue("误报：注释里的举例被判违规（注释剥离失效）", scan.violations.isEmpty())
        // ⚠️ 这条 fixture 剥离注释后**不含任何代码**，所以它只证"注释剥离生效"，
        //    不能拿它当空转证据 —— 空转防线在真实扫描的 `rowBlocks > 0` 与常量探针上。
        assertEquals("注释剥离后不应残留可扫描的 Row 块", 0, scan.rowBlocks)
    }

    /** ④ 豁免标记必须真的能放行（否则标记形同虚设）。 */
    @Test
    fun `豁免标记必须生效`() {
        val src = """
            // $EXEMPT_MARKER: 该行始终只有这一枚整行瓦片，宽度由 Column 决定
            Row { SettingActionButton(label = "a", description = "", onClick = {}) }
        """.trimIndent()
        assertTrue(
            "豁免标记失效：加了标记仍被判违规",
            scanRowWidth(stripComments(src), src).violations.isEmpty(),
        )
    }

    /**
     * ⑤ 块注释**可嵌套**时不得把后面的代码当成注释吞掉（KDoc 正文里写内层块注释是真实场景）。
     * ⚠️ 这条用例是给 `stripComments` 的状态机兜底的：换成非贪婪的块注释正则
     * `/\*.*?\*\/` 会在内层结尾处提前收尾，把紧随其后的违规代码误判成"注释"而放过。
     * （上面这行刻意把结尾斜杠写成 `\/` —— 直接写出来会让**本注释自己**提前闭合。）
     */
    @Test
    fun `嵌套块注释不得吞掉其后的违规代码`() {
        val src = """
            /* 外层 /* 内层 */ 仍在外层 */
            Row { SettingActionButton(label = "a", description = "", onClick = {}) }
        """.trimIndent()
        assertTrue(
            "护栏失效：嵌套块注释后面的违规代码被当成注释放过（stripComments 不是状态机）",
            scanRowWidth(stripComments(src), src).violations.isNotEmpty(),
        )
    }

    /**
     * ⑥ 原始字符串 `\"\"\"` 内的行注释符与块注释符**都不是注释**，且其后的代码仍要被扫到。
     *
     * 这条钉的是状态机的一个真实坑：一段 JSON 夹具（本项目备份/端点相关测试里到处都是）
     * 内部满是 `\"` 和 `https://`，若把 `\"\"\"` 当普通字符串处理，反斜杠转义会把引号状态
     * 搅乱，随后整段代码可能被判成"还在字符串里"而逃过扫描。
     * ⚠️ 夹具用拼接构造，不嵌套三引号 —— 否则这条用例本身又会踩上注释围栏。
     */
    @Test
    fun `原始字符串里的注释符号不得影响后续扫描`() {
        val q = "\"\"\""
        val src = "val json = $q\n" +
            "{ \"url\": \"https://example/x\" /* 不是注释 */ }\n" +
            "$q\n" +
            "Row { SettingActionButton(label = \"a\", description = \"\", onClick = {}) }"
        assertTrue(
            "护栏失效：原始字符串之后的违规代码没被扫到（状态机没认 `\"\"\"`）",
            scanRowWidth(stripComments(src), src).violations.isNotEmpty(),
        )
    }

    /**
     * ⑦ 原始字符串**以引号收尾**（`\"\"\"…\"\"\"\"`，结尾 4 个引号）时，状态机必须把整串引号吃掉。
     *
     * 本仓 `VisualizerThemeStringsGateTest` 的 `Regex(\"\"\"…\"\"\"\")` 正是这种写法：
     * 只吃 3 个引号会把剩下那个当成普通字符串的**开头**，此后整段代码都被判成字符串内容，
     * 扫描器安静地漏掉一切（比报错更糟）。
     */
    @Test
    fun `原始字符串以引号收尾时不得吞掉后续代码`() {
        val q = "\"\"\""
        val src = "val r = Regex($q abc$q\")\n" +
            "Row { SettingActionButton(label = \"a\", description = \"\", onClick = {}) }"
        assertTrue(
            "护栏失效：以引号收尾的原始字符串之后，违规代码没被扫到",
            scanRowWidth(stripComments(src), src).violations.isNotEmpty(),
        )
    }

    // ─────────────────────────── 内部实现 ───────────────────────────
    /**
     * 定位 main 源码根目录。AGP 单测的 `user.dir` 是**模块目录**（`…/app`），同时尝试仓库根。
     * 定位失败直接失败而不是跳过 —— 否则这道门被静默禁用。
     * （每个扫描门各自持一份私有顶层 helper，是本项目既有范式，见 [ChipContentWidthScanTest]；
     * Kotlin 的 `private` 顶层声明是**文件作用域**，同包重名不冲突。）
     */
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

    /** G8 作用域：设置页宿主 + 分区 + 导入歌单行；⛔ 不含组件定义文件（见类注释）。 */
    private fun scopedFiles(root: File): List<File> {
        val screens = File(root, "ui/screens")
        val settings = File(screens, "settings")
        val sections = settings.listFiles { f -> f.name.endsWith("Section.kt") }.orEmpty().toList()
        val extra = listOf(
            File(screens, "SettingsScreen.kt"),
            File(settings, "ImportedPlaylistRow.kt"),
        ).filter { it.isFile }
        val all = (sections + extra).distinctBy { it.absolutePath }
        assertTrue("G8 作用域为空：${settings.absolutePath}", all.isNotEmpty())
        return all.sortedBy { it.name }
    }
}

/** 豁免标记：写在调用行或其上方 3 行内，后跟理由 */
private const val EXEMPT_MARKER = "RowWidth-exempt"

/**
 * 活性探针：一段**常量**源码，必须被判违规。
 *
 * 用它而不是用生产代码的脏数据来证明"扫描器活着" —— 生产代码修干净之后真违规数必然归零，
 * 那时只有常量探针还能回答"判据是不是已经失效"（否则这道门会安静地变成假绿）。
 */
private const val LIVENESS_PROBE = """
Row { SettingActionButton(label = "probe", description = "", onClick = {}) }
"""

/**
 * 容器标识：只认 `Row` 这个词。
 * `\b` 两端各管一件事 —— 左边让 FQN 写法 `androidx.compose.foundation.layout.Row(` 也命中（`.` 是非词字符），
 * 右边把 `RowScope` / `SettingsChipRow` 排除掉。后面的 `(` / `{` 由扫描器再判。
 */
private val ROW_CALL = Regex("""\bRow\b""")

/** 默认"整行宽"的设置组件；T11 委托后 `SettingsTile` 只有 `chip = true` 才是芯片 */
private val COMPONENT_CALL = Regex("""\b(SettingActionButton|SettingSwitch|SettingsTile)\(""")

/**
 * 出现任一即说明宽度已被显式给定 → 不算违规。
 * ⚠️ 与 [ChipContentWidthScanTest] 的同类名单**故意不同**：这里**不把 `fillMaxWidth(` 当放行条件** ——
 * `Row` 里第一枚 `fillMaxWidth()` 正是本门的病灶（后续兄弟被挤成 0 宽），
 * 调用方写 `modifier = Modifier.fillMaxWidth()` 不叫"给了宽度"，叫"整行一枚请改回 `Column` 直接调用"。
 */
private val WIDTH_GIVEN = listOf(".width(", "widthIn(", "weight(", ".size(")

private class RowScan(
    val violations: List<Int>,
    val rowBlocks: Int,
    val checkedCalls: Int,
)

/**
 * **纯函数**：在已剥离注释的源码上跑 G8。
 *
 * @param clean 已剥离注释的源码（长度与换行位置与原文一致 → 行号可直接用）
 * @param raw   原始源码（查豁免标记 —— 标记本身写在注释里）
 *
 * 嵌套 `Row` 会让内层组件被内外两层各查一次，`violations` 因此可能重复；
 * 这里**故意不去重**：重复行号只是报错里多一行，而去重会掩盖"到底扫到了几处"。
 */
private fun scanRowWidth(clean: String, raw: String): RowScan {
    val hits = mutableListOf<Int>()
    val rawLines = raw.lines()
    var rows = 0
    var checked = 0

    for (m in ROW_CALL.findAll(clean)) {
        // 找 `Row` 后面的第一个非空白字符：`(` = 带实参的写法，`{` = 只有尾随 lambda 的写法
        var p = m.range.last + 1
        while (p < clean.length && clean[p].isWhitespace()) p++
        val j = when {
            p >= clean.length -> continue
            clean[p] == '(' -> {
                val close = matchDelimiter(clean, p, '(', ')')
                if (close < 0) continue
                var q = close + 1
                while (q < clean.length && clean[q].isWhitespace()) q++
                // `Row(modifier = …)` 没有内容块 → 跳过（不是容器使用错误，只是本门管不着）
                if (q >= clean.length || clean[q] != '{') continue
                q
            }
            clean[p] == '{' -> p
            else -> continue                       // `Row::class` / 变量名等同形词，忽略
        }
        val bodyEnd = matchDelimiter(clean, j, '{', '}')
        if (bodyEnd < 0) continue
        rows++

        val body = clean.substring(j + 1, bodyEnd)
        for (c in COMPONENT_CALL.findAll(body)) {
            val aOpen = clean.indexOf('(', j + 1 + c.range.first)
            if (aOpen < 0) continue
            val aClose = matchDelimiter(clean, aOpen, '(', ')')
            if (aClose < 0) continue
            val args = clean.substring(aOpen + 1, aClose)
            if (args.contains("chip = true")) continue          // 芯片按内容 wrap，本就不该有宽度
            if (WIDTH_GIVEN.any { args.contains(it) }) continue  // 调用方已给宽度
            checked++
            val line = clean.substring(0, aOpen).count { it == '\n' } + 1
            val exempt = (maxOf(0, line - 4) until minOf(rawLines.size, line))
                .any { rawLines[it].contains(EXEMPT_MARKER) }
            if (!exempt) hits += line
        }
    }
    return RowScan(hits, rows, checked)
}

/** 返回与 `text[start]`（值为 [open]）配对的 [close] 下标；找不到返回 -1。字符串/字符字面量内的括号不计。 */
private fun matchDelimiter(text: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var inStr = false
    var inChar = false
    var inRaw = false
    var i = start
    while (i < text.length) {
        if (inRaw) {
            // 原始字符串（`"""`）里反斜杠**不转义**，唯一的出口是再次遇到 `"""`；
            // 里面的 `)` `}` 都是普通字符，参与配对会把括号数算歪。
            // ⚠️ 收尾要按「连续引号串」整段吃：Kotlin 允许 `"""…""""`（内容以 `"` 结尾，
            //    共 4 个引号），本仓的正则就有这种写法。只吃 3 个会把剩下那个当成普通
            //    字符串开头，后面整段代码被判成字符串内容而逃过扫描。
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

/** `text[p]` 起是不是原始字符串围栏 `"""`。 */
private fun isRawFence(text: String, p: Int): Boolean = quoteRun(text, p) >= 3

/** `text[p]` 起连续双引号的个数（不是引号则为 0）。用于把 `"""…""""` 的收尾整段吃掉。 */
private fun quoteRun(text: String, p: Int): Int {
    var k = 0
    while (p + k < text.length && text[p + k] == '"') k++
    return k
}

/**
 * 把注释内容替换成空格（**保留长度与换行位置** → 行号不变）。字符串内的 `//` 不是注释。
 *
 * ⚠️ 必须是**状态机**，不能是 `/\*.*?\*\/` 这种非贪婪的块注释正则：
 * ① Kotlin 的块注释**可嵌套**（KDoc 正文里写内层块注释是真实场景），非贪婪正则会提前收尾，
 *    把紧随其后的代码误当注释吃掉（`嵌套块注释不得吞掉其后的违规代码` 就是钉这条）；
 * ② 整段删除而非替换成等长空格，会让跨行注释两侧的代码粘到同一行，行号判据全乱。
 *
 * ⚠️ 必须认**原始字符串** `"""`：它内部的反斜杠不转义、行注释符与块注释符都不是注释。
 * 不认的话，一段 JSON 夹具（里面满是 `"` 与 `https://`）会把 `inStr` 状态搅乱，
 * 之后的 KDoc 可能被整段当成"字符串内容"，从而把注释里的东西当代码、把代码当注释。
 *
 * ⚠️ 上面两处把结尾斜杠写成 `\/` 是**必须的** —— 直接写出来会让本注释自己提前闭合
 * （这个文件的第一版就踩了，靠 `logs_temp/kt_standalone.py` 独立编译当场抓到）。
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
                // ⚠️ 收尾按「连续引号串」整段吃（见 [quoteRun]）：`"""…""""` 是合法写法，
                //    只吃 3 个会留下一个引号当普通字符串开头，后续代码全被判成字符串内容。
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
