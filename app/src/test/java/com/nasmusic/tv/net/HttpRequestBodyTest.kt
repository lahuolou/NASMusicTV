package com.nasmusic.tv.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * [HttpRequestBody] 回归测试。
 *
 * 钉住两件事：① `chunked` 请求体必须被**完整解码并消费**（此前只按 Content-Length 读，
 * 浏览器走 chunked 时服务端一行字节都不读就返回，body 留在 keep-alive 连接上污染下一请求，
 * 手机侧只剩 "Failed to fetch"）；② 任何未能读干净的路径都要有明确结果，交由调用方断连。
 */
class HttpRequestBodyTest {

    private fun read(headers: Map<String, String>, body: String, maxBytes: Long = 1L shl 20) =
        HttpRequestBody.read(headers, ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)), maxBytes)

    private fun bytesOf(result: HttpRequestBody.Result): String {
        assertTrue("期望 Read，实际 $result", result is HttpRequestBody.Result.Read)
        return String((result as HttpRequestBody.Result.Read).bytes, Charsets.UTF_8)
    }

    @Test
    fun `定长请求体按 Content-Length 精确读完`() {
        val r = read(mapOf("content-length" to "11"), "hello worl")
        // 少读即畸形：不允许把「对端提前关闭」当成一次正常上传
        assertTrue(r is HttpRequestBody.Result.Malformed)
        assertEquals(
            "hello world",
            bytesOf(read(mapOf("content-length" to "11"), "hello worldEXTRA-ON-NEXT-REQUEST"))
        )
    }

    @Test
    fun `chunked 多块请求体解码为完整字节`() {
        val r = read(
            mapOf("transfer-encoding" to "chunked"),
            "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
        )
        assertEquals("hello world", bytesOf(r))
    }

    @Test
    fun `chunked 大小行可带扩展且十六进制可用大写`() {
        val r = read(
            mapOf("transfer-encoding" to "chunked"),
            "A;ext=1\r\n0123456789\r\n0\r\n\r\n"
        )
        assertEquals("0123456789", bytesOf(r))
    }

    @Test
    fun `chunked trailer 行被消费不污染连接`() {
        val r = read(
            mapOf("transfer-encoding" to "chunked"),
            "3\r\nabc\r\n0\r\nX-Checksum: zz\r\n\r\n"
        )
        assertEquals("abc", bytesOf(r))
    }

    @Test
    fun `transfer-encoding 优先于 content-length`() {
        // 两者同时存在时按 chunked 解析（RFC 7230 §3.3.3），否则会把下一请求的头当 body 吃掉
        val r = read(
            mapOf("transfer-encoding" to "chunked", "content-length" to "99"),
            "2\r\nhi\r\n0\r\n\r\n"
        )
        assertEquals("hi", bytesOf(r))
    }

    @Test
    fun `无 Content-Length 且非 chunked 视为无请求体`() {
        assertEquals("", bytesOf(read(mapOf("content-type" to "application/json"), "")))
    }

    @Test
    fun `声明超限直接拒绝且不读入内存`() {
        val r = read(mapOf("content-length" to "999999999"), "")
        assertEquals(HttpRequestBody.Result.OverLimit, r)
    }

    @Test
    fun `chunked 单块声明超限同样拒绝`() {
        // 块头声明 16 字节（10 hex），上限 8 —— 必须拒绝且不把块体读进内存
        val r = read(mapOf("transfer-encoding" to "chunked"), "10\r\naaaaaaaaaaaaaaaa\r\n0\r\n\r\n", maxBytes = 8L)
        assertEquals(HttpRequestBody.Result.OverLimit, r)
    }

    @Test
    fun `块头无法解析判为畸形`() {
        val r = read(mapOf("transfer-encoding" to "chunked"), "ZZ\r\n")
        assertTrue(r is HttpRequestBody.Result.Malformed)
    }

    @Test
    fun `chunked 块体中途 EOF 判为畸形`() {
        val r = read(mapOf("transfer-encoding" to "chunked"), "10\r\nabc")
        assertTrue(r is HttpRequestBody.Result.Malformed)
    }
}
