package com.burikktv.iptv.data

import android.content.Context
import com.burikktv.iptv.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Fetches the community-maintained iptv-org playlist (grouped by country) and
 * the matching country flag metadata, with a disk cache so the app still has
 * something to show if the network is unavailable.
 */
class PlaylistRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val playlistCacheFile: File get() = File(context.cacheDir, "playlist.m3u")
    private val flagsCacheFile: File get() = File(context.cacheDir, "flags.json")

    data class PlaylistResult(
        val channels: List<Channel>,
        val countryFlags: Map<String, String>,
        val epgUrls: Set<String>,
        val fromCache: Boolean,
    )

    suspend fun load(customPlaylistUrls: Set<String> = emptySet()): Result<PlaylistResult> = withContext(Dispatchers.IO) {
        runCatching {
            val playlistText = fetchWithCache(url = PLAYLIST_URL, cacheFile = playlistCacheFile)
            val flagsText = runCatching {
                fetchWithCache(url = COUNTRIES_URL, cacheFile = flagsCacheFile)
            }.getOrNull()

            val channels = M3UParser.parse(playlistText.text).toMutableList()
            val epgUrls = M3UParser.extractEpgUrls(playlistText.text).toMutableSet()
            var anyCustomFromCache = false
            for (url in customPlaylistUrls) {
                val cacheFile = File(context.cacheDir, "custom_${url.hashCode()}.m3u")
                val fetched = runCatching { fetchWithCache(url = url, cacheFile = cacheFile) }.getOrNull()
                if (fetched == null) continue
                anyCustomFromCache = anyCustomFromCache || fetched.fromCache
                epgUrls += M3UParser.extractEpgUrls(fetched.text)
                // Custom playlists are rarely grouped by country name (usually by
                // genre, or not grouped at all), so their original group-title would
                // otherwise pollute the country list with unrelated entries. All
                // custom channels are surfaced under one dedicated group instead.
                val customChannels = M3UParser.parse(fetched.text).map { channel ->
                    channel.copy(country = CUSTOM_GROUP_NAME)
                }
                channels += customChannels
            }
            val flags = flagsText?.let { parseFlags(it.text) } ?: emptyMap()

            // A custom playlist can legitimately overlap with the base playlist (or
            // with another custom playlist) and reuse the exact same name+URL pair,
            // which would otherwise produce two channels sharing the same id and
            // crash any Compose lazy list keyed by it.
            val deduped = channels.distinctBy { it.id }

            PlaylistResult(
                channels = deduped,
                countryFlags = flags,
                epgUrls = epgUrls,
                fromCache = playlistText.fromCache || anyCustomFromCache,
            )
        }
    }

    private data class Fetched(val text: String, val fromCache: Boolean)

    private fun fetchWithCache(url: String, cacheFile: File): Fetched {
        val fresh = tryDownload(url, cacheFile)
        if (fresh != null) return Fetched(fresh, fromCache = false)
        if (cacheFile.exists()) {
            return Fetched(cacheFile.readText(), fromCache = true)
        }
        error("Tidak dapat mengunduh $url dan tidak ada cache tersimpan")
    }

    private fun tryDownload(url: String, cacheFile: File): String? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                cacheFile.sink().buffer().use { sink -> sink.writeAll(response.body.source()) }
            }
            cacheFile.readText()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseFlags(json: String): Map<String, String> {
        val array = JSONArray(json)
        val map = HashMap<String, String>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val name = obj.optString("name")
            val flag = obj.optString("flag")
            if (name.isNotBlank() && flag.isNotBlank()) {
                map[name] = flag
            }
        }
        return map
    }

    companion object {
        private const val PLAYLIST_URL = "https://iptv-org.github.io/iptv/index.country.m3u"
        private const val COUNTRIES_URL = "https://iptv-org.github.io/api/countries.json"
        const val CUSTOM_GROUP_NAME = "Channel Kustom"
    }
}

private fun File.readText(): String = source().buffer().use { it.readUtf8() }
