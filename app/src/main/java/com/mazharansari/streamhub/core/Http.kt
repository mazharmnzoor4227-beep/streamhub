package com.mazharansari.streamhub.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Single shared HTTP layer for all providers. One OkHttpClient with a shared
 * in-memory cookie jar, so cookies (including Cloudflare clearance cookies
 * obtained through [CfBypass]) persist across providers and requests.
 */
object Http {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                val list = cookieStore.getOrPut(url.host) { mutableListOf() }
                for (c in cookies) {
                    list.removeAll { it.name == c.name }
                    list.add(c)
                }
            }
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> {
            synchronized(cookieStore) {
                val now = System.currentTimeMillis()
                return (cookieStore[url.host] ?: emptyList())
                    .filter { it.expiresAt > now }
            }
        }
    }

    /** Add cookies obtained elsewhere (e.g. WebView bypass) to the jar. */
    fun addCookies(host: String, cookieHeader: String) {
        val url = "https://$host".toHttpUrlOrNull() ?: return
        val cookies = cookieHeader.split(";").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size != 2 || kv[0].isBlank()) null
            else Cookie.Builder().name(kv[0].trim()).value(kv[1].trim()).domain(host).build()
        }
        cookieJar.saveFromResponse(url, cookies)
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun requestBuilder(url: String, headers: Map<String, String>): Request.Builder {
        val b = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
        headers.forEach { (k, v) -> b.header(k, v) }
        return b
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            client.newCall(requestBuilder(url, headers).get().build()).execute().use { res ->
                res.body?.string() ?: ""
            }
        }

    /** GET and return (statusCode, body). Use to detect 403 / Cloudflare walls. */
    suspend fun getWithCode(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(requestBuilder(url, headers).get().build()).execute().use { res ->
            res.code to (res.body?.string() ?: "")
        }
    }

    suspend fun getDoc(url: String, headers: Map<String, String> = emptyMap()): Document =
        withContext(Dispatchers.IO) {
            Jsoup.parse(get(url, headers), url)
        }

    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        client.newCall(requestBuilder(url, headers).post(body).build()).execute().use { res ->
            res.body?.string() ?: ""
        }
    }

    suspend fun postFormDoc(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): Document = withContext(Dispatchers.IO) {
        Jsoup.parse(postForm(url, form, headers), url)
    }

    /** Resolve a possibly-relative href against a base URL. */
    fun absUrl(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return try {
            java.net.URL(java.net.URL(base), href).toString()
        } catch (_: Exception) {
            href
        }
    }
}
