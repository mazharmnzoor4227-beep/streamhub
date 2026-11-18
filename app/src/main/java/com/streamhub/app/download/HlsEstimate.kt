package com.streamhub.app.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * HLS size estimate (v1.0.2): Media3 reports `contentLength = -1` for HLS
 * (m3u8) downloads, so the Downloads screen used to show a bare
 * "Downloading… X bytes" with NO percentage — the user saw no % at all.
 * This estimates the total from the playlist itself:
 * total ≈ (sum of EXTINF durations) × (variant BANDWIDTH) / 8.
 * Best-effort only: null on any failure (bad playlist, no bandwidth,
 * network error) — the caller then shows bytes without a % rather than a
 * wrong number. One short-lived client, 10s timeouts; never blocks the UI.
 */
object HlsEstimate {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val extinfRegex = Regex("""#EXTINF:([\d.]+)""")
    private val bandwidthRegex = Regex("""BANDWIDTH=(\d+)""")

    /**
     * Estimated total bytes for an HLS (m3u8) URL, or null when it cannot
     * be estimated. [url] is the URL that was/will be enqueued (already
     * proxy-wrapped when the stream needs it).
     */
    suspend fun estimate(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching { estimateSync(url) }.getOrNull()
    }

    private fun estimateSync(url: String): Long? {
        val master = fetchText(url) ?: return null
        if (!master.contains("#EXTM3U")) return null
        var playlistUrl = url
        var bandwidth: Long? = null
        if (master.contains("#EXT-X-STREAM-INF")) {
            // Master playlist: pick the highest-bandwidth variant.
            var bestBandwidth = -1L
            var bestUri: String? = null
            val lines = master.lines()
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF")) continue
                val bw = bandwidthRegex.find(line)?.groupValues?.getOrNull(1)
                    ?.toLongOrNull() ?: continue
                val uri = lines.getOrNull(i + 1)?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.startsWith("#") }
                    ?: continue
                if (bw > bestBandwidth) {
                    bestBandwidth = bw
                    bestUri = uri
                }
            }
            if (bestUri == null || bestBandwidth <= 0) return null
            bandwidth = bestBandwidth
            playlistUrl = resolveUrl(url, bestUri)
        } else {
            // Single media playlist: no bandwidth anywhere — cannot estimate.
            return null
        }
        val media = if (playlistUrl == url) master else fetchText(playlistUrl) ?: return null
        var durationSec = 0.0
        for (match in extinfRegex.findAll(media)) {
            durationSec += match.groupValues[1].toDoubleOrNull() ?: 0.0
        }
        val bw = bandwidth ?: return null
        if (durationSec <= 0 || bw <= 0) return null
        return (durationSec * bw / 8.0).toLong()
    }

    private fun fetchText(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return runCatching { URI(base).resolve(relative).toString() }.getOrDefault(relative)
    }
}
