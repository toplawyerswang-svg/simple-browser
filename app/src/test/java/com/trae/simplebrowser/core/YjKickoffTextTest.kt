package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 开场文字 + 报告交付约定的拼装测试（2026-11-25） */
class YjKickoffTextTest {

    // ---------- 姓名拆分 ----------

    @Test
    fun owners_splitOnEverySeparator() {
        assertEquals(listOf("王伟江", "梁诗婉"), YjKickoffText.parseOwnerNames("王伟江、梁诗婉"))
        assertEquals(listOf("王伟江", "梁诗婉"), YjKickoffText.parseOwnerNames("王伟江,梁诗婉"))
        assertEquals(listOf("王伟江", "梁诗婉"), YjKickoffText.parseOwnerNames(" 王伟江 ；梁诗婉 "))
        assertEquals(listOf("王伟江", "梁诗婉", "张三"), YjKickoffText.parseOwnerNames("王伟江\n梁诗婉/张三"))
    }

    @Test
    fun owners_blankAndDuplicatesDropped() {
        assertTrue(YjKickoffText.parseOwnerNames(null).isEmpty())
        assertTrue(YjKickoffText.parseOwnerNames("   ").isEmpty())
        assertTrue(YjKickoffText.parseOwnerNames("、、,  ").isEmpty())
        assertEquals(listOf("王伟江"), YjKickoffText.parseOwnerNames("王伟江、王伟江"))
    }

    @Test
    fun owners_mergeFixedPicksFirstThenExtra() {
        assertEquals(
            listOf("王伟江", "梁诗婉"),
            YjKickoffText.mergeOwners(true, true, "")
        )
        assertEquals(listOf("梁诗婉"), YjKickoffText.mergeOwners(false, true, null))
        assertEquals(
            listOf("王伟江", "张三", "李四"),
            YjKickoffText.mergeOwners(true, false, "张三、李四")
        )
        // 补充框写了默认名字也不重复
        assertEquals(
            listOf("王伟江", "梁诗婉"),
            YjKickoffText.mergeOwners(true, true, "王伟江、梁诗婉")
        )
        assertTrue(YjKickoffText.mergeOwners(false, false, "").isEmpty())
    }

    // ---------- 约定段 ----------

    @Test
    fun requirement_default_isUploadEvidenceBackedWithTwoOwners() {
        val text = YjKickoffText.composeRequirement(YjKickoffText.ReportRequirement())
        assertTrue(text.startsWith("【报告交付约定】"))
        assertTrue(text.contains("报告上传到研究部：是"))
        assertTrue(text.contains("附信源报告"))
        assertTrue(text.contains("report_type"))
        assertTrue(text.contains("王伟江、梁诗婉"))
        assertTrue(text.contains("report_owner_user_ids"))
        assertTrue(text.contains("agents-api/users"))
        assertFalse(text.contains("普通报告"))
    }

    @Test
    fun requirement_noUpload_dropsFormAndOwners() {
        val text = YjKickoffText.composeRequirement(
            YjKickoffText.ReportRequirement(upload = false, evidenceBacked = true, owners = listOf("王伟江"))
        )
        assertTrue(text.contains("无需上传到研究部"))
        assertFalse(text.contains("report_type"))
        assertFalse(text.contains("report_owner_user_ids"))
        assertFalse(text.contains("王伟江"))
    }

    @Test
    fun requirement_plainReport_saysNoIntensityCheck() {
        val text = YjKickoffText.composeRequirement(
            YjKickoffText.ReportRequirement(upload = true, evidenceBacked = false, owners = listOf("梁诗婉"))
        )
        assertTrue(text.contains("普通报告"))
        assertTrue(text.contains("不声明 report_type"))
        assertFalse(text.contains("附信源报告（"))
        assertTrue(text.contains("梁诗婉"))
    }

    @Test
    fun requirement_noOwners_warnsInvisible() {
        val text = YjKickoffText.composeRequirement(
            YjKickoffText.ReportRequirement(upload = true, evidenceBacked = true, owners = emptyList())
        )
        assertTrue(text.contains("未指定"))
        assertTrue(text.contains("对普通用户不可见"))
        assertFalse(text.contains("report_owner_user_ids"))
    }

    // ---------- 整体拼装 ----------

    @Test
    fun compose_joinsUserTextAndRequirement() {
        val composed = YjKickoffText.compose("  先梳理这个夹里的合同  ", YjKickoffText.ReportRequirement())
        assertEquals(
            "先梳理这个夹里的合同\n\n【报告交付约定】",
            composed!!.substring(0, composed.indexOf("\n- "))
        )
        assertTrue(composed.contains("报告上传到研究部：是"))
    }

    @Test
    fun compose_emptyUserText_sendsRequirementOnly() {
        val composed = YjKickoffText.compose("", YjKickoffText.ReportRequirement())
        assertTrue(composed!!.startsWith("【报告交付约定】"))
        assertFalse(composed.startsWith("\n"))
    }

    @Test
    fun compose_tooLong_returnsNullInsteadOfTruncating() {
        val huge = "字".repeat(YjKickoffText.MAX_CHARS)
        assertNull(YjKickoffText.compose(huge, YjKickoffText.ReportRequirement()))
        // 上限内放行（自由文字 + 约定段合计）
        val fits = "字".repeat(YjKickoffText.MAX_CHARS - 400)
        assertTrue(YjKickoffText.compose(fits, YjKickoffText.ReportRequirement()) != null)
    }

    @Test
    fun compose_requirementAloneFitsEvenAtMaxOwners() {
        val req = YjKickoffText.ReportRequirement(
            owners = YjKickoffText.mergeOwners(true, true, "张三、李四、王五、赵六")
        )
        val composed = YjKickoffText.compose("", req)
        assertTrue(composed != null && composed.length < YjKickoffText.MAX_CHARS)
        assertTrue(composed!!.contains("张三、李四、王五、赵六"))
    }
}