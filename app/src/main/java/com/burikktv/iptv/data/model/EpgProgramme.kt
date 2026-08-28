package com.burikktv.iptv.data.model

/** One scheduled programme entry parsed from an XMLTV (EPG) feed. */
data class EpgProgramme(
    val channelId: String,
    val title: String,
    val startMillis: Long,
    val stopMillis: Long,
)

/** The programme currently airing on [tvgId] at [nowMillis], if the EPG covers that channel. */
fun Map<String, List<EpgProgramme>>.currentProgrammeTitle(
    tvgId: String?,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    if (tvgId.isNullOrBlank()) return null
    return this[tvgId]?.firstOrNull { nowMillis in it.startMillis until it.stopMillis }?.title
}
