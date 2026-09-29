package com.trae.simplebrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 令牌无效或已失效（服务端401）。 */
class AsrAuthException : Exception("令牌无效或已失效，请重新输入")

/** 轻舟录音转写服务客户端。所有接口按用户令牌鉴权，任务按令牌身份隔离。 */
object AsrClient {
    // HTTPS 主通道走 api.orientlaw.cn；8800 HTTP 通道仅作服务器端备用，APP 不使用
    private const val BASE_URL = "https://api.orientlaw.cn/api/asr"

    data class Options(
        val transcribe: Boolean,
        val timestamps: Boolean,
        val diarize: Boolean,
        val dialect2mandarin: Boolean
    ) {
        fun toJson(): String = JSONObject().apply {
            put("transcribe", transcribe)
            put("timestamps", timestamps)
            put("diarize", diarize)
            put("dialect2mandarin", dialect2mandarin)
        }.toString()

        companion object {
            fun fromJson(json: String): Options = JSONObject(json).let {
                Options(
                    transcribe = it.optBoolean("transcribe", true),
                    timestamps = it.optBoolean("timestamps", true),
                    diarize = it.optBoolean("diarize", true),
                    dialect2mandarin = it.optBoolean("dialect2mandarin", false)
                )
            }
        }
    }

    data class ShareInfo(
        val shareId: String,
        val pwd: String,
        val url: String,
        val validityHours: Int,
        val maxOpens: Int
    )

    /** 校验令牌是否有效，成功返回对应用户身份名。 */
    fun verifyToken(context: Context, token: String): String {
        val j = postJson(context, "$BASE_URL/auth/verify", token, JSONObject())
        return j.optString("user")
    }

    /** 上传音频创建任务，返回 task_id；onProgress 回调上传进度 0..100。
     *  displayName 为分享文件的原始名：服务端任务列表展示用它，不传则退回上传文件名。 */
    fun createTask(context: Context, audio: File, opts: Options, displayName: String? = null,
                   onProgress: (Int) -> Unit): String {
        val token = token(context)
        val boundary = "lzbr" + System.currentTimeMillis()
        val conn = (URL("$BASE_URL/task").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 600000
            doOutput = true
            setRequestProperty("X-LZ-Token", token)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        val total = audio.length()
        conn.outputStream.use { os ->
            fun text(s: String) = os.write(s.toByteArray(Charsets.UTF_8))
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"audio\"; filename=\"${audio.name}\"\r\n")
            text("Content-Type: application/octet-stream\r\n\r\n")
            audio.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                var lastPct = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    sent += n
                    if (total > 0) {
                        val pct = (sent * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            text("\r\n--$boundary\r\n")
            // 原始文件名单独走表单字段：上传临时名无意义，扩展名才供服务端识别格式
            if (!displayName.isNullOrBlank()) {
                text("Content-Disposition: form-data; name=\"display_name\"\r\n")
                text("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                text(displayName.trim().replace(Regex("[\\p{Cntrl}]"), "").take(200))
                text("\r\n")
            }
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"options\"\r\n")
            text("Content-Type: application/json\r\n\r\n")
            text(opts.toJson())
            text("\r\n--$boundary--\r\n")
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        if (code == 401) throw AsrAuthException()
        if (code !in 200..299) throw IllegalStateException("服务器返回 $code: ${body.take(200)}")
        return JSONObject(body).getString("task_id")
    }

    /** 查询任务状态与结果。 */
    fun getTask(context: Context, taskId: String): JSONObject {
        val conn = connection(context, "$BASE_URL/task/$taskId")
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body)
    }

    /** 我的历史任务列表。 */
    fun listTasks(context: Context): JSONArray {
        val conn = connection(context, "$BASE_URL/tasks")
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body).optJSONArray("tasks") ?: JSONArray()
    }

    /** 删除我的任务（含音频与已生成分享）。 */
    fun deleteTask(context: Context, taskId: String) {
        val conn = (URL("$BASE_URL/task/$taskId").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("X-LZ-Token", token(context))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
    }

    /** 为已完成的任务生成带提取码的分享链接；allowSegmentPlay/showSpeakerNames 控制分享页展示要求。 */
    fun createShare(context: Context, taskId: String, validityHours: Int, maxOpens: Int,
                    allowSegmentPlay: Boolean = true, showSpeakerNames: Boolean = true): ShareInfo {
        val conn = (URL("$BASE_URL/task/$taskId/share").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { os ->
            os.write(("validity_hours=$validityHours&max_opens=$maxOpens" +
                "&allow_segment_play=${if (allowSegmentPlay) 1 else 0}" +
                "&show_speaker_names=${if (showSpeakerNames) 1 else 0}").toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        val j = JSONObject(body)
        return ShareInfo(
            shareId = j.getString("share_id"),
            pwd = j.getString("pwd"),
            url = j.getString("url"),
            validityHours = j.optInt("validity_hours", validityHours),
            maxOpens = j.optInt("max_opens", maxOpens)
        )
    }

    /** 给说话人标注名字（服务器并入声纹库），返回更新后的 result。 */
    fun labelSpeaker(context: Context, taskId: String, speaker: Int, name: String, gender: String): JSONObject {
        val conn = (URL("$BASE_URL/task/$taskId/label").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { os ->
            os.write(JSONObject().apply {
                put("speaker", speaker)
                put("name", name)
                put("gender", gender)
            }.toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body).getJSONObject("result")
    }

    /** 声纹库中已有人名（用于标注时的快捷选择）。 */
    fun listVoiceprints(context: Context): List<String> {        val conn = connection(context, "$BASE_URL/voiceprints")
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        val arr = JSONObject(body).optJSONArray("voiceprints")
        val names = ArrayList<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i).optString("name")
                if (n.isNotEmpty()) names.add(n)
            }
        }
        return names
    }

    /** 按需生成（或返回已有）AI 摘要与待办，返回更新后的 result。同步调用可能耗时几十秒。 */
    fun generateSummary(context: Context, taskId: String): JSONObject {
        val conn = (URL("$BASE_URL/task/$taskId/summary").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 300000
            doOutput = true
            setRequestProperty("X-LZ-Token", AppPrefs.getAsrToken(context))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body).getJSONObject("result")
    }

    /** 音频播放地址：整段流播（不带a/b）或指定毫秒区间切片（点段落回听）。令牌走查询参数。 */
    fun taskAudioUrl(context: Context, taskId: String, aMs: Long = -1L, bMs: Long = -1L): String {
        val t = java.net.URLEncoder.encode(AppPrefs.getAsrToken(context), "UTF-8")
        val base = "$BASE_URL/task/$taskId/audio"
        return if (aMs >= 0 && bMs > aMs) "$base?a=$aMs&b=$bMs&token=$t" else "$base?token=$t"
    }

    /** 微信聊天记录转存分享：服务端解析 zip 后直接返回分享链接（30 天有效）。
     *  participants 为记录中出现的微信昵称（转存后弹真名填写框用）。 */
    data class WxChatShare(
        val shareId: String,
        val pwd: String,
        val url: String,
        val validityHours: Int,
        val expiresAt: Long,
        val participants: List<String>
    )

    /** 上传微信聊天记录 zip 建分享，onProgress 回调上传进度 0..100。 */
    fun uploadWxChat(context: Context, zip: File, displayName: String?,
                     onProgress: (Int) -> Unit): WxChatShare =
        postWxChatZip(context, "wxchat", zip, displayName, onProgress)

    /** 向已有聊天记录分享追加 zip：服务端按时间归并消息，分享链接与提取码不变。 */
    fun appendWxChat(context: Context, shareId: String, zip: File,
                     onProgress: (Int) -> Unit): WxChatShare =
        postWxChatZip(context, "wxchat/$shareId/append", zip, null, onProgress)

    private fun postWxChatZip(context: Context, path: String, zip: File,
                              displayName: String?, onProgress: (Int) -> Unit): WxChatShare {
        val token = token(context)
        val boundary = "lzbr" + System.currentTimeMillis()
        val conn = (URL("$BASE_URL/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 600000
            doOutput = true
            setRequestProperty("X-LZ-Token", token)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        val total = zip.length()
        conn.outputStream.use { os ->
            fun text(s: String) = os.write(s.toByteArray(Charsets.UTF_8))
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"file\"; filename=\"${zip.name}\"\r\n")
            text("Content-Type: application/zip\r\n\r\n")
            zip.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                var lastPct = -1
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    sent += n
                    if (total > 0) {
                        val pct = (sent * 100 / total).toInt()
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            text("\r\n--$boundary\r\n")
            if (!displayName.isNullOrBlank()) {
                text("Content-Disposition: form-data; name=\"display_name\"\r\n")
                text("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                text(displayName.trim().replace(Regex("[\\p{Cntrl}]"), "").take(200))
                text("\r\n")
            }
            text("--$boundary--\r\n")
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        if (code == 401) throw AsrAuthException()
        if (code !in 200..299) throw IllegalStateException("服务器返回 $code: ${body.take(200)}")
        val j = JSONObject(body)
        val participants = ArrayList<String>()
        val pArr = j.optJSONArray("participants")
        if (pArr != null) {
            for (i in 0 until pArr.length()) {
                val n = pArr.optString(i)
                if (n.isNotEmpty()) participants.add(n)
            }
        }
        return WxChatShare(
            shareId = j.getString("share_id"),
            pwd = j.getString("pwd"),
            url = j.getString("url"),
            validityHours = j.optInt("validity_hours", 720),
            expiresAt = j.optLong("expires_at", 0L),
            participants = participants
        )
    }

    /** 重命名聊天记录分享（分享页标题与 Agent JSON 的 title 同步生效）。 */
    fun renameWxChatShare(context: Context, shareId: String, title: String) {
        val conn = (URL("$BASE_URL/wxchat/$shareId/rename").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use {
            it.write(JSONObject().put("title", title).toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
    }

    /** 批量设置昵称→真名并刷新指定分享的显示名快照（转存后填名的保存通道）。
     *  空真名项由调用方过滤；服务端对 share 做 owner 校验。 */
    fun bulkSetWxNames(context: Context, mapping: Map<String, String>, shareId: String?) {
        val conn = (URL("$BASE_URL/wxchat/names").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        val payload = JSONObject().apply {
            put("mapping", JSONObject(mapping))
            if (!shareId.isNullOrBlank()) put("share_id", shareId)
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
    }

    // ------------------------------------------------------------ 微信聊天记录转存分享管理

    /** 我的聊天记录转存分享列表（设置页管理入口）。 */
    fun listWxChatShares(context: Context): JSONArray {
        val conn = connection(context, "$BASE_URL/wxchat/list")
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body).optJSONArray("shares") ?: JSONArray()
    }

    /** 删除我的聊天记录分享（含服务器上的附件）。 */
    fun deleteWxChatShare(context: Context, shareId: String) {
        val conn = (URL("$BASE_URL/wxchat/$shareId").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("X-LZ-Token", token(context))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
    }

    /** 微信昵称→真名映射与历史分享中出现过的全部昵称。names: {昵称:真名}，known: 昵称数组。 */
    fun listWxNameMappings(context: Context): JSONObject {
        val conn = connection(context, "$BASE_URL/wxchat/names")
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body)
    }

    /** 设置（realname 空=清除）微信昵称的真名映射；markSelf=true 标记为机主昵称，
     *  false 取消标记，null 不变。实时作用于所有分享展示。 */
    fun setWxNameMapping(context: Context, nickname: String, realname: String,
                         markSelf: Boolean? = null) {
        val conn = (URL("$BASE_URL/wxchat/name").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { os ->
            val body = buildString {
                append("nickname=").append(java.net.URLEncoder.encode(nickname, "UTF-8"))
                append("&realname=").append(java.net.URLEncoder.encode(realname, "UTF-8"))
                if (markSelf != null) append("&self=").append(if (markSelf) "1" else "0")
            }
            os.write(body.toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
    }

    // ------------------------------------------------------------ 内部

    private fun token(context: Context): String = AppPrefs.getAsrToken(context)

    private fun connection(context: Context, url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("X-LZ-Token", token(context))
        }

    private fun postJson(context: Context, url: String, token: String, payload: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token)
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body)
    }

    private fun checkCode(code: Int, body: String) {
        if (code == 401) throw AsrAuthException()
        if (code !in 200..299) throw IllegalStateException("服务器返回 $code: ${body.take(200)}")
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }
}
