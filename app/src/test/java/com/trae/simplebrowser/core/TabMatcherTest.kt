package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快捷方式复用匹配测试：同身份 + 同页面才复用；同站不同页面的快捷方式互不抢占 */
class TabMatcherTest {

    private fun tab(id: Long, url: String, profile: String = ProfileManager.DEFAULT_PROFILE) =
        TabManager.Tab(id = id, title = "t$id", url = url, profile = profile)

    // ---------- urlHost ----------

    @Test
    fun urlHost_basicHttpAndHttps() {
        assertEquals("a.com", TabMatcher.urlHost("https://a.com/"))
        assertEquals("a.com", TabMatcher.urlHost("http://a.com/x/y?z=1#w"))
        assertEquals("a.com", TabMatcher.urlHost("HTTPS://A.COM/Page"))
    }

    @Test
    fun urlHost_stripsPortUserinfoAndKeepsSubdomain() {
        assertEquals("a.com", TabMatcher.urlHost("https://user:pass@a.com:8080/p"))
        assertEquals("m.a.com", TabMatcher.urlHost("https://m.a.com/"))
        assertEquals("a.com", TabMatcher.urlHost("https://a.com:443"))
    }

    @Test
    fun urlHost_rejectsNonHttpAndBlank() {
        assertNull(TabMatcher.urlHost(null))
        assertNull(TabMatcher.urlHost(""))
        assertNull(TabMatcher.urlHost("   "))
        assertNull(TabMatcher.urlHost("file:///sdcard/a.html"))
        assertNull(TabMatcher.urlHost("about:blank"))
        assertNull(TabMatcher.urlHost("a.com/no-scheme"))
        assertNull(TabMatcher.urlHost("https://"))
    }

    // ---------- pageKey ----------

    @Test
    fun pageKey_normalizesTrailingSlashDefaultPortAndCase() {
        assertEquals("https://a.com/", TabMatcher.pageKey("https://a.com"))
        assertEquals("https://a.com/", TabMatcher.pageKey("https://A.COM:443/"))
        assertEquals("https://a.com/Page?x=1", TabMatcher.pageKey("HTTPS://A.COM/Page/?x=1#frag"))
        assertEquals("http://a.com/", TabMatcher.pageKey("http://a.com:80/"))
        assertEquals("https://a.com:8443/p", TabMatcher.pageKey("https://a.com:8443/p/"))
    }

    @Test
    fun pageKey_keepsQueryButDropsFragment() {
        assertEquals("https://a.com/p?a=1&b=2", TabMatcher.pageKey("https://a.com/p/?a=1&b=2#top"))
        // 同一路径不同查询是不同的页面（快捷方式不能把筛选条件悄悄换掉）
        assertTrue(TabMatcher.pageKey("https://a.com/p?a=1") != TabMatcher.pageKey("https://a.com/p?a=2"))
    }

    @Test
    fun pageKey_rejectsNonHttpAndBlank() {
        assertNull(TabMatcher.pageKey(null))
        assertNull(TabMatcher.pageKey(""))
        assertNull(TabMatcher.pageKey("about:blank"))
        assertNull(TabMatcher.pageKey("file:///sdcard/a.html"))
        assertNull(TabMatcher.pageKey("https://"))
        assertNull(TabMatcher.pageKey("https://[::1]:8080/"))
    }

    // ---------- findReusableTabId ----------

    @Test
    fun match_reportedBug_twoShortcutsSameHostDifferentPath() {
        // 用户场景：根页与 /my-batch-calendar 各开一个标签，两个快捷方式各回各的页面
        val tabs = listOf(
            tab(1L, "https://yanjiubu.orientlaw.cn/"),
            tab(2L, "https://yanjiubu.orientlaw.cn/my-batch-calendar")
        )
        // 根页标签更近活跃也不能抢走日历快捷方式
        assertEquals(
            2L,
            TabMatcher.findReusableTabId(
                tabs, "https://yanjiubu.orientlaw.cn/my-batch-calendar",
                ProfileManager.DEFAULT_PROFILE, mapOf(1L to 999L, 2L to 1L)
            )
        )
        assertEquals(
            1L,
            TabMatcher.findReusableTabId(
                tabs, "https://yanjiubu.orientlaw.cn/",
                ProfileManager.DEFAULT_PROFILE, mapOf(1L to 1L, 2L to 999L)
            )
        )
    }

    @Test
    fun match_sameHostDifferentPathWithoutTargetTab_returnsNull() {
        // 只有根页标签时点日历快捷方式：必须新开标签（旧版按域名命中根页 -> 不跳转，即本次缺陷）
        val tabs = listOf(tab(1L, "https://yanjiubu.orientlaw.cn/"))
        assertNull(
            TabMatcher.findReusableTabId(
                tabs, "https://yanjiubu.orientlaw.cn/my-batch-calendar",
                ProfileManager.DEFAULT_PROFILE, mapOf(1L to 999L)
            )
        )
    }

    @Test
    fun match_rootShortcutWithoutRootTab_returnsNull() {
        // 站点根快捷方式也按页面精确匹配：站内翻到别的页面后不再被当成"已打开"
        val tabs = listOf(tab(1L, "https://a.com/forum/topic/9"))
        assertNull(
            TabMatcher.findReusableTabId(tabs, "https://a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
    }

    @Test
    fun match_samePage_normalizedUrlReuses() {
        val tabs = listOf(tab(1L, "https://a.com/forum/topic/9/?page=2#reply"))
        assertEquals(
            1L,
            TabMatcher.findReusableTabId(
                tabs, "https://a.com/forum/topic/9?page=2",
                ProfileManager.DEFAULT_PROFILE, emptyMap()
            )
        )
    }

    @Test
    fun match_samePageDifferentQuery_doesNotReuse() {
        val tabs = listOf(tab(1L, "https://a.com/p?a=1"))
        assertNull(
            TabMatcher.findReusableTabId(tabs, "https://a.com/p?a=2", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
    }

    @Test
    fun match_schemeOrSubdomainDiffers_doesNotReuse() {
        val tabs = listOf(tab(1L, "https://m.a.com/p"))
        assertNull(
            TabMatcher.findReusableTabId(tabs, "https://www.a.com/p", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
        assertNull(
            TabMatcher.findReusableTabId(tabs, "http://m.a.com/p", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
        // 两个子域各开一页时，只命中对应子域的那一个
        val both = listOf(tab(1L, "https://m.a.com/p"), tab(2L, "https://www.a.com/p"))
        assertEquals(
            2L,
            TabMatcher.findReusableTabId(both, "https://www.a.com/p", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
        assertEquals(
            1L,
            TabMatcher.findReusableTabId(both, "https://m.a.com/p", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
    }

    @Test
    fun match_profileDiffers_doesNotReuse() {
        val tabs = listOf(tab(1L, "https://a.com/p", profile = ProfileManager.profileNameOf(2)))
        assertNull(
            TabMatcher.findReusableTabId(tabs, "https://a.com/p", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
    }

    @Test
    fun match_noCandidate_returnsNull() {
        assertNull(
            TabMatcher.findReusableTabId(emptyList(), "https://a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
        assertNull(
            TabMatcher.findReusableTabId(
                listOf(tab(1L, "https://b.com/")), "https://a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap()
            )
        )
        // 快捷方式 URL 无法提取页面键（外链/空串）时不复用
        assertNull(
            TabMatcher.findReusableTabId(
                listOf(tab(1L, "https://a.com/")), "about:blank", ProfileManager.DEFAULT_PROFILE, emptyMap()
            )
        )
    }

    @Test
    fun match_multipleHits_picksMostRecentlyActive() {
        // 同一页面开了多个标签（重复打开）取最近活跃
        val tabs = listOf(tab(1L, "https://a.com/p"), tab(2L, "https://a.com/p/"))
        val lastActive = mapOf(1L to 100L, 2L to 500L)
        assertEquals(
            2L,
            TabMatcher.findReusableTabId(tabs, "https://a.com/p", ProfileManager.DEFAULT_PROFILE, lastActive)
        )
        val lastActiveFlipped = mapOf(1L to 900L, 2L to 500L)
        assertEquals(
            1L,
            TabMatcher.findReusableTabId(tabs, "https://a.com/p", ProfileManager.DEFAULT_PROFILE, lastActiveFlipped)
        )
        // 无时间戳记录的按 0 处理；全平分时按列表顺序取先建的（确定性兜底，实际运行所有标签都有时间戳）
        assertEquals(
            1L,
            TabMatcher.findReusableTabId(tabs, "https://a.com/p", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
    }

    @Test
    fun match_blankTabUrl_ignored() {
        val tabs = listOf(tab(1L, ""))
        assertNull(
            TabMatcher.findReusableTabId(tabs, "https://a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
    }
}