package com.trae.simplebrowser.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class SmsMsg(
    val sender: String,
    val body: String,
    val sentAt: Long
)

/** 12368 短信自动备份的纯逻辑：号码匹配、内容指纹、上存报文组装与解析（可直跑 JUnit）。 */
object SmsBackupLogic {

    /** 官方上存入口（ECS nginx 门面 → SER8 smsbox）。 */
    const val DEFAULT_BASE_URL = "https://api.orientlaw.cn/api/sms"

    /** 单次上存批量上限。 */
    const val BATCH_SIZE = 100

    /** 法院短信统一服务号。号码可能带空格/连字符等分隔符，比对前剔除；其余字符
     *  （如字母、国家码）一律视为不同号码，不做宽松归一以免误收。 */
    const val COURT_SENDER = "12368"

    private val SEPARATORS = Regex("[\\s\\-().\u2014\u2013]")

    fun isCourtSms(sender: String?): Boolean {
        if (sender.isNullOrBlank()) return false
        return sender.replace(SEPARATORS, "") == COURT_SENDER
    }

    /** 与服务端去重键同构：sha1(sender|sent_at|body)。 */
    fun contentHash(sender: String, body: String, sentAt: Long): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest("$sender|$sentAt|$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun buildIngestPayload(messages: List<SmsMsg>): String {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().apply {
                put("sender", m.sender)
                put("body", m.body)
                put("sent_at", m.sentAt)
            })
        }
        return JSONObject().put("messages", arr).toString()
    }

    data class IngestResult(val added: Int, val duplicates: Int, val total: Int)

    fun parseIngestResponse(json: String): IngestResult {
        val j = JSONObject(json)
        return IngestResult(
            added = j.optInt("added", 0),
            duplicates = j.optInt("duplicates", 0),
            total = j.optInt("total", 0)
        )
    }
}
