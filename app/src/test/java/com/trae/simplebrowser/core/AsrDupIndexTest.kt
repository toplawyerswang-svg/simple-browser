package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 录音转写去重索引基线测试：键生成、TSV 往返、写入淘汰、脏行容错。 */
class AsrDupIndexTest {

    @Test
    fun keyOf_combinesMd5AndOptionsTag() {
        assertEquals("abc123|1101", AsrDupIndex.keyOf("abc123", "1101"))
        // 不同选项标签 = 不同键：同内容换选项要重转
        assertTrue(AsrDupIndex.keyOf("abc123", "1101") != AsrDupIndex.keyOf("abc123", "1111"))
    }

    @Test
    fun parseSerialize_roundtrip() {
        val map = LinkedHashMap<String, AsrDupIndex.Entry>()
        map["a|1101"] = AsrDupIndex.Entry("task-1", 1000L)
        map["b|1111"] = AsrDupIndex.Entry("task-2", 2000L)
        val parsed = AsrDupIndex.parse(AsrDupIndex.serialize(map))
        assertEquals("task-1", parsed["a|1101"]?.taskId)
        assertEquals(2000L, parsed["b|1111"]?.savedAt)
    }

    @Test
    fun parse_toleratesBlankAndBrokenLines() {
        val parsed = AsrDupIndex.parse("a|1101\ttask-1\t1000\n\n垃圾行\nb|x\t非数字\n")
        assertEquals(1, parsed.size)
        assertEquals("task-1", parsed["a|1101"]?.taskId)
    }

    @Test
    fun put_sameKeyOverwritten_andMovedToNewest() {
        var map = AsrDupIndex.put(LinkedHashMap(), "k1", "old", 100L)
        map = AsrDupIndex.put(map, "k2", "mid", 200L)
        map = AsrDupIndex.put(map, "k1", "new", 300L)
        assertEquals("new", map["k1"]?.taskId)
        // 重写后的 k1 排到最末：未超上限时不淘汰，淘汰顺序由 prune 测试覆盖
        assertEquals(listOf("k2", "k1"), map.keys.toList())
    }

    @Test
    fun put_prunesOldestBeyondLimit() {
        var map = LinkedHashMap<String, AsrDupIndex.Entry>()
        for (i in 1..AsrDupIndex.MAX_ENTRIES + 5) {
            map = AsrDupIndex.put(map, "key$i", "task$i", i.toLong())
        }
        assertEquals(AsrDupIndex.MAX_ENTRIES, map.size)
        assertNull(map["key1"])        // 最早写入被淘汰
        assertNull(map["key5"])        // 前 5 个都被淘汰
        assertEquals("task6", map["key6"]?.taskId)
        assertEquals("task${AsrDupIndex.MAX_ENTRIES + 5}", map["key${AsrDupIndex.MAX_ENTRIES + 5}"]?.taskId)
    }
}
