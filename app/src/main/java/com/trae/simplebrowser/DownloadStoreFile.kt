package com.trae.simplebrowser

import android.content.Context
import android.content.Intent
import android.util.Log
import com.trae.simplebrowser.core.DownloadRecord
import com.trae.simplebrowser.core.DownloadStoreLogic
import java.io.File

/** 下载记录刷新广播：设置页注册接收后重载列表 */
object DownloadEvents {
    const val ACTION_UPDATED = "com.trae.lightboat.action.DOWNLOAD_UPDATED"

    fun send(context: Context) {
        runCatching {
            context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
        }
    }
}

/**
 * 下载记录的文件持久化（filesDir/downloads.txt，行格式见 [DownloadStoreLogic]）。
 * Service/Activity 各持一个实例共享同一文件，锁必须是进程级共享的（companion），
 * 否则两边的读-改-写交错会互相覆盖（如删除记录时丢掉服务刚写入的完成状态）。
 */
class DownloadStoreFile(context: Context) {

    private val logTag = "DownloadStore"
    private val file = File(context.filesDir, "downloads.txt")

    companion object {
        private val lock = Any()
    }

    fun load(): List<DownloadRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        runCatching { DownloadStoreLogic.decode(file.readText()) }
            .onFailure { Log.e(logTag, "decode downloads.txt failed, start fresh", it) }
            .getOrElse { emptyList() }
    }

    fun save(records: List<DownloadRecord>) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(DownloadStoreLogic.encode(records))
        }.onFailure { Log.e(logTag, "write downloads.txt failed", it) }
    }

    /** 读-改-写一步完成并广播刷新 */
    fun update(transform: (List<DownloadRecord>) -> List<DownloadRecord>) {
        synchronized(lock) {
            save(transform(load()))
        }
        DownloadEvents.send(AppContextHolder.get())
    }

    fun upsert(record: DownloadRecord) = update { DownloadStoreLogic.upsert(it, record) }

    fun updateById(id: String, transform: (DownloadRecord) -> DownloadRecord) =
        update { DownloadStoreLogic.update(it, id, transform) }

    /** 批量删除记录并广播刷新 */
    fun removeByIds(ids: Set<String>) = update { DownloadStoreLogic.removeByIds(it, ids) }
}

/** 应用级 Context 持有者（仅用于无 Context 工具场景发送广播；Application onCreate 赋值） */
object AppContextHolder {
    @Volatile
    lateinit var appContext: Context
        private set

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context = appContext
}
