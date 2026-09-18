package com.trae.simplebrowser

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.trae.simplebrowser.core.DownloadKind
import com.trae.simplebrowser.core.DownloadRecord
import com.trae.simplebrowser.core.DownloadStatus
import com.trae.simplebrowser.core.DownloadStoreLogic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 下载模块仪器测试：store 真机落盘回环 + 前台服务失败路径自停。
 */
@RunWith(AndroidJUnit4::class)
class VideoDownloadServiceTest {

    @Test
    fun downloadStoreFile_persistsRoundtripOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DownloadStoreFile(context)
        val rec = DownloadRecord(
            id = "it_store_1",
            kind = DownloadKind.HLS,
            title = "真机落盘验证",
            fileName = "it_store_1.ts",
            path = "Download/轻舟浏览器/it_store_1.ts",
            uri = "content://media/downloads/1",
            status = DownloadStatus.RUNNING,
            progress = 33,
            detail = "5/15",
            createdAt = System.currentTimeMillis(),
            finishedAt = 0L
        )
        store.upsert(rec)
        store.updateById("it_store_1") { it.copy(status = DownloadStatus.DONE, progress = 100) }

        val reloaded = DownloadStoreFile(context).load()
        val saved = reloaded.firstOrNull { it.id == "it_store_1" }
        assertTrue("record should persist to files/downloads.txt", saved != null)
        assertTrue(saved!!.status == DownloadStatus.DONE && saved.progress == 100)

        // 清理
        store.update { DownloadStoreLogic.removeById(it, "it_store_1") }
        assertTrue(DownloadStoreFile(context).load().none { it.id == "it_store_1" })
    }

    @Test
    fun unreachablePlaylistFailsGracefullyAndServiceStops() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        ActivityScenario.launch(MainActivity::class.java).use {
            it.onActivity { activity ->
                ContextCompat.startForegroundService(
                    activity,
                    Intent(activity, VideoDownloadService::class.java).apply {
                        putExtra(VideoDownloadService.EXTRA_URL, "https://127.0.0.1:9/x.m3u8")
                        putExtra(VideoDownloadService.EXTRA_TITLE, "冒烟测试")
                    }
                )
            }

            // 服务应走完 3 次重试 → 失败通知 → 自停，最长 60 秒
            val deadline = System.currentTimeMillis() + 60_000
            while (isServiceRunning(context) && System.currentTimeMillis() < deadline) {
                Thread.sleep(1000)
            }
            assertFalse("service should self-stop after failed download", isServiceRunning(context))
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return am.getRunningServices(50).any { it.service.className == VideoDownloadService::class.java.name }
    }
}
