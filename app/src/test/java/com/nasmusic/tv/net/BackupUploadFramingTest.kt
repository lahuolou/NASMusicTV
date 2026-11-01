package com.nasmusic.tv.net

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.US_ASCII

/**
 * 内嵌 server 的**连接帧**回归测试（真 NanoHTTPD + 真回环 socket）。
 *
 * 钉住的缺陷：上传被拒绝（未授权 / 超限）或走 `chunked` 时，旧实现一行请求体都不读，
 * 响应却仍是 `Connection: keep-alive` —— 残留字节被 NanoHTTPD 解析成**下一个请求**，
 * 浏览器连接池复用时拿到 `400 Bad Request`，`fetch` 只剩一句 "Failed to fetch"，
 * 用户和日志都看不到真因。
 *
 * 断言的是 socket 层面的硬性质，不是文案：
 * 1. 拒绝路径 → 连接必须走到 EOF（服务端已断，不可能污染下一请求）；
 * 2. 正常路径 → 同一连接上的第二个请求必须拿到它自己的响应。
 *
 * ⚠️ 本模块单测**未开启** `testOptions.unitTests.isIncludeAndroidResources`
 * （见 [DeviceDiagnosticsTest] 头注），`context.getString()` 在工作线程上必抛
 * `Resources$NotFoundException`，所以超限 / chunked 两条路径用 [FramingProbeServer]
 * 复现同样的处理契约（同一套 [readRequestBody] + [noKeepAlive]），而不是直接驱动
 * [BackupTransferServer]；未授权 403 那条不取资源，走真 server。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupUploadFramingTest {

    /** 与生产一致的处理契约，但消息用字面量（绕开单测拿不到 Android 资源的限制） */
    private class FramingProbeServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response = when (session.uri) {
            "/upload" -> when (val body = session.readRequestBody(1024)) {
                is HttpRequestBody.Result.Read -> plain("got:${String(body.bytes, Charsets.UTF_8)}")
                is HttpRequestBody.Result.OverLimit -> plain("too-large").noKeepAlive()
                is HttpRequestBody.Result.Malformed -> plain("malformed").noKeepAlive()
            }
            "/reject-oversize" -> when (session.readRequestBody(1024)) {
                is HttpRequestBody.Result.OverLimit -> plain("too-large").noKeepAlive()
                else -> plain("unexpected")
            }
            "/next" -> plain("second-request-ok")
            else -> plain("not-found")
        }

        private fun plain(text: String): Response =
            newFixedLengthResponse(Response.Status.OK, "text/plain; charset=UTF-8", text)
    }

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun connect(port: Int): Socket =
        Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 5000); soTimeout = 5000 }

    private fun Socket.send(text: String) {
        getOutputStream().run {
            write(text.toByteArray(US_ASCII))
            flush()
        }
    }

    /** 读一个完整响应（状态行 + 头 + 按 Content-Length 的正文） */
    private fun readResponse(input: InputStream): String {
        val head = StringBuilder()
        var blankRuns = 0
        while (head.length < 64 * 1024) {
            val b = input.read()
            if (b == -1) break
            val c = b.toChar()
            head.append(c)
            blankRuns = if (c == '\r' || c == '\n') blankRuns + 1 else 0
            if (blankRuns >= 4) break // CRLFCRLF
        }
        val headers = head.toString()
        val body = StringBuilder()
        val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
            .find(headers)?.groupValues?.get(1)?.toIntOrNull()
        if (length != null) {
            var remaining = length
            while (remaining > 0) {
                val b = input.read()
                if (b == -1) break
                body.append(b.toChar())
                remaining--
            }
        }
        return headers + body
    }

    /** 读到连接关闭；返回残留字节（非空即说明有内容会被当成下一个请求） */
    private fun drainUntilEof(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    @Test
    fun `chunked 上传读干净后同连接的下一个请求不再被污染`() {
        val port = freePort()
        val server = FramingProbeServer(port)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            connect(port).use { s ->
                // 浏览器用 fetch + 无名 Blob 时走 chunked（不带 Content-Length）
                s.send(
                    "POST /upload HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
                )
                val first = readResponse(s.getInputStream())
                assertTrue("chunked 应被完整解码, 实际: $first", first.contains("got:hello world"))

                // 同一连接复用：修复前这里拿到的是 400 Bad Request（残留 body 被当成请求行）
                s.send("GET /next HTTP/1.1\r\nHost: x\r\n\r\n")
                val second = readResponse(s.getInputStream())
                assertTrue(
                    "同连接的下一请求必须拿到自己的响应, 实际首行: ${second.lineSequence().first()}",
                    second.startsWith("HTTP/1.1 200") && second.contains("second-request-ok")
                )
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `声明超限的拒绝必须断连而不把 body 留给下一请求`() {
        val port = freePort()
        val server = FramingProbeServer(port)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            connect(port).use { s ->
                // 只发表头声明 1MB，一个字节 body 都不发：读不干净就必须断连
                s.send("POST /reject-oversize HTTP/1.1\r\nHost: x\r\nContent-Length: 1048576\r\n\r\n")
                val resp = readResponse(s.getInputStream())
                assertTrue("应返回超限提示: $resp", resp.contains("too-large"))
                assertEquals("拒绝后连接必须走到 EOF", "", drainUntilEof(s.getInputStream()))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `未授权的带体 POST 必须断连`() {
        val port = freePort()
        val server = BackupTransferServer(app, onRestore = { true }, onBackupChanged = {}, port = port)
        assertTrue("测试服务器启动失败（端口 $port）", server.start())
        try {
            connect(port).use { s ->
                // 不带 token：serve() 第一行就返回 403，此时 body 一个字节都没读
                s.send("POST /api/upload HTTP/1.1\r\nHost: x\r\nContent-Length: 11\r\n\r\nhello world")
                val resp = readResponse(s.getInputStream())
                assertTrue(
                    "应返回 403, 实际首行: ${resp.lineSequence().first()}",
                    resp.startsWith("HTTP/1.1 403")
                )
                assertEquals("403 后连接必须走到 EOF", "", drainUntilEof(s.getInputStream()))
            }
        } finally {
            server.stop()
        }
    }
}
