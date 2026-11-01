package com.nasmusic.tv.data.cache

import android.content.Context
import androidx.annotation.StringRes
import com.nasmusic.tv.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 落盘根。⛔ 不许新增"猜路径"的根（如 `applicationInfo.dataDir`），数据库一律走 `getDatabasePath`。
 */
enum class CacheRoot { CACHE_DIR, FILES_DIR, EXTERNAL_MUSIC, EXTERNAL_ROOT, DATABASES }

/** 占用一览的分组（卡②「占用与存储」按此分组，小计 = 组内条目相加） */
enum class CacheGroup { APP_CACHE, PERSISTENT, INDEX_DB, MEDIA_MODEL, APP_DATA }

/**
 * 一条可管理的落盘缓存登记项（方案 §1.14）。
 *
 * - [dir] 必须是源码里出现的**字面量**（`cover_cache_` / `demucs_pcm_` 这类模板前缀要连下划线一起写），
 *   门禁 G9（`CacheInventoryGateTest`）就是拿它和全树扫描结果对账的；外部根条目留空串（路径由 [CacheRoot] 决定）。
 * - [deletable] = 有可用的清除入口（**可能**在别的分区，如人脸库入口在照片墙）。
 *   G9 判据②会反查 [clearVia] 在 main 源集里真有 `fun` 声明 ⇒ "说能清其实清不了"过不了门。
 *   ⚠️ [clearVia] 一律填**设置链路真正调用**的那个函数名（`MainViewModel.clearXxx`），
 *   ⛔ 不要填库内部的同名 `clear()` —— 本轮 3 个漏网项正是那么漏掉的。
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
            // cache-inventory-exempt: 外部音乐目录的回落路径，与 EXTERNAL_MUSIC（dir=""）是同一份数据
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
            return@withContext sumSizes(
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
        sumSizes(sizes, truncated)
    }

    private fun sumSizes(sizes: List<Long>, truncated: Boolean) =
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
