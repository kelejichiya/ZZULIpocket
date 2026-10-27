package com.zzupocket.app.data.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 节假日：timor.tech 免费接口。
 * 返回 { "10-01": {"holiday": true, "name": "国庆节", "date": "2026-10-01"} }
 * holiday=true 为放假（红色），false 为调休上班（班）。
 */
object HolidayClient {

    data class Holiday(val name: String, val isOff: Boolean)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 返回 key = "MM-dd" 的节假日表 */
    suspend fun fetch(year: Int): Map<String, Holiday> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://timor.tech/api/holiday/year/$year")
                .header("User-Agent", "Mozilla/5.0")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyMap()
                val body = resp.body?.string().orEmpty()
                val root = JSONObject(body)
                val holiday = root.optJSONObject("holiday") ?: return@use emptyMap()
                val out = mutableMapOf<String, Holiday>()
                holiday.keys().forEach { key ->
                    val o = holiday.optJSONObject(key) ?: return@forEach
                    val off = o.optBoolean("holiday", false)
                    out[key] = Holiday(o.optString("name"), off)
                }
                out
            }
        }.getOrDefault(emptyMap())
    }
}
