package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 快捷方式复用匹配测试：同身份+同域名（区分子域名）才复用，多命中取最近活跃 */
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

    // ---------- findReusableTabId ----------

    @Test
    fun match_sameHostDifferentPath_reuses() {
        val tabs = listOf(tab(1L, "https://a.com/"), tab(2L, "https://a.com/forum/topic/9"))
        // 站内点过几层页面（URL 已变）仍按主机名命中；多命中取最近活跃
        assertEquals(
            2L,
            TabMatcher.findReusableTabId(tabs, "https://a.com/", ProfileManager.DEFAULT_PROFILE, mapOf(2L to 999L))
        )
    }

    @Test
    fun match_subdomainDiffers_doesNotReuse() {
        val tabs = listOf(tab(1L, "https://m.a.com/x"))
        assertNull(
            TabMatcher.findReusableTabId(tabs, "https://www.a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
        // 两个子域都开着时，只命中对应子域的标签
        val both = listOf(tab(1L, "https://m.a.com/x"), tab(2L, "https://www.a.com/y"))
        assertEquals(
            2L,
            TabMatcher.findReusableTabId(both, "https://www.a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
        assertEquals(
            1L,
            TabMatcher.findReusableTabId(both, "https://m.a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap())
        )
    }

    @Test
    fun match_profileDiffers_doesNotReuse() {
        val tabs = listOf(tab(1L, "https://a.com/x", profile = ProfileManager.profileNameOf(2)))
        assertNull(
            TabMatcher.findReusableTabId(tabs, "https://a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap())
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
        // 快捷方式 URL 无法提取主机（外链/空串）时不复用
        assertNull(
            TabMatcher.findReusableTabId(
                listOf(tab(1L, "https://a.com/")), "about:blank", ProfileManager.DEFAULT_PROFILE, emptyMap()
            )
        )
    }

    @Test
    fun match_multipleHits_picksMostRecentlyActive() {
        val tabs = listOf(tab(1L, "https://a.com/old"), tab(2L, "https://a.com/new"))
        val lastActive = mapOf(1L to 100L, 2L to 500L)
        assertEquals(
            2L,
            TabMatcher.findReusableTabId(tabs, "https://a.com/", ProfileManager.DEFAULT_PROFILE, lastActive)
        )
        val lastActiveFlipped = mapOf(1L to 900L, 2L to 500L)
        assertEquals(
            1L,
            TabMatcher.findReusableTabId(tabs, "https://a.com/", ProfileManager.DEFAULT_PROFILE, lastActiveFlipped)
        )
        // 无时间戳记录的按 0 处理；全平分时按列表顺序取先建的（确定性兜底，实际运行所有标签都有时间戳）
        assertEquals(
            1L,
            TabMatcher.findReusableTabId(tabs, "https://a.com/", ProfileManager.DEFAULT_PROFILE, emptyMap())
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
