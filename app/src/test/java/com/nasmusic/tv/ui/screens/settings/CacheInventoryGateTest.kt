package com.nasmusic.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G9：磁盘缓存必须全部登记在 `data/cache/CacheInventory.kt`（方案 §1.14）。
 *
 * 成因：本轮人肉扫出 14 处落盘缓存，其中 5 处设置页管不到、4 处统计不到，还有
 * 3 个「清除函数早写好了但没人调」（CoverUrlPersistentCache / AccompanimentCache /
 * EmbeddedCoverExtractor）。靠人肉复查必然再漏，所以把「有没有登记」变成文本门。
 *
 * 三条判据：
 *  1. 源码里 cacheDir / filesDir / dataDir 后面紧跟的**字面量**（逗号或 `.resolve(`）
 *     必须作为 `dir = "…"` 出现在登记表里；Room 的三个 `.db` 名单独扫。未登记 = 红。
 *  2. 登记为 `deletable = true` 的条目必须给 `clearVia`，且 main 源集里真有同名 `fun` 声明
 *     =「说能清、其实清不了」= 红。这条专治清除函数写好没接线。
 *  3. 登记的非空 `dir` 必须在全树出现过 = 防「登记了一条已经不存在的路径」。
 *
 * 已知失配（诚实记录，别当门没用）：常量拼接的目录名扫不到，实测 accompaniment 就是漏网项，
 * 它靠人工登记；所以判据 3 只做"出现过"的正向检查，不做"必须被扫到"的反向断言。
 *
 * 空转防护：命中目录数必须 >= 15（实测 16）；命中为 0 说明正则失配或源根定位错了。
 * 豁免：`cache-inventory-exempt: <理由>` 行级注释（口径同既有 Xxx-exempt）。
 *
 * ⚠️ 行号一律在未剥离的 `raw` 上数，正则跑在 [stripComments] 上 —— 这只有两者**长度逐字符
 * 相等**才成立（stripComments 只把注释字符换成空格，从不删除字符）。若哪天 helper 改成
 * "删除注释"，豁免行与 `found` 的行号会一起漂到别处，门会安静地放行真违规。
 */
class CacheInventoryGateTest {

    private val exempt = "cache-inventory-exempt:"

    /** 字符类里的美元符写成 \u0024：raw string 中裸 `$` 会被 Kotlin 当成模板起始符 */
    private val dirPattern = Regex(
        """(?:cacheDir|filesDir|dataDir)\s*(?:,\s*|\.resolve\(\s*)"([^"/\u0024]+)"""
    )

    @Test
    fun `每个落盘目录都必须登记在 CacheInventory`() {
        val root = mainSourceRoot()
        val registry = read(root.resolve("data/cache/CacheInventory.kt"))
        val allMain = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { read(it) }

        val found = LinkedHashMap<String, String>()          // dir → 出处
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val raw = f.readText()
            val lines = raw.split("\n")
            dirPattern.findAll(stripComments(raw)).forEach { m ->
                val line = raw.substring(0, m.range.first).count { c -> c == '\n' } + 1
                val waived = (maxOf(0, line - 4) until minOf(lines.size, line + 1))
                    .any { lines[it].contains(exempt) }
                if (!waived) found.putIfAbsent(m.groupValues[1], "${f.name}:$line")
            }
        }
        listOf("backend/download/db/DownloadDatabase.kt", "backend/local/db/LocalMusicDatabase.kt",
               "backend/photo/db/PhotoFaceDatabase.kt").forEach { rel ->
            Regex(""""([A-Za-z0-9_]+\.db)"""").findAll(stripComments(read(root.resolve(rel))))
                .forEach { found.putIfAbsent(it.groupValues[1], "room") }
        }

        // 空转下限写不变式（当前基线 16 个，门只要求 >= 15）；命中为 0 说明正则失配或源根错位。
        assertTrue("G9 只扫到 ${found.size} 个目录，疑似正则失配或源根错位", found.size >= 15)
        println("G9 命中 " + found.size + " 个落盘目录：" +
            found.entries.joinToString(", ") { it.key + "<-" + it.value })

        val registered = registeredDirs(registry)
        val missing = found.keys.filter { it !in registered }
        assertTrue("未登记的落盘目录：" + missing.joinToString { "$it（${found[it]}）" } +
            " —— 加进 CacheInventory.entries 并决定 deletable/说明", missing.isEmpty())

        val bogus = registered.filter { !allMain.contains(it) }
        assertTrue("登记表有 ${bogus.size} 条路径在源码中找不到，登记已失真：$bogus", bogus.isEmpty())
    }

    @Test
    fun `登记为可清的条目必须有真实的清除函数声明`() {
        val root = mainSourceRoot()
        val registry = read(root.resolve("data/cache/CacheInventory.kt"))
        val allMain = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { read(it) }
        val dead = deadClearVia(registry, allMain)
        assertTrue("这些条目声称可清但找不到 fun 声明：$dead（补函数，或改 deletable = false）", dead.isEmpty())
    }

    /** 判据 1 的登记表侧：`dir = "x"` 集合（空串 = 外部根本身，不参与比对） */
    private fun registeredDirs(registry: String) =
        Regex("""dir\s*=\s*"([^"/\u0024]+)"""").findAll(registry).map { it.groupValues[1] }.toSet()

    /** 判据 2：deletable = true 却找不到 `fun clearVia(` 的条目 */
    private fun deadClearVia(registry: String, allMain: String) =
        Regex("""dir\s*=\s*"([^"]+)"[\s\S]{0,500}?deletable\s*=\s*true[\s\S]{0,300}?clearVia\s*=\s*"([A-Za-z0-9_]+)"""")
            .findAll(registry)
            .filterNot { allMain.contains("fun ${it.groupValues[2]}(") }
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    // ── 自证：每条判据都要有能把它打红的样本 ──
    @Test
    fun `自证 未登记目录必须判红`() {
        val registry = """CacheEntry(dir = "image_cache", deletable = true, clearVia = "clearCoverCache")"""
        val found = setOf("image_cache", "album_covers", "brand_new_cache")
        assertEquals(listOf("album_covers", "brand_new_cache"), (found - registeredDirs(registry)).sorted())
    }

    @Test
    fun `自证 clearVia 拼错必须判红`() {
        val registry = """CacheEntry(dir = "accompaniment", deletable = true, clearVia = "clearCacheNotThere")"""
        assertEquals(listOf("accompaniment" to "clearCacheNotThere"),
            deadClearVia(registry, "    fun clearCache() { }"))
    }

    @Test
    fun `自证 真实存在的清除函数必须判绿`() {
        val registry = """CacheEntry(dir = "accompaniment", deletable = true, clearVia = "clearCache")"""
        assertEquals(emptyList<Pair<String, String>>(), deadClearVia(registry, "    fun clearCache() { }"))
    }

    @Test
    fun `自证 注释里的示例目录不得计入`() {
        val src = "// File(context.cacheDir, \"not_a_real_dir\")\nFile(context.cacheDir, \"accompaniment\")\n" +
            "cacheDir.resolve(\"image_cache\")"
        assertEquals(listOf("accompaniment", "image_cache"),
            dirPattern.findAll(stripComments(src)).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `自证 模板文件名只取到美元符之前`() {
        val dollar = 0x24.toChar()   // 真树写法：File(cacheDir, "cover_cache_$hash.$ext")
        val src = """File(cacheDir, "cover_cache_${dollar}hash.${dollar}ext")"""
        assertEquals(listOf("cover_cache_"), dirPattern.findAll(src).map { it.groupValues[1] }.toList())
    }

    /**
     * 保长保换行：只把注释字符换成空格，字符串/字符字面量内容原样留下（正则要读引号里的目录名）。
     *
     * ⚠️ **必须**连字符字面量一起处理，否则失串：真树有 4 处 `trim('\'', '"')`
     * （MetingApiService:119 / BilibiliMvService:71 / AppPreferences:959 / ModelTransferServer:137），
     * 那个 `'"'` 会被当成字符串起始符，一路吞到下一个 `"` —— 本机实测会少扫到 3 个目录
     * （判据①静默空转）。剥壳后长度必须与入参逐字符相等（`replace` 成空格而非删除），
     * 否则 [每个落盘目录都必须登记在 CacheInventory] 里"行号在 raw 上数、正则跑在剥后文本上"
     * 的前提不成立，豁免行会漂走。
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src)
        var i = 0
        var inLine = false; var inBlock = false; var inStr = false; var inChar = false
        while (i < out.length) {
            val c = out[i]
            when {
                inStr -> {
                    if (c == '\\') { i += 2; continue }
                    if (c == '"') inStr = false
                }
                inChar -> {
                    if (c == '\\') { i += 2; continue }
                    if (c == '\'') inChar = false
                }
                inLine -> if (c == '\n') inLine = false else out.setCharAt(i, ' ')
                inBlock -> {
                    if (c == '*' && i + 1 < out.length && out[i + 1] == '/') {
                        inBlock = false
                        out.setCharAt(i, ' '); out.setCharAt(i + 1, ' ')
                        i += 2
                        continue
                    }
                    if (c != '\n') out.setCharAt(i, ' ')
                }
                c == '"' -> inStr = true
                c == '\'' -> inChar = true
                c == '/' && i + 1 < out.length && out[i + 1] == '/' -> inLine = true
                c == '/' && i + 1 < out.length && out[i + 1] == '*' -> inBlock = true
            }
            i++
        }
        return out.toString()
    }

    private fun read(f: File) = if (f.exists()) f.readText() else error("找不到文件：${f.absolutePath}")

    private fun mainSourceRoot(): File {
        // AGP 单测的 user.dir 是模块目录（…/app），CI 与本地也可能是仓库根，两处都试。
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}")
    }
}
