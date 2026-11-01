package com.nasmusic.tv.backend.impl

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 飞牛 `track/list` 解析容错回归网（v2.39.3）。
 *
 * 🙏 感谢 @Chengxi-x 提供修复方案（`fetchAllPages` 逐条容错 + `parseTrack`
 * 安全访问器），本测试守的正是该方案覆盖的场景。详见 CHANGELOG v2.39.3「致谢」。
 *
 * ## 背景（真实故障）
 * 用户环境：fnOS 商店版飞牛音乐 + NASMusicTV 2.38.15。
 * `curl` 直接打 `track/list` 返回 `total=1541`、`list` 完整；专辑/艺术家列表正常；
 * **但 APP「全部歌曲」显示 0 首、搜索也无结果**。
 *
 * ## 根因
 * `fetchAllPages` 的 `items.forEach { parse(obj)?.let { ... } }` **没有异常隔离**：
 * 服务端任意一条曲目的字段类型异常（CUE 分轨产生的 `duration:"154100.5"`、
 * `album` 是字符串而非对象、`artists` 是单个对象而非数组…）都会让 Gson 的
 * `asLong` / `getAsJsonObject` / `getAsJsonArray` 抛异常 → **中断整个 forEach**
 * → 冒泡到 `runCatchingSuspend` 被吞成 `getOrDefault(emptyList())`
 * → **整页 500 首全部丢失**。
 *
 * 因为 `loadAllTracks()`（搜索索引）与 `getSongs()` 共用同一个 `parseTrack`，
 * 所以「歌曲列表 0 首」与「搜索无结果」同时出现；而专辑/艺术家走
 * `parseAlbum`/`parseArtist`（只读 `name`/`trackCount`，不解析 `duration`），
 * 完全不受影响 —— 这正好解释了用户观测到的症状组合。
 *
 * ## 本测试守的三条线
 * 1. **T0 异常隔离**：一条脏数据只丢它自己，其余照常入列表（不能整页归零）；
 * 2. **T1 字段容错**：`duration` 为字符串/浮点、`album` 为字符串、
 *    `artists` 为对象时，尽力解析而非崩溃；
 * 3. **不误伤**：`guid` / `title` 都正常的曲目必须全部保留，条数不能少。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeiniuAdapterTrackParseTest {

    private lateinit var server: MockWebServer

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** `track/list` 要返回的原始 JSON 数组内容（不含外层信封） */
    private var trackListJson: String = "[]"

    /** `track/list` 的 total */
    private var total: Int = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val body = when {
                    path.startsWith(FeiniuUrl.ACCESS_CODE_VERIFY_PATH) ->
                        """{"code":0,"msg":"","data":null}"""
                    path.endsWith("/user/password-login") ->
                        """{"code":0,"msg":"","data":{"userToken":"tok-1"}}"""
                    path.endsWith("/user/me") ->
                        """{"code":0,"msg":"","data":{"guid":"u1","name":"u"}}"""
                    path.endsWith("/sys/config") ->
                        """{"code":0,"msg":"","data":{"serverName":"TestNAS","serverVersion":"1.0"}}"""
                    path.contains("/track/list") ->
                        """{"code":0,"msg":"","data":{"total":$total,"list":$trackListJson}}"""
                    else -> """{"code":0,"msg":"","data":null}"""
                }
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody(body)
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl() = server.url("/").toString()

    private suspend fun connectedAdapter(): FeiniuAdapter {
        val adapter = FeiniuAdapter(context)
        assertTrue(adapter.initialize(baseUrl(), "", "user", "pass"))
        return adapter
    }

    // ── T0：异常隔离是「整页归零」的根因修复 ────────────────────────────

    @Test
    fun `one dirty track must not wipe the whole page`() = runBlocking {
        // 5 条正常 + 1 条 duration 为小数字符串（Gson asLong 会抛 NumberFormatException）
        trackListJson = """
            [
              {"guid":"g1","title":"正常1","duration":1000},
              {"guid":"g2","title":"正常2","duration":2000},
              {"guid":"g3","title":"脏数据","duration":"154100.5"},
              {"guid":"g4","title":"正常4","duration":4000},
              {"guid":"g5","title":"正常5","duration":5000}
            ]
        """.trimIndent()
        total = 5

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        // v2.39.2 及以前：这里会是 0（整页被一条脏数据拖垮）
        // v2.39.3：T1 的 longOf 已能容错，5 条全部解析成功
        assertEquals("一条脏数据不得导致整页归零", 5, songs.size)
        assertEquals(listOf("正常1", "正常2", "脏数据", "正常4", "正常5"), songs.map { it.title })
    }

    @Test
    fun `invalid primitive still keeps other tracks when parse cannot recover`() = runBlocking {
        // "2:33" 这类时间戳格式：longOf 解析不出 → 回退 0，但**不抛异常**，条目仍保留
        trackListJson = """
            [
              {"guid":"g1","title":"A","duration":1000},
              {"guid":"g2","title":"B","duration":"2:33"},
              {"guid":"g3","title":"C","duration":3000}
            ]
        """.trimIndent()
        total = 3

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        assertEquals("无法解析的 duration 只影响该条的时长，不得丢条目", 3, songs.size)
        assertEquals(1000L, songs[0].durationMs)
        assertEquals("无法解析时回退 0", 0L, songs[1].durationMs)
        assertEquals(3000L, songs[2].durationMs)
    }

    // ── T1：字段类型容错 ────────────────────────────────────────────────

    @Test
    fun `duration as numeric string is parsed`() = runBlocking {
        trackListJson = """[{"guid":"g1","title":"A","duration":"154100"}]"""
        total = 1

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        assertEquals(1, songs.size)
        assertEquals(154100L, songs[0].durationMs)
    }

    @Test
    fun `duration as decimal string is truncated not dropped`() = runBlocking {
        trackListJson = """[{"guid":"g1","title":"A","duration":"154100.9"}]"""
        total = 1

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        assertEquals("小数字符串必须能解析，不能丢条目", 1, songs.size)
        assertEquals(154100L, songs[0].durationMs)
    }

    @Test
    fun `album as string must not crash and track is still listed`() = runBlocking {
        // 服务端把 album 发成字符串（而非对象）：旧代码 getAsJsonObject 会抛异常
        trackListJson = """
            [
              {"guid":"g1","title":"A","duration":1000,"album":"某专辑"},
              {"guid":"g2","title":"B","duration":2000}
            ]
        """.trimIndent()
        total = 2

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        assertEquals("album 类型异常不得导致丢条目", 2, songs.size)
        assertEquals("A", songs[0].title)
        assertEquals("album 非对象时降级为空", "", songs[0].album)
    }

    @Test
    fun `artists as single object must not crash`() = runBlocking {
        // 服务端把 artists 发成单个对象（而非数组）
        trackListJson = """
            [
              {"guid":"g1","title":"A","duration":1000,"artists":{"guid":"a1","name":"歌手"}},
              {"guid":"g2","title":"B","duration":2000}
            ]
        """.trimIndent()
        total = 2

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        assertEquals("artists 类型异常不得导致丢条目", 2, songs.size)
        assertEquals("artists 非数组时降级为空串", "", songs[0].artist)
    }

    @Test
    fun `audioSpec duration is used as fallback`() = runBlocking {
        trackListJson = """
            [{"guid":"g1","title":"A","audioSpec":{"duration":98765}}]
        """.trimIndent()
        total = 1

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        assertEquals(1, songs.size)
        assertEquals("顶层 duration 缺失时应回退 audioSpec.duration", 98765L, songs[0].durationMs)
    }

    // ── 硬约束：guid / title 缺失仍然丢弃（这是业务语义，不是 bug）──────

    @Test
    fun `track without guid or title is skipped`() = runBlocking {
        trackListJson = """
            [
              {"title":"无 guid"},
              {"guid":"g2"},
              {"guid":"g3","title":"正常","duration":3000}
            ]
        """.trimIndent()
        total = 3

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        assertEquals("缺 guid/title 的条目应被跳过", 1, songs.size)
        assertEquals("正常", songs[0].title)
    }

    // ── 大页完整性：模拟真实规模 ────────────────────────────────────────

    @Test
    fun `full page with mixed dirty data keeps all valid tracks`() = runBlocking {
        // 构造 200 条，其中每 10 条掺一条脏数据（模拟 1541 首库里零星脏条目）
        val entries = (0 until 200).map { i ->
            when {
                i % 10 == 3 -> """{"guid":"g$i","title":"脏$i","duration":"154100.5"}"""
                i % 10 == 7 -> """{"guid":"g$i","title":"脏$i","artists":{"guid":"a","name":"x"},"duration":1000}"""
                else -> """{"guid":"g$i","title":"正常$i","duration":${i * 1000}}"""
            }
        }
        trackListJson = entries.joinToString(",", "[", "]")
        total = 200

        val adapter = connectedAdapter()
        val songs = adapter.getSongs(500, 0)
        adapter.close()

        assertEquals("整页 200 条一条都不能丢", 200, songs.size)
        assertEquals("正常0", songs[0].title)
        assertEquals("顺序必须保持", (0 until 200).map { "g$it" }, songs.map { it.id.removePrefix("feiniu_") })
    }
}
