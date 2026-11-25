package com.trae.simplebrowser

import android.content.Context
import com.trae.simplebrowser.core.KickoffHistory
import com.trae.simplebrowser.core.KickoffVersion
import java.io.File

/**
 * 开场文案历史版本的本机持久层（2026-11-25）。
 *
 * 只存本机：filesDir/kickoff_versions.txt（app 私有目录，卸载即清），格式见
 * [KickoffHistory.serialize]。读写全程加锁（对话框内可能边删边读），单文件量级很小
 * （≤50 条 × 几百字），整份重写即可，与 AsrDupStore/DownloadStoreFile 同风格。
 */
object KickoffHistoryStore {

    private const val FILE_NAME = "kickoff_versions.txt"
    private val lock = Any()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 读全部历史（按最近使用/保存倒序）；文件不存在或损坏时给空列表，不抛 */
    fun load(context: Context): List<KickoffVersion> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyList()
        runCatching { KickoffHistory.parse(f.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    /** 覆盖写入（调用方传完整列表） */
    fun save(context: Context, list: List<KickoffVersion>): Boolean = synchronized(lock) {
        runCatching {
            val f = file(context)
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(KickoffHistory.serialize(list), Charsets.UTF_8)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }.isSuccess
    }

    /** 保存一版（去重 + 裁剪）并返回最新列表 */
    fun upsert(
        context: Context,
        text: String,
        now: Long,
        upload: Boolean,
        evidenceBacked: Boolean,
        owners: List<String>
    ): List<KickoffVersion> {
        val next = KickoffHistory.upsert(load(context), text, now, upload, evidenceBacked, owners)
        save(context, next)
        return next
    }

    fun markUsed(context: Context, id: String, now: Long): List<KickoffVersion> {
        val next = KickoffHistory.markUsed(load(context), id, now)
        save(context, next)
        return next
    }

    fun remove(context: Context, id: String): List<KickoffVersion> {
        val next = KickoffHistory.remove(load(context), id)
        save(context, next)
        return next
    }
}