package com.trae.simplebrowser

import android.content.Context
import android.util.Log
import com.trae.simplebrowser.core.AsrDupIndex
import java.io.File

/** 录音转写去重的本地持久层：filesDir 下 TSV 索引文件，读写全程加锁。
 *  单批次在服务工作线程顺序推进，并发批次极少见，锁只保证不写坏。 */
object AsrDupStore {
    private const val TAG = "AsrDupStore"
    private const val FILE_NAME = "asr_dup_index.tsv"
    private val lock = Any()

    /** 查已转写任务的 task_id；没有返回 null。 */
    fun findTaskId(context: Context, md5: String, optionsTag: String): String? {
        synchronized(lock) {
            val map = AsrDupIndex.parse(read(context))
            return map[AsrDupIndex.keyOf(md5, optionsTag)]?.taskId
        }
    }

    /** 转写成功后记入索引（覆盖同键旧值，超出上限自动淘汰最早条目）。 */
    fun remember(context: Context, md5: String, optionsTag: String, taskId: String) {
        synchronized(lock) {
            val map = AsrDupIndex.put(AsrDupIndex.parse(read(context)),
                AsrDupIndex.keyOf(md5, optionsTag), taskId, System.currentTimeMillis())
            runCatching {
                File(context.filesDir, FILE_NAME).writeText(AsrDupIndex.serialize(map), Charsets.UTF_8)
            }.onFailure { Log.w(TAG, "dup index write failed", it) }
        }
    }

    private fun read(context: Context): String =
        runCatching { File(context.filesDir, FILE_NAME).readText() }.getOrDefault("")
}
