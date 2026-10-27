package com.zzupocket.app.data.energy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 完美校园「云缴费」宿舍购电查询。
 *
 * 抓包确认：
 *  - Host: h5cloud.17wanxiao.com:18443
 *  - 接口: /CloudPayment/user/getRoomState.do
 *  - 参数: payProId=3161（宿舍购电）、schoolcode=836（郑州轻工业大学）、businesstype=2、roomverify=...
 *  - 响应: {"returncode":"100","quantity":"293.29","quantityunit":"度","description":"8-201", ...}
 */
object CloudPaymentClient {

    private const val BASE = "https://h5cloud.17wanxiao.com:18443"
    private const val REFERER =
        "$BASE/CloudPayment/bill/selectPayProject.do?txcode=2&interurl=substituted_pay" +
            "&payProId=3161&amtflag=0&payamt=1&payproname=%E5%AE%BF%E8%88%8D%E8%B4%AD%E7%94%B5"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 17) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Mobile Safari/537.36"

    data class RoomState(
        val quantity: String,
        val unit: String,
        val room: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun query(cookie: String, roomVerify: String): Result<RoomState> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE/CloudPayment/user/getRoomState.do" +
                "?payProId=3161&schoolcode=836&businesstype=2&roomverify=$roomVerify"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", REFERER)
                .header("User-Agent", UA)
                .header("Cookie", cookie.trim())
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("网络请求失败（HTTP ${resp.code}）")
                if (!body.trimStart().startsWith("{")) {
                    // 服务端会话失效时偶尔返回纯文本
                    error("登录已失效，请重新抓取 Cookie")
                }
                val o = JSONObject(body)
                val code = o.optString("returncode")
                if (code != "100") {
                    error(o.optString("returnmsg").ifBlank { "查询失败（$code）" })
                }
                RoomState(
                    quantity = o.optString("quantity"),
                    unit = o.optString("quantityunit").ifBlank { "度" },
                    room = o.optString("description"),
                )
            }
        }
    }

    /** 从抓包的请求 URL 里取出 roomverify */
    fun roomVerifyFromUrl(url: String): String? =
        Regex("""[?&]roomverify=([^&]+)""").find(url)?.groupValues?.get(1)
}
