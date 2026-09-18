package com.trae.simplebrowser.core

import com.trae.simplebrowser.Bookmark

/**
 * 地址栏输入联想（纯 Kotlin 可单测）：
 * 历史 + 书签合并匹配，url 完整命中优先（前缀其次、包含最后），同 url 去重（书签优先展示）。
 */
data class Suggestion(
    val title: String,
    val url: String,
    val fromBookmark: Boolean,
    /** 排序权重：越大越靠前 */
    val score: Int
)

object SuggestEngine {

    fun suggest(
        history: List<HistoryEntry>,
        bookmarks: List<Bookmark>,
        query: String,
        limit: Int = 8
    ): List<Suggestion> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val out = mutableListOf<Suggestion>()
        fun scoreOf(text: String): Int = when {
            text == q -> 3
            text.startsWith(q) -> 2
            text.contains(q) -> 1
            else -> 0
        }

        for (b in bookmarks) {
            val s = maxOf(scoreOf(b.url.lowercase()), scoreOf(b.title.lowercase()))
            if (s > 0) out.add(Suggestion(b.title.ifBlank { b.url }, b.url, true, s + 1))
        }
        for (h in history) {
            val s = maxOf(scoreOf(h.url.lowercase()), scoreOf(h.title.lowercase()))
            if (s > 0) out.add(Suggestion(h.title.ifBlank { h.url }, h.url, false, s))
        }

        // 同 url 去重：书签条目优先（stable sorted 后先出现者保留）
        val deduped = linkedMapOf<String, Suggestion>()
        for (item in out.sortedByDescending { it.score }) {
            deduped.putIfAbsent(item.url, item)
        }
        return deduped.values.take(limit)
    }
}
