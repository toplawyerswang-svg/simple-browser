package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多标签页状态管理基线测试 */
class TabManagerTest {

    @Test
    fun create_appendsAndBecomesCurrent() {
        val m = TabManager()
        val t1 = m.create(url = "https://a.com")
        val t2 = m.create(url = "https://b.com")
        assertEquals(2, m.size)
        assertEquals(t2.id, m.currentId)
        assertEquals("https://b.com", m.current?.url)
        assertTrue(t1.id < t2.id)
    }

    @Test
    fun create_respectsLimit() {
        val m = TabManager(maxTabs = 3)
        repeat(3) { m.create() }
        assertFalse(m.canCreate())
        assertThrows(IllegalStateException::class.java) { m.create() }
    }

    @Test
    fun closeCurrent_prefersRightNeighborThenLeft() {
        val m = TabManager()
        val a = m.create()
        val b = m.create()
        val c = m.create() // current=c
        // 关闭当前（队尾）→ 左邻 b
        assertEquals(b.id, m.close(c.id))
        // 关闭当前（队尾）→ 左邻 a
        assertEquals(a.id, m.close(b.id))
        assertEquals(1, m.size)
        // 关最后一个：null
        assertNull(m.close(a.id))
        assertEquals(0, m.size)
    }

    @Test
    fun closeCurrent_rightNeighborWinsWhenPresent() {
        val m = TabManager()
        m.create()
        val b = m.create()
        m.create() // a b c
        m.select(b.id) // 关中间：右邻 c 成为当前
        val cId = m.tabs[2].id
        assertEquals(cId, m.close(b.id))
    }

    @Test
    fun closeNonCurrent_keepsCurrent() {
        val m = TabManager()
        val a = m.create()
        val b = m.create()
        m.select(b.id)
        m.close(a.id)
        assertEquals(b.id, m.currentId)
        assertEquals(1, m.size)
    }

    @Test
    fun close_missingIdIsNoop() {
        val m = TabManager()
        val a = m.create()
        assertEquals(a.id, m.close(999L))
        assertEquals(1, m.size)
    }

    @Test
    fun select_unknownIdRejected() {
        val m = TabManager()
        val a = m.create()
        assertFalse(m.select(999L))
        assertEquals(a.id, m.currentId)
        assertTrue(m.select(a.id))
    }

    @Test
    fun closeOthers_keepsTargetAsCurrent() {
        val m = TabManager()
        m.create(); m.create()
        val keep = m.create()
        m.create()
        assertTrue(m.closeOthers(keep.id))
        assertEquals(listOf(keep.id), m.tabs.map { it.id })
        assertEquals(keep.id, m.currentId)
        assertFalse(m.closeOthers(999L))
    }

    @Test
    fun update_ignoresBlankValues() {
        val m = TabManager()
        val t = m.create(title = "原标题", url = "https://a.com")
        m.update(t.id, title = "  ", url = "")
        assertEquals("原标题", m.current?.title)
        m.update(t.id, title = "新标题", url = "https://b.com")
        assertEquals("新标题", m.current?.title)
        assertEquals("https://b.com", m.current?.url)
    }

    @Test
    fun indexOf_and_defaultMax() {
        val m = TabManager()
        val a = m.create()
        val b = m.create()
        assertEquals(0, m.indexOf(a.id))
        assertEquals(1, m.indexOf(b.id))
        assertEquals(-1, m.indexOf(123L))
        // v1.20.0 起：用户设想同时开最多 15 个网页，上限 8 → 15
        assertEquals(15, TabManager.DEFAULT_MAX_TABS)
    }
}
