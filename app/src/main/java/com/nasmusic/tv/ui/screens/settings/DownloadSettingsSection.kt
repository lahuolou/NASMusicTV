package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.DownloadStats
import com.nasmusic.tv.backend.export.ExportState
import com.nasmusic.tv.backend.export.ExportError
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 下载设置分区状态 */
data class DownloadSettingsState(
    val downloadStats: DownloadStats,
    val downloadEnabled: Boolean,
    val autoDownloadOnPlay: Boolean,
    val autoDownloadLimit: Int,
    val downloadLocation: String,
    val exportState: ExportState,
)

/** 下载设置分区动作 */
data class DownloadSettingsActions(
    val onToggleDownloadEnabled: ((Boolean) -> Unit)?,
    val onToggleAutoDownloadOnPlay: ((Boolean) -> Unit)?,
    val onChangeAutoDownloadLimit: ((Int) -> Unit)?,
    val onChangeDownloadLocation: ((String) -> Unit)?,
    val onClearAllDownloads: (() -> Unit)?,
    val onExportToDevice: (() -> Unit)?,
    val onCancelExport: (() -> Unit)?,
    val onResetExportState: (() -> Unit)?,
)

/**
 * 下载设置分区（原 SettingsScreen DOWNLOAD 分支，逻辑逐行搬迁；四卡构成见方案 §3.2 / Task 6）。
 * 清空确认弹窗由 [onClearAllDownloadsRequested] 回调上抛给宿主（弹窗保持单例）。
 *
 * ⚠️ Task 6 只做「SubSectionTitle → `SettingsGroupCard`」与「清除全部下载」按方案
 * 上移进「已下载」卡（它清的就是那些统计）；卡内行级排布逐行未动。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DownloadSettingsSection(
    state: DownloadSettingsState,
    actions: DownloadSettingsActions,
    onClearAllDownloadsRequested: () -> Unit
) {
    Column {
        SectionTitle(stringResource(R.string.settings_download))
        // 卡① 下载开关（标题复用原 settings_download_basic）
        SettingsGroupCard(title = stringResource(R.string.settings_download_basic)) {
            SettingSwitch(
                label = stringResource(R.string.settings_download_enabled),
                description = stringResource(R.string.settings_download_enabled_desc),
                checked = state.downloadEnabled,
                onClick = { actions.onToggleDownloadEnabled?.invoke(!state.downloadEnabled) }
            )
            SettingSwitch(
                label = stringResource(R.string.settings_auto_download_on_play),
                description = stringResource(R.string.settings_auto_download_on_play_desc),
                checked = state.autoDownloadOnPlay,
                enabled = state.downloadEnabled,
                onClick = { actions.onToggleAutoDownloadOnPlay?.invoke(!state.autoDownloadOnPlay) }
            )
            // 自动下载数量上限（手动下载不受限）
            // ⚠️ 这里的「标题 Text」是**行标题**不是卡标题，保留在卡内（方案 T6）
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(
                    text = stringResource(R.string.settings_auto_download_limit, state.autoDownloadLimit),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button()
                )
                SettingsHint(stringResource(R.string.settings_auto_download_limit_desc))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AdjustButton("-", onClick = {
                        actions.onChangeAutoDownloadLimit?.invoke((state.autoDownloadLimit - 10).coerceAtLeast(1))
                    })
                    Text(text = state.autoDownloadLimit.toString(), color = NasMusicColors.TextPrimary, fontSize = FontSize.title())
                    AdjustButton("+", onClick = {
                        actions.onChangeAutoDownloadLimit?.invoke((state.autoDownloadLimit + 10).coerceAtMost(5000))
                    })
                }
            }
        }
        // 卡② 存储位置（标题复用原 settings_download_location）
        SettingsGroupCard(title = stringResource(R.string.settings_download_location)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(
                    text = if (state.downloadLocation == "CUSTOM") stringResource(R.string.settings_download_location_custom) else stringResource(R.string.settings_download_location_internal),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button()
                )
                // §4.5 DOWNLOAD ②：说明写"换了目录会怎样"。旧文案「外接设备导出功能可在
                // 「我的 → 下载」中使用」已过时 —— 导出入口现在就在本页卡④里。
                SettingsHint(stringResource(R.string.settings_download_location_hint))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AdjustButton(stringResource(R.string.settings_download_btn_internal), onClick = { actions.onChangeDownloadLocation?.invoke("INTERNAL") })
                    AdjustButton(stringResource(R.string.settings_download_btn_external), onClick = { actions.onChangeDownloadLocation?.invoke("CUSTOM") })
                }
            }
        }
        // 卡③ 已下载（标题复用原 settings_download_stats；「清除全部下载」按方案从
        // 原 location 之后上移进本卡 —— 它清的就是上面这些统计）
        SettingsGroupCard(title = stringResource(R.string.settings_download_stats)) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                val stats = state.downloadStats
                SettingsInfoRow(stringResource(R.string.settings_download_stats_songs), stats.songCount.toString())
                SettingsInfoRow(stringResource(R.string.settings_download_stats_lyrics), stats.lyricsCount.toString())
                SettingsInfoRow(stringResource(R.string.settings_download_stats_covers), stats.coverCount.toString())
                SettingsInfoRow(
                    stringResource(R.string.settings_download_stats_size),
                    formatBytes(stats.totalBytes)
                )
            }
            // P1-17: 清空所有下载
            // §4.7-③ S6：删掉的是用户主动下载到本机的音乐文件，取 Warning 色。
            SettingActionButton(
                label = stringResource(R.string.settings_clear_downloads_title),
                description = stringResource(R.string.settings_clear_downloads_desc),
                onClick = onClearAllDownloadsRequested,
                danger = true,
            )
        }
        // 卡④ 导出到外接设备（§8.8.9；标题复用原 settings_download_export）
        SettingsGroupCard(title = stringResource(R.string.settings_download_export)) {
            // Desc-exempt: 本卡的 AdjustButton 是导出过程中的「取消 / 确定」，不是需要说明的
            // 可选项；本卡唯一的可操作入口（导出按钮）自带 description，见下方 else 分支。
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text(
                    text = stringResource(R.string.settings_download_export_desc),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body()
                )
                Spacer(modifier = Modifier.height(8.dp))
                when (val exportState = state.exportState) {
                    is ExportState.Running -> {
                        // 导出进行中：进度条 + 取消按钮
                        Text(
                            text = stringResource(R.string.status_export_progress, exportState.done, exportState.total),
                            color = NasMusicColors.Primary,
                            fontSize = FontSize.button()
                        )
                        if (exportState.current.isNotEmpty()) {
                            Text(
                                text = exportState.current,
                                color = NasMusicColors.TextSecondary,
                                fontSize = FontSize.small()
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // 自定义进度条（TV Material3 无 LinearProgressIndicator）
                        val progress = if (exportState.total > 0) exportState.done.toFloat() / exportState.total else 0f
                        Box(
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(NasMusicColors.TextSecondary.copy(alpha = 0.3f))
                        ) {
                            Box(
                                modifier = Modifier.fillMaxHeight()
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .background(NasMusicColors.Primary)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        AdjustButton("X", onClick = { actions.onCancelExport?.invoke() })
                    }
                    is ExportState.Completed -> {
                        Text(
                            text = stringResource(R.string.status_export_done, exportState.done, exportState.skipped),
                            color = NasMusicColors.Primary,
                            fontSize = FontSize.button()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AdjustButton(stringResource(R.string.common_confirm), onClick = { actions.onResetExportState?.invoke() })
                    }
                    is ExportState.Failed -> {
                        val errMsg = when (exportState.reason) {
                            ExportError.NO_DEVICE -> stringResource(R.string.dialog_export_no_device)
                            ExportError.NO_PERMISSION -> stringResource(R.string.dialog_export_no_device)
                            ExportError.NO_SPACE -> stringResource(R.string.msg_export_no_space)
                            ExportError.NOTHING_TO_EXPORT -> stringResource(R.string.msg_export_nothing)
                            else -> stringResource(R.string.status_export_cancelled)
                        }
                        Text(text = errMsg, color = androidx.compose.ui.graphics.Color.Red, fontSize = FontSize.button())
                        Spacer(modifier = Modifier.height(8.dp))
                        AdjustButton(stringResource(R.string.common_confirm), onClick = { actions.onResetExportState?.invoke() })
                    }
                    is ExportState.Cancelled -> {
                        Text(
                            text = stringResource(R.string.status_export_cancelled),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.button()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AdjustButton(stringResource(R.string.common_confirm), onClick = { actions.onResetExportState?.invoke() })
                    }
                    else -> {
                        // Idle / Preparing → 显示导出按钮
                        SettingActionButton(
                            label = stringResource(R.string.settings_download_export),
                            // 这个 key 一直存在（settings_download_export_desc）却没被引用，
                            // 按钮此前给的是空串 ⇒ G6-A 一建门就红在这里。
                            description = stringResource(R.string.settings_download_export_desc),
                            onClick = { actions.onExportToDevice?.invoke() },
                            // 走系统目录选择器（下一级）
                            trailing = Trail.Arrow,
                        )
                    }
                }
            }
        }
    }
}
