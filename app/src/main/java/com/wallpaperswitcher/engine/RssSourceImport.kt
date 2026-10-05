package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.RssSource

/**
 * 导入订阅源时的合并规则：**同一个 URL 算同一个源，已有就就地更新**。
 *
 * 之前「导入阅读订阅源」是无条件 `insert`，把同一份清单再导入一次（更新了规则、
 * 或者只是重复点了导入）就会多出一行同名订阅源；配置导入那条路则是"同 URL 跳过"，
 * 规则更新进不来。现在两条路都走这里：[decide] 决定新增/更新哪些行，
 * [merge] 负责"更新"的字段取舍。
 *
 * 身份取裁剪空白后的 URL（与配置导入原先的去重口径一致），文件名/标题变化不影响
 * 判定。更新**保留 id**，因此这个源的文章缓存、登录信息（按 sourceId 存）、
 * 桌面列表里的位置都不会丢，也不会有第二行。
 */
object RssSourceImport {

    /** 一次导入的落地结果：[inserted] 全新行，[updated] 就地更新的已有行。 */
    data class Decision(
        val inserted: List<RssSource>,
        val updated: List<RssSource>,
        /** 没有 URL、无法判定的条目数（解析阶段一般已经过滤掉）。 */
        val ignored: Int,
    )

    /**
     * 把导入的 [incoming] 合并进当前列表 [existing]。
     *
     * - URL 相同 → [merge] 就地更新（同一批里出现多次也只留最后一条）；
     * - URL 没见过 → 新增；
     * - URL 为空 → 忽略并计入 [Decision.ignored]。
     */
    fun decide(existing: List<RssSource>, incoming: List<RssSource>): Decision {
        val byUrl = LinkedHashMap<String, RssSource>()
        for (source in existing) {
            urlKey(source.url)?.let { key -> byUrl.putIfAbsent(key, source) }
        }
        var ignored = 0
        for (source in incoming) {
            val key = urlKey(source.url)
            if (key == null) {
                ignored++
                continue
            }
            val current = byUrl[key]
            byUrl[key] = if (current == null) source.copy(url = key) else merge(current, source)
        }
        // id == 0 的是新行（Room 自增，已有行必然带 id）。
        return Decision(
            inserted = byUrl.values.filter { it.id == 0L },
            updated = byUrl.values.filter { it.id != 0L },
            ignored = ignored,
        )
    }

    /**
     * 同 URL 的"更新"：内容跟随导入（名称、类型、原始规则 JSON），
     * id / createdAt / 抓取状态 / 本地开关状态保留——用户在列表里关掉的源
     * 不会因为重新导入被悄悄打开，列表顺序也不会跳。
     */
    fun merge(existing: RssSource, incoming: RssSource): RssSource = existing.copy(
        name = incoming.name.trim().ifBlank { existing.name },
        url = incoming.url.trim(),
        type = incoming.type,
        rawJson = incoming.rawJson.ifBlank { existing.rawJson },
    )

    private fun urlKey(url: String?): String? =
        url?.trim()?.takeIf { it.isNotEmpty() }
}
