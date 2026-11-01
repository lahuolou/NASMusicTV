package com.nasmusic.tv.util

import com.nasmusic.tv.BuildConfig

/**
 * 应用日志工具（按构建类型分层）。
 *
 * | 级别 | Debug 构建 | Release 构建 |
 * |---|---|---|
 * | d / i | logcat + 落盘 | **完全不产生** |
 * | w | logcat + 落盘 | 落盘 |
 * | e | logcat + 落盘 | logcat + 落盘 |
 *
 * 落盘（[LogStore]）是为设备侧回看：电视上没法连 adb，用户报「刚才闪退了」时
 * logcat 早没了。日志页（设置 → 日志）可按级别过滤，D/I 只在 Debug 出现。
 *
 * ⚠️ **分级准则**：逐端点 / 逐请求 / 缓存命中等追踪信息用 `d`；
 * 聚合后的功能失败（所有端点都失败、解析失败）用 `w`；
 * 崩溃、数据丢失、用户操作失败用 `e`。⛔ 不要把 `e` 当「留痕」用——
 * release 日志页只应出现真正需要处理的问题。
 *
 * ⚠️ 本类不得反向被 [LogStore] 调用（递归），见 `LogsTabWiringGateTest`。
 */
object AppLog {
    /** Debug 级别日志：仅 Debug 构建产生（logcat + 落盘） */
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            LogStore.append(LogStore.LEVEL_DEBUG, tag, message, throwable)
            android.util.Log.d(tag, message, throwable)
        }
    }

    /** Info 级别日志：仅 Debug 构建产生（logcat + 落盘） */
    fun i(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            LogStore.append(LogStore.LEVEL_INFO, tag, message, throwable)
            android.util.Log.i(tag, message, throwable)
        }
    }

    /** Warning 级别：始终落盘；logcat 仅 Debug 输出（release 不刷系统日志） */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        LogStore.append(LogStore.LEVEL_WARN, tag, message, throwable)
        if (BuildConfig.DEBUG) {
            android.util.Log.w(tag, message, throwable)
        }
    }

    /** Error 级别：始终落盘 + 始终输出（生产环境也必须可见） */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        LogStore.append(LogStore.LEVEL_ERROR, tag, message, throwable)
        android.util.Log.e(tag, message, throwable)
    }
}
