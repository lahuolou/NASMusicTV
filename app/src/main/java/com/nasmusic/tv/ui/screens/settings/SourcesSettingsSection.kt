package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.NetworkSource
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel

/**
 * 来源分区状态（v2.39.0 合并 SERVER + LOCAL_MUSIC + NETDISK 三个旧分区，方案 §3 / Task 3）。
 *
 * 字段**逐一沿用旧分区的原名与可空性**（`ServerSettingsState` / `LocalMusicSettingsState` /
 * `BaiduPanSettingsState`），`SettingsScreen` 侧只是把三处构造合成一处。
 *
 * [defaultNetworkSource] 的 UI（卡⑤「网络搜索默认源」）由 **Task 7** 落地，
 * 经 `MainViewModel.updateDefaultNetworkSource` → `AppPreferences.setDefaultNetworkSource`
 * 写 DataStore（§6-10）。
 */
data class SourcesSettingsState(
    val isConnected: Boolean,
    val serverDisplayName: String,
    val settings: AppSettings,
    val libraryCount: Int,
    val baiduEnabled: Boolean,
    val baiduLoggedIn: Boolean,
    val baiduConnectionState: NetworkMusicViewModel.BaiduConnectionState,
    val baiduMusicRootDirLocal: String,
    val baiduMvDirLocal: String?,
    val baiduIndexScanned: Int,
    val baiduIndexScanning: Boolean,
    val baiduApicExtracting: Boolean,
    val baiduApicExtracted: Int,
    val baiduApicTotal: Int,
    val defaultNetworkSource: NetworkSource,
)

/**
 * 来源分区动作。
 *
 * ⚠️ 可空性**逐字沿用旧分区**（未按方案原文改成非空默认 `{}`）：合并是纯搬迁，
 * 保留可空 ⇒ 「断开连接 / 登录按钮 / 重建索引」三处**条件渲染**的判据不变 ——
 * 尤其 `ServerSettingsSection` 的断开连接是「未连接时整段不渲染」，改成非空会让
 * 判据失效、按钮变成常驻（Task 7 明令禁止的手顺改动）。
 */
data class SourcesActions(
    val onNavigateToServerConnect: (() -> Unit)?,
    val onDisconnect: (() -> Unit)?,
    val onSetEnabled: (Boolean) -> Unit = {},
    val onRescan: () -> Unit = {},
    val onToggleBaiduEnabled: ((Boolean) -> Unit)?,
    val onStartBaiduDeviceCode: (() -> Unit)?,
    val onLogoutBaidu: (() -> Unit)?,
    val onRebuildBaiduIndex: (() -> Unit)?,
    /** T7：改网络搜索默认源（写 DataStore，见 `DefaultNetworkSourcePrefTest`） */
    val onChangeDefaultNetworkSource: (NetworkSource) -> Unit = {},
)

/** 来源分区对话框触发回调（对话框宿主仍在 `SettingsScreen`，本任务只搬参数名） */
data class SourcesDialogActions(
    val onShowBaiduAuthDialog: () -> Unit,
    val onShowMusicRootDialog: () -> Unit,
    val onShowMvDirDialog: () -> Unit,
)

/**
 * 来源分区：① NAS 服务器 ② 本地曲库 ③ 百度网盘 ④ 索引与封面 ⑤ 网络搜索默认源（§3.2 / Task 7）。
 *
 * 卡片标题：①②④⑤ 用新增的 `settings_group_*`；③ **复用** `settings_netdisk_group_baidu`。
 *
 * ⚠️ 行级排布逐行未动（T3 纯搬迁产物）：断开连接是**条件渲染**（未连接整段不渲染，
 * ⛔ 不是 `enabled = false`）；占位网盘分组（`CloudDriveType.PLACEHOLDER`）已按
 * §6-4（用户 2026-10-18 确认删除）连同 `PlaceholderRow` 一并移除。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SourcesSettingsSection(
    state: SourcesSettingsState,
    actions: SourcesActions,
    dialogs: SourcesDialogActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_sources))

        // ── ① NAS 服务器 ──
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_nas_server),
            description = stringResource(R.string.settings_group_nas_server_desc),
        ) {
            val statusText = if (state.isConnected)
                stringResource(R.string.server_connected, state.serverDisplayName)
            else
                stringResource(R.string.server_connect_desc)
            Text(statusText, color = NasMusicColors.TextPrimary, fontSize = FontSize.button(),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
            SettingActionButton(
                label = stringResource(R.string.server_config_title),
                description = if (state.isConnected) stringResource(R.string.server_connected, state.serverDisplayName)
                    else stringResource(R.string.server_connect_desc),
                onClick = { actions.onNavigateToServerConnect?.invoke() },
                // 跳到连接页（下一级）
                trailing = Trail.Arrow,
            )
            // ⛔ 未连接时「断开连接」整段不渲染（不是 enabled = false）—— 保留原形态
            if (state.isConnected && actions.onDisconnect != null) {
                Spacer(modifier = Modifier.height(8.dp))
                SettingActionButton(
                    label = stringResource(R.string.server_disconnect),
                    description = stringResource(R.string.settings_disconnect_desc),
                    onClick = actions.onDisconnect,
                    // §4.7-③ S6：断开后要重新走连接流程（找回服务器地址 / 输口令）
                    danger = true,
                )
            }
        }

        // ── ② 本地曲库 ──
        SettingsGroupCard(title = stringResource(R.string.settings_group_local_music)) {
            // ⛔ 总开关 = 音乐权限的唯一触发点（§5.5）：ViewModel 内部判
            //    「已授权直接开 / 未授权先申请、被拒回弹」，这里只转发目标值。
            val s = state.settings
            SettingSwitch(
                label = stringResource(R.string.settings_local_music_source),
                description = stringResource(R.string.settings_local_music_source_desc),
                checked = s.localMusicEnabled,
                onClick = { actions.onSetEnabled(!s.localMusicEnabled) }
            )
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                SettingsInfoRow(
                    stringResource(R.string.settings_local_music_source),
                    stringResource(R.string.settings_local_music_count, state.libraryCount),
                )
            }
            // 开关关闭时置灰（§5.6）：关着开关扫描没有意义 —— 结果也不会出现在曲库
            SettingActionButton(
                label = stringResource(R.string.settings_local_music_rescan),
                description = stringResource(R.string.settings_local_music_rescan_desc),
                onClick = actions.onRescan,
                enabled = s.localMusicEnabled,
            )
        }

        // ── ③ 百度网盘 ──
        SettingsGroupCard(title = stringResource(R.string.settings_netdisk_group_baidu)) {
            SettingSwitch(label = stringResource(R.string.settings_netdisk_enable), description = stringResource(R.string.settings_netdisk_enable_desc), checked = state.baiduEnabled, onClick = { actions.onToggleBaiduEnabled?.invoke(!state.baiduEnabled) })

            // 授权失败时，在登录按钮上方持续显示失败原因（即使对话框关闭也能看到）
            val baiduFailedState = state.baiduConnectionState as? NetworkMusicViewModel.BaiduConnectionState.Failed
            if (baiduFailedState != null) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.netdisk_auth_failed),
                        color = NasMusicColors.Warning,
                        fontSize = FontSize.body(),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = baiduFailedState.message,
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small()
                    )
                }
            }

            // 已登录但音乐根目录不存在，提示用户重新设置
            val baiduDirMissing = state.baiduConnectionState is NetworkMusicViewModel.BaiduConnectionState.DirMissing
            if (baiduDirMissing) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.netdisk_dir_missing),
                        color = NasMusicColors.Warning,
                        fontSize = FontSize.body(),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.netdisk_dir_missing_desc),
                        color = NasMusicColors.TextSecondary,
                        fontSize = FontSize.small()
                    )
                }
            }

            if (actions.onStartBaiduDeviceCode != null) {
                Spacer(modifier = Modifier.height(8.dp))
                if (state.baiduLoggedIn) {
                    SettingActionButton(
                        label = stringResource(R.string.settings_netdisk_logged_in),
                        description = stringResource(R.string.settings_netdisk_logout_desc),
                        onClick = { actions.onLogoutBaidu?.invoke() }
                    )
                } else {
                    SettingActionButton(
                        label = stringResource(R.string.settings_netdisk_login),
                        description = stringResource(R.string.settings_netdisk_login_desc),
                        onClick = {
                            dialogs.onShowBaiduAuthDialog()
                            actions.onStartBaiduDeviceCode?.invoke()
                        },
                        // 打开设备码授权对话框（下一级）
                        trailing = Trail.Arrow,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            // §4.5 硬要求 3：路径是**当前值**，归 SettingsInfoRow 的值位；说明归 description。
            // 此前路径直接塞进按钮的 description 里 —— 同一行既像说明又像值，是既有违例。
            SettingsInfoRow(stringResource(R.string.settings_netdisk_music_root), state.baiduMusicRootDirLocal)
            SettingActionButton(
                label = stringResource(R.string.settings_netdisk_music_root_edit),
                description = stringResource(R.string.settings_netdisk_music_root_desc),
                onClick = { dialogs.onShowMusicRootDialog() },
                // 打开路径编辑对话框（下一级）
                trailing = Trail.Arrow,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsInfoRow(
                stringResource(R.string.settings_netdisk_mv_dir),
                state.baiduMvDirLocal?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.common_not_set),
            )
            SettingActionButton(
                label = stringResource(R.string.settings_netdisk_mv_dir_edit),
                description = stringResource(R.string.settings_netdisk_mv_dir_desc),
                onClick = { dialogs.onShowMvDirDialog() },
                // 打开路径编辑对话框（下一级）
                trailing = Trail.Arrow,
            )
        }

        // ── ④ 索引与封面 ──
        // §4.5：卡级说明说明"索引 / 封面提取"两件事的代价；APIC 没有独立按钮可挂（T8b 已裁决）
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_netdisk_index),
            description = stringResource(R.string.settings_netdisk_index_desc2),
        ) {
            if (actions.onRebuildBaiduIndex != null) {
                Text(
                    text = if (state.baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning_progress, state.baiduIndexScanned)
                    else stringResource(R.string.settings_netdisk_index_desc, state.baiduIndexScanned),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
                // APIC 封面提取进度（放在已扫描歌曲数量下方、重建索引按钮上方）
                if (state.baiduApicExtracting || state.baiduApicTotal > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (state.baiduApicExtracting) {
                            stringResource(R.string.settings_baidu_apic_extracting, state.baiduApicExtracted, state.baiduApicTotal)
                        } else {
                            stringResource(R.string.settings_baidu_apic_done, state.baiduApicTotal)
                        },
                        color = if (state.baiduApicExtracting) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                        fontSize = FontSize.body(),
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    if (state.baiduApicExtracting && state.baiduApicTotal > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 16.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(NasMusicColors.SurfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth((state.baiduApicExtracted.toFloat() / state.baiduApicTotal).coerceIn(0f, 1f))
                                    .height(4.dp)
                                    .background(NasMusicColors.Primary, RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
                SettingActionButton(
                    label = stringResource(R.string.settings_netdisk_index_rebuild),
                    description = if (state.baiduIndexScanning) stringResource(R.string.settings_netdisk_index_scanning_progress, state.baiduIndexScanned)
                                   else stringResource(R.string.settings_netdisk_index_rebuild_desc),
                    onClick = { if (!state.baiduIndexScanning) actions.onRebuildBaiduIndex?.invoke() }
                )
            }
        }

        // ── ⑤ 网络搜索默认源（T7 新增暴露） ──
        // 口径同音质档位（令牌化前既有词汇）：选中项「▶ 」前缀，T11 与音质档位
        // 一并换成 SettingsTile(selected = true)，本任务不提前改。
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_default_source),
            description = stringResource(R.string.settings_default_source_desc),
        ) {
            NetworkSource.entries.forEach { source ->
                val selected = state.defaultNetworkSource == source
                SettingActionButton(
                    label = if (selected) "▶ ${source.displayName}" else source.displayName,
                    description = source.key,
                    onClick = { actions.onChangeDefaultNetworkSource(source) }
                )
            }
        }
    }
}
