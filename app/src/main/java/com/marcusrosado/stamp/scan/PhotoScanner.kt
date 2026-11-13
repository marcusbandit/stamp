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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** A country a scan found, with the first and last time it shows up. */
data class Found(val country: Country, val evidence: PhotoEvidence)

sealed interface ScanState {
    data object Idle : ScanState

    data class Running(
        val checked: Int,
        val total: Int,
        val found: List<Found>,
    ) : ScanState

    data class Done(val found: List<Found>, val total: Int, val located: Int) : ScanState

    data class Failed(val message: String) : ScanState
}

/**
 * Finds the countries in the user's photos, from the GPS position saved in each photo's
 * EXIF data. Runs in the app's scope, so
 * folding the phone or leaving the screen does not stop it halfway.
 */
class PhotoScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val atlas: suspend () -> Atlas,
    /** Whether separate land masses count on their own; if not, a photo counts for its country. */
    private val split: () -> Boolean,
) {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var job: Job? = null

    /** Scans the photos on this phone. */
    fun start() = launch { scanDevice(it) }

    /** Stops a running scan, or clears a finished or failed one, back to Idle. */
    fun cancel() {
        generation++
        job?.cancel()
        _state.value = ScanState.Idle
    }

    /** Bumped on every start and cancel, so a stopped scan can never write state again. */
    @Volatile private var generation = 0

    private fun launch(body: suspend (Collector) -> ScanState) {
        if (job?.isActive == true) return
        val gen = ++generation
        _state.value = ScanState.Running(0, 0, emptyList())
        job = scope.launch(Dispatchers.IO) {
            val result = try {
                body(Collector(atlas(), gen))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScanState.Failed(e.message ?: e.javaClass.simpleName)
            }
            publish(gen, result)
        }
    }

    private fun publish(gen: Int, s: ScanState) {
        if (gen == generation) _state.value = s
    }

    /** Turns points into countries and keeps the first and last date per country. */
    private inner class Collector(private val atlas: Atlas, private val gen: Int) {
        private val spans = HashMap<String, LongArray>()

        // Photos cluster: hundreds from one city. Cache lookups on a ~100 m grid, fine enough
        // that a cell straddling a border is rare, and remember misses too.
        private val cache = HashMap<Long, String?>()
        var located = 0
            private set

        fun add(lat: Double, lon: Double, ms: Long) {
            located++
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

        fun progress(checked: Int, total: Int) = publish(gen, ScanState.Running(checked, total, found()))

        fun done(total: Int) = ScanState.Done(found(), total, located)
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
