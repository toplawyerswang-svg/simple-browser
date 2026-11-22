package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 应用内更新决策测试：版本码对比、sha256 指纹比对、安全文件名 */
class AppUpdateTest {

    // ---------- isUpdateAvailable ----------

    @Test
    fun update_serverHigherThanCurrent() {
        assertTrue(AppUpdate.isUpdateAvailable(68L, 67L))
        assertTrue(AppUpdate.isUpdateAvailable(100L, 67L))
    }

    @Test
    fun update_equalOrLowerOrInvalid_noUpdate() {
        assertFalse(AppUpdate.isUpdateAvailable(67L, 67L)) // 同版本
        assertFalse(AppUpdate.isUpdateAvailable(66L, 67L)) // 服务端更旧
        assertFalse(AppUpdate.isUpdateAvailable(0L, 67L)) // 服务端无版本信息
        // 本机版本未知(0)：纯比较语义下视为可更新（装最新包比不装安全）；实际运行 PackageInfo 恒可得，不会出现 0
        assertTrue(AppUpdate.isUpdateAvailable(68L, 0L))
        assertFalse(AppUpdate.isUpdateAvailable(0L, 0L))
    }

    // ---------- shaMatches ----------

    @Test
    fun sha_matchesIgnoringCaseAndWhitespace() {
        val sha = "1A51BE3B278737B170F2CCEBD8B1976274BA8799138D707AA9E9EA9DEE9F73AB"
        assertTrue(AppUpdate.shaMatches(sha, sha.lowercase()))
        assertTrue(AppUpdate.shaMatches(" $sha ", sha.lowercase()))
    }

    @Test
    fun sha_mismatchOrMissing_neverMatches() {
        assertFalse(
            AppUpdate.shaMatches(
                "1a51be3b278737b170f2ccebd8b1976274ba8799138d707aa9e9ea9dee9f73ab",
                "ffffffff278737b170f2ccebd8b1976274ba8799138d707aa9e9ea9dee9f73ab"
            )
        )
        assertFalse(AppUpdate.shaMatches("", "abc"))
        assertFalse(AppUpdate.shaMatches("abc", ""))
        assertFalse(AppUpdate.shaMatches("   ", "   "))
    }

    // ---------- apkFileName ----------

    @Test
    fun fileName_keepsSafeCharsOnly() {
        assertEquals("lightboat-update-1.21.0.apk", AppUpdate.apkFileName("1.21.0"))
        assertEquals("lightboat-update-1.21.0_beta.apk", AppUpdate.apkFileName("1.21.0 beta"))
        // 路径分隔符替换为下划线：/..\ 四字符 → _.._（点号保留，不再构成分隔）
        assertEquals("lightboat-update-_.._.apk", AppUpdate.apkFileName("/..\\"))
        assertEquals("lightboat-update-unknown.apk", AppUpdate.apkFileName(""))
    }
}
