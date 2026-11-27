package com.trae.simplebrowser.core

/**
 * 快捷方式复用匹配（纯 Kotlin，可 JVM 单元测试）。
 * 语义：点桌面快捷方式 = 回到该快捷方式指向的那个页面（同身份 + 同页面），而不是重新打开一遍。
 * 页面按 协议+主机+端口+路径+查询 判定（忽略 #片段）：同一站点上的不同快捷方式
 * （如 / 与 /my-batch-calendar）互不抢占，各回各的页面。
 */
object TabMatcher {

    /**
     * 从 URL 提取主机名（小写）。仅认 http/https；无 scheme、空串、裸域名等返回 null。
     * 域名含子域名：m.example.com 与 www.example.com 是不同主机，互不匹配。
     */
    fun urlHost(url: String?): String? = splitUrl(url)?.host

    /**
     * 页面匹配键：scheme://host[:port]/path[?query]，忽略 #片段。
     * - 主机小写；默认端口（http:80 / https:443）省略
     * - 空路径按 "/"；末尾多余的 "/" 归一（"/p/" 与 "/p" 视为同一页面）
     * - 查询串原样保留（"/p?a=1" 与 "/p?a=2" 视为不同页面）
     * 仅认 http/https；其余返回 null。
     */
    fun pageKey(url: String?): String? {
        val u = splitUrl(url) ?: return null
        val port = when {
            u.port.isNullOrBlank() -> ""
            (u.scheme == "http" && u.port == "80") -> ""
            (u.scheme == "https" && u.port == "443") -> ""
            else -> ":${u.port}"
        }
        var path = u.path.ifBlank { "/" }
        if (path.length > 1) path = path.trimEnd('/').ifBlank { "/" }
        val query = if (u.query.isEmpty()) "" else "?${u.query}"
        return "${u.scheme}://${u.host}$port$path$query"
    }

    /**
     * 找可复用的快捷方式目标标签：同身份（Profile）+ 同一页面（完整页面键一致）。
     * 多个命中取最近活跃的（lastActiveAtMs 缺失按 0，即更早的）。
     */
    fun findReusableTabId(
        tabs: List<TabManager.Tab>,
        shortcutUrl: String?,
        profile: String,
        lastActiveAtMs: Map<Long, Long>
    ): Long? {
        val key = pageKey(shortcutUrl) ?: return null
        return tabs.asSequence()
            .filter { it.profile == profile }
            .filter { pageKey(it.url) == key }
            .maxByOrNull { lastActiveAtMs[it.id] ?: 0L }
            ?.id
    }

    private data class ParsedUrl(
        val scheme: String,
        val host: String,
        val port: String?,
        val path: String,
        val query: String
    )

    /** 拆解 http(s) URL；IPv6 字面量（[::1]）与非法输入返回 null（App 面向普通网页，不做特殊匹配） */
    private fun splitUrl(url: String?): ParsedUrl? {
        if (url.isNullOrBlank()) return null
        val scheme = when {
            url.startsWith("http://", ignoreCase = true) -> "http"
            url.startsWith("https://", ignoreCase = true) -> "https"
            else -> return null
        }
        val rest = url.substring(scheme.length + 3)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd < 0) rest else rest.substring(0, authorityEnd)
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isBlank() || hostPort.startsWith("[")) return null
        val host = hostPort.substringBefore(':').lowercase()
        if (host.isBlank()) return null
        val port = hostPort.substringAfter(':', missingDelimiterValue = "").ifBlank { null }
        val afterAuthority = if (authorityEnd < 0) "" else rest.substring(authorityEnd)
        val withoutFragment = afterAuthority.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        val query = if ('?' in withoutFragment) withoutFragment.substringAfter('?') else ""
        return ParsedUrl(scheme, host, port, path, query)
    }
}