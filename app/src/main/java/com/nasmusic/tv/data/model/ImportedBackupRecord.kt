package com.nasmusic.tv.data.model

/**
 * 「用户自己在系统选择器里挑过、并成功恢复过」的备份文件登记（存于 DataStore，
 * 见 `AppPreferences.importedBackups`）。
 *
 * ## 为什么需要它（v2.39.4，真机取证 2026-10-21）
 * 备份列表 `BackupFileUtils.listBackups()` 走 `MediaStore.Downloads`，而 MediaProvider 的
 * 默认可见性规则是**只能看见本 app 拥有的行**。用户从电脑/小米互传/浏览器下载拷进
 * `Download/NASMusic/` 的备份 json，`owner_package_name` 是 `com.android.shell`（或下载器包名）
 * ⇒ 选择器（用户逐次授权）读得到、恢复能成功，列表却列不出来 —— 在用户眼里就是
 * 「导入成功了但文件凭空消失」。这条登记把「挑过的文件」补回列表，不复制文件、不占双份空间。
 *
 * - uri 必须是 SAF 的**可持久化**授权（`LocalFilePicker.persistRead`），重启后依然能读；
 *   授权丢失（文件被移走/用户撤销）时由 [mergeImportedEntries][com.nasmusic.tv.util.BackupFileUtils.mergeImportedEntries]
 *   按 `persistedUriPermissions` 剔掉，不留点必炸的死行
 * - 存最近 20 条，按 name 淘汰重复；不进备份导出内容（uri 是本机授权，换设备无意义）
 * - Gson 序列化（data.model 已在 ProGuard keep 名单内）
 */
data class ImportedBackupRecord(
    val uri: String,
    val displayName: String,
    val lastModified: Long,
)
