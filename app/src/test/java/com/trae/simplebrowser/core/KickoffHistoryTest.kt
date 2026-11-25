package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开场文案历史版本逻辑测试（2026-11-25）：空关键词=全量、去重、裁剪、搜索、序列化往返 */
class KickoffHistoryTest {

    private fun v(id: String, text: String, savedAt: Long, usedAt: Long = 0L) =
        KickoffVersion(id = id, text = text, savedAt = savedAt, usedAt = usedAt)

    // ---------- 保存/去重/裁剪 ----------

    @Test
    fun upsert_ignoresBlank() {
        val list = listOf(v("a", "旧文案", 1L))
        assertEquals(list, KickoffHistory.upsert(list, "   ", 2L))
        assertEquals(list, KickoffHistory.upsert(list, "", 3L))
    }

    @Test
    fun upsert_sameTextDoesNotDuplicate_butBumpsToFront() {
        val list = listOf(v("a", "第一段", 100L), v("b", "第二段", 90L))
        val next = KickoffHistory.upsert(list, "  第一段  ", 200L, upload = false, owners = listOf("张三"))
        assertEquals(2, next.size)
        assertEquals("第一段", next[0].text)
        assertEquals(200L, next[0].savedAt)
        assertEquals(false, next[0].upload)
        assertEquals(listOf("张三"), next[0].owners)
    }

    @Test
    fun upsert_capsAtMaxVersions_droppingOldestUsed() {
        var list = emptyList<KickoffVersion>()
        for (i in 1..KickoffHistory.MAX_VERSIONS + 5) {
            list = KickoffHistory.upsert(list, "第 $i 版", i.toLong())
        }
        assertEquals(KickoffHistory.MAX_VERSIONS, list.size)
        assertEquals("第 ${KickoffHistory.MAX_VERSIONS + 5} 版", list[0].text)
        assertFalse(list.any { it.text == "第 1 版" })
    }

    @Test
    fun order_prefersUsedAtOverSavedAt() {
        val list = listOf(
            v("old", "老文案", 100L),
            v("used", "常用文案", 50L, usedAt = 500L)
        )
        assertEquals("used", KickoffHistory.ordered(list)[0].id)
    }

    // ---------- 关键词搜索（用户强调：没输关键词要全量） ----------

    @Test
    fun filter_emptyQueryReturnsEverything() {
        val list = listOf(v("a", "合同梳理", 10L), v("b", "退赔款分配", 20L), v("c", "证据质证", 30L))
        assertEquals(3, KickoffHistory.filter(list, "").size)
        assertEquals(3, KickoffHistory.filter(list, "   ").size)
        // 全量也按最近使用/保存倒序
        assertEquals(listOf("c", "b", "a"), KickoffHistory.filter(list, "").map { it.id })
    }

    @Test
    fun filter_keywordMatchesBodyCaseInsensitive() {
        val list = listOf(
            v("a", "把三份 合同 逐份梳理", 10L),
            v("b", "Contract 风险提示", 20L),
            v("c", "退赔款分配", 30L)
        )
        assertEquals(listOf("a"), KickoffHistory.filter(list, "合同").map { it.id })
        assertEquals(listOf("b"), KickoffHistory.filter(list, "contract").map { it.id })
        assertEquals(listOf("b"), KickoffHistory.filter(list, "CONTRACT").map { it.id })
    }

    @Test
    fun filter_multiTermsAreAnd() {
        val list = listOf(
            v("a", "三份合同逐份梳理，付款节点与违约责任", 10L),
            v("b", "合同条款风险提示", 20L)
        )
        assertEquals(listOf("a"), KickoffHistory.filter(list, "合同 付款").map { it.id })
        assertEquals(listOf("a", "b"), KickoffHistory.filter(list, "合同").map { it.id }.sorted())
        assertTrue(KickoffHistory.filter(list, "合同 退赔").isEmpty())
    }

    @Test
    fun markUsed_movesToFront_andRemoveWorks() {
        val list = listOf(v("a", "甲", 100L), v("b", "乙", 200L))
        val used = KickoffHistory.markUsed(list, "a", 300L)
        assertEquals("a", used[0].id)
        assertEquals(300L, used[0].usedAt)
        assertEquals(listOf("b"), KickoffHistory.remove(used, "a").map { it.id })
    }

    // ---------- 摘要 ----------

    @Test
    fun summary_flattensWhitespaceAndTruncates() {
        assertEquals("第一行 第二行", KickoffHistory.summary("第一行\n\n  第二行  "))
        val long = "字".repeat(KickoffHistory.SUMMARY_CHARS + 20)
        val s = KickoffHistory.summary(long)
        assertTrue(s.endsWith("…"))
        assertEquals(KickoffHistory.SUMMARY_CHARS + 1, s.length)
    }

    // ---------- 序列化往返（含换行/制表符/反斜杠/中文） ----------

    @Test
    fun serializeParse_roundTripKeepsEverything() {
        val list = listOf(
            KickoffVersion(
                id = "100-abc", text = "第一行\n第二行\t带制表符\\反斜杠 end",
                savedAt = 100L, usedAt = 200L,
                upload = true, evidenceBacked = false, owners = listOf("王伟江", "梁诗婉")
            ),
            KickoffVersion(id = "101-def", text = "普通文案", savedAt = 90L, usedAt = 0L,
                upload = false, evidenceBacked = true, owners = emptyList())
        )
        val back = KickoffHistory.parse(KickoffHistory.serialize(list))
        assertEquals(2, back.size)
        assertEquals(list[0].text, back[0].text)
        assertEquals(list[0].id, back[0].id)
        assertEquals(false, back[0].evidenceBacked)
        assertEquals(listOf("王伟江", "梁诗婉"), back[0].owners)
        assertEquals(false, back[1].upload)
        assertTrue(back[1].owners.isEmpty())
    }

    @Test
    fun parse_skipsBrokenLines() {
        val raw = buildString {
            append("1-a\t100\t0\t1\t1\t\t正常文案\n")
            append("坏行没有制表符\n")
            append("2-b\tnot-a-number\t0\t1\t1\t\t坏时间\n")
            append("3-c\t50\t0\t1\t1\t\t\n")   // 空文本
            append("4-d\t60\t0\t1\t1\t王伟江\t第二条正常")
        }
        val list = KickoffHistory.parse(raw)
        assertEquals(2, list.size)
        // parse 后按最近保存倒序（1-a savedAt=100 > 4-d savedAt=60）
        assertEquals(listOf("1-a", "4-d"), list.map { it.id })
    }

    @Test
    fun parse_emptyOrGarbageReturnsEmpty() {
        assertTrue(KickoffHistory.parse("").isEmpty())
        assertTrue(KickoffHistory.parse("   ").isEmpty())
        assertTrue(KickoffHistory.parse("随便一段没有格式的文本").isEmpty())
    }
}