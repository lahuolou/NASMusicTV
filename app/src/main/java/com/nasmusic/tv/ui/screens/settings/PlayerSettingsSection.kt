package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import kotlin.math.roundToInt

/**
 * 播放设置分区状态（v2.39.0 收窄：人声分离/模型 → `LyricsKaraokeSettingsSection`，
 * 可视化画质/封面滤镜 → `VisualEffectsSettingsSection`，方案 §3 / Task 3）。
 *
 * [equalizerPresetName] 由 SettingsBranch 传当前预设显示名（只读），
 * Task 4 的均衡器卡以只读行呈现它；改预设本身在 EqualizerScreen 里做。
 */
data class PlayerSettingsState(
    val settings: AppSettings,
    /** F2-5：crossfade 开关/时长 */
    val crossfadeEnabled: Boolean = false,
    val crossfadeDurationSec: Int = 4,
    /** F2-7：应用内音量（应用级增益 0.0–1.0，独立于系统音量） */
    val appVolume: Float = 1f,
    /** F2-6：音质档位（AUTO=0/999/320/128） */
    val qualityTier: Int = 0,
    /** 当前均衡器预设显示名（只读） */
    val equalizerPresetName: String = "",
)

/** 播放设置分区动作（同上，已迁走域的回调一并删除） */
data class PlayerSettingsActions(
    val onToggleAutoPlayNext: (Boolean) -> Unit,
    val onChangePlayMode: (PlayMode) -> Unit,
    val onOpenEqualizer: (() -> Unit)?,
    /** F2-5：crossfade 开关/时长 */
    val onToggleCrossfade: (Boolean) -> Unit = {},
    val onChangeCrossfadeDuration: (Int) -> Unit = {},
    /** F2-7：应用内音量（应用级增益 0.0–1.0，独立于系统音量） */
    val onChangeAppVolume: (Float) -> Unit = {},
    /** F2-6：音质档位 */
    val onChangeQualityTier: (Int) -> Unit = {},
    /** v2.35.0 多码率：清除全部单曲音质覆盖（方案 §5.3） */
    val onClearQualityOverrides: (() -> Unit)? = null,
)

/**
 * 播放设置分区（原 SettingsScreen PLAYBACK 分支，逻辑逐行搬迁；四卡构成见方案 §3.2）。
 *
 * ⚠️ Task 4 只做了「加一层卡片外壳」：行内排布一律未动 ——
 * 卡②的 5 个时长按钮 Row 既不给宽度也没有选中态，属**线上既有缺陷**，
 * 已由 **T13** 修掉（改走 `SettingsChipRow` + `SettingsChip`，并补上选中态）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerSettingsSection(
    state: PlayerSettingsState,
    actions: PlayerSettingsActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_playback))
        // 卡① 播放行为（§3.2）
        SettingsGroupCard(title = stringResource(R.string.settings_group_play_behavior)) {
            SettingSwitch(label = stringResource(R.string.settings_auto_play), description = stringResource(R.string.settings_auto_play_desc), checked = state.settings.autoPlayNext, onClick = { actions.onToggleAutoPlayNext(!state.settings.autoPlayNext) })
            PlayModeSelector(
                current = state.settings.defaultPlayMode,
                onSelect = { actions.onChangePlayMode(it) },
                description = stringResource(R.string.settings_play_mode_desc),
            )
        }
        // 卡② 过渡与音量（crossfade + 时长档位 + 应用内音量）
        SettingsGroupCard(title = stringResource(R.string.settings_group_transition_volume)) {
            SettingSwitch(
                label = stringResource(R.string.settings_crossfade),
                description = stringResource(R.string.settings_crossfade_desc),
                checked = state.crossfadeEnabled,
                onClick = { actions.onToggleCrossfade(!state.crossfadeEnabled) }
            )
            if (state.crossfadeEnabled) {
                // 时长档位 2/4/6/8/12 秒（TV 遥控适配的离散选择，比滑条更易 D-Pad 操作）。
                // v2.39.0 T13：原来是 `Row + forEach + SettingActionButton`，五枚全走默认
                // `fillMaxWidth()` ⇒ Row 给 wrap 子项的 maxWidth 是「行内剩余宽度」，
                // 第一枚「2s」吃满整行、后四档被挤成 0 宽（线上既有缺陷，只有「2s」可见）。
                // 竖屏 407dp 也放不下 5 枚 ⇒ 每行 3 枚（§4.8 A2）；TV / 手机横屏仍是一行 5 枚（B1）。
                // 芯片组没有自带说明位 ⇒ 上方补 SettingsHint（§4.5）
                SettingsHint(stringResource(R.string.settings_crossfade_duration_desc))
                SettingsChipRow(
                    items = listOf(2, 4, 6, 8, 12),
                    tv = 5,
                    phonePortrait = 3,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                ) { sec ->
                    // 顺带补上选中态：原来 5 枚完全看不出当前是哪一档（同一行本来就缺，非新增功能）
                    SettingsChip(
                        label = "${sec}s",
                        selected = state.crossfadeDurationSec == sec,
                        onClick = { actions.onChangeCrossfadeDuration(sec) },
                    )
                }
            }
            // ── 应用内音量（应用级增益 0.0–1.0，独立于系统音量）──
            // 仿封面滤镜的 AdjustButton 行：− [居中百分比] +，5% 步进（0–100）。
            // 与封面滤镜的差异：百分比用 weight(1f) + 居中文本把 − / + 拉到整行两端，
            // 视觉上是"一条音量条"而非"左侧一簇按钮"，D-Pad 左右各一格的节奏更明确。
            Text(
                text = stringResource(R.string.settings_app_volume),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp)
            )
            Text(
                text = stringResource(R.string.settings_app_volume_desc),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.small(),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdjustButton("-", onClick = {
                    val pct = (state.appVolume * 100).roundToInt()
                    actions.onChangeAppVolume((pct - 5).coerceAtLeast(0) / 100f)
                })
                Text(
                    text = "${(state.appVolume * 100).roundToInt()}%",
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.title(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                AdjustButton("+", onClick = {
                    val pct = (state.appVolume * 100).roundToInt()
                    actions.onChangeAppVolume((pct + 5).coerceAtMost(100) / 100f)
                })
            }
        }
        // 卡③ 音质（全局档位 + 卡片级说明 + 清除逐曲覆盖）
        // §4.5：原来散落在卡内底部的两条说明 Text 已合并进卡片 description（≤26 汉字，
        // 效果导向），不再在列表末尾追加两段灰字。
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_quality),
            description = stringResource(R.string.settings_group_quality_desc),
        ) {
            // F2-6：音质分级（仅 Meting 网络源生效；NAS 原品质直传）
            Text(
                text = stringResource(R.string.settings_quality_tier),
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp)
            )
            // v2.35.0 修复：改为**纵向列表**。
            //
            // 原实现把 5 个档位放在 `Row` 里，但 `SettingActionButton` 内部是
            // `Modifier.fillMaxWidth()`（设置页纵向列表的既定样式）—— 在 Row 中第一个按钮
            // 会撑满整行宽度，后续按钮被挤成 0 宽，用户只看到「自动」一个选项
            // （实测反馈："网络源音质选项中，只有一个自动"）。该缺陷在 v2.35.0 之前就存在。
            //
            // 纵向列表同时解决了另外两个问题：
            // ① 该组件右侧会渲染固定的「确定」文案，横向排列时 5 个「确定」很易误读；
            // ② 手机竖屏宽度有限，5 个按钮横排必然溢出（与码率面板同类问题）。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 4.dp)
            ) {
                // 遍历单一真相源（含 192 档），避免"常量加了 UI 忘了加"的漂移
                QualityTiers.all.forEach { tier ->
                    val label = stringResource(QualityTiers.labelResOf(tier))
                    SettingActionButton(
                        label = if (state.qualityTier == tier) "▶ $label" else label,
                        description = if (tier == QualityTiers.AUTO) {
                            stringResource(R.string.quality_tier_auto_desc)
                        } else {
                            QualityTiers.descriptionOf(tier)
                        },
                        onClick = { actions.onChangeQualityTier(tier) }
                    )
                }
            }
            // v2.35.0 多码率：清除全部单曲音质覆盖（方案 §5.3）
            Spacer(modifier = Modifier.height(8.dp))
            SettingActionButton(
                label = stringResource(R.string.quality_override_clear),
                description = stringResource(R.string.quality_override_clear_desc),
                onClick = { actions.onClearQualityOverrides?.invoke() }
            )
        }
        // 卡④ 均衡器：入口 + 当前预设名（只读；改预设在 EqualizerScreen）
        SettingsGroupCard(title = stringResource(R.string.settings_group_equalizer)) {
            SettingActionButton(
                label = stringResource(R.string.settings_equalizer),
                description = stringResource(R.string.settings_equalizer_desc),
                onClick = { actions.onOpenEqualizer?.invoke() },
                // 跳到均衡器页面（下一级）
                trailing = Trail.Arrow,
            )
            SettingsInfoRow(
                label = stringResource(R.string.settings_equalizer),
                value = state.equalizerPresetName
            )
        }
    }
}
