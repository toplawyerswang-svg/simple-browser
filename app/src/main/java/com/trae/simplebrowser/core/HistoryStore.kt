package com.trae.simplebrowser.core

/**
 * 浏览历史纯逻辑（存取由 AppPrefs 做 JSON 序列化，这里只管数据规则，可 JVM 单测）。
 * 规则：同 url 视为同一条（移到最新）、列表按时间倒序、总量上限截断。
 */
data class HistoryEntry(
    val title: String,
    val url: String,
    val ts: Long
)

object HistoryStore {
    const val MAX_ENTRIES = 200

    /** 返回插入后的新列表：去重 + 置顶 + 截断；url 空白时原样返回 */
    fun add(list: List<HistoryEntry>, title: String, url: String, ts: Long): List<HistoryEntry> {
        if (url.isBlank()) return list
        val rest = list.filterNot { it.url == url }
        val entry = HistoryEntry(title.ifBlank { url }, url, ts)
        return (listOf(entry) + rest).take(MAX_ENTRIES)
    }

    /** 删除单条（按 url 精确匹配） */
    fun remove(list: List<HistoryEntry>, url: String): List<HistoryEntry> =
        list.filterNot { it.url == url }

    /** 建议匹配：url 或 title 包含关键词（忽略大小写），保持时间倒序 */
    fun filterMatch(list: List<HistoryEntry>, query: String, limit: Int): List<HistoryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return list.filter { entry ->
            entry.url.lowercase().contains(q) || entry.title.lowercase().contains(q)
        }.take(limit)
    }
}
