package com.nasmusic.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.nasmusic.tv.R

/**
 * 设置分区枚举（v2.36.0 由 `SettingsScreen.kt` 的 private enum 上移至此并放开可见性）。
 *
 * ⚠️ 上移原因：竖屏两级页需要把「当前进入的分区」提升到 `NavigationViewModel`
 * （`AppRoot` 的 BACK handler 要读它，见方案 §6.2 / §8.7 / K2）——
 * `NavigationViewModel` 在 `ui/viewmodel/`，若枚举留在 `ui/screens/SettingsScreen.kt`，
 * 会出现 viewmodel → screens 的反向依赖；放到 `ui/screens/settings/` 子包后仍属
 * 「设置域」，依赖方向可接受（方案 §8.7 规避方案 ①）。
 *
 * 可见性取 `public`（方案原文写 internal）：`NavigationViewModel` 与 `SettingsScreen`
 * 都是 public 成员，暴露 internal 类型会编译失败（`'public' function exposes its
 * 'internal' parameter type`）。模块内无外部消费方，公开无实际风险。
 *
 * ⚠️ **v2.39.0 重分类**（方案 §3，用户 2026-10-18 裁决「按内容归属划分，不追求各 tab
 * 条数均衡」）：SERVER / NETDISK / LOCAL_MUSIC 三个 tab 并为 **SOURCES**（它们都是
 * 「曲库从哪来」的一种来源）；PLAYBACK 里的可视化画质 + 封面滤镜独立为 **VISUAL_EFFECTS**；
 * 人声分离 + 模型管理独立为 **LYRICS_KARAOKE**；DOWNLOAD / CACHE 因域不同**保持独立 tab**。
 *
 * ⚠️ 播放统计（`onOpenPlayStats`）、均衡器（`onOpenEqualizer`）**不是分区**，
 * 是 PLAYBACK / GENERAL 分区内的入口按钮，行为不变。
 *
 * ⚠️ 本项目**没有**持久化「分区顺序」，也没有任何测试断言 `entries` 的顺序
 * ⇒ 插入与重排安全（既有结论，重分类后仍成立）。
 */
enum class SettingsSection(val titleRes: Int, val icon: ImageVector) {
    GENERAL(R.string.settings_general, Icons.Default.Settings),           // 外观 · 语言 · 方向
    PLAYBACK(R.string.settings_playback, Icons.Default.Audiotrack),       // 播放行为 · 过渡音量 · 音质 · 均衡器
    /** 新增：歌词显示（字号/高亮，T10）+ K 歌人声分离与模型管理 */
    LYRICS_KARAOKE(R.string.settings_lyrics_karaoke, Icons.Default.Mic),
    /** 新增：可视化画质与效果主题 + 封面滤镜。⚠️ 图标名 AutoFilmed 在 icons 1.7.8 里不存在（编译实证），换 AutoAwesome */
    VISUAL_EFFECTS(R.string.settings_visual_effects, Icons.Default.AutoAwesome),
    /** 照片墙（§7.1 / §7.2），原地不动 */
    PHOTO_WALL(R.string.settings_photo_wall, Icons.Default.PhotoLibrary),
    /** 新增：NAS 服务器 + 本地曲库 + 百度网盘 + 默认网络源，同为「曲库从哪来」 */
    SOURCES(R.string.settings_sources, Icons.Default.Storage),
    /** 离线副本的生命周期（开关/上限/位置/清除/导出），与「来源」「缓存」都是不同域 ⇒ 保持独立 */
    DOWNLOAD(R.string.settings_download, Icons.Default.Download),
    /** 存储与缓存：缓存策略 + 占用与清理（T8b） */
    CACHE(R.string.settings_cache, Icons.Default.Tune),
    /** 端点与凭据配置（原 settings_network，重分类后改名「网络高级设置」） */
    NETWORK(R.string.settings_network_advanced, Icons.Default.Wifi),
    /** 备份 · 歌单导入 · 播放统计 */
    DATA(R.string.settings_data, Icons.Default.MusicNote),
    /** 版本信息 · 连接信息 · 能力说明 · 开源与许可 */
    ABOUT(R.string.settings_about, Icons.Default.Info),
    /**
     * v2.39.4 新增：运行日志（警告/错误持久化，可反复回看）。
     *
     * 排在最后：它是排障用的"兜底入口"，不是日常配置项。
     * 图标 `BugReport` 已对照 icons 1.7.8 的 jar 实证存在（⛔ 别换成未验证的名字，
     * `AutoFilmed` 就是这么编译失败的）。
     */
    LOGS(R.string.settings_logs, Icons.Default.BugReport),
}
