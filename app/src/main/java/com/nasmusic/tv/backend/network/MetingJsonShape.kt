package com.nasmusic.tv.backend.network

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 一次「响应体 → 歌曲对象数组」收敛的结果。
 *
 * [shape] 是**诊断串**，不是给用户看的文案：真机日志里要能一眼看出
 * 「端点回的是数组 / 包装对象 / 错误信封 / 不是 JSON」。
 */
internal data class MetingArrayExtraction(
    /** 认出来的歌曲对象数组；非对象元素（数字、字符串）已被丢弃 */
    val items: List<JsonObject> = emptyList(),
    /** 形态诊断串：`array@root` / `array@root.data` / `object@root keys=[…]` / `invalid:…` */
    val shape: String = "",
    /** 响应体开头摘要（换行压平 + 截断），用于核对端点到底回了什么 */
    val bodyPreview: String = "",
)

/**
 * Meting 兼容端点响应形态的**收敛器**（v2.39.2）。
 *
 * ## 为什么单独成文件
 * `type=search` 并不保证返回 JSON 数组。真机日志（2026-10-20 00:17:51）抓到：
 * `MetingDiag parseSongs error: JsonSyntaxException: … Expected BEGIN_ARRAY but was BEGIN_OBJECT at line 1 column 2`。
 * 原实现直接用 `TypeToken<List<JsonObject>>` 反序列化 ⇒ 端点只要回一个 `{`
 * （错误信封 `{"error":…}`、或 `{"data":[…]}` 这类包装）整次搜索就变成异常，
 * 而且日志里只剩异常文本、看不到**端点到底回了什么**，无法定位。
 *
 * ## 为什么是纯 JVM
 * 只依赖 Gson，不碰 Android / `AppLog` ⇒ 免 Gradle 通道（`logs_temp/kt_standalone.py`）
 * 能直接编译 + 单测，不必等一次完整 `testDebugUnitTest` 才知道逻辑对不对。
 *
 * ## 容忍顺序
 * ① 根就是数组 → 直接用；
 * ② 根是对象 → 按 [WRAPPER_KEYS] 顺序下钻（最多 [MAX_DEPTH] 层），
 *    取**第一个非空**数组；全是空数组则返回第一个空数组（"端点确实回了空列表"，
 *    与"形态认不出来"是两种不同的日志，不能混）；
 * ③ 都不是 → 返回空 items + 形态诊断（调用方据此记一条带正文摘要的 W 日志）。
 */
internal object MetingJsonShape {

    /**
     * 包装键，按命中优先级排列。
     * `data` / `result` 是 Meting 系最常见的两种信封；其余是各家聚合层的惯用名。
     */
    private val WRAPPER_KEYS = listOf(
        "data", "result", "results", "songs", "list", "items", "tracks", "playlist", "song",
    )

    /** 下钻层数上限：`{"data":{"result":{"songs":[…]}}}` 这种已经够离谱了。 */
    private const val MAX_DEPTH = 3

    /** 正文摘要长度上限（⛔ 端点可能回整页 HTML 错误页，别把它整个写进日志）。 */
    internal const val PREVIEW_LIMIT = 240

    /** 入口：解析 [body] 并收敛成歌曲数组。**不抛异常**（畸形 JSON 也只返回诊断）。 */
    fun extract(gson: Gson, body: String): MetingArrayExtraction {
        val preview = preview(body)
        val root: JsonElement? = try {
            gson.fromJson(body, JsonElement::class.java)
        } catch (e: Exception) {
            return MetingArrayExtraction(shape = "invalid:${e.javaClass.simpleName}", bodyPreview = preview)
        }
        if (root == null) return MetingArrayExtraction(shape = "empty", bodyPreview = preview)
        return resolve(root, "root", 0, preview)
    }

    private fun resolve(
        el: JsonElement,
        path: String,
        depth: Int,
        preview: String,
    ): MetingArrayExtraction {
        if (el.isJsonArray) {
            val objs = el.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            return MetingArrayExtraction(objs, "array@$path", preview)
        }
        if (!el.isJsonObject) {
            // JsonNull / 数字 / 字符串：端点多半回了错误页或纯文本
            return MetingArrayExtraction(shape = "scalar@$path", bodyPreview = preview)
        }
        val obj = el.asJsonObject
        if (depth >= MAX_DEPTH) {
            return MetingArrayExtraction(
                shape = "too-deep@$path keys=${obj.keySet()}",
                bodyPreview = preview,
            )
        }
        var emptyArrayFallback: MetingArrayExtraction? = null
        for (key in WRAPPER_KEYS) {
            val child = obj.get(key) ?: continue
            if (!child.isJsonArray && !child.isJsonObject) continue
            val hit = resolve(child, "$path.$key", depth + 1, preview)
            if (hit.items.isNotEmpty()) return hit
            // 空数组也算「认出来了」——留作兜底，但继续往下找非空的
            if (emptyArrayFallback == null && hit.shape.startsWith("array@")) {
                emptyArrayFallback = hit
            }
        }
        return emptyArrayFallback ?: MetingArrayExtraction(
            shape = "object@$path keys=${obj.keySet()}",
            bodyPreview = preview,
        )
    }

    /** 正文摘要：换行压平 + 截断。 */
    internal fun preview(body: String): String {
        val flat = body.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= PREVIEW_LIMIT) flat else flat.take(PREVIEW_LIMIT) + "…"
    }
}
