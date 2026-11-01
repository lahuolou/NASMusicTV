package com.nasmusic.tv.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.NasMusicVersion
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.VersionInfo
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** GitHub 项目主页（关于页「开源与许可」卡的仓库链接，Text 与 Intent 共用同一常量） */
private const val GITHUB_PROJECT_URL = "https://github.com/hxzhang2000/NasMusicTV"

/** 关于分区状态 */
data class AboutSettingsState(
    val isConnected: Boolean,
    val serverDisplayName: String,
    val backendApiVersion: String,
    val apiVersions: List<VersionInfo>,
)

/**
 * 关于分区（原 SettingsScreen ABOUT 分支，逻辑逐行搬迁；四卡构成见方案 §3.2 / Task 8）。
 *
 * ⚠️ Task 8 只做「裸 `Text` / `SubSectionTitle` 标题 → `SettingsGroupCard` 卡片头」，
 * 并把 GitHub 仓库链接从 `Text.clickable` 换成可聚焦瓦片
 * （方案 §2-7 缺陷本体：`Modifier.clickable` 的文本在 TV 上聚焦不到，遥控器没路径）：
 * 容器 / 焦点色与 [SettingActionButton] 同一套口径（T11 起两者都走 `SettingsTile`），
 * 跳转仍走 `Intent.ACTION_VIEW`。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AboutSettingsSection(state: AboutSettingsState) {
    Column {
        SectionTitle(stringResource(R.string.settings_about))
        // 卡① 版本信息（§4.5：本页全是只读信息行，说明由卡片 description 承担）
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_version),
            description = stringResource(R.string.settings_group_about_desc),
        ) {
            AboutRow(label = stringResource(R.string.settings_app_name), value = stringResource(R.string.app_name))
            AboutRow(label = stringResource(R.string.about_version), value = NasMusicVersion.DISPLAY)
            AboutRow(label = stringResource(R.string.settings_build_type), value = NasMusicVersion.BUILD_TYPE)
            AboutRow(label = stringResource(R.string.about_license), value = stringResource(R.string.about_license_value))
            AboutRow(label = stringResource(R.string.settings_supported_backends), value = stringResource(R.string.settings_supported_backends_value))
        }
        // 卡② 连接信息（当前连接的后端 + 全量 API 版本号）
        SettingsGroupCard(title = stringResource(R.string.settings_group_connection_info)) {
            // 当前连接的后端信息
            if (state.isConnected) {
                AboutRow(label = stringResource(R.string.settings_backend_type), value = state.serverDisplayName)
                AboutRow(label = stringResource(R.string.settings_api_version), value = state.backendApiVersion)
            } else {
                AboutRow(label = stringResource(R.string.settings_backend_type), value = stringResource(R.string.settings_not_connected))
            }
            // 全量 API 版本号（后端 + 外部服务）
            Text(
                text = stringResource(R.string.settings_api_versions),
                color = NasMusicColors.Primary,
                fontSize = FontSize.button(),
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
            if (state.apiVersions.isEmpty()) {
                AboutRow(label = stringResource(R.string.settings_api_versions_empty), value = "")
            } else {
                state.apiVersions.forEach { v ->
                    val (labelText, valueText) = formatVersionInfo(v)
                    AboutRow(label = labelText, value = valueText)
                }
            }
        }
        // 卡③ 能力说明
        SettingsGroupCard(title = stringResource(R.string.settings_group_capabilities)) {
            AboutRow(label = stringResource(R.string.settings_network_music_info), value = stringResource(R.string.settings_network_music_value))
            AboutRow(label = stringResource(R.string.settings_independent_music), value = stringResource(R.string.settings_independent_music_value))
            AboutRow(label = stringResource(R.string.settings_radio_info), value = stringResource(R.string.settings_radio_value))
            AboutRow(label = stringResource(R.string.settings_baidu_netdisk_info), value = stringResource(R.string.settings_baidu_netdisk_value))
            AboutRow(label = stringResource(R.string.settings_lyrics_info), value = stringResource(R.string.settings_lyrics_value))
        }
        // 卡④ 开源与许可（版权说明 + GitHub 项目链接）
        SettingsGroupCard(title = stringResource(R.string.settings_group_opensource)) {
            // 版权说明
            Text(
                text = stringResource(R.string.settings_copyright),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.settings_copyright_text),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.small(),
                lineHeight = FontSize.small() * 1.5
            )
            // GitHub 项目链接
            Spacer(modifier = Modifier.height(20.dp))
            val context = LocalContext.current
            Text(
                text = stringResource(R.string.settings_star_prompt),
                color = NasMusicColors.Primary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // ⚠️ 原 `Text(...).clickable { startActivity }` 在 TV 上聚焦不到（方案 §2-7），
            // 换成瓦片；T11 起统一走 SettingsTile（取值收口，G7-B 要求分区零裸瓦片）。
            SettingsTile(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PROJECT_URL))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = GITHUB_PROJECT_URL,
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.button(),
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        // ⚠️ OpenInNew 是方向性图标：1.6+ 旧路径 Icons.Default/Filled.OpenInNew 已 HIDDEN，
                        // 必须 Icons.AutoMirrored.Filled.OpenInNew（否则 Unresolved reference）
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = NasMusicColors.Primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
