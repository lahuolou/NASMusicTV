package com.nasmusic.tv.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 系统文件选择器（SAF）与所选文件的读取 —— 「从本地文件导入」的统一入口。
 *
 * ## 为什么要有它（v2.39.4）
 * 扫码传输把本 app 变成服务端给手机浏览器上传，但**手机上一打开浏览器，app 就进后台**，
 * Android 12+ 的 cgroup freezer 会把整个进程冻结（`cgroup.freeze=1`），监听中的
 * `ServerSocket` 不再 accept ⇒ 浏览器只会停在 SYN_SENT 并抛 `Failed to fetch`
 * （真机取证见 `docs/technical-overview.md` §10 的备份传输根因条目）。
 * 所以凡是「往 app 里送一个文件」的功能，都补一条**不经过网络**的本地选择入口。
 *
 * ## ⛔ 不做设备能力探测
 * `resolveActivity()` 在包可见性（API 30+）下要靠 `<queries>` 精确镜像目标 filter 的
 * category 与 mimeType 才可能命中；v2.39.3 就是这样误判成「本机没有选择器」，把整行
 * 藏掉了 —— 一个探测失败就无声消失的入口，比点了报错更难排查。
 * 现在**所有设备一律显示**，起不来由调用方捕获 `ActivityNotFoundException` 并把原因
 * 显示给用户（电视上的 Google SAF 桩包点了会弹系统自己的提示，同理由用户看到）。
 */
object LocalFilePicker {

    private const val TAG = "LocalFilePicker"

    /** 内置存储的文档提供者（系统文件选择器列目录用的 authority） */
    private const val EXTERNAL_STORAGE_DOC_AUTHORITY = "com.android.externalstorage.documents"

    /** 导出类文件统一落在公共下载目录的这个子目录下（与 `BackupFileUtils` 同一约定） */
    private val initialDocumentId: String
        get() = "primary:${Environment.DIRECTORY_DOWNLOADS}/${BackupFileUtils.BACKUP_SUBDIR}"

    /** 备份 json：经 MTP 或文件管理器拷入的文件常被标成 octet-stream，只填 json 会让它变灰不可选 */
    val BACKUP_MIME_TYPES = listOf("application/json", "text/plain", "application/octet-stream")

    /** 歌单：m3u / m3u8 各家 provider 报的类型不统一，同样放开 octet-stream */
    val PLAYLIST_MIME_TYPES = listOf(
        "audio/x-mpegurl",
        "application/vnd.apple.mpegurl",
        "text/plain",
        "application/octet-stream"
    )

    /** 分离模型 `.onnx`：provider 基本一律报 octet-stream，干脆不过滤 */
    val ANY_MIME_TYPES = listOf("*/*")

    /** 提供方报告的大小超过上限 —— 调用方据此给出「不像是要找的文件」的提示 */
    class TooLargeException(val bytes: Long) : Exception("file is $bytes bytes")

    /**
     * 选择器的初始目录 = 导出备份用的公共目录 `Download/NASMusic`。
     *
     * 文档 id 是「存储根:相对路径」（`primary:Download/NASMusic`），整段按 URI 段编码后
     * 挂在 `/document/` 下，这是 DocumentsUI 认的规范形态：
     * `content://com.android.externalstorage.documents/document/primary%3ADownload%2FNASMusic`。
     * ⛔ 这里**不用** `DocumentsContract.buildDocumentUri`：它走 `appendEncodedPath`，
     * 会把 `primary:` 前缀丢掉，得到一个提供者可读不出文档的残缺 Uri（单测抓到过）。
     *
     * `EXTRA_INITIAL_URI` 自 API 26 起生效，更低版本返回 null（选择器用自己的默认页，
     * 不会因为少传一个 extra 而失败）；个别 ROM 不认这个 id 时同样只是回落默认页。
     */
    fun initialUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri.parse(
                "content://$EXTERNAL_STORAGE_DOC_AUTHORITY/document/" + Uri.encode(initialDocumentId)
            )
        } else {
            null
        }

    /** 构造「选一个本地文件」的 SAF 意图（[mimeTypes] 决定选择器里哪些文件变灰） */
    fun openIntent(mimeTypes: List<String> = ANY_MIME_TYPES): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            initialUri()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            // 可持久化读授权：SAF 单次授权只活到本次 Activity 结束，而备份文件是要
            // **长期列在下方列表里、下次还能选**的（重启后仍依赖这条授权）。
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        }

    /**
     * 把选择器给的读授权转成**跨重启**授权。
     *
     * ⛔ 必须在 `onActivityResult` 里、调用方还持有该 Uri 时同步调用（异步协程里补做会被
     * 系统当成「授权已过期」而拒绝）。失败只返回 false，调用方继续走「提示 + 不登记」。
     */
    fun persistRead(context: Context, uri: Uri): Boolean =
        try {
            context.contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "takePersistableUriPermission failed", e)
            false
        }

    /** 所选文件的修改时间（**毫秒** epoch，与 `BackupFileUtils.BackupFile.lastModified` 同一单位）；提供方不报或不是文档 Uri 时返回 0 */
    fun lastModified(context: Context, uri: Uri): Long =
        if (!DocumentsContract.isDocumentUri(context, uri)) 0L else
            try {
                val raw = context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
                } ?: 0L
                // 契约写的是毫秒，但个别 provider（含部分 ROM 的下载提供器）给的是秒；
                // 混进列表会把该条排到最末尾，按数量级归一化掉。
                if (raw in 1..9_999_999_999L) raw * 1000L else raw
            } catch (e: Exception) {
                AppLog.e(TAG, "lastModified failed", e)
                0L
            }

    /** 所选文件的显示名（拿不到就退回末段路径，仅供提示文案用） */
    fun displayName(context: Context, uri: Uri): String =
        try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) ?: uri.lastPathSegment ?: uri.toString()
                    else uri.lastPathSegment ?: uri.toString()
                } ?: (uri.lastPathSegment ?: uri.toString())
        } catch (e: Exception) {
            AppLog.e(TAG, "displayName failed", e)
            uri.lastPathSegment ?: uri.toString()
        }

    /** 文件字节数；提供方不报大小（或查询失败）时返回 -1，调用方按「未知」放行 */
    fun sizeOf(context: Context, uri: Uri): Long =
        try {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
                } ?: -1L
        } catch (e: Exception) {
            AppLog.e(TAG, "sizeOf failed", e)
            -1L
        }

    /**
     * 整份读入文本（备份 json、歌单这类小文件）。
     *
     * 选择器允许挑到任意文件（视频、压缩包同样可能是 octet-stream），
     * 不设上限直接 `readText()` 会 OOM ⇒ 先按提供方报告的大小拦一道，
     * 超过 [maxBytes] 抛 [TooLargeException]。
     */
    fun readText(context: Context, uri: Uri, maxBytes: Long): String {
        val size = sizeOf(context, uri)
        if (size > maxBytes) throw TooLargeException(size)
        return context.contentResolver.openInputStream(uri)?.use {
            it.reader(Charsets.UTF_8).use { r -> r.readText() }
        } ?: throw IOException("openInputStream returned null")
    }

    /**
     * 流式复制到目标文件（分离模型这类上百 MB、不能整份进内存的文件）。
     *
     * ⚠️ 调用方务必先复制**再校验**（校验通过才替换正式文件），失败路径要把 [target] 删掉。
     *
     * @return 实际写入的字节数
     */
    fun copyToFile(context: Context, uri: Uri, target: File): Long {
        target.parentFile?.mkdirs()
        var written = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("openInputStream returned null")
        input.use { src ->
            FileOutputStream(target).use { dst ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    dst.write(buf, 0, n)
                    written += n
                }
                dst.flush()
            }
        }
        return written
    }
}
