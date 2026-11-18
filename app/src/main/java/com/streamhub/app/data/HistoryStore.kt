package com.streamhub.app.data

import android.content.Context
import com.streamhub.app.data.api.ApiDetails
import com.streamhub.app.data.api.ApiEpisode

/**
 * Watch history (v1.0.2, dedupe fixed v1.0.3): everything the user
 * watched, newest first. SharedPreferences "streamhub_history".
 *
 * DEDUPE (v1.0.3): the key is TITLE + EPISODE — watching the same
 * series/episode twice (even via a different source, which gives a
 * different details id) shows ONCE, keeping the latest position/time and
 * moving to the top. The old key (source|details|episode) let the same
 * episode appear twice. Old entries written under the old key are merged
 * on read by [dedupeEntries], so no migration wipe is needed.
 *
 * ADULT (v1.0.3): entries carry an `isAdult` flag. Adult history shows
 * ONLY inside the Lock hidden page — Home's History rail and the main
 * History screen call [loadAll] with `includeAdult = false`.
 */
object HistoryStore {
    private const val PREFS = "streamhub_history"
    private const val SEP = "\u001F"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Dedupe key: normalised title + episode number. Pure, so unit tests
     * can assert it without Android. Two records for the same title and
     * episode — whatever the source — share one key.
     */
    fun dedupeKey(title: String, episodeNumber: Int): String =
        title.trim().lowercase() + "|" + episodeNumber

    data class HistoryEntry(
        val title: String,
        val poster: String?,
        val sourceId: String,
        val detailsId: String,
        val episodeNumber: Int,
        val positionMs: Long,
        val durationMs: Long,
        val watchedAtMs: Long,
        val isAdult: Boolean = false,
    )

    /**
     * Merge duplicate (title, episode) rows, keeping the latest
     * `watchedAtMs` (and its position). Pure — unit-tested.
     */
    fun dedupeEntries(entries: List<HistoryEntry>): List<HistoryEntry> =
        entries
            .groupBy { dedupeKey(it.title, it.episodeNumber) }
            .values
            .map { group -> group.maxByOrNull { it.watchedAtMs }!! }
            .sortedByDescending { it.watchedAtMs }

    fun record(
        context: Context,
        sourceId: String,
        details: ApiDetails,
        episode: ApiEpisode,
        positionMs: Long,
        durationMs: Long,
        isAdult: Boolean = false,
    ) {
        val value = listOf(
            details.title, details.poster ?: "", sourceId, details.id,
            episode.number.toString(), positionMs.toString(),
            durationMs.toString(), System.currentTimeMillis().toString(),
            if (isAdult) "1" else "0",
        ).joinToString(SEP)
        prefs(context).edit()
            .putString(dedupeKey(details.title, episode.number), value)
            .apply()
    }

    /**
     * All watched items, newest first, deduped by title+episode.
     * [includeAdult] = false for Home and the main History screen (adult
     * history lives ONLY in the Lock page, which passes true and then
     * filters to adult entries itself).
     */
    fun loadAll(context: Context, includeAdult: Boolean = true): List<HistoryEntry> {
        val out = mutableListOf<HistoryEntry>()
        for ((_, value) in prefs(context).all) {
            val raw = value as? String ?: continue
            val parts = raw.split(SEP)
            if (parts.size < 8) continue
            // 9th field (isAdult) was added in v1.0.3; older 8-field rows
            // read as non-adult.
            val adult = parts.getOrNull(8) == "1"
            if (adult && !includeAdult) continue
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
                    isAdult = adult,
                ),
            )
        }
        return dedupeEntries(out)
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
