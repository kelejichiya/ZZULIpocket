package com.zzupocket.app.data.qingguo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.random.Random

/**
 * 学校统一身份认证的「扫码登录」（i轻大 APP 扫）。
 *
 * 协议来自 KYS 登录页 `createQrCode` / `getAuthorization`：
 *  1. 生成 16 位 uuid，二维码内容 `https://kys.zzuli.edu.cn/cas/openAuth?uuid=<uuid>`；
 *  2. 轮询 `POST /cas/casSweepCodeLoginQueryController`（uuid + token），
 *     `obj.code` 依次为 waitSweep → waitAuthorized → alreadyAuthorized；
 *  3. 已授权后 GET `/cas/login?service=<教务 caslogin>`，CAS 会带 ticket 跳回教务，
 *     教务会话就落在同一个 CookieJar 里（HttpOnly 也不影响 OkHttp）。
 */
class KysQrLogin(
    private val http: OkHttpClient,
    private val jar: DomainCookieJar,
    private val json: Json,
) {

    data class Poll(val code: String, val token: String?)

    /** 生成二维码并拿回 uuid（先访问一次登录页，建立 KYS 会话） */
    suspend fun start(): String = withContext(Dispatchers.IO) {
        jar.clear()
        get(loginUrl())
        newUuid()
    }

    fun qrContent(uuid: String): String = "$BASE/cas/openAuth?uuid=$uuid"

    suspend fun poll(uuid: String, token: String): Poll = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("uuid", uuid)
            .add("token", token)
            .build()
        val request = Request.Builder()
            .url("$BASE/cas/casSweepCodeLoginQueryController")
            .header("User-Agent", UA)
            .header("Referer", loginUrl())
            .header("X-Requested-With", "XMLHttpRequest")
            .post(body)
            .build()
        val text = execute(request)
        val obj = parseObj(text)
        val code = obj?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
        val newToken = obj?.get("token")?.jsonPrimitive?.contentOrNull
        android.util.Log.i("QingguoQr", "poll code=$code tokenSet=${!newToken.isNullOrBlank()}")
        Poll(code = code, token = newToken)
    }

    /** 已授权：走一次 CAS 登录，拿教务会话 */
    suspend fun complete(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var detail = ""
        // 原始 service 与编码后的 service 各试一次：不同 CAS 版本对 query 编码要求不同
        val candidates = listOf(
            loginUrl(),
            "$BASE/cas/login?service=" + java.net.URLEncoder.encode(SERVICE, "UTF-8"),
        )
        for (url in candidates) {
            val result = runCatching { getWithResponse(url) }
            val pair = result.getOrNull()
            val status = pair?.first ?: -1
            val finalUrl = pair?.second ?: ""
            val errorText = result.exceptionOrNull()?.let { "，异常：${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
            val jwglCookies = jar.cookiesFor("jwgl.zzuli.edu.cn").map { it.name }
            detail = "HTTP $status$errorText，最终地址 $finalUrl，教务 Cookie=$jwglCookies"
            android.util.Log.i("QingguoQr", "complete $url -> $detail")
            // 关键：只要教务已经下发 JSESSIONID 就算换到了会话；
            // 跳转链最后一步偶尔会抛异常（重定向/连接被重置），不能因此判失败。
            if (jwglCookies.any { it.equals("JSESSIONID", true) }) {
                return@withContext true to detail
            }
        }
        false to detail
    }

    private fun loginUrl(): String =
        "$BASE/cas/login?service=$SERVICE"

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
        return execute(request)
    }

    private fun getWithResponse(url: String): Pair<Int, String> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Referer", "$BASE/cas/login?service=$SERVICE")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            return resp.code to resp.request.url.toString()
        }
    }

    private fun execute(request: Request): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("无法连接统一身份认证：${e.message}", e)
        }
        response.use {
            if (!it.isSuccessful) throw IOException("统一认证返回 HTTP ${it.code}")
            return it.body?.string().orEmpty()
        }
    }

    /** 服务端返回的是"字符串里再套一层 JSON"，这里两种形态都兼容 */
    private fun parseObj(text: String): JsonObject? {
        val trimmed = text.trim().removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        val candidates = listOf(trimmed, text.trim())
        for (raw in candidates) {
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: continue
            val obj = root["obj"] as? JsonObject ?: continue
            return obj
        }
        return null
    }

    private fun newUuid(): String {
        val hex = "0123456789abcdef"
        val chars = CharArray(16) { hex[Random.nextInt(16)] }
        chars[4] = '4'
        chars[9] = "89ab"[Random.nextInt(4)]
        return String(chars)
    }

    companion object {
        const val BASE = "https://kys.zzuli.edu.cn"
        const val SERVICE = "https://jwgl.zzuli.edu.cn/caslogin"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
