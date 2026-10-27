package com.zzupocket.app.data.qingguo

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 按域名分组的内存 CookieJar。
 *
 * CAS（kys.zzuli.edu.cn）与教务（jwgl.zzuli.edu.cn）的 Cookie 同名不同域，
 * 必须分开存；登录完成后再把教务域下的 Cookie 交给 [QingguoClient]。
 */
class DomainCookieJar : CookieJar {

    private val store = mutableMapOf<String, MutableMap<String, Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val bucket = store.getOrPut(url.host) { mutableMapOf() }
        cookies.forEach { bucket[it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val bucket = store[url.host] ?: return emptyList()
        return bucket.values.filter { it.matches(url) }
    }

    /** 取某个 host 上当前有效的 Cookie（给 QingguoClient 用） */
    @Synchronized
    fun cookiesFor(host: String): List<Cookie> =
        store.entries
            .filter { host == it.key || host.endsWith(".${it.key}") }
            .flatMap { it.value.values.filter { c -> c.domain.removePrefix(".") == host || host.endsWith(c.domain.removePrefix(".")) } }

    /** 某个 host 的 Cookie header（"k=v; k2=v2"） */
    @Synchronized
    fun cookieHeaderFor(host: String): String =
        cookiesFor(host).joinToString("; ") { "${it.name}=${it.value}" }

    @Synchronized
    fun clear() = store.clear()
}
