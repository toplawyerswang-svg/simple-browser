package com.trae.simplebrowser.core

/**
 * 绑定研究部弹窗「开场文字」的拼装（2026-11-25 用户需求）。
 *
 * 需求：在开场文字输入框下方增加三个「报告交付」选项，其结果**并入开场文字**一起发给
 * 文件夹专属 Agent——所以研究部平台零改动（Agent 用自己已装的递交技能执行）：
 *   ① 报告是否上传到研究部（默认：上传）
 *   ② 是否以「附信源报告」形态上传（默认：附信源；上传时的 `report_type:"附信源报告"` 由 Agent 声明）
 *   ③ 报告所有人（默认：王伟江、梁诗婉；另有补充框）
 * 所有人是 `report_owner_user_ids`（用户 id 数组），Agent 可用
 * `GET /api/agents-api/users?q=姓名` 把姓名换成 id —— 这条已实测（两人各命中一条）。
 *
 * 本模块刻意纯 Kotlin、零 Android 依赖，便于单测直跑。
 */
object YjKickoffText {

    /** 与 AsrClient.YJ_INITIAL_MESSAGE_MAX_CHARS / 研究部消息正文上限同口径 */
    const val MAX_CHARS = 6000

    const val DEFAULT_OWNER_A = "王伟江"
    const val DEFAULT_OWNER_B = "梁诗婉"

    /** 报告交付约定（UI 三项的取值快照） */
    data class ReportRequirement(
        /** 报告是否上传到研究部 */
        val upload: Boolean = true,
        /** 是否以「附信源报告」形态上传（upload=false 时无意义） */
        val evidenceBacked: Boolean = true,
        /** 报告所有人（按顺序、已去重）；空列表 = 未指定 */
        val owners: List<String> = listOf(DEFAULT_OWNER_A, DEFAULT_OWNER_B)
    )

    /** 姓名框分隔符：顿号/逗号/分号/斜杠/空白（中英文）都当分隔 */
    private val OWNER_SEPARATORS = Regex("[、,，;；/／\\s]+")

    /**
     * 把「其他人」输入框的内容拆成姓名列表：按分隔符切开、去首尾空白、丢空串、保序去重。
     * 只做机械拆分，不校验人是否在册（在册校验由 Agent 查 users 接口时由平台完成）。
     */
    fun parseOwnerNames(raw: CharSequence?): List<String> {
        val text = raw?.toString().orEmpty()
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        for (part in text.split(OWNER_SEPARATORS)) {
            val name = part.trim()
            if (name.isNotEmpty() && !out.contains(name)) out.add(name)
        }
        return out
    }

    /** 两位固定勾选 + 补充框 → 最终所有人列表（固定项在前，补充项按输入顺序，整体去重） */
    fun mergeOwners(pickA: Boolean, pickB: Boolean, extra: CharSequence?): List<String> {
        val out = ArrayList<String>()
        if (pickA) out.add(DEFAULT_OWNER_A)
        if (pickB) out.add(DEFAULT_OWNER_B)
        for (name in parseOwnerNames(extra)) {
            if (!out.contains(name)) out.add(name)
        }
        return out
    }

    /**
     * 生成「【报告交付约定】」段（不含用户自由文字）。
     * 不上传时只留一行说明——形态与所有人都不再相关，写出来只会干扰 Agent。
     */
    fun composeRequirement(req: ReportRequirement): String {
        val sb = StringBuilder("【报告交付约定】")
        if (!req.upload) {
            sb.append("\n- 报告无需上传到研究部：本次结果在会话内交付即可，不必在研究部建报告。")
            return sb.toString()
        }
        sb.append("\n- 报告上传到研究部：是")
        sb.append(
            if (req.evidenceBacked) {
                "\n- 报告形态：附信源报告（递交时在请求体注明 report_type:\"附信源报告\"；正文带注释与证据截图附件，" +
                    "并按平台附信源完整性校验把注释/附件引用闭合，校验不过会被拒收）"
            } else {
                "\n- 报告形态：普通报告（不声明 report_type，平台不做附信源引用校验）"
            }
        )
        val owners = req.owners.filter { it.isNotBlank() }
        sb.append(
            if (owners.isEmpty()) {
                "\n- 报告所有人：未指定（平台默认该报告对普通用户不可见；需要可见就请补指定）"
            } else {
                "\n- 报告所有人：${owners.joinToString("、")}（按姓名调 GET /api/agents-api/users 查出 user_id，" +
                    "填进 report_owner_user_ids 递交）"
            }
        )
        return sb.toString()
    }

    /**
     * 最终发给 Agent 的正文 = 用户自由文字（可空）+ 空行 + 约定段。
     * 超长返回 null（由界面提示用户精简，**不静默截断**——截断会把约定截掉一半）。
     */
    fun compose(userText: String, req: ReportRequirement): String? {
        val text = userText.trim()
        val requirement = composeRequirement(req)
        val composed = if (text.isEmpty()) requirement else "$text\n\n$requirement"
        return if (composed.length > MAX_CHARS) null else composed
    }
}