package com.burikktv.iptv.data

import android.content.Context
import com.burikktv.iptv.data.model.EpgProgramme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Fetches and parses XMLTV EPG feeds referenced by a playlist's `x-tvg-url`,
 * with a disk cache so a slow/unreachable guide doesn't block channel
 * browsing. Feeds are commonly gzip-compressed (`.xml.gz`), detected here by
 * the gzip magic bytes rather than trusting the URL's extension.
 */
class EpgRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun load(epgUrls: Set<String>): Map<String, List<EpgProgramme>> = withContext(Dispatchers.IO) {
        val programmes = mutableListOf<EpgProgramme>()
        for (url in epgUrls) {
            runCatching {
                val cacheFile = File(context.cacheDir, "epg_${url.hashCode()}.bin")
                val bytes = fetchWithCache(url, cacheFile) ?: return@runCatching
                val xml = decompressIfNeeded(bytes)
                programmes += EpgParser.parse(xml)
            }
        }
        programmes.groupBy { it.channelId }
    }

    private fun fetchWithCache(url: String, cacheFile: File): ByteArray? {
        val fresh = tryDownload(url)
        if (fresh != null) {
            runCatching { cacheFile.writeBytes(fresh) }
            return fresh
        }
        return if (cacheFile.exists()) cacheFile.readBytes() else null
    }

    private fun tryDownload(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decompressIfNeeded(bytes: ByteArray): String {
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        return if (isGzip) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }
}
