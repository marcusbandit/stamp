package com.marcusrosado.stamp.data

import com.marcusrosado.stamp.data.Places.Companion.fold

/**
 * Finding a country by what people call it, not only by the name on the map: "UK", "USA",
 * "Holland", "Czechia", an old name or a two-letter code.
 */
object CountrySearch {
    /** Other names, keyed by the country's id, already folded the way a query is. */
    private val aliases: Map<String, List<String>> = mapOf(
        "GBR" to listOf("uk", "great britain", "britain", "england", "scotland", "wales", "northern ireland"),
        "USA" to listOf("usa", "us", "united states", "america"),
        "NLD" to listOf("holland", "the netherlands"),
        "CZE" to listOf("czechia"),
        "MMR" to listOf("burma"),
        "CIV" to listOf("cote d'ivoire", "cote divoire"),
        "SWZ" to listOf("swaziland"),
        "MKD" to listOf("macedonia"),
        "TUR" to listOf("turkiye"),
        "CPV" to listOf("cape verde", "cabo verde"),
        "TLS" to listOf("east timor"),
        "COD" to listOf("drc", "dr congo", "congo kinshasa", "zaire"),
        "COG" to listOf("congo brazzaville"),
        "ARE" to listOf("uae", "emirates", "dubai"),
        "KOR" to listOf("korea"),
        "PRK" to listOf("dprk"),
        "IRN" to listOf("persia"),
        "LKA" to listOf("ceylon"),
        "THA" to listOf("siam"),
        "VAT" to listOf("holy see", "vatican city"),
        "RUS" to listOf("russian federation"),
        "CHN" to listOf("prc"),
        "TWN" to listOf("republic of china"),
        "BIH" to listOf("bosnia"),
        "VNM" to listOf("viet nam"),
        "LAO" to listOf("laos"),
        "FSM" to listOf("micronesia"),
        "DEU" to listOf("deutschland"),
        "ESP" to listOf("espana"),
        "JPN" to listOf("nippon", "nihon"),
    )

    /**
     * Whether [c] answers the folded query [q]: its name or its country's name contains it, its
     * code is it, or one of its other names starts with it.
     */
    fun matches(c: Country, q: String, countryName: String? = null): Boolean =
        matches(Key(c, countryName), q)

    /** What a country is searched by, folded once, so typing never folds every name again. */
    class Key(val c: Country, countryName: String?) {
        val name = fold(c.name)
        val country = countryName?.let(::fold)
        val codes = setOf(c.id.lowercase(), c.alpha2.lowercase()).filter { it.isNotEmpty() }
        val others = aliases[c.id].orEmpty()
    }

    fun matches(k: Key, q: String): Boolean {
        if (q.isEmpty()) return true
        if (k.name.contains(q) || q in k.codes) return true
        // A part also turns up under its country's name: "France" lists Corsica and Réunion.
        if (k.c.isPart && k.country?.contains(q) == true) return true
        return q.length >= 2 && k.others.any { it.startsWith(q) }
    }
}
