package com.trae.simplebrowser

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.trae.simplebrowser.core.SmsBackupLogic
import com.trae.simplebrowser.core.SmsMsg
import kotlin.concurrent.thread

/**
 * 12368 短信实时监听：系统短信到达广播（静态注册，进程被杀也能收到）。
 * 长短信多段 pdu 会在同一广播里按 originatingAddress 分组合并，时间取各段最新。
 * 只入队 + 触发冲刷，网络失败留在队列等下次；广播里不做任何耗时同步操作。
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!AppPrefs.isSmsBackupEnabled(context)) return

        val bodies = LinkedHashMap<String, StringBuilder>()
        val times = HashMap<String, Long>()
        for (m in Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
            val sender = m.originatingAddress ?: continue
            if (!SmsBackupLogic.isCourtSms(sender)) continue
            bodies.getOrPut(sender) { StringBuilder() }.append(m.displayMessageBody ?: "")
            val t = m.timestampMillis
            if (t > (times[sender] ?: 0L)) times[sender] = t
        }
        if (bodies.isEmpty()) return

        val list = bodies.map { (sender, body) ->
            SmsMsg(sender, body.toString(), times[sender] ?: System.currentTimeMillis())
        }
        val ctx = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                SmsBackupStore.get(ctx).enqueue(list)
                SmsBackupManager.flushPendingAsync(ctx)
            } finally {
                // goAsync 仅在真实广播派发时非空；直调 onReceive（测试）时为 null
                pending?.finish()
            }
        }
    }
}
