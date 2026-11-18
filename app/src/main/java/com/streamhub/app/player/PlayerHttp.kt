package com.streamhub.app.player

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Shared OkHttp client for ExoPlayer (v1.0.3).
 *
 * Replaces Media3's DefaultHttpDataSource: connection pooling, HTTP/2,
 * keep-alive, 10s timeouts, and DNS-over-HTTPS (Cloudflare) with a
 * fallback to the system resolver — if DoH itself fails, playback must
 * still resolve hosts, so a DoH failure never takes playback down.
 * Media3's default buffers are NOT touched (first frame needs only
 * ~2.5s buffered; shrinking buffers was a known foot-gun).
 */
object PlayerHttp {

    private const val DOH_URL = "https://cloudflare-dns.com/dns-query"

    @Volatile
    private var client: OkHttpClient? = null

    fun client(): OkHttpClient = client ?: synchronized(this) {
        client ?: buildClient().also { client = it }
    }

    private fun buildClient(): OkHttpClient {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val doh = DnsOverHttps.Builder()
            .client(bootstrap)
            .url(DOH_URL.toHttpUrl())
            .bootstrapDnsHosts(
                listOf(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                ),
            )
            .build()
        // DoH first, system DNS as fallback — never fail a lookup outright.
        val dns = Dns { hostname ->
            runCatching { doh.lookup(hostname) }
                .getOrElse { Dns.SYSTEM.lookup(hostname) }
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .dns(dns)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
