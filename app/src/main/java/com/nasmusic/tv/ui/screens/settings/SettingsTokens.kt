package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 设置区尺寸与动效令牌（方案 §4.7）。
 *
 * 存在理由：同一语义此前各有 2–6 套取值（圆角 8 种、内边距 4 套、focusedScale 6 种、
 * 动画 4 种）。改任何一处只动这里，⛔ 不要在分区文件里写字面量。
 * 卡片外壳（`SettingsGroupCard`）用 `cardShape`，行用 `rowShape`，两级圆角不可互换 ——
 * 圆角一致 = 层级只靠明度与缩进区分，这正是 §4.1 的三阶明度阶梯成立的前提。
 *
 * 本文件**只放令牌定义**，⛔ 不放任何 composable（G7-A 的判据就是"分区文件里不再出现
 * 这些字面量"，令牌自身是唯一例外，靠这份点名清单豁免）。
 */
internal object SettingsTokens {
    val cardShape = RoundedCornerShape(18.dp)
    val rowShape = RoundedCornerShape(12.dp)
    val chipShape = RoundedCornerShape(10.dp)
    // 开关（§5.2，用户 2026-10-18 选定参考图中间那款）：轨道与滑块都是**完全圆端**，
    // 故半径 = 各自高度的一半；改高度必须同时改半径，否则不再是 stadium。
    val switchTrackShape = RoundedCornerShape(percent = 50)
    val switchThumbShape = RoundedCornerShape(percent = 50)

    val rowHPadding = 20.dp
    val rowVPadding = 16.dp
    val chipHPadding = 18.dp
    val chipVPadding = 10.dp

    val rowGap = 8.dp            // 卡内行间距
    val chipGap = 10.dp          // 同一行芯片之间（SettingsChipRow 用，§4.8）
    val cardGap = 16.dp          // 卡与卡间距（= SettingsGroupCard 的 bottom padding）
    val cardInnerHPadding = 10.dp

    // ↓ 开关几何：结构比例照抄参考图（滑块宽 = 轨道 50%、高 = 67%、描边 = 高的 3%），
    //   绝对尺寸由 TV 字号地板反推，推导过程与偏离记录见 §5.2
    val switchTrackWidth = 128.dp
    val switchTrackHeight = 36.dp
    val switchStroke = 1.dp
    val switchThumbInset = 6.dp          // 36 × 16.4% ≈ 5.9（参考图滑块上下各内缩 11px / 轨道高 67px）
    val switchThumbWidth = 58.dp         // 内宽 116 的 50%（参考图 49.7%）
    val switchThumbHeight = 24.dp        // 36 - 2×6
    val switchThumbTravel = 58.dp        // 内宽 116 - 滑块 58（= 滑块宽，行程恰好等于半宽）
    val chipMinWidth = 88.dp             // 芯片 wrap 后的下限，防同排宽度跳变（§4.8 A3）
    val infoValueWidth = 180.dp          // SettingsInfoRow 尾部值列固定宽（§4.8 A4：尾部不参与挤压）

    val focusScaleRow = 1.03f    // 整行瓦片
    val focusScaleChip = 1.06f   // 小尺寸可选项（芯片 / ± 按钮）
    val focusScaleDisabled = 1f  // 置灰不放大（有这条才不需要 Style-exempt，见 G7-A 判据）
    val pressScaleRow = 0.98f
    val pressScaleChip = 0.96f
    const val animMs = 220
}
