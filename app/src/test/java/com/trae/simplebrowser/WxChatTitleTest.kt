package com.trae.simplebrowser

import org.junit.Assert.assertEquals
import org.junit.Test

/** WxChatShareFlow.applyNamesToTitle：真名填写保存后把昵称回填进默认记录名 */
class WxChatTitleTest {

    @Test
    fun replacesNicknamesWithRealNames() {
        assertEquals(
            "张三律师、李四 2026.1.5~3.8",
            WxChatShareFlow.applyNamesToTitle(
                "张三、李四 2026.1.5~3.8",
                mapOf("张三" to "张三律师")
            )
        )
    }

    @Test
    fun multipleNames_allReplaced() {
        assertEquals(
            "张三律师、李四律师 2026.1.5~3.8",
            WxChatShareFlow.applyNamesToTitle(
                "张三、李四 2026.1.5~3.8",
                mapOf("张三" to "张三律师", "李四" to "李四律师")
            )
        )
    }

    @Test
    fun longestNickReplacedFirst_avoidsSubstringClash() {
        // 昵称「王五」是「老王五」的子串：长昵称先替换，短昵称不能再误伤替换结果
        assertEquals(
            "老王五 Lawyers、王五律师",
            WxChatShareFlow.applyNamesToTitle(
                "老王五、王五",
                mapOf("老王五" to "老王五 Lawyers", "王五" to "王五律师")
            )
        )
    }

    @Test
    fun unmappedNick_staysAsIs() {
        assertEquals(
            "张三、李四 2026.1.5~3.8",
            WxChatShareFlow.applyNamesToTitle(
                "张三、李四 2026.1.5~3.8",
                mapOf("赵六" to "赵六律师")
            )
        )
    }

    @Test
    fun blankNickOrReal_noCrash() {
        // 空昵称跳过；空真名按原样替换成空（生产入口已过滤，这里只保底不崩）
        assertEquals("、", WxChatShareFlow.applyNamesToTitle("、张三", mapOf("" to "X", "张三" to "")))
    }

    @Test
    fun emptyMapping_returnsSame() {
        val t = "张三 2026.1.5~3.8"
        assertEquals(t, WxChatShareFlow.applyNamesToTitle(t, emptyMap()))
    }
}
