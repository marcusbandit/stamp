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

    @Test fun evidenceMergeWidensTheSpan() {
        val a = PhotoEvidence(firstMs = 200, lastMs = 500)
        assertEquals(PhotoEvidence(100, 500), a.merge(PhotoEvidence(100, 300)))
        assertEquals(PhotoEvidence(200, 900), a.merge(PhotoEvidence(0, 900)))
        assertEquals(a, a.merge(null))
    }
}
