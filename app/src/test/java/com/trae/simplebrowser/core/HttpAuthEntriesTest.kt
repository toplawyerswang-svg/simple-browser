package com.trae.simplebrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * HttpAuthEntries：HTTP 认证记住密码的多系统隔离。
 * 重点回归用户缺陷：同一服务器多个系统、同账户名不同密码，预填互不串。
 */
class HttpAuthEntriesTest {

    private val codec = HttpAuthEntryCodec(
        b64 = { Base64.getEncoder().encodeToString(it) },
        unb64 = { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    )

    // ---------- originKey：不同子域名/端口/协议各自成键 ----------

    @Test
    fun originKey_子域名各自成键() {
        val a = HttpAuthEntries.originKey("https", "a.example.com", -1)
        val b = HttpAuthEntries.originKey("https", "b.example.com", -1)
        assertNotEquals(a, b)
        assertEquals("https://a.example.com", a)
    }

    @Test
    fun originKey_端口不同成键不同_默认端口省略() {
        assertEquals("http://1.2.3.4:8080", HttpAuthEntries.originKey("http", "1.2.3.4", 8080))
        assertEquals(HttpAuthEntries.originKey("http", "h.com", -1), HttpAuthEntries.originKey("http", "h.com", 80))
        assertEquals(HttpAuthEntries.originKey("https", "h.com", -1), HttpAuthEntries.originKey("https", "h.com", 443))
        assertNotEquals(HttpAuthEntries.originKey("http", "h.com", -1), HttpAuthEntries.originKey("https", "h.com", -1))
    }

    @Test
    fun originKey_scheme未知用unknown_绝不与旧版host键重合() {
        val key = HttpAuthEntries.originKey(null, "h.com", -1)
        assertEquals("unknown://h.com", key)
        assertNotEquals("h.com", key)
    }

    @Test
    fun originKey_大小写与空白归一() {
        assertEquals(HttpAuthEntries.originKey("HTTPS", " A.Example.COM ", -1), HttpAuthEntries.originKey("https", "a.example.com", -1))
    }

    // ---------- appPathOf：系统路径提示取首段 ----------

    @Test
    fun appPathOf_取首段_根与异常归根() {
        assertEquals("/", HttpAuthEntries.appPathOf(null))
        assertEquals("/", HttpAuthEntries.appPathOf(""))
        assertEquals("/", HttpAuthEntries.appPathOf("/"))
        assertEquals("/appA", HttpAuthEntries.appPathOf("/appA/index.html"))
        assertEquals("/appA", HttpAuthEntries.appPathOf("/appA"))
        assertEquals("/login", HttpAuthEntries.appPathOf("/login"))
        assertEquals("/", HttpAuthEntries.appPathOf("notapath"))
    }

    // ---------- pick：同源多系统预填互不串（缺陷回归核心） ----------

    private fun entry(appPath: String, user: String, pass: String, ts: Long = 0L) =
        HttpAuthEntries.Entry(appPath, user, pass, ts)

    @Test
    fun pick_同源两系统同账户名各取各的密码() {
        val entries = listOf(entry("/appA", "wang", "密码A"), entry("/appB", "wang", "密码B"))
        assertEquals("密码A", HttpAuthEntries.pick(entries, "/appA")?.password)
        assertEquals("密码B", HttpAuthEntries.pick(entries, "/appB")?.password)
    }

    @Test
    fun pick_系统内深层页面命中其首段() {
        val entries = listOf(entry("/appA", "wang", "密码A"), entry("/appB", "wang", "密码B"))
        assertEquals("密码B", HttpAuthEntries.pick(entries, "/appB/deep/page")?.password)
    }

    @Test
    fun pick_段边界不误配() {
        // 同源两条记录时，/application/page 不得命中 /app（前缀必须是段边界）
        val entries = listOf(entry("/app", "wang", "密码app"), entry("/appZ", "z", "zpass"))
        assertNull(HttpAuthEntries.pick(entries, "/application/page"))
    }

    @Test
    fun pick_根路径条目匹配一切_长路径优先() {
        val entries = listOf(entry("/", "root", "rootpass"), entry("/appB", "wang", "密码B"))
        assertEquals("密码B", HttpAuthEntries.pick(entries, "/appB/x")?.password)
        assertEquals("rootpass", HttpAuthEntries.pick(entries, "/other")?.password)
        assertEquals("rootpass", HttpAuthEntries.pick(entries, "/")?.password)
    }

    @Test
    fun pick_无匹配且单条记录回退之() {
        val entries = listOf(entry("/login", "wang", "密码A"))
        assertEquals("密码A", HttpAuthEntries.pick(entries, "/portal")?.password)
    }

    @Test
    fun pick_无匹配且多条记录不猜() {
        val entries = listOf(entry("/appA", "wang", "密码A"), entry("/appB", "wang", "密码B"))
        assertNull(HttpAuthEntries.pick(entries, "/appC"))
    }

    @Test
    fun pick_同路径多账号取最近使用() {
        val entries = listOf(entry("/appA", "a1", "旧", ts = 100L), entry("/appA", "a2", "新", ts = 200L))
        assertEquals("a2", HttpAuthEntries.pick(entries, "/appA")?.username)
    }

    @Test
    fun pick_空列表返回空() {
        assertNull(HttpAuthEntries.pick(emptyList(), "/appA"))
    }

    // ---------- upsert：改密覆盖、同系统多账号、同账号名跨系统并存 ----------

    @Test
    fun upsert_同路径同账号视为改密覆盖() {
        val base = HttpAuthEntries.upsert(emptyList(), entry("/appA", "wang", "旧密码", 1L))
        val next = HttpAuthEntries.upsert(base, entry("/appA", "wang", "新密码", 2L))
        assertEquals(1, next.size)
        assertEquals("新密码", next[0].password)
        assertEquals(2L, next[0].lastUsed)
    }

    @Test
    fun upsert_同路径不同账号并存() {
        var base = HttpAuthEntries.upsert(emptyList(), entry("/appA", "a1", "p1"))
        base = HttpAuthEntries.upsert(base, entry("/appA", "a2", "p2"))
        assertEquals(2, base.size)
    }

    @Test
    fun upsert_同账号名不同系统并存() {
        var base = HttpAuthEntries.upsert(emptyList(), entry("/appA", "wang", "密码A"))
        base = HttpAuthEntries.upsert(base, entry("/appB", "wang", "密码B"))
        assertEquals(2, base.size)
        assertTrue(base.any { it.password == "密码A" } && base.any { it.password == "密码B" })
    }

    // ---------- removeAppPath：取消记住只清当前系统 ----------

    @Test
    fun removeAppPath_只清该系统的全部账号() {
        val base = listOf(entry("/appA", "a1", "p1"), entry("/appA", "a2", "p2"), entry("/appB", "wang", "密码B"))
        val left = HttpAuthEntries.removeAppPath(base, "/appA")
        assertEquals(1, left.size)
        assertEquals("/appB", left[0].appPath)
    }

    // ---------- 序列化：坏行跳过、中文名密码/空密码/换行密码安全 ----------

    @Test
    fun codec_roundtrip_中文与特殊字符() {
        val entries = listOf(
            entry("/appA", "王律师", "密码\n带换行\t带制表"),
            entry("/", "user", ""),
            entry("/appB", "b", "p", 1234567890L)
        )
        val decoded = codec.decode(codec.encode(entries))
        assertEquals(entries, decoded)
    }

    @Test
    fun decode_空与坏输入跳过不崩() {
        assertTrue(codec.decode(null).isEmpty())
        assertTrue(codec.decode("").isEmpty())
        assertTrue(codec.decode("垃圾行\n1|2|3\n###|###|###|abc").isEmpty())
    }

    @Test
    fun decode_旧版单条b64格式不误读不崩() {
        // 旧格式是整体 b64("user\npass")，无竖线分段——解码结果应为空列表而非乱数据
        val legacy = Base64.getEncoder().encodeToString("wang\npass".toByteArray(Charsets.UTF_8))
        assertTrue(codec.decode(legacy).isEmpty())
    }
}
