package com.nasmusic.tv.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.AppSettings
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.NetworkSource
import com.nasmusic.tv.data.model.PlayMode
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.ui.components.BaiduDirPickerDialog
import com.nasmusic.tv.ui.components.ConfirmDialog
import com.nasmusic.tv.ui.components.FocusableSurface
import com.nasmusic.tv.ui.components.portraitTouchTarget
import com.nasmusic.tv.ui.components.responsiveDialogSize
import com.nasmusic.tv.ui.screens.netdisk.BaiduAuthDialog
import com.nasmusic.tv.ui.screens.settings.AboutSettingsSection
import com.nasmusic.tv.ui.screens.settings.AboutSettingsState
import com.nasmusic.tv.ui.screens.settings.CacheSettingsActions
import com.nasmusic.tv.ui.screens.settings.CacheSettingsSection
import com.nasmusic.tv.ui.screens.settings.CacheSettingsState
import com.nasmusic.tv.ui.screens.settings.DataSettingsActions
import com.nasmusic.tv.ui.screens.settings.DataSettingsSection
import com.nasmusic.tv.ui.screens.settings.DataSettingsState
import com.nasmusic.tv.ui.screens.settings.DownloadSettingsActions
import com.nasmusic.tv.ui.screens.settings.DownloadSettingsSection
import com.nasmusic.tv.ui.screens.settings.DownloadSettingsState
import com.nasmusic.tv.ui.screens.settings.GeneralSettingsActions
import com.nasmusic.tv.ui.screens.settings.GeneralSettingsSection
import com.nasmusic.tv.ui.screens.settings.GeneralSettingsState
import com.nasmusic.tv.ui.screens.settings.LyricsKaraokeActions
import com.nasmusic.tv.ui.screens.settings.LyricsKaraokeSettingsSection
import com.nasmusic.tv.ui.screens.settings.LyricsKaraokeSettingsState
import com.nasmusic.tv.ui.screens.settings.LogsSettingsActions
import com.nasmusic.tv.ui.screens.settings.LogsSettingsSection
import com.nasmusic.tv.ui.screens.settings.LogsSettingsState
import com.nasmusic.tv.util.LogSnapshot
import com.nasmusic.tv.util.LogStore
import com.nasmusic.tv.ui.screens.settings.NetworkMusicDialogActions
import com.nasmusic.tv.ui.screens.settings.NetworkMusicSection
import com.nasmusic.tv.ui.screens.settings.NetworkMusicSettingsActions
import com.nasmusic.tv.ui.screens.settings.NetworkMusicSettingsState
import com.nasmusic.tv.ui.screens.settings.PlayerSettingsActions
import com.nasmusic.tv.ui.screens.settings.PlayerSettingsSection
import com.nasmusic.tv.ui.screens.settings.PlayerSettingsState
import com.nasmusic.tv.ui.screens.settings.PhotoWallRuntimeState
import com.nasmusic.tv.ui.screens.settings.PhotoWallSettingsActions
import com.nasmusic.tv.ui.screens.settings.PhotoWallSettingsSection
import com.nasmusic.tv.ui.screens.settings.PhotoWallSettingsState
import com.nasmusic.tv.ui.screens.settings.SourcesActions
import com.nasmusic.tv.ui.screens.settings.SourcesDialogActions
import com.nasmusic.tv.ui.screens.settings.SourcesSettingsSection
import com.nasmusic.tv.ui.screens.settings.SourcesSettingsState
import com.nasmusic.tv.ui.screens.settings.SettingsSection
import com.nasmusic.tv.ui.screens.settings.SettingsSectionBackHeader
import com.nasmusic.tv.ui.screens.settings.SettingsSectionList
import com.nasmusic.tv.ui.screens.settings.VisualEffectsActions
import com.nasmusic.tv.ui.screens.settings.VisualEffectsSettingsSection
import com.nasmusic.tv.ui.screens.settings.VisualEffectsSettingsState
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.LocalUiMode
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.theme.UiMode

/**
 * 设置屏幕 — 左侧为导航侧边栏（settings-sidebar），右侧为具体选项（settings-content）
 *
 * R-2 拆分：各分区内容已迁至 ui/screens/settings/ 下的 Section Composable
 * （State/Actions data class 分组签名，W0 冻结版）；本文件仅保留侧栏导航、
 * 分区路由、对话框宿主与对外参数签名（AppRoot 引用不变）。
 *
 * v2.36.0：竖屏（`UiMode.PhonePortrait`）改为**两级页** —— 一级为分区列表、
 * 二级为分区内容全屏 + 返回头；`selectedSection` 状态由 `NavigationViewModel` 持有
 * （`AppRoot` 的 BACK handler 需要读它，方案 §6.2 / §8.7 / K2）。
 * TV 与手机横屏**保持现状左右分栏，零改动**（方案 §3.1 B1 硬规则）。
 */

@OptIn(
    ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleAnimations: (Boolean) -> Unit,
    onToggleAutoPlayNext: (Boolean) -> Unit,
    onChangePlayMode: (PlayMode) -> Unit,
    onToggleCacheLyrics: (Boolean) -> Unit,
    onToggleCacheCover: (Boolean) -> Unit,
    onClearLyricsCache: (() -> Unit)? = null,
    onClearCoverCache: (() -> Unit)? = null,
    onClearMvCache: (() -> Unit)? = null,
    /** T8b：分离产物与临时残留（原「清除全部缓存」改名 + 扩容后的真正入口） */
    onClearSeparationArtifacts: (() -> Unit)? = null,
    /** T8b：封面地址表 / 解包封面两个新增清理入口 */
    onClearCoverUrlCache: (() -> Unit)? = null,
    onClearExtractedCoverCache: (() -> Unit)? = null,
    /** T8b：剩余空间（StorageGuard.availableBytes，⛔ 不要再起一个 StatFs） */
    storageAvailableBytes: Long = 0L,
    onOpenEqualizer: (() -> Unit)? = null,
    onOpenPlayStats: (() -> Unit)? = null,
    // F2-5：跨曲交叉淡入淡出
    crossfadeEnabled: Boolean = false,
    crossfadeDurationSec: Int = 4,
    onToggleCrossfade: (Boolean) -> Unit = {},
    onChangeCrossfadeDuration: (Int) -> Unit = {},
    // F2-7：应用内音量（应用级增益 0.0–1.0，独立于系统音量）
    appVolume: Float = 1f,
    onChangeAppVolume: (Float) -> Unit = {},
    // F2-6：音质档位
    qualityTier: Int = 0,
    onChangeQualityTier: (Int) -> Unit = {},
    /** v2.35.0 多码率：清除全部单曲音质覆盖（方案 §5.3） */
    onClearQualityOverrides: (() -> Unit)? = null,
    onChangeMetingApiBaseUrl: ((String) -> Unit)? = null,
    // MTV 视频搜索端点配置
    mvApiBaseUrl: String = "",
    onChangeMvApiBaseUrl: ((String) -> Unit)? = null,
    // 网络歌词端点配置
    lyricsKugouBaseUrl: String = "",
    onChangeLyricsKugouBaseUrl: ((String) -> Unit)? = null,
    lyricsNeteaseBaseUrl: String = "",
    onChangeLyricsNeteaseBaseUrl: ((String) -> Unit)? = null,
    // Jamendo（CC 独立音乐）Client ID
    jamendoClientId: String = "",
    onChangeJamendoClientId: ((String) -> Unit)? = null,
    // 封面滤镜设置
    coverFilterEnabled: Boolean = false,
    coverFilterBlurRadius: Float = 8f,
    coverFilterDarkOverlay: Float = 0.3f,
    onToggleCoverFilter: (Boolean) -> Unit = {},
    onChangeCoverBlurRadius: (Float) -> Unit = {},
    onChangeCoverDarkOverlay: (Float) -> Unit = {},
    // 天气 API Key 设置
    weatherApiKey: String = "",
    onChangeWeatherApiKey: ((String) -> Unit)? = null,
    // ── T16（方案 §5.4）：网络探针状态由 MainViewModel 持有，UI 只渲染 + 发意图 ──
    isNetworkTesting: Boolean = false,
    networkTestStatus: String = "",
    onRunNetworkProbe: () -> Unit = {},
    // 频谱显示设置
    // 全局字体字号调整
    fontAdjustment: Int = 0,
    onChangeFontAdjustment: (Int) -> Unit = {},
    // 语言设置
    language: String = "system",
    onChangeLanguage: ((String) -> Unit)? = null,
    // 分离模式设置
    separationMode: com.nasmusic.tv.player.SeparationMode = com.nasmusic.tv.player.SeparationMode.FAST,
    onChangeSeparationMode: ((com.nasmusic.tv.player.SeparationMode) -> Unit)? = null,
    // 歌曲离线下载设置
    downloadEnabled: Boolean = true,
    autoDownloadOnPlay: Boolean = false,
    autoDownloadLimit: Int = 50,
    downloadLocation: String = "INTERNAL",
    onToggleDownloadEnabled: ((Boolean) -> Unit)? = null,
    onToggleAutoDownloadOnPlay: ((Boolean) -> Unit)? = null,
    onChangeAutoDownloadLimit: ((Int) -> Unit)? = null,
    onChangeDownloadLocation: ((String) -> Unit)? = null,
    onClearAllDownloads: (() -> Unit)? = null,
    // 下载统计信息
    downloadStats: com.nasmusic.tv.backend.download.DownloadStats = com.nasmusic.tv.backend.download.DownloadStats(),
    // 导出到外接设备
    exportState: com.nasmusic.tv.backend.export.ExportState = com.nasmusic.tv.backend.export.ExportState.Idle,
    onExportToDevice: (() -> Unit)? = null,
    onCancelExport: (() -> Unit)? = null,
    onResetExportState: (() -> Unit)? = null,
    // 高质量分离模型下载状态
    modelDownloaded: Boolean = false,
    modelDownloading: Boolean = false,
    modelDownloadProgress: Float = 0f,
    modelDownloadedMB: Long = 0L,
    modelTotalMB: Long = 0L,
    modelSizeMB: Double = 0.0,
    modelDownloadError: String? = null,
    modelPath: String = "",
    /** 高质量分离失败的完整诊断详情（设置页长期留存，供用户截图回传） */
    hqErrorDetail: String? = null,
    onDownloadModel: (() -> Unit)? = null,
    onDeleteModel: (() -> Unit)? = null,
    onRefreshModelStatus: (() -> Unit)? = null,
    onScanTransferModel: (() -> Unit)? = null,
    /** 从本地文件导入分离模型（SAF 选择器，所有设备都显示） */
    onImportModelFile: (() -> Unit)? = null,
    // ── T10：歌词显示（与播放页弹窗共用同一份持久化 key）──
    lyricsFontScale: Float = 1f,
    lyricsHighlightMode: com.nasmusic.tv.data.model.LyricsHighlightMode =
        com.nasmusic.tv.data.model.LyricsHighlightMode.LINE_BY_LINE,
    onChangeLyricsFontScale: (Float) -> Unit = {},
    onChangeLyricsHighlightMode: (com.nasmusic.tv.data.model.LyricsHighlightMode) -> Unit = {},
    // ── T10：分离模型自定义下载源（空串 = 内置默认源）──
    customModelUrl: String = "",
    onChangeCustomModelUrl: ((String) -> Unit)? = null,
    onResetCustomModelUrl: (() -> Unit)? = null,
    // T1.6.4（§十三 裁决项 9=A）：可视化画质三档（低/中/高）
    visualizerQuality: VisualQuality = VisualQuality.Default,
    onChangeVisualizerQuality: (VisualQuality) -> Unit = {},
    /** 当前均衡器预设显示名（只读；PLAYBACK 卡④呈现，改预要在 EqualizerScreen） */
    equalizerPresetName: String = "",
    // 数据管理（备份/恢复）
    backupFiles: List<com.nasmusic.tv.util.BackupFileUtils.BackupFile> = emptyList(),
    backupMessage: com.nasmusic.tv.data.model.BackupMessage? = null,
    onRefreshBackupFiles: (() -> Unit)? = null,
    onExportBackup: (() -> Unit)? = null,
    onImportBackup: ((Uri) -> Unit)? = null,
    onDeleteBackup: ((Uri) -> Unit)? = null,
    onConsumeBackupMessage: (() -> Unit)? = null,
    onScanTransferBackup: (() -> Unit)? = null,
    /** 本地文件导入备份（SAF，launcher 由 SettingsBranch 持有） */
    onImportBackupFile: (() -> Unit)? = null,
    // 歌单导入（2026-09-18：§4.4.2 导入入口 + 最近导入记录 + 消息）
    playlistImportHistory: List<com.nasmusic.tv.data.model.PlaylistImportHistoryItem> = emptyList(),
    playlistImportMessage: com.nasmusic.tv.data.model.BackupMessage? = null,
    onImportPlaylistFile: (() -> Unit)? = null,
    /** 从本地文件导入歌单（SAF 选择器） */
    onImportPlaylistLocalFile: (() -> Unit)? = null,
    onOpenImportedPlaylist: ((String) -> Unit)? = null,
    onConsumePlaylistImportMessage: (() -> Unit)? = null,
    // 服务器连接设置
    serverConfig: com.nasmusic.tv.data.model.ServerConfig = com.nasmusic.tv.data.model.ServerConfig.Empty,
    isConnected: Boolean = false,
    serverDisplayName: String = "",
    backendApiVersion: String = "Unknown",
    // 全量后端/服务的 API 版本号聚合（供关于页展示）
    apiVersions: List<com.nasmusic.tv.data.model.VersionInfo> = emptyList(),
    isConnecting: Boolean = false,
    onConnect: ((com.nasmusic.tv.data.model.ServerConfig) -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null,
    // 百度网盘设置
    baiduEnabled: Boolean = false,
    baiduLoggedIn: Boolean = false,
    baiduConnecting: Boolean = false,
    baiduConnectionState: com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState = com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.Off,
    baiduDeviceCode: com.nasmusic.tv.backend.network.baidu.BaiduOAuthClient.DeviceCodeResult? = null,
    baiduMusicRootDir: String = com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.APP_DIR,
    baiduMvDir: String? = null,
    baiduIndexScanned: Int = 0,
    baiduIndexScanning: Boolean = false,
    baiduApicExtracting: Boolean = false,
    baiduApicExtracted: Int = 0,
    baiduApicTotal: Int = 0,
    onToggleBaiduEnabled: ((Boolean) -> Unit)? = null,
    onStartBaiduDeviceCode: (() -> Unit)? = null,
    onCancelBaiduDeviceCode: (() -> Unit)? = null,
    onLogoutBaidu: (() -> Unit)? = null,
    onChangeBaiduMusicRootDir: ((String) -> Unit)? = null,
    onChangeBaiduMvDir: ((String?) -> Unit)? = null,
    onListBaiduDirs: (suspend (String) -> List<BaiduFile>)? = null,
    onRebuildBaiduIndex: (() -> Unit)? = null,
    onNavigateToServerConnect: (() -> Unit)? = null,
    // T7：改网络搜索默认源（SOURCES 卡⑤，写 DataStore）
    onChangeDefaultNetworkSource: (NetworkSource) -> Unit = {},
    // v2.36.0 竖屏两级页（方案 §4.6 / §8.7）：当前进入的分区（null = 一级列表）
    selectedSection: SettingsSection? = null,
    onOpenSection: (SettingsSection) -> Unit = {},
    onCloseSection: () -> Unit = {},
    // v2.36.0 屏幕方向（L1 全局策略；L2 顶部栏按钮只是快捷改它）
    screenOrientation: String = "auto",
    onChangeScreenOrientation: ((String) -> Unit)? = null,
    /**
     * 照片墙分区的动作（§7.2）。
     *
     * ⚠️ **设置状态不在这里**：17 个 `photoWall*` 字段全在 [settings]（`AppSettings`）里，
     * 分区直接读它 ⇒ 只多这一个参数，而不是 17 个字段 + 19 个回调。
     */
    photoWallActions: PhotoWallSettingsActions = PhotoWallSettingsActions(),
    /**
     * 照片墙的**运行时派生状态**（阶段 9 引入）：权限三态、目录拒绝原因、
     * 各来源照片数、人脸检测进度。
     *
     * ⚠️ 单独成参数而不是塞进 [settings]：这些值**不落盘、不进备份**，
     * 与 `AppSettings` 的语义完全不同（`AppSettings` 是「用户选的」）。
     * 阶段 10/11 只需往 [PhotoWallRuntimeState] 里加字段，**不再动本签名**。
     */
    photoWallRuntime: PhotoWallRuntimeState = PhotoWallRuntimeState(),
    /**
     * 本地音乐（SOURCES 卡②，§5.6 权限瘦身）：开关值在 [settings]（localMusicEnabled）里，
     * 这里只传运行时计数（Room 已入库数，不落盘）与两个动作 ——
     * v2.39.0 起三个旧分区并为 SOURCES，不再需要 LocalMusicSettingsState/Actions 载体。
     */
    localMusicLibraryCount: Int = 0,
    onSetLocalMusicEnabled: (Boolean) -> Unit = {},
    onRescanLocalMusic: () -> Unit = {},
    /**
     * v2.39.4：运行日志快照（LOGS 分区）。
     *
     * 数据来自 `LogStore` 的落盘文件，**重启后仍在**；与 [settings] 分开传
     * ——它是运行时事实，不落盘、不进备份（同 [photoWallRuntime] 的口径）。
     */
    logs: LogSnapshot = LogSnapshot(),
    onRefreshLogs: () -> Unit = {},
    onClearLogs: () -> Unit = {},
    logsMinLevel: String = LogStore.LEVEL_DEBUG,
    onSelectLogsMinLevel: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
var activeSection by remember { mutableStateOf(SettingsSection.GENERAL) }
    // 结果消息的屏幕级落点：分区内那一行会被列表滚动甩出视野（真机上「导入成功却毫无提示」
    // 就是这么来的），同一条消息还必须进 Toast —— 见 BackupResultVisibilityGateTest
    val toastContext = LocalContext.current.applicationContext
    // 竖屏只认 PhonePortrait；TV 与手机横屏走现状两栏（B1 硬规则）
    val isPortraitPhone = LocalUiMode.current == UiMode.PhonePortrait
    // 分区路由主体：竖屏用 navVM 的 selectedSection（非空时才会渲染内容），否则用两栏的 activeSection
    val displaySection: SettingsSection = if (isPortraitPhone) {
        selectedSection ?: SettingsSection.GENERAL
    } else {
        activeSection
    }
    // 双形态状态统一（2026-10-06）：竖屏选中分区（navVM 持久）后，横屏/TV 的局部
    // activeSection 跟随同步 ⇒ 竖屏二级页 → 旋转/切横屏时侧栏高亮与内容不丢分区。
    // ⛔ 反向不行：activeSection 是两栏局部状态，**不写回 navVM.settingsSection**——
    //    TV 端 settingsSection 恒为 null（AppRoot BACK 语义约束，见 AppRoot.kt:229 注释）。
    //    selectedSection 为 null（一级列表）时不覆盖，保留横屏上次停留分区。
    LaunchedEffect(selectedSection) {
        if (selectedSection != null) activeSection = selectedSection
    }

    // D-Pad 焦点修复：内容区按左键移回左侧导航栏
    // 根因：右侧内容区（LazyColumn）与左侧导航栏（verticalScroll Column）是两个独立滚动容器，
    // 播放设置等内容超长（整段为单个 LazyColumn item，含多组横排按钮）时，
    // 系统几何查找无法从内容区内部"走出"到左栏——表现为按左键焦点卡住或原地不动。
    // 方案：内容区声明为 focusGroup，向左越界（exit）时强制聚焦左侧导航栏当前分区项。
    // 每个分区项持有自己的 FocusRequester；activeSection 变化时重新解析目标项。
    val navFocusRequesters = remember { mutableMapOf<SettingsSection, FocusRequester>() }
    // ⚠️ G3 接线门禁（SettingsSectionWiringGateTest）要求每个分区在 SettingsScreen 里
    //    以 `SettingsSection.X` 字面出现 ≥2 次（when 分支 + 这里）。上面的泛型
    //    `entries.forEach` 不产字面 ⇒ 这里显式列举，新增枚举值漏登记会被门禁抓住。
    //    ⛔ 不要改回 entries.forEach：那会让门禁对每个新分区退化成「只剩 when 一处」。
    listOf(
        SettingsSection.GENERAL,
        SettingsSection.PLAYBACK,
        SettingsSection.LYRICS_KARAOKE,
        SettingsSection.VISUAL_EFFECTS,
        SettingsSection.PHOTO_WALL,
        SettingsSection.SOURCES,
        SettingsSection.DOWNLOAD,
        SettingsSection.CACHE,
        SettingsSection.NETWORK,
        SettingsSection.DATA,
        SettingsSection.ABOUT,
        SettingsSection.LOGS,
    ).forEach { section ->
        navFocusRequesters.getOrPut(section) { remember { FocusRequester() } }
    }

    // T16（方案 §5.3）：端点/文本编辑对话框的**唯一**运行状态。
    // 原 7 个端点/Key `show*Dialog` 布尔（Meting/MV/天气/歌词×2/Jamendo/自定义模型源）
    // 与 3 个 error 幽灵状态（metingUrlError / mvUrlError / lyricsUrlError —— 只写不读，
    // 校验失败的真实反馈只有「对话框不关」）随收敛删除；打开对话框 = 构造一个
    // [EndpointEditor]，确认/取消逻辑整体搬进它的 onConfirm。
    // 百度目录的文本回退分支同样走本宿主（目录树形态仍由 showBaidu*Dialog 布尔承载）。
    var endpointEditor by remember { mutableStateOf<EndpointEditor?>(null) }

    // P1-17: 清空所有下载确认弹窗
    var showClearDownloadsConfirm by remember { mutableStateOf(false) }

    // 待删除的备份文件（非空时显示确认弹窗）
    var backupToDelete by remember {
        mutableStateOf<com.nasmusic.tv.util.BackupFileUtils.BackupFile?>(null)
    }

    // 百度网盘设备码授权对话框显隐
    var showBaiduAuthDialog by remember { mutableStateOf(false) }

    // 百度目录**树选择**对话框显隐（T16 后只承载 BaiduDirPickerDialog 形态；
    // 无目录树回调时的文本回退由 endpointEditor 承载，onShow 回调里分流）
    var showBaiduMusicRootDialog by remember { mutableStateOf(false) }
    var showBaiduMvDirDialog by remember { mutableStateOf(false) }

    // 百度网盘本地编辑值（参数仅作初始值；编辑后本地立即生效，回调负责持久化）
    var baiduMusicRootLocal by remember { mutableStateOf(baiduMusicRootDir) }
    var baiduMvDirLocal by remember { mutableStateOf(baiduMvDir) }

    // 进入"数据管理"分区时刷新备份文件列表
    // 2026-09-25 审查修复（8-2）：竖屏两级页下 activeSection 恒为 GENERAL（侧栏不渲染），
    // key 用 activeSection 会让手机进 DATA 分区永不刷新。displaySection 横屏==activeSection、
    // 竖屏==selectedSection，两端语义统一。
    LaunchedEffect(displaySection) {
        if (displaySection == SettingsSection.DATA) onRefreshBackupFiles?.invoke()
    }

    // v2.39.4：进入「日志」分区时刷新一次 —— 日志是外部（AppLog / 崩溃处理器）随时
    // 追加的，不在进页时读一次，页面会一直停在进页前的旧快照上。
    LaunchedEffect(displaySection) {
        if (displaySection == SettingsSection.LOGS) onRefreshLogs()
    }

    // 备份操作结果消息显示后自动消费
    //
    // ⚠️ v2.36.2：**失败消息不自动消费**（真机反馈「点恢复静默失败无提示」）。
    // 恢复入口在备份文件列表的每一行里，用户点完仍停在列表位置；若消息 4s 就消失，
    // 等于什么提示都没有。失败信息保留到用户离开该分区 / 下一次操作覆盖它为止，
    // 配合下方「有新消息就滚到分区顶部」保证一定看得见。成功提示仍 4s 自动消失。
    LaunchedEffect(backupMessage) {
        val msg = backupMessage
        if (msg != null) {
            // 屏幕级落点：分区内那一行在列表顶部，SAF 返回时列表还在惯性滚动、
            // `animateScrollToItem` 会被取消并被 runCatching 吞掉 ⇒ 只靠那一行等于没提示。
            Toast.makeText(toastContext, msg.text, Toast.LENGTH_LONG).show()
            if (!msg.isError) {
                kotlinx.coroutines.delay(4000)
                onConsumeBackupMessage?.invoke()
            }
        }
    }

    // 备份结果消息出现在分区**顶部** → 把分区列表滚回顶部，否则用户在列表下方看不到它
    // （备份文件列表可能很长；DATA 分区整体是一个 item，故下标 0 即分区顶部）
    val sectionListState = rememberLazyListState()
    LaunchedEffect(backupMessage) {
        if (backupMessage != null) {
            runCatching { sectionListState.animateScrollToItem(0) }
        }
    }

    // 歌单导入结果消息显示后自动消费（镜像 backupMessage 4s 消费）
    LaunchedEffect(playlistImportMessage) {
        val msg = playlistImportMessage
        if (msg != null) {
            // 同 backupMessage：结果必须有一处不随列表滚动的落点
            Toast.makeText(toastContext, msg.text, Toast.LENGTH_LONG).show()
            kotlinx.coroutines.delay(4000)
            onConsumePlaylistImportMessage?.invoke()
        }
    }

    // T16：预解析字符串区已清空 —— 端点对话框的 title/hint 一律存 resId，
    // 由通用宿主在 Composable 作用域内 stringResource 解析（onShow 回调是非
    // Composable 上下文，不能调 stringResource）

    Row(
        modifier = modifier.fillMaxSize().padding(
            when {
                isPortraitPhone -> 16.dp
                LocalUiMode.current == UiMode.PhoneLandscape -> 16.dp
                else -> 32.dp
            }
        )
    ) {
        // --- 左侧：侧边导航栏（bg2 Surface 背景）---
        // v2.36.0：竖屏两级页不显示侧栏（改为分区列表 → 二级全屏内容）
        if (!isPortraitPhone) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(NasMusicColors.Surface)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 头部
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                Box(
                    modifier = Modifier.size(32.dp).background(
                        NasMusicColors.Primary,
                        shape = RoundedCornerShape(8.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, tint = NasMusicColors.TextPrimary, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = stringResource(R.string.nav_settings), color = NasMusicColors.TextPrimary, fontSize = FontSize.title())
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSection.entries.forEach { section ->
                val selected = section == activeSection
                FocusableSurface(
                    onClick = { activeSection = section },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .focusRequester(navFocusRequesters.getValue(section)),
                    shape = RoundedCornerShape(12.dp),
                    focusedScale = 1.08f,
                    animationDurationMs = 250,
                    containerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.18f) else Color.Transparent,
                    contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f) else NasMusicColors.SurfaceVariant,
                    focusedContentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary,
                    pressedScale = 0.97f
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(imageVector = section.icon, contentDescription = null, tint = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = stringResource(section.titleRes), color = if (selected) NasMusicColors.Primary else NasMusicColors.TextPrimary, fontSize = FontSize.button())
                    }
                }
            }
        }
        } // if (!isPortraitPhone) 侧栏结束

        // --- 右侧：具体设置项（R-2：各分区已迁至 settings/ 子包，按域组装 State/Actions）---
        // v2.36.0 竖屏两级页（方案 §4.6）：
        //   一级（selectedSection == null）→ 分区列表
        //   二级（非 null）→ 返回头 + 单列分区内容（复用下方同一份 when 实现）
        if (isPortraitPhone && selectedSection == null) {
            SettingsSectionList(onPick = onOpenSection)
        } else {
        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
        if (isPortraitPhone) {
            SettingsSectionBackHeader(section = displaySection, onBack = onCloseSection)
        }
        // focusGroup + 左向 exit 重定向：任何分区内容按左键无法继续左移时，焦点回到导航栏当前分区项
        LazyColumn(
            // v2.36.2：备份结果消息出现在分区顶部时滚回顶部（见上方 LaunchedEffect）
            state = sectionListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = if (isPortraitPhone) 0.dp else 24.dp)
                .focusGroup()
                .focusProperties {
                    exit = {
                        if (it == FocusDirection.Left) {
                            if (!isPortraitPhone) navFocusRequesters.getValue(activeSection).requestFocus()
                            FocusRequester.Default
                        } else {
                            FocusRequester.Default
                        }
                    }
                }
        ) {
            when (displaySection) {
                SettingsSection.GENERAL -> item {
                    GeneralSettingsSection(
                        state = GeneralSettingsState(
                            settings = settings,
                            language = language,
                            fontAdjustment = fontAdjustment,
                            screenOrientation = screenOrientation,
                        ),
                        actions = GeneralSettingsActions(
                            onChangeLanguage = onChangeLanguage,
                            onToggleDarkTheme = onToggleDarkTheme,
                            onToggleAnimations = onToggleAnimations,
                            onChangeFontAdjustment = onChangeFontAdjustment,
                            onChangeScreenOrientation = onChangeScreenOrientation,
                        )
                    )
                }
                SettingsSection.PLAYBACK -> item {
                    PlayerSettingsSection(
                        state = PlayerSettingsState(
                            settings = settings,
                            // F2-5：crossfade
                            crossfadeEnabled = crossfadeEnabled,
                            crossfadeDurationSec = crossfadeDurationSec,
                            // F2-7：应用内音量
                            appVolume = appVolume,
                            // F2-6：音质档位
                            qualityTier = qualityTier,
                            equalizerPresetName = equalizerPresetName,
                        ),
                        actions = PlayerSettingsActions(
                            onToggleAutoPlayNext = onToggleAutoPlayNext,
                            onChangePlayMode = onChangePlayMode,
                            onOpenEqualizer = onOpenEqualizer,
                            onToggleCrossfade = onToggleCrossfade,
                            onChangeCrossfadeDuration = onChangeCrossfadeDuration,
                            onChangeAppVolume = onChangeAppVolume,
                            onChangeQualityTier = onChangeQualityTier,
                            onClearQualityOverrides = onClearQualityOverrides,
                        )
                    )
                }
                SettingsSection.PHOTO_WALL -> item {
                    PhotoWallSettingsSection(
                        state = PhotoWallSettingsState(
                            settings = settings,
                            // ⚠️ 直接用 isTVDevice()（@Composable，进程内缓存），
                            //   不新增签名参数：设备类型不是「设置状态」，加参数只会
                            //   让本来就很长的签名再长一行，且所有调用点都要跟着改。
                            isTV = com.nasmusic.tv.ui.components.isTVDevice(),
                            nasConnected = isConnected,
                            runtime = photoWallRuntime,
                        ),
                        actions = photoWallActions,
                    )
                }
                SettingsSection.LYRICS_KARAOKE -> item {
                    LyricsKaraokeSettingsSection(
                        state = LyricsKaraokeSettingsState(
                            settings = settings,
                            separationMode = separationMode,
                            modelDownloaded = modelDownloaded,
                            modelDownloading = modelDownloading,
                            modelDownloadProgress = modelDownloadProgress,
                            modelDownloadedMB = modelDownloadedMB,
                            modelTotalMB = modelTotalMB,
                            modelSizeMB = modelSizeMB,
                            modelDownloadError = modelDownloadError,
                            modelPath = modelPath,
                            hqErrorDetail = hqErrorDetail,
                            lyricsFontScale = lyricsFontScale,
                            lyricsHighlightMode = lyricsHighlightMode,
                            customModelUrl = customModelUrl,
                        ),
                        actions = LyricsKaraokeActions(
                            onChangeSeparationMode = onChangeSeparationMode,
                            onDownloadModel = onDownloadModel,
                            onDeleteModel = onDeleteModel,
                            onScanTransferModel = onScanTransferModel,
                            onImportModelFile = onImportModelFile,
                            onChangeLyricsFontScale = onChangeLyricsFontScale,
                            onChangeLyricsHighlightMode = onChangeLyricsHighlightMode,
                            // T16：自定义源编辑并入通用宿主（原 showCustomModelUrlDialog）
                            onEditCustomModelUrl = {
                                endpointEditor = EndpointEditor(
                                    titleRes = R.string.settings_model_custom_url,
                                    hintRes = R.string.settings_model_custom_url_desc,
                                    value = customModelUrl,
                                ) { input ->
                                    val trimmed = input.trim()
                                    // scheme 不合法时**不关闭对话框**（用户可继续改）；真正的取值校验仍在
                                    // `AppPreferences.setModelDownloadUrl` → `normalizeEndpointUrl`，这里只是提前挡一下。
                                    // 空串 = 恢复内置默认源（与「恢复默认源」按钮同一条路径）
                                    if (trimmed.isEmpty() || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                        onChangeCustomModelUrl?.invoke(trimmed)
                                        endpointEditor = null
                                    }
                                }
                            },
                            onResetCustomModelUrl = onResetCustomModelUrl,
                        )
                    )
                }
                SettingsSection.VISUAL_EFFECTS -> item {
                    VisualEffectsSettingsSection(
                        state = VisualEffectsSettingsState(
                            settings = settings,
                            visualizerQuality = visualizerQuality,
                            coverFilterEnabled = coverFilterEnabled,
                            coverFilterBlurRadius = coverFilterBlurRadius,
                            coverFilterDarkOverlay = coverFilterDarkOverlay,
                        ),
                        actions = VisualEffectsActions(
                            onChangeVisualizerQuality = onChangeVisualizerQuality,
                            onToggleCoverFilter = onToggleCoverFilter,
                            onChangeCoverBlurRadius = onChangeCoverBlurRadius,
                            onChangeCoverDarkOverlay = onChangeCoverDarkOverlay,
                        )
                    )
                }
                SettingsSection.SOURCES -> item {
                    SourcesSettingsSection(
                        state = SourcesSettingsState(
                            isConnected = isConnected,
                            serverDisplayName = serverDisplayName,
                            settings = settings,
                            libraryCount = localMusicLibraryCount,
                            baiduEnabled = baiduEnabled,
                            baiduLoggedIn = baiduLoggedIn,
                            baiduConnectionState = baiduConnectionState,
                            baiduMusicRootDirLocal = baiduMusicRootLocal,
                            baiduMvDirLocal = baiduMvDirLocal,
                            baiduIndexScanned = baiduIndexScanned,
                            baiduIndexScanning = baiduIndexScanning,
                            baiduApicExtracting = baiduApicExtracting,
                            baiduApicExtracted = baiduApicExtracted,
                            baiduApicTotal = baiduApicTotal,
                            defaultNetworkSource = settings.defaultNetworkSource,
                        ),
                        actions = SourcesActions(
                            onNavigateToServerConnect = onNavigateToServerConnect,
                            onDisconnect = onDisconnect,
                            onSetEnabled = onSetLocalMusicEnabled,
                            onRescan = onRescanLocalMusic,
                            onToggleBaiduEnabled = onToggleBaiduEnabled,
                            onStartBaiduDeviceCode = onStartBaiduDeviceCode,
                            onLogoutBaidu = onLogoutBaidu,
                            onRebuildBaiduIndex = onRebuildBaiduIndex,
                            onChangeDefaultNetworkSource = onChangeDefaultNetworkSource,
                        ),
                        dialogs = SourcesDialogActions(
                            onShowBaiduAuthDialog = { showBaiduAuthDialog = true },
                            // T16：有目录树回调 → 目录树形态；否则回退通用端点宿主（文本输入），
                            // 原 else 分支的独立 TextInputDialog 已删除（§5.3）
                            onShowMusicRootDialog = {
                                if (onListBaiduDirs != null) {
                                    showBaiduMusicRootDialog = true
                                } else if (onChangeBaiduMusicRootDir != null) {
                                    // 与原对话框外层条件 `&& onChangeBaiduMusicRootDir != null` 等价：
                                    // 回退为 null 时点击无反应，不弹输入框
                                    endpointEditor = EndpointEditor(
                                        titleRes = R.string.settings_netdisk_music_root,
                                        hintRes = R.string.settings_netdisk_hint_dir,
                                        value = baiduMusicRootLocal,
                                    ) { input ->
                                        val trimmed = input.trim()
                                        if (trimmed.isNotEmpty()) {
                                            baiduMusicRootLocal = trimmed
                                            onChangeBaiduMusicRootDir?.invoke(trimmed)
                                        }
                                        endpointEditor = null
                                    }
                                }
                            },
                            onShowMvDirDialog = {
                                if (onListBaiduDirs != null) {
                                    showBaiduMvDirDialog = true
                                } else if (onChangeBaiduMvDir != null) {
                                    endpointEditor = EndpointEditor(
                                        titleRes = R.string.settings_netdisk_mv_dir,
                                        hintRes = R.string.settings_netdisk_hint_dir,
                                        value = baiduMvDirLocal.orEmpty(),
                                    ) { input ->
                                        val trimmed = input.trim()
                                        baiduMvDirLocal = trimmed.ifEmpty { null }
                                        onChangeBaiduMvDir?.invoke(baiduMvDirLocal)
                                        endpointEditor = null
                                    }
                                }
                            },
                        )
                    )
                }
                SettingsSection.DOWNLOAD -> item {
                    DownloadSettingsSection(
                        state = DownloadSettingsState(
                            downloadStats = downloadStats,
                            downloadEnabled = downloadEnabled,
                            autoDownloadOnPlay = autoDownloadOnPlay,
                            autoDownloadLimit = autoDownloadLimit,
                            downloadLocation = downloadLocation,
                            exportState = exportState,
                        ),
                        actions = DownloadSettingsActions(
                            onToggleDownloadEnabled = onToggleDownloadEnabled,
                            onToggleAutoDownloadOnPlay = onToggleAutoDownloadOnPlay,
                            onChangeAutoDownloadLimit = onChangeAutoDownloadLimit,
                            onChangeDownloadLocation = onChangeDownloadLocation,
                            onClearAllDownloads = onClearAllDownloads,
                            onExportToDevice = onExportToDevice,
                            onCancelExport = onCancelExport,
                            onResetExportState = onResetExportState,
                        ),
                        onClearAllDownloadsRequested = { showClearDownloadsConfirm = true }
                    )
                }
                SettingsSection.ABOUT -> item {
                    AboutSettingsSection(
                        state = AboutSettingsState(
                            isConnected = isConnected,
                            serverDisplayName = serverDisplayName,
                            backendApiVersion = backendApiVersion,
                            apiVersions = apiVersions,
                        )
                    )
                }
                SettingsSection.CACHE -> item {
                    CacheSettingsSection(
                        state = CacheSettingsState(
                            settings = settings,
                            availableBytes = storageAvailableBytes
                        ),
                        actions = CacheSettingsActions(
                            onToggleCacheLyrics = onToggleCacheLyrics,
                            onToggleCacheCover = onToggleCacheCover,
                            onClearLyricsCache = onClearLyricsCache,
                            onClearCoverCache = onClearCoverCache,
                            onClearMvCache = onClearMvCache,
                            onClearSeparationArtifacts = onClearSeparationArtifacts,
                            onClearCoverUrlCache = onClearCoverUrlCache,
                            onClearExtractedCoverCache = onClearExtractedCoverCache,
                        )
                    )
                }
                SettingsSection.NETWORK -> item {
                    NetworkMusicSection(
                        state = NetworkMusicSettingsState(
                            metingApiBaseUrl = settings.metingApiBaseUrl,
                            jamendoClientId = jamendoClientId,
                            mvApiBaseUrl = mvApiBaseUrl,
                            lyricsKugouBaseUrl = lyricsKugouBaseUrl,
                            lyricsNeteaseBaseUrl = lyricsNeteaseBaseUrl,
                            weatherApiKey = weatherApiKey,
                            isNetworkTesting = isNetworkTesting,
                            networkTestStatus = networkTestStatus,
                        ),
                        actions = NetworkMusicSettingsActions(
                            onChangeMetingApiBaseUrl = onChangeMetingApiBaseUrl,
                            onChangeJamendoClientId = onChangeJamendoClientId,
                            onChangeMvApiBaseUrl = onChangeMvApiBaseUrl,
                            onChangeLyricsKugouBaseUrl = onChangeLyricsKugouBaseUrl,
                            onChangeLyricsNeteaseBaseUrl = onChangeLyricsNeteaseBaseUrl,
                            onChangeWeatherApiKey = onChangeWeatherApiKey,
                            // T16：探针 I/O 已下沉 MainViewModel.runNetworkProbe()（§5.4），UI 只发意图
                            onRunNetworkTest = onRunNetworkProbe,
                        ),
                        dialogs = NetworkMusicDialogActions(
                            // T16（§5.3）：6 个端点/Key 编辑入口全部构造通用宿主。
                            // scheme 校验失败的反馈 = 对话框不关（用户可改）——原 3 个 error
                            // 状态只写不读（幽灵），随收敛删除，用户可感知行为不变。
                            onShowMetingUrlDialog = {
                                endpointEditor = EndpointEditor(
                                    titleRes = R.string.settings_meting_api_url,
                                    hintRes = R.string.settings_meting_api_url_hint,
                                    value = settings.metingApiBaseUrl,
                                ) { input ->
                                    val trimmed = input.trim()
                                    if (trimmed.isEmpty()) {
                                        onChangeMetingApiBaseUrl?.invoke(
                                            com.nasmusic.tv.backend.network.MetingApiService.DEFAULT_BASE_URL
                                        )
                                        endpointEditor = null
                                    } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                                        // 校验失败：保持对话框打开
                                    } else {
                                        onChangeMetingApiBaseUrl?.invoke(trimmed)
                                        endpointEditor = null
                                    }
                                }
                            },
                            onShowJamendoClientIdDialog = {
                                endpointEditor = EndpointEditor(
                                    titleRes = R.string.settings_jamendo_client_id,
                                    hintRes = R.string.settings_jamendo_client_id,
                                    value = jamendoClientId,
                                ) { input ->
                                    onChangeJamendoClientId?.invoke(input.trim())
                                    endpointEditor = null
                                }
                            },
                            onShowMvUrlDialog = {
                                endpointEditor = EndpointEditor(
                                    titleRes = R.string.settings_mv_api_url,
                                    hintRes = R.string.settings_mv_api_url_hint,
                                    value = mvApiBaseUrl,
                                ) { input ->
                                    val trimmed = input.trim()
                                    if (trimmed.isEmpty()) {
                                        onChangeMvApiBaseUrl?.invoke(
                                            com.nasmusic.tv.backend.network.mv.BilibiliMvService.DEFAULT_BASE_URL
                                        )
                                        endpointEditor = null
                                    } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                                        // 校验失败：保持对话框打开
                                    } else {
                                        onChangeMvApiBaseUrl?.invoke(trimmed)
                                        endpointEditor = null
                                    }
                                }
                            },
                            onShowLyricsKugouDialog = {
                                endpointEditor = EndpointEditor(
                                    titleRes = R.string.settings_lyrics_kugou_url,
                                    hintRes = R.string.settings_lyrics_url_hint,
                                    value = lyricsKugouBaseUrl,
                                ) { input ->
                                    val trimmed = input.trim()
                                    if (trimmed.isEmpty()) {
                                        onChangeLyricsKugouBaseUrl?.invoke(
                                            com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_KUGOU_BASE_URL
                                        )
                                        endpointEditor = null
                                    } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                                        // 校验失败：保持对话框打开
                                    } else {
                                        onChangeLyricsKugouBaseUrl?.invoke(trimmed)
                                        endpointEditor = null
                                    }
                                }
                            },
                            onShowLyricsNeteaseDialog = {
                                endpointEditor = EndpointEditor(
                                    titleRes = R.string.settings_lyrics_netease_url,
                                    hintRes = R.string.settings_lyrics_url_hint,
                                    value = lyricsNeteaseBaseUrl,
                                ) { input ->
                                    val trimmed = input.trim()
                                    if (trimmed.isEmpty()) {
                                        onChangeLyricsNeteaseBaseUrl?.invoke(
                                            com.nasmusic.tv.lyrics.LyricsNetworkProvider.DEFAULT_NETEASE_BASE_URL
                                        )
                                        endpointEditor = null
                                    } else if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                                        // 校验失败：保持对话框打开
                                    } else {
                                        onChangeLyricsNeteaseBaseUrl?.invoke(trimmed)
                                        endpointEditor = null
                                    }
                                }
                            },
                            onShowWeatherApiKeyDialog = {
                                endpointEditor = EndpointEditor(
                                    titleRes = R.string.settings_weather_api_key,
                                    hintRes = R.string.settings_weather_api_key_hint,
                                    value = weatherApiKey,
                                    masked = true,
                                ) { input ->
                                    onChangeWeatherApiKey?.invoke(input.trim())
                                    endpointEditor = null
                                }
                            },
                        )
                    )
                }
                SettingsSection.DATA -> item {
                    DataSettingsSection(
                        state = DataSettingsState(
                            backupFiles = backupFiles,
                            backupMessage = backupMessage,
                            playlistImportHistory = playlistImportHistory,
                            playlistImportMessage = playlistImportMessage,
                        ),
                        actions = DataSettingsActions(
                            onExportBackup = onExportBackup,
                            onImportBackup = onImportBackup,
                            onScanTransferBackup = onScanTransferBackup,
                            onImportBackupFile = onImportBackupFile,
                            onOpenPlayStats = onOpenPlayStats,
                            onImportPlaylistFile = onImportPlaylistFile,
                            onImportPlaylistLocalFile = onImportPlaylistLocalFile,
                            onOpenImportedPlaylist = onOpenImportedPlaylist,
                            onConsumePlaylistImportMessage = onConsumePlaylistImportMessage,
                        ),
                        onDeleteRequested = { file -> backupToDelete = file }
                    )
                }
                SettingsSection.LOGS -> item {
                    LogsSettingsSection(
                        state = LogsSettingsState(
                            entries = logs.entries,
                            fileSizeBytes = logs.fileSizeBytes,
                            minLevel = logsMinLevel,
                        ),
                        actions = LogsSettingsActions(
                            onRefresh = onRefreshLogs,
                            onClear = onClearLogs,
                            onSelectMinLevel = onSelectLogsMinLevel,
                        ),
                    )
                }
            }
        }
        } // Column（竖屏二级 / 横屏右栏）
        } // else（非竖屏一级列表）
    }

    // ===================== 对话框宿主（R-2：保持在主文件，状态与分区共享） =====================

    // 百度网盘设备码授权对话框
    if (showBaiduAuthDialog && onStartBaiduDeviceCode != null) {
        BaiduAuthDialog(
            deviceCode = baiduDeviceCode,
            connectionState = baiduConnectionState,
            onCancel = {
                onCancelBaiduDeviceCode?.invoke()
                showBaiduAuthDialog = false
            },
            onDismiss = {
                showBaiduAuthDialog = false
            }
        )
    }

    // 百度网盘音乐根目录选择对话框（目录树形态；无目录树回调时 onShow 已改走
    // 通用端点宿主 endpointEditor —— 文本回退不再是独立的 TextInputDialog）
    if (showBaiduMusicRootDialog && onListBaiduDirs != null && onChangeBaiduMusicRootDir != null) {
        BaiduDirPickerDialog(
            initialPath = baiduMusicRootLocal.ifBlank { com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.APP_DIR },
            onListDirs = onListBaiduDirs,
            onConfirm = { path ->
                baiduMusicRootLocal = path
                onChangeBaiduMusicRootDir(path)
                showBaiduMusicRootDialog = false
            },
            onDismiss = { showBaiduMusicRootDialog = false }
        )
    }

    // 百度网盘 MV 目录选择对话框（目录树形态；文本回退同上走 endpointEditor）
    if (showBaiduMvDirDialog && onListBaiduDirs != null && onChangeBaiduMvDir != null) {
        BaiduDirPickerDialog(
            initialPath = baiduMvDirLocal?.takeIf { it.isNotBlank() }
                ?: baiduMusicRootLocal.ifBlank { com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.APP_DIR },
            onListDirs = onListBaiduDirs,
            onConfirm = { path ->
                baiduMvDirLocal = path
                onChangeBaiduMvDir(path)
                showBaiduMvDirDialog = false
            },
            onDismiss = { showBaiduMvDirDialog = false }
        )
    }

    // ===================== T16（§5.3）：端点/文本编辑通用宿主（唯一） =====================
    // 所有「点行 → 弹输入框 → 确认回调」形态的对话框都从这里走：百度目录文本回退、
    // 天气 Key（masked）、Meting / MV / 酷狗 / 网易云端点、Jamendo Client ID、分离模型自定义源。
    // 确认与否由 [EndpointEditor.onConfirm] 自己决定（校验失败不置 null = 对话框保持打开）。
    endpointEditor?.let { editor ->
        TextInputDialog(
            title = stringResource(editor.titleRes),
            hint = stringResource(editor.hintRes),
            initialValue = editor.value,
            masked = editor.masked,
            onConfirm = editor.onConfirm,
            onDismiss = { endpointEditor = null }
        )
    }
    // 删除备份文件确认弹窗
    backupToDelete?.let { file ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { backupToDelete = null },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            BackHandler { backupToDelete = null }
            Column(
                modifier = Modifier
                    // 520dp 在竖屏（Compose 口径 ≈439dp）会被对话框窗口裁掉 ❌ → §2.4 对话框族
                    // 统一响应式尺寸（TV/横屏仍返回 `width(520.dp)`，逐字等价，B1）
                    .then(responsiveDialogSize(520.dp, scrollable = true))
                    .background(NasMusicColors.Surface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.settings_delete_backup_confirm_title),
                    color = NasMusicColors.Warning,
                    fontSize = FontSize.subtitle()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_delete_backup_confirm_message, file.displayName),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.button(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                ) {
                    FocusableSurface(
                        onClick = { backupToDelete = null },
                        // 44dp 在竖屏只有 36.1 物理 dp ❌ → §2.7 换算抬到 56dp
                        modifier = Modifier.width(140.dp).height(portraitTouchTarget(44.dp)),
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.SurfaceVariant,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContainerColor = NasMusicColors.Primary.copy(alpha = 0.25f),
                        focusedContentColor = NasMusicColors.TextPrimary
                    ) {
                        Text(
                            text = stringResource(R.string.common_cancel),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.button(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
                        )
                    }
                    FocusableSurface(
                        onClick = {
                            onDeleteBackup?.invoke(file.uri)
                            backupToDelete = null
                        },
                        // 44dp 在竖屏只有 36.1 物理 dp ❌ → §2.7 换算抬到 56dp
                        modifier = Modifier.width(140.dp).height(portraitTouchTarget(44.dp)),
                        shape = RoundedCornerShape(10.dp),
                        focusedScale = 1.08f,
                        animationDurationMs = 150,
                        containerColor = NasMusicColors.Warning,
                        contentColor = NasMusicColors.TextPrimary,
                        focusedContainerColor = NasMusicColors.Warning.copy(alpha = 0.85f),
                        focusedContentColor = NasMusicColors.TextPrimary,
                        requestFocusOnLaunch = true
                    ) {
                        Text(
                            text = stringResource(R.string.settings_delete_backup),
                            color = NasMusicColors.TextPrimary,
                            fontSize = FontSize.button(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }

    // P1-17: 清空所有下载确认弹窗
    if (showClearDownloadsConfirm) {
        Dialog(
            onDismissRequest = { showClearDownloadsConfirm = false },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            ConfirmDialog(
title = stringResource(R.string.settings_clear_downloads_title),
                message = stringResource(R.string.settings_clear_downloads_message),
                destructive = true,
                onConfirm = {
                    onClearAllDownloads?.invoke()
                    showClearDownloadsConfirm = false
                },
                onDismiss = { showClearDownloadsConfirm = false }
            )
        }
    }
}

/**
 * T16（方案 §5.3）：端点/文本编辑对话框的通用宿主描述。
 *
 * 「点行 → 弹 [TextInputDialog] → 确认回调」形态的对话框全部收敛到这一个 data class 上，
 * `SettingsScreen` 只持有一个 `endpointEditor: EndpointEditor?`；打开对话框 = 构造一个实例，
 * 关闭对话框 = 把它置回 null（**由 [onConfirm] 自己决定**——校验失败不置 null，
 * 对话框就保持打开，用户可继续改；这正是原 9 个宿主的关闭语义，行为不变）。
 *
 * @param titleRes 对话框标题资源（通用宿主在 Composable 作用域内 `stringResource` 解析，
 *   故 onShow 回调——非 Composable 上下文——里只能存 resId，不能存已解析字符串）
 * @param hintRes 输入框空态占位文字资源
 * @param value 打开对话框时的初始值（构造时快照；对话框打开期间设置值不变，语义与原实现一致）
 * @param masked 密码态（天气 API Key 用；输入框显示圆点，与原 `masked = true` 一致）
 * @param onConfirm 确认回调，入参为输入框当前文本；内部自行完成 trim / 校验 / 应用并决定是否关闭
 *
 * ⚠️ 与方案 §5.3 原文的 `(titleRes, descRes, value, onConfirm)` 有一处命名偏离：
 * `descRes` → [hintRes]（贴 [TextInputDialog] 的实参名，它本来就是输入框占位文字），
 * 并新增 [masked]。方案未覆盖的两个百度目录回退分支也并入了本宿主（目录树形态
 * 仍由 `showBaidu*Dialog` 布尔 + `BaiduDirPickerDialog` 承载，不同构不硬并）。
 */
private data class EndpointEditor(
    val titleRes: Int,
    val hintRes: Int,
    val value: String,
    val masked: Boolean = false,
    val onConfirm: (String) -> Unit,
)
