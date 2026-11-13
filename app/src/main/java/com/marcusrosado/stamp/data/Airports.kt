package com.marcusrosado.stamp.data

import java.io.BufferedReader

/** An airport with scheduled flights, from OurAirports. */
class Airport(
    /** The three-letter IATA code, such as "HND". */
    val code: String,
    val name: String,
    /** The town it serves, such as "Tokyo"; empty when OurAirports has none. */
    val town: String,
    /** ISO alpha-2 of the country it is in. */
    val country: String,
    val lat: Double,
    val lon: Double,
    /** 0 for a big international airport, 1 for a medium one, 2 for a small one. */
    val size: Int,
) {
    val key: String = Places.fold("$name $town")
}

/**
 * The bundled airports, read once from assets/airports.tsv (built by tools/build_airports.py),
 * biggest first.
 */
class Airports private constructor(val all: List<Airport>) {
    private val byCode: Map<String, Airport> = all.associateBy { it.code }

    operator fun get(code: String): Airport? = byCode[code.uppercase()]

    /**
     * The airports a trip from or to [at] most likely used: the big ones within reach first,
     * nearest first, then smaller ones close by.
     */
    fun near(at: Pair<Double, Double>, limit: Int = 6): List<Airport> =
        all.asSequence()
            .map { it to Distance.km(at, it.lat to it.lon) }
            .filter { (a, km) -> km < REACH_KM[a.size] }
            // A big airport an hour further away still beats a small one next door.
            .sortedBy { (a, km) -> km + a.size * 120 }
            .take(limit)
            .map { it.first }
            .toList()

    /** Airports whose code, name or town matches [query], code matches first. */
    fun search(query: String, limit: Int = 40): List<Airport> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val code = q.uppercase().takeIf { it.length == 3 }?.let { byCode[it] }
        val key = Places.fold(q)
        val words = all.asSequence().filter { it !== code && it.key.contains(key) }
            .sortedWith(compareBy<Airport> { !it.key.startsWith(key) && !it.key.contains(" $key") }.thenBy { it.size })
        return (listOfNotNull(code) + words.take(limit)).take(limit)
    }

    companion object {
        /** How far people travel to an airport of each size, in km. */
        private val REACH_KM = doubleArrayOf(250.0, 120.0, 60.0)

        fun read(reader: BufferedReader): Airports = Airports(
            reader.lineSequence().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 7) return@mapNotNull null
                Airport(
                    code = p[0], name = p[1], town = p[2], country = p[3],
                    lat = p[4].toDoubleOrNull() ?: return@mapNotNull null,
                    lon = p[5].toDoubleOrNull() ?: return@mapNotNull null,
                    size = when (p[6]) { "L" -> 0; "M" -> 1; else -> 2 },
                )
            }.toList(),
        )
    }
}
