package com.trae.simplebrowser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.graphics.Typeface
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 微信聊天记录转存流程：分享进来的「聊天记录_*.zip」上传 ECS asrsvc 解析并生成
 *  分享链接（30 天有效，agent 可经 /s/<id>/chat.json 读取结构化结果）。
 *  跑在 AsrProcessingService（dataSync 前台服务）里，切后台不被掐网中断；
 *  进度走工具栏上方悬浮条与系统通知。链接生成即完成，无需轮询。 */
object WxChatShareFlow {
    private const val TAG = "LiteBrowser"
    private const val NOTIF_CHANNEL_PROGRESS = "asr_progress"
    private const val NOTIF_CHANNEL_RESULT = "asr_result"
    private const val NOTIF_ID_RESULT = 2003
    private var lastNotifyAt = 0L

    /** 一次成功的转存：displayName 是记录标题（新建=zip 名可改，追加=目标记录名），
     *  url 已含提取码；shareId/participants 用于转存后的真名填写（保存需刷新该份快照）。
     *  appended=true 表示本次是追加进已有记录（链接不变，结果文案区分）。 */
    data class Success(val displayName: String, val url: String, val pwd: String,
                       val expiresAt: Long, val shareId: String,
                       val participants: List<String>,
                       val appended: Boolean = false)

    /** 追加选择框用的已有记录摘要（服务端已按创建时间倒序）。 */
    private data class WxShareRef(val shareId: String, val title: String,
                                  val messageCount: Int, val createdAt: Long)

    private var uiRef = WeakReference<MainActivity>(null)

    /** 页面不在前台期间完成的转存，回前台时统一弹展示。 */
    private val pendingResults = ArrayDeque<Success>()

    /** 判定分享项是否微信聊天记录包：微信固定以「聊天记录_日期_时间.zip」命名分享合并转发。
     *  文件名只是初判，最终以服务端解压出 聊天记录.txt 为准。 */
    fun isWxChatZip(context: Context, uri: Uri): Boolean {
        val name = AsrShareFlow.queryDisplayName(context, uri) ?: return false
        return name.startsWith("聊天记录") && name.endsWith(".zip", ignoreCase = true)
    }

    /** 分享入口：令牌核验通过后查已有记录——有则弹「新建/追加」选择框，无则直接新建上传。 */
    fun offer(activity: MainActivity, items: List<AsrShareFlow.MediaItem>) {
        uiRef = WeakReference(activity)
        AsrAuth.ensureVerified(activity) {
            Thread {
                val shares = runCatching { queryShareRefs(activity) }.getOrDefault(emptyList())
                fireUi(activity) {
                    if (activity.isFinishing) return@fireUi
                    if (shares.isEmpty()) startTransfer(activity, items, null, null)
                    else showAppendChooser(activity, items, shares)
                }
            }.start()
        }
    }

    private fun startTransfer(activity: MainActivity, items: List<AsrShareFlow.MediaItem>,
                              appendTo: String?, appendTitle: String?) {
        Toast.makeText(activity, R.string.wxchat_toast_started, Toast.LENGTH_SHORT).show()
        AsrProcessingService.startWxChat(activity, items, appendTo, appendTitle)
    }

    private fun queryShareRefs(context: Context): List<WxShareRef> {
        val arr = AsrClient.listWxChatShares(context)
        val out = ArrayList<WxShareRef>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(WxShareRef(o.optString("share_id"), o.optString("title", "聊天记录"),
                o.optInt("message_count", 0), o.optLong("created_at")))
        }
        return out
    }

    /** 追加选择框：点列表项=追加进该记录（按时间归并、链接不变）；「新建记录」=照旧单独建份。
     *  不用 setMessage+setItems 组合——appcompat 1.7.0 该布局下列表会被消息面板吞掉不渲染，
     *  改为自定义 view（消息文本 + 筛选框 + ListView），与真名填写弹窗同套路。
     *  记录多时可在筛选框输关键词（匹配标题/条数/日期）快速定位目标记录。 */
    private fun showAppendChooser(activity: MainActivity, items: List<AsrShareFlow.MediaItem>,
                                  shares: List<WxShareRef>) {
        val dm = activity.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val fmt = SimpleDateFormat("M月d日", Locale.CHINA)
        val labels = shares.map { s ->
            activity.getString(R.string.wxchat_append_item, s.title, s.messageCount,
                fmt.format(Date(s.createdAt * 1000)))
        }
        fun listHeight(n: Int) = (n * dp(48)).coerceAtLeast(dp(48))
            .coerceAtMost(dm.heightPixels * 45 / 100)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.wxchat_append_msg, shares.size)
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(dp(24), dp(10), dp(24), dp(4))
        })
        val filter = EditText(activity).apply {
            hint = activity.getString(R.string.wxchat_append_filter_hint)
            setSingleLine(true)
        }
        box.addView(filter)
        val list = ListView(activity)
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, ArrayList<String>())
        list.adapter = adapter
        box.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, listHeight(shares.size)))
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.wxchat_append_title)
            .setView(box)
            .setPositiveButton(R.string.wxchat_append_new) { _, _ ->
                startTransfer(activity, items, null, null)
            }
            .setNegativeButton(R.string.wxchat_append_cancel) { _, _ ->
                Toast.makeText(activity, R.string.wxchat_append_aborted, Toast.LENGTH_SHORT).show()
            }
            .create()
        var visibleShares: List<WxShareRef> = shares
        fun applyFilter(kwRaw: String) {
            val kw = kwRaw.trim()
            val idx = if (kw.isEmpty()) shares.indices.toList()
            else shares.indices.filter { labels[it].contains(kw, ignoreCase = true) }
            visibleShares = idx.map { shares[it] }
            adapter.clear()
            idx.forEach { adapter.add(labels[it]) }
            (list.layoutParams as LinearLayout.LayoutParams).let {
                it.height = listHeight(visibleShares.size)
                list.layoutParams = it
            }
        }
        applyFilter("")
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = applyFilter(s?.toString() ?: "")
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        list.setOnItemClickListener { _, _, which, _ ->
            dialog.dismiss()
            val s = visibleShares[which]
            startTransfer(activity, items, s.shareId, s.title)
        }
        dialog.show()
    }

    /** 服务线程入口：逐个上传（微信一次只分享一个聊天记录包，多包按队列推进）。
     *  appendTo 非空时全部追加进该记录（微信一次只分享一包，多包同目标顺序追加）。 */
    fun runBatch(context: Context, items: List<AsrShareFlow.MediaItem>,
                 appendTo: String?, appendTitle: String?) {
        var done = 0
        val failures = ArrayList<String>()
        for ((index, item) in items.withIndex()) {
            try {
                deliver(processOne(context, item, items.size, index + 1, appendTo, appendTitle))
                done++
            } catch (e: AsrAuthException) {
                failures.add(describe(context, item, e))
                break  // 令牌失效：余下项必同样失败，停止队列等用户重新核验
            } catch (e: Exception) {
                Log.e(TAG, "wxchat upload failed", e)
                failures.add(describe(context, item, e))
            }
        }
        finishBatch(context, done, failures)
    }

    /** 处理单个 zip：拷贝到缓存 → 上传（新建或追加）。失败抛异常。 */
    private fun processOne(context: Context, item: AsrShareFlow.MediaItem,
                           total: Int, ordinal: Int,
                           appendTo: String?, appendTitle: String?): Success {
        val appCtx = context.applicationContext
        val displayName = AsrShareFlow.queryDisplayName(appCtx, item.uri) ?: "聊天记录.zip"
        report(appCtx, appCtx.getString(R.string.wxchat_pill_copy), -1, total, ordinal)
        val local = File(appCtx.cacheDir, "wxchat_upload_${System.currentTimeMillis()}.zip")
        try {
            appCtx.contentResolver.openInputStream(item.uri)?.use { ins ->
                local.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: throw IllegalStateException(appCtx.getString(R.string.wxchat_err_read))
            if (local.length() == 0L) throw IllegalStateException(appCtx.getString(R.string.wxchat_err_empty))

            val share = if (appendTo != null) {
                AsrClient.appendWxChat(appCtx, appendTo, local) { pct ->
                    report(appCtx, appCtx.getString(R.string.wxchat_pill_upload, pct), pct, total, ordinal)
                }
            } else {
                AsrClient.uploadWxChat(appCtx, local, displayName) { pct ->
                    report(appCtx, appCtx.getString(R.string.wxchat_pill_upload, pct), pct, total, ordinal)
                }
            }
            Log.i(TAG, "wxchat share created: ${share.shareId} appended=${appendTo != null}")
            // 追加时展示目标记录名；新建时展示 zip 名（真名弹窗里可改名）
            return Success(appendTitle ?: displayName, share.url, share.pwd, share.expiresAt,
                share.shareId, share.participants, appended = appendTo != null)
        } finally {
            local.delete()
        }
    }

    /** 完成的一项：页面在前台先弹真名填写（保存后刷本份快照），否则排队等回前台。 */
    private fun deliver(s: Success) {
        val activity = uiRef.get()
        if (AsrShareFlow.activityResumed && activity != null && !activity.isFinishing) {
            showNameDialog(activity, listOf(s))
        } else {
            synchronized(pendingResults) { pendingResults.add(s) }
        }
    }

    /** MainActivity 回前台时调用：弹后台期间完成的真名填写。 */
    fun drainPending(activity: MainActivity) {
        val results = synchronized(pendingResults) {
            val list = pendingResults.toList()
            pendingResults.clear()
            list
        }
        if (results.isNotEmpty()) showNameDialog(activity, results)
    }

    /** 真名填写界面：顶部「记录名称」可改名（保存即重命名分享），下面是全部微信昵称
     *  逐行预填（已设过的带出）；保存=批量改名+写入映射并刷新本份快照（分享页立即生效），
     *  跳过=保持现状。 */
    private fun showNameDialog(activity: MainActivity, results: List<Success>) {
        Thread {
            val mapping = HashMap<String, String>()
            runCatching {
                val o = AsrClient.listWxNameMappings(activity).optJSONObject("names")
                if (o != null) for (k in o.keys()) mapping[k] = o.optString(k)
            }  // 预填失败不阻断填写，编辑框留空即可
            fireUi(activity) { buildAndShowNameDialog(activity, results, mapping) }
        }.start()
    }

    private fun buildAndShowNameDialog(activity: MainActivity, results: List<Success>,
                                       mapping: Map<String, String>) {
        if (activity.isFinishing) return
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val editors = ArrayList<Pair<String, EditText>>()
        val nameEditors = ArrayList<Pair<Success, EditText>>()
        val seen = HashSet<String>()
        for (s in results) {
            // 记录名称：预填当前标题（新建=zip 名；追加=目标记录名），改动后保存即重命名
            box.addView(TextView(activity).apply {
                text = activity.getString(R.string.wxchat_name_title_label)
                textSize = 12f
                setTextColor(0xFF8A8A8A.toInt())
                setPadding(0, dp(10), 0, 0)
            })
            val nameEdit = EditText(activity).apply {
                setText(s.displayName)
                setSingleLine(true)
            }
            box.addView(nameEdit)
            nameEditors.add(s to nameEdit)
            if (s.participants.isEmpty()) continue
            if (results.size > 1) {
                box.addView(TextView(activity).apply {
                    text = s.displayName
                    textSize = 13f
                    setTextColor(0xFF1565C0.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(10), 0, 0)
                })
            }
            for (nick in s.participants) {
                if (!seen.add(nick)) continue  // 多份记录重复昵称只填一次
                box.addView(TextView(activity).apply {
                    text = nick
                    textSize = 14f
                    setTextColor(0xFF212121.toInt())
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(8), 0, 0)
                })
                val edit = EditText(activity).apply {
                    hint = activity.getString(R.string.wxchat_name_hint)
                    setText(mapping[nick] ?: "")
                    setSingleLine(true)
                }
                box.addView(edit)
                editors.add(nick to edit)
            }
        }
        val scroll = ScrollView(activity).apply { addView(box) }
        val note = TextView(activity).apply {
            text = activity.getString(R.string.wxchat_fill_note)
            textSize = 12f
            setTextColor(0xFF8A8A8A.toInt())
            setPadding(dp(4), dp(10), dp(4), 0)
        }
        val wrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(note)
            addView(scroll)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.wxchat_fill_title)
            .setView(wrapper)
            .setCancelable(false)
            .setPositiveButton(R.string.wxchat_fill_save, null)
            .setNegativeButton(R.string.wxchat_fill_skip) { _, _ ->
                showResult(activity, results)
            }
            .create()
        dialog.setOnShowListener {
            val btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val titles = nameEditors.mapNotNull { (s, edit) ->
                    val v = edit.text.toString().trim()
                        .replace(Regex("[\\p{Cntrl}]"), "").take(200)
                    if (v.isNotEmpty() && v != s.displayName) s.shareId to v else null
                }
                val toSave = editors.mapNotNull { (nick, edit) ->
                    val v = edit.text.toString().trim()
                    if (v.isNotEmpty()) nick to v else null
                }.toMap()
                btn.isEnabled = false
                Thread {
                    try {
                        for ((sid, t) in titles) AsrClient.renameWxChatShare(activity, sid, t)
                        if (toSave.isNotEmpty()) {
                            AsrClient.bulkSetWxNames(activity, toSave, results.first().shareId)
                        }
                        val renamed = titles.toMap()
                        fireUi(activity) {
                            dialog.dismiss()
                            showResult(activity, results.map { s ->
                                if (renamed.containsKey(s.shareId)) s.copy(displayName = renamed[s.shareId]!!)
                                else s
                            })
                        }
                    } catch (e: Exception) {
                        fireUi(activity) {
                            btn.isEnabled = true
                            if (!activity.isFinishing) {
                                Toast.makeText(activity, activity.getString(R.string.asr_failed_prefix) +
                                    (e.message ?: e.toString()), Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun finishBatch(context: Context, done: Int, failures: List<String>) {
        uiRef.get()?.let { activity ->
            fireUi(activity) {
                activity.findViewById<TextView>(R.id.asrProgressPill)?.visibility = View.GONE
            }
        }
        runCatching { NotificationManagerCompat.from(context).cancel(AsrShareFlow.NOTIF_ID_PROGRESS) }
        if (done > 0) postResultNotification(context, done)
        if (failures.isNotEmpty()) {
            val activity = uiRef.get()
            if (AsrShareFlow.activityResumed && activity != null && !activity.isFinishing) {
                fireUi(activity) {
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.wxchat_progress_title)
                        .setMessage(context.getString(R.string.asr_failed_prefix) +
                            failures.joinToString("\n"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    /** 结果展示：链接已含提取码，可直接转发或交给 AI 助手阅读；追加的注明目标记录。 */
    private fun showResult(activity: MainActivity, results: List<Success>) {
        fireUi(activity) {
            if (activity.isFinishing) return@fireUi
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            val message = results.joinToString("\n\n") { s ->
                val expire = if (s.expiresAt > 0) fmt.format(Date(s.expiresAt * 1000))
                else activity.getString(R.string.wxchat_expire_fallback)
                if (s.appended) activity.getString(R.string.wxchat_result_appended_item,
                    s.displayName, s.url, expire)
                else activity.getString(R.string.wxchat_result_item, s.displayName, s.url, expire)
            }
            val firstUrl = results.first().url
            val shareText = results.joinToString("\n\n") {
                activity.getString(R.string.wxchat_share_text, it.displayName, it.url)
            }
            val allAppended = results.all { it.appended }
            AlertDialog.Builder(activity)
                .setTitle(if (allAppended) R.string.wxchat_result_appended_title
                          else R.string.wxchat_result_title)
                .setMessage(message)
                .setNeutralButton(R.string.wxchat_btn_copy) { _, _ ->
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("share", shareText))
                    Toast.makeText(activity, R.string.asr_share_copied, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(R.string.wxchat_btn_open) { _, _ ->
                    // 与设置页「结果」入口一致：独立任务打开（singleTask 复用会清掉
                    // 其上的设置页导致返回不回列表），返回键回到来源页
                    activity.startActivity(
                        Intent(activity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                            .setData(Uri.parse(firstUrl)))
                }
                .setPositiveButton(R.string.wxchat_btn_share) { _, _ ->
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, shareText)
                    activity.startActivity(Intent.createChooser(send,
                        activity.getString(R.string.wxchat_btn_share)))
                }
                .show()
        }
    }

    /** 进度上报：悬浮条必更新；系统通知仅在通知权限开启时发（该权限被拒则静默跳过）。 */
    private fun report(context: Context, stage: String, pct: Int, total: Int, ordinal: Int) {
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

    private fun postProgress(context: Context, text: String, pct: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 400 && pct != 100) return
        lastNotifyAt = now
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(context.getString(R.string.wxchat_notif_title))
            .setContentText(text)
            .setProgress(100, pct.coerceAtLeast(0), pct != 100)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(NOTIF_ID_RESULT - 1, notification) }
    }

    private fun postResultNotification(context: Context, succeeded: Int) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(context)
        val text = if (succeeded > 1) context.getString(R.string.wxchat_notif_done_multi, succeeded)
        else context.getString(R.string.wxchat_notif_done_one)
        val contentIntent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, NOTIF_CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_transcribe)
            .setContentTitle(context.getString(R.string.wxchat_notif_title))
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

    private fun describe(context: Context, item: AsrShareFlow.MediaItem, e: Exception): String {
        val name = AsrShareFlow.queryDisplayName(context.applicationContext, item.uri) ?: "聊天记录"
        return "$name：${e.message ?: e.toString()}"
    }

    /** 在 UI 线程执行但不等待（进度类更新用）。 */
    private fun fireUi(activity: MainActivity, block: () -> Unit) {
        if (activity.isFinishing) return
        activity.runOnUiThread {
            if (!activity.isFinishing) block()
        }
    }
}
