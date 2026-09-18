package com.trae.simplebrowser.core

import java.net.URLEncoder

/**
 * 搜索引擎定义（纯 Kotlin 可单测）。
 * 模板尾部直接拼 URL 编码后的关键词，如 "https://www.baidu.com/s?wd="。
 */
data class SearchEngine(
    val id: String,
    val name: String,
    val template: String
) {
    fun buildQueryUrl(query: String): String =
        template + URLEncoder.encode(query, "UTF-8")
}

object SearchEngines {
    val ALL = listOf(
        SearchEngine("baidu", "百度", "https://www.baidu.com/s?wd="),
        SearchEngine("bing", "必应", "https://www.bing.com/search?q="),
        SearchEngine("sogou", "搜狗", "https://www.sogou.com/web?query="),
        SearchEngine("google", "谷歌", "https://www.google.com/search?q=")
    )

    const val DEFAULT_ID = "baidu"

    /** 未知 id 回退默认引擎（老升级用户无偏好记录时直接可用） */
    fun byId(id: String?): SearchEngine =
        ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_ID }
}
