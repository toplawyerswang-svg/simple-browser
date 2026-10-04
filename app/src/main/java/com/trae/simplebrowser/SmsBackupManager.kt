package com.trae.simplebrowser

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.trae.simplebrowser.core.SmsBackupLogic
import com.trae.simplebrowser.core.SmsMsg
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 令牌无效或未设置（服务端401）。 */
class SmsAuthException : Exception("上传令牌无效或未设置")

/**
 * 12368 短信自动备份管理：上存队列冲刷、历史回扫、令牌核验。
 * 网络调用均为阻塞式（调用方负责放到线程里），HTTP 风格与 AsrClient 一致。
 */
object SmsBackupManager {

    data class BackfillResult(
        val matched: Int,      // 手机里的 12368 短信总条数
        val uploaded: Int,     // 本次实际上存新增
        val duplicates: Int    // 服务端判重条数
    )

    fun baseUrl(context: Context): String =
        AppPrefs.getSmsBaseUrl(context).ifBlank { SmsBackupLogic.DEFAULT_BASE_URL }

    private fun token(context: Context): String = AppPrefs.getSmsToken(context)

    fun hasPermissions(context: Context): Boolean {
        val ctx = context.applicationContext
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** 核验令牌，成功返回服务器库内总条数。 */
    fun ping(context: Context, base: String, token: String): Int {
        val conn = connection("$base/ping", token)
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body).optInt("total", 0)
    }

    /**
     * 把队列里的短信批量上存，全部成功返回 null，中断返回错误说明。
     * force=true 供「立即上存」按钮手动触发（总开关关闭时也允许把存着的短信送出去）。
     */
    fun flushPending(context: Context, force: Boolean = false): String? {
        if (!force && !AppPrefs.isSmsBackupEnabled(context)) return null
        val store = SmsBackupStore.get(context)
        if (store.pendingCount() == 0) return null
        val tok = token(context)
        if (tok.isBlank()) return "上传令牌未设置，短信已暂存待传"
        val base = baseUrl(context)
        while (true) {
            val batch = store.pendingSnapshot(SmsBackupLogic.BATCH_SIZE)
            if (batch.isEmpty()) return null
            try {
                postIngest(base, tok, batch.map { it.msg })
                store.markSentAndRemovePending(
                    batch,
                    base
                )
            } catch (e: SmsAuthException) {
                return "上传令牌无效，短信已暂存待传"
            } catch (e: IOException) {
                return "网络不可用，短信已暂存待传"
            } catch (e: IllegalStateException) {
                return e.message
            }
            if (batch.size < SmsBackupLogic.BATCH_SIZE) return null
        }
    }

    /** 异步冲刷（收新短信 / 打开 APP / 打开设置页时调用），同一时刻只跑一份。 */
    fun flushPendingAsync(context: Context) {
        val app = context.applicationContext
        if (!AppPrefs.isSmsBackupEnabled(app)) return
        synchronized(this) {
            if (flushing) return
            flushing = true
        }
        Thread {
            try {
                flushPending(app)
            } catch (e: Exception) {
                // 队列冲刷失败不打扰用户，短信留在 pending 下次再试
            } finally {
                synchronized(this) { flushing = false }
            }
        }.start()
    }

    /**
     * 回扫手机收件箱里全部 12368 短信并上存（跳过本机已传记录；服务端另有判重兜底）。
     * onProgress(scanned, uploaded) 在每个批次完成后回调。
     */
    fun backfill(
        context: Context,
        onProgress: (scanned: Int, uploaded: Int) -> Unit
    ): BackfillResult {
        val ctx = context.applicationContext
        if (!hasPermissions(ctx)) {
            throw IllegalStateException("缺少短信读取权限")
        }
        val base = baseUrl(ctx)
        val tok = token(ctx)
        if (tok.isBlank()) throw SmsAuthException()
        val store = SmsBackupStore.get(ctx)

        val matched = ArrayList<SmsMsg>()
        val sentSet = store.sentHashes(base)
        val projection = arrayOf(
            Telephony.Sms.Inbox.ADDRESS,
            Telephony.Sms.Inbox.BODY,
            Telephony.Sms.Inbox.DATE,
            Telephony.Sms.Inbox.DATE_SENT
        )
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, null, null,
            "${Telephony.Sms.Inbox.DATE} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val sender = c.getString(0) ?: continue
                if (!SmsBackupLogic.isCourtSms(sender)) continue
                val msg = SmsMsg(
                    sender = sender,
                    body = c.getString(1) ?: "",
                    sentAt = (c.getLong(3).takeIf { it > 0 } ?: c.getLong(2))
                )
                val h = SmsBackupLogic.contentHash(msg.sender, msg.body, msg.sentAt)
                if (h in sentSet) continue
                sentSet.add(h)
                matched.add(msg)
            }
        }

        var uploaded = 0
        var duplicates = 0
        for (chunk in matched.chunked(SmsBackupLogic.BATCH_SIZE)) {
            val res = postIngest(base, tok, chunk)
            uploaded += res.added
            duplicates += res.duplicates
            store.recordSent(
                chunk.map { SmsBackupLogic.contentHash(it.sender, it.body, it.sentAt) },
                base
            )
            onProgress(matched.size, uploaded)
        }
        return BackfillResult(matched.size, uploaded, duplicates)
    }

    // ------------------------------------------------------------ HTTP

    @Volatile
    private var flushing = false

    private fun postIngest(base: String, token: String, messages: List<SmsMsg>): SmsBackupLogic.IngestResult {
        val conn = (URL("$base/ingest").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("X-LZ-Token", token)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use {
            it.write(SmsBackupLogic.buildIngestPayload(messages).toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return SmsBackupLogic.parseIngestResponse(body)
    }

    private fun connection(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("X-LZ-Token", token)
        }

    private fun checkCode(code: Int, body: String) {
        if (code == 401) throw SmsAuthException()
        if (code !in 200..299) throw IllegalStateException("服务器返回 $code: ${body.take(200)}")
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }
}
