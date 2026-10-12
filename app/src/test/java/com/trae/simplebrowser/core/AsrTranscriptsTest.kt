package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** 转写纯文本渲染基线测试：详情页「复制全文」与列表批量「复制/分享」共用的格式。 */
class AsrTranscriptsTest {

    private fun seg(speaker: Int, begin: String, end: String, text: String) =
        AsrTranscripts.Seg(speaker, begin, end, text)

    private fun named(name: String?, gender: String = "") = AsrTranscripts.SpeakerMeta(name, gender)

    @Test
    fun full_diarizeAndTimestamps_namedAndFallback() {
        val text = AsrTranscripts.render(
            segments = listOf(
                seg(0, "00:00", "00:13", "喂，我的事。"),
                seg(1, "00:15", "00:19", "不排除这个可能。"),
                seg(0, "00:21", "00:34", "目前看是这样。")
            ),
            speakerNames = mapOf(1 to named("王伟江", "男")),
            hasTimestamps = true,
            hasDiarize = true,
            fallbackSpeaker = { n -> "说话人$n" }
        )
        // 具名带性别；未具名按首次出现顺序编号，同号复用
        assertEquals(
            "[说话人1][00:00-00:13]喂，我的事。\n" +
                "[王伟江（男）][00:15-00:19]不排除这个可能。\n" +
                "[说话人1][00:21-00:34]目前看是这样。",
            text
        )
    }

    @Test
    fun noDiarize_noSpeakerPrefix() {
        val text = AsrTranscripts.render(
            segments = listOf(seg(0, "00:00", "00:05", "只有一句话。")),
            speakerNames = mapOf(0 to named("张三")),
            hasTimestamps = true,
            hasDiarize = false,
            fallbackSpeaker = { n -> "说话人$n" }
        )
        assertEquals("[00:00-00:05]只有一句话。", text)
    }

    @Test
    fun noTimestamps_noTimePrefix() {
        val text = AsrTranscripts.render(
            segments = listOf(seg(0, "", "", "没有时间戳。")),
            speakerNames = emptyMap(),
            hasTimestamps = false,
            hasDiarize = false,
            fallbackSpeaker = { n -> "说话人$n" }
        )
        assertEquals("没有时间戳。", text)
    }

    @Test
    fun emptyName_treatedAsUnnamed_fallsBackToNumbering() {
        val text = AsrTranscripts.render(
            segments = listOf(seg(2, "00:01", "00:02", "甲。"), seg(3, "00:03", "00:04", "乙。")),
            speakerNames = mapOf(2 to named("", "男")),
            hasTimestamps = false,
            hasDiarize = true,
            fallbackSpeaker = { n -> "说话人$n" }
        )
        // 空名字不算具名；性别照常缀在编号后面；非 0 起点的 speaker id 也按出现顺序编号
        assertEquals("[说话人1（男）]甲。\n[说话人2]乙。", text)
    }

    @Test
    fun emptySegments_returnsEmptyString() {
        val text = AsrTranscripts.render(
            segments = emptyList(),
            speakerNames = emptyMap(),
            hasTimestamps = true,
            hasDiarize = true,
            fallbackSpeaker = { n -> "说话人$n" }
        )
        assertEquals("", text)
    }

    @Test
    fun trailingWhitespace_trimmed() {
        val text = AsrTranscripts.render(
            segments = listOf(seg(0, "", "", "结尾段落。")),
            speakerNames = emptyMap(),
            hasTimestamps = false,
            hasDiarize = false,
            fallbackSpeaker = { n -> "说话人$n" }
        )
        assertEquals("结尾段落。", text)
    }

    // ---------- joinDocuments 批量拼篇 ----------

    @Test
    fun joinDocuments_namedSections_blankLineBetween() {
        val text = AsrTranscripts.joinDocuments(
            listOf("通话-张三" to "你好。\n好的。", "通话-李四" to "收到。")
        )
        assertEquals("【通话-张三】\n你好。\n好的。\n\n【通话-李四】\n收到。", text)
    }

    @Test
    fun joinDocuments_emptyList_returnsEmptyString() {
        assertEquals("", AsrTranscripts.joinDocuments(emptyList()))
    }
}
