package com.nasmusic.tv.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MainViewModel 构造顺序门禁（v2.39.3 补丁，2026-10-20 真机 release 启动崩溃）。
 *
 * ## 背景
 * 崩溃现场（release，混淆栈经 mapping.txt + dexdump 双向还原）：
 * `SynchronizedLazyImpl.getValue` on null，DefaultDispatcher-worker，崩在
 * `updateMergedData()` 协程体读 `resolvedAlbumCovers`（源码 1775 行，声明在 1848 行）处。
 * 链路：末尾 init 块 `netVM.restoreBaiduIndexOnStart { _ -> updateMergedData() }` 触发
 * Default 协程，而 `constructorReady.await()` 守卫**被跳过**——R8 把多个挂起 lambda
 * 水平合并进同一个类、共用状态标签，链上前一个挂起点把标签置 1，合并后本协程的守卫
 * 派发读到陈旧值，`await()` 被绕过直插函数体，读到尚未初始化的 lazy 委托字段。
 * v2.37.0 的守卫只防「源码层面」的竞态，防不住 R8 合并改写控制流。
 *
 * ## 修法（源码侧）
 * 三个 `by lazy`（resolvedAlbumCovers / resolvedArtistCovers / exportCoordinator）
 * 声明提前到类顶部 ⇒ 构造器最前几条就赋值委托字段，之后任何协程读到的都不是 null。
 * 本门禁把这个顺序钉死，防止后人把它们挪回文件后部。
 *
 * ## 判据口径（与仓库其它源码门禁一致）
 * 先剥注释（引号感知）再按词边界匹配；正/反向自证喂**同一份谓词**。
 */
class MainViewModelConstructionOrderTest {

    private companion object {
        /** 必须留在 MainViewModel 类顶部的 by lazy 字段 */
        val LAZY_FIELDS = listOf(
            "resolvedAlbumCovers",
            "resolvedArtistCovers",
            "exportCoordinator"
        )
    }

    // ───────────────────────── 定位（失败即 fail，不 skip）─────────────────────────

    /** 定位 `MainViewModel.kt`（AGP 单测 user.dir 是模块目录 `…/app`）。 */
    private fun mainViewModelFile(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt"),
            File("app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt"),
            File("../app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 MainViewModel.kt（user.dir=${File("").absolutePath}）；已尝试：" +
                candidates.joinToString { it.absolutePath },
            found != null
        )
        return found!!
    }

    // ───────────────────────── 谓词（被三条用例共用）─────────────────────────

    /**
     * 剥注释（引号/字符字面量感知，**保留换行**）。
     *
     * ⚠️ 必须剥：KDoc 里大段描述 `by lazy` / `init` 的文字会把词边界匹配骗过去。
     */
    fun stripComments(src: String): String {
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

    /** 字段声明 `private val <name>` 在剥注释源码中的下标；未声明返回 -1。 */
    private fun declIndex(stripped: String, field: String): Int {
        val m = Regex("""private\s+val\s+$field\b""").find(stripped) ?: return -1
        return m.range.first
    }

    /** 首个 `init {` 块在剥注释源码中的下标；没有返回 -1。 */
    private fun firstInitIndex(stripped: String): Int {
        val m = Regex("""(^|\n)[ \t]*init[ \t]*\{""").find(stripped) ?: return -1
        return m.range.first
    }

    /** `constructorReady.complete(` 下标；没有返回 -1。 */
    private fun completeIndex(stripped: String): Int {
        val m = Regex("""constructorReady\.complete\(""").find(stripped) ?: return -1
        return m.range.first
    }

    /** 共享谓词：字段声明存在、且位于首个 init 块**之前**。 */
    private fun declaredBeforeFirstInit(stripped: String, field: String): Boolean {
        val d = declIndex(stripped, field)
        val init = firstInitIndex(stripped)
        return d >= 0 && init >= 0 && d < init
    }

    // ───────────────────────── 用例 ─────────────────────────

    @Test
    fun `三个 by lazy 声明都在首个 init 块之前`() {
        val stripped = stripComments(mainViewModelFile().readText())
        LAZY_FIELDS.forEach { field ->
            assertTrue(
                "⛔ MainViewModel.$field 的声明不在首个 init 块之前。" +
                    " v2.39.3 真机崩溃根因：晚声明的 by lazy 委托字段会被构造期 init 块" +
                    " 触发的 Default 协程读到 null（R8 lambda 合并可绕过 constructorReady 守卫）。" +
                    " 处理：把声明连同注释移回类顶部（文件前 ~200 行）。",
                declaredBeforeFirstInit(stripped, field)
            )
        }
    }

    @Test
    fun `constructorReady 放行点在全部三个声明之后`() {
        val stripped = stripComments(mainViewModelFile().readText())
        val complete = completeIndex(stripped)
        assertTrue("找不到 constructorReady.complete(——最后一个 init 块被删了？", complete >= 0)
        LAZY_FIELDS.forEach { field ->
            val d = declIndex(stripped, field)
            assertTrue(
                "⛔ MainViewModel.$field 声明缺失或晚于 constructorReady.complete()。" +
                    " 放行信号必须在全部字段声明之后，否则 await() 返回时委托字段仍是 null。",
                d in 0 until complete
            )
        }
    }

    /** 正向自证：谓词在**违规夹具**上必须判 false —— 否则上面两条是恒真的空门禁。 */
    @Test
    fun `正向自证 违规夹具判 false`() {
        // 夹具 A：init 块在前、声明在后（正是 v2.39.3 崩溃时的布局）
        val badOrder = """
            class Foo {
                init {
                    println("wiring")
                }
                private val resolvedAlbumCovers: Map<String, String> by lazy { emptyMap() }
            }
        """.trimIndent()
        assertFalse(declaredBeforeFirstInit(stripComments(badOrder), "resolvedAlbumCovers"))

        // 夹具 B：根本没有该字段的声明
        val missing = "class Foo { init { println(1) } }"
        assertFalse(declaredBeforeFirstInit(stripComments(missing), "resolvedArtistCovers"))

        // 夹具 C：声明写在注释里（剥注释后应消失）——证明剥逻辑真的在生效
        val onlyInComment = "class Foo { init { println(1) }\n// private val exportCoordinator by lazy { 1 } }"
        assertFalse(declaredBeforeFirstInit(stripComments(onlyInComment), "exportCoordinator"))

        // 夹具 D：init 块写在字符串里（剥注释不该误伤，谓词按首个真 init 判）
        val stringInit = "class Foo {\n private val exportCoordinator by lazy { 1 }\n val s = \"init {\"\n init { println(1) } }"
        assertTrue(declaredBeforeFirstInit(stripComments(stringInit), "exportCoordinator"))
    }

    /** 反向自证：原始源码里**确实**声明了这三个字段（证明扫描定位有效，而非路径写错恒真）。 */
    @Test
    fun `反向自证 原文确实声明了三个 by lazy`() {
        val raw = mainViewModelFile().readText()
        LAZY_FIELDS.forEach { field ->
            assertTrue(
                "定位失效：原文未见 private val $field 的声明（user.dir=${File("").absolutePath}）",
                Regex("""private\s+val\s+$field\b""").containsMatchIn(raw)
            )
        }
    }
}
