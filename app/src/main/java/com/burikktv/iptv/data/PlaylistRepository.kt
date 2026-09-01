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
 * Builds the channel list purely from the user's own custom playlists (added
 * via "Tambah Playlist"), plus the community-maintained country flag lookup
 * so `group-title` values that happen to be country names still get a flag
 * emoji. This used to also fetch the full iptv-org global catalog
 * (~14,000 channels across every country) automatically on every launch, but
 * that catalog — several MB of text plus its parsed object graph — stayed
 * resident in memory for the entire app session regardless of whether the
 * user ever browsed it, which was a major fixed cost behind OutOfMemoryError
 * crashes on Android TV boxes with a small heap. Custom playlists are
 * usually a fraction of that size and are what the user actually asked for.
 */
class PlaylistRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val flagsCacheFile: File get() = File(context.cacheDir, "flags.json")

    data class PlaylistResult(
        val channels: List<Channel>,
        val countryFlags: Map<String, String>,
        val fromCache: Boolean,
    )

    suspend fun load(customPlaylistUrls: Set<String> = emptySet()): Result<PlaylistResult> = withContext(Dispatchers.IO) {
        runCatching {
            val flagsText = runCatching {
                fetchWithCache(url = COUNTRIES_URL, cacheFile = flagsCacheFile)
            }.getOrNull()

            val channels = mutableListOf<Channel>()
            var anyFromCache = false
            for (url in customPlaylistUrls) {
                val cacheFile = File(context.cacheDir, "custom_${url.hashCode()}.m3u")
                val fetched = runCatching { fetchWithCache(url = url, cacheFile = cacheFile) }.getOrNull()
                if (fetched == null) continue
                anyFromCache = anyFromCache || fetched.fromCache
                channels += M3UParser.parse(fetched.text)
            }
            val flags = flagsText?.let { parseFlags(it.text) } ?: emptyMap()

            // Two custom playlists can legitimately overlap and reuse the same
            // name+URL pair, which would otherwise produce two channels sharing
            // the same id and crash any Compose lazy list keyed by it.
            val deduped = channels.distinctBy { it.id }

            PlaylistResult(
                channels = deduped,
                countryFlags = flags,
                fromCache = anyFromCache,
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
        private const val COUNTRIES_URL = "https://iptv-org.github.io/api/countries.json"
    }
}

private fun File.readText(): String = source().buffer().use { it.readUtf8() }
