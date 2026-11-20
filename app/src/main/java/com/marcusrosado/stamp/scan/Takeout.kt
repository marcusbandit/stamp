package com.marcusrosado.stamp.scan

import org.apache.commons.compress.archivers.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    /** Google Takeout's list of the user's exports, where each part has its own Download button. */
    const val MANAGE_URL = "https://takeout.google.com/manage"

    /**
     * The part number Google puts at the end of a Takeout zip's name, as in
     * takeout-20240101T000000Z-3-007.zip, or null for a name without one.
     */
    /**
     * Which export a part belongs to: its name without the part number, as in
     * takeout-20240101T000000Z for takeout-20240101T000000Z-007.zip.
     */
    fun exportOf(name: String): String =
        name.replace(Regex("""-\d{1,4}\.zip$""", RegexOption.IGNORE_CASE), "").removeSuffix(".zip")

    fun partNumber(name: String): Int? =
        Regex("""-(\d{1,4})\.zip$""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The part size to pick in Takeout so one part fits in [freeBytes] with room to spare, in GB:
     * one of the sizes Takeout offers, never below its smallest.
     */
    fun partSizeFor(freeBytes: Long): Int {
        val gb = 1L shl 30
        return listOf(50, 10, 4, 2).firstOrNull { it * gb + it * gb / 4 + gb <= freeBytes } ?: 1
    }

    /**
     * An export read one part at a time, kept in a small file between parts, since the user
     * leaves Stamp to download each one. [parts] maps each part read, by name and size, to its
     * number; [spans] holds the first and last time per country id, as a scan collects them.
     */
    data class Session(
        val parts: Map<String, Int> = emptyMap(),
        val spans: Map<String, Pair<Long, Long>> = emptyMap(),
        val photos: Int = 0,
        val located: Int = 0,
        /** The export these parts came from, by [exportOf]; a part of another one starts over. */
        val export: String = "",
    ) {
        /** True when the part named by [key] was read already. */
        fun has(key: String) = key in parts

        /**
         * This session with one more part read: its [photos] counted on top, and [spans] and
         * [located] as the scan now holds them, since the scan started from this session's.
         */
        fun plus(key: String, number: Int, photos: Int, located: Int, spans: Map<String, Pair<Long, Long>>) =
            Session(parts + (key to number), spans, this.photos + photos, located, export)

        /** The lowest part number not read yet, to download next. */
        val next: Int get() = generateSequence(1) { it + 1 }.first { it !in parts.values }

        /** The part numbers read, in order, leaving out parts whose name had none. */
        val numbers: List<Int> get() = parts.values.filter { it > 0 }.sorted()

        fun toJson(): String = JSONObject()
            .put("parts", JSONObject().apply { parts.forEach { (k, n) -> put(k, n) } })
            .put("spans", JSONObject().apply { spans.forEach { (id, s) -> put(id, JSONArray().put(s.first).put(s.second)) } })
            .put("photos", photos)
            .put("located", located)
            .put("export", export)
            .toString()

        companion object {
            fun parse(json: String): Session? = runCatching {
                val o = JSONObject(json)
                val p = o.getJSONObject("parts")
                val s = o.getJSONObject("spans")
                Session(
                    parts = p.keys().asSequence().associateWith { p.getInt(it) },
                    spans = s.keys().asSequence().associateWith { s.getJSONArray(it).let { a -> a.getLong(0) to a.getLong(1) } },
                    photos = o.getInt("photos"),
                    located = o.getInt("located"),
                    export = o.optString("export"),
                )
            }.getOrNull()

            fun read(file: File): Session =
                runCatching { file.readText() }.getOrNull()?.let(::parse) ?: Session()

            /** Writes beside the file and moves it over, so a kill mid-write keeps the last good one. */
            fun write(file: File, session: Session) {
                val part = File(file.path + ".part")
                part.writeText(session.toJson())
                if (!part.renameTo(file)) { file.delete(); part.renameTo(file) }
            }
        }
    }

    /** Google Takeout, opened on the export page with only Google Photos selected. */
    const val TAKEOUT_URL = "https://takeout.google.com/settings/takeout/custom/photos"

    /**
     * Stamp's page for exports too big for the phone (docs/takeout). It reads the zips on a
     * computer, in the browser, and saves the places as one small zip of sidecars that
     * [readZip] reads like any Takeout part.
     */
    const val COMPUTER_URL = "https://marcusbandit.github.io/stamp/takeout/"
}
