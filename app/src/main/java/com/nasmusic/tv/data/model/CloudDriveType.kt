package com.nasmusic.tv.data.model

import androidx.annotation.StringRes
import com.nasmusic.tv.R

/**
 * 网盘类型枚举（可扩展）
 *
 * v2.39.0 起设置页只渲染百度网盘一项（方案 §6-4，用户 2026-10-18 确认删除
 * 「其他网盘」占位分组）⇒ 原 `IMPLEMENTED` / `PLACEHOLDER` 两个集合随
 * `PlaceholderRow` 一并删除。四个枚举条目、`key`、`displayNameRes` 与
 * [fromKey] **必须保留**：`aliyun` / `123` / `quark` 三个 key 仍可能出现在
 * 旧 `CloudDriveConfig` JSON 里，`fromKey` 要能解析（删了等于老用户的
 * 配置读不出来）。
 *
 * @param key         AppPreferences 存储键（与 sourceId 一致）
 * @param displayName 设置页显示名（中文原值，数据用途）
 */
enum class CloudDriveType(
    val key: String,
    /** 显示名资源 ID（本地化展示用） */
    @StringRes val displayNameRes: Int,
    /** 设置页显示名（中文原值，UI 展示走 [displayNameRes]） */
    val displayName: String
) {
    BAIDU("baidu", R.string.cloud_drive_baidu, "百度网盘"),
    ALIYUN("aliyun", R.string.cloud_drive_aliyun, "阿里云盘"),
    DRIVE_123("123", R.string.cloud_drive_123, "123网盘"),
    QUARK("quark", R.string.cloud_drive_quark, "夸克网盘");

    companion object {
        fun fromKey(key: String?): CloudDriveType? = entries.find { it.key == key }
    }
}
