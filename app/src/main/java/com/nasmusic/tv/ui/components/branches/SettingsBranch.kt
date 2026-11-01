package com.nasmusic.tv.ui.components.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.ActivityResult
import com.nasmusic.tv.backend.photo.PhotoSourceKind
import com.nasmusic.tv.util.LocalFilePicker
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.*
import com.nasmusic.tv.ui.screens.*
import com.nasmusic.tv.ui.screens.library.*
import com.nasmusic.tv.ui.screens.settings.*
import com.nasmusic.tv.ui.screens.netdisk.*
import com.nasmusic.tv.ui.screens.stats.*
import com.nasmusic.tv.ui.viewmodel.*
import kotlinx.coroutines.launch

/**
 * Settings 分支提取自 AppRoot（Method too large 根治：分支下沉 branches/）。
 * 分支体逐行搬迁，外层共享状态经参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsBranch(
    viewModel: MainViewModel,
    settings: com.nasmusic.tv.data.model.AppSettings,
    isLoading: Boolean,
    isConnected: Boolean,
    serverDisplayName: String,
    serverConfig: ServerConfig,
    coverFilterEnabled: Boolean,
    coverFilterBlurRadius: Float,
    coverFilterDarkOverlay: Float,
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onConnect: (ServerConfig) -> Unit,
) {
                    val backendApiVersion by viewModel.serverVM.backendApiVersion.collectAsState(initial = "Unknown")
                    val apiVersions by viewModel.serverVM.apiVersions.collectAsState(initial = emptyList())
                    // F2-5：crossfade 设置状态
                    val crossfadeEnabled by viewModel.prefs.player.crossfadeEnabled.collectAsState(initial = false)
                    val crossfadeDurationSec by viewModel.prefs.player.crossfadeDurationSec.collectAsState(initial = 4)
                    // F2-7：应用内音量（PlayerViewModel 状态，独立于系统音量）
                    val appVolume by viewModel.playerVM.appVolume.collectAsState(initial = 1f)
                    // F2-6：音质档位
                    val qualityTier by viewModel.prefs.player.qualityTier.collectAsState(initial = 0)
                    val weatherApiKey by viewModel.prefs.weather.weatherApiKey.collectAsState(initial = "")
                    val baiduConnectionState by viewModel.netVM.baiduConnectionState.collectAsState(initial = com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.Off)
                    val baiduDeviceCode by viewModel.baiduDeviceCode.collectAsState(initial = null)
                    val baiduIndexScanned by viewModel.baiduIndexScanned.collectAsState(initial = 0)
                    val baiduIndexScanning by viewModel.baiduIndexScanning.collectAsState(initial = false)
                    val baiduApicExtracting by viewModel.baiduApicExtracting.collectAsState(initial = false)
                    val baiduApicExtracted by viewModel.baiduApicExtracted.collectAsState(initial = 0)
                    val baiduApicTotal by viewModel.baiduApicTotal.collectAsState(initial = 0)
                    var showBackupTransferDialog by remember { mutableStateOf(false) }
                    var showModelTransferDialog by remember { mutableStateOf(false) }
                    var showPlaylistImportDialog by remember { mutableStateOf(false) }
                    val branchContext = androidx.compose.ui.platform.LocalContext.current
                    // 「从本地文件导入」三枚入口（备份 json / 歌单 / 分离模型）：文件已在这台设备上时
                    // 不必绕扫码 —— 扫码要求本 app 活着当服务端，手机一开浏览器就进后台被冻结。
                    // ⛔ 一律显示，不做「本机有没有选择器」的能力探测（v2.39.3 就是这样误判把行藏掉的）；
                    // 起不来由 launchDocumentPicker 把原因显示到对应区块顶部。
                    val backupFileLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        val picked = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
                        if (picked != null) {
                            // 必须**在结果回调里同步**转持久授权（协程里补做会被系统当成授权已过期），
                            // 有了它这份文件才能在重启后继续留在备份列表里 —— 见 ImportedBackupRecord。
                            LocalFilePicker.persistRead(branchContext, picked)
                            viewModel.backupVM.importBackup(picked, fromLocalFile = true)
                        }
                    }
                    val playlistFileLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        val picked = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
                        if (picked != null) viewModel.playlistImportVM.importPlaylist(picked)
                    }
                    val modelFileLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        val picked = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
                        if (picked != null) viewModel.downloadVM.importModelFromUri(picked)
                    }
                    // 修复（H-3）：组合内 runBlocking 同步读改为 Flow 订阅
                    val baiduConfig by viewModel.prefs.baidu.baiduConfigFlow.collectAsState(
                        initial = com.nasmusic.tv.data.model.CloudDriveConfig(com.nasmusic.tv.data.model.CloudDriveType.BAIDU)
                    )
                    val jamendoClientId by viewModel.prefs.network.jamendoClientIdFlow.collectAsState(initial = "")
                    val separationMode by viewModel.vocalVM.separationMode.collectAsState()
                    // T10：歌词字号 / 高亮（与播放页 NowPlayingBranch 读的是同一份持久化 key）
                    val lyricsFontScale by viewModel.prefs.lyrics.lyricsFontScale.collectAsState(initial = 1.0f)
                    val lyricsHighlightMode by viewModel.lyricsHighlightMode.collectAsState()
                    val modelDownloaded by viewModel.downloadVM.modelDownloaded.collectAsState()
                    // === F2-2b 睡眠定时器（常驻按钮状态，本页内收集） ===
                    val sleepTimerSt by viewModel.playerVM.sleepTimerState.collectAsState()
                    val modelDownloading by viewModel.downloadVM.modelDownloading.collectAsState()
                    val modelDownloadProgress by viewModel.downloadVM.modelDownloadProgress.collectAsState()
                    val modelDownloadedMB by viewModel.downloadVM.modelDownloadedMB.collectAsState()
                    val modelTotalMB by viewModel.downloadVM.modelTotalMB.collectAsState()
                    val modelSizeMB by viewModel.downloadVM.modelSizeMB.collectAsState()
                    val modelDownloadError by viewModel.downloadVM.modelDownloadError.collectAsState()
                    val modelPath by viewModel.downloadVM.modelPath.collectAsState()
                    val hqErrorDetail by viewModel.vocalVM.hqErrorDetail.collectAsState()
                    // 进入设置页时刷新模型状态（检查文件是否已下载）和下载统计
                    LaunchedEffect(Unit) {
                        viewModel.downloadVM.refreshModelStatus()
                        viewModel.downloadVM.refreshDownloadStats()
                    }
                    // 歌单导入：最近导入记录 + 导入结果消息
                    val playlistImportHistory by viewModel.playlistImportVM.importHistory.collectAsState(initial = emptyList())
                    val playlistImportMessage by viewModel.playlistImportVM.importMessage.collectAsState(initial = null)
                    // 本地音乐（§5.6）：Room 已入库曲目数 —— 含已下载曲，不受总开关影响（D2）
                    val localMusicSongs by viewModel.localSongs.collectAsState(initial = emptyList())
                    // v2.39.0：当前均衡器预设（PLAYBACK 卡④只读呈现，改预要在 EqualizerScreen）
                    val equalizerPreset by viewModel.equalizerPreset.collectAsState(initial = com.nasmusic.tv.data.model.EqualizerPreset.NORMAL)
                    // v2.36.0 竖屏设置两级页：当前进入的分区（null = 一级列表）。
                    // 状态归 NavigationViewModel —— AppRoot 的 BACK handler 需要读它（方案 §6.2 / K2）
                    val settingsSection by viewModel.navVM.settingsSection.collectAsState(initial = null)
                    // v2.36.0 屏幕方向（L1 全局策略）
                    val screenOrientation by viewModel.prefs.display.screenOrientation.collectAsState(initial = "auto")
                    // 阶段 9（§9）：照片墙的运行时状态 —— 权限三态 + 目录拒绝原因。
                    // ⚠️ 都来自 visualizerVM 的 StateFlow，**不从 AppSettings 读**：
                    //    权限状态官方禁止落盘（§9.4），目录拒绝是瞬时事实。
                    val photoWallPermissionState by viewModel.visualizerVM.photoPermissionState.collectAsState()
                    val photoWallDirectoryReject by viewModel.visualizerVM.photoDirectoryReject.collectAsState()
                    // 阶段 10（§14.2.5）：各来源照片数 / 合计 —— 由 `PhotoWallController` 的
                    // 聚合结果派生。⚠️ 同样是**运行时事实**（不落盘、不进备份），
                    // 所以走 `PhotoWallRuntimeState` 而不是 `AppSettings`。
                    val photoWallPerSource by viewModel.visualizerVM.photoWall.perSourceCount.collectAsState()
                    val photoWallMerged by viewModel.visualizerVM.photoWall.mergedCount.collectAsState()
                    // 阶段 11 追加：人脸过滤后的**实际展示**张数（与 mergedCount 口径不同，见 §10.183）
                    val photoWallDisplay by viewModel.visualizerVM.photoWall.displayCount.collectAsState()
                    // 阶段 11（§10）：人脸扫描进度 / 可用性 —— 同样是运行时事实。
                    // ⚠️ `faceScanState` 是 getter（内部 `by lazy`）⇒ 只有进设置页才建
                    //    `FaceScanManager`（开 photo_face.db / 读 assets），平时零成本。
                    val photoWallFaceScan by viewModel.visualizerVM.faceScanState.collectAsState()
                    val faceScanSupported by viewModel.visualizerVM.faceScanSupported.collectAsState()
                    // T8b：剩余空间（CACHE「占用与存储」首行）
                    val storageAvailableBytes by viewModel.storageAvailableBytes.collectAsState()
                    // T16（§5.4）：网络探针状态（MainViewModel 持有，UI 只渲染 + 发意图）
                    val networkProbeRunning by viewModel.networkProbeRunning.collectAsState(initial = false)
                    val networkProbeStatus by viewModel.networkProbeStatus.collectAsState(initial = "")
                    // v2.39.4：运行日志快照（LOGS 分区；进页/刷新时由 VM 读 LogStore）
                    val logs by viewModel.logs.collectAsState()
                    val logsMinLevel by viewModel.logsMinLevel.collectAsState()
                    SettingsScreen(
                        selectedSection = settingsSection,
                        onOpenSection = { viewModel.navVM.openSettingsSection(it) },
                        onCloseSection = { viewModel.navVM.closeSettingsSection() },
                        screenOrientation = screenOrientation,
                        onChangeScreenOrientation = { value ->
                            coroutineScope.launch { viewModel.prefs.display.setScreenOrientation(value) }
                        },
                        settings = settings,
                        onToggleDarkTheme = { viewModel.updateDarkTheme(it) },
                        onToggleAnimations = { viewModel.updateAnimationsEnabled(it) },
                        onToggleAutoPlayNext = { viewModel.updateAutoPlayNext(it) },
                        onChangePlayMode = { viewModel.updateDefaultPlayMode(it) },
                        onToggleCacheLyrics = { viewModel.updateCacheLyrics(it) },
                        onToggleCacheCover = { viewModel.updateCacheCover(it) },
                        onClearLyricsCache = { viewModel.clearLyricsCache() },
                        onClearCoverCache = { viewModel.clearCoverCache() },
                        onClearMvCache = { viewModel.clearMvPersistentCache() },
                        onClearSeparationArtifacts = { viewModel.clearSeparationArtifacts() },
                        onClearCoverUrlCache = { viewModel.clearCoverUrlCache() },
                        onClearExtractedCoverCache = { viewModel.clearExtractedCoverCache() },
                        // T8b：CACHE「占用与存储」首行剩余空间（StorageGuard 的 30s 轮询值）
                        storageAvailableBytes = storageAvailableBytes,
                        onOpenEqualizer = { viewModel.navVM.navigateTo(Screen.Equalizer) },
                        onOpenPlayStats = { viewModel.navVM.navigateTo(Screen.PlayStats) },
                        // F2-5：crossfade 设置接线
                        crossfadeEnabled = crossfadeEnabled,
                        crossfadeDurationSec = crossfadeDurationSec,
                        onToggleCrossfade = { viewModel.setCrossfadeEnabled(it) },
                        onChangeCrossfadeDuration = { viewModel.setCrossfadeDurationSec(it) },
                        // F2-7：应用内音量接线
                        appVolume = appVolume,
                        onChangeAppVolume = { viewModel.playerVM.setAppVolume(it) },
                        // F2-6：音质档位接线
                        qualityTier = qualityTier,
                        onChangeQualityTier = { viewModel.setQualityTier(it) },
                        equalizerPresetName = stringResource(equalizerPreset.displayNameRes),
                        onClearQualityOverrides = { viewModel.clearAllQualityOverrides() },
                        onChangeMetingApiBaseUrl = { viewModel.updateMetingApiBaseUrl(it) },
                        mvApiBaseUrl = settings.mvApiBaseUrl,
                        onChangeMvApiBaseUrl = { viewModel.updateMvApiBaseUrl(it) },
                        lyricsKugouBaseUrl = settings.lyricsKugouBaseUrl,
                        onChangeLyricsKugouBaseUrl = { viewModel.updateLyricsKugouBaseUrl(it) },
                        lyricsNeteaseBaseUrl = settings.lyricsNeteaseBaseUrl,
                        onChangeLyricsNeteaseBaseUrl = { viewModel.updateLyricsNeteaseBaseUrl(it) },
                        // Jamendo（CC 独立音乐）
                        jamendoClientId = jamendoClientId,
                        onChangeJamendoClientId = { viewModel.updateJamendoClientId(it) },
                        weatherApiKey = weatherApiKey,
                        onChangeWeatherApiKey = { viewModel.updateWeatherApiKey(it) },
                        // T16：网络探针（I/O 在 MainViewModel.runNetworkProbe，§5.4）
                        isNetworkTesting = networkProbeRunning,
                        networkTestStatus = networkProbeStatus,
                        onRunNetworkProbe = { viewModel.runNetworkProbe() },
                        // T1.6.4（§十三 裁决项 9=A）：接已有的 setQuality（写 DataStore ⇒ 立即生效）。
                        // ⛔ 不走 MainViewModel.updateVisualizerQuality（它没有降档回退主题的逻辑）。
                        visualizerQuality = settings.visualizerQuality,
                        onChangeVisualizerQuality = { viewModel.visualizerVM.setQuality(it) },
                        fontAdjustment = settings.fontAdjustment,
                        onChangeFontAdjustment = { viewModel.updateFontAdjustment(it) },
                        // 语言设置
                        language = settings.language,
                        onChangeLanguage = { lang ->
                            coroutineScope.launch {
                                viewModel.updateLanguage(lang)
                                // 用 finish + 新 Intent 重启，避免 recreate() 导致双 DataStore 冲突。
                                // 不调用 Runtime.exit(0) —— 进程自然回收，避免中断 PlaybackService 播放。
                                val activity = context as? android.app.Activity
                                if (activity != null) {
                                    val pkg = activity.packageName
                                    val mgr = activity.packageManager
                                    val intent = mgr.getLaunchIntentForPackage(pkg)
                                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    if (intent != null) activity.startActivity(intent)
                                    activity.finish()
                                }
                            }
                        },
                        // 数据管理（备份/恢复）
                        backupFiles = viewModel.backupVM.backupFiles.collectAsState(initial = emptyList()).value,
                        backupMessage = viewModel.backupVM.backupMessage.collectAsState(initial = null).value,
                        onRefreshBackupFiles = { viewModel.backupVM.refreshBackupFiles() },
                        onExportBackup = { viewModel.backupVM.exportBackup() },
                        onImportBackup = { uri -> viewModel.backupVM.importBackup(uri) },
                        onDeleteBackup = { uri -> viewModel.backupVM.deleteBackup(uri) },
                        onConsumeBackupMessage = { viewModel.backupVM.consumeBackupMessage() },
                        onScanTransferBackup = { showBackupTransferDialog = true },
                        onImportBackupFile = {
                            launchDocumentPicker(backupFileLauncher, LocalFilePicker.BACKUP_MIME_TYPES) {
                                viewModel.backupVM.reportBackupFailure(it)
                            }
                        },
                        // 歌单导入（阶段5）
                        playlistImportHistory = playlistImportHistory,
                        playlistImportMessage = playlistImportMessage,
                        onImportPlaylistFile = { showPlaylistImportDialog = true },
                        onImportPlaylistLocalFile = {
                            launchDocumentPicker(playlistFileLauncher, LocalFilePicker.PLAYLIST_MIME_TYPES) {
                                viewModel.playlistImportVM.reportImportFailure(it)
                            }
                        },
                        onOpenImportedPlaylist = { _ -> viewModel.navVM.navigateTo(Screen.Mine) },
                        onConsumePlaylistImportMessage = { viewModel.playlistImportVM.consumeImportMessage() },
                        // 百度网盘设置
                        baiduEnabled = baiduConfig.enabled,
                        baiduLoggedIn = baiduConnectionState is com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.LoggedIn
                            || baiduConnectionState is com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.DirMissing,
                        baiduConnecting = baiduConnectionState is com.nasmusic.tv.ui.viewmodel.NetworkMusicViewModel.BaiduConnectionState.Connecting,
                        baiduConnectionState = baiduConnectionState,
                        baiduDeviceCode = baiduDeviceCode,
                        baiduMusicRootDir = baiduConfig.musicRootDir,
                        baiduMvDir = baiduConfig.mvDir,
                        baiduIndexScanned = baiduIndexScanned,
                        baiduIndexScanning = baiduIndexScanning,
                        baiduApicExtracting = baiduApicExtracting,
                        baiduApicExtracted = baiduApicExtracted,
                        baiduApicTotal = baiduApicTotal,
                        onToggleBaiduEnabled = { viewModel.netVM.setBaiduEnabled(it) },
                        onStartBaiduDeviceCode = { viewModel.netVM.startBaiduDeviceCodeFlow() },
                        onCancelBaiduDeviceCode = { viewModel.netVM.cancelBaiduDeviceCode() },
                        onLogoutBaidu = { viewModel.netVM.logoutBaidu() },
                        onChangeBaiduMusicRootDir = { viewModel.netVM.setBaiduMusicRootDir(it) },
                        onChangeBaiduMvDir = { viewModel.netVM.setBaiduMvDir(it) },
                        onListBaiduDirs = { viewModel.listBaiduDirs(it) },
                        onRebuildBaiduIndex = { viewModel.netVM.rebuildBaiduIndex() },
                        onNavigateToServerConnect = { viewModel.navVM.navigateTo(Screen.ServerConnect) },
                        // T7：默认网络搜索源（写 DataStore，方案 §6-10）
                        onChangeDefaultNetworkSource = { viewModel.updateDefaultNetworkSource(it) },
                        // 服务器连接设置
                        serverConfig = serverConfig,
                        isConnected = isConnected,
                        serverDisplayName = serverDisplayName,
                        backendApiVersion = backendApiVersion,
                        apiVersions = apiVersions,
                        isConnecting = isLoading,
                        onConnect = onConnect,
                        onDisconnect = { viewModel.serverVM.disconnect() },
                        // 封面滤镜设置
                    coverFilterEnabled = coverFilterEnabled,
                    coverFilterBlurRadius = coverFilterBlurRadius,
                    coverFilterDarkOverlay = coverFilterDarkOverlay,
                    onToggleCoverFilter = { viewModel.updateCoverFilterEnabled(it) },
                    onChangeCoverBlurRadius = { viewModel.updateCoverFilterBlurRadius(it) },
                    onChangeCoverDarkOverlay = { viewModel.updateCoverFilterDarkOverlay(it) },
                    // 分离模式设置
                    separationMode = separationMode,
                    onChangeSeparationMode = { viewModel.vocalVM.setSeparationMode(it) },
                    // 高质量分离模型下载状态
                    modelDownloaded = modelDownloaded,
                    modelDownloading = modelDownloading,
                    modelDownloadProgress = modelDownloadProgress,
                    modelDownloadedMB = modelDownloadedMB,
                    modelTotalMB = modelTotalMB,
                    modelSizeMB = modelSizeMB,
                    modelDownloadError = modelDownloadError,
                    modelPath = modelPath,
                    hqErrorDetail = hqErrorDetail,
                    onDownloadModel = { viewModel.downloadVM.downloadModel() },
                    onDeleteModel = { viewModel.deleteModel() },
                    onRefreshModelStatus = { viewModel.downloadVM.refreshModelStatus() },
                    onScanTransferModel = { showModelTransferDialog = true },
                    onImportModelFile = {
                        launchDocumentPicker(modelFileLauncher, LocalFilePicker.ANY_MIME_TYPES) {
                            viewModel.downloadVM.reportModelImportFailure(it)
                        }
                    },
                    // T10：歌词显示（与播放页弹窗读同一条 Flow，改完立刻对播放页生效）
                    lyricsFontScale = lyricsFontScale,
                    lyricsHighlightMode = lyricsHighlightMode,
                    onChangeLyricsFontScale = { viewModel.updateLyricsFontScale(it) },
                    onChangeLyricsHighlightMode = { viewModel.setLyricsHighlightMode(it) },
                    // T10：分离模型自定义下载源（空串 = 内置默认源）
                    customModelUrl = settings.modelDownloadUrl,
                    onChangeCustomModelUrl = { viewModel.saveCustomModelUrl(it) },
                    onResetCustomModelUrl = { viewModel.saveCustomModelUrl("") },
                    // 歌曲离线下载设置
                    downloadEnabled = settings.downloadEnabled,
                    autoDownloadOnPlay = settings.autoDownloadOnPlay,
                    autoDownloadLimit = settings.autoDownloadLimit,
                    downloadLocation = settings.downloadLocation,
                    onToggleDownloadEnabled = { viewModel.updateDownloadEnabled(it) },
                    onToggleAutoDownloadOnPlay = { viewModel.updateAutoDownloadOnPlay(it) },
                    onChangeAutoDownloadLimit = { viewModel.updateAutoDownloadLimit(it) },
                    onChangeDownloadLocation = { viewModel.updateDownloadLocation(it) },
                    onClearAllDownloads = { viewModel.downloadVM.clearAllDownloads() },
                    downloadStats = viewModel.downloadVM.downloadStats.collectAsState().value,
                    // 导出到外接设备
                    exportState = viewModel.exportState.collectAsState().value,
                    onExportToDevice = { viewModel.showExportDeviceDialog() },
                    onCancelExport = { viewModel.cancelExport() },
                    onResetExportState = { viewModel.resetExportState() },
                    // 照片墙（§7.2）：写值一律走 prefs.photoWall.* 的 setter，
                    // 与「屏幕方向」同款写法（直接 coroutineScope.launch），
                    // 不必在 MainViewModel 上再加 17 个纯转发方法。
                    // ⚠️ 读值不在这里 —— PhotoWallSettingsSection 直接读 settings（AppSettings）。
                    //
                    // 阶段 9：授权相关动作走 `visualizerVM`（它不是纯 setter ——
                    // 「打开图库」要判权限、拉起系统对话框、被拒后回弹），
                    // 状态与逻辑都收口在那个 ViewModel 里，这里只做接线。
                    //
                    // ⚠️ 阶段 11 已把人脸检测三个动作接上（`visualizerVM.startFaceScan` /
                    //   `stopFaceScan` / `clearFaceScan`）；本阶段不再有留空动作。
                    photoWallRuntime = PhotoWallRuntimeState(
                        permissionState = photoWallPermissionState,
                        directoryReject = photoWallDirectoryReject,
                        galleryCount = photoWallPerSource[PhotoSourceKind.GALLERY] ?: 0,
                        externalCount = photoWallPerSource[PhotoSourceKind.EXTERNAL] ?: 0,
                        jellyfinCount = photoWallPerSource[PhotoSourceKind.JELLYFIN] ?: 0,
                        mergedCount = photoWallMerged,
                        displayCount = photoWallDisplay,
                        faceScanDone = photoWallFaceScan.done,
                        faceScanTotal = photoWallFaceScan.total,
                        faceScanPhase = photoWallFaceScan.phase,
                        faceScanSupported = faceScanSupported,
                    ),
                    photoWallActions = PhotoWallSettingsActions(
                        // ⛔ 图库开关是授权的**唯一触发点**（§6.2）：
                        //    ViewModel 内部会判断「已授权直接开 / 未授权先申请」。
                        onToggleGallery = { v -> viewModel.visualizerVM.setGallerySourceEnabled(v) },
                        onReselectPhotos = { viewModel.visualizerVM.requestGalleryPermission() },
                        onPickDirectory = { viewModel.visualizerVM.requestPhotoDirectoryPick() },
                        // 阶段 10：重扫三个来源（§6.8）。⚠️ 会**取消**正在跑的扫描重来一次，
                        // 因为用户是显式点的按钮，不该被「上一次还没扫完」吞掉。
                        onRescan = { viewModel.visualizerVM.photoWall.rescan() },
                        onToggleExternal = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setExternalEnabled(v) }
                        },
                        onToggleJellyfin = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setJellyfinEnabled(v) }
                        },
                        onToggleSourceBalance = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setSourceBalance(v) }
                        },
                        onToggleCommonDirsOnly = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setCommonDirsOnly(v) }
                        },
                        onToggleFacesOnly = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setFacesOnly(v) }
                        },
                        // 阶段 11：人脸检测（§10.2 后台分片 + 可中断 + 可续跑）。
                        // ⚠️ 「开始」在没有任何照片时会先触发一次重扫（设置页不在照片墙上）。
                        onStartFaceScan = { viewModel.visualizerVM.startFaceScan() },
                        onStopFaceScan = { viewModel.visualizerVM.stopFaceScan() },
                        onClearFaceScan = { viewModel.visualizerVM.clearFaceScan() },
                        onToggleRandomTransition = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setRandomTransition(v) }
                        },
                        onChangeFixedTransition = { id ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setFixedTransition(id) }
                        },
                        onChangeTransitionMs = { ms ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setTransitionMs(ms) }
                        },
                        onChangeHoldMs = { ms ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setHoldMs(ms) }
                        },
                        onChangeScaleMode = { mode ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setScaleMode(mode) }
                        },
                        onToggleKenBurns = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setKenBurns(v) }
                        },
                        onToggleAudioReactive = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setAudioReactive(v) }
                        },
                        onTogglePulseZoom = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setPulseZoom(v) }
                        },
                        onToggleBreathe = { v ->
                            coroutineScope.launch { viewModel.prefs.photoWall.setBreathe(v) }
                        }
                    ),
                    // 本地音乐（§5.6）：开关是音乐权限的**唯一触发点**（§5.5）——
                    // 「已授权直接开 / 未授权先申请、被拒回弹」全在 MainViewModel 里判。
                    localMusicLibraryCount = localMusicSongs.size,
                    onSetLocalMusicEnabled = { v -> viewModel.setLocalMusicEnabled(v) },
                    onRescanLocalMusic = { viewModel.refreshLocalMusic() },
                    // v2.39.4：运行日志（LOGS 分区）—— 快照来自 LogStore 落盘文件
                    logs = logs,
                    onRefreshLogs = { viewModel.refreshLogs() },
                    onClearLogs = { viewModel.clearLogs() },
                    logsMinLevel = logsMinLevel,
                    onSelectLogsMinLevel = { viewModel.setLogsMinLevel(it) },
                    )
                    // 扫码传输备份弹窗
                    if (showBackupTransferDialog) {
                        BackupTransferDialog(
                            onRestore = { json -> viewModel.backupVM.restoreBackupFromJsonBlocking(json) },
                            onBackupChanged = { viewModel.backupVM.refreshBackupFiles() },
                            onDismiss = { showBackupTransferDialog = false }
                        )
                    }
                    // 扫码传输模型弹窗
                    if (showModelTransferDialog) {
                        ModelTransferDialog(
                            modelPath = modelPath,
                            modelSizeMB = modelSizeMB,
                            onModelUploaded = { viewModel.downloadVM.refreshModelStatus() },
                            onDismiss = { showModelTransferDialog = false }
                        )
                    }
                    // 歌单扫码上传导入弹窗（阶段5.5：TV 无 DocumentsUI，扫码是电视上的唯一入口；
                    // 本机有文件选择器的设备另走「从本地文件导入」，见 playlistFileLauncher）
                    if (showPlaylistImportDialog) {
                        PlaylistImportUploadDialog(
                            onFileReceived = { name, bytes ->
                                viewModel.playlistImportVM.importRemoteFileBlocking(name, bytes)
                            },
                            onDismiss = { showPlaylistImportDialog = false }
                        )
                    }
}

/**
 * 打开系统文件选择器（三枚「从本地文件导入」入口共用）。
 *
 * ⛔ 起不来时必须把原因经 [onUnavailable] 显示到对应区块顶部：入口一律渲染、不做能力探测，
 * 静默失败在用户眼里就等于「没有这个功能」—— v2.39.3 用 `resolveActivity()` 探测，
 * 因 `<queries>` 没镜像目标 filter 的 mimeType 而在 MIUI 上误判成「本机没有选择器」，
 * 整行直接消失了。
 */
private fun launchDocumentPicker(
    launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    mimeTypes: List<String>,
    onUnavailable: (String) -> Unit
) {
    try {
        launcher.launch(LocalFilePicker.openIntent(mimeTypes))
    } catch (e: ActivityNotFoundException) {
        AppLog.e("SettingsBranch", "系统文件选择器不可用", e)
        onUnavailable(e.message ?: "ActivityNotFoundException")
    }
}
