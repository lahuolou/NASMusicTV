package com.nasmusic.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `SongsPagingState.resolveError` 回归网（v2.39.3，T7b）。
 *
 * ## 为什么需要这个测试
 * v2.39.2 及以前，服务端返回 `code != 0` 时链路是这样断的：
 * `dataOf` 记下 `lastFailure` → `fetchAllPages` 静默 break → `getSongs` 返回空列表
 * → ViewModel 的 `catch` 不触发 → **UI 只显示「暂无歌曲」**。
 * 用户完全不知道服务端已经拒绝请求，只能靠 logcat 才能发现。
 *
 * v2.39.3 把失败原因带进 `SongsPagingState.error`。判定逻辑集中在
 * [SongsPagingState.resolveError]（纯函数，无 Android 依赖，因此可以在这里全覆盖）。
 *
 * ## 为什么必须是「诊断优先」而不是「totalCount 优先」
 * 服务端返回 `code != 0` 时，`getSongs` 与 `getSongsTotalCount` 会**同时**失败
 * （都经 `dataOf` → null → 0），`totalCount` 恒为 0。若用 `totalCount > 0`
 * 判定，**恰好漏掉最该提示的场景**。下面 `server rejects with zero total`
 * 就是守这一条。
 */
class SongsPagingStateResolveErrorTest {

    private val fallback = "歌曲数据解析失败（服务端字段不兼容）"

    private fun resolve(
        batchSize: Int,
        totalCount: Int,
        diagnostic: String = ""
    ) = SongsPagingState.resolveError(batchSize, totalCount, diagnostic, fallback)

    // ── 分支 1：本页有数据 → 永不报错 ──────────────────────────────────

    @Test
    fun `non-empty batch never reports error`() {
        assertNull("有数据就不该报错（即便有旧诊断）", resolve(1, 1541, "旧错误"))
        assertNull("满页也不报错", resolve(500, 1541))
    }

    // ── 分支 2：空页 + 有诊断 → 展示具体原因（含 total=0 的关键场景）────

    @Test
    fun `server rejects with zero total still surfaces diagnostic`() {
        // 这是最容易写错的分支：code!=0 时 total 也是 0，
        // 若判据写成 `totalCount > 0` 就会静默通过 → 用户仍看到「暂无歌曲」。
        val result = resolve(
            batchSize = 0,
            totalCount = 0,
            diagnostic = "服务端返回错误：code=100005 sort 参数不合法"
        )
        assertEquals("服务端明确报错时必须展示该原因", "服务端返回错误：code=100005 sort 参数不合法", result)
    }

    @Test
    fun `network error diagnostic is surfaced`() {
        val result = resolve(0, 0, "无法连接服务器（ConnectException），请检查网络与地址")
        assertEquals("无法连接服务器（ConnectException），请检查网络与地址", result)
    }

    @Test
    fun `token expired diagnostic is surfaced`() {
        assertEquals("登录令牌已失效，请重新连接", resolve(0, 0, "登录令牌已失效，请重新连接"))
    }

    // ── 分支 3：空页 + 无诊断 + total>0 → 兜底「解析失败」───────────────

    @Test
    fun `empty page with positive total falls back to parse failure`() {
        // 服务端说有 1541 首，却一条都解析不出来，且没有明确诊断
        // → 典型「所有条目字段都不兼容」的静默失败
        assertEquals(fallback, resolve(0, 1541, ""))
        assertEquals(fallback, resolve(0, 1, ""))
    }

    // ── 分支 4：空页 + 无诊断 + total==0 → 真没歌，不误报 ──────────────

    @Test
    fun `truly empty library reports nothing`() {
        assertNull("服务端确实没歌时不得误报", resolve(0, 0, ""))
    }

    @Test
    fun `blank diagnostic is treated as absent`() {
        // 防御：诊断是空白字符串（不是 null）时也必须走分支 3/4，不能被 isNotBlank 骗过
        assertNull("空白诊断 + total=0 → 不报错", resolve(0, 0, "   "))
        assertEquals("空白诊断 + total>0 → 走兜底", fallback, resolve(0, 1541, "   "))
    }

    // ── 边界：诊断优先级高于 total 判定 ────────────────────────────────

    @Test
    fun `diagnostic takes precedence over total heuristic`() {
        // total>0 且同时有诊断 → 应展示**具体诊断**而非通用兜底
        val specific = "服务端返回错误：code=100005"
        assertEquals(specific, resolve(0, 1541, specific))
    }

    // ── 数据类本身：error 字段的 copy / 透传语义 ──────────────────────

    @Test
    fun `error survives copy of other fields`() {
        val original = SongsPagingState(songs = emptyList(), totalCount = 1541, error = "出错了")
        val copied = original.copy(currentPage = 3, isLoading = true)

        assertEquals("copy 不应丢掉 error", "出错了", copied.error)
        assertEquals(3, copied.currentPage)
        assertEquals(1541, copied.totalCount)
    }

    @Test
    fun `default state has no error`() {
        assertNull(SongsPagingState().error)
    }
}
