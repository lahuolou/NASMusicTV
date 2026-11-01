package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.LyricsHighlightMode
import com.nasmusic.tv.player.SeparationMode
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * 歌词与 K 歌分区状态（v2.39.0 从 `PlayerSettingsSection` 切出，方案 §3 / Task 3）。
 *
 * 字段与 `PlayerSettingsState` 被切出的那几个**同名同类型**，未重命名 —— 纯搬迁，
 * 便于逐行对照 diff。`settings` 保留（分离模式开关要读 `AppSettings`）。
 */
data class LyricsKaraokeSettingsState(
    val settings: AppSettings,
    val separationMode: SeparationMode,
    val modelDownloaded: Boolean,
    val modelDownloading: Boolean,
    val modelDownloadProgress: Float,
    val modelDownloadedMB: Long,
    val modelTotalMB: Long,
    val modelSizeMB: Double,
    val modelDownloadError: String?,
    val modelPath: String,
    val hqErrorDetail: String?,
    /** T10：歌词字号倍率（与播放页弹窗读同一条 Flow，⛔ 不要写成 Double） */
    val lyricsFontScale: Float = 1f,
    /** T10：歌词高亮方式（逐行 / 逐字） */
    val lyricsHighlightMode: LyricsHighlightMode = LyricsHighlightMode.LINE_BY_LINE,
    /** T10：分离模型自定义下载源（空串 = 用内置默认源） */
    val customModelUrl: String = "",
)

/** 歌词与 K 歌分区动作（可空性与默认值同 `PlayerSettingsActions` 原样） */
data class LyricsKaraokeActions(
    val onChangeSeparationMode: ((SeparationMode) -> Unit)?,
    val onDownloadModel: (() -> Unit)?,
    val onDeleteModel: (() -> Unit)?,
    val onScanTransferModel: (() -> Unit)?,
    /** 从本地文件导入分离模型（SAF 选择器，所有设备都显示；与扫码上传走同一套 SHA-256 校验） */
    val onImportModelFile: (() -> Unit)? = null,
    /** T10：歌词字号 / 高亮（与播放页共用同一份持久化 key） */
    val onChangeLyricsFontScale: (Float) -> Unit = {},
    val onChangeLyricsHighlightMode: (LyricsHighlightMode) -> Unit = {},
    /** T10：分离模型自定义源（编辑走 SettingsScreen 的 TextInputDialog 宿主，恢复默认直接写空串） */
    val onEditCustomModelUrl: (() -> Unit)? = null,
    val onResetCustomModelUrl: (() -> Unit)? = null,
)

/**
 * 分离模型卡里的一枚动作按钮。
 *
 * 存在理由：`SettingsChipRow` 要求同构元素（`List<T>` + 同一个 `itemContent`），
 * 而这三行按钮的 `label` / `description` 各不相同、`trailing` 与 `danger` 也不一致。
 * 收成一个小 holder 比给 `SettingsChipRow` 加一个「四元组」形参干净得多
 * （Kotlin 标准库没有 `Quadruple`，硬用 `Triple` 会丢掉 `trailing`/`danger` 两个语义）。
 */
private data class ModelAction(
    val label: String,
    val description: String,
    val onClick: () -> Unit,
    val trailing: Trail = Trail.None,
    val danger: Boolean = false,
)

/**
 * 歌词与 K 歌分区（人声分离模式 + 分离模型管理；歌词显示卡由 T10 追加）。
 *
 * 卡片构成（方案 §3.2，编号此后不再变）：卡②人声分离 / 卡③分离模型；
 * 卡①歌词显示在 T10 直接以 `SettingsGroupCard` 落地。
 *
 * ⚠️ Task 4 只加卡片外壳：卡内行级排布逐行未动（原 `PlayerSettingsSection.kt` 搬迁产物）。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LyricsKaraokeSettingsSection(
    state: LyricsKaraokeSettingsState,
    actions: LyricsKaraokeActions
) {
    Column {
        SectionTitle(stringResource(R.string.settings_lyrics_karaoke))
        // 卡① 歌词显示（T10 新增：字号 / 高亮与播放页弹窗共用同一份持久化 key，
        // 所以在设置页改完回到播放页立刻生效 —— 不存在"两份配置谁优先"的问题）
        SettingsGroupCard(title = stringResource(R.string.settings_group_lyrics)) {
            Text(
                text = stringResource(R.string.settings_lyrics_scale),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp)
            )
            // 说明位统一走 SettingsHint（§4.5）：芯片组没有 description 形参，
            // 原来手写的两行灰字与 SettingSwitch 的 description 是同一规格，收成一个组件。
            SettingsHint(stringResource(R.string.settings_lyrics_scale_desc))
            // 4 档与播放页 `LyricsSettingsDialog` 完全同源（0.7/1.0/1.3/1.6，复用同一批标签 key）
            Row(
                modifier = Modifier.padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LYRICS_FONT_SCALES.forEach { (scale, labelRes) ->
                    SettingsChip(
                        label = stringResource(labelRes),
                        selected = kotlin.math.abs(state.lyricsFontScale - scale) < 0.01f,
                        onClick = { actions.onChangeLyricsFontScale(scale) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_lyrics_highlight),
                color = NasMusicColors.TextPrimary,
                fontSize = FontSize.button(),
                modifier = Modifier.padding(start = 4.dp)
            )
            SettingsHint(stringResource(R.string.settings_lyrics_highlight_desc))
            Row(
                modifier = Modifier.padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsChip(
                    label = stringResource(R.string.player_highlight_line),
                    selected = state.lyricsHighlightMode == LyricsHighlightMode.LINE_BY_LINE,
                    onClick = { actions.onChangeLyricsHighlightMode(LyricsHighlightMode.LINE_BY_LINE) }
                )
                SettingsChip(
                    label = stringResource(R.string.player_highlight_word),
                    selected = state.lyricsHighlightMode == LyricsHighlightMode.WORD_BY_WORD,
                    onClick = { actions.onChangeLyricsHighlightMode(LyricsHighlightMode.WORD_BY_WORD) }
                )
            }
        }
        // 卡② 人声分离
        SettingsGroupCard(title = stringResource(R.string.settings_group_separation)) {
            run {
                val isHq = state.separationMode == SeparationMode.HIGH_QUALITY
                val hqLabel = if (state.modelDownloaded) stringResource(R.string.settings_hq_mode_downloaded) else stringResource(R.string.settings_hq_mode_not_downloaded)
                val hqDesc = when {
                    !state.modelDownloaded -> stringResource(R.string.settings_download_model_hint)
                    isHq -> stringResource(R.string.settings_onnx_inference)
                    else -> stringResource(R.string.settings_dsp_inference)
                }
                SettingSwitch(
                    label = hqLabel,
                    description = hqDesc,
                    checked = isHq,
                    enabled = state.modelDownloaded,
                    onClick = { actions.onChangeSeparationMode?.invoke(if (isHq) SeparationMode.FAST else SeparationMode.HIGH_QUALITY) }
                )
            }
        }
        // 卡③ 分离模型（下载中进度 / 已下载路径+删除 / 未下载下载按钮，三态互斥）
        // §4.5：三个按钮各自已有 description，这里补的是**卡级**说明（分离的代价与预期）
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_model),
            description = stringResource(R.string.settings_model_size_desc),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                if (state.modelDownloading) {
                    // 下载中：进度条
                    Text(
                        text = stringResource(R.string.settings_downloading_model) + "：${(state.modelDownloadProgress * 100).toInt()}%  (${state.modelDownloadedMB}MB / ${state.modelTotalMB}MB)",
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.body()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NasMusicColors.SurfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(state.modelDownloadProgress.coerceIn(0f, 1f))
                                .height(8.dp)
                                .background(NasMusicColors.Primary, RoundedCornerShape(4.dp))
                        )
                    }
                } else if (state.modelDownloaded) {
                    // 已下载：显示路径 + 大小 + 删除按钮
                    Column {
                        Text(
                            text = stringResource(R.string.settings_model_downloaded_size, state.modelSizeMB),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.body()
                        )
                        Text(
                            text = stringResource(R.string.settings_model_path, state.modelPath),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.small()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // §4.8 A3：等宽两枚的 `Box(Modifier.weight(1f))` 兜法已收进 SettingsChipRow，
                        // 调用方⛔ 不许再手抄；竖屏（407dp）降为每行 1 枚，各占整行 ⇒ 说明不再折 3 行。
                        SettingsChipRow(
                            items = listOf(
                                ModelAction(
                                    label = stringResource(R.string.settings_delete_model),
                                    description = stringResource(R.string.settings_delete_model_desc),
                                    onClick = { actions.onDeleteModel?.invoke() },
                                    // §4.7-③ S6：删的是已下载的分离模型，重建要重下一遍 ⇒ Warning 色
                                    danger = true,
                                ),
                                ModelAction(
                                    label = stringResource(R.string.settings_scan_upload_model),
                                    description = stringResource(R.string.settings_scan_upload_model_desc),
                                    onClick = { actions.onScanTransferModel?.invoke() },
                                    // 打开扫码传输对话框（下一级）
                                    trailing = Trail.Arrow,
                                ),
                                // 本机已有模型文件时不必走网络：直接挑本地 .onnx，
                                // 落盘后按与下载/上传完全一致的 SHA-256 标准校验
                                ModelAction(
                                    label = stringResource(R.string.settings_import_model_file),
                                    description = stringResource(R.string.settings_import_model_file_desc),
                                    onClick = { actions.onImportModelFile?.invoke() },
                                    trailing = Trail.Arrow,
                                ),
                            ),
                            tv = 2,
                            phonePortrait = 1,
                            equalWidth = true,
                        ) { item ->
                            SettingActionButton(
                                label = item.label,
                                description = item.description,
                                onClick = item.onClick,
                                trailing = item.trailing,
                                danger = item.danger,
                            )
                        }
                    }
                } else {
                    // 未下载：显示下载按钮 + 路径 + 扫码上传
                    Column {
                        Text(
                            text = stringResource(R.string.settings_network_model_not_downloaded),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.body()
                        )
                        Text(
                            text = stringResource(R.string.settings_storage_path, state.modelPath),
                            color = NasMusicColors.TextSecondary,
                            fontSize = FontSize.small()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        SettingsChipRow(
                            items = listOf(
                                ModelAction(
                                    label = stringResource(R.string.settings_download_separation_model),
                                    description = stringResource(R.string.settings_download_separation_model_desc),
                                    onClick = { actions.onDownloadModel?.invoke() },
                                ),
                                ModelAction(
                                    label = stringResource(R.string.settings_scan_upload_model),
                                    description = stringResource(R.string.settings_scan_upload_model_desc),
                                    onClick = { actions.onScanTransferModel?.invoke() },
                                    // 打开扫码传输对话框（下一级）
                                    trailing = Trail.Arrow,
                                ),
                                // 本机已有模型文件时不必走网络：直接挑本地 .onnx，
                                // 落盘后按与下载/上传完全一致的 SHA-256 标准校验
                                ModelAction(
                                    label = stringResource(R.string.settings_import_model_file),
                                    description = stringResource(R.string.settings_import_model_file_desc),
                                    onClick = { actions.onImportModelFile?.invoke() },
                                    trailing = Trail.Arrow,
                                ),
                            ),
                            tv = 2,
                            phonePortrait = 1,
                            equalWidth = true,
                        ) { item ->
                            SettingActionButton(
                                label = item.label,
                                description = item.description,
                                onClick = item.onClick,
                                trailing = item.trailing,
                                danger = item.danger,
                            )
                        }
                    }
                }
                state.modelDownloadError?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = err, color = NasMusicColors.Danger, fontSize = FontSize.small())
                }
            }
            // ── 自定义源（T10）：值与"恢复默认"都在这里；编辑走 SettingsScreen 的 TextInputDialog 宿主 ──
            SettingsInfoRow(
                label = stringResource(R.string.settings_model_custom_url),
                value = state.customModelUrl.ifBlank {
                    stringResource(R.string.settings_model_custom_url_builtin)
                },
                description = stringResource(R.string.settings_model_custom_url_desc)
            )
            SettingsChipRow(
                items = listOf(
                    ModelAction(
                        label = stringResource(R.string.settings_model_custom_url_edit),
                        description = stringResource(R.string.settings_model_custom_url_desc),
                        onClick = { actions.onEditCustomModelUrl?.invoke() },
                        // 打开端点编辑对话框（下一级）
                        trailing = Trail.Arrow,
                    ),
                    ModelAction(
                        label = stringResource(R.string.settings_reset_custom_model_url),
                        description = stringResource(R.string.settings_reset_custom_model_url_desc),
                        onClick = { actions.onResetCustomModelUrl?.invoke() },
                    ),
                ),
                tv = 2,
                phonePortrait = 1,
                equalWidth = true,
            ) { item ->
                SettingActionButton(
                    label = item.label,
                    description = item.description,
                    onClick = item.onClick,
                    trailing = item.trailing,
                    danger = item.danger,
                )
            }
            // ── 高质量分离失败详情（本轮长期留存，供用户对着屏幕截图回传）──
            state.hqErrorDetail?.let { detail ->
                Text(
                    text = stringResource(R.string.settings_hq_failure_detail_title),
                    color = NasMusicColors.Danger,
                    fontSize = FontSize.body()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NasMusicColors.SurfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = detail,
                        color = NasMusicColors.TextPrimary,
                        fontSize = FontSize.small()
                    )
                }
            }
        }
    }
}

/**
 * 歌词字号档位（与播放页 `LyricsSettingsDialog` 的 4 档完全一致）。
 *
 * ⛔ 不要改成"任意滑条"：0.7/1.0/1.3/1.6 是播放页既有的离散档，改成连续值会让
 * 同一首歌在两个页面显示成不同大小（两处读的是同一个 Float key）。
 */
private val LYRICS_FONT_SCALES: List<Pair<Float, Int>> = listOf(
    0.7f to R.string.lyrics_settings_size_small,
    1.0f to R.string.lyrics_settings_size_medium,
    1.3f to R.string.lyrics_settings_size_large,
    1.6f to R.string.lyrics_settings_size_xlarge,
)
