package com.nasmusic.tv.net

import fi.iki.elonen.NanoHTTPD

/**
 * 内嵌 server 的请求体读取器（v2.39.4：修复"上传失败: Failed to fetch"）。
 *
 * 为什么需要它：NanoHTTPD 的 `HTTPSession.execute()` 只读请求头，**从不解析 body**
 * （已用 javap 反汇编 2.3.1 核实），所以 [NanoHTTPD.IHTTPSession.getInputStream] 恒定位在
 * body 起点。此前各 server 只按 `Content-Length` 定长读，遇到另外两类合法请求就一行字节都不读
 * 便返回响应：
 * - `Transfer-Encoding: chunked`（Chromium 系浏览器用 `fetch` + 无名 Blob 时常常走这条，
 *   因为 JS 侧拿不到精确字节数就不发 Content-Length）；
 * - 超过体量上限的请求。
 * 响应里却仍是 `Connection: keep-alive`，于是没被消费的 body 留在 socket 上，被解析成
 * **下一个请求** —— 手机浏览器连接池复用同一连接，下一跳直接收到
 * `400 Bad Request`，`fetch` 的 promise 以 `Failed to fetch` 拒绝，真实原因被彻底吞掉。
 * 因此本读取器的契约是：**任何返回路径都必须让 socket 处于干净的请求边界**
 * ——要么读完，要么由调用方用 [noKeepAlive] 立即断连。
 */
internal object HttpRequestBody {

    /** chunked 块头（`<hex-size>[;ext]`）单行上限；正常块头 < 20 字节 */
    private const val MAX_CHUNK_HEADER_BYTES = 128

    /** 读取结果。除 [Read] 外调用方都必须断连。 */
    sealed interface Result {
        /** 请求体已完整消费（可能为空数组）——连接干净，可 keep-alive */
        data class Read(val bytes: ByteArray) : Result

        /** 声明或实发体量超过 [maxBytes]——body 未消费，必须断连 */
        data object OverLimit : Result

        /** 请求体畸形 / 对端提前关闭——连接状态不可信，必须断连 */
        data class Malformed(val detail: String) : Result
    }

    /**
     * 消费整个请求体。
     *
     * `Transfer-Encoding: chunked` 优先于 `Content-Length`（RFC 7230 §3.3.3 的解析顺序，
     * 也是防请求走私的要求）；两者皆无则视为无请求体。
     */
    fun read(headers: Map<String, String>, input: java.io.InputStream, maxBytes: Long): Result {
        val transferEncoding = headers["transfer-encoding"] ?: ""
        val contentLength = headers["content-length"]?.toLongOrNull()

        return if (transferEncoding.contains("chunked", ignoreCase = true)) {
            readChunked(input, maxBytes)
        } else if (contentLength == null || contentLength <= 0L) {
            Result.Read(ByteArray(0))
        } else {
            readFixed(input, contentLength, maxBytes)
        }
    }

    /** 按 Content-Length 定长读取（keep-alive 上绝不能读到 -1，否则会阻塞至 SO_TIMEOUT） */
    private fun readFixed(input: java.io.InputStream, contentLength: Long, maxBytes: Long): Result {
        if (contentLength > maxBytes) return Result.OverLimit
        val buffer = java.io.ByteArrayOutputStream(minOf(contentLength, 64L * 1024).toInt())
        val chunk = ByteArray(16 * 1024)
        var remaining = contentLength
        while (remaining > 0) {
            val read = input.read(chunk, 0, minOf(chunk.size.toLong(), remaining).toInt())
            if (read < 0) {
                return Result.Malformed("对端提前关闭：期望 $contentLength 字节，只读到 ${contentLength - remaining}")
            }
            buffer.write(chunk, 0, read)
            remaining -= read
        }
        return Result.Read(buffer.toByteArray())
    }

    /**
     * 解码 `chunked` 分块传输编码：`<hex-size>\r\n<payload>\r\n` 重复，`0\r\n` 后跟可选
     * trailer 行与结束空行。全程不把超限的 payload 读进内存。
     */
    private fun readChunked(input: java.io.InputStream, maxBytes: Long): Result {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            // readLine 返回 null 有两种含义（EOF / 行长超限），两者都是"连接已不可信"
            val headerLine = readLine(input, MAX_CHUNK_HEADER_BYTES)
                ?: return Result.Malformed("chunked：块头缺失或超过 $MAX_CHUNK_HEADER_BYTES 字节")
            val sizeText = headerLine.substringBefore(';').trim()
            val size = sizeText.toLongOrNull(radix = 16)
                ?: return Result.Malformed("chunked：块头无法解析（$sizeText）")
            if (size == 0L) {
                // 消费 trailer 行直到结束空行
                while (true) {
                    val trailer = readLine(input, MAX_CHUNK_HEADER_BYTES)
                        ?: return Result.Malformed("chunked：trailer 未正常结束")
                    if (trailer.isEmpty()) break
                }
                return Result.Read(buffer.toByteArray())
            }
            if (size > maxBytes - buffer.size()) return Result.OverLimit
            // 块头声明的长度不足信，逐块扣减读取，绝不按 size 预分配
            val block = ByteArray(16 * 1024)
            var remaining = size
            while (remaining > 0) {
                val read = input.read(block, 0, minOf(block.size.toLong(), remaining).toInt())
                if (read < 0) return Result.Malformed("chunked：块体（$size 字节）处遇到 EOF")
                buffer.write(block, 0, read)
                remaining -= read
            }
            // 块体后必须跟 CRLF，丢弃（两个字节都要读，否则短路求值会让流失去同步）
            val cr = input.read()
            val lf = input.read()
            if (cr != '\r'.code || lf != '\n'.code) {
                return Result.Malformed("chunked：块体后缺少 CRLF")
            }
        }
    }

    /** 读到 CRLF 为止的一行（不含 CRLF）；EOF 且无内容返回 null；超 [maxBytes] 返回 null 由调用方判畸形 */
    private fun readLine(input: java.io.InputStream, maxBytes: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\r'.code) {
                val next = input.read()
                if (next == '\n'.code) return sb.toString()
                sb.append(b.toChar())
                if (next != -1) sb.append(next.toChar())
            } else {
                sb.append(b.toChar())
            }
            if (sb.length > maxBytes) {
                // 行长超限即已破坏连接同步，无法安全继续
                sb.setLength(0)
                return null
            }
        }
    }
}

/**
 * 读取 session 的请求体（[HttpRequestBody.read] 的会话侧入口）。
 *
 * 依赖 NanoHTTPD 的事实：`HTTPSession.execute()` 只解析请求头、**从不消费 body**，
 * 因此 [NanoHTTPD.IHTTPSession.getInputStream] 恒定位在 body 起点。
 */
internal fun NanoHTTPD.IHTTPSession.readRequestBody(maxBytes: Long): HttpRequestBody.Result =
    HttpRequestBody.read(headers, inputStream, maxBytes)

/**
 * 标记响应必须关闭连接。
 *
 * 任何**没有把请求体消费干净**的响应（鉴权拒绝、超限、畸形）都必须调用它，
 * 否则残留字节会被解析成下一个请求（详见 [HttpRequestBody] 头注）。
 */
internal fun NanoHTTPD.Response.noKeepAlive(): NanoHTTPD.Response = apply { closeConnection(true) }
