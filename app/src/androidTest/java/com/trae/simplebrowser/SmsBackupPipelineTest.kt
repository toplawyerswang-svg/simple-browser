package com.trae.simplebrowser

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trae.simplebrowser.core.SmsBackupLogic
import com.trae.simplebrowser.core.SmsMsg
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模拟器端到端：入队 → 冲刷上传 → 本地 smsbox（adb reverse tcp:8730）落库。
 * 覆盖队列 SQLite 读写、HTTP 上传、服务端去重回包解析、sent 记录。
 * 仅在模拟器/测试环境跑，不进正式交付流程。
 */
@RunWith(AndroidJUnit4::class)
class SmsBackupPipelineTest {

    @Test
    fun enqueueFlushUploadEndToEnd() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = SmsBackupStore.get(context)
        val base = "http://127.0.0.1:8730"
        val token = "localtest-token-1234567890"

        AppPrefs.setSmsBackupEnabled(context, true)
        AppPrefs.setSmsToken(context, token)
        AppPrefs.setSmsBaseUrl(context, base)

        try {
            val before = store.pendingCount()
            val msg1 = SmsMsg("12368", "PIPELINE-TEST-A 仪器测试短信", 1759651200000L)
            val msg2 = SmsMsg("12368", "PIPELINE-TEST-B 仪器测试短信", 1759651300000L)
            store.enqueue(listOf(msg1, msg2))
            assertEquals(before + 2, store.pendingCount())

            val err = SmsBackupManager.flushPending(context, force = true)
            assertEquals(null, err)
            assertEquals(before, store.pendingCount())

            // sent 记录应已按 base 记账，回扫判重将跳过这两条
            val h1 = SmsBackupLogic.contentHash(msg1.sender, msg1.body, msg1.sentAt)
            assertEquals(true, store.sentHashes(base).contains(h1))
        } finally {
            AppPrefs.setSmsBaseUrl(context, "")
            AppPrefs.setSmsToken(context, "")
        }
    }
}
