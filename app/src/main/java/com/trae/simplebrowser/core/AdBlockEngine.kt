package com.trae.simplebrowser.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 广告拦截引擎。
 * 规则合并匹配：内置 assets 精选表 + 在线全量表（anti-AD，存 filesDir）+ 站点白名单。
 * 匹配语义：请求 host 等于规则或为其子域即命中；主文档请求由调用方放行，引擎只拦子资源。
 */
object AdBlockEngine {
    private const val TAG = "AdBlock"
    private const val PREFS = "adblock_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTOHIDE = "autohide"
    private const val KEY_WHITELIST = "whitelist_json"
    private const val KEY_LAST_UPDATE = "last_update_at"
    private const val KEY_ONLINE_COUNT = "online_count"
    private const val KEY_TOTAL_BLOCKED = "total_blocked"
    private const val ASSET_RULES = "adblock_domains.txt"
    private const val ONLINE_RULE_FILE = "adblock_online_domains.txt"
    private const val MAX_RECENT = 20

    /** 在线规则源：anti-AD 官方直连优先，GitHub 镜像兜底（ghfast.top 实测最快） */
    private val SOURCE_URLS = arrayOf(
        "https://anti-ad.net/domains.txt",
        "https://ghfast.top/https://raw.githubusercontent.com/privacy-protection-tools/anti-AD/master/domains.txt"
    )

    @Volatile private var builtinRules: Set<String> = emptySet()
    @Volatile private var onlineRules: Set<String> = emptySet()
    @Volatile private var whitelist: Set<String> = emptySet()
    @Volatile private var loaded = false
    @Volatile private var updating = false

    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "adblock-io") }
    private val totalBlockedCounter = AtomicLong(0)
    private val pageBlockedCounts = Collections.synchronizedMap(WeakHashMap<Any, Int>())
    private val pageHosts = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val recentBlocked = Collections.synchronizedMap(WeakHashMap<Any, MutableList<String>>())

    data class RuleStatus(
        val builtinCount: Int,
        val onlineCount: Int,
        val lastUpdateAt: Long,
        val totalBlocked: Long
    )

    // ---------- 开关 ----------
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun isAutoHideEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOHIDE, true)
    fun setAutoHideEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOHIDE, value).apply()
    }

    // ---------- 白名单 ----------
    fun getWhitelist(context: Context): List<String> = whitelist.toList().sorted()

    fun addWhitelist(context: Context, host: String): Boolean {
        val h = normalizeHost(host) ?: return false
        val current = whitelist.toMutableSet()
        if (!current.add(h)) return false
        whitelist = current
        saveWhitelist(context)
        return true
    }

    fun removeWhitelist(context: Context, host: String): Boolean {
        val current = whitelist.toMutableSet()
        val removed = current.remove(host.lowercase())
        whitelist = current
        saveWhitelist(context)
        return removed
    }

    /** 页面 host 自身或其任一父域在白名单即放行（www.example.com 命中规则 example.com） */
    private fun isWhitelisted(pageHost: String): Boolean {
        var idx = 0
        while (idx < pageHost.length) {
            if (pageHost.substring(idx) in whitelist) return true
            idx = pageHost.indexOf('.', idx) + 1
            if (idx == 0) break
        }
        return false
    }

    /** 供注入/展示场景判断页面是否被放行（低频，直接读 prefs，不依赖异步加载完成） */
    fun isPageWhitelisted(context: Context, pageHost: String?): Boolean {
        val host = pageHost?.lowercase().orEmpty()
        if (host.isBlank()) return false
        val set = parseWhitelist(prefs(context).getString(KEY_WHITELIST, "[]").orEmpty())
        var idx = 0
        while (idx < host.length) {
            if (host.substring(idx) in set) return true
            val next = host.indexOf('.', idx)
            if (next < 0) break
            idx = next + 1
        }
        return false
    }

    private fun saveWhitelist(context: Context) {
        val array = JSONArray()
        whitelist.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_WHITELIST, array.toString()).apply()
    }

    // ---------- 规则加载 ----------
    fun initAsync(context: Context) {
        val app = context.applicationContext
        ioExecutor.execute { loadAll(app) }
    }

    @Synchronized
    private fun loadAll(context: Context) {
        if (loaded) return
        totalBlockedCounter.set(prefs(context).getLong(KEY_TOTAL_BLOCKED, 0L))
        whitelist = parseWhitelist(prefs(context).getString(KEY_WHITELIST, "[]").orEmpty())
        builtinRules = loadBuiltin(context)
        onlineRules = loadOnlineFile(context)
        loaded = true
        Log.i(TAG, "rules loaded: builtin=${builtinRules.size} online=${onlineRules.size} whitelist=${whitelist.size}")
    }

    private fun parseWhitelist(json: String): Set<String> {
        val result = mutableSetOf<String>()
        runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                normalizeHost(array.optString(i, ""))?.let { result.add(it) }
            }
        }
        return result
    }

    private fun loadBuiltin(context: Context): Set<String> {
        val set = mutableSetOf<String>()
        runCatching {
            context.assets.open(ASSET_RULES).use { ins ->
                BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).forEachLine { line ->
                    parseRuleLine(line)?.let { set.add(it) }
                }
            }
        }.onFailure { Log.e(TAG, "load builtin rules failed", it) }
        return set
    }

    private fun loadOnlineFile(context: Context): Set<String> {
        val file = File(context.filesDir, ONLINE_RULE_FILE)
        if (!file.isFile) return emptySet()
        val set = mutableSetOf<String>()
        runCatching {
            file.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                parseRuleLine(line)?.let { set.add(it) }
            }
        }.onFailure { Log.e(TAG, "load online rules failed", it) }
        return set
    }

    /** 支持 hosts（0.0.0.0 domain）与纯域名两种行；注释行跳过 */
    private fun parseRuleLine(raw: String): String? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null
        val space = line.indexOf(' ')
        if (space > 0) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2) line = parts[1] else return null
        }
        line = line.trimEnd('.').lowercase()
        if (line.length !in 4..253) return null
        if (!line.contains('.')) return null
        for (c in line) {
            if (c !in 'a'..'z' && c !in '0'..'9' && c != '.' && c != '-') return null
        }
        return line
    }

    private fun normalizeHost(input: String): String? {
        val host = input.trim().lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
            .trimEnd('.')
        if (host.length !in 4..253) return null
        if (!host.contains('.')) return null
        for (c in host) {
            if (c !in 'a'..'z' && c !in '0'..'9' && c != '.' && c != '-') return null
        }
        return host
    }

    // ---------- 拦截判定 ----------
    /** onPageStarted 时同步主文档 host（供白名单判断）；view 用 WebView 本身做键 */
    fun setPageHost(view: Any, host: String?) {
        val h = host?.lowercase().orEmpty()
        if (h.isNotBlank()) pageHosts[view] = h else pageHosts.remove(view)
    }

    /**
     * 是否拦截该子资源请求。IO 线程调用。
     * @param pageHost 当前页面主文档 host，为空时引擎自行查缓存在案
     */
    fun shouldBlock(context: Context, view: Any?, pageHost: String?, requestHost: String?): Boolean {
        if (!isEnabled(context)) return false
        val req = normalizeHost(requestHost.orEmpty()) ?: return false
        val page = pageHost ?: (view?.let { pageHosts[it] } ?: "")
        if (page.isNotBlank() && isWhitelisted(page)) return false
        return matchesRules(req)
    }

    private fun matchesRules(hostLower: String): Boolean {
        if (builtinRules.isNotEmpty()) {
            var idx = 0
            while (idx <= hostLower.length) {
                if (hostLower.substring(idx) in builtinRules) return true
                val next = hostLower.indexOf('.', idx)
                if (next < 0) break
                idx = next + 1
            }
        }
        if (onlineRules.isNotEmpty()) {
            var idx = 0
            while (idx <= hostLower.length) {
                if (hostLower.substring(idx) in onlineRules) return true
                val next = hostLower.indexOf('.', idx)
                if (next < 0) break
                idx = next + 1
            }
        }
        return false
    }

    // ---------- 拦截记录 ----------
    /** IO 线程调用；返回该页累计拦截数 */
    fun recordBlocked(view: Any, url: String): Int {
        val count = (pageBlockedCounts[view] ?: 0) + 1
        pageBlockedCounts[view] = count
        val host = normalizeHost(url) ?: url.take(60)
        val list = recentBlocked[view] ?: Collections.synchronizedList(mutableListOf<String>()).also {
            recentBlocked[view] = it
        }
        synchronized(list) {
            list.add(host)
            while (list.size > MAX_RECENT) list.removeAt(0)
        }
        totalBlockedCounter.incrementAndGet()
        return count
    }

    fun persistTotalBlocked(context: Context) {
        prefs(context).edit().putLong(KEY_TOTAL_BLOCKED, totalBlockedCounter.get()).apply()
    }

    fun resetPageBlocked(view: Any) {
        pageBlockedCounts.remove(view)
        recentBlocked.remove(view)
    }

    fun pageBlockedCount(view: Any): Int = pageBlockedCounts[view] ?: 0

    /** 本页被拦域名去重列表（最新在前） */
    fun recentBlockedHosts(view: Any): List<String> {
        val list = recentBlocked[view] ?: return emptyList()
        return synchronized(list) { list.toList() }.asReversed().distinct()
    }

    fun ruleStatus(context: Context): RuleStatus {
        val p = prefs(context)
        return RuleStatus(
            builtinCount = builtinRules.size,
            onlineCount = p.getInt(KEY_ONLINE_COUNT, 0),
            lastUpdateAt = p.getLong(KEY_LAST_UPDATE, 0L),
            totalBlocked = totalBlockedCounter.get()
        )
    }

    // ---------- 在线更新 ----------
    fun isUpdating(): Boolean = updating

    /** 后台线程下载并替换在线规则；回调在 IO 线程，调用方需自行切 UI 线程 */
    fun updateOnlineRules(context: Context, onResult: (Result<Int>) -> Unit) {
        if (updating) {
            onResult(Result.failure(IllegalStateException("updating")))
            return
        }
        val app = context.applicationContext
        updating = true
        ioExecutor.execute {
            val result = runCatching {
                var lastError: Exception? = null
                var count = -1
                for (source in SOURCE_URLS) {
                    try {
                        count = fetchRuleFile(source, File(app.filesDir, ONLINE_RULE_FILE))
                        break
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "rule source failed: $source (${e.message})")
                    }
                }
                if (count < 0) throw (lastError ?: IllegalStateException("all rule sources failed"))
                count
            }
            result.onSuccess { count ->
                synchronized(this) { onlineRules = loadOnlineFile(app) }
                prefs(app).edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .putInt(KEY_ONLINE_COUNT, count)
                    .apply()
                Log.i(TAG, "online rules updated: $count")
            }
            updating = false
            onResult(result)
        }
    }

    private fun fetchRuleFile(urlStr: String, outFile: File): Int {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LightboatBrowser")
        val code = conn.responseCode
        if (code != 200) throw IllegalStateException("HTTP $code")
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        var count = 0
        try {
            tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
                conn.inputStream.use { ins ->
                    BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).forEachLine { raw ->
                        val domain = parseRuleLine(raw)
                        if (domain != null) {
                            writer.write(domain)
                            writer.newLine()
                            count++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        // 过小说明拉到的是错误页或被劫持内容，直接作废
        if (count < 100) {
            tmp.delete()
            throw IllegalStateException("suspicious tiny rule file ($count lines)")
        }
        if (!tmp.renameTo(outFile)) {
            outFile.delete()
            if (!tmp.renameTo(outFile)) throw IllegalStateException("rename rule file failed")
        }
        return count
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- 页面脚本 ----------

    /**
     * 保守自动隐藏（onPageFinished 注入 + MutationObserver 持续监听）：
     * 1) 全屏遮罩：fixed 且覆盖 85% 视口、z-index ≥ 500、整层是链接或铺满链接、无表单/视频 → 移除；
     * 2) 悬浮广告：fixed/absolute 且 id/class 命中广告词表 → 隐藏；
     * 3) 广告 iframe 空壳（网络层已拦内容）→ 隐藏占位。
     * id/class 按 token 精确匹配，避免 gradient、download 等词误伤。
     */
    const val AUTO_HIDE_JS = """(function(){
if(window.__lzbAd)return 0;window.__lzbAd=1;
var vw=window.innerWidth||document.documentElement.clientWidth;
var vh=window.innerHeight||document.documentElement.clientHeight;
var BAD={'ad':1,'ads':1,'adv':1,'advert':1,'adverts':1,'advertisement':1,'advertisements':1,'adbox':1,'adframe':1,'adslot':1,'adwrap':1,'adcontainer':1,'adbanner':1,'adsbygoogle':1,'taboola':1,'outbrain':1,'gpt':1,'dfp':1,'popunder':1,'sponsor':1,'sponsored':1};
function toks(el){var cn=el.className;var s=(typeof cn==='string')?cn:((cn&&cn.baseVal)||'');var id=(el.id||'').toLowerCase();if(id)s+=' '+id;return s.toLowerCase().split(/[^a-z0-9]+/);}
function badName(el){var t=toks(el);for(var i=0;i<t.length;i++){if(t[i]&&BAD[t[i]])return true;}return false;}
function hide(el){el.style.setProperty('display','none','important');return 1;}
var killed=0;
function scan(root){
var list;try{list=root.querySelectorAll('iframe,div,ins,section,aside,a');}catch(e){return;}
for(var i=0;i<list.length;i++){
var el=list[i];
if(!el||!el.style||el===document.body||el===document.documentElement)continue;
var cs;try{cs=getComputedStyle(el);}catch(e){continue;}
if(!cs||cs.display==='none'||cs.visibility==='hidden')continue;
var r;try{r=el.getBoundingClientRect();}catch(e){continue;}
var area=r.width*r.height;if(area<=0)continue;
var floating=(cs.position==='fixed'||cs.position==='absolute');
if(floating&&r.width>=vw*0.85&&r.height>=vh*0.85){
var z=parseInt(cs.zIndex||'0',10)||0;
var hasForm=false;try{hasForm=!!el.querySelector('input,textarea,video,button[aria-label],select');}catch(e){}
if(!hasForm&&z>=500){
var clickable=false;
try{
if(el.tagName==='A')clickable=true;
else{var a=el.querySelector('a');
if(a){var ar=a.getBoundingClientRect();clickable=(ar.width>=r.width*0.6&&ar.height>=r.height*0.5);}}
if(!clickable)clickable=badName(el);
}catch(e){}
if(clickable){killed+=hide(el);continue;}
}
}
if(floating&&badName(el)){var busy=false;try{busy=!!el.querySelector('input,video');}catch(e){}
if(!busy&&area>=vw*vh*0.015){killed+=hide(el);continue;}}
if(el.tagName==='IFRAME'){
var src=(el.getAttribute('src')||'').toLowerCase();
var nm=((el.name||'')+' '+(el.id||'')).toLowerCase();
if(src.indexOf('googlesyndication')>=0||src.indexOf('doubleclick')>=0||nm.indexOf('google_ads_iframe')>=0||badName(el)){
if(r.height>=40){killed+=hide(el);continue;}}
}
}
}
scan(document);
var mo=new MutationObserver(function(muts){
for(var i=0;i<muts.length;i++){
var m=muts[i];
if(m.addedNodes){for(var j=0;j<m.addedNodes.length;j++){var n=m.addedNodes[j];if(n&&n.nodeType===1){try{scan(n);}catch(e){}}}}
}
});
try{mo.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
return killed;
})()"""

    /**
     * 手动"清理浮层广告"（菜单触发，激进版）：移除所有覆盖约半屏以上的 fixed/absolute
     * 大块遮罩（保留含视频的容器），外加角落悬浮图文广告。刷新页面即可完全恢复。
     */
    const val STRIP_OVERLAY_JS = """(function(){
var vw=window.innerWidth||document.documentElement.clientWidth;
var vh=window.innerHeight||document.documentElement.clientHeight;
var BAD={'ad':1,'ads':1,'adv':1,'advert':1,'pop':1,'popup':1,'float':1,'floatad':1,'coupon':1,'promo':1,'activity':1,'banner':1,'mask':1,'modal':1,'layer':1};
var killed=0;
var list=document.querySelectorAll('body *');
for(var i=0;i<list.length;i++){
var el=list[i];
if(!el||!el.style||el===document.body||el===document.documentElement)continue;
var tag=el.tagName;
if(tag==='SCRIPT'||tag==='STYLE'||tag==='LINK'||tag==='META'||tag==='NOSCRIPT'||tag==='HEAD')continue;
var cs;try{cs=getComputedStyle(el);}catch(e){continue;}
if(!cs||cs.display==='none'||cs.visibility==='hidden')continue;
if(cs.position!=='fixed'&&cs.position!=='absolute')continue;
var r;try{r=el.getBoundingClientRect();}catch(e){continue;}
if(r.width<=0||r.height<=0)continue;
var hasVideo=false;try{hasVideo=!!el.querySelector('video');}catch(e){}
if(hasVideo)continue;
if(r.width>=vw*0.45&&r.height>=vh*0.45){el.style.setProperty('display','none','important');killed++;continue;}
var cn=el.className;var nameStr=((el.id||'')+' '+((typeof cn==='string')?cn:((cn&&cn.baseVal)||''))).toLowerCase();
var toks=nameStr.split(/[^a-z0-9]+/);var named=false;
for(var t=0;t<toks.length;t++){if(toks[t]&&BAD[toks[t]]){named=true;break;}}
if(named){
var hasLinkImg=false;
try{hasLinkImg=!!el.querySelector('a img')||(!!el.querySelector('a')&&!!el.querySelector('img'));}catch(e){}
if(cs.position==='fixed'&&hasLinkImg&&r.width<vw*0.45){el.style.setProperty('display','none','important');killed++;}
}
}
return killed;
})()"""
}
