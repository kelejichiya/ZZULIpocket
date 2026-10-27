package com.zzupocket.app.data.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用系统定位拿经纬度和「市 + 区」；没权限/没定位就返回 null。
 *
 * 区级名称有两条路：
 *  1. 系统 Geocoder —— 国内 ROM 经常没有反向地理编码服务，只能拿到市或直接失败；
 *  2. OSM Nominatim 兜底 —— Geocoder 拿不到区时再请求一次，通常能给出 city_district。
 * 坐标本身也会先试「最后已知位置」，拿不到再主动请求一次当前位置。
 */
object LocationClient {

    data class Place(val lat: Double, val lon: Double, val city: String)

    fun locate(context: Context): Place? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val location = lastKnown(lm) ?: current(lm)
        if (location == null) return null

        val name = reverse(context, location.latitude, location.longitude)
            ?: "当前位置"
        return Place(lat = location.latitude, lon = location.longitude, city = name)
    }

    private fun lastKnown(lm: LocationManager): Location? = runCatching {
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
    }.getOrNull()

    /** 主动请求一次当前位置；8 秒拿不到就算了（不要卡住首页） */
    private fun current(lm: LocationManager): Location? {
        // GPS 优先：模拟定位/真机定位通常都比网络定位准
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (provider in providers) {
            if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
            val latch = CountDownLatch(1)
            var result: Location? = null
            val ok = runCatching {
                lm.getCurrentLocation(
                    provider,
                    null,
                    { r -> r.run() },
                ) { loc ->
                    result = loc
                    latch.countDown()
                }
            }.isSuccess
            if (ok && latch.await(3, TimeUnit.SECONDS) && result != null) return result
        }
        return null
    }

    private fun reverse(context: Context, lat: Double, lon: Double): String? {
        // Geocoder 在没有反向地理编码服务的 ROM 上会一直阻塞，必须限时
        val fromGeocoder = runCatching {
            val task = java.util.concurrent.Executors.newSingleThreadExecutor().submit<String?> {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.CHINA).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                    val city = a.locality ?: a.subAdminArea ?: a.adminArea
                    val district = listOf(a.subLocality, a.subAdminArea)
                        .firstOrNull { !it.isNullOrBlank() && it != city }
                    format(city, district)
                }
            }
            try {
                task.get(2, TimeUnit.SECONDS)
            } catch (e: Throwable) {
                task.cancel(true)
                null
            }
        }.getOrNull()

        // Geocoder 已经给出区就不再请求网络；只有「只有市 / 什么都没拿到」才兜底
        if (fromGeocoder != null && fromGeocoder.contains("·")) return fromGeocoder
        val fromBdc = runCatching { bigDataCloud(lat, lon) }.getOrNull()
        if (fromBdc != null && fromBdc.contains("·")) return fromBdc
        val fromOsm = runCatching { osm(lat, lon) }.getOrNull()
        return fromBdc ?: fromOsm ?: fromGeocoder
    }

    /** BigDataCloud 免 key 反向地理编码（国内可访问，能给出区） */
    private fun bigDataCloud(lat: Double, lon: Double): String? {
        val url = URL(
            "https://api.bigdatacloud.net/data/reverse-geocode-client" +
                "?latitude=$lat&longitude=$lon&localityLanguage=zh",
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            setRequestProperty("User-Agent", "NieQingAssistant/1.0 (Android)")
        }
        return try {
            if (conn.responseCode != 200) return null
            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val city = root.optString("city").ifBlank { root.optString("principalSubdivision") }
            val locality = root.optString("locality")
            val admin = root.optJSONObject("localityInfo")?.optJSONArray("administrative")
            val level6 = if (admin != null) {
                (0 until admin.length())
                    .mapNotNull { admin.optJSONObject(it) }
                    .lastOrNull { it.optInt("adminLevel") == 6 && it.optString("name").isNotBlank() }
                    ?.optString("name")
            } else null
            format(city, locality.ifBlank { level6 })
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun format(city: String?, district: String?): String? {
        val c = city?.trim()?.removeSuffix("市")?.removeSuffix("省")
        val d = district?.trim()
        return when {
            !c.isNullOrBlank() && !d.isNullOrBlank() && d != c -> "$c·$d"
            !d.isNullOrBlank() -> d
            !c.isNullOrBlank() -> c
            else -> null
        }
    }

    /** OSM 反向地理编码，返回「郑州·金水区」 */
    private fun osm(lat: Double, lon: Double): String? {
        val url = URL(
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14" +
                "&accept-language=zh-CN&lat=$lat&lon=$lon",
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3000
            readTimeout = 3000
            setRequestProperty("User-Agent", "NieQingAssistant/1.0 (Android)")
        }
        return try {
            if (conn.responseCode != 200) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val address = JSONObject(text).optJSONObject("address") ?: return null
            val city = address.optString("city")
                .ifBlank { address.optString("town") }
                .ifBlank { address.optString("county") }
            val district = address.optString("city_district")
                .ifBlank { address.optString("suburb") }
                .ifBlank { address.optString("district") }
                .ifBlank { address.optString("county") }
            format(city, district)
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
