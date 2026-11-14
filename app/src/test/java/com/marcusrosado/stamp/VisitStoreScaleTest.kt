package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Geo
import com.marcusrosado.stamp.data.LatestWriter
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.VisitStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Years of use: hundreds of places, hundreds of trips, thousands of stops. Every id here is
 * invented: lands are "L000" to "L199", some with a part "-P", cities are "city-00001" and on.
 */
class VisitStoreScaleTest {
    private val dir: File = Files.createTempDirectory("stamp-scale").toFile().apply { deleteOnExit() }
    private val file = File(dir, "visits.json")

    /** Remembers each city's land, the way the app's own does. */
    private val geo = object : Geo {
        val seen = HashMap<String, String>()
        override fun landOfCity(cityId: String): String? =
            seen[cityId] ?: cityId.removePrefix("city-").toIntOrNull()?.let { landFor(it) }?.also { seen[cityId] = it }
        override fun landOf(lat: Double, lon: Double): String = landFor(((lat + 90) * 10).toInt())
        override fun countryOf(land: String): String = land.substringBefore('-')
    }

    private fun landFor(n: Int): String = "L%03d".format(n % LANDS) + if (n % 7 == 0) "-P" else ""

    private fun bigStore(): VisitStore {
        val countries = JSONObject()
        for (i in 0 until LANDS) countries.put("L%03d".format(i), JSONObject().put("state", if (i % 10 == 0) "want" else "visited").put("rating", i % 6))
        val trips = JSONArray()
        val pins = JSONObject()
        var city = 0
        for (t in 0 until TRIPS) {
            val stops = (0 until STOPS).map { city++; "city-%05d".format(city) }
            val pin = "pin:t$t"
            pins.put(pin, JSONObject().put("name", "Spot $t").put("lat", (t % 1800) / 10.0 - 89.9).put("lon", 10.0))
            val memories = JSONArray((0 until 6).map { m ->
                JSONObject().put("id", "m$t-$m").put("place", stops[m % STOPS]).put("media", "content://example/$t/$m").put("taken", 1_600_000_000_000L + t * 1000L + m)
            })
            trips.put(JSONObject()
                .put("id", "t%05d".format(t))
                .put("lands", JSONArray(listOf(landFor(city - STOPS + 1))))
                .put("date", "%04d-%02d".format(2000 + t % 25, 1 + t % 12))
                .put("cities", JSONArray(stops + pin))
                .put("arrived", JSONObject(stops.withIndex().associate { (k, s) -> s to "%04d-%02d-%02d".format(2000 + t % 25, 1 + t % 12, 1 + k) }))
                .put("memories", memories))
        }
        file.writeText(JSONObject().put("version", 3).put("countries", countries).put("trips", trips).put("pins", pins).toString())
        return VisitStore(file).also { it.geo = geo }
    }

    @Test fun anEditStaysFast() {
        val s = bigStore()
        // Warm up the JIT, then time the edit itself as the main thread sees it.
        repeat(100) { s.setRating("L001", it % 6) }
        val times = (0 until 20).map { i ->
            val t0 = System.nanoTime()
            s.setRating("L%03d".format(1 + i % 9), 1 + i % 5)
            (System.nanoTime() - t0) / 1_000_000.0
        }.sorted()
        val median = times[times.size / 2]
        println("scale: ${TRIPS * STOPS} stops, edit median $median ms, worst ${times.last()} ms")
        // Was 75 ms here before saves left the caller's thread; a frame is 16 ms.
        assertTrue("edit median $median ms", median < 25.0)
    }

    @Test fun aBurstOfEditsEndsWithTheFileMatchingTheFinalState() {
        val s = bigStore()
        val trip = s.allTrips.value[17]
        repeat(100) { i ->
            s.setRating("L%03d".format(1 + i % 50), i % 6)
            s.setLoved("L%03d".format(1 + i % 30), i % 2 == 0)
            if (i % 10 == 0) s.addTrip("L%03d".format(i))
            s.updateTrip(trip.copy(date = PartialDate(2000 + i)))
        }
        s.setStopDates(trip.id, trip.cities.first(), PartialDate(2024, 5, 1), null)
        assertTrue(s.flush())
        assertEquals(s.backup(), file.readText())
    }

    @Test fun aStoreOpenedAgainLosesNothing() {
        val s = bigStore()
        val trip = s.allTrips.value[3]
        repeat(50) { i -> s.setRating("L%03d".format(1 + i), 1 + i % 5) }
        s.addTrip("L150")
        s.removeCity(trip.id, trip.cities[2])
        s.setCity("L040", "city-99999", TravelState.Want)
        // No flush: opening the file again must see everything all the same.
        val again = VisitStore(file).also { it.geo = geo }
        assertEquals(s.visits.value, again.visits.value)
        assertEquals(s.allTrips.value, again.allTrips.value)
    }

    @Test fun theWriterKeepsOnlyTheNewestAndRetriesAFailedWrite() {
        val w = LatestWriter.of(File(dir, "plain.txt"))
        val threads = (0 until 4).map { k -> Thread { repeat(500) { i -> w.submit { "$k-$i" } } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        w.submit { "last" }
        assertTrue(w.flush())
        assertEquals("last", File(dir, "plain.txt").readText())

        var tries = 0
        w.submit { if (tries++ == 0) error("disk full") else "after a retry" }
        // The save thread may get to it first; either way the first try fails and is kept.
        w.flush()
        assertTrue(w.flush())
        assertEquals("after a retry", File(dir, "plain.txt").readText())
    }

    companion object {
        const val LANDS = 200
        const val TRIPS = 600
        const val STOPS = 8
    }
}
