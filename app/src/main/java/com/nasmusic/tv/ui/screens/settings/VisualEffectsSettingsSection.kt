package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 视觉特效分区状态（v2.39.0 从 `PlayerSettingsSection` 切出，方案 §3 / Task 3）。
 *
 * `settings` 保留：效果主题的只读行要读 `AppSettings.visualizerTheme`。
 */
data class VisualEffectsSettingsState(
    val settings: AppSettings,
    val visualizerQuality: VisualQuality,
    val coverFilterEnabled: Boolean,
    val coverFilterBlurRadius: Float,
    val coverFilterDarkOverlay: Float,
)

/** 视觉特效分区动作（可空性沿用旧 `PlayerSettingsActions` 的非空写法） */
data class VisualEffectsActions(
    val onChangeVisualizerQuality: (VisualQuality) -> Unit,
    val onToggleCoverFilter: (Boolean) -> Unit,
    val onChangeCoverBlurRadius: (Float) -> Unit,
    val onChangeCoverDarkOverlay: (Float) -> Unit,
)

/**
 * 视觉特效分区（可视化画质 + 效果主题只读行 + 封面滤镜；卡片构成见方案 §3.2）。
 *
 * ⚠️ Task 4 只加卡片外壳：卡内行级排布逐行未动（原 `PlayerSettingsSection.kt` 搬迁产物）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun VisualEffectsSettingsSection(
    state: VisualEffectsSettingsState,
    actions: VisualEffectsActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_visual_effects))
        // 卡① 可视化
        SettingsGroupCard(title = stringResource(R.string.settings_group_visualizer)) {
            // ── T1.6.4（§十三 裁决项 9=A / §2.4 方案 A）：可视化画质三档 ──
            // 背景：VisualQuality 此前无任何 UI 入口 ⇒ 恒 MEDIUM ⇒ E18 反馈残像 / E19 粒子文字 /
            // E20 等离子流场（Tier.ULTRA → allowFramebuffer）被 supports() 永久过滤、从未渲染过。
            // 接 VisualizerViewModel.setQuality() ⇒ 写 DataStore ⇒ VisualizerStage 的
            // LaunchedEffect(theme, quality) 重新 sync ⇒ RendererSwapper 对现有渲染器重进 onEnter，
            // **立即生效、无需重启**（§2.4 ④ 已核实 RendererSwapper.sync 支持仅画质变化的复用路径）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp)
            ) {
                // 遍历单一真相源（三档），避免"档位加了 UI 忘了加"的漂移
                VisualQuality.entries.forEach { q ->
                    val label = when (q) {
                        VisualQuality.LOW -> stringResource(R.string.settings_visualizer_quality_low)
                        VisualQuality.MEDIUM -> stringResource(R.string.settings_visualizer_quality_medium)
                        VisualQuality.HIGH -> stringResource(R.string.settings_visualizer_quality_high)
                    }
                    val desc = when (q) {
                        VisualQuality.LOW -> stringResource(R.string.settings_visualizer_quality_low_desc)
                        VisualQuality.MEDIUM -> stringResource(R.string.settings_visualizer_quality_medium_desc)
                        VisualQuality.HIGH -> stringResource(R.string.settings_visualizer_quality_high_desc)
                    }
                    SettingActionButton(
                        label = if (state.visualizerQuality == q) "▶ $label" else label,
                        description = desc,
                        onClick = { actions.onChangeVisualizerQuality(q) }
                    )
                }
            }
            // 当前效果主题（只读）：主题切换在播放页全屏可视化里做（§1.2-✕15 的结论）
            // §4.5：只读行本身不带功能说明，但这里必须告诉用户"去哪儿改"—— 否则这行看起来是坏的
            SettingsInfoRow(
                stringResource(R.string.settings_visualizer_theme),
                state.settings.visualizerTheme.displayName,
                description = stringResource(R.string.settings_visualizer_theme_desc),
            )
        }
        // 卡② 封面滤镜
        SettingsGroupCard(title = stringResource(R.string.settings_group_cover_filter)) {
            SettingSwitch(
                label = stringResource(R.string.settings_cover_filter),
                description = stringResource(R.string.settings_cover_filter_desc),
                checked = state.coverFilterEnabled,
                onClick = { actions.onToggleCoverFilter(!state.coverFilterEnabled) }
            )
            if (state.coverFilterEnabled) {
                Text(
                    text = stringResource(R.string.settings_cover_blur_radius, state.coverFilterBlurRadius.toInt()),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(start = 4.dp)
                )
                SettingsHint(stringResource(R.string.settings_cover_blur_desc))
                // Blur radius buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AdjustButton("-", onClick = {
                        val new = (state.coverFilterBlurRadius - 2f).coerceAtLeast(0f)
                        actions.onChangeCoverBlurRadius(new)
                    })
                    Text(
                        text = "%.0fpx".format(state.coverFilterBlurRadius),
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.title(),
                        modifier = Modifier.width(64.dp).padding(horizontal = 8.dp)
                    )
                    AdjustButton("+", onClick = {
                        val new = (state.coverFilterBlurRadius + 2f).coerceAtMost(40f)
                        actions.onChangeCoverBlurRadius(new)
                    })
                }
                Text(
                    text = stringResource(R.string.settings_cover_dark_overlay, (state.coverFilterDarkOverlay * 100).toInt()),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    modifier = Modifier.padding(start = 4.dp)
                )
                SettingsHint(stringResource(R.string.settings_cover_dark_desc))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AdjustButton("-", onClick = {
                        val new = (state.coverFilterDarkOverlay - 0.1f).coerceAtLeast(0f)
                        actions.onChangeCoverDarkOverlay(new)
                    })
                    Text(
                        text = "${(state.coverFilterDarkOverlay * 100).toInt()}%",
                        color = NasMusicColors.Primary,
                        fontSize = FontSize.title(),
                        modifier = Modifier.width(64.dp).padding(horizontal = 8.dp)
                    )
                    AdjustButton("+", onClick = {
                        val new = (state.coverFilterDarkOverlay + 0.1f).coerceAtMost(1f)
                        actions.onChangeCoverDarkOverlay(new)
                    })
                }
            }
        }
    }
}
