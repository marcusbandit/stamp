package com.marcusrosado.stamp.data

/**
 * The user's places as a spreadsheet: one row per marked country or land mass, with the
 * details that fit in a cell. Commas and quotes inside names are escaped the CSV way.
 */
fun visitsCsv(visits: Map<String, Visit>, atlas: Atlas, places: Places?): String = buildString {
    appendLine("country,part of,state,visits,first visit,last visit,loved,stars,cities")
    for ((id, v) in visits.entries.sortedBy { atlas.byId[it.key]?.name ?: it.key }) {
        val c = atlas.byId[id]
        val dates = v.trips.mapNotNull { it.date }.sorted()
        val cities = v.cities.mapNotNull { cid ->
            v.pins[cid]?.name ?: places?.byId?.get(cid)?.name
        }
        val row = listOf(
            c?.name ?: id,
            c?.takeIf { it.isPart }?.let { atlas.byId[it.country]?.name }.orEmpty(),
            v.state.label,
            v.trips.size.toString(),
            dates.firstOrNull()?.key.orEmpty(),
            dates.lastOrNull()?.key.orEmpty(),
            if (v.loved) "yes" else "",
            if (v.rating > 0) v.rating.toString() else "",
            cities.joinToString("; "),
        )
        appendLine(row.joinToString(",") { cell(it) })
    }
}

/**
 * One CSV cell. Text a spreadsheet would run as a formula, such as a place named "=SUM(...)",
 * gets a leading apostrophe so it stays text; commas, quotes and line breaks are quoted.
 */
private fun cell(raw: String): String {
    val s = if (raw.firstOrNull() in setOf('=', '+', '-', '@')) "'$raw" else raw
    return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
