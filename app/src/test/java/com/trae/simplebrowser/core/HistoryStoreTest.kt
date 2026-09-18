package com.trae.simplebrowser.core

import com.trae.simplebrowser.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStoreTest {

    private fun entry(url: String, title: String = url, ts: Long = 0L) =
        HistoryEntry(title, url, ts)

    @Test
    fun add_dedupesByUrlAndMovesToTop() {
        var list = listOf(entry("a.com", ts = 1), entry("b.com", ts = 2))
        list = HistoryStore.add(list, "A again", "a.com", ts = 3)
        assertEquals(2, list.size)
        assertEquals("a.com", list[0].url)
        assertEquals(3L, list[0].ts)
    }

    @Test
    fun add_blankUrlIgnored() {
        val list = listOf(entry("a.com"))
        assertEquals(list, HistoryStore.add(list, "t", "  ", ts = 0L))
    }

    @Test
    fun add_capsAtLimit() {
        var list = emptyList<HistoryEntry>()
        for (i in 1..(HistoryStore.MAX_ENTRIES + 30)) {
            list = HistoryStore.add(list, "t$i", "site$i.com", i.toLong())
        }
        assertEquals(HistoryStore.MAX_ENTRIES, list.size)
        assertEquals("site${HistoryStore.MAX_ENTRIES + 30}.com", list[0].url)
    }

    @Test
    fun remove_removesOnlyExactUrl() {
        var list = listOf(entry("a.com"), entry("b.com"))
        list = HistoryStore.remove(list, "a.com")
        assertEquals(1, list.size)
        assertEquals("b.com", list[0].url)
    }

    @Test
    fun filterMatch_matchesUrlOrTitleCaseInsensitive() {
        val list = listOf(
            entry("https://www.example.com/page", "Example Page"),
            entry("https://baidu.com", "百度")
        )
        assertEquals(1, HistoryStore.filterMatch(list, "EXAMPLE", 10).size)
        assertEquals(1, HistoryStore.filterMatch(list, "百度", 10).size)
        assertTrue(HistoryStore.filterMatch(list, "", 10).isEmpty())
        assertTrue(HistoryStore.filterMatch(list, "nomatch", 10).isEmpty())
    }
}

class SuggestEngineTest {

    private fun history(url: String, title: String = url) = HistoryEntry(title, url, 0L)

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(SuggestEngine.suggest(listOf(history("a.com")), listOf(), "  ").isEmpty())
    }

    @Test
    fun bookmarksRankAboveHistoryWithSameScore() {
        val result = SuggestEngine.suggest(
            history = listOf(history("example.com", "History")),
            bookmarks = listOf(Bookmark("Bookmark", "example.com")),
            query = "example"
        )
        assertEquals(1, result.size)
        assertTrue(result[0].fromBookmark)
    }

    @Test
    fun prefixBeatsContains() {
        val result = SuggestEngine.suggest(
            history = listOf(
                history("x.com/bing", "contains bing"),
                history("bing.com", "Bing")
            ),
            bookmarks = listOf(),
            query = "bing"
        )
        assertEquals("bing.com", result[0].url)
    }

    @Test
    fun dedupesByUrlKeepsBookmark() {
        val result = SuggestEngine.suggest(
            history = listOf(history("example.com", "FromHistory")),
            bookmarks = listOf(Bookmark("FromBookmark", "example.com")),
            query = "example"
        )
        assertEquals(1, result.size)
        assertEquals("FromBookmark", result[0].title)
    }

    @Test
    fun respectsLimit() {
        val list = (1..20).map { history("site$it.com") }
        assertEquals(8, SuggestEngine.suggest(list, listOf(), "site").size)
        assertFalse(SuggestEngine.suggest(list, listOf(), "nomatch").isNotEmpty())
    }
}
