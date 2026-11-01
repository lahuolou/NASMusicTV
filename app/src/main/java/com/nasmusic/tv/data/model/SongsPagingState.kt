package com.nasmusic.tv.data.model

/**
 * 歌曲分页加载状态
 */
data class SongsPagingState(
    val songs: List<Song> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    /**
     * 本次加载失败的可展示原因（null = 无错误）。
     *
     * v2.39.3 新增：飞牛 `track/list` 返回 `code != 0` 时，底层会写
     * `FeiniuAdapter.lastFailure`，但该诊断此前**只在 initialize/testConnection
     * 时快照一次**，运行期永不回流到 UI —— 用户只看到「暂无歌曲」，完全不知道
     * 服务端已经报错。此字段把失败原因带到界面上（见 [resolveError]）。
     */
    val error: String? = null
) {
    companion object {
        /**
         * 判定「本页取空」是否要展示为错误（v2.39.3，T7b 的纯函数版本）。
         *
         * 抽成纯函数的原因：`MainViewModel` 依赖完整的 `NasMusicApp`（playerManager /
         * prefs / 各种 Manager），单测里无法安全构造；而这段判定是本次修复里最容易
         * 写错、也最需要回归保护的部分。放到这里后可以在纯 JVM 单测里覆盖全部分支。
         *
         * ## 判定规则（顺序敏感）
         *
         * | 条件 | 结论 | 返回 |
         * | --- | --- | --- |
         * | 本页有数据 | 加载正常 | `null` |
         * | 本页为空 + 有诊断 | 服务端明确报错（code!=0 / HTTP / 网络 / 令牌） | 诊断原文 |
         * | 本页为空 + 无诊断 + total>0 | 服务端声明有数据却取不到 → 解析整体失效 | [parseFailedFallback] |
         * | 本页为空 + 无诊断 + total==0 | 服务端确实没歌 | `null`（**不误报**） |
         *
         * ## 为什么不能只用 `totalCount > 0` 判定
         * 服务端返回 `code != 0` 时，`getSongs` 与 `getSongsTotalCount` 会**同时**
         * 失败（都经 `dataOf` → 返回 null → 0），`totalCount` 恒为 0。若只看
         * `totalCount > 0`，恰好漏掉「服务端明确拒绝请求」这个最该提示用户的场景。
         * 因此必须**诊断优先**。
         *
         * @param batchSize 本次实际取到的歌曲条数（`List.size`）
         * @param totalCount 服务端声明的总数（拿不到时为 0）
         * @param diagnostic 当前后端的实时诊断文案（可为空串）
         * @param parseFailedFallback 无诊断但疑似解析失效时的兜底文案（已本地化）
         * @return 要展示给用户的错误文案；`null` 表示不展示错误
         */
        fun resolveError(
            batchSize: Int,
            totalCount: Int,
            diagnostic: String,
            parseFailedFallback: String
        ): String? = when {
            batchSize > 0 -> null
            diagnostic.isNotBlank() -> diagnostic
            totalCount > 0 -> parseFailedFallback
            else -> null
        }
    }
}
