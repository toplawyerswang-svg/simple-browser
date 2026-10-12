package com.trae.simplebrowser.core

/** 转写结果 → 纯文本的统一渲染：详情页「复制全文」与列表多选「批量复制/分享」共用，
 *  保证单篇与批量的文字格式完全一致。纯 Kotlin 无 Android 依赖，便于 JVM 单测。 */
object AsrTranscripts {

    data class Seg(val speaker: Int, val begin: String, val end: String, val text: String)

    data class SpeakerMeta(val name: String?, val gender: String)

    /** 段落行格式：[说话人][起止时间]正文，与详情页逐段展示一致；未区分说话人时不带说话人前缀。 */
    fun render(
        segments: List<Seg>,
        speakerNames: Map<Int, SpeakerMeta>,
        hasTimestamps: Boolean,
        hasDiarize: Boolean,
        fallbackSpeaker: (ordinal: Int) -> String
    ): String {
        // 未具名说话人按首次出现顺序编号（1 起），同一说话人全程同号
        val fallbackOrder = LinkedHashMap<Int, String>()
        val sb = StringBuilder()
        for (seg in segments) {
            if (hasDiarize) {
                val meta = speakerNames[seg.speaker]
                val base = meta?.name?.takeIf { it.isNotEmpty() }
                    ?: fallbackOrder.getOrPut(seg.speaker) { fallbackSpeaker(fallbackOrder.size + 1) }
                val gender = meta?.gender.orEmpty()
                sb.append('[').append(if (gender.isNotEmpty()) "$base（$gender）" else base).append(']')
            }
            if (hasTimestamps) sb.append('[').append(seg.begin).append('-').append(seg.end).append(']')
            sb.append(seg.text).append('\n')
        }
        return sb.toString().trim()
    }

    /** 多篇转写拼篇：每篇以【文件名】抬头，篇间空行分隔（批量复制与系统分享共用同一格式）。 */
    fun joinDocuments(docs: List<Pair<String, String>>): String =
        docs.joinToString("\n\n") { "【${it.first}】\n${it.second}" }
}
