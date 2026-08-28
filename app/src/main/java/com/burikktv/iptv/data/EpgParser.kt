package com.burikktv.iptv.data

import android.util.Xml
import com.burikktv.iptv.data.model.EpgProgramme
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Parses the XMLTV format used by EPG (electronic program guide) feeds, e.g.
 * `<programme start="20260827000000 +0000" stop="..." channel="...">`.
 */
object EpgParser {

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    }

    fun parse(xml: String): List<EpgProgramme> {
        val programmes = mutableListOf<EpgProgramme>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        var channelId: String? = null
        var startMillis: Long? = null
        var stopMillis: Long? = null
        var title: StringBuilder? = null
        var inTitle = false

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "programme" -> {
                            channelId = parser.getAttributeValue(null, "channel")?.ifBlank { null }
                            startMillis = parseXmltvDate(parser.getAttributeValue(null, "start"))
                            stopMillis = parseXmltvDate(parser.getAttributeValue(null, "stop"))
                            title = null
                        }
                        "title" -> {
                            if (channelId != null) {
                                inTitle = true
                                title = StringBuilder()
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inTitle) title?.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title" -> inTitle = false
                        "programme" -> {
                            val cId = channelId
                            val start = startMillis
                            val stop = stopMillis
                            val t = title?.toString()?.trim()
                            if (cId != null && start != null && stop != null && !t.isNullOrEmpty()) {
                                programmes += EpgProgramme(cId, t, start, stop)
                            }
                            channelId = null
                            startMillis = null
                            stopMillis = null
                            title = null
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return programmes
    }

    private fun parseXmltvDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { dateFormat.get()!!.parse(raw.trim())?.time }.getOrNull()
    }
}
