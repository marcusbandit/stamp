package com.marcusrosado.stamp.scan

import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONObject
import java.nio.channels.SeekableByteChannel

/** One located photo: where and when it was taken. [takenMs] is 0 when unknown. */
data class GeoPoint(val lat: Double, val lon: Double, val takenMs: Long)

/**
 * Google Takeout exports of Google Photos put a JSON "sidecar" next to every photo. It
 * holds the position Google knows for the photo, which survives even when the image's
 * own EXIF was stripped, and the capture time:
 *
 *     { "title": "IMG_0001.jpg",
 *       "photoTakenTime": { "timestamp": "1554278400" },
 *       "geoData":     { "latitude": 35.67, "longitude": 139.65 },
 *       "geoDataExif": { "latitude": 35.67, "longitude": 139.65 } }
 *
 * 0.0, 0.0 means no position. Album metadata files have no photoTakenTime and are skipped.
 */
object Takeout {

    /** The point a sidecar describes, or null when it is not a photo sidecar or has no position. */
    fun parseSidecar(json: String): GeoPoint? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val taken = o.optJSONObject("photoTakenTime") ?: return null
        val ms = (taken.optString("timestamp").toLongOrNull() ?: 0L) * 1000
        // Google's own place first: it holds any fix the user made in Google Photos.
        val geo = listOf("geoData", "geoDataExif")
            .mapNotNull { o.optJSONObject(it) }
            .map { it.optDouble("latitude", 0.0) to it.optDouble("longitude", 0.0) }
            .firstOrNull { (lat, lon) -> !(lat == 0.0 && lon == 0.0) && !lat.isNaN() && !lon.isNaN() }
            ?: return null
        return GeoPoint(geo.first, geo.second, ms)
    }

    /**
     * Reads every sidecar in one Takeout zip. Only the zip's central directory and the JSON
     * entries are read, so a 50 GB archive costs a few megabytes of reading, not 50 GB.
     */
    fun readZip(
        channel: SeekableByteChannel,
        onTotal: (Int) -> Unit = {},
        onEntry: (Entry) -> Unit,
    ) {
        ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
            val entries = zip.entries.toList().filter { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
            onTotal(entries.size)
            for (e in entries) {
                // A sidecar is a few hundred bytes; anything big is not one.
                if (e.size > MAX_SIDECAR) { onEntry(Entry(null, photo = false)); continue }
                val text = zip.getInputStream(e).use { it.readBytes().decodeToString() }
                onEntry(Entry(parseSidecar(text), photo = isPhotoSidecar(text)))
            }
        }
    }

    /** One JSON file of an export: the point it gives, if any, and whether it describes a photo at all. */
    class Entry(val point: GeoPoint?, val photo: Boolean)

    /** True for a photo's sidecar, false for album files and other metadata. */
    fun isPhotoSidecar(json: String): Boolean =
        runCatching { JSONObject(json).has("photoTakenTime") }.getOrDefault(false)

    /** Count of sidecar candidates in a zip, for progress before the real pass. */
    fun countJson(channel: SeekableByteChannel): Int =
        ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
            zip.entries.toList().count { !it.isDirectory && it.name.endsWith(".json", ignoreCase = true) }
        }

    private const val MAX_SIDECAR = 64 * 1024L

    /** Google Takeout, opened on the export page with only Google Photos selected. */
    const val TAKEOUT_URL = "https://takeout.google.com/settings/takeout/custom/photos"
}
