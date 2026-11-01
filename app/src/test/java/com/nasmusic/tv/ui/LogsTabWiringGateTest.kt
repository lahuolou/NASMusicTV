package com.nasmusic.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「设置页 · 日志 tab」的接线门禁（运行期错误持久化，可反复查看）。
 *
 * 为什么需要源码门禁：本项目无 compose-ui-test，以下四类断链**编译都能过**，但功能静默失效：
 *  1. 枚举加了 `LOGS` 却没在 `SettingsScreen` 渲染 / 没登记焦点请求器（TV 上按「下」进不去）；
 *  2. `LogStore` 写好了，但 `AppLog.w/e` 没接上 ⇒ 日志文件永远是空的；
 *  3. 崩溃处理器没装 ⇒ 最需要留痕的场景（闪退）一条都留不下；
 *  4. `LogStore` 反向调用 `AppLog` ⇒ 无限递归（写入 → 打印 → 再写入）。
 *
 * 每条谓词都配负向自证（同一份谓词喂反例必须判负），防「门禁空转」。
 */
class LogsTabWiringGateTest {

    // ─────────────────── 1. 分区枚举 + 双语标题 ───────────────────

    @Test
    fun `日志分区必须登记到枚举`() {
        val enumSrc = mainSource("ui/screens/settings/SettingsSection.kt")
        assertTrue(
            "SettingsSection 里找不到 `LOGS(R.string.…, Icons…)` —— 枚举写法变了或没登记",
            Regex("""^\s{4}LOGS\(R\.string\.\w+,\s*Icons""", RegexOption.MULTILINE).containsMatchIn(enumSrc),
        )
    }

    @Test
    fun `日志分区标题必须中英齐备`() {
        val enumSrc = mainSource("ui/screens/settings/SettingsSection.kt")
        val m = Regex("""LOGS\(R\.string\.(\w+),""").find(enumSrc)
        assertTrue("没抽到 LOGS 的 titleRes", m != null)
        val key = m!!.groupValues[1]
        for (dir in listOf("values", "values-en")) {
            val xml = resXml(dir)
            assertTrue("$dir/strings.xml 缺 `$key`（编译能过、渲染会崩）", xml.contains("""name="$key""""))
        }
    }

    // ─────────────────── 2. SettingsScreen 渲染 + 焦点登记 ───────────────────

    @Test
    fun `SettingsScreen 必须渲染日志分区并登记焦点请求器`() {
        val screen = stripComments(mainSource("ui/screens/SettingsScreen.kt"))
        val hits = Regex("""SettingsSection\.LOGS\b""").findAll(screen).count()
        assertTrue(
            "`SettingsSection.LOGS` 只出现 $hits 次（需 when 分支 + navFocusRequesters 各一处，G3 口径 ≥2）",
            hits >= 2,
        )
        assertTrue(
            "SettingsScreen 没有调用 `LogsSettingsSection(` —— 分支可能渲染了空 item",
            screen.contains("LogsSettingsSection("),
        )
        assertTrue(
            "SettingsScreen 没有把过滤级别传进 LogsSettingsState（minLevel = logsMinLevel）",
            screen.contains("minLevel = logsMinLevel"),
        )
        assertTrue(
            "SettingsScreen 没有把级别切换动作接到 LogsSettingsActions",
            screen.contains("onSelectMinLevel = onSelectLogsMinLevel"),
        )
    }

    @Test
    fun `进入日志分区必须刷新一次`() {
        val screen = stripComments(mainSource("ui/screens/SettingsScreen.kt"))
        // 与 DATA 分区同款：LaunchedEffect(displaySection) 里按分区触发刷新
        assertTrue(
            "没有在进入 LOGS 分区时调用刷新（日志页会永远显示进页前的旧快照）",
            Regex("""displaySection == SettingsSection\.LOGS[\s\S]{0,160}onRefreshLogs""").containsMatchIn(screen),
        )
    }

    // ─────────────────── 3. SettingsBranch 接线 ───────────────────

    @Test
    fun `SettingsBranch 必须接线日志状态与动作`() {
        val branch = stripComments(mainSource("ui/components/branches/SettingsBranch.kt"))
        for (needle in listOf(
            "logs = logs",
            "onRefreshLogs =",
            "onClearLogs =",
            "logsMinLevel = logsMinLevel",
            "onSelectLogsMinLevel =",
        )) {
            assertTrue("SettingsBranch 缺 `$needle` —— 日志页拿不到状态或点了没反应", branch.contains(needle))
        }
        assertTrue(
            "SettingsBranch 没有收集 viewModel.logs",
            branch.contains("viewModel.logs.collectAsState"),
        )
        assertTrue(
            "SettingsBranch 没有收集 viewModel.logsMinLevel（切换级别后芯片选中态不会更新）",
            branch.contains("viewModel.logsMinLevel.collectAsState"),
        )
    }

    // ─────────────────── 4. AppLog 双写 ───────────────────

    @Test
    fun `AppLog 的 warn 与 error 都必须落盘`() {
        val src = stripComments(mainSource("util/AppLog.kt"))
        for (sig in listOf("fun w(", "fun e(")) {
            assertTrue("AppLog.$sig 方法体里没有 LogStore.append( —— 该类日志不会进日志页", persistedIn(src, sig))
        }
    }

    /**
     * D/I 是调试级别：**必须**在 Debug 构建落盘（日志页才能按级别过滤），
     * 同时**必须**被 `BuildConfig.DEBUG` 门控（release 既不打 logcat 也不写盘）。
     * 缺门控 ⇒ release 的 D/I 会把 W/E 挤出 400 行窗口（用户报障现场丢失）。
     */
    @Test
    fun `AppLog 的 debug 与 info 必须受 DEBUG 门控并落盘`() {
        val src = stripComments(mainSource("util/AppLog.kt"))
        for (sig in listOf("fun d(", "fun i(")) {
            assertTrue("AppLog.$sig 方法体里没有 LogStore.append( —— Debug 构建日志页看不到该级别", persistedIn(src, sig))
            assertTrue(
                "AppLog.$sig 没有 BuildConfig.DEBUG 门控 —— release 会白写盘并挤掉 W/E",
                Regex("""\Q$sig\E[\s\S]{0,240}BuildConfig\.DEBUG""").containsMatchIn(src),
            )
        }
    }

    /** 负向自证：没有 BuildConfig.DEBUG 门控的 `fun d(` 必须判负。 */
    @Test
    fun `负向自证 未门控的 debug 落盘必须判负`() {
        val sample = """
            fun d(tag: String, message: String) {
                LogStore.append(LogStore.LEVEL_DEBUG, tag, message)
            }
        """.trimIndent()
        assertTrue(
            "护栏空转：未门控的 debug 落盘被误判为已门控",
            !Regex("""\Qfun d(\E[\s\S]{0,240}BuildConfig\.DEBUG""").containsMatchIn(sample),
        )
    }

    // ─────────────────── 5. 崩溃落盘 ───────────────────

    @Test
    fun `未捕获异常必须落盘`() {
        val app = stripComments(mainSource("NasMusicApp.kt"))
        assertTrue(
            "NasMusicApp 没有安装 UncaughtExceptionHandler —— 闪退不会留痕",
            app.contains("setDefaultUncaughtExceptionHandler"),
        )
        assertTrue(
            "崩溃处理器里没有 LogStore.appendBlocking( —— 异步写来不及落盘就进程终止了",
            app.contains("LogStore.appendBlocking("),
        )
        assertTrue(
            "NasMusicApp 没有初始化 LogStore（onCreate 里缺 LogStore.init(）",
            app.contains("LogStore.init("),
        )
    }

    // ─────────────────── 6. 防递归 ───────────────────

    @Test
    fun `LogStore 不得反向调用 AppLog`() {
        val src = stripComments(mainSource("util/LogStore.kt"))
        assertTrue(
            "LogStore 里出现了 AppLog. 调用 —— 会形成 写入→打印→再写入 的无限递归",
            !src.contains("AppLog."),
        )
    }

    // ─────────────────── 7. 混淆规则：Gson 泛型签名 ───────────────────

    /**
     * v2.39.2 真机 bug（日志页抓到）：release 包报
     * `IllegalStateException: TypeToken must be created with a type argument`。
     * 根因是 R8 默认剥掉 `Signature` 属性，`object : TypeToken<Map<String, X>>() {}.type`
     * 拿不到类型实参。这条规则**只影响 release**，debug 与全部单测都测不到 ⇒ 必须门禁锁。
     */
    @Test
    fun `Gson 泛型签名必须在混淆规则中保留`() {
        val rules = projectFile("app/proguard-rules.pro")
        assertTrue(
            "proguard-rules.pro 缺 `-keepattributes Signature` —— release 下所有 TypeToken 调用点都会抛异常",
            hasSignatureKeep(rules),
        )
        assertTrue(
            "proguard-rules.pro 缺 TypeToken 匿名子类保留规则（只保 Gson 自身类不够）",
            rules.lineSequence().any {
                it.trim().startsWith("-keep class * extends com.google.gson.reflect.TypeToken")
            },
        )
    }

    /** 负向自证：只 keep Gson 自身类、不 keepattributes Signature 的规则必须判负。 */
    @Test
    fun `负向自证 缺 Signature 保留必须判负`() {
        val sample = "# Gson\n-keep class com.google.gson.** { *; }\n"
        assertTrue("护栏空转：缺 Signature 的样例被误判为已保留", !hasSignatureKeep(sample))
    }

    // ─────────────────── 8. 日志详情入口 ───────────────────

    @Test
    fun `日志行必须能打开完整详情`() {
        val section = stripComments(mainSource("ui/screens/settings/LogsSettingsSection.kt"))
        assertTrue("缺 LogDetailDialog( —— 只能看截断的首行", section.contains("LogDetailDialog("))
        assertTrue("详情里必须展示完整堆栈（entry.stackTrace）", section.contains("entry.stackTrace"))
        assertTrue(
            "列表行必须可聚焦（SettingsTile）—— 否则遥控器根本点不开详情；" +
                "⛔ 分区里不许手写 FocusableSurface（令牌只在 SettingsComponents 定义一次）",
            section.contains("SettingsTile("),
        )
    }

    // ─────────────────── 8b. 级别过滤（v2.39.4） ───────────────────

    /**
     * 四个级别芯片 + 过滤纯函数 + 切换动作缺一不可：
     * 只画芯片不接 `filterAtLeast`（列表不变）或只过滤不落动作（点了没反应）
     * 都能编译通过，必须在源码层面锁住。
     */
    @Test
    fun `日志页必须提供级别过滤`() {
        val section = stripComments(mainSource("ui/screens/settings/LogsSettingsSection.kt"))
        for (needle in listOf(
            "settings_logs_level_all",
            "settings_logs_level_info",
            "settings_logs_level_warn",
            "settings_logs_level_error",
            "LogStore.filterAtLeast(",
            "onSelectMinLevel",
        )) {
            assertTrue("日志页缺 `$needle` —— 无法按级别筛选", section.contains(needle))
        }
    }

    /** 负向自证：只渲染列表、不带过滤的样例必须判负。 */
    @Test
    fun `负向自证 无级别过滤的日志页必须判负`() {
        val sample = "state.entries.forEach { LogEntryRow(entry = it, onOpen = {}) }"
        assertTrue(
            "护栏空转：没有过滤的样例被误判通过",
            !(sample.contains("LogStore.filterAtLeast(") && sample.contains("onSelectMinLevel")),
        )
    }

    // ─────────────────── 9. 详情可复制（v2.39.2 真机反馈） ───────────────────

    /**
     * 真机原话：「报错信息文本无法长按选择进行复制，并且还未显示完全。
     * 要增加一个复制报错信息的按钮。」
     *
     * 电视上**没有长按选择**这回事（遥控器没指针），一键复制是唯一出口；
     * 而复制内容漏了堆栈就等于没复制 ⇒ 这里同时锁「按钮存在」与「写的是全文」。
     */
    @Test
    fun `日志详情必须提供一键复制全文`() {
        val section = stripComments(mainSource("ui/screens/settings/LogsSettingsSection.kt"))
        assertTrue(
            "详情里没有 `settings_logs_detail_copy` 文案 —— 复制按钮没接上",
            section.contains("settings_logs_detail_copy"),
        )
        assertTrue(
            "详情里没有调用 `toClipboardText()` —— 复制出去的内容不是全文",
            section.contains("toClipboardText()"),
        )
        assertTrue(
            "详情里没有调用 `LinkUtils.copyToClipboard` —— 按钮点了不会写剪贴板",
            section.contains("LinkUtils.copyToClipboard("),
        )
    }

    /** 负向自证：只放一个「关闭」按钮的详情必须判负。 */
    @Test
    fun `负向自证 详情只有关闭按钮必须判负`() {
        val sample = """
            SettingsChip(
                label = stringResource(R.string.settings_logs_detail_close),
                selected = false,
                onClick = onDismiss,
            )
        """.trimIndent()
        assertTrue(
            "护栏空转：没有复制按钮的样例被误判通过",
            !(sample.contains("settings_logs_detail_copy") && sample.contains("toClipboardText()")),
        )
    }

    // ─────────────────── 10. 详情可滚动（TV 上不许被裁） ───────────────────

    /**
     * 真机反馈的后半句「还未显示完全」根因：`responsiveDialogSize` 在**非竖屏**
     * 只返回 `Modifier.width(…)`，`scrollable` 参数在 TV 上被静默忽略
     * ⇒ 长堆栈直接被屏幕裁掉，而且遥控器也没有拖动这回事。
     *
     * 判据三件套缺一不可：高度上限（别顶出屏幕）+ 正文滚动容器 + 上下键处理。
     */
    @Test
    fun `日志详情在 TV 上必须能滚到底`() {
        val section = stripComments(mainSource("ui/screens/settings/LogsSettingsSection.kt"))
        assertTrue(
            "详情缺 `heightIn(` 高度上限 —— TV 分支的 responsiveDialogSize 不给高度，长堆栈会顶出屏幕",
            section.contains("heightIn("),
        )
        assertTrue(
            "详情缺 `verticalScroll(` —— 堆栈根本滚不动",
            section.contains("verticalScroll("),
        )
        assertTrue(
            "详情缺上下键处理（`Key.DirectionDown`）—— 遥控器无法翻页，纯文本也没有子焦点能带滚",
            section.contains("Key.DirectionDown"),
        )
    }

    /** 负向自证：只有 `responsiveDialogSize(…, scrollable = true)` 的写法必须判负。 */
    @Test
    fun `负向自证 只靠 responsiveDialogSize 可滚动必须判负`() {
        val sample = "Column(modifier = responsiveDialogSize(landscapeWidth = 760.dp, scrollable = true)) { }"
        assertTrue(
            "护栏空转：只传 scrollable = true 的样例被误判为已处理（该参数在 TV 上被忽略）",
            !(sample.contains("heightIn(") && sample.contains("verticalScroll(") && sample.contains("Key.DirectionDown")),
        )
    }

    // ─────────────────── 12. 清空确认必须是真弹窗（v2.39.2 真机反馈） ───────────────────

    /**
     * 真机原话：「点击清空日志，发现提示框出现在日志信息的最下方，应该用弹窗展示」。
     *
     * 根因：`ConfirmDialog` **本身不是弹窗** —— 它只是一个 `Box(fillMaxSize)` 遮罩，
     * 必须由调用方套一层真正的 `Dialog`（全仓既有调用点都是这个写法）。
     * 直接塞进分区的 `Column` 就会变成「列表下方一整块全屏遮罩」。
     */
    @Test
    fun `清空确认必须套真正的 Dialog`() {
        val section = stripComments(mainSource("ui/screens/settings/LogsSettingsSection.kt"))
        val body = bodyOf(section, "internal fun LogsSettingsSection(") ?: ""
        assertTrue("取不到 LogsSettingsSection 方法体", body.isNotEmpty())
        assertTrue(
            "分区里没有 `Dialog(` —— ConfirmDialog 会被当成普通内容插进列表下方",
            body.contains("Dialog("),
        )
        assertTrue(
            "`ConfirmDialog(` 没有被 `Dialog(` 包住（顺序/距离不对）",
            Regex("""Dialog\([\s\S]{0,600}ConfirmDialog\(""").containsMatchIn(body),
        )
    }

    /** 负向自证：裸 ConfirmDialog（不套 Dialog）必须判负。 */
    @Test
    fun `负向自证 裸 ConfirmDialog 必须判负`() {
        val sample = """
            if (showClearConfirm) {
                ConfirmDialog(
                    title = "t", message = "m", onConfirm = {}, onDismiss = {}, destructive = true,
                )
            }
        """.trimIndent()
        assertTrue(
            "护栏空转：没套 Dialog 的 ConfirmDialog 被误判通过",
            !Regex("""Dialog\([\s\S]{0,600}ConfirmDialog\(""").containsMatchIn(sample),
        )
    }

    // ─────────────────── 13. 完整性自证 + 预览阶段拦键 ───────────────────

    /**
     * 真机反馈「复制粘出来本身就少」—— 光把堆栈渲染出来不够，
     * 必须让用户当场能核对这条到底有多少内容，以及它是不是写入时被截断的。
     */
    @Test
    fun `日志详情必须能自证内容完整`() {
        val section = stripComments(mainSource("ui/screens/settings/LogsSettingsSection.kt"))
        for (needle in listOf(
            "settings_logs_detail_size",
            "entry.totalLines",
            "entry.totalChars",
            "settings_logs_detail_truncated",
            "entry.truncated",
        )) {
            assertTrue("详情缺 `$needle` —— 无法判断复制出来的是不是全文", section.contains(needle))
        }
    }

    /**
     * 翻页必须走 `onPreviewKeyEvent`：预览阶段**先于**子节点派发，
     * 焦点芯片即使吞掉方向键也拦不住。`onKeyEvent` 依赖冒泡，一旦被消费就彻底滚不动。
     */
    @Test
    fun `翻页必须用预览阶段拦键`() {
        val section = stripComments(mainSource("ui/screens/settings/LogsSettingsSection.kt"))
        assertTrue("缺 onPreviewKeyEvent —— 方向键可能被子节点吞掉，正文滚不动", section.contains("onPreviewKeyEvent"))
        assertTrue("仍在用 onKeyEvent（冒泡阶段，靠不住）", !section.contains(".onKeyEvent"))
    }

    /** 负向自证：用 `.onKeyEvent` 的写法必须判负。 */
    @Test
    fun `负向自证 冒泡阶段拦键必须判负`() {
        val sample = "Column(modifier = Modifier.onKeyEvent { e -> e.key == Key.DirectionDown })"
        assertTrue(
            "护栏空转：`.onKeyEvent` 的写法被误判为已处理",
            !(sample.contains("onPreviewKeyEvent") && !sample.contains(".onKeyEvent")),
        )
    }

    // ─────────────────── 11. Meting 响应形态收敛（v2.39.2） ───────────────────
    /**
     * 真机日志：`parseSongs error: JsonSyntaxException … Expected BEGIN_ARRAY but was BEGIN_OBJECT`。
     * 端点回对象时旧写法整次搜索报废 ⇒ 必须走 [MetingJsonShape] 收敛形态。
     */
    @Test
    fun `parseSongs 必须先收敛响应形态`() {
        val src = stripComments(mainSource("backend/network/MetingApiService.kt"))
        assertTrue(
            "parseSongs 没有走 `MetingJsonShape.extract(` —— 端点回对象时又会整次搜索失败",
            src.contains("MetingJsonShape.extract("),
        )
        assertTrue(
            "parseSongs 不再使用 `TypeToken<List<JsonObject>>`（该写法对对象响应直接抛异常）",
            !src.contains("TypeToken<List<JsonObject>>"),
        )
        assertTrue(
            "形态诊断没有进日志（AppLog 里要带 shape / body）—— 真机无从判断端点回了什么",
            Regex("""parseSongs: no songs \(shape=""").containsMatchIn(src),
        )
    }

    /** 负向自证：旧的 TypeToken 直解析写法必须判负。 */
    @Test
    fun `负向自证 TypeToken 直解析必须判负`() {
        val sample = """
            private fun parseSongs(body: String): List<Song> {
                val type = object : TypeToken<List<JsonObject>>() {}.type
                return gson.fromJson(body, type)
            }
        """.trimIndent()
        assertTrue(
            "护栏空转：旧的 TypeToken 直解析写法被误判为已收敛形态",
            !sample.contains("MetingJsonShape.extract("),
        )
    }

    private fun hasSignatureKeep(rules: String): Boolean =
        rules.lineSequence().any { it.trim() == "-keepattributes Signature" }

    // ─────────────────── 负向自证（同一份谓词喂反例） ───────────────────

    /** 负向 ①：`AppLog` 不写 LogStore 时，[persistedIn] 必须判负。 */
    @Test
    fun `负向自证 AppLog 未接 LogStore 必须判负`() {
        val sample = """
            object AppLog {
                fun w(tag: String, message: String) {
                    android.util.Log.w(tag, message)
                }
                fun e(tag: String, message: String) {
                    android.util.Log.e(tag, message)
                }
            }
        """.trimIndent()
        assertTrue("护栏空转：没接 LogStore 的样例被误判为已落盘", !persistedIn(sample, "fun w("))
        assertTrue("护栏空转：没接 LogStore 的样例被误判为已落盘", !persistedIn(sample, "fun e("))
    }

    /** 负向 ②：`LogStore` 反向调用 `AppLog` 时必须被检出。 */
    @Test
    fun `负向自证 LogStore 反向调用 AppLog 必须被检出`() {
        val sample = "object LogStore { fun append() { AppLog.e(\"LogStore\", \"x\") } }"
        assertTrue("护栏空转：递归调用没被检出", sample.contains("AppLog."))
    }

    /** 负向 ③：`SettingsScreen` 只加枚举不加渲染时必须判负。 */
    @Test
    fun `负向自证 只登记枚举不渲染必须判负`() {
        val sample = "listOf(SettingsSection.LOGS, SettingsSection.DATA).forEach { }"
        assertTrue("护栏空转：没渲染 LogsSettingsSection 的样例被误判通过", !sample.contains("LogsSettingsSection("))
    }

    /** 负向 ④：崩溃处理器缺 appendBlocking 时必须判负。 */
    @Test
    fun `负向自证 崩溃处理器缺同步落盘必须判负`() {
        val sample = """
            Thread.setDefaultUncaughtExceptionHandler { t, e -> previous?.uncaughtException(t, e) }
        """.trimIndent()
        assertTrue("护栏空转：缺 appendBlocking 的样例被误判通过", !sample.contains("LogStore.appendBlocking("))
    }

    // ─────────────────── 工具 ───────────────────

    /**
     * 判定某个方法体内是否调用 `LogStore.append(`（剥注释后、按大括号配对取方法体）。
     *
     * 只认「方法体」而不是全文：`AppLog` 的 KDoc 里也会提到 LogStore，
     * 全文匹配会让注释把门禁骗过去（T14 的 `Desc-exempt` 就栽在这种错位上）。
     */
    private fun persistedIn(src: String, signature: String): Boolean {
        val body = bodyOf(src, signature) ?: return false
        return body.contains("LogStore.append(")
    }

    /** 取 `signature` 起第一个 `{` 到配对 `}` 的正文；找不到返回 null。 */
    private fun bodyOf(src: String, signature: String): String? {
        val start = src.indexOf(signature)
        if (start < 0) return null
        val open = src.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open)
    }

    /**
     * 项目根相对路径（`app/proguard-rules.pro` 这类不在 java 源集里的文件）。
     * `user.dir` 可能是仓库根、`app/` 或更深的构建目录 ⇒ 多候选。
     */
    private fun projectFile(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $relativePath（user.dir=${File("").absolutePath}）", found != null)
        return found!!.readText(Charsets.UTF_8)
    }

    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 $relativePath（user.dir=${File("").absolutePath}）",
            found != null,
        )
        return found!!.readText(Charsets.UTF_8)
    }

    private fun resXml(valuesDir: String): String {
        val candidates = listOf(
            File("src/main/res/$valuesDir/strings.xml"),
            File("app/src/main/res/$valuesDir/strings.xml"),
            File("../app/src/main/res/$valuesDir/strings.xml"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $valuesDir/strings.xml", found != null)
        return found!!.readText(Charsets.UTF_8)
    }

    /**
     * 剥注释（状态机，支持嵌套块注释 / 原始字符串 / 字符字面量）。
     * 与 `SettingsGroupCoverageTest.stripComments` 同一口径的最小实现：
     * 门禁判据必须建立在「真代码」上，否则 KDoc 里的一句话就能把红变绿。
     */
    private fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var depth = 0
        var inStr = false
        var inRaw = false
        var inChar = false
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else '\u0000'
            when {
                inLine -> {
                    if (c == '\n') { inLine = false; sb.append('\n') } else sb.append(' ')
                    i++
                }
                inBlock -> {
                    if (c == '/' && n == '*') { depth++; sb.append("  "); i += 2 }
                    else if (c == '*' && n == '/') {
                        depth--; sb.append("  "); i += 2
                        if (depth == 0) inBlock = false
                    } else { sb.append(if (c == '\n') '\n' else ' '); i++ }
                }
                inRaw -> {
                    val run = quoteRun(src, i)
                    if (run >= 3) { repeat(run) { sb.append('"') }; inRaw = false; i += run }
                    else { sb.append(c); i++ }
                }
                inStr -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2 }
                    else { if (c == '"') inStr = false; i++ }
                }
                inChar -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2 }
                    else { if (c == '\'') inChar = false; i++ }
                }
                c == '/' && n == '/' -> { inLine = true; sb.append("  "); i += 2 }
                c == '/' && n == '*' -> { inBlock = true; depth = 1; sb.append("  "); i += 2 }
                quoteRun(src, i) >= 3 -> { sb.append("\"\"\""); inRaw = true; i += 3 }
                else -> {
                    if (c == '"') inStr = true
                    if (c == '\'') inChar = true
                    sb.append(c); i++
                }
            }
        }
        return sb.toString()
    }

    private fun quoteRun(s: String, from: Int): Int {
        var k = 0
        while (from + k < s.length && s[from + k] == '"') k++
        return k
    }
}
