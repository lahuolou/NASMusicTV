package com.nasmusic.tv.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.LocalFocusableContentColor
import com.nasmusic.tv.ui.components.adaptiveColumns
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors

/**
 * R-2 拆分：设置页共享组件（原 SettingsScreen.kt 私有组件迁出并放开可见性）。
 *
 * 命名注意（W0 约定）：InfoRow 统一命名为 [SettingsInfoRow]，
 * 避免与 SongInfoPanel.InfoRow 同名混淆。
 */

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        color = NasMusicColors.Primary,
        fontSize = FontSize.subtitle(),
        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
    )
}

/**
 * 设置分组卡片：卡片头 + 内部留白；行组件保持原样嵌入（方案 §4.1/§4.2）。
 *
 * ⛔ 卡片**不可聚焦**（不加 `FocusableSurface`）：TV 上焦点必须逐行落在真实控件上，
 * 卡片参与聚焦会多出一跳、破坏既有 `navFocusRequesters` 节奏（§4.6）。
 */
@Composable
internal fun SettingsGroupCard(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = SettingsTokens.cardGap)
            .clip(SettingsTokens.cardShape)
            .background(NasMusicColors.Surface.copy(alpha = 0.45f))
            .border(1.dp, NasMusicColors.Border, SettingsTokens.cardShape)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp).height(14.dp)
                    .background(NasMusicColors.Primary, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = NasMusicColors.Primary,
                fontSize = FontSize.button(),
                fontWeight = FontWeight.Bold
            )
        }
        if (description != null) {
            Text(
                text = description,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.small(),
                modifier = Modifier.padding(start = 27.dp, end = 16.dp, bottom = 6.dp)
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsTokens.cardInnerHPadding).padding(bottom = SettingsTokens.cardInnerHPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsTokens.rowGap),
            content = content
        )
    }
}

/**
 * 设置区**唯一**的可聚焦瓦片（方案 §4.7-②，门禁 G7-B）。
 *
 * 存在理由：同一份 `FocusableSurface` 参数块此前在设置区有 7 份，圆角 4 种、`focusedScale`
 * 6 种、动画时长 4 种、按压缩放 3 种 —— 逐个组件手改参数必然再次漂移。这里收成一份，
 * 取值全部来自 [SettingsTokens]，分区里**不准**再出现裸 `FocusableSurface(`。
 *
 * 语义只有四个开关，覆盖设置页的全部瓦片形态：
 * - [enabled]：置灰。⚠️ `onClick` 里**必须**判一次 —— `FocusableSurface` 仍可聚焦，
 *   单靠颜色挡不住遥控器的「确认」键。
 * - [selected]：「已选中」的**唯一**表达（`Primary@0.18` 底 + Primary 字，§4.7-③ S3）。
 *   ⛔ 设置页不出现实底 Primary —— 那是唯一主 CTA（连接页「连接」按钮）的语义。
 * - [danger]：破坏性动作（§4.7-③ S6）。文字与焦点底都取 `Warning`；
 *   ⚠️ 方案 §4.7-② 的片段里 danger 会落进 `else` 分支拿到 `Primary@0.15` 焦点底，
 *   与 S6「焦点底也取 Warning」冲突 —— 这里以 S6 为准，danger 单列一支。
 * - [chip]：小尺寸可选项（芯片 / ± 按钮）。圆角、缩放、按压、外边距各降一档。
 *
 * ⛔ 别在这里加 `height()`：芯片的高度由调用方给（`portraitTouchTarget(48.dp)`），
 * 行的高度由内容撑开 —— 统一写死会把 `BackupFileRow` 的 64dp 方块也压扁。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
    chip: Boolean = false,
    content: @Composable () -> Unit,
) {
    val accent = if (danger) NasMusicColors.Warning else NasMusicColors.Primary
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.padding(vertical = if (chip) 0.dp else 4.dp),
        shape = if (chip) SettingsTokens.chipShape else SettingsTokens.rowShape,
        focusedScale = when {
            !enabled -> SettingsTokens.focusScaleDisabled
            chip -> SettingsTokens.focusScaleChip
            else -> SettingsTokens.focusScaleRow
        },
        pressedScale = if (chip) SettingsTokens.pressScaleChip else SettingsTokens.pressScaleRow,
        animationDurationMs = SettingsTokens.animMs,
        containerColor = when {
            !enabled -> NasMusicColors.Surface.copy(alpha = 0.5f)
            selected -> accent.copy(alpha = 0.18f)
            else -> NasMusicColors.Surface
        },
        contentColor = when {
            !enabled -> NasMusicColors.TextSecondary
            selected -> accent
            danger -> NasMusicColors.Warning
            else -> NasMusicColors.TextPrimary
        },
        focusedContainerColor = when {
            !enabled -> NasMusicColors.SurfaceVariant
            selected -> accent.copy(alpha = 0.3f)
            danger -> accent.copy(alpha = 0.18f)
            else -> NasMusicColors.Primary.copy(alpha = 0.15f)
        },
        focusedContentColor = when {
            !enabled -> NasMusicColors.TextSecondary
            danger -> accent
            else -> NasMusicColors.TextPrimary
        },
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f),
        content = content,
    )
}

/**
 * 行瓦片的统一内容排版：标签 + 说明（左，占权重）+ 尾部（右）。方案 §4.7-②。
 *
 * 说明用 `FontSize.small()` + 固定 [NasMusicColors.TextSecondary]（§4.7-④）：此前
 * `SettingSwitch` / `SettingActionButton` 的说明是 `FontSize.body()` + 跟随焦点的
 * `LocalFocusableContentColor`，而 GENERAL 的语言/方向说明是 `small()` + 固定色 ——
 * 同为"行说明"两套规格。变色还会让「标签 + 说明」在聚焦时同时提亮，反而削弱层级差。
 */
@Composable
private fun SettingsTileBody(
    label: String,
    description: String?,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = LocalFocusableContentColor.current, fontSize = FontSize.button())
            if (description != null) {
                Text(text = description, color = NasMusicColors.TextSecondary, fontSize = FontSize.small())
            }
        }
        trailing()
    }
}

/**
 * `SettingActionButton` 的尾部形态（§4.7-③ S5）：尾部只表达"这一行让我干什么"。
 *
 * - [Arrow]：**进入下一级**（打开页面 / 对话框 / 系统选择器）。
 * - [None]：**就地执行**（重扫、清除、下载、删除）。
 *
 * ⛔ 不再固定挂「确定」—— 每个入口都挂「确定」等于什么都没说（S5 要消灭的现状缺陷）。
 */
internal enum class Trail { None, Arrow }

/**
 * 开关轨（§5.2，用户 2026-10-18 三选一选定的中间款）。
 *
 * 尺寸全部来自 [SettingsTokens]，令牌值由 `scripts/measure_switch_reference.py` 量参考图得到，
 * ⛔ 不要就地改数字。
 *
 * ⛔ 轨道**不填充** —— 参考图内部取样就是页面渐变背景本身，只描边。
 * ⛔ 不套 `portraitTouchTarget` —— 整行瓦片才是热区，这 36dp 高不是触摸目标
 *   （conventions-adaptive-ui.md §6.5「容器即热区」的同一口径）。
 * ⛔ 滑块宽度**两态恒定** —— 随文案变宽会让「开」与「OFF」在切换时跳宽。
 *
 * @param enabled 置灰时整体降透明度。轨道画的是 `TextPrimary` / `TextSecondary`，
 *   不跟随 `contentColor`；不降透明度的话「置灰的开关」看起来和能点的一样亮。
 */
@Composable
private fun SwitchTrack(checked: Boolean, enabled: Boolean = true) {
    val onText = stringResource(R.string.settings_on)
    val offText = stringResource(R.string.settings_off)
    Box(
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.4f)
            .width(SettingsTokens.switchTrackWidth)
            .height(SettingsTokens.switchTrackHeight)
            .border(
                SettingsTokens.switchStroke,
                NasMusicColors.TextPrimary.copy(alpha = 0.8f),
                SettingsTokens.switchTrackShape,
            )
            // 内缩 = 滑块离轨道**外缘**的距离（参考图 11px / 轨道 67px ≈ 16.4% ⇒ 36 × 16.4% ≈ 6dp）。
            // ⚠️ 方案片段写的是 `switchThumbInset - switchStroke`（5dp），那会得到内宽 118 / 内高 26，
            //    与方案自己声明的「内宽 116、内高 24、行程 = 116 − 58 = 58」自相矛盾 —— 以几何表为准，取 6dp。
            .padding(SettingsTokens.switchThumbInset)
    ) {
        // 非活动标签先画，贴在另一侧（与滑块不重叠）
        Text(
            text = if (checked) offText else onText,
            color = NasMusicColors.TextSecondary,
            fontSize = FontSize.caption(),
            modifier = Modifier
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 8.dp),
        )
        val travel by animateDpAsState(
            targetValue = if (checked) 0.dp else SettingsTokens.switchThumbTravel,
            animationSpec = tween(SettingsTokens.animMs),
            label = "switchThumb",
        )
        Box(
            modifier = Modifier
                .offset(x = travel)
                .width(SettingsTokens.switchThumbWidth)
                .height(SettingsTokens.switchThumbHeight)
                .background(NasMusicColors.TextPrimary, SettingsTokens.switchThumbShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // 参考图：活动标签是深色字，取**页面背景色**而非纯黑
                text = if (checked) onText else offText,
                color = NasMusicColors.Background,
                fontSize = FontSize.caption(),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    // 文字没有消失，而是从"尾部提示"升格成开关本体的一部分（§5.2）：
    // 短标签进滑块，整词进 TalkBack —— 「开」读出来不成词，所以朗读另给整词。
    val stateText = stringResource(if (checked) R.string.settings_state_on else R.string.settings_state_off)
    SettingsTile(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label，$stateText" },
        enabled = enabled,
    ) {
        SettingsTileBody(label = label, description = description) {
            SwitchTrack(checked = checked, enabled = enabled)
        }
    }
}

/**
 * 芯片组 / 调整行上方的说明文本（§4.5）。
 *
 * 为什么需要它：`SettingSwitch` / `SettingActionButton` 都自带 `description` 形参，
 * 而**芯片组**（`SettingsChip` 排成一行）与**调整行**（`AdjustButton − / +`）没有说明位 ——
 * 分区里于是出现两种写法并存：有的卡用 `Text` 手写一行灰字，有的干脆没有。
 * 本组件是这两类行的**唯一**说明位，规格与 `SettingSwitch` 的 description 完全一致
 * （`TextSecondary` + `FontSize.small()`），保证同一张卡里两类行的说明看起来是一套。
 *
 * ⚠️ 自带 `bottom = 8.dp`：调用方**不要**再补 `Spacer(height = 8.dp)`，否则间距翻倍。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = NasMusicColors.TextSecondary,
        fontSize = FontSize.small(),
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/**
 * 播放模式选择器。
 *
 * @param description **必填、无默认值**（§4.5）：这个组件此前没有说明位，是"覆盖缺口"的源头之一。
 *   靠编译器强制调用方给说明，而不是靠门禁事后补 —— 门禁只能发现"少给了"，发现不了"该不该给"。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayModeSelector(
    current: PlayMode,
    onSelect: (PlayMode) -> Unit,
    description: String,
) {
    Column {
        Text(
            text = stringResource(R.string.settings_play_mode),
            color = NasMusicColors.TextPrimary,
            fontSize = FontSize.button(),
            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
        )
        SettingsHint(description)
        // v2.39.0 真机反馈：竖屏 407dp 一行放不下 4 枚「默认播放模式」芯片，第 4 枚被挤没
        // —— 与时长档同一条原语（SettingsChipRow）：竖屏 2×2 等宽，TV/横屏一行 4 枚。
        SettingsChipRow(
            items = PlayMode.values().toList(),
            tv = 4,
            phonePortrait = 2,
            equalWidth = true,
        ) { mode ->
            SettingsChip(
                label = stringResource(mode.displayNameRes),
                selected = current == mode,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingActionButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    /**
     * v2.35.0 修复：允许外部覆盖布局宽度。
     *
     * 默认仍为 `fillMaxWidth()`（占满整行，设置页纵向列表的既定样式）。
     * 但**放在 `Row` 里时必须传入固定宽度** —— 否则第一个按钮会撑满整行，
     * 后续按钮被挤成 0 宽（表现为"网络源音质只有『自动』一个选项"）。
     * 该缺陷在 v2.35.0 之前就存在（音质档位行原本就是 Row + 4 个此组件）。
     */
    modifier: Modifier = Modifier.fillMaxWidth(),
    /**
     * 阶段 11 新增：置灰（低画质档的人脸检测，§15.3 T11.4）。
     *
     * ⚠️ 与 [SettingSwitch] 的 `enabled` 同一套视觉口径（`Surface.copy(alpha = 0.5f)` +
     * `TextSecondary`），并且 `onClick` 里**必须**判一次 —— `FocusableSurface` 仍可聚焦，
     * 单靠颜色挡不住遥控器的「确认」键。
     */
    enabled: Boolean = true,
    /**
     * 尾部形态（§4.7-③ S5）：进入下一级 → [Trail.Arrow]，就地执行 → [Trail.None]。
     *
     * ⛔ 默认 `None`：多数按钮是"就地执行"，让调用方显式声明"这是个入口"。
     */
    trailing: Trail = Trail.None,
    /**
     * 破坏性动作（§4.7-③ S6）：文字与焦点底都取 `Warning`。
     *
     * 用于「清除全部下载 / 清除分离缓存 / 断开连接 / 删除备份」这四处 ——
     * 它们此前与普通按钮毫无差别，用户看不出"点下去会没东西"。
     */
    danger: Boolean = false,
) {
    SettingsTile(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        danger = danger,
    ) {
        SettingsTileBody(label = label, description = description) {
            if (trailing == Trail.Arrow) {
                Text(
                    text = "›",
                    color = if (danger) NasMusicColors.Warning else NasMusicColors.Primary,
                    fontSize = FontSize.button()
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BackupFileRow(
    file: com.nasmusic.tv.util.BackupFileUtils.BackupFile,
    onRestore: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsTile(
            onClick = onRestore,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = NasMusicColors.Primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = file.displayName, color = NasMusicColors.TextPrimary, fontSize = FontSize.button())
                    Text(
                        text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(file.lastModified)),
                        color = LocalFocusableContentColor.current,
                        fontSize = FontSize.body()
                    )
                }
                Text(
                    text = stringResource(R.string.settings_import_backup),
                    color = NasMusicColors.Primary,
                    fontSize = FontSize.body()
                )
            }
        }
        if (onDelete != null) {
            // 删除是设置区唯一一处**现状就已经用语义色**的瓦片（§4.7-③ S6 的样本），
            // 收口到 `danger = true` 后其余破坏性按钮在 T12 跟上。
            SettingsTile(
                onClick = onDelete,
                modifier = Modifier.size(64.dp),
                danger = true,
                chip = true,
            ) {
                Text(
                    text = stringResource(R.string.settings_delete_backup),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.body(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxSize().padding(vertical = 20.dp)
                )
            }
        }
    }
}

@Composable
internal fun AboutRow(label: String, value: String) {
    // 修复（2026-09-22 用户反馈）：原「标签 + weight Spacer + 值」结构在值过长时
    // Spacer 塌缩、值紧跟标签（视觉上忽左忽右）。改为固定双栏：标签占左（超长换行），
    // 值恒右对齐（超长换行也按右缘对齐）。手机 / TV 一致。
    Box(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding)
    ) {
        SettingsLabelValueRow(
            label = label,
            value = value,
            labelFontSize = FontSize.button(),
            valueFontSize = FontSize.button(),
            valueWeight = FontWeight.Normal,
        )
    }
}

/**
 * 「标签 + 值」的唯一版式（方案 §4.7-④ / §4.8 A4）：标签占满剩余宽度、值恒右对齐。
 *
 * 存在理由：`AboutRow` 与 `SettingsInfoRow` 是同一个版式的两套实现，后者用
 * `Arrangement.SpaceBetween` 且**不限宽** —— 长值会把标签挤没（竖屏实测）。收成一份后
 * 两处共用同一条 `Row`。⛔ 不要给值加 `maxLines`：长值必须折行，不许省略号截断。
 *
 * 两档尾部宽度（[valueMaxWidth]）：
 * - `null` → 值列按 `weight(1.5f)` 吃 60%（`AboutRow` 用）。**能力说明那几行的值是整句**
 *   （「网易云 / QQ音乐 / 酷狗 / 酷我 / 咪咕(Meting-API 端点)」），定宽会把 TV 上的一行
 *   压成三四行，属可感知回退 ⇒ 说明性行保持比例分配。
 * - 给定值 → 值列**最多**这么宽（`SettingsInfoRow` 传 `infoValueWidth` = 180dp，§4.8 A4）。
 *   指标行的值都是「1.23 GB」「12」这类短串：用 `widthIn(max = …)` 而不是 `width(…)`，
 *   短值不占满 180dp（省下的宽度自动还给标签），长值（自定义源 URL）在这个上限内折行。
 *
 * ⚠️ 标签列用 `weight(1f)`（`fill = true`），**不能**用 A4 字面的 `fill = false`：
 * `fill = false` 时长标签只占自身宽度，`Row` 的剩余空间无人认领、值会紧跟标签，
 * 右侧留一大块空白 —— 正是本函数下方那条「竖屏修正」注释里记的「值把标签挤没 / 值忽左忽右」
 * 同一类排版事故的另一种形态。
 */
@Composable
private fun SettingsLabelValueRow(
    label: String,
    value: String,
    labelFontSize: androidx.compose.ui.unit.TextUnit,
    valueFontSize: androidx.compose.ui.unit.TextUnit,
    valueWeight: FontWeight,
    valueMaxWidth: androidx.compose.ui.unit.Dp? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = NasMusicColors.TextSecondary,
            fontSize = labelFontSize,
            modifier = Modifier.weight(1f)
        )
        // 竖屏修正（20:40 用户反馈）：值不限定宽度时长文本会吃掉整行、把 weight
        // 标签挤没。`valueMaxWidth` 档给值列一个上限（尾部不参与挤压），
        // 比例档则按 标签 1 : 值 1.5 分配（约 40%/60%），两档都是恒右对齐。
        Text(
            text = value,
            color = NasMusicColors.TextPrimary,
            fontSize = valueFontSize,
            fontWeight = valueWeight,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = if (valueMaxWidth != null) {
                Modifier.widthIn(max = valueMaxWidth).padding(start = 16.dp)
            } else {
                Modifier.weight(1.5f).padding(start = 16.dp)
            }
        )
    }
}

/**
 * 格式化 [com.nasmusic.tv.data.model.VersionInfo] 为 (label, value) 对。
 *
 * - Static：有版本 → (服务名, 版本号)；有 description → (服务名, 版本号·描述)
 * - Runtime：有版本 → (服务名, 版本号)；无 → (服务名, 未连接)
 * - NoVersion：无版本 → (服务名, "") 仅展示服务名
 * - Disconnected：(服务名, 未连接)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun formatVersionInfo(v: com.nasmusic.tv.data.model.VersionInfo): Pair<String, String> {
    return when (v) {
        is com.nasmusic.tv.data.model.VersionInfo.Static -> {
            if (v.description.isNotBlank()) {
                v.serviceName to "${v.version} · ${v.description}"
            } else {
                v.serviceName to v.version
            }
        }
        is com.nasmusic.tv.data.model.VersionInfo.Runtime -> {
            v.serviceName to v.version
        }
        is com.nasmusic.tv.data.model.VersionInfo.NoVersion -> {
            v.serviceName to ""
        }
        is com.nasmusic.tv.data.model.VersionInfo.Disconnected -> {
            v.serviceName to stringResource(R.string.settings_not_connected)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun AdjustButton(text: String, onClick: () -> Unit) {
    SettingsTile(
        onClick = onClick,
        // 48dp 在竖屏只有 39.4 物理 dp ❌ → §2.7 换算抬到 56dp（TV/横屏保持 48dp，B1）
        // ⚠️ 这个字面量是 `SmallTouchTargetScanTest` 的抓手，⛔ 不要令牌化（§4.7-⑥ 已说明）。
        modifier = Modifier.size(portraitTouchTarget(48.dp)),
        chip = true,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = text, color = LocalFocusableContentColor.current, fontSize = FontSize.title(), fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 设置页共享芯片（单选组用）。
 *
 * 从 `PhotoWallSettingsSection` 的私有 `OptionChip` 提升而来；T11 起**内部委托
 * [SettingsTile]**，选中态因此与行瓦片共用同一种表达（`Primary@0.18` 底 + Primary 字，
 * §4.7-③ S3）—— 此前芯片是**实底 Primary + 黑字**，与行瓦片的 `0.18` 底是两套。
 *
 * ⛔ **宽度必须由内容决定**（文字 + padding），不能用 `fillMaxSize()`：
 * 本组件总是被放在 `Row` 里并排，而 `Row` 给每个 wrap-content 子项的最大宽度是
 * 「本行剩余宽度」——`fillMaxSize()` 会取这个最大值 ⇒ **第一个 chip 撑满整行，
 * 同排后续 chip 被挤成 0 宽**（v2.37.0「画面适配只显示满屏」、v2.35.0「音质只有自动」
 * 两次线上实例，护栏见 `ChipContentWidthScanTest`）。
 * 高度由 `portraitTouchTarget(48.dp)` 给定（同样是那道门的抓手，⛔ 别换写法），故内容只填高。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsTile(
        onClick = onClick,
        modifier = modifier.height(portraitTouchTarget(48.dp)),
        selected = selected,
        chip = true,
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = SettingsTokens.chipHPadding),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = LocalFocusableContentColor.current,
                fontSize = FontSize.button(),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                // 芯片恒单行：高度被 `portraitTouchTarget(48.dp)` 钉死，折行会溢出。
                maxLines = 1
            )
        }
    }
}

/**
 * 芯片组按形态分行：竖屏每行 [phonePortrait] 枚，TV / 手机横屏每行 [tv] 枚。
 *
 * 存在理由：`Row` 不会换行，芯片多了只能被挤扁或压成 0 宽（方案 §4.8 ①②）。
 * 手动分行而不是 FlowRow / horizontalScroll —— 后两种在 D-Pad 下焦点会跑出屏幕或滚不出去
 * （`PhotoWallSettingsSection` 的 43 个转场芯片早就是这个做法，本原语把它收成一个调用点）。
 *
 * [equalWidth] 是给「带长说明的整行瓦片」（模型下载那类）用的：需要等宽两枚时，
 * `Box(Modifier.weight(1f))` 这个兜法只在**这里**出现一次（A3：调用方⛔ 不许手抄）；
 * 芯片（短标签档位）不要开，让它们按内容 wrap。
 *
 * ⚠️ 间距统一走 [SettingsTokens.chipGap]（10dp），比原来手写的 12dp 紧 2dp ——
 * 这是「令牌统一」的直接后果，不是排版回归；V17 判读按「排布形态不变」看，不按逐像素。
 *
 * ⚠️ `itemContent` 的接收者是 [RowScope]（不是普通 lambda）—— `equalWidth` 分支里的
 * `Modifier.weight(1f)` 只有在这个接收者下才编译得过，这也正是"兜法只能出现在这一处"的技术保证。
 */
@Composable
internal fun <T> SettingsChipRow(
    items: List<T>,
    tv: Int = items.size,
    phonePortrait: Int = minOf(items.size, 2),
    medium: Int = tv,
    equalWidth: Boolean = false,
    modifier: Modifier = Modifier,
    itemContent: @Composable RowScope.(T) -> Unit,
) {
    val columns = adaptiveColumns(tv = tv, phonePortrait = phonePortrait, medium = medium)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SettingsTokens.rowGap),
    ) {
        items.chunked(columns.coerceAtLeast(1)).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(SettingsTokens.chipGap)) {
                // 把 RowScope 接收者固定为局部量：`equalWidth` 分支里 Box 的 content 是
                // BoxScope 作用域，itemContent（@Composable RowScope.(T) -> Unit）的
                // 第一接收者无法再隐式解析 ⇒ 必须显式 `rowScope.itemContent(item)`。
                val rowScope = this
                rowItems.forEach { item ->
                    if (equalWidth) Box(modifier = Modifier.weight(1f)) { rowScope.itemContent(item) }
                    else itemContent(item)
                }
            }
        }
    }
}

/**
 * 下载统计行：标签 + 值（W0 约定重命名：原 InfoRow → SettingsInfoRow）。
 *
 * 版式与 `AboutRow` 共用 [SettingsLabelValueRow]（标签占满剩余、值恒右对齐）——
 * 此前这里用 `Arrangement.SpaceBetween` 且不限宽，长值会把标签挤没（§4.7-④）。
 * 水平边距取 `rowHPadding`，与行瓦片的内边距对齐，标签不再比上面的开关行凸出 20dp。
 * 值列走 §4.8 A4 的**封顶**档（`infoValueWidth` = 180dp）：指标行的值都是短串，
 * 封顶后短值不白占宽度、长值也不会把标签挤没；说明性长句仍归 `AboutRow`（比例档）。
 *
 * @param description 只读行按 §4.5 规范**默认不带**功能说明；只有该项"删了有代价"
 *   需要警示时才给（T8b 的 9 条缓存代价说明）。`null` = 不占第二行。
 * @param indent 从属行缩进（T8b 占用一览的分组 → 条目两级）；分组行/顶层行保持 `false`。
 */
@Composable
internal fun SettingsInfoRow(
    label: String,
    value: String,
    description: String? = null,
    indent: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            start = SettingsTokens.rowHPadding + if (indent) 16.dp else 0.dp,
            end = SettingsTokens.rowHPadding,
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            SettingsLabelValueRow(
                label = label,
                value = value,
                labelFontSize = FontSize.body(),
                valueFontSize = FontSize.body(),
                valueWeight = FontWeight.Medium,
                // §4.8 A4：指标行的尾部值列封顶 infoValueWidth，长值不许把标签挤没。
                valueMaxWidth = SettingsTokens.infoValueWidth,
            )
        }
        if (description != null) {
            Text(
                text = description,
                color = NasMusicColors.TextSecondary,
                fontSize = FontSize.caption(),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/** 格式化字节数为人类可读字符串 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        else -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    }
}
