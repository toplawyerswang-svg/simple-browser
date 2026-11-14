package com.trae.simplebrowser

import android.content.Context
import android.net.Uri
import android.webkit.WebView

/**
 * asrsvc 分享页 owner 注入：把令牌交给页面（window.__lzAsrToken）并揭示「移入资料夹」按钮
 * （页面按钮默认 display:none）。仅对 asrsvc 域注入——令牌不能暴露给任意网页；
 * 外部浏览器/AI 打开同一链接没有注入，看不到该按钮也无法调用资料夹接口。
 * 两个 WebView 入口共用：主浏览器（MainActivity）与设置页分享预览（SettingsActivity，
 * 后者 v1.16.5 补上——b27d252 加按钮时只挂了主浏览器，设置页列表点开的分享页按钮一直不出现）。
 */
object AsrOwnerInject {

    fun inject(context: Context, view: WebView, url: String) {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return
        val asrHost = runCatching { Uri.parse(AsrClient.BASE_URL).host }.getOrNull() ?: return
        if (!host.equals(asrHost, ignoreCase = true)) return
        val token = AppPrefs.getAsrToken(context)
        if (token.isBlank()) return
        val js = "(function(){try{window.__lzAsrToken=function(){return " +
            org.json.JSONObject.quote(token) + ";};}catch(e){}" +
            "var b=document.getElementById('lzMoveBtn');if(b)b.style.display='';})();"
        runCatching { view.evaluateJavascript(js, null) }
    }
}
