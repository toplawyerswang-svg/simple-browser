package com.trae.simplebrowser

import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.trae.simplebrowser.core.FolderPicker

/** 归入资料夹的共用流程：设置页两个列表（长按单条 / 多选批量）、识别结果详情页、
 *  夹内批量移动与聊天分享页共用。
 *  服务端 move 语义：一条记录最多归属一个夹，已在夹中的重复移入会被跳过。
 *  onDone 在成功移入后回调（调用方借此刷新列表/退出多选），回调发生在 UI 线程。 */
object FolderAddFlow {

    /** 单条移入：列出已有文件夹 +「＋新建」项，选中即移入。
     *  copy=false 移动语义（默认），copy=true 复制语义（保留其他夹归属）。 */
    fun moveSingle(activity: AppCompatActivity, kind: String, ref: String, title: String,
                   copy: Boolean = false, onDone: () -> Unit = {}) {
        moveBatch(activity, listOf(kind to ref),
            dialogTitle = activity.getString(R.string.folder_add_to, title),
            copy = copy, onDone = onDone)
    }

    /** 批量移入：payload 为 (kind, ref) 列表；copy=false 服务端逐条 move（一条记录只归属
     *  一个夹），copy=true 复制语义（保留其他夹归属，一条记录可同时在多个夹）。
     *  onSettle 只触发一次且无论取消/失败/成功都会走到（收尾用，如转存流程弹结果），
     *  与 onDone（仅移入成功后刷新调用方）互补；均在 UI 线程回调。
     *  注意 onDone 保持在参数末位——既有调用方用尾随 lambda 传它。 */
    fun moveBatch(activity: AppCompatActivity, payload: List<Pair<String, String>>,
                  dialogTitle: String? = null, onSettle: (() -> Unit)? = null,
                  copy: Boolean = false, onDone: () -> Unit = {}) {
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
                    val title = dialogTitle
                        ?: activity.getString(if (copy) R.string.folder_copy_selected
                                              else R.string.folder_move_selected, payload.size)
                    showPicker(activity, list, title, payload, onDone, ::settle, copy)
                }
            } catch (e: Exception) {
                showError(activity, e)
                settle()
            }
        }.start()
    }

    /**
     * 选夹弹窗（2026-11-26 加关键词搜索）：搜索框 +「＋ 新建文件夹」常驻行 + 可滚动的文件夹列表。
     * - 搜索只过滤已有文件夹，「新建」永远可点（搜不到可当场建一个）；
     * - 没有文件夹时不出搜索框（只剩新建入口，搜也没意义）；
     * - 列表高度取「内容高度」与「屏高预留键盘/标题/按钮后的上限」的较小值，
     *   键盘弹出时不会把列表和取消按钮压在下面。
     */
    private fun showPicker(activity: AppCompatActivity,
                           folders: List<AsrClient.FolderInfo>,
                           title: String,
                           payload: List<Pair<String, String>>,
                           onDone: () -> Unit, settle: () -> Unit, copy: Boolean) {
        val rowH = dp(activity, 50)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), dp(activity, 2))
        }
        var dialog: AlertDialog? = null
        val hasFolders = folders.isNotEmpty()

        val listBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroller = ScrollView(activity).apply {
            addView(listBox)
            isFillViewport = false
        }

        // render 引用 dialog / scroller 等，先声明后在下面赋值（Kotlin 局部函数可捕获 var）
        fun render(query: String) {
            val matched = FolderPicker.filter(folders, query) { it.name }
            listBox.removeAllViews()
            if (matched.isEmpty()) {
                listBox.addView(TextView(activity).apply {
                    text = activity.getString(R.string.folder_search_no_match)
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setTextColor(secondaryColor(activity))
                    setLineSpacing(dp(activity, 3).toFloat(), 1f)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 96)))
            } else {
                matched.forEach { f ->
                    listBox.addView(pickerRow(activity).apply {
                        text = f.name
                        setOnClickListener {
                            dialog?.dismiss()
                            addBatch(activity, f.folderId, f.name, payload, onDone, settle, copy)
                        }
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, rowH))
                }
            }
            val cap = minOf(dp(activity, 320),
                activity.resources.displayMetrics.heightPixels - dp(activity, 430))
                .coerceAtLeast(dp(activity, 120))
            val h = if (matched.isEmpty()) dp(activity, 96)
                    else minOf(matched.size * rowH, cap)
            (scroller.layoutParams as? LinearLayout.LayoutParams)?.height = h
            scroller.requestLayout()
        }

        if (hasFolders) {
            val search = EditText(activity).apply {
                hint = activity.getString(R.string.folder_search_hint)
                setSingleLine(true)
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 4), dp(activity, 8))
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) =
                    render(s?.toString().orEmpty())
            })
            root.addView(search, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // 「＋ 新建文件夹」常驻：不参与关键词过滤，任何搜索词下都能当场新建
        root.addView(pickerRow(activity).apply {
            text = activity.getString(R.string.folder_new_and_add)
            setOnClickListener {
                dialog?.dismiss()
                promptNewAndMove(activity, payload, onDone, settle, copy)
            }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH))

        if (hasFolders) {
            root.addView(scroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 0)))
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel) { _, _ -> settle() }
            .create()
        // 键盘弹出时窗口跟着缩，列表在剩余空间里滚动（不设会把列表/取消按钮压在键盘下）
        dialog.setOnShowListener {
            dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        if (hasFolders) render("")
    }

    /** 弹窗列表行：统一 16sp/50dp/水波纹，文字色跟随主题（深色模式不隐形） */
    private fun pickerRow(activity: AppCompatActivity): TextView = TextView(activity).apply {
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(dp(activity, 12), 0, dp(activity, 12), 0)
        background = selectableItemBackground(activity)
        isClickable = true
        isFocusable = true
    }

    private fun dp(activity: AppCompatActivity, v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    /** 主题里的次要文字色（无匹配提示用；跟随系统深浅色） */
    private fun secondaryColor(activity: AppCompatActivity): Int {
        val tv = TypedValue()
        if (activity.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)) {
            if (tv.resourceId != 0) return ContextCompat.getColor(activity, tv.resourceId)
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT) return tv.data
        }
        return android.graphics.Color.GRAY
    }

    /** 点按水波纹背景（与对话框条目一致）；取不到时不设背景也能点 */
    private fun selectableItemBackground(activity: AppCompatActivity) =
        android.util.TypedValue().let { tv ->
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) ContextCompat.getDrawable(activity, tv.resourceId) else null
        }

    /** 新建文件夹后立即移入。 */
    private fun promptNewAndMove(activity: AppCompatActivity,
                                 payload: List<Pair<String, String>>, onDone: () -> Unit,
                                 settle: () -> Unit, copy: Boolean = false) {
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
                            addBatch(activity, fid, name, payload, onDone, settle, copy)
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
                         settle: () -> Unit, copy: Boolean = false) {
        Thread {
            try {
                val (added, skipped, failed) =
                    AsrClient.folderAddItems(activity, folderId, payload, copy)
                activity.runOnUiThread {
                    if (dead(activity)) return@runOnUiThread
                    var msg = activity.getString(
                        if (copy) R.string.folder_batch_copied else R.string.folder_batch_added,
                        folderName, added)
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