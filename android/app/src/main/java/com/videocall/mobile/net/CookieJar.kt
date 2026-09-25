package com.videocall.mobile.net

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Persists cookies (the httpOnly session cookie + the readable CSRF cookie)
 * to SharedPreferences so a login survives an app restart, same as a
 * browser's cookie jar would. One flat "host -> serialized cookies" map is
 * plenty for a single-server client; no need for a real cookie database.
 */
class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs = context.applicationContext.getSharedPreferences("vc_cookies", Context.MODE_PRIVATE)
    private val store = mutableMapOf<String, MutableList<Cookie>>()

    init {
        for ((host, serialized) in prefs.all) {
            val raw = serialized as? String ?: continue
            val cookies = raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { Cookie.parse(HttpUrl.Builder().scheme("https").host(host).build(), line) }.getOrNull()
            }
            if (cookies.isNotEmpty()) store[host] = cookies.toMutableList()
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val host = url.host
        val existing = store.getOrPut(host) { mutableListOf() }
        for (c in cookies) {
            existing.removeAll { it.name == c.name }
            if (c.expiresAt > System.currentTimeMillis()) existing.add(c)
        }
        persist(host)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        val valid = list.filter { it.expiresAt > now }
        if (valid.size != list.size) {
            store[url.host] = valid.toMutableList()
            persist(url.host)
        }
        return valid
    }

    @Synchronized
    fun cookieValue(host: String, name: String): String? =
        store[host]?.firstOrNull { it.name == name }?.value

    @Synchronized
    fun clear(host: String) {
        store.remove(host)
        prefs.edit().remove(host).apply()
    }

    /**
     * Imports "name=value; name2=value2" cookies from a foreign store (the
     * system WebView's CookieManager, after an OIDC login flow) into this
     * jar. WebView's getCookie() doesn't expose the real expiry/flags the
     * server set, so these are given a generous local shelf life — the
     * server enforces the real session lifetime regardless.
     */
    @Synchronized
    fun importRawCookies(host: String, rawHeader: String) {
        val existing = store.getOrPut(host) { mutableListOf() }
        val farFuture = System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
        for (pair in rawHeader.split(";")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (name.isEmpty()) continue
            existing.removeAll { it.name == name }
            existing.add(Cookie.Builder().name(name).value(value).domain(host).path("/").expiresAt(farFuture).build())
        }
        persist(host)
    }

    private fun persist(host: String) {
        val serialized = store[host]?.joinToString("\n") { it.toString() } ?: ""
        prefs.edit().putString(host, serialized).apply()
    }
}
