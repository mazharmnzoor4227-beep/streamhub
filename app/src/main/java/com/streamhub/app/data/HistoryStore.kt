package com.streamhub.app.data

import android.content.Context
import com.streamhub.app.data.api.ApiDetails
import com.streamhub.app.data.api.ApiEpisode

/**
 * Watch history (v1.0.2): everything the user watched, newest first.
 * SharedPreferences "streamhub_history": key =
 * "sourceId|detailsId|episodeNumber", value =
 * title ␟ poster ␟ sourceId ␟ detailsId ␟ episodeNumber ␟ positionMs ␟
 * durationMs ␟ watchedAtMs. Recorded on play start, every ~10s while
 * playing, and on exit — so tapping an entry resumes exactly where the
 * user left off (same resume path as Continue Watching).
 */
object HistoryStore {
    private const val PREFS = "streamhub_history"
    private const val SEP = "\u001F"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(sourceId: String, detailsId: String, episodeNumber: Int) =
        "$sourceId|$detailsId|$episodeNumber"

    data class HistoryEntry(
        val title: String,
        val poster: String?,
        val sourceId: String,
        val detailsId: String,
        val episodeNumber: Int,
        val positionMs: Long,
        val durationMs: Long,
        val watchedAtMs: Long,
    )

    fun record(
        context: Context,
        sourceId: String,
        details: ApiDetails,
        episode: ApiEpisode,
        positionMs: Long,
        durationMs: Long,
    ) {
        val value = listOf(
            details.title, details.poster ?: "", sourceId, details.id,
            episode.number.toString(), positionMs.toString(),
            durationMs.toString(), System.currentTimeMillis().toString(),
        ).joinToString(SEP)
        prefs(context).edit()
            .putString(key(sourceId, details.id, episode.number), value)
            .apply()
    }

    /** All watched items, newest first. */
    fun loadAll(context: Context): List<HistoryEntry> {
        val out = mutableListOf<HistoryEntry>()
        for ((_, value) in prefs(context).all) {
            val raw = value as? String ?: continue
            val parts = raw.split(SEP)
            if (parts.size < 8) continue
            out.add(
                HistoryEntry(
                    title = parts[0],
                    poster = parts[1].ifBlank { null },
                    sourceId = parts[2],
                    detailsId = parts[3],
                    episodeNumber = parts[4].toIntOrNull() ?: 0,
                    positionMs = parts[5].toLongOrNull() ?: 0L,
                    durationMs = parts[6].toLongOrNull() ?: 0L,
                    watchedAtMs = parts[7].toLongOrNull() ?: 0L,
                ),
            )
        }
        return out.sortedByDescending { it.watchedAtMs }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** "Just now", "5m ago", "2h ago", "Yesterday", "3d ago". */
    fun formatAgo(watchedAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diff = (nowMs - watchedAtMs).coerceAtLeast(0L)
        val minutes = diff / 60_000
        val hours = diff / 3_600_000
        val days = diff / 86_400_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days == 1L -> "Yesterday"
            days < 7 -> "${days}d ago"
            else -> "${days / 7}w ago"
        }
    }
}
