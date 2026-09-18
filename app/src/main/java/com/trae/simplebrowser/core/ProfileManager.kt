package com.trae.simplebrowser.core

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * 多身份（multi-profile）登录态隔离：每个身份对应一个 WebView Profile，
 * cookie、localStorage、IndexedDB、HTTP 缓存各自独立，因此同一个网站的多个账号可以同时保持登录。
 *
 * 身份1 恒为系统默认 Profile（"Default"），沿用应用既有登录态，老用户升级后零迁移；
 * 身份2 起为命名 Profile。该能力由 WebView APK 自身提供（AndroidX boundary，需 WebView 116+），
 * 是否可用由 [isSupported] 判定，不支持的设备上调用方应退化为单身份并给出提示。
 */
object ProfileManager {

    /** 身份1 固定使用系统默认 Profile 名 */
    const val DEFAULT_PROFILE = "Default"

    private const val NAMED_PREFIX = "identity"

    /** 身份序号上限（徽标只显示一位数字，实际场景 2 个账号已足够） */
    const val MAX_IDENTITY_INDEX = 4

    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }.getOrDefault(false)

    /** 身份序号 → Profile 名 */
    fun profileNameOf(index: Int): String =
        if (index <= 1) DEFAULT_PROFILE else "$NAMED_PREFIX$index"

    /** Profile 名 → 身份序号；无法识别的名字按身份1 处理 */
    fun indexOfProfileName(name: String): Int {
        if (name == DEFAULT_PROFILE) return 1
        return name.removePrefix(NAMED_PREFIX).toIntOrNull()?.coerceIn(1, MAX_IDENTITY_INDEX) ?: 1
    }

    private fun storeOrNull(): ProfileStore? {
        if (!isSupported()) return null
        return runCatching { ProfileStore.getInstance() }.getOrNull()
    }

    /**
     * 把 WebView 绑定到指定身份，必须在 loadUrl 之前调用。
     * 返回是否绑定成功；失败（设备不支持或调用被拒）时调用方不得按该身份继续。
     */
    fun applyTo(webView: WebView, profileName: String): Boolean {
        if (profileName == DEFAULT_PROFILE) return true
        val store = storeOrNull() ?: return false
        return runCatching {
            store.getOrCreateProfile(profileName)
            WebViewCompat.setProfile(webView, profileName)
            true
        }.getOrDefault(false)
    }

    /** 身份对应的 Cookie 管理器；不支持多身份时回退到默认单例（即身份1） */
    fun cookieManager(profileName: String): CookieManager {
        if (profileName == DEFAULT_PROFILE) return CookieManager.getInstance()
        val store = storeOrNull() ?: return CookieManager.getInstance()
        return runCatching { store.getOrCreateProfile(profileName).cookieManager }
            .getOrNull() ?: CookieManager.getInstance()
    }
}
