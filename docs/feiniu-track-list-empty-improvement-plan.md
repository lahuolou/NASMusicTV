# 飞牛音乐适配器：歌曲列表为 0 / 搜索无结果 改进方案

> 适用版本：NASMusicTV 2.38.15
> 问题环境：fnOS 商店版「飞牛音乐」为后端；NASMusicTV 服务器类型选择「飞牛音乐」
> 创建时间：2026-10-18
> 状态：**假设已收敛 + 改进方案**（H4 已排除；H1 与 H2b 为最可能根因，尚未在真机复现确认，见 §4 与 §7）
>
> **2026-10-18 更新**：应用户要求补充两块内容——
> ① **§2.2b** 澄清 `?sort=createdAt%2Cdesc&page=1&size=500` 的编码正确性（`URLEncoder` 对
> 逗号编码为 `%2C` 是标准行为，客户端切勿预编码）；
> ② **§5.7 T7**「错误码 / 失败原因展示到 UI」完整方案（T7a-T7d），让服务端返回
> `code!=0` 时用户能看到具体原因而非静态「暂无歌曲」。
>
> **2026-10-18 二次更新（分步验证流程）**：应把验证拆成两步——
>
> | 步骤 | 谁做 | 做什么 | 产物 |
> | --- | --- | --- | --- |
> | **① 取证** | **用户**（跑脚本） | 运行 `docs/feiniu-diagnose.sh`，把完整输出反馈回来 | 服务端真实信封 `code/msg`、`size` 上限、单条曲目字段结构 |
> | **② 改码** | **开发者**（据反馈） | 按 **§9「反馈 → 代码修改」决策表** 选择落地哪些 T 方案 | 改 `FeiniuAdapter.kt` / `MainViewModel.kt` / `SongList.kt` 等，再让用户装 APK 复测 |
>
> **为什么不一次性全改**：H1（解析）与 H2b（服务端拒绝参数）的症状**同为「0 首」但根因完全不同**，
> 盲目全改会引入不必要改动，也无法确认哪条真正奏效。先取证定性，再精准改码，每步都有明确验证点。

---

## 1. 问题现象

用户报告（已确认的观测事实）：

| # | 观测项 | 结果 |
| --- | --- | --- |
| P1 | `curl` 直接访问 `GET /music/api/v1/track/list` | 返回正常，`total = 1541`，`list` 数组完整 |
| P2 | NASMusicTV「连接状态测试」 | 成功 |
| P3 | 专辑列表、艺术家列表 | 完全正常（数量非 0） |
| P4 | 「全部歌曲」页面 | **显示 0 首** |
| P5 | APP 内搜索 NAS 歌曲 | **无任何结果** |

关键判据：**P3 正常 + P4/P5 同时为 0** 的组合，把问题收敛到「曲目（Track）专属的解析/取数路径」，而非「登录/认证/基址」这类影响所有后端的全局故障。因为专辑走 `AlbumDto.name`、艺术家走 `ArtistDto.name`，而曲目走 `TrackDto.title` —— 三者字段路径不同。

---

### 1.1 ⚠️ 关键区分：curl 请求 ≠ APP 请求（本方案立论前提）

| 请求方 | 实际请求 | 参数差异 |
| --- | --- | --- |
| 用户 curl（P1 已确认正常） | `GET /music/api/v1/track/list` | **无** `page` / `size` / `sort`，走服务端**默认分页** |
| APP `getSongs(500, 0)` | `GET /music/api/v1/track/list?sort=createdAt%2Cdesc&page=1&size=500` | 显式 `sort=createdAt,desc`、`page=1`、`size=500` |
| APP `loadAllTracks()`（搜索） | `GET /music/api/v1/track/list?sort=createdAt%2Cdesc&page=N&size=200` | 显式 `sort` + 全量翻页 |

**推论**：P1 只能证明「服务端 `track/list` 在有数据、默认分页下可用」，**不能**证明「APP 带 `sort+size=500` 的请求也返回同样结构」。任何基于 P1 排除「服务端对特定参数返回不同结构」的结论都需要再验证。

**因此**：本方案中 H4（total 异常）因 P1 明确排除；但 H1/H2/H3 仍**完全可能成立**，必须用 §4 的「带参对照实验」才能定性。

---

## 2. 代码定位（已核对源码，行号取自 2.38.15）

### 2.1 曲目列表取数（`FeiniuAdapter.kt`）

| 位置 | 代码 | 说明 |
| --- | --- | --- |
| `:594` | `override suspend fun getSongs(limit: Int, offset: Int)` | SONGS Tab / 后台全量加载的唯一取数入口 |
| `:599` | `fetchAllPages("track/list", "sort" to "createdAt,desc", page = page, size = safeLimit, singlePage = true)` | `page = offset/safeLimit + 1`，`size = limit` |
| `:605` | `getSongsTotalCount()` → `fetchTotal` | `?page=1&size=1`，读 `data.total` |
| `:650` | `getRecentSongs()` → `size = 100, singlePage = true` | 首页「最近添加」 |
| `:667` | `getRandomSongs()` → 随机取一页 | 随心听 |
| `:724` | `loadAllTracks()` → `fetchAllPages(...)` 全量翻页 | 搜索缓存 |

**常量**（同文件 `companion object`）：

- `DEFAULT_PAGE_SIZE = 200`（`:74`）
- `MAX_PAGES = 200`（`:77`，防御 `total` 异常）
- `TRACK_CACHE_TTL_MS = 5 * 60 * 1000L`（`:101`）

### 2.2 曲目字段解析（`parseTrack`，`:1176`）

```kotlin
private fun parseTrack(obj: JsonObject, fallbackTrackNumber: Int = 0, audioSpec: JsonObject? = null): Song? {
    val guid = str(obj, "guid") ?: return null
    val title = str(obj, "title")?.takeIf { it.isNotBlank() } ?: return null   // ← 关键
    ...
}
```

- **`guid` 缺失 → 返回 null**
- **`title` 缺失或空白 → 返回 null**

对比：`parseAlbum`（`:1133`）取 `str(obj, "name")`；`parseArtist`（`:1156`）取 `str(obj, "name")`。

### 2.2b ⚠️ URL 参数编码：`sort=createdAt%2Cdesc` 是**标准且正确**的写法

用户问「`GET /music/api/v1/track/list?sort=createdAt%2Cdesc&page=1&size=500` 这样写是否正确？」

**答案：完全正确，且是唯一正确的写法**。`FeiniuUrl.endpoint`（`:106`）用 Java 的
`URLEncoder.encode(value, "UTF-8")` 拼参数（`:114`）：

```kotlin
sb.append(name).append('=').append(URLEncoder.encode(rawValue.toString(), "UTF-8"))
```

`URLEncoder` 按 `application/x-www-form-urlencoded` 规则编码，**保留字符只有**
`A-Z a-z 0-9 - . _ *`，空格变 `+`，其余字符一律 `%XX`。因此：

| 原始值 | `URLEncoder` 输出 | 服务端解码后 |
| --- | --- | --- |
| `createdAt,desc` | `createdAt%2Cdesc` | `createdAt,desc` ✅ |
| `trackCount,desc` | `trackCount%2Cdesc` | `trackCount,desc` ✅ |
| `favoriteAt,desc` | `favoriteAt%2Cdesc` | `favoriteAt,desc` ✅ |

逗号 `,` 在 form-urlencoded 里**不是**保留字符，所以必然被编码成 `%2C`。所有主流
服务端框架（Spring / Express / OkHttp 等）在读取 `@RequestParam` / `req.query.sort`
时都会自动解码，最终拿到的是 `createdAt,desc`。

**两个必须注意的边界**：

1. **禁止客户端自己预编码**：如果客户端把值写成字面量 `"createdAt%2Cdesc"` 再传入
   `endpoint()`，`URLEncoder` 会把 `%` 再编码成 `%25`，请求变成
   `sort=createdAt%252Cdesc` —— 服务端解码得到 `createdAt%2Cdesc`（**带百分号**），
   `sort` 字段名匹配失败。**这是最容易踩的坑**。
2. **服务端若用 `rawQuery` 直接匹配**（不看解码值）：少数自制/非标服务端会用
   `request.rawQuery` 或正则匹配原始查询串，此时 `createdAt%2Cdesc` 与期望的
   `createdAt,desc` 字符串比对会失败 → 可能返回参数校验错误码。这属于**服务端缺陷**，
   不是客户端写法问题，但它是 §3 H2b 的一个候选触发点，必须用 §4.1b 的 `code/msg`
   打印才能确认。

### 2.3 分页判末（`fetchAllPages`，`:1043`）

```kotlin
if (singlePage || items.size() == 0) break
val total = data.get("total")?.asInt ?: -1
val reachedEnd = if (total >= 0) {
    pagesFetched.toLong() * size >= total     // ← P2-7 修复后的主判据
} else {
    items.size() < size
}
```

⚠️ **关键缺陷**：`getSongs(limit, offset)` 走的是 `singlePage = true`，**只取一页就返回**，不做翻页。因此歌曲列表的分页完整性完全依赖「上一页 `hasMore` 驱动下一页」，由 `MainViewModel` 负责。

### 2.4 ViewModel 侧（`MainViewModel.kt`）

```kotlin
private val pageSize = 500                              // :297

fun loadSongsNextPage() {                                // :1144
    ...
    val batch = adapter.getSongs(pageSize, offset)       // offset = state.songs.size
    ...
    hasMore = batch.size == pageSize                     // :1158  ← 关键
    ...
}

private fun loadAllSongsBackground() {                   // :1179
    while (true) {
        val batch = adapter.getSongs(pageSize, offset)
        if (batch.isEmpty()) break
        ...
        if (batch.size < pageSize) break                // :1202  ← 关键
    }
}
```

**两条终止条件都依赖「单页条数 == 500」**：

- `hasMore = batch.size == pageSize` → 若一页不足 500 条，**翻页停止**；
- `if (batch.size < pageSize) break` → 同上。

### 2.5 搜索路径（`searchSongs`，`:680` + `loadAllTracks`，`:703`）

搜索是**客户端本地过滤**：`searchSongs` 只依赖 `loadAllTracks()` 的全量快照（TTL 5 分钟）。

```kotlin
val fresh = fetchAllPages("track/list", "sort" to "createdAt,desc") { parseTrack(it) }
allTracksCache = fresh
```

⚠️ **这里不带 `singlePage`**，是真正的全量翻页，但仍受两个隐性约束：

1. `MAX_PAGES = 200` 页 × `DEFAULT_PAGE_SIZE = 200` 条 = **最多 40,000 条**；
2. 翻页判末仍依赖 `data.total` 与 `size`，若服务端 `total` 恒为 0 或恒为 1541 而单页实际只回少量，则提前 break（见 §3.2）。

---

## 3. 根因假设（按可能性排序，已按 P1 证据重排）

### 前置：P1 排除了什么

用户提供的关键证据：`curl` 无参访问 `track/list` → `total=1541`，`list` 数组完整。

| 假设 | 是否被 P1 排除 |
| --- | --- |
| 服务端数据不存在 | ✅ 排除 |
| `total` 异常（H4 原述） | ✅ 排除 |
| `track/list` 端点不可用 / 服务端无数据 | ✅ 排除 |
| H1 `parseTrack` 解析异常（整页丢弃 / 字段名不匹配） | ❌ **未排除**（与分页参数无关，是纯解析问题） |
| H2 分页参数触发的「静默截断」 | ✅ **已排除**（见下「用户新增推理」） |
| H3 `pageSize` 语义冲突 | ❌ **未排除** |
| **H2b** 分页参数触发「错误码」而非「静默截断」 | ❌ **未排除**（新增，关键） |

### ⚠️ 用户新增推理（已采纳）：为什么 H2「静默截断」分支被排除

用户指出：「**就算 size 被截断，至少也应该有 500 首歌曲出现，现在是 0**」。

这条推理**完全正确**，它排除了 H2 的「静默截断」子分支：

- 若服务端对 `size=500` **静默截断**到 200 → `getSongs` 返回 200 条 → UI 显示 **200 首**，不是 0；
- 现在 UI 显示 **0 首** → 说明**不是**「静默截断」，而是**要么一个都没解析出来（H1），要么整批请求失败（H2b）**。

### 关键补充：`code != 0` 会让 `fetchAllPages` 立即 break 返回空

`dataOf`（`:1012`）在信封 `code != 0` 时**直接返回 null**（除 `99999`/`120001` 抛 `AuthExpiredException` 外）：

```kotlin
private fun dataOf(envelope: JsonObject?, url: String): JsonObject? {
    if (envelope == null) return null
    val code = envelope.get("code")?.asInt ?: 0
    if (code != 0) {
        ...
        return null                      // ← 关键：返回 null
    }
    return envelope.getAsJsonObject("data")
}
```

`fetchAllPages`（`:1059`）里：

```kotlin
val data = fetchPageEnvelope(path, currentPage, size, *extra) ?: break   // ← dataOf 返回 null → break
```

**推论**：如果 APP 的 `?sort=createdAt,desc&page=1&size=500` 让服务端返回 `code != 0`（如参数校验失败、`sort` 字段名不识别、`size` 越界被拒），则 `fetchAllPages` **立即 break，返回空列表** → UI 显示 0 首。

这与用户的推理一致：**服务端不是静默截断，而是拒绝了这个请求**。

### H1（最可能）｜`parseTrack` 是「整页丢弃」结构，任一字段类型异常导致全页为空

**这是本方案重新评估后最可能的根因**，原因如下。

#### 3.1.1 结构性漏洞：`fetchAllPages` 的 `forEach` 没有异常隔离

```kotlin
items.forEach { el ->
    val obj = el as? JsonObject ?: return@forEach
    parse(obj)?.let { result.add(it) }   // ← parse 抛异常 = 整个 forEach 中断
}
```

`parseTrack`（`:1176`）里 `obj.get("duration")?.asLong`（`:1206`）**不是 `safe` 调用**：

```kotlin
val durationMs = obj.get("duration")?.asLong ?: spec?.get("duration")?.asLong ?: 0L
```

Gson 的 `JsonElement.asLong` 语义：
- 若服务端某条曲目 `duration` 是**字符串** `"154100"` 或 `"2:33"`（CUE 转码场景常见）→ `asLong` 抛 `IllegalStateException`；
- 若服务端某条曲目 `duration` 是**浮点** `154100.5` → 同抛异常；
- **一条异常 → `items.forEach` 中断 → `fetchAllPages` 抛出 → `runCatchingSuspend` 捕获 → `getOrDefault(emptyList())` → 整页 500 条全部丢失**。

#### 3.1.2 为什么能完美解释症状组合

| 症状 | H1 如何解释 |
| --- | --- |
| P1 curl 正常 | 服务端数据没坏，是 APP 解析挂了 |
| P2 连接测试成功 | `testConnection` 走 `user/me` 或 `sys/config`，**完全不经过 `parseTrack`** |
| P3 专辑/艺术家正常 | `parseAlbum`/`parseArtist` 是**独立函数**，且**不解析 `duration`**（只读 `name`/`trackCount`/`releaseDate`），所以不受 `duration` 类型异常影响 |
| P4 歌曲为 0 | `getSongs` 抛出后被吞，返回空列表 |
| P5 搜索无结果 | `loadAllTracks` 走同一个 `parseTrack`，同样抛异常 → `allTracksCache = emptyList()` |

#### 3.1.3 H1 的两个子分支（诊断时需区分）

| 子分支 | 表现 | 日志 |
| --- | --- | --- |
| **H1a：字段名不匹配**（服务端无 `title`，只有 `name`） | `parseTrack` **每一条**都因 `title` 为空返回 null | `fetchAllPages` 正常返回空列表，**无任何异常日志** |
| **H1b：字段类型异常**（`duration` 是字符串/浮点） | 第 N 条抛异常，**整页中断** | `AppLog.e("getSongs failed", it)` + 栈里有 `asLong` |

两种情形最终都显示「0 首」，但**日志表现完全不同**，用 §4.3 的 `adb logcat` 一秒钟区分。

### H2b（高可能性，替代原 H2）｜分页参数触发服务端「错误码」而非「静默截断」

用户推理 + `dataOf` 逻辑共同支持此分支。APP 的请求参数组合 `?sort=createdAt,desc&page=1&size=500` 可能触达服务端某条参数校验路径，导致 `code != 0`。

**候选触发点**（需 §4.5 逐项排除）：

1. `sort=createdAt,desc` —— curl 未带 `sort`，走服务端默认排序；APP 显式传 `createdAt,desc`，若该字段名/排序格式在服务端不合法 → 报错；
2. `size=500` —— 若服务端对 `size` 有硬上限且**超出后返回错误码**（而非静默截断）→ 报错；
3. 两者组合 —— 部分服务端只在「同时提供 `sort` 与超大 `size`」时才校验出错。

**为什么 P1 不能排除 H2b**：curl 请求参数与 APP 完全不同，P1 只证明了「无参/默认分页」可用。

### H3｜`MainViewModel.pageSize = 500` 与 `FeiniuAdapter` 页大小语义冲突

`BackendAdapter.getSongs(limit, offset)` 的 `limit` 直接透传为飞牛的 `size`。即使服务端不设上限，UI 层「批次是否满页」的判断也依赖 `pageSize=500`。**但此假设只能解释「少加载几页」，不能解释 0**——除非与 H1/H2b 叠加。

### H4（已排除）｜`total` 返回异常导致全量翻页提前终止

P1 已确认 `total=1541` 且 `list` 完整，此假设**不再成立**。保留在文档中作为「曾被考虑但已排除」的记录。

### H5｜缓存/状态残留

`allTracksCache`（TTL 5 分钟）或 `_songsPaging` 残留了上次连接失败时的空快照，导致本次显示 0。属于二次因素，非根因。

### 已排除

- **认证失败**：`testConnection` 通过 + 专辑/艺术家正常，排除 token 失效（`:499` `testConnection` 走 `verifyToken()` 或 `sys/config`，与曲目解析完全独立）。
- **基址归一化错误**：专辑/艺术家都基于同一 `apiBase`，若归一化错，三者一起挂。
- **网络/超时**：curl 成功且其余列表正常。
- **服务端无数据 / `total` 异常**：P1 直接证伪。

---

## 4. 复现与诊断步骤（在用户机器上执行）

### 4.1 关键对照实验：带与不带 `size`

```bash
# ① 复现 curl 的默认行为（用户已确认正常）
curl -s "http://<nas>:5666/music/api/v1/track/list?page=1&sort=createdAt,desc" -H "Authorization: <token>"

# ② 复现 APP 的请求
curl -s "http://<nas>:5666/music/api/v1/track/list?page=1&size=500&sort=createdAt,desc" -H "Authorization: <token>"

# ③ 探测 size 上限（二分：200 / 100 / 50）
for n in 500 300 200 100 50 20; do
  echo "=== size=$n ==="
  curl -s "http://<nas>:5666/music/api/v1/track/list?page=1&size=$n&sort=createdAt,desc" \
       -H "Authorization: <token>" | python -c \
       "import sys,json;d=json.load(sys.stdin);print('total=',d.get('data',{}).get('total'),'len(list)=',len(d.get('data',{}).get('list',[])))"
done
```

**判读**（**关键：先看信封 `code`，再看 `list`**）：

| 观察 | 结论 | 对应假设 |
| --- | --- | --- |
| ② 的响应信封 `code != 0`（HTTP 可能仍是 200） | 服务端**拒绝了**该参数组合 → `dataOf` 返回 null → `fetchAllPages` break → **0 首** | **H2b**（用户推理已排除「静默截断」，此分支才解释 0） |
| ② 的 `code == 0` 且 `len(list)` < 请求的 500 | 服务端静默截断 → 应显示 200/100 首（**不是 0**） | H3 触发链 |
| ② 的 `code == 0`、`len(list) == 500` 但字段**没有 `title`** | `parseTrack` 每条都返回 null → **0 首**（静默，无日志） | **H1a** |
| ② 的 `code == 0`、`len(list) == 500`、字段正常，但 APP 显示 0 | `parseTrack` 内部抛异常（如 `duration` 类型）→ 整页被吞 | **H1b** |

> **重点**：**用户推理已证明 H2「静默截断」不可能导致 0 首**（截断只会减少数量）。所以如果 ② 的 `code == 0` 但 `list` 有数据，而 APP 显示 0，**只能是 H1（解析挂了）**；如果 ② 的 `code != 0`，则是 **H2b（服务端拒绝参数）**。

### 4.1b 关键：打印完整信封（含 `code` 与 `msg`）

上面的脚本只打印了 `data.total` 和 `list` 长度，**没打印 `code`**。而 `code != 0` 正是 H2b 的关键。请改用：

```bash
curl -s "http://<nas>:5666/music/api/v1/track/list?page=1&size=500&sort=createdAt,desc" \
     -H "Authorization: <token>" | python -c "
import sys, json
raw = json.load(sys.stdin)
print('HTTP_OK=True')
print('code =', raw.get('code'))
print('msg  =', raw.get('msg'))
d = raw.get('data') or {}
print('total =', d.get('total'), 'len(list) =', len(d.get('list', [])))
"
```

**判读**：
- `code != 0` → **H2b 成立**（服务端拒绝 `sort=createdAt,desc` / `size=500` 之一或组合）；`msg` 字段会给出服务端的具体报错文案；
- `code == 0` 但 `len(list) == 0` → 服务端返回空页（可能默认分页在带 `sort` 时行为不同），需继续看 §4.2；
- `code == 0` 且 `len(list) > 0` → 服务端正常，问题在 APP 的 `parseTrack`（H1），跳转 §4.3。

### 4.2 抓取单条曲目字段结构

```bash
curl -s "http://<nas>:5666/music/api/v1/track/list?page=1&size=1&sort=createdAt,desc" \
     -H "Authorization: <token>" \
  | python -c "import sys,json;print(json.dumps(json.load(sys.stdin)['data']['list'][0], ensure_ascii=False, indent=2))"
```

**重点核对**：`guid`、`title`（还是 `name`?）、`duration`（数值还是字符串?）、`album`（对象还是 `albumGUID` 字符串?）、`artists`（数组还是 `artistNames`?）、`coverId`、`audioSpec` 是否存在。

### 4.3 用 logcat 区分 H1（解析异常）与「字段名不匹配」（静默空）

这是本方案新增的关键诊断。两种情形最终都显示「0 首」，但日志表现**完全不同**，一秒钟就能定位：

```bash
adb logcat -s FeiniuAdapter:* NASMusic:*

# 关键看这几条 tag 有没有出现：
# - "getSongs failed" + 栈         → H1 解析异常（duration/asLong 抛 IllegalStateException）
# - "request error url=..."        → H1 传输层异常
# - 没有任何报错                    → 字段名不匹配（parseTrack 返回 null，静默丢弃）
```

**判读**：

| logcat 表现 | 结论 | 对应假设 |
| --- | --- | --- |
| 出现 `AppLog.e("getSongs failed", it)` 且栈里有 `asLong` / `IllegalStateException` | `parseTrack` 对某条 `duration` 字段抛异常，整页被吞 | **H1b** |
| 出现 `request error` | 传输层异常（网络/超时/信封解析） | H1 的传输分支 |
| **完全无报错日志**，但歌曲数为 0 | `parseTrack` 每条都返回 null（`title` 字段名不匹配 / `guid` 缺失） | **H1a** |
| 日志正常，`loadSongsNextPage: loaded 200, total 200/1541` | 服务端 `size` 截断，批次不满页 | H2 / H3 |

> ⚠️ 如果 logcat 无任何 FeiniuAdapter 相关输出，可能是 `AppLog` 的 `BuildConfig.DEBUG` 守卫在 release 下被 R8 折掉了。此时改用 `logcat` 过滤 `NASMusic`（`MainViewModel` 的日志无守卫），或临时改用 debug 包复现。

### 4.4 验证分页完整性（P1 已确认 total 稳定，此处只做 sanity check）

> ⚠️ 本步骤原用于验证 H4，现已被 P1 排除。**保留仅作为**「确认 APP 侧多页翻页能否正常拉满 1541 条」的 sanity check，不再用于判断 total 是否异常。

```bash
curl -s "http://<nas>:5666/music/api/v1/track/list?page=1&size=200&sort=createdAt,desc" \
     -H "Authorization: <token>" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('total',d['total'],'page1_len',len(d['list']))"
curl -s "http://<nas>:5666/music/api/v1/track/list?page=2&size=200&sort=createdAt,desc" \
     -H "Authorization: <token>" | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('page2_len',len(d['list']))"
```

若 `page1_len + page2_len ≈ total` → 服务端分页正常，问题在客户端解析；若 `page2_len == 0` 但 `total` 仍为 1541 → 服务端分页异常。

---

## 5. 改进方案

> 原则：**不改动其他后端（Jellyfin/Navidrome/Daoliyu）**，只针对 `FeiniuAdapter` 与飞牛专用调用路径。所有改动保持 `BackendAdapter` 接口契约不变。

### 5.0 T0｜`fetchAllPages` 逐条解析异常隔离（修复 H1 的核心缺陷）

**这是修复 H1 最关键的一步**。现状是 `items.forEach` 内 `parse(obj)` 抛异常会中断整页，一条脏数据丢掉 500 首。改为逐条 `runCatching`：

```kotlin
// fetchAllPages 内，替换原来的 items.forEach 块
items.forEach { el ->
    val obj = el as? JsonObject ?: return@forEach
    val parsed = try {
        parse(obj)
    } catch (e: Exception) {
        // 单条解析失败只丢弃该条，不中断整页 —— 这是 H1 的核心缺陷修复
        AppLog.w(TAG, "parse item failed: ${str(obj, "guid")} e=${e.javaClass.simpleName}")
        null
    }
    parsed?.let { result.add(it) }
}
```

**效果**：
- 一条 `duration` 为字符串的脏数据 → 只丢这一条，**其余 499 条正常入列表**；
- 日志有 `parse item failed` 告警，便于事后定位是哪条曲目、哪个字段；
- 专辑/艺术家解析同理加固（`parseAlbum`/`parseArtist`），保持一致的防御水平。

> 若后续发现某类字段**系统性**类型错误（如整个列表的 `duration` 都是字符串），可再在 `parseTrack` 内用 `parseDurationMs` 容错（见 T1），两条防线叠加。

### 5.1 T1｜`parseTrack` 字段容错（防御性，修复 H1 的字段分支）

**目标**：即便服务端字段名/类型变化，也不至于整页静默丢失。**注意 T0 已保证「一条失败不拖累整页」，T1 进一步让「常见脏数据」也能正确解析**。

```kotlin
private fun parseTrack(obj: JsonObject, fallbackTrackNumber: Int = 0, audioSpec: JsonObject? = null): Song? {
    val guid = str(obj, "guid") ?: return null

    // title 字段容错：优先 title，回退 name / songName / trackName
    val title = listOf("title", "name", "songName", "trackName")
        .firstNotNullOfOrNull { str(obj, it) }
        ?.takeIf { it.isNotBlank() } ?: return null

    // duration 容错：数字 / 字符串数字 / 字符串浮点 / 缺失
    val durationMs = parseDurationMs(obj.get("duration"))
        ?: spec?.get("duration")?.let { parseDurationMs(it) }
        ?: 0L
    ...
}
```

```kotlin
/** 数字或字符串形式的毫秒时长；无法解析返回 null（调用方回退 0） */
private fun parseDurationMs(el: JsonElement?): Long? {
    if (el == null || el.isJsonNull) return null
    return try {
        when {
            el.isJsonPrimitive && el.asJsonPrimitive.isNumber -> el.asLong
            el.isJsonPrimitive -> el.asString.trim().toDoubleOrNull()?.toLong()
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}
```

**验收**：
- 服务端 `title` 缺失但 `name` 存在 → 仍能解析出歌曲；
- 服务端 `duration` 为字符串 `"154100"` / `"154100.5"` → 不抛异常，正常取值；
- 服务端 `duration` 缺失 → `durationMs = 0`，UI 显示 `—`（与现有 `bitrate = 0` 同一降级策略）；
- 服务端 `guid` 缺失 → 仍返回 null（这是硬约束，无法补救）。

### 5.2 T2｜服务端 `size` 上限自适应探测（针对 H2 的残余：即便截断也保证 UI 至少显示一页）

**目标**：不硬编码 `size`，首次翻页失败/不满时自动降档重试。**注意**：T2 主要解决的是「少加载几页」而非「0 首」；真正解决 0 首的是 T0（H1）与 T5b（H2b）。

```kotlin
companion object {
    /** 候选页大小（从大到小），首个能取满或能对齐 total 的即采用 */
    private val CANDIDATE_SIZES = intArrayOf(500, 200, 100, 50)
}

private suspend fun effectivePageSize(path: String, sort: String, prefer: Int): Int {
    var best = prefer.coerceAtMost(CANDIDATE_SIZES.first())
    for (size in CANDIDATE_SIZES) {
        if (size > prefer) continue
        val data = fetchPageEnvelope(path, page = 1, size = size, "sort" to sort)
        val items = data?.getAsJsonArray("list")?.size() ?: 0
        val total = data?.get("total")?.asInt ?: -1
        // 取满请求的 size，或已覆盖 total，说明服务端接受该 size
        if (items >= size || (total >= 0 && items >= total)) { best = size; break }
        // 服务端返回非空但少于请求 → 用实际返回值作为后续页大小
        if (items > 0) best = items
    }
    return best
}
```

> ⚠️ 此探测只在 `getSongs`/`loadAllTracks` 首次进入时做一次，结果按 `(path, sort)` 缓存，避免每次翻页都多打一发探测请求。

**配套改动**：`fetchAllPages` 增加「页大小自适应」参数或由调用方先 `effectivePageSize` 再传入，保持现有签名兼容。

### 5.3 T3｜`getSongs` 不再依赖外部 `pageSize` 契约（修复 H2 触发链）

**当前契约风险**：`MainViewModel.pageSize = 500` 是**界面层硬编码**，与 `FeiniuAdapter.DEFAULT_PAGE_SIZE = 200` 冲突。飞牛路径下：

- `getSongs(500, 0)` → `size=500`；
- 服务端若上限 200 → 返回 200；
- `batch.size < pageSize` → **`hasMore = false` + 后台加载 break**。

**改进**：`FeiniuAdapter.getSongs` 内部自行决定请求的 `size`，**不信任入参**：

```kotlin
override suspend fun getSongs(limit: Int, offset: Int): List<Song> = withContext(Dispatchers.IO) {
    runCatchingSuspend {
        val size = effectivePageSize("track/list", "createdAt,desc", limit.takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE)
        val page = (offset / size) + 1
        fetchAllPages("track/list", "sort" to "createdAt,desc", page = page, size = size, singlePage = true) {
            parseTrack(it)
        }
    }.onFailure { AppLog.e(TAG, "getSongs failed", it) }.getOrDefault(emptyList())
}
```

**配套 UI 层改动（可选，若 T3 仍无法让 `hasMore` 稳定）**：

- 在 `MainViewModel` 增加对飞牛后端的特殊处理：把「翻页是否结束」的判断改为**与 `totalCount` 对齐**，而不是「批次大小 == 500」。

```kotlin
// loadSongsNextPage 中
val totalCount = if (state.totalCount == 0) adapter.getSongsTotalCount() else state.totalCount
val newState = SongsPagingState(
    songs = state.songs + batch,
    totalCount = totalCount,
    isLoading = false,
    hasMore = totalCount <= 0 || state.songs.size + batch.size < totalCount,
    currentPage = state.currentPage + 1
)
```

> 此改动对 Jellyfin/Navidrome 无副作用：它们本就会返回接近 `limit` 的批次，`totalCount` 对齐语义一致。

### 5.4 T4｜`loadAllSongsBackground` 终止条件对齐 `totalCount`

现状：

```kotlin
if (batch.size < pageSize) break
```

改进（先取一次总数，避免空循环）：

```kotlin
private fun loadAllSongsBackground() {
    if (!_isBackgroundLoadingAll.compareAndSet(false, true)) return
    viewModelScope.launch {
        val adapter = backendRegistry.getAdapter() ?: run { ... }
        try {
            val total = adapter.getSongsTotalCount()
            var offset = 0
            while (total <= 0 || offset < total) {
                val batch = adapter.getSongs(pageSize, offset)
                if (batch.isEmpty()) break
                ...  // 追加 songs、更新 UI
                offset += batch.size
            }
        } finally { _isBackgroundLoadingAll.set(false) }
    }
}
```

**注意**：仍需保留 `batch.isEmpty()` 作为防御，防止 `total` 异常时死循环。

### 5.5 T5｜搜索路径增加诊断与降级

`searchSongs` 依赖 `loadAllTracks()`，若全量翻页被截断，搜索必然不全。改进：

1. 增加搜索快照的**计数告警**：`loadAllTracks` 完成时，与 `fetchTotal` 的 `total` 对比，若 `snapshot.size < total * 0.8` → `AppLog.w` 明确记录「搜索索引不完整 (size/total)」，便于定位。
2. 支持**按需触发搜索索引重建**：当搜索无结果且上次索引早于当前登录会话时，允许清空 `allTracksCache` 后重拉（当前 TTL 5 分钟已足够，若用户切服务器则 `allTracksCacheKey` 已换）。
3. **兜底**：若服务端后续版本支持 `track/search` 或 `track/metadata`，优先改用服务端搜索（见 §7 待确认项）。

### 5.5b T5b｜信封 `code != 0` 时保留诊断信息，不静默 break（修复 H2b）

**这是修复 H2b 的关键**。现状 `dataOf` 在 `code != 0` 时只记录 `lastFailure` 并返回 null，`fetchAllPages` 直接 `?: break`，**调用方完全看不到失败原因**。

#### 5.5b.1 现状的静默失败链

```
服务端返回 {code: 1000xx, msg: "..."}
  → dataOf: 记 lastFailure，返回 null
  → fetchAllPages: data == null → break
  → 返回空列表
  → runCatchingSuspend 未抛异常（getOrDefault 未触发）
  → MainViewModel 拿到 emptyList()
  → UI 显示 0 首，**无任何错误提示**
```

用户只看到「0 首」，完全不知道服务端已经报错了。

#### 5.5b.2 改进：区分「静默空」与「明确失败」

方案 A（最小改动）：让 `dataOf` 在 `code != 0` 时抛出一个**可识别的信封异常**，`fetchAllPages` 捕获后先 `AppLog.w` 再 break。

```kotlin
private class EnvelopeException(val code: Int, val msg: String) : Exception("feiniu code=$code msg=$msg")
```

```kotlin
private fun dataOf(envelope: JsonObject?, url: String): JsonObject? {
    if (envelope == null) return null
    val code = envelope.get("code")?.asInt ?: 0
    if (code != 0) {
        val msg = str(envelope, "msg").orEmpty()
        when (code) {
            99999, 120001 -> { ... throw AuthExpiredException() }   // 维持现有行为
            else -> throw EnvelopeException(code, msg)               // 新增：可识别失败
        }
    }
    return envelope.getAsJsonObject("data")
}
```

```kotlin
private suspend fun <T> fetchAllPages(...) {
    while (pagesFetched < MAX_PAGES) {
        val data = try {
            fetchPageEnvelope(path, currentPage, size, *extra)
        } catch (e: EnvelopeException) {
            AppLog.w(TAG, "track/list rejected: code=${e.code} msg=${e.msg}")
            lastFailure = envelopeFailure(e.code, e.msg)   // 确保 UI 能提示具体原因
            break                                          // 返回已累积结果，不抛给调用方
        }
        if (data == null) break
        ...
    }
    return result
}
```

**效果**：
- 服务端拒绝参数时，日志出现明确的 `track/list rejected: code=... msg=...`；
- `lastFailure` 被设置 → `BackendRegistry` 的连接诊断能展示具体错误（不再只显示「0 首」）；
- `fetchAllPages` 仍返回已累积结果（通常为空），UI 不崩溃，但可提示。

**验收**：
- 日志能打印服务端具体 `code` 与 `msg`；
- UI 不再静默显示 0，而是能通过 `lastFailure` 通道展示错误原因；
- `99999`/`120001` 走 token 过期路径，行为不变。

方案 B（可选，若后续要自动降级）：在 `EnvelopeException.code` 属于「参数校验类」（如 `1000xx`）时，触发 `effectivePageSize` 降档重试（`size` 500→200→100→50），并去掉 `sort` 重试一次。这需要先确认服务端到底拒绝哪个参数（见 §4.5），再决定是否启用。

### 5.6 T6｜增加飞牛后端单测（回归防线）

新增 `app/src/test/java/com/nasmusic/tv/backend/impl/FeiniuAdapterParseTrackTest.kt`（纯 JVM，无 Robolectric）：

| 用例 | 输入 JSON（`track/list` 单条） | 期望 |
| --- | --- | --- |
| 标准字段 | `{"guid":"g1","title":"A","duration":1000}` | 解析出 `Song("feiniu_g1","A")` |
| `title` 缺失 | `{"guid":"g1","name":"A","duration":1000}` | 仍解析出 `Song("feiniu_g1","A")` |
| `duration` 字符串 | `{"guid":"g1","title":"A","duration":"1000"}` | 不抛异常，`durationMs=1000` |
| `duration` 缺失 | `{"guid":"g1","title":"A"}` | 不抛异常，`durationMs=0` |
| `guid` 缺失 | `{"title":"A"}` | 返回 null（不进列表） |
| `title`/`name` 均缺失 | `{"guid":"g1"}` | 返回 null |

新增 `FeiniuAdapterPagingTest.kt`（MockWebServer，若已有 `FeiniuAdapterAccessCodeTest` 基建则复用）：

| 用例 | Mock 行为 | 期望 |
| --- | --- | --- |
| 单页 200 且 total 1541 | 返回 `size=500` 请求 → 只给 200 条 + total=1541 | `getSongs(500,0)` 返回 200 条（UI 显示 200，不是 0） |
| 单页 500 | 返回 500 条 + total=1541 | `getSongs(500,0)` 返回 500 条 |
| `title` 换成 `name` | 500 条全无 `title` 有 `name` | 仍能解析出 500 条 |
| total 与 list 不一致 | 返回 500 条 + total=0 | `loadAllTracks` 翻页不提前 break |
| **信封 `code != 0`（H2b）** | 返回 `{"code":1000xx,"msg":"..."}` | **T5b 生效**：日志出现 `track/list rejected: code=...`，`lastFailure` 被设置，UI 能展示错误而非静默 0 |
| **`duration` 字符串导致抛异常（H1b）** | 500 条里第 3 条 `duration:"154100"` | **T0 生效**：只丢弃该条，返回 499 条，不中断整页 |

### 5.7 T7｜错误码 / 失败原因真正展示到 UI（修复「用户只看到 0 首」）

**这是对用户「错误码应该能够提示出来，让用户知道」的直接回应**。数据层已经有
`lastFailure` 分类机制，但**整条链路在 UI 层断裂**——服务端返回 `code=xxx` 时，用户
看到的只是「暂无歌曲」四个字，完全不知道服务端已经拒绝了这个请求。

#### 5.7.1 现状：完整静默失败链（已核对源码，2.38.15）

```
服务端返回 {code:1000xx, msg:"sort 参数不合法"}
 ① FeiniuAdapter.dataOf(:1032)
      → 写 lastFailure = envelopeFailure(code,msg)；打日志 "api error code=... msg=..."
      → 返回 null
 ② fetchAllPages(:1059)  data==null → break → 返回 emptyList()
 ③ getSongs(:602)  runCatchingSuspend 正常返回；getOrDefault 未触发 → 返回 emptyList()，**不抛异常**
 ④ MainViewModel.loadSongsNextPage(:1152)  batch=emptyList()，try 块正常走完
      → catch 分支(:1167) 不触发 → showError() 从未被调用
 ⑤ SongsPagingState(songs=[], totalCount=0, hasMore=false)
 ⑥ SongList.kt:113  songs.isEmpty() && !isLoading && !isSearching → 显示静态 "暂无歌曲"
```

**三个关键断裂点**：

| # | 位置 | 断裂原因 |
| --- | --- | --- |
| **B1** | `BackendRegistry.lastErrorDetail` | **只在 `initialize`(:126/:152) 与 `testConnection`(:239) 各快照一次**。运行期 `FeiniuAdapter.lastFailure` 持续更新，**永不回流**到 `BackendRegistry`。 |
| **B2** | `FeiniuAdapter.getSongs` | `runCatchingSuspend{...}.getOrDefault(emptyList())` **吞掉一切异常**，ViewModel 的 `catch` 分支形同虚设——`e.message?.take(50)` 那行代码对 `code != 0` 场景**永远不可达**。 |
| **B3** | `SongsPagingState` / `SongList.kt` | 状态模型**没有错误字段**；UI 空态(`:113`)只渲染静态文案 `library_no_songs`（「暂无歌曲」），不接收任何诊断信息。 |

#### 5.7.2 T7a 数据层：让 ViewModel 能读到实时诊断

`BackendRegistry` 增加一个**运行期可查**的转发方法（不改变 `BackendAdapter` 接口契约，
`FeiniuAdapter.lastErrorDetail` 已覆写，`lastFailure` 是 `@Volatile` 属性，读它是线程安全的）：

```kotlin
/**
 * 读取当前活跃适配器的实时失败诊断。
 *
 * ⚠️ 与 [lastErrorDetail]（只在 initialize/testConnection 时快照）不同：
 * 本方法每次调用都实时读取 currentAdapter.lastErrorDetail，
 * 因此能拿到运行期（如 track/list 返回 code!=0 时）的最新失败原因。
 */
fun currentErrorDetail(): String =
    synchronized(lock) { currentAdapter }?.lastErrorDetail.orEmpty()
```

> 若担心 `currentAdapter` 在读瞬间被替换，可改为返回 `Pair(版本号, 文案)` 或直接缓存
> 最近一次 `lastFailure`，但当前 `synchronized` 读引用已足够安全（适配器替换在锁内）。

#### 5.7.3 T7b 状态层：`SongsPagingState` 增加错误字段

```kotlin
data class SongsPagingState(
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val error: String? = null          // 新增：本次加载失败的可展示原因（null = 无错误）
)
```

`MainViewModel.loadSongsNextPage` 修改（**核心判据：`totalCount > 0` 但 `batch.isEmpty()`**
——这是「服务端明明有数据却取不到」的强信号，用它区分「真没歌」与「加载失败」）：

```kotlin
try {
    val offset = state.songs.size
    val batch = adapter.getSongs(pageSize, offset)
    val totalCount = if (state.totalCount == 0) adapter.getSongsTotalCount() else state.totalCount
    // 关键：服务端有数据但本页拉空 → 视为失败，带诊断
    val loadError = if (batch.isEmpty() && totalCount > 0) {
        backendRegistry.currentErrorDetail().ifBlank {
            getApplication<Application>().getString(R.string.library_songs_parse_failed)
        }
    } else null
    val newState = SongsPagingState(
        songs = state.songs + batch,
        totalCount = totalCount,
        isLoading = false,
        hasMore = batch.size == pageSize,
        currentPage = state.currentPage + 1,
        error = loadError
    )
    _songsPaging.value = newState
    _songs.value = UiState.Success(newState.songs)
    buildArtistMapsIncremental(batch)
    ...
}
```

**判据四象限**（决定 `error` 是否非空）：

| `totalCount` | `batch.isEmpty()` | `currentErrorDetail()` | 结论 | `error` |
| --- | --- | --- | --- | --- |
| `> 0` | ✅ | 非空 | H2b：服务端拒绝参数（`code!=0`） | 具体诊断 ✅ |
| `> 0` | ✅ | 空 | H1a：字段名不匹配（静默丢弃，不设 lastFailure） | 通用「解析失败」文案 |
| `> 0` | ❌ | — | H1b 已被 T0 修复，只丢 1 条，其余 499 条正常 | null（不误报） |
| `== 0` | ✅ | — | 服务端真的没有歌 | null（UI 显示「暂无歌曲」） |

> **重要**：第 4 象限保证不误报。若服务端 `track/list` 合法返回 `total=0`（用户 NAS 真的
> 没有歌），UI 仍显示「暂无歌曲」而不是错误提示。这是 T7 与「把错误泛化」方案的本质区别。

#### 5.7.4 T7c UI 层：空态显示错误 + 重试

⚠️ **先纠正一个常见的误判（本方案首次核对 UI 后的修正）**：用户看到「0 首」时，**实际
渲染的是 `EmptyHint`，不是 `SongList`**。三条候选渲染路径必须分清：

| 路径 | 触发条件（`LibraryScreen.kt`） | 空态组件 |
| --- | --- | --- |
| 搜索有结果 | `:707 showSearchContent=true` → `SongsTab` | `SongList.kt:113`（此时 `isSearching=true`，`!isSearching` 为 false，**该空态分支实际不可达**） |
| **加载完成且歌曲为空** | **`:786 displaySongs.isEmpty() && songsPaging.songs.isEmpty()` → `EmptyHint(SONGS)`** | **`EmptyHint`（`BrowseComponents.kt:257`）← 用户真正看到的** |
| 加载中 | `:741 isLoading=true` → 骨架屏 | — |

结论：**必须改 `EmptyHint`**（主路径）。`EmptyHint` 当前签名只接收 `isConnected` + `tab`，
`SONGS` 分支渲染的是静态 `library_empty_songs`（「暂无歌曲」）+ `library_empty_songs_hint`
（「连接服务器或索引本地文件即可浏览歌曲」）——**没有任何诊断信息，也没有重试入口**。

**T7c.1 `EmptyHint` 增加可选错误参数**（默认值保证 ALBUMS/ARTISTS 两个现有调用点零改动）：

```kotlin
@Composable
internal fun EmptyHint(
    isConnected: Boolean,
    tab: LibraryTab = LibraryTab.ALBUMS,
    errorText: String? = null,           // 新增：非空时优先展示错误
    onRetry: (() -> Unit)? = null        // 新增：非空时展示「重试」按钮
) {
    val (icon, title, subtitle) = when {
        // 新增：有可展示的错误 → 优先展示错误，覆盖「暂无歌曲」
        !errorText.isNullOrBlank() -> Triple(
            Icons.Default.Warning,
            stringResource(R.string.library_songs_load_failed, errorText),
            ""
        )
        !isConnected -> Triple(
            Icons.Default.CloudOff,
            stringResource(R.string.common_not_connected),
            stringResource(R.string.library_connect_or_local_hint)
        )
        tab == LibraryTab.ARTISTS -> Triple(
            Icons.Default.Person,
            stringResource(R.string.library_empty_artists),
            stringResource(R.string.library_empty_artists_hint)
        )
        tab == LibraryTab.SONGS -> Triple(
            Icons.Default.MusicNote,
            stringResource(R.string.library_empty_songs),
            stringResource(R.string.library_empty_songs_hint)
        )
        else -> Triple(
            Icons.Default.Album,
            stringResource(R.string.library_empty_albums),
            stringResource(R.string.library_empty_albums_hint)
        )
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = if (errorText != null) NasMusicColors.Error
                       else NasMusicColors.TextSecondary.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                color = if (errorText != null) NasMusicColors.Error
                        else NasMusicColors.TextSecondary,
                fontSize = FontSize.title(),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    color = NasMusicColors.TextSecondary.copy(alpha = 0.7f),
                    fontSize = FontSize.button()
                )
            }
            if (onRetry != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.library_songs_retry))
                }
            }
        }
    }
}
```

> ⚠️ **注意**：错误色使用 `NasMusicColors.Danger`（`Theme.kt:49`，`Color(0xFFF87171)`），
> **不是** `NasMusicColors.Error`（后者不存在，会编译失败）。上面代码里所有
> `NasMusicColors.Error` 应统一替换为 `NasMusicColors.Danger`，图标 `Icons.Default.Warning`
> 也对应 `Danger` 语义（警告色）。

**T7c.2 `LibraryScreen.kt:786` 分支透传**（`LibraryScreen` 需新增 `songsError` 与 `onRetrySongs` 参数）：

```kotlin
LibraryTab.SONGS -> if (displaySongs.isEmpty() && songsPaging.songs.isEmpty()) {
    EmptyHint(
        isConnected = isConnected,
        tab = LibraryTab.SONGS,
        errorText = songsError,           // 来自 songsPaging.error
        onRetry = onRetrySongs            // 点击「重试」触发重新加载
    )
} else {
    SongsTab(...)
}
```

**T7c.3 `SongList`（次级路径）顺手同步**：`SongList.kt:113` 的空态分支当前用静态
`library_no_songs`，虽然正常情况下不可达，但 `SongsTab` 在搜索无结果但 `isSearching`
被短暂置位时仍可能经过。为保持防御一致性，同样接收 `songsPaging.error` + `onRetrySongs`，
逻辑同上（见前面版本代码）。改动量小，属低风险加固。

**T7c.4 `MainViewModel` 新增**：

```kotlin
fun retrySongsLoad() {
    _songsPaging.value = SongsPagingState()      // 清空含 error 的旧状态
    loadSongsFirstPage()
}
```

`LibraryScreen` 的 `songsError` 由调用方 `LibraryBranch` 从 `songsPaging.error` 透传。

**同时可选的轻量提示**：`MainViewModel` 在 `loadError` 非空时额外调用一次
`showError(getString(R.string.library_songs_load_failed, loadError))`，让顶部的 5 秒
提示也出现一次——但**主展示必须在空态内常驻**（`showError` 5 秒后会自清，
不足以让用户看清服务端 code）。

#### 5.7.5 T7d 字符串资源（中/英双语，需同步 `values-en`）

```xml
<!-- values/strings.xml -->
<string name="library_songs_load_failed">歌曲加载失败：%s</string>
<string name="library_songs_retry">重试</string>
<string name="library_songs_parse_failed">歌曲数据解析失败（服务端字段不兼容），请反馈版本号</string>
```

```xml
<!-- values-en/strings.xml -->
<string name="library_songs_load_failed">Failed to load songs: %s</string>
<string name="library_songs_retry">Retry</string>
<string name="library_songs_parse_failed">Song data could not be parsed (incompatible server fields). Please report this.</string>
```

> ⚠️ 本项目有 `VisualizerThemeStringsGateTest` 类门禁（漏一个 `values-en` key 编译
> 能过但渲染时崩）——虽它只覆盖 visualizer theme 字符串，但新增资源时应**主动**同步
> `values-en`，保持一致防御水平。

#### 5.7.6 T7 与 T0 / T5b 的联动（缺一不可）

T7 不是独立方案，它依赖前序修复才能生效：

| T7 依赖 | 作用 | 若不落地 |
| --- | --- | --- |
| **T5b**（`code != 0` 抛 `EnvelopeException`） | 保证 `lastFailure` **一定**被设置到具体 code/msg | T7b 的 `currentErrorDetail()` 读到空串 → 显示通用「解析失败」而非具体 code，排查效率打折 |
| **T0**（`fetchAllPages` 逐条隔离） | 保证 H1b 场景下 `batch` 不为空（只丢 1 条） | `batch.isEmpty()` 恒为 true → `totalCount>0` 时**误报错误**给用户（其实只是 1 条脏数据） |

**结论**：实施顺序必须是 **T5b → T0 → T7**。T5b 让诊断存在，T0 让判据准确，T7 让用户看见。

#### 5.7.7 T7 单测

- `SongsPagingStateTest`：构造含 `error` 的 state，断言字段透传、`copy` 语义正确。
- `MainViewModelLoadSongsErrorTest`（Robolectric + Mock BackendAdapter）：
  | Mock 行为 | `_songsPaging.value.error` 期望 |
  | --- | --- |
  | `getSongs` 返回空 + `totalCount=1541` + `lastErrorDetail="服务端返回错误：code=1000xx"` | 非空，且包含 `1000xx` |
  | `getSongs` 返回空 + `totalCount=0` | **null**（不误报） |
  | `getSongs` 返回 499 条（T0 生效）+ `totalCount=1541` | **null**（H1b 已修，不误报） |
- `SongListRenderErrorTest`（若已有 Compose UI test 基建）：`songsPaging.error != null` 时渲染「歌曲加载失败：…」+「重试」按钮；点击触发 `onRetrySongs`。

---

## 6. 实施顺序与风险控制

| 步骤 | 改动 | 风险 | 建议验证 |
| --- | --- | --- | --- |
| **0** | **T5b 信封 `code != 0` 抛 `EnvelopeException` + 保留诊断**（修复 H2b，**本次问题最可能**） | 低（新增异常类 + 日志，`dataOf` 已有 `lastFailure` 记录） | 单测 + logcat 看 `track/list rejected` |
| **1** | **T0 `fetchAllPages` 逐条异常隔离**（修复 H1） | 低（纯容错，不改正常路径） | 单测 + logcat 看 `parse item failed` |
| **2** | **T7 错误码展示到 UI**（T7a 实时诊断 + T7b 状态字段 + T7c 空态渲染 + T7d 双语资源） | 中（跨 数据层/状态层/UI 三层，需同步 `values-en`） | 单测 + 真机截图验证 |
| 3 | T1 `parseTrack` 字段容错 | 低（纯解析函数） | 单测通过 |
| 4 | T2 页大小自适应探测 | 中（新增首次探测开销） | 单测 + 真机日志 |
| 5 | T3 `getSongs` 内部 size | 中（可能改变其他后端调用路径，需回归 Jellyfin/Navidrome） | 全量单测 |
| 6 | T4 后台加载终止条件 | 中（ViewModel 逻辑） | Robolectric 测试 |
| 7 | T5 搜索诊断 | 低 | 日志验证 |
| 8 | T6 单测 | 无风险 | CI |

**T7 与 T5b / T0 的依赖关系（缺一不可，见 §5.7.6）**：必须先落 T5b（保证 `lastFailure`
有具体 code/msg）→ 再落 T0（保证 H1b 场景不误报错误）→ 最后落 T7（让用户看见）。
顺序颠倒会导致 T7b 的判据 `totalCount>0 && batch.isEmpty()` 在 H1b 场景下**误报错误**。

**回归范围**（改动影响面）：

- `app/src/test` 全量（`./gradlew.bat testDebugUnitTest --rerun-tasks --no-daemon`）；
- `lintDebug`（T7 涉及 Compose UI，lint 可能报 unused parameter 等，需过 `lintDebug`）；
- 若 T3 涉及 `BackendAdapter` 默认实现改动，跑 `JellyfinAdapterTest` / `NavidromeAdapterTest` / `SubsonicAdapterTest`。
- 双语资源：T7d 新增的 `library_songs_load_failed` 等 key 必须同步 `values-en/strings.xml`，
  否则英语用户看到空串（本项目 `VisualizerThemeStringsGateTest` 正是为防这类问题而设）。

**回滚**：所有改动均为方法内局部修改，不涉及数据结构变更，`git revert` 即可。

---

## 7. 待确认项（需要用户在真机/服务端补充的信息）

> **关键建议**：以下 1-2 项是最快的定位路径，用 §4.1b 的脚本一次就能定性。

| # | 待确认项 | 如何确认 | 阻塞影响 |
| --- | --- | --- | --- |
| **1** | **APP 的 `getSongs` 请求返回的信封 `code` 是否为 0** | §4.1b：打印 `code` + `msg` | **一锤定音**：`code != 0` → H2b；`code == 0` → H1 |
| **2** | **APP 的 `getSongs` 请求是否真的返回了数据**（`len(list)`） | §4.1 ② 复现 `?sort=createdAt,desc&page=1&size=500` | 区分「服务端拒绝」与「APP 解析挂」 |
| 3 | `track/list` 单条曲目的字段名是 `title` 还是 `name` | §4.2 | H1a 判定 |
| 4 | `duration` 是数字还是字符串（或存在混合类型） | §4.2 | H1b 判定 |
| 5 | 服务端 `track/list` 的 `size` 有效上限（静默截断到多少） | §4.1 ③ 的循环脚本 | H2 残余 / 性能 |
| 6 | logcat 中是否出现 `getSongs failed` / `parse item failed` | §4.3 | H1b vs H1a 判定 |
| 7 | 服务端是否对超大 `size` 返回错误码（而非静默截断） | 观察 HTTP status + 信封 `code` | 决定是否需要 `code` 分支处理 |
| 8 | 服务端是否已有 `track/search` 端点（后续版本） | 官方 API 变更日志 | T5 长期优化 |

> ✅ **已确认项（P1）**：`total=1541` 稳定、`list` 完整、服务端有数据 → 已排除「服务端无数据」与「`total` 异常」。
>
> ✅ **已确认项（用户推理）**：「静默截断只能减少数量、不可能变 0」→ 已排除 H2 的「静默截断」分支，指向 **H1 或 H2b**。

---

## 8. 参考资料

- 项目内协议文档：`docs/archive/feiniu-backend-improvement-plan.md`
  - §2.4 端点表：`track/list` → `{list:[Track],total}`，`page`/`size`/`sort=createdAt,desc`
  - §2.5 DTO 语义：`TrackDto(guid, title, coverId, duration /* 毫秒 */, ...)`，`AlbumDto(guid, name, ...)`，`ArtistDto(guid, name, ...)`
  - §4 任务 T1-T8：重写传输层、认证、解析
- 源码：
  - `app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt`（`parseTrack` :1176；`fetchAllPages` :1043；`getSongs` :594；`loadAllTracks` :703；`searchSongs` :680；`testConnection` :499；`dataOf` :1012；`execute` :950；`recordNetworkError` :992；`renderFailure` :393；`lastFailure` :189）
  - `app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuUrl.kt`（`normalize` :57；`endpoint` :106 —— 用 `URLEncoder.encode(value, "UTF-8")` :114，逗号编码为 `%2C`）
  - `app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt`（`lastErrorDetail` 接口默认实现 :68）
  - `app/src/main/java/com/nasmusic/tv/backend/BackendRegistry.kt`（`lastErrorDetail` 只在 `initialize` :126/:152 与 `testConnection` :239 快照；**T7a 需新增 `currentErrorDetail()`**）
  - `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`（`pageSize=500` :297；`loadSongsNextPage` :1144；`loadAllSongsBackground` :1179；`showError` :740；`_errorMessage` :716）
  - `app/src/main/java/com/nasmusic/tv/data/model/SongsPagingState.kt`（**T7b 需新增 `error` 字段**）
  - `app/src/main/java/com/nasmusic/tv/ui/screens/library/browse/SongList.kt`（空态渲染 :113 —— **T7c 需改造**，接收 `error` + `onRetrySongs`）
  - `app/src/main/res/values/strings.xml` / `values-en/strings.xml`（`feiniu_err_*` :1476-1483；`library_no_songs` :629；**T7d 需新增 `library_songs_load_failed` 等**）
- 参考项目：`github.com/QiaoKes/fn-music-tv`（飞牛音乐 TV 客户端，GPL-3.0；协议依据来源）
- 飞牛 fnOS 官网 / 飞牛社区：`https://www.fnnas.com`、`https://club.fnnas.com`（应用版本、更新日志）
- fnOS Web API 社区文档：`https://fnosp.github.io/fnnas-api/`（系统级 API 文档，非音乐服务专用）

---

## 附录 A｜为什么「连接测试通过」但不能说明曲目可用

`FeiniuAdapter.testConnection()`（`:499`）走的是：

```kotlin
if (userToken.isNotBlank()) verifyToken()          // GET user/me
else {
    val url = FeiniuUrl.endpoint(apiBase, "sys/config")
    dataOf(get(url, authenticated = false), url) != null   // 免认证探测
}
```

这两条链路**与 `track/list`、`parseTrack` 完全独立**。因此：

- 「连接测试通过」只证明「认证/基址/网络可达」；
- 「专辑/艺术家正常」证明「`album/list`、`artist/list` 的翻页与 `parseAlbum`/`parseArtist` 链路 OK」；
- 「歌曲为 0」只能来自 `track/list` 取数或 `parseTrack` 解析 —— 这正是本文档 §3 假设覆盖的范围。

**结论**：连接测试成功不能作为「歌曲接口正常」的证据，需要在诊断时把三个路径分开验证。

---

## 9. 分步验证：脚本输出 → 代码修改（决策表）

> **两步走的落地依据**。用户跑 `docs/feiniu-diagnose.sh` 后，把脚本输出反馈回来；
> 开发者**按下面这张表**选择要改哪些 T 方案，而不是盲目全改。

### 9.1 脚本运行方式（用户侧）

Windows（推荐 Git Bash / WSL）、Linux、macOS 通用。**密码会由脚本自动算 sha256 传给服务端，
不需要用户手工算 hash**；脚本只做诊断、不保存任何凭据、输出中的 token 已脱敏。

```bash
# 基本用法（服务器地址可带端口）
./docs/feiniu-diagnose.sh <服务器地址> <用户名> <密码>

# 开启「外网访问码」的飞牛：多传第 4 个参数
./docs/feiniu-diagnose.sh 192.168.1.100:5666 admin MyP@ss123 "访问码"

# 保存完整输出回传给开发者（建议这样做，避免复制遗漏）
./docs/feiniu-diagnose.sh 192.168.1.100 admin pass > report.txt 2>&1
```

脚本依次执行 8 个步骤：访问码探测 → 登录 → 系统版本 → 对照请求（无 sort）→ **APP 关键请求**
（`sort+size=500`）→ size 上限探测（500/300/200/100/50）→ 抓单条字段结构 → 专辑/艺术家对照组。

### 9.2 脚本输出 → 代码修改 决策表

**核心分支**：看步骤 5（APP 关键请求）的信封 `code` 与 `list` 长度。

| 脚本观测（步骤 5） | 配合观测 | 定性 | **要改的 T 方案** | 主要改动文件 |
| --- | --- | --- | --- | --- |
| `code != 0`（如 1000xx） | 步骤 6 某档 size 也报错 | **H2b 服务端拒绝参数** | **T5b + T7**（必改） | `FeiniuAdapter.kt`（`dataOf`/`fetchAllPages`）；`MainViewModel.kt`；`EmptyHint` |
| `code == 0` 且 `list` 有数据但 `< size` | 步骤 6 定位截断档位（如 500→实回 200） | H3 页大小不匹配 | **T2 + T3**（自适应 size） | `FeiniuAdapter.kt`（`getSongs`/新增 `effectivePageSize`）；`MainViewModel.kt`（`hasMore` 对齐 `totalCount`） |
| `code == 0` 且 `list` 有数据、size 不截断 | 步骤 7 **单条无 `title` 只有 `name`** | **H1a 字段名不匹配** | **T0 + T1**（`parseTrack` 字段容错） | `FeiniuAdapter.kt`（`parseTrack`/`fetchAllPages`） |
| `code == 0` 且 `list` 有数据、字段名正常 | 步骤 7 **`duration` 是字符串/浮点** | **H1b 类型异常** | **T0**（逐条隔离，必须）+ T1（`parseDurationMs`） | `FeiniuAdapter.kt`（`fetchAllPages`/`parseTrack`） |
| `code == 0` 且 `list` 正常，但**APP 仍显示 0 首** | — | 客户端状态残留 / 缓存（H5） | 排查 `allTracksCache` / `_songsPaging` 复位时机 | `MainViewModel.kt` |
| `total == 0` 且 `list == 0` | 步骤 8 专辑/艺术家也空 | 服务端真的没歌 | **不改代码**，提示用户确认库 | — |

### 9.3 常见组合与最小改动集

症状可能同时命中多个假设，按「是否影响 0 首」给优先级：

| 组合 | 判断 | 最小改动集 |
| --- | --- | --- |
| **H2b 单独命中**（`code!=0`） | 服务端拒绝参数，其他全正常 | T5b + T7（错误展示） |
| **H1a/H1b 单独命中**（`code==0`、字段问题） | APP 解析挂，服务端正常 | T0 + T1（若 T1 字段名兜底也做）+ T7（让解析失败可见） |
| **H2b + H3 同时**（`code!=0` 且小 size 才成功） | 服务端对超大 size 报错，但小 size 可用 | T5b + T2 + T7 |
| **三管齐下**（不确定，想一次覆盖） | 兜底 | T0 + T1 + T5b + T7（T2/T3 视 size 探测结果） |

### 9.4 改码后的验证（用户侧，第 2 步）

装新 APK 后按顺序复测，**与脚本输出对应**：

1. **看 APP 是否显示具体错误**（T7 生效）：`code!=0` 时 UI 应显示「歌曲加载失败：服务端返回错误：code=xxx」+ 重试按钮，而非「暂无歌曲」；
2. **看歌曲是否 > 0**（T0/T1/T2/T3 生效）：`list` 正常时应显示曲目，且总条数 ≈ `total`；
3. **搜索 NAS 歌曲**（T0 生效）：`loadAllTracks` 不再静默空，能搜到结果；
4. **看日志**：`adb logcat -s FeiniuAdapter:*` 应出现 `parse item failed`（T0）或 `track/list rejected: code=`（T5b）；
5. **对照组不回归**：专辑、艺术家列表仍正常（改动未波及 `parseAlbum`/`parseArtist`）。

> ⚠️ 若步骤 5 显示 `code != 0`，说明**服务端确实拒绝了 APP 的参数组合**，此时 T0/T1（解析容错）
> **无法**让歌曲恢复（数据根本拿不到），必须先做 T5b + T7 让错误可见，再决定是否做 T2/T3
> 降档重试。这是两步走的根本意义——**先定性，再动手**，避免改了没用的地方。

---

## 附录 B｜诊断脚本说明（`docs/feiniu-diagnose.sh`）

**为什么用脚本而不是让用户手敲 curl**：

| 手敲 curl 的问题 | 脚本如何解决 |
| --- | --- |
| 登录要 `sha256(密码)`，用户不会算 | 脚本用 Python 自动算，只收明文密码（不落盘、脱敏回显） |
| 访问码要 `base64(code)`，用户不会编 | 脚本自动 base64 并注入 `x-access-code` / `x-access-source` 头 |
| 用户不知道 APP 的确切参数（`sort` 编码等） | 脚本硬编码 APP 的请求，自动拼 `sort=createdAt%2Cdesc&page=1&size=500` |
| 输出只有裸 JSON，看不出关键字段 | 脚本统一打印 HTTP 状态 + 信封 `code/msg/total/list 长度`，一眼判读 |
| 单条字段结构难核对 | 步骤 7 自动打印第一条曲目的「字段名 → 类型 / 示例值」 |

**脚本关键实现对齐 APP**（均已核对源码）：

- 基址归一化：`FeiniuUrl.normalize` 同约定，缺 scheme 补 http、缺端口补 5666/5667；
- 登录：`POST user/password-login`，body `{username, password: sha256hex, deviceId}`，取 `data.userToken`；
- 认证头：`Authorization: <token>`（**无 Bearer 前缀**，与 `FeiniuAdapter.kt:959` 一致）；
- 访问码头：`x-access-code: base64(code)` + `x-access-source: app`（与 `FeiniuAdapter.kt` 一致）；
- 参数编码：`sort=createdAt%2Cdesc`（`URLEncoder` 结果，与 `FeiniuUrl.kt:114` 一致）；
- 密码摘要：SHA-256 小写 hex（与 `FeiniuAdapter.kt:1255-1258` 一致）。

**已知限制**：

- 脚本依赖 `python`/`python3`（仅用于 sha256/base64/JSON 解析），Windows Git Bash 需装 Python；
- 脚本不保存任何凭据；输出中 `token` 只显示首 8 + 尾 4 位；
- 已强制 `PYTHONIOENCODING=utf-8`，避免 Windows 默认 GBK 把中文输出打成乱码；
- 若飞牛服务端未开启访问码，脚本步骤 1 的探测可能返回非 0，属正常，脚本会提示「若未开启请忽略」。