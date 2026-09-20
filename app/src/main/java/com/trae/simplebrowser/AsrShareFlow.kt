package com.trae.simplebrowser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/** 录音分享处理流程：多选处理项 → 前台服务上传 ECS asrsvc → 轮询 → 结果页。
 *  支持一次分享多个音视频文件（选项确认一次后逐个排队处理）；
 *  实际处理跑在 AsrProcessingService（dataSync 前台服务）里，切后台不被掐网中断；
 *  进度走工具栏上方悬浮条与系统通知，不弹居中对话框阻断浏览。
 *  页面不在前台时完成的结果先排队，MainActivity 回前台时 drainPending 统一展示。 */
object AsrShareFlow {
    private const val TAG = "LiteBrowser"
    private const val POLL_INTERVAL_MS = 5000L
    private const val POLL_MAX_MS = 2 * 60 * 60 * 1000L
    private const val NOTIF_CHANNEL_PROGRESS = "asr_progress"
    private const val NOTIF_CHANNEL_RESULT = "asr_result"
    private const val NOTIF_ID_PROGRESS = 2001
    private const val NOTIF_ID_RESULT = 2002
    private var lastNotifyAt = 0L
    private var lastSuccessName: String? = null

    /** 一个待处理的音视频分享项：uri 与其 MIME（用于确定上传扩展名）。 */
    data class MediaItem(val uri: Uri, val mime: String)

    private class Success(val resultFile: File, val displayName: String, val taskId: String,
                          val options: JSONObject?)

    /** 处理选项页所在的页面引用：悬浮条与直接展示结果用；页面销毁后由队列兜底。 */
    private var uiRef = WeakReference<MainActivity>(null)

    /** MainActivity 是否处于前台（onResume/onPause 维护）。 */
    @Volatile
    var activityResumed = false

    /** 页面不在前台期间完成的转写结果与失败项，回前台时统一展示。 */
    private val pendingResults = ArrayDeque<Success>()
    private val pendingFailures = ArrayDeque<String>()

    /** 已知音视频扩展名到 MIME 的映射：发送方不标类型时按显示名兜底识别。 */
    private val EXT_MIME = mapOf(
        "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "wav" to "audio/x-wav", "aac" to "audio/aac",
        "amr" to "audio/amr", "ogg" to "audio/ogg", "flac" to "audio/flac", "wma" to "audio/x-ms-wma",
        "opus" to "audio/opus", "3gp" to "audio/3gpp",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo", "webm" to "video/webm", "m4v" to "video/mp4"
    )

    /** 判定分享项是否音视频并给出 MIME：intent 类型 → 内容提供者类型 → 文件扩展名，逐级兜底。
     *  微信多文件分享常把 intent 类型标成通配，逐项核实才不会漏。 */
    fun resolveMediaMime(context: Context, uri: Uri, intentMime: String?): String? {
        if (intentMime != null &&
            (intentMime.startsWith("audio/") || intentMime.startsWith("video/"))) return intentMime
        val typed = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (typed != null && (typed.startsWith("audio/") || typed.startsWith("video/"))) return typed
        val ext = queryDisplayName(context, uri)?.substringAfterLast('.', "")?.lowercase()
        return EXT_MIME[ext]
    }

    fun offerAudioProcessing(activity: MainActivity, items: List<MediaItem>) {
        uiRef = WeakReference(activity)
        // 音频识别功能需要有效令牌：无令牌先弹输入框，ECS 核验通过后才进入处理选项
        AsrAuth.ensureVerified(activity) { showOptions(activity, items) }
    }

    private fun showOptions(activity: MainActivity, items: List<MediaItem>) {
        val labels = arrayOf(
            activity.getString(R.string.asr_opt_transcribe),
            activity.getString(R.string.asr_opt_timestamps),
            activity.getString(R.string.asr_opt_diarize),
            activity.getString(R.string.asr_opt_dialect)
        )
        val checked = booleanArrayOf(true, true, true, false)
        AlertDialog.Builder(activity)
            .setTitle(if (items.size > 1)
                activity.getString(R.string.asr_dialog_title) + "（${items.size}个文件）"
                else activity.getString(R.string.asr_dialog_title))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.asr_btn_start) { _, _ ->
                val opts = AsrClient.Options(
                    transcribe = checked[0] || checked[1] || checked[2],
                    timestamps = checked[1],
                    diarize = checked[2],
                    dialect2mandarin = checked[3]
                )
                Toast.makeText(activity, R.string.asr_toast_started, Toast.LENGTH_SHORT).show()
                AsrProcessingService.start(activity, items, opts)
            }
            .show()
    }

    /** 处理整批分享的音视频（在服务的工作线程上逐个推进）。 */
    fun runBatch(context: Context, items: List<MediaItem>, opts: AsrClient.Options) {
        runBatch(context, items, opts, done = 0, failed = 0)
    }

    /** 递归推进队列；done/failed 是本批次已完成的计数，令牌失效时重新核验后从中断处继续。 */
    private fun runBatch(context: Context, items: List<MediaItem>, opts: AsrClient.Options,
                         done: Int, failed: Int) {
        if (items.isEmpty()) {
            finishBatch(context, done, failed)
            return
        }
        val item = items.first()
        val rest = items.drop(1)
        val ordinal = done + failed + 1
        try {
            deliverResult(processOne(context, item, opts, items.size, ordinal))
            runBatch(context, rest, opts, done + 1, failed)
        } catch (e: AsrAuthException) {
            // 令牌失效：清除后弹输入框重新核验，通过后继续当前文件及余下文件
            AppPrefs.clearAsrAuth(context)
            val activity = uiRef.get()
            if (activity == null || activity.isFinishing) {
                // 页面已不在，无法重新核验：本项记为失败，继续余下文件
                recordFailure(describe(context, item, e))
                runBatch(context, rest, opts, done, failed + 1)
                return
            }
            AsrAuth.promptTokenInput(activity) {
                Thread {
                    try {
                        deliverResult(processOne(context, item, opts, items.size, ordinal))
                        runBatch(context, rest, opts, done + 1, failed)
                    } catch (retry: Exception) {
                        Log.e(TAG, "asr process failed", retry)
                        recordFailure(describe(context, item, retry))
                        runBatch(context, rest, opts, done, failed + 1)
                    }
                }.start()
            }
            return
        } catch (e: Exception) {
            Log.e(TAG, "asr process failed", e)
            recordFailure(describe(context, item, e))
            runBatch(context, rest, opts, done, failed + 1)
        }
    }

    /** 处理完成的一项：页面在前台直接打开结果页，否则排队等回前台。 */
    private fun deliverResult(s: Success) {
        val activity = uiRef.get()
        if (activityResumed && activity != null && !activity.isFinishing) {
            openResult(activity, s)
        } else {
            synchronized(pendingResults) { pendingResults.add(s) }
        }
    }

    private fun recordFailure(detail: String) {
        synchronized(pendingFailures) { pendingFailures.add(detail) }
    }

    private fun consumeFailures(): List<String> = synchronized(pendingFailures) {
        val list = pendingFailures.toList()
        pendingFailures.clear()
        list
    }

    /** 批次收尾：隐藏悬浮条、发完成通知；失败项前台直接弹汇总，否则留在队列等回前台。 */
    private fun finishBatch(context: Context, done: Int, failed: Int) {
        uiRef.get()?.let { activity ->
            fireUi(activity) {
                activity.findViewById<TextView>(R.id.asrProgressPill)?.visibility = View.GONE
            }
        }
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID_PROGRESS) }
        if (done > 0) {
            postResultNotification(context, done, lastSuccessName)
        }
        if (failed > 0) {
            val activity = uiRef.get()
            if (activityResumed && activity != null && !activity.isFinishing) {
                val failures = consumeFailures()
                if (failures.isNotEmpty()) showFailureSummary(activity, failures)
            }
        }
    }

    /** MainActivity 回前台时调用：把后台期间完成的结果页打开、失败项弹汇总。 */
    fun drainPending(activity: MainActivity) {
        val results = synchronized(pendingResults) {
            val list = pendingResults.toList()
            pendingResults.clear()
            list
        }
        for (s in results) openResult(activity, s)
        if (failuresVisibleAt(activity)) {
            val failures = consumeFailures()
            if (failures.isNotEmpty()) showFailureSummary(activity, failures)
        }
    }

    private fun failuresVisibleAt(activity: MainActivity): Boolean =
        activityResumed && !activity.isFinishing

    private fun showFailureSummary(activity: MainActivity, failures: List<String>) {
        fireUi(activity) {
            if (activity.isFinishing) return@fireUi
            val message = if (failures.size == 1) {
                activity.getString(R.string.asr_failed_prefix) + failures[0]
            } else {
                activity.getString(R.string.asr_batch_failed_list_fmt, failures.size,
                    failures.joinToString("\n"))
            }
            AlertDialog.Builder(activity)
                .setTitle(R.string.asr_progress_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** 进度上报：悬浮条必更新；系统通知仅在通知权限开启时发（该权限被拒则静默跳过）。 */
    private fun report(context: Context, remaining: Int, ordinal: Int, stage: String, pct: Int = -1) {
        val total = ordinal + remaining - 1
        val label = if (total > 1) {
            context.getString(R.string.asr_pill_batch_fmt, ordinal, total, stage)
        } else stage
        uiRef.get()?.let { activity ->
            fireUi(activity) {
                activity.findViewById<TextView>(R.id.asrProgressPill)?.apply {
                    text = label
                    visibility = View.VISIBLE
                }
            }
        }
        postProgress(context, label, pct)
    }

    /** 处理单个文件：拷贝到缓存 → 上传建任务 → 轮询至完成。失败抛异常。 */
    private fun processOne(context: Context, item: MediaItem, opts: AsrClient.Options,
                           remaining: Int, ordinal: Int): Success {
        val appCtx = context.applicationContext
        report(appCtx, remaining, ordinal, appCtx.getString(R.string.asr_progress_copy))
        val displayName = queryDisplayName(appCtx, item.uri)
        val local = File(appCtx.cacheDir,
            "asr_upload_${System.currentTimeMillis()}${resolveExt(displayName, item.mime)}")
        try {
            appCtx.contentResolver.openInputStream(item.uri)?.use { ins ->
                local.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: throw IllegalStateException("无法读取分享的录音内容")
            if (local.length() == 0L) throw IllegalStateException("分享的录音内容为空")

            val taskId = AsrClient.createTask(appCtx, local, opts, displayName) { pct ->
                report(appCtx, remaining, ordinal,
                    appCtx.getString(R.string.asr_progress_upload, pct), pct)
            }
            Log.i(TAG, "asr task created: $taskId")

            report(appCtx, remaining, ordinal, appCtx.getString(R.string.asr_progress_stage_recognizing))
            val deadline = System.currentTimeMillis() + POLL_MAX_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
                val task = AsrClient.getTask(appCtx, taskId)
                val status = task.optString("status")
                val stage = task.optString("stage")
                if (stage.isNotEmpty()) report(appCtx, remaining, ordinal, stage)
                when (status) {
                    "SUCCEEDED" -> {
                        val resultFile = File(appCtx.cacheDir, "asr_result_$taskId.json")
                        resultFile.writeText(task.getJSONObject("result").toString(), Charsets.UTF_8)
                        lastSuccessName = displayName ?: task.optString("filename")
                        return Success(resultFile, displayName ?: task.optString("filename"),
                            taskId, task.optJSONObject("options"))
                    }
                    "FAILED" -> throw IllegalStateException(task.optString("error", "识别失败"))
                }
            }
            throw IllegalStateException("处理超时")
        } finally {
            local.delete()
        }
    }

    private fun postProgress(context: Context, text: String, pct: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 400 && pct != 100) return
        lastNotifyAt = now
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(context.getString(R.string.asr_notif_title))
            .setContentText(text)
            .setProgress(100, pct.coerceAtLeast(0), pct != 100)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTIF_ID_PROGRESS, notification) }
    }

    private fun postResultNotification(context: Context, succeeded: Int, firstName: String?) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(context)
        val text = when {
            succeeded == 1 && firstName != null ->
                context.getString(R.string.asr_notif_done_one, firstName)
            succeeded > 1 -> context.getString(R.string.asr_notif_done_multi, succeeded)
            else -> context.getString(R.string.asr_notif_all_failed)
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(context.getString(R.string.asr_notif_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { nm.notify(NOTIF_ID_RESULT, notification) }
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_PROGRESS,
                context.getString(R.string.asr_notif_channel_progress),
                NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(NOTIF_CHANNEL_RESULT,
                context.getString(R.string.asr_notif_channel_result),
                NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** 前台服务入口通知：IMPORTANCE_LOW + 静默更新，不让进度通知打扰。 */
    fun startForeground(service: Service) {
        ensureChannels(service)
        val notification = progressNotification(service)
        if (Build.VERSION.SDK_INT >= 29) {
            service.startForeground(NOTIF_ID_PROGRESS, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            service.startForeground(NOTIF_ID_PROGRESS, notification)
        }
    }

    fun stopForeground(service: Service) {
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private fun progressNotification(context: Context) =
        NotificationCompat.Builder(context, NOTIF_CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(context.getString(R.string.asr_notif_title))
            .setContentText(context.getString(R.string.asr_progress_preparing))
            .setProgress(100, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun openResult(activity: MainActivity, s: Success) {
        if (activity.isFinishing) return
        val it = Intent(activity, AsrResultActivity::class.java)
            .putExtra("result_path", s.resultFile.absolutePath)
            .putExtra("filename", s.displayName)
            .putExtra("task_id", s.taskId)
        s.options?.let { options ->
            it.putExtra("opt_timestamps", options.optBoolean("timestamps"))
            it.putExtra("opt_diarize", options.optBoolean("diarize"))
            it.putExtra("opt_mandarin", options.optBoolean("dialect2mandarin"))
        }
        activity.startActivity(it)
    }

    private fun describe(context: Context, item: MediaItem, e: Exception): String {
        val name = queryDisplayName(context.applicationContext, item.uri) ?: "音视频文件"
        return "$name：${e.message ?: e.toString()}"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var displayName: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) displayName = c.getString(0)
            }
        }
        // file:// 分享没有内容提供者可查，从路径段取文件名（扩展名识别依赖它）
        if (displayName == null && uri.scheme == "file") {
            displayName = uri.lastPathSegment?.substringAfterLast('/')
        }
        return displayName?.take(200)
    }

    /** 上传扩展名：显示名优先，MIME 兜底，保证 asrsvc 能识别格式。 */
    private fun resolveExt(displayName: String?, mime: String?): String {
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase()
        if (!fromName.isNullOrEmpty() && fromName in EXT_MIME) return ".$fromName"
        return when (mime) {
            "audio/mpeg" -> ".mp3"
            "audio/amr", "audio/3gpp" -> ".amr"
            "audio/ogg" -> ".ogg"
            "audio/flac" -> ".flac"
            "audio/wav", "audio/x-wav" -> ".wav"
            "video/mp4", "video/3gpp" -> ".mp4"
            "video/quicktime" -> ".mov"
            "video/x-matroska" -> ".mkv"
            "video/x-msvideo" -> ".avi"
            "video/webm" -> ".webm"
            else -> ".m4a"
        }
    }

    /** 在 UI 线程执行但不等待（进度类更新用）。 */
    private fun fireUi(activity: MainActivity, block: () -> Unit) {
        if (activity.isFinishing) return
        activity.runOnUiThread {
            if (!activity.isFinishing) block()
        }
    }
}
