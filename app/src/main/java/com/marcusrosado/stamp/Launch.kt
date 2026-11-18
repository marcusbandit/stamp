package com.marcusrosado.stamp

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * What Stamp was opened to do, other than open: one of the app icon's shortcuts, a photo shared
 * into it, or a place link. Read from the intent here, then carried out by the app once it shows.
 */
sealed interface Launch {
    data object NewTrip : Launch
    data object FindCity : Launch
    data object Scan : Launch
    data object Here : Launch

    /** A photo shared from another app, as its content uri; its location is read on the phone. */
    data class Photo(val uri: String) : Launch

    /** A geo: link with a position, and how close it asked to look, if it said. */
    data class Place(val lat: Double, val lon: Double, val zoom: Double?) : Launch

    /** A geo: link with no position Stamp can read, such as a street address to search. */
    data object Unreadable : Launch

    companion object {
        /** The extra a launcher shortcut names itself with (res/xml/shortcuts.xml). */
        const val SHORTCUT = "com.marcusrosado.stamp.SHORTCUT"

        /** What [intent] asks for, or null for a plain launch. */
        fun of(intent: Intent?): Launch? {
            intent ?: return null
            // Reopened from the recent apps, the activity gets the intent it first came with:
            // a photo shared yesterday must not open again.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            intent.getStringExtra(SHORTCUT)?.let { return shortcut(it) }
            return when (intent.action) {
                Intent.ACTION_SEND -> stream(intent)?.let { Photo(it.toString()) }
                Intent.ACTION_VIEW -> intent.dataString?.let { geo(it) }
                else -> null
            }
        }

        private fun stream(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)

        fun shortcut(key: String): Launch? = when (key) {
            "trip" -> NewTrip
            "find" -> FindCity
            "scan" -> Scan
            "here" -> Here
            else -> null
        }

        /**
         * Reads a geo: link (RFC 5870, plus the q= and z= that map apps add): "geo:48.85,2.35",
         * "geo:48.85,2.35?z=12", or "geo:0,0?q=48.85,2.35(Eiffel Tower)". Null when it is not a
         * geo: link at all; [Unreadable] when it is one with no position, such as an address.
         */
        fun geo(link: String): Launch? {
            if (!link.startsWith("geo:", ignoreCase = true)) return null
            val body = link.substring(4).removePrefix("//")
            val params = body.substringAfter('?', "").split('&').mapNotNull { p ->
                val kv = p.split('=', limit = 2)
                if (kv.size == 2) kv[0].lowercase() to decode(kv[1]) else null
            }.toMap()
            // A position in q= is the place; the one before it is often 0,0 or only the area to search.
            val at = params["q"]?.let(::coords) ?: coords(body.substringBefore('?').substringBefore(';'))
                ?: return Unreadable
            val zoom = params["z"]?.toDoubleOrNull()?.takeIf { it > 0 }
            return Place(at.first, at.second, zoom)
        }

        private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        /** "48.85,2.35", maybe with a height or a label after it; 0,0 is no position. */
        private fun coords(s: String): Pair<Double, Double>? {
            val parts = s.substringBefore('(').split(',')
            if (parts.size < 2) return null
            val lat = parts[0].trim().toDoubleOrNull() ?: return null
            val lon = parts[1].trim().toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
            return lat to lon
        }
    }
}
