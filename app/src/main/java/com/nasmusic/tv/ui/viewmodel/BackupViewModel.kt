package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.mv.MvSearchManager
import com.nasmusic.tv.data.model.BackupMessage
import com.nasmusic.tv.data.model.ImportedBackupRecord
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.data.prefs.backupGson
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.BackupFileUtils
import com.nasmusic.tv.util.LocalFilePicker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 备份域 ViewModel（R-1 拆分自 MainViewModel）：
 * 备份文件列表 / 导出 / 导入 / 删除 / 消息消费。
 *
 * 依赖：AppPreferences、Context 文件操作、MvSearchManager（MV 缓存随备份导出）。
 */
class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences
    private val mvSearchManager: MvSearchManager = nasMusicApp.mvSearchManager

    /** 当前可用的备份文件列表（按修改时间倒序） */
    private val _backupFiles = MutableStateFlow<List<BackupFileUtils.BackupFile>>(emptyList())
    val backupFiles: StateFlow<List<BackupFileUtils.BackupFile>> = _backupFiles.asStateFlow()

    /** 备份操作结果消息（导出成功/失败、导入成功/失败） */
    private val _backupMessage = MutableStateFlow<BackupMessage?>(null)
    val backupMessage: StateFlow<BackupMessage?> = _backupMessage.asStateFlow()

    /** 刷新备份文件列表（进入设置页时调用） */
    fun refreshBackupFiles() {
        viewModelScope.launch {
            _backupFiles.value = currentBackupFiles()
        }
    }

    /**
     * 列表 = 本 app 导出的 + 「从本地文件导入」登记过的。
     *
     * ⚠️ 第二类不可省：从电脑/互传/浏览器下载拷进 `Download/NASMusic/` 的备份 json 不归本 app
     * 所有，MediaStore 查询对它返回空（真机取证见 `ImportedBackupRecord`），只查 MediaStore 会让
     * 用户觉得「导入成功了但文件凭空消失」。
     */
    private suspend fun currentBackupFiles(): List<BackupFileUtils.BackupFile> =
        BackupFileUtils.listBackups(getApplication(), prefs.getImportedBackups())

    /** 导出完整备份到 Downloads/NASMusic/（含服务器地址，不含密码/Token） */
    fun exportBackup() {
        viewModelScope.launch {
            try {
                val data = prefs.backup.exportBackupData().copy(
                    mvCacheEntries = mvSearchManager.exportMvCache()
                )
                val json = backupGson.toJson(data)
                val result = BackupFileUtils.export(getApplication(), json)
                result.onSuccess { fileName ->
                    _backupFiles.value = currentBackupFiles()
                    _backupMessage.value = BackupMessage(getApplication<Application>().getString(R.string.backup_exported, fileName))
                }.onFailure { e ->
                    AppLog.e("BackupViewModel", "exportBackup failed", e)
                    _backupMessage.value = BackupMessage(getApplication<Application>().getString(R.string.backup_export_failed, e.message?.take(60) ?: ""), isError = true)
                }
            } catch (e: Exception) {
                AppLog.e("BackupViewModel", "exportBackup failed", e)
                _backupMessage.value = BackupMessage(getApplication<Application>().getString(R.string.backup_export_failed, e.message?.take(60) ?: ""), isError = true)
            }
        }
    }

    /**
     * 从指定备份文件恢复数据
     * 恢复后服务器未连接（密码不备份），需重新连接
     *
     * @param fromLocalFile true = 来自「从本地文件导入」的选择器。除了提示要带上文件名，
     *   还必须把这份文件**登记**进列表（见 [currentBackupFiles]），否则用户导入完在列表里找不到它。
     */
    fun importBackup(uri: Uri, fromLocalFile: Boolean = false) {
        viewModelScope.launch {
            try {
                val json = BackupFileUtils.read(getApplication(), uri).getOrThrow()
                // ⚠️ 必须用 backupGson（容错枚举）：老备份里可能存着已删除的枚举名
                // （如 visualizerTheme="CLASSICAL_WAVE"），默认 Gson 会把它写成 null
                // 并绕过 Kotlin 非空检查 → 后续 .name 抛 NPE → 整份导入失败。见 BackupGson。
                val data = backupGson.fromJson(json, AppPreferences.BackupData::class.java)
                prefs.backup.importBackupData(data)
                mvSearchManager.importMvCache(data.mvCacheEntries)
                // 刷新受备份影响的 UI 状态
                val ctx = getApplication<Application>()
                val fileName = if (fromLocalFile) recordLocalImport(uri) else null
                refreshAfterImport()
                _backupMessage.value = BackupMessage(
                    if (fileName != null) {
                        ctx.getString(R.string.backup_restored_from_file, fileName)
                    } else {
                        ctx.getString(R.string.backup_restored)
                    }
                )
            } catch (e: Exception) {
                AppLog.e("BackupViewModel", "importBackup failed", e)
                _backupMessage.value = BackupMessage(getApplication<Application>().getString(R.string.backup_restore_failed, e.message?.take(60) ?: ""), isError = true)
            }
        }
    }

    /**
     * 把本地导入的文件登记进备份列表，返回其显示名（供提示文案用）。
     *
     * ⚠️ 持久化授权**不在这里申请** —— `takePersistableUriPermission` 必须在 Activity 结果回调
     * 里同步调用（协程里补做可能被系统视为授权已过期），见 SettingsBranch 的 backupFileLauncher。
     */
    private suspend fun recordLocalImport(uri: Uri): String {
        val ctx = getApplication<Application>()
        val name = LocalFilePicker.displayName(ctx, uri)
        prefs.recordImportedBackup(
            ImportedBackupRecord(
                uri = uri.toString(),
                displayName = name,
                lastModified = LocalFilePicker.lastModified(ctx, uri)
            )
        )
        return name
    }

    /**
     * 从 JSON 字符串恢复备份（用于扫码传输）
     * @return true 恢复成功；false 失败
     */
    suspend fun restoreBackupFromJson(json: String): Boolean {
        return try {
            // 同上：扫码传输过来的也可能是老备份，必须走容错 Gson
            val data = backupGson.fromJson(json, AppPreferences.BackupData::class.java)
            prefs.backup.importBackupData(data)
            mvSearchManager.importMvCache(data.mvCacheEntries)
            refreshAfterImport()
            _backupMessage.value = BackupMessage(getApplication<Application>().getString(R.string.backup_restored))
            true
        } catch (e: Exception) {
            AppLog.e("BackupViewModel", "restoreBackupFromJson failed", e)
            false
        }
    }

    /**
     * 非挂起版本的 [restoreBackupFromJson]，供 BackupTransferServer 回调用。
     *
     * NanoHTTPD 的 `serve()` 是同步的，必须立即返回响应；此方法用 `runBlocking`
     * 在 NanoHTTPD 工作线程上桥接 suspend 调用（非主线程，安全）。
     */
    fun restoreBackupFromJsonBlocking(json: String): Boolean =
        kotlinx.coroutines.runBlocking { restoreBackupFromJson(json) }

    /** 导入备份后刷新相关 StateFlow（收藏、歌单、队列、统计等由 prefs Flow 自动更新） */
    private suspend fun refreshAfterImport() {
        // 服务器连接状态保持断开（密码不备份），其余由 collect 自动同步
        _backupFiles.value = currentBackupFiles()
    }

    /** 删除指定备份文件 */
    fun deleteBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val result = BackupFileUtils.delete(getApplication(), uri)
                if (result.isSuccess) prefs.removeImportedBackup(uri.toString())
                _backupFiles.value = currentBackupFiles()
                result.onSuccess {
                    _backupMessage.value = BackupMessage(getApplication<Application>().getString(R.string.backup_deleted))
                }.onFailure { e ->
                    AppLog.e("BackupViewModel", "deleteBackup failed", e)
                    _backupMessage.value = BackupMessage(getApplication<Application>().getString(R.string.backup_delete_failed, e.message?.take(60) ?: ""), isError = true)
                }
            } catch (e: Exception) {
                AppLog.e("BackupViewModel", "deleteBackup failed", e)
                _backupMessage.value = BackupMessage(getApplication<Application>().getString(R.string.backup_delete_failed, e.message?.take(60) ?: ""), isError = true)
            }
        }
    }

    /**
     * 外部报告一次备份操作失败（例如系统文件选择器被 ROM 停用，启动即抛）。
     * 错误必须落在界面上，不能只进日志 —— 分区顶部的 backupMessage 就是它的显示位。
     */
    fun reportBackupFailure(reason: String) {
        _backupMessage.value = BackupMessage(
            getApplication<Application>().getString(R.string.backup_restore_failed, reason.take(60)),
            isError = true
        )
    }

    /** 消费备份结果消息（UI 显示后调用） */
    fun consumeBackupMessage() {
        _backupMessage.value = null
    }
}
