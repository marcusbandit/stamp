package com.marcusrosado.stamp

import android.app.Application
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.CityAreas
import com.marcusrosado.stamp.data.FineShapes
import com.marcusrosado.stamp.data.Flags
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.SetAside
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
class StampApp : Application(), androidx.work.Configuration.Provider {
    /**
     * WorkManager starts when the hourly look first needs it, not with every process: the
     * manifest takes out its startup initializer, which ran on the main thread before anything
     * else, for a schedule most users never turn on.
     */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Parsed once, off the main thread, as soon as the process starts. */
    val atlas by lazy {
        scope.async(Dispatchers.IO) {
            Atlas.parse(assets.open(COUNTRIES_ASSET).bufferedReader().use { it.readText() })
        }
    }

    /**
     * Where each country sits in the 1:10m file, for the selected outline at mid zoom and closer.
     * Only the places are kept; a country's text is read from the asset when it is selected.
     * Empty if the asset is missing.
     */
    val fineShapes by lazy {
        scope.async(Dispatchers.IO) {
            atlas.await()
            runCatching { FineShapes.index { runCatching { assets.open(FINE_COUNTRIES_ASSET) }.getOrNull() } }.getOrDefault(FineShapes.EMPTY)
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

    /** The marks, read on the IO thread as soon as the process starts, never on the main one. */
    val visitsLoading by lazy { scope.async(Dispatchers.IO) { VisitStore(File(filesDir, VISITS_FILE)) } }

    /** The marks. The first screen waits for [visitsLoading] before it reads them, so this never blocks it. */
    val visits: VisitStore by lazy { kotlinx.coroutines.runBlocking { visitsLoading.await() } }

    /** The marks if they were read, or null while loading or when reading them failed. */
    private fun loadedVisits(): VisitStore? =
        if (visitsLoading.isCompleted && !visitsLoading.isCancelled) runCatching { visits }.getOrNull() else null

    /**
     * Save files that could not be read and were set aside, newest first, until the user saves
     * a copy or restores one. Found after the store has loaded, since loading is what sets one aside.
     */
    private val _setAside by lazy {
        visits
        kotlinx.coroutines.flow.MutableStateFlow(SetAside.find(File(filesDir, VISITS_FILE)))
    }
    val setAside: kotlinx.coroutines.flow.StateFlow<List<File>> get() = _setAside

    /** Marks [f] dealt with, off the main thread, by the same single writer as the look. */
    fun setAsideHandled(f: File) {
        scope.launch(lookWriter) {
            SetAside.handled(f)
            _setAside.value = SetAside.find(File(filesDir, VISITS_FILE))
        }
    }
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

    /** A switch that is off until the user turns it on, which leaves a flag file behind. */
    inner class OffUntilOn(private val onFlag: String) {
        val on = kotlinx.coroutines.flow.MutableStateFlow(flags.get(onFlag))

        fun set(value: Boolean) {
            on.value = value
            scope.launch(lookWriter) { flags.set(onFlag, on.value) }
        }
    }

    /** Every place the user is known to have been, for the heat map: hourly samples and photo positions. */
    val seen by lazy { com.marcusrosado.stamp.data.Seen(File(filesDir, SEEN_FILE)) }

    /** Whether Stamp notes where the phone is about once an hour. Off until the user turns it on. */
    val hourly by lazy { OffUntilOn(FLAG_HOURLY) }

    /** Turns the hourly look on or off, and starts or stops its schedule with it. */
    fun setHourly(on: Boolean) {
        hourly.set(on)
        com.marcusrosado.stamp.data.Hourly.schedule(this, on)
    }

    /** Stamp Pro: sold in the Play build, always unlocked in a build from source. */
    val pro: com.marcusrosado.stamp.data.ProStore by lazy { com.marcusrosado.stamp.data.proStore(this, flags) }

    /** Whether the heat map of the places seen shows on the map. */
    val heatMap by lazy { Toggle(FLAG_NO_HEAT_MAP) }

    /** Everything a backup file holds: the marks and trips, and the places seen. */
    fun backupText(): String {
        val places = seen.json() ?: return visits.backup()
        return org.json.JSONObject(visits.backup()).put("seen", places).toString()
    }

    /**
     * Restores a backup. The places seen are replaced only when the backup has them, so an older
     * backup never wipes the heat map. False and nothing changed when it is no Stamp backup.
     */
    fun restoreText(text: String): Boolean {
        if (!visits.restore(text)) return false
        // The marks are in; a place file that cannot be written leaves the heat map as it was.
        runCatching { org.json.JSONObject(text).optJSONArray("seen")?.let { seen.restore(it) } }
        return true
    }

    /** Whether a country's separate land masses, such as Corsica or Hawaii, are marked on their own. */
    val split by lazy { Toggle(FLAG_WHOLE) }

    /** Whether photos kept at places show on the map. */
    val mapPhotos by lazy { Toggle(FLAG_NO_MAP_PHOTOS) }

    /** Whether the route lines between a visit's cities show on the map. */
    val routes by lazy { Toggle(FLAG_NO_ROUTES) }

    /** Whether legs by train, car or bus follow the real rails and roads, fetched per country. */
    val followLines by lazy { Toggle(FLAG_NO_FOLLOW_LINES) }

    /** Whether a tapped city's outline is fetched from OpenStreetMap when Stamp has none of its own. */
    val fetchOutlines by lazy { Toggle(FLAG_NO_CITY_OUTLINES) }

    /** Outlines fetched for cities, kept for the ones the user logged, outside the backup. */
    val cityOutlines by lazy {
        com.marcusrosado.stamp.data.CityOutlines(File(noBackupFilesDir, "city-outlines"), "Stamp/${BuildConfig.VERSION_NAME} (Android travel map)", scope)
    }

    /** The rail and road packs, kept outside the user's backup: map data, fetched again at will. */
    val routePacks by lazy { com.marcusrosado.stamp.data.RoutePacks(File(noBackupFilesDir, "routes"), scope) }

    /** Whether Stamp looks for a newer version of itself when it opens. */
    val updateCheck by lazy { Toggle(FLAG_NO_UPDATE_CHECK) }

    /** Newer versions from the GitHub releases, and installing one from inside the app. */
    val updates by lazy { com.marcusrosado.stamp.data.Updates(this, scope, BuildConfig.VERSION_NAME, BuildConfig.SELF_UPDATE) }

    /** What the map marks: countries, regions or cities. */
    val mapView: kotlinx.coroutines.flow.MutableStateFlow<com.marcusrosado.stamp.data.MapView> by lazy {
        kotlinx.coroutines.flow.MutableStateFlow(
            com.marcusrosado.stamp.data.MapView.of(runCatching { File(filesDir, MAP_VIEW_FILE).readText().trim() }.getOrNull()),
        )
    }

    fun setMapView(v: com.marcusrosado.stamp.data.MapView) {
        mapView.value = v
        scope.launch(lookWriter) { runCatching { File(filesDir, MAP_VIEW_FILE).writeText(v.key) } }
    }

    /** The outlines of each country's regions, read per country when first drawn. */
    val regionShapes by lazy {
        com.marcusrosado.stamp.data.RegionShapes { cc -> runCatching { assets.open("regions/$cc.bin") }.getOrNull() }
    }

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
    val scanner by lazy { PhotoScanner(this, scope, { atlas.await() }, { split.on.value }, onPoints = { seen.add(it) }) }

    override fun onCreate() {
        super.onCreate()
        // A crash right after a tap still saves it. Installed before the crash log, so the report
        // is written first and this runs after it; out of memory, encoding the store would fail.
        val crash = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Only a process that read the marks has any to save; a worker's never did.
            if (error !is OutOfMemoryError && warm.get()) loadedVisits()?.let { runCatching { it.flush() } }
            crash?.uncaughtException(thread, error)
        }
        // First of the rest, so a crash anywhere after it leaves a report on the phone.
        CrashLog.install(this)
        // Nothing else here: the hourly sample starts the process on its own too, up to 24 times a
        // day, and needs none of the map data. The screen starts the rest with warmUp(), and the
        // marks, the atlas and the cities start reading the first time anything touches them.
    }

    private val warm = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Starts reading the marks, the map data and the rest of what the screens need, once per
     * process. MainActivity calls it first thing, so a process the hourly sample started reads
     * only the heat map's file.
     */
    fun warmUp() {
        if (!warm.compareAndSet(false, true)) return
        // Starts reading the marks now, on the IO thread; the first screen waits for them.
        visitsLoading.start()
        // The switches the first screen reads, each a small file, read here off the main thread
        // so composing it does not wait on the disk. The look is read on the main thread on
        // purpose, before the first frame.
        scope.launch(Dispatchers.IO) {
            // Each of these is lazy and reads its file on first touch: touching them here is what
            // moves those reads off the main thread. Do not remove as unused.
            listOf(split, mapPhotos, routes, followLines, fetchOutlines, here, heatMap, updateCheck)
            mapView.value
            lastBackup.value
            // The schedule outlives the app, but a permission taken back in Android's settings
            // ends it. Turning the switch off cancels it then, so with the switch off there is
            // nothing to cancel, and WorkManager need not start at all.
            if (hourly.on.value) {
                com.marcusrosado.stamp.data.Hourly.schedule(this@StampApp, com.marcusrosado.stamp.data.Hourly.allowed(this@StampApp))
            }
        }
        // Leaving the app, every change is on disk before Android may end the process. Usually
        // the save thread has already written it and this returns at once.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStopped(activity: android.app.Activity) {
                loadedVisits()?.flush()
            }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        MapLibre.getInstance(this)
        atlas.start()
        places.start()
        // Once the map data is in, the store can tell which land each stop of a trip is on.
        scope.launch(Dispatchers.Default) {
            val a = atlas.await()
            val p = runCatching { places.await() }.getOrNull() ?: return@launch
            val seen = java.util.concurrent.ConcurrentHashMap<String, String>()
            visitsLoading.await().geo = object : com.marcusrosado.stamp.data.Geo {
                override fun landOfCity(cityId: String): String? =
                    seen[cityId] ?: p.byId[cityId]?.let { c -> a.landOf(c.lat, c.lon, c.country)?.id }?.also { seen[cityId] = it }
                override fun landOf(lat: Double, lon: Double): String? = a.locate(lat, lon)?.id
                override fun countryOf(land: String): String = a.countryOf(land)
            }
        }
        cityAreas.start()
        scope.launch(Dispatchers.IO) { cityOutlines.start() }
        scope.launch(Dispatchers.IO) { seen.load() }
        // Grants for photos of removed memories, visits or countries go back to Android.
        // Never while a file that could not be read is still set aside: an empty store would hand
        // every grant back, and restoring that file would then point at photos Stamp may no longer open.
        scope.launch(Dispatchers.IO) {
            val visits = visitsLoading.await()
            if (visits.damaged || setAside.value.isNotEmpty()) return@launch
            val inUse = visits.visits.value.values.flatMap { v -> v.trips.flatMap { it.memories } + v.memories }.mapNotNull { it.media }.toSet()
            com.marcusrosado.stamp.scan.Memories.releaseUnused(this@StampApp, inUse)
        }
    }

    companion object {
        private const val VISITS_FILE = "visits.json"
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
        private const val FLAG_NO_CITY_OUTLINES = "no_city_outlines"
        private const val FLAG_HOURLY = "hourly_here"
        private const val FLAG_NO_HEAT_MAP = "no_heat_map"
        const val SEEN_FILE = "seen.tsv"
        private const val FLAG_NO_HERE = "no_here"
        private const val FLAG_NO_UPDATE_CHECK = "no_update_check"
        private const val MAP_VIEW_FILE = "map_view.txt"
        private const val BACKUP_FILE = "last_backup.txt"
    }
}
