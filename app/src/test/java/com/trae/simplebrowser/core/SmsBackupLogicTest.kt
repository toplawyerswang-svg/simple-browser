package com.trae.simplebrowser.core

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsBackupLogicTest {

    // ------------------------------------------------------------ 号码匹配

    @Test
    fun courtSenderExactMatch() {
        assertTrue(SmsBackupLogic.isCourtSms("12368"))
    }

    @Test
    fun courtSenderToleratesSeparators() {
        assertTrue(SmsBackupLogic.isCourtSms(" 12368 "))
        assertTrue(SmsBackupLogic.isCourtSms("123-68"))
    }

    @Test
    fun nonCourtSendersRejected() {
        assertFalse(SmsBackupLogic.isCourtSms("1236"))
        assertFalse(SmsBackupLogic.isCourtSms("123680"))
        assertFalse(SmsBackupLogic.isCourtSms("8612368"))
        assertFalse(SmsBackupLogic.isCourtSms("+8612368"))
        assertFalse(SmsBackupLogic.isCourtSms("1069123456"))
        assertFalse(SmsBackupLogic.isCourtSms("10086"))
        assertFalse(SmsBackupLogic.isCourtSms(""))
        assertFalse(SmsBackupLogic.isCourtSms(null))
        assertFalse(SmsBackupLogic.isCourtSms("123x68"))
    }

    // ------------------------------------------------------------ 内容指纹

    @Test
    fun hashStableAndDistinct() {
        val a = SmsBackupLogic.contentHash("12368", "开庭传票", 1759651200000L)
        val b = SmsBackupLogic.contentHash("12368", "开庭传票", 1759651200000L)
        assertEquals(a, b)
        assertNotEquals(a, SmsBackupLogic.contentHash("12368", "开庭传票2", 1759651200000L))
        assertNotEquals(a, SmsBackupLogic.contentHash("12368", "开庭传票", 1759651200001L))
        assertNotEquals(a, SmsBackupLogic.contentHash("12367", "开庭传票", 1759651200000L))
    }

    @Test
    fun hashMatchesServerAlgorithm() {
        // 服务端 smsbox.py 的去重键 = sha1("{sender}|{sent_at}|{body}")，这里对齐验证
        val sender = "12368"
        val body = "【人民法院】您有一个案件已立案"
        val sentAt = 1759651200000L
        val expected = java.security.MessageDigest.getInstance("SHA-1")
            .digest("$sender|$sentAt|$body".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, SmsBackupLogic.contentHash(sender, body, sentAt))
    }

    // ------------------------------------------------------------ 报文组装与解析

    @Test
    fun ingestPayloadRoundTrip() {
        val msgs = listOf(
            SmsMsg("12368", "第一条", 1000L),
            SmsMsg("12368", "第二条：含\"引号\"与\n换行", 2000L)
        )
        val json = SmsBackupLogic.buildIngestPayload(msgs)
        val obj = org.json.JSONObject(json)
        val arr = obj.getJSONArray("messages")
        assertEquals(2, arr.length())
        assertEquals("12368", arr.getJSONObject(0).getString("sender"))
        assertEquals("第一条", arr.getJSONObject(0).getString("body"))
        assertEquals(1000L, arr.getJSONObject(0).getLong("sent_at"))
        assertEquals("第二条：含\"引号\"与\n换行", arr.getJSONObject(1).getString("body"))
    }

    @Test
    fun ingestPayloadEmptyList() {
        val obj = org.json.JSONObject(SmsBackupLogic.buildIngestPayload(emptyList()))
        assertEquals(0, obj.getJSONArray("messages").length())
    }

    @Test
    fun parseIngestResponse() {
        val res = SmsBackupLogic.parseIngestResponse(
            "{\"added\":2,\"duplicates\":1,\"total\":3}")
        assertEquals(2, res.added)
        assertEquals(1, res.duplicates)
        assertEquals(3, res.total)
    }

    @Test
    fun parseIngestResponseToleratesMissingFields() {
        val res = SmsBackupLogic.parseIngestResponse("{}")
        assertEquals(0, res.added)
        assertEquals(0, res.duplicates)
        assertEquals(0, res.total)
    }
}
