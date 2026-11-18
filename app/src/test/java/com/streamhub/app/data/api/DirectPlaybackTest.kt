package com.streamhub.app.data.api

import com.streamhub.app.ui.player.resolvePlayUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.5 — direct-first playback: the player tries the raw CDN URL
 * first and falls back to the proxy URL only when direct fails.
 *
 * Covers the three pieces that make it work:
 * - [unwrapProxyUrl]: raw URL behind a (multiply-)wrapped proxy URL.
 * - [dedupeStreamsByHost]/[applyFastStart]: CDN-host matching must use
 *   the raw URL — after wrapping, every proxied stream shares the
 *   tunnel host, so the old code collapsed 7 mirrors into 1.
 * - [resolvePlayUrl]: direct first, proxy-only (IP-bound) sources never
 *   tried direct, direct-failed mirrors go proxy next time.
 */
class DirectPlaybackTest {

    private val api = ApiClient()

    private fun proxiedStream(
        rawUrl: String,
        quality: Int? = 720,
        name: String = "Server",
        source: String? = "multimovies",
    ): ApiStream {
        val proxyUrl = "https://strategy-terry-masters-king.trycloudflare.com/api/proxy?url=" +
            java.net.URLEncoder.encode(rawUrl, "UTF-8")
        return ApiStream(
            name = name, url = proxyUrl, quality = quality, type = "m3u8",
            headers = mapOf("Referer" to "https://example.com/"),
            proxy = true, source = source, directUrl = rawUrl,
        )
    }

    @Test
    fun `unwrapProxyUrl returns a plain url untouched`() {
        assertEquals(
            "https://cdn.example.com/1.m3u8",
            unwrapProxyUrl("https://cdn.example.com/1.m3u8"),
        )
    }

    @Test
    fun `unwrapProxyUrl unwraps single and double wrapped proxy urls`() {
        val raw = "https://cdn.example.com/1.m3u8?token=abc&x=1"
        val single = api.proxyUrl(raw)
        assertEquals(raw, unwrapProxyUrl(single))
        // A proxy URL wrapped a second time (what the v1.0.4 app did).
        val double = api.proxyUrl(single)
        assertEquals(raw, unwrapProxyUrl(double))
    }

    @Test
    fun `proxyUrl is idempotent on an already proxied url`() {
        val raw = "https://cdn.example.com/1.m3u8"
        val once = api.proxyUrl(raw)
        val twice = api.proxyUrl(once)
        assertEquals(once, twice)
    }

    @Test
    fun `dedupe keeps one mirror per CDN host even when all are proxied`() {
        // The v1.0.4 bug: all 7 mirrors of "The Odyssey" share the tunnel
        // host after wrapping, so dedupe kept only 1. With directUrl set,
        // dedupe is by CDN host and all 3 survive.
        val deduped = dedupeStreamsByHost(
            listOf(
                proxiedStream("https://cdn-a.example.com/1.m3u8", 1080),
                proxiedStream("https://cdn-a.example.com/2.m3u8", 720),
                proxiedStream("https://cdn-b.example.com/1.m3u8", 720),
                proxiedStream("https://cdn-c.example.com/1.m3u8", 480),
            ),
        )
        assertEquals(3, deduped.size)
        assertEquals(1080, deduped[0].quality)
        assertEquals("https://cdn-b.example.com/1.m3u8", deduped[1].directUrl)
    }

    @Test
    fun `fast_start matches the stream whose CDN host it names`() {
        val streams = listOf(
            proxiedStream("https://slow.example.com/1.m3u8", 1080),
            proxiedStream("https://fast.example.com/1.m3u8", 720),
        )
        // fast_start arrives as a proxy URL of the lowest-variant playlist.
        val fastStart = api.proxyUrl("https://fast.example.com/1-low.m3u8")
        val ordered = applyFastStart(streams, fastStart)
        assertEquals("https://fast.example.com/1.m3u8", ordered[0].directUrl)
        assertEquals("https://slow.example.com/1.m3u8", ordered[1].directUrl)
    }

    @Test
    fun `resolvePlayUrl tries direct first for a normal mirror`() {
        val stream = proxiedStream("https://cdn-a.example.com/1.m3u8")
        val (url, isDirect) = resolvePlayUrl(stream, "multimovies", directFailed = false)
        assertTrue(isDirect)
        assertEquals("https://cdn-a.example.com/1.m3u8", url)
    }

    @Test
    fun `resolvePlayUrl goes proxy after direct failed`() {
        val stream = proxiedStream("https://cdn-a.example.com/1.m3u8")
        val (url, isDirect) = resolvePlayUrl(stream, "multimovies", directFailed = true)
        assertFalse(isDirect)
        assertTrue(url.contains("/api/proxy?url="))
    }

    @Test
    fun `resolvePlayUrl never tries direct for Pornhub (IP-bound)`() {
        val stream = proxiedStream(
            "https://ev.phncdn.com/videos/1.mp4",
            quality = 720,
            source = "pornhub",
        ).copy(type = "video")
        val (url, isDirect) = resolvePlayUrl(stream, null, directFailed = false)
        assertFalse(isDirect)
        assertTrue(url.contains("/api/proxy?url="))
    }

    @Test
    fun `resolvePlayUrl uses proxy when there is no direct url`() {
        val stream = ApiStream(
            name = "Server", url = "https://cdn.example.com/1.mp4",
            quality = 480, type = "video", proxy = false, source = "moviebox",
        )
        val (url, isDirect) = resolvePlayUrl(stream, "moviebox", directFailed = false)
        assertFalse(isDirect)
        assertEquals("https://cdn.example.com/1.mp4", url)
    }
}
