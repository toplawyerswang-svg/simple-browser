package com.trae.simplebrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.trae.simplebrowser.core.AppUpdate
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 应用内检查更新（v1.21.0，F-44）：设置页手动触发，不做启动静默检查（用户拍板）。
 * 链路：发布站 latest 接口 → 对比版本码 → 下载到应用私有目录 → sha256 校验 → FileProvider 弹系统安装窗。
 * Android 平台硬限制：系统安装确认窗必现，用户需点一次「安装」。
 */
object UpdateManager {

    /** 发布站地址与轻舟条目（soft.orientlaw.cn，条目 id=2） */
    const val UPDATE_BASE_URL = "https://soft.orientlaw.cn"
    private const val LATEST_PATH = "/api/software/2/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER = 32 * 1024

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val size: Long,
        val sha256: String,
        val downloadUrl: String
    )

    sealed class CheckResult {
        data class UpToDate(val versionName: String?) : CheckResult()
        data class Available(val info: UpdateInfo) : CheckResult()
        object Failed : CheckResult()
    }

    /** 查询发布站最新版本信息；网络失败/报文异常返回 Failed */
    fun checkLatest(): CheckResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(UPDATE_BASE_URL + LATEST_PATH).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return CheckResult.Failed
            val code = json.optLong("versionCode", 0L)
            if (code <= 0) return CheckResult.UpToDate(json.optString("versionName").takeIf { it.isNotBlank() })
            val url = json.optString("downloadUrl").orEmpty()
            val sha = json.optString("sha256").orEmpty()
            // 缺下载地址或缺指纹：宁可报失败也不进入无校验的安装流程
            if (url.isBlank() || sha.isBlank()) return CheckResult.Failed
            CheckResult.Available(
                UpdateInfo(
                    versionCode = code,
                    versionName = json.optString("versionName").orEmpty().ifBlank { code.toString() },
                    size = json.optLong("size", 0L),
                    sha256 = sha,
                    downloadUrl = if (url.startsWith("http")) url else UPDATE_BASE_URL + url
                )
            )
        } catch (e: Exception) {
            CheckResult.Failed
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 下载更新包到应用私有目录并校验 sha256；成功返回就绪的 APK 文件，失败返回 null（中间文件已清理）。
     * 校验不过/大小不符一律不交付安装，防损坏或被篡改的包进入安装流程。
     */
    fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? {
        val dir = context.getExternalFilesDir("update")
            ?: File(context.cacheDir, "update").apply { mkdirs() }
        dir.mkdirs()
        // 只保留本次更新包，清掉历史残留
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val tmp = File(dir, AppUpdate.apkFileName(info.versionName) + ".part")
        val final = File(dir, AppUpdate.apkFileName(info.versionName))
        var conn: HttpURLConnection? = null
        try {
            conn = URL(info.downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            if (conn.responseCode !in 200..299) return null
            val total = if (conn.contentLength > 0) conn.contentLength.toLong() else info.size
            var read = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            val actual = sha256Hex(tmp)
            if (!AppUpdate.shaMatches(info.sha256, actual)) {
                runCatching { tmp.delete() }
                return null
            }
            if (info.size > 0 && tmp.length() != info.size) {
                runCatching { tmp.delete() }
                return null
            }
            if (!tmp.renameTo(final)) {
                tmp.copyTo(final, overwrite = true)
                tmp.delete()
            }
            return final
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return null
        } finally {
            conn?.disconnect()
        }
    }

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 触发系统安装窗。返回 false = 缺「安装未知应用」授权，已跳转系统设置页，授权后回来重试即可。
     */
    fun installApk(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val grant = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(grant) }.isSuccess
            if (!opened) {
                // 个别 ROM 无该系统页：退到应用详情设置页让用户手动放行
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updatefiles", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
