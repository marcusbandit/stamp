package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Seen
import com.marcusrosado.stamp.data.SeenFrom
import com.marcusrosado.stamp.data.SeenPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SeenTest {
    private val dir: File = Files.createTempDirectory("seen").toFile().apply { deleteOnExit() }
    private fun seen() = Seen(File(dir, "seen.tsv"))
    private val hour = 3_600_000L

    @Test fun nearbyPhotosOnOneDayAreOnePoint() {
        val s = seen()
        val n = s.add(listOf(
            SeenPoint(10 * hour, 10.0001, 20.0001, SeenFrom.Photo),
            SeenPoint(11 * hour, 10.0002, 20.0002, SeenFrom.Photo),
            SeenPoint(40 * hour, 10.0001, 20.0001, SeenFrom.Photo),
            SeenPoint(10 * hour, 30.0, 40.0, SeenFrom.Photo),
        ))
        assertEquals(3, n)
        // Hourly samples count once an hour, not once a day.
        assertEquals(2, s.add(listOf(SeenPoint(10 * hour, 10.0001, 20.0001, SeenFrom.Hourly), SeenPoint(11 * hour, 10.0001, 20.0001, SeenFrom.Hourly))))
        assertEquals(0, s.add(listOf(SeenPoint(11 * hour + 5, 10.0001, 20.0001, SeenFrom.Hourly))))
        assertEquals(5, seen().apply { load() }.points.value.size)
    }

    @Test fun clearingOneSourceKeepsTheRest() {
        val s = seen()
        s.add(listOf(SeenPoint(1, 1.0, 1.0, SeenFrom.Hourly), SeenPoint(1, 2.0, 2.0, SeenFrom.GooglePhotos)))
        s.clear(SeenFrom.GooglePhotos)
        assertEquals(listOf(SeenFrom.Hourly), seen().apply { load() }.points.value.map { it.from })
    }

    @Test fun aBackupRoundTrips() {
        val s = seen()
        s.add(listOf(SeenPoint(5, 1.5, -2.5, SeenFrom.Photo)))
        val other = Seen(File(dir, "other.tsv"))
        other.restore(s.json())
        assertEquals(s.points.value, other.points.value)
    }

    @Test fun cellsAddUpTheirPoints() {
        val cells = Seen.cells(listOf(
            SeenPoint(1, 10.001, 20.001, SeenFrom.Hourly), SeenPoint(2, 10.002, 20.002, SeenFrom.Hourly), SeenPoint(3, 50.0, 50.0, SeenFrom.Hourly),
        ))
        assertEquals(listOf(1, 2), cells.map { it.count }.sorted())
    }
}
