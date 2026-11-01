package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/** 通用设置分区状态（语言/主题/动画/字号/屏幕方向） */
data class GeneralSettingsState(
    val settings: AppSettings,
    val language: String,
    val fontAdjustment: Int,
    /** v2.36.0：屏幕方向策略 "auto" / "portrait" / "landscape" */
    val screenOrientation: String = "auto",
)

/** 通用设置分区动作 */
data class GeneralSettingsActions(
    val onChangeLanguage: ((String) -> Unit)?,
    val onToggleDarkTheme: (Boolean) -> Unit,
    val onToggleAnimations: (Boolean) -> Unit,
    val onChangeFontAdjustment: (Int) -> Unit,
    /** v2.36.0：改屏幕方向（L1 全局策略，唯一真相之源） */
    val onChangeScreenOrientation: ((String) -> Unit)? = null,
)

/** 通用设置分区（原 SettingsScreen GENERAL 分支，逻辑逐行搬迁；由 SettingsScreen 以 LazyColumn item 包装） */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun GeneralSettingsSection(
    state: GeneralSettingsState,
    actions: GeneralSettingsActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_general))
        // 卡① 界面语言：说明升格为卡片 description（§4.3：SubSectionTitle 由卡片头取代）
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_language),
            description = stringResource(R.string.settings_language_desc),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "system" to stringResource(R.string.settings_language_system),
                    "zh" to stringResource(R.string.settings_language_zh),
                    "en" to stringResource(R.string.settings_language_en)
                ).forEach { (value, label) ->
                    SettingsChip(
                        label = label,
                        selected = state.language == value,
                        onClick = { actions.onChangeLanguage?.invoke(value) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        // 卡② 外观与动效：暗色主题 / 动画效果 / 字号调整（原 settings_font_size 小节并入）
        SettingsGroupCard(title = stringResource(R.string.settings_group_theme)) {
            SettingSwitch(label = stringResource(R.string.settings_dark_theme), description = stringResource(R.string.settings_dark_theme_desc), checked = state.settings.darkTheme, onClick = { actions.onToggleDarkTheme(!state.settings.darkTheme) })
            SettingSwitch(label = stringResource(R.string.settings_animations), description = stringResource(R.string.settings_animations_desc), checked = state.settings.animationsEnabled, onClick = { actions.onToggleAnimations(!state.settings.animationsEnabled) })
            // 字体字号调整（在当前Theme档位基础上增减）
            // 芯片/调整行的说明位走 SettingsHint（§4.5）—— 此前这行**没有任何说明**
            SettingsHint(stringResource(R.string.settings_group_font_hint))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdjustButton("-", onClick = { actions.onChangeFontAdjustment((state.fontAdjustment - 1).coerceAtLeast(-8)) })
                Text(
                    text = if (state.fontAdjustment == 0) stringResource(R.string.settings_font_standard) else if (state.fontAdjustment > 0) "+${state.fontAdjustment}" else "${state.fontAdjustment}",
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.title(),
                    modifier = Modifier.widthIn(min = 100.dp).padding(horizontal = 8.dp),
                    textAlign = TextAlign.Center
                )
                AdjustButton("+", onClick = { actions.onChangeFontAdjustment((state.fontAdjustment + 1).coerceAtMost(8)) })
            }
        }
        // 卡③ 屏幕方向：说明升格为卡片 description
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_orientation),
            description = stringResource(R.string.settings_orientation_hint),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "auto" to stringResource(R.string.settings_orientation_auto),
                    "portrait" to stringResource(R.string.settings_orientation_portrait),
                    "landscape" to stringResource(R.string.settings_orientation_landscape)
                ).forEach { (value, label) ->
                    SettingsChip(
                        label = label,
                        selected = state.screenOrientation == value,
                        onClick = { actions.onChangeScreenOrientation?.invoke(value) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
