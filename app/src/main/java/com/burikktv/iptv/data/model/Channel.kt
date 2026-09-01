package com.burikktv.iptv.data.model

data class Channel(
    val id: String,
    val name: String,
    val logoUrl: String?,
    val streamUrl: String,
    val userAgent: String?,
    val referrer: String?,
    val country: String,
    /** W3C Clear Key license JSON (`{"keys":[...],"type":"temporary"}`), parsed from
     * `#KODIPROP:inputstream.adaptive.license_key`. Null when the channel isn't DRM-protected. */
    val clearKeyLicenseJson: String? = null,
    /** Widevine license server URL, parsed from `#KODIPROP:inputstream.adaptive.license_key`
     * when `license_type=com.widevine.alpha`. Unlike Clear Key, the decryption key itself
     * isn't in the playlist — ExoPlayer fetches it from this server at playback time. */
    val widevineLicenseUrl: String? = null,
    /** Extra HTTP headers (e.g. an auth token) required by [widevineLicenseUrl], parsed from
     * the same `license_key` value. */
    val widevineLicenseHeaders: Map<String, String> = emptyMap(),
    /** True when the playlist explicitly tags this stream as a DASH (.mpd) manifest via
     * `#KODIPROP:inputstream.adaptive.manifest_type=mpd`, even if the URL itself has no
     * `.mpd` extension for ExoPlayer to infer the format from. */
    val forceDash: Boolean = false,
)

data class Country(
    val name: String,
    val flagEmoji: String?,
)

const val FAVORITES_KEY = "__favorites__"
const val SEARCH_KEY = "__search__"
const val MANAGE_PLAYLISTS_KEY = "__manage_playlists__"
