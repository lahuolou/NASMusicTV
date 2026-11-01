package com.nasmusic.tv.backend.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Meting 响应形态收敛（[MetingJsonShape]）的纯 JVM 单测。
 *
 * ## 为什么有这一层
 * v2.39.2 真机日志抓到：`MetingDiag parseSongs error: JsonSyntaxException:
 * … Expected BEGIN_ARRAY but was BEGIN_OBJECT at line 1 column 2 path $`。
 * 端点是**第三方聚合层**，返回形态不可控 —— 错误信封、`{"data":[…]}` 包装、
 * 甚至整页 HTML 错误页都可能。原来直接用 `TypeToken<List<JsonObject>>` 反序列化，
 * 一个 `{` 就让整次搜索变成异常，而日志里只有异常文本、看不到端点回了什么。
 *
 * ## 判据约定
 * - 「空数组」与「形态认不出」必须是**两种不同的 shape** —— 前者是端点的正常答案，
 *   后者才是需要人介入的问题；混成一个值会让真机日志失去诊断力。
 * - 最后一条是**负向自证**：证明旧写法在对象响应上真的会抛异常（护栏不是空转）。
 */
class MetingJsonShapeTest {

    private val gson = Gson()

    private fun extract(body: String) = MetingJsonShape.extract(gson, body)

    // ───────────────────── 正向：数组与包装 ─────────────────────

    @Test
    fun `根数组直接可用`() {
        val r = extract("""[{"title":"A"},{"title":"B"}]""")
        assertEquals(2, r.items.size)
        assertEquals("array@root", r.shape)
    }

    @Test
    fun `data 包装必须能下钻`() {
        val r = extract("""{"code":200,"data":[{"title":"A"}]}""")
        assertEquals(1, r.items.size)
        assertEquals("A", r.items[0].get("title").asString)
        assertEquals("array@root.data", r.shape)
    }

    @Test
    fun `嵌套包装必须能下钻到数组`() {
        val r = extract("""{"data":{"songs":[{"title":"A"},{"title":"B"},{"title":"C"}]}}""")
        assertEquals(3, r.items.size)
        assertEquals("array@root.data.songs", r.shape)
    }

    @Test
    fun `优先取非空数组而不是第一个命中的空数组`() {
        // data 是空数组、result 有货 —— 必须继续往下找，不能拿到空就收工
        val r = extract("""{"data":[],"result":[{"title":"A"}]}""")
        assertEquals(1, r.items.size)
        assertEquals("array@root.result", r.shape)
    }

    @Test
    fun `数组里的非对象元素必须被丢弃而不是炸掉`() {
        val r = extract("""[{"title":"A"},42,"x",null,{"title":"B"}]""")
        assertEquals(2, r.items.size)
    }

    // ───────────────────── 边界：空数组 vs 认不出 ─────────────────────

    @Test
    fun `空数组算认出来了`() {
        assertEquals("array@root", extract("[]").shape)
        assertEquals("array@root.data", extract("""{"data":[]}""").shape)
        assertTrue(extract("[]").items.isEmpty())
    }

    @Test
    fun `错误信封必须给出对象形态诊断并带正文摘要`() {
        val r = extract("""{"code":403,"error":"forbidden: rate limited"}""")
        assertTrue("错误信封不该被当成歌曲列表", r.items.isEmpty())
        assertTrue("形态诊断必须标明是对象：实际 ${r.shape}", r.shape.startsWith("object@root"))
        assertTrue("诊断里必须带键名，否则不知道端点回了什么：实际 ${r.shape}", r.shape.contains("error"))
        assertTrue("正文摘要必须含错误内容：实际 ${r.bodyPreview}", r.bodyPreview.contains("rate limited"))
    }

    @Test
    fun `非 JSON 正文必须给出 invalid 诊断而不是抛异常`() {
        val r = extract("<html><body>502 Bad Gateway</body></html>")
        assertTrue(r.items.isEmpty())
        assertTrue("畸形正文的形态诊断必须以 invalid 开头：实际 ${r.shape}", r.shape.startsWith("invalid:"))
        assertTrue("正文摘要必须保留原文线索", r.bodyPreview.contains("502"))
    }

    @Test
    fun `标量正文必须给出 scalar 诊断`() {
        assertEquals("scalar@root", extract("\"ok\"").shape)
        assertEquals("scalar@root", extract("null").shape)
    }

    // ───────────────────── 摘要 ─────────────────────

    @Test
    fun `正文摘要必须压平换行并截断`() {
        val long = "a".repeat(MetingJsonShape.PREVIEW_LIMIT + 500)
        val p = MetingJsonShape.preview("line1\nline2\r\n$long")
        assertTrue("摘要不得含换行（否则日志会被撑成多行）", !p.contains('\n') && !p.contains('\r'))
        assertTrue(
            "摘要长度必须被截断（上限 ${MetingJsonShape.PREVIEW_LIMIT} + 省略号）：实际 ${p.length}",
            p.length <= MetingJsonShape.PREVIEW_LIMIT + 1,
        )
        assertTrue("截断必须有可辨识的省略号", p.endsWith("…"))
    }

    // ───────────────────── 负向自证 ─────────────────────

    /**
     * 证明本类不是空转：**旧写法**（直接按 `List<JsonObject>` 反序列化）
     * 在同一个对象响应上必然抛 [JsonSyntaxException]。
     */
    @Test
    fun `负向自证 直接按数组反序列化对象响应必然抛异常`() {
        val body = """{"error":"forbidden"}"""
        var thrown: Throwable? = null
        try {
            val type = object : TypeToken<List<JsonObject>>() {}.type
            gson.fromJson<List<JsonObject>>(body, type)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("护栏空转：旧写法居然没抛异常，说明这条用例选错了样本", thrown is JsonSyntaxException)
        // 同一份样本走新实现必须安然返回诊断
        assertTrue(extract(body).items.isEmpty())
    }
}
