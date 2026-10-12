package com.trae.simplebrowser

import android.content.Context
import com.trae.simplebrowser.core.AsrTranscripts
import org.json.JSONArray
import org.json.JSONObject

/** core 渲染层的 JSON 适配：把服务端 result/options 转成纯数据入参。
 *  详情页复制传显式开关；列表批量汇总传 getTask 的 options（缺失时按内容推断）。 */
object AsrTranscriptsJson {

    fun segments(arr: JSONArray?): List<AsrTranscripts.Seg> {
        if (arr == null) return emptyList()
        val list = ArrayList<AsrTranscripts.Seg>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(AsrTranscripts.Seg(
                speaker = o.optInt("speaker", 0),
                begin = o.optString("begin"),
                end = o.optString("end"),
                text = o.optString("text")
            ))
        }
        return list
    }

    fun speakerNames(obj: JSONObject?): Map<Int, AsrTranscripts.SpeakerMeta> {
        if (obj == null) return emptyMap()
        val map = HashMap<Int, AsrTranscripts.SpeakerMeta>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val id = k.toIntOrNull() ?: continue
            val o = obj.optJSONObject(k) ?: continue
            map[id] = AsrTranscripts.SpeakerMeta(
                name = o.optString("name").takeIf { it.isNotEmpty() },
                gender = o.optString("gender")
            )
        }
        return map
    }

    /** 详情页路径：调用方持有显式识别开关（勾选了时间戳/区分说话人、当前展示原文还是译文）。 */
    fun plainText(context: Context, result: JSONObject, hasTimestamps: Boolean,
                  hasDiarize: Boolean, preferMandarin: Boolean): String =
        AsrTranscripts.render(
            segments((if (preferMandarin) result.optJSONArray("mandarin_segments") else null)
                ?: result.optJSONArray("segments")),
            speakerNames(result.optJSONObject("speaker_names")),
            hasTimestamps, hasDiarize
        ) { n -> context.getString(R.string.asr_speaker_label, n) }

    /** 批量路径：任务选项取自 getTask 返回的 options；缺失时按内容推断。 */
    fun plainText(context: Context, result: JSONObject, options: JSONObject?): String =
        plainText(context, result,
            hasTimestamps = options?.optBoolean("timestamps") ?: inferTimestamps(result),
            hasDiarize = options?.optBoolean("diarize") ?: inferDiarize(result),
            preferMandarin = result.optJSONArray("mandarin_segments") != null)

    /** 无显式开关时：任一段落带起止时间即认为含时间戳。 */
    fun inferTimestamps(result: JSONObject): Boolean {
        val segs = result.optJSONArray("segments") ?: return false
        for (i in 0 until segs.length()) {
            val o = segs.optJSONObject(i) ?: continue
            if (o.optString("begin").isNotEmpty() || o.optString("end").isNotEmpty()) return true
        }
        return false
    }

    /** 无显式开关时：任一段落 speaker≠0 或说话人表里有具名条目，即认为区分了说话人。 */
    fun inferDiarize(result: JSONObject): Boolean {
        val segs = result.optJSONArray("segments") ?: return false
        for (i in 0 until segs.length()) {
            if ((segs.optJSONObject(i)?.optInt("speaker", 0) ?: 0) != 0) return true
        }
        val names = result.optJSONObject("speaker_names") ?: return false
        val keys = names.keys()
        while (keys.hasNext()) {
            if (names.optJSONObject(keys.next())?.optString("name").orEmpty().isNotEmpty()) return true
        }
        return false
    }
}
