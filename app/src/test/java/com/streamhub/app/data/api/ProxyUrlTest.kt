package com.streamhub.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.3 — the #1 playback bug: proxyUrl() used to drop the stream's
 * Referer/User-Agent, so segments hit hotlink-protected CDNs with no
 * Referer → 403 → proxy 502 → every title erroring. These tests pin the
 * fix: headers flow into the proxy URL, URL-encoded.
 */
class ProxyUrlTest {

    private val client = ApiClient()

    @Test
    fun `proxyUrl forwards Referer and User-Agent from stream headers`() {
        val url = client.proxyUrl(
            "https://play.zephyrix.org/cdn/hls/abc123/master.m3u8",
            mapOf(
                "Referer" to "https://play.zephyrix.org/",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/124.0.0.0",
            ),
        )
        assertTrue(url.startsWith("https://strategy-terry-masters-king.trycloudflare.com/api/proxy?url="))
        assertTrue("referer missing: $url", url.contains("referer=https%3A%2F%2Fplay.zephyrix.org%2F"))
        assertTrue("ua missing: $url", url.contains("ua=Mozilla%2F5.0+%28Windows+NT+10.0%3B+Win64%3B+x64%29+Chrome%2F124.0.0.0"))
    }

    @Test
    fun `proxyUrl header lookup is case-insensitive`() {
        val url = client.proxyUrl(
            "https://cdn.example.com/seg.ts",
            mapOf("referer" to "https://example.com/", "user-agent" to "TestAgent/1.0"),
        )
        assertTrue(url.contains("referer=https%3A%2F%2Fexample.com%2F"))
        assertTrue(url.contains("ua=TestAgent%2F1.0"))
    }

    @Test
    fun `proxyUrl without headers is the plain url param`() {
        val url = client.proxyUrl("https://cdn.example.com/video.mp4")
        assertEquals(
            "https://strategy-terry-masters-king.trycloudflare.com/api/proxy?url=https%3A%2F%2Fcdn.example.com%2Fvideo.mp4",
            url,
        )
        assertFalse(url.contains("referer="))
        assertFalse(url.contains("ua="))
    }

    @Test
    fun `proxyUrl with empty headers adds nothing`() {
        val url = client.proxyUrl("https://cdn.example.com/video.mp4", emptyMap())
        assertFalse(url.contains("referer="))
        assertFalse(url.contains("ua="))
    }
}
