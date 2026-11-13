package com.marcusrosado.stamp.data

/** A point typed or pasted by the user, in decimal degrees. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Reads a coordinate pair in the forms people paste:
 * - decimal, the way Google Maps copies it: `34.4667, 131.7667` or `-33.86 151.21`
 * - with hemispheres: `34.4667° N, 131.7667° E`, `N 34.4667 E 131.7667`, `34.4667N 131.7667E`
 * - degrees, minutes and seconds: `34°28'00.1"N 131°46'00.1"E`, also with ′ and ″
 * - degrees and decimal minutes: `34°28.002'N 131°46.002'E`
 * - a Google Maps link: `.../@34.4667,131.7667,15z`, `...!3d34.4667!4d131.7667`, `?q=34.4667,131.7667`
 *
 * Returns null for anything it cannot read as one point on Earth.
 */
object Coordinates {
    private const val NUM = """[-+]?\d+(?:\.\d+)?"""
    // A place link carries the exact pin as !3d<lat>!4d<lon>; the @ part is only the view.
    private val pinInLink = Regex("""!3d($NUM)!4d($NUM)""")
    private val atInLink = Regex("""@($NUM),($NUM)""")
    private val queryInLink = Regex("""[?&](?:q|query|ll|center|destination|daddr|sll)=($NUM)(?:,|%2C)\s*($NUM)""", RegexOption.IGNORE_CASE)
    private val number = Regex("""\d+(?:\.\d+)?""")
    private val hemisphere = Regex("""(?<![A-Z])[NSEW](?![A-Z])""")

    fun parse(input: String): LatLon? {
        val s = input.trim()
        if (s.isEmpty()) return null
        // Most exact first: the place's pin, then a searched point, and only then the view's centre.
        for (r in listOf(pinInLink, queryInLink, atInLink)) {
            for (m in r.findAll(s)) {
                valid(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())?.let { return it }
            }
        }
        // A link with no point in it is not a pair of numbers to guess at.
        if (s.contains("://") || s.startsWith("www.", ignoreCase = true)) return null
        return plain(s)
    }

    private fun plain(raw: String): LatLon? {
        val s = raw.uppercase()
            .replace('′', '\'').replace('’', '\'').replace('″', '"').replace("''", "\"")
            .replace('º', '°')
        val letters = hemisphere.findAll(s).toList()
        val (a, b) = when {
            letters.size == 2 -> {
                val firstNum = number.find(s)?.range?.first ?: return null
                if (letters[0].range.first > firstNum) {
                    // Suffixes: 34°28'N 131°46'E
                    s.substring(0, letters[0].range.last + 1) to s.substring(letters[0].range.last + 1)
                } else {
                    // Prefixes: N 34 28 E 131 46
                    s.substring(0, letters[1].range.first) to s.substring(letters[1].range.first)
                }
            }
            letters.isEmpty() -> splitBare(s) ?: return null
            else -> return null
        }
        val p = part(a) ?: return null
        val q = part(b) ?: return null
        // A pair given longitude first, E or W before N or S, still lands the right way round.
        return when {
            p.axis == 'X' && q.axis != 'X' -> valid(q.value, p.value)
            p.axis == 'Y' && q.axis == 'Y' -> null
            p.axis == 'X' && q.axis == 'X' -> null
            else -> valid(p.value, q.value)
        }
    }

    /** Splits a pair with no hemisphere letters: at a comma, or halfway through the numbers. */
    private fun splitBare(s: String): Pair<String, String>? {
        val commas = s.count { it == ',' }
        if (commas == 1) {
            val i = s.indexOf(',')
            return s.substring(0, i) to s.substring(i + 1)
        }
        val nums = number.findAll(s).toList()
        if (nums.size !in setOf(2, 4, 6)) return null
        val cut = nums[nums.size / 2].range.first
        // Keep a minus sign with the number it belongs to.
        val at = if (cut > 0 && s[cut - 1] == '-') cut - 1 else cut
        return s.substring(0, at) to s.substring(at)
    }

    /** One half of the pair: a signed value and which axis its letter named, if any. */
    private class Part(val value: Double, val axis: Char?)

    private fun part(s: String): Part? {
        val nums = number.findAll(s).map { it.value.toDouble() }.toList()
        if (nums.isEmpty() || nums.size > 3) return null
        if (nums.drop(1).any { it >= 60.0 }) return null
        // Minutes and seconds only make sense after whole degrees.
        if (nums.size > 1 && nums[0] % 1.0 != 0.0) return null
        val abs = nums[0] + nums.getOrElse(1) { 0.0 } / 60.0 + nums.getOrElse(2) { 0.0 } / 3600.0
        val letter = hemisphere.find(s)?.value?.single()
        val negative = s.trimStart().startsWith("-") || letter == 'S' || letter == 'W'
        val axis = when (letter) {
            'N', 'S' -> 'Y'
            'E', 'W' -> 'X'
            else -> null
        }
        return Part(if (negative) -abs else abs, axis)
    }

    private fun valid(lat: Double, lon: Double): LatLon? =
        if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
}
