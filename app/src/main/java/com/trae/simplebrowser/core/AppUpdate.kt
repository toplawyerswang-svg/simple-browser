package com.trae.simplebrowser.core

/**
 * 应用内更新决策（纯 Kotlin，可 JVM 单元测试）。
 * 服务端：soft.orientlaw.cn 发布站 GET /api/software/<id>/latest。
 */
object AppUpdate {

    /** 服务端版本码大于当前版本码才算有新版本；服务端无版本信息（0）时恒为否。版本码用 Long（PackageInfoCompat.getLongVersionCode） */
    fun isUpdateAvailable(serverVersionCode: Long, currentVersionCode: Long): Boolean =
        serverVersionCode > currentVersionCode

    /** 安装包指纹比对：规范化大小写后全等；任一侧为空视为未提供校验，一律不算匹配 */
    fun shaMatches(expectedHex: String, actualHex: String): Boolean {
        val expected = expectedHex.trim().lowercase()
        val actual = actualHex.trim().lowercase()
        return expected.isNotEmpty() && actual.isNotEmpty() && expected == actual
    }

    /** 更新包落盘文件名：版本号只保留安全字符，防路径注入/异常文件名 */
    fun apkFileName(versionName: String): String {
        val safe = versionName.map { c ->
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("").ifBlank { "unknown" }
        return "lightboat-update-$safe.apk"
    }
}
