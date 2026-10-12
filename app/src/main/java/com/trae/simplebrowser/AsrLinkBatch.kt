package com.trae.simplebrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 批量生成分享链接并弹汇总清单（复制/分享两用）。
 *  设置页多选「分享链接」与批量转写结果页共用；单条失败不阻断其余，失败名单附在汇总尾部。 */
object AsrLinkBatch {

    /** tasks 为 (文件名, task_id) 列表；有效期/次数沿用 AppPrefs 记忆的上次分享参数。 */
    fun generateAndShow(activity: AppCompatActivity, tasks: List<Pair<String, String>>) {
        Toast.makeText(activity,
            activity.getString(R.string.asr_batch_link_progress, tasks.size), Toast.LENGTH_SHORT).show()
        Thread {
            val blocks = ArrayList<String>()
            val failed = ArrayList<String>()
            val hours = AppPrefs.getShareValidityHours(activity)
            val opens = AppPrefs.getShareMaxOpens(activity)
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            for ((name, taskId) in tasks) {
                try {
                    val info = AsrClient.createShare(activity, taskId, hours, opens)
                    val opensText = if (info.maxOpens == 0) "不限打开次数" else "可打开${info.maxOpens}次"
                    val expire = fmt.format(Date(System.currentTimeMillis() + info.validityHours * 3600_000L))
                    blocks.add("▪ $name\n链接：${info.url}\n提取码：${info.pwd}\n有效至 $expire（$opensText）")
                } catch (e: Exception) {
                    android.util.Log.w("AsrLinkBatch", "share link failed: $taskId", e)
                    failed.add(name)
                }
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (blocks.isEmpty()) {
                    Toast.makeText(activity,
                        activity.getString(R.string.asr_failed_prefix) +
                            activity.getString(R.string.asr_batch_link_all_failed),
                        Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                var msg = activity.getString(R.string.asr_batch_link_header, blocks.size) + "\n\n" +
                    blocks.joinToString("\n\n")
                if (failed.isNotEmpty()) {
                    msg += "\n\n" + activity.getString(R.string.asr_batch_link_failed,
                        failed.size, failed.joinToString("、"))
                }
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(R.string.asr_batch_link_title, blocks.size))
                    .setMessage(msg)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.asr_btn_copy_link) { _, _ ->
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("share", msg))
                        Toast.makeText(activity, R.string.asr_share_copied, Toast.LENGTH_SHORT).show()
                    }
                    .setPositiveButton(R.string.asr_btn_share) { _, _ ->
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, msg)
                        activity.startActivity(Intent.createChooser(send,
                            activity.getString(R.string.asr_btn_share)))
                    }
                    .show()
            }
        }.start()
    }
}
