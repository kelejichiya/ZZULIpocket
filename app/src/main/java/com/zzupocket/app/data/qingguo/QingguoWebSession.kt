package com.zzupocket.app.data.qingguo

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.zzupocket.app.core.AppGraph
import com.zzupocket.app.core.school.SchoolProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 教务 WebView 会话。
 *
 * 为什么需要它：青果/CAS 的登录态 Cookie 是 **HttpOnly** 的，`CookieManager.getCookie()`
 * 读不到，OkHttp 也就带不上；但 WebView 自己发请求时一定会带上。
 *
 * 所以登录（短信 / i轻工大扫码 / 统一认证）完成后，保留这个 WebView，
 * 之后所有教务页面都用它内部的 `fetch()` 取回来 —— 同源请求，Cookie 自动携带。
 */
object QingguoWebSession {

    private val main = Handler(Looper.getMainLooper())
    private val seq = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, (String?) -> Unit>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var ready = false

    /**
     * 最近一次 `onPageFinished` 的 URL。
     *
     * 光有 [ready] 不够：它只表示"不是 CAS 登录页"，但可能还停在 `kys.zzuli.edu.cn`
     * 的错误页上。判断"是不是真的回到教务域"要看 host。
     */
    @Volatile
    private var lastFinishedUrl: String? = null

    /** 上次尝试静默续期的时刻；避免网络抖动时连续 reload 登录页 */
    @Volatile
    private var lastRenewAt = 0L

    /** 这个 WebView 是否真的落到过教务域（用它区分"网页登录"和"纯账密登录"） */
    @Volatile
    private var everLanded = false

    private val loadWaiters = mutableListOf<() -> Unit>()

    private const val RENEW_COOLDOWN_MS = 30_000L

    /** 登录页的地址（KYS 统一身份认证，带 service 回调） */
    const val LOGIN_URL =
        "https://kys.zzuli.edu.cn/cas/login?service=https%3A%2F%2Fjwgl.zzuli.edu.cn%2Fcaslogin"

    /** 在 Activity/Application 里调用一次；可重复调用 */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(context: Context): WebView {
        webView?.let { return it }
        val wv = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "DSHBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                ): Boolean {
                    // 移动端登录页偶尔会把页面顶到 ipp 校内门户（校外访问超时），直接拦掉
                    val host = request?.url?.host.orEmpty()
                    return host == "iapp.zzuli.edu.cn" || host.endsWith(".iapp.zzuli.edu.cn")
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url != null && url.contains("iapp.zzuli.edu.cn")) {
                        view?.stopLoading()
                        view?.loadUrl(LOGIN_URL)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    lastFinishedUrl = url
                    if (isJwglUrl(url)) everLanded = true
                    if (!url.isNullOrBlank() && !url.contains("cas/login")) ready = true
                    val waiters = synchronized(loadWaiters) {
                        val copy = loadWaiters.toList()
                        loadWaiters.clear()
                        copy
                    }
                    waiters.forEach { it() }
                }
            }
        }
        webView = wv
        ready = false
        lastFinishedUrl = null
        wv.loadUrl(LOGIN_URL)
        return wv
    }

    fun current(): WebView? = webView

    fun isReady(): Boolean = ready

    /** 打开登录页（重新登录时用） */
    fun reloadLogin() {
        ready = false
        lastFinishedUrl = null
        webView?.loadUrl(LOGIN_URL)
    }

    /**
     * 冷启动后第一次取教务数据前，确保 WebView 已经建好。
     *
     * 原来只有课表页会 attach；用户如果直接点「成绩 / 考试」，WebView 还是 null，
     * 页面会显示"登录已失效"，其实只是没建 WebView。
     */
    suspend fun ensureAttached(): Boolean {
        if (webView != null) return true
        val ctx = AppGraph.appContextOrNull() ?: return false
        return withContext(Dispatchers.Main) {
            attach(ctx)
            true
        }
    }

    private fun isJwglUrl(url: String?): Boolean =
        url != null && url.contains("jwgl.zzuli.edu.cn") && !url.contains("cas/login")

    /** 等 WebView 从 CAS 登录页跳回教务域（第一次 fetch 之前用） */
    private suspend fun awaitJwgl(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isJwglUrl(lastFinishedUrl)) return true
            delay(200)
        }
        return isJwglUrl(lastFinishedUrl)
    }

    /**
     * 用统一身份认证（KYS CAS）的 SSO 会话静默续期。
     *
     * 青果的 `JSESSIONID` 闲置超时后，只要 CAS 的 TGT 还在，重新打开带 service 的登录页
     * 就会自动 302 回来并换一张新会话票 —— 用户不需要再收一次短信验证码。
     *
     * @return true = 已经回到教务域且会话可用；false = CAS 也过期了/网络不通，需要用户登录
     */
    suspend fun renewViaCas(timeoutMs: Long = 10_000): Boolean {
        val wv = webView ?: return false
        val now = System.currentTimeMillis()
        if (now - lastRenewAt < RENEW_COOLDOWN_MS) return false
        lastRenewAt = now
        ready = false
        lastFinishedUrl = null
        android.util.Log.i("QingguoSession", "会话可能过期，尝试 CAS 静默续期")
        withContext(Dispatchers.Main) { wv.loadUrl(LOGIN_URL) }
        if (!awaitJwgl(timeoutMs)) {
            android.util.Log.w("QingguoSession", "静默续期失败：CAS 没有自动跳回教务域（TGT 可能也过期了）")
            return false
        }
        val ok = isValid()
        android.util.Log.i("QingguoSession", if (ok) "静默续期成功" else "静默续期后会话仍不可用")
        return ok
    }

    /** 是否是"请重新登录"类的页面（页面里出现登录表单/凭证失效提示） */
    fun looksLikeLoginPage(html: String): Boolean =
        (html.contains("name=\"username\"") && html.contains("logon.action")) ||
            (html.contains("_sessionid") && html.contains("cas/login")) ||
            html.contains("凭证已失效") ||
            (html.contains("请重新登录") && html.length < 2000)

    /** WebView 当前是不是停在登录页（静默续期失败后用它区分"真过期"和"网络不通"） */
    fun isAtLoginPage(): Boolean = lastFinishedUrl?.contains("cas/login") == true

    /** WebView 是否真的登录过教务（网页登录 = true；纯账密登录可能一直停在 CAS 登录页） */
    fun hasLanded(): Boolean = everLanded

    /** 已登录的 WebView 里取一个教务页面；失败/跨域返回 null */
    suspend fun fetch(path: String): String? = request(path, null)

    /** POST 表单（青果的成绩/考试数据页都是二次 POST 加载） */
    suspend fun post(path: String, form: Map<String, String>): String? = request(path, form)

    private suspend fun request(path: String, form: Map<String, String>?): String? {
        val created = webView == null
        if (created) {
            if (!ensureAttached()) return null
            // 刚建好的 WebView 还在走 CAS 自动登录，等它落回教务域再发 fetch；
            // 否则从 kys 页面 cross-origin fetch jwgl 会直接失败，被上层误判成"登录失效"
            awaitJwgl(5_000)
        } else {
            val finished = lastFinishedUrl
            if (finished == null || finished.contains("cas/login")) awaitJwgl(5_000)
        }
        val wv = webView ?: return null
        val url = if (path.startsWith("http")) path else SchoolProfile.JWGL_BASE + path
        return suspendCancellableCoroutine { cont ->
            val id = seq.incrementAndGet()
            pending[id] = { text ->
                if (cont.isActive) cont.resume(text?.takeUnless { it == "__ERR__" })
            }
            main.post {
                val body = form?.entries?.joinToString("&") {
                    java.net.URLEncoder.encode(it.key, "UTF-8") + "=" +
                        java.net.URLEncoder.encode(it.value, "UTF-8")
                }
                val init = if (body == null) {
                    "{credentials:'include'}"
                } else {
                    "{method:'POST',credentials:'include'," +
                        "headers:{'Content-Type':'application/x-www-form-urlencoded'}," +
                        "body:'$body'}"
                }
                val js = """
                    (function(){
                      fetch('$url', $init)
                        .then(function(r){ return r.arrayBuffer(); })
                        .then(function(b){
                          var t;
                          try {
                            t = new TextDecoder('utf-8', {fatal:true}).decode(b);
                          } catch (e) {
                            t = new TextDecoder('gbk').decode(b);
                          }
                          DSHBridge.post('$id', t);
                        })
                        .catch(function(e){ DSHBridge.post('$id', '__ERR__'); });
                    })();
                """.trimIndent()
                wv.evaluateJavascript(js, null)
            }
            cont.invokeOnCancellation { pending.remove(id) }
        }
    }

    /** 会话是否可用：能取到教务首页且不是"凭证已失效" */
    suspend fun isValid(): Boolean {
        val html = fetch("/frame/homes.action") ?: return false
        return !looksLikeLoginPage(html)
    }

    /** 打开页面并执行一段 JS；脚本里用 `DSHBridge.post('__ID__', 数据)` 回传结果 */
    suspend fun runScript(
        path: String,
        scriptTemplate: String,
        timeoutMs: Long = 30_000,
    ): String? {
        val created = webView == null
        if (created) {
            if (!ensureAttached()) return null
            awaitJwgl(5_000)
        } else {
            val finished = lastFinishedUrl
            if (finished == null || finished.contains("cas/login")) awaitJwgl(5_000)
        }
        val wv = webView ?: return null
        val url = if (path.startsWith("http")) path else SchoolProfile.JWGL_BASE + path
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                synchronized(loadWaiters) {
                    loadWaiters.add { if (cont.isActive) cont.resume(true) }
                }
                main.post { wv.loadUrl(url) }
            }
        } ?: return null
        kotlinx.coroutines.delay(600)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val id = seq.incrementAndGet()
                pending[id] = { text -> if (cont.isActive) cont.resume(text) }
                main.post {
                    val js = "(function(){ " + scriptTemplate.replace("__ID__", id.toString()) + " })();"
                    wv.evaluateJavascript(js, null)
                }
                cont.invokeOnCancellation { pending.remove(id) }
            }
        }
    }

    /** 把 OkHttp 拿到的教务 Cookie 注入 WebView（扫码登录后抓学号用） */
    fun injectCookies(header: String, baseUrl: String = SchoolProfile.JWGL_BASE) {
        val cm = CookieManager.getInstance()
        header.split(";").forEach { part ->
            val kv = part.trim()
            if (kv.contains("=")) cm.setCookie(baseUrl, kv)
        }
        cm.flush()
    }

    fun clear() {
        CookieManager.getInstance().removeAllCookies { }
        ready = false
        everLanded = false
        reloadLogin()
    }

    class Bridge {
        @JavascriptInterface
        fun post(id: String, text: String) {
            pending.remove(id.toIntOrNull())?.invoke(text)
        }
    }
}
