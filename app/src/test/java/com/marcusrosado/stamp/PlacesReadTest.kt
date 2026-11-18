package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Places
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** The city list's reader and search keys against the ones they replaced, on the real assets. */
class PlacesReadTest {
    private val citiesFile = File("src/main/assets/cities.tsv")
    private val places = citiesFile.bufferedReader().use { c ->
        File("src/main/assets/regions.tsv").bufferedReader().use { r -> Places.read(c, r) }
    }

    private class Old(val id: String, val name: String, val ascii: String, val country: String, val admin1: String, val lat: Double, val lon: Double, val population: Int)

    private fun oldRead(): List<Old> = citiesFile.readLines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 8) return@mapNotNull null
        val lat = p[5].toDoubleOrNull() ?: return@mapNotNull null
        val lon = p[6].toDoubleOrNull() ?: return@mapNotNull null
        Old(p[0], p[1], p[2], p[3], p[4], lat, lon, p[7].toIntOrNull() ?: 0)
    }

    private val marks = Regex("\\p{M}+")
    private fun oldFold(s: String) = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    @Test fun readsEveryCityAsBefore() {
        val before = oldRead()
        assertEquals(before.size, places.byId.size)
        for (o in before) {
            val c = places.byId.getValue(o.id)
            assertEquals(o.name, c.name)
            assertEquals(o.ascii, c.ascii)
            assertEquals(o.country, c.country)
            assertEquals(o.admin1, c.admin1)
            assertEquals(o.id, o.lat, c.lat, 0.0)
            assertEquals(o.id, o.lon, c.lon, 0.0)
            assertEquals(o.id, o.population, c.population)
            assertEquals(oldFold(o.name), c.key)
        }
    }

    @Test fun foldMatchesTheNormalizerForEveryName() {
        for (o in oldRead()) {
            assertEquals(oldFold(o.name), Places.fold(o.name))
            assertEquals(oldFold(o.ascii), Places.fold(o.ascii))
        }
        for (s in listOf("Malmö", "MALMÖ", "São Paulo", "Ærø", "İstanbul", "", "  Mixed Case  ")) assertEquals(oldFold(s), Places.fold(s))
    }

    @Test fun decimalsReadAsToDouble() {
        for (s in listOf("42.5078", "-0.0", "0", "-12", "180.0000", ".5", "5.", "1e3", "-", "", "1.2.3", "+4", "12345678901234567.5", "0.000000000000001"))
            assertEquals(s, s.toDoubleOrNull(), Places.decimal(s, 0, s.length))
    }

    @Test fun loadTime() {
        // Printed for the record: the old reader's work, split() and the normalizer per name,
        // against the new one. Not asserted, since a loaded build machine makes timings noisy.
        fun now() = citiesFile.bufferedReader().use { c -> "".reader().buffered().use { r -> Places.read(c, r) } }
        fun before() = oldRead().map { oldFold(it.name) }
        repeat(2) { now(); before() }
        val t0 = System.nanoTime()
        repeat(3) { before() }
        val t1 = System.nanoTime()
        repeat(3) { now() }
        val t2 = System.nanoTime()
        println("cities.tsv: split and normalizer %.0f ms, own reader %.0f ms".format((t1 - t0) / 3e6, (t2 - t1) / 3e6))
    }

    @Test fun searchFindsWhatItFoundBefore() {
        for (q in listOf("mal", "sao", "são", "new", "kyo", "a", "zz", "Ø")) {
            for (cc in listOf("SE", "BR", "US", "JP", "DK")) {
                val all = places.citiesIn(cc)
                val f = oldFold(q.trim())
                val starts = all.filter { it.key.startsWith(f) || it.ascii.lowercase(Locale.ROOT).startsWith(f) }
                val contains = all.filter { it !in starts && (it.key.contains(f) || it.ascii.lowercase(Locale.ROOT).contains(f)) }
                assertEquals("$cc $q", (starts + contains).map { it.id }, places.search(cc, q).map { it.id })
            }
        }
    }

    @Test fun worldSearchIsTheSameFromManyThreadsAtOnce() {
        // The pickers search off the main thread, and a search a keystroke made stale keeps
        // running beside the next one: the first ones race to sort the world's cities.
        val fresh = citiesFile.bufferedReader().use { c -> "".reader().buffered().use { r -> Places.read(c, r) } }
        val queries = listOf("par", "sao", "kyo", "new", "mal", "ber", "osl", "lon")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(queries.size)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val answers = queries.map { q -> pool.submit<List<String>> { start.await(); fresh.searchAll(q).map { it.id } } }
            start.countDown()
            for ((q, a) in queries.zip(answers)) assertEquals(q, places.searchAll(q).map { it.id }, a.get())
        } finally {
            pool.shutdown()
        }
    }

    @Test fun worldSearchAfterPreparingIsUnchanged() {
        val fresh = citiesFile.bufferedReader().use { c -> "".reader().buffered().use { r -> Places.read(c, r) } }
        fresh.prepareWorldSearch()
        for (q in listOf("par", "são", "x", "ab")) assertEquals(q, places.searchAll(q).map { it.id }, fresh.searchAll(q).map { it.id })
    }
}
