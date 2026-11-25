package com.trae.simplebrowser.core

/**
 * 开场文案「历史版本」的纯逻辑（2026-11-25 用户需求）。
 *
 * 需求：手机里写过开场文案后要能存下来，下次可以直接选用历史版本，且**按关键词能搜到**。
 * 用户补充口径：**还没输入关键词时，展开的历史必须是全量**（不是空列表、也不是只显示前几条）。
 *
 * 本模块只做数据规则与序列化（零 Android 依赖，JVM 单测直跑）；读写文件在
 * KickoffHistoryStore。序列化刻意手写「转义 + 制表符分隔 + 行」而不是用 org.json：
 * org.json 在 JVM 单测里是桩实现，用它会逼着把存储逻辑排除在回归之外。
 */
data class KickoffVersion(
    /** 稳定 id（保存时刻 + 文本哈希）：删除/标记使用都按它定位 */
    val id: String,
    val text: String,
    /** 保存时间（毫秒） */
    val savedAt: Long,
    /** 最近一次被「使用」的时间（0=没被用过） */
    val usedAt: Long = 0L,
    /** 保存当时的报告交付约定快照（只用于列表标签展示；选用时只回填文字） */
    val upload: Boolean = true,
    val evidenceBacked: Boolean = true,
    val owners: List<String> = emptyList()
)

object KickoffHistory {

    /** 本机最多保留多少条（超出按「最久未用」淘汰） */
    const val MAX_VERSIONS = 50

    /** 列表摘要长度（一行显示用；完整正文仍在 text 里） */
    const val SUMMARY_CHARS = 140

    /** 排序权重：用过看 usedAt，否则看 savedAt */
    fun recency(v: KickoffVersion): Long = if (v.usedAt > v.savedAt) v.usedAt else v.savedAt

    /** 按最近使用/保存倒序 */
    fun ordered(list: List<KickoffVersion>): List<KickoffVersion> =
        list.sortedByDescending { recency(it) }

    private fun idOf(text: String, now: Long): String =
        "$now-${text.hashCode().toUInt().toString(16)}"

    /**
     * 保存一版文案：同一段文字不重复保存（只把时间与约定快照刷新、并挪到最前），
     * 其余情况插到最前并裁到 [MAX_VERSIONS]。空白文本直接忽略。
     */
    fun upsert(
        list: List<KickoffVersion>,
        text: String,
        now: Long,
        upload: Boolean = true,
        evidenceBacked: Boolean = true,
        owners: List<String> = emptyList()
    ): List<KickoffVersion> {
        val body = text.trim()
        if (body.isEmpty()) return list
        val existing = list.firstOrNull { it.text.trim() == body }
        val entry = if (existing != null) {
            existing.copy(text = body, savedAt = now, usedAt = now,
                upload = upload, evidenceBacked = evidenceBacked, owners = owners)
        } else {
            KickoffVersion(idOf(body, now), body, now, now, upload, evidenceBacked, owners)
        }
        val rest = list.filterNot { it.id == entry.id }
        return (listOf(entry) + rest).take(MAX_VERSIONS)
    }

    /** 标记被选用（挪到最前） */
    fun markUsed(list: List<KickoffVersion>, id: String, now: Long): List<KickoffVersion> {
        val hit = list.firstOrNull { it.id == id } ?: return ordered(list)
        val updated = hit.copy(usedAt = now)
        return ordered(listOf(updated) + list.filterNot { it.id == id })
    }

    fun remove(list: List<KickoffVersion>, id: String): List<KickoffVersion> =
        list.filterNot { it.id == id }

    /**
     * 关键词过滤：**空关键词返回全量**（用户明确要求：输入前展示全部历史）。
     * 多关键词按空格切分、全部命中（AND）、忽略大小写；只匹配正文。
     */
    fun filter(list: List<KickoffVersion>, query: String): List<KickoffVersion> {
        val orderedList = ordered(list)
        val terms = query.trim().lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (terms.isEmpty()) return orderedList
        return orderedList.filter { v ->
            val body = v.text.lowercase()
            terms.all { body.contains(it) }
        }
    }

    /** 列表摘要：折叠换行与多余空白，过长截断加省略号 */
    fun summary(text: String, max: Int = SUMMARY_CHARS): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max).trimEnd() + "…"
    }

    /** 文字字数（按去掉首尾空白后的字符数，与输入框计数同口径） */
    fun charCount(text: String): Int = text.trim().length

    // ---------------- 序列化（制表符分隔 + 行，字段转义） ----------------

    private fun esc(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(c)
            }
        }
    }

    private fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> { append('\t'); i += 2 }
                    'n' -> { append('\n'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    else -> { append(c); i += 1 }
                }
            } else {
                append(c)
                i += 1
            }
        }
    }

    /** 单条编码：id \t savedAt \t usedAt \t upload(1/0) \t evidence(1/0) \t owners(、) \t text */
    private fun encode(v: KickoffVersion): String = listOf(
        esc(v.id),
        v.savedAt.toString(),
        v.usedAt.toString(),
        if (v.upload) "1" else "0",
        if (v.evidenceBacked) "1" else "0",
        esc(v.owners.joinToString("、")),
        esc(v.text)
    ).joinToString("\t")

    fun serialize(list: List<KickoffVersion>): String =
        list.joinToString("\n") { encode(it) }

    /**
     * 解析：坏行整行跳过（不因一行损坏丢掉整份历史），字段数不符的同样跳过。
     * 空文本的记录也跳过（历史里不该有空文案）。
     */
    fun parse(raw: String): List<KickoffVersion> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<KickoffVersion>()
        for (line in raw.split("\n")) {
            if (line.isBlank()) continue
            val f = line.split("\t")
            if (f.size < 7) continue
            val savedAt = f[1].toLongOrNull() ?: continue
            val usedAt = f[2].toLongOrNull() ?: 0L
            val text = unesc(f[6])
            if (text.isBlank()) continue
            val owners = unesc(f[5]).split("、").map { it.trim() }.filter { it.isNotEmpty() }
            out.add(
                KickoffVersion(
                    id = unesc(f[0]),
                    text = text,
                    savedAt = savedAt,
                    usedAt = usedAt,
                    upload = f[3] == "1",
                    evidenceBacked = f[4] == "1",
                    owners = owners
                )
            )
        }
        return ordered(out).take(MAX_VERSIONS)
    }
}