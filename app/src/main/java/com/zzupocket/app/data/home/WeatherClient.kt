package com.zzupocket.app.data.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 天气：Open-Meteo（免费、无需 key）。
 * 坐标默认郑州（科学大道校区附近），返回当前温度/体感/湿度 + 今日最高最低。
 */
object WeatherClient {

    data class Weather(
        val temp: Int,
        val feelsLike: Int,
        val humidity: Int,
        val high: Int,
        val low: Int,
        val text: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun url(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=Asia%2FShanghai&forecast_days=1"

    suspend fun fetch(lat: Double = 34.7466, lon: Double = 113.6254): Weather? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url(lat, lon)).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                val root = JSONObject(body)
                val cur = root.getJSONObject("current")
                val daily = root.getJSONObject("daily")
                val code = cur.optInt("weather_code", -1)
                Weather(
                    temp = cur.optDouble("temperature_2m", 0.0).toInt(),
                    feelsLike = cur.optDouble("apparent_temperature", 0.0).toInt(),
                    humidity = cur.optInt("relative_humidity_2m", 0),
                    high = daily.getJSONArray("temperature_2m_max").optDouble(0, 0.0).toInt(),
                    low = daily.getJSONArray("temperature_2m_min").optDouble(0, 0.0).toInt(),
                    text = describe(code),
                )
            }
        }.getOrNull()
    }

    /** WMO 天气代码 → 中文 */
    private fun describe(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66, 67 -> "冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "雪粒"
        80, 81 -> "阵雨"
        82 -> "强阵雨"
        85, 86 -> "阵雪"
        95 -> "雷阵雨"
        96, 99 -> "雷阵雨伴冰雹"
        else -> "—"
    }
}
