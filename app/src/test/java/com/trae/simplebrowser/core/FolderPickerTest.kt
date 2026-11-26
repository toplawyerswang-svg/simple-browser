package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选夹弹窗关键词过滤（2026-11-26）：空关键词=全量、子串命中、大小写、多关键词 AND、全角空格 */
class FolderPickerTest {

    private val names = listOf(
        "合促会供应商入库",
        "test",
        "暮客纪政豪",
        "创新le源自动化诉讼案",
        "苏州公司11000元处罚",
        "创新华之颜调解",
        "王培华",
        "纪政豪担保公司"
    )

    private fun pick(query: String) = FolderPicker.filter(names, query) { it }

    // ---------- 空关键词 ----------

    @Test
    fun blankQuery_returnsAllInOrder() {
        assertEquals(names, pick(""))
        assertEquals(names, pick("   "))
        assertEquals(names, pick("\u3000\t\n"))
    }

    // ---------- 单关键词 ----------

    @Test
    fun singleKeyword_matchesSubstring() {
        assertEquals(listOf("苏州公司11000元处罚"), pick("苏州"))
        assertEquals(listOf("王培华"), pick("培华"))
    }

    @Test
    fun singleKeyword_ignoresCase() {
        assertEquals(listOf("test"), pick("TEST"))
        assertEquals(listOf("创新le源自动化诉讼案"), pick("LE"))
        assertEquals(listOf("test"), pick("tEs"))
    }

    @Test
    fun singleKeyword_noMatch_returnsEmpty() {
        assertTrue(pick("不存在的夹").isEmpty())
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(listOf("王培华"), pick("  王培华  "))
    }

    // ---------- 多关键词 AND ----------

    @Test
    fun multipleKeywords_allMustMatch() {
        // 两个词分别落在名字的不同位置
        assertEquals(listOf("苏州公司11000元处罚"), pick("苏州 处罚"))
        // 只命中一个词的不算：暮客纪政豪 没有「公司」
        assertEquals(listOf("纪政豪担保公司"), pick("纪政豪 公司"))
    }

    @Test
    fun multipleKeywords_oneMisses_returnsEmpty() {
        assertTrue(pick("苏州 不存在").isEmpty())
    }

    @Test
    fun multipleKeywords_extraWhitespaceIgnored() {
        assertEquals(listOf("苏州公司11000元处罚"), pick("  苏州   处罚  "))
    }

    @Test
    fun fullWidthSpaceAlsoSeparatesTerms() {
        // 全角空格 U+3000（中文输入法下常见）
        assertEquals(listOf("苏州公司11000元处罚"), pick("苏州\u3000处罚"))
    }

    // ---------- 顺序与对象 ----------

    @Test
    fun preservesInputOrder() {
        assertEquals(listOf("创新le源自动化诉讼案", "创新华之颜调解"), pick("创新"))
    }

    @Test
    fun worksOnObjectsNotJustNames() {
        data class Folder(val id: String, val name: String)
        val folders = listOf(
            Folder("1", "苏州公司11000元处罚"),
            Folder("2", "王培华")
        )
        val hit = FolderPicker.filter(folders, "处罚") { it.name }
        assertEquals(listOf("1"), hit.map { it.id })
    }
}