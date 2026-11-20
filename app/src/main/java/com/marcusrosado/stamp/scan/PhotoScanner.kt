package com.marcusrosado.stamp.scan

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.PhotoEvidence
import com.marcusrosado.stamp.data.SeenFrom
import com.marcusrosado.stamp.data.SeenPoint
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Where a scan reads from: the photos on this phone, or a Google Photos export from Google Takeout. */
enum class ScanSource { Device, Takeout }

/** A country a scan found, with the first and last time it shows up. */
data class Found(val country: Country, val evidence: PhotoEvidence)

/**
 * Where a part-by-part Takeout import stands: the part numbers read so far, the part just
 * handed over and whether it had been read before. [uri] is that part, to delete once read.
 */
data class Parts(val numbers: List<Int>, val count: Int, val last: Int?, val again: Boolean, val uri: String, val next: Int)

sealed interface ScanState {
    data object Idle : ScanState

    data class Running(
        val checked: Int,
        val total: Int,
        val found: List<Found>,
    ) : ScanState

    data class Done(
        val found: List<Found>,
        val total: Int,
        val located: Int,
        val source: ScanSource = ScanSource.Device,
        /**
         * The ticks the user changed in the review, by country id. Kept here and not in the
         * screen, so closing the review and opening it again from Passport keeps them.
         */
        val picks: Map<String, Boolean> = emptyMap(),
        /** Set when the export is being read one part at a time. */
        val parts: Parts? = null,
        /** The picked zips read into this review, by name and size, so one picked twice counts once. */
        val zips: Set<String> = emptySet(),
        /** True until the user answers whether to add another zip. */
        val askMore: Boolean = false,
    ) : ScanState

    data class Failed(val message: String, val source: ScanSource = ScanSource.Device) : ScanState
}

/**
 * Finds the countries in the user's photos, from the GPS position saved in each photo's EXIF
 * data or in the location files of a Google Takeout export. Runs in the app's scope, so folding
 * the phone or leaving the screen does not stop it halfway. Every position found also goes to
 * [onPoints], which keeps it for the heat map, so it stays when the photo is gone.
 */
class PhotoScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val atlas: suspend () -> Atlas,
    /** Whether separate land masses count on their own; if not, a photo counts for its country. */
    private val split: () -> Boolean,
    private val onPoints: (List<SeenPoint>) -> Unit = {},
    /** Where an export read one part at a time is kept between parts. */
    private val partsFile: java.io.File? = null,
) {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var job: Job? = null

    /** Scans the photos on this phone. */
    fun start() = launch(ScanSource.Device) { scanDevice(it) }

    /**
     * Reads the Google Takeout zips the user picked. With a review of picked zips still open,
     * they add to it, ticks and all.
     */
    fun importTakeout(zips: List<Uri>) {
        val before = (_state.value as? ScanState.Done)?.takeIf { it.source == ScanSource.Takeout && it.parts == null }
        launch(ScanSource.Takeout) { c -> readTakeout(zips, c, before) }
    }

    /** The user answered whether to add another zip; the review stops asking. */
    fun answeredMore() {
        _state.update { s -> if (s is ScanState.Done) s.copy(askMore = false) else s }
    }

    /**
     * Reads one part of a Takeout export, handed over as the user downloads it, and adds what
     * it holds to the parts read before. False when another read is still running.
     */
    fun importTakeoutPart(part: Uri): Boolean {
        if (job?.isActive == true) return false
        // One review across all the parts: ticks changed after an earlier part stay.
        val picks = (_state.value as? ScanState.Done)?.takeIf { it.parts != null }?.picks.orEmpty()
        reading = true
        launch(ScanSource.Takeout) { c -> readPart(part, c).let { if (it is ScanState.Done) it.copy(picks = picks) else it } }
        return true
    }

    /** True while a Takeout part is being read, as opposed to another scan. */
    @Volatile var reading = false
        private set

    /** Forgets the parts read so far, once their countries are added. */
    fun endParts() {
        partsFile?.delete()
    }

    /** Stops a running scan, or clears a finished or failed one, back to Idle. */
    fun cancel() {
        generation++
        job?.cancel()
        _state.value = ScanState.Idle
    }

    /** Records ticks changed in the review of a finished scan; does nothing once it is gone. */
    fun pick(changes: Map<String, Boolean>) {
        _state.update { s -> if (s is ScanState.Done) s.copy(picks = s.picks + changes) else s }
    }

    /** Bumped on every start and cancel, so a stopped scan can never write state again. */
    @Volatile private var generation = 0

    private fun launch(source: ScanSource, body: suspend (Collector) -> ScanState) {
        if (job?.isActive == true) return
        val gen = ++generation
        _state.value = ScanState.Running(0, 0, emptyList())
        job = scope.launch(Dispatchers.IO) {
            val result = try {
                body(Collector(atlas(), gen, source))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScanState.Failed(e.message ?: e.javaClass.simpleName, source)
            }
            reading = false
            publish(gen, result)
        }
    }

    private fun publish(gen: Int, s: ScanState) {
        if (gen == generation) _state.value = s
    }

    /** Turns points into countries and keeps the first and last date per country. */
    private inner class Collector(private val atlas: Atlas, private val gen: Int, private val source: ScanSource) {
        private val spans = HashMap<String, LongArray>()
        private val points = ArrayList<SeenPoint>()
        private val from = if (source == ScanSource.Takeout) SeenFrom.GooglePhotos else SeenFrom.Photo

        /** Hands the positions found so far to be kept. */
        fun keep() {
            if (points.isEmpty()) return
            val batch = ArrayList(points)
            points.clear()
            runCatching { onPoints(batch) }
        }

        // Photos cluster: hundreds from one city. Cache lookups on a ~100 m grid, fine enough
        // that a cell straddling a border is rare, and remember misses too.
        private val cache = HashMap<Long, String?>()
        var located = 0
            private set

        /** Starts from what earlier parts of the same export found. */
        fun seed(session: Takeout.Session) {
            // Kept the way the earlier parts counted; with separate land masses off now, an
            // island's span joins its country's.
            session.spans.forEach { (id, s) ->
                val span = spans.getOrPut(if (split()) id else atlas.countryOf(id)) { longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE) }
                span[0] = minOf(span[0], s.first)
                span[1] = maxOf(span[1], s.second)
            }
            located = session.located
        }

        /** Starts from a review the new zips add to. */
        fun seed(done: ScanState.Done) {
            done.found.forEach { f ->
                val span = spans.getOrPut(f.country.id) { longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE) }
                if (f.evidence.firstMs > 0) span[0] = minOf(span[0], f.evidence.firstMs)
                if (f.evidence.lastMs > 0) span[1] = maxOf(span[1], f.evidence.lastMs)
            }
            located = done.located
        }

        fun spans(): Map<String, Pair<Long, Long>> = spans.mapValues { (_, s) -> s[0] to s[1] }

        fun add(lat: Double, lon: Double, ms: Long) {
            located++
            points += SeenPoint(ms, lat, lon, from)
            val key = (lat * 1000).roundToInt().toLong() * 1_000_000 + (lon * 1000).roundToInt()
            val id = if (cache.containsKey(key)) cache[key] else atlas.locate(lat, lon)?.id.also { cache[key] = it }
            id ?: return
            val span = spans.getOrPut(if (split()) id else atlas.countryOf(id)) { longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE) }
            if (ms > 0) {
                span[0] = minOf(span[0], ms)
                span[1] = maxOf(span[1], ms)
            }
        }

        fun found(): List<Found> = spans.mapNotNull { (id, s) ->
            val c = atlas.byId[id] ?: return@mapNotNull null
            val first = if (s[0] == Long.MAX_VALUE) 0 else s[0]
            val last = if (s[1] == Long.MIN_VALUE) 0 else s[1]
            Found(c, PhotoEvidence(first, last))
        }.sortedByDescending { it.evidence.lastMs }

        fun progress(checked: Int, total: Int) {
            // Handed over in large batches: each one redraws the heat map.
            if (points.size >= 20_000) keep()
            publish(gen, ScanState.Running(checked, total, found()))
        }

        fun done(total: Int, parts: Parts? = null): ScanState.Done {
            keep()
            return ScanState.Done(found(), total, located, source, parts = parts)
        }
    }

    private suspend fun scanDevice(c: Collector): ScanState {
        // Without this Android hands out photos with their location removed, and every
        // photo would look like it has none.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return ScanState.Failed("Android is not letting Stamp see where photos were taken. Allow photo access again and pick Allow all.")
        }
        return scanPhotos(listPhotos(), c)
    }

    /** Reads photos in parallel batches, publishing progress after each batch. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun scanPhotos(photos: List<Photo>, c: Collector): ScanState {
        val pool = Dispatchers.IO.limitedParallelism(PARALLEL)
        photos.chunked(BATCH).forEachIndexed { b, batch ->
            coroutineContext.ensureActive()
            val points = coroutineScope {
                batch.map { p ->
                    async(pool) {
                        val exif = readExif(p.uri)
                        val ll = exif?.latLong ?: return@async null
                        Triple(ll[0], ll[1], exif.takenMs ?: p.takenMs)
                    }
                }.awaitAll()
            }
            points.forEach { it?.let { (lat, lon, ms) -> c.add(lat, lon, ms) } }
            c.progress(minOf((b + 1) * BATCH, photos.size), photos.size)
        }
        return c.done(photos.size)
    }

    private suspend fun readTakeout(picked: List<Uri>, c: Collector, before: ScanState.Done? = null): ScanState {
        val job = coroutineContext[Job]
        before?.let(c::seed)
        val keys = picked.associateWith { "${displayName(it)} ${size(it)}" }
        val read = before?.zips.orEmpty()
        val zips = picked.filter { keys[it] !in read }.distinctBy { keys[it] }
        // First pass reads only the central directories, so progress has a real total.
        val total = zips.sumOf { uri -> withChannel(uri) { Takeout.countJson(it) } }
        var checked = 0
        var photos = before?.total ?: 0
        for (uri in zips) {
            withChannel(uri) { ch ->
                Takeout.readZip(ch) { entry ->
                    // Stop means stop, between two location files.
                    if (job?.isActive == false) throw CancellationException("stopped")
                    if (entry.photo) photos++
                    entry.point?.let { c.add(it.lat, it.lon, it.takenMs) }
                    checked++
                    if (checked % 200 == 0) c.progress(checked, total)
                }
            }
            coroutineContext.ensureActive()
            c.progress(checked, total)
        }
        // A review with photos asks for the next zip; one with none says the files were wrong.
        return c.done(photos).copy(
            picks = before?.picks.orEmpty(),
            zips = read + zips.mapNotNull { keys[it] },
            askMore = photos > 0,
        )
    }

    private suspend fun readPart(uri: Uri, c: Collector): ScanState {
        val file = partsFile ?: error("No place to keep the parts")
        val name = displayName(uri)
        val key = "$name ${size(uri)}"
        val number = name?.let(Takeout::partNumber) ?: 0
        val export = name?.let(Takeout::exportOf).orEmpty()
        // A part of another export starts over, so an old unfinished one never mixes in.
        val before = Takeout.Session.read(file).takeIf { it.export == export } ?: Takeout.Session(export = export)
        c.seed(before)
        fun parts(s: Takeout.Session, again: Boolean) =
            Parts(s.numbers, s.parts.size, number.takeIf { it > 0 }, again, uri.toString(), s.next)
        if (before.has(key)) return c.done(before.photos, parts(before, again = true)).copy(askMore = true)
        val job = coroutineContext[Job]
        var photos = 0
        var checked = 0
        var total = 0
        withChannel(uri) { ch ->
            Takeout.readZip(ch, onTotal = { total = it; c.progress(0, it) }) { entry ->
                if (job?.isActive == false) throw CancellationException("stopped")
                if (entry.photo) photos++
                entry.point?.let { c.add(it.lat, it.lon, it.takenMs) }
                checked++
                if (checked % 200 == 0) c.progress(checked, maxOf(checked, total))
            }
        }
        coroutineContext.ensureActive()
        val after = before.plus(key, number, photos, c.located, c.spans())
        Takeout.Session.write(file, after)
        return c.done(after.photos, parts(after, again = false)).copy(askMore = true)
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun size(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
    }.getOrDefault(-1L)

    private fun <T> withChannel(uri: Uri, block: (java.nio.channels.SeekableByteChannel) -> T): T {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Could not open the file")
        return try {
            pfd.use { FileInputStream(it.fileDescriptor).channel.use(block) }
        } catch (e: java.io.IOException) {
            android.util.Log.w("Stamp", "Could not read $uri", e)
            // A zip handed over straight from a mail or cloud app is a stream, not a file.
            throw IllegalStateException("Stamp could not read that zip. Download it to this phone first, then choose it from Downloads.", e)
        }
    }

    private class Photo(val uri: Uri, val takenMs: Long)

    private fun listPhotos(): List<Photo> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val out = ArrayList<Photo>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val taken = c.getLong(takenCol).takeIf { it > 0 } ?: (c.getLong(addedCol) * 1000)
                out += Photo(ContentUris.withAppendedId(collection, c.getLong(idCol)), taken)
            }
        }
        return out
    }

    private class Exif(val latLong: DoubleArray?, val takenMs: Long?)

    /** Position and capture time from EXIF. Null when the file cannot be read. */
    private fun readExif(uri: Uri): Exif? {
        // Android strips location from photos unless the original is asked for, which needs
        // ACCESS_MEDIA_LOCATION.
        val original = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(uri) else uri
        return read(original)
    }

    private fun read(uri: Uri): Exif? = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            val e = ExifInterface(it)
            Exif(
                latLong = e.latLong?.takeUnless { ll -> ll[0] == 0.0 && ll[1] == 0.0 },
                takenMs = e.dateTimeOriginal ?: e.dateTime,
            )
        }
    }.getOrNull()

    private companion object {
        const val BATCH = 64
        const val PARALLEL = 6
    }
}
