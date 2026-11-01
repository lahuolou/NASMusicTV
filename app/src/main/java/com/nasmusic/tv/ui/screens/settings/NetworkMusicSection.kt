package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 网络音乐设置分区状态（Meting/Jamendo/MV/歌词端点/天气 Key/网络测试状态） */
data class NetworkMusicSettingsState(
    val metingApiBaseUrl: String,
    val jamendoClientId: String,
    val mvApiBaseUrl: String,
    val lyricsKugouBaseUrl: String,
    val lyricsNeteaseBaseUrl: String,
    val weatherApiKey: String,
    val isNetworkTesting: Boolean,
    val networkTestStatus: String,
)

/** 网络音乐设置分区动作（URL 编辑对话框由宿主持有） */
data class NetworkMusicSettingsActions(
    val onChangeMetingApiBaseUrl: ((String) -> Unit)?,
    val onChangeJamendoClientId: ((String) -> Unit)?,
    val onChangeMvApiBaseUrl: ((String) -> Unit)?,
    val onChangeLyricsKugouBaseUrl: ((String) -> Unit)?,
    val onChangeLyricsNeteaseBaseUrl: ((String) -> Unit)?,
    val onChangeWeatherApiKey: ((String) -> Unit)?,
    val onRunNetworkTest: () -> Unit,
)

/** URL 编辑对话框触发回调（对话框本体保持在 SettingsScreen，避免状态重复） */
data class NetworkMusicDialogActions(
    val onShowMetingUrlDialog: () -> Unit,
    val onShowJamendoClientIdDialog: () -> Unit,
    val onShowMvUrlDialog: () -> Unit,
    val onShowLyricsKugouDialog: () -> Unit,
    val onShowLyricsNeteaseDialog: () -> Unit,
    val onShowWeatherApiKeyDialog: () -> Unit,
)

/**
 * 网络音乐设置分区（原 SettingsScreen NETWORK 分支，逻辑逐行搬迁；五卡构成见方案 §3.2 / Task 8）。
 *
 * ⚠️ Task 8 把原 5 处裸 `Text` 标题换成 `SettingsGroupCard` 卡片头（方案 §5.1 要求的
 * 「消灭 NETWORK tab 的裸 Text 特例」）：`settings_network_search` / `settings_jamendo_client_id` /
 * `settings_mv_api_url` / `settings_lyrics_endpoint` / `settings_weather_api_key` 五个标题
 * **不再单独渲染**（同名信息由卡片头承担）；其下的说明文字按「卡内排布逐行不动」
 * 原样留在卡内（合并进卡片 `description` 是 T14 的事）。
 * ⚠️ Jamendo Client ID 与 Meting 端点同属「音乐搜索端点」卡（方案 §3.2），两块之间保留
 * 16dp 分隔（原为 24dp 块间 Spacer，卡间距已由 `SettingsGroupCard` 承担）。
 * ⚠️ 各块的 `if (actions.onChangeXxx != null)` 条件渲染**原样保留**（未改成 `enabled = false`）。
 */
@Composable
internal fun NetworkMusicSection(
    state: NetworkMusicSettingsState,
    actions: NetworkMusicSettingsActions,
    dialogs: NetworkMusicDialogActions
) {
    androidx.compose.foundation.layout.Column {
        SectionTitle(stringResource(R.string.settings_network))
        Text(
            text = stringResource(R.string.settings_network_test_desc),
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(bottom = 16.dp, start = 4.dp)
        )
        // 卡① 连通性：网络测试
        SettingsGroupCard(title = stringResource(R.string.settings_group_connectivity)) {
            NetworkTestCard(state = state, onRun = actions.onRunNetworkTest)
        }

        // 卡② 音乐搜索端点：Meting 预设 + 自定义、Jamendo Client ID（方案 §3.2 同卡）
        // §4.5：把「自定义端点有风险」这句警示合并进卡片 description，不再另起一行灰字
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_search_endpoints),
            description = stringResource(R.string.settings_group_endpoint_risk_desc),
        ) {
            // --- 网络搜索：Meting-API 端点配置 ---
            if (actions.onChangeMetingApiBaseUrl != null) {
                Text(
                    text = stringResource(R.string.settings_meting_api_url_desc),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )
                Text(
                    text = stringResource(R.string.settings_meting_preset_endpoints),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                )
                val currentNormalized = state.metingApiBaseUrl.trim().trimEnd('/')
                com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS.forEach { (nameRes, url) ->
                    val selected = currentNormalized == url.trimEnd('/')
                    PresetEndpointRow(
                        name = stringResource(nameRes),
                        url = url,
                        selected = selected,
                        onClick = { actions.onChangeMetingApiBaseUrl(url) }
                    )
                }
                // 自定义端点选项
                val isPreset = com.nasmusic.tv.backend.network.MetingApiService.PRESET_ENDPOINTS
                    .any { it.second.trimEnd('/') == currentNormalized }
                val customSelected = !isPreset
                PresetEndpointRow(
                    name = stringResource(R.string.settings_meting_custom_endpoint),
                    url = if (customSelected) state.metingApiBaseUrl else stringResource(R.string.settings_meting_custom_endpoint_desc),
                    selected = customSelected,
                    trailingLabel = stringResource(R.string.settings_meting_api_url_edit),
                    onClick = { dialogs.onShowMetingUrlDialog() }
                )
            }

            // --- 网络搜索：Jamendo Client ID（CC 独立音乐）---
            if (actions.onChangeJamendoClientId != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.settings_jamendo_client_id_desc),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )
                // 当前值状态卡 + 修改按钮
                SettingsTile(
                    onClick = { dialogs.onShowJamendoClientIdDialog() },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding)
                    ) {
                        Text(
                            text = if (state.jamendoClientId.isBlank())
                                stringResource(R.string.settings_not_configured_hint)
                            else state.jamendoClientId.take(24) + if (state.jamendoClientId.length > 24) "…" else "",
                            color = if (state.jamendoClientId.isBlank()) NasMusicColors.TextSecondary
                                    else NasMusicColors.Primary,
                            fontSize = FontSize.button(),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.settings_tap_to_edit),
                            color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                            fontSize = FontSize.small(),
                            modifier = androidx.compose.ui.Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        // 卡③ MV 端点：预设 + 自定义
        if (actions.onChangeMvApiBaseUrl != null) {
            SettingsGroupCard(title = stringResource(R.string.settings_group_mv_endpoints)) {
                Text(
                    text = stringResource(R.string.settings_mv_api_url_desc),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )
                Text(
                    text = stringResource(R.string.settings_mv_preset_endpoints),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                )
                val mvCurrentNormalized = state.mvApiBaseUrl.trim().trimEnd('/')
                com.nasmusic.tv.backend.network.mv.BilibiliMvService.ENDPOINT_OPTIONS.forEach { (nameRes, url) ->
                    val selected = mvCurrentNormalized == url.trimEnd('/')
                    PresetEndpointRow(
                        name = stringResource(nameRes),
                        url = url,
                        selected = selected,
                        onClick = { actions.onChangeMvApiBaseUrl(url) }
                    )
                }
                // 自定义端点选项
                val mvIsPreset = com.nasmusic.tv.backend.network.mv.BilibiliMvService.ENDPOINT_OPTIONS
                    .any { it.second.trimEnd('/') == mvCurrentNormalized }
                val mvCustomSelected = !mvIsPreset
                PresetEndpointRow(
                    name = stringResource(R.string.settings_mv_custom_endpoint),
                    url = if (mvCustomSelected) state.mvApiBaseUrl else stringResource(R.string.settings_mv_custom_endpoint_desc),
                    selected = mvCustomSelected,
                    trailingLabel = stringResource(R.string.settings_mv_api_url_edit),
                    onClick = { dialogs.onShowMvUrlDialog() }
                )
            }
        }

        // 卡④ 歌词端点：酷狗、网易云
        if (actions.onChangeLyricsKugouBaseUrl != null || actions.onChangeLyricsNeteaseBaseUrl != null) {
            SettingsGroupCard(title = stringResource(R.string.settings_group_lyrics_endpoints)) {
                // 酷狗端点
                Text(
                    text = stringResource(R.string.settings_lyrics_kugou_url),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                )
                SettingsTile(
                    onClick = { dialogs.onShowLyricsKugouDialog() },
                    // ⚠️ 不再自带 `.padding(vertical = 4.dp)`：SettingsTile 已经给了行外边距，
                    // 再叠一层会变成 8dp（三处端点/Key 行同一处理）。
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                            Text(
                                text = state.lyricsKugouBaseUrl.ifBlank { stringResource(R.string.settings_lyrics_url_reset) },
                                color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                                fontSize = FontSize.body()
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_lyrics_url_edit),
                            color = NasMusicColors.Primary,
                            fontSize = FontSize.button()
                        )
                    }
                }
                // 网易云端点
                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_lyrics_netease_url),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                )
                SettingsTile(
                    onClick = { dialogs.onShowLyricsNeteaseDialog() },
                    // ⚠️ 不再自带 `.padding(vertical = 4.dp)`：SettingsTile 已经给了行外边距，
                    // 再叠一层会变成 8dp（三处端点/Key 行同一处理）。
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                            Text(
                                text = state.lyricsNeteaseBaseUrl.ifBlank { stringResource(R.string.settings_lyrics_url_reset) },
                                color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                                fontSize = FontSize.body()
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_lyrics_url_edit),
                            color = NasMusicColors.Primary,
                            fontSize = FontSize.button()
                        )
                    }
                }
            }
        }

        // 卡⑤ 天气 API Key
        if (actions.onChangeWeatherApiKey != null) {
            SettingsGroupCard(title = stringResource(R.string.settings_group_weather_key)) {
                Text(
                    text = stringResource(R.string.settings_weather_api_key_desc),
                    color = NasMusicColors.TextSecondary,
                    fontSize = FontSize.body(),
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )
                SettingsTile(
                    onClick = { dialogs.onShowWeatherApiKeyDialog() },
                    // ⚠️ 不再自带 `.padding(vertical = 4.dp)`：SettingsTile 已经给了行外边距，
                    // 再叠一层会变成 8dp（三处端点/Key 行同一处理）。
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.foundation.layout.Row(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                            Text(
                                text = if (state.weatherApiKey.isNotBlank()) "···${state.weatherApiKey.takeLast(6)}"
                                       else stringResource(R.string.common_not_set),
                                color = if (state.weatherApiKey.isNotBlank()) NasMusicColors.TextPrimary
                                        else NasMusicColors.TextSecondary,
                                fontSize = FontSize.button()
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_weather_api_key_edit),
                            color = NasMusicColors.Primary,
                            fontSize = FontSize.button()
                        )
                    }
                }
            }
        }
    }
}

/** 网络测试卡片（原 NETWORK 分支的网络测试瓦片，逻辑搬迁；T11 起走 SettingsTile） */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkTestCard(state: NetworkMusicSettingsState, onRun: () -> Unit) {
    SettingsTile(
        onClick = onRun,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        // ⚠️ 原来这里的 `containerColor = SurfaceVariant` + 1dp `Border` 描边一起表达
        // 「这是一张卡、不是普通行」。收口到 SettingsTile 后容器色回到 Surface；
        // 描边本来也画不出来（`FocusableSurface` 的 `background` 排在入参 modifier 之后，
        // 会把描边整条盖住）⇒ 一并去掉，视觉等价。
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.tv.material3.Icon(
                imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                contentDescription = null,
                tint = if (state.isNetworkTesting) NasMusicColors.TextSecondary else NasMusicColors.Primary,
                modifier = androidx.compose.ui.Modifier.size(20.dp)
            )
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.width(12.dp))
            Text(
                text = if (state.isNetworkTesting) stringResource(R.string.settings_network_testing) else stringResource(R.string.settings_network_test),
                color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                fontSize = FontSize.button()
            )
            androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.weight(1f))
            if (state.networkTestStatus.isNotBlank()) {
                val isNetSuccess = state.networkTestStatus.startsWith("success:")
                val netMessage = if (isNetSuccess) state.networkTestStatus.removePrefix("success:") else state.networkTestStatus.removePrefix("error:")
                Text(
                    text = if (isNetSuccess) "✓ $netMessage" else "✗ $netMessage",
                    color = if (isNetSuccess) NasMusicColors.Primary else NasMusicColors.Warning,
                    fontSize = FontSize.body()
                )
            }
        }
    }
}

/** 预设端点单选行（Meting/MV 端点列表共用，逻辑搬迁） */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
private fun PresetEndpointRow(
    name: String,
    url: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingLabel: String? = null
) {
    SettingsTile(
        onClick = onClick,
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        // 「已选中」= 唯一的 0.18 底 + Primary 字（§4.7-③ S3），不再手调 containerColor。
        selected = selected,
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = androidx.compose.ui.Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                Text(
                    text = name,
                    // 选中时 SettingsTile 的 contentColor 已经是 Primary，跟随即可。
                    color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                    fontSize = FontSize.button()
                )
                Text(
                    text = url,
                    color = com.nasmusic.tv.ui.components.LocalFocusableContentColor.current,
                    fontSize = FontSize.body(),
                    modifier = androidx.compose.ui.Modifier.padding(top = 2.dp)
                )
            }
            if (selected && trailingLabel == null) {
                Text(
                    text = "✓",
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.button()
                )
            }
            if (trailingLabel != null) {
                Text(
                    text = trailingLabel,
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.button()
                )
            }
        }
    }
}
