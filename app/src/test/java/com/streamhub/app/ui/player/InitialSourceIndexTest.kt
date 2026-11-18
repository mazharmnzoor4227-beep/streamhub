package com.streamhub.app.ui.player

import com.streamhub.app.data.api.ApiStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.0.3 — first-play source index: HLS/DASH starts at index 0 (the
 * adaptive selector starts on the lowest variant and steps up), while
 * direct MP4 lists start at the LOWEST quality so the first frame
 * arrives fast on a slow connection.
 */
class InitialSourceIndexTest {

    @Test
    fun `hls starts at index 0`() {
        val streams = listOf(
            ApiStream(name = "A", url = "https://a.example.com/1.m3u8", quality = 1080, type = "m3u8"),
            ApiStream(name = "B", url = "https://b.example.com/1.m3u8", quality = 720, type = "m3u8"),
        )
        assertEquals(0, initialSourceIndex(streams))
    }

    @Test
    fun `mp4 starts at lowest quality`() {
        val streams = listOf(
            ApiStream(name = "A", url = "https://a.example.com/1080.mp4", quality = 1080, type = "video"),
            ApiStream(name = "B", url = "https://b.example.com/720.mp4", quality = 720, type = "video"),
            ApiStream(name = "C", url = "https://c.example.com/480.mp4", quality = 480, type = "video"),
        )
        assertEquals(2, initialSourceIndex(streams))
    }

    @Test
    fun `empty list is index 0`() {
        assertEquals(0, initialSourceIndex(emptyList()))
    }

    @Test
    fun `language display names are real names not codes`() {
        assertEquals("Hindi", languageDisplayName("hindi"))
        assertEquals("English", languageDisplayName("english"))
        assertEquals("Korean", languageDisplayName("ko"))
    }
}
