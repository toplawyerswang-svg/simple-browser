package com.trae.simplebrowser

import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 归入资料夹的共用流程：设置页两个列表（长按单条 / 多选批量）与识别结果详情页共用。
 *  服务端 move 语义：一条记录最多归属一个夹，已在夹中的重复移入会被跳过。
 *  onDone 在成功移入后回调（调用方借此刷新列表/退出多选），回调发生在 UI 线程。 */
object FolderAddFlow {

    /** 单条移入：列出已有文件夹 +「＋新建」项，选中即移入。 */
    fun moveSingle(activity: AppCompatActivity, kind: String, ref: String, title: String,
                   onDone: () -> Unit = {}) {
        moveBatch(activity, listOf(kind to ref),
            dialogTitle = activity.getString(R.string.folder_add_to, title), onDone = onDone)
    }

    /** 批量移入：payload 为 (kind, ref) 列表，服务端逐条 move。
     *  onSettle 只触发一次且无论取消/失败/成功都会走到（收尾用，如转存流程弹结果），
     *  与 onDone（仅移入成功后刷新调用方）互补；均在 UI 线程回调。 */
    fun moveBatch(activity: AppCompatActivity, payload: List<Pair<String, String>>,
                  dialogTitle: String? = null, onDone: () -> Unit = {},
                  onSettle: (() -> Unit)? = null) {
        var settled = false
        fun settle() {
            if (!settled) {
                settled = true
                activity.runOnUiThread { onSettle?.invoke() }
            }
        }
        Thread {
            try {
                val list = AsrClient.listFolders(activity)
                activity.runOnUiThread {
                    if (dead(activity)) return@runOnUiThread
                    val options = mutableListOf(activity.getString(R.string.folder_new_and_add))
                    list.forEach { options.add(it.name) }
                    AlertDialog.Builder(activity)
                        .setTitle(dialogTitle
                            ?: activity.getString(R.string.folder_move_selected, payload.size))
                        .setItems(options.toTypedArray()) { _, which ->
                            if (which == 0) {
                                promptNewAndMove(activity, payload, onDone, ::settle)
                            } else {
                                val f = list[which - 1]
                                addBatch(activity, f.folderId, f.name, payload, onDone, ::settle)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> settle() }
                        .show()
                }
            } catch (e: Exception) {
                showError(activity, e)
                settle()
            }
        }.start()
    }

    /** 新建文件夹后立即移入。 */
    private fun promptNewAndMove(activity: AppCompatActivity,
                                 payload: List<Pair<String, String>>, onDone: () -> Unit,
                                 settle: () -> Unit) {
        val input = EditText(activity).apply {
            hint = activity.getString(R.string.folder_name_hint)
            setSingleLine(true)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.folder_create_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel) { _, _ -> settle() }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                Thread {
                    try {
                        val fid = AsrClient.createFolder(activity, name)
                        activity.runOnUiThread {
                            if (dead(activity)) return@runOnUiThread
                            addBatch(activity, fid, name, payload, onDone, settle)
                        }
                    } catch (e: Exception) {
                        showError(activity, e)
                        settle()
                    }
                }.start()
            }
            .show()
    }

    /** 统一走批量接口移入（单条即 1 项批量）：一次请求拿到新增/跳过/失败计数，提示口径一致。 */
    private fun addBatch(activity: AppCompatActivity, folderId: String, folderName: String,
                         payload: List<Pair<String, String>>, onDone: () -> Unit,
                         settle: () -> Unit) {
        Thread {
            try {
                val (added, skipped, failed) = AsrClient.folderAddItems(activity, folderId, payload)
                activity.runOnUiThread {
                    if (dead(activity)) return@runOnUiThread
                    var msg = activity.getString(R.string.folder_batch_added, folderName, added)
                    if (skipped > 0) msg += activity.getString(R.string.folder_batch_skipped, skipped)
                    if (failed > 0) msg += activity.getString(R.string.folder_batch_failed, failed)
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    onDone()
                    settle()
                }
            } catch (e: Exception) {
                showError(activity, e)
                settle()
            }
        }.start()
    }

    private fun showError(activity: AppCompatActivity, e: Exception) {
        activity.runOnUiThread {
            if (!dead(activity)) {
                Toast.makeText(activity,
                    activity.getString(R.string.asr_failed_prefix) + (e.message ?: e.toString()),
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dead(activity: AppCompatActivity) = activity.isFinishing || activity.isDestroyed
}
