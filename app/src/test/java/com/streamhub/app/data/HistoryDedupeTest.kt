package com.streamhub.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.3 — History dedupe: watching the same series/episode twice showed
 * twice. The key is now title+episode, so the same episode via ANY source
 * is one row keeping the latest position/time.
 */
class HistoryDedupeTest {

    private fun entry(
        title: String,
        episode: Int,
        positionMs: Long,
        watchedAtMs: Long,
        sourceId: String = "animeworld",
    ) = HistoryStore.HistoryEntry(
        title = title,
        poster = null,
        sourceId = sourceId,
        detailsId = "id-$sourceId",
        episodeNumber = episode,
        positionMs = positionMs,
        durationMs = 1_400_000L,
        watchedAtMs = watchedAtMs,
    )

    @Test
    fun `same episode twice via different sources is one row with latest position`() {
        val merged = HistoryStore.dedupeEntries(
            listOf(
                entry("Naruto Shippuden", 1, 60_000, 1_000, sourceId = "animeworld"),
                entry("Naruto Shippuden", 1, 300_000, 2_000, sourceId = "xanime"),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(300_000L, merged[0].positionMs)
        assertEquals(2_000L, merged[0].watchedAtMs)
    }

    @Test
    fun `dedupe key ignores title case and surrounding spaces`() {
        assertEquals(
            HistoryStore.dedupeKey("Naruto Shippuden", 1),
            HistoryStore.dedupeKey("  naruto shippuden ", 1),
        )
    }

    @Test
    fun `different episodes stay separate rows`() {
        val merged = HistoryStore.dedupeEntries(
            listOf(
                entry("Naruto Shippuden", 1, 60_000, 1_000),
                entry("Naruto Shippuden", 2, 60_000, 2_000),
            ),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `dedupe keeps newest first ordering`() {
        val merged = HistoryStore.dedupeEntries(
            listOf(
                entry("Title A", 1, 10_000, 1_000),
                entry("Title B", 1, 10_000, 3_000),
                entry("Title A", 1, 20_000, 2_000),
            ),
        )
        assertEquals(2, merged.size)
        assertEquals("Title B", merged[0].title)
        assertEquals("Title A", merged[1].title)
        assertEquals(20_000L, merged[1].positionMs)
        assertTrue(merged.all { it.watchedAtMs > 0 })
    }
}
