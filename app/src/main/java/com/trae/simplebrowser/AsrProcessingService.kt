package com.trae.simplebrowser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder

/** 转写处理前台服务：分享的音视频转写、微信聊天记录转存都放在 dataSync 前台服务里跑。
 *  应用切后台后系统会掐断普通进程的网络（DNS 解析失败）并可能冻结进程，
 *  前台服务可保住网络与运行状态，处理不因用户离开而中断。 */
class AsrProcessingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uris = intent?.getStringArrayListExtra(EXTRA_URIS).orEmpty()
        val mimes = intent?.getStringArrayListExtra(EXTRA_MIMES).orEmpty()
        val isWxChat = intent?.getBooleanExtra(EXTRA_WXCHAT, false) == true
        if (uris.isEmpty() || (!isWxChat && intent?.getStringExtra(EXTRA_OPTS).isNullOrEmpty())) {
            stopSelf()
            return START_NOT_STICKY
        }
        AsrShareFlow.startForeground(this)
        val items = uris.zip(mimes) { u, m -> AsrShareFlow.MediaItem(Uri.parse(u), m) }
        val optsJson = intent?.getStringExtra(EXTRA_OPTS)
        val appendTo = intent?.getStringExtra(EXTRA_WXCHAT_APPEND)
        val appendTitle = intent?.getStringExtra(EXTRA_WXCHAT_APPEND_TITLE)
        Thread {
            try {
                if (isWxChat) {
                    WxChatShareFlow.runBatch(this, items, appendTo, appendTitle)
                } else {
                    val opts = AsrClient.Options.fromJson(optsJson ?: "{}")
                    AsrShareFlow.runBatch(this, items, opts)
                }
            } finally {
                AsrShareFlow.stopForeground(this)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_MIMES = "mimes"
        private const val EXTRA_OPTS = "opts"
        private const val EXTRA_WXCHAT = "wxchat"
        private const val EXTRA_WXCHAT_APPEND = "wxchat_append"
        private const val EXTRA_WXCHAT_APPEND_TITLE = "wxchat_append_title"

        /** 从处理选项页启动（用户点了「开始处理」，属前台启动，不受后台启动限制）。 */
        fun start(context: Context, items: List<AsrShareFlow.MediaItem>, opts: AsrClient.Options) {
            val it = Intent(context, AsrProcessingService::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri.toString() }))
                .putStringArrayListExtra(EXTRA_MIMES, ArrayList(items.map { it.mime }))
                .putExtra(EXTRA_OPTS, opts.toJson())
            context.startForegroundService(it)
        }

        /** 微信聊天记录转存：令牌核验通过后启动，无选项页。
         *  appendTo 非空=追加进已有记录（appendTitle 为其展示名，供结果弹窗显示）。 */
        fun startWxChat(context: Context, items: List<AsrShareFlow.MediaItem>,
                        appendTo: String? = null, appendTitle: String? = null) {
            val it = Intent(context, AsrProcessingService::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri.toString() }))
                .putStringArrayListExtra(EXTRA_MIMES, ArrayList(items.map { it.mime }))
                .putExtra(EXTRA_WXCHAT, true)
            if (appendTo != null) {
                it.putExtra(EXTRA_WXCHAT_APPEND, appendTo)
                it.putExtra(EXTRA_WXCHAT_APPEND_TITLE, appendTitle ?: "")
            }
            context.startForegroundService(it)
        }
    }
}
