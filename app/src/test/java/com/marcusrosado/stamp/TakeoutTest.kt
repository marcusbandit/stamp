package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.PhotoEvidence
import com.marcusrosado.stamp.scan.GeoPoint
import com.marcusrosado.stamp.scan.Takeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TakeoutTest {
    private fun sidecar(lat: Double, lon: Double, ts: Long, exifLat: Double = 0.0, exifLon: Double = 0.0) = """
        {"title":"IMG.jpg","photoTakenTime":{"timestamp":"$ts","formatted":"x"},
         "geoData":{"latitude":$lat,"longitude":$lon,"altitude":0.0},
         "geoDataExif":{"latitude":$exifLat,"longitude":$exifLon,"altitude":0.0}}
    """.trimIndent()

    @Test fun readsGeoData() {
        assertEquals(GeoPoint(35.67, 139.65, 1_680_000_000_000), Takeout.parseSidecar(sidecar(35.67, 139.65, 1_680_000_000)))
    }

    @Test fun prefersGooglesPositionOverTheCamerasOwn() {
        val p = Takeout.parseSidecar(sidecar(1.0, 2.0, 10, exifLat = 38.7, exifLon = -9.1))!!
        assertEquals(1.0, p.lat, 0.0)
        assertEquals(2.0, p.lon, 0.0)
        val camera = Takeout.parseSidecar(sidecar(0.0, 0.0, 10, exifLat = 38.7, exifLon = -9.1))!!
        assertEquals(38.7, camera.lat, 0.0)
    }

    @Test fun zeroZeroIsNoPosition() = assertNull(Takeout.parseSidecar(sidecar(0.0, 0.0, 10)))

    @Test fun albumMetadataIsSkipped() =
        assertNull(Takeout.parseSidecar("""{"title":"Trip","description":"","access":"protected"}"""))

    @Test fun readsOnlyJsonEntriesFromAZip() {
        val zip = File.createTempFile("takeout", ".zip").apply { deleteOnExit() }
        ZipOutputStream(zip.outputStream()).use { z ->
            fun put(name: String, body: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(body); z.closeEntry() }
            put("Takeout/Google Photos/Trip/IMG_1.jpg", ByteArray(4096) { 7 })
            put("Takeout/Google Photos/Trip/IMG_1.jpg.supplemental-metadata.json", sidecar(35.67, 139.65, 100).toByteArray())
            put("Takeout/Google Photos/Trip/IMG_2.jpg.json", sidecar(0.0, 0.0, 200).toByteArray())
            put("Takeout/Google Photos/Trip/metadata.json", """{"title":"Trip"}""".toByteArray())
        }
        var total = -1
        val points = mutableListOf<GeoPoint?>()
        var photos = 0
        Files.newByteChannel(zip.toPath()).use { ch -> Takeout.readZip(ch, onTotal = { total = it }) { points += it.point; if (it.photo) photos++ } }
        assertEquals(3, total)
        assertEquals("album metadata is no photo", 2, photos)
        assertEquals(listOf(GeoPoint(35.67, 139.65, 100_000)), points.filterNotNull())
    }

    /** The file docs/takeout/index.html saves on a computer, made from an invented export. */
    @Test fun readsTheComputerPagesFile() {
        val zip = File(javaClass.getResource("/takeout/stamp-google-photos.zip")!!.toURI())
        val points = mutableListOf<GeoPoint>()
        Files.newByteChannel(zip.toPath()).use { ch -> Takeout.readZip(ch) { e -> e.point?.let { points += it } } }
        assertEquals(listOf(GeoPoint(35.68, 139.69, 1_680_000_000_000), GeoPoint(48.85, 2.35, 1_690_000_000_000)), points)
    }

    @Test fun partNumbersComeFromGooglesNames() {
        assertEquals(7, Takeout.partNumber("takeout-20240101T000000Z-007.zip"))
        assertEquals(12, Takeout.partNumber("takeout-20240101T000000Z-3-012.ZIP"))
        assertNull(Takeout.partNumber("stamp-google-photos.zip"))
        assertNull(Takeout.partNumber("photos.json"))
    }

    @Test fun partSizeLeavesRoomOnThePhone() {
        val gb = 1L shl 30
        assertEquals(1, Takeout.partSizeFor(0))
        assertEquals(1, Takeout.partSizeFor(3 * gb))
        assertEquals(2, Takeout.partSizeFor(4 * gb))
        assertEquals(4, Takeout.partSizeFor(8 * gb))
        assertEquals(10, Takeout.partSizeFor(20 * gb))
        assertEquals(50, Takeout.partSizeFor(80 * gb))
    }

    @Test fun aSessionSurvivesTheFile() {
        val file = File.createTempFile("parts", ".json").apply { deleteOnExit() }
        val s = Takeout.Session(mapOf("takeout-001.zip 2000" to 1, "other.zip 5" to 0), mapOf("JPN" to (100L to 900L)), photos = 40, located = 30)
        Takeout.Session.write(file, s)
        assertEquals(s, Takeout.Session.read(file))
        file.writeText("not json")
        assertEquals(Takeout.Session(), Takeout.Session.read(file))
        assertEquals(Takeout.Session(), Takeout.Session.read(File(file.path + ".missing")))
    }

    @Test fun partsAddUp() {
        val one = Takeout.Session().plus("takeout-001.zip 10", 1, photos = 5, located = 3, spans = mapOf("JPN" to (1L to 2L)))
        val two = one.plus("takeout-002.zip 12", 2, photos = 4, located = 7, spans = mapOf("JPN" to (1L to 2L), "FRA" to (3L to 4L)))
        assertEquals(listOf(1, 2), two.numbers)
        assertEquals(9, two.photos)
        assertEquals(7, two.located)
        assertEquals(setOf("JPN", "FRA"), two.spans.keys)
        assertEquals(true, two.has("takeout-001.zip 10"))
        assertEquals(false, two.has("takeout-001.zip 11"))
        assertEquals(listOf(2), Takeout.Session().plus("a.zip 1", 0, 0, 0, emptyMap()).plus("t-002.zip 2", 2, 0, 0, emptyMap()).numbers)
    }

    @Test fun evidenceMergeWidensTheSpan() {
        val a = PhotoEvidence(firstMs = 200, lastMs = 500)
        assertEquals(PhotoEvidence(100, 500), a.merge(PhotoEvidence(100, 300)))
        assertEquals(PhotoEvidence(200, 900), a.merge(PhotoEvidence(0, 900)))
        assertEquals(a, a.merge(null))
    }
}
