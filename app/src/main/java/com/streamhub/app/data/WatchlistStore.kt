package com.streamhub.app.data

import android.content.Context
import com.streamhub.app.data.api.ApiItem

/**
 * "My List" (v1.0.2): titles the user saved to watch later.
 * SharedPreferences "streamhub_watchlist", same unit-separator value
 * format as WatchProgressStore: key = "sourceId|detailsId", value =
 * title ␟ poster ␟ sourceId ␟ detailsId ␟ savedAtMs.
 * Newest saved first. Local only — no account, no server.
 */
object WatchlistStore {
    private const val PREFS = "streamhub_watchlist"
    private const val SEP = "\u001F"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(sourceId: String, detailsId: String) = "$sourceId|$detailsId"

    fun isSaved(context: Context, sourceId: String, detailsId: String): Boolean =
        prefs(context).contains(key(sourceId, detailsId))

    /**
     * Toggle: saves when absent (returns true), removes when present
     * (returns false). [item] must carry its source id.
     */
    fun toggle(context: Context, item: ApiItem): Boolean {
        val sourceId = item.source ?: ""
        val k = key(sourceId, item.id)
        val p = prefs(context)
        return if (p.contains(k)) {
            p.edit().remove(k).apply()
            false
        } else {
            val value = listOf(
                item.title, item.poster ?: "", sourceId, item.id,
                System.currentTimeMillis().toString(),
            ).joinToString(SEP)
            p.edit().putString(k, value).apply()
            true
        }
    }

    fun remove(context: Context, sourceId: String, detailsId: String) {
        prefs(context).edit().remove(key(sourceId, detailsId)).apply()
    }

    /** All saved titles, newest first. */
    fun loadAll(context: Context): List<ApiItem> {
        val out = mutableListOf<Pair<Long, ApiItem>>()
        for ((_, value) in prefs(context).all) {
            val raw = value as? String ?: continue
            val parts = raw.split(SEP)
            if (parts.size < 5) continue
            out.add(
                (parts[4].toLongOrNull() ?: 0L) to ApiItem(
                    id = parts[3],
                    title = parts[0],
                    poster = parts[1].ifBlank { null },
                    source = parts[2],
                ),
            )
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }
}
