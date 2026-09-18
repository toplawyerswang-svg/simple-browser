package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 下载记录序列化与列表操作基线测试 */
class DownloadStoreLogicTest {

    private fun record(
        id: String = "r1",
        title: String = "标题",
        status: DownloadStatus = DownloadStatus.RUNNING,
        progress: Int = 0,
        detail: String = ""
    ) = DownloadRecord(
        id = id, kind = DownloadKind.HLS, title = title, fileName = "f.mp4",
        path = "Download/轻舟浏览器/f.mp4", uri = "content://x/1", status = status,
        progress = progress, detail = detail, createdAt = 1000L, finishedAt = 0L
    )

    @Test
    fun encodeDecodeRoundtrip_specialChars() {
        val rec = record(
            title = "My|Video\\Col:1\n第二行",
            detail = "10/24 片|进度"
        )
        val text = DownloadStoreLogic.encode(listOf(rec))
        val decoded = DownloadStoreLogic.decode(text)
        assertEquals(1, decoded.size)
        assertEquals(rec, decoded[0])
    }

    @Test
    fun encodeDecode_allStatusesAndKinds() {
        val records = listOf(
            record(id = "a", status = DownloadStatus.RUNNING, progress = 5),
            record(id = "b", status = DownloadStatus.DONE, progress = 100),
            record(id = "c", status = DownloadStatus.FAILED, progress = 40)
        )
        assertEquals(records, DownloadStoreLogic.decode(DownloadStoreLogic.encode(records)))
    }

    @Test
    fun decode_skipsMalformedLines() {
        val good = DownloadStoreLogic.encode(listOf(record()))
        val decoded = DownloadStoreLogic.decode("garbage\n$good\n|1|2")
        assertEquals(1, decoded.size)
    }

    @Test
    fun decode_emptyAndBlank() {
        assertEquals(0, DownloadStoreLogic.decode("").size)
        assertEquals(0, DownloadStoreLogic.decode("\n\n").size)
    }

    @Test
    fun upsert_replacesExistingAndPrependsNew() {
        val a = record(id = "a")
        val b = record(id = "b")
        val updated = record(id = "a", status = DownloadStatus.DONE, progress = 100)
        val list = DownloadStoreLogic.upsert(listOf(a, b), updated)
        assertEquals(listOf(updated, b), list)
    }

    @Test
    fun upsert_capsAtMax() {
        var list = emptyList<DownloadRecord>()
        repeat(60) { list = DownloadStoreLogic.upsert(list, record(id = "id$it")) }
        assertEquals(DownloadStoreLogic.MAX_RECORDS, list.size)
        assertEquals("id59", list[0].id) // 最新的在队首
    }

    @Test
    fun updateById_transformsOnlyTarget() {
        val list = listOf(record(id = "a", progress = 1), record(id = "b", progress = 2))
        val out = DownloadStoreLogic.update(list, "b") { it.copy(progress = 99) }
        assertEquals(1, out[0].progress)
        assertEquals(99, out[1].progress)
    }

    @Test
    fun updateById_missingKeepsList() {
        val list = listOf(record(id = "a"))
        assertTrue(DownloadStoreLogic.update(list, "zzz") { it.copy(progress = 9) } === list)
    }

    @Test
    fun removeById() {
        val list = listOf(record(id = "a"), record(id = "b"))
        assertEquals(listOf(record(id = "b")), DownloadStoreLogic.removeById(list, "a"))
    }

    @Test
    fun removeByIds_deletesMultiple() {
        val list = listOf(record(id = "a"), record(id = "b"), record(id = "c"))
        assertEquals(listOf(record(id = "b")), DownloadStoreLogic.removeByIds(list, setOf("a", "c")))
    }

    @Test
    fun removeByIds_emptySetKeepsList() {
        val list = listOf(record(id = "a"))
        assertEquals(list, DownloadStoreLogic.removeByIds(list, emptySet()))
    }

    @Test
    fun removeByIds_unknownIdsNoop() {
        val list = listOf(record(id = "a"))
        assertEquals(list, DownloadStoreLogic.removeByIds(list, setOf("zzz")))
    }

    @Test
    fun escape_allSpecials() {
        val s = "a|b\\c\nd"
        assertEquals("a\\pb\\\\c\\ld", DownloadStoreLogic.escape(s))
        assertEquals(s, DownloadStoreLogic.unescape(DownloadStoreLogic.escape(s)))
    }
}
