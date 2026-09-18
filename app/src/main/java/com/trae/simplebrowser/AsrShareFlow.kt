package com.trae.simplebrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch

/** 录音分享处理流程：多选处理项 → 上传 ECS asrsvc → 轮询 → 结果页。 */
object AsrShareFlow {
    private const val TAG = "LiteBrowser"
    private const val POLL_INTERVAL_MS = 5000L
    private const val POLL_MAX_MS = 2 * 60 * 60 * 1000L

    fun offerAudioProcessing(activity: MainActivity, mime: String?, stream: Uri) {
        // 音频识别功能需要有效令牌：无令牌先弹输入框，ECS 核验通过后才进入处理选项
        AsrAuth.ensureVerified(activity) { showOptions(activity, mime, stream) }
    }

    private fun showOptions(activity: MainActivity, mime: String?, stream: Uri) {
        val labels = arrayOf(
            activity.getString(R.string.asr_opt_transcribe),
            activity.getString(R.string.asr_opt_timestamps),
            activity.getString(R.string.asr_opt_diarize),
            activity.getString(R.string.asr_opt_dialect)
        )
        val checked = booleanArrayOf(true, true, true, false)
        AlertDialog.Builder(activity)
            .setTitle(R.string.asr_dialog_title)
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
                Thread { process(activity, mime, stream, opts, retried = false) }.start()
            }
            .show()
    }

    private fun process(activity: MainActivity, mime: String?, stream: Uri,
                        opts: AsrClient.Options, retried: Boolean) {
        val dialog = onUi(activity) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.asr_progress_title)
                .setMessage(activity.getString(R.string.asr_progress_copy))
                .setCancelable(false)
                .show()
        }
        var audioFile: File? = null
        try {
            val appCtx = activity.applicationContext
            val displayName = queryDisplayName(appCtx, stream)
            val local = File(appCtx.cacheDir, "asr_upload_${System.currentTimeMillis()}${resolveExt(displayName, mime)}")
            appCtx.contentResolver.openInputStream(stream)?.use { ins ->
                local.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: throw IllegalStateException("无法读取分享的录音内容")
            audioFile = local
            if (local.length() == 0L) throw IllegalStateException("分享的录音内容为空")

            val taskId = AsrClient.createTask(activity, local, opts) { pct ->
                fireUi(activity) {
                    dialog?.setMessage(activity.getString(R.string.asr_progress_upload, pct))
                }
            }
            Log.i(TAG, "asr task created: $taskId")

            val deadline = System.currentTimeMillis() + POLL_MAX_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
                val task = AsrClient.getTask(activity, taskId)
                val status = task.optString("status")
                val stage = task.optString("stage")
                if (stage.isNotEmpty()) {
                    fireUi(activity) { dialog?.setMessage(stage) }
                }
                when (status) {
                    "SUCCEEDED" -> {
                        val resultFile = File(appCtx.cacheDir, "asr_result_$taskId.json")
                        resultFile.writeText(task.getJSONObject("result").toString(), Charsets.UTF_8)
                        fireUi(activity) {
                            dismiss(dialog)
                            openResult(activity, resultFile, task.optJSONObject("options"),
                                displayName ?: task.optString("filename"), taskId)
                        }
                        return
                    }
                    "FAILED" -> throw IllegalStateException(task.optString("error", "识别失败"))
                }
            }
            throw IllegalStateException("处理超时")
        } catch (e: AsrAuthException) {
            // 令牌失效：清除后弹输入框重新核验，核验通过自动重试一次
            AppPrefs.clearAsrAuth(activity)
            if (retried) {
                fireUi(activity) {
                    dismiss(dialog)
                    if (!activity.isFinishing) {
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.asr_progress_title)
                            .setMessage(R.string.asr_token_invalid)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            } else {
                fireUi(activity) {
                    dismiss(dialog)
                }
                AsrAuth.promptTokenInput(activity) {
                    Thread { process(activity, mime, stream, opts, retried = true) }.start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "asr process failed", e)
            fireUi(activity) {
                dismiss(dialog)
                if (!activity.isFinishing) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.asr_progress_title)
                        .setMessage(activity.getString(R.string.asr_failed_prefix) + (e.message ?: e.toString()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        } finally {
            audioFile?.delete()
        }
    }

    private fun openResult(activity: MainActivity, resultFile: File, options: JSONObject?,
                           displayName: String, taskId: String) {
        if (activity.isFinishing) return
        val it = Intent(activity, AsrResultActivity::class.java)
            .putExtra("result_path", resultFile.absolutePath)
            .putExtra("filename", displayName)
            .putExtra("task_id", taskId)
        if (options != null) {
            it.putExtra("opt_timestamps", options.optBoolean("timestamps"))
            it.putExtra("opt_diarize", options.optBoolean("diarize"))
            it.putExtra("opt_mandarin", options.optBoolean("dialect2mandarin"))
        }
        activity.startActivity(it)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var displayName: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) displayName = c.getString(0)
            }
        }
        return displayName?.take(200)
    }

    /** 上传扩展名：显示名优先，MIME 兜底，保证 asrsvc 能识别格式。 */
    private fun resolveExt(displayName: String?, mime: String?): String {
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase()
        val valid = setOf(
            "m4a", "mp3", "wav", "aac", "amr", "ogg", "flac", "wma", "opus", "3gp",
            "mp4", "mov", "mkv", "avi", "webm", "m4v"
        )
        if (!fromName.isNullOrEmpty() && fromName in valid) return ".$fromName"
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

    /** 在 UI 线程执行并等待完成，返回执行结果；Activity 已结束时返回 null。 */
    private fun <T> onUi(activity: MainActivity, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return if (activity.isFinishing) null else block()
        }
        val latch = CountDownLatch(1)
        var value: T? = null
        activity.runOnUiThread {
            try {
                value = if (activity.isFinishing) null else block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return value
    }

    /** 在 UI 线程执行但不等待（进度类更新用）。 */
    private fun fireUi(activity: MainActivity, block: () -> Unit) {
        if (activity.isFinishing) return
        activity.runOnUiThread {
            if (!activity.isFinishing) block()
        }
    }

    private fun dismiss(dialog: AlertDialog?) {
        try {
            dialog?.dismiss()
        } catch (ignored: Exception) {
        }
    }
}
