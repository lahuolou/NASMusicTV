package com.nasmusic.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「设置分区的每个可操作行必须待在 `SettingsGroupCard` 里」（方案 §5.7 G1）。
 *
 * 与 ChipContentWidthScanTest / SmallTouchTargetScanTest 同范式：项目无 compose-ui-test，
 * 「子项没有分组边界」这类**纯视觉缺陷**只能靠源码结构门禁锁住。
 *
 * 判定：`SettingSwitch(` / `SettingActionButton(` / `SettingsInfoRow(` 调用点必须落在
 * 同文件某个 `SettingsGroupCard(` 的大括号配对区内；豁免标记 `Group-exempt: <理由>`；
 * `//` 与 `/* … */` 注释先剥离。
 *
 * ⚠️ MIGRATED 是**增量白名单的反向**：只有已完成卡片化的文件才被扫描，逐任务扩大，
 * 全部迁完（T15）后改为扫描 `ui/screens/settings/` 全部 `*Section.kt`。
 */
class SettingsGroupCoverageTest {

    /**
     * G1 的作用域（T15 起 = **全量**）：`ui/screens/settings/` 下全部 `*Section.kt`。
     * T15 之前是增量名单 —— 只扫已卡片化的文件，防止门禁红在旧任务身上；
     * 全部迁完后改为按目录枚举，新增分区文件**自动**进门，不再需要记得登记。
     *
     * ⚠️ 目录里还有一个同名后缀的**非 UI** 文件：`SettingsSection.kt` 是分区**枚举**定义，
     * 不是分区 UI，天然没有卡片 —— 显式排除（见 [nonSectionFiles] 的防锈断言）。
     */
    private fun migratedFiles(root: File): List<File> {
        val all = root.listFiles { f -> f.name.endsWith("Section.kt") }.orEmpty().sortedBy { it.name }
        val excluded = all.filter { it.name in nonSectionFiles }
        // 排除名单防锈：被排除的文件一旦开始出现卡片或设置行，说明它已经变成 UI 分区，
        // 必须从名单里移除 —— 否则本门会**静默漏扫**它。
        val leaked = excluded.mapNotNull { f ->
            val t = stripComments(f.readText())
            if (t.contains("SettingsGroupCard(") || rowCalls.any { t.contains(it) }) f.name else null
        }
        assertTrue(
            "nonSectionFiles 排除名单已失效，以下文件出现了卡片/设置行，请从名单移除：$leaked",
            leaked.isEmpty(),
        )
        return all.filter { it.name !in nonSectionFiles }
    }

    private val rowCalls = listOf("SettingSwitch(", "SettingActionButton(", "SettingsInfoRow(")

    /** 以 `Section.kt` 结尾但**不是**分区 UI 的文件（见 [migratedFiles] 的防锈断言） */
    private val nonSectionFiles = setOf("SettingsSection.kt")

    /**
     * G6-C 的触发名单：出现任一即视为「本卡有芯片组 / 调整行，必须有说明」。
     *
     * 后两项是**渲染芯片与调整行的私有 helper**，不是芯片本身 —— 源码门禁只能看调用点，
     * 不加它们，`PHOTO_WALL ③`（43 个转场芯片）在门禁眼里就是"没有芯片"。
     */
    private val chipRowCalls = listOf(
        "AdjustButton(",
        "SettingsChip(",
        "DurationRow(",
        "PhotoTransitionChips(",
    )

    @Test
    fun `已迁移分区的每个设置行都必须位于 SettingsGroupCard 内`() {
        val root = settingsRoot()
        val files = migratedFiles(root)
        assertTrue("G1 作用域为空：${root.absolutePath}", files.isNotEmpty())
        var scanned = 0
        val violations = mutableListOf<String>()
        for (f in files) {
            val name = f.name
            val text = stripComments(f.readText())
            val cardRanges = groupCardSpans(text)
            assertTrue("$name 已列入迁移名单却找不到 SettingsGroupCard 调用", cardRanges.isNotEmpty())
            text.lines().forEachIndexed { idx, line ->
                val call = rowCalls.firstOrNull { line.contains(it) } ?: return@forEachIndexed
                if (line.contains("Group-exempt:")) return@forEachIndexed
                if (line.contains("internal fun ") || line.contains("private fun ")) return@forEachIndexed // 组件自身定义
                val offset = text.lineOffset(idx)
                scanned++
                if (cardRanges.none { offset in it }) violations += "$name:${idx + 1} $call"
            }
        }
        assertTrue("扫描到 0 个调用点 —— 门禁在空转", scanned > 0)
        if (violations.isNotEmpty()) fail("以下设置行不在任何分组卡片内：\n" + violations.joinToString("\n"))
    }

    // ─────────── G2：SubSectionTitle 必须已移除（T15） ───────────

    @Test
    fun `SubSectionTitle 必须已从源码中彻底移除`() {
        val root = settingsRoot()
        val hits = root.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
            .flatMap { f ->
                stripComments(f.readText()).lines().withIndex()
                    .filter { it.value.contains("SubSectionTitle(") }
                    .map { "${f.name}:${it.index + 1}" }
            }
            .toList()
        assertTrue(
            "仍有 SubSectionTitle 调用：$hits（分组标题必须用 SettingsGroupCard 卡片头）",
            hits.isEmpty(),
        )
    }

    /** 负向自证：把 SubSectionTitle 塞回样例文本必须被检出。 */
    @Test
    fun `样例里的 SubSectionTitle 必须被检出`() {
        val text = "Column {\n    SubSectionTitle(\"x\")\n}"
        assertTrue(
            "护栏空转：SubSectionTitle 检测失效",
            stripComments(text).lines().any { it.contains("SubSectionTitle(") },
        )
    }

    /** 负向自证：把一行挪到卡片外，必须被判违规。 */
    @Test
    fun `卡片外的设置行必须判负`() {
        val text = """
            fun a() {
                SettingsGroupCard(title = "x") {
                    SettingSwitch(label = "1")
                }
                SettingSwitch(label = "2")
            }
        """.trimIndent()
        val spans = groupCardSpans(text)
        val outside = text.lineOffset(4)
        assertTrue("负向用例失效：卡片外的行仍被算作卡片内", spans.none { outside in it })
    }

    /** 注释里举例的写法不得被误判（剥离注释生效）。 */
    @Test
    fun `注释中的设置行调用不算违规`() {
        val text = "// SettingSwitch(label = \"x\")\nval y = 1\n"
        assertTrue(stripComments(text).contains("SettingSwitch") == false)
    }

    // ───────────────────────── G7 风格统一 ─────────────────────────

    /** 文件级豁免必须点名，且名单里的文件必须真的存在（改名即失效，见 §4.7-⑥）。 */
    private val styleOutOfFileNames = setOf(
        "SettingsTokens.kt",
        "PlaylistImportUploadDialog.kt",
        "SettingsSectionList.kt",
    )

    /** 参数名：这四行的取值只准引用 SettingsTokens，不准出现数字字面量。 */
    private val styleParamNames = listOf("shape", "focusedScale", "pressedScale", "animationDurationMs")

    /** G7-A：参数位含数字字面量 → 违规（整行标了 `Style-exempt:` 才放行）。注释先剥离。 */
    private fun literalStyleViolations(text: String): List<String> =
        stripComments(text).lines().withIndex().mapNotNull { (i, raw) ->
            val line = raw.trim()
            val param = styleParamNames.firstOrNull { p ->
                line.startsWith("$p =") || line.contains(" $p =") || line.contains(",$p =")
            } ?: return@mapNotNull null
            if (line.contains("Style-exempt") || !line.any { it.isDigit() }) return@mapNotNull null
            "${i + 1}:$param"
        }

    /** G7-B：`FocusableSurface(` 只准出现在 SettingsTile 那一处（FQN 写法同样命中）。 */
    private fun tileCallCount(text: String): Int =
        Regex("""\bFocusableSurface\(""").findAll(stripComments(text)).count()

    /**
     * 「实底 Primary」= 该行 `containerColor` 里的 `NasMusicColors.Primary` 后面**不是** `.copy(`。
     *
     * ⚠️ 判据必须是这个正则，不能写成 `line.contains("NasMusicColors.Primary,")`：
     * 真实病灶有两种写法 —— 裸的 `containerColor = NasMusicColors.Primary,`，以及
     * `containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,`
     * （原 `OptionChip` / `PlayModeSelector` 就是后者）。只匹配带逗号的那种会整类漏掉，
     * 而方案 §4.7-⑥ 自带的负向样本恰好就是这一行 —— 漏了它负向自证也过不去。
     */
    private val solidPrimary = Regex("""NasMusicColors\.Primary\b(?!\.copy)""")

    /** G7-C：「已选中」只准 `SettingsTile(selected = true)`，不准实底 Primary 或手调 alpha。 */
    private fun selectedStyleViolations(text: String): List<String> =
        stripComments(text).lines().withIndex().mapNotNull { (i, raw) ->
            val line = raw.trim()
            when {
                line.contains("Style-exempt") -> null
                line.contains("containerColor") && solidPrimary.containsMatchIn(line) -> "${i + 1}"
                line.contains("containerColor") && line.contains("alpha = 0.18") -> "${i + 1}"
                else -> null
            }
        }

    @Test
    fun `设置区瓦片取值必须全部走 SettingsTokens`() {
        val root = settingsRoot()
        val files = root.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
        val scoped = files.filter { it.name !in styleOutOfFileNames }
        assertTrue("G7 扫描到 0 个受检文件 —— 源目录定位失败或豁免名单吞掉了全部文件", scoped.isNotEmpty())
        for (name in styleOutOfFileNames) {
            assertTrue("文件级豁免 $name 已不存在（改名后该文件会静默脱离检查）",
                files.any { it.name == name })
        }
        val hits = scoped.flatMap { f -> literalStyleViolations(f.readText()).map { "${f.name}:$it" } }
        assertTrue("参数位仍有字面量（必须改成 SettingsTokens.*）：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun `设置区分区不得手写 FocusableSurface 参数块`() {
        val root = settingsRoot()
        val scoped = root.listFiles { f ->
            f.name.endsWith("Section.kt") || f.name == "ImportedPlaylistRow.kt"
        }.orEmpty().toList()
        assertTrue("G7-B 扫描到 0 个分区文件", scoped.isNotEmpty())
        val hits = scoped.mapNotNull { f ->
            tileCallCount(f.readText()).takeIf { it > 0 }?.let { "${f.name}($it 处)" }
        }
        val shared = File(root, "SettingsComponents.kt")
        val sharedCount = if (shared.exists()) tileCallCount(shared.readText()) else 0
        assertTrue(
            "分区里还有裸瓦片：${hits}；SettingsComponents 内 FocusableSurface( 出现 $sharedCount 次（只准 SettingsTile 那 1 处）",
            hits.isEmpty() && sharedCount <= 1,
        )
    }

    @Test
    fun `已选中态只准一种表达`() {
        val root = settingsRoot()
        val scoped = root.walkTopDown().filter { it.isFile && (it.name.endsWith("Section.kt") || it.name == "ImportedPlaylistRow.kt") }.toList()
        val hits = scoped.flatMap { f -> selectedStyleViolations(f.readText()).map { "${f.name}:$it" } }
        assertTrue("出现实底 Primary / 手调 0.18 选中态，必须改用 SettingsTile(selected = true)：$hits", hits.isEmpty())
    }

    /** 负向自证：三条判据各自必须能咬人（缺任一，"全绿"可能只是空转）。 */
    @Test
    fun `G7 三条负向自证`() {
        val a = "FocusableSurface(\n    shape = RoundedCornerShape(12.dp),\n    focusedScale = if (enabled) 1.03f else 1f,\n    animationDurationMs = 250,\n)"
        assertTrue("G7-A 空转：字面量圆角/缩放/时长没被检出",
            literalStyleViolations(a).size >= 3)
        assertTrue("G7-A 误伤：令牌写法被判负",
            literalStyleViolations("    focusedScale = if (chip) SettingsTokens.focusScaleChip else SettingsTokens.focusScaleRow,").isEmpty())
        // 方案原文这里写 `== 2`，但样本里 `FocusableSurface(` 只出现 1 次 —— 按实际计数写 1。
        assertTrue("G7-B 空转：裸瓦片没被检出", tileCallCount(a) == 1)
        assertTrue("G7-B 空转：FQN 写法漏检", tileCallCount("    com.nasmusic.tv.ui.components.FocusableSurface(") == 1)
        // 第 1 行是 `if (selected) …Primary else …Surface` 形态（原 OptionChip 的写法），
        // 第 2 行是手调 0.18 —— 两种都必须被咬，缺一即 G7-C 漏一类。
        val c = "    containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,\n" +
            "    containerColor = NasMusicColors.Primary.copy(alpha = 0.18f),"
        assertTrue("G7-C 空转：实底/手调选中态没被检出", selectedStyleViolations(c).size == 2)
        assertTrue("G7-C 误伤：SettingsTile 委托写法被判负",
            selectedStyleViolations("    SettingsTile(onClick = {}, selected = true) { }").isEmpty())
    }

    // ───────────────────────── G7-D 开关文案解寄生 ─────────────────────────

    /**
     * G7-D 的判据：一行里出现 `settings_wifi_on` / `settings_wifi_off` 即违规。
     *
     * 抽成函数是为了负向自证能喂**同一份谓词** —— 否则负向用例证明的只是"另写了一段正则"。
     */
    private fun wifiStringHits(text: String): List<Int> =
        stripComments(text).lines().withIndex()
            .filter { it.value.contains("settings_wifi_on") || it.value.contains("settings_wifi_off") }
            .map { it.index + 1 }
            .toList()

    /** main 源集根目录（G7-D 扫全树，不是只扫 `ui/screens/settings/`）。 */
    private fun mainKotlinRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到 main 源集目录（user.dir=${File("").absolutePath}）", found != null)
        return found!!
    }

    /** G7-D：开关态不得再寄生 WiFi 字符串（实测全树只有 `SettingSwitch` 一个消费方）。 */
    @Test
    fun `设置区不得复用 WiFi 开关文案`() {
        val root = mainKotlinRoot()
        var scanned = 0
        val hits = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                scanned++
                val rel = f.relativeTo(root).path.replace('\\', '/')
                wifiStringHits(f.readText()).map { "$rel:$it" }
            }
            .toList()
        assertTrue("G7-D 扫到 0 个 .kt 文件 —— 源目录定位失败或谓词失配（空转）", scanned > 0)
        assertTrue("仍引用 settings_wifi_on/off：$hits（开关态必须用 settings_on/off）", hits.isEmpty())
    }

    /**
     * G7-D 的另一半：旧 key 必须从两份 `strings.xml` 里消失。
     *
     * 只删代码引用不够 —— 留着死 key，下一个人想加开关态就会把它捡回来，
     * 于是「WiFi 文案寄生」重新长出来，而 G7-D 只扫 `.kt`，看不见资源文件。
     * 同一条用例顺带钉住四个新 key **中英齐备**（缺一侧是运行期 `getString` 抛异常，不是编译错误）。
     */
    @Test
    fun `WiFi 开关字符串必须从两份 strings 删除`() {
        val resCandidates = listOf(File("src/main/res"), File("app/src/main/res"), File("../app/src/main/res"))
        val res = resCandidates.firstOrNull { it.isDirectory }
        assertTrue("找不到 res 目录（user.dir=${File("").absolutePath}）", res != null)
        for (locale in listOf("values", "values-en")) {
            val f = File(res!!, "$locale/strings.xml")
            assertTrue("$locale/strings.xml 不存在", f.isFile)
            val text = f.readText()
            for (gone in listOf("settings_wifi_on", "settings_wifi_off")) {
                assertTrue("$locale/strings.xml 仍留着 $gone（死 key 会被下一个人捡回去）",
                    !text.contains("name=\"$gone\""))
            }
            for (added in listOf("settings_on", "settings_off", "settings_state_on", "settings_state_off")) {
                assertTrue("$locale/strings.xml 缺 $added —— 中英必须同批加，否则运行期 getString 抛 IllegalArgumentException",
                    text.contains("name=\"$added\""))
            }
        }
    }

    /** 负向自证：同一份谓词必须能咬人，且不得误伤新 key。 */
    @Test
    fun `G7-D 负向自证`() {
        assertTrue("G7-D 空转：样例里的 settings_wifi_on 没被检出",
            wifiStringHits("text = stringResource(R.string.settings_wifi_on)").isNotEmpty())
        assertTrue("G7-D 空转：样例里的 settings_wifi_off 没被检出",
            wifiStringHits("text = stringResource(R.string.settings_wifi_off)").isNotEmpty())
        assertTrue("G7-D 误伤：新 key settings_on / settings_state_on 被判负",
            wifiStringHits(
                "text = stringResource(R.string.settings_on)\n" +
                    "cd = stringResource(R.string.settings_state_on)",
            ).isEmpty())
        assertTrue("G7-D 误伤：注释里举例的旧 key 被判负",
            wifiStringHits("// 旧写法：R.string.settings_wifi_on").isEmpty())
    }

    // ─────────── G6：说明覆盖（§4.5） ───────────

    /**
     * 扫一个分区源码，返回缺 `description` 的调用点 `文件:行 调用名`。
     *
     * G6-A（断言 A + B 合并：`PlayModeSelector` 加了必填 `description` 后仍要防人改回可选）
     * 与负向自证**共用这一条路径** —— 自证必须走真实判据，否则它只证明了自己。
     *
     * ⚠️ 判据是「**实参区**含非空 `description =`」，不是单行 `contains`：
     * 说明文案是 `stringResource(R.string.x)`，长参数会被 ktlint/IDE 折到下一行；
     * 单行判据会在折行时假红，而 `contains("description")` 又会被 `description = ""` 骗过。
     */
    private fun descViolations(raw: String, label: String): List<String> {
        val text = stripComments(raw)
        val rawLines = raw.lines()
        val out = mutableListOf<String>()
        text.lines().forEachIndexed { idx, line ->
            val call = listOf("SettingSwitch(", "SettingActionButton(", "PlayModeSelector(")
                .firstOrNull { line.contains(it) } ?: return@forEachIndexed
            if (line.contains("internal fun ") || line.contains("private fun ")) return@forEachIndexed
            if (exemptAt(rawLines, idx)) return@forEachIndexed
            val open = text.lineOffset(idx) + line.indexOf(call) + call.length - 1
            val args = text.substring(open, argEnd(text, open))
            val empty = args.contains("description = \"\"")
            if (!args.contains("description =") || empty) out += "$label:${idx + 1} $call"
        }
        return out
    }

    private fun descCallCount(raw: String): Int {
        val text = stripComments(raw)
        val rawLines = raw.lines()
        return text.lines().withIndex().count { (idx, line) ->
            listOf("SettingSwitch(", "SettingActionButton(", "PlayModeSelector(").any { line.contains(it) } &&
                !line.contains("internal fun ") && !line.contains("private fun ") &&
                !exemptAt(rawLines, idx)
        }
    }

    /**
     * 该行（或向前 3 行内）有没有豁免标记。
     *
     * ⚠️ 两个都必须：① 标记写在**注释**里，而 `stripComments` 会把注释换成空格 ——
     * 在剥离后的文本上找 `Desc-exempt:` 永远找不到（本门第一版就踩了这个坑）；
     * ② 允许写在上方 3 行内，否则一段多行调用（参数一行一个）只能把标记塞进参数列表里。
     */
    private fun exemptAt(rawLines: List<String>, idx: Int): Boolean =
        (maxOf(0, idx - 3)..idx).any { rawLines.getOrElse(it) { "" }.contains("Desc-exempt:") }

    @Test
    fun `每个开关、按钮与播放模式选择器都必须带非空 description`() {
        val root = settingsRoot()
        var scanned = 0
        val missing = mutableListOf<String>()
        for (f in root.listFiles { file -> file.name.endsWith("Section.kt") }.orEmpty()) {
            val raw = f.readText()
            scanned += descCallCount(raw)
            missing += descViolations(raw, f.name)
        }
        assertTrue("扫描到 0 个调用点 —— G6-A 在空转", scanned > 0)
        if (missing.isNotEmpty()) {
            fail(
                "以下设置行缺 description（§4.5：每个可操作项都要一句说明，≤26 汉字）：\n" +
                    missing.joinToString("\n") { "  • $it" } +
                    "\n\n确实不该有说明的行，在调用上方加 `// Desc-exempt: <理由>`。"
            )
        }
    }

    /**
     * 芯片组 / 调整行所在的卡必须有一句说明 —— 满足任一条即可：
     * ① 卡内有 `SettingsHint(`（芯片组与调整行没有 `description` 形参，这是它们的说明位）；
     * ② 卡头自带非空 `description`（说明由卡片头承担，再加一条 `SettingsHint` 就是重复文案）。
     *
     * ⚠️ ② 是**对方案原文的修正**：方案 Step 1 的片段只认 `SettingsHint(`，但 §4.5 的表里
     * `GENERAL ①/③` 标的是 ✅「已有卡片 description」—— 两处自相矛盾。按「说明写给人看、
     * 不重复」的意图取并集。
     *
     * ⚠️ 触发名单除方案给的 `AdjustButton(` / `SettingsChip(` 外，还加了两个**渲染芯片/调整行
     * 的私有 helper**：`PhotoTransitionChips(`（43 个转场芯片）与 `DurationRow(`（− / + 秒数行）。
     * 不加这两个，`PHOTO_WALL ③` 那张卡的芯片在门禁眼里就是"不存在"—— 抓的是调用点而不是
     * 渲染结果，这是源码门禁的固有盲区，能补一个是一个。
     */
    @Test
    fun `芯片组与调整行所在卡片必须有 SettingsHint`() {
        val root = settingsRoot()
        var checkedCards = 0
        val missing = mutableListOf<String>()
        for (f in root.listFiles { file -> file.name.endsWith("Section.kt") }.orEmpty()) {
            val raw = f.readText()
            val rawLines = raw.lines()
            val text = stripComments(raw)
            for ((i, card) in groupCards(text).withIndex()) {
                val (header, span) = card
                // 豁免可写在卡头那几行（上方 3 行内）或卡内任意位置
                val cardLine = raw.substring(0, span.first).count { it == '\n' }
                if (exemptAt(rawLines, cardLine) || raw.substring(span).contains("Desc-exempt:")) continue
                val body = text.substring(span)
                if (chipRowCalls.none { body.contains(it) }) continue
                checkedCards++
                val hasCardDesc = header.contains("description =") && !header.contains("description = \"\"")
                if (!body.contains("SettingsHint(") && !hasCardDesc) {
                    missing += "${f.name} 第 ${i + 1} 张卡（有芯片/调整行，既无 SettingsHint 也无卡片 description）"
                }
            }
        }
        assertTrue("扫描到 0 张含芯片的卡片 —— G6-C 在空转", checkedCards > 0)
        if (missing.isNotEmpty()) {
            fail("芯片组/调整行缺说明（§4.5：芯片组也要一句说明）：\n" + missing.joinToString("\n") { "  • $it" })
        }
    }

    /** 负向自证：缺 description 的行必须被同一条路径判负，有 description 的行不能误伤。 */
    @Test
    fun `缺 description 的开关必须判负`() {
        val text = """
            SettingsGroupCard(title = "x") {
                SettingSwitch(label = "1", description = "ok")
                SettingSwitch(label = "2")
                SettingActionButton(label = "3", description = "")
            }
        """.trimIndent()
        val v = descViolations(text, "sample")
        assertTrue(
            "负向用例失效：缺 description / 空 description 的行没被判负（实际命中 $v）",
            v.any { it.contains(":3") } && v.any { it.contains(":4") }
        )
        assertTrue("误伤：带非空 description 的行被判负", v.none { it.contains(":2") })
    }

    /** 负向自证：`Desc-exempt` 必须真的能放行（否则标记形同虚设）。 */
    @Test
    fun `豁免标记必须能放行缺 description 的行`() {
        val text = """
            SettingsGroupCard(title = "x") {
                // Desc-exempt: 这一行是导出过程的取消按钮，不需要说明
                SettingActionButton(label = "3", description = "")
            }
        """.trimIndent()
        assertTrue(
            "豁免标记失效：加了 Desc-exempt 仍被判负",
            descViolations(text, "sample").isEmpty(),
        )
    }

    /** 负向自证：卡片既无 SettingsHint 也无卡片 description 时必须判负（判据与上面那条断言同源）。 */
    @Test
    fun `含芯片但无 SettingsHint 的卡片必须判负`() {
        val text = """
            SettingsGroupCard(title = "x") {
                AdjustButton("-", onClick = {})
            }
        """.trimIndent()
        val cards = groupCards(stripComments(text))
        assertEquals("样例里应恰好解析出 1 张卡", 1, cards.size)
        val (header, span) = cards[0]
        val body = stripComments(text).substring(span)
        val hasCardDesc = header.contains("description =") && !header.contains("description = \"\"")
        assertTrue(
            "护栏空转：既无 SettingsHint 也无卡片 description 的卡没被判负",
            chipRowCalls.any { body.contains(it) } && !body.contains("SettingsHint(") && !hasCardDesc,
        )
    }

    /** 从 `(` 的下标开始做括号配对，返回匹配 `)` **之后**的下标。字符串/字符字面量内的括号跳过。 */
    private fun argEnd(text: String, openParen: Int): Int {
        var depth = 0; var i = openParen; var inString = false; var inChar = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> { if (c == '\\') i++ else if (c == '"') inString = false }
                inChar -> { if (c == '\\') i++ else if (c == '\'') inChar = false }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' -> depth++
                c == ')' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return text.length
    }

    // ─────────── 辅助 ───────────

    /** 返回每个 `SettingsGroupCard(` 尾随 lambda 体（大括号配对）的字符区间。 */
    private fun groupCardSpans(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (true) {
            val at = text.indexOf("SettingsGroupCard(", startIndex = i)
            if (at < 0) break
            val brace = text.indexOf('{', at)
            if (brace < 0) break
            var depth = 0
            var end = -1
            var j = brace
            while (j < text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (end > brace) out += (brace + 1)..(end - 1)
            i = at + 1
        }
        return out
    }

    /**
     * 每张卡：(**实参区**文本, 尾随 lambda 体区间)。
     *
     * 与 [groupCardSpans] 的区别：这里把卡头实参一起返回，G6-C 才能判断"说明是否已由
     * 卡片 `description` 承担"。两者由同一次遍历产出 ⇒ 下标天然对齐，不会出现
     * "第 3 张卡的 body 配到第 2 张卡的 header"。
     */
    private fun groupCards(text: String): List<Pair<String, IntRange>> {
        val out = mutableListOf<Pair<String, IntRange>>()
        var i = 0
        while (true) {
            val at = text.indexOf("SettingsGroupCard(", startIndex = i)
            if (at < 0) break
            val open = at + "SettingsGroupCard".length
            val argsEnd = argEnd(text, open)               // 匹配 `)` 之后
            val brace = text.indexOf('{', argsEnd)
            if (brace < 0) break
            var depth = 0
            var end = -1
            var j = brace
            while (j < text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (end > brace) out += text.substring(open, argsEnd) to (brace + 1)..(end - 1)
            i = at + 1
        }
        return out
    }

    /**
     * 剥注释，**保留长度与换行位置**（行号因此与原文一致 —— G7 的失败信息要能直接跳行）。
     *
     * 三条都必须处理，缺一条 G7 就会假绿/假红：
     * - **块注释换成等长空格**而不是直接删除：直接删会把跨行 `/* … */` 两侧的代码粘到一行，
     *   而 G7-A 是逐行判「参数位有没有数字」，粘连出来的行会同时假绿（原来那行没了）与假红。
     * - **Kotlin 的块注释可嵌套**（`/* a /* b */ c */` 是一整个注释，KDoc 正文里写
     *   `/* … */` 也不会提前闭合）⇒ 必须按深度计，不能用 `/\*.*?\*\/` 非贪婪一刀切。
     *   ⚠️ 上一行把结尾斜杠写成 `\*\/` 是**必须的**：直接写出来会让本注释提前闭合，
     *   后面的 `* - **字符串…` 就变成"游离在类外的代码"，报错落在与真因无关的行上。
     * - **字符串/字符字面量里的 `//` 不是注释**（本仓有 `it.substringBefore("//")` 这种写法），
     *   按行 `substringBefore("//")` 会把后半行吃掉。
     */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var depth = 0
        var inLine = false
        var inStr = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            val n = if (i + 1 < text.length) text[i + 1] else '\u0000'
            when {
                inLine -> {
                    sb.append(if (c == '\n') '\n' else ' ')
                    if (c == '\n') inLine = false
                    i++
                }
                depth > 0 -> when {
                    c == '/' && n == '*' -> { depth++; sb.append("  "); i += 2 }
                    c == '*' && n == '/' -> { depth--; sb.append("  "); i += 2 }
                    else -> { sb.append(if (c == '\n') '\n' else ' '); i++ }
                }
                inStr -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2 }
                    else { if (c == '"') inStr = false; i++ }
                }
                inChar -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < text.length) { sb.append(text[i + 1]); i += 2 }
                    else { if (c == '\'') inChar = false; i++ }
                }
                c == '/' && n == '/' -> { inLine = true; sb.append("  "); i += 2 }
                c == '/' && n == '*' -> { depth = 1; sb.append("  "); i += 2 }
                c == '"' -> { inStr = true; sb.append(c); i++ }
                c == '\'' -> { inChar = true; sb.append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun String.lineOffset(lineIndex: Int): Int {
        var o = 0
        for (k in 0 until lineIndex) o += lines()[k].length + 1
        return o
    }

    private fun settingsRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui/screens/settings"),
            File("app/src/main/java/com/nasmusic/tv/ui/screens/settings"),
            File("../app/src/main/java/com/nasmusic/tv/ui/screens/settings"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到设置源码目录（user.dir=${File("").absolutePath}）", found != null)
        return found!!
    }
}
