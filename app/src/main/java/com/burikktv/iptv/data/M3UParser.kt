package com.burikktv.iptv.data

import android.util.Base64
import com.burikktv.iptv.data.model.Channel

/**
 * Parses the extended M3U format used by the iptv-org playlist, where each
 * entry's `group-title` attribute is the channel's country name. Also
 * understands the Kodi-style `#KODIPROP` tags that many DASH IPTV sources use
 * to describe DRM — both Clear Key and Widevine, via
 * `inputstream.adaptive.license_type` / `.license_key` — and manifest type
 * (`inputstream.adaptive.manifest_type`).
 */
object M3UParser {

    private val extinfRegex = Regex("""^#EXTINF:-?\d+((?:\s+[\w-]+="[^"]*")*)\s*,(.*)$""")
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val vlcOptRegex = Regex("""^#EXTVLCOPT:(\S+?)=(.*)$""", RegexOption.IGNORE_CASE)
    private val kodiPropRegex = Regex("""^#KODIPROP:(\S+?)=(.*)$""", RegexOption.IGNORE_CASE)
    private val hexPairRegex = Regex("""^[0-9a-fA-F]+:[0-9a-fA-F]+$""")

    fun parse(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.iterator()

        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingCountry: String? = null
        var pendingUserAgent: String? = null
        var pendingReferrer: String? = null
        var pendingLicenseType: String? = null
        var pendingLicenseKey: String? = null
        var pendingManifestType: String? = null
        val seenIds = HashSet<String>()

        fun reset() {
            pendingName = null
            pendingLogo = null
            pendingCountry = null
            pendingUserAgent = null
            pendingReferrer = null
            pendingLicenseType = null
            pendingLicenseKey = null
            pendingManifestType = null
        }

        while (lines.hasNext()) {
            val line = lines.next()
            when {
                line.startsWith("#EXTINF") -> {
                    val match = extinfRegex.find(line) ?: continue
                    val attrs = match.groupValues[1]
                    val name = match.groupValues[2].trim()
                    val attrMap = attrRegex.findAll(attrs).associate { it.groupValues[1] to it.groupValues[2] }

                    pendingName = name.ifBlank { attrMap["tvg-name"] }
                    pendingLogo = attrMap["tvg-logo"]?.ifBlank { null }
                    pendingCountry = attrMap["group-title"]?.ifBlank { null }
                    pendingUserAgent = attrMap["http-user-agent"]?.ifBlank { null }
                    pendingReferrer = attrMap["http-referrer"]?.ifBlank { null }
                }
                line.startsWith("#EXTVLCOPT") -> {
                    val match = vlcOptRegex.find(line) ?: continue
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    when (key) {
                        "http-user-agent" -> if (pendingUserAgent == null) pendingUserAgent = value
                        "http-referrer" -> if (pendingReferrer == null) pendingReferrer = value
                    }
                }
                line.startsWith("#KODIPROP") -> {
                    val match = kodiPropRegex.find(line) ?: continue
                    val key = match.groupValues[1].lowercase()
                    val value = match.groupValues[2].trim()
                    when (key) {
                        "inputstream.adaptive.license_type" -> pendingLicenseType = value
                        "inputstream.adaptive.license_key" -> pendingLicenseKey = value
                        "inputstream.adaptive.manifest_type" -> pendingManifestType = value.lowercase()
                    }
                }
                line.startsWith("#") -> {
                    // Ignore other directives (#EXTM3U, #EXTGRP, etc.)
                }
                else -> {
                    val name = pendingName
                    if (name != null) {
                        val country = pendingCountry?.takeIf { it.isNotBlank() } ?: "Lainnya"
                        val baseId = "$name|$line".hashCode().toString()
                        var id = baseId
                        var suffix = 1
                        while (!seenIds.add(id)) {
                            id = "$baseId-${suffix++}"
                        }
                        val widevine = buildWidevineConfig(pendingLicenseType, pendingLicenseKey)
                        channels += Channel(
                            id = id,
                            name = name,
                            logoUrl = pendingLogo,
                            streamUrl = line,
                            userAgent = pendingUserAgent,
                            referrer = pendingReferrer,
                            country = country,
                            clearKeyLicenseJson = buildClearKeyJson(pendingLicenseType, pendingLicenseKey),
                            widevineLicenseUrl = widevine?.first,
                            widevineLicenseHeaders = widevine?.second ?: emptyMap(),
                            forceDash = pendingManifestType == "mpd",
                        )
                    }
                    reset()
                }
            }
        }

        return channels
    }

    /**
     * Normalizes a `#KODIPROP:inputstream.adaptive.license_key` value into the
     * W3C Clear Key JSON ExoPlayer's DRM session manager expects. The value in
     * the wild is either already that JSON, or the much more common
     * `<kid-hex>:<key-hex>` shorthand.
     */
    private fun buildClearKeyJson(licenseType: String?, licenseKeyRaw: String?): String? {
        if (licenseKeyRaw.isNullOrBlank()) return null
        val type = licenseType?.trim()?.lowercase()
        if (type != null && type != "clearkey" && type != "org.w3.clearkey") return null

        val trimmed = licenseKeyRaw.trim()
        return when {
            trimmed.startsWith("{") -> trimmed
            hexPairRegex.matches(trimmed) -> {
                val (kidHex, keyHex) = trimmed.split(":", limit = 2)
                val kid = hexToBase64Url(kidHex) ?: return null
                val key = hexToBase64Url(keyHex) ?: return null
                """{"keys":[{"kty":"oct","k":"$key","kid":"$kid"}],"type":"temporary"}"""
            }
            else -> null
        }
    }

    /**
     * Parses a Widevine `#KODIPROP:inputstream.adaptive.license_key` value into
     * its license server URL and any extra HTTP headers it needs (e.g. an auth
     * token). The Kodi inputstream.adaptive convention is
     * `<url>|<header1>=<value1>&<header2>=<value2>|<post-data>|<response>` —
     * only the URL and headers are needed here, since ExoPlayer's default DRM
     * session manager already POSTs the raw Widevine challenge and expects a
     * raw response, matching the common case for these fields.
     */
    private fun buildWidevineConfig(licenseType: String?, licenseKeyRaw: String?): Pair<String, Map<String, String>>? {
        val type = licenseType?.trim()?.lowercase()
        if (type != "com.widevine.alpha" && type != "widevine") return null
        if (licenseKeyRaw.isNullOrBlank()) return null

        val parts = licenseKeyRaw.split("|")
        val url = parts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val headers = parts.getOrNull(1)
            ?.split("&")
            ?.mapNotNull { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank()) kv[0].trim() to kv[1].trim() else null
            }
            ?.toMap()
            .orEmpty()
        return url to headers
    }

    private fun hexToBase64Url(hex: String): String? {
        if (hex.length % 2 != 0) return null
        return runCatching {
            val bytes = ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }.getOrNull()
    }
}
