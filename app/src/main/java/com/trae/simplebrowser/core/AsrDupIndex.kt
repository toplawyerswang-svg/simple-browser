package com.trae.simplebrowser.core

/** 录音转写去重索引（纯逻辑，无 Android 依赖，便于 JVM 单测）。
 *  索引键 = 文件内容 MD5 + 识别选项标签：同内容同选项视为重复，直接复用服务端既有任务结果；
 *  换了识别选项（如补勾方言译普通话）算新键，照常重转。 */
object AsrDupIndex {

    /** 上限防无限膨胀：超出时淘汰最早写入的条目。 */
    const val MAX_ENTRIES = 500

    /** 键 = "md5|选项标签"；md5 为小写十六进制，选项标签不含分隔符。 */
    fun keyOf(md5: String, optionsTag: String): String = "$md5|$optionsTag"

    /** 索引文本格式：每行 `key\ttask_id\t写入时间戳毫秒`，解析保持文件顺序（LinkedHashMap）。 */
    fun parse(text: String): LinkedHashMap<String, Entry> {
        val map = LinkedHashMap<String, Entry>()
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue
            val ts = parts[2].toLongOrNull() ?: continue
            map[parts[0]] = Entry(taskId = parts[1], savedAt = ts)
        }
        return map
    }

    fun serialize(map: Map<String, Entry>): String =
        map.entries.joinToString("\n") { (k, e) -> "$k\t${e.taskId}\t${e.savedAt}" }

    /** 写入并按 MAX_ENTRIES 淘汰最早条目；返回淘汰后的新映射。 */
    fun put(map: LinkedHashMap<String, Entry>, key: String, taskId: String, savedAt: Long):
            LinkedHashMap<String, Entry> {
        val next = LinkedHashMap(map)
        next.remove(key)   // 同键覆盖视为新写入，排到最末
        next[key] = Entry(taskId, savedAt)
        while (next.size > MAX_ENTRIES) {
            val oldest = next.keys.first()
            next.remove(oldest)
        }
        return next
    }

    data class Entry(val taskId: String, val savedAt: Long)
}
