package com.nasmusic.tv.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.ImportedBackupRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件读写工具
 *
 * 备份文件统一存放在 Downloads/NASMusic/ 目录（TV 用户可经文件管理器访问）：
 * - API 29+：MediaStore.Downloads（无需权限）
 * - API < 29：主备份写应用内部存储 filesDir/backups/（/data 真闪存，断电不丢），
 *   另写一份到外部存储公共 Downloads 目录供文件管理器访问
 *   （注意：部分电视 ROM 的公共存储是 RAM-backed，断电即丢失，故内部存储才是可靠主备份）
 *   ⚠️ v2.38.3 权限瘦身后不再声明写存储权限：API 23–28 的公共副本因运行时权限
 *   未授予本来就写不进；API 22 及以下也会因权限缺失**静默失败**（2026-10-06 起，
 *   方案 §2.2）。两档的外部副本都只是便利品，**主备份不受影响**——恢复走内部存储。
 *
 * 文件名格式：NASMusic_backup_yyyyMMdd_HHmmss.json
 */
object BackupFileUtils {

    private const val TAG = "BackupFileUtils"
    /** 备份子目录名（公共 Downloads 下的 `Download/NASMusic`）；`LocalFilePicker` 的初始目录共用此值 */
    const val BACKUP_SUBDIR = "NASMusic"
    private const val FILE_PREFIX = "NASMusic_backup_"
    private const val FILE_SUFFIX = ".json"

    /** 备份 json 的量级上限：正常备份只有几十 KB，超过这个数的一定不是备份文件 */
    private const val MAX_BACKUP_BYTES = 10L * 1024 * 1024

    /** API < 29：应用内部存储备份目录（/data 真闪存，断电不丢） */
    private fun internalBackupDir(context: Context): File =
        File(context.filesDir, BACKUP_SUBDIR)

    /** API < 29：公共 Downloads 备份目录（普通 ROM 上文件管理器可访问） */
    private fun publicBackupDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            BACKUP_SUBDIR
        )

    data class BackupFile(
        val displayName: String,
        val uri: Uri,
        val lastModified: Long,
        /**
         * true = 这条来自「用户在选择器里挑过的文件」登记（`ImportedBackupRecord`），
         * 不是本 app 导出的。区别在于**权限来源**：导出项归本 app 所有，MediaStore 直接可删；
         * 登记项靠 SAF 持久授权，删除要走 [DocumentsContract.deleteDocument]。
         */
        val localImported: Boolean = false,
    )

    /**
     * 生成备份文件名（含时间戳）
     */
    fun generateFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "$FILE_PREFIX$ts$FILE_SUFFIX"
    }

    /**
     * 导出备份 JSON 到 Downloads/NASMusic/
     *
     * @return 成功返回备份文件显示名；失败返回错误信息
     */
    fun export(context: Context, json: String): Result<String> {
        return try {
            val fileName = generateFileName()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBDIR")
                }
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val uri = context.contentResolver.insert(collection, values)
                    ?: return Result.failure(Exception(context.getString(R.string.backup_util_create_failed)))
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: return Result.failure(Exception(context.getString(R.string.backup_util_write_failed)))
                Result.success(fileName)
            } else {
                // 主备份：应用内部存储（/data 真闪存，断电不丢）
                val internalDir = internalBackupDir(context)
                if (!internalDir.exists() && !internalDir.mkdirs()) {
                    return Result.failure(Exception(context.getString(R.string.backup_util_mkdir_failed)))
                }
                File(internalDir, fileName).writeText(json, Charsets.UTF_8)
                // 辅助副本：公共 Downloads（普通 ROM 上文件管理器可访问；RAM 盘 ROM 上尽力而为）
                try {
                    val publicDir = publicBackupDir()
                    if (publicDir.exists() || publicDir.mkdirs()) {
                        File(publicDir, fileName).writeText(json, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    AppLog.w(TAG, "public backup copy failed", e)
                }
                Result.success(fileName)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "export failed", e)
            Result.failure(e)
        }
    }

    /**
     * 列出「本机可用的备份文件」= app 自己导出的（MediaStore 可见）+ 用户在选择器里挑过并
     * 恢复成功的（[ImportedBackupRecord] 登记），按修改时间倒序合并。
     *
     * ⚠️ 第二类**必须存在**：拷进 `Download/NASMusic/` 的文件归 `com.android.shell`/下载器所有，
     * MediaProvider 的默认可见性规则让本 app 查不到它 —— 选择器能读（用户逐次授权）、恢复能成，
     * 但只查 MediaStore 的列表里永远没有它（2026-10-21 真机复现，见 `ImportedBackupRecord` 的说明）。
     */
    fun listBackups(
        context: Context,
        imported: List<ImportedBackupRecord> = emptyList(),
    ): List<BackupFile> = mergeImportedEntries(
        exported = listExportedBackups(context),
        imported = imported,
        grantedUriStrings = grantedReadUris(context)
    )

    /** 本 app 当前仍持有的可持久化读授权（授权是否还在只能问系统，字符串本身看不出来） */
    private fun grantedReadUris(context: Context): Set<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()

    /**
     * 合并规则（纯函数，单测直接喂数据）：
     * 1. 丢掉授权已失效的登记项 —— 留着就是一条「点了必炸」的死行；
     * 2. 与导出项**同名**的登记项丢掉：同名的导出项归本 app 所有，读删都更可靠，
     *    同屏出现两条同名行只会让人以为重复导出了；
     * 3. 合并后按修改时间倒序（列表按「最近的可能就是要恢复的」排）。
     */
    fun mergeImportedEntries(
        exported: List<BackupFile>,
        imported: List<ImportedBackupRecord>,
        grantedUriStrings: Set<String>
    ): List<BackupFile> {
        val exportedNames = exported.map { it.displayName }.toSet()
        val extras = imported
            .filter { it.uri in grantedUriStrings }
            .filterNot { it.displayName in exportedNames }
            .map {
                BackupFile(
                    displayName = it.displayName,
                    uri = Uri.parse(it.uri),
                    lastModified = it.lastModified,
                    localImported = true
                )
            }
        return (exported + extras).sortedByDescending { it.lastModified }
    }

    /**
     * 列出 Downloads/NASMusic/ 下**本 app 看得见**的备份文件（按修改时间倒序）
     */
    private fun listExportedBackups(context: Context): List<BackupFile> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_MODIFIED
                )
                val selection = "${MediaStore.Downloads.RELATIVE_PATH} = ?"
                val selectionArgs = arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$BACKUP_SUBDIR/")
                val files = mutableListOf<BackupFile>()
                context.contentResolver.query(
                    collection, projection, selection, selectionArgs,
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: continue
                        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) continue
                        val id = cursor.getLong(idCol)
                        files.add(
                            BackupFile(
                                displayName = name,
                                uri = Uri.withAppendedPath(collection, id.toString()),
                                lastModified = cursor.getLong(dateCol) * 1000L
                            )
                        )
                    }
                }
                files
            } else {
                // 合并内部存储（可靠主备份）与公共 Downloads（普通 ROM 上可见），按名称去重
                val dirs = listOf(internalBackupDir(context), publicBackupDir())
                val seen = mutableSetOf<String>()
                val files = mutableListOf<BackupFile>()
                for (dir in dirs) {
                    if (!dir.exists()) continue
                    dir.listFiles { f ->
                        f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX)
                    }?.forEach { f ->
                        if (seen.add(f.name)) {
                            files.add(
                                BackupFile(
                                    displayName = f.name,
                                    uri = Uri.fromFile(f),
                                    lastModified = f.lastModified()
                                )
                            )
                        }
                    }
                }
                files.sortedByDescending { it.lastModified }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "listBackups failed", e)
            emptyList()
        }
    }

    /**
     * 读取备份文件内容
     *
     * 选择器放开挑任意文件，量级上限与「不像备份文件」的判定统一由
     * [LocalFilePicker.readText] 把关，这里只负责把异常换成本地化文案。
     */
    fun read(context: Context, uri: Uri): Result<String> {
        return try {
            Result.success(LocalFilePicker.readText(context, uri, MAX_BACKUP_BYTES))
        } catch (e: LocalFilePicker.TooLargeException) {
            Result.failure(
                Exception(context.getString(R.string.backup_util_too_large, e.bytes / (1024 * 1024)))
            )
        } catch (e: java.io.IOException) {
            AppLog.e(TAG, "read failed", e)
            Result.failure(Exception(context.getString(R.string.backup_util_read_failed), e))
        } catch (e: Exception) {
            AppLog.e(TAG, "read failed", e)
            Result.failure(e)
        }
    }

    /**
     * 删除备份文件
     *
     * - 文档型 Uri（用户在选择器里挑的、经「本地导入」登记的）：走
     *   `DocumentsContract.deleteDocument` —— 这类文件不归本 app 所有，`resolver.delete`
     *   会因为缺写授权抛 SecurityException；删不掉时把原因显示出来（列表不会自动同步文件管理器）
     * - API 29+：通过 MediaStore.Downloads 删除（文件为应用自身创建，无需额外权限）
     * - API < 29：同时删除内部存储与公共 Downloads 中的两份副本（按文件名匹配）
     */
    fun delete(context: Context, uri: Uri): Result<Unit> {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            return try {
                if (DocumentsContract.deleteDocument(context.contentResolver, uri)) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception(context.getString(R.string.backup_util_delete_failed)))
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "deleteDocument failed", e)
                Result.failure(e)
            }
        }
        return try {
            val deleted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(uri, null, null)
            } else {
                val file = File(uri.path ?: return Result.failure(Exception(context.getString(R.string.backup_util_invalid_path))))
                val fileName = file.name
                var count = 0
                // 两份副本（内部存储 + 公共 Downloads）都删除，避免列表去重后残留
                val candidates = listOf(
                    File(internalBackupDir(context), fileName),
                    File(publicBackupDir(), fileName)
                )
                for (candidate in candidates) {
                    if (candidate.exists() && candidate.delete()) count++
                }
                count
            }
            if (deleted > 0) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(context.getString(R.string.backup_util_delete_failed)))
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "delete failed", e)
            Result.failure(e)
        }
    }
}
