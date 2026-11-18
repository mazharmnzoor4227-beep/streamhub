package com.streamhub.app.player

import com.streamhub.app.core.Http
import org.json.JSONObject

/**
 * Skip-intro timestamps from the AniSkip database, keyed by MyAnimeList id.
 * Only providers that supply a MAL id (e.g. ReAnime) get exact timestamps;
 * without an id the player falls back to a manual +85s skip button.
 */
object SkipIntro {
    data class OpRange(val startMs: Long, val endMs: Long)

    suspend fun fetchOpRange(malId: Int?, episode: Int, durationMs: Long): OpRange? {
        if (malId == null || malId <= 0 || durationMs <= 0) return null
        val durationSec = (durationMs / 1000).toInt()
        val url =
            "https://api.aniskip.com/v2/skip-times/$malId/$episode?types[]=op&episodeLength=$durationSec"
        return try {
            val text = Http.get(url)
            val obj = JSONObject(text)
            val results = obj.optJSONArray("results") ?: return null
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                if (r.optString("skipType") != "op") continue
                val interval = r.optJSONObject("interval") ?: continue
                val start = interval.optDouble("startTime", -1.0)
                val end = interval.optDouble("endTime", -1.0)
                if (start >= 0 && end > start) {
                    return OpRange((start * 1000).toLong(), (end * 1000).toLong())
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
