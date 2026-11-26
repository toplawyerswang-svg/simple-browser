package com.trae.simplebrowser.core

/**
 * 「移入文件夹」选夹弹窗的关键词过滤（2026-11-26 用户需求：夹多了翻着费劲）。
 *
 * 口径与 APP 其他搜索一致：
 * - **空关键词（含纯空白）返回全量**——打开弹窗默认看到所有文件夹；
 * - 多个关键词按空白切分（半角/全角空格都算），全部命中才算（AND），忽略大小写；
 * - 只匹配文件夹名称，保持原有顺序。
 *
 * 零 Android 依赖，JVM 单测直跑。
 */
object FolderPicker {

    /** 关键词分隔：半角空白与全角空格（中文输入法常见）都当分隔符 */
    private val SEP = Regex("[\\s\\u3000]+")

    fun terms(query: String): List<String> =
        query.trim().lowercase().split(SEP).filter { it.isNotEmpty() }

    /** 按名称过滤；空关键词原样返回（同一引用，调用方可据此判断是否在过滤） */
    fun <T> filter(items: List<T>, query: String, name: (T) -> String): List<T> {
        val ts = terms(query)
        if (ts.isEmpty()) return items
        return items.filter { item ->
            val lower = name(item).lowercase()
            ts.all { lower.contains(it) }
        }
    }
}