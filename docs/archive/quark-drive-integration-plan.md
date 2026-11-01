# 夸克网盘接入可行性评估与开发计划

> 🗄️ **归档状态（2026-10-18）**：所有者已裁决**放弃夸克网盘接入路线**，本篇随之下线、**不再维护**。
> 保留价值有三：① §2.2 的两条平台线对照（小程序 vs 网盘）**是官方原文证据**，下次有人提"接夸克"可直接引，
> 不必重走一遍调研；② §3 的能力缺口清单（G1–G8）是**独立于凭据问题**的技术事实；③ §10 的 **WebDAV 落地要点**
> 是本篇唯一仍可执行的产出 —— 若日后做 WebDAV/Alist，那节是起点。
> ⚠️ 本篇**从未提交入库**（2026-10-18 建于工作区、同日归档），因此是**普通移动而非 `git mv`**，无历史可保留。
>
> 状态：**评估已收敛，路线已否决** — 原 §7 P0-1（"官方是否开放个人开发者入驻"）已由所有者提供的
> `open.quark.cn` 登录态正文**关闭**，结论是**否定的、且是三重封锁**。
>
> **结论先说（2026-10-18 修订）**：
> 1. 用户提供的那个 `open.quark.cn` 页面**不是网盘开放平台**，而是**夸克小程序开发者平台**。它发的是
>    "小程序登录授权"凭据（`app_id`/`client_id`/`client_key`/`open_id`/`session_key`，MD5 签名，网关 `api-mini-program.quark.cn`），
>    与 README 里那套"网盘 HTTP API"凭据（`client_id`/`sign_key`，SHA256 签名，网关 `open-api-drive.quark.cn`）**是两条不同的产品线，不可互换**。
> 2. 该小程序平台的**「文件」API 标注"（建设中）"** —— 即使拿到资质，官方目前也**没有**给第三方提供网盘文件接口。
> 3. `app_id` **需公司主体审批**、`client_id` **需小程序审批** ⇒ 个人 / GPL 开源项目**申请不到**。
> 4. ⇒ **走"申请夸克开发者凭据 → 自研网盘音源"这条路，经官方原文确认不可达。**
> 5. ⇒ 现实路线收敛为 **T5「WebDAV / Alist 通用桥接」**（夸克侧授权由用户自建的 alist 负责，本项目只实现标准 WebDAV）。
>    网盘 API 的专属实现**降级为"仅当凭据意外可得时的备选"**。
>
> ⚠️ 但 §3 的能力缺口（无列目录、搜索死顶 100、无音频元数据）仍然真实存在 —— 它们解释了**为什么当初不建议**做夸克专属后端，
> 而 §2.2/§4 的凭据事实解释了**为什么现在做不了**。两件事别混。

---

## 1. 信息来源与可信度分级

本文档的所有技术断言按下表分级：**接口能力/端点/签名以 S1+S2 为依据，平台资质与准入以 S3（官方原文）为权威**。S4 一律禁引。

| 级别 | 来源 | 说明 | 可用性 |
|:---:|---|---|---|
| **S1** | `wlor0623/quarkclouddrive-skill` 仓库 `references/*.md`（9 份，共 ~96 KB）+ `SKILL.md` | 该 Skill 的**接口/行为说明书**，含错误码、参数、行为约束。README 自称"枚举值与错误码结合官方文档核对" | ✅ 主要依据 |
| **S2** | `wlor0623/quark-api` 仓库 `README.md`（1106 行） | **自称非官方**，是对 `quark-drive.cjs` 打包产物做静态逆向整理出来的端点/签名。其中两条 errno（10001/11001）标注为"实测验证" | ✅ 端点与签名依据，但**逐条需实测复核** |
| **S3** | `https://open.quark.cn/api/device#dbKRb` + `/tutorial/login`（用户提供的第 2 个链接） | ✅ **已读取**（2026-10-18 由所有者以登录态粘贴正文；agent 侧抓取只拿到加载壳，该站是登录态 SPA）。正文为「小程序开发接入指南 → 一、开发必须」与「设备 → 登录」两页 | ✅ **官方原文，权威**。裁决依据见 §2.2 |
| **S4** | 搜索引擎返回的 CSDN / 百家号类"接入指南"文章 | **已判定为 AI 内容农场伪造**，出现 `quarkpan.com/oauth/authorize`、`api.quark.cn/device/register`、"TPM 密钥签 JWT"、"WASM Proof-of-Work 解 429 挑战"、`X-Quark-Signature` 等本项目实测不存在的东西 | ⛔ **禁止引用**，见 §9 |

> ⚠️ 关键背景：S1/S2 出自同一个 **AI Agent Skill**（"夸克网盘 Skill"，运行在 OpenClaw / 千问 之类的 agent 运行时里）。
> 它的设计目标是「对话中找文件、存文件、整理相册」，**不是**「给第三方 App 做媒体库」。
> 这个定位差异是本文档几乎所有能力缺口的根因。

---

## 2. 网关与鉴权（S2）

| 项 | 值 |
|---|---|
| API 网关 | `https://open-api-drive.quark.cn` |
| 授权页 / H5 | `https://pan.quark.cn` |
| 客态（免登录）分享 | `https://drive-pc.quark.cn`、`https://api-album.quark.cn` |
| 签名头 | `x-pan-client-id`、`x-pan-tm`（毫秒时间戳）、`x-pan-token` |
| 签名算法 | `x-pan-token = SHA256_hex_lower("{METHOD}&{PATH}&{TM}&{SIGN_KEY}")`，**PATH 不含域名与查询串** |
| 通用查询参数 | `req_id`（UUID）、`access_token`、`device_id`、`platform` |
| 响应包络 | `{status, errno, error_info, agent_msg, req_id, data}`；成功判定 `status==0 && data 存在` |

**没有 SDK、纯 HTTP+JSON**，本项目现有的 OkHttp + Gson 栈可直接调用，**不引入任何新依赖、不影响 APK 体积**（SHA-256 在 JDK 里）。

### 2.1 凭据从哪来 —— 本项目的头号门槛

S2 的 README §1.4 披露了那个 Skill **打包产物里硬编码的默认凭据**（"wild 模式"）：

```
client_id = "third_party_agent"
sign_key  = "cf134812e2de4032bd1cb7c3727e84b3"
device_id = "wild_claw"
scope     = "clouddrive.base clouddrive.netdisk"
```

> ⛔ **这套凭据绝不能内置进 NASMusicTV 的 APK。** 理由（按严重度排序）：
> 1. 它不是本项目的应用身份，是**别人 AI Agent 项目的客户端身份**；用它调网关 = 冒充他人客户端。
> 2. `sign_key` 已在一个**公开仓库里静态泄露**，签名对任意第三方都是可伪造的 —— 把它写进 GPL 开源项目等于**分发一个已知泄露的密钥**，法律与合规风险由项目承担。
> 3. 夸克服务端随时可以按 `client_id` 封禁/收紧该身份，本项目的功能会**毫无预警地整体失效**（且无法自救，因为申请不到工单身份）。
> 4. 该身份对应的是"网盘 Skill 授权"体系（撤销须在**夸克网盘独立端 App** → 我的 → 登录授权管理 → **网盘 Skill 授权** 完成，见 S1 `auth.md`），与"第三方 App 接入"是两条不同的产品通道。
> 5. S1 明确记载：**智能搜索 / 相册整理 / 知识库问答属"AI 高级功能"，当前仅开放 5000 体验名额，先到先得。** 若搜索能力实际挂在配额上，任何以搜索为核心的设计都可能在配额耗尽后直接不可用。

**因此 §4 的路线取舍本质上是在选"凭据从哪来"，而不是"代码怎么写"。**

### 2.2 ⭐ 两条平台线，别混为一谈（S3 官方原文裁决）

所有者提供的 `open.quark.cn` 正文（「开发必须 · 1.1 配置参数 / 1.2 服务端功能域名」+「设备 · 登录 · code2Session」）证明它是一个**小程序**平台，与 README 描述的**网盘 HTTP 网关**是两套东西：

| 维度 | 夸克**小程序**开放平台（`open.quark.cn`，S3 官方原文） | 夸克**网盘** HTTP API（`open-api-drive.quark.cn`，S2 逆向文档） |
|---|---|---|
| 交付形态 | 跑在**夸克 App 内**的小程序（`qk.*` JS API、组件、分包加载、跨域配置、track 埋点） | 外部客户端直连的纯 HTTP+JSON |
| 身份凭据 | `app_id`（**公司主体审批后分配，一个主体唯一**）、`client_id`（**小程序审批后分配**）、`client_key` | `client_id`、`sign_key` |
| 用户标识 | `open_id`（小程序内唯一）、`session_key`（≤512 字节） | `access_token` + `refresh_token` + `device_id` + `user_id` |
| 网关 | `https://api-mini-program.quark.cn` | `https://open-api-drive.quark.cn` |
| 签名 | `sign = MD5(client_id + client_key + request_id + param_str)`，参数按 key 升序 `k=v&` 拼接 | `x-pan-token = SHA256_hex("{METHOD}&{PATH}&{TM}&{SIGN_KEY}")` |
| 服务端要求 | `code2Session` **官方明示"应在开发者服务端调用"** ⇒ `client_key` 不能进客户端 ⇒ 独立 APK 需**自建后端** | 客户端直连（Skill 就是这么做的） |
| 有没有网盘文件能力 | **「文件（建设中）」**；同样"建设中"的还有 **分享 / 媒体 / 运行环境**。已开放的只有 登录 / 权限 / 组件 / 调试 / 统计 / 主题 | 有（搜索/上传/下载/分享/转存/AI），但见 §3.2 缺口 |

> 🔴 **三条封锁同时成立**：① 资质需**公司主体**（个人申请不到）② 形态是**小程序**（不是我们的独立 APK，等于"把音乐播放器搬进夸克里"，与本项目定位相反）③ **文件 API 尚未开放**（"建设中"）。
> ③ 单独就足以判死：哪怕前两条解决，今天也没有接口可调。
>
> ⚠️ **最容易踩的坑**：两边的字段名都叫 `client_id`，签名却一个是 MD5 拼 `k=v` 串、一个是 SHA256 拼 `METHOD&PATH&TM&KEY`。
> 拿小程序的 `client_id`/`client_key` 去签 `open-api-drive.quark.cn` 的请求**必然 10001「签名验证失败，禁止访问」**，
> 而这不是"实现 bug"，是用错了平台。已写成 §9 的风险项与 §7.5 的断言。
>
> ❓ **残留未知**（官方页面看不到入口，只能问官方）：README 那套 `open-api-drive.quark.cn` 的 `client_id`/`sign_key`
> 究竟通过哪条通道发放。已知线索是 S2 提到的 **千问开放平台**（夸克网盘/夸克扫描王为首批接入服务，面向"生态伙伴和开发者"，
> 终端类型为手机/PC/AI 眼镜）与那个 Skill 的「网盘 Skill 授权」体系 —— **都不是个人自助申请的入口**。
> 若要走通，需要所有者向夸克侧申请；在此之前，本项目**不应**为夸克专属 API 投入实现工时。

---

## 3. 接口能力盘点 vs 本项目契约要求

本项目有两条**语义完全不同**的接入契约，必须先确定夸克走哪条：

| 契约 | 位置 | 硬性要求 | 夸克是否满足 |
|---|---|---|---|
| **A. NAS 后端**（`BackendAdapter`） | [BackendAdapter.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt) | `getAlbums()` / `getArtists()` / `getSongs(limit, offset)` / `getSongsTotalCount()` —— **全库枚举 + 分页 + 总数** | ❌ **不满足**，见下方缺口 G1 |
| **B. 网络音源**（`NetworkMusicService`） | [NetworkMusicService.kt:31](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt#L31) | `search()` + `resolvePlayUrl()` + `resolveLyrics()`；`searchByDirectory()` 专为网盘设计（同文件 :49） | ⚠️ **部分满足**，但缺元数据 |

### 3.1 已确认的可用能力

| 能力 | 端点 | 备注 |
|---|---|---|
| 关键词/语义搜索 | `POST /agent/v1/file/search` | 见缺口 G1 |
| 文件详情 | `GET /open/v1/file/info?fid=&fetch_full_path=1` | 单 fid，可回查 |
| 建目录（幂等） | `POST /open/v1/dir` | 同名返回已有 fid |
| 移动 / 任务查询 | `POST /open/v1/file/move`、`/open/v1/task/query` | `status` 0待处理/1处理中/2完成/3失败/4暂停 |
| 下载直链 | `POST /open/v1/file/get_download_url`（单个）、`multi_get_download_url`（批量） | 见 G4 |
| 分片上传（秒传+断点） | `upload_pre`→`update/hash`→`get_upload_urls`→PUT OSS→`upload_finish` | 本项目不需要写入 |
| **分享详情 + 分页浏览** | `POST /open/v1/share/detail`（取 `stoken`）、`GET /open/v1/share/page_detail`（`_page`/`_size`/`pdir_fid`/`_fetch_total`） | ⭐ **唯一确认能"分页枚举一棵目录树"的通道** |
| 转存 | `POST /open/v1/share/saveas`（`task_type=17`，异步走 task/query） | 需 `share_fid_token` |
| 用户/会员信息 | `/open/v1/user/info`、`/open/v1/user/get_vip_info`（`used`/`capacity` 字节） | 可用作探活 + 容量提示 |

### 3.2 缺口清单（决定可行性的一组事实）

| ID | 缺口 | 证据 | 影响 |
|:---:|---|---|---|
| **G1** | **没有"列目录"接口。** `references/` 九份说明书里**没有一份**是目录浏览；文件访问一律从 `fid` 或 `search` 出发 | S1 目录结构 + S2 §4 端点清单 | 🔴 **致命**。百度那套「`listall` + `recursion=1` 全量枚举 → 本地索引」在夸克上**没有等价物**（对照 [BaiduPanApi.kt:149](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduPanApi.kt#L149) 与 `BaiduFileIndexCache`）。用户"播放我网盘里全部音乐"做不到 |
| **G2** | **搜索上限 100 条且官方说明书明示"不支持分页"**；S2 的 README 却列出 `page` 参数 —— **两份来源互相矛盾** | S1 `file-search.md:42`（"不支持分页，一次最多返回 100 条结果"）vs S2 §4.1（`page` 字段） | 🔴 即使 `total=2274`，一次也拿不回全量 fid。矛盾必须由 P0-3 实测裁决 |
| **G3** | 搜索是**语义/AI 检索**，且智能搜索疑似挂在 **5000 名额**上 | S1 `auth.md:80` 欢迎文案 + `file-search.md:46`（支持描述画面、时间地点人物组合） | 🟠 稳定性/配额不可控，不能当作可依赖的库枚举手段 |
| **G4** | 音频**元数据全缺**：条目只有 `fid/parent_fid/filename/size/file_type/category/created_at/updated_at/big_thumbnail`，**无 duration、无比特率、无 ID3、无专辑/歌手**；`big_thumbnail` 是图片/视频类才有 | S2 §4.1 字段表；对照百度侧 `fileMetas` 能拿到 `media_info.duration_ms`/`bitrate`/`thumbs`（[BaiduPanApi.kt:423](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduPanApi.kt#L423)） | 🟠 时长/专辑/歌手/封面必须**客户端自建**：文件名解析（项目已有 `ArtistSplitter`、`PinyinUtils`、命名约定）或下载后解析 ID3（项目已有 [Id3v2Parser.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/baidu/Id3v2Parser.kt)）。⚠️ NowPlaying 进度条与进度记忆是**按毫秒**驱动的，无 duration 需明确降级表现 |
| **G5** | 下载**必须带 Cookie**：`Cookie: x_pan_client_id={client_id}; x_pan_access_token={access_token}`，且 `download_url` 的 host 是 OSS/CDN 域名、**未记录** | S2 §6.3 | 🟠 必须复用 `BackendAuthHeaders` provider → `BaiduHttpDataSourceFactory` 拦截器注入链路；⚠️ 该项目已明确警告**不许放宽 host 匹配，否则令牌随 302 泄漏到 CDN**（AGENTS.md）。P0-4 必须先量出真实 host 再写精确匹配 |
| **G6** | 应用可访问范围是否受沙箱限制 —— 仍**未知**（S3 已读，但那是小程序平台，不含网盘 API 条款；百度侧有硬约束：自 2026-08-31 起应用仅可访问 `/apps/{应用名}`，见 [BaiduNetdiskConfig.kt:27](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduNetdiskConfig.kt#L27)） | S3 未覆盖该问题（§2.2） | 🟢 **已降级**：路线冻结后不再阻塞；若凭据到位再启用 T3，须重查 |
| **G7** | `get_download_url` 是否支持 `Range`（决定能否 seek、能否流式播放而非整曲下载） | S2 §6.3 只在示例注释里提"可选，支持断点续传"，**未经本项目实测** | 🔴 **go/no-go**。若不支持 Range，ExoPlayer 渐进播放直接不可行，只能退化成"先下载后播" |
| **G8** | 非会员限速 / 免费容量 | 无来源 | 🟡 决定产品体验，须真机测（SVIP 与 NORMAL 对照） |

> **G1+G2+G7 三条决定了：直接把夸克实现成一个 `BackendAdapter`（ServerConnect 里第 6 个后端）是走不通的。**

---

## 4. 路线选择

| 路线 | 凭据来源 | 能达成什么 | 代价与风险 | 评级 |
|---|---|---|---|:---:|
| **T0 内置 wild 凭据** | 泄露的 `third_party_agent` + `cf1348...` | 技术上最快 | ⛔ 见 §2.1 五条理由 —— **不建议，本文档否决它** | ❌ |
| **T1 用户自填凭据** | 用户自己申请到的网盘 `client_id`/`sign_key`，app **不内置任何默认值**（留空即不显示该源） | 走完整 T3 功能 | 机制上项目**已有先例**（`CloudDriveConfig.customAppKey/customSecretKey` + 设置页"高级：自填 AppKey"）。⚠️ 但 S3 证实**个人申请不到任何凭据通道** ⇒ 用户"自填"也无从填起 → **实质受众为 0** | ❌（被 T2 连坐） |
| **T2 走官方开放平台入驻** | 本项目自己的网盘 `client_id`/`sign_key` | 与 T1 同，且可发布、可长期演进 | 🔴 **经 S3 官方原文判定不可达**：① `app_id` 需公司主体审批 ② 该平台的交付形态是**小程序**（不是独立 APK）③ **「文件」API 标注"建设中"**，今天没有接口可调。原评级 ⭐⭐⭐⭐ 作废 | ❌ |
| **T3 分享链接转存式** | 同 T1/T2 | 「用户粘贴一个夸克分享链接（无损音乐合集在国内极常见）→ app 分页枚举整棵树 → 转存到 app 目录 → 建索引 → 在线播放」 | **设计上最优**（列目录由 `share/page_detail` 提供，天然分页，绕开 G1）；代价是转存**双份占容量**、转存是异步任务需轮询。🔴 **但同样卡在凭据上** —— 没有 `client_id`/`sign_key` 就签不出 `x-pan-token`。→ **保留为"凭据到位后的首选实现形态"**，当前不可开工 | ⭐⭐⭐⭐（阻塞中） |
| **T4 网页端 Cookie 逆向**（`file/sort` 等） | 用户手工导出的登录 cookie | 能列目录、能拿直链，无需任何资质 | ToS 灰 + 风控/封号 + TV 上"让用户粘 cookie"体验很差 + 与本项目"凭据 AES-GCM 加密存储"的假设冲突（cookie 寿命短）。**仅可作为个人实验分支，不进主线、不分发** | ⭐ |
| **T5 WebDAV / Alist 桥接** | **无需任何夸克凭据** —— 用户自建 alist，夸克侧授权由 alist 负责 | 一次实现，通吃夸克/阿里/115/OneDrive，规避单平台 API 变更与封号风险；本项目代码保持协议干净，**合规暴露留在用户自建的桥接侧** | 需先实现 WebDAV 后端（本项目**尚无**：全仓库 grep `webdav|alist` 零命中）。协议简单（PROPFIND + Basic auth + Range），直接复用现有 `BackendAdapter` 契约，且**列目录是 WebDAV 的原生能力** ⇒ G1/G2/G4 一次性消失（WebDAV 能拿到 `Content-Length`、`getlastmodified`，播放走标准 Range 流式，无 Cookie 问题） | ⭐⭐⭐⭐⭐ **唯一现实推荐** |

> **T5 并非新想法。** 项目自己的归档方案（`docs/archive/aliyundrive-support-plan.md:20-23`）已经为阿里云盘评估过同一组选项，
> 当时给「接入其他网盘（115/夸克/天翼云）」打 ⭐⭐（理由原文即"需各自申请、开发工作量重复"），给「WebDAV 通用后端」打 ⭐⭐⭐⭐。
> 那个结论今天**没有被推翻，只是没被执行**（`CloudDriveType.IMPLEMENTED` 仍只有 `BAIDU`）。
> 所以本次评估必须回答一个前置问题：**夸克专属实现 vs WebDAV 通用后端，先做哪个？**（= §8 决策 Q4）

### 4.1 推荐（2026-10-18 修订）

**推荐 = T5（WebDAV / Alist 通用桥接），且这是当前唯一可直接开工的路线。**

理由（按决定性排序）：
1. **T2 被官方原文判死**（公司主体 + 小程序形态 + 文件 API 未开放），T1 因 T2 而死，T3 因缺凭据而无法开工 —— 夸克专属实现的**前置条件今天不成立**，投工时就是投死路。
2. T5 **不需要任何夸克资质**，且把"非官方访问"的实现与风险留在用户自建的 alist 侧，本项目只实现标准协议 —— 与本项目"家庭局域网、用户自填任意 NAS 地址"的既有取向一致。
3. T5 顺手解决了本项目的**同类需求全集**：阿里云盘（那份归档方案至今没落地）、115、123、OneDrive、`CloudDriveType` 里剩下的两个占位枚举，以及"用户想把任意网盘当 NAS 后端"。
4. T5 让 §3.2 里最难的三个缺口（G1 无列目录 / G2 搜索上限 100 / G4 无音频元数据）**整体消失**，因为 WebDAV 原生就是"列目录 + Range 流式"，可以直接落进 `BackendAdapter` 契约而不必塞进 `NetworkMusicService`。

**若未来夸克凭据意外可得**（所有者从夸克侧拿到 `open-api-drive.quark.cn` 的 `client_id`/`sign_key`）：
再启用 T3 形态（分享链接 → 分页枚举 → 转存 → 索引 → 播放，挂 `NetworkMusicService`，`sourceId = "quark"`），
届时 §3.2 的 G1–G8 与 §7 的 P0/M0–M4 仍然有效，可直接复用。
**在此之前：不写夸克专属代码，不内置任何第三方凭据。**

---

## 5. 授权链路设计（网盘 agent OAuth —— TV 形态适配良好，但⚠️前提未满足）

> ⚠️ **前提未满足**：本节描述的是 `open-api-drive.quark.cn` 的 **agent OAuth** 链路，需要网盘 `client_id`/`sign_key`。
> §2.2 已证明这套凭据**个人申请不到**，所以本节目前是**"凭据到位后才适用"的设计储备**。
> ⛔ **不要**把 S3 那套 `qk.login` + `code2Session`（小程序登录，MD5 签名，`api-mini-program.quark.cn`）当成网盘授权 —— 它只给 `open_id`/`session_key`，**拿不到任何文件访问能力**。

S2 §3 给出两条 OAuth 路径，对 Android TV 的适配性差异巨大：

| 路径 | 端点 | TV 适配性 |
|---|---|---|
| **Agent 授权码模式**（Skill 默认） | `POST /agent/v1/get_authorize_page_url` → 返回 `authorize_page_url` + `page_code` → **轮询** `GET /agent/v1/oauth/get_aac_by_pagecode` → 得 `agent_auth_code` → `GET /agent/v1/oauth/agent_auth_code` → 换 `access_token` + `refresh_token`（含 `*_expires_at` 毫秒） | ✅ **最优**：无需 redirect_uri、无需本地 HTTP 服务、无需用户手抄 code。TV 只需显示一个 URL/二维码，用户在手机浏览器打开完成授权，app 轮询拿结果 —— **与本项目百度网盘的设备码对话框是同一种 UI 形态**，可直接照搬 [BaiduAuthDialog.kt](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/netdisk/BaiduAuthDialog.kt) 的"大字 user_code + 进度条 + 轮询"结构 |
| 标准 OAuth2 授权码模式 | `https://pan.quark.cn/open/v1/oauth/authorize?...&redirect_uri=` → `GET /open/v1/oauth/token` | ❌ **TV 上不可用**：必须有可达的回调地址。S1 `auth.md:26` 也印证 Skill 在 PC 上的做法是"启动本地授权服务器并阻塞等待"，电视端做不到 |
| 刷新 | `POST /agent/v1/oauth/access_token/rotate`（`refresh_token` + `device_id`） | ✅ token 持久化模型可与百度对齐（`data/model/BaiduTokens.kt` 的形态） |
| 撤销 | `POST /api/v3/oauth_access_token/revoke_auth`，或 H5 解绑页（须在**夸克网盘独立端 App** 内打开） | 🟡 解绑入口对用户不友好，UI 需给二维码 + 文字路径双通道（S1 `auth.md:109-120` 已有可直接借用的人话文案） |

⚠️ **S1 记录的两条授权行为约束，必须落到 UI**：
- 已授权账号重复登录会返回 `code: -118`（表示授权仍有效），此时**不得**再次调用登录、不得提示成功（`auth.md:47-53`）；
- 授权超时的兜底是"让用户从跳转后的 URL 里复制 `code` 手工回填"（`auth.md:87-99`）—— TV 上需要一个手工输入授权码的输入框作为降级路径。

---

## 6. 与现有代码的接缝（已核对到行）

夸克如果落地，**90% 的接缝是百度那条链路的现成件**，这也是工作量可控的原因：

| 关注点 | 现有实现 | 夸克要做的事 |
|---|---|---|
| 音源注册 | `NetworkMusicManager.registerService/unregisterService`（[NetworkMusicManager.kt:396](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicManager.kt#L396)，ConcurrentHashMap 运行时开关） | 提供 `QuarkNetdiskService(sourceId="quark")`，开关开启才注册 |
| 歌曲 ID 前缀 | `BaiduNetdiskConfig.songId()` → `ntwk_baidu_${fsId}`（:147） | 新增 `ntwk_quark_${fid}`；`NetworkSongId` 路由需认识新前缀 |
| 播放直链头注入 | `BackendRegistry` → `BackendAuthHeaders`（provider 实时读）→ `BaiduHttpDataSourceFactory` 拦截器按 **host 精确匹配** | 注入 G5 的两个 Cookie；host 名单来自 P0-4 实测，⛔ 不许放宽 |
| 凭据存放 | `buildConfigField("BAIDU_APP_ID"/"BAIDU_APP_SECRET")`（[build.gradle.kts:55](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/build.gradle.kts#L55)）+ gitignored `keystore.properties` + 已提交的 `keystore.properties.example` 模板 | 同样加 `QUARK_CLIENT_ID`/`QUARK_SIGN_KEY`，example 里**只写注释、值留空** |
| token 加密 | `AppPreferences` 按 `CloudDriveType` 存 JSON，`tokens` 用 CryptoUtils AES-GCM（[AppPreferences.kt:1763](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt#L1763)） | 复用；⚠️ CI 需要 `CRYPTO_PASSPHRASE` secret，否则 push/tag 被 fail-fast 挡下 |
| **既有技术债** | `CloudDriveConfig.tokens` 类型**写死 `BaiduTokens`**，源码注释自己标了"多网盘扩展时需泛型化或 sealed"（[CloudDriveConfig.kt:13](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/model/CloudDriveConfig.kt#L13)）；`baiduConfigFlow` 也只取 `BAIDU.key`（[AppPreferences.kt:1787](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt#L1787)） | **M0 必须先还这笔债**（tokens → sealed / `cloudDriveConfigFlow(type)`），否则夸克是往死路里加代码 |
| 设置页 | `CloudDriveType.QUARK` 已存在，当前在 `PLACEHOLDER` 里灰显"敬请期待"（[CloudDriveType.kt:24](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/data/model/CloudDriveType.kt#L24)，渲染点 `BaiduPanSettingsSection.kt:193`） | 把 QUARK 移入 `IMPLEMENTED` → 占位自动消失，需补真实卡片 |
| API 漂移防护 | `ApiProbe.computeFieldFingerprint` + `API_PROBE_BASELINE`（百度侧至今仍是空串 = 未固化）+ `apiDrifted`/`apiDriftNotified` | 非官方网关**更需要**这套指纹门，直接复用 |
| 错误码 i18n | `ERRNO_MAP` → `R.string.baidu_errno_*`（`values` + `values-en`） | 建 `quark_errno_*`；⚠️ 缺 `values-en` 键的历史教训是"编译通过、渲染时崩"（`VisualizerThemeStringsGateTest` 同源问题） |

---

## 7. 分期计划

### P0 —— 可行性验证（**已冻结：除 P0-1 外全部依赖夸克网盘凭据，凭据不可得则不执行**；预计 1.5 人日 + 一次真机）

| # | 验证项 | 方法 | 状态 / 通过标准 |
|---|---|---|---|
| P0-1 | 补齐 S3：官方开放平台准入 | ✅ **已完成（2026-10-18，所有者提供登录态正文）** | **结论：不可达** —— ① `app_id` 需**公司主体**审批 ② 形态是**小程序**（非独立 APK）③ **「文件」API「建设中」**，无可调接口。详见 §2.2。**残留**：`open-api-drive.quark.cn` 的 `client_id`/`sign_key` 由哪条通道发放（页面未见入口，只能问官方 / 千问开放平台生态入驻） |
| P0-2 | 拿到一份**属于本项目**的网盘 `client_id`/`sign_key` | ⛔ **阻塞点，且当前无解**（原设计"T2 不行则 T1 自填"两条均已被 P0-1 连坐否决） | **唯一出路**：所有者向夸克侧申请到生态/开发者接入凭据；申请不到 ⇒ 本表余下项与 M1–M4 **全部取消**，改投 T5 |
| P0-3 | **搜索分页真相**（G2 矛盾裁决） | 同一 keyword 带 `page=1/2/3` 对比返回 fid 集合是否变化 | 得出"能分页"或"死顶 100 条"的确定结论 |
| P0-4 | **`download_url` 的 host 与 Range**（G5/G7） | 取直链后发 `Range: bytes=0-1023`，看是否回 `206` + `Content-Range`；记录最终域名 | 206 可用 ⇒ 支持流式播放；否则本计划降级为"仅下载后播放" |
| P0-5 | **API 22 创维 TV 的证书链** | 真机安装诊断入口直连 `open-api-drive.quark.cn` 与 P0-4 拿到的 CDN 域名 | 两条域名握手都成功。⚠️ 本项目已知创维 TV 的 162 根证书库**不信任 Let's Encrypt**（`project-tv-api22-lets-encrypt-untrusted`），任一域名走 LE 链即**直接判死**，无需再看后面所有项 |
| P0-6 | 会员/非会员对照（G8） | 同一文件用 NORMAL 与 SVIP 账号各测一次下载速率 | 给出速率区间，决定是否需要"建议开通会员"文案 |

> ⛔ **P0 全部是"真机/真凭据"的取证，不是单元测试。** 项目已有硬规矩：**测试不得探测端点连通性**，URL 只当字符串数据处理（见 §7.5 门与 memory `feedback-no-endpoint-connectivity-tests`）。
> P0 的探针应做成 debug 构建里一个 `--es` 参数可达的诊断页，输出打到 `logs_temp/`（⛔ 不进仓库根目录）。

> 🔒 **M1–M4 冻结**：以下里程碑全部以"P0-2 拿到本项目自己的网盘 `client_id`/`sign_key`"为前置。
> 按 §2.2 的结论，该前置当前不成立 ⇒ **只保留设计储备，不排工时**。
> 唯一**不受凭据阻塞**的是 M0 的第 1 项（`CloudDriveConfig.tokens` 泛型化）—— 那是仓库里自己标注的既有技术债，
> 无论将来接夸克、阿里还是 123 都要先还。

### M0 —— 还债 + 骨架（约 2 人日；仅第 1 项可立即执行，第 2/3 项随 M1 冻结）
1. `CloudDriveConfig.tokens` 泛型化/sealed 化；`baiduConfigFlow` → `cloudDriveConfigFlow(type)`（`AppPreferences.kt:1787`）。
2. `build.gradle.kts` 增 `QUARK_CLIENT_ID`/`QUARK_SIGN_KEY`（从 `keystore.properties` 读，**默认空**）+ `keystore.properties.example` 补注释模板。
3. `backend/network/quark/QuarkSigner.kt`（纯函数）+ `QuarkApiConfig.kt`（端点/参数/errno 集中表，对齐 `BaiduNetdiskConfig` 的组织方式）。

### M1 —— 只读音源 MVP（约 5 人日）
`QuarkOAuthClient`（agent 授权页 + 轮询 + rotate 刷新 + `-118` 分支 + 手工回填 code 降级）→ `QuarkDriveApi`（search/file info/get_download_url/user info）→ `QuarkNetdiskService : NetworkMusicService` → `QuarkStreamFactory` + host 精确匹配的 Cookie 注入 → 设置页夸克卡片（授权状态、昵称/会员/容量、索引状态、重建按钮）。
**验收**：TV 上完成授权 → 搜索到音频 → 点播放能出声、能进 NextPlaying；断开/过期 token 能自愈刷新。

### M2 —— 分享链接转存式（约 4 人日，T3 主体）
`QuarkShareIndexer`：`share/detail` 取 `stoken` → `share/page_detail` 递归翻页（含 `pdir_fid` 子目录 + `_fetch_total`）→ 过滤音频 → `share/saveas`（携带 `fid_token_list`）→ `task/query` 轮询 → 入库。UI 需要一个「粘贴分享链接」对话框 + 提取码输入 + "转存会占用双倍容量"的显式二次确认（复用 `StorageGuard` 的容量语义）。

### M3 —— 元数据补齐（约 4 人日，取决于 G4）
文件名解析（目录即标签：`searchByDirectory` 契约已为网盘设计好）+ 首帧 ID3 解析回填 duration/cover（复用 `Id3v2Parser`/`EmbeddedCoverExtractor`）+ 封面走 `searchCoverUrl`/ItunesCoverSearcher 兜底 + 歌词沿用 `LyricsNetworkProvider` 模糊匹配。**明确接受**：无后端元数据 ⇒ 播放列表时长可能缺失，UI 需空态表现。

### M4 —— 护栏与发版（约 2 人日）
`ApiProbe` 指纹基线固化（把 `API_PROBE_BASELINE` 这套机制真正跑起来，百度侧还是空的）+ ProGuard keep 规则 + 错误码双语齐全 + CHANGELOG 与 `docs/technical-overview.md` §10 同步 + 真机回归（含创维 5.1.1 基准机）。

**合计**：P0 1.5 人日（+真机，**已因 P0-2 无解而停止**） / M0–M4 约 17 人日（**已冻结**）。

### 7.5 门（gate 测试，全部纯逻辑、零网络）
| 门 | 断言 |
|---|---|
| `QuarkSignerTest` | 固定向量验签：`{METHOD大写, PATH 不含查询串, TM, KEY}` → hex 小写；改任一项签名必变；**负例必须命中自己的 tag** |
| `QuarkEnvelopeTest` | `status/errno/data` 包络解析；10001/11001/31001/32003/32004/41004/41007/41008 → string 资源齐全（含 `values-en`） |
| `QuarkParseTest` | `BrowseFileItem`、`share/page_detail`、`get_download_url` 响应 fixture 解析（fixture 是本地 JSON，不打网络） |
| `QuarkStreamHostGateTest` | Cookie 只在**精确 host** 注入；断言"跨 host / 跟 302 时不携带 token" —— 防令牌泄漏 |
| `QuarkWiringGateTest` | 对齐 `ProviderMirrorWiringGateTest` 口径：开关关闭不注册服务、不调任何 API；开启才 `registerService("quark")` |
| `CloudDriveTypeGateTest` | `IMPLEMENTED`/`PLACEHOLDER` 变更后，设置页不再出现夸克占位、双语显示键完整 |

---

## 8. 决策状态（Q1 已由所有者 2026-10-18 裁决关闭；Q2/Q3 随之失效；**Q4 仍开放**）

| # | 决策 | 选项 | 我的建议 |
|---|---|---|---|
| **Q1** | ~~是否申请夸克开放平台开发者？~~ → **已由 P0-1 代答：申请不到**（需公司主体；且该平台不含网盘文件接口）。剩余动作"是否另行向夸克侧询问网盘 `client_id`/`sign_key` 通道" → **所有者 2026-10-18 裁决：不询问，本路线关闭** | — | 无后续动作 |
| **Q2** | ~~是否接受 T1「不内置凭据、用户自填」？~~ → **随路线关闭而失效**：机制上接受（与百度"高级：自填 AppKey"先例一致，法律上干净），但**用户自己也申请不到凭据**，自填框会是空功能。内置泄露凭据我仍然明确否决（该否决**长期有效**，与路线是否放弃无关） | 接受 / 不接受 | ❌ 不做 |
| **Q3** | 产品定位：**`BackendAdapter` 第 6 个后端**（ServerConnect 里）还是**网盘音源**（Netdisk Tab，与百度并列）？ | 后端 / 音源 | **音源**（若将来启用）。G1/G2 已证明后端语义对不上；⚠️ 但注意 §10 的结论相反 —— **WebDAV 走音源 + 本地索引**，同样不要伪装成 `BackendAdapter` |
| **Q4** | ⏳ **本篇归档时仍未裁决**：夸克专属实现已被判死 ⇒ 是否改为投入 **WebDAV / Alist 通用后端**（一次通吃夸克/阿里/115/OneDrive；项目 2026 年的归档结论本就更看好它且至今未实现）。放弃夸克路线**不等于**采纳 WebDAV —— 两者是独立决策 | 投 WebDAV / 都不做 | **投 WebDAV**（§10 已给落地要点与工时估算 6–8 人日）。它不依赖任何厂商资质，工时不会因夸克政策变化而作废；若也不做，则本仓网盘音源长期只有百度一家 |

---

## 9. 风险登记

| 风险 | 等级 | 处置 |
|---|:---:|---|
| **把泄露的 `third_party_agent`/`sign_key` 内置进开源 APK** | 🔴 高 | §2.1 已否决。若有人在实现中这么写，`QuarkApiConfig` 需有一条断言拒绝内置值 |
| **误把小程序凭据当网盘凭据**（`client_id` 同名，签名一套 MD5 一套 SHA256，网关也不同域） | 🟠 中 | §2.2 对照表 + §7.5 断言：`QuarkSignerTest` 的固定向量必须锁 SHA256 那条式子；若有人改用 MD5 立即红 |
| **误把 `qk.login`/`code2Session` 当网盘授权**（它只给 `open_id`/`session_key`，且官方要求在服务端调用） | 🟠 中 | §5 顶部警示；任何"小程序登录换 access_token"的设计都是错的 |
| 夸克网盘 `client_id`/`sign_key` **无发放通道**（P0-2） | 🔴 **已发生** | 本路线冻结；改投 T5（WebDAV/Alist），见 §4.1 |
| 网关随 Skill 项目策略变更（改 `client_id` 白名单、按身份裁剪返回字段） | 🔴 高 | `ApiProbe` 指纹漂移检测 + 启动一次性提示（`apiDriftNotified` 语义现成） |
| 智能搜索 5000 名额耗尽 ⇒ 功能静默消失 | 🟠 中 | 不以搜索为核心路径（走 M2 的分享枚举）；errno/配额错误必须有面向用户的文案 |
| Range 不支持 ⇒ ExoPlayer 无法流播 | 🔴 高（未决） | P0-4 优先裁决，失败则整体降级为"下载到本地后播放"，工作量与体验重估 |
| API 22 创维不信任网关/CDN 证书链 | 🔴 高（未决） | P0-5 优先裁决，失败即判死（本机已有可复用的 cacerts 提取复现配方） |
| 转存导致用户网盘容量翻倍 | 🟠 中 | 二次确认 + 容量预估提示 + `32003/32004` 明确文案 |
| 授权体系绑定"夸克网盘**独立端 App**"（解绑须在该 App 内完成） | 🟡 低 | UI 给二维码 + 文字路径双通道，直接借用 S1 `auth.md` 的人话文案 |
| 搜索引擎上的 AI 农场文章伪造端点，误导后续实现 | 🟠 中 | §1 明确 S4 禁引；任何新增端点必须能指回 S1/S2 的具体段落 |

---

## 10. 若采纳 Q4 推荐：WebDAV（T5）落地要点速记

> 这一节只是**为裁决提供依据的要点**，不是完整方案。裁决通过后应另立独立方案文档（并按 §7 的门与分期写法展开）。

| 项 | 要点 |
|---|---|
| 契约选择 | 仍建议挂 **`NetworkMusicService` + 本地索引**（与百度同构），**不要**伪装成 `BackendAdapter`：WebDAV 没有"专辑/歌手"概念。`searchByDirectory` 的接口注释已经把场景写好了 —— "网盘常以'粤语 / 经典老歌 / 民谣'等标签作为目录名组织音乐"（[NetworkMusicService.kt:41](file:///D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt#L41)） |
| 枚举方式 | RFC4918 的 `PROPFIND` **`Depth` 只允许 0/1**，没有百度 `listall` 那种一次递归 ⇒ 需按目录 **BFS**，代价是 N+1 次请求（目录数多时明显）。必须限并发 + 存进度 + 可中断重建（复用 `BaiduFileIndexCache` 的索引生命周期语义） |
| 播放 | `getStreamUrl` = base + URL 编码后的资源路径；凭据**必须走请求头**（Basic），⛔ 不许拼进 URL —— 现成链路 `BackendRegistry → BackendAuthHeaders(provider) → 拦截器按 host 精确匹配` 可直接复用（同 AGENTS.md 里飞牛那条约束） |
| 元数据 | `getcontentlength` / `getlastmodified` / `resourcetype` 原生可得，**时长/ID3/封面仍需客户端补**（与 G4 同源：复用 `Id3v2Parser` / `EmbeddedCoverExtractor` / `searchCoverUrl` 兜底）—— 这是 WebDAV 相对夸克 API 的唯一残留短板，但**可控** |
| 音频过滤 | 直接复用已验证的 `BaiduPanApi.AUDIO_EXTENSIONS`（30 个扩展名）与 `EXOPLAYER_NATIVE_AUDIO`（APE/DSF 等需 UI 容错提示） |
| ⚠️ 安全 | PROPFIND 返回 **XML**：解析器必须**禁用 DTD / 外部实体**（XXE）。仓库里现有解析全是 Gson JSON，**没有 XML 解析先例**，这是本次唯一新增的攻击面，需要一条专门的审查项 |
| ⚠️ 传输 | Basic auth 在明文 http 上等于口令裸奔。家庭局域网与项目既有 `usesCleartextTraffic` 取舍一致，可接受；但公网 alist 必须 https，且**创维 API 22 不信任 Let's Encrypt**（既有结论）⇒ 用户 alist 若用 LE 证书，电视上直接握手失败 —— 这条必须进 P0 级真机验证，且要在设置页给出可诊断的失败文案 |
| 工时 | 约 **6–8 人日**（PROPFIND 客户端 + Basic + 路径编码 + BFS 索引 + 播放头注入 + 设置页 + 门测试），**低于**夸克专属的 17 人日，且一次覆盖夸克/阿里/115/123/OneDrive |

---

## 11. 参考来源

- 用户提供 1：<https://github.com/wlor0623/quark-api>（S2，README 1106 行，自称非官方逆向整理）
- 用户提供 2：<https://open.quark.cn/api/device#dbKRb>（S3，「一、开发必须 → 1.1 配置参数 / 1.2 服务端功能域名」）与 <https://open.quark.cn/tutorial/login>（S3，「设备 → 登录 → qk.login / code2Session / qk.getUserInfo」）。
  ⚠️ 抓取工具无法读取（登录态 SPA，只返回加载壳），正文由所有者于 2026-10-18 以登录态粘贴提供 ⇒ **官方原文，权威级**。裁决见 §2.2
- S2 的上游权威：<https://github.com/wlor0623/quarkclouddrive-skill>（S1，`SKILL.md` + `references/{auth,file-search,file-read,file-ops,file-upload,file-share,file-saveas,file-organize,assistant}.md`）
- 项目内先例（本地仓库，非外链）：`docs/archive/百度网盘音乐播放开发方案.md`、`docs/archive/aliyundrive-support-plan.md`、`app/src/main/java/com/nasmusic/tv/backend/network/baidu/*`

（以下链接经核对为 AI 内容农场伪造，**仅作反面记录，禁止引用**：
[CSDN WorkBuddy 避坑指南](https://m.blog.csdn.net/weixin_34101914/article/details/166659565)、
[CSDN WorkBuddy 实战](https://m.blog.csdn.net/weixin_29054399/article/details/166613067)、
[CSDN Skill 封装教程](https://blog.csdn.net/weixin_28678151/article/details/166021998)、
[CSDN 夸克网盘 api 接口](https://wenku.csdn.net/answer/187wux3kjk)、
[CSDN 认证与授权流程](https://wenku.csdn.net/answer/4x1myxhdrr)）
