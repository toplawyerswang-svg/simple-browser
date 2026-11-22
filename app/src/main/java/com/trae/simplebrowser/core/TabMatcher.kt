package com.trae.simplebrowser.core

/**
 * 快捷方式复用匹配（纯 Kotlin，可 JVM 单元测试）。
 * 语义：点桌面快捷方式 = 回到已开着的那个站（同身份 + 同域名），而不是重新打开一遍。
 */
object TabMatcher {

    /**
     * 从 URL 提取主机名（小写）。仅认 http/https；无 scheme、空串、裸域名等返回 null。
     * 域名含子域名：m.example.com 与 www.example.com 是不同主机，互不匹配。
     */
    fun urlHost(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val rest = when {
            url.startsWith("http://", ignoreCase = true) -> url.substring(7)
            url.startsWith("https://", ignoreCase = true) -> url.substring(8)
            else -> return null
        }
        // 取 authority（到第一个 / ? # 为止），去掉 userinfo@，去掉端口
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@', missingDelimiterValue = authority)
        val host = hostPort.substringBefore(':')
        return host.takeIf { it.isNotBlank() }?.lowercase()
    }

    /**
     * 找可复用的快捷方式目标标签：同身份（Profile）+ 同域名（区分子域名）。
     * 多个命中取最近活跃的（lastActiveAtMs 缺失按 0，即更早的）。
     */
    fun findReusableTabId(
        tabs: List<TabManager.Tab>,
        shortcutUrl: String?,
        profile: String,
        lastActiveAtMs: Map<Long, Long>
    ): Long? {
        val host = urlHost(shortcutUrl) ?: return null
        return tabs.asSequence()
            .filter { it.profile == profile }
            .filter { urlHost(it.url) == host }
            .maxByOrNull { lastActiveAtMs[it.id] ?: 0L }
            ?.id
    }
}
