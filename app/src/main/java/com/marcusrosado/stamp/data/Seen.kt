package com.marcusrosado.stamp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.File
import java.util.Locale
import kotlin.math.floor

/** Where a point of the heat map came from. The key is what the file holds. */
enum class SeenFrom(val key: String) {
    /** The hourly look at where the phone is. */
    Hourly("h"),
    /** A photo on this phone. */
    Photo("p"),
    /** A photo in a Google Photos export. */
    GooglePhotos("g"),
    ;

    companion object {
        fun of(key: String): SeenFrom? = entries.firstOrNull { it.key == key }
    }
}

/** Somewhere the user was, and when, in epoch ms. */
data class SeenPoint(val ms: Long, val lat: Double, val lon: Double, val from: SeenFrom)

/** A square of the heat map with how many points fell in it. */
class HeatCell(val lat: Double, val lon: Double, val count: Int)

/**
 * Every place the user is known to have been: the hourly samples, and the positions of their
 * photos, kept on their own so the points stay when the photos go. One line per point in a
 * private file, added to as points come in; it is part of the backup.
 *
 * Photos taken a hundred metres apart on one day are one point, and so is a second hourly
 * sample in the same hour, so a big library or a long stay at home does not swamp the map.
 */
class Seen(private val file: File) {
    private val _points = MutableStateFlow<List<SeenPoint>>(emptyList())
    val points: StateFlow<List<SeenPoint>> = _points.asStateFlow()
    private val keys = HashSet<Key>()
    private var loaded = false
    // The file was there but could not be read: it is left as it is, and a backup leaves the
    // places out rather than saving none and wiping them on the next restore.
    private var unreadable = false

    private data class Key(val lat: Int, val lon: Int, val time: Long, val from: SeenFrom)

    private fun key(p: SeenPoint) = Key(
        floor(p.lat * 1000).toInt(), floor(p.lon * 1000).toInt(),
        if (p.from == SeenFrom.Hourly) p.ms / HOUR else p.ms / DAY, p.from,
    )

    /** Reads the file once; every other call does this first. Never on the main thread. */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val out = ArrayList<SeenPoint>()
        if (file.exists()) {
            val read = runCatching { file.forEachLine { line -> parse(line)?.let { if (keys.add(key(it))) out += it } } }
            unreadable = read.isFailure
        }
        _points.value = out
    }

    /** Adds [new], dropping those already known. Returns how many were added. */
    @Synchronized
    fun add(new: List<SeenPoint>): Int {
        load()
        val fresh = new.filter { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && keys.add(key(it)) }
        if (fresh.isEmpty()) return 0
        file.parentFile?.mkdirs()
        file.appendText(fresh.joinToString("") { line(it) + "\n" })
        _points.value = _points.value + fresh
        return fresh.size
    }

    /** Forgets every point, or only those from [from]. */
    @Synchronized
    fun clear(from: SeenFrom? = null) {
        load()
        replace(if (from == null) emptyList() else _points.value.filter { it.from != from })
    }

    /** The points as they go in a backup, or null when the file could not be read. */
    @Synchronized
    fun json(): JSONArray? {
        load()
        if (unreadable) return null
        return JSONArray(_points.value.map { line(it) })
    }

    /** Replaces every point with those of a backup. */
    @Synchronized
    fun restore(a: JSONArray) {
        load()
        replace((0 until a.length()).mapNotNull { parse(a.optString(it)) })
    }

    private fun replace(list: List<SeenPoint>) {
        keys.clear()
        val kept = list.filter { keys.add(key(it)) }
        file.parentFile?.mkdirs()
        val part = File(file.parentFile, file.name + ".part")
        part.writeText(kept.joinToString("") { line(it) + "\n" })
        if (!part.renameTo(file)) { file.writeText(part.readText()); part.delete() }
        _points.value = kept
    }

    companion object {
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR

        private fun line(p: SeenPoint) = String.format(Locale.ROOT, "%d\t%.4f\t%.4f\t%s", p.ms, p.lat, p.lon, p.from.key)

        private fun parse(line: String): SeenPoint? {
            val f = line.split('\t')
            if (f.size < 4) return null
            val lat = f[1].toDoubleOrNull() ?: return null
            val lon = f[2].toDoubleOrNull() ?: return null
            return SeenPoint(f[0].toLongOrNull() ?: 0, lat, lon, SeenFrom.of(f[3]) ?: return null)
        }

        /** The points gathered into squares of [step] degrees, each at its points' middle. */
        fun cells(points: List<SeenPoint>, step: Double = 0.02): List<HeatCell> {
            class Sum { var lat = 0.0; var lon = 0.0; var n = 0 }
            val by = HashMap<Long, Sum>()
            for (p in points) {
                val k = floor(p.lat / step).toLong() * 1_000_000 + floor(p.lon / step).toLong()
                val s = by.getOrPut(k) { Sum() }
                s.lat += p.lat; s.lon += p.lon; s.n++
            }
            return by.values.map { HeatCell(it.lat / it.n, it.lon / it.n, it.n) }
        }
    }
}
