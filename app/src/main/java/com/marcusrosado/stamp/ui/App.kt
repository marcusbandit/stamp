package com.marcusrosado.stamp.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.ensureActive
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcusrosado.stamp.Posture
import com.marcusrosado.stamp.StampApp
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.postureFor
import com.marcusrosado.stamp.scan.ScanState
import kotlin.math.ln
import kotlin.math.max

private const val FLAG_ONBOARDED = "onboarded"
private const val FLAG_SCANNED = "scanned"

/** Close enough that a big city's outline fills a good part of the screen. */
const val CITY_ZOOM = 7.0

/** How close the map opens over the country the phone is in: the country with its neighbours. */
const val HERE_ZOOM = 3.4

/** As close as the map goes: a home, or where the phone is. */
const val HOME_ZOOM = 12.0

/**
 * A request to move the map; [nonce] makes a repeat of the same request fire again. With [box] (south, west, north, east) the map fits that area into
 * the part of the map left showing, no closer than [zoom].
 */
data class Focus(
    val lat: Double,
    val lon: Double,
    val zoom: Double,
    val nonce: Long = System.nanoTime(),
    val box: List<Double>? = null,
)

/** Everything a layout needs to draw and to report taps. Layouts hold no state of their own. */
class AppState(
    val posture: Posture,
    val destination: Destination,
    val atlas: Atlas,
    /** Null for the moment it takes to read the city list after launch. */
    val places: Places?,
    val cityAreas: com.marcusrosado.stamp.data.CityAreas,
    val visits: Map<String, Visit>,
    val home: String?,
    val selected: Country?,
    val scan: ScanState,
    val hasScanned: Boolean,
    val camera: CameraSpot,
    val focus: Focus?,
    val onDestination: (Destination) -> Unit,
    val onSelect: (String?) -> Unit,
    /** Sets the selection without moving the map. */
    val onRestoreSelection: (String?) -> Unit,
    val onSet: (String, TravelState?) -> Unit,
    val details: DetailActions,
    val onOpenList: () -> Unit,
    /** Search every country's cities and fly to the one picked. */
    val onFindCity: () -> Unit,
    /** Start a trip at a city anywhere, then open it. */
    val onNewTrip: () -> Unit,
    /** A trip to open as soon as its country's card is up, set by [onNewTrip] and the passport. */
    val pendingTrip: String?,
    val onTripOpened: () -> Unit,
    /** The trip timeline closed. */
    val onTripClosed: () -> Unit = {},
    /** Opens a trip from anywhere: its starting place's card, then its timeline. */
    val onOpenTrip: (String) -> Unit,
    /** Every trip, each once. */
    val trips: List<com.marcusrosado.stamp.data.Trip>,
    /** Opens the scan flow as it stands: progress, or the review of a finished scan. */
    val onOpenScan: () -> Unit,
    /** Starts over: drops a finished or failed scan and opens the choice of source. */
    val onOpenNewScan: () -> Unit,
    val onFocused: () -> Unit,
    val onOpenCountry: (String) -> Unit,
    val onIntro: () -> Unit,
    val look: String,
    val onLook: (String) -> Unit,
    /** Separate land masses are marked on their own. */
    val split: Boolean,
    val onSplit: (Boolean) -> Unit,
    /** Photos kept at places show on the map. */
    val mapPhotos: Boolean,
    val onMapPhotos: (Boolean) -> Unit,
    /** Route lines between a visit's cities show on the map. */
    val routes: Boolean,
    val onRoutes: (Boolean) -> Unit,
    /** Whether train, car and bus legs follow the real rails and roads. */
    val followLines: Boolean = true,
    val onFollowLines: (Boolean) -> Unit = {},
    /** When the last backup was saved, epoch ms, 0 for never. */
    val lastBackup: Long,
    /** Whether the country the phone is in gets marked and the map opens there. */
    val here: Boolean = false,
    val onHere: (Boolean) -> Unit = {},
    /** Where the phone was found by the locate button, drawn as a dot. */
    val me: Pair<Double, Double>? = null,
    val onLocate: () -> Unit = {},
    /** Flies the map in close on a home. */
    val onShowHome: (Double, Double) -> Unit = { _, _ -> },
    val app: com.marcusrosado.stamp.StampApp,
) {
    /** "🇦🇹 Austria" for a country code. */
    val countryLabel: (String) -> String = { a2 ->
        atlas.mains.firstOrNull { it.alpha2 == a2 }?.let { "${it.flag} ${it.name}".trim() } ?: a2
    }
}

/** Waits for the country data, then shows onboarding once and the app after that. */
@Composable
fun Root(app: StampApp) {
    val atlas by produceState<Atlas?>(null) { value = app.atlas.await() }
    var onboarded by rememberSaveable { mutableStateOf(app.flags.get(FLAG_ONBOARDED)) }
    var scanOpen by rememberSaveable { mutableStateOf(false) }
    // The introduction opened again from Settings: shown over the app, which keeps its place.
    var replaying by rememberSaveable { mutableStateOf(false) }
    val home by app.visits.home.collectAsStateWithLifecycle()
    val lookId by app.look.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize()) {
        val a = atlas ?: return@Surface
        if (!onboarded) HalfPage {
            Onboarding(
                a,
                home = home,
                onHome = { app.visits.setHome(it) },
                onStartScan = { app.flags.set(FLAG_SCANNED, true); app.scanner.start() },
                look = lookId,
                onLook = app::setLook,
                onHere = app.here::set,
                onDone = { openScan ->
                    app.flags.set(FLAG_ONBOARDED, true)
                    onboarded = true
                    scanOpen = openScan
                },
            )
        } else {
            App(
                app, a, scanOpen,
                onScanOpen = { scanOpen = it },
                onIntro = { replaying = true },
            )
            if (replaying) {
                HalfPage {
                    Onboarding(
                        a,
                        home = home,
                        onHome = { app.visits.setHome(it) },
                        onStartScan = { app.flags.set(FLAG_SCANNED, true); app.scanner.start() },
                        look = lookId,
                        onLook = app::setLook,
                        onHere = app.here::set,
                        onDone = { openScan ->
                            replaying = false
                            scanOpen = openScan
                        },
                        onClose = { replaying = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun App(
    app: StampApp,
    atlas: Atlas,
    scanOpen: Boolean,
    onScanOpen: (Boolean) -> Unit,
    onIntro: () -> Unit,
) {
    var destination by rememberSaveable { mutableStateOf(Destination.Map) }
    // The tab a jump came from, such as "show on map" from Settings, so back returns there.
    var cameFrom by rememberSaveable { mutableStateOf<Destination?>(null) }
    // Back from a tab returns where it came from, or to the map; never out of the app from
    // inside it. Declared first so anything open on a screen, which registers later, closes
    // before this runs.
    val backTo = PaneRules.back(destination.tab, cameFrom?.tab)
    androidx.activity.compose.BackHandler(enabled = backTo != null) {
        destination = backTo?.destination() ?: Destination.Map
        cameFrom = null
    }
    /** Shows the map from another tab, remembering the tab so back returns to it. */
    fun jumpToMap() {
        if (destination != Destination.Map) cameFrom = destination
        destination = Destination.Map
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showList by rememberSaveable { mutableStateOf(false) }
    // A city search across every country: to fly to a city, or to start a trip there.
    var cityHunt by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTrip by rememberSaveable { mutableStateOf<String?>(null) }
    var tripFromTab by rememberSaveable { mutableStateOf(false) }
    val allTrips by app.visits.allTrips.collectAsStateWithLifecycle()
    var focus by remember { mutableStateOf<Focus?>(null) }
    var me by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val locating = androidx.compose.runtime.rememberCoroutineScope()
    val camera = remember { CameraSpot() }
    val visits by app.visits.visits.collectAsStateWithLifecycle()
    val home by app.visits.home.collectAsStateWithLifecycle()
    val split by app.split.on.collectAsStateWithLifecycle()
    val mapPhotos by app.mapPhotos.on.collectAsStateWithLifecycle()
    val routes by app.routes.on.collectAsStateWithLifecycle()
    val follow by app.followLines.on.collectAsStateWithLifecycle()
    val lines by app.routePacks.net.collectAsStateWithLifecycle()
    val lastBackup by app.lastBackup.collectAsStateWithLifecycle()
    val here by app.here.on.collectAsStateWithLifecycle()
    // Once per launch: find the country the phone is in, mark it visited if it is not marked
    // yet (a home stays lived, a wish becomes a visit), and open the map over it.
    val context = androidx.compose.ui.platform.LocalContext.current
    var located by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(here) {
        if (!here || located) return@LaunchedEffect
        val (lat, lon) = com.marcusrosado.stamp.data.Here.find(context) ?: return@LaunchedEffect
        located = true
        val land = atlas.locate(lat, lon) ?: return@LaunchedEffect
        val country = atlas.byId[land.country] ?: land
        val markId = if (app.split.on.value) land.id else country.id
        val now = app.visits.visits.value[markId]?.state
        if (now == null || now == TravelState.Want) {
            app.visits.set(markId, TravelState.Visited)
        }
        focus = Focus(country.labelLat, country.labelLon, HERE_ZOOM)
    }
    val lookId by app.look.collectAsStateWithLifecycle()
    val scan by app.scanner.state.collectAsStateWithLifecycle()
    // A damaged city list only costs the city features, never the app.
    val places by produceState<Places?>(null) { value = runCatching { app.places.await() }.getOrNull() }
    val airports by produceState<com.marcusrosado.stamp.data.Airports?>(null) { value = runCatching { app.airports.await() }.getOrNull() }
    // Like the city list above it: a damaged file costs the outlines, never the app.
    val cityAreas by produceState(com.marcusrosado.stamp.data.CityAreas.EMPTY) {
        value = runCatching { app.cityAreas.await() }.getOrNull() ?: com.marcusrosado.stamp.data.CityAreas.EMPTY
    }

    // The card that holds a trip: its country's, or with islands counted as part of their
    // country, the island's own when the country's card does not hold it (a Honolulu trip with
    // the United States itself unmarked), so the trip can always open.
    fun cardFor(land: String, tripId: String): String {
        if (split) return land
        val country = atlas.countryOf(land)
        // Read from the store, not this frame's snapshot: a trip made a moment ago is already there.
        return if (app.visits.visits.value[country]?.trips?.any { it.id == tripId } == true) country else land
    }

    val openCountry: (String) -> Unit = { id ->
        atlas.byId[id]?.let { c ->
            selectedId = id
            jumpToMap()
            showList = false
            focus = focusOn(c)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val postureNow by rememberUpdatedState(postureFor(maxWidth.value.toInt(), maxHeight.value.toInt()))
        val state = AppState(
            posture = postureFor(maxWidth.value.toInt(), maxHeight.value.toInt()),
            destination = destination,
            atlas = atlas,
            places = places,
            cityAreas = cityAreas,
            visits = visits,
            home = home,
            selected = selectedId?.let { atlas.byId[it] },
            scan = scan,
            hasScanned = app.flags.get(FLAG_SCANNED),
            camera = camera,
            focus = focus,
            onDestination = { destination = it; cameFrom = null },
            onRestoreSelection = { selectedId = it },
            onSelect = { id ->
                selectedId = id
                // Picking a country by hand drops a trip still waiting to open somewhere else.
                pendingTrip = null
                // A picked country moves into the map left showing beside or above its card.
                id?.let { atlas.byId[it] }?.let { focus = focusOn(it) }
            },
            // Islands counted as part of their country: clearing the country clears its islands too.
            onSet = { id, s ->
                if (s == null && !split) app.visits.clearAll(listOf(id) + atlas.partsOf(id).map { it.id })
                else app.visits.set(id, s)
            },
            details = remember(app) { DetailActions(
                onLoved = app.visits::setLoved,
                onRating = app.visits::setRating,
                onAddTrip = app.visits::addTrip,
                onUpdateTrip = app.visits::updateTrip,
                onRemoveTrip = app.visits::removeTrip,
                onAddCity = app.visits::addCity,
                onAddPin = app.visits::addPin,
                onRenamePin = app.visits::renamePin,
                onShowOnMap = { lat, lon ->
                    // The card would cover the city on a phone, so it closes there; beside the
                    // map on a big screen it covers nothing and stays.
                    if (postureNow == Posture.Portrait) selectedId = null
                    jumpToMap()
                    focus = Focus(lat, lon, CITY_ZOOM)
                },
                onStopDates = app.visits::setStopDates,
                onMoveStop = app.visits::moveStop,
                onLeg = { t, from, to, ways, via, around -> app.visits.setLeg(t, from, to, ways, via, around) },
                onTripStart = app.visits::setTripStart,
                onAddDayPlace = app.visits::addDayPlace,
                onAddDayPin = app.visits::addDayPin,
                onRemoveDayPlace = app.visits::removeDayPlace,
                onSlept = app.visits::setSlept,
                onTripEnd = app.visits::setTripEnd,
                onAddMemories = app.visits::addMemories,
                onUpdateMemory = app.visits::updateMemory,
                onRemoveMemory = app.visits::removeMemory,
                onRemoveCity = app.visits::removeCity,
                onCityNote = app.visits::setCityNote,
                onAddRegion = app.visits::addRegion,
                onRemoveRegion = app.visits::removeRegion,
            ) },
            onOpenList = { showList = true },
            onFindCity = { cityHunt = "find" },
            onNewTrip = { cityHunt = "trip" },
            pendingTrip = pendingTrip,
            trips = allTrips,
            onTripOpened = { pendingTrip = null },
            onTripClosed = {
                // Opened from the passport: the card it came through was never seen, so it goes.
                if (tripFromTab) selectedId = null
                tripFromTab = false
            },
            onOpenTrip = { id ->
                app.visits.allTrips.value.firstOrNull { it.id == id }?.home?.let { land ->
                    val show = cardFor(land, id)
                    // The trip opens over the tab it was chosen from, which stays put beneath,
                    // so nothing flashes on the way and closing it lands back there.
                    tripFromTab = destination != Destination.Map
                    selectedId = show
                    atlas.byId[show]?.let { focus = focusOn(it) }
                    pendingTrip = id
                }
            },
            onOpenScan = { onScanOpen(true) },
            onOpenNewScan = {
                if (scan !is ScanState.Running) app.scanner.cancel()
                onScanOpen(true)
            },
            onFocused = { focus = null },
            onOpenCountry = openCountry,
            onIntro = onIntro,
            look = lookId,
            onLook = app::setLook,
            split = split,
            onSplit = app.split::set,
            mapPhotos = mapPhotos,
            onMapPhotos = app.mapPhotos::set,
            routes = routes,
            onRoutes = app.routes::set,
            followLines = follow,
            onFollowLines = app.followLines::set,
            lastBackup = lastBackup,
            here = here,
            onHere = app.here::set,
            me = me,
            onLocate = {
                locating.launch {
                    val at = com.marcusrosado.stamp.data.Here.find(context, com.marcusrosado.stamp.data.Here.TWO_MINUTES)
                    if (at == null) {
                        android.widget.Toast.makeText(context, "Can't tell where you are right now", android.widget.Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    me = at
                    jumpToMap()
                    focus = Focus(at.first, at.second, HOME_ZOOM)
                }
            },
            onShowHome = { lat, lon ->
                if (postureNow == Posture.Portrait) selectedId = null
                jumpToMap()
                focus = Focus(lat, lon, HOME_ZOOM)
            },
            app = app,
        )
        // The packs of every country a leg by rail or road starts or ends in, the way there
        // from a home in another country included. Switched off, the kept packs go.
        LaunchedEffect(visits, places, airports, follow) {
            if (!follow) { app.routePacks.clear(); return@LaunchedEffect }
            val plain = Journey(visits, places, airports)
            val codes = visits.values.filter { it.state == TravelState.Visited }.flatMap { v -> v.trips.map { it to v } }.distinctBy { it.first.id }
                .flatMap { (t, v) ->
                    t.legEnds.filter { (a, b) -> t.legBetween(a, b)?.ways.orEmpty().any { com.marcusrosado.stamp.data.RouteNet.kindOf(it) != null } }
                        .flatMap { (a, b) -> listOfNotNull(plain.endPoint(t, v, a), plain.endPoint(t, v, b)) }
                }
                .mapNotNull { p -> atlas.locate(p.lat, p.lon)?.let { land -> land.alpha2.ifEmpty { null } ?: atlas.byId[land.country]?.alpha2?.ifEmpty { null } } }
                .toSet()
            if (codes.isNotEmpty()) app.routePacks.want(codes)
        }
        val journey = remember(visits, places, airports, lines, follow) { Journey(visits, places, airports, lines.takeIf { follow }) }
        // Every trip's lines, worked out once for every map and off the main thread: following
        // the rails can take a moment the first time. The last lines stay up meanwhile.
        val mapRoutes by produceState(emptyList<MapRoute>(), journey) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                // A trip that crosses borders shows on every country it touched; draw it once.
                journey.visits.values.filter { it.state.counts }
                    .flatMap { v -> v.trips.map { it to v } }
                    .distinctBy { (t, _) -> t.id }
                    .flatMap { (t, v) -> ensureActive(); journey.routes(t, v) }
                    .distinct()
            }
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalJourney provides journey, LocalMapRoutes provides mapRoutes) {
            when (state.posture) {
                Posture.Portrait -> PortraitLayout(state)
                // One layout for the big screen, flat or bent, so bending it never swaps the tree.
                Posture.Landscape, Posture.Unfolded -> RailLayout(state)
            }
        }

        cityHunt?.let { why ->
            CityPicker(
                countryName = "", flag = "", alpha2 = "",
                places = places,
                already = emptySet(),
                title = if (why == "trip") "Where did the trip start?" else "Find a city",
                onPick = { c ->
                    cityHunt = null
                    val land = atlas.landOf(c.lat, c.lon, c.country)
                    val show = land?.let { if (split) it.id else it.country }
                    destination = Destination.Map
                    if (why == "trip") {
                        val id = app.visits.newTripAt(c.id)
                        val home = id?.let { t -> app.visits.allTrips.value.firstOrNull { it.id == t }?.home }
                        if (id != null && home != null) {
                            selectedId = cardFor(home, id)
                            pendingTrip = id
                        }
                    } else if (show != null) {
                        selectedId = show
                    }
                    focus = Focus(c.lat, c.lon, CITY_ZOOM)
                },
                onPin = { cityHunt = null },
                onDismiss = { cityHunt = null },
                countryLabel = state.countryLabel,
            )
        }
        if (showList) {
            HalfPage {
                CountryListScreen(
                    atlas, visits,
                    onSet = state.onSet,
                    onShowOnMap = openCountry,
                    onClose = { showList = false },
                    split = split,
                )
            }
        }
        if (scanOpen) {
            HalfPage {
                ScanScreen(
                    state = scan,
                    visits = visits,
                    home = home,
                    onStartDevice = { app.flags.set(FLAG_SCANNED, true); app.scanner.start() },
                    onAdd = { found, mark ->
                        app.visits.addFromPhotos(found, mark)
                        app.scanner.cancel()
                        onScanOpen(false)
                        destination = Destination.Passport
                    },
                    onReset = { app.scanner.cancel() },
                    onClose = { onScanOpen(false) },
                )
            }
        }
    }
}

/** Centre on the country's label point, zoomed so most of it fits. */
private fun focusOn(c: Country): Focus {
    val span = max(c.maxLon - c.minLon, (c.maxLat - c.minLat) * 1.6).coerceAtLeast(0.5)
    val zoom = (ln(360.0 / span) / ln(2.0) - 0.3).coerceIn(1.5, 7.0)
    // A country across the date line has a box around the whole world: aim at its label instead.
    val box = if (c.maxLon - c.minLon < 180) listOf(c.minLat, c.minLon, c.maxLat, c.maxLon) else null
    return Focus(c.labelLat, c.labelLon, if (box != null) MAX_COUNTRY_ZOOM else zoom, box = box)
}

/** The closest the map goes for a country: Liechtenstein fills a good part, never the screen. */
const val MAX_COUNTRY_ZOOM = 6.0
