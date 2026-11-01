# 设置区整理（Settings Reorganization）开发方案

> **⛔ 归档状态：✅ 已落地（2026-10-19）。** T1–T17 全部执行完毕并逐个提交（`bdfd4a2` → T17 的文档提交）；
> 逐任务实现出入与数字对账见 `docs/technical-overview.md` §10.249–§10.256；用户可感知变更见 `CHANGELOG.md`
> 的 v2.39.0 段落。**§6 唯一保留的未决项 = 13b**（`k_pitch_semitones` / `k_playback_speed` 要不要继续写
> DataStore，超出本方案范围）——已按文档生命周期规则显式登记进 `docs/archive/README.md` 对应行，未静默埋掉。
> 真机验收（V1–V18）与 Gradle 全量验证 ⏳ 待用户执行。本文仅供追溯，⛔ 不再作为执行依据。


> **版本：v1.1**（2026-10-18 审校修订，逐条对照源码核实；v1.0 = 2026-10-18 初稿）。变更明细见文末 **§11 修订记录**。⛔ 执行以本版为准。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把设置页从「子项之间无视觉边界、说明文案覆盖不齐、同一类组件各写各的取值、**一排组件在竖屏被压成 0 宽或高块参差**」的平铺列表，重构为「**按内容归属**划分的 11 个 tab，每个 tab 由 2–6 张分组卡片构成，每个可操作项带一句说明，**全部行组件共用一份尺寸/动效令牌**，**且同一套分区代码在 TV / 手机横屏 / 手机竖屏三种形态下都不变形**」，并清掉 9 组零消费方的死配置、补上 4 项有真实后端却无 UI 的隐藏设置、把 CACHE 页从「只统计 `cacheDir` 一个数字」补成**覆盖全树 19 条落盘位置的占用一览 + 6 个清理按钮**（用户 2026-10-18 追加要求，规格 §1.14 / T8b）。tab 的条数与长度**不作为目标**（用户 2026-10-18 明确：归属由内容决定，不追求平均）。

**Architecture:** 沿用 v2.36.0 的 R-2 分区模式（每个 tab 一个 `*SettingsSection` composable，只吃 `State` + `Actions` 数据类）与 R-4 门面域（`AppPreferences` 的 15 个 `*Prefs`）。UI 侧新增四个原语：`SettingsGroupCard`（外壳 + 卡片头 + 内部留白，§4.2）、`SettingsHint`（芯片组/调整行上方的说明文本，§4.5）、`SettingsTokens` + `SettingsTile`（尺寸/动效唯一真源 + 瓦片唯一委托点，§4.7）、`SettingsChipRow`（芯片组按 `adaptiveColumns` 手动分行，横竖屏唯一的排布入口，§4.8）；再加一个 `SwitchTrack`（§5.2 的分段式开关轨，按 `scripts/measure_switch_reference.py` 量出的参考图规格自绘）。行级组件 `SettingSwitch` / `SettingActionButton` / `SettingsInfoRow` / `AdjustButton` **一律不改签名**（三处例外，全部带默认值、调用方可不感知：`PlayModeSelector` 增加**必填** `description`，`SettingActionButton` 增加 `trailing`，`SettingsInfoRow` 增加 `modifier`）—— 分组边界由卡片容器与卡片头提供，而不是由行的容器色变化提供，因此迁移是纯包裹、可逐 tab 提交、每步都可真机验收。

**Tech Stack:** Kotlin 2.3.10 · Jetpack Compose + `androidx.tv:tv-material`（alpha，需 `ExperimentalTvMaterial3Api` opt-in）· DataStore Preferences + Gson · Robolectric + JUnit4（**项目无 `compose-ui-test`**，UI 断言只能用源码文本扫描范式，见 §5.7）。

**Spec:** 本文档自身即 spec（无上游设计文档）。权威现状依据：
[app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt)、
[ui/screens/settings/SettingsSection.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsSection.kt)、
[ui/screens/settings/SettingsComponents.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsComponents.kt)、
[data/prefs/AppPreferences.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt)。
所有 file:line 均在本会话内重新打开核对过。

## Global Constraints

- 包 `com.nasmusic.tv`，Min SDK 22 / Target 34 / Java 17；单模块 Gradle。
- 构建必须带 `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`（AGENTS.md「本地构建」）；报 `fileHashes.lock (拒绝访问)` 时先 `./gradlew.bat --stop`，**不要 `rm` 锁文件**。
- 单测在本机可跑：`./gradlew.bat testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process`；报数必须带 `--rerun-tasks`（增量 up-to-date 不重执行）。**基线按"跑前重测"取，历史参考**：2026-10-11 = 184 类 / 2035 例，2026-10-12 全量 2040 例零失败，**2026-10-17 21:03 本地全量工件 = 211 个 XML / 2218 例 / 0 失败 0 skipped 0 error**（v1.1 审校核对 `app/build/test-results/testDebugUnitTest/` 实况）。下文 T3/T8b/T13 的"基线"同此口径：执行时先重跑一遍取当次数，⛔ 不要把这里的数字当成永不变的断言值。
- lint 已阻塞（无新增 **error** 才行；256 条 warning 不失败）。
- ⛔ 绝不用 PowerShell 对 UTF-8 源文件做文本往返（CP936 往返会吃掉中文注释和相邻 ASCII 标点）。批量替换用 Edit/Write 工具。
- ⛔ 单元测试不得探测端点连通性：URL 只当字符串数据断言，需要 HTTP 用 mock / MockWebServer。本方案里「网络测试」是**生产功能**，可以真实发请求；测试里不许真连。
- ⛔ 不得断言 provider 镜像的**收敛时序**（`set…()` 后立刻 `get…Sync()` 读到新值）。`ProviderMirrorTest` 的 4 条镜像计时用例已于 `5997380` 整体删除，改由 `ProviderMirrorWiringGateTest`（G1 启动接线 / G2 自动枚举 `cached*` 字段的读侧·写侧·`@Volatile` / G3 解密守卫 / G4 负向自证）钉形状。本方案所有新测试一律走 `appSettings` Flow 或源码接线门。
- ⛔ 凭据不进备份 JSON：`BackupData` 历史上就排除 `serverPassword` / `serverApiToken` / `serverAccessCode` / 天气 Key 明文；本次删除项不得把凭据带入任何新字段。
- ⛔ `BackendAdapter.streamHeaders` 注入必须保持 host 精确匹配，本方案不触碰该链路。
- **分类纪律（用户 2026-10-18）**：一个设置项进哪个 tab，**只由内容归属决定**，不许为了"让各 tab 差不多长"合并或拆分；`CACHE`（8 项）与 `ABOUT`（0 个可操作项）并存是正常结果。
- **说明纪律（用户 2026-10-18）**：卡片内每个**可操作项**（`SettingSwitch` / `SettingActionButton` / `PlayModeSelector` / 芯片组 / `AdjustButton` 行）必须带一句说明，**≤ 26 个汉字**，写效果不写实现；只读信息行豁免（由卡片 `description` 承担）。⛔ 说明位不得用来显示当前值。
- **页面纪律（用户 2026-10-18 追问，§3.3 裁决）**：服务器连接**继续是独立 `Screen`**，不内嵌进设置 tab；⛔ 设置页源码内不得出现凭据输入（`TextFieldValue` / `PasswordVisualTransformation` / `KeyboardType.Password`）。
- **风格纪律（用户 2026-10-18 追加，§4.7）**：设置区的圆角 / 内边距 / 聚焦缩放 / 按压缩放 / 动画时长**只有 `SettingsTokens` 一处取值**，瓦片**只有 `SettingsTile` 一个委托点**；「已选中」只有一种表达（`Primary@0.18` 底 + Primary 字），危险动作统一 `Warning`；⛔ 分区文件内不得再出现 `FocusableSurface(` 参数块（G7-B）。文件级豁免必须点名，实测只有三个：`SettingsTokens.kt`、`PlaylistImportUploadDialog.kt`、`SettingsSectionList.kt`（§4.7-⑥）。
- **响应式纪律（用户 2026-10-18 追加，§4.8）**：形态判定唯一入口 `LocalUiMode`，分支谓词**只写 `PhonePortrait`**、`else` 必须与改动前**逐字等价**（手机横屏与 TV 共用一套布局）；口径唯一权威 = [docs/conventions-adaptive-ui.md](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/docs/conventions-adaptive-ui.md)，本方案**不另立第二套**。⛔ 不为竖屏写第二套 dp 字面量（`PHONE_UI_SCALE = 0.82` 已由 density 生效）；⛔ 不用 `FlowRow` / `horizontalScroll`（TV 焦点会跑出屏幕）；一行多枚芯片只准走 `SettingsChipRow`，`Box(Modifier.weight(1f))` 的兜法只允许出现在该原语内部一处。
- **缓存纪律（用户 2026-10-18 追加，§1.14 / T8b）**：代码里**任何会落盘的目录或文件**都必须进 `CacheInventory` 登记表（G9 扫全 `app/src/main` 的字面量路径，⛔ 只登记"设置页已经管到的"）；`deletable = true` 必须有 `clearVia`，且该名字在 main 源集真有一个 `fun`。⛔ 不给 Room 库、`datastore`、用户自行放入的 LRC、已下载歌曲、分离模型放删除按钮（体积计入，删除入口各归其域）。清除类按钮的提示数字与占用一览的数字**必须同源**（都用字节数，⛔ 不许一个报「%d 个文件」一个报 MB）。
- 文案：所有新增用户可见文案必须同时进 `values/strings.xml` 与 `values-en/strings.xml`（缺失会「编译通过、渲染时崩」——同 `VisualizerThemeStringsGateTest` 的成因）。
- 文档纪律：`CHANGELOG.md` 只写「做了什么」一行，根因/实现细节/验证过程一律进 `docs/technical-overview.md` §10.N；已 tag 的版本段落冻结，不改历史段。
- 临时/日志产物只进 `logs_temp/`，图片只进 `output/`，可复用脚本只进 `scripts/`；仓库根不放散文件。

---

## §1 现状全量清单（tab → 子项 → 持久化 key → 消费方）

图例：**●** = 有 UI 有后端；**◐** = 有后端无 UI（隐藏设置，§6 判定是否暴露）；**○** = 有 key 无任何消费方（死配置）；**✕** = UI 管道存在但不渲染（幽灵参数）。

### 1.1 GENERAL 通用 — [GeneralSettingsSection.kt:55-127](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/GeneralSettingsSection.kt#L55)

| # | 子项 | 控件 | key | 备注 |
|---|---|---|---|---|
| 1 | 界面语言（跟随系统/中/英） | `Row` 内 3 × 内联 `FocusableSurface`(:63-93) | `settings_language` (:373) | R-7 有 SharedPreferences 镜像 |
| 2 | 深色主题 | `SettingSwitch` (:95) | `settings_dark_theme` (:272) | 恒定深色，实际只有开 |
| 3 | 动画效果 | `SettingSwitch` (:96) | `settings_animations` (:273) | |
| 4 | 字号调整 −/＋（−8..8） | `AdjustButton` ×2 (:104/:112) | `settings_font_adjustment` (:347) | |
| 5 | 屏幕方向（自动/竖屏/横屏） | chips ×3 (:116-) | `display_screen_orientation` (:385) | R-7 镜像 `KEY_ORIENTATION_MIRROR` (:103) |

### 1.2 PLAYBACK 播放 — [PlayerSettingsSection.kt:93-470](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/PlayerSettingsSection.kt#L93)

| # | 子项 | 控件 | key | 备注 |
|---|---|---|---|---|
| 1 | 自动播放下一曲 | `SettingSwitch` (:99) | `settings_auto_play_next` (:274) | |
| 2 | 播放模式 | `PlayModeSelector` | `settings_play_mode` (:275) | |
| 3 | 淡入淡出 | `SettingSwitch` (:103) | `settings_crossfade_enabled` (:1125) | |
| 4 | 淡入淡出时长 2/4/6/8/12s | `Row` + `durations.forEach { SettingActionButton(...) }` (:112-123)，**未传 `modifier`** ⇒ 走默认 `fillMaxWidth()` | `settings_crossfade_duration_sec` (:1126) | 🔴 **当前实机只有「2s」可见**：`Row` 给第一个 wrap 子项的 `maxWidth` 是行内剩余宽度，第一档吃满、后四档被挤成 **0 宽**（v2.35.0 音质档、v2.37.0 画面适配同因同症）。现有 `ChipContentWidthScanTest` 抓不到（抓手在 `FocusableSurface` 层）。修法 = §4.8 A2/A3 + T13 |
| 5 | 应用音量 −/＋（5% 步进） | `AdjustButton` ×2 (:148/:159) | `settings_app_volume` (:1144) | |
| 6 | 音质档位（`QualityTiers.all` 纵向列表） | `SettingActionButton` ×N (:190) | `settings_quality_tier` (:1130) | |
| 7 | 清除逐曲音质覆盖 | `SettingActionButton` (:223) | `song_quality_overrides`（QualityOverrides.kt:44） | |
| 8 | 均衡器入口 | `SettingActionButton` (:216) | `equalizer_preset`/`equalizer_bands` (:369/:370) | 均衡器本体是 `Screen.Equalizer`，不是 tab |
| 9 | 可视化画质（3 档） | `SettingActionButton` ×3 (:253) | `visualizer_quality` (:325) | 语义与「播放」无关 |
| 10 | 高质量人声分离开关 | `SettingSwitch` (:271) | `k_separation_mode` (:460) | 属 K 歌域 |
| 11 | 模型进度 / 下载 / 删除 / 扫码传输 / 路径 / 错误 | 混合 (:282-377) | 无 key（运行时） | 属 K 歌域；失败详情 `hqErrorDetail` (:61) |
| 12 | 封面滤镜开关 | `SettingSwitch` (:404) | `cover_filter_enabled` (:318) | 属视觉域 |
| 13 | 模糊半径（步进 2，0–40） | `AdjustButton` ×2 (:425/:435) | `cover_filter_blur_radius` (:319) | 视觉域 |
| 14 | 暗化叠加（步进 0.1，0–1） | `AdjustButton` ×2 (:453/:463) | `cover_filter_dark_overlay` (:320) | 视觉域 |
| ✕15 | **可视化效果主题** | 无控件 | `settings_visualizer_theme` (:324) | **幽灵管道**：`PlayerSettingsState.visualizerTheme` (:38) 与 `Actions.onChangeVisualizerTheme` (:67) 从 `SettingsScreen.kt:545/:570` 传入，`PlayerSettingsSection` 内 **0 次读取**。主题选择实际只在可视化舞台（`VisualizerStage:537` + `VisualizerViewModel:466`） |

> PLAYBACK 现状 = 14 个真实控件 + 4 个互不相干的域（播放行为 / 音质 / K歌分离 / 视觉滤镜），纵向约 40 行，是全树最重的 tab。

### 1.3 PHOTO_WALL 照片墙 — [PhotoWallSettingsSection.kt:187-495](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/PhotoWallSettingsSection.kt#L187)

5 个 `SubSectionTitle`（:190 来源 / :321 筛选 / :376 转场 / :435 停留运动 / :445 音频反应）下挂 17 个持久化字段：

| 组 | 子项 | key |
|---|---|---|
| 照片来源 | 相册总开关（唯一权限触发点）、外部目录开关、Jellyfin 开关、来源均衡、目录选择、常用目录限制 | `photo_wall_gallery_enabled` (:328) / `photo_wall_external_enabled` (:329) / `photo_wall_jellyfin_enabled` (:330) / `photo_wall_source_balance` (:331) / `photo_wall_dir_uri` (:332) / `photo_wall_common_dirs_only` (:333) |
| 显示筛选 | 仅有人脸开关、人脸扫描入口、扫描进度/阶段/支持性（运行时） | `photo_wall_faces_only` (:334) / `photo_wall_face_scan_done` (:335) |
| 计数信息 | 相册/外部/Jellyfin/可用池 4 行 `SettingsInfoRow`（运行时） | — |
| 转场效果 | 随机转场开关、固定转场 chips、转场时长 | `photo_wall_random_transition` (:336) / `photo_wall_fixed_transition` (:337) / `photo_wall_transition_ms` (:338，范围 `300..2000`) |
| 停留期运动 | 停留时长、画面适配 `OptionChip`、Ken Burns | `photo_wall_hold_ms` (:339，`3_000..30_000`) / `photo_wall_scale_mode` (:340) / `photo_wall_ken_burns` (:341) |
| 音频反应 | 音频反应总开关、脉冲缩放、呼吸 | `photo_wall_audio_reactive` (:342) / `photo_wall_pulse_zoom` (:343) / `photo_wall_breathe` (:344) |

另有 `PhotoWallRuntimeState`（权限授予、目录被拒、4 个计数、扫描进度/阶段/supported）与相册介绍对话框。

### 1.4 LOCAL_MUSIC 本地音乐 — [LocalMusicSettingsSection.kt:39-68](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/LocalMusicSettingsSection.kt#L39)

| # | 子项 | 控件 | key |
|---|---|---|---|
| 1 | 本地音乐总开关（唯一权限申请入口） | `SettingSwitch` (:48) | `local_music_enabled` (:354) |
| 2 | 已索引曲数信息行 | `SettingsInfoRow` (:55) | 运行时 |
| 3 | 重新扫描 | `SettingActionButton` (:61) | 运行时 |

### 1.5 DOWNLOAD 下载 — [DownloadSettingsSection.kt:56-223](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/DownloadSettingsSection.kt#L56)

| # | 子项 | 控件 | key |
|---|---|---|---|
| 1 | 已下载 歌曲/歌词/封面/占用 4 行 | `SettingsInfoRow` ×4 (:67-70) | 运行时 |
| 2 | 下载功能开关 | `SettingSwitch` (:76) | `settings_download_enabled` (:350) |
| 3 | 播放时自动下载 | `SettingSwitch` (:82) | `settings_auto_download_on_play` (:351) |
| 4 | 自动下载上限 ±10（1..5000） | `AdjustButton` ×2 (:104/:108) | `settings_auto_download_limit` (:355) |
| 5 | 下载位置 本机/外接 | `AdjustButton` ×2 (:129/:130) | `settings_download_location` (:356) |
| 6 | 清除全部下载 | `SettingActionButton` (:135) | — |
| 7 | 导出到设备（状态机：选目录/进行中/完成/失败，含取消与重置） | 混合 (:142-220) | `export_tree_uri` (:359) / `export_volume_id` (:360) |

### 1.6 SERVER 服务器 — [ServerSettingsSection.kt:36-58](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/ServerSettingsSection.kt#L36)

| # | 子项 | 控件 |
|---|---|---|
| 1 | 连接状态文本 (:42-43) | `Text` |
| 2 | 服务器配置（跳 `Screen.ServerConnect`） | `SettingActionButton` (:45) |
| 3 | 断开连接 | `SettingActionButton` (:53) |

> 全树最薄 tab：3 行。对应 key 在 `server_*`（:258-269），主要由 ServerConnect 页读写。

### 1.7 CACHE 缓存 — [CacheSettingsSection.kt:49-125](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/CacheSettingsSection.kt#L49)

| # | 子项 | 控件 | key |
|---|---|---|---|
| 1 | 缓存目录占用（`LaunchedEffect(refreshKey)` + IO 遍历，300ms 收敛） | `Text` (:75) | 运行时 |
| 2 | 缓存歌词 | `SettingSwitch` (:82) | `settings_cache_lyrics` (:276) |
| 3 | 缓存封面 | `SettingSwitch` (:83) | `settings_cache_cover` (:277) |
| 4 | 清除歌词缓存 / 封面缓存 / MV 缓存 / 全部缓存 | `SettingActionButton` ×4 (:88-119) | — |

> ⚠️ 本表只列"设置页里已经有的"。**代码里实际存在 14 处落盘缓存，其中 5 处今天完全管不到、4 处只统计不到** —— 全量对照见 §1.14，补齐动作见 **Task 8b**（含门禁 G9）。两个按钮名不副实（「清除全部缓存」只删伴奏目录、「清除封面缓存」只清 Coil）也在 §1.14 结论 2。

### 1.8 NETWORK 网络 — [NetworkMusicSection.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/NetworkMusicSection.kt)

| # | 子项 | 控件 | key |
|---|---|---|---|
| 1 | 网络测试卡片（真实 OkHttp HEAD，逻辑内联在 `SettingsScreen.kt:713-744`） | `NetworkTestCard` | 无（一次性） |
| 2 | Meting 预设端点 + 自定义端点 | `PresetEndpointRow` ×N | `settings_meting_api_base_url` (:280) |
| 3 | Jamendo Client ID | `FocusableSurface` 状态卡 | `settings_jamendo_client_id` (:285) |
| 4 | MV 预设端点 + 自定义 | `PresetEndpointRow` ×N | `settings_mv_api_base_url` (:281) |
| 5 | 歌词酷狗端点 | `FocusableSurface` 行 | `settings_lyrics_kugou_base_url` (:283) |
| 6 | 歌词网易云端点 | `FocusableSurface` 行 | `settings_lyrics_netease_base_url` (:284) |
| 7 | 天气 API Key（末 6 位掩码显示） | `FocusableSurface` 行 | `weather_openweathermap_api_key_enc` (:315)，AES-GCM 加密 |

> ⚠️ 本 tab 的小标题**全部用裸 `Text`**（`color = NasMusicColors.Primary`）而非 `SubSectionTitle`，是 11 个 tab 里唯一不统一的写法。

### 1.9 NETDISK 网盘 — [BaiduPanSettingsSection.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/BaiduPanSettingsSection.kt)

| # | 子项 | 控件 | key |
|---|---|---|---|
| 1 | 百度网盘开关 | `SettingSwitch` | `cloud_drive_configs` (:482) 内 `enabled` |
| 2 | 授权失败横幅 / 根目录缺失横幅 | `Column` + `Text` | 运行时 |
| 3 | 登录（设备码对话框）/ 已登录后改为退出 | `SettingActionButton` | 令牌在 `CloudDriveConfig` |
| 4 | 音乐根目录 | `SettingActionButton` | 同上 |
| 5 | MV 目录 | `SettingActionButton` | 同上 |
| 6 | 索引已扫描数 + 重建索引 | `Text` + `SettingActionButton` | 运行时 |
| 7 | APIC 封面提取进度 + 4dp 进度条 | `Text` + `Box` | 运行时 |
| 8 | **其他网盘占位行（"敬请期待" ×N，不可聚焦）** —— 纯噪音，§6-4 判定删除 | `PlaceholderRow` (:193-195) | `CloudDriveType.PLACEHOLDER`（[CloudDriveType.kt:30](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/model/CloudDriveType.kt#L30)） |

### 1.10 DATA 数据 — [DataSettingsSection.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/DataSettingsSection.kt)

| # | 子项 | 控件 | key |
|---|---|---|---|
| 1 | 分区说明文本 | `Text` | — |
| 2 | 备份结果消息（v2.36.2 移到分区最顶部，4s 自动消费且仅 `!isError` 时消费） | `Text` | 运行时 |
| 3 | 导出备份 | `SettingActionButton` | `BackupData`（:1905-1933，**不含任何凭据**） |
| 4 | 扫码传输（手机下载/上传备份） | `SettingActionButton` | 运行时 |
| 5 | 播放统计面板入口（F2-1） | `SettingActionButton` | `play_records` (:476) |
| 6 | 歌单导入（SAF OpenDocument） | `SettingActionButton` | `playlist_import_history` (:299) |
| 7 | 最近导入记录行（点击跳「我的」，无删除） | `ImportedPlaylistRow` ×N | 同上 |
| 8 | 导入结果消息 | `Text` | 运行时 |
| 9 | 备份文件列表（恢复 / 删除，删除确认弹窗在宿主） | `BackupFileRow` ×N | SAF URI |

### 1.11 ABOUT 关于 — [AboutSection.kt:37-105](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/AboutSection.kt#L37)

| # | 子项 | 控件 |
|---|---|---|
| 1 | 应用名 / 版本 / 构建类型 / 许可证 / 支持的后端 | `AboutRow` ×5 (:38-42) |
| 2 | 后端类型 / 后端 API 版本（已连接时） | `AboutRow` ×2 (:45/:46) |
| 3 | 各服务 API 版本列表（`apiVersions`，空则显示"未连接"行） | `AboutRow` ×N (:60-64) |
| 4 | 网络音乐 / 独立音乐 / 电台 / 百度网盘 / 歌词 五行说明 | `AboutRow` ×5 (:67-71) |
| 5 | 版权文本 ×2 | `Text` (:74/:80) |
| 6 | GitHub 仓库地址 | `Text` + `.clickable` (:95-105) — ⚠️ **不是 `FocusableSurface`，TV 遥控器无法聚焦**（§5.5） |

### 1.12 设置页之外的持久化设置（必须决定归属）

| 子项 | 现居 | key | 消费方 |
|---|---|---|---|
| 歌词字号（0.7/1.0/1.3/1.6 + 预览） | `ui/screens/LyricsSettingsDialog.kt`，由播放页调起 | `lyrics_font_scale` (:877) | `NowPlayingBranch.kt:93-94`（`lyricsFontScale` :93 + `onLyricsFontScaleChange` :94 → `viewModel.updateLyricsFontScale`） |
| 歌词高亮模式（逐行/逐字） | `NowPlayingScreen.kt:473-477`（横屏）/:1766-1775（竖屏） | 见 `NowPlayingBranch:121` | `LyricsView` |
| 歌词源 | `NowPlayingBranch:120` | — | `LyricsManager` |
| **K 歌变调 / 倍速** | `NowPlayingBranch:144-149` 提交 | `k_pitch_semitones` (:445) / `k_playback_speed` (:446) | `PlayerManager`、`KaraokePlaybackScreen`、`VocalSeparationViewModel`。**裁决（用户 2026-10-18）：运行时控制项，不进设置页**（§6 第 13 行）—— 本表保留这两行只为说明"为什么设置页里没有它们" |
| 效果主题 | `VisualizerStage:537` | `settings_visualizer_theme` (:324) | `VisualizerViewModel:466` |
| **默认网络源** | **无 UI** | `settings_default_network_source` (:279) | **活链路**：`NasMusicApp:343` → `NetworkMusicManager`；`@Volatile` 镜像 :169/:213-214，同步读 :1199 |
| **模型自定义下载地址** | **无 UI** | `settings_model_download_url` (:282) | **活链路**：`NasMusicApp:324` → `ModelDownloadManager:205/:329`（自建镜像/NAS，SHA-256 校验仍生效） |
| 曲库快捷提示开关 | 无 UI | `show_library_shortcut_hint` (:363) | 读 `LibraryScreen:289`，写 :296（一次性提示，自动消费） |

### 1.13 死配置（有 key / 有读写方法，全树零消费方）

| # | key | 声明 | 门面 | 唯一「消费」 |
|---|---|---|---|---|
| D1 | `settings_spectrum_enabled` (:323) | AppPreferences:323 | `VisualizerPrefs.kt:14` | `AppPreferences.setSpectrumEnabled` (:1120-1122) 无人调用 |
| D2 | `weather_enabled` (:307) | AppPreferences:307 | `weather.weatherEnabled` Flow (:1023) | 只有 `BackupData` (:1925/:1969/:2075) |
| D3 | `weather_manual_city` (:308) | :308 | `:1034` | 同上 (:1926/:1970/:2076) |
| D4 | `weather_auto_refresh` (:309) | :309 | `:1045` | 同上 (:1927/:1971/:2077) |
| D5 | `cloud_drive_configs.customAppKey` | `CloudDriveConfig.kt:31` | `AppPreferences:1879-1881` | 零调用 |
| D6 | `…customSecretKey` | `CloudDriveConfig.kt:~33` | `AppPreferences:1883-1884` 区 | 零调用 |
| D7 | `…apiDriftNotified` | `CloudDriveConfig.kt:36` | `AppPreferences:1885-1887` | 零调用 |
| G1 | `settings_lyrics_offset` (:278) | :278 | `LyricsPrefs.kt:12` ← `MainViewModel:3062` ← `SettingsScreen:132` 参数 | **✕ 全链无控件**；`AppSettings.lyricsOffsetMs` (:25) / `:798` 读入后 **`lyrics/` 下无人使用**（`LrcParser.parse(offset=)` 只吃 LRC 头 `[offset:]`，见 [LrcParser.kt:18-25](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/lyrics/LrcParser.kt#L18)） |

### 1.14 磁盘缓存全量清单（2026-10-18 按代码实测；用户追加要求，落到 §10 第 13 条）

用户要求：「缓存管理部分需要读取代码，看是否还有可管理的缓存数据未纳入设置页中管理」。下面是把全树**所有落盘写入点**扫出来的结果 —— 四个根（`cacheDir` / `filesDir` / `getExternalFilesDir` / `databases/`）共 **14 处可管理缓存**（表 1–14），逐格对照 CACHE 分区现状（§1.7：1 个数字 + 2 个开关 + 4 个清除按钮）。**另有 4 处"扫得到但不是缓存"的落盘位置**（表 15–18：用户自己放的 LRC 目录、偏好 DataStore、外部目录的回落路径、模型目录名）—— 它们必须一起进登记表，否则 G9 会误判红、或者把用户文件当成缓存清掉。所有行号均已实读核对；表 15–18 是把 T8b 的正则**真跑一遍全树**才浮出来的（命中 16 个目录名，见 T8b Step 2）。这 18 行最终落进登记表的 **19 条**条目（拆合关系逐笔写在 T8b Step 4 的注记里，⛔ 不要把"18 行"与"19 条"当成互相印证的数）。

| # | 内容 | 路径构造（file:line） | 根 | 上限 / 淘汰 | 计入顶部数字? | 用户可清? | **结论** |
|---|---|---|---|---|---|---|---|
| 1 | Coil 图片磁盘缓存 | `cacheDir/image_cache`（[NasMusicApp.kt:591-592](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/NasMusicApp.kt#L591)，`maxSizeBytes` 100MB） | cacheDir | 100MB，Coil 自动 LRU | ✅ | ✅ `MainViewModel.kt:3316-3324`（`Coil.imageLoader().memoryCache/diskCache.clear()`） | 保留，但**说明要改口**（见结论 2） |
| 2 | 分离产物 WAV（伴奏/人声/原唱） | `cacheDir/accompaniment/{id}_{accompaniment,vocals,original}.wav`（[AccompanimentCache.kt:26,32-34,51,58](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/player/AccompanimentCache.kt#L26)） | cacheDir | 500MB + 10 文件（`:25-26`，`cleanupCache()` :64-97） | ✅ | ⚠️ 只删**三种后缀**：UI 走 `clearAccompaniments()`（:136-146），同目录里 `saveOriginalFile` 落的其它命名文件删不掉；**全量 API `clearCache()`（:126-130）零调用方** | 改接 `clearCache()` |
| 3 | 内嵌封面解包产物 | `cacheDir/cover_cache_{md5}.jpg\|png`（[EmbeddedCoverExtractor.kt:98](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/local/EmbeddedCoverExtractor.kt#L98)） | cacheDir | **无上限、无淘汰** | ✅ | ⛔ 「清除封面缓存」不碰它；类里只有 `clearMemoryCache()`（:150-152，注释写着「不删文件…由系统清理 cacheDir」）且**零调用方** | **新增清理项** |
| 4 | 本机专辑封面缩略图 | `cacheDir/album_covers/{mediaStoreId}.jpg`（[LocalCoverExtractor.kt:90-91](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/local/LocalCoverExtractor.kt#L90)） | cacheDir | 无上限 | ✅ | ⛔ **该类没有任何删除方法**（实测方法名单 :37/:73/:88/:107/:128/:134） | **新增清理项（要新写删除函数）** |
| 5 | HQ 分离的临时输入 | `cacheDir/nasmusic_hq/{id}_input.tmp`（[HqSeparationOrchestrator.kt:255-257](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/player/HqSeparationOrchestrator.kt#L255)） | cacheDir | 每任务后清（`cleanupTempFile` :322-331） | ✅ | ⛔ 进程被杀/断电后的残留**无人扫**（无启动 sweep） | **并入"清除残留临时文件"** |
| 6 | Demucs 解码临时 PCM | `cacheDir/demucs_pcm_{nanoTime}.tmp`（[DemucsSeparator.kt:580](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/player/DemucsSeparator.kt#L580)） | cacheDir | `finally`/错误路径删（:552/:602/:625/:791/:797） | ✅ | ⛔ 同上；且它是**大文件**（float32 44.1kHz×2ch ≈ 21MB/分钟，5 分钟一首 ≈ 106MB） | 同上 |
| 7 | 歌词持久缓存 | `filesDir/lyrics_cache/{id}.lrc` + `filesDir/lyrics_cache.json`（[LyricsPersistentCache.kt:36-37](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/lyrics/LyricsPersistentCache.kt#L36)） | filesDir | 2000 条（`:41`）LRU | ⛔ **不计**（只 walk `cacheDir`） | ✅ 按钮 `CacheSettingsSection.kt:88-96`（链路 `MainViewModel.kt:3307` → `LyricsManager.kt:390` → `LyricsPersistentCache.clear()` :119-124，**同时删 `.lrc` 目录与索引文件**） | **补占用行（目录与索引文件各 1 行，共用同一个清除入口）** |
| 8 | MV 搜索结果持久缓存 | `filesDir/mv_cache.json`（[MvPersistentCache.kt:27](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/mv/MvPersistentCache.kt#L27)） | filesDir | 5000 条（`:30`） | ⛔ | ✅ 按钮 :107-116 | **补占用行** |
| 9 | **专辑/艺人封面地址映射** | `filesDir/cover_url_cache.json`（[CoverUrlPersistentCache.kt:44](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/local/CoverUrlPersistentCache.kt#L44)） | filesDir | **10000 条**（`:53`） | ⛔ | ⛔ **`clear()` 早已写好（:106-117，KDoc 原文「供设置页"缓存管理"手动清除」），但全树零调用方** —— 实测 usages 只有 `MainViewModel:1810/:1835` 的 `exportAll` 与 :1877/:1930/:1952 的 `put*` | **新增清理项（接上现成 API，零新逻辑）** |
| 10 | **百度网盘本地索引** | `filesDir/baidu_index.json`（[BaiduFileIndexCache.kt:42](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduFileIndexCache.kt#L42)） | filesDir | **128MB 判死**（伴生对象 `MAX_CACHE_BYTES`，注释实录：3.8 万条目约 60MB；超限在 `load()` 里 `clear()` 后重扫 :72-78） | ⛔ | ⛔ 只有 SOURCES 的「重建索引」，既无大小显示也无删除 | **新增只读行**（删＝全量重扫，代价必须写在说明里） |
| 11 | 已下载歌曲（含随附封面/歌词） | `getExternalFilesDir(MUSIC)/{artist}/{album}/…`（[DownloadPathBuilder.kt:42-43,50-53](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/download/DownloadPathBuilder.kt#L42)） | 外部 | DB 记账 | ⛔ | ✅ **已在 DOWNLOAD tab**（统计 `DownloadSettingsSection.kt:66-74`、清空 :135-140） | ⛔ 不在 CACHE 重复放删除按钮 |
| 12 | 下载中断的 `.part` | `…/.tmp/{name}.part`（`DownloadPathBuilder.kt:50-53` + `TMP_DIR` :186） | 外部 | — | ⛔ | 自动：启动 `recoverAfterCrash()` 整目录扫（[SongDownloadManager.kt:644-651](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/download/SongDownloadManager.kt#L644)）；`clearAll()`→`cancelAll()` 删 DB 记录的 `tmpPath`（:543-544、:529-536） | ⛔ 不纳入（已有自动链路覆盖，见 §6-17） |
| 13 | Demucs ONNX 模型 166MB | `getExternalFilesDir(null)/models/`（[ModelDownloadManager.kt:103-109,115-117](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/player/ModelDownloadManager.kt#L103)） | 外部 | ±1% 尺寸校验 | ⛔ | ✅ 已在 LYRICS_KARAOKE 模型卡（下载/删除） | ⛔ 同上，只在汇总里计入体积 |
| 14 | 三个 Room 库 | `databases/downloads.db`（`DownloadDatabase.kt:102`）/ `local_music.db`（`LocalMusicDatabase.kt:37`）/ `photo_face.db`（`PhotoFaceDatabase.kt:46`） | databases | 无 | ⛔ | 仅 `photo_face` 有清除（`PhotoWallSettingsSection.kt:371` ← `SettingsBranch.kt:325`）；另两个只能靠「重建索引」 | **新增只读行**（⛔ 不提供删库按钮） |
| — | **以下 4 处不是缓存，但必须进登记表**（表 1–14 是"要不要管"，15–18 是"门会不会误判 / 会不会误删用户数据"） | | | | | | |
| 15 | 用户自放 LRC 的扫描目录 | `File(context.filesDir, "lyrics")` 与 `getExternalFilesDir(null)/lyrics`（[LyricsManager.kt:417-418](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt#L417)，`getLocalLrcFile()` 的只读扫描名单） | filesDir / 外部 | 无（只读扫描，app 从不写） | ⛔ | ⛔ **绝不可清**：里面是**用户自己拷进去的歌词文件**，删了是数据丢失不是省空间 | **登记为 2 条只读行**（`filesDir/lyrics` 与外部 `files/lyrics` 是两个位置、各 1 行、共用一条说明；G9 正则正好扫得到 `lyrics`，不登记 ⇒ 门红；登记成 `deletable=true` ⇒ 更糟）。⛔ 名单里另两处 `/storage/emulated/0/Music`（:415）与 `/Download`（:416）**不登记**：那是用户的**整机目录**、不属本应用，把它的体积显示进"应用缓存占用"是误导（G9 的根关键字也扫不到裸 `File("…")` 常量路径） |
| 16 | 偏好 DataStore | `File(context.filesDir, "datastore/" + …preferences_pb")`（[AppPreferences.kt:117](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt#L117)、[QualityOverrides.kt:52](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/prefs/QualityOverrides.kt#L52)） | filesDir | — | ⛔ | ⛔ 不可清（删 = 清空全部设置） | **登记为只读行**（它解释了一件必被追问的事：**为什么总占用大于各"可清"项之和**） |
| 17 | 外部音乐目录的**回落路径** | `File(filesDir, "music")`（[NasMusicApp.kt:399](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/NasMusicApp.kt#L399)、:410 —— `getExternalFilesDir(DIRECTORY_MUSIC)` 返回 null 时 `rootProvider` 的兜底） | filesDir | — | ⛔ | 与表 11 是**同一份数据** | ⚠️ **G9 豁免**（`cache-inventory-exempt:`，理由"回落路径已计入 `dir=""` 条目"）—— 两条都登记会把已下载歌曲**双计体积** |
| 18 | 模型目录名 | `File(baseDir, "models")`（[ModelDownloadManager.kt:105](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/player/ModelDownloadManager.kt#L105)，`baseDir = getExternalFilesDir(null)`） | 外部 | ±1% 校验（表 13） | ⛔ | 已在 LYRICS_KARAOKE 模型卡 | **登记但正则扫不到**（逗号前的"根"是变量 `baseDir` 而非 `cacheDir`/`filesDir`）⇒ 登记表完整度靠人工，这是 G9 的**已知边界**，KDoc 里必须写明 |

**另有一条现成却没人用的能力**：磁盘剩余空间 `StorageGuard.availableBytes()`（[StorageGuard.kt:58](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/download/StorageGuard.kt#L58)，双 `StatFs` 取小值）与它的 `StateFlow`（:46），类注释自己写着「30s 轮询 + 广播：**保持设置页实时显示**」，但实测 `ui/` 下对 `availableBytes` **零命中** —— 这个承诺今天没有落地点，正好用作占用一览的首行。

三条**结构性结论**（决定了 T8b 的形状）：

1. **顶部那个数字的口径是错的，而且是可复现的线上缺陷**：`CacheSettingsSection.kt:59-73` 只 `walkTopDown()` 了 `context.cacheDir`，而表 7–10 与 13–14 全在 `filesDir` / 外部 / `databases/` ⇒ (a) 数字系统性低估（歌词 LRC、MV、封面地址表、网盘索引、模型、已下载歌曲、三个 DB 一项都没算进去）；(b) 更糟的是**点了「清除歌词缓存」或「清除 MV 缓存」之后数字不动** —— `refreshKey++`（:93/:113）重算的还是 `cacheDir`。用户看到的"清了却没少"就来自这里，与 §2-11 的时长档 0 宽属同批既有缺陷。
2. **两个按钮名称与行为不符**：「清除全部缓存」（:119，说明 `settings_clear_all_cache_desc` 写"删除 HQ 模式缓存和网络歌曲缓存"）实际只删 `cacheDir/accompaniment` 里三种后缀的 wav，链路为 `SettingsBranch.kt:133` → `MainViewModel.kt:2699-2705` → `VocalSeparationViewModel.kt:306-307` → `PlayerManager.kt:295` → `HqSeparationOrchestrator.kt:213-215` → `AccompanimentCache.kt:136-146`，**既不清 Coil 也不清解包封面**；「清除封面缓存」反之只清 Coil，表 3/4 两块仍在。⇒ 用户无法区分"清了哪些"，正是主诉"看不懂"的一个具体来源。
3. **同类遗漏会反复发生，所以必须上门禁而不是靠下次再人肉扫**：本轮扫出 **3 个"写好但从未接线"的清除 API**（`CoverUrlPersistentCache.clear()` :106、`AccompanimentCache.clearCache()` :127、`EmbeddedCoverExtractor.clearMemoryCache()` :150）与 **2 个"尺寸 API 没人用"**（`AccompanimentCache.getCacheSize()` :102 返回字节、零调用方；`LyricsManager.getCacheSize()` :397 返回的是**条目数不是字节**，容易被误当尺寸用）。这些全靠 grep 才发现 ⇒ §5.7 新增 **G9 缓存登记表门**（登记表必须 ⊇ 源码里出现过的落盘目录字面量；登记为"可清"的项必须有真实清除调用点）。

---

## §2 现状问题诊断（12 条，逐条给证据）

1. **子项无边界（用户主诉）**：`SettingsComponents.kt` 的全部词汇是 `SectionTitle` (:45) / `SubSectionTitle` (:56) / 行组件，**没有分组容器原语**。分组只靠「一行 Primary 色粗体文字 + `Spacer(height=24.dp)`」，在 TV 上小标题和行标签同为 `FontSize.button()` 级别（`SubSectionTitle` :60 用 `FontSize.button()`，行标签 `SettingSwitch` :114 也用 `FontSize.button()`，只差 `FontWeight.Bold`），**视觉上无法区分「这是标题」还是「这是一项」**。
2. **域错位（不是"长度不均"）**：PLAYBACK 里同时装着播放行为、音质、K 歌人声分离、视觉滤镜四个互不相干的域（约 40 行），而「本地曲库开关」与「服务器/网盘来源」本属同一内容域却被拆成三个 tab；歌词设置则完全不在设置页。**tab 该按内容域划，条数是结果不是目标**（用户 2026-10-18 裁决，§3）。
3. **播放 tab 混装四个域**：可视化画质(:235)、人声分离(:262)、封面滤镜(:403) 与播放行为(:99-230) 并列在同一 tab —— 前两者与"怎么播"无关，一个是"看起来什么样"、一个是"跟着唱"。这三块必须独立成 tab（§3 的 `VISUAL_EFFECTS` / `LYRICS_KARAOKE`）。
4. **同类文案两套写法**：NETWORK tab 用裸 `Text` 当小标题，其余 tab 用 `SubSectionTitle`；`SettingSwitch` 的开关态用文字「✓ 开启」/「␣␣␣␣关闭」（`settings_wifi_on/off`，:119 —— 复用了 WiFi 字符串 key，命名与内容都是债）。
5. **幽灵管道**：`PlayerSettingsState.visualizerTheme` + `Actions.onChangeVisualizerTheme` 一路从 `SettingsBranch:160-161` → `SettingsScreen:545/570` 传到分区后被丢弃（§1.2-✕15）。同理 `SettingsScreen:132` 的 `onChangeLyricsOffset` 参数没有任何分区消费。它们让「效果主题/歌词偏移看起来应该有设置项」，实则没有。
6. **死配置随备份扩散**：D1–D7 不仅占 API 面，还被写进 `BackupData`（:1925-1927、:1969-1971、:2075-2077），使「清理设置」变成「清理数据格式」，风险从 UI 抬到持久层。
7. **TV 可达性缺陷**：关于页 GitHub 链接用 `Text.clickable`（AboutSection:95-105），遥控器聚焦不到；这是"看起来能点其实点不到"。
8. **宿主过重且逻辑内联**：`SettingsScreen` 单文件 1120 行、约 100 个参数、6 个近似重复的端点 `TextInputDialog` 宿主，网络测试的 OkHttp 调用直接写在 `onRunNetworkTest` lambda 里（:713-744）—— UI 层承担 I/O，无法单测。
9. **说明文案覆盖不齐**：带 `description` 形参的组件确实填了（`SettingSwitch` :114-115 会把说明渲染在标签下方），但**没有说明位的组件一条说明都没有**——`PlayModeSelector`（SettingsComponents:129-156 渲染 5 个播放模式，只有一句「播放模式」标题）、淡入淡出时长 5 按钮（PlayerSettingsSection:112-123，`description = ""`，无单位/作用范围说明；⚠️ 且这 5 个按钮当前**只有第一档可见**，见 §2-11）、照片墙「画面适配」两个 `OptionChip`（PhotoWallSettingsSection:419-432）、缓存目录占用行（CacheSettingsSection:75）、分离模型路径行。同一页里有的项有说明、有的项裸奔，是"看不懂"的主要来源。
10. **组件风格不统一（用户 2026-10-18 追加要求）**：`ui/screens/settings/` 下 **20 处裸 `FocusableSurface(`** 散在 7 个文件（`SettingsComponents.kt` 6、`NetworkMusicSection.kt` 6、`GeneralSettingsSection.kt` 2、`SettingsSectionList.kt` 2、`PlaylistImportUploadDialog.kt` 2、`ImportedPlaylistRow.kt` 1、`PhotoWallSettingsSection.kt` 1），每处都手写一遍容器色/圆角/缩放/时长，于是同一语义有多套取值（下列数字全部由 `grep -o` 在本目录实测，2026-10-18 复核）：
    - **圆角**：整目录 `RoundedCornerShape(N)` 共 8 种取值（12dp×16、8dp×6、10dp×2、4dp×2、2dp×2、16/6/3 各 1）；其中落在 **`shape = ` 参数位**（即瓦片/芯片自己的圆角，装饰性 `clip()` 除外）的有 **15 处、4 种取值**：12dp×9、8dp×3、10dp×2、6dp×1 —— 这 15 处是 §4.7 G7-A 的收口对象。
    - **内容内边距 4 套**：`24/18`（SettingsComponents:110/:197）、`24/14`（:242）、`24/12`（:300/:72）、**0 水平边距**（`SettingsInfoRow` :380）。
    - **`focusedScale` 6 种**（字面量 19 处，另 1 处条件式 \`if (enabled) 1.03f else 1f\` = SettingsComponents:187）：1.02×6（NetworkMusicSection:137/:236/:279/:331/:437、SettingsSectionList:81）、1.03×4（SettingsComponents:100/:232、ImportedPlaylistRow:50、NetworkMusicSection:380）、1.05×3（GeneralSettingsSection:82/:142、PlaylistImportUploadDialog:157）、1.06×2（SettingsComponents:273、PhotoWallSettingsSection:607）、1.08×3（SettingsComponents:143、SettingsSectionList:146、PlaylistImportUploadDialog:196）、1.1×1（SettingsComponents:361）。
    - **`animationDurationMs` 4 种**：250×10、200×6、150×1（PlaylistImportUploadDialog:158）、120×1（:197）。**`pressedScale` 3 种**：0.98×10、0.96×3、0.95×2。
    - **「已选中」三种互不兼容的表达**：实底 Primary + 黑字（SettingsComponents:145-146、PhotoWallSettingsSection:609）、`Primary.copy(alpha = 0.18f)`（GeneralSettingsSection:78/:138、NetworkMusicSection:439）、纯文字前缀「▶」（PlayerSettingsSection:191/:254）—— 合计 **5 处**（§4.7 G7-C 作用域内 4 处：SettingsComponents 的那处不在 `*Section.kt` 扫描范围）。
    - **尾部文案三个来源**：固定「确定」`common_confirm`（:204-208）、「导入」`settings_import_backup`（:262）、开关态文字「✓ 开启」/「␣␣␣␣关闭」（前导空格是对齐 hack）复用 WiFi key `settings_wifi_on/off`（:119）。
    - **危险动作只有 `BackupFileRow` 的删除用了 `Warning`**（:276-279），清除缓存 / 清除全部下载 / 断开连接与它视觉无差别。
    根因不是"没审美"，是**没有唯一的委托点**：每加一行组件就重抄一遍参数块，漂移是必然的。
11. **竖屏被挤压变形（用户 2026-10-18 追加要求）**：设置页顶层的竖屏两级页是有的（`SettingsScreen.kt:302/:426-431/:435`），但**11 个 `*Section.kt` 全按 TV 的 ~1592dp 内容宽写**，竖屏只有 407dp（`PHONE_UI_SCALE = 0.82` 后的 Compose 口径）⇒ 差 3.9×。实测到三类失效 + 一类现状可用样本，逐条行号、算式与修法见 **§4.8**；其中**淡入淡出时长档是现在就存在的缺陷**（`PlayerSettingsSection.kt:112-123` 的 `Row` + `forEach` 未给宽度 ⇒ 第一档吃满整行、后四档 0 宽，TV 与竖屏同时中招），⚠️ 而 §5.7 的 `ChipContentWidthScanTest` 抓不到它，因为那道门的抓手在 `FocusableSurface` 层、这里根本没出现它。既有竖屏门禁 `ScreenUiModeCoverageTest` 也覆盖不到：它的触发条件是**顶层 `fun XxxScreen(`**，分区文件叫 `XxxSettingsSection(` ⇒ 全部逃逸。
12. **缓存页管的只是"cacheDir 那一小块"（用户 2026-10-18 追加要求，全量实测见 §1.14）**：代码里有 **14 处落盘缓存**，CACHE 页只覆盖其中 1 个数字（且只 walk `cacheDir`）+ 2 个开关 + 4 个按钮。差额是具体可指认的：**5 处管不到**（`cover_url_cache.json` 10000 条、`baidu_index.json` 可涨到 128MB、`album_covers/` 无删除方法、`cover_cache_*.jpg` 无删除方法、`demucs_pcm_*.tmp` / `nasmusic_hq/*.tmp` 崩溃残留无人扫）＋**4 处统计不到**（歌词 LRC、MV、网盘索引都在 `filesDir`；模型与已下载歌曲在 `getExternalFilesDir`；三个 Room 库在 `databases/`）。连带产生一个用户可直接看到的怪象：**点「清除歌词缓存」「清除 MV 缓存」后顶部数字一动不动**（`CacheSettingsSection.kt:59-73` 重算的仍是 `cacheDir`）。另有两处**名称与行为不符**（「清除全部缓存」只删伴奏目录、「清除封面缓存」只清 Coil）与 **3 个写好却从未接线的清除 API**。修法与门禁见 **Task 8b / G9**。

---

## §3 重新分类（**只按内容归属划分，不追求各 tab 条数均衡**）

> **裁决原则（用户 2026-10-18 明确）**：一个设置项属于哪个 tab，**由它是什么内容决定**，不由"每个 tab 应该差不多长"决定。
> 因此本方案**不**把两个不同域的项目合并以凑长度，也**不**因为某个 tab 只有 3 行就非拆不可 ——
> `CACHE`（8 项）与 `ABOUT`（0 个可操作项）并存是正常结果。
> 上一版"容量方差从 40:3 收敛"的说法**作废**：那是巧合，不是依据。

**分类问的只有一句话：用户来找这个开关时，脑子里的词是什么？** 按此重划后，唯一真实的合并理由是"本来就同一个域"：

| 旧 tab | 归属 | 依据（内容，不是长度） |
|---|---|---|
| SERVER（3 行） | → **SOURCES** 卡① | 它就是"曲库从哪来"的一种来源；与网盘/本地曲库并列同类 |
| NETDISK | → **SOURCES** 卡③④ | 同上；占位行按 §6-4 删除 |
| LOCAL_MUSIC（3 行） | → **SOURCES** 卡② | `local_music_enabled` 决定本机/U 盘歌曲是否进入曲库**与聚合搜索**（`SearchAggregator` 的 LOCAL 分支，见 `LocalMusicGateTest` L3）—— 它是**来源开关**，不是本地文件管理 |
| DOWNLOAD | **保留独立 tab** | 「下载」是**离线副本的生命周期**（开关/上限/位置/清除/导出），与「来源」无关；与「缓存」也不同 —— 缓存**可随时清空且不丢内容**，下载**是用户要保住的内容**。三者是三个域，不合并 |
| CACHE | **保留独立 tab**，更名「存储与缓存」 | 缓存策略（歌词/封面）+ 占用与清理，同一域；**并按 §1.14 把全树 14 处落盘缓存收进同一张登记表**（占用一览 + 缺失清理项），这样"这个 app 占了多少、能不能腾"有一个唯一出口 |
| PLAYBACK 里的可视化画质 + 封面滤镜 | → **VISUAL_EFFECTS** | 与播放行为无关，是"看起来什么样"，与效果主题同域 |
| PLAYBACK 里的人声分离 + 模型管理 | → **LYRICS_KARAOKE** | 与歌词同属"跟着唱"这一个使用场景（分离模式 = 伴奏，模型 = 它的前提） |
| 播放页的歌词字号 / 高亮模式 | → **LYRICS_KARAOKE**（播放页入口保留） | 是歌词的显示属性，域内归属明确；设置页必须能看到"它存在且能复位" |
| GENERAL / PHOTO_WALL / NETWORK / DATA / ABOUT | 原地不动 | 域本身成立，只是内部缺分组边界（§4） |

### §3.1 新枚举

```kotlin
enum class SettingsSection(val titleRes: Int, val icon: ImageVector) {
    GENERAL(R.string.settings_general, Icons.Default.Settings),           // 外观 · 语言 · 方向
    PLAYBACK(R.string.settings_playback, Icons.Default.Audiotrack),       // 播放行为 · 过渡音量 · 音质 · 均衡器
    LYRICS_KARAOKE(R.string.settings_lyrics_karaoke, Icons.Default.Mic),  // 新增：歌词显示 + K 歌人声分离
    VISUAL_EFFECTS(R.string.settings_visual_effects, Icons.Default.AutoFilmed), // 新增：可视化 + 封面滤镜
    PHOTO_WALL(R.string.settings_photo_wall, Icons.Default.PhotoLibrary),
    SOURCES(R.string.settings_sources, Icons.Default.Storage),            // NAS · 本地曲库 · 网盘 · 默认网络源
    DOWNLOAD(R.string.settings_download, Icons.Default.Download),         // 离线副本（不变，卡片化）
    CACHE(R.string.settings_cache, Icons.Default.Tune),                   // 存储与缓存
    NETWORK(R.string.settings_network_advanced, Icons.Default.Wifi),      // 端点与凭据配置
    DATA(R.string.settings_data, Icons.Default.MusicNote),                // 备份 · 歌单导入 · 播放统计
    ABOUT(R.string.settings_about, Icons.Default.Info),
}
```

`SettingsSection.kt:37-49` 既有结论仍然成立：本项目**没有**持久化分区顺序、**也没有**任何测试断言 `entries` 顺序 ⇒ 重排与插入安全。

### §3.2 每 tab 的卡片构成（卡片即域内小分类）

| tab | 卡片（标题 → 内含项） |
|---|---|
| **GENERAL** | ①外观与动效：深色主题、动画、字号调整 −/＋<br>②界面语言：跟随系统/中/英<br>③屏幕方向：自动/竖屏/横屏 |
| **PLAYBACK** | ①播放行为：自动播放下一曲、播放模式<br>②过渡与音量：淡入淡出、时长 2/4/6/8/12s、应用音量<br>③音质：全局档位列表（每项自带码率说明）、清除逐曲覆盖<br>④均衡器：入口 + 当前预设名 |
| **LYRICS_KARAOKE** | ①歌词显示：字号（0.7/1.0/1.3/1.6）、高亮模式（逐行/逐字）<br>②人声分离：模式开关<br>③分离模型：进度 / 下载 / 删除 / 扫码传输 / 本地路径 / **自定义源 URL（新增暴露）** / 错误详情面板<br>⛔ 变调 / 倍速**不入本 tab**（运行时控制项，§6 第 13 行裁决） |
| **VISUAL_EFFECTS** | ①可视化：画质 3 档、当前效果主题（只读行，注明"在播放页切换"）<br>②封面滤镜：开关、模糊半径、暗化叠加 |
| **PHOTO_WALL** | 5 卡（现有 5 个 `SubSectionTitle` 一比一换卡）：照片来源 / 显示筛选 / 转场效果 / 停留期运动 / 音频反应 |
| **SOURCES** | ①NAS 服务器：连接状态、**「服务器配置」入口（独立页面，见 §3.3）**、断开连接<br>②本地曲库：总开关（唯一权限触发点）、已索引数、重新扫描<br>③百度网盘：开关、授权失败/根目录缺失横幅、登录-退出、音乐根目录、MV 目录<br>④索引与封面：已扫描歌曲数、APIC 提取进度、重建索引<br>⑤网络搜索默认源（**新增暴露**） |
| **DOWNLOAD** | ①下载开关：功能开关、播放时自动下载、自动下载上限<br>②存储位置：本机 / 外接<br>③已下载：统计 4 行、清除全部下载<br>④导出到设备：说明 + 状态机 |
| **CACHE** | ①缓存策略：缓存歌词、缓存封面<br>②占用与存储（按 `CacheGroup` 5 组，组行=小计、其下逐目录）：剩余空间；应用缓存（Coil/分离产物/解包封面/本机缩略图/两处临时残留）；持久缓存（歌词目录+歌词索引+MV+封面地址表+**用户 LRC 两处目录**，后两行只读、共用一条说明）；索引与数据库（百度索引+3 个 Room 库）；媒体与模型（已下载歌曲+模型，**只计体积**）；应用数据（偏好 DataStore，**只解释总数为什么比可清之和大**）<br>③缓存清理：清除歌词 / 封面（Coil）/ **解包封面（新增）** / **封面地址表（新增）** / **分离产物含临时残留（改名+扩容）** / MV |
| **NETWORK** | ①连通性：网络测试<br>②音乐搜索端点：Meting 预设 + 自定义、Jamendo Client ID<br>③MV 端点：预设 + 自定义<br>④歌词端点：酷狗、网易云<br>⑤天气 API Key |
| **DATA** | ①备份与恢复：导出、扫码传输、备份文件列表<br>②歌单导入：导入入口、最近导入记录<br>③播放统计：入口 |
| **ABOUT** | ①版本信息 ②连接信息 ③能力说明 ④开源与许可（GitHub 链接改可聚焦） |

### §3.3 「服务器连接设置」要不要并进设置页 —— **裁决：不并，保持独立页面**

结论：[ServerConnectScreen.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/ServerConnectScreen.kt)（**845 行**，仅由设置页 SERVER 卡进入：`ServerSettingsSection.kt:49` → `SettingsBranch.kt:223` → `navVM.navigateTo(Screen.ServerConnect)`）**必须继续是独立 `Screen`**，不内嵌进 tab。五条硬依据：

1. **它自带一个随页面开关的本地 HTTP 服务与常驻凭据二维码**（ServerConnectScreen:154-177、:255；注释即写明「token 随 URL 下发，HTTP 服务随页面开关」）。这套东西的生命周期必须绑在"整屏"上：嵌进卡片后，要么「切走 tab 服务还开着」，要么「在卡内滚动导致服务反复起停」，两种都是新缺陷面。**这是安全边界，不是排版问题**（凭据经局域网 URL 明文下发，页面离开即应关服务）。
2. **焦点域冲突**：TV 无触摸，输入框靠 D-Pad + 软键盘。设置页已有一套 `navFocusRequesters` 的侧栏↔内容区焦点交接（`SettingsScreen.kt:324-327`）；把 5 个输入框 + 类型选择卡 + 键盘弹层塞进同一个焦点域，等于让"表单"和"开关列表"抢左右方向键 —— 本项目在焦点域上已有明确教训（focusGroup + exit=Cancel 只能锁住上下，四向必须逐节点直赋，见 `project-compose-focus-domain-limit` 记忆与 `KaraokePickerTvFocusScanTest`）。
3. **BACK 栈会掉一层**：`AppRoot.kt:192` 已定死「从 ServerConnect 返回 = 回设置页」。合并后它不再是 `Screen`，BACK 将落到 NowPlaying —— 五级 BACK 链（AppRoot Level 0→3）要重写，收益为负。
4. **竖屏两级页会变成三级**：v2.36.0 的形态是「分区列表 → 全屏分区」两级。内嵌表单后是「列表 → 分区 → 表单」，返回头逻辑（`SettingsSectionBackHeader`）与 `NavigationViewModel.settingsSection` 的 BACK 消费分支（§6.2 既有设计）都要加分支。
5. **表单本身需要整屏宽度**：后端类型 ×5、地址/端口（按类型变默认值 :90-100）、账号/密码/API Token/访问码（按后端**条件显隐** :434/:452/:470）、测试连接 + 连接 + 结果反馈，右侧还要给二维码留位。

**设置页侧要做的事（T7 内）**：把 NAS 服务器卡的入口按钮做扎实 ——
- 入口按钮的 `description` 显示**当前生效摘要**（后端类型 + 主机，如 `Navidrome · 192.168.0.190:4535`），而不是空话；未连接时说明「首次使用需在连接页填写地址与账号」。
- 卡片说明文案明确「服务器配置在新页面完成，本页只负责开关与断开」，避免用户在设置页找输入框。
- ⛔ 不要在设置页内嵌任何凭据输入框（密码/API Token/访问码）。这条写进 `SettingsSectionWiringGateTest` 的负向断言：`ui/screens/settings/*.kt` 中不得出现 `TextFieldValue` / `PasswordVisualTransformation`。

---

## §4 UI 分组方案（解决「子项未做分割」）

### §4.1 设计原则：边界由「卡片外壳」提供，行组件不动

不改 `SettingSwitch` / `SettingActionButton` 的容器色（它们已是 `NasMusicColors.Surface` `0xFF162032` 的独立瓦片）。卡片外壳取**比页面背景略亮、比瓦片略暗**的中间色，形成三层明度阶梯：

```
页面 Background 0xFF0C1222  →  卡片外壳 Surface@0.45f ≈ 0xFF111A2B  →  行瓦片 Surface 0xFF162032
```

再加 1dp `NasMusicColors.Border` `0xFF2A3A52` 描边 + 卡片头（Primary 竖条 + 粗体标题 + 可选描述）。这样：分组边界 = 外壳矩形；组内单项 = 亮一级瓦片 + 8dp 间隙 + 焦点缩放/描边。**两套信息互不干扰，且迁移只加一层包裹，零行为变更。**

### §4.2 新增原语 ①：`SettingsGroupCard`（分组外壳 + 卡片头）

> 本方案新增**三类**东西：本节 `SettingsGroupCard`（边界）、§4.5 的 `SettingsHint`（说明位）、§4.7 的 `SettingsTokens` + `SettingsTile`（风格唯一取值 + 委托点）。除此之外**行级组件的公开签名一律不动**。
> `SettingsTokens.kt` 在 **T1** 就整份建立（§4.7-① 全文），虽然当时还没有行组件消费它 —— 先立令牌再迁移，才能避免"迁移到一半发现取值没处放"又回去写手调值。

```kotlin
/** 设置分组卡片：卡片头 + 内部留白；行组件保持原样嵌入。 */
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
```

要点：
- `Modifier.fillMaxWidth()`（无参）只作用在卡片外壳，不违反 `ChipContentWidthScanTest`（该门禁扫的是 `FocusableSurface` 调用点，卡片不是 `FocusableSurface`）。
- ⛔ 卡片**不可聚焦**（不加 `FocusableSurface`）：TV 上焦点必须逐行落在真实控件上，卡片参与聚焦会多出一跳、破坏既有 `navFocusRequesters` 节奏。
- ⚠️ `Row` 里的 `AdjustButton` / 时长按钮等既有「多按钮同排」写法照搬进卡片时仍**必须**保持固定宽度或 `Modifier.weight()`，否则复现 v2.35.0/v2.37.0 的 0 宽缺陷。

### §4.3 迁移的唯一动作

```kotlin
// 迁移前
SubSectionTitle(stringResource(R.string.settings_cache_switch))
SettingSwitch(label = …, checked = …, onClick = …)
SettingSwitch(label = …, checked = …, onClick = …)

// 迁移后
SettingsGroupCard(title = stringResource(R.string.settings_group_cache_switch)) {
    SettingSwitch(label = …, checked = …, onClick = …)
    SettingSwitch(label = …, checked = …, onClick = …)
}
```

- 分区顶部的 `SectionTitle(stringResource(section.titleRes))` **保留**（TV 右栏与竖屏二级页都需要页面级标题），卡片头是它的下一层。
- 卡片标题一律新增 `settings_group_*` 字符串，`values/` 与 `values-en/` 同步；`SubSectionTitle` 迁移完即删（§5.7 门禁要求全树 0 命中）。

### §4.4 三形态一致性

`SettingsScreen` 的三种形态共用同一批分区 composable（TV 侧栏 240dp + 右栏 `LazyColumn`；手机横屏双栏；手机竖屏两级页 `SettingsSectionList` + 全屏分区 + `SettingsSectionBackHeader`），因此卡片化自动三形态生效。需要额外确认两点：
- 竖屏左右内边距：卡片 `padding(bottom = 16.dp)` 外，竖屏根 `Column` 保持 `padding(horizontal = 16.dp)`，避免卡片描边贴屏幕边。
- 触控目标：卡片内的 `AdjustButton` 已用 `portraitTouchTarget(48.dp)`（SettingsComponents:359），迁移不动它。

### §4.5 每个子项都要有一句文字说明（用户 2026-10-18 要求）

**规范（一句话）**：卡片里**每个可操作项**都必须有一句说明，告诉用户「这个东西干什么 / 改了会怎样」；**只读信息行**（`SettingsInfoRow` / `AboutRow` / 统计与路径行）豁免——它们本身是数据，说明由所属卡片的 `description` 承担。

**三条硬要求**
1. 长度 **≤ 26 个汉字**（≈一行半手机竖屏、一行 TV）。超过就是文案没提炼，不是字号问题；不要用 `FontSize.small()` 塞长段落。
2. 说明写**效果**，不写实现：✅「切歌时新旧两首重叠淡入」 ❌「启用 crossfade 参数」。
3. ⛔ 说明位**不得**被拿来显示当前值（现状有违例：`BaiduPanSettingsSection` 把根目录路径塞进 `description`）。当前值走 `SettingsInfoRow` 或值文本，与说明分离——否则卡片化后同一行既像说明又像值，回到"分不清"。

**两个新增位（补齐无说明位的组件）**

```kotlin
/** 芯片组/调整行上方的说明文本（与 SettingSwitch 的 description 同字号同颜色，保证覆盖一致）。 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = NasMusicColors.TextSecondary,
        fontSize = FontSize.small(),
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

/** 播放模式选择器补说明位（原组件无 description 形参，是覆盖缺口的源头之一）。 */
@Composable
internal fun PlayModeSelector(
    current: PlayMode,
    onSelect: (PlayMode) -> Unit,
    description: String,          // ← 新增必填，强制调用方给说明
) { /* 标题 + SettingsHint(description) + 原 chips */ }
```

**逐条补齐清单**（✅ = 现在已有说明、保持不动；🔧 = 现在没有、必须补，右侧为**落地文案**）

| tab / 卡 | 项 | 状态 | 说明文案（中） | 新增 string key |
|---|---|---|---|---|
| GENERAL ① | 深色主题 / 动画 / 字号 | ✅ / ✅ / 🔧 | 字号：「在当前界面字号基础上整体放大或缩小，范围 −8 到 +8」 | `settings_group_font_hint` |
| GENERAL ② | 界面语言 | ✅ | — | — |
| GENERAL ③ | 屏幕方向 | ✅ | — | — |
| PLAYBACK ① | 自动播放下一曲 | ✅ | — | — |
| PLAYBACK ① | **播放模式** | 🔧 | 「当前队列按什么顺序继续播放」 | `settings_play_mode_desc` |
| PLAYBACK ② | 淡入淡出开关 | ✅ | — | — |
| PLAYBACK ② | **淡入淡出时长** | 🔧 | 「切歌时新旧两首重叠的秒数，越长过渡越平缓」 | `settings_crossfade_duration_desc` |
| PLAYBACK ② | 应用音量 | ✅ | — | — |
| PLAYBACK ③ | 音质档位（每档） | ✅ | — | — |
| PLAYBACK ③ | 两条档位说明 | ✅ | 合并进卡片 `description`，不再散落两处 `Text` | — |
| PLAYBACK ③ | 清除逐曲覆盖 | ✅ | — | — |
| PLAYBACK ④ | 均衡器入口 | ✅ | — | — |
| LYRICS_KARAOKE ① | **歌词字号** | 🔧 | 「只影响播放页歌词大小，不影响系统界面」 | `settings_lyrics_scale_desc` |
| LYRICS_KARAOKE ① | **歌词高亮** | 🔧 | 「逐行整句变色，逐字需要该歌曲有词级时间轴」 | `settings_lyrics_highlight_desc` |
| LYRICS_KARAOKE ② | 人声分离模式 | ✅ | — | — |
| LYRICS_KARAOKE ③ | **分离模型（下载/删除/路径/自定义源）** | 🔧（卡级） | 三个按钮**已有** description（`settings_download_model_hint` 等）；🔧 指卡级「分离要本机跑模型，约 158 MB；电视较慢属正常」与 T10 新增的自定义源行「填 NAS 直链，校验和仍需匹配」 | `settings_model_size_desc` / `settings_model_custom_url_desc` |
| VISUAL_EFFECTS ① | 可视化画质 | ✅ | — | — |
| VISUAL_EFFECTS ① | **效果主题只读行** | 🔧 | 「在播放页全屏可视化里按方向键切换主题」 | `settings_visualizer_theme_desc` |
| VISUAL_EFFECTS ② | 封面滤镜三项 | ✅ / 🔧 / 🔧 | 模糊半径「在播放页背景上生效，与画质无关」；暗化「压暗背景让歌词更清楚」 | `settings_cover_blur_desc` / `settings_cover_dark_desc` |
| PHOTO_WALL ①–⑤ | 各开关 | ✅ | — | — |
| PHOTO_WALL ③ | **随机/固定转场** | 🔧（固定转场 chips 无说明） | 「转场效果的挑选方式，下方可选具体时长」 | `settings_pw_transition_mode_desc` |
| PHOTO_WALL ④ | **画面适配两个 `OptionChip`** | 🔧 | 「满屏会裁掉边缘，完整则上下留黑边」 | `settings_pw_scale_desc` |
| PHOTO_WALL ① | **来源三开关** | ✅ | 三个开关**已有** description（`settings_photo_wall_gallery_desc` / `_external_desc` / `_jellyfin_desc`）；如需分组级提示可写进卡片 `description`，不算 G6 缺口 | — |
| SOURCES ① | 服务器配置入口 / 断开 | ✅ | 按 §3.3 改为显示当前后端与主机摘要 | — |
| SOURCES ② | 本地曲库开关 / 重扫 | ✅ | 两个控件**已有** description（`settings_local_music_source_desc`「扫描本机与 U 盘上的音乐；打开后才会申请音乐权限」/ `settings_local_music_rescan_desc`「全量重扫本机与 U 盘上的音乐文件」），G6-A 现状即过 | — |
| SOURCES ③ | 百度三项目录/登录 | ✅ | — | — |
| SOURCES ④ | **重建索引 / APIC 封面** | 🔧（卡级） | ⚠️本卡**只有一个按钮**（重建索引，且**已有** description：扫描中显示进度、否则 `settings_netdisk_index_rebuild_desc`）；**APIC 没有独立入口按钮** —— 封面提取是索引扫描成功后自动启动的后台任务（`NetworkMusicViewModel:612`），设置页只有进度文本。🔧 指卡级说明「索引决定曲库能否被搜到；封面提取读取内嵌图片，耗时随曲数增长」 | `settings_netdisk_index_desc2`（卡级一条；⛔ 不要 `settings_baidu_apic_desc` —— 没有 APIC 按钮可挂） |
| SOURCES ⑤ | **默认网络源（新增）** | 🔧 | 「搜索与点播优先使用的网络音乐来源」 | `settings_default_source_desc` |
| DOWNLOAD ① | 三个开关与上限 | ✅ / ✅ / ✅ | — | — |
| DOWNLOAD ② | **存储位置两按钮** | 🔧 | 「换目录不会自动搬迁已下载的歌曲」 | `settings_download_location_hint` |
| DOWNLOAD ③ | 清除全部下载 | ✅ | — | — |
| DOWNLOAD ④ | 导出到设备 | ✅ | — | — |
| CACHE ① | 缓存歌词 / 封面 | ✅ | — | — |
| CACHE ② | **占用一览：剩余空间 + 5 个组行 + 19 条目录行**（T8b） | 只读行**豁免功能说明**（§4.5 规范） | 首行「剩余空间」1 个（现树**无**语义正确的 key —— `settings_download_stats_size`「占用空间」(:331) 与 `download_storage_full`「存储空间不足（已预留 100MB）」(:1282) 都不是"可用容量"，必须新增 `settings_cache_free_space`）+ 组行标签 5 个（应用缓存 / 持久缓存 / 索引与数据库 / 媒体与模型 / 应用数据）+ 目录标签 19 个（`settings_cache_dir_coil`、`_separation`、`_hq_tmp`、`_embedded`、`_pcm_tmp`、`_local_covers`、`_lyrics`、`_lyrics_index`、`_mv`、`_cover_url`、`_local_lrc`、`_local_lrc_ext`、`_baidu`、`_db_downloads`、`_db_local`、`_db_face`、`_downloads`、`_model`、`_prefs`；⚠️ `_local_lrc` 与 `_local_lrc_ext` 是**同一种东西的两个位置**（`filesDir/lyrics` 与外部 `files/lyrics`，[LyricsManager.kt:417-418](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt#L417)），两行**共用同一条说明**，所以说明只 9 条不是 10 条） | **25 个 key**（1 + 5 组名 + 19 标签，均为**名称**不是说明） |
| CACHE ② | **9 条"有代价"的只读行说明**（T8b） | 🔧 | 「歌词与索引同进同退」「清后需重新联网解析」「**用户自行放入的 LRC，不删除**」「清除需重新扫描全部文件」「删除需重新扫描本机曲库」「人脸数据在照片墙设置里清除」「在下载设置里管理」「分离模型在歌词与K歌里管理」「**删除等于重置全部设置**」（每条 ≤ 26 汉字） | **9 个 key**（`settings_cache_dir_*_desc`，见 §1.14 表 15/16 与 T8b Step 7） |
| CACHE ② | 卡片级说明 | 🔧 | 「本机已占用的空间，只读；清除请用到下方「缓存清理」」（复用现存的 `settings_cache_dir_size` 槽位 —— 该 key 现值为「当前缓存目录大小: %s」，仅被 `CacheSettingsSection.kt:75` 消费；T8b 后首行改为「剩余空间」，此 key 腾出，把值改成不含 `%s` 的卡片说明即可，⛔ 别新增 key） | — |
| CACHE ② | 顶部「应用缓存」数字口径 | 🔧（**线上缺陷修复**） | 原「统计有延迟，清除后立即回零」不成立：口径漏了 `filesDir` 与外部目录（§1.14 结论 1）⇒ 改由 `CacheInventory.snapshot()` 出数，`truncated` 时显示「≥」 | — |
| CACHE ③ | 清除歌词 / 封面（Coil）/ MV / 分离产物 | 4 项已有说明 | 前三个保留；第四个**改名 + 改说明**：「清除全部缓存」→「清除分离缓存」，说明「删除伴奏与人声副本，含分离临时残留」（必须与真实删除范围一致，§1.14 结论 2） | —（改名与说明**复用** `settings_clear_all_cache` / `_desc` 两个 key 的槽位，只改文案） |
| CACHE ③ | **清除解包封面（新增）** | 🔧 | 「清除从歌曲里提取的封面图，下次播放重新提取」 | `settings_clear_extracted_cover_desc` |
| CACHE ③ | **清除封面地址表（新增）** | 🔧 | 「清除在线封面地址，下次需重新联网解析」 | `settings_clear_cover_url_desc` |
| NETWORK ①–⑤ | 端点行 / Client ID / 天气 Key | ✅ | 端点行把「自定义端点风险」合并进卡片 `description` | `settings_group_endpoint_risk_desc` |
| DATA ①–③ | 导出 / 扫码 / 恢复 / 导入 / 统计 | ✅ | — | — |
| ABOUT ①–④ | 只读信息行 | 豁免 | 卡片 `description` 写「这里只是版本与能力信息，不可修改」 | `settings_group_about_desc` |

> 🔧 **19 处**（按「状态」列的 🔧 记号数：**非 CACHE 14 处 + CACHE 5 处**）。**新增 string key 53 个** = 非 CACHE **17 个**（15 项文案 + 2 条卡片级 `settings_group_endpoint_risk_desc` / `settings_group_about_desc`；与 T14 Step 4 名单同口径；⛔ 不含 `settings_karaoke_pitch_desc` / `settings_karaoke_speed_desc` —— 变调/倍速不进设置页，§6-13）+ **T8b 缓存页 36 个**（1 剩余空间标签 + 5 组名 + 19 目录标签 + 9 条代价说明 + 2 个新按钮说明）。
> ⚠️ **v1.1 审校订正**：初版把「PHOTO_WALL ① 来源三开关」「SOURCES ② 本地曲库开关/重扫」误标为 🔧（两处控件**本来就有** description，G6-A 现状即过），并把「SOURCES ④」按两个按钮计（实际 APIC 无入口按钮、扫描后自动提取）—— 三处已改 ✅ / 合并，🔧 21→19、非 CACHE key 20→17。
> ⚠️ 两个**复用槽位不算新增**：`settings_cache_dir_size`（现「当前缓存目录大小: %s」→ 腾出后改值为卡②卡片说明，⛔ 顺带去掉 `%s`）、`settings_clear_all_cache` / `settings_clear_all_cache_desc`（原"清除全部缓存"名与说明 → 只改文案，键位留给"清除分离缓存"）。旧文案里的「22 个」是把两条卡片级**重复计入**的结果，以本行口径为准。
> ⚠️ 还有**一处既有的"参数类型"改动**也不进新增名单：`status_accompaniment_cache_cleared`（`values/strings.xml:855`「伴奏缓存已清除（%d 个文件）」/ `values-en:815`）要随 T8b Step 5 的返回值改造，从 `%d 个文件` 改成 `%s`（`formatBytes`）—— 中英**必须同批改**，只改一侧会让 `LanguageMirrorTest` 之外的运行期 `getString` 格式化直接崩（占位符类型不匹配是 IllegalArgumentException，不是编译错误）。
> ⛔ 全部 `values/strings.xml` + `values-en/strings.xml` 同步（同 §5.7 的字符串门 G3、`LanguageMirrorTest` 要求中英 key **一一对应**，只加中文会在竖屏一级列表渲染时崩）。
> ⚠️ **分工**：T14 只补**非 CACHE 的 15 项 + 2 条卡片级**；CACHE 的 5 处由 **T8b 自带** —— 9 条代价说明挂在只读 `SettingsInfoRow` 上（G6 不扫只读行，§4.5 规范），2 个新按钮的 `description` **必须在 T8b 当场写**，否则 T14 建 G6 时会红在旧任务身上。

**门禁（G6，写进 `SettingsGroupCoverageTest`，实现见 T14 Step 1）**
- 断言 A（含原 B）：`ui/screens/settings/*Section.kt` 里每个 `SettingSwitch(` / `SettingActionButton(` / `PlayModeSelector(` 调用，其**实参区**（从 `(` 起括号配对到匹配的 `)`，跳过字符串字面量）必须含 `description =`，且实参不得是空串字面量 `""`。`PlayModeSelector` 的形参改成必填后编译器已兜住"必须有"，这条断言防的是**有人把它改回可选**。
- 断言 C：以 `SettingsGroupCard(` 的大括号配对区为扫描单元（与 G1 同一套区域解析），**含 `AdjustButton(` 或 `SettingsChip(` 的卡区必须至少出现一次 `SettingsHint(`** —— 即"芯片组/调整行也得有说明"。（T11 把私有的 `OptionChip` 换成了共享的 `SettingsChip`，抓手随之改名。）⛔ 不用"上方 6 行内"这类行窗口判据，它对格式化和代码移动极敏感，会把门变成噪音源。
- 断言 D（负向自证）：A 与 C 各配一个破坏样本必须判负（A 的样本里同时含"缺 description"与"`description = ""`"两种行，另验证带非空说明的行**不被误伤**）；再加两条**空转断言**（A 的真实调用点数 > 0、C 的真实含芯片卡片数 > 0）。
- 豁免标记 `Desc-exempt: <理由>`，且注释里的示例写法先剥离（口径同 `ChipContentWidthScanTest`）。⛔ `SettingsInfoRow` / `AboutRow` 只读行**不进**扫描名单（§4.5 规范：它们的说明由卡片 `description` 承担）。


### §4.6 D-Pad 焦点

卡片外壳不可聚焦 ⇒ 每个 tab 的可聚焦序列 = 卡片内行序列 = 与迁移前完全一致。`SettingsScreen.kt:324-327` 的 `navFocusRequesters`（侧栏 ↔ 内容区）只与 tab **集合**有关，重分类后必须同步：删除 `LOCAL_MUSIC`/`SERVER`/`NETDISK` 三个键，新增 `LYRICS_KARAOKE`/`VISUAL_EFFECTS`/`SOURCES`。这是 §3 改动里唯一会漏就出 bug 的地方，所以由 §5.7 的 G3 接线门禁锁住。

⚠️ `SOURCES` 卡①的「服务器配置」按钮**跳出设置页**（`Screen.ServerConnect`，§3.3）：返回时 `AppRoot.kt:192` 把焦点交回 `navSettings`，卡片化后这个落点仍是分区级 —— 迁移时不要把它改成"回侧栏"，否则用户每次配完服务器都要重走一遍 tab 列表。

---

### §4.7 组件风格统一（用户 2026-10-18 追加要求）

> 诊断见 §2-10。这一节的目标不是"更好看"，而是**把同一语义只留一种取值、且取值只写在一个地方**。
> 判据很硬：改完 `ui/screens/settings/` 里**不允许再出现字面量圆角、字面量缩放、字面量动画时长**，全部走令牌；行组件的 `FocusableSurface` 参数块从 6 份收成 1 份。

#### ① 令牌（唯一真源）

```kotlin
// app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsTokens.kt
package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 设置区尺寸与动效令牌（§4.7）。
 *
 * 存在理由：同一语义此前各有 2–6 套取值（圆角 8 种、内边距 4 套、focusedScale 6 种、
 * 动画 4 种）。改任何一处只动这里，⛔ 不要在分区文件里写字面量。
 * 卡片外壳（`SettingsGroupCard`）用 `cardShape`，行用 `rowShape`，两级圆角不可互换 ——
 * 圆角一致 = 层级只靠明度与缩进区分，这正是 §4.1 的三阶明度阶梯成立的前提。
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
```

#### ② 参数块收成一份：`SettingsTile`

`SettingSwitch` / `SettingActionButton` / `BackupFileRow` / `AdjustButton` / `PlayModeSelector` 的芯片 / `GeneralSettingsSection` 的两处内联 `FocusableSurface`（语言 :74-82、方向 :134-142）**全部改为委托同一个内部封装**，这是"风格统一"唯一可靠的执行方式 —— 逐个组件手改参数必然再次漂移：

```kotlin
// 追加在 SettingsComponents.kt
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SettingsTile(
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    danger: Boolean = false,
    chip: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val accent = if (danger) NasMusicColors.Warning else NasMusicColors.Primary
    FocusableSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.padding(vertical = if (chip) 0.dp else 4.dp),
        shape = if (chip) SettingsTokens.chipShape else SettingsTokens.rowShape,
        focusedScale = when {
            !enabled -> SettingsTokens.focusScaleDisabled   // 原 SettingActionButton:187 的 `if (enabled) 1.03f else 1f`
            chip -> SettingsTokens.focusScaleChip
            else -> SettingsTokens.focusScaleRow
        },
        pressedScale = if (chip) SettingsTokens.pressScaleChip else SettingsTokens.pressScaleRow,
        animationDurationMs = SettingsTokens.animMs,
        containerColor = when {
            !enabled -> NasMusicColors.Surface.copy(alpha = 0.5f)
            selected -> accent.copy(alpha = 0.18f)          // 「已选中」只有一种表达（§4.7-③）
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
            else -> NasMusicColors.Primary.copy(alpha = 0.15f)
        },
        focusedContentColor = if (enabled) NasMusicColors.TextPrimary else NasMusicColors.TextSecondary,
        focusBorderColor = NasMusicColors.FocusRing.copy(alpha = 0.6f),
        content = content
    )
}

/** 行瓦片的统一内容排版：标签 + 说明（左，占权重）+ 尾部（右）。 */
@Composable
private fun SettingsTileBody(
    label: String,
    description: String?,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsTokens.rowHPadding, vertical = SettingsTokens.rowVPadding),
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
```

#### ③ 六条统一规则（每条都对应 §2-10 里的一处漂移）

| # | 规则 | 落地取值 | 被消灭的现状 |
|---|---|---|---|
| S1 | **圆角只有两级**：卡 18 / 行 12（芯片 10） | `SettingsTokens.cardShape` / `rowShape` / `chipShape` | 整目录 `RoundedCornerShape(N)` 8 种取值；其中 `shape = ` 参数位 15 处 / 4 种（12dp×9、8dp×3、10dp×2 GeneralSettingsSection:77/:137、6dp×1）+ `PlaceholderRow` 的 8dp（:73） |
| S2 | **行内边距只有一套** | `20 / 16dp`（`SettingsTileBody`） | `24/18`、`24/14`、`24/12`、`SettingsInfoRow` 的 0 水平边距（:380） |
| S3 | **「已选中」只有一种表达**：`Primary@0.18` 底 + Primary 字 + 前缀「▶」 | `SettingsTile(selected = true)`；⛔ 实底 Primary 留给**唯一主 CTA**（连接页的「连接」按钮），设置页不出现实底选中 | 实底 Primary + 黑字（SettingsComponents:145-146、PhotoWallSettingsSection:609）／`Primary.copy(alpha = 0.18f)`（GeneralSettingsSection:78/:138、NetworkMusicSection:439）／纯文字前缀「▶」（PlayerSettingsSection:191/:254）三套并存、合计 5 处（§2-10） |
| S4 | **缩放/时长只有一套**：行 1.03、芯片 1.06、按压 0.98/0.96、220ms | `SettingsTokens.focusScaleRow/Chip`、`pressScaleRow/Chip`、`animMs` | `focusedScale` 6 种（1.02/1.03/1.05/1.06/1.08/1.1，字面量 18 处）、`animationDurationMs` 4 种（250/200/150/120）、`pressedScale` 3 种（0.98/0.96/0.95） |
| S5 | **尾部只表达"这是什么动作"**：开关 = 分段式开关轨（§5.2，用户 2026-10-18 从三张参考图里选定中间那款）、进入下一级 = `›`、纯动作不给文字；⛔ 不再固定挂「确定」 | `SettingSwitch` 尾部 `SwitchTrack`；`SettingActionButton(trailing = Trail.Arrow)` 或 `Trail.None`；新增 `settings_on` / `settings_off` 两个语义 key（zh 开/关、en ON/OFF，同时用于滑块内文本与 TalkBack `contentDescription`） | `common_confirm`「确定」出现在每个入口按钮（:204-208）、`settings_import_backup`（:262）、`settings_wifi_on/off`（:119）三种尾部来源 |
| S6 | **危险动作统一用 `Warning`**：清除全部下载 / 清除全部缓存 / 删除备份 / 断开连接 | `SettingsTile(danger = true)`（文字 + 焦点底都取 `Warning`） | 只有 `BackupFileRow` 的删除用了语义色（:276-279），其余破坏性按钮与普通按钮无差别 |

#### ④ 顺带修掉的三处排版缺陷（属于"统一"，不是新增功能）

- `SettingsInfoRow`（:378-396）与 `AboutRow`（:295-320）是同一个"标签 + 值"版式的两套实现，且前者用 `SpaceBetween` 不限宽 —— **长值会把标签挤没**（`AboutRow` 的 KDoc :296-298 与 :309-311 记录的就是同一问题在竖屏的两次修复）。统一为 `AboutRow` 那套 `1 : 1.5` 权重双栏、值恒右对齐；`SettingsInfoRow` 保留名字与签名不变，只把内部换成同一个实现。
- `SettingSwitch` 与 `SettingActionButton` 的 `description` 用 `FontSize.body()`（:115 / :202），而 GENERAL 的语言/方向说明用 `FontSize.small()`（GeneralSettingsSection:60、:120）—— 同为"行说明"却两种字号。统一取 **`FontSize.small()`**（与 §4.5 的 `SettingsHint` 同规格），行标签仍是 `FontSize.button()`。
- **说明的颜色也有两套**：`SettingSwitch`/`SettingActionButton` 的说明用 `LocalFocusableContentColor.current`（:115 / :202，跟随焦点态变色），GENERAL 的说明用固定色。统一为**固定 `TextSecondary`** —— 变色会让"标签 + 说明"两行在聚焦时同时提亮，反而削弱了标签/说明的层级差；`SettingsTileBody`（②）已按此实现。

#### ⑤ 明确**不做**的（避免变成重写 UI）

- ⛔ 不改 `SettingSwitch` / `SettingActionButton` / `SettingsInfoRow` / `AdjustButton` 的**公开签名**（S5 的尾部形态通过新增带默认值的形参 `trailing` 实现，调用点可不改；`PlayModeSelector` 的 `description` 必填是 §4.5 已定的唯一例外）。
- ⛔ 不动 `FocusableSurface` 本身（全站共用，`FocusIndicatorContractTest` 守着）；`SettingsTile` 只是设置区的委托层。
- ⛔ 不引入新的 Material 组件（TV Compose 是 alpha，§AGENTS 已知坑），开关轨用 `Box` + `Modifier.offset` 自绘（§5.2），⛔ 不用 `androidx.compose.material.Switch`。
- ⛔ **卡片头内部留白不令牌化**（`SettingsGroupCard` 的 16/14/6/27dp 与竖条的 2dp 圆角）：它本身已是唯一实现点（G1 强制所有分组走这个 composable），再包一层只是给 4 个数字换名字。这就是 G7-A 的判据取**参数位**（`shape = `）而不是"所有形状字面量"的原因 —— 装饰性 `clip()` 与小面积描边属于各自组件的内部。

#### ⑥ 门禁 G7（`SettingsGroupCoverageTest` 内，与 G6 同一提交组）

**范围先说清**（范围靠 glob 表达会被"新文件刚好不匹配"悄悄放过，所以门里维护一份点名清单并断言它不多不少）：

```kotlin
private val styleOutOfFileNames = setOf(
    "SettingsTokens.kt",            // 令牌的唯一定义处
    "PlaylistImportUploadDialog.kt",// 对话框按钮词汇：不是卡片行，§4.7 不收口
    "SettingsSectionList.kt",       // 竖屏分区导航列表：非行瓦片，圆角是列表形状
)
```
门内先断言 `ui/screens/settings/` 下的文件名集合减去 `styleOutOfFileNames` 后**非空**，再断言 `styleOutOfFileNames` 里的每一项**确实存在**（防止改名后名单变成死条目 = 该文件重新受检却没人发现）。这三处之外不收口，只留作 §9 的已知残余。

- **G7-A（取值必须走令牌）**：范围 = 上述目录内**非豁免**文件（含 `SettingsComponents.kt` —— `SettingsTile` 传的是 `SettingsTokens.xxx`，天然不含字面量）。注释剥离后不得匹配这四个**参数位**模式：
  `shape = RoundedCornerShape(` / `focusedScale = 1` / `pressedScale = 0` / `animationDurationMs = [0-9]`。
  ⛔ 判据只取**参数位**不取所有 `RoundedCornerShape(`：装饰性 `clip(RoundedCornerShape(4.dp))`（PlayerSettingsSection:294/:301/:390 的进度条与缩略图）不是瓦片形状，令牌化它属于范围膨胀。
- **G7-B（委托点唯一）**：`*Section.kt` 与 `ImportedPlaylistRow.kt` 不得出现 `FocusableSurface(`（分区只准用行组件或 `SettingsTile`）；`SettingsComponents.kt` 内 `FocusableSurface(` 出现次数 **≤ 1**（就是 `SettingsTile` 那一处）。
- **G7-C（选中态唯一）**：`*Section.kt` 与 `ImportedPlaylistRow.kt` 中不得出现 `containerColor = NasMusicColors.Primary`（实底选中）与手调 `0.18` alpha —— 一律 `SettingsTile(selected = true)`。
- **G7-D**：`settings_wifi_on` / `settings_wifi_off` 在 `app/src/main/**` 的引用次数归零（WiFi 字符串解除寄生；定义可保留给真实 WiFi 文案使用方，若全树无其他使用则连 key 一起删）。
- **负向自证**（四条各配一个破坏样本，缺一项则门禁可能在空转）：含 `shape = RoundedCornerShape(12.dp)` 的样本文本必须被 G7-A 判负；含 `containerColor = if (selected) NasMusicColors.Primary` 的必须被 G7-C 判负；含 `FocusableSurface(` 的分区样本文本必须被 G7-B 判负；含 `stringResource(R.string.settings_wifi_on)` 的必须被 G7-D 判负。
- **空转断言**：门必须另外数出「受检文件数」与「被检出的参数块数」并断言 > 0 —— 否则"违规为 0"可能只是因为什么都没扫到。口径同 G5、同 `ChipContentWidthScanTest`。
- 该门在**迁移前跑必须判红**，且红名单就是 §2-10 的计数（15 处 `shape = ` 字面量、20 处裸 `FocusableSurface(`、**4 处实底/手调选中**：实底 1 = `PhotoWallSettingsSection:609`（`OptionChip`，`containerColor = if (selected) NasMusicColors.Primary else Surface`）；手调 0.18 ×3 = `GeneralSettingsSection:78`、`GeneralSettingsSection:138`、`NetworkMusicSection:439`）—— 这就是 T11 的红-绿对账依据，不收红名单不许转绿。
- 豁免标记 `Style-exempt: <理由>`（行级），只留给"这一处确实需要不同取值"并写明为什么；文件级豁免**只有**上面名单里的三个，新增必须同提交改这份点名清单。

---

### §4.8 横竖屏三形态适配（用户 2026-10-18 追加：「竖屏下这些组件被挤压变形了」）

**口径先立住（全部沿用 [docs/conventions-adaptive-ui.md](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/docs/conventions-adaptive-ui.md)，本方案⛔ 不另立第二套）**：

- 形态判定唯一入口 = `LocalUiMode`（`TV` / `PhonePortrait` / `PhoneLandscape`，`ui/theme/UiMode.kt`）；**B1 硬规则**：分支谓词只写 `PhonePortrait`，`else` 分支必须**逐字等价**于改动前（手机横屏与 TV 共用一套布局）。
- `PHONE_UI_SCALE = 0.82` 由 [MainActivity.kt:289](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt#L289) 以 **density** 方式生效 ⇒ 360 物理 dp 的竖屏手机，Compose 口径 ≈ **439dp**。所以「竖屏再写一套 dp」是错的，需要抬的只有**触摸目标**（`portraitTouchTarget()`）。

**现状证据（本会话逐个重新打开核对）**：

1. 设置页**顶层已经有竖屏两级页**：[SettingsScreen.kt:302](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt#L302) `isPortraitPhone`、`:304-308` 用 `navVM.selectedSection` 路由、`:426-431` 页 padding 竖屏 16dp、`:435` 竖屏不渲染 240dp 侧栏。⇒ **一级导航不缺，缺的是二级页里 11 个 `*Section.kt` 的横向自适应**。
2. 内容宽差 **3.9×**：竖屏 439 − 2×16 = **407dp**；TV 1920 − 2×32（页 padding，SettingsScreen:426）− 240（侧栏，:438）− 24（内容列 `LazyColumn` 的 `start = 24.dp`，:516）≈ **1592dp**。所有分区都是按 TV 那一档写的，竖屏等于把一行压到 **26%** 宽（407/1592）。
3. ⚠️ **既有竖屏门禁覆盖不到设置内容**：`ScreenUiModeCoverageTest` 的触发条件是**顶层 `fun XxxScreen(`**（conventions §9），而分区文件都叫 `XxxSettingsSection(` ⇒ **11 个分区全部逃逸**。这正是 conventions §9 开头那句「忘了做竖屏编译过、单测过，只有真机才看得见」的实例。
4. **实测到的四类失效**（逐条给行号；① 是**当前就存在的缺陷**，不是改版引入）：
   - **① 0 宽塌陷**：[PlayerSettingsSection.kt:112-123](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/PlayerSettingsSection.kt#L113) 淡入淡出时长档 = `Row(spacedBy(8.dp))` + `durations.forEach { SettingActionButton(...) }`，**没传 `modifier`** ⇒ 走默认 `Modifier.fillMaxWidth()`（[SettingsComponents.kt:176](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsComponents.kt#L176)）。`Row` 给 wrap 子项的 `maxWidth` 是**行内剩余宽度** ⇒ 第一个「2s」吃满整行，后 4 档（4/6/8/12s）**被挤成 0 宽**。与 v2.35.0「音质只有『自动』」、v2.37.0「画面适配只显示满屏」同一根因、同一族症状（选项不见了）。⚠️ 现有 `ChipContentWidthScanTest` **抓不到它** —— 那道门只看 `FocusableSurface(` 调用点内部是否 `fillMaxSize()`，而这个调用点里连 `FocusableSurface` 都没出现（包在 `SettingActionButton` 内）。**门禁的抓手差了一层组件**，G8 补的就是这一层。
   - **② `weight(1f)` 平分芯片 ⇒ 竖屏长高、互相参差**：[PlayerSettingsSection.kt:318-336](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/PlayerSettingsSection.kt#L318) 两枚模型按钮写成 `Box(Modifier.weight(1f)) { SettingActionButton(...) }`（靠外层 weight 兜住内层 `fillMaxWidth`）。TV 上每枚 ~750dp 一行摆平；竖屏每枚 (407−12)/2 = **197dp**，减掉左右 24dp 内边距与尾部「确定」19sp×2 ≈ 38dp ⇒ 标签列只剩 **~111dp**，而说明「手机扫描上传模型文件到电视」13 个全角字 × `Body` 17sp ≈ **221dp** ⇒ 折 2–3 行；另一枚说明 10 字 ≈ 170dp，两枚**高度不等**。这就是用户看到的"挤压变形"——不是崩，是行高失控 + 参差。
   - **③ 尾部文字与标签抢宽**：`SettingActionButton` 尾部固定挂 `common_confirm`「确定」（:204-208），在 197dp 的芯片里抢掉 ~38dp（19%）。T12 的 `Trail` 收口后入口型改 `›`（16dp）、就地执行不给文字 ⇒ **这一项自动消失**，无需单独排期。
   - **④ 允许保留的现状**：语言三芯片（[GeneralSettingsSection.kt:62-92](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/GeneralSettingsSection.kt#L62)）用 `weight(1f)` 等宽：竖屏每枚 (407−24)/3 ≈ **128dp**，「跟随系统」4 字 × `Body` 17sp ≈ 68dp + 左右 16dp 内边距 = 100dp ≤ 128 ⇒ **不折行，现状可用**。它是 A2 里"短标签等宽芯片可以保留 `weight`"的判据样本（阈值：每个标签 ≤ 4 个全角字）。

**规则 A1–A5**：

| 编号 | 规则 | 反例（现状） |
|---|---|---|
| A1 | 分支谓词只写 `PhonePortrait`；设置区**不新增** `PhoneLandscape` 分支（手机横屏继续与 TV 共用），确需时按 conventions §2 三条件记录 | — |
| A2 | 一行内 ≥2 个**可操作瓦片**：要么走 `SettingsChipRow`（内部按 `adaptiveColumns` 分行，竖屏自动降为每行 2 枚），要么只允许**短标签等宽芯片**（≤4 全角字）保留 `weight(1f)`。⛔ 不许把 `Row` 当横向流式容器硬塞 | `PlayerSettingsSection:112-123`（0 宽）、`:318-336`（高块参差） |
| A3 | 芯片宽度由 `SettingsTile(chip = true)` 决定 = **wrap 内容 + `chipHPadding`**，下限 `chipMinWidth`；⛔ 调用方不再传 `.width(64.dp)`。需要**等宽两枚**时只准写 `SettingsChipRow(equalWidth = true)` —— `Box(Modifier.weight(1f))` 这个兜法**只允许存在于该原语内部一处**（`SettingsComponents.kt` 在 G8 作用域之外），调用方一律禁止手抄。同排不等宽是允许的（等宽是 `weight` 的副作用，不是设计目标） | `SettingsComponents.kt:164-172` KDoc 里「放 Row 里必须传固定宽度」这条**纯人工约定** —— 三个版本踩同一个坑，靠人记就会再踩 |
| A4 | 整行瓦片：标签列 `weight(1f, fill = false)` + **尾部固定宽**（开关轨 128dp、`›` 16dp、信息行的值列 `infoValueWidth` 180dp），⛔ 尾部参与挤压；说明不设 `maxLines`（允许折行，⛔ 不许用省略号截掉说明） | `SettingsInfoRow` 用 `SpaceBetween` 不限宽（:378-396），长值把标签挤没 |
| A5 | 尺寸仍走 `SettingsTokens`（density 已 ×0.82，⛔ 不为竖屏写第二套 dp）；**只有热区**走 `portraitTouchTarget()`。开关轨、进度条、卡片竖条都**不是**热区 ⇒ 不套（套了反而是错的） | conventions §6.3 豁免清单同一口径 |

**新原语 `SettingsChipRow`**（落 `SettingsComponents.kt`，T13）—— 把「按 `adaptiveColumns` 手动分行」这个已被真机验证过的做法（[PhotoWallSettingsSection.kt:572-581](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/PhotoWallSettingsSection.kt#L572) 的注释就是理由：**超宽时 TV 焦点会跑到屏幕外**）收成一个调用点：

```kotlin
// 追加在 SettingsComponents.kt
/**
 * 芯片组按形态分行：竖屏每行 [phonePortrait] 枚，TV / 手机横屏每行 [tv] 枚。
 *
 * 存在理由：`Row` 不会换行，芯片多了只能被挤扁或压成 0 宽（§4.8 ①②）。
 * 手动分行而不是 FlowRow / horizontalScroll —— 后两种在 D-Pad 下焦点会跑出屏幕或滚不出去。
 *
 * [equalWidth] 是给「带长说明的整行瓦片」（模型下载那类）用的：需要等宽两枚时，
 * `Box(Modifier.weight(1f))` 这个兜法只在**这里**出现一次（A3）；
 * 芯片（短标签档位）不要开，让它们按内容 wrap。
 */
@Composable
internal fun <T> SettingsChipRow(
    items: List<T>,
    tv: Int = items.size,
    phonePortrait: Int = minOf(items.size, 2),
    medium: Int = tv,
    equalWidth: Boolean = false,
    modifier: Modifier = Modifier,
    itemContent: @Composable androidx.compose.foundation.layout.RowScope.(T) -> Unit,
) {
    val columns = adaptiveColumns(tv = tv, phonePortrait = phonePortrait, medium = medium)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SettingsTokens.rowGap),
    ) {
        items.chunked(columns.coerceAtLeast(1)).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(SettingsTokens.chipGap)) {
                rowItems.forEach { item ->
                    if (equalWidth) Box(modifier = Modifier.weight(1f)) { itemContent(item) }
                    else itemContent(item)
                }
            }
        }
    }
}
```

调用点改写（三处，全部在 T13；代码见 Task 13 Step 4）：`PlayerSettingsSection:112-123` 时长档 → `SettingsChipRow(durations, tv = 5, phonePortrait = 3)`，内部换成带选中态的 `SettingsChip`；`:318-336` 与 `:352-368` 模型双按钮 → `SettingsChipRow(items, tv = 2, phonePortrait = 1, equalWidth = true)`（TV / 手机横屏仍是等宽两枚 = **逐字等价**，满足 B1；竖屏上下排、各自整行）。

**门禁 G8（`app/src/test/.../ui/SettingsRowWidthScanTest.kt`，T13）**：

- **判据**：范围 = `ui/screens/SettingsScreen.kt` + `ui/screens/settings/*Section.kt` + `ImportedPlaylistRow.kt`（⛔ 不含 `SettingsComponents.kt` / `SettingsTokens.kt` —— 组件定义里的默认 `fillMaxWidth()` 与 `SettingsChipRow` 内部唯一那处 `Box(weight(1f))` 是实现细节，放进作用域会把正解本身判成违规）。注释先剥离，然后：凡 `Row`（**`Row(…) { }` 与 `Row { }` 两种写法都要认** —— 只按 `Row(` 找会漏掉手写单行用例）的大括号配对区内出现「默认整行宽的设置组件调用」（`SettingActionButton(` / `SettingSwitch(` / 实参区不含 `chip = true` 的 `SettingsTile(`），且该调用的**实参区**（括号配对，不含尾随 lambda）不含 `.width(` / `widthIn(` / `weight(` / `.size(` ⇒ 判违规。⚠️ `fillMaxWidth(` **不算"已给宽度"**（它就是病灶）。豁免标记 `RowWidth-exempt: <理由>`（调用点所在行或向前 3 行内）。
- **建门时的红名单（2026-10-18 本机按上述判据实测）**：作用域 **14** 个文件（`*Section.kt` 12 个 + `SettingsScreen.kt` + `ImportedPlaylistRow.kt`）、`Row` 内容块 **25** 个、被检查的组件调用点 **5** 个、违规 **5** 处 —— 全在 `PlayerSettingsSection.kt`：`:117`（时长档）、`:323`/`:330`（已下载模型双按钮）、`:357`/`:364`（未下载模型双按钮）。后四处是 `Box(Modifier.weight(1f)) { SettingActionButton(…) }`：`weight` 给的是**外层 Box**、不在组件实参区里，所以照样命中 —— 这正是 A3 要禁的"调用方手抄兜法"。
- ⚠️ **判据必须落在"组件调用"上，不能落在"Row 里有 ≥2 个子项"上** —— 时长档那行字面上只有 1 个调用点（`forEach` 生成 5 个子项），按子项数判会**空转**（报"0 处"却什么都没查，正是 conventions §6.5 记过的同类坑）。
- **自证四件套**：① 负向用例把 `PlayerSettingsSection:112-123` 的**原文**当 fixture 喂进去，必须判违规（这条同时钉住"修好后不许回潮"）；② 压成一行的 `Row { SettingActionButton(label, description, onClick) }` 必须判违规；③ 注释里举例的写法不得误判（剥离注释生效）；④ 空转断言：真实扫描里 `Row(` 块数 > 0 **且**被检查的组件调用点数 > 0。
- 与既有门的关系：`ChipContentWidthScanTest` 继续守 `FocusableSurface` 层（T11 Step 7 已要求把它的 `CALL` 正则扩到 `SettingsTile|SettingsChip`），G8 守**上一层（组件调用方）**。两层都要在，缺任一层都留有一条无人看守的 0 宽路径。

**`SettingsSectionList.kt`（竖屏一级分区列表）单独走查**：它是竖屏专属形态，被 G7 豁免（圆角是列表形状），但 V16 必须逐项目视 —— 分区名 + 计数的双栏在 407dp 下是否折行，只有真机知道。

---

## §5 整体优化（交付项 4）

1. **§5.1 卡片头取代 `SubSectionTitle`**：明度阶梯 + 竖条 + 粗体三重编码，替换掉「只差一个 FontWeight.Bold」的旧写法；同时消灭 NETWORK tab 的裸 `Text` 特例。
2. **§5.2 开关改为「分段式开关轨」（用户 2026-10-18 三选一，选定中间那款）**：`SettingSwitch` 现在尾部复用 `R.string.settings_wifi_on/off`（SettingsComponents:119）—— 一个 WiFi 文案寄生 + 纯文字状态。目标形态（**全部数字由 [scripts/measure_switch_reference.py](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/scripts/measure_switch_reference.py) 量出，⛔ 不许照眼看**）：

   | 量到的参考图（px，800×600 原图） | 比值 | 落地（dp） |
   |---|---|---|
   | 轨道外框 `312 × 67` | 长高比 4.657 | `128 × 36`（⚠️ 唯一主动偏离，见下） |
   | 描边宽 2px、色 `(247,255,255)` | 高的 3.0% | `1.dp`，`TextPrimary.copy(alpha = 0.8f)` |
   | 轨道**无填充**（内部取样 `(27,41,63)` / `(32,48,73)` = 页面渐变背景本身） | — | ⛔ 不给轨道 `background`，只描边 |
   | 滑块实心 `155 × 45`，色 `(255,255,255)` | 宽 = 轨道 **49.7%**、高 = **67.2%** | `58 × 24`（内宽的 50% / 高的 72%），`TextPrimary` |
   | 滑块上下内缩各 11px、左内缩 14px | 16.4% / 4.5% | `6.dp`（令牌 `switchThumbInset`） |
   | 两端完全圆（顶边直线段从左端点起 28px，理论 stadium = h/2 = 33.5，差值是抗锯齿） | — | `RoundedCornerShape(percent = 50)` |
   | 活动标签「ON」`33 × 18`，色 `(30,40,51)` ≈ **页面背景色而非纯黑** | 帽高 = 轨道高 **26.9%** | `FontSize.caption()`，色 `NasMusicColors.Background` |
   | 非活动标签「OFF」色 `(69,93,121)` = 背景 + 约 40 明度 | — | `NasMusicColors.TextSecondary` |

   **为什么宽度从 4.657 收到 3.56**：参考图的长条是「滑块占一半 + 两段标签之间 173px 空档」（ON 右端 x=308 → OFF 左端 x=481）撑出来的，**空档不是结构比例**；结构比例（滑块宽 50% / 高 67% / 描边 3% / 帽高 27%）全部照抄。若连空档一起照抄，36dp 高的轨道要 168dp 宽，竖屏 407dp 的内容宽会被一个开关吃掉 41%。
   **为什么帽高从 26.9% 抬到 35%**：TV 最小字档 `CaptionTv = 18sp`（Theme.kt:113）是地板，18sp 的帽高 ≈ 12.6dp，`12.6 / 36 = 0.35`；要维持 0.269 得把轨道做到 47dp 高、168dp 宽。**字号地板优先于参考图比例**，这条偏离写进 V15 的真机判读。

   行为定义（参考图只画了 ON 一态，OFF 态按轴对称推得，实现前无需再问）：滑块在**左** = 开、滑块内是「开」深色字、轨道右侧是暗灰「关」；滑块在**右** = 关、两标签角色互换。几何自洽核对：轨道 `128 × 36` − 描边 `1` − 内缩 `6` ⇒ 内宽 116、内高 24；滑块 `58 × 24` = 内宽的 **50.0%**（照抄参考图 49.7%），位移行程 = 116 − 58 = **58dp**，走 `SettingsTokens.animMs`。⛔ **两态滑块宽度恒定**（58dp），不随文案长度变化 —— 否则中文「开」与英文「OFF」会让滑块在切换时跳宽。
   ⛔ 开关轨**不是触摸目标**：整行瓦片才是热区（conventions-adaptive-ui.md §6.5「容器即热区」的同一口径），所以 36dp 高**不需要** `portraitTouchTarget()` 抬到 56dp —— 给它套 `portraitTouchTarget` 反而是错的。
   文字并未消失，而是**从尾部提示升格为开关本体的一部分**：`settings_on` / `settings_off` 两个语义 key（zh 开/关、en ON/OFF）同时供滑块文本与整行 `contentDescription` 使用，解除对 WiFi 字符串的寄生（G7-D）。
   **风险与回退**：参考图是纯黑白，两态只差在滑块位置与哪个词是深色。3 米判读若失败（V15），回退方案是**单令牌改动** —— 开态滑块填 `Primary`、关态保持 `TextPrimary`，⛔ 不许为此加第三种组件或改判据。
   **本项与 §4.7 是同一次改动**（S5 的尾部形态 + G7-D 的寄生解除都落在这里），故并入 **T12** 执行，不单独排期；取值令牌化在 **T11**。
3. **§5.3 宿主瘦身（不做大重写）**：
   - 6 个端点 `TextInputDialog` 宿主收敛为 1 个：`data class EndpointEditor(val titleRes: Int, val descRes: Int, val value: String, val onConfirm: (String) -> Unit)`，`SettingsScreen` 只持有一个 `var editing: EndpointEditor? = null`。校验仍走 `normalizeEndpointUrl`（AppPreferences:958-967）。
   - `onChangeLyricsOffset`（SettingsScreen:132）随 §6-1 的链路一并删除。
   - `PlayerSettingsState.visualizerTheme` / `Actions.onChangeVisualizerTheme` 删除（幽灵管道），改由 `SettingsInfoRow` 只读呈现。
4. **§5.4 网络测试下沉**：`SettingsScreen.kt:713-744` 的内联 OkHttp HEAD 移到 `MainViewModel.runNetworkProbe()`（或 `NetworkViewModel`），UI 只发意图并渲染 `isNetworkTesting` / `networkTestStatus`。目的不是可测连通性（⛔ 测试不探端点），而是让 UI 层不持有 I/O。
5. **§5.5 TV 可达性**：关于页 GitHub 链接 `Text.clickable`（AboutSection:95-105）改为可聚焦瓦片，与其它可点项同套焦点视觉 —— T8 先用现有 `FocusableSurface` 参数块落地，**T11 起换成 `SettingsTile`**（统一后设置区不该再有任何手写参数块）。
6. **§5.6 「不可用」与「未实现」分离**：`PlaceholderRow`（SettingsComponents:68-83）随占位网盘一起删除；真正「当前画质档不支持人脸检测」这类置灰继续用 `enabled = false`（口径见 `SettingActionButton` KDoc :176-179：置灰必须在 `onClick` 里再判一次，`FocusableSurface` 仍可聚焦）。
7. **§5.7 门禁（本项目 UI 断言的唯一可行手段）**：新增 `SettingsGroupCoverageTest`（仿 [ChipContentWidthScanTest](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/test/java/com/nasmusic/tv/ui/ChipContentWidthScanTest.kt) 与 [LocalMusicGateTest](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/test/java/com/nasmusic/tv/ci/LocalMusicGateTest.kt) 的源码文本扫描范式）。
   - **G1 覆盖**：`ui/screens/settings/*.kt` 中每个 `SettingSwitch(`/`SettingActionButton(`/`SettingsInfoRow(` 调用点，必须落在某个 `SettingsGroupCard(` 的大括号配对区内；豁免标记 `Group-exempt: <理由>`；注释先剥离。
   - **G2 零残留**：全树 `SubSectionTitle(` 0 命中（含注释，口径同 `LocalMusicGateTest` L4/U14）。
   - **G3 分区接线**：`SettingsSection.entries` 每个值都能在 `SettingsScreen.kt` 的 `when(displaySection)` 找到分支、在 `navFocusRequesters` 建立时被注册、且 `R.string.settings_*` 在 `values/` 与 `values-en/` 同时存在（仿 `VisualizerThemeStringsGateTest`）。
   - **G4 负向自证**（缺一项则门禁可能在空转，必须写）：把 `SettingSwitch` 塞到卡片外 → G1 判负；把 `SubSectionTitle` 塞回源码 → G2 判负；删掉 `values-en` 一个 tab 标题 → G3 判负。
   - **G5 空转断言**：真实扫描必须命中 >0 个调用点。
   - **G6 说明覆盖**：定义与实现见 **§4.5 / T14**（本门的特殊之处：判据是"实参区含非空 `description =`"，需要括号配对而不是单行 `contains`）。
   - **G7 风格统一**：定义与实现见 **§4.7-⑥ / T11（A/B/C）+ T12（D）**（判据是"参数位字面量必须走令牌" + `FocusableSurface(` 委托点唯一 + 开关文案解寄生，同样需要范围点名）。
   - **G8 行宽（横竖屏）**：定义与实现见 **§4.8 / T13** —— 独立文件 `SettingsRowWidthScanTest.kt`（它的作用域是"分区调用方 + `SettingsScreen`"，与 G1–G7 的 `ui/screens/settings/*.kt` 不一致，塞进同一个类会让 `scopedFiles()` 两套口径互相踩）。守的是**组件调用方**那一层：`Row(` 的内容块里出现无宽度的整行瓦片即违规；与 `ChipContentWidthScanTest`（守 `FocusableSurface` 内容层）**两层缺一不可**，缺任一层都留有一条无人看守的 0 宽路径。
   - **G9 缓存登记表**：定义与实现见 **§1.14 / T8b Step 2** —— 独立文件 `CacheInventoryGateTest.kt`（它扫的是**整个 `app/src/main`**，不是 `ui/screens/settings/`；与 G1–G7 同塞一类会让 `scopedFiles()` 的口径二次失配，与 G8 同理）。三条判据：① 源码里出现过的**落盘根 + 字面量子目录**（`cacheDir`/`filesDir`/`dataDir` 后紧跟逗号或 `.resolve(`，外加 Room 的三个 `.db`）必须作为 `dir = "…"` 登记进 `CacheInventory.entries`；② 登记为 `deletable = true` 的条目必须给 `clearVia`，且 main 源集里真有同名 `fun` 声明（专治「`clear()` 写好了没人调」，本轮实测 3 处）；③ 登记的非空 `dir` 必须在全树出现过（防「登记了一条已不存在的路径」）。另有一条空转下限：扫描命中的目录数 `>= 15`（实测 16，**这个数字会随代码变，不许抄进注释当断言**）。豁免 `cache-inventory-exempt: <理由>`，实测只有 1 类需要（`NasMusicApp:399/:410` 的 `"music"` 回落路径，与外部根条目是同一份数据，双登记会虚高体积）。
     已知边界（⛔ 别当门没用）：**常量拼接**的目录名扫不到（`accompaniment` 来自 `CACHE_DIR_NAME`）、**变量根**扫不到（`File(baseDir, "models")`）⇒ 所以**不许**反向断言"每条登记都必须被扫到"，登记表完整度仍靠人工（§1.14 表 15–18）。
     与 G1/G6 的分工：G1/G6 守**呈现层**（行有没有进卡片、有没有说明），G9 守**数据层**（有没有落下盘缓存没被登记）—— 三者互不重叠，缺 G9 则下一次新增缓存目录依旧只有靠人肉 grep 才发现（§1.14 结论 3）。
8. **§5.8 备份兼容**：`BackupData`（AppPreferences:1905-1933）删字段后，旧备份 JSON 的未知字段由 Gson 忽略 —— 必须由 `BackupGsonTest` 实证「含 `weatherEnabled` 等被删字段的旧 JSON 仍能导入且 `nameOrDefault()/ordinalOrDefault()` 守卫不崩」。凭据继续排除。
9. **§5.9 不做**：设置项搜索/过滤、按 tab 的「恢复默认」、tab 顺序自定义（本项目无持久化分区顺序、也无 entries 顺序断言 —— 见 `SettingsSection.kt:37-49` 的既有结论，插入/重排安全）。加这些属范围膨胀，YAGNI。

---

## §6 可删除项评估（交付项 5）

| # | 项 | 判据 | **裁决** |
|---|---|---|---|
| 1 | `settings_lyrics_offset` 全链（G1） | 无控件；`AppSettings.lyricsOffsetMs` 被读入后 lyrics 层零使用；`LrcParser` 偏移只来自 LRC `[offset:]` 头（LrcParser.kt:23-25/87） | ✅ **已裁决（用户 2026-10-18）：删除整链** —— AppPreferences :278/:874/:798/:1999、`LyricsPrefs.kt:12`、`MainViewModel:3062`、`AppSettings:25`、`SettingsScreen:132`、`SettingsBranch:129`。留着的代价是"看起来能调歌词延迟"，实现它的收益远低于修 6 处接线的成本 |
| 2 | `settings_spectrum_enabled`（D1） | 只有 setter（:1120-1122）+ 门面（VisualizerPrefs:14），全树零读取 | **删除** |
| 3 | `weather_enabled` / `weather_manual_city` / `weather_auto_refresh`（D2–D4） | 仅 AppPreferences Flow（:1023/:1034/:1045）+ `BackupData`（:1925-1927/:1969-1971/:2075-2077）；天气电台只在 `WeatherRadioScreen` 切心情，城市来自定位 | **删除**（含 `BackupData` 字段，配 §5.8 兼容测试） |
| 4 | 其他网盘占位行（`CloudDriveType.PLACEHOLDER` + `PlaceholderRow` + `settings_netdisk_group_others(_desc)`） | 不可聚焦、无行为、纯噪音；夸克网盘已 2026-10-18 由所有者裁决放弃 | ✅ **已裁决（用户 2026-10-18 确认删除）**：删占位循环（`BaiduPanSettingsSection:192-195`）、`PlaceholderRow` 组件（§5.6）与两个字符串 key（`values/`+`values-en/` 同步）。**连带删两个常量**：`PLACEHOLDER`（:30）的唯一消费方就是这段循环，`IMPLEMENTED`（:28）的唯一消费方是 `PLACEHOLDER` —— 删掉循环后两者同时成为死码。⛔ **枚举四项与 `key`/`fromKey`（:32）保留**：`aliyun`/`123`/`quark` 三个 key 仍可能存在于旧 `CloudDriveConfig` JSON 中，`fromKey` 必须能解析；`cloud_drive_*` 四个显示名 key 也一并保留（备份/日志里还要认得它们） |
| 5 | `customAppKey` / `customSecretKey` / `apiDriftNotified`（D5–D7） | `AppPreferences:1879-1887` 三对方法零调用方 | **删除**（`CloudDriveConfig` 字段一并删；Gson 对旧 JSON 未知字段宽容） |
| 6 | `visualizerTheme` / `onChangeVisualizerTheme` 幽灵管道 | 分区从不读取（§1.2-✕15），主题真源在 `VisualizerStage:537` | **删管道**，key `settings_visualizer_theme` **保留**（有真实消费方），在 VISUAL_EFFECTS 加只读行呈现 |
| 7 | 「深色主题」开关 | 恒定深色，关掉只是变亮且多数页面写死深色调 | ✅ **已裁决（用户 2026-10-18：可以保留）** —— 有真实生效链路，删除会伤及已习惯的用户。⛔ 但**不给它写"关掉会变亮"这类说明**（§4.5 规范第 2 条：说明写效果，而它的效果在多数页面不可见，写了就是误导）；卡片 `description` 只说「深色为默认外观，个别页面仍使用固定配色」 |
| 8 | `show_library_shortcut_hint` | 有读（LibraryScreen:289）有写（:296），一次性提示自动消费 | **保留，不进设置页**（一次性提示不该有持久开关） |
| 9 | `music_source`（当前源） | `MainViewModel:561` 写，是运行态而非用户偏好 | **保留，不进设置页** |
| 10 | **`settings_default_network_source`** | **活链路**：NasMusicApp:343 provider + R-7 镜像 :213-214/:1199，但**无 UI** | **新增暴露** → SOURCES 卡 ⑤ |
| 11 | **`settings_model_download_url`** | **活链路**：NasMusicApp:324 → ModelDownloadManager:205/:329（自建镜像/NAS），SHA-256 校验对其同样生效，但**无 UI** | **新增暴露** → LYRICS_KARAOKE 模型卡（编辑对话框复用 §5.3 的通用端点编辑器；说明文案必须点明"换权重须同步改 EXPECTED_SHA256"） |
| 12 | 歌词字号 / 高亮模式（只在播放页） | `lyrics_font_scale`(:877) 等真实生效 | **设置页补呈现**（播放页入口保留），归 LYRICS_KARAOKE 卡 ① |
| 13 | K 歌变调 / 倍速 | `k_pitch_semitones`(:445)/`k_playback_speed`(:446) 由 `NowPlayingBranch:144-149` 提交；真实消费方 `PlayerManager` / `KaraokePlaybackScreen` / `VocalSeparationViewModel` | ✅ **已裁决（用户 2026-10-18 推翻原案）：保留在播放页，不进设置页** —— 原裁决「设置页只读呈现 + 重置」作废。理由：变调/倍速是**用户在播放/跟唱过程中即时控制的运行态**，不是偏好；设置页里放一行"当前 3 半音"既不能调、又要用户跳一层才能重置，比播放页原地的滑杆更差。⛔ 设置页不呈现、不加「重置」行，§3.2 的 LYRICS_KARAOKE 无「K 歌」卡 |
| 13b | （连带问题）`k_pitch_semitones` / `k_playback_speed` **要不要继续写 DataStore** | 两者今天由 `NowPlayingBranch` 提交并持久化，重启后仍带回上次的半音/倍速 | ⚠️ **超出本方案范围，留待裁决**：本方案只把它们移出**设置页呈现**，**不动 DataStore 读写链路**（动它属于播放页/K 歌语义，且会让正在跟唱的用户在重启后被静默重置）。若裁决为"运行态就该运行态"，另开任务删 key + `BackupData` 字段 |
| 14 | 缓存目录占用统计 | `LaunchedEffect(refreshKey)` + IO 遍历（CacheSettingsSection） | **保留**，但确保离开分区时取消（与 `PhotoWall` 扫描同类协程取消口径） |
| 15 | DATA 里的「扫码传输」与 PLAYBACK 模型「扫码传输」 | 两个不同载荷（备份 JSON vs 166MB 模型） | **都保留**，但分别归到各自卡片并注明传的是什么，避免用户以为是同一功能 |
| 16 | **内存态缓存**（网络播放直链 `playUrlCache`/`deadProbeCache` 上限 500，[NetworkMusicManager.kt:42](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt#L42)/:69/:75；飞牛封面 ID 三表 512，[FeiniuAdapter.kt:211-217](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt#L211)（私有 `LruCache` :1286）；封面取色 64，[CoverPaletteProvider.kt:21](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/visualizer/CoverPaletteProvider.kt#L21)；Coil `memoryCache` 按内存 25%，[NasMusicApp.kt:586-588](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/NasMusicApp.kt#L586)）—— §1.14 追加要求的对照面：它们**不是落盘缓存** | 重启即空、不占磁盘、且**三处已有各自的失效路径**（`clearPlayUrlCache()` :104-111 由档位变化调用、`CoverPaletteProvider.clear()` :58、Coil 随 `clearCoverCache()` :3323 一起清） | ⛔ **不纳入设置页**：没有磁盘占用可显示、清了下次播放又回来，放按钮只会让用户以为"省了空间"。内存压力由系统与应用侧 `onTrimMemory` 处理，属另一条链路 |
| 17 | 下载中断残留 `.part`（§1.14 表 12） | 启动 `recoverAfterCrash()` 已整目录清扫（[SongDownloadManager.kt:644-651](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/download/SongDownloadManager.kt#L644)），`clearAll()`→`cancelAll()` 另删 DB 记录的 `tmpPath`（:543-544、:529-536） | ⛔ **不加手动按钮**（自动链路已覆盖；再加一个"清除残留"反而暗示平时没清）。**人声分离的临时残留不同**：`nasmusic_hq/*_input.tmp` 与 `demucs_pcm_*.tmp` **没有**启动 sweep，所以由 T8b 并入「清除分离缓存」（§1.14 表 5/6） |

净结果：**删除 5 个 DataStore key**（`settings_lyrics_offset` §6-1、`settings_spectrum_enabled` §6-2、`weather_enabled` / `weather_manual_city` / `weather_auto_refresh` §6-3）+ **3 个 `CloudDriveConfig` 字段**（`customAppKey` / `customSecretKey` / `apiDriftNotified` §6-5，见 `CloudDriveConfig.kt:31/:32/:36` —— 是 config 字段而非 DataStore key，v1.1 审校订正：原稿把三者算进 key 数写成「8 个 DataStore key」）+ 1 个 `BackupData` 三元组 + 1 个占位组件 + 2 条幽灵管道参数；**新增暴露 4 项**（默认网络源、模型自定义源、歌词字号、歌词高亮，原拟新增的第 5 项「变调/倍速」已按 §6-13 撤销）；**新增缓存管理动作见 T8b**（补 2 个清理按钮 + 1 处改名扩容 + 占用一览；表 16/17 两行是"评估过但**不**纳入"，写在这里是为了下次不再重扫）；设置项总数从「14 个真实控件挤在 PLAYBACK」变为「每 tab 3–6 卡、每卡 2–5 项」。

---

## §7 实施任务

> 每个任务都让 app 可编译、可装真机、可提交。迁移类任务（T2、T4–T8）共用 §4.3 的唯一动作和 §4.2 的 `SettingsGroupCard`，各自列出**具体卡片标题与成员行**（不是"同上个任务"）。
> **T11/T12 是「组件风格统一」的两段**（用户 2026-10-18 追加要求，规格见 §4.7）：T11 只收**取值**（令牌 + 委托点，diff 里不许出现文案/语义变化），T12 只收**语义**（尾部形态、开关轨、危险色）。两段都排在卡片化（T2–T8）与新增行（T10）之后 —— 反过来做就是同一行组件改两遍，且 T10 的新行会按旧风格写、再被 T11 重写。
> 横竖屏适配（**T13**，用户 2026-10-18 追加：「竖屏下这些组件被挤压变形了」，规格见 §4.8）夹在 T12 与 T14 之间是有原因的：它改的是**排布容器**，必须等取值（T11）与尾部语义（T12）都定型后再做，否则 T13 的 diff 里会同时出现令牌、文案、布局三类变化，真机走查出了问题是三选一排查；又必须赶在说明补齐（T14）之前，因为 §4.8 ② 那种"高块参差"正是**长说明撞上等宽芯片**量出来的 —— 先定形态、再按形态给文案长度设上限（≤26 字），顺序反过来就会写完 26 字才发现竖屏放不下。
> T13 还顺带修一条**线上既有缺陷**（时长档只有「2s」可见），所以它单独占一次提交、单独取证，⛔ 不要和 T11/T12 的改版混在一个 commit 里。
> 缓存补全（**T8b**，用户 2026-10-18 追加要求：「缓存管理部分需要读取代码，是否还有可管理的缓存数据未纳入设置页面管理，也加入进来」，规格见 §1.14 表 1–18 + §4.5 的 CACHE 小节）排在 T11 之前是有原因的：它一次引入 **剩余空间 + 19 条占用行 + 2 个新按钮 + 1 处改名扩容 + 36 个 string key**，这批新行必须**先存在**，才能在 T13 定横竖屏形态（尾部值列宽走 §4.8 A4 `infoValueWidth`）、被 T14 的 G6 说明门扫到；反过来先改版再补行，就是同一批组件写两遍（T10 已踩过一次）。G9 缓存登记表门也在 T8b 当场立 —— 它是**数据层**门（扫全树落盘目录字面量），与 G1/G6 的呈现层门互不重叠，分工见 §5.7。
> 说明补齐（T14）在 T11–T13 之后：G6 门扫的是最终形态的调用点，先补文案再改组件会让 G6 的红名单里混进"组件重构"的噪音。
> 构建口令（全任务通用）：
> ```bash
> JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
>   ./gradlew.bat testDebugUnitTest assembleDebug lintDebug --no-daemon \
>   -Pkotlin.compiler.execution.strategy=in-process
> ```

---

### Task 1: `SettingsTokens` + `SettingsGroupCard` 原语

**Files:**
- Create: `app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsTokens.kt`（§4.7-① 全文，一次建齐：卡片级 + 行/芯片级 + 动效，即使此刻只有卡片级被消费）
- Modify: `app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsComponents.kt`（在 `SectionTitle` :45-52 之后插入 `SettingsGroupCard`）
- Create: `app/src/test/java/com/nasmusic/tv/ui/SettingsGroupCardContractTest.kt`

**Interfaces:**
- Produces: `@Composable internal fun SettingsGroupCard(title: String, modifier: Modifier = Modifier, description: String? = null, content: @Composable ColumnScope.() -> Unit)`、`internal object SettingsTokens`（`cardShape` / `rowShape` / `chipShape` / `switchTrackShape` / `switchThumbShape` / `rowHPadding` / `rowVPadding` / `chipHPadding` / `chipVPadding` / `rowGap` / `chipGap` / `cardGap` / `cardInnerHPadding` / `switchTrackWidth` / `switchTrackHeight` / `switchStroke` / `switchThumbInset` / `switchThumbWidth` / `switchThumbHeight` / `switchThumbTravel` / `chipMinWidth` / `infoValueWidth` / `focusScaleRow` / `focusScaleChip` / `focusScaleDisabled` / `pressScaleRow` / `pressScaleChip` / `animMs`）—— T11 的 `SettingsTile`、T12 的 `SwitchTrack`、T13 的 `SettingsChipRow` 都从这里取值守卫

- [ ] **Step 1: 写失败的门禁测试**（结构锚点断言，与 `LocalMusicGateTest` L1 同范式）

```kotlin
package com.nasmusic.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `SettingsGroupCard` 的**视觉编码契约**（方案 §4.1/§4.2）。
 *
 * 项目无 `compose-ui-test`，无法断言真实布局，因此锁「三层明度阶梯 + 描边 + 不可聚焦」
 * 这三条设计判据的源码锚点：一旦有人把卡片改成 `FocusableSurface`（TV 会多出一跳、
 * 破坏 navFocusRequesters 节奏）或把外壳调成比行瓦片更亮（分组边界反变模糊），
 * 本测试立刻红。
 */
class SettingsGroupCardContractTest {

    @Test
    fun `卡片外壳必须略亮于页面背景、略暗于行瓦片并带描边`() {
        val source = settingsSource("SettingsComponents.kt")
        assertTrue("缺 SettingsGroupCard —— 分组原语被删了？", source.contains("internal fun SettingsGroupCard"))
        assertTrue(
            "卡片外壳必须用 NasMusicColors.Surface.copy(alpha = 0.45f)（三层明度阶梯的中层）",
            source.contains("NasMusicColors.Surface.copy(alpha = 0.45f)"),
        )
        assertTrue("卡片必须 1dp NasMusicColors.Border 描边", source.contains("border(1.dp, NasMusicColors.Border"))
        assertTrue("卡片圆角必须走令牌（⛔ 不许在组件里写字面量）", source.contains("SettingsTokens.cardShape"))
    }

    @Test
    fun `卡片圆角取值必须是 18dp 且只在 SettingsTokens 里出现一次`() {
        val tokens = settingsSource("SettingsTokens.kt")
        assertTrue("SettingsTokens.cardShape 必须是 RoundedCornerShape(18.dp)（与行瓦片 12.dp 形成层级差）",
            Regex("""cardShape\s*=\s*RoundedCornerShape\(18\.dp\)""").containsMatchIn(tokens))
        assertTrue("两级圆角不可互换：cardShape(18) 与 rowShape(12) 必须是不同取值",
            Regex("""rowShape\s*=\s*RoundedCornerShape\(12\.dp\)""").containsMatchIn(tokens) &&
                !Regex("""rowShape\s*=\s*RoundedCornerShape\(18""").containsMatchIn(tokens))
    }

    @Test
    fun `卡片本身绝不可聚焦`() {
        val source = settingsSource("SettingsComponents.kt")
        val body = source.substringAfter("internal fun SettingsGroupCard").substringBefore("\n}\n")
        assertTrue(
            "SettingsGroupCard 里出现了 FocusableSurface —— 卡片参与聚焦会让 TV 每组多一跳",
            body.isNotEmpty() && !body.contains("FocusableSurface"),
        )
    }

    /** 负向自证：把 FocusableSurface 塞进卡片体，上一条必须判负。 */
    @Test
    fun `卡片体内塞入 FocusableSurface 后不可聚焦断言必须判负`() {
        val sabotaged = "internal fun SettingsGroupCard(...) {\n FocusableSurface(onClick = {})\n}\n"
        val body = sabotaged.substringAfter("internal fun SettingsGroupCard").substringBefore("\n}\n")
        assertTrue("护栏失效：塞入 FocusableSurface 后仍判通过", body.contains("FocusableSurface"))
    }

    private fun settingsSource(fileName: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui/screens/settings/$fileName"),
            File("app/src/main/java/com/nasmusic/tv/ui/screens/settings/$fileName"),
            File("../app/src/main/java/com/nasmusic/tv/ui/screens/settings/$fileName"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $fileName（user.dir=${File("").absolutePath}）", found != null)
        return found!!.readText()
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew.bat testDebugUnitTest --tests "*SettingsGroupCardContractTest" --no-daemon -Pkotlin.compiler.execution.strategy=in-process`
Expected: FAIL —「找不到 SettingsTokens.kt」/「缺 SettingsGroupCard」（两例都红才说明门真的在检这两件事）

- [ ] **Step 3: 建 `SettingsTokens.kt` + 实现组件**

`SettingsTokens.kt` 照 §4.7-① 全文落（含 `focusScaleDisabled` 与开关轨五项），文件内**只允许**令牌定义，⛔ 不放任何 composable。`SettingsGroupCard` 照 §4.2 的代码插入 `SettingsComponents.kt`（它的 padding/`spacedBy` 已经在读令牌），并补 import：`androidx.compose.foundation.layout.ColumnScope`、`androidx.compose.ui.draw.clip`。文件已 import `background` / `border`? —— 现有 import 只到 `androidx.compose.foundation.background`（:3），**`border` 需新增** `androidx.compose.foundation.border`。

- [ ] **Step 4: 跑测试确认通过**

同上命令，Expected: PASS（3 例）

- [ ] **Step 5: 编译 + lint**

Run: `./gradlew.bat assembleDebug lintDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process`
Expected: `BUILD SUCCESSFUL`；无新增 lint error。

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsTokens.kt \
        app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsComponents.kt \
        app/src/test/java/com/nasmusic/tv/ui/SettingsGroupCardContractTest.kt
git commit -m "feat(settings): 新增 SettingsTokens 尺寸令牌与 SettingsGroupCard 分组卡片原语"
```

---

### Task 2: GENERAL tab 卡片化 + 覆盖率门禁建立

**Files:**
- Modify: `app/src/main/java/com/nasmusic/tv/ui/screens/settings/GeneralSettingsSection.kt`
- Modify: `app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`
- Create: `app/src/test/java/com/nasmusic/tv/ui/SettingsGroupCoverageTest.kt`

**Interfaces:**
- Consumes: `SettingsGroupCard` + `SettingsTokens`（T1）
- Produces: `SettingsGroupCoverageTest`（含 `MIGRATED` 名单，后续任务逐个追加；并**必须**提供 T11 复用的私有工具 `settingsRoot()` 与 `stripComments(text)`）；字符串 key `settings_group_theme` / `settings_group_language` / `settings_group_orientation`

- [ ] **Step 1: 写失败的门禁（只扫已迁移文件）**

```kotlin
package com.nasmusic.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「设置分区的每个可操作行必须待在 `SettingsGroupCard` 里」（方案 §5.7 G1）。
 *
 * 与 ChipContentWidthScanTest / SmallTouchTargetScanTest 同范式：项目无 compose-ui-test，
 * 「子项没有分组边界」这类**纯视觉缺陷**只能靠源码结构门禁锁住。
 *
 * 判定：`SettingSwitch(` / `SettingActionButton(` / `SettingsInfoRow(` 调用点必须落在
 * 同文件某个 `SettingsGroupCard(` 的大括号配对区内；豁免标记 `Group-exempt: <理由>`；
 * `//` 与 `/* … */` 注释先剥离。
 *
 * ⚠️ MIGRATED 是**增量白名单的反向**：只有已完成卡片化的文件才被扫描，逐任务扩大，
 * 全部迁完（T15）后改为扫描 `ui/screens/settings/` 全部 `*Section.kt`。
 */
class SettingsGroupCoverageTest {

    private val migrated = listOf(
        "GeneralSettingsSection.kt",
    )

    private val rowCalls = listOf("SettingSwitch(", "SettingActionButton(", "SettingsInfoRow(")

    @Test
    fun `已迁移分区的每个设置行都必须位于 SettingsGroupCard 内`() {
        val root = settingsRoot()
        var scanned = 0
        val violations = mutableListOf<String>()
        for (name in migrated) {
            val text = stripComments(root.resolve(name).readText())
            val cardRanges = groupCardSpans(text)
            assertTrue("$name 已列入迁移名单却找不到 SettingsGroupCard 调用", cardRanges.isNotEmpty())
            text.lines().forEachIndexed { idx, line ->
                val call = rowCalls.firstOrNull { line.contains(it) } ?: return@forEachIndexed
                if (line.contains("Group-exempt:")) return@forEachIndexed
                if (line.contains("internal fun ") || line.contains("private fun ")) return@forEachIndexed // 组件自身定义
                val offset = text.lineOffset(idx)
                scanned++
                if (cardRanges.none { offset in it }) violations += "$name:${idx + 1} $call"
            }
        }
        assertTrue("扫描到 0 个调用点 —— 门禁在空转", scanned > 0)
        if (violations.isNotEmpty()) fail("以下设置行不在任何分组卡片内：\n" + violations.joinToString("\n"))
    }

    /** 负向自证：把一行挪到卡片外，必须被判违规。 */
    @Test
    fun `卡片外的设置行必须判负`() {
        val text = """
            fun a() {
                SettingsGroupCard(title = "x") {
                    SettingSwitch(label = "1")
                }
                SettingSwitch(label = "2")
            }
        """.trimIndent()
        val spans = groupCardSpans(text)
        val outside = text.lineOffset(4)
        assertTrue("负向用例失效：卡片外的行仍被算作卡片内", spans.none { outside in it })
    }

    /** 注释里举例的写法不得被误判（剥离注释生效）。 */
    @Test
    fun `注释中的设置行调用不算违规`() {
        val text = "// SettingSwitch(label = \"x\")\nval y = 1\n"
        assertTrue(stripComments(text).contains("SettingSwitch") == false)
    }

    // ─────────── 辅助 ───────────

    /** 返回每个 `SettingsGroupCard(` 尾随 lambda 体（大括号配对）的字符区间。 */
    private fun groupCardSpans(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (true) {
            val at = text.indexOf("SettingsGroupCard(", startIndex = i)
            if (at < 0) break
            val brace = text.indexOf('{', at)
            if (brace < 0) break
            var depth = 0
            var end = -1
            var j = brace
            while (j < text.length) {
                when (text[j]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
                j++
            }
            if (end > brace) out += (brace + 1)..(end - 1)
            i = at + 1
        }
        return out
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    private fun String.lineOffset(lineIndex: Int): Int {
        var o = 0
        for (k in 0 until lineIndex) o += lines()[k].length + 1
        return o
    }

    private fun settingsRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui/screens/settings"),
            File("app/src/main/java/com/nasmusic/tv/ui/screens/settings"),
            File("../app/src/main/java/com/nasmusic/tv/ui/screens/settings"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到设置源码目录（user.dir=${File("").absolutePath}）", found != null)
        return found!!
    }
}
```

- [ ] **Step 2: 跑测试确认失败**（`GeneralSettingsSection.kt` 里 `SettingSwitch(` 在卡片外）

- [ ] **Step 3: 加字符串**（两个文件都要，缺一即崩渲染）

`values/strings.xml`：
```xml
<string name="settings_group_theme">外观与动效</string>
<string name="settings_group_language">界面语言</string>
<string name="settings_group_orientation">屏幕方向</string>
```
`values-en/strings.xml`：
```xml
<string name="settings_group_theme">Appearance &amp; motion</string>
<string name="settings_group_language">Language</string>
<string name="settings_group_orientation">Screen orientation</string>
```

- [ ] **Step 4: 迁移 GENERAL**

保留 `SectionTitle(stringResource(R.string.settings_general))`（:55），然后：
- 卡 ①`settings_group_language`：语言三选 `Row`（:63-93，三个内联 `FocusableSurface` + `Modifier.weight(1f)`，**不是** `OptionChip`）整段入卡；`settings_language_desc` 说明（:57-62）改为卡片 `description`，不再单独 `Text`。
- 卡 ②`settings_group_theme`：`SettingSwitch` ×2（:95/:96）+ 字号调整 `Row`（:99-113，两个 `AdjustButton` + `widthIn(min = 100.dp)` 的居中数值文本）。
- 卡 ③`settings_group_orientation`：`settings_orientation_hint`（:117-122）改为卡片 `description`，方向三选 `Row`（:123-）整段入卡。

> ⚠️ 三处 chips 都在 `Row` 里靠 `weight(1f)` 分宽，入卡后 `Row` 外面多了一层 10dp 卡片内边距 —— `weight` 语义不变，安全；但**不要**把这些 `FocusableSurface` 改成 `SettingActionButton`（后者默认 `fillMaxWidth()`，同排会把兄弟挤成 0 宽，`ChipContentWidthScanTest` 的历史案例）。

- [ ] **Step 5: 跑测试确认通过**（含 3 条自证）

- [ ] **Step 6: 编译 + lint + 提交**

```bash
git commit -am "feat(settings): 通用分区改为分组卡片"
```

---

### Task 3: 分区枚举重分类 + 新分区骨架 + 接线门禁

**Files:**
- Modify: `app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsSection.kt`
- Create: `.../settings/LyricsKaraokeSettingsSection.kt`、`.../settings/VisualEffectsSettingsSection.kt`、`.../settings/SourcesSettingsSection.kt`
- Delete（内容迁走，非删除功能）: `.../settings/ServerSettingsSection.kt`、`.../settings/LocalMusicSettingsSection.kt`、`.../settings/BaiduPanSettingsSection.kt` → 三者主体合并进 `SourcesSettingsSection.kt`
- Modify: `.../settings/PlayerSettingsSection.kt`（切出歌词域与 K 歌域＝人声分离+模型、视觉滤镜三块）、`.../settings/DownloadSettingsSection.kt`（**原地保留为独立 tab**，本任务不动它）
- Modify: `ui/screens/SettingsScreen.kt`、`ui/components/branches/SettingsBranch.kt`、`ui/screens/settings/SettingsSectionList.kt`
- Create: `app/src/test/java/com/nasmusic/tv/ui/SettingsSectionWiringGateTest.kt`

**Interfaces:**
- Produces: `enum SettingsSection { GENERAL, PLAYBACK, LYRICS_KARAOKE, VISUAL_EFFECTS, PHOTO_WALL, SOURCES, DOWNLOAD, CACHE, NETWORK, DATA, ABOUT }`
- Produces: `LyricsKaraokeSettingsState/Actions`、`VisualEffectsSettingsState/Actions`、`SourcesSettingsState/Actions`（字段由各旧分区搬来，签名见 Step 3）
- 字符串：`settings_lyrics_karaoke`、`settings_visual_effects`、`settings_sources`、`settings_network_advanced`（中英双份）；`settings_local_music` / `nav_server` / `settings_netdisk` 降级为**卡片标题**，`settings_download` 仍是 DOWNLOAD tab 标题 —— 全部复用不新建。

- [ ] **Step 1: 写失败的接线门禁**

```kotlin
package com.nasmusic.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分区枚举的三处接线必须同步（方案 §4.6/§5.7 G3）。
 *
 * 新增一个 SettingsSection 而漏掉 `when` 分支 ⇒ 编译失败（when 非穷举），有编译器兜底；
 * 但**漏注册 `navFocusRequesters` ⇒ 编译通过、TV 上从侧栏按「下」进不去内容区**，
 * 漏 `values-en` 标题 ⇒ 编译通过、竖屏一级列表渲染时崩。这两类只有文本门禁能锁。
 */
class SettingsSectionWiringGateTest {

    @Test
    fun `每个分区都必须注册焦点请求器`() {
        val screen = mainSource("ui/screens/SettingsScreen.kt")
        val sections = enumEntries(mainSource("ui/screens/settings/SettingsSection.kt"))
        assertTrue("没扫到任何分区枚举项 —— 枚举写法变了，门禁需同步", sections.isNotEmpty())
        // 口径：对每个分区名，SettingsScreen 全文必须出现 ≥2 次（when 分支 + 焦点注册）
        for (s in sections) {
            val hits = Regex("SettingsSection\\.$s\\b").findAll(screen).count()
            assertTrue("SettingsScreen 里 `$s` 只出现 $hits 次（需 when 分支 + navFocusRequesters 各一处）", hits >= 2)
        }
    }

    @Test
    fun `每个分区的标题字符串必须中英齐备`() {
        val enumSrc = mainSource("ui/screens/settings/SettingsSection.kt")
        val titles = Regex("""(\w+)\(R\.string\.(\w+),\s*Icons""").findAll(enumSrc).map { it.groupValues[2] }.toList()
        assertTrue("没扫到任何 titleRes —— 枚举写法变了，门禁需同步", titles.isNotEmpty())
        val zh = resXml("values"); val en = resXml("values-en")
        val zhMissing = titles.filterNot { zh.contains("""name="$it"""") }
        val enMissing = titles.filterNot { en.contains("""name="$it"""") }
        assertTrue("values/strings.xml 缺分区标题：$zhMissing", zhMissing.isEmpty())
        assertTrue("values-en/strings.xml 缺分区标题（编译能过、渲染会崩）：$enMissing", enMissing)
    }

    /** 负向自证：伪造一个没有字符串的分区名，上一条必须判负。 */
    @Test
    fun `缺英文标题的分区必须被判负`() {
        val en = resXml("values-en")
        assertTrue("护栏空转：values-en 里居然真的有 settings_bogus_section",
            !en.contains("""name="settings_bogus_section""""))
    }

    private fun enumEntries(src: String): List<String> =
        Regex("""^\s{4}([A-Z][A-Z_]+)\(R\.string\.""", RegexOption.MULTILINE)
            .findAll(src).map { it.groupValues[1] }.toList()

    /**
     * `user.dir` 在 Gradle 测试 worker 下可能是 `app/`、仓库根，也可能是带 `../` 的变体 ——
     * 照 `LocalMusicGateTest.mainSourceRoot()` 的多候选写法，⛔ 不写绝对路径、不用行号锚点。
     */
    private fun mainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/$relativePath"),
            File("app/src/main/java/com/nasmusic/tv/$relativePath"),
            File("../app/src/main/java/com/nasmusic/tv/$relativePath"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $relativePath（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}", found != null)
        return found!!.readText(Charsets.UTF_8)
    }

    private fun resXml(valuesDir: String): String {
        val candidates = listOf(
            File("src/main/res/$valuesDir/strings.xml"),
            File("app/src/main/res/$valuesDir/strings.xml"),
            File("../app/src/main/res/$valuesDir/strings.xml"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 $valuesDir/strings.xml（user.dir=${File("").absolutePath}）", found != null)
        return found!!.readText(Charsets.UTF_8)
    }
}
```

> ⚠️ 判据是「每个分区名在 `SettingsScreen.kt` 里以 `SettingsSection.X` 形式出现 ≥2 次」（`when` 分支一处 + `navFocusRequesters` 注册一处）。若某分区刻意不注册焦点请求器，就给它加 `// Focus-exempt: <理由>` 并把该名字写进断言的例外名单，**不要**把阈值放宽到 ≥1。

- [ ] **Step 2: 跑测试确认失败**（新分区还不存在，`values-en` 缺 `settings_lyrics_karaoke`）

- [ ] **Step 3: 建 3 个新分区文件（先只做"搬迁"，不卡片化）**

- `LyricsKaraokeSettingsSection.kt`：从 `PlayerSettingsSection.kt` 切出
  - `data class LyricsKaraokeSettingsState(val settings: AppSettings, val separationMode: SeparationMode, val modelDownloaded: Boolean, val modelDownloading: Boolean, val modelDownloadProgress: Float, val modelDownloadedMB: Long, val modelTotalMB: Long, val modelSizeMB: Double, val modelDownloadError: String?, val modelPath: String, val hqErrorDetail: String?)` —— 除 `settings`（原本也在 `PlayerSettingsState` :37）外，其余 10 个字段与 `PlayerSettingsState` :39-47、:61 **同名同类型剪切过来**，不重命名。
  - `data class LyricsKaraokeActions(val onChangeSeparationMode: ((SeparationMode) -> Unit)?, val onDownloadModel: (() -> Unit)?, val onDeleteModel: (() -> Unit)?, val onScanTransferModel: (() -> Unit)?)` —— 从 `PlayerSettingsActions` :70-73 剪切，可空性与默认值保持原样。
  - 内容 = **纯搬迁**：原 `PlayerSettingsSection.kt` :262-375（分离模式 + 模型块）逐行复制，一处不加。⛔ 歌词字号/高亮、自定义模型源这两块的 UI **不在本任务**——它们今天不在设置页（字号/高亮在播放页弹窗，自定义模型源根本没有 UI），且 `lyricsFontScale`(`Float`，`LyricsPrefs.kt:10`) / `highlight` / `modelDownloadUrl`(`AppSettings:43`) 也**都不在** `AppSettings` 同一条 Flow 上。这两块由 **T10** 追加字段与卡片，分两步走的好处：T3/T4 的 diff 保持"纯搬迁"可逐行对照，新 UI 的接线错误只可能出现在 T10 一个提交里。（变调/倍速同样"今天不在设置页"，但按 §6-13 裁决**永远不进**设置页，不在这两块的名单里。）
- `VisualEffectsSettingsSection.kt`：`VisualEffectsSettingsState(val settings: AppSettings, val visualizerQuality: VisualQuality, val coverFilterEnabled: Boolean, val coverFilterBlurRadius: Float, val coverFilterDarkOverlay: Float)` + `Actions(val onChangeVisualizerQuality, val onToggleCoverFilter, val onChangeCoverBlurRadius, val onChangeCoverDarkOverlay)`；内容 = 原 :235-260（画质）+ :403-470（封面滤镜）+ 一行 `SettingsInfoRow(效果主题, settings.visualizerTheme.displayName)`。
- `SourcesSettingsSection.kt`：**三个旧分区文件（`ServerSettingsSection.kt` / `LocalMusicSettingsSection.kt` / `BaiduPanSettingsSection.kt`）合并成一个**，字段逐一沿用旧名（减少 `SettingsScreen` 侧改动）：
  - `data class SourcesSettingsState(val isConnected: Boolean, val serverDisplayName: String, val settings: AppSettings, val libraryCount: Int, val baiduEnabled: Boolean, val baiduLoggedIn: Boolean, val baiduConnectionState: NetworkMusicViewModel.BaiduConnectionState, val baiduMusicRootDirLocal: String, val baiduMvDirLocal: String?, val baiduIndexScanned: Int, val baiduIndexScanning: Boolean, val baiduApicExtracting: Boolean, val baiduApicExtracted: Int, val baiduApicTotal: Int, val defaultNetworkSource: NetworkSource)`
    （`isConnected` / `serverDisplayName` 沿用 `ServerSettingsState` :17-20 的原名；`settings` / `libraryCount` 沿用 `LocalMusicSettingsState` :26-30；`baidu*` 10 个字段沿用 `BaiduPanSettingsState` :27-38 的原名与可空性 —— **逐一保留原名**，`SettingsScreen` 侧只是将三处构造合成一处。）
  - `data class SourcesActions(val onNavigateToServerConnect: () -> Unit, val onDisconnect: () -> Unit, val onSetEnabled: (Boolean) -> Unit, val onRescan: () -> Unit, val onToggleBaiduEnabled: (Boolean) -> Unit, val onStartBaiduDeviceCode: () -> Unit, val onLogoutBaidu: () -> Unit, val onRebuildBaiduIndex: () -> Unit, val onChangeDefaultNetworkSource: (NetworkSource) -> Unit)`
    （前四个沿用旧分区原字段名；`defaultNetworkSource` 的 setter 由 T7 新加。旧三文件的可空回调（`((Boolean) -> Unit)?`）合并时**改为非空**默认 `{}`，与 `LocalMusicSettingsActions` :33-36 的既有写法一致。）
  - `data class SourcesDialogActions(val onShowBaiduAuthDialog: () -> Unit, val onShowMusicRootDialog: () -> Unit, val onShowMvDirDialog: () -> Unit)`（对话框宿主仍在 `SettingsScreen`，本任务只搬参数名）。
  - 内容 = 旧三文件 `Column` 主体按 §3.2 的五卡顺序拼接（去掉三处重复的 `SectionTitle`）。
  - ⛔ **迁移红线**：本地曲库总开关是音乐权限的**唯一触发点**（`LocalMusicSettingsSection.kt:19-24` 的既有约定，护栏 `LocalMusicGateTest`），搬迁只允许整段 `SettingSwitch` 原样复制，**不得**把权限判断、申请逻辑或"重新扫描"的 `enabled = s.localMusicEnabled` 置灰条件改动或上移；服务器卡只保留「打开配置页 / 断开」两个动作，⛔ 不在设置页内嵌任何凭据输入框（§3.3）。
- `DownloadSettingsSection.kt`：**本任务不动** —— 下载是独立域（§3 表「保留独立 tab」），文件与 `DownloadSettingsState/Actions` 签名原样保留，卡片化在 T6 做。
- `PlayerSettingsSection.kt`：删除已迁走的字段与 UI，去掉 ✕15（`visualizerTheme` :38 与 `onChangeVisualizerTheme` :67），新增 `val equalizerPresetName: String` 供均衡器卡显示当前预设。

- [ ] **Step 4: 改枚举 + 三形态接线**

`SettingsSection.kt` 换成 §3 的 11 值；`SettingsScreen.kt` 的 `when(displaySection)` 分支、`navFocusRequesters` 建立、对话框宿主随新签名同步；`SettingsBranch.kt` 传参更新（删 `onChangeVisualizerTheme = …` :161、删 `onChangeLyricsOffset` :129）；`SettingsSectionList.kt` 无需改（它遍历 `SettingsSection.entries`）。

- [ ] **Step 5: 跑接线门禁 + 全量单测**

Run: `./gradlew.bat testDebugUnitTest --tests "*SettingsSectionWiringGateTest" --no-daemon -Pkotlin.compiler.execution.strategy=in-process` → PASS
Run 全量：`./gradlew.bat testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process --rerun-tasks` → 与基线同数（v1.1 审校参考：2026-10-17 本地工件 211 XML / 2218 例）或仅因删除项减少且零失败。

- [ ] **Step 6: 编译 + lint + 提交**

```bash
git commit -am "refactor(settings): 分区重分类为 11 个域（歌词K歌/视觉特效独立，服务器+本地曲库+网盘并为来源）"
```

---

### Task 4: PLAYBACK / LYRICS_KARAOKE / VISUAL_EFFECTS 卡片化

**Files:** Modify 三个分区文件 + `values/strings.xml` + `values-en/strings.xml`；Modify `SettingsGroupCoverageTest.kt`（名单追加 3 项）

- [ ] **Step 1: 把 `PlayerSettingsSection.kt`、`LyricsKaraokeSettingsSection.kt`、`VisualEffectsSettingsSection.kt` 加入 `migrated` 名单 → 跑测试确认失败**
- [ ] **Step 2: 加字符串**（中英各一份）：`settings_group_play_behavior`（播放行为）/ `settings_group_transition_volume`（过渡与音量）/ `settings_group_quality`（音质）/ `settings_group_equalizer`（均衡器）/ `settings_group_separation`（人声分离）/ `settings_group_model`（分离模型）/ `settings_group_visualizer`（可视化）/ `settings_group_cover_filter`（封面滤镜）。⛔ `settings_group_lyrics` **留到 T10** 再加（本任务没有这张卡，别放未使用的 key）；`settings_group_karaoke` **不新建**（K 歌卡不进设置页，§6-13）。
- [ ] **Step 3: 按 §3.2 的卡片构成包裹**
  - PLAYBACK：卡①含 `SettingSwitch(自动播放下一曲)` + `PlayModeSelector`；卡②含 `SettingSwitch(淡入淡出)` + 5 个时长 `SettingActionButton`（⚠️ **原样搬**：`PlayerSettingsSection.kt:112-123` 那个 `Row` 既不给宽度也没有选中态，属**线上既有缺陷**，本任务不动排布、由 **T13** 一并修）+ 音量 `AdjustButton` 行；卡③含档位列表 + 2 条说明 `Text` + 清除覆盖按钮；卡④含均衡器入口。
  - LYRICS_KARAOKE：本任务**只包现存的两块**——卡「人声分离」（`SettingSwitch(分离模式)`，原 :262-286）与卡「分离模型」（进度 `Text`、下载/删除/扫码 3 按钮、路径 `SettingsInfoRow`、错误 `Text` + `hqErrorDetail` 面板，原 :287-375）。§3.2 的卡①歌词显示在 **T10** 才出现（那时直接以 `SettingsGroupCard` 落地，不会触发 G1 违规）；本任务这两块即最终的卡②/卡③，编号此后不再变。
  - VISUAL_EFFECTS：卡①可视化（画质 3 按钮 + 效果主题只读行）；卡②封面滤镜（开关 + 半径行 + 暗化行）。
- [ ] **Step 4: 跑测试确认通过（含 3 条自证）+ 编译**
- [ ] **Step 5: 提交** `git commit -am "feat(settings): 播放/歌词K歌/视觉三个分区改为分组卡片"`

---

### Task 5: PHOTO_WALL 卡片化

**Files:** Modify `PhotoWallSettingsSection.kt` + 字符串两份；`migrated` 名单追加

- [ ] **Step 1: 名单追加 → 测试失败**
- [ ] **Step 2: 加字符串**：`settings_group_pw_sources`（照片来源）/ `settings_group_pw_filter`（显示筛选）/ `settings_group_pw_transition`（转场效果）/ `settings_group_pw_motion`（停留期运动）/ `settings_group_pw_audio`（音频反应）——**直接复用现有 5 个 `settings_photo_wall_*` 标题字符串**（:190/:321/:376/:435/:445 已在用），若命名合适则此步只补 `values-en` 缺失项，不新增 key。
- [ ] **Step 3: 5 个 `SubSectionTitle` 一比一换成 `SettingsGroupCard`**：来源卡含 3 开关 + 均衡 + 目录行 + 常用目录限制 + 4 计数行；筛选卡含人脸开关 + 扫描入口 + 扫描进度/阶段；转场卡含随机开关 + 固定转场 chips + 时长；运动卡含停留时长 + `OptionChip`（画面适配，⚠️ 同排宽度陷阱）+ Ken Burns；音频卡含 3 开关。
- [ ] **Step 4: 测试通过 + 真机竖屏确认权限申请仍只由「相册总开关」触发**（§5.6 迁移不改 onClick 语义）
- [ ] **Step 5: 提交** `git commit -am "feat(settings): 照片墙分区改为分组卡片"`

---

### Task 6: DOWNLOAD 卡片化

**Files:** Modify `DownloadSettingsSection.kt`（**独立 tab，原地保留**，T3 未动它）+ `values/strings.xml`、`values-en/strings.xml`；`migrated` 名单追加

- [ ] **Step 1: 名单追加 → 失败**
- [ ] **Step 2: 字符串**：四个卡片标题**直接复用现有的 4 个 `SubSectionTitle` 字符串** —— `settings_download_basic`（:75）/ `settings_download_location`（:115）/ `settings_download_stats`（:64）/ `settings_download_export`（:142），本步只需核对 `values-en` 四项齐全，缺则补译，**不新增 key**。
- [ ] **Step 3: 4 个 `SubSectionTitle` 一比一换成 `SettingsGroupCard`**：
  - 卡①下载开关（原 basic，:75）：`SettingSwitch(启用下载)` :76-81 + `SettingSwitch(播放时自动下载)` :82-88 + 上限 `AdjustButton` 行 :90-112（含 `settings_auto_download_limit` / `_desc` 两行 `Text`，整段进卡）。
  - 卡②存储位置（原 location，:115）：当前路径 + 说明 `Text` :116-127 + 本机 / 外接两个 `AdjustButton` :129-130。
  - 卡③已下载（原 stats，:64）：统计 4 行 `SettingsInfoRow` :67-74 +「清除全部下载」`SettingActionButton` :137-141（**从原 location 之后上移到本卡内**，语义上它清的就是这些统计）。
  - 卡④导出到设备（原 export，:142）：说明 `Text` :143-148 + 状态机各行 + `SettingActionButton(导出)` :214-219。
  - ⚠️ 卡①②④里的"小节标题 `Text`"（如 `settings_auto_download_limit`）是**行标题**不是卡标题，保留在卡内；只有 `SubSectionTitle` 换成 `SettingsGroupCard(title=…)`。
- [ ] **Step 4: 测试通过 + 编译 + 提交** `git commit -am "feat(settings): 下载分区改为分组卡片"`

---

### Task 7: SOURCES 卡片化 + 默认网络源上线 + 占位行删除

**Files:** Modify `SourcesSettingsSection.kt`（T3 产物）/ 字符串 / `AppPreferences.kt`（新增 setter 若缺）/ `MainViewModel.kt` / `data/model/CloudDriveType.kt` / `SettingsComponents.kt`（删 `PlaceholderRow`）/ `app/src/test/java/com/nasmusic/tv/ui/SettingsSectionWiringGateTest.kt`（追加凭据负向断言）

**Interfaces:**
- Consumes: `settings_default_network_source`（AppPreferences :279，R-7 镜像 :169/:213-214，同步读 :1199）
- Produces: `SourcesActions.onChangeDefaultNetworkSource: (NetworkSource) -> Unit` → `MainViewModel.updateDefaultNetworkSource(source)` → `prefs.network.setDefaultNetworkSource(source)`（:887 已存在）

- [ ] **Step 1: 门禁追加 `SourcesSettingsSection.kt` → 失败**
- [ ] **Step 2: 加字符串**（中英各一份）：`settings_group_nas_server`（NAS 服务器）/ `settings_group_local_music`（本地曲库）/ `settings_group_baidu`（百度网盘，**复用** `settings_netdisk_group_baidu` :67）/ `settings_group_netdisk_index`（索引与封面）/ `settings_group_default_source`（网络搜索默认源）/ `settings_default_source_desc` / `settings_group_nas_server_desc`（「服务器地址与账号在连接页填写，本页只做开关与断开」）
- [ ] **Step 3: 写默认源的失败单测（确定性，零时序）**

```kotlin
// app/src/test/java/com/nasmusic/tv/data/prefs/DefaultNetworkSourcePrefTest.kt
package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nasmusic.tv.data.model.NetworkSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「设置页新增的默认网络源卡」写的是哪条事实源（方案 §6-10）。
 *
 * ⚠️ 断言只走 `appSettings` Flow（AppPreferences:799 读 `keyDefaultNetworkSource`），
 * **绝不**断言 `getDefaultNetworkSourceSync()`（:1199，`@Volatile` 内存镜像）——
 * 「写完立刻读到新值」是时序量，`ProviderMirrorTest` 的 4 条镜像计时用例已因此
 * 于 5997380 整体删除，镜像的**接线与形状**由 `ProviderMirrorWiringGateTest` G2
 * 自动枚举 `cached*` 字段钉住（`cachedDefaultNetworkSource` :169 已在网内）。
 * 本测试只证明"这个 key 真的被这条 setter 写、真的被这个字段读"，即 UI 接对了线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultNetworkSourcePrefTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        prefs = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `setDefaultNetworkSource 必须落到 appSettings 的 defaultNetworkSource`() = runBlocking {
        prefs.setDefaultNetworkSource(NetworkSource.QJIEYA)
        assertEquals(NetworkSource.QJIEYA, prefs.appSettings.first().defaultNetworkSource)
    }

    @Test
    fun `未设置时必须回落 NetworkSource DEFAULT`() = runBlocking {
        assertEquals(NetworkSource.DEFAULT, prefs.appSettings.first().defaultNetworkSource)
    }

    /** 负向自证：写入必须真的改变事实源（防止 setter 空转）。 */
    @Test
    fun `改到另一个源后事实源必须跟着变`() = runBlocking {
        prefs.setDefaultNetworkSource(NetworkSource.QJIEYA)
        prefs.setDefaultNetworkSource(NetworkSource.DEFAULT)
        assertEquals(NetworkSource.DEFAULT, prefs.appSettings.first().defaultNetworkSource)
    }
}
```

> ⚠️ 落地前先读 [AppPreferences.kt:887](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt#L887) 确认 `setDefaultNetworkSource` 是挂在 `AppPreferences` 还是 `network` 门面上（门面见 `NetworkMusicPrefs.kt`），按真实调用路径写；`NetworkSource` 的枚举成员名以 [data/model/NetworkSource.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/model/NetworkSource.kt) 为准，若无 `QJIEYA` 就换一个非 DEFAULT 的真实成员。构造范式照 [LanguageMirrorTest.kt:37-40](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/test/java/com/nasmusic/tv/data/prefs/LanguageMirrorTest.kt#L37)。

- [ ] **Step 3b: 加 UI 接线门（防止"卡片加了但读的是另一个 key"）**

在同一测试类里追加源码文本断言：`SourcesSettingsSection.kt` 必须同时含 `defaultNetworkSource` 与 `onChangeDefaultNetworkSource`；`SettingsBranch.kt` 必须含 `setDefaultNetworkSource`（或其门面形式）。并写负向自证（把 `onChangeDefaultNetworkSource` 从样例文本里去掉必须判负）。

- [ ] **Step 4: 实现默认源卡 + 卡片化 + 删占位**
  - **五卡构成（§3.2）**：
    - 卡① NAS 服务器：`SettingsInfoRow(当前连接, state.serverDisplayName)` + 「服务器配置」`SettingActionButton`（ServerSettingsSection:45-50，跳 `Screen.ServerConnect`，`description` 填**生效摘要** `Navidrome · 192.168.0.190:4535`，见 §3.3）+ 「断开连接」`SettingActionButton`（:53-57，⚠️ 未连接时是**整段不渲染**而不是置灰 —— `ServerSettingsSection:51` 的 `if (state.isConnected && actions.onDisconnect != null)`；搬迁时保留同一形态，⛔ 别顺手改成 `enabled = false`，否则电视上会多出一颗按不动的按钮）。
    - 卡② 本地曲库：总开关（原 LocalMusicSettingsSection:48-53，⛔ 权限唯一触发点，整段原样复制）+ 已索引数 `SettingsInfoRow`（:55-58）+ 重新扫描 `SettingActionButton`（:61-66，`enabled = s.localMusicEnabled` 保持不变，:65）。
    - 卡③ 百度网盘：开关 :68 + 授权失败/根目录缺失横幅 :69-110 + 登录/退出 :111-128 + 音乐根目录 :130-135 + MV 目录 :136-141。
    - 卡④ 索引与封面：已扫描歌曲数 + APIC 提取进度 :142-181 + 重建索引 :182-190。
    - 卡⑤ 网络搜索默认源（新增）。
  - 默认源卡：`NetworkSource.entries`（除占位项）逐个 `SettingActionButton`（`selected` 用容器色，口径同音质档位 :189-195 —— ⚠️ 这是**令牌化前**的既有词汇，T11 会把它连同音质档位一起换成 `SettingsTile(selected = true)`，本任务不提前改，免得同一行改两遍）。
  - 删除 `BaiduPanSettingsSection` 遗留的 `PLACEHOLDER.forEach`（:192-196，迁到 SOURCES 时整段不带入）、`PlaceholderRow`（SettingsComponents:68-83）、`R.string.settings_netdisk_group_others` 与 `settings_netdisk_group_others_desc`（中英各一份，values:414-415 / values-en:411-412）。**用户 2026-10-18 已确认删除（§6-4）**。
  - `grep` 确认 `CloudDriveType.PLACEHOLDER` 全树 0 命中后，**连同 `IMPLEMENTED`（:28）一起删**：实测 `PLACEHOLDER` 的唯一消费方就是这段循环，`IMPLEMENTED` 的唯一消费方是 `PLACEHOLDER` 的定义（:30），删循环后两者同时成死码。⛔ 四个枚举条目、`key`、`displayNameRes` 与 `fromKey`（:32）**保留** —— `aliyun`/`123`/`quark` 三个 key 仍可能出现在旧 `CloudDriveConfig` JSON 里，`fromKey` 必须能解析（删了就是让老用户的配置读不出来）。
  - **凭据负向断言（§3.3）**：在 `SettingsSectionWiringGateTest` 追加一条 —— `ui/screens/settings/*.kt` 全文不得出现 `TextFieldValue` / `PasswordVisualTransformation` / `KeyboardType.Password`（服务器配置留在独立页，设置页不内嵌输入框）。配一条负向自证：把 `TextFieldValue` 塞进样例文本必须判负。
- [ ] **Step 5: 测试全绿 + 编译 + 提交** `git commit -am "feat(settings): 来源分区（服务器+本地曲库+网盘）卡片化并上线默认网络源；移除未支持网盘占位行"`

---

### Task 8: CACHE / NETWORK / DATA / ABOUT 卡片化

**Files:** Modify 四个分区文件 + 字符串；`migrated` 追加 4 项

- [ ] **Step 1: 名单追加 → 失败**
- [ ] **Step 2: 加字符串**：`settings_group_cache_policy`（缓存策略）/ `settings_group_cache_clear`（占用与清理）/ `settings_group_connectivity`（连通性）/ `settings_group_search_endpoints`（音乐搜索端点）/ `settings_group_mv_endpoints`（MV 端点）/ `settings_group_lyrics_endpoints`（歌词端点）/ `settings_group_weather_key`（天气 API Key）/ `settings_group_backup`（备份与恢复）/ `settings_group_playlist_import`（歌单导入）/ `settings_group_play_stats`（播放统计）/ `settings_group_version`（版本信息）/ `settings_group_connection_info`（连接信息）/ `settings_group_capabilities`（能力说明）/ `settings_group_opensource`（开源与许可）
- [ ] **Step 3: 逐文件包裹**（NETWORK 的 5 处裸 `Text` 标题直接换成卡片头，这是 §5.1 消灭特例的地方）
- [ ] **Step 4: 关于页 GitHub 链接改可聚焦瓦片**（AboutSection:95-105）：保留 `Intent.ACTION_VIEW` 跳转与容器/焦点色同 `SettingActionButton` 口径；⚠️ `Text.clickable` 在 TV 上**聚焦不到**，这正是 §2-7 的缺陷本体，换成 `FocusableSurface` 才算修好。本任务先用现有参数块落地（G7-B 是 T11 才建立的门），**T11 Step 5 把它换成 `SettingsTile`**。若 `SmallTouchTargetScanTest` 对新按钮尺寸报错，用其既有豁免标记写理由，不要放宽规则。
- [ ] **Step 5: 测试全绿 + 编译 + 提交** `git commit -am "feat(settings): 缓存/网络/数据/关于分区改为分组卡片，关于页仓库链接改为可聚焦"`

---

### Task 8b: 缓存管理补全（占用一览 + 缺失清理项 + 门禁 G9；§1.14 / §2-12）

> **为什么单独一次提交**：T8 只做"把现有 4 个按钮和 1 个数字包成卡片"（纯形态），本任务做的是**内容缺口**——把代码里 **14 处可管理缓存 + 4 处"扫得到但不是缓存"的落盘位置**（§1.14 表 15–18）收进一张登记表、补 3 个从未接线的清除入口、修 1 个"清了数字不动"的统计口径。混在 T8 里，真机走查发现问题时无法区分是"卡片化改坏了"还是"新增的删除路径改坏了"。
> ⚠️ 本任务**含一条线上既有缺陷的修复**（点「清除歌词缓存 / 清除 MV 缓存」后顶部数字不回落，§1.14 结论 1），所以 commit 前缀用 `fix`。
> ⛔ 本任务**不碰**下载目录与模型文件的删除逻辑（它们已各归 DOWNLOAD / LYRICS_KARAOKE，重复放按钮会让用户以为是两回事），只在占用一览里**计入体积**。

**Files:**
- Create: `app/src/main/java/com/nasmusic/tv/data/cache/CacheInventory.kt`（登记表 + 尺寸计算，唯一真源）
- Create: `app/src/test/java/com/nasmusic/tv/ui/screens/settings/CacheInventoryGateTest.kt`（G9）
- Modify: `ui/screens/settings/CacheSettingsSection.kt`（三卡构成、占用一览 5 组 19 行、**新增 2 个按钮** + **1 处改名扩容**（共 6 个清理按钮）、改 2 个按钮的名与说明）
- Modify: `backend/local/EmbeddedCoverExtractor.kt`（新写磁盘删除；顺手接上 `clearMemoryCache()` :150）、`backend/local/LocalCoverExtractor.kt`（新写 `clearDiskCache()` —— 该类今天**一个删除方法都没有**）
- Modify: `player/HqSeparationOrchestrator.kt`（新增 `clearAllSeparationArtifacts()`：`accompanimentCache?.clearCache()` + 两个临时目录 sweep）、`lyrics/LyricsManager.kt`（新增**字节**尺寸 API，现有 `getCacheSize()` :397 返回的是条目数）
  ⚠️ 同一个文件的 `getLocalLyricsDirs()` :405-419 里有**用户自己放的目录**（外部 `Music`、`Download`、外部 `lyrics`、`filesDir/lyrics`，§1.14 表 15）—— 那是**读取源**不是缓存，⛔ 本任务不得给它任何删除路径，只在占用一览按 `deletable = false` 计入体积。
- Modify: `ui/viewmodel/MainViewModel.kt`、`ui/components/branches/SettingsBranch.kt:130-133`、`ui/screens/SettingsScreen.kt`（参数透传）、`values/strings.xml` + `values-en/strings.xml`（**本任务的 36 个新 key 落点**：1 剩余空间标签 + 5 组名 + 19 行标签 + 9 条代价说明 + 2 条按钮说明，口径见 §4.5 注记；中英必须同步，`LanguageMirrorTest` 会红）
- Modify: `docs/technical-overview.md`（§10.N）

**Interfaces:**
- Consumes: T8 已建立的 `SettingsGroupCard` / `SettingsInfoRow` / `SettingActionButton`（本任务的行按**现有风格**写，⛔ 不要在这里发明新原语 —— 取值与尾部语义分别由 **T11 / T12** 收敛，与 T10 同套路）
- Produces:
  - `CacheInventory.entries: List<CacheEntry>`、`CacheInventory.resolve(context, entry): File`、`CacheInventory.sizeOf(context, entry): CacheSize`（`data class CacheSize(val bytes: Long, val truncated: Boolean)`）
  - `MainViewModel.clearCoverUrlCache()` / `clearExtractedCoverCache()` / `clearSeparationArtifacts()`
  - `EmbeddedCoverExtractor.clearDiskCache(): Long`、`LocalCoverExtractor.clearDiskCache(): Long`、`HqSeparationOrchestrator.clearAllSeparationArtifacts(): Long`
  - 门禁 G9（`CacheInventoryGateTest`）

- [ ] **Step 1: 真机取证（改版前后各一次，这是本任务唯一能证明缺陷存在的方式）**

未改版的 APK 上：进 CACHE → 记下顶部数字 → 点「清除歌词缓存」→ 等 1s（`refreshKey` 那条 `LaunchedEffect` 有 300ms `delay`，`CacheSettingsSection.kt:62`）→ **再记一次数字**。

```bash
& "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.0.114:5555 exec-out screencap -p > output/settings-cache-before.png
# 遥控器点「清除歌词缓存」后再拍一张
& "C:\Users\hxzha\AppData\Local\Android\Sdk\platform-tools\adb.exe" -s 192.168.0.114:5555 exec-out screencap -p > output/settings-cache-after-lyrics.png
```

预期（按 §1.14 结论 1 的推断）：**两张图数字相同**，而 `filesDir/lyrics_cache/` 已被清空。用 `adb shell run-as com.nasmusic.tv du -sh app_SQLError* files filesDir` 之类核对目录确实变小（run-as 只在 debug 包可用）。若数字竟然变了，说明本机 `lyrics_cache` 落在别处，**先回来更正 §1.14 再动手**。

- [ ] **Step 2: 写 G9「缓存登记表」失败门测**

新文件 `app/src/test/java/com/nasmusic/tv/ui/screens/settings/CacheInventoryGateTest.kt`。三条判据 + 五条自证，扫描口径沿用本项目既有源码文本门（每个门各持一份私有 helper 是既有范式，见 `ChipContentWidthScanTest` / `SettingsRowWidthScanTest`）。

⚠️ **下面的正则不是"照着写"的，是拿真树跑过的**：同一份正则在当前代码上命中 **16 个目录名**（`album_covers`、`baidu_index.json`、`cover_cache_`、`cover_url_cache.json`、`datastore`、`demucs_pcm_`、`image_cache`、`lyrics`、`lyrics_cache`、`lyrics_cache.json`、`music`、`mv_cache.json`、`nasmusic_hq` + Room 三条 `.db`）。三个失配点已经改进了判据设计，必须一并写进 KDoc：

- **`$` 模板前缀**：`"cover_cache_…"`、`"demucs_pcm_…"` 只能扫到下划线前缀（字符类里排除 `$`）⇒ 登记表的 `dir` 要写成**带下划线的前缀**，且这两项在 `cacheDir` **根下是散文件**、不是子目录（Step 4 的 `sizeOf` 特例）。
- **常量拼接扫不到**：`AccompanimentCache` 的目录名来自 `CACHE_DIR_NAME`（:25），正则**看不见 `accompaniment`** ⇒ 本门只能兜「字面量子目录漏登记」，登记表完整度仍靠人工（所以 `accompaniment` 照样登记）。⛔ 因此**不许**反向断言"每项登记都必须被扫到"，那会对每一条常量路径误判红。
- **`.resolve("x")` 写法**：Coil 目录是 `cacheDir.resolve("image_cache")`（NasMusicApp:591），只认逗号会整类漏掉 ⇒ 正则同时接受 `,` 与 `.resolve(`。

```kotlin
package com.nasmusic.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G9：磁盘缓存必须全部登记在 `data/cache/CacheInventory.kt`（方案 §1.14）。
 *
 * 成因：本轮人肉扫出 14 处落盘缓存，其中 5 处设置页管不到、4 处统计不到，还有
 * 3 个「清除函数早写好了但没人调」（CoverUrlPersistentCache / AccompanimentCache /
 * EmbeddedCoverExtractor）。靠人肉复查必然再漏，所以把「有没有登记」变成文本门。
 *
 * 三条判据：
 *  1. 源码里 cacheDir / filesDir / dataDir 后面紧跟的**字面量**（逗号或 `.resolve(`）
 *     必须作为 `dir = "…"` 出现在登记表里；Room 的三个 `.db` 名单独扫。未登记 = 红。
 *  2. 登记为 `deletable = true` 的条目必须给 `clearVia`，且 main 源集里真有同名 `fun` 声明
 *     =「说能清、其实清不了」= 红。这条专治清除函数写好没接线。
 *  3. 登记的非空 `dir` 必须在全树出现过 = 防「登记了一条已经不存在的路径」。
 *
 * 已知失配（诚实记录，别当门没用）：常量拼接的目录名扫不到，实测 accompaniment 就是漏网项，
 * 它靠人工登记；所以判据 3 只做"出现过"的正向检查，不做"必须被扫到"的反向断言。
 *
 * 空转防护：命中目录数必须 >= 15（实测 16）；命中为 0 说明正则失配或源根定位错了。
 * 豁免：`cache-inventory-exempt: <理由>` 行级注释（口径同既有 Xxx-exempt）。
 */
class CacheInventoryGateTest {

    private val exempt = "cache-inventory-exempt:"

    /** 字符类里的美元符写成 \u0024：raw string 中裸 `$` 会被 Kotlin 当成模板起始符 */
    private val dirPattern = Regex(
        """(?:cacheDir|filesDir|dataDir)\s*(?:,\s*|\.resolve\(\s*)"([^"/\u0024]+)"""
    )

    @Test
    fun `每个落盘目录都必须登记在 CacheInventory`() {
        val root = mainSourceRoot()
        val registry = read(root.resolve("data/cache/CacheInventory.kt"))
        val allMain = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { read(it) }

        val found = LinkedHashMap<String, String>()          // dir → 出处
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val raw = f.readText()
            val lines = raw.split("\n")
            dirPattern.findAll(stripComments(raw)).forEach { m ->
                val line = raw.substring(0, m.range.first).count { c -> c == '\n' } + 1
                val waived = (maxOf(0, line - 4) until minOf(lines.size, line + 1))
                    .any { lines[it].contains(exempt) }
                if (!waived) found.putIfAbsent(m.groupValues[1], "${f.name}:$line")
            }
        }
        listOf("backend/download/db/DownloadDatabase.kt", "backend/local/db/LocalMusicDatabase.kt",
               "backend/photo/db/PhotoFaceDatabase.kt").forEach { rel ->
            Regex(""""([A-Za-z0-9_]+\.db)"""").findAll(stripComments(read(root.resolve(rel))))
                .forEach { found.putIfAbsent(it.groupValues[1], "room") }
        }

        assertTrue("G9 只扫到 ${found.size} 个目录，疑似正则失配或源根错位（实测应为 16）", found.size >= 15)
        println("G9 命中 " + found.size + " 个落盘目录：" +
            found.entries.joinToString(", ") { it.key + "<-" + it.value })

        val registered = registeredDirs(registry)
        val missing = found.keys.filter { it !in registered }
        assertTrue("未登记的落盘目录：" + missing.joinToString { "$it（${found[it]}）" } +
            " —— 加进 CacheInventory.entries 并决定 deletable/说明", missing.isEmpty())

        val bogus = registered.filter { !allMain.contains(it) }
        assertTrue("登记表有 ${bogus.size} 条路径在源码中找不到，登记已失真：$bogus", bogus.isEmpty())
    }

    @Test
    fun `登记为可清的条目必须有真实的清除函数声明`() {
        val root = mainSourceRoot()
        val registry = read(root.resolve("data/cache/CacheInventory.kt"))
        val allMain = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { read(it) }
        val dead = deadClearVia(registry, allMain)
        assertTrue("这些条目声称可清但找不到 fun 声明：$dead（补函数，或改 deletable = false）", dead.isEmpty())
    }

    /** 判据 1 的登记表侧：`dir = "x"` 集合（空串 = 外部根本身，不参与比对） */
    private fun registeredDirs(registry: String) =
        Regex("""dir\s*=\s*"([^"/\u0024]+)"""").findAll(registry).map { it.groupValues[1] }.toSet()

    /** 判据 2：deletable = true 却找不到 `fun clearVia(` 的条目 */
    private fun deadClearVia(registry: String, allMain: String) =
        Regex("""dir\s*=\s*"([^"]+)"[\s\S]{0,500}?deletable\s*=\s*true[\s\S]{0,300}?clearVia\s*=\s*"([A-Za-z0-9_]+)"""")
            .findAll(registry)
            .filterNot { allMain.contains("fun ${it.groupValues[2]}(") }
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    // ── 自证：每条判据都要有能把它打红的样本 ──
    @Test
    fun `自证 未登记目录必须判红`() {
        val registry = """CacheEntry(dir = "image_cache", deletable = true, clearVia = "clearCoverCache")"""
        val found = setOf("image_cache", "album_covers", "brand_new_cache")
        assertEquals(listOf("album_covers", "brand_new_cache"), (found - registeredDirs(registry)).sorted())
    }

    @Test
    fun `自证 clearVia 拼错必须判红`() {
        val registry = """CacheEntry(dir = "accompaniment", deletable = true, clearVia = "clearCacheNotThere")"""
        assertEquals(listOf("accompaniment" to "clearCacheNotThere"),
            deadClearVia(registry, "    fun clearCache() { }"))
    }

    @Test
    fun `自证 真实存在的清除函数必须判绿`() {
        val registry = """CacheEntry(dir = "accompaniment", deletable = true, clearVia = "clearCache")"""
        assertEquals(emptyList<Pair<String, String>>(), deadClearVia(registry, "    fun clearCache() { }"))
    }

    @Test
    fun `自证 注释里的示例目录不得计入`() {
        val src = "// File(context.cacheDir, \"not_a_real_dir\")\nFile(context.cacheDir, \"accompaniment\")\n" +
            "cacheDir.resolve(\"image_cache\")"
        assertEquals(listOf("accompaniment", "image_cache"),
            dirPattern.findAll(stripComments(src)).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `自证 模板文件名只取到美元符之前`() {
        val dollar = 0x24.toChar()   // 真树写法：File(cacheDir, "cover_cache_$hash.$ext")
        val src = """File(cacheDir, "cover_cache_${dollar}hash.${dollar}ext")"""
        assertEquals(listOf("cover_cache_"), dirPattern.findAll(src).map { it.groupValues[1] }.toList())
    }

    /** 保长保换行：只把注释字符换成空格，字符串内容原样留下（正则要读引号里的目录名） */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src)
        var i = 0; var inLine = false; var inBlock = false; var inStr = false
        while (i < out.length) {
            val c = out[i]
            when {
                inLine -> if (c == '\n') inLine = false else out[i] = ' '
                inBlock -> { if (c == '*' && i + 1 < out.length && out[i + 1] == '/') { inBlock = false; i++ }; if (c != '\n') out[i] = ' ' }
                inStr -> if (c == '"') inStr = false
                else -> {
                    if (c == '/' && i + 1 < out.length && out[i + 1] == '/') inLine = true
                    else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') inBlock = true
                    else if (c == '"') inStr = true
                }
            }
            i++
        }
        return out.toString()
    }

    private fun read(f: File) = if (f.exists()) f.readText() else error("找不到文件：${f.absolutePath}")

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            val c = File(dir, "app/src/main/java/com/nasmusic/tv")
            if (c.exists()) return c
            dir = dir.parentFile ?: return@repeat
        }
        error("未能定位 app/src/main/java/com/nasmusic/tv，请检查运行目录")
    }
}
```

> ⚠️ **行号一律在未剥离的 `raw` 上数，正则跑在 `stripComments(raw)` 上** —— 这只有两者**长度逐字符相等**才成立（既有门的口径）。若哪天 helper 改成"删除注释"，豁免行与 `found` 的行号会一起漂到别处，门会安静地放行真违规。

- [ ] **Step 3: 跑 G9 确认失败**（`CacheInventory.kt` 还不存在 ⇒ 两条主断言在 `read()` 处 `error`）。Run: `./gradlew.bat testDebugUnitTest --tests "*CacheInventoryGateTest" --no-daemon -Pkotlin.compiler.execution.strategy=in-process`，预期 **2 红 / 5 绿**（五条自证只跑纯函数、不读登记表文件，本就该绿；它们的作用是实现完成后仍能把真违规打红）。

- [ ] **Step 4: 落地登记表**

```kotlin
// app/src/main/java/com/nasmusic/tv/data/cache/CacheInventory.kt
package com.nasmusic.tv.data.cache

import android.content.Context
import androidx.annotation.StringRes
import com.nasmusic.tv.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 落盘根。⛔ 不许新增"猜路径"的根（如 `applicationInfo.dataDir`），数据库一律走 `getDatabasePath` */
enum class CacheRoot { CACHE_DIR, FILES_DIR, EXTERNAL_MUSIC, EXTERNAL_ROOT, DATABASES }

/** 占用一览的分组（卡② 按此分组，小计 = 组内条目相加） */
enum class CacheGroup { APP_CACHE, PERSISTENT, INDEX_DB, MEDIA_MODEL, APP_DATA }

/**
 * 一条可管理的落盘缓存登记项（方案 §1.14）。
 *
 * - [dir] 必须是源码里出现的**字面量**（`cover_cache_` / `demucs_pcm_` 这类模板前缀要连下划线一起写），
 *   门禁 G9 就是拿它和全树扫描结果对账的；外部根条目留空串（路径由 [CacheRoot] 决定）。
 * - [deletable] = 有可用的清除入口（**可能**在别的分区，如人脸库入口在照片墙）。
 *   G9 判据②会反查 [clearVia] 在 main 源集里真有 `fun` 声明 ⇒ "说能清其实清不了"过不了门。
 * - [descRes] 可空：只读行按 §4.5 规范不写功能说明，**只在该项有代价要警示时**才给（重扫、误删用户文件等）。
 */
data class CacheEntry(
    val dir: String,
    val root: CacheRoot,
    val group: CacheGroup,
    @StringRes val labelRes: Int,
    @StringRes val descRes: Int? = null,
    val deletable: Boolean,
    val clearVia: String?
)

/** 尺寸结果：[truncated] 为真表示文件数超过扫描上限，数字是**下界**，UI 必须显示「≥」 */
data class CacheSize(val bytes: Long, val truncated: Boolean) {
    override fun toString(): String = (if (truncated) "≥ " else "") + com.nasmusic.tv.ui.screens.settings.formatBytes(bytes)
}

object CacheInventory {

    /** 扫描上限：已下载曲库文件数可达数万，整树 walk 在创维 5.1.1 上会拖住 IO（§1.14 结论 1） */
    private const val MAX_FILES = 4000

    val entries: List<CacheEntry> = listOf(
        // ── 应用缓存（cacheDir，今天唯一被统计的根）──
        CacheEntry(dir = "image_cache", root = CacheRoot.CACHE_DIR, group = CacheGroup.APP_CACHE,
            labelRes = R.string.settings_cache_dir_coil, deletable = true, clearVia = "clearCoverCache"),
        CacheEntry(dir = "accompaniment", root = CacheRoot.CACHE_DIR, group = CacheGroup.APP_CACHE,
            labelRes = R.string.settings_cache_dir_separation, deletable = true, clearVia = "clearSeparationArtifacts"),
        CacheEntry(dir = "nasmusic_hq", root = CacheRoot.CACHE_DIR, group = CacheGroup.APP_CACHE,
            labelRes = R.string.settings_cache_dir_hq_tmp, deletable = true, clearVia = "clearSeparationArtifacts"),
        CacheEntry(dir = "cover_cache_", root = CacheRoot.CACHE_DIR, group = CacheGroup.APP_CACHE,
            labelRes = R.string.settings_cache_dir_embedded, deletable = true, clearVia = "clearExtractedCoverCache"),
        CacheEntry(dir = "demucs_pcm_", root = CacheRoot.CACHE_DIR, group = CacheGroup.APP_CACHE,
            labelRes = R.string.settings_cache_dir_pcm_tmp, deletable = true, clearVia = "clearSeparationArtifacts"),
        CacheEntry(dir = "album_covers", root = CacheRoot.CACHE_DIR, group = CacheGroup.APP_CACHE,
            labelRes = R.string.settings_cache_dir_local_covers, deletable = true, clearVia = "clearExtractedCoverCache"),

        // ── 持久缓存（filesDir，今天完全不被统计 = §1.14 结论 1 的缺陷本体）──
        CacheEntry(dir = "lyrics_cache", root = CacheRoot.FILES_DIR, group = CacheGroup.PERSISTENT,
            labelRes = R.string.settings_cache_dir_lyrics, descRes = R.string.settings_cache_dir_lyrics_desc,
            deletable = true, clearVia = "clearLyricsCache"),
        // 索引文件与 .lrc 目录由**同一个** clear() 一起删（LyricsPersistentCache:119-124 同时
        // listFiles().delete() + indexFile.delete()），所以两行都算"可清"、共用同一个 clearVia；
        // 分成两条行的理由是它们**各占一块磁盘**，合并显示会让用户以为只有一个地方。
        CacheEntry(dir = "lyrics_cache.json", root = CacheRoot.FILES_DIR, group = CacheGroup.PERSISTENT,
            labelRes = R.string.settings_cache_dir_lyrics_index, deletable = true, clearVia = "clearLyricsCache"),
        CacheEntry(dir = "mv_cache.json", root = CacheRoot.FILES_DIR, group = CacheGroup.PERSISTENT,
            labelRes = R.string.settings_cache_dir_mv, deletable = true, clearVia = "clearMvPersistentCache"),
        CacheEntry(dir = "cover_url_cache.json", root = CacheRoot.FILES_DIR, group = CacheGroup.PERSISTENT,
            labelRes = R.string.settings_cache_dir_cover_url, descRes = R.string.settings_cache_dir_cover_url_desc,
            deletable = true, clearVia = "clearCoverUrlCache"),
        CacheEntry(dir = "lyrics", root = CacheRoot.FILES_DIR, group = CacheGroup.PERSISTENT,
            labelRes = R.string.settings_cache_dir_local_lrc, descRes = R.string.settings_cache_dir_local_lrc_desc,
            deletable = false, clearVia = null),
        // 同一个只读扫描名单的第二处（LyricsManager:417 `getExternalFilesDir(null)/lyrics`）。
        // 分成两行而不是合并，是因为 sizeOf 只能按单一 root 求值、两处的体积必须分别可核对；
        // 说明复用同一条 key（两行讲的都是"这是用户自己放的文件"）。
        // ⛔ 名单里另两处 `/storage/emulated/0/Music`（:415）与 `/Download`（:416）**不登记**：
        // 那是**用户整机目录**、不属本应用，把它的体积显示在"应用缓存占用"里是误导，
        // 且 G9 的根关键字（cacheDir/filesDir/dataDir）本来就扫不到裸 `File("…")` 常量路径。
        CacheEntry(dir = "lyrics", root = CacheRoot.EXTERNAL_ROOT, group = CacheGroup.PERSISTENT,
            labelRes = R.string.settings_cache_dir_local_lrc_ext, descRes = R.string.settings_cache_dir_local_lrc_desc,
            deletable = false, clearVia = null),

        // ── 索引与数据库（只读：删了要重扫/重建，代价必须写在说明里）──
        CacheEntry(dir = "baidu_index.json", root = CacheRoot.FILES_DIR, group = CacheGroup.INDEX_DB,
            labelRes = R.string.settings_cache_dir_baidu, descRes = R.string.settings_cache_dir_baidu_desc,
            deletable = false, clearVia = null),
        CacheEntry(dir = "downloads.db", root = CacheRoot.DATABASES, group = CacheGroup.INDEX_DB,
            labelRes = R.string.settings_cache_dir_db_downloads, deletable = false, clearVia = null),
        CacheEntry(dir = "local_music.db", root = CacheRoot.DATABASES, group = CacheGroup.INDEX_DB,
            labelRes = R.string.settings_cache_dir_db_local, descRes = R.string.settings_cache_dir_db_local_desc,
            deletable = false, clearVia = null),
        CacheEntry(dir = "photo_face.db", root = CacheRoot.DATABASES, group = CacheGroup.INDEX_DB,
            labelRes = R.string.settings_cache_dir_db_face, descRes = R.string.settings_cache_dir_db_face_desc,
            deletable = true, clearVia = "clearResults"),

        // ── 媒体与模型（体积计入，⛔ 不在本分区放删除按钮：已各归 DOWNLOAD / LYRICS_KARAOKE）──
        CacheEntry(dir = "", root = CacheRoot.EXTERNAL_MUSIC, group = CacheGroup.MEDIA_MODEL,
            labelRes = R.string.settings_cache_dir_downloads, descRes = R.string.settings_cache_dir_downloads_desc,
            deletable = false, clearVia = null),
        CacheEntry(dir = "models", root = CacheRoot.EXTERNAL_ROOT, group = CacheGroup.MEDIA_MODEL,
            labelRes = R.string.settings_cache_dir_model, descRes = R.string.settings_cache_dir_model_desc,
            deletable = false, clearVia = null),

        // ── 应用数据（⛔ 不是缓存，登记只为让"总数比可清之和大"这件事有解释）──
        CacheEntry(dir = "datastore", root = CacheRoot.FILES_DIR, group = CacheGroup.APP_DATA,
            labelRes = R.string.settings_cache_dir_prefs, descRes = R.string.settings_cache_dir_prefs_desc,
            deletable = false, clearVia = null)
    )

    /** 分组小计用的稳定顺序 */
    val groups: List<CacheGroup> = CacheGroup.entries

    fun resolve(context: Context, e: CacheEntry): File? {
        val base = when (e.root) {
            CacheRoot.CACHE_DIR -> context.cacheDir
            CacheRoot.FILES_DIR -> context.filesDir
            // 外部音乐目录为 null（未授予存储/未挂载外部卷）时，AppSettings 的 rootProvider 会回落到
            // filesDir/music（NasMusicApp:399/:410）；那条字面量在 G9 里以 cache-inventory-exempt 豁免，
            // 因为它与下面的 EXTERNAL_MUSIC 是同一份数据，重复登记会双计体积。
            CacheRoot.EXTERNAL_MUSIC -> context.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "music")
            CacheRoot.EXTERNAL_ROOT -> context.getExternalFilesDir(null) ?: context.filesDir
            CacheRoot.DATABASES -> runCatching { context.getDatabasePath(e.dir) }.getOrNull()
                ?: return null
        } ?: return null
        return if (e.dir.isEmpty()) base else File(base, e.dir)
    }

    /**
     * 单条目尺寸。⛔ 不得在主线程调用。
     *
     * `cover_cache_*` 与 `demucs_pcm_*` 是 `cacheDir` **根下的散文件**（EmbeddedCoverExtractor:98、
     * DemucsSeparator:580 用文件名前缀而非子目录），所以这两条按前缀在根目录里列文件，
     * 不能当子目录 walk（当子目录算会恒为 0，数字看着正常但永远不涨）。
     */
    suspend fun sizeOf(context: Context, e: CacheEntry): CacheSize = withContext(Dispatchers.IO) {
        val target = resolve(context, e) ?: return@withContext CacheSize(0L, false)
        if (e.root == CacheRoot.CACHE_DIR && e.dir.endsWith("_")) {
            val files = runCatching {
                context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith(e.dir) }.orEmpty().toList()
            }.getOrDefault(emptyList())
            return@withContext sumOf(
                files.take(MAX_FILES).map { runCatching { it.length() }.getOrDefault(0L) },
                truncated = files.size > MAX_FILES
            )
        }
        if (!target.exists()) return@withContext CacheSize(0L, false)
        if (target.isFile) return@withContext CacheSize(runCatching { target.length() }.getOrDefault(0L), false)
        var count = 0
        var truncated = false
        val sizes = ArrayList<Long>()
        target.walkTopDown().filter { it.isFile }.forEach { f ->
            if (count >= MAX_FILES) truncated = true
            else { count++; sizes += runCatching { f.length() }.getOrDefault(0L) }
        }
        sumOf(sizes, truncated)
    }

    private fun sumOf(sizes: List<Long>, truncated: Boolean) =
        CacheSize(sizes.sumOf { it }, truncated)

    /** 全量快照：一个协程算完所有条目，UI 只在结果回来时重组一次（⛔ 不要在 Composable 里逐项调用） */
    suspend fun snapshot(context: Context): Map<CacheEntry, CacheSize> =
        withContext(Dispatchers.IO) { entries.associateWith { runCatching { sizeOf(context, it) }.getOrDefault(CacheSize(0L, false)) } }

    /** 分组小计（卡② 的组行用这个，⛔ 不要在 UI 侧重复实现求和） */
    fun groupTotal(snapshot: Map<CacheEntry, CacheSize>, group: CacheGroup): CacheSize {
        val members = entries.filter { it.group == group }
        return CacheSize(
            bytes = members.sumOf { (snapshot[it] ?: CacheSize(0L, false)).bytes },
            truncated = members.any { (snapshot[it] ?: CacheSize(0L, false)).truncated }
        )
    }
}
```

> ⚠️ **四处口径必须照此实现，不要"顺手简化"**：
> ① `MAX_FILES` 截断 ⇒ 数字带「≥」前缀而不是假装精确（AGENTS.md 的"测量要可复算"）。
> ② `cover_cache_` / `demucs_pcm_` 是**根下散文件**：`File(cacheDir, "cover_cache_")` 去 resolve 会恒为 0（子目录不存在、`isFile` 也 false），所以 `sizeOf` 里有"目录名以 `_` 结尾 ⇒ 按前缀列 `cacheDir` 根文件"的特例分支，KDoc 已写明原因。G9 判据③能查到这两个前缀在源码里出现过，判据①也把它们的登记项当普通目录名比对 ⇒ **不需要**豁免；真正需要豁免的只有 `NasMusicApp:399/:410` 的 `"music"`（与 `EXTERNAL_MUSIC` 条目是同一份数据，双计会虚高），落地时在那两行各加一条 `cache-inventory-exempt: 外部音乐目录的回落路径，已计入 dir="" 条目`。
> ③ `DATABASES` 用 `context.getDatabasePath(name)`（⛔ 不要 `File(applicationInfo.dataDir, "databases")` 猜路径：API 30+ 该字段已废弃，且多用户/工作目录场景路径不同）。
> ④ `resolve` 对 `EXTERNAL_MUSIC` 的回落与 `AppSettings` 的 `rootProvider`（NasMusicApp:399）必须**同一套**，否则统计的是另一个目录、删的是用户真正在用的那个。
>
> ⚠️ **19 条登记项 vs G9 扫出的 16 个目录名，逐笔对账**（这几个数会随代码变，⛔ 别抄进注释当断言，门里只有 `found.size >= 15` 一条空转下限）：两侧**能对上的是 15 条**；扫描侧多 1 条 `music`（走 `cache-inventory-exempt`，与外部根是同一份数据）；登记侧多 3 条**本来就扫不到**的 —— `accompaniment`（常量拼接）、`models`（根是变量 `baseDir`）、`dir = ""` 的外部根（没有目录名）。另有两处是"同一个 `dir` 字符串、两个 root 各 1 行"（`lyrics`）与"同一块盘、索引文件与目录各 1 行"（`lyrics_cache` / `lyrics_cache.json`），它们不影响扫描侧计数，但会让"行数 ≠ 目录数"，读表时别当成漏登。
> ⚠️ **登记项（19）与 §1.14 表（18 行）不是一一对应**，映射是这五处错位：表 14「三个 Room 库」= **3 条**（G9 按 `.db` 字面量逐条对账）；表 15「用户自放 LRC」= **2 条**（`filesDir/lyrics` 与外部 `files/lyrics` 是两个位置）；表 7 的索引文件 `lyrics_cache.json` = **独立 1 条**；表 12（`.part`）与表 17（`filesDir/music` 回落）**各 0 条**（前者 §6-17 裁决不纳入、后者豁免）；表 13 与表 18（模型目录）= **1 条**（同一个 `models`）。⇒ 18 − 2（不登记）+ 2（Room 拆）+ 1（LRC 拆）= **19**。真要对账请按上面的 `dir` 清单逐名点，⛔ 不要拿两边的行数互相印证。

- [ ] **Step 5: 补三个"已写好却没人调"的清除入口**
  - `CoverUrlPersistentCache.clear()`（`backend/local/CoverUrlPersistentCache.kt:106-117`，KDoc 已写明"供设置页缓存管理手动清除"）⇒ 只做**接线**，不改实现：`MainViewModel` 新增 `fun clearCoverUrlCache() { viewModelScope.launch { nasMusicApp.coverUrlPersistentCache.clear(); postLocalConnectMessage(...) } }`（口径照 `clearLyricsCache()` :3307-3313）。
  - `AccompanimentCache.clearCache()`（[AccompanimentCache.kt:127-130](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/player/AccompanimentCache.kt#L127)，删**整目录**、**零调用方**）⇒ 在 `HqSeparationOrchestrator` 新增 `fun clearAllSeparationArtifacts(): Long`：**先判 `_separating.value`（:111-112 已有 `separating: StateFlow<Boolean>`），正在分离时直接返回 0 并给出可见提示**（⛔ 分离中删文件会让当次任务失败，这是可预见的用户可感回归）；否则调 `accompanimentCache?.clearCache()` + 扫 `cacheDir/nasmusic_hq` 里以 `_input.tmp` 结尾的文件（复用 :320-331 的后缀判死口径，别新发明匹配规则）+ 扫 `cacheDir` 根下 `demucs_pcm_*.tmp`（[DemucsSeparator.kt:580](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/player/DemucsSeparator.kt#L580)），返回释放字节数。`PlayerManager` 加同名转发（照 :295 的一行式），`VocalSeparationViewModel` 同理。
    ⚠️ 今天那个按钮走的是**另一条**更窄的路径：`MainViewModel:2699` → `HqSeparationOrchestrator.clearAccompanimentCache()` :213-214 → `AccompanimentCache.clearAccompaniments()` :136-146，它**只删** `_accompaniment.wav` / `_vocals.wav` / `_original.wav` **三种后缀** —— 这就是 §1.14 结论 2「名不副实」的第二层（连目录里其它残留都不清）。改造后的入口必须换用 `clearCache()`，且**返回值从"文件数"变成"字节数"**，提示文案 `status_accompaniment_cache_cleared`（带 `%d` 计数）要同步改成 `formatBytes` 口径，⛔ 不要留下"提示说清了 3 个、占用却掉了 40MB"的两套数。
  - `EmbeddedCoverExtractor.clearMemoryCache()`（:150-152）⇒ 新写 `fun clearDiskCache(): Long`：删 `cacheDir` 根下 `cover_cache_` 前缀文件 + 调 `clearMemoryCache()`；`LocalCoverExtractor` 新写 `fun clearDiskCache(): Long` 删 `album_covers/` 整目录（该类今天没有任何删除方法）。两者都在 IO 线程、都返回释放字节数，供结果提示复用 `formatBytes`。
  - ⚠️ **`clearVia` 一律填"设置链路真正调用"的那个函数名**（`MainViewModel.clearCoverCache` / `clearLyricsCache` / `clearMvPersistentCache` 这类，实测 :3316/:3307/:2713 已存在），⛔ 不要填库内部的同名 `clear()` —— G9 判据②只验"`fun <name>(` 在 main 源集里出现过"，填内部名会让"按钮其实根本没接上"蒙混过关（本轮的三个漏网项正是这么漏掉的）。同理 `photo_face.db` 填 `clearResults`（`FaceScanManager:134`，入口在照片墙分区，不在本分区）。

- [ ] **Step 6: 分区改造（三卡，占用一览用 `SettingsInfoRow`，清理用 `SettingActionButton`）**
  - 卡①「缓存策略」：两个 `SettingSwitch` 原样（:82-83）。
  - 卡②「占用与存储」：首行 **剩余空间**（`StorageGuard.availableBytes` StateFlow :46 —— 类注释承诺的"设置页实时显示"由本行兑现，⛔ 不要再起一个 `StatFs`），随后按 `CacheInventory.groups` 的**固定 5 组**展开：组行 = `CacheInventory.groupTotal(snapshot, group)`，组行下逐条 = `snapshot[entry]`（共 19 行，含 `deletable = false` 的只读行）。**数字一律来自 `CacheInventory.snapshot()` + `groupTotal()`**，⛔ 不许在 Composable 里 `walkTopDown`（既有审查 #13 的教训，`CacheSettingsSection.kt:52-54` 注释就是那条记录）、⛔ 不许在 UI 侧自己写求和（分组口径会在第二处漂移）。整个快照放 `LaunchedEffect(refreshKey)` 内的**一个** IO 协程；离开分区即取消（§6-14 的同一条口径）。
    - ⚠️ 组行与逐条行都是"标签 + 尾部值"两栏 ⇒ 竖屏下必须走 §4.8 **A4 的 `infoValueWidth`** 口径（裁决 6：横竖屏排布），否则 19 行长值会把标签挤没；本任务先用现有 `SettingsInfoRow`，收敛在 **T13**。
    - ⚠️ `truncated` 的行要显示「≥ 123 MB」（`CacheSize.toString()` 已带前缀），⛔ 不许把「≥」在 UI 侧剥掉——那是"扫描上限"的唯一可见证据，否则低端盒子上的数字看着精确、实际是下界。
  - 卡③「缓存清理」：6 个按钮 —— 歌词、封面（Coil）、**解包封面（新增）**、**封面地址表（新增）**、**分离产物与临时残留（原「清除全部缓存」改名+扩容）**、MV。删除类一律 `danger = true` 留给 **T12 Step 5**（本任务先用现有 `SettingActionButton`，别提前发明 `Trail`）。
  - ⛔ **不提供**删除 `baidu_index.json`、两个 Room 库、已下载歌曲、模型文件、偏好 DataStore、用户 LRC 目录的按钮：网盘索引与 `local_music.db` 删了要重扫（说明写「清除需重新扫描全部文件」并把入口指向 SOURCES 卡④），`datastore` 删了等于清空全部设置，`lyrics` 目录里是**用户自己放的文件**（§1.14 表 15），后三者已在别的 tab 或有专属入口（重复按钮会让用户以为是两回事）。
- [ ] **Step 7: 名称与说明修正**（**逐条写进 §4.5 的 CACHE 小节**，那里是文案唯一真源）：「清除全部缓存」→「清除分离缓存」，说明由「删除 HQ 模式缓存和网络歌曲缓存（约10秒）」改为「删除伴奏与人声副本，含分离临时残留」（≤26 字，且必须与 `clearCache()` + 两个临时 sweep 的真实范围一致）；「清除封面缓存」说明「清除 Coil 图片缓存器的磁盘缓存」（`settings_clear_coil_cache`，strings.xml:173）保留但**标签补「(Coil)」**，与新按钮「清除解包封面」形成可区分的一对。⚠️ 占用一览的 `descRes` **不是**功能说明（§4.5 规范里只读行本来就豁免），只给"有代价"的 **9 条**（挂在 **10 行**上：`_local_lrc` 与 `_local_lrc_ext` 两行共用 `settings_cache_dir_local_lrc_desc`，与 §4.5「CACHE ② 9 条代价说明」行同口径）—— 歌词缓存「歌词与索引同进同退」、封面地址表「清后需重新联网解析」、两行用户 LRC「用户自行放入的 LRC，不删除」、网盘索引「清除需重新扫描全部文件」、本机曲库库「删除需重新扫描本机曲库」、人脸库「人脸数据在照片墙设置里清除」、已下载歌曲「在下载设置里管理」、模型「分离模型在歌词与K歌里管理」、偏好 DataStore「删除等于重置全部设置」；无代价的行 `descRes = null`，别为凑对称写废话。
- [ ] **Step 8: 跑 G9 转绿 + 全量单测 + 编译**。`--rerun-tasks` 全量，日志进 `logs_temp/settings_t8b_test.log`，**读日志不读退出码**（`BUILD SUCCESSFUL` + 测试 XML 计数一起看）。基线按跑前重测取（v1.1 审校参考：2026-10-17 本地工件 211 个 XML / 2218 例 / 0 失败），本任务后 XML 数应 **+1**（新增 `CacheInventoryGateTest`），例数只增不减。
- [ ] **Step 9: 真机三形态走查（V18）** + **提交** `git commit -am "fix(settings): 缓存页统计口径漏掉 filesDir 与外部目录，补齐 5 处未管理的缓存并加登记表门 G9"`

---

### Task 9: 死配置清理（含备份兼容）

**Files:** Modify `data/prefs/AppPreferences.kt` / `VisualizerPrefs.kt` / `LyricsPrefs.kt` / `data/model/AppSettings.kt` / `data/model/CloudDriveConfig.kt` / `ui/viewmodel/MainViewModel.kt` / `ui/components/branches/SettingsBranch.kt` / `ui/screens/SettingsScreen.kt`；Modify `app/src/test/java/com/nasmusic/tv/data/prefs/BackupGsonTest.kt`

**Interfaces:**
- Consumes: 无（纯删除）
- Produces: `BackupData` 不再含 `weatherEnabled` / `weatherManualCity` / `weatherAutoRefresh`

- [ ] **Step 1: 写"旧备份仍能导入"的失败测试**

```kotlin
// 追加到 BackupGsonTest.kt
@Test
fun `旧备份含已删除字段时必须仍能导入且忽略它们`() {
    val legacyJson = """
        {"darkTheme":true,"animations":true,
         "weatherEnabled":true,"weatherManualCity":"北京","weatherAutoRefresh":false,
         "spectrumEnabled":true,"lyricsOffsetMs":-800}
    """.trimIndent()
    val restored = gson.fromJson(legacyJson, AppPreferences.BackupData::class.java)
    assertTrue("含未知字段的旧备份反序列化为 null", restored != null)
    assertEquals(true, restored.darkTheme)
    assertEquals(false, restored.animations)
}

@Test
fun `新导出的备份不得再包含已删除字段`() {
    val json = gson.toJson(AppPreferences.BackupData())
    for (gone in listOf("weatherEnabled", "weatherManualCity", "weatherAutoRefresh", "spectrumEnabled", "lyricsOffsetMs")) {
        assertTrue("导出 JSON 仍含被删字段 $gone —— 备份格式没跟着设置一起清", !json.contains(gone))
    }
}
```

> ⚠️ `BackupData` 的实际构造/`gson` 实例获取方式以 [BackupGsonTest.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/test/java/com/nasmusic/tv/data/prefs/BackupGsonTest.kt) 现有用例为准，Step 1 先读该文件再落笔；`spectrumEnabled` / `lyricsOffsetMs` 从未进过 `BackupData`，写进 legacy JSON 只是验证 Gson 宽容度。

- [ ] **Step 2: 跑测试确认失败点**（第二条现在必红：`weatherEnabled` 仍在 `BackupData` :1925-1927）
- [ ] **Step 3: 按 §6 删除**
  1. D1：`keySpectrumEnabled`(:323)、`setSpectrumEnabled`(:1120-1122)、`VisualizerPrefs.kt:14`。
  2. D2–D4：`keyWeatherEnabled/ManualCity/AutoRefresh`(:307-309)、三个 Flow(:1023-1052)、`BackupData` 三字段(:1925-1927)、导出(:1969-1971)、导入(:2075-2077)。
  3. D5–D7：`getBaiduCustomAppKey`/`set…`(:1879-1881)、`customSecretKey`(:1883-1884)、`getBaiduApiDriftNotified`/`set…`(:1885-1887) + `CloudDriveConfig.kt:31/33/36` 字段与 KDoc(:20)。
  4. G1：`keyLyricsOffset`(:278)、`setLyricsOffset`(:874)、`appSettings` 读(:798)、备份回写(:1999)、`LyricsPrefs.kt:12`、`MainViewModel:3062`、`AppSettings:25`、`SettingsScreen:132` 参数、`SettingsBranch:129` 传参。
- [ ] **Step 4: grep 自检**

Run: `grep -rn "spectrumEnabled\|weatherEnabled\|weatherManualCity\|weatherAutoRefresh\|customAppKey\|customSecretKey\|apiDriftNotified\|lyricsOffset" app/src` → Expected: **0 命中**（含注释；AGENTS.md 的 U14 口径：陈旧注释会误导后人）

- [ ] **Step 5: 跑 `BackupGsonTest` + 全量单测 + 编译 + lint**
- [ ] **Step 6: 提交** `git commit -am "chore(prefs): 移除频谱/天气/百度自定义凭据/歌词偏移等零消费设置项及其备份字段"`

---

### Task 10: 隐藏设置上线（歌词字号/高亮、自定义模型源）

**Files:** Modify `LyricsKaraokeSettingsSection.kt`、`SettingsScreen.kt`、`SettingsBranch.kt`、`ui/viewmodel/MainViewModel.kt`、`AppPreferences.kt`（补 setter 缺口）、`strings.xml` ×2、`migrated`/新增测试

**Interfaces:**
- Produces: `MainViewModel.updateLyricsFontScale(Float)`（播放页弹窗已在用则**直接复用**，不新建）、`MainViewModel.saveCustomModelUrl(String)` / `resetCustomModelUrl()`——三个都只在"现有链路没有同名方法"时才补，先 grep `NowPlayingBranch` 的调用点
- ⛔ **不做**：变调 / 倍速的任何设置页呈现与「重置」按钮（用户 2026-10-18 裁决，§6-13：运行时控制项，留在播放页）。

- [ ] **Step 1: 写「自定义模型源」契约的失败单测（确定性，不探端点）**

```kotlin
// app/src/test/java/com/nasmusic/tv/data/prefs/ModelDownloadUrlPrefTest.kt
package com.nasmusic.tv.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 把 `settings_model_download_url` 暴露到设置页后必须守住的三条语义（方案 §6-11）。
 *
 * 该 key 走 `customUrlProvider`（NasMusicApp:324 → ModelDownloadManager:205/:329），
 * **自定义源同样受 `EXPECTED_SHA256` 约束**：一旦 UI 侧写进一个被改坏或被截断的 URL，
 * 用户看到的症状是"下得下来但验不过"，而 SHA 校验只会报"文件不完整"——最难归因的一类。
 * 因此这里钉的是 `normalizeEndpointUrl`（:958-967）的三条边界，不是连通性。
 *
 * ⚠️ 断言走 `appSettings.modelDownloadUrl`（:807 读原始 key），
 * **不**断言 `getModelDownloadUrlSync()`（:1220，`@Volatile` 镜像）—— 镜像计时不是可断言量，
 * 接线形状已由 `ProviderMirrorWiringGateTest` G2 覆盖（`cachedModelDownloadUrl` :176）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelDownloadUrlPrefTest {

    private lateinit var prefs: AppPreferences

    @Before
    fun setup() {
        prefs = AppPreferences(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `合法 URL 必须以规范化后的原样入库`() = runBlocking {
        prefs.setModelDownloadUrl("  http://192.168.0.10:8080/demucs/htdemucs_fp16.onnx  ")
        assertEquals(
            "http://192.168.0.10:8080/demucs/htdemucs_fp16.onnx",
            prefs.appSettings.first().modelDownloadUrl,
        )
    }

    @Test
    fun `空白必须回落空串即内置默认候选`() = runBlocking {
        prefs.setModelDownloadUrl("http://nas.local:5000/a.onnx")
        prefs.setModelDownloadUrl("")
        assertEquals("", prefs.appSettings.first().modelDownloadUrl)
    }

    @Test
    fun `非法 URL 必须被拒绝并保持旧值`() = runBlocking {
        prefs.setModelDownloadUrl("http://nas.local:5000/a.onnx")
        prefs.setModelDownloadUrl("ftp://bad/only") // scheme 非 http/https → normalize 返回 null
        assertEquals("http://nas.local:5000/a.onnx", prefs.appSettings.first().modelDownloadUrl)
    }

    @Test
    fun `无点无端口非 localhost 的裸主机名必须被拒绝`() = runBlocking {
        prefs.setModelDownloadUrl("http://naslocal/a.onnx")
        assertEquals("", prefs.appSettings.first().modelDownloadUrl)
    }
}
```

> ⚠️ 最后一条的期望值取决于"初始就是空"这一事实：`setModelDownloadUrl` 在 `url.isNotBlank() && normalized == null` 时**直接 return 不写**（:1225-1228），所以未写过时读到的仍是默认 `""`。若实测发现它会写入空串之外的值，**那是实现的缺陷，按实现改测试就是错**——先回来更新本方案 §6-11 的裁决。
> ⛔ 不得为了跑绿而放宽 `normalizeEndpointUrl`（AGENTS.md：加强门禁而非放宽阈值；同「Strengthen gates, not thresholds」）。
> 若 `setModelDownloadUrl` / `getModelDownloadUrlSync` 实际挂在 `player` 门面上（`PlayerPrefs.kt`），按真实门面调用。

- [ ] **Step 2: 跑测试确认失败**
- [ ] **Step 3: 接线（新增行一律直接以 `SettingsGroupCard` 落地，否则 T2 建立的 G1 覆盖率门会红）**
  - **新增字符串**（中英各一份）：`settings_group_lyrics`（歌词显示）—— T4 有意没提前加，本任务加。⛔ 不要加 `settings_group_karaoke`（K 歌卡不进设置页，§6-13）。
  - **卡①「歌词显示」**（置于本分区最上方）：字号 4 chip（0.7 / 1.0 / 1.3 / 1.6，**复用播放页 `LyricsSettingsDialog` 的现有字符串 key**，不新造）+ 高亮 2 chip（`player_highlight_word` / `player_highlight_line`）。⚠️ chips 用 `OptionChip` 时必须给固定宽度、⛔ 不要塞进 `Row` 里让首个撑满整行（v2.35.0「音质只有一个自动」的同类缺陷，护栏 `ChipContentWidthScanTest`）。
  - **卡②「人声分离」/ 卡③「分离模型」**（T4 产物）：在模型卡内**追加**「自定义源 URL」行（值为空显示"内置默认源"；点击 → **复用 `SettingsScreen` 现有 6 个端点编辑对话框宿主之一**加一个 `when` 分支，⛔ 不要新建第 7 个 `TextInputDialog`——T16 收敛为通用 `EndpointEditor` 时这个分支要一起并进去）+「恢复默认」行。
  - 新行**当场就写 `description`**（T14 的 G6 门会扫到它们；文案见 §4.5 表：`settings_lyrics_scale_desc`、`settings_lyrics_highlight_desc`、`settings_model_custom_url_desc`）。
- [ ] **Step 4: 状态字段接线**：`LyricsKaraokeSettingsState` 追加 `lyricsFontScale: Float`（对齐 `LyricsPrefs.kt:10` 的 `Flow<Float>`，⛔ 不要写成 `Double`）、`lyricsHighlightMode: LyricsHighlightMode`、`customModelUrl: String`；`Actions` 追加 `onChangeLyricsFontScale` / `onChangeLyricsHighlightMode` / `onEditCustomModelUrl` / `onResetCustomModelUrl`。`SettingsBranch` 从 `MainViewModel` 已有的 UiState 字段喂进来（播放页弹窗用的就是同一批），缺 getter 才在 `AppPreferences` 补。⛔ 不加 `pitchSemitones` / `playbackSpeed` / `onResetPitch` / `onResetSpeed`（§6-13）。
- [ ] **Step 5: 播放页与设置页共享同一 key 的一致性检查**：设置页改字号后回到播放页应立刻生效（两处读同一条 Flow）；真机验证并把结论写进 §10.N。
- [ ] **Step 6: 测试 + 编译 + 提交** `git commit -am "feat(settings): 歌词字号/高亮、模型自定义源进入设置页"`

---

### Task 11: 组件风格统一 ①（令牌化 + 唯一委托点：§4.7 S1–S4，门禁 G7-A/B/C）

**Files:**
- Create `app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsTokens.kt`（T1 已建则在此补全行/芯片/动效令牌）
- Modify `SettingsComponents.kt`（新增 `SettingsTile` / `SettingsTileBody` / `SettingsChip`；`SettingSwitch` :86-125、`PlayModeSelector` :129-156、`SettingActionButton` :160-211、`BackupFileRow` :215-292、`AdjustButton` :355-374、`SettingsInfoRow` :378-396 改为委托）
- Modify `GeneralSettingsSection.kt`（两处内联瓦片 :74-82 / :134-142）、`NetworkMusicSection.kt`（6 处 FQN 调用 :133/:230/:273/:325/:370/:431）、`PhotoWallSettingsSection.kt`（私有 `OptionChip` :601-613 提升为共享）、`ImportedPlaylistRow.kt` :46
- Modify `app/src/test/java/com/nasmusic/tv/ui/SettingsGroupCoverageTest.kt`（追加 G7-A/B/C + 自证）、`app/src/test/java/com/nasmusic/tv/ui/ChipContentWidthScanTest.kt`（`CALL` 正则）

**Interfaces:**
- Consumes：T1 的 `SettingsGroupCard`（卡片外壳已经用 `SettingsTokens.cardShape`）、T2–T8 的全部卡片化分区
- Produces：`SettingsTokens`（§4.7-① 全文）、`SettingsTile(onClick, enabled, selected, danger, chip, modifier, content)`、`SettingsChip(label, selected, onClick, modifier)`（`modifier` **必须**给宽度，沿用原 `OptionChip` 的宽度约束）

- [x] **Step 1: 写失败的 G7-A/B/C 门禁**（追加到 `SettingsGroupCoverageTest.kt`，复用 T2 已有的 `settingsRoot()` / `stripComments()`）

```kotlin
    // ───────────────────────── G7 风格统一 ─────────────────────────

    /** 文件级豁免必须点名，且名单里的文件必须真的存在（改名即失效，见 §4.7-⑥）。 */
    private val styleOutOfFileNames = setOf(
        "SettingsTokens.kt",
        "PlaylistImportUploadDialog.kt",
        "SettingsSectionList.kt",
    )

    /** 参数名：这四行的取值只准引用 SettingsTokens，不准出现数字字面量。 */
    private val styleParamNames = listOf("shape", "focusedScale", "pressedScale", "animationDurationMs")

    /** G7-A：参数位含数字字面量 → 违规（整行标了 `Style-exempt:` 才放行）。注释先剥离。 */
    private fun literalStyleViolations(text: String): List<String> =
        stripComments(text).lines().withIndex().mapNotNull { (i, raw) ->
            val line = raw.trim()
            val param = styleParamNames.firstOrNull { p ->
                line.startsWith("$p =") || line.contains(" $p =") || line.contains(",$p =")
            } ?: return@mapNotNull null
            if (line.contains("Style-exempt") || !line.any { it.isDigit() }) return@mapNotNull null
            "${i + 1}:$param"
        }

    /** G7-B：`FocusableSurface(` 只准出现在 SettingsTile 那一处（FQN 写法同样命中）。 */
    private fun tileCallCount(text: String): Int =
        Regex("""\bFocusableSurface\(""").findAll(stripComments(text)).count()

    /** G7-C：「已选中」只准 `SettingsTile(selected = true)`，不准实底 Primary 或手调 alpha。 */
    private fun selectedStyleViolations(text: String): List<String> =
        stripComments(text).lines().withIndex().mapNotNull { (i, raw) ->
            val line = raw.trim()
            when {
                line.contains("Style-exempt") -> null
                line.contains("containerColor") && line.contains("NasMusicColors.Primary,") -> "${i + 1}"
                line.contains("containerColor") && line.contains("alpha = 0.18") -> "${i + 1}"
                else -> null
            }
        }

    @Test
    fun `设置区瓦片取值必须全部走 SettingsTokens`() {
        val root = settingsRoot()
        val files = root.listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList()
        val scoped = files.filter { it.name !in styleOutOfFileNames }
        assertTrue("G7 扫描到 0 个受检文件 —— 源目录定位失败或豁免名单吞掉了全部文件", scoped.isNotEmpty())
        for (name in styleOutOfFileNames) {
            assertTrue("文件级豁免 $name 已不存在（改名后该文件会静默脱离检查）",
                files.any { it.name == name })
        }
        val hits = scoped.flatMap { f -> literalStyleViolations(f.readText()).map { "${f.name}:$it" } }
        assertTrue("参数位仍有字面量（必须改成 SettingsTokens.*）：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun `设置区分区不得手写 FocusableSurface 参数块`() {
        val root = settingsRoot()
        val scoped = root.listFiles { f ->
            f.name.endsWith("Section.kt") || f.name == "ImportedPlaylistRow.kt"
        }.orEmpty().toList()
        assertTrue("G7-B 扫描到 0 个分区文件", scoped.isNotEmpty())
        val hits = scoped.mapNotNull { f ->
            tileCallCount(f.readText()).takeIf { it > 0 }?.let { "${f.name}($it 处)" }
        }
        val shared = File(root, "SettingsComponents.kt")
        val sharedCount = if (shared.exists()) tileCallCount(shared.readText()) else 0
        assertTrue(
            "分区里还有裸瓦片：${hits}；SettingsComponents 内 FocusableSurface( 出现 $sharedCount 次（只准 SettingsTile 那 1 处）",
            hits.isEmpty() && sharedCount <= 1,
        )
    }

    @Test
    fun `已选中态只准一种表达`() {
        val root = settingsRoot()
        val scoped = root.walkTopDown().filter { it.isFile && (it.name.endsWith("Section.kt") || it.name == "ImportedPlaylistRow.kt") }.toList()
        val hits = scoped.flatMap { f -> selectedStyleViolations(f.readText()).map { "${f.name}:$it" } }
        assertTrue("出现实底 Primary / 手调 0.18 选中态，必须改用 SettingsTile(selected = true)：$hits", hits.isEmpty())
    }

    /** 负向自证：三条判据各自必须能咬人（缺任一，"全绿"可能只是空转）。 */
    @Test
    fun `G7 三条负向自证`() {
        val a = "FocusableSurface(\n    shape = RoundedCornerShape(12.dp),\n    focusedScale = if (enabled) 1.03f else 1f,\n    animationDurationMs = 250,\n)"
        assertTrue("G7-A 空转：字面量圆角/缩放/时长没被检出",
            literalStyleViolations(a).size >= 3)
        assertTrue("G7-A 误伤：令牌写法被判负",
            literalStyleViolations("    focusedScale = if (chip) SettingsTokens.focusScaleChip else SettingsTokens.focusScaleRow,").isEmpty())
        assertTrue("G7-B 空转：裸瓦片没被检出", tileCallCount(a) == 2)
        assertTrue("G7-B 空转：FQN 写法漏检", tileCallCount("    com.nasmusic.tv.ui.components.FocusableSurface(") == 1)
        val c = "    containerColor = if (selected) NasMusicColors.Primary else NasMusicColors.Surface,\n" +
            "    containerColor = NasMusicColors.Primary.copy(alpha = 0.18f),"
        assertTrue("G7-C 空转：实底/手调选中态没被检出", selectedStyleViolations(c).size == 2)
        assertTrue("G7-C 误伤：SettingsTile 委托写法被判负",
            selectedStyleViolations("    SettingsTile(onClick = {}, selected = true) { }").isEmpty())
    }
```

> ⚠️ **G7-A 的判据是「参数名行内不得有数字」，不是「不得出现 `RoundedCornerShape(`」**。原因见上面负向样本第 2 行：`SettingActionButton:187` 的真面目是 `focusedScale = if (enabled) 1.03f else 1f` —— 条件表达式里藏字面量，只匹配 `= 1.` 会整类漏掉。代价是 `focusedScale = if (enabled) SettingsTokens.focusScaleRow else 1f` 也会被咬，所以令牌里必须备 `focusScaleDisabled = 1f`（§4.7-① 已加），这是**有意的**：置灰不放大这件事也该是唯一取值。
> ⚠️ **令牌化不许夺走其它门的抓手**：`SmallTouchTargetScanTest` 靠 `(\d+(\.\d+)?)\.dp` 字面量量触控目标，`portraitTouchTarget(48.dp)`（SettingsComponents:359）**保持原样**；G7-A 只检四个参数名，正是不想把审计面从"取值"扩到"尺寸"。

- [x] **Step 2: 跑门确认判红，并把红名单留档**

Run: `./gradlew.bat testDebugUnitTest --tests "*SettingsGroupCoverageTest" --no-daemon -Pkotlin.compiler.execution.strategy=in-process`
Expected: 三条 FAIL，违规清单应接近 §2-10 的计数（**15** 处 `shape = ` 字面量 + 其余三个参数名字面量行、**20** 处裸 `FocusableSurface(`、**4** 处实底/手调选中态 —— G7-C 作用域内口径，`SettingsComponents:145-146` 那处不在 `*Section.kt` 扫描范围故不计入）。⛔ 红名单比这少 → 先修扫描，别开始迁移（扫描漏一类就等于那类永远没人管）。把清单贴进 `docs/technical-overview.md` 的新 §10.N，作为迁移前后的对账基线。

> ✅ **实做（2026-10-19）**：红名单由 `logs_temp/t11_redlist.py`（与单测同一份谓词）干跑产出并留档在 §10.250 ——
> **G7-A 69 处 / 14 个受检文件、G7-B 5 文件 11 处 + `SettingsComponents` 7 处、G7-C 3 处**。
> 与上面"15 / 20 / 4"的估算口径不同（估算把 FQN 写法、`SettingsComponents` 内、豁免文件混在不同列），
> 以 §10.250 的表为准。⛔ 断言里写不变式（`== 0`），别把任何一版数字抄进去。

- [x] **Step 3: 落地 `SettingsTokens.kt`**（§4.7-① 全文，含 `focusScaleDisabled` 与开关轨五项）

- [x] **Step 4: 加 `SettingsTile` / `SettingsTileBody`（§4.7-②），把 5 个行组件改为委托**
  - ⛔ **公开签名不变**（`SettingSwitch(label, description, checked, onClick, enabled)` 等），调用点零改动 —— 本任务只做"取值收口"，改语义（尾部文案）在 T12。
  - `SettingActionButton` 的 `modifier` 默认值**保持** `Modifier.fillMaxWidth()`，且 :164-172 的「0 宽陷阱」KDoc **一行都不许删**（`ChipContentWidthScanTest` 的注释里就引用了它）。
  - `BackupFileRow` 的主瓦片 + 删除瓦片 → `SettingsTile`（删除那颗继续 `danger = true`，它是现状里唯一已经用 `Warning` 的，:276-279）；`size(64.dp)` 保留。
  - `AdjustButton` 的 `focusedScale = 1.1f` → `SettingsTokens.focusScaleChip`（1.06）：**这是可感知的改动**，± 按钮放大幅度变小，Step 8 真机专验（V14）。
  - ✅ **实做**：`danger` 在 `SettingsTile` 里**单列一支**（焦点底 `Warning@0.18`、焦点字 `Warning`）——
    §4.7-② 的片段会让 danger 落进 `else` 拿到 `Primary@0.15` 焦点底，与 S6「焦点底也取 Warning」冲突，以 S6 为准。

- [x] **Step 5: 迁移分区内联瓦片**
  - `GeneralSettingsSection` 语言 :74-82 / 方向 :134-142 → `SettingsChip`（选中态从 `Primary@0.18` 变为 `SettingsTile(selected = true)`，视觉等价）。
  - `NetworkMusicSection` 6 处（:133/:230/:273/:325/:370/:431，含 `focusedScale = 1.02f` 那一族）→ `SettingsTile`。**注意 `:369 NetworkTestCard` 与 `:424 PresetEndpointRow` 是私有 composable，迁移点在函数体内**，不改它们的签名。
  - `PhotoWallSettingsSection` 的私有 `OptionChip`（:601-613）**提升**为 `SettingsComponents.kt` 的 `SettingsChip`，三处调用点（:420/:425/:587）改引用；⚠️ 提升后 `modifier` 仍需给固定宽度（v2.37.0「画面适配只显示满屏」的护栏就是这个宽度）。
  - `ImportedPlaylistRow:46` → `SettingsTile`。
  - `AboutSection` 的 GitHub 链接瓦片（T8 Step 4 落地的 `FocusableSurface`）→ `SettingsTile`，至此 G7-B 的"分区 0 裸瓦片"才可能真的归零。
  - ✅ **实做补充**：`NetworkTestCard` 原来的 `containerColor = SurfaceVariant` + 1dp `Border` 描边一起表达
    「这是一张卡」。描边本来就画不出来（`FocusableSurface` 的 `background` 排在入参 modifier 之后，整条盖住），
    收口后容器色回到 `Surface` ⇒ 描边一并删除，视觉等价。

- [x] **Step 6: 统一「标签 + 值」版式与说明字号（§4.7-④）**：`SettingsInfoRow` 内部换成 `AboutRow` 的 `1 : 1.5` 权重双栏（值 `TextAlign.End`，长值换行不再挤掉标签）；`SettingSwitch`/`SettingActionButton` 的 `description` 由 `FontSize.body()` + `LocalFocusableContentColor` 改为 `FontSize.small()` + `NasMusicColors.TextSecondary`（与 GENERAL 的 :60/:120 对齐，也与 §4.5 的 `SettingsHint` 同规格）。
  - ✅ **实做**：两者共用新抽出的 `SettingsLabelValueRow`；`SettingsInfoRow` 水平边距同时收口到 `rowHPadding`
    （S2 点名了它的 0 水平边距），标签不再比上面的开关行凸出 20dp。

- [x] **Step 7: 把 `ChipContentWidthScanTest` 的抓手一起改**：`CALL` 正则（:255）由 `\bFocusableSurface\(` 扩为 `\b(FocusableSurface|SettingsTile|SettingsChip)\(` —— 迁移后 0 宽陷阱的载体换了名字，不扩就变成"扫不到调用点 → 空转断言仍绿但实际失配"。扩完确认该测试自己的 `callSites > 0` 断言仍成立。
  - ✅ **实做**：另补 `设置区新瓦片名字同样必须被判违规` 一条，给 `SettingsTile` / `SettingsChip` 各一个破坏样本。

- [ ] **Step 8: 全量验证 + 真机抽查 + 提交**

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```
Expected: G7-A/B/C 转绿，`ChipContentWidthScanTest` / `SmallTouchTargetScanTest` / `FocusIndicatorContractTest` 仍绿（这三个是本任务的回归面）。真机 V14 见 §8。

```bash
git commit -m "refactor(settings): 设置区尺寸动效令牌化，行组件统一委托 SettingsTile"
```

---

### Task 12: 组件风格统一 ②（尾部语义 + 开关轨 + 危险动作：S5/S6、§5.2，门禁 G7-D）

> T11 收的是"取值"，本任务收的是"这一行让我干什么"。两者故意分开：T11 的 diff 应当**看不出任何文案/语义变化**，本任务的 diff 全是语义变化，真机验收只看这里。

**Files:** Modify `SettingsComponents.kt`（`SettingSwitch` 尾部、`SettingActionButton` 新增 `trailing`）、`values/strings.xml` + `values-en/strings.xml`（加 `settings_on`/`settings_off`，删 `settings_wifi_on`/`settings_wifi_off`）、`DownloadSettingsSection.kt`、`CacheSettingsSection.kt`、`ServerSettingsSection.kt`、`SettingsGroupCoverageTest.kt`

**Interfaces:**
- Consumes：T11 的 `SettingsTile(danger = …)`
- Produces：`SettingActionButton(label, description, onClick, modifier, enabled, trailing: Trail = Trail.None)`、`enum class Trail { None, Arrow }`、`SwitchTrack`（私有）

- [x] **Step 1: 写失败的 G7-D + 危险动作门禁**

```kotlin
    /** G7-D：开关态不得再寄生 WiFi 字符串（实测全树只有 SettingsComponents:119 一个消费方）。 */
    @Test
    fun `设置区不得复用 WiFi 开关文案`() {
        // 口径同 settingsRoot()：user.dir 变体多，逐个候选，⛔ 不写绝对路径
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val root = candidates.firstOrNull { it.isDirectory }
        assertTrue("找不到 app/src/main/java 源码目录（user.dir=${File("").absolutePath}）", root != null)
        val hits = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> stripComments(f.readText()).lines().withIndex()
                .filter { it.value.contains("settings_wifi_on") || it.value.contains("settings_wifi_off") }
                .map { "${f.name}:${it.index + 1}" } }
            .toList()
        assertTrue("仍引用 settings_wifi_on/off：$hits（开关态必须用 settings_on/off）", hits.isEmpty())
    }

    /** 负向自证：样例文本必须被检出。 */
    @Test
    fun `G7-D 负向自证`() {
        assertTrue("G7-D 空转",
            stripComments("text = stringResource(R.string.settings_wifi_on)").contains("settings_wifi_on"))
    }
```

- [x] **Step 2: 加四个 key**（`values/strings.xml` 与 `values-en/strings.xml` 各一份）：滑块内可见短标签 `settings_on` = 「开」/ ON、`settings_off` = 「关」/ OFF；朗读用整词 `settings_state_on` = 「已开启」/ On、`settings_state_off` = 「已关闭」/ Off。⚠️ 现存 `settings_wifi_on` 的值是 `✓ 开启`、`settings_wifi_off` 是 `␣␣␣␣关闭`（前导空格是对齐 hack，strings.xml:187-188 / values-en:184-185）—— 新 key **不带**符号与对齐空格。短标签与朗读词分开，是因为「开」放进滑块好看、放进 `contentDescription` 读出来不成词。
  - ✅ **实做**：4 个 key 中英齐备；另加一条门禁 `WiFi 开关字符串必须从两份 strings 删除` 钉住「只加一边不会编译报错、只会在运行期 `getString` 抛 `IllegalArgumentException`」这个坑。
- [x] **Step 3: 自绘 `SwitchTrack`（§5.2 的实测几何）**，`SettingSwitch` 尾部改用它：

```kotlin
/**
 * 开关轨（§5.2，用户 2026-10-18 三选一选定的中间款）。尺寸全部来自 SettingsTokens，
 * 令牌值由 `scripts/measure_switch_reference.py` 量参考图得到，⛔ 不要就地改数字。
 *
 * ⛔ 轨道不填充 —— 参考图内部取样就是页面渐变背景本身。
 * ⛔ 不套 portraitTouchTarget —— 整行瓦片才是热区，这条 36dp 高不是触摸目标。
 */
@Composable
private fun SwitchTrack(checked: Boolean) {
    val onText = stringResource(R.string.settings_on)
    val offText = stringResource(R.string.settings_off)
    Box(
        modifier = Modifier
            .width(SettingsTokens.switchTrackWidth)
            .height(SettingsTokens.switchTrackHeight)
            .border(
                SettingsTokens.switchStroke,
                NasMusicColors.TextPrimary.copy(alpha = 0.8f),
                SettingsTokens.switchTrackShape,
            )
            // 内缩 = 滑块离轨道边的距离，扣掉描边占位
            .padding(SettingsTokens.switchThumbInset - SettingsTokens.switchStroke)
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
                .width(SettingsTokens.switchThumbWidth)   // 两态恒定，⛔ 不随文案变宽
                .height(SettingsTokens.switchThumbHeight)
                .background(NasMusicColors.TextPrimary, SettingsTokens.switchThumbShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (checked) onText else offText,
                color = NasMusicColors.Background,        // 参考图：深色字取页面背景色，不是纯黑
                fontSize = FontSize.caption(),
            )
        }
    }
}
```

  令牌里补上派生值（注释写清算式，⛔ 不要在组件里现算）：`val switchThumbTravel = 58.dp`（= 内宽 116 − 滑块 58）。
  ⚠️ 动画**不读** `animationsEnabled` —— 现状 `FocusableSurface` 的 `animationDurationMs` 同样不读它；在这里单独加判断会造出"只有开关尊重设置"的不一致，属范围膨胀。
  `SettingSwitch` 尾部改成只有 `SwitchTrack(checked)`、不再挂任何文字，整行加 `contentDescription = "$label，${stringResource(if (checked) R.string.settings_state_on else R.string.settings_state_off)}"`（`FocusableSurface` 支持 `modifier.semantics`，或直接用其 `contentDescription` 形参 —— 以现有 `FocusableSurface` 签名为准，T12 Step 3 落地时先读一遍）。
  - ✅ **实做**：`FocusableSurface` **没有** `contentDescription` 形参 ⇒ 走 `Modifier.semantics { contentDescription = … }`。
    ⚠️ 方案片段里 `.padding(switchThumbInset - switchStroke)` **不采用** —— 那会把「描边」与「内缩」混成一个量，
    日后把 `switchStroke` 调成 2dp 会静默吃掉 2dp 行程且无处报错。实现取 `.padding(switchThumbInset)`，
    内宽 116 / 内高 24 / 行程 58 与几何表逐项一致且与描边解耦（详见 `technical-overview` §10.251）。
    另加 `enabled` 形参（整行置灰时轨道同步 `alpha(0.4f)`）。

- [x] **Step 4: 尾部按动作种类分（S5）**：`SettingActionButton` 加 `trailing: Trail = Trail.None`，删掉固定的 `stringResource(R.string.common_confirm)`（:204-208）。
  - 「进入下一级」的入口按钮 → `Trail.Arrow`（`Text("›", …)`）：服务器配置、均衡器、百度登录/根目录/MV 目录、下载位置、缓存目录、导出/导入备份、扫码传输。
  - "就地执行"的动作按钮 → `Trail.None`：重扫本地音乐、重建索引、清除缓存、清除全部下载、下载/删除模型。
  - ⛔ `BackupFileRow` 的「导入」尾部（:262）保持文字 —— 那一行右侧文字是"这颗按钮干什么"的唯一标识，去掉后只剩文件名，属于**可读性退化**（不是漂移）。
  - ✅ **实做**：`Trail.Arrow` 实际接线 **15 处**（Data 4 / Download 1 / LyricsKaraoke 3 / PhotoWall 2 / Player 1 / Sources 4）。
    ⚠️ **「下载位置」「缓存目录」两处按现状不加 `Arrow`** —— 下载位置已变成卡②里的两枚 `AdjustButton`（内 / 外接存储，就地切），
    缓存目录已被 T8b 并进占用一览成为只读 `SettingsInfoRow`；两者都没有「进下一级」这个动作，给只读行挂 `›` 反而误导。
- [x] **Step 5: 危险动作上色（S6）**：给四处加 `danger = true` —— `DownloadSettingsSection:135-141`（清除全部下载）、`CacheSettingsSection` 的清除全部缓存、`ServerSettingsSection:53`（断开连接）、`BackupFileRow` 删除（已在 T11 带 `danger = true`）。同时 `CacheSettingsSection` 那条的说明写「不会删除已下载的音乐」（§4.5 表）。
  - ✅ **实做**：4 处落地 —— `DownloadSettingsSection:145`、`CacheSettingsSection:160`（现名「清除分离缓存」）、
    `SourcesSettingsSection:129`（`ServerSettingsSection` 已在 T3 搬进 `SourcesSettingsSection.kt`）、`SettingsComponents:460`。
    ⚠️ 缓存页另有 5 个清除按钮**故意不上色**（歌词 / 封面 Coil / 解包封面 / 封面地址表 / MV 都是秒级可重建），
    整页刷 Warning 会让警告本身失效。
    ⚠️ 本步附注的「说明写『不会删除已下载的音乐』」**不再适用**：§4.5 CACHE ③ 已把该按钮改名「清除分离缓存」、
    说明定为「删除伴奏与人声副本，含分离临时残留」（≤26 字上限），T8b 已按 §4.5 落地；§4.5 是文案唯一真源，以它为准。
- [x] **Step 6: 删旧 key**：`settings_wifi_on` / `settings_wifi_off` 在代码里已 0 引用（Step 1 的门 + `grep -rn "settings_wifi_" app/src` 双证），四处定义（两份 strings × 2）一并删除。⛔ 留"暂时不用"的死 key 就是 §6 里 D5–D7 那类债的重演。
  - ✅ **实做**：两份 strings 各删 2 条、各加 4 条；`grep -rn "settings_wifi_" app/src` 归零（含 XML）。
- [ ] **Step 7: 跑 G7-D + 全量单测 + `LanguageMirrorTest`（中英 key 对称门）+ 编译 + 提交**
  - ⏳ **待用户编译验证**（与 T11 Step 8 同口径：编译由用户执行，验证命令见交付说明）。

```bash
git commit -m "feat(settings): 开关改分段式开关轨、按钮尾部按动作种类区分、危险动作统一 Warning 色"
```

---

### Task 13: 横竖屏三形态适配（§4.8 A1–A5，门禁 G8）

**Files:**
- Create `app/src/test/java/com/nasmusic/tv/ui/SettingsRowWidthScanTest.kt`（G8，本任务唯一的门）
- Modify `app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsComponents.kt`（新增 `SettingsChipRow`；`SettingsInfoRow` :378-396 按 A4 收口）
- Modify `app/src/main/java/com/nasmusic/tv/ui/screens/settings/PlayerSettingsSection.kt`（:112-123 时长档、:318-336 与 :352-370 模型双按钮）
- Modify `docs/technical-overview.md` §10.N（记 0 宽塌陷缺陷的真机取证与三形态走查结论）

**Interfaces:**
- Consumes：T11 的 `SettingsTile(chip = …)` / `SettingsChip(label, selected, onClick)` / `SettingsTokens.chipGap` / `chipMinWidth`；T12 的 `Trail`（入口尾部只剩 16dp，A4 的"尾部不参与挤压"才有意义）
- Produces：`SettingsChipRow(items, tv, phonePortrait, medium, equalWidth, modifier, itemContent)`（§4.8 全文）—— **T14 之后所有"一行多枚带说明的瓦片"都必须走它**，`G8` 就是靠这个原语才有的兜法

> ⚠️ 本任务**含一个线上既有缺陷的修复**（时长档只有「2s」可见，§1 第 4 行 / §2-11 / §4.8 ①），不是改版回归。
> 所以 Step 1 先取证再动代码，提交信息也单列这条 —— 出问题时好回滚、好解释。

- [x] **Step 1: 真机取证（改代码之前）**：用**当前 main 的 APK**（未含 T11–T13）装到手机，竖屏 → 设置 → 播放设置 → 「淡入淡出时长」那一行，截图存 `output/`。
  预期画面只有一个「2s」按钮占满整行，4/6/8/12s **不见了**（不报错、不警告）。
  > ⚠️ **实际执行方式已调整（2026-10-19）**：本轮改代码时**没有**在改动前先装旧包取证（AI 不自行上机，
  > 且改动已落盘）。改为**静态红名单取证**，结论等价且更可复现：把 `HEAD` 版设置区 14 个文件导出到临时
  > 目录，跑 `logs_temp/t13_redlist.py --root <临时目录>` ⇒ **8 处违规 / 3 个文件 / 29 个 `Row` 内容块**
  > （`PlayerSettingsSection.kt:98`、`LyricsKaraokeSettingsSection.kt:205/212/241/248/277/286`、
  > `ImportedPlaylistRow.kt:44`），修复后 **0 处 / 24 个**。真机「只有 2s 可见」的观感取证并入 V16 ①
  > 一并复核（同一台机、同一份 APK，改前改后各一张，见 §8 V16）。
  ```bash
  JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
    ./gradlew.bat assembleDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process
  adb connect <手机>:5555
  adb -s <手机>:5555 install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s <手机>:5555 shell am start -n com.nasmusic.tv/.ui.MainActivity
  adb -s <手机>:5555 exec-out screencap -p > output/settings-crossfade-0width.png
  ```
  截图结论写进 §10.N（"5 档只渲染出 1 档"），并留一张 TV 端同页截图做 B1 对照（TV 上 5 档全在）。

- [x] **Step 2: 写失败的 G8 门禁**（✅ 实做：10 例 —— 1 条真实扫描 + 6 条自证；比方案片段多 3 条，
  且**空转断言已改**：方案里的 `checkedCalls > 0` 是不可满足断言，改成常量探针 `LIVENESS_PROBE`，
  理由见 §10.252）

```kotlin
package com.nasmusic.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「设置分区里放进 `Row` 的**整行瓦片**必须显式给定宽度」的门禁（方案 §4.8，G8）。
 *
 * ## 为什么需要它
 * `Row` 给每个 wrap-content 子项的 `maxWidth` 是**本行剩余宽度**。默认 `fillMaxWidth()`
 * 的设置组件放进 `Row` 后，第一个吃满整行、后续兄弟被挤成 **0 宽** —— 表现为"选项不见了"
 * （v2.35.0 音质档、v2.37.0 画面适配、以及**当前 main 上仍存在的** v2.38.x 淡入淡出时长档）。
 * 竖屏内容宽只有 TV 的 26%（§4.8 ②），同一段代码在手机上还会把芯片压成高块、互相参差。
 *
 * ## 为什么必须新增这一层
 * 已有的 [ChipContentWidthScanTest] 守的是 `FocusableSurface` 那一层（"内容别用 fillMaxSize"）。
 * 时长档那个调用点里**连 `FocusableSurface` 都没出现**（包在 `SettingActionButton` 内）——
 * 抓手差了一层组件，所以三代都没拦住。G8 守**上一层：组件调用方**。两层缺一不可。
 *
 * ## 判定规则（命中需同时满足）
 * ① 某处 `Row` 调用带**内容块** —— 两种写法都要认：`Row(…) { … }` 与 `Row { … }`
 *    （后者没有左括号，只按 `Row(` 找会漏掉一批手写用例）；
 * ② 该内容块内出现"默认整行宽"的设置组件调用（`SettingActionButton(` / `SettingSwitch(`，
 *    或**不带** `chip = true` 的 `SettingsTile(`）；
 * ③ 该组件调用的**实参区**（括号配对，不含尾随 lambda）不含任何"已给宽度"的修饰符
 *    （`.width(` / `widthIn(` / `weight(` / `.size(`）；
 *    ⚠️ `fillMaxWidth(` **不在放行名单里** —— `Row` 里第一枚 `fillMaxWidth()` 正是本门的病灶，
 *    写它等于承认"这一枚要独占整行"，那就该从 `Column` 直接调用，不要包 `Row`；
 * ④ 该调用所在行（或向前 3 行内）没有豁免标记 `RowWidth-exempt: <理由>`；
 * ⑤ 注释（`//`、`/* … */`）先剥离 —— 注释里举例的写法不算违规。
 *
 * ⛔ **判据必须落在"组件调用"上，不能落在"Row 里有 ≥2 个子项"上**：
 * 时长档那行字面上只有 1 个调用点（`durations.forEach { … }` 生成 5 个子项），
 * 按子项数判会**静默空转** —— 报"0 处违规"却什么都没查。
 *
 * ## 作用域
 * `ui/screens/SettingsScreen.kt` + `ui/screens/settings/*Section.kt` +
 * `ui/screens/settings/ImportedPlaylistRow.kt`。
 * ⛔ 不含 `SettingsComponents.kt` / `SettingsTokens.kt`：那是组件**定义**，
 * 整行瓦片的默认 `fillMaxWidth()` 与 `SettingsChipRow` 内部唯一那处 `Box(weight(1f))`
 * 都属实现细节（A3），放进作用域会把正解本身判成违规。
 */
class SettingsRowWidthScanTest {

    @Test
    fun `Row 内的整行设置瓦片必须显式给定宽度或改走 SettingsChipRow`() {
        val root = mainSourceRoot()
        var files = 0
        var rowBlocks = 0
        var checkedCalls = 0
        val violations = mutableListOf<String>()

        scopedFiles(root).forEach { file ->
            files++
            val raw = file.readText()
            val clean = stripComments(raw)
            val scan = scanRowWidth(clean, raw)
            rowBlocks += scan.rowBlocks
            checkedCalls += scan.checkedCalls
            val rel = file.relativeTo(root).path.replace('\\', '/')
            scan.violations.forEach { violations += "$rel:$it" }
        }

        assertTrue(
            "作用域内没有扫到任何源码文件 —— 目录或文件名规则失配：${root.absolutePath}",
            files > 0,
        )
        // ⚠️ 两条空转自证缺一不可：Row 块数 > 0 只证明"扫到了容器"，
        //    被检查的调用点数 > 0 才证明"扫到了组件"。
        assertTrue(
            "作用域内没有扫到任何带内容块的 `Row(` —— 扫描逻辑失配（空转），结果不可信",
            rowBlocks > 0,
        )
        assertTrue(
            "作用域内没有扫到任何被检查的组件调用点 —— 扫描逻辑失配（空转），结果不可信",
            checkedCalls > 0,
        )

        if (violations.isNotEmpty()) {
            fail(
                "以下设置组件被放进 `Row` 却没有给定宽度：\n" +
                    violations.joinToString("\n") { "  • $it" } +
                    "\n\n症状：TV 上第一个吃满整行、后续兄弟被挤成 **0 宽**（选项不见了）；" +
                    "竖屏（内容宽只有 TV 的 26%）芯片被压成高块且互相参差。\n" +
                    "修法：一行多枚 → `SettingsChipRow(items, tv = …, phonePortrait = …) { … }`（§4.8）；" +
                    "整行一枚 → 直接从 `Column` 调用，不要再包 `Row`。\n" +
                    "宽度确实由调用方/变量决定、静态判不出来的，在调用上方加 " +
                    "`// $EXEMPT_MARKER: <理由>`。",
            )
        }
    }

    // ─────────────────── 自证：护栏本身必须有效 ───────────────────

    /** ① 负向用例：`PlayerSettingsSection:112-123` 时长档**修复前的原文**必须被判违规。 */
    @Test
    fun `时长档原始写法必须被判违规`() {
        val src = """
            if (state.crossfadeEnabled) {
                val durations = listOf(2, 4, 6, 8, 12)
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                ) {
                    durations.forEach { sec ->
                        SettingActionButton(
                            label = "${'$'}{sec}s",
                            description = "",
                            onClick = { actions.onChangeCrossfadeDuration(sec) }
                        )
                    }
                }
            }
        """.trimIndent()
        val scan = scanRowWidth(stripComments(src), src)
        assertTrue("护栏失效：时长档（Row + forEach + 无宽度的 SettingActionButton）没被判违规",
            scan.violations.isNotEmpty())
    }

    /** ② 压成一行、只有 1 个整行瓦片的 `Row` 同样必须判违规（配对逻辑最容易漏这类）。 */
    @Test
    fun `单行 Row 包一个整行瓦片必须被判违规`() {
        val src = """Row { SettingActionButton(label = "a", description = "", onClick = {}) }"""
        val scan = scanRowWidth(stripComments(src), src)
        assertTrue("护栏失效：单行写法没被判违规", scan.violations.isNotEmpty())
    }

    /** ③ 误报防线 A：调用点自己给了宽度 → 放行。 */
    @Test
    fun `调用点已显式给定宽度必须放行`() {
        val src = """
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingActionButton(
                    label = "a",
                    description = "",
                    onClick = {},
                    modifier = Modifier.width(120.dp)
                )
            }
        """.trimIndent()
        assertTrue("误报：已给 .width() 的调用点被判违规",
            scanRowWidth(stripComments(src), src).violations.isEmpty())
    }

    /** ③ 误报防线 B：芯片（`chip = true`）本来就按内容 wrap → 放行。 */
    @Test
    fun `芯片必须放行`() {
        val src = """
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsTile(onClick = {}, chip = true) { Text("2s") }
            }
        """.trimIndent()
        assertTrue("误报：chip = true 的瓦片被判违规",
            scanRowWidth(stripComments(src), src).violations.isEmpty())
    }

    /** ③ 误报防线 C：注释里举例的写法不得被误判（注释剥离生效）。 */
    @Test
    fun `注释里举例的写法不得被误判`() {
        val src = """
            // 反例：Row { SettingActionButton(label = "a", description = "", onClick = {}) }
            val x = 1
        """.trimIndent()
        val scan = scanRowWidth(stripComments(src), src)
        assertTrue("误报：注释里的举例被判违规（注释剥离失效）", scan.violations.isEmpty())
        // ⚠️ 这条 fixture 剥离注释后**不含任何代码**，所以它只证"注释剥离生效"，
        //    不能拿它当空转证据 —— 空转防线在真实扫描的两条计数器（rowBlocks / checkedCalls > 0）上。
        assertEquals("注释剥离后不应残留可扫描的 Row 块", 0, scan.rowBlocks)
    }

    /** ④ 豁免标记必须真的能放行（否则标记形同虚设）。 */
    @Test
    fun `豁免标记必须生效`() {
        val src = """
            // $EXEMPT_MARKER: 该行始终只有这一枚整行瓦片，宽度由 Column 决定
            Row { SettingActionButton(label = "a", description = "", onClick = {}) }
        """.trimIndent()
        assertTrue("豁免标记失效：加了标记仍被判违规",
            scanRowWidth(stripComments(src), src).violations.isEmpty())
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /**
     * 定位 main 源码根目录。AGP 单测的 `user.dir` 是**模块目录**（`…/app`），同时尝试仓库根。
     * 定位失败直接失败而不是跳过 —— 否则这道门被静默禁用。
     * （每个扫描门各自持一份私有顶层 helper，是本项目既有范式，见 [ChipContentWidthScanTest]）
     */
    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertNotNull(
            "找不到源码目录（user.dir=${File("").absolutePath}）；已尝试：${candidates.joinToString { it.absolutePath }}",
            found,
        )
        return found!!
    }

    /** G8 作用域：设置页宿主 + 分区 + 导入歌单行；⛔ 不含组件定义文件（见类注释）。 */
    private fun scopedFiles(root: File): List<File> {
        val screens = File(root, "ui/screens")
        val settings = File(screens, "settings")
        val sections = settings.listFiles { f -> f.name.endsWith("Section.kt") }.orEmpty().toList()
        val extra = listOf(
            File(screens, "SettingsScreen.kt"),
            File(settings, "ImportedPlaylistRow.kt"),
        ).filter { it.isFile }
        val all = (sections + extra).distinctBy { it.absolutePath }
        assertTrue("G8 作用域为空：${settings.absolutePath}", all.isNotEmpty())
        return all.sortedBy { it.name }
    }
}

/** 豁免标记：写在调用行或其上方 3 行内，后跟理由 */
private const val EXEMPT_MARKER = "RowWidth-exempt"

/**
 * 容器标识：只认 `Row` 这个词。
 * `\b` 两端各管一件事 —— 左边让 FQN 写法 `androidx.compose.foundation.layout.Row(` 也命中（`.` 是非词字符），
 * 右边把 `RowScope` / `SettingsChipRow` 排除掉。后面的 `(` / `{` 由扫描器再判。
 */
private val ROW_CALL = Regex("""\bRow\b""")

/** 默认"整行宽"的设置组件；T11 委托后 `SettingsTile` 只有 `chip = true` 才是芯片 */
private val COMPONENT_CALL = Regex("""\b(SettingActionButton|SettingSwitch|SettingsTile)\(""")

/**
 * 出现任一即说明宽度已被显式给定 → 不算违规。
 * ⚠️ 与 [ChipContentWidthScanTest] 的同类名单**故意不同**：这里**不把 `fillMaxWidth(` 当放行条件** ——
 * `Row` 里第一枚 `fillMaxWidth()` 正是本门的病灶（后续兄弟被挤成 0 宽），
 * 调用方写 `modifier = Modifier.fillMaxWidth()` 不叫"给了宽度"，叫"整行一枚请改回 `Column` 直接调用"。
 */
private val WIDTH_GIVEN = listOf(".width(", "widthIn(", "weight(", ".size(")

private class RowScan(
    val violations: List<Int>,
    val rowBlocks: Int,
    val checkedCalls: Int,
)

/**
 * **纯函数**：在已剥离注释的源码上跑 G8。
 *
 * @param clean 已剥离注释的源码（长度与换行位置与原文一致 → 行号可直接用）
 * @param raw   原始源码（查豁免标记 —— 标记本身写在注释里）
 *
 * 嵌套 `Row` 会让内层组件被内外两层各查一次，`violations` 因此可能重复；
 * 这里**故意不去重**：重复行号只是报错里多一行，而去重会掩盖"到底扫到了几处"。
 */
private fun scanRowWidth(clean: String, raw: String): RowScan {
    val hits = mutableListOf<Int>()
    val rawLines = raw.lines()
    var rows = 0
    var checked = 0

    for (m in ROW_CALL.findAll(clean)) {
        // 找 `Row` 后面的第一个非空白字符：`(` = 带实参的写法，`{` = 只有尾随 lambda 的写法
        var p = m.range.last + 1
        while (p < clean.length && clean[p].isWhitespace()) p++
        val j = when {
            p >= clean.length -> continue
            clean[p] == '(' -> {
                val close = matchDelimiter(clean, p, '(', ')')
                if (close < 0) continue
                var q = close + 1
                while (q < clean.length && clean[q].isWhitespace()) q++
                // `Row(modifier = …)` 没有内容块 → 跳过（不是容器使用错误，只是本门管不着）
                if (q >= clean.length || clean[q] != '{') continue
                q
            }
            clean[p] == '{' -> p
            else -> continue                       // `Row::class` / 变量名等同形词，忽略
        }
        val bodyEnd = matchDelimiter(clean, j, '{', '}')
        if (bodyEnd < 0) continue
        rows++

        val body = clean.substring(j + 1, bodyEnd)
        for (c in COMPONENT_CALL.findAll(body)) {
            val aOpen = clean.indexOf('(', j + 1 + c.range.first)
            if (aOpen < 0) continue
            val aClose = matchDelimiter(clean, aOpen, '(', ')')
            if (aClose < 0) continue
            val args = clean.substring(aOpen + 1, aClose)
            if (args.contains("chip = true")) continue          // 芯片按内容 wrap，本就不该有宽度
            if (WIDTH_GIVEN.any { args.contains(it) }) continue  // 调用方已给宽度
            checked++
            val line = clean.substring(0, aOpen).count { it == '\n' } + 1
            val exempt = (maxOf(0, line - 4) until minOf(rawLines.size, line))
                .any { rawLines[it].contains(EXEMPT_MARKER) }
            if (!exempt) hits += line
        }
    }
    return RowScan(hits, rows, checked)
}

/** 返回与 `text[start]`（值为 [open]）配对的 [close] 下标；找不到返回 -1。字符串/字符字面量内的括号不计。 */
private fun matchDelimiter(text: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var inStr = false
    var inChar = false
    var i = start
    while (i < text.length) {
        val c = text[i]
        if (inStr) {
            if (c == '\\') { i += 2; continue }
            if (c == '"') inStr = false
        } else if (inChar) {
            if (c == '\\') { i += 2; continue }
            if (c == '\'') inChar = false
        } else {
            when {
                c == '"' -> inStr = true
                c == '\'' -> inChar = true
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        i++
    }
    return -1
}

/** 把注释内容替换成空格（**保留长度与换行位置** → 行号不变）。字符串内的 `//` 不是注释。 */
private fun stripComments(src: String): String {
    val sb = StringBuilder(src.length)
    var i = 0
    var inStr = false
    var inChar = false
    var inLineComment = false
    var inBlockComment = false
    while (i < src.length) {
        val c = src[i]
        val n = if (i + 1 < src.length) src[i + 1] else '\u0000'
        when {
            inLineComment -> {
                if (c == '\n') { inLineComment = false; sb.append('\n') } else sb.append(' ')
                i++
            }
            inBlockComment -> {
                if (c == '*' && n == '/') { inBlockComment = false; sb.append("  "); i += 2 } else {
                    sb.append(if (c == '\n') '\n' else ' ')
                    i++
                }
            }
            inStr -> {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == '"') inStr = false
                i++
            }
            inChar -> {
                sb.append(c)
                if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i += 2; continue }
                if (c == '\'') inChar = false
                i++
            }
            c == '/' && n == '/' -> { inLineComment = true; sb.append("  "); i += 2 }
            c == '/' && n == '*' -> { inBlockComment = true; sb.append("  "); i += 2 }
            else -> {
                if (c == '"') inStr = true
                if (c == '\'') inChar = true
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}
```

  跑 **Step 2 的测试（此时 Step 3/4 还没做）—— 必须红**：
  ```bash
  JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
    ./gradlew.bat testDebugUnitTest --tests "*SettingsRowWidthScanTest" --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process
  ```
  预期真实扫描命中 **5 处**（2026-10-18 本机按同一判据实测：作用域 14 个文件、`Row` 内容块 25 个、被检查的组件调用点 5 个、违规 5 处）：`PlayerSettingsSection.kt` 的
  `:117`（时长档，5 枚挤成 1 枚可见）、`:323`/`:330`（已下载模型的删除 / 扫码上传）、
  `:357`/`:364`（未下载模型的下载 / 扫码上传）。
  ⚠️ `:323`/`:330`/`:357`/`:364` 现状是 `Box(Modifier.weight(1f)) { SettingActionButton(…) }` —— `weight` 给的是**外层 Box**、不是组件的实参区，所以照样命中。这正是 A3 要禁的"调用方手抄兜法"，Step 4 改写后自动消失。
  若命中数与这 5 处不符，**先核对 `scopedFiles()` 是否真的取到了文件**（空转断言会告诉你），不要改判据去凑数。

- [x] **Step 3: 落地 `SettingsChipRow`**：把 §4.8 的代码块原样追加到 `SettingsComponents.kt`。
  （✅ 实做：签名与 §4.8 逐字一致；`itemContent` 接收者确为 `RowScope`，`equalWidth` 的
  `Box(weight(1f))` 是全仓唯一一处等宽兜法）
  依赖：`adaptiveColumns(tv, phonePortrait, medium)`（`ui/components/CommonComponents.kt:152`，公开 Composable）、
  `SettingsTokens.chipGap` / `rowGap`（T1）。⚠️ `itemContent` 的接收者是 **`RowScope`**（不是普通 lambda）——
  `equalWidth` 分支里的 `Modifier.weight(1f)` 只有在这个接收者下才编译得过，这也正是"兜法只能出现在这一处"的技术保证。

- [x] **Step 4: 改写三处调用点**（✅ 实做：时长档 `tv = 5 / phonePortrait = 3`；模型与自定义源
  三处 `tv = 2 / phonePortrait = 1 / equalWidth = true`；另加 `ImportedPlaylistRow` 去掉外层 `Row`。
  ⚠️ `Triple` 解构**没有采用** —— 三处还各带 `trailing` / `danger` 两个语义，四元组用
  `private data class ModelAction` 承载，见 §10.252）

  ① 时长档 [PlayerSettingsSection.kt:112-123](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/PlayerSettingsSection.kt#L112) —— 换成 `SettingsChip` 行：
  ```kotlin
  if (state.crossfadeEnabled) {
      // 时长档位 2/4/6/8/12 秒（TV 遥控适配的离散选择，比滑条更易 D-Pad 操作）
      // 竖屏 407dp 放不下 5 枚 ⇒ 每行 3 枚（§4.8 A2）；TV / 手机横屏仍是一行 5 枚（B1 逐字等价）
      SettingsChipRow(
          items = listOf(2, 4, 6, 8, 12),
          tv = 5,
          phonePortrait = 3,
          modifier = Modifier.padding(start = 16.dp, top = 4.dp),
      ) { sec ->
          SettingsChip(
              label = "${sec}s",
              selected = state.crossfadeDurationSec == sec,
              onClick = { actions.onChangeCrossfadeDuration(sec) },
          )
      }
  }
  ```
  顺带修掉一条老问题：现状 5 枚是 `SettingActionButton`，**选中态完全看不出来**（不知道当前是哪档）；
  `SettingsChip(selected = …)` 之后与语言/画质芯片家族同构。⚠️ 这条只是"同一行本来就缺选中态"，不是本任务新增功能。

  ② / ③ 模型双按钮 :318-336 与 :352-370 —— `Box(weight(1f))` 的兜法收进原语：
  ```kotlin
  // 已下载分支
  SettingsChipRow(
      items = listOf(
          Triple(
              stringResource(R.string.settings_delete_model),
              stringResource(R.string.settings_delete_model_desc),
              { actions.onDeleteModel?.invoke() },
          ),
          Triple(
              stringResource(R.string.settings_scan_upload_model),
              stringResource(R.string.settings_scan_upload_model_desc),
              { actions.onScanTransferModel?.invoke() },
          ),
      ),
      tv = 2,
      phonePortrait = 1,
      equalWidth = true,
  ) { (label, desc, onClick) ->
      SettingActionButton(label = label, description = desc, onClick = onClick)
  }
  ```
  未下载分支同形（`settings_download_separation_model` / `settings_scan_upload_model`）。
  **TV 与手机横屏逐字等价**：`equalWidth` 在 `columns == tv == 2` 时产出的就是 `Row { Box(weight(1f)) { … } ×2 }`（B1）；
  竖屏 `columns == 1` ⇒ 每枚独占一行、整行宽（`weight(1f)` 的独子 = 满宽），说明有 407dp 可写 ⇒ 不再折 3 行。
  ⚠️ `Triple` 的解构写 `) { (label, desc, onClick) ->` 需要 Kotlin 的 lambda 解构（`val (a,b,c) = it` 语法糖）；
  若编译器不接受这种写法，改成 `) { item -> SettingActionButton(item.first, item.second, item.third) }`，语义相同。
  删除那枚要带 `danger = true`（S6，与 T12 Step 5 同一处，⛔ 别改两遍）。

- [x] **Step 5: A4 —— `SettingsInfoRow` 尾部定宽**（✅ 实做：`SettingsLabelValueRow` 新增
  `valueMaxWidth: Dp?`，`SettingsInfoRow` 传 `infoValueWidth` 且用 `widthIn(max = …)`；
  ⚠️ **偏离方案片段**：标签列取 `weight(1f)` 而非 `weight(1f, fill = false)` —— 后者会让值列
  紧跟标签、不再贴右边缘，理由见 §10.252）（[SettingsComponents.kt:378-396](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/settings/SettingsComponents.kt#L378)）：
  ```kotlin
  @Composable
  internal fun SettingsInfoRow(label: String, value: String, modifier: Modifier = Modifier) {
      Row(
          modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(SettingsTokens.chipGap),
          verticalAlignment = Alignment.CenterVertically,
      ) {
          // ⛔ 不用 SpaceBetween：它让两列各自 wrap，长值会把标签挤到折行甚至截断（§4.8 A4 反例）
          Text(
              text = label,
              color = NasMusicColors.TextSecondary,
              fontSize = FontSize.body(),
              modifier = Modifier.weight(1f, fill = false),
          )
          Text(
              text = value,
              color = NasMusicColors.TextPrimary,
              fontSize = FontSize.body(),
              fontWeight = FontWeight.Medium,
              textAlign = androidx.compose.ui.text.style.TextAlign.End,
              modifier = Modifier.width(SettingsTokens.infoValueWidth),
          )
      }
  }
  ```
  令牌里加 `val infoValueWidth = 180.dp`（§4.7-① 已列）。⛔ 不给 `maxLines`，长值允许折行。
  ⚠️ 这是**组件内部**的 `Row`（`SettingsComponents.kt` 不在 G8 作用域），改它不会被自己的门判违规。

- [x] **Step 6: A1 / A5 复核（不写代码，逐条打勾）**（✅ 三条全部复核通过，见 §10.252）
  - A1：本任务 diff 里**没有**新的 `PhoneLandscape` 分支；`SettingsChipRow` 只经 `adaptiveColumns`（其竖屏分支即 `PhonePortrait` 判定）拿列数。
  - A5：diff 里**没有**为竖屏新增的 dp 字面量（尺寸只有 `SettingsTokens.*`）；`SwitchTrack` / 卡片竖条 / 下载进度条**没有**被包上 `portraitTouchTarget()`（它们不是热区，套了反而是错的）。
  - 既有 `ScreenUiModeCoverageTest` **不覆盖分区文件**（触发条件是顶层 `fun XxxScreen(`，§4.8 ③）⇒ 本任务不要指望它，也不要去改它的触发条件（会把 11 个分区一次性拉进门禁，红名单失控）。

- [ ] **Step 7: 跑 G8 + 全量单测 + 编译**（⏳ 待用户执行：AI 不跑 Gradle。
  ✅ 已用**独立编译通道**跑过 G8 本体 10 例全绿 + 其余 5 个扫描门 38 例全绿，
  但那条通道**编不动 Compose 文件** ⇒ `assembleRelease` 仍必须由用户兜底）
  ```bash
  ./gradlew.bat --stop
  JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
    ./gradlew.bat testDebugUnitTest --tests "*SettingsRowWidthScanTest" --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process
  JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
    ./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon \
    -Pkotlin.compiler.execution.strategy=in-process *> logs_temp/settings_t13_test.log
  ```
  基线按跑前重测取、必须**只增不减**（v1.1 审校参考：2026-10-17 本地工件 211 个 XML / 2218 例 / 0 失败；报数必须带 `--rerun-tasks`）。日志看 `BUILD SUCCESSFUL` / 失败计数，⛔ 别看退出码。

- [ ] **Step 8: 三形态真机走查（§8 V16 / V17）**（⏳ 待用户执行，AI 不自行上机；与 §8 V16/V17 同一份清单）
  1. **竖屏 360 与 411 两档**各走一遍 11 个分区：时长档 3+2 排布、模型双按钮上下排且说明不折 3 行、`SettingsInfoRow` 标签不被长值挤没、开关轨不被压扁、二级页返回不丢分区（既有 `selectedSection` 逻辑）。截图进 `output/`。
  2. **TV / 手机横屏逐字一致**（B1 回归）：同一份 APK 在 TV 上对照改版前截图，时长档仍是一行 5 枚、模型仍是一行两枚等宽。⛔ 只要 TV 上有任何一处布局变化就是 B1 违约，先回滚再查。
  3. `SettingsSectionList.kt`（竖屏一级分区列表，G7 豁免但 V16 必查）逐项目视分区名 + 计数是否折行。

- [x] **Step 9: 提交**（✅ 实做：`b88a539`，8 files / 837 insertions / 101 deletions）

```bash
git commit -m "fix(settings): 时长档 0 宽塌陷与竖屏芯片挤压 —— 新增 SettingsChipRow 分行原语 + G8 宽度门"
```

---

### Task 14: 每个子项补一句说明（§4.5 的 15 项 + 2 条卡片级 + G6 门禁；CACHE 页 36 个 key 已在 T8b 落地）

**Files:** Modify `SettingsComponents.kt`（新增 `SettingsHint`、`PlayModeSelector` 加必填 `description`）/ §4.5 表命中的 8 个分区文件（General / Player / LyricsKaraoke / VisualEffects / PhotoWall / Sources / Download / AboutSection；⚠️ **Cache 分区不在名单** —— 它的 2 个新按钮说明与 9 条代价说明已在 **T8b** 当场落地，本任务只让 G6 扫到它们）/ `values/strings.xml` + `values-en/strings.xml`；Modify `app/src/test/java/com/nasmusic/tv/ui/SettingsGroupCoverageTest.kt`（追加 G6 三断言 + 自证）

**Interfaces:**
- Produces: `SettingsHint(text: String, modifier: Modifier = Modifier)`（§4.5 定义）
- Produces: `PlayModeSelector(current: PlayMode, onSelect: (PlayMode) -> Unit, description: String)` —— **`description` 无默认值**，靠编译器强制调用方给说明
- Consumes: T10–T13 之后所有分区文件的全部行（含新上线的歌词字号/高亮、模型自定义源，以及 T11 委托化后的 `SettingsTile`、T12 的 `SwitchTrack` / `Trail`）

- [x] **Step 1: 写失败的 G6 门禁**（✅ 实做：5 例 —— A + C + 3 条自证。**两处对方案片段的修正**：
  ① 豁免标记 `Desc-exempt:` 必须查**原文**而不是剥离后的文本（注释已被换成空格，永远查不到），
  且允许写在调用上方 3 行内；② G6-C 除了 `SettingsHint(` 还接受**卡头自带非空 `description`** ——
  方案的表把 `GENERAL ①/③` 标成 ✅「已有卡片 description」，片段却只认 `SettingsHint(`，两处自相矛盾。
  另：触发名单加了 `DurationRow(` / `PhotoTransitionChips(` 两个渲染芯片的私有 helper，
  否则 `PHOTO_WALL ③` 的 43 个芯片在门禁眼里不存在）

```kotlin
    // ─────────── G6：说明覆盖（§4.5） ───────────

    /**
     * 扫一个分区源码，返回缺 `description` 的调用点 `文件:行 调用名`。
     * G6-A（断言 A + B 合并：`PlayModeSelector` 加了必填 description 后仍要防人改回可选）
     * 与负向自证**共用这一条路径** —— 自证必须走真实判据，否则它只证明了自己。
     */
    private fun descViolations(text: String, label: String): List<String> {
        val out = mutableListOf<String>()
        text.lines().forEachIndexed { idx, line ->
            val call = listOf("SettingSwitch(", "SettingActionButton(", "PlayModeSelector(")
                .firstOrNull { line.contains(it) } ?: return@forEachIndexed
            if (line.contains("internal fun ") || line.contains("private fun ")) return@forEachIndexed // 组件自身定义
            if (line.contains("Desc-exempt:")) return@forEachIndexed
            val open = text.lineOffset(idx) + line.indexOf(call) + call.length - 1
            val args = text.substring(open, argEnd(text, open))
            val empty = args.contains("description = \"\"")
            if (!args.contains("description =") || empty) out += "$label:${idx + 1} $call"
        }
        return out
    }

    private fun descCallCount(text: String): Int = text.lines().count { line ->
        listOf("SettingSwitch(", "SettingActionButton(", "PlayModeSelector(").any { line.contains(it) } &&
            !line.contains("internal fun ") && !line.contains("private fun ") && !line.contains("Desc-exempt:")
    }

    @Test
    fun `每个开关、按钮与播放模式选择器都必须带非空 description`() {
        val root = settingsRoot()
        var scanned = 0
        val missing = mutableListOf<String>()
        for (f in root.listFiles { file -> file.name.endsWith("Section.kt") }.orEmpty()) {
            val text = stripComments(f.readText())
            scanned += descCallCount(text)
            missing += descViolations(text, f.name)
        }
        assertTrue("扫描到 0 个调用点 —— G6-A 在空转", scanned > 0)
        if (missing.isNotEmpty()) fail("以下设置行缺 description（§4.5：每个可操作项都要一句说明）：\n" + missing.joinToString("\n"))
    }

    @Test
    fun `芯片组与调整行所在卡片必须有 SettingsHint`() {
        val root = settingsRoot()
        var checkedCards = 0
        val missing = mutableListOf<String>()
        for (f in root.listFiles { file -> file.name.endsWith("Section.kt") }.orEmpty()) {
            val text = stripComments(f.readText())
            for ((i, span) in groupCardSpans(text).withIndex()) {
                val body = text.substring(span)
                if (body.contains("Desc-exempt:")) continue
                if (!(body.contains("AdjustButton(") || body.contains("SettingsChip("))) continue
                checkedCards++
                if (!body.contains("SettingsHint(")) missing += "${f.name} 第 ${i + 1} 张卡（有芯片/调整行，无 SettingsHint）"
            }
        }
        assertTrue("扫描到 0 张含芯片的卡片 —— G6-C 在空转", checkedCards > 0)
        if (missing.isNotEmpty()) fail("芯片组/调整行缺说明：\n" + missing.joinToString("\n"))
    }

    /** 负向自证：缺 description 的行必须被同一条路径判负，有 description 的行不能误伤。 */
    @Test
    fun `缺 description 的开关必须判负`() {
        val text = """
            SettingsGroupCard(title = "x") {
                SettingSwitch(label = "1", description = "ok")
                SettingSwitch(label = "2")
                SettingActionButton(label = "3", description = "")
            }
        """.trimIndent()
        val v = descViolations(text, "sample")
        assertTrue("负向用例失效：缺 description / 空 description 的行没被判负（实际命中 $v）",
            v.any { it.contains(":3") } && v.any { it.contains(":4") })
        assertTrue("误伤：带非空 description 的行被判负", v.none { it.contains(":2") })
    }

    /** 负向自证：卡片缺 SettingsHint 必须被 G6-C 的路径检出（把上面两条断言的判据抽出来同样成立）。 */
    @Test
    fun `含芯片但无 SettingsHint 的卡片必须判负`() {
        val body = "SettingsGroupCard(title = \"x\") {\n    AdjustButton(\"-\", onClick = {})\n}"
        assertTrue("护栏空转：SettingsHint 检测失效",
            (body.contains("AdjustButton(") || body.contains("SettingsChip(")) && !body.contains("SettingsHint("))
    }

    /** 从 `(` 的下标开始做括号配对，返回匹配 `)` 之后的下标。字符串字面量内的括号必须跳过。 */
    private fun argEnd(text: String, openParen: Int): Int {
        var depth = 0; var i = openParen; var inString = false; var inChar = false
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> { if (c == '\\') i++; else if (c == '"') inString = false }
                inChar -> { if (c == '\\') i++; else if (c == '\'') inChar = false }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' -> depth++
                c == ')' -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return text.length
    }
```

> ⚠️ `Desc-exempt: <理由>` 只留给**确实不该有说明**的行（豁免必须写理由）；`SettingsInfoRow` / `AboutRow` 只读行**不在** G6 的扫描名单里（§4.5 规范：说明由所属卡片的 `description` 承担），不要把它们加进 `descViolations` 的调用名列表凑覆盖率。

- [x] **Step 2: 跑测试确认失败**（✅ 实测红名单：G6-A 2 处 —— `DownloadSettingsSection:221`（`description = ""`，
  `settings_download_export_desc` 这个 key 一直存在却没被引用）+ `PlayerSettingsSection:80`
  （`PlayModeSelector` 无 description 形参）；G6-C 10 张卡，7 个文件）

- [x] **Step 3: 加组件**：`SettingsComponents.kt` 内新增 `SettingsHint`（§4.5 代码，`TextSecondary` + `FontSize.small()`，与 `SettingSwitch` 的 description 视觉同规格 :113-118）；`PlayModeSelector`（:129-156）签名加**必填** `description: String`，函数体首行渲染 `SettingsHint(description)`；全树 `PlayModeSelector(` 调用点随之被迫补参（编译器兜底，别加默认值）。（✅ 实做；`SettingsHint` 自带 `bottom = 8.dp`，调用方不要再补 `Spacer` —— 原来手写的灰字 `Text` + `Spacer` 组合已统一收进它）

- [x] **Step 4: 按 §4.5 表逐条补文案**（✅ 实做，见 §10.253。**与名单的两处出入**：
  ① 新增 `settings_group_quality_desc`（PLAYBACK ③ 两条说明合并进卡片 description 的落点），
  并删除被它取代的 `quality_tier_hint_lossless` / `quality_tier_hint_scope`；
  ② 百度目录两行按 §4.5 硬要求 3 拆成 `SettingsInfoRow`（路径归值位）+ `SettingActionButton`
  （说明归 description），新增 `settings_netdisk_music_root_desc` / `_music_root_edit` / `_mv_dir_edit`
  三个 key，`settings_download_location_desc`（内容已过时："导出在「我的 → 下载」"）一并删除。
  另：`settings_download_export_desc` **一直存在却零引用**，G6-A 一建门就红在这里 —— 已接线）（非 CACHE 的 **15 项文案 + 2 条卡片级 = 17 个 key**，中英各一份；key 名照表：`settings_group_font_hint`、`settings_play_mode_desc`、`settings_crossfade_duration_desc`、`settings_lyrics_scale_desc`、`settings_lyrics_highlight_desc`、`settings_model_size_desc`、`settings_model_custom_url_desc`、`settings_visualizer_theme_desc`、`settings_cover_blur_desc`、`settings_cover_dark_desc`、`settings_pw_transition_mode_desc`、`settings_pw_scale_desc`、`settings_netdisk_index_desc2`、`settings_default_source_desc`、`settings_download_location_hint`，另加两条卡片级 `settings_group_endpoint_risk_desc` / `settings_group_about_desc`；⛔ 不含 `settings_pw_source_desc` / `settings_local_music_desc` / `settings_baidu_apic_desc` —— 三处控件**已有** description 或没有可挂的按钮（§4.5 订正后为 ✅，v1.1 审校从原 18 项名单中删去）；⛔ 不含 `settings_karaoke_pitch_desc` / `settings_karaoke_speed_desc` —— 变调/倍速不进设置页，§6-13；⛔ **CACHE 页的 36 个 key 与两条复用槽位（`settings_cache_dir_size`、`settings_clear_all_cache_desc`）已由 T8b 落地**，本步不要重复改，只核对 G6 扫到那两个新按钮时它们确实带了非空 `description`；另注：§5.2 开关轨的 4 个 key（`settings_on` / `settings_off` / `settings_state_on` / `settings_state_off`）属 **T12**，不计入本步 17 个）
  - 顺带把 `BaiduPanSettingsSection` 里**用 `description` 显示当前根目录路径**的写法改成 `SettingsInfoRow`（§4.5 硬要求 3 的现存违例），路径行归值位、目录说明归 `description`。
  - PLAYBACK ③ 的两条档位说明 `Text`（PlayerSettingsSection:228-240）合并进「音质」卡 `description`，删除散落的两个 `Text`。

- [x] **Step 5: 逐条核对长度**：中文说明 ≤ 26 汉字（§4.5 硬要求 1）。超长的一律重写，不要靠 `FontSize.small()` 塞长段落、不要改成两行 `Text`。（✅ `logs_temp/t14_selfcheck.py` 第 [3] 组逐条量过：最长 25 字 `settings_model_size_desc`；§4.5 表里 `settings_group_font_hint` 的原始样稿 28 字**超标**，已提炼为 24 字版本）

  - 顺带把 `BaiduPanSettingsSection` 里**用 `description` 显示当前根目录路径**的写法改成 `SettingsInfoRow`（§4.5 硬要求 3 的现存违例），路径行归值位、目录说明归 `description`。（✅ 实做：音乐根目录与 MV 目录**两行同病同治**，各拆成「路径行（值位）+ 修改按钮（说明位）」）

- [ ] **Step 6: 跑 G6 + 全量单测 + 编译 + 提交**（⏳ Gradle 部分待用户执行；✅ G6 等 6 个扫描门
  已用独立编译通道跑过 53 例全绿，`logs_temp/t14_selfcheck.py` 105 项全过）

```bash
git commit -am "feat(settings): 每个设置子项补一句说明文案，新增 SettingsHint 与说明覆盖门禁"
```

---

### Task 15: 删除 `SubSectionTitle` 并把覆盖率门禁转为全量扫描

**Files:** Modify `SettingsComponents.kt`（删 :56-64 定义）；Modify `SettingsGroupCoverageTest.kt`（`migrated` 名单换成按目录枚举）

- [x] **Step 1: 全树 grep 确认 0 命中**
  > ⚠️ **与方案预期的出入**：方案假设此时已 0 调用点，实测 `DataSettingsSection:139` 还剩
  > **最后一个**（卡②里的「最近导入记录」小标题）。已按 §3.2 意图处理：该块**移出卡②独立成卡**
  > （卡中卡会破坏卡片边界），`SubSectionTitle` 随之归零，Step 3 才能删定义。

Run: `grep -rn "SubSectionTitle" app/src` → Expected: 仅剩 `SettingsComponents.kt` 自身定义（0 个调用点）

- [x] **Step 2: 加 G2 断言（含负向自证）**
  > ⚠️ **对方案片段的修正**：G1 转**全量**后，按目录枚举会扫到 `SettingsSection.kt` ——
  > 那是分区**枚举**定义文件、不是分区 UI，天然没有卡片。显式排除 + **防锈断言**
  > （被排除的文件一旦出现卡片/设置行，测试立刻红，逼人把它移出名单），
  > 否则排除名单会静默漏扫。

```kotlin
@Test
fun `SubSectionTitle 必须已从源码中彻底移除`() {
    val root = settingsRoot()
    val hits = root.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
        .flatMap { f -> stripComments(f.readText()).lines().withIndex()
            .filter { it.value.contains("SubSectionTitle(") }
            .map { "${f.name}:${it.index + 1}" } }
        .toList()
    assertTrue("仍有 SubSectionTitle 调用：$hits（分组标题必须用 SettingsGroupCard 卡片头）", hits.isEmpty())
}

/** 负向自证：把 SubSectionTitle 塞回样例文本必须被检出。 */
@Test
fun `样例里的 SubSectionTitle 必须被检出`() {
    val text = "Column {\n    SubSectionTitle(\"x\")\n}"
    assertTrue("护栏空转：SubSectionTitle 检测失效",
        stripComments(text).lines().any { it.contains("SubSectionTitle(") })
}
```

- [x] **Step 3: 删除组件定义 + `migrated` 改为全目录扫描**，跑测试 PASS（✅ 实做：G1/G2 等
  6 个扫描门 55 例全绿 —— 含新增的 `nonSectionFiles` 防锈断言；`logs_temp/t13_selfcheck.py`
  57 项 + `t14_selfcheck.py` 105 项回归全过）
- [ ] **Step 4: 提交** `git commit -am "refactor(settings): 移除 SubSectionTitle，分组卡片覆盖率转为全量门禁"`

---

### Task 16: 宿主瘦身（端点对话框收敛 + 网络测试下沉）

**Files:** Modify `ui/screens/SettingsScreen.kt`（删 6 份重复对话框状态、:713-744 的内联 OkHttp）、`ui/components/branches/SettingsBranch.kt`、`ui/viewmodel/MainViewModel.kt`（新增 `runNetworkProbe()`）、`app/src/test/java/com/nasmusic/tv/ui/SettingsEndpointDialogWiringTest.kt`

- [x] **Step 1: 写失败的门禁**：断言 `SettingsScreen.kt` 里 `TextInputDialog(` 调用点只剩 1 个通用宿主 + 1 个删除确认 + 1 个清除下载确认（共 ≤3），且不含 `OkHttpClient`/`Request.Builder` 字样（I/O 已下沉）。含负向自证（把 `OkHttpClient` 塞回文本必须判负）。（✅ 实做：`SettingsEndpointDialogWiringTest` 10 例 —— 红 ≠ 空转：首跑 4 正向失败 / 6 过（3 负向自证 + 2 现状本就满足）；删除/清除确认实际是 `Dialog(`+自绘 与 `Dialog(`+`ConfirmDialog`，门禁按「`TextInputDialog(` == 1 + 两确认弹窗仍在」落地，与方案 ≤3 意图一致）
- [x] **Step 2: 实现 `EndpointEditor` spec（§5.3）与 `MainViewModel.runNetworkProbe()`**（探针 URL、超时、状态文案逐行照搬原 lambda，行为不变；⛔ 不要在单测里真连该端点）（✅ 实做出入见 §10.255：spec 加 `masked`、`descRes`→`hintRes`；百度目录文本回退也并入通用宿主，目录树形态保留布尔 + `BaiduDirPickerDialog`；方案所写的 `:713-744 内联 OkHttp` 现状已是 `HttpURLConnection` 且只有 30 行，照搬下沉）
- [x] **Step 3: 跑测试 PASS + 真机验证网络测试结果与迁移前一致**（✅ 单测：新门 10/10 绿 + 5 个设置扫描门 46 例回归全绿 + `t16_selfcheck.py` 正向/负向自证全过；⏳ 真机验证待用户执行 —— 归入 V16 走查）
- [x] **Step 4: 提交** `git commit` "refactor(settings): 端点编辑对话框收敛为通用宿主，网络测试下沉到 ViewModel"（⛔ 非 `-am`：只 add 本任务文件，不带入其他会话改动）

---

### Task 17: 文档同步与发版前验证

**Files:** Modify `CHANGELOG.md`（当前未发布版本段落）、`docs/technical-overview.md`（新增 §10.N）、`docs/superpowers/…` 无；本方案归档

- [x] **Step 1: 全量单测**：`./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon -Pkotlin.compiler.execution.strategy=in-process` → grep `BUILD SUCCESSFUL` 并核对测试 XML 总数（⛔ 不要只看退出码/通知，见用户长期反馈）（✅ 2026-10-19 19:0x 实跑：**218 份 XML / 2279 例 / 0 失败 / 0 错误**，`ApiDriftNotifyTest` 退役后计数；随后真机反馈修复 `c96a9c7` 落地，最终复跑见 §10.257）
- [x] **Step 2: lint + assembleRelease**：`./gradlew.bat lintDebug assembleRelease --no-daemon -Pkotlin.compiler.execution.strategy=in-process`（输出重定向到 `logs_temp/release_build.log`）（✅ 2026-10-19 19:47 链式实跑 BUILD SUCCESSFUL 11m26s：`lintDebug` **0 Error / 157 Warning**（`app/build/reports/lint-results-debug.xml`），APK `NASMusicTV-release-v2-39-0.apk` 25,758,135 B / versionCode 185 / versionName 2.39.0）
- [x] **Step 3: `CHANGELOG.md` 逐条一行、只写做了什么**：卡片化、分区重分类、删除项、新增暴露项、GitHub 链接可聚焦、每项补说明文案、开关改分段式开关轨、按钮尾部区分、危险动作上色、尺寸动效令牌化、**修复淡入淡出时长档只显示一档**、**设置分区排布适配手机竖屏**、**缓存页补齐占用一览与 3 个缺失的清理入口**、**修复清除歌词/MV 缓存后占用数字不回落**。**根因与验证过程一律进 §10.N**。⚠️ 本方案属"改版"，发版号必须与用户确认后再写（既有规则：已打 tag 的版本段落冻结）。（✅ v2.39.0 段落已落，用户总请求已明确"升 v2.39.0" = 发版号确认；✅ 尺寸动效令牌化是内部改动不进 CHANGELOG——用户可感知的"开关/按钮/危险色"各条已分别覆盖）
- [x] **Step 4: `docs/technical-overview.md` 新增一节**：本次 tab 映射表、明度阶梯取值、**G7-A 迁移前后的红名单对照**（§2-10 的计数 → 0）、`ChipContentWidthScanTest` 扩正则前后的 `callSites` 数、**G8 建门时的 5 处红名单（`PlayerSettingsSection:117/323/330/357/364`）→ 0**、开关轨的**参考图实测数与两处偏离**（含 `scripts/measure_switch_reference.py` 的输出口径）、竖屏内容宽 407dp vs TV 1592dp 的算式、幽灵管道的发现过程、死配置的 grep 判据、备份兼容结论（旧 JSON 宽容）、真机验收截图口径（V16 竖屏两档 / V17 B1 对照 / **V18 三形态：清前后实际字节数、逐目录归零、低端机焦点**）；缓存侧另记 **G9 建门时正则在全树命中的目录数**（当前 16，随代码变，⛔ 不许抄进注释当断言）、**19 条登记项与 16 个命中数的差额来源**（`accompaniment` 常量拼接 + `models` 变量根 + `dir=""` 外部根 + `music` 豁免）、两条已知漏网面与为什么**不加反向断言**。（✅ = §10.256；G8 红名单实测 8 处/3 文件（方案估 5 处），G9 命中实测 15+1 豁免，均已按实测记录）
- [x] **Step 5: 本方案归档**（按 AGENTS.md 文档生命周期：✅ 已落地 → `git mv docs/settings-reorganization-plan.md docs/archive/`，并在 `docs/archive/README.md` 加一行「doc / reason / superseding record」）。§6 的 18 行裁决**只剩 1 行开放**：13b（`k_pitch_semitones` / `k_playback_speed` 要不要继续写 DataStore）—— 已明确划在本方案范围外，归档时**必须**按文档生命周期规则把这条未决项显式写进 `docs/archive/README.md` 那一行，⛔ 不许静默埋掉。其余已落定（含用户 2026-10-18 确认的 ①歌词偏移链删除、②占位网盘行删除、③深色主题保留、④变调/倍速不进设置页、⑤缓存按 §1.14 表 1–18 补齐、表 16/17 两行"评估过但不纳入"即为终局口径），归档行另记 superseding 记录（§10.N + CHANGELOG 条目）；**若执行中新出现未决项**（例如某条说明文案被要求重写、`focusScaleChip` 判读后调整、或 V18 ② 判出"总数与可清之和"对不上需要新增登记项），同样必须显式记录。⛔ 不许只留 `docs/` 根当"还能做"的暗示。（✅ 第十五轮登记 + 13b 显式留档 + 顶部归档状态头；死链自证残留 0；执行期遗留 = V1–V18 真机走查待用户，同样显式记录）
- [x] **Step 6: 提交** `git commit` "docs(settings): 设置区整理方案落地记录与归档（v2.39.0）"（✅ `1097954`；⛔ 非 `-am`：README 用 patch 法只暂存本轮登记行，未带入其它会话的第十四轮未提交改动）

---

## §8 真机验收清单（TV 192.168.0.114:5555 + 手机）

> 安装口径（AGENTS.md）：`adb connect 192.168.0.114:5555` → `adb -s … install -r app/build/outputs/apk/release/app-release.apk`；若已装不同签名的 debug 版需先 `uninstall com.nasmusic.tv`。**卸载/安装由用户执行**（既有分工），agent 侧只做 adb 驱动 + 截图判读，截图判读用 NEAREST/profiles，⛔ 不用 LANCZOS 描边。

- [ ] V1 TV：11 个 tab 侧栏顺序与图标正确；每个 tab 内卡片边界肉眼可辨，卡片头与行标签层级分明
- [ ] V2 TV：D-Pad 从侧栏「下」进入内容区、**卡片头本身不可聚焦**（焦点只在行上）、逐行不跳序、不回弹；侧栏 ↔ 内容区焦点交接正常（`navFocusRequesters`）
- [ ] V3 TV：关于页 GitHub 链接可被遥控器聚焦并打开
- [ ] V4 TV：照片墙「相册总开关」仍是唯一权限申请入口；人脸扫描置灰项仍点不动（`enabled` 双保险）
- [ ] V5 TV：同排多按钮（淡入淡出时长、画面适配 chips（T11 后名为 `SettingsChip`）、方向 chips）**全部可见**（0 宽缺陷未复发；两道门都要在：`ChipContentWidthScanTest` 的 `callSites` 在 T11 扩正则后仍 >0 且数量不降，`SettingsRowWidthScanTest`（G8）的 `rowBlocks` / `checkedCalls` 同样 >0）
- [ ] V6 TV：来源 tab —— 本地曲库开关仍会（且仅会）触发音乐权限；百度登录/根目录/MV 目录/重建索引行为与迁移前一致；默认网络源改动后点播生效
- [ ] V7 TV：歌词 tab 改字号 → 回播放页立刻生效；分离模型「自定义源 URL」填自建镜像地址后 SHA-256 校验语义不变（填非法值应**保持旧值**并给出可见反馈，不是静默存下）
- [ ] V8 TV：数据 tab —— 旧版本导出的备份文件仍能在新包「恢复」（§6 删除字段的兼容实证），结果消息仍显示在卡片顶部可见处
- [ ] V9 手机竖屏：两级页一级列表 11 行齐；二级页有返回头、卡片左右不贴屏边、触控目标达标
- [ ] V10 手机横屏：双栏布局与 TV 同卡片
- [ ] V11 创维 5.1.1（minSdk 22 基准机）：设置页进入无崩溃、无 ANR（缓存目录遍历仍在协程内）
- [ ] **V12 说明文案（§4.5）**：TV 1080p 与手机竖屏各抽查 4 个 tab（GENERAL / PLAYBACK / SOURCES / LYRICS_KARAOKE），逐条确认说明**不截断、不换到第三行、不与当前值挤在同一视觉位**；`SettingsHint` 与 `SettingSwitch` 的 description 在屏幕上看起来是同一层级的次要文本
- [ ] **V13 服务器入口往返（§3.3）**：TV 上从 SOURCES 卡①「服务器配置」跳出 → 在独立页完成连接 → BACK 回设置页，**焦点落回「服务器配置」按钮**（不是侧栏、不是内容区顶部）；卡①摘要文本随连接结果更新；确认设置页内**没有任何**凭据输入框
- [ ] **V14 风格一致性（§4.7 / T11）**：同一 tab 内所有行瓦片的圆角、内边距、描边一致；聚焦放大只有**两档**（整行 1.03 / 芯片 1.06），TV 上能看出"芯片跳得更起劲"；`AdjustButton` 的 ± 由 1.1 降到 1.06 后**按下去仍有明确反馈**（这条是 T11 唯一可能被感知为"变弱"的改动，若判读为"不知道按没按到"就停下改判 `focusScaleChip`，不要放宽门）；长值行（缓存目录、模型路径、百度根目录）的标签不再被值挤没；行说明与卡片描述看起来是同一层级的次要文本
- [ ] **V15 尾部语义（§5.2 / T12）**：开关只显示**分段式开关轨**，**只看图形**就能判出开/关（TV 3 米距离 + 手机竖屏各一次）；⚠️ 专验两条**参考图偏离**（§5.2 已记）：① 轨道 128dp 比参考图等比换算的 167dp 窄 —— 判读"滑块行程够不够看清"；② 活动标签帽高按 35%（字号地板 18sp 反推）而非参考图的 26.9% —— 判读"滑块里的『开』字有没有被裁"。任一判读失败按 §5.2 回退方案走**单令牌改动**，⛔ 不加第三种组件；开无障碍朗读读出「已开启/已关闭」；入口型按钮尾部是 `›`、就地执行按钮无尾部（不再有满屏「确定」）；四处危险动作（清除全部下载 / 清除全部缓存 / 删除备份 / 断开连接）呈 `Warning` 色且**与普通按钮一眼可分**；`settings_wifi_on/off` 已从两份 strings.xml 消失、`settings_on/off` + `settings_state_on/off` 中英齐备（`LanguageMirrorTest` 仍绿）
- [ ] **V16 竖屏走查（§4.8 / T13）**：手机 **360 与 411 两档物理宽各走一遍 11 个分区**，逐条判读：① 淡入淡出时长 **5 档全部可见**（3+2 两行，这是本任务修的既有缺陷，改前只有「2s」）；② 模型双按钮上下排、说明**不折到第三行**、两枚**同高**；③ `SettingsInfoRow`（缓存目录、模型路径、百度根目录、下载统计）的标签不被长值挤没；④ 开关轨仍是 128×36 的 stadium，**没被压成方块或胶囊**；⑤ 语言三芯片（A2 允许保留 `weight` 的样本）仍不折行；⑥ `SettingsSectionList` 一级分区名 + 计数不折行（该文件被 G7 豁免，只能靠目视）。截图进 `output/`，判读结论写进 §10.N
- [ ] **V17 手机横屏与 TV 逐字一致（B1 回归，§4.8 A1 / T13）**：同一份 APK 在 TV（1920）与手机横屏上对照改版前截图 —— 时长档仍是**一行 5 枚**、模型双按钮仍是**一行两枚等宽**、`SettingsInfoRow` 的标签/值位置不变。⛔ TV 或横屏上出现任何一处布局变化都算 B1 违约（`else` 分支没做到逐字等价），先回滚 T13 的该处改动再查，不要"看起来差不多"就放过
- [ ] **V18 缓存占用一览（§1.14 / T8b）**：三形态各一次 ——
  ① **数字回落**（本任务修的既有缺陷，与 Step 1 的改版前取证首尾对照）：进 CACHE 记下顶部总数 → 点「清除歌词缓存」→ 等 1s → **数字必须变小**；再点「清除 MV 缓存」→ 再变小。⛔ 若两次都不动，说明尺寸计算仍只读 `cacheDir`，回到 T8b Step 7 核对口径，不要当成"这台机器缓存本来就是 0"（先用 `adb shell run-as com.nasmusic.tv du -sh` 自证非零）。
  ② **逐目录归零**：对有独立按钮的组各清一次（应用缓存 / 持久缓存 / 媒体与模型三组覆盖 5 个清理入口），清完对应行的占用必须显示 `0 B` 或整行消失（按 §4.5 的实现口径），且顶部总数 = 剩余不可清行之和（**总数 > 可清之和是正常现象**，不可清名单见 §4.5 卡②，包括用户自放 LRC、偏好 DataStore、已下载歌曲、分离模型）。
  ③ **低端盒子不卡**：创维 5.1.1 上进 CACHE 后**立即**用 D-Pad 连续上下移动焦点，扫描期间不得出现焦点丢失、跳行、按键无响应或 ANR（19 条目录遍历全在协程内 + `MAX_FILES` 截断，超出显示「≥」）。⚠️ 专验 `walkTopDown` 那两条大目录（`image_cache`、外部下载目录）在缓存很多时是否拖慢首屏 —— 首屏数字应先显示"计算中"再逐行填充，不能整卡等齐才出现。
  截图进 `output/`（`settings-cache-*.png`），判读结论与**清前后的实际字节数**写进 §10.N。

## §9 风险与回滚

| 风险 | 缓解 |
|---|---|
| T3 的分区合并牵动 `SettingsScreen` 约 100 个参数，接线漏一处即崩 | 分 6 步提交，每步可编译可装；`SettingsSectionWiringGateTest` 锁 when + 焦点 + 中英字符串 |
| 卡片化后竖屏内容更高，BACK「滚到顶部」语义（`SettingsScreen:397-401`）是否仍正确 | T8 后真机验 V9；消息提示仍在分区第一个元素（v2.36.2 的教训：不能沉到列表下方） |
| 删 `BackupData` 字段导致旧备份恢复失败 | T9 Step 1 先写兼容测试；Gson 忽略未知字段，且 `nameOrDefault()`/`ordinalOrDefault()` 守卫继续兜枚举 null |
| 删 `cloud_drive_configs` 字段导致已登录用户掉登录态 | 只删 `customAppKey`/`customSecretKey`/`apiDriftNotified` 三个**从未写入过**的字段（零调用方 ⇒ 存量 JSON 里不存在），`enabled`/令牌/目录保留；T9 Step 4 的 grep 0 命中 + 真机 V6/V8 双证 |
| 歌词字号双入口（播放页弹窗 + 设置页）打架 | 两处都只写同一 key，读同一 `AppSettings`；V7 验收「改一处另一处立刻同步」 |
| 纯视觉改动无法单测证明 | §5.7 的门禁组（G1 覆盖 / G2 零残留 / G3 接线 / G4 负向自证 / G5 空转）+ §4.5 的 G6 说明覆盖 + §4.7 的 G7 风格统一 + §4.8 的 G8 行宽 + §1.14 的 G9 缓存登记表 + §8 十八项真机判读；UI 未上机验收前，**不得**在 CHANGELOG 声称"已优化" |
| **T11 一次牵动 6 个共享行组件 + 4 个分区的内联瓦片**，改坏了是全站设置页同时坏 | 逐组件提交（`SettingsTokens` → `SettingsTile` → 每个共享组件 → 每个分区），每一步都编译 + 装真机；三个既有门（`FocusIndicatorContractTest` / `SmallTouchTargetScanTest` / `ChipContentWidthScanTest`）必须与 G7 **同轮**跑，任一红就停在那一步，不许跳步收尾 |
| **令牌化把 `FocusableSurface(` 换了名字，导致 0 宽陷阱门静默失配**（扫不到调用点但空转断言仍可能靠其它文件的命中撑绿） | T11 Step 7 是**硬性步骤**：`ChipContentWidthScanTest.CALL` 正则必须扩到 `SettingsTile\|SettingsChip`，并核对该测试自己打印的 `callSites` 数量在迁移前后**不降反升**（数字记进 §10.N） |
| G7-A 的「参数行不得含数字」会误伤合法的条件式令牌写法（如 `if (enabled) SettingsTokens.x else 1f`） | 用 `Style-exempt: <理由>` 行级点名放行并写明为什么，⛔ 不放宽判据（长期反馈：加强门禁而非放宽阈值）；`focusScaleDisabled = 1f` 进令牌就是为了让绝大多数场景根本不需要豁免 |
| 删 `settings_wifi_on/off` 删到真正在用的地方 | 实测全树只有 `SettingsComponents:119` 一个消费方（其余 4 处是两份 strings.xml 的定义）；T12 Step 6 仍要求 `grep -rn "settings_wifi_" app/src` 归零后才删定义。**若**将来别处需要"开启/关闭"文案，用新 key `settings_on/off`，⛔ 不要把 WiFi key 再引入设置区（G7-D 会红） |
| 说明文案过长在 TV/竖屏被截断，或写成实现描述用户读不懂 | §4.5 硬要求 1（≤26 汉字）+ V12 逐条抽查；超长一律重写文案，⛔ 不调字号、不折成两行 `Text` |
| 三合一 `SourcesSettingsSection` 搬迁时改动了权限申请/置灰语义（本地曲库总开关是权限唯一触发点） | T3 Step 3 的「迁移红线」要求整段原样复制；`LocalMusicGateTest` 仍在网 + V6 首项专验「开关仍只触发一次权限」 |
| **T13 为竖屏改排布，把 TV / 手机横屏一起改掉**（B1 违约：`else` 分支必须逐字等价） | `SettingsChipRow` 的 `tv` 参数**就等于现状的行数**（时长档 `tv = 5`、模型 `tv = 2`），`equalWidth = true` 产出的容器结构与原 `Row { Box(weight(1f)) ×2 }` 逐项同形；V17 用**改版前的 TV 截图**做对照，⛔ 不许"以新版为准" |
| **`adaptiveColumns` 的双输入陷阱**：`LocalConfiguration.screenWidthDp` 是未缩放 Android dp（≈360），竖屏布局宽却是 Compose dp（×0.82 后 ≈439） | 竖屏分支在 `adaptiveColumns` 内**直接返回 `phonePortrait`**（`CommonComponents.kt:153`），不参与 600/1000 阈值判断 ⇒ 只要 T13 不自己写 `if (widthDp …)` 就踩不到；复核项：T13 diff 里**不得出现** `screenWidthDp` |
| **G8 与 `ChipContentWidthScanTest` 只留一层**，另一层失配就静默留下 0 宽路径 | 两层分工写死在 §4.8：旧门守 `FocusableSurface` 的**内容**（`fillMaxSize`），G8 守**调用方**（无宽度的整行瓦片）。T11 Step 7 扩旧门正则、T13 建 G8，两步各自的红名单都要在 §10.N 留数；两门的空转断言都是**调用点数 > 0**，不是"违规数 = 0" |
| **T13 里最诱发的空转**：按"Row 有 ≥2 个子项"判 | 时长档那行字面只有 1 个调用点（`forEach` 生成 5 枚）—— 按子项数判**报 0 处违规却什么都没查**。G8 判据钉死在"Row 内容块内的组件调用"上，负向用例①直接拿 `PlayerSettingsSection:112-123` 的**原文**当 fixture，回潮即红 |
| **0 宽塌陷是线上既有缺陷**，混在改版提交里说不清是"改坏的"还是"早就坏的" | T13 **单独一次提交**，Step 1 先用未改版的 APK 取证（截图存 `output/`、结论进 §10.N），V16 ① 再验修复；commit 前缀用 `fix(settings)` 而非 `feat` |
| **T8b 的目录遍历把低端机卡住**：19 条登记项里两条是大目录（Coil `image_cache` 上限 100MB、外部下载目录可达数千文件），`walkTopDown` 全量统计在 API 22 盒子上是几百毫秒到秒级的 IO | 统计**全在协程内**、逐行填充（先出卡再出数），`MAX_FILES = 4000` 截断并显示「≥」；⛔ 不许为了"数字好看"去掉截断或改到 UI 线程同步算。V11 验进入不 ANR、V18 ③ 专验扫描期间 D-Pad 连续移动焦点不掉帧 |
| **分离进行中清产物 ⇒ 分离任务失败**：`nasmusic_hq/*_input.tmp` 与 `demucs_pcm_*.tmp` 正在被 `DemucsSeparator` 读写，`accompaniment/` 正在被 K 歌播放读 | 「清除分离缓存」按钮的 `enabled` 必须挂分离运行态（置灰双保险口径同 §5.6：`enabled=false` 且 `onClick` 再判一次），⛔ 不做"运行时也照删"；V18 新增一条：分离进行中该按钮点不动、其余 4 组按钮仍可用 |
| **`cover_cache_` / `demucs_pcm_` 是 `cacheDir` 根下散文件、不是子目录**：`File(cacheDir, "cover_cache_")` 恒不存在 ⇒ 尺寸恒为 0、删除恒空转，而且看不出错 | `sizeOf` 里有"目录名以 `_` 结尾 ⇒ 按前缀列 `cacheDir` 根文件"的特例分支（KDoc 写明原因）；⚠️ 该分支是一次 `listFiles` 全根扫描，同样只在协程内。G9 判据③ 只验"前缀字符串在全树出现过"，**防不了前缀被改名** ⇒ 前缀常量的真源仍是 `EmbeddedCoverExtractor:98` / `DemucsSeparator:580`，改名时两侧必须同步 |
| **G9 有已知漏网面**：常量拼接的目录名（`accompaniment` 来自 `CACHE_DIR_NAME`）与变量根（`File(baseDir, "models")`）正则扫不到 ⇒ 门绿 ≠ 登记全 | 诚实写进 KDoc（§1.14 表 15–18 就是人工底稿），判据②「deletable 必须有 `fun`」+ 判据③「登记项必须在全树出现过」各兜一半失效面；⛔ **不加**"每条登记都必须被扫到"的反向断言凑覆盖（那会对每条常量路径误判红，随后被人当噪音关掉）。口径见 §5.7 G9 |
| 回滚 | 迁移任务逐 tab 提交，单 tab 回滚 = `git revert <sha>`；纯文档层回滚 = `git checkout -- docs` |

## §10 自检（对照用户 5 项交付 + 2026-10-18 各轮追加的 8 条要求/裁决 —— 下列 13 条全覆盖）

1. 完整设置项清单 → §1（11 个 tab 逐行 + §1.12 页外设置 + §1.13 死配置，合计约 90 个持久化 key，全部带 file:line）
2. 重新规划内容与 tab 分类 → §3（**只按内容归属划分**：映射表逐条给"为什么在这个 tab"的依据 + 枚举定义 + 每 tab 卡片构成表）
3. UI 优化「子项无分割」 → §4（明度阶梯设计 + `SettingsGroupCard` 完整实现代码 + 迁移唯一动作 + 三形态与焦点规则）
4. 整体优化 → §5（9 条，含开关态图形化、宿主瘦身、I/O 下沉、TV 可达性、门禁、备份兼容、明确的不做清单）
5. 可删除项评估 → §6（**18 行**裁决表，每条给判据；同时给出 4 项「应新增暴露」，1 行 13b 显式留待裁决。第 16/17 行是缓存主题的"评估过但**不**纳入"，见第 13 条）
6. **「各 tab 不必平均，按内容归属」** → §3 开头裁决原则（并明确作废上一版的"容量方差收敛"说法）；DOWNLOAD 与 CACHE 因**域不同**保持两个 tab，LOCAL_MUSIC 因**是来源开关**并入 SOURCES —— 两处都是按内容判，不按长度判
7. **「服务器连接是否并进设置页」** → §3.3，裁决**不并**，给 5 条硬依据（凭据二维码 + 页面级 HTTP 服务的生命周期、焦点域冲突、BACK 掉层、竖屏变三级、表单需整屏）；设置页侧只做摘要入口 + 新增「设置页不得出现凭据输入框」负向门禁（T7 Step 4）
8. **「每个子项加文字说明」** → §4.5 规范（一句话规则 + 三条硬要求）+ `SettingsHint` / `PlayModeSelector(description)` 两个补齐位 + **逐条 15 项落地文案表 + 2 条卡片级说明**（CACHE 分区的 36 个 key 由 **T8b** 自带、不在 T14 名单，口径见 §4.5 注记）+ G6 门禁 A/B/C/D（T14）
9. **「组件风格要统一」（用户 2026-10-18 追加）** → §2-10 逐条实测漂移计数（圆角/边距/缩放/时长/选中态/尾部文案/危险色）+ §4.7 的 `SettingsTokens`（唯一真源）/ `SettingsTile`（唯一委托点）/ S1–S6 六条规则 + G7-A/B/C/D 门禁（T11 收取值、T12 收语义）+ §8 V14/V15 真机判读
10. **「开关组件参考这个图，选用中间这个」（用户 2026-10-18 追加，附 800×600 三选一参考图）** → §5.2 的**全部数字由 [scripts/measure_switch_reference.py](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/scripts/measure_switch_reference.py) 量出**（中线白色游程法定位轨道/滑块、无字列取纵向边界、字形窗口内缩避开圆角 AA），⛔ 不是照眼描；两处与参考图的偏离（宽度 4.657→3.56、帽高 26.9%→35%）都给了算式与理由（空档非结构比例 / 18sp 字号地板），并落进 §4.7 令牌 + T12 的 `SwitchTrack` + V15 专验
11. **「组件排布要支持横屏和竖屏，竖屏下组件被挤压变形」（用户 2026-10-18 追加）** → §2-11 诊断 + §4.8 的四类**实测**失效（0 宽塌陷 / 高块参差 / 尾部抢宽 / 允许保留的等宽短标签）+ A1–A5 五条规则 + `SettingsChipRow` 原语（复用项目已真机验证的 `adaptiveColumns` 手动分行，⛔ 不用 FlowRow / horizontalScroll）+ G8 门禁（T13）+ V16 竖屏两档 / V17 B1 逐字一致
12. **「K 歌变调 / 倍速是运行时控制的内容，无需设置中持久保存」（用户 2026-10-18 追加裁决，推翻原 §6-13）** → §6 第 13 行改为「保留在播放页，**不进设置页**」+ 新增 13b 行把「DataStore 是否继续写」显式标为**超出本方案范围的未决项**；连带撤除：§3.2 的「K 歌」卡（原 ③④ 人声分离/模型 重编号为 ②③）、§4.5 表两行与两个 key（该轮文案项 22→20、🔧 19→18、总 key 24→22）、T4 的「这三块由 T10 追加」→「这两块」、T10 标题/Produces/Step 3/Step 4/commit 文案、T14 Consumes 与 key 名单。⛔ 播放页与 K 歌页的现有链路一律不动（本方案不碰运行态）。（该轮 key 计数先被第 13 条的缓存轮改写、**再被 v1.1 审校订正为「非 CACHE 17 个 key」**，现口径以 §4.5 注记与 §11 修订记录为准。）
13. **「缓存管理部分需要读取代码，看是否还有可管理的缓存数据未纳入设置页中管理，也加入进来」（用户 2026-10-18 追加要求，本轮唯一新增任务 T8b）** → §1.14 的全树落盘扫描（**表 1–14 可管理缓存 + 表 15–18"扫得到但不是缓存"的登记项**，含 4 类问题：5 处管不到 / 4 处统计不到 / 2 个按钮名不副实 / 3 个清除 API 写好没接线）+ §2-12 结论 + §3.2 卡②改按 `CacheGroup` 5 组展开 + **§4.5 CACHE 小节 8 行**（剩余空间 + 5 组名 + 19 目录标签 = **25 个名称**、9 条"有代价"说明、2 条新按钮说明，合计 **36 个新 key**，另有 3 处复用槽位与 1 处 `%d→%s` 既有 key 改造）+ **Task 8b**（`CacheInventory` **19 条**登记表 + 三卡 UI + **2 个新按钮 / 1 处改名扩容**，接线 3 条从未被调用的清除链路 + 修「清了数字不动」的统计口径）+ **G9 门**（§5.7，含三条判据与已知漏网面）+ §6-16/17 两行"评估过但不纳入" + **V18** 三形态真机判读 + §9 四条新风险行。
实施路径 → §7（**18 个任务**，TDD 红-绿-提交，无占位符）；验收与风险 → §8（18 项真机判读）/§9。

---

## §11 修订记录（本文档的版本控制）

| 版本 | 日期 | 性质 | 说明 |
|---|---|---|---|
| v1.0 | 2026-10-18 | 初稿 | 按用户 5 项交付 + 2026-10-18 各轮追加的 8 条要求/裁决写成 §1–§10 全文 |
| v1.1 | 2026-10-18 | **审校修订（当前版）** | 对 v1.0 做完整审阅：**所有 file:line 引用逐一对照真树核实**，所有计数重算，修正事实错误、消除内部矛盾、刷新会过期的基线数字。方案的设计与裁决**不变**，只改"与源码不符的陈述"。执行以本版为准 |

**v1.1 逐条变更明细**

**A. 源码行号 / 路径订正（引用与真树不一致）**

| # | 位置 | v1.0 | v1.1（订正后，均已实测） |
|---|---|---|---|
| A1 | §1.2 PLAYBACK 行 7 / 行 8 | 逐曲覆盖 `:216`、均衡器 `:223`（两行互换） | 对调为逐曲覆盖 `:223`、均衡器 `:216`（源码 `:216` = equalizer、`:223` = quality_override_clear） |
| A2 | §1.2 PLAYBACK 行 11 | `:284-375` | `:282-377` |
| A3 | §1.3 标题 | `:187-470` | `:187-495` |
| A4 | §1.4 LOCAL_MUSIC 标题 | `:45-66` | `:39-68`（表内三行 `:48`/`:55`/`:61` 复核无误，未改） |
| A5 | §1.5 DOWNLOAD 标题 | `:62-220` | `:56-223` |
| A6 | §1.6 连接状态文本 | `(:36-)` | `(:42-43)` |
| A7 | §1.12 歌词字号消费方 | `NowPlayingBranch.kt:94` | `:93-94`（`lyricsFontScale` :93 + `onLyricsFontScaleChange` :94） |
| A8 | T3 Step 3 | `PlayerSettingsState` `:38-48、:62`；`PlayerSettingsActions` `:69-72` | 10 个 karaoke 字段在 `:39-47` 与 `:61`；4 个回调在 `:70-73` |
| A9 | T3 Step 3 | `LocalMusicSettingsState :28-32`、`LocalMusicSettingsActions :35-38` | `:26-30`、`:33-36`（class 起止行实测） |
| A10 | T7 Step 4 卡① | 服务器配置 `:45-51`；断开连接 `:53-58`，未连接时 `enabled = false` | 服务器配置 `:45-50`；断开连接 `:53-57`，且**是条件渲染不是置灰** —— `ServerSettingsSection:51` 的 `if (state.isConnected && actions.onDisconnect != null)`（原述会把搬迁引向"多一颗按不动的按钮"） |
| A11 | T7 Step 4 卡② | 总开关 `:46-51`、已索引行 `:53-56`、重扫 `:59-64` | `:48-53` / `:55-58` / `:61-66` |

**B. 计数与分类订正（重算后与源码不符的陈述）**

| # | 位置 | v1.0 | v1.1 |
|---|---|---|---|
| B1 | §2-10 `focusedScale` | 字面量 18 处 | **19 处** + 1 处条件式（`SettingsComponents:187`）；1.03 口径去掉 `:187` |
| B2 | §2-10 `animationDurationMs` | 250×11 / 200×5 | 250×10 / 200×6 |
| B3 | §2-10 `pressedScale` | 0.98×6 | 0.98×10 |
| B4 | §2-10 / §4.7-⑥ 选中态 | 3 处 | **5 处**（补漏 `NetworkMusicSection:439`、`PhotoWallSettingsSection:609`；G7-C 作用域内 4 处 = 实底 1 `PhotoWallSettingsSection:609` + 手调 0.18 ×3 `GeneralSettingsSection:78/:138`、`NetworkMusicSection:439`） |
| B5 | §2-11 / §4.8-① / §10-11 | 12 个 `*Section.kt`（分区）；内容宽 `1920−64−240−40 ≈ 1536dp`（算式不通）；差 3.8× | **11 个**（目录实测）；`1920 − 2×32（页，SettingsScreen:426）− 240（侧栏，:438）− 24（LazyColumn start，:516）≈ 1592dp`，比例 407/1592=26%；差 **3.9×** |
| B6 | §4.5 表 | PHOTO_WALL ① 来源三开关 / SOURCES ② 本地曲库 标 🔧 | 改 **✅**（`settings_photo_wall_gallery_desc` 等、`settings_local_music_source_desc`/`_rescan_desc` 均已存在，G6-A 现状即过） |
| B7 | §4.5 表 SOURCES ④ | 按「重建索引 + APIC 封面」两个按钮计 🔧 | 改 **🔧（卡级）**：本卡只有一个按钮（重建索引，且已有 description）；**APIC 无独立入口**，是索引扫描成功后的自动任务（`NetworkMusicViewModel:612`）⇒ ⛔ 删 `settings_baidu_apic_desc` |
| B8 | §4.5 表 CACHE ② 卡片级 | key 写 `settings_cache_dir_desc`（**不存在**） | 改现存 `settings_cache_dir_size`（「当前缓存目录大小: %s」，仅 `CacheSettingsSection.kt:75` 消费；T8b 后腾出复用，去掉 `%s`） |
| B9 | §4.5 注记 / T14 | 🔧 21 处、非 CACHE key 20 个、T14「18 项 + 2 卡片级 = 20 个 key」 | 🔧 **19** 处（非 CACHE 14 + CACHE 5）、非 CACHE **17** 个（15 项文案 + 2 卡片级）、总数 **53** 个；T14 Step 4 / 标题 / §10-8 / §10-12 同步为 **15 + 2 = 17**，名单删 `settings_pw_source_desc` / `settings_local_music_desc` / `settings_baidu_apic_desc` |
| B10 | §6 净结果 | 「删除 8 个 DataStore key + 3 个 config 字段」 | 「删除 **5 个** DataStore key（`settings_lyrics_offset`/`settings_spectrum_enabled`/`weather_*` 三条）+ **3 个 `CloudDriveConfig` 字段**（`customAppKey`/`customSecretKey`/`apiDriftNotified`，见 `CloudDriveConfig.kt:31/:32/:36`）」—— 原稿把 config 字段误算成 DataStore key |
| B11 | §2-4 / §2-10 尾部文案 | 泛写「已开启/已关闭」 | 改为源码真实值「✓ 开启」/「␣␣␣␣关闭」（前导空格对齐 hack），避免照文档实现出另一种对齐 |

**C. 内部矛盾消除**

| # | 位置 | 矛盾 | v1.1 |
|---|---|---|---|
| C1 | T8b Step 7 vs §4.5 CACHE ② / `CacheInventory` | descRes「只给 6 条」 vs 表中「9 条」 | 统一为 **9 条代价说明挂在 10 行上**（`_local_lrc` 与 `_local_lrc_ext` 共用 `settings_cache_dir_local_lrc_desc`），并逐条列出 9 条文案 |
| C2 | T14 Step 4 复用槽位 | 写 `settings_cache_dir_desc`（不存在的 key） | 改 `settings_cache_dir_size` |
| C3 | T17 Step 4 | 「TV 1536dp」 | 「TV **1592dp**」（同 B5 算式） |
| C4 | §4.5 注记 vs §5.2/T12 | 开关轨 4 个 key（`settings_on/off`、`settings_state_on/off`）未说明是否计入 53 | 在 T14 Step 4 明注属 T12、不计入本步 17 个；53 的 口径（= 非 CACHE 17 + CACHE 36）不变 |

**D. 基线刷新（v1.0 引用的单测数字已过期）**

文首约束、T3 Step 5、T8b Step 8、T13 Step 7 的「基线」由「2026-10-12 全量 2040 例 / 186 类」改为**跑前重测口径**，并登记 v1.1 审校时的实测参考值：**2026-10-17 21:03 本地全量工件 = 211 个 XML / 2218 例 / 0 失败 0 skipped 0 error**（`app/build/test-results/testDebugUnitTest/`）。⛔ 这些是参考值不是断言值，执行任务时先重跑一遍取当次数。

**E. 版本控制约定**

- 本文档的后续修订**在本表追加一行**（版本号递增、写明改了什么），⛔ 不静默改稿；同一轮里的多处修改合并为一行，明细用 A/B/C/D 分类。
- 已核对的**未变项**（v1.1 复核仍与真树一致、无需改动）：§5.7 的 G1–G9 门禁引用、`scripts/measure_switch_reference.py` 存在性、`KaraokePickerTvFocusScanTest` 引用、孤儿屏名（`KaraokePickerScreen` / `BridgeFLowScreen` 全文未出现，为审阅中的误记，无需修改）、§1.14 表 1–18 的目录与 API 行号。
- 本方案落地后按 AGENTS.md 文档生命周期 `git mv` 进 `docs/archive/`，本表随之作为该文档的版本史保留。

