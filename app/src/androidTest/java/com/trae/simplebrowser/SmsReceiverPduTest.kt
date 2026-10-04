package com.trae.simplebrowser

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 模拟器端到端第二环：构造真实 SMS-DELIVER PDU，直接投给 SmsReceiver.onReceive
 * （模拟器无短信控制台、shell 无 root，系统派发这一跳无法模拟；其余全链路真实）。
 * 期望：PDU 解析出发件号 12368 → 入队 → 异步冲刷上传到本地 smsbox（adb reverse）。
 */
@RunWith(AndroidJUnit4::class)
class SmsReceiverPduTest {

    /** GSM 03.38 7bit 打包：septet 序列 → 字节序列（位流式，校验样例 "hello"→E8 32 9B FD 06）。 */
    private fun gsm7pack(chars: IntArray): ByteArray {
        val out = ArrayList<Byte>()
        var acc = 0
        var accBits = 0
        for (c in chars) {
            acc = acc or (c shl accBits)
            accBits += 7
            while (accBits >= 8) {
                out.add((acc and 0xFF).toByte())
                acc = acc ushr 8
                accBits -= 8
            }
        }
        if (accBits > 0) out.add(acc.toByte())
        return out.toByteArray()
    }

    /** SMS-DELIVER PDU：无SMSC + MTI:04 + 主叫12368(05 81 21 63 8F) + PID/DCS + SCTS + UD。 */
    private fun build12368Pdu(body: String): ByteArray {
        val ud = gsm7pack(body.map { it.code }.toIntArray())
        val pdu = ArrayList<Int>()
        pdu.add(0x00)            // SMSC 长度 0
        pdu.add(0x04)            // TP-MTI=00(DELIVER) + MMS=1
        pdu.add(0x05)            // 主叫号码位数 5
        pdu.add(0x81)            // TON/NPI
        pdu.addAll(listOf(0x21, 0x63, 0xF8)) // 12368 半八位组：低半字节在前，奇数末位补 F 在高半字节
        pdu.add(0x00)            // TP-PID
        pdu.add(0x00)            // TP-DCS GSM7
        pdu.addAll(listOf(0x62, 0x01, 0x50, 0x10, 0x00, 0x00, 0x00)) // SCTS 26-10-05 01:00
        pdu.add(body.length)     // UDL = septet 数（全 ASCII body：字符数即 septet 数）
        for (b in ud) pdu.add(b.toInt() and 0xFF)
        return pdu.map { it.toByte() }.toByteArray()
    }

    @Test
    fun receiverPduEnqueueAndUpload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        AppPrefs.setSmsBackupEnabled(context, true)
        AppPrefs.setSmsToken(context, "localtest-token-1234567890")
        AppPrefs.setSmsBaseUrl(context, "http://127.0.0.1:8730")

        val store = SmsBackupStore.get(context)
        val body = "PDU-E2E-${System.currentTimeMillis() % 100000}"
        val pdu = build12368Pdu(body)
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", arrayOf(pdu))
            putExtra("format", "3gpp")
        }

        val before = store.pendingCount()
        SmsReceiver().onReceive(context, intent)

        // 入队+上存全程异步且窗口极短，设备端瞬态不可靠观测——以服务器终态为准：
        // 轮询 /list?q=<唯一正文>，出现即证明 PDU解析→匹配→入队→HTTP上传→服务端落库 全通
        var found = false
        for (i in 1..60) {
            Thread.sleep(500)
            try {
                val conn = java.net.URL(
                    "http://127.0.0.1:8730/list?limit=10&q=" +
                        java.net.URLEncoder.encode(body, "UTF-8")
                ).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("X-LZ-Token", "localtest-token-1234567890")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                if (text.contains(body)) {
                    found = true
                    break
                }
            } catch (e: Exception) {
                // 服务端偶发不可达就继续轮询
            }
        }
        assertTrue("30 秒内服务器应收到 PDU 测试短信", found)

        // 服务器应已收到：通过 /ping 核对（令牌与地址上面已配置）
        val total = SmsBackupManager.ping(context, "http://127.0.0.1:8730", "localtest-token-1234567890")
        assertTrue("服务器应已收到 PDU 测试短信", total >= 3)
    }
}
