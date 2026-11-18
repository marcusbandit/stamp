package com.marcusrosado.stamp.data

/** How many recently picked cities the city picker remembers. */
const val RECENT_CITIES = 8

/** [recent] with [id] moved to the front, newest first, at most [max] long. */
fun bumpRecent(recent: List<String>, id: String, max: Int = RECENT_CITIES): List<String> =
    (listOf(id) + recent.filter { it != id }).take(max)

/** The recent city ids written one per line, and read back the same way. */
fun recentToText(recent: List<String>): String = recent.joinToString("\n")
fun recentFromText(text: String?): List<String> =
    text.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(RECENT_CITIES).toList()

/**
 * The cities to offer before anything is typed: the recent picks, newest first, then the
 * user's own cities, biggest first, at most [limit]. Ids that are no city, such as a pin,
 * and cities [keep] turns down are left out.
 */
fun yourCities(
    recent: List<String>,
    mine: Collection<String>,
    byId: Map<String, City>,
    keep: (City) -> Boolean = { true },
    limit: Int = 10,
): List<City> {
    val fromRecent = recent.mapNotNull { byId[it] }.filter(keep)
    val seen = fromRecent.mapTo(HashSet()) { it.id }
    val fromMine = mine.mapNotNull { byId[it] }.filter { it.id !in seen && keep(it) }
        .distinctBy { it.id }.sortedByDescending { it.population }
    return (fromRecent + fromMine).take(limit)
}
