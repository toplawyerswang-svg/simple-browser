package com.trae.simplebrowser

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.trae.simplebrowser.core.DownloadKind
import com.trae.simplebrowser.core.DownloadRecord
import com.trae.simplebrowser.core.DownloadStatus
import com.trae.simplebrowser.core.HlsKey
import com.trae.simplebrowser.core.VideoUrlSniffer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 带用户可读错误文案（资源 id）的下载中止异常 */
private class DownloadUserException(@StringRes val messageRes: Int) : Exception()

/**
 * m3u8 视频下载前台服务：解析播放列表 → 逐分片下载（AES-128 自动解密）→
 * 合并写入公共 Download/轻舟浏览器/ 目录，通知栏展示进度与结果。
 *
 * 直链视频（mp4 等）不走本服务，由 MainActivity 用系统 DownloadManager 下载。
 */
class VideoDownloadService : Service() {

    private val logTag = "VideoDownload"
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor()
    private val pendingParams = ArrayDeque<DownloadParams>()
    private var loopRunning = false
    private val store by lazy { DownloadStoreFile(this) }

    @Volatile
    private var cancelled = false

    private var lastNotifyAt = 0L
    private lateinit var notifications: NotificationManagerCompat

    /** 本条下载完成后的可打开 content uri（API29+ MediaStore；API<29 的 file:// 不可直接打开，留空） */
    @Volatile
    private var publishedUri: String? = null

    data class DownloadParams(
        val url: String,
        val title: String,
        val referer: String,
        val cookie: String?,
        val ua: String
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationManagerCompat.from(this)
        createChannels()
        Log.i(logTag, "service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val params = readParams(it)
            Log.i(logTag, "queued: title=${params.title} url=${params.url.take(80)}")
            startInForeground()
            synchronized(lock) {
                pendingParams.addLast(params)
                if (!loopRunning) {
                    loopRunning = true
                    executor.execute(::drainQueue)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        executor.shutdownNow()
        Log.i(logTag, "service destroyed")
        super.onDestroy()
    }

    // ---------- 主流程 ----------

    private fun drainQueue() {
        while (true) {
            val params = synchronized(lock) {
                if (pendingParams.isEmpty()) {
                    loopRunning = false
                    null
                } else {
                    pendingParams.removeFirst()
                }
            } ?: break
            cancelled = false
            processDownload(params)
        }
        if (synchronized(lock) { pendingParams.isEmpty() && !loopRunning }) {
            Log.i(logTag, "queue drained -> stop service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun processDownload(params: DownloadParams) {
        Log.i(logTag, "download start: ${params.title}")
        val recordId = "hls_" + System.currentTimeMillis()
        cancelRequests.remove(recordId)
        store.upsert(
            DownloadRecord(
                id = recordId,
                kind = DownloadKind.HLS,
                title = params.title,
                fileName = "",
                path = "",
                uri = "",
                status = DownloadStatus.RUNNING,
                progress = 0,
                detail = getString(R.string.video_download_preparing),
                createdAt = System.currentTimeMillis(),
                finishedAt = 0L
            )
        )
        try {
            downloadHls(params, recordId)
        } catch (e: DownloadUserException) {
            val reason = getString(e.messageRes)
            Log.w(logTag, "download aborted: $reason")
            store.updateById(recordId) {
                it.copy(status = DownloadStatus.FAILED, detail = reason, finishedAt = System.currentTimeMillis())
            }
            notifyResult(success = false, name = params.title, detail = reason)
        } catch (e: InterruptedException) {
            Log.w(logTag, "download interrupted")
            store.updateById(recordId) {
                it.copy(status = DownloadStatus.FAILED, detail = getString(R.string.video_download_error_cancelled), finishedAt = System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(logTag, "download error", e)
            store.updateById(recordId) {
                it.copy(status = DownloadStatus.FAILED, detail = getString(R.string.video_download_error_network), finishedAt = System.currentTimeMillis())
            }
            notifyResult(
                success = false,
                name = params.title,
                detail = getString(R.string.video_download_error_network)
            )
        }
    }

    private fun downloadHls(p: DownloadParams, recordId: String) {
        publishedUri = null
        updateProgress(getString(R.string.video_download_preparing), 0, 0, indeterminate = true, force = true)

        var playlistUrl = p.url
        var text = fetchText(playlistUrl, p)
        if (VideoUrlSniffer.isMasterPlaylist(text)) {
            val best = VideoUrlSniffer.parseMaster(text, playlistUrl).firstOrNull()
                ?: throw DownloadUserException(R.string.video_download_error_playlist)
            Log.i(logTag, "master playlist -> variant bw=${best.bandwidth}")
            playlistUrl = best.url
            text = fetchText(playlistUrl, p)
        }
        val playlist = VideoUrlSniffer.parseMedia(text, playlistUrl)
        if (playlist.encryptionUnsupported) throw DownloadUserException(R.string.video_download_error_encrypted)
        if (!playlist.hasEndList) throw DownloadUserException(R.string.video_download_error_live)
        if (playlist.hasByteRange) throw DownloadUserException(R.string.video_download_error_byterange)
        if (playlist.segments.isEmpty()) throw DownloadUserException(R.string.video_download_error_playlist)
        if (playlist.segments.size > MAX_SEGMENTS) throw DownloadUserException(R.string.video_download_error_too_many)
        // 嗅探到的直链可能是广告片段/防盗链短文件，时长过短时明确失败而非"秒完成"
        if (VideoUrlSniffer.isSuspiciouslyShort(playlist.totalDurationSec)) {
            throw DownloadUserException(R.string.video_download_error_too_short)
        }

        val ext = if (playlist.initSegmentUrl != null) "mp4" else "ts"
        val fileName = VideoUrlSniffer.sanitizeFileName(p.title) +
            "_" + VideoUrlSniffer.fileTimestamp() + "." + ext
        val temp = File(cacheDir, "hls_${System.currentTimeMillis()}.tmp")
        Log.i(logTag, "playlist ok: ${playlist.segments.size} segments, ext=$ext, file=$fileName")

        var keyUriCached: String? = null
        var keyBytes = ByteArray(0)
        try {
            FileOutputStream(temp).use { out ->
                playlist.initSegmentUrl?.let { initUrl ->
                    out.write(fetchBytes(initUrl, p))
                    Log.i(logTag, "init segment saved: ${initUrl.take(64)}")
                }
                val total = playlist.segments.size
                playlist.segments.forEachIndexed { index, seg ->
                    if (cancelled || Thread.currentThread().isInterrupted ||
                        cancelRequests.remove(recordId)
                    ) {
                        throw DownloadUserException(R.string.video_download_error_cancelled)
                    }
                    var data = fetchBytes(seg.url, p)
                    val key = seg.key
                    if (key != null && key.method.equals("AES-128", ignoreCase = true)) {
                        val keyUri = key.uri ?: throw DownloadUserException(R.string.video_download_error_encrypted)
                        if (keyUriCached != keyUri) {
                            keyUriCached = keyUri
                            keyBytes = fetchBytes(keyUri, p)
                        }
                        val iv = ivForSegment(key, playlist.mediaSequence + index)
                        data = runCatching { decryptAes128(data, keyBytes, iv) }
                            .onFailure { Log.w(logTag, "segment $index decrypt failed, keep raw: ${it.message}") }
                            .getOrDefault(data)
                    }
                    out.write(data)
                    val progressPct = ((index + 1) * 100) / total
                    val segmentDetail = getString(R.string.video_download_progress_fmt, p.title, index + 1, total)
                    store.updateById(recordId) {
                        it.copy(progress = progressPct, detail = segmentDetail)
                    }
                    updateProgress(
                        segmentDetail,
                        index + 1, total, indeterminate = false
                    )
                }
            }
            // 合并产物过小说明拿到的不是有效视频（防盗链/空流），在落盘前拦截
            if (VideoUrlSniffer.isSuspiciouslySmall(temp.length())) {
                throw DownloadUserException(R.string.video_download_error_too_small)
            }
            val savedPath = publishToDownloads(temp, fileName, if (ext == "mp4") "video/mp4" else "video/mp2t")
            store.updateById(recordId) {
                it.copy(
                    status = DownloadStatus.DONE,
                    fileName = fileName,
                    path = savedPath,
                    uri = publishedUri.orEmpty(),
                    progress = 100,
                    detail = getString(R.string.video_download_saved_path_fmt, savedPath),
                    finishedAt = System.currentTimeMillis()
                )
            }
            Log.i(logTag, "download finished: $savedPath")
            notifyResult(success = true, name = fileName, detail = getString(R.string.video_download_saved_path_fmt, savedPath))
        } finally {
            temp.delete()
        }
    }

    // ---------- 网络 ----------

    private fun fetchText(url: String, p: DownloadParams): String = String(fetchBytes(url, p), Charsets.UTF_8)

    private fun fetchBytes(url: String, p: DownloadParams): ByteArray {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                p.ua.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("User-Agent", it) }
                p.referer.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Referer", it) }
                p.cookie?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code for ${url.take(64)}")
                    return conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
            } catch (e: InterruptedException) {
                throw DownloadUserException(R.string.video_download_error_cancelled)
            } catch (e: Exception) {
                lastError = e
                Log.w(logTag, "fetch attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) runCatching { Thread.sleep(500) }
            }
        }
        throw lastError ?: IllegalStateException("fetch failed: $url")
    }

    // ---------- AES-128 ----------

    private fun decryptAes128(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        require(key.size == 16) { "bad key size ${key.size}" }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** IV：KEY 行带 0x 前缀 IV 用之，否则用分片序号（MEDIA-SEQUENCE + 下标）的 128 位大端 */
    private fun ivForSegment(key: HlsKey, sequence: Long): ByteArray {
        hexToBytes(key.ivHex)?.let { return it }
        return ByteBuffer.allocate(16).putLong(0L).putLong(sequence).array()
    }

    private fun hexToBytes(hexIn: String?): ByteArray? {
        if (hexIn == null) return null
        val hex = hexIn.trim().removePrefix("0x").removePrefix("0X")
        if (hex.length != 32 || hex.any { it.lowercaseChar() !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) {
            return null
        }
        return ByteArray(16) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }

    // ---------- 落盘 ----------

    private fun publishToDownloads(temp: File, displayName: String, mimeType: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream failed")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            contentResolver.update(uri, done, null, null)
            publishedUri = uri.toString()
            return "$RELATIVE_DIR/$displayName"
        }

        // API 28-：直写公共 Download 目录；无写权限时退到应用外部私有目录
        val hasWrite = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val dir = if (hasWrite) {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)
        } else {
            File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)
        }
        dir.mkdirs()
        val target = File(dir, displayName)
        temp.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    // ---------- 通知 ----------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, getString(R.string.video_download_channel), NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, getString(R.string.video_download_result_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun startInForeground() {
        val notification = progressNotification(getString(R.string.video_download_preparing), 0, 0, indeterminate = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID_PROGRESS, notification)
        }
    }

    private fun updateProgress(text: String, progress: Int, total: Int, indeterminate: Boolean, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyAt < 400 && progress != total) return
        lastNotifyAt = now
        runCatching {
            notifications.notify(NOTIF_ID_PROGRESS, progressNotification(text, progress, total, indeterminate))
        }
    }

    private fun progressNotification(text: String, progress: Int, total: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.video_download_notification_title))
            .setContentText(text)
            .setProgress(total, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloadsIntent())
            .build()

    private fun notifyResult(success: Boolean, name: String, detail: String) {
        runCatching {
            notifications.cancel(NOTIF_ID_PROGRESS)
            if (!notifications.areNotificationsEnabled()) return
            val title = getString(if (success) R.string.video_download_done else R.string.video_download_failed)
            val text = if (success) "$name\n$detail" else "$name：$detail"
            val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openDownloadsIntent())
                .build()
            notifications.notify(absOrOne(name.hashCode()), notification)
        }
    }

    private fun openDownloadsIntent(): PendingIntent? {
        val intent = Intent("android.intent.action.VIEW_DOWNLOADS")
        return runCatching {
            PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrNull()
    }

    private fun absOrOne(value: Int): Int = if (value == 0) 1 else kotlin.math.abs(value)

    private fun readParams(intent: Intent) = DownloadParams(
        url = intent.getStringExtra(EXTRA_URL).orEmpty(),
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "video" },
        referer = intent.getStringExtra(EXTRA_REFERER).orEmpty(),
        cookie = intent.getStringExtra(EXTRA_COOKIE),
        ua = intent.getStringExtra(EXTRA_UA).orEmpty()
    )

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_COOKIE = "extra_cookie"
        const val EXTRA_UA = "extra_ua"

        /** 用户在下载管理删除的运行中记录 id；下载线程在每个分片前检查后自行退出 */
        private val cancelRequests = ConcurrentHashMap.newKeySet<String>()

        /** 请求取消一条运行中的 m3u8 下载（记录 id 为 hls_ 前缀） */
        fun requestCancel(recordId: String) {
            cancelRequests.add(recordId)
        }

        private const val CHANNEL_PROGRESS = "video_download"
        private const val CHANNEL_RESULT = "video_download_result"
        private const val NOTIF_ID_PROGRESS = 1001
        private const val RELATIVE_DIR = "Download/轻舟浏览器"
        private const val FOLDER_NAME = "轻舟浏览器"

        /** 分片数上限，防失控（直播长流/异常播放列表） */
        private const val MAX_SEGMENTS = 5000
    }
}
