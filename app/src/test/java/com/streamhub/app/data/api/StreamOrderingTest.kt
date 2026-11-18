package com.streamhub.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.0.3 — multi-server mirror handling: mirrors are deduped by CDN host
 * (same host twice = one mirror), and the server's fast_start hint moves
 * its stream to the front so playback starts on the fastest mirror.
 */
class StreamOrderingTest {

    private fun stream(url: String, quality: Int? = 720, name: String = "Server") =
        ApiStream(name = name, url = url, quality = quality, type = "m3u8", proxy = true)

    @Test
    fun `dedupe keeps first stream per host`() {
        val deduped = dedupeStreamsByHost(
            listOf(
                stream("https://cdn-a.example.com/1.m3u8", 1080),
                stream("https://cdn-a.example.com/2.m3u8", 720),
                stream("https://cdn-b.example.com/1.m3u8", 720),
            ),
        )
        assertEquals(2, deduped.size)
        assertEquals(1080, deduped[0].quality)
        assertEquals("https://cdn-b.example.com/1.m3u8", deduped[1].url)
    }

    @Test
    fun `fast_start url moves to front`() {
        val streams = listOf(
            stream("https://slow.example.com/1.m3u8", 1080),
            stream("https://fast.example.com/1.m3u8", 720),
        )
        val ordered = applyFastStart(streams, "https://fast.example.com/1.m3u8")
        assertEquals("https://fast.example.com/1.m3u8", ordered[0].url)
        assertEquals("https://slow.example.com/1.m3u8", ordered[1].url)
    }

    @Test
    fun `fast_start host moves to front`() {
        val streams = listOf(
            stream("https://slow.example.com/1.m3u8", 1080),
            stream("https://fast.example.com/1.m3u8", 720),
        )
        val ordered = applyFastStart(streams, "fast.example.com")
        assertEquals("https://fast.example.com/1.m3u8", ordered[0].url)
    }

    @Test
    fun `fast_start with no match keeps order`() {
        val streams = listOf(
            stream("https://a.example.com/1.m3u8", 1080),
            stream("https://b.example.com/1.m3u8", 720),
        )
        val ordered = applyFastStart(streams, "https://missing.example.com/1.m3u8")
        assertEquals(streams, ordered)
        assertEquals(streams, applyFastStart(streams, null))
    }
}
