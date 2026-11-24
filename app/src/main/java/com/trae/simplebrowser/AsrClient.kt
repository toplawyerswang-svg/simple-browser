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
    // HTTPS 主通道走 api.orientlaw.cn；8800 HTTP 通道仅作服务器端备用，APP 不使用。
    // 公开常量：分享页 owner 注入需按此域判定（MainActivity.injectAsrOwnerJs）
    const val BASE_URL = "https://api.orientlaw.cn/api/asr"

    data class Options(
        val transcribe: Boolean,
        val timestamps: Boolean,
        val diarize: Boolean,
        val dialect2mandarin: Boolean
    ) {
        /** 去重索引用的稳定选项标签：同内容同选项才视为重复可复用。 */
        fun dedupTag(): String =
            "${if (transcribe) 1 else 0}${if (timestamps) 1 else 0}" +
                "${if (diarize) 1 else 0}${if (dialect2mandarin) 1 else 0}"

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
     *  participants 为记录中出现的微信昵称（转存后弹真名填写框用）；
     *  title 为服务端保存的记录名（新建=参与人真名+起止时间的默认名，旧服务端无此字段为 null）。
     *  addedMessages/skippedDuplicates 仅追加时有意义：本次新增条数与判重跳过条数。 */
    data class WxChatShare(
        val shareId: String,
        val pwd: String,
        val url: String,
        val validityHours: Int,
        val expiresAt: Long,
        val participants: List<String>,
        val title: String? = null,
        val addedMessages: Int = -1,
        val skippedDuplicates: Int = 0
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
            text("\r\n")
            // display_name 省略时不能再输出「\r\n--boundary\r\n」再接终止符——
            // 悬空的边界行会开出一个空 part，werkzeug 判多 part 非法返回 400（追加失败根因）
            if (displayName.isNullOrBlank()) {
                text("--$boundary--\r\n")
            } else {
                text("--$boundary\r\n")
                text("Content-Disposition: form-data; name=\"display_name\"\r\n")
                text("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                text(displayName.trim().replace(Regex("[\\p{Cntrl}]"), "").take(200))
                text("\r\n--$boundary--\r\n")
            }
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
            participants = participants,
            title = j.optString("title").trim().takeIf { it.isNotEmpty() },
            addedMessages = j.optInt("added_messages", -1),
            skippedDuplicates = j.optInt("skipped_duplicates", 0)
        )
    }

    /** 重命名聊天记录分享（分享页标题与 Agent JSON 的 title 同步生效）。
     *  keepAuto=true（真名回填生成的默认名）保留服务端 auto_title 标记，追加仍自动刷新；
     *  默认 false 视为用户手动定制，追加不再自动改名。 */
    fun renameWxChatShare(context: Context, shareId: String, title: String,
                          keepAuto: Boolean = false) {
        val conn = (URL("$BASE_URL/wxchat/$shareId/rename").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        val payload = JSONObject().put("title", title)
        if (keepAuto) payload.put("auto", true)
        conn.outputStream.use {
            it.write(payload.toString().toByteArray(Charsets.UTF_8))
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

    // ------------------------------------------------------------ 资料夹（录音+聊天记录跨类分组）

    data class FolderInfo(
        val folderId: String,
        val name: String,
        val itemCount: Int,
        val aliveCount: Int,
        val createdAt: Long,
        /** 成员明细（kind to ref）：APP 据此把已归档条目从根目录列表隐藏、挑选面板只列未归档。 */
        val items: List<Pair<String, String>> = emptyList(),
        /** 研究部绑定摘要（未配置/未接线时为 null；列表徽标用） */
        val yj: YanjiubuBinding? = null
    )

    /** 研究部侧的绑定视角（asrsvc 归一后的紧凑字段）。 */
    data class YanjiubuBinding(
        val yjFolderId: String,
        val yjFolderName: String,
        val agentName: String?,
        val agentChannel: String?,
        val agentModel: String?,
        val agentActive: Boolean
    )

    /** 研究部绑定状态（详情页「研究部绑定…」）。 */
    data class YanjiubuStatus(
        val configured: Boolean,
        val bound: Boolean,
        val binding: YanjiubuBinding?,
        /** 平台不可达时为错误文案（绑定状态未知，不等于未绑定） */
        val platformError: String?,
        /** 桥接未配置等服务端原因 */
        val error: String?
    )

    /** 一键绑定结果（agentState: exists=已就绪 / creating=后台创建中 / failed）。 */
    data class YanjiubuBindResult(
        val ok: Boolean,
        val alreadyBound: Boolean,
        val folderName: String?,
        val agentState: String?,
        val agentName: String?,
        /** 开场文字是否已排入研究部后台投递（用户没填时为 false） */
        val initialMessageScheduled: Boolean = false
    )

    /** 开场文字上限（与研究部消息正文上限、asrsvc 校验一致；超长由服务端拒绝，客户端提前拦） */
    const val YJ_INITIAL_MESSAGE_MAX_CHARS = 6000

    /** 文件夹成员：kind=task（录音转写）/ wxchat（聊天记录分享）；失效条目 alive=false。
     *  wxchat 条目附带 url（带提取码的分享链接，供点开预览），task 条目为空串。 */
    data class FolderEntry(
        val index: Int,
        val kind: String,
        val ref: String,
        val title: String,
        val meta: String,
        val alive: Boolean,
        val error: String?,
        val url: String
    )

    data class FolderDetail(val folderId: String, val name: String, val entries: List<FolderEntry>)

    private fun parseFolder(j: JSONObject): FolderInfo {
        val itemArr = j.optJSONArray("items")
        val members = ArrayList<Pair<String, String>>()
        if (itemArr != null) {
            for (i in 0 until itemArr.length()) {
                val o = itemArr.optJSONObject(i) ?: continue
                val kind = o.optString("kind")
                val ref = o.optString("ref")
                if (kind.isNotEmpty() && ref.isNotEmpty()) members.add(kind to ref)
            }
        }
        return FolderInfo(
            folderId = j.optString("folder_id"),
            name = j.optString("name", "未命名文件夹"),
            itemCount = j.optInt("item_count", 0),
            aliveCount = j.optInt("alive_count", 0),
            createdAt = j.optLong("created_at", 0L),
            items = members,
            yj = j.optJSONObject("yj")?.let(::parseYjBinding)
        )
    }

    /**
     * Android org.json 的坑（2026-11-23 生产实测「查询失败：null」根因）：
     * 服务端 JSON 里显式的 null，经 optString() 会返回字符串 "null"，不是空串——
     * 直接 ifEmpty 判定会把「一切正常」误判成「平台错误」。本助手把缺失/JSON null 统一为 null。
     */
    private fun optStringOrNull(j: JSONObject, key: String): String? {
        if (j.isNull(key)) return null
        return j.optString(key).ifEmpty { null }
    }

    /** 解析 asrsvc 归一后的研究部绑定摘要（字段缺失/JSON null 一律当 null，不猜测）。 */
    private fun parseYjBinding(j: JSONObject): YanjiubuBinding {
        val agent = j.optJSONObject("agent")
        return YanjiubuBinding(
            yjFolderId = j.optString("yj_folder_id"),
            yjFolderName = j.optString("yj_folder_name", "未命名文件夹"),
            agentName = agent?.let { optStringOrNull(it, "name") },
            agentChannel = agent?.let { optStringOrNull(it, "channel") },
            agentModel = agent?.let { optStringOrNull(it, "model") },
            agentActive = agent?.optBoolean("active", true) ?: true
        )
    }

    /** 研究部桥接接口的错误体是 {"error": "..."}——取出人话，别再让用户看整段 JSON。 */
    private fun checkCodeReadable(code: Int, body: String) {
        if (code == 401) throw AsrAuthException()
        if (code !in 200..299) {
            val msg = try {
                optStringOrNull(JSONObject(body), "error")
            } catch (_: Exception) {
                null
            }
            throw IllegalStateException(msg ?: "服务器返回 $code")
        }
    }

    /** 资料夹 ↔ 研究部绑定状态（强制刷新，详情页用）。 */
    fun folderYanjiubuStatus(context: Context, folderId: String): YanjiubuStatus {
        val conn = connection(context, "$BASE_URL/folders/$folderId/yanjiubu")
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCodeReadable(code, body)
        val j = JSONObject(body)
        return YanjiubuStatus(
            configured = j.optBoolean("configured", false),
            bound = j.optBoolean("bound", false),
            binding = j.optJSONObject("binding")?.let(::parseYjBinding),
            platformError = optStringOrNull(j, "platform_error"),
            error = optStringOrNull(j, "error")
        )
    }

    /**
     * 一键绑定：研究部建同名文件夹（重名复用）→ 绑定 → 建夹专属 Agent（无则建/有则复用）。
     * 同步段（建夹+绑定+源端校验）通常数秒；Agent 创建在平台后台完成（约 30 秒），
     * 返回 agentState=creating 时由界面轮询状态。
     *
     * initialMessage（2026-11-24 用户需求）：绑定弹窗里用户填的开场文字。服务端转发给研究部，
     * 研究部在 Agent 建好、默认技能确认安装后自动发给它（后台编排，不拖慢本接口）；
     * 空白视为不填（不发送）。
     */
    fun bindFolderYanjiubu(context: Context, folderId: String, initialMessage: String? = null): YanjiubuBindResult {
        val payload = JSONObject()
        val msg = initialMessage?.trim().orEmpty()
        if (msg.isNotEmpty()) payload.put("initial_message", msg)
        val conn = (URL("$BASE_URL/folders/$folderId/yanjiubu/bind").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCodeReadable(code, body)
        val j = JSONObject(body)
        val folder = j.optJSONObject("folder")
        val agent = j.optJSONObject("agent")
        return YanjiubuBindResult(
            ok = j.optBoolean("ok", false),
            alreadyBound = j.optBoolean("already_bound", false),
            folderName = folder?.let { optStringOrNull(it, "name") },
            agentState = agent?.let { optStringOrNull(it, "state") },
            agentName = agent?.optJSONObject("agent")?.let { optStringOrNull(it, "name") },
            initialMessageScheduled = j.optJSONObject("initial_message")?.optBoolean("scheduled", false) ?: false
        )
    }

    /** 我的文件夹列表。 */
    fun listFolders(context: Context): List<FolderInfo> {
        val conn = connection(context, "$BASE_URL/folders")
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        val arr = JSONObject(body).optJSONArray("folders") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parseFolder) }
    }

    /** 新建文件夹，返回 folder_id。 */
    fun createFolder(context: Context, name: String): String {
        val conn = (URL("$BASE_URL/folders").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use {
            it.write(JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        return JSONObject(body).getString("folder_id")
    }

    /** 文件夹详情（含成员明细）。 */
    fun folderDetail(context: Context, folderId: String): FolderDetail {
        val conn = connection(context, "$BASE_URL/folders/$folderId")
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        val j = JSONObject(body)
        val arr = j.optJSONArray("entries")
        val entries = ArrayList<FolderEntry>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                entries.add(FolderEntry(
                    index = o.optInt("index"),
                    kind = o.optString("kind"),
                    ref = o.optString("ref"),
                    title = o.optString("title", "未命名"),
                    meta = o.optString("meta"),
                    alive = o.optBoolean("alive", false),
                    error = optStringOrNull(o, "error"),
                    url = o.optString("url")
                ))
            }
        }
        return FolderDetail(
            folderId = j.optString("folder_id"),
            name = j.optString("name", "未命名文件夹"),
            entries = entries
        )
    }

    /** 重命名文件夹（已生成分享的标题同步生效）。 */
    fun renameFolder(context: Context, folderId: String, name: String) {
        val conn = (URL("$BASE_URL/folders/$folderId/rename").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use {
            it.write(JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
    }

    /** 删除文件夹（成员本体不受影响）。 */
    fun deleteFolder(context: Context, folderId: String) {
        val conn = (URL("$BASE_URL/folders/$folderId").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("X-LZ-Token", token(context))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
    }

    /** 批量加入文件夹（列表多选移入 / 单条移入 / 文件夹内挑选添加共用）。
     *  items 为 (kind, ref) 列表；返回 (added, skipped, failed) 三元组，failed 为失败条数。 */
    fun folderAddItems(context: Context, folderId: String, items: List<Pair<String, String>>,
                       copy: Boolean = false): Triple<Int, Int, Int> {
        val conn = (URL("$BASE_URL/folders/$folderId/items/batch").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        val arr = JSONArray()
        items.forEach { (kind, ref) ->
            arr.put(JSONObject().put("kind", kind).put("ref", ref))
        }
        val payloadJson = JSONObject().put("items", arr)
        if (copy) payloadJson.put("copy", true)   // 复制语义：保留其他夹归属（一条记录可同时在多个夹）
        conn.outputStream.use {
            it.write(payloadJson.toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
        val j = JSONObject(body)
        return Triple(j.optInt("added", 0), j.optInt("skipped", 0),
            (j.optJSONArray("failed") ?: JSONArray()).length())
    }

    /** 把条目移出文件夹（不删除成员本体）。 */
    fun folderRemoveItem(context: Context, folderId: String, kind: String, ref: String) {
        val conn = (URL("$BASE_URL/folders/$folderId/items/remove").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use {
            it.write(JSONObject().put("kind", kind).put("ref", ref).toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn, code)
        checkCode(code, body)
    }

    /** 为文件夹生成聚合分享链接（目录+逐条查看，接收方一个提取码贯通全部内容）。 */
    fun createFolderShare(context: Context, folderId: String, validityHours: Int, maxOpens: Int): ShareInfo {
        val conn = (URL("$BASE_URL/folders/$folderId/share").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("X-LZ-Token", token(context))
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use {
            it.write("validity_hours=$validityHours&max_opens=$maxOpens".toByteArray(Charsets.UTF_8))
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
