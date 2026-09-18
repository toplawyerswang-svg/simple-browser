package com.trae.simplebrowser.core

/** 下载条目类型 */
enum class DownloadKind { DIRECT, HLS }

/** 下载状态 */
enum class DownloadStatus { RUNNING, DONE, FAILED }

/** 一条视频下载记录（设置页"下载管理"展示用） */
data class DownloadRecord(
    val id: String,
    val kind: DownloadKind,
    val title: String,
    val fileName: String,
    val path: String,
    val uri: String,
    val status: DownloadStatus,
    /** 0..100；m3u8 按分片数折算，直链由 DownloadManager 查询 */
    val progress: Int,
    /** 附加说明（分片进度、失败原因等） */
    val detail: String,
    val createdAt: Long,
    val finishedAt: Long
)

/**
 * 下载记录的纯逻辑（序列化/合并/裁剪），可 JVM 单元测试。
 * 存储格式：每行一条记录，字段按 [FIELDS] 顺序以 | 分隔，字符串做转义。
 */
object DownloadStoreLogic {

    private const val FIELDS = 11
    const val MAX_RECORDS = 50

    fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\l")

    fun unescape(s: String): String =
        s.replace("\\l", "\n").replace("\\p", "|").replace("\\\\", "\\")

    fun encode(records: List<DownloadRecord>): String =
        records.joinToString("\n") { r ->
            listOf(
                escape(r.id),
                r.kind.name,
                escape(r.title),
                escape(r.fileName),
                escape(r.path),
                escape(r.uri),
                r.status.name,
                r.progress.toString(),
                escape(r.detail),
                r.createdAt.toString(),
                r.finishedAt.toString()
            ).joinToString("|")
        }

    fun decode(text: String): List<DownloadRecord> =
        text.lines().mapNotNull { line ->
            val f = line.split("|")
            if (f.size != FIELDS) return@mapNotNull null
            runCatching {
                DownloadRecord(
                    id = unescape(f[0]),
                    kind = DownloadKind.valueOf(f[1]),
                    title = unescape(f[2]),
                    fileName = unescape(f[3]),
                    path = unescape(f[4]),
                    uri = unescape(f[5]),
                    status = DownloadStatus.valueOf(f[6]),
                    progress = f[7].toIntOrNull()?.coerceIn(0, 100) ?: 0,
                    detail = unescape(f[8]),
                    createdAt = f[9].toLongOrNull() ?: 0L,
                    finishedAt = f[10].toLongOrNull() ?: 0L
                )
            }.getOrNull()
        }

    /** 按 id 更新或插入（新记录放队首，展示最新的在前） */
    fun upsert(records: List<DownloadRecord>, record: DownloadRecord): List<DownloadRecord> {
        val rest = records.filterNot { it.id == record.id }
        return (listOf(record) + rest).take(MAX_RECORDS)
    }

    /** 按 id 更新部分字段；找不到时原样返回同一实例 */
    fun update(
        records: List<DownloadRecord>,
        id: String,
        transform: (DownloadRecord) -> DownloadRecord
    ): List<DownloadRecord> {
        if (records.none { it.id == id }) return records
        return records.map { if (it.id == id) transform(it) else it }
    }

    fun removeById(records: List<DownloadRecord>, id: String): List<DownloadRecord> =
        records.filterNot { it.id == id }

    /** 批量删除（下载管理"批量管理"用）；ids 为空时原样返回 */
    fun removeByIds(records: List<DownloadRecord>, ids: Set<String>): List<DownloadRecord> =
        if (ids.isEmpty()) records else records.filterNot { it.id in ids }
}
