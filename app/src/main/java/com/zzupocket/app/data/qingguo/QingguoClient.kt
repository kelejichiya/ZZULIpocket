package com.zzupocket.app.data.qingguo

import com.zzupocket.app.core.AppError
import com.zzupocket.app.core.school.SchoolProfile
import com.zzupocket.app.data.auth.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.security.MessageDigest

/**
 * 青果 KINGOSOFT 教务客户端（郑州轻工业大学）。
 *
 * 与郑大版 CAS/EAS 的差别：
 *  - 没有 JWT；登录成功后服务端只发一个 `JSESSIONID` 会话 Cookie，
 *    所有业务页面（课表、成绩、考试、学籍）都靠它；
 *  - 登录参数要走 `jkingo.des.js` 的加密（见 [KingoDes]）；
 *  - 页面是 GBK 编码的 JSP/HTML，不是 JSON。
 *
 * 会话 Cookie 落到 [TokenStore]（加密 SharedPreferences），重启 App 不必重新登录；
 * 服务端超时后由 [fetchText] 识别"返回了登录页"并抛 [AppError.ReLoginRequired]。
 */
class QingguoClient(
    private val baseUrl: String = SchoolProfile.JWGL_BASE,
    private val http: OkHttpClient,
    private val json: Json,
    private val store: TokenStore,
) {

    data class LoginResult(val account: String, val displayName: String?)

    /** 内存里的 Cookie；退出/重新登录时清空 */
    private val cookies = linkedMapOf<String, String>()

    init {
        store.qingguoCookie?.takeIf { it.isNotBlank() }?.let { raw ->
            raw.split(';').forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) cookies[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
            }
        }
    }

    fun isLoggedIn(): Boolean = !store.qingguoAccount.isNullOrBlank() && cookies.isNotEmpty()

    fun account(): String? = store.qingguoAccount

    fun displayName(): String? = store.qingguoName

    fun cookieHeader(): String = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    fun savedAt(): Long = store.qingguoCookieAt

    // ── 登录 ─────────────────────────────────────────────────────────────

    /**
     * 账密登录。
     *
     * 步骤与浏览器端 `loginbar.js` 完全一致：
     *  1. GET 登录页，取 `_sessionid` 与 JSESSIONID
     *  2. GET 临时 DES key 与服务器时间
     *  3. `password = md5(md5(pwd) + md5(""))`，`username = base64(账号;;sessionid)`
     *  4. `token = md5(md5(params) + md5(nowtime))`，`params = base64(strEnc(params, deskey))`
     *  5. POST /cas/logon.action
     */
    suspend fun login(account: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val acc = account.trim()
        if (acc.isEmpty() || password.isEmpty()) throw AppError.BadCredentials("请输入学号和密码")

        cookies.clear()
        store.qingguoCookie = null

        val loginPage = fetchText(SchoolProfile.LOGIN_PAGE, allowLoginPage = true, requireSession = false)
        val sessionId = SESSION_ID_RE.find(loginPage)?.groupValues?.get(1)
            ?: extractSessionIdFromCookie()
            ?: throw AppError.Parse("登录页里找不到 _sessionid（青果改版了？）")

        val desKey = fetchText(SchoolProfile.TEMP_DESKEY, allowLoginPage = true, requireSession = false).trim()
        val nowTime = fetchText(SchoolProfile.TEMP_NOWTIME, allowLoginPage = true, requireSession = false).trim()
        if (desKey.isEmpty() || nowTime.isEmpty()) {
            throw AppError.Parse("取不到临时 DES key / 服务器时间")
        }

        val pwd = md5(md5(password) + md5(""))
        val user = Base64.getEncoder().encodeToString("$acc;;$sessionId".toByteArray(Charsets.UTF_8))

        // 浏览器端这些字段是按真实密码算的（loginbar.js 的 checkpwd/isPasswordPolicy），
        // 写死成常量会被服务端判成"经非法处理的登录信息"。
        val expression = password.fold(0) { acc0, c ->
            acc0 or when {
                c.isDigit() -> 8
                c in 'a'..'z' -> 4
                c in 'A'..'Z' -> 2
                else -> 1
            }
        }
        val userZh = if (password.lowercase().contains(acc.lowercase())) 1 else 0
        val policy = if (password.length >= 6 && password != acc) 1 else 0

        val params = buildString {
            append("_u=").append(user)
            append("&_p=").append(pwd)
            append("&randnumber=")
            append("&isPasswordPolicy=").append(policy)
            append("&txt_mm_expression=").append(expression)
            append("&txt_mm_length=").append(password.length)
            append("&txt_mm_userzh=").append(userZh)
            append("&hid_flag=1&hidlag=1&hid_dxyzm=")
        }
        val token = md5(md5(params) + md5(nowTime))
        val encoded = KingoDes.encrypt(params, desKey)

        val body = FormBody.Builder()
            .add("params", encoded)
            .add("token", token)
            .add("timestamp", nowTime)
            .add("deskey", desKey)
            .add("ssessionid", sessionId)
            .build()

        val response = execute(
            Request.Builder()
                .url("$baseUrl${SchoolProfile.LOGON_ACTION}")
                .header("Referer", "$baseUrl${SchoolProfile.LOGIN_PAGE}")
                .header("User-Agent", UA)
                .post(body)
                .build(),
        )
        val text = decode(response)
        response.close()

        val status = runCatching {
            json.parseToJsonElement(text).let { it as? JsonObject }
                ?.get("status")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val message = runCatching {
            json.parseToJsonElement(text).let { it as? JsonObject }
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()

        if (status != "200") {
            val readable = message.ifBlank { "登录失败（HTTP $status）" }
            // 只记状态码与服务端文案，不记账号密码
            android.util.Log.w("QingguoLogin", "登录被拒 status=$status msg=$readable")
            throw if (status == "401" || status == "402" || readable.contains("密码") || readable.contains("账号")) {
                AppError.BadCredentials("$readable（HTTP $status）")
            } else {
                AppError.Server(status?.toIntOrNull() ?: -1, "$readable（HTTP $status）")
            }
        }

        // 登录成功后 JSESSIONID 可能换新 -> 把响应里的 Set-Cookie 合并进来
        persistCookies()

        // 青果登录接口返回的是 `result` 跳转地址 —— 浏览器端会 location.href 过去，
        // 会话认证就发生在那次请求里；不跟这一步，后续业务页一律"凭证已失效"。
        val resultUrl = runCatching {
            json.parseToJsonElement(text).let { it as? JsonObject }
                ?.get("result")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (!resultUrl.isNullOrBlank()) {
            runCatching { fetchText(resultUrl, allowLoginPage = true) }
        }

        if (!isSessionValid()) {
            cookies.clear()
            store.qingguoCookie = null
            throw AppError.Server(0, "账号密码登录成功，但教务会话没有建立。请改用「扫码 / 短信登录」。")
        }

        store.qingguoAccount = acc
        store.qingguoCookieAt = System.currentTimeMillis()
        store.lastLoginAt = store.qingguoCookieAt

        val name = runCatching { fetchStudentName() }.getOrNull()
        if (!name.isNullOrBlank()) store.qingguoName = name
        LoginResult(acc, name)
    }

    // ── 扫码登录（喜鹊儿）──────────────────────────────────────────────
    // 对齐 loginbar.js 的二维码流程：
    //   1. 生成 `smdljwxt + 16 位随机串` 作为二维码内容；
    //   2. 轮询 POST /frame/LoginBar.jsp {operate=query, qrCode=token}，扫到后返回学号；
    //   3. POST /cas/logon.action {username=学号, password=token, loginmethod=xiqueer}
    //      —— 这条路径不需要密码，也不走 DES 加密。

    suspend fun startQrLogin(): String = withContext(Dispatchers.IO) {
        cookies.clear()
        store.qingguoCookie = null
        fetchText(SchoolProfile.LOGIN_PAGE, allowLoginPage = true, requireSession = false)
        "smdljwxt" + (1..16).map { QR_CHARS.random() }.joinToString("")
    }

    /** 扫到后返回学号；还没扫返回 null */
    suspend fun pollQrLogin(token: String): String? = withContext(Dispatchers.IO) {
        val text = runCatching {
            postForm("/frame/LoginBar.jsp", mapOf("operate" to "query", "qrCode" to token))
        }.getOrNull().orEmpty().trim()
        text.takeIf { it.length in 4..40 && !it.contains("<") }
    }

    /** 用二维码 token 完成登录（`loginmethod=xiqueer`，密码位置传 token） */
    suspend fun loginByQr(username: String, token: String): LoginResult = withContext(Dispatchers.IO) {
        val text = postForm(
            "/cas/logon.action",
            mapOf(
                "username" to username,
                "password" to token,
                "loginmethod" to "xiqueer",
            ),
            referer = "$baseUrl${SchoolProfile.LOGIN_PAGE}",
        )
        val status = runCatching {
            json.parseToJsonElement(text).let { it as? JsonObject }
                ?.get("status")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val message = runCatching {
            json.parseToJsonElement(text).let { it as? JsonObject }
                ?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        if (status != "200") {
            android.util.Log.w("QingguoLogin", "扫码登录被拒 status=$status msg=$message")
            throw AppError.Server(status?.toIntOrNull() ?: -1, message.ifBlank { "扫码登录失败" })
        }
        val resultUrl = runCatching {
            json.parseToJsonElement(text).let { it as? JsonObject }
                ?.get("result")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (!resultUrl.isNullOrBlank()) runCatching { fetchText(resultUrl, allowLoginPage = true) }

        if (!isSessionValid()) {
            throw AppError.Server(0, "扫码成功但教务会话未建立，请刷新二维码重试")
        }
        persistCookies()
        store.qingguoAccount = username
        store.qingguoCookieAt = System.currentTimeMillis()
        store.lastLoginAt = store.qingguoCookieAt
        val name = runCatching { fetchStudentName() }.getOrNull()
        if (!name.isNullOrBlank()) store.qingguoName = name
        LoginResult(username, name)
    }

    /** 从 CAS 登录后的 CookieJar 里接管教务域会话 */
    fun adoptFromJar(jar: DomainCookieJar): Boolean {
        val list = jar.cookiesFor("jwgl.zzuli.edu.cn")
        if (list.isEmpty()) return false
        cookies.clear()
        list.forEach { cookies[it.name] = it.value }
        persistCookies()
        store.qingguoCookieAt = System.currentTimeMillis()
        store.lastLoginAt = store.qingguoCookieAt
        return true
    }

    /** 会话是否真的可用（有些"登录成功"其实只拿到了未认证的 JSESSIONID） */
    suspend fun isSessionValid(): Boolean {
        if (cookies.isEmpty()) return false
        val html = runCatching { fetchText("/frame/homes.action", allowLoginPage = true) }.getOrNull()
        if (html == null) {
            android.util.Log.w("QingguoLogin", "会话校验：请求失败")
            return false
        }
        val bad = looksLikeLogin(html) || html.contains("凭证已失效")
        if (bad) {
            android.util.Log.w(
                "QingguoLogin",
                "会话校验失败：len=${html.length} head=" +
                    html.replace(Regex("\\s+"), " ").take(240),
            )
        }
        return !bad
    }

    /** 退出：只清本地会话（服务端会话会随超时自然失效，不额外发请求） */
    fun logout() {
        cookies.clear()
        store.qingguoCookie = null
        store.qingguoCookieAt = 0L
        store.qingguoAccount = null
        store.qingguoName = null
    }

    /**
     * 网页（短信/扫码）登录成功后清掉 OkHttp 这边的旧会话。
     *
     * 用户以前可能用过账密登录，[cookies] 里会留着一套早已过期的 Cookie；
     * 如果不清，课表取数时会在 WebView 失败后回退到这套旧会话，
     * 结果是「网页明明登录着，却提示登录失效」。
     */
    fun clearCookies() {
        cookies.clear()
        store.qingguoCookie = null
        store.qingguoCookieAt = 0L
    }

    /**
     * 采用 WebView 里登录后落下的会话 Cookie（扫码 / 统一身份认证 / 短信都能走这条路）。
     *
     * 这是「没有青果密码」时的主路径：用户在 App 内置网页里完成学校自己的登录，
     * 我们只取会话，不接触密码。
     *
     * @param accountHint 用户事先填的学号（可空）；为空时尝试从首页识别
     * @return 是否拿到了可用会话
     */
    suspend fun adoptCookies(raw: String?, accountHint: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (raw.isNullOrBlank()) return@withContext false
        val parsed = linkedMapOf<String, String>()
        raw.split(';').forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) parsed[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
        }
        if (parsed.isEmpty()) return@withContext false

        cookies.clear()
        cookies.putAll(parsed)
        persistCookies()
        store.qingguoCookieAt = System.currentTimeMillis()
        store.lastLoginAt = store.qingguoCookieAt
        android.util.Log.i("QingguoLogin", "采用会话：cookie 名=${cookies.keys}")

        // ⚠️ 页面"跳回教务首页"不代表会话一定有效：WebView 可能在 CAS 回调完成前就触发了
        // onPageFinished，拿到的还是未认证的 JSESSIONID。这里必须实际请求一次验证。
        if (!isSessionValid()) {
            android.util.Log.w("QingguoLogin", "adoptCookies：拿到的会话无效（可能过早抓取）")
            cookies.clear()
            store.qingguoCookie = null
            store.qingguoCookieAt = 0L
            return@withContext false
        }

        val hint = accountHint?.trim().orEmpty()
        if (hint.isNotEmpty()) store.qingguoAccount = hint
        if (store.qingguoAccount.isNullOrBlank()) {
            discoverAccount()?.let { store.qingguoAccount = it }
        }
        if (store.qingguoName.isNullOrBlank()) {
            runCatching { fetchStudentName() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { store.qingguoName = it }
        }
        return@withContext cookies.isNotEmpty()
    }

    /** 从首页/菜单里认出学号（拿不到就返回 null，由界面让用户手填） */
    suspend fun discoverAccount(): String? {
        val pages = listOf(
            "/frame/homes.action",
            SchoolProfile.profilePath,
            // 学籍页的数据是 Ajax 拉这个接口渲染的，直接请求它比解析 HTML 壳更可靠
            "/student/STU_BaseInfoAction.do?hidOption=InitData",
            "/student/STU_BaseInfoAction.do?hidOption=InitData&menucode_current=SB11",
        )
        // 轻工大学号是 54 + 年级 + 序号（12 位），不能只认 20 开头
        val patterns = listOf(
            Regex("""["']xh["']\s*[:=]\s*["'](\d{10,14})["']"""),
            Regex("""[?&]xh=(\d{10,14})"""),
            Regex("""学号[^0-9]{0,60}(\d{10,14})"""),
        )
        for (page in pages) {
            val html = runCatching { fetchText(page, allowLoginPage = true) }.getOrNull() ?: continue
            for (p in patterns) {
                p.find(html)?.groupValues?.get(1)?.let { return it }
            }
        }
        // 学籍表单是 Ajax **POST** 出来的（页面里是 Ajax.doPost），只发 GET 拿不到数据
        val ajaxPages = listOf(
            "/student/STU_BaseInfoAction.do?hidOption=InitData",
            "/student/STU_BaseInfoAction.do?hidOption=InitData&menucode_current=SB11",
        )
        for (page in ajaxPages) {
            val html = runCatching {
                postForm(page, emptyMap(), referer = "$baseUrl${SchoolProfile.profilePath}")
            }.getOrNull() ?: continue
            for (p in patterns) {
                p.find(html)?.groupValues?.get(1)?.let { return it }
            }
        }
        return null
    }

    /** WebView 登录后补填学号 */
    fun setAccount(account: String) {
        val acc = account.trim()
        if (acc.isNotEmpty()) store.qingguoAccount = acc
    }

    // ── 页面请求 ─────────────────────────────────────────────────────────

    suspend fun fetchText(
        path: String,
        allowLoginPage: Boolean = false,
        referer: String? = null,
        requireSession: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        if (requireSession && cookies.isEmpty()) throw AppError.ReLoginRequired()
        val builder = Request.Builder()
            .url(absolute(path))
            .header("User-Agent", UA)
            .get()
        if (cookies.isNotEmpty()) builder.header("Cookie", cookieHeader())
        referer?.let { builder.header("Referer", it) }
        val response = execute(builder.build())
        try {
            val text = decode(response)
            if (!allowLoginPage && looksLikeLogin(text)) throw AppError.ReLoginRequired()
            text
        } finally {
            response.close()
        }
    }

    suspend fun postForm(
        path: String,
        form: Map<String, String>,
        referer: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val builder = Request.Builder()
            .url(absolute(path))
            .header("User-Agent", UA)
            .header("Cookie", cookieHeader())
            .post(body)
        referer?.let { builder.header("Referer", it) }
        val response = execute(builder.build())
        try {
            decode(response)
        } finally {
            response.close()
        }
    }

    /** `params=base64("xn=..&xq=..&xh=..")` 这类页面直接把查询串塞进 `params` */
    fun paramsOf(vararg pairs: Pair<String, String>): String =
        Base64.getEncoder().encodeToString(
            pairs.joinToString("&") { "${it.first}=${it.second}" }.toByteArray(Charsets.UTF_8),
        )

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun absolute(path: String): String =
        if (path.startsWith("http")) path else "$baseUrl/${path.trimStart('/')}"

    private fun execute(request: Request): Response {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw AppError.Network("无法连接教务系统，请检查网络（青果仅校园网可达）", e)
        }
        mergeSetCookies(response)
        if (response.code == 401 || response.code == 403) {
            response.close()
            throw AppError.ReLoginRequired("教务会话已失效，请重新登录")
        }
        return response
    }

    private fun mergeSetCookies(response: Response) {
        val headers = response.headers("Set-Cookie")
        if (headers.isEmpty()) return
        headers.forEach { raw ->
            val first = raw.substringBefore(';')
            val idx = first.indexOf('=')
            if (idx > 0) cookies[first.substring(0, idx).trim()] = first.substring(idx + 1).trim()
        }
        persistCookies()
    }

    private fun persistCookies() {
        if (cookies.isNotEmpty()) store.qingguoCookie = cookieHeader()
    }

    private fun extractSessionIdFromCookie(): String? = cookies["JSESSIONID"]

    private fun decode(response: Response): String {
        val bytes = response.body?.bytes() ?: return ""
        val cs = runCatching {
            response.header("Content-Type")
                ?.substringAfter("charset=", "")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        if (!cs.isNullOrBlank()) {
            return runCatching { String(bytes, charsetOf(cs)) }.getOrElse { String(bytes) }
        }
        // 青果多数页面是 GBK 且不声明 charset：先按 UTF-8 严格解码，失败再按 GB18030
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }.getOrElse {
            String(bytes, charsetOf("GB18030"))
        }
    }

    private fun charsetOf(name: String): java.nio.charset.Charset = runCatching {
        java.nio.charset.Charset.forName(name)
    }.getOrDefault(Charsets.UTF_8)

    private fun looksLikeLogin(html: String): Boolean =
        (html.contains("name=\"username\"") && html.contains("logon.action")) ||
            (html.contains("_sessionid") && html.contains("cas/login")) ||
            html.contains("凭证已失效") ||
            (html.contains("请重新登录") && html.length < 2000)

    /** 首页出现 "姓名：张三" / "学生姓名" 之类时尽量取出来，取不到就返回 null */
    private suspend fun fetchStudentName(): String? {
        val html = runCatching { fetchText(SchoolProfile.homePath, allowLoginPage = true) }.getOrNull() ?: return null
        val patterns = listOf(
            Regex("姓名[：:]\\s*([\\u4e00-\\u9fa5]{2,10})"),
            Regex("学生姓名[：:]\\s*<[^>]*>\\s*([\\u4e00-\\u9fa5]{2,10})"),
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            val name = m.groupValues[1].trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        private val SESSION_ID_RE = Regex("var\\s+_sessionid\\s*=\\s*\"([A-F0-9]+)\"")

        /** 二维码 token 的字符集（与 loginbar.js 的 uuid 生成一致） */
        private val QR_CHARS: List<Char> =
            ('0'..'9').toList() + ('a'..'z').toList() + ('A'..'Z').toList()

        fun md5(text: String): String =
            MessageDigest.getInstance("MD5")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        @Suppress("unused")
        fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
