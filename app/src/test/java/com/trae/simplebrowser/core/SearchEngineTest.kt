package com.trae.simplebrowser.core

import com.trae.simplebrowser.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    @Test
    fun buildQueryUrl_encodesChinese() {
        val url = SearchEngines.byId("baidu").buildQueryUrl("轻舟 浏览器")
        assertTrue(url.startsWith("https://www.baidu.com/s?wd="))
        assertTrue(url.contains("+") || url.contains("%"))
    }

    @Test
    fun byId_allEnginesResolvable() {
        for (engine in SearchEngines.ALL) {
            assertEquals(engine, SearchEngines.byId(engine.id))
        }
    }

    @Test
    fun byId_unknownFallsBackToDefault() {
        assertEquals(SearchEngines.DEFAULT_ID, SearchEngines.byId("not-exist").id)
        assertEquals(SearchEngines.DEFAULT_ID, SearchEngines.byId(null).id)
    }

    @Test
    fun templatesContainNoQueryYet() {
        for (engine in SearchEngines.ALL) {
            assertTrue(engine.template.endsWith("="))
        }
    }
}
