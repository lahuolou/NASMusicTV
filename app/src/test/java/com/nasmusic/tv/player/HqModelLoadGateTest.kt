package com.nasmusic.tv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 高质量分离「建会话前系统内存门槛」。
 *
 * 真机依据（2026-10-21 诊断）：整机剩余 201MB 时 `createSession()` 撑了 16.9s 后抛
 * `std::bad_alloc`；同轮实测建会话常驻 ≈459MB（§10.246）⇒ 门槛取 700MB（≈1.5×）。
 *
 * 门槛只认系统可用内存：ORT 的权重分配走 native malloc，JVM 堆口径（旧 `separate()`
 * 里那道 150MB）在建会话阶段根本够不着。
 */
class HqModelLoadGateTest {

    @Test
    fun `真机实测的 201MB 必须拒绝`() {
        assertTrue(isHqModelLoadMemoryInsufficient(201))
    }

    @Test
    fun `边界 699 拒绝 700 放行`() {
        assertTrue(isHqModelLoadMemoryInsufficient(699))
        assertFalse(isHqModelLoadMemoryInsufficient(700))
    }

    @Test
    fun `内存充足时放行`() {
        assertFalse(isHqModelLoadMemoryInsufficient(2048))
    }

    @Test
    fun `读不到内存时放行不误伤`() {
        assertFalse(isHqModelLoadMemoryInsufficient(null))
    }
}
