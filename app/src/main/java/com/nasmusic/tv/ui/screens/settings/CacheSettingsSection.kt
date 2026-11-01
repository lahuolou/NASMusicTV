package com.nasmusic.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.R
import com.nasmusic.tv.data.cache.CacheEntry
import com.nasmusic.tv.data.cache.CacheGroup
import com.nasmusic.tv.data.cache.CacheInventory
import com.nasmusic.tv.data.cache.CacheSize
import com.nasmusic.tv.data.model.AppSettings
import kotlinx.coroutines.delay

/** 缓存分区状态 */
data class CacheSettingsState(
    val settings: AppSettings,
    /**
     * 剩余空间（`StorageGuard.availableBytes` 的当前值）。
     * ⛔ 不要在分区里另起一个 `StatFs` —— 那会与下载队列看到的"可用空间"出现两个数。
     */
    val availableBytes: Long = 0L,
)

/** 缓存分区动作 */
data class CacheSettingsActions(
    val onToggleCacheLyrics: (Boolean) -> Unit,
    val onToggleCacheCover: (Boolean) -> Unit,
    val onClearLyricsCache: (() -> Unit)?,
    val onClearCoverCache: (() -> Unit)?,
    val onClearMvCache: (() -> Unit)?,
    /** T8b：伴奏/人声副本 + 分离临时残留（取代旧「清除全部缓存」，范围与返回值都已扩容） */
    val onClearSeparationArtifacts: (() -> Unit)? = null,
    /** T8b：`filesDir/cover_url_cache.json` */
    val onClearCoverUrlCache: (() -> Unit)? = null,
    /** T8b：`cacheDir/cover_cache_*` + `cacheDir/album_covers/` */
    val onClearExtractedCoverCache: (() -> Unit)? = null,
)

/**
 * 缓存分区（三卡：缓存策略 / 占用与存储 / 缓存清理）。
 *
 * T8 只把原 CACHE 分支包成两张卡（形态变更）；T8b 在这里补**内容**：占用一览从
 * 「`cacheDir` 一个数字」扩成「剩余空间 + 5 组 + 19 条落盘位置」，清理按钮从 4 个补到 6 个，
 * 并且**统计口径与清除口径同源**（都走 [CacheInventory]），所以点完按钮数字一定会回落。
 *
 * ⚠️ 两条硬约束（方案 §1.14 结论 1 / 审查 #13）：
 * ① 快照只在一个 IO 协程里算一次（`LaunchedEffect(refreshKey)`），⛔ 不在 Composable 里 walk；
 * ② 数字一律来自 `CacheInventory.snapshot()` + `groupTotal()`，⛔ UI 侧不自己求和。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun CacheSettingsSection(
    state: CacheSettingsState,
    actions: CacheSettingsActions
) {
    val context = LocalContext.current
    // 占用快照：进分区算一次；任一清理按钮点过（refreshKey++）后重算。
    var snapshot by remember { mutableStateOf<Map<CacheEntry, CacheSize>>(emptyMap()) }
    // 2026-09-26 审查补修（#11 Low）：分区内清理缓存后数字不刷新。加 refreshKey 触发
    // 重算——每个清理按钮点击后 ++，LaunchedEffect(refreshKey) 重新执行。
    var refreshKey by remember { mutableStateOf(0) }
    LaunchedEffect(refreshKey) {
        // 清理动作是 fire-and-forget 异步（MainViewModel 里 viewModelScope.launch），
        // 重算前留 300ms 让其落盘，否则可能量到删除中途的尺寸。
        if (refreshKey > 0) delay(300)
        // 单次 IO 协程算完 19 条（内部已 withContext(IO)），UI 只在结果回来时重组一次。
        snapshot = CacheInventory.snapshot(context)
    }
    Column {
        SectionTitle(stringResource(R.string.settings_cache))
        // 卡① 缓存策略（原歌词 tab 的歌词/封面缓存开关）
        SettingsGroupCard(title = stringResource(R.string.settings_group_cache_policy)) {
            SettingSwitch(label = stringResource(R.string.settings_cache_lyrics), description = stringResource(R.string.settings_cache_lyrics_desc), checked = state.settings.cacheLyrics, onClick = { actions.onToggleCacheLyrics(!state.settings.cacheLyrics) })
            SettingSwitch(label = stringResource(R.string.settings_cache_cover), description = stringResource(R.string.settings_cache_cover_desc), checked = state.settings.cacheCover, onClick = { actions.onToggleCacheCover(!state.settings.cacheCover) })
        }
        // 卡② 占用与存储（只读：剩余空间 + 5 组 + 19 条）
        SettingsGroupCard(
            title = stringResource(R.string.settings_group_cache_occupancy),
            description = stringResource(R.string.settings_cache_dir_size)
        ) {
            SettingsInfoRow(
                label = stringResource(R.string.settings_cache_free_space),
                value = formatBytes(state.availableBytes)
            )
            CacheInventory.groups.forEach { group ->
                SettingsInfoRow(
                    label = stringResource(cacheGroupLabel(group)),
                    value = CacheInventory.groupTotal(snapshot, group).toString()
                )
                CacheInventory.entries.filter { it.group == group }.forEach { entry ->
                    SettingsInfoRow(
                        label = stringResource(entry.labelRes),
                        // 「≥」前缀由 CacheSize.toString() 给（扫描上限的唯一可见证据，⛔ 不要在 UI 侧剥掉）
                        value = (snapshot[entry] ?: CacheSize(0L, false)).toString(),
                        description = entry.descRes?.let { stringResource(it) },
                        indent = true
                    )
                }
            }
        }
        // 卡③ 缓存清理（6 个入口）
        SettingsGroupCard(title = stringResource(R.string.settings_group_cache_clear)) {
            if (actions.onClearLyricsCache != null) {
                SettingActionButton(
                    label = stringResource(R.string.settings_clear_lyrics_cache),
                    description = stringResource(R.string.settings_clear_lyrics_cache_desc),
                    onClick = {
                        actions.onClearLyricsCache?.invoke()
                        refreshKey++
                    }
                )
            }
            if (actions.onClearCoverCache != null) {
                SettingActionButton(
                    label = stringResource(R.string.settings_clear_cover_cache),
                    description = stringResource(R.string.settings_clear_coil_cache),
                    onClick = {
                        actions.onClearCoverCache?.invoke()
                        refreshKey++
                    }
                )
            }
            if (actions.onClearExtractedCoverCache != null) {
                SettingActionButton(
                    label = stringResource(R.string.settings_clear_extracted_cover),
                    description = stringResource(R.string.settings_clear_extracted_cover_desc),
                    onClick = {
                        actions.onClearExtractedCoverCache?.invoke()
                        refreshKey++
                    }
                )
            }
            if (actions.onClearCoverUrlCache != null) {
                SettingActionButton(
                    label = stringResource(R.string.settings_clear_cover_url),
                    description = stringResource(R.string.settings_clear_cover_url_desc),
                    onClick = {
                        actions.onClearCoverUrlCache?.invoke()
                        refreshKey++
                    }
                )
            }
            if (actions.onClearSeparationArtifacts != null) {
                // §4.7-③ S6：这是缓存页代价最大的一颗 —— 删掉的是分离算出来的伴奏，
                // 重建要走一遍完整分离（HQ 档在电视上以分钟计），故取 Warning 色。
                SettingActionButton(
                    label = stringResource(R.string.settings_clear_separation_cache),
                    description = stringResource(R.string.settings_clear_separation_cache_desc),
                    onClick = {
                        actions.onClearSeparationArtifacts?.invoke()
                        refreshKey++
                    },
                    danger = true,
                )
            }
            if (actions.onClearMvCache != null) {
                SettingActionButton(
                    label = stringResource(R.string.settings_clear_mv_cache),
                    description = stringResource(R.string.settings_clear_mv_cache_desc),
                    onClick = {
                        actions.onClearMvCache?.invoke()
                        refreshKey++
                    }
                )
            }
        }
    }
}

/** 分组名 → string 资源（5 组固定，`when` 穷尽保证新增分组不会漏文案） */
private fun cacheGroupLabel(group: CacheGroup): Int = when (group) {
    CacheGroup.APP_CACHE -> R.string.settings_cache_group_app_cache
    CacheGroup.PERSISTENT -> R.string.settings_cache_group_persistent
    CacheGroup.INDEX_DB -> R.string.settings_cache_group_index_db
    CacheGroup.MEDIA_MODEL -> R.string.settings_cache_group_media_model
    CacheGroup.APP_DATA -> R.string.settings_cache_group_app_data
}
