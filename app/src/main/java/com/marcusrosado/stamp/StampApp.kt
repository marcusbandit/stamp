package com.marcusrosado.stamp

import android.app.Application
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.CityAreas
import com.marcusrosado.stamp.data.FineShapes
import com.marcusrosado.stamp.data.Flags
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.VisitStore
import com.marcusrosado.stamp.scan.PhotoScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import java.io.File

/** Holds the things that outlive any one screen: the country data, the marks, the scanner. */
class StampApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Parsed once, off the main thread, as soon as the process starts. */
    val atlas by lazy {
        scope.async(Dispatchers.IO) {
            Atlas.parse(assets.open(COUNTRIES_ASSET).bufferedReader().use { it.readText() })
        }
    }

    /** The 1:10m shapes, for the selected outline at mid zoom and closer. Empty if the asset is missing. */
    val fineShapes by lazy {
        scope.async(Dispatchers.IO) {
            atlas.await()
            runCatching { assets.open(FINE_COUNTRIES_ASSET).use { FineShapes.read(it.readBytes()) } }.getOrDefault(FineShapes.EMPTY)
        }
    }

    /** Cities and region names, read after the atlas so the map comes first. */
    val places by lazy {
        scope.async(Dispatchers.IO) {
            atlas.await()
            assets.open(CITIES_ASSET).bufferedReader().use { cities ->
                assets.open(REGIONS_ASSET).bufferedReader().use { regions -> Places.read(cities, regions) }
            }
        }
    }

    /** Airports with scheduled flights, for the flights of a trip. Read when first asked for. */
    val airports by lazy {
        scope.async(Dispatchers.IO) {
            runCatching { assets.open(AIRPORTS_ASSET).bufferedReader().use { com.marcusrosado.stamp.data.Airports.read(it) } }.getOrNull()
        }
    }

    /** City outlines, read last: the map and the city list come first. Empty if the asset is missing. */
    val cityAreas by lazy {
        scope.async(Dispatchers.IO) {
            places.await()
            runCatching { assets.open(CITY_AREAS_ASSET).use { CityAreas.read(it) } }.getOrDefault(CityAreas.EMPTY)
        }
    }

    val visits by lazy { VisitStore(File(filesDir, "visits.json")) }
    val flags by lazy { Flags(filesDir) }

    /** The look the user picked, by id, or "auto" to follow the phone. Kept in a one-line file. */
    val look: kotlinx.coroutines.flow.MutableStateFlow<String> by lazy {
        kotlinx.coroutines.flow.MutableStateFlow(
            // Read before the first frame on purpose: reading it later would flash the wrong look.
            runCatching { java.io.File(filesDir, LOOK_FILE).readText().trim() }.getOrNull()?.ifEmpty { null }
                ?: com.marcusrosado.stamp.ui.AUTO_LOOK,
        )
    }

    /**
     * A switch that is on unless the user turned it off, which leaves a flag file behind. Written
     * by the same single writer as the look, so quick flips land in order.
     */
    inner class Toggle(private val offFlag: String) {
        val on = kotlinx.coroutines.flow.MutableStateFlow(!flags.get(offFlag))

        fun set(value: Boolean) {
            on.value = value
            scope.launch(lookWriter) { flags.set(offFlag, !on.value) }
        }
    }

    /** Whether a country's separate land masses, such as Corsica or Hawaii, are marked on their own. */
    val split by lazy { Toggle(FLAG_WHOLE) }

    /** Whether photos kept at places show on the map. */
    val mapPhotos by lazy { Toggle(FLAG_NO_MAP_PHOTOS) }

    /** Whether the route lines between a visit's cities show on the map. */
    val routes by lazy { Toggle(FLAG_NO_ROUTES) }

    /** Whether legs by train, car or bus follow the real rails and roads, fetched per country. */
    val followLines by lazy { Toggle(FLAG_NO_FOLLOW_LINES) }

    /** The rail and road packs, kept outside the user's backup: map data, fetched again at will. */
    val routePacks by lazy { com.marcusrosado.stamp.data.RoutePacks(File(noBackupFilesDir, "routes"), scope) }

    /** Whether the country the phone is in gets marked and the map opens there. */
    val here by lazy { Toggle(FLAG_NO_HERE) }

    /** When the last backup was saved, in epoch ms, or 0 for never. */
    val lastBackup: kotlinx.coroutines.flow.MutableStateFlow<Long> by lazy {
        kotlinx.coroutines.flow.MutableStateFlow(
            runCatching { java.io.File(filesDir, BACKUP_FILE).readText().trim().toLong() }.getOrDefault(0L),
        )
    }

    fun backedUp(at: Long) {
        lastBackup.value = at
        scope.launch(lookWriter) { runCatching { java.io.File(filesDir, BACKUP_FILE).writeText("$at") } }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val lookWriter = Dispatchers.IO.limitedParallelism(1)

    fun setLook(id: String) {
        look.value = id
        // One writer at a time, so two quick picks land in the order they were made.
        scope.launch(lookWriter) { runCatching { java.io.File(filesDir, LOOK_FILE).writeText(look.value) } }
    }
    val scanner by lazy { PhotoScanner(this, scope, { atlas.await() }, { split.on.value }) }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after it leaves a report on the phone.
        CrashLog.install(this)
        MapLibre.getInstance(this)
        atlas.start()
        places.start()
        // Once the map data is in, the store can tell which land each stop of a trip is on.
        scope.launch(Dispatchers.Default) {
            val a = atlas.await()
            val p = runCatching { places.await() }.getOrNull() ?: return@launch
            val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
            visits.geo = object : com.marcusrosado.stamp.data.Geo {
                override fun landOfCity(cityId: String): String? =
                    seen[cityId] ?: p.byId[cityId]?.let { c -> a.landOf(c.lat, c.lon, c.country)?.id }?.also { seen[cityId] = it }
                override fun landOf(lat: Double, lon: Double): String? = a.locate(lat, lon)?.id
                override fun countryOf(land: String): String = a.countryOf(land)
            }
        }
        cityAreas.start()
        // Grants for photos of removed memories, visits or countries go back to Android.
        // Never after a file that could not be read: an empty store would hand every grant back,
        // and a restored backup would then point at photos Stamp may no longer open.
        if (!visits.damaged) scope.launch(Dispatchers.IO) {
            val inUse = visits.visits.value.values.flatMap { it.trips }.flatMap { it.memories }.mapNotNull { it.media }.toSet()
            com.marcusrosado.stamp.scan.Memories.releaseUnused(this@StampApp, inUse)
        }
    }

    companion object {
        const val COUNTRIES_ASSET = "countries.geojson"
        const val FINE_COUNTRIES_ASSET = "countries_10m.geojson"
        const val CITIES_ASSET = "cities.tsv"
        const val CITY_AREAS_ASSET = "cityareas.bin"
        const val REGIONS_ASSET = "regions.tsv"
        const val AIRPORTS_ASSET = "airports.tsv"
        private const val LOOK_FILE = "look.txt"
        private const val FLAG_WHOLE = "whole_countries"
        private const val FLAG_NO_MAP_PHOTOS = "no_map_photos"
        private const val FLAG_NO_ROUTES = "no_routes"
        private const val FLAG_NO_FOLLOW_LINES = "no_follow_lines"
        private const val FLAG_NO_HERE = "no_here"
        private const val BACKUP_FILE = "last_backup.txt"
    }
}
