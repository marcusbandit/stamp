package com.marcusrosado.stamp.ui

import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.wrapContentSize
import com.marcusrosado.stamp.Posture
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.draggable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.TravelState

/** Outer screen, upright: bottom bar, and the country card slides over the bottom of the map. */
@Composable
fun PortraitLayout(state: AppState) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = d == state.destination,
                        onClick = { state.onDestination(d) },
                        icon = { Icon(d.icon, contentDescription = null) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            Pane(state, sidePanel = false, bottomInset = 0.dp)
        }
    }
}

/**
 * Every screen but the folded phone held upright: the map and its card, with the rail on the
 * right edge, flat or bent. The content starts at the window's left edge, so the seam's place
 * needs no shifting.
 */
@Composable
fun RailLayout(state: AppState) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { Pane(state, sidePanel = true, bottomInset = navBottom()) }
        Rail(state)
    }
}

@Composable
private fun navBottom() = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
private fun Pane(state: AppState, sidePanel: Boolean, bottomInset: Dp) {
    val padding = PaddingValues(bottom = bottomInset)
    val page = Modifier.statusBarsPadding()
    // Each tab keeps where it was, its open page and its scroll, while another one shows.
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // The map is built once and stays under every tab, so coming back to it shows it at once
    // instead of loading it again.
    Box(Modifier.fillMaxSize()) {
        MapPane(state, sidePanel, bottomInset)
        when (PaneRules.cover(state.destination.tab)) {
            PaneRules.Cover.None -> Unit
            // Bent, the passport takes one half and the map stays on the other.
            PaneRules.Cover.Passport -> HalfPage {
                saved.SaveableStateProvider("passport") {
                    Passport(state, padding, page)
                }
            }
            PaneRules.Cover.Settings -> Surface(Modifier.fillMaxSize()) {
                saved.SaveableStateProvider("settings") {
                    SettingsScreen(state, contentPadding = padding, modifier = page)
                }
            }
        }
    }
}

@Composable
private fun MapPane(state: AppState, sidePanel: Boolean, bottomInset: Dp) {
    val selected = state.selected
    val hinge = LocalHinge.current
    BackHandler(enabled = selected != null && state.destination == Destination.Map) { state.onSelect(null) }
    // One tree for every posture: the map, and a pane beside or below it. Only their sizes
    // change, so bending the phone resizes the map instead of tearing it down and building a
    // new one, which is what made the screen flash.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        // Where the pane starts along the axis it shares with the map, and how wide the seam is.
        val vertical = hinge?.vertical ?: true
        val side = PaneRules.side(bent = hinge != null, wideFlat = sidePanel, selected = selected != null)
        val (mapExtent, gap) = when {
            hinge != null && hinge.vertical -> hinge.start.coerceIn(0.dp, w) to (hinge.end - hinge.start)
            hinge != null -> hinge.start.coerceIn(0.dp, h) to (hinge.end - hinge.start)
            side == PaneRules.Side.Card -> (w - 360.dp) to 1.dp
            else -> w to 0.dp
        }
        val spring = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.9f, stiffness = 320f)
        val extent by androidx.compose.animation.core.animateDpAsState(mapExtent, spring, label = "map")
        val full = if (vertical) w else h
        val mapW = if (vertical) extent else w
        val mapH = if (vertical) h else extent
        val showPane = side != PaneRules.Side.None

        // The card opens small so most of the map stays in view, and grows on a drag or a tap
        // of its handle when there is more to read.
        // The phone's card follows the finger at any height, from the whole screen down to its
        // row of mark buttons; let go below that and it slides away. A new country opens it at
        // a bit under half, and the map keeps that much clear below the country.
        val opened = h * 0.45f
        // At its tallest the card stops below the status bar, so its handle never sits where a
        // pull opens the notification shade.
        val tallest = h - WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // The card's height lives on while it closes: keyed on the chosen country it would jump
        // back to the opening height the moment the choice clears, and flash there on its way
        // out. A newly chosen country, or the same one again, starts at the opening height.
        var sheetH by remember { mutableStateOf(opened) }
        var restedAt by remember { mutableStateOf(opened) }
        var sheetFor by remember { mutableStateOf<String?>(null) }
        // One settle at a time: a release can reach the handle, the card and its content
        // together, and two settles racing pulled a closing card back up before it vanished.
        val settling = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
        var closing by remember { mutableStateOf(false) }
        if (selected != null && selected.id != sheetFor) {
            sheetFor = selected.id
            sheetH = opened
            restedAt = opened
            closing = false
        }
        if (selected == null) sheetFor = null
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val density = androidx.compose.ui.platform.LocalDensity.current
        // A finger on the card takes it over, even mid-close: the close is off and it follows.
        fun dragBy(dy: Dp) {
            settling[0]?.cancel()
            closing = false
            sheetH = (sheetH - dy).coerceIn(0.dp, tallest)
        }
        // A fling lands once per gesture: onPreFling may already have settled it.
        var flung by remember { mutableStateOf(false) }
        // Where the card rests: the opening height, or full height with a gap left at the top to
        // grab it by. Below the opening height it closes.
        val stops = listOf(0.dp, opened, tallest - SHEET_TOP_GAP)
        fun settle(velocity: Float = 0f) {
            if (closing) return
            val target = sheetStop(sheetH, with(density) { velocity.toDp() }, restedAt, stops)
            restedAt = target
            if (target == 0.dp) closing = true
            settling[0]?.cancel()
            settling[0] = scope.launch {
                androidx.compose.animation.core.animate(
                    sheetH.value, target.value, initialVelocity = -with(density) { velocity.toDp() }.value,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 420f),
                ) { v, _ -> sheetH = v.dp }
                if (target == 0.dp) state.onSelect(null)
            }
        }
        // The whole card drags, the way Android's own sheets do: pulled up it grows before its
        // content scrolls, and pulled down past the top of its content it shrinks.
        val sheetScroll = remember(tallest) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f || sheetH >= tallest) return Offset.Zero
                    val before = sheetH
                    dragBy(with(density) { available.y.toDp() })
                    return Offset(0f, -with(density) { (sheetH - before).toPx() })
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                    dragBy(with(density) { available.y.toDp() })
                    return available
                }
                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (flung) flung = false else settle(available.y)
                    return available
                }
                override suspend fun onPreFling(available: Velocity): Velocity {
                    // A flick up while the card can still grow moves the card, not its content.
                    if (available.y < 0f && sheetH < tallest - SHEET_TOP_GAP - 1.dp) {
                        flung = true
                        settle(available.y)
                        return available
                    }
                    return Velocity.Zero
                }
            }
        }
        val cardOnMap = !sidePanel && hinge == null && selected != null
        // The room the map keeps clear for the card. It stays after the card closes, so closing
        // never shifts the map; the next country picked brings its own.
        var cardCover by remember { mutableStateOf(0.dp) }
        if (cardOnMap) cardCover = opened
        // The map itself never changes size; the pane slides over it and the map moves its
        // centre into what is left. The buttons over it follow the part that shows.
        StampMap(
            state, Modifier.fillMaxSize(),
            coveredEnd = if (vertical) w - mapExtent else 0.dp,
            coveredBottom = if (!vertical) h - mapExtent else cardCover,
            // Settings covers the whole map, and so does the passport unless the phone is bent.
            animate = when (PaneRules.cover(state.destination.tab)) {
                PaneRules.Cover.None -> true
                PaneRules.Cover.Passport -> hinge != null
                PaneRules.Cover.Settings -> false
            },
        )
        Box(Modifier.size(mapW, mapH)) {
            // The legend, centred over the map's part of the screen, however wide it is.
            Legend(state, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
            // On the thumb's side: left on the big screen, flat or bent, and right on the folded phone.
            val left = state.posture == Posture.Unfolded
            if (hinge != null || sidePanel || selected == null) {
                var menuOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                // What the map shows, along the bottom on the side away from the main button,
                // with a region or a found city floating above it.
                Column(
                    Modifier.align(if (left) Alignment.BottomEnd else Alignment.BottomStart)
                        .padding(start = if (left) 0.dp else 12.dp, end = if (left) 12.dp else 0.dp, bottom = bottomInset + 8.dp)
                        .widthIn(max = mapW - 96.dp),
                    horizontalAlignment = if (left) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.spot?.let { SpotFor(state, it) }
                    ViewBar(state.mapView, state.onMapView, state.routes, state.onRoutes)
                }
                ActionMenu(
                    state,
                    open = menuOpen,
                    onOpen = { menuOpen = it },
                    Modifier.align(if (left) Alignment.BottomStart else Alignment.BottomEnd)
                        .padding(start = if (left) 4.dp else 0.dp, end = if (left) 0.dp else 4.dp, bottom = bottomInset),
                    left = left,
                )
                // Where am I, like any map app: above the main button, on the same side. It steps
                // aside while the menu is open, whose items rise through the same place.
                androidx.compose.animation.AnimatedVisibility(
                    visible = !menuOpen,
                    modifier = Modifier.align(if (left) Alignment.BottomStart else Alignment.BottomEnd)
                        .padding(start = if (left) 20.dp else 0.dp, end = if (left) 0.dp else 20.dp, bottom = bottomInset + 96.dp),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
                ) { LocateButton(state, Modifier) }
            }
            // The card slides up from the bottom and back down, rather than appearing at once. It
            // keeps showing the last country while it leaves.
            var lastSelected by remember { mutableStateOf(selected) }
            if (selected != null) lastSelected = selected
            androidx.compose.animation.AnimatedVisibility(
                visible = !sidePanel && hinge == null && selected != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically(
                    androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f),
                ) { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(220)) { it } +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)),
            ) {
                val shown = lastSelected ?: return@AnimatedVisibility
                Surface(
                    modifier = Modifier.fillMaxWidth().height(sheetH)
                        .nestedScroll(sheetScroll)
                        .draggable(
                            orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                            state = androidx.compose.foundation.gestures.rememberDraggableState { px -> dragBy(with(density) { px.toDp() }) },
                            onDragStopped = { v -> settle(v) },
                        ),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                ) {
                    Column {
                        SheetHandle(
                            expanded = sheetH > h * 0.7f,
                            // A tap jumps between the opening height and the tallest.
                            onToggle = {
                                settling[0]?.cancel()
                                settling[0] = scope.launch {
                                    androidx.compose.animation.core.animate(
                                        sheetH.value, (if (sheetH > h * 0.7f) opened else tallest - SHEET_TOP_GAP).value,
                                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 380f),
                                    ) { v, _ -> sheetH = v.dp }
                                    restedAt = sheetH
                                }
                            },
                            onDragBy = ::dragBy,
                            onRelease = { v -> settle(v) },
                        )
                        Card(state, shown, Modifier)
                    }
                }
            }
        }

        // The pane: the card beside the map on a big screen, or the other half of a bent one.
        var lastPanel by remember { mutableStateOf(selected) }
        if (selected != null) lastPanel = selected
        // Once the card has slid away, forget it: the hidden pane then already holds the
        // passport, so bending the screen with nothing chosen builds nothing new.
        if (selected == null && extent >= full - 0.5.dp) lastPanel = null
        val paneStart = extent + gap
        val paneSize = (full - paneStart).coerceAtLeast(0.dp)
        // The pane's contents are laid out at the size they end at and revealed as the pane
        // slides open, so text never squeezes mid-slide. On the big screen they stay built while
        // hidden too, since building the passport the moment the screen bends shows as a black
        // half for a few frames.
        val target = (full - mapExtent - gap).coerceAtLeast(0.dp)
        var lastTarget by remember { mutableStateOf(full / 2) }
        if (target > 0.dp) lastTarget = target
        if (showPane || paneSize > 0.dp || state.posture == Posture.Unfolded) {
            Surface(
                Modifier
                    .offset(x = if (vertical) paneStart else 0.dp, y = if (vertical) 0.dp else paneStart)
                    .size(if (vertical) paneSize else w, if (vertical) h else paneSize)
                    .clipToBounds(),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
              Box(
                  Modifier
                      .wrapContentSize(Alignment.TopStart, unbounded = true)
                      .size(if (vertical) lastTarget else w, if (vertical) h else lastTarget),
              ) {
                // Clear of the status bar on top and the taskbar or gesture bar below.
                val top = (if (vertical) Modifier.statusBarsPadding() else Modifier)
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                val shown = selected ?: lastPanel.takeIf { hinge == null }
                if (shown != null) {
                    Card(state, shown, top)
                } else {
                    Passport(state, PaddingValues(bottom = bottomInset), top)
                }
              }
            }
        }
    }
}

/**
 * The map beside a trip's timeline: the same map as the main one, with its own camera, gliding
 * to whichever stop the timeline has reached.
 */
@Composable
private fun TripMap(state: AppState, modifier: Modifier, at: Pair<Double, Double>?) {
    val camera = remember { CameraSpot(at?.first ?: 30.0, at?.second ?: 10.0, if (at != null) 6.0 else 0.6) }
    var focus by remember { mutableStateOf<Focus?>(null) }
    LaunchedEffect(at) { at?.let { focus = Focus(it.first, it.second, 5.0) } }
    StampMap(state, modifier, camera = camera, focus = focus, onFocused = { focus = null }, onTap = {}, selectable = false)
}

/** The passport, with everything it needs from the app's state. */
@Composable
private fun Passport(state: AppState, padding: PaddingValues, modifier: Modifier) = PassportScreen(
    state.atlas, state.visits, state.home, state.scan, state.hasScanned,
    onScan = state.onOpenScan, onOpenCountry = state.onOpenCountry, contentPadding = padding,
    modifier = modifier, split = state.split,
    trips = state.trips, places = state.places, onOpenTrip = state.onOpenTrip,
)

/** A country's card, with everything it needs from the app's state. */
@Composable
private fun Card(state: AppState, shown: com.marcusrosado.stamp.data.Country, modifier: Modifier) {
    CountryDetail(
        shown, state.visits[shown.id], isHome = state.home == shown.id,
        onSet = { state.onSet(shown.id, it) },
        actions = state.details,
        places = state.places,
        onClose = { state.onSelect(null) },
        modifier = modifier,
        partOf = shown.takeIf { it.isPart }?.let { state.atlas.byId[it.country]?.name },
        markedParts = if (state.split || shown.isPart) emptyList() else state.atlas.partsOf(shown.id)
            .mapNotNull { p -> state.visits[p.id]?.let { p.id to "${p.name} · ${it.state.short}" } },
        partVisits = if (state.split || shown.isPart) emptyList() else state.atlas.partsOf(shown.id).mapNotNull { state.visits[it.id] },
        onOpenPart = { state.onSelect(it) },
        countryLabel = state.countryLabel,
        landName = { state.atlas.byId[it]?.name ?: it },
        openTrip = state.pendingTrip,
        onTripOpened = state.onTripOpened,
        onTripClosed = state.onTripClosed,
        tripMap = { m, at -> TripMap(state, m, at) },
    )
}

/** The world map with everything the user marked on it: countries, cities, routes, areas and photos. */
@Composable
private fun StampMap(
    state: AppState,
    modifier: Modifier,
    coveredEnd: Dp = 0.dp,
    coveredBottom: Dp = 0.dp,
    camera: CameraSpot = state.camera,
    focus: Focus? = state.focus,
    onFocused: () -> Unit = state.onFocused,
    onTap: (String?) -> Unit = state.onSelect,
    onRestore: (String?) -> Unit = state.onRestoreSelection,
    selectable: Boolean = true,
    animate: Boolean = true,
) {
    val selected = state.selected
    // A tapped line opens the picker for the leg it draws, right over the map: no country
    // picked, no trip opened, and closing it leaves the map as it was. Where several legs lie
    // under the finger, such as the way out and the way back over the same rails, or a line
    // several trips share, it asks which first.
    var choosing by remember { mutableStateOf<List<RouteTap>?>(null) }
    var editing by remember { mutableStateOf<RouteTap?>(null) }
    val journey = LocalJourney.current
    fun visitOf(tripId: String) = journey.visits.values.firstOrNull { v -> v.trips.any { it.id == tripId } }
    fun open(entry: RouteTap) {
        if (entry.leg == null) state.onOpenTrip(entry.trip) else editing = entry
    }
    choosing?.let { entries ->
        val oneTrip = entries.map { it.trip }.distinct().size == 1
        SeamAlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(if (oneTrip) "Which leg?" else "Which trip?") },
            text = {
                Column {
                    entries.forEach { e ->
                        val visit = visitOf(e.trip)
                        val t = visit?.trips?.firstOrNull { it.id == e.trip } ?: return@forEach
                        val where = t.lands.mapNotNull { state.atlas.byId[it]?.name }.distinct().joinToString("  →  ")
                        // A leg reads as its two ends, the way the trip page names them.
                        fun end(id: String) = journey.endPoint(t, visit, id)?.name?.substringBefore(',')?.trim()
                            ?: if (id == com.marcusrosado.stamp.data.Leg.START || id == com.marcusrosado.stamp.data.Leg.END) "Home" else "A stop"
                        val legName = e.leg?.let { (a, b) -> "${end(a)}  →  ${end(b)}" }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { choosing = null; open(e) }.padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Column {
                                Text(
                                    if (oneTrip) legName ?: where.ifEmpty { "A trip" } else where.ifEmpty { "A trip" },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                val second = if (oneTrip) rangeLabel(t.date, t.until) else listOfNotNull(legName, rangeLabel(t.date, t.until)).joinToString(" · ").ifEmpty { null }
                                second?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { choosing = null }) { Text("Cancel") } },
        )
    }
    editing?.let { e ->
        val (from, to) = e.leg ?: return@let
        val visit = visitOf(e.trip)
        val trip = visit?.trips?.firstOrNull { it.id == e.trip }
        if (trip == null) { editing = null; return@let }
        LegEditor(
            trip, visit, from, to,
            onDone = { steps, around -> state.details.onLeg(e.trip, from, to, steps, around); editing = null },
            onDismiss = { editing = null },
        )
    }
    // The regions drawn, their marks, and what is done in full; the shapes are read off the main
    // thread, a country's file at a time, and only for the countries drawn as regions.
    val regionIds = remember(state.mapView, state.atlas, state.visits) { regionCountries(state.mapView, state.atlas, state.visits) }
    val shapes by androidx.compose.runtime.produceState(emptyList<Pair<String, com.marcusrosado.stamp.data.RegionShape>>(), regionIds, state.regionShapes) {
        val rs = state.regionShapes ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            regionIds.flatMap { id -> val a2 = state.atlas.byId[id]?.alpha2.orEmpty(); if (a2.length == 2) rs.of(a2).map { id to it } else emptyList() }
        }
    }
    val marks = remember(regionIds, state.visits, state.places) { regionMarks(regionIds, state.atlas, state.visits, state.places) }
    val complete = remember(state.mapView, shapes, marks, state.visits) { completeCountries(state.mapView, state.atlas, state.visits, shapes, marks) }
    val wantCities = remember(state.visits, state.places) {
        state.visits.values.flatMap { v -> v.wantCities.mapNotNull { placeOf(it, v, state.places) } }.map { it.lat to it.lon }.distinct()
    }
    WorldMap(
        animate = animate,
        regionLayer = if (regionIds.isEmpty()) null else RegionLayer(
            shapes, marks,
            remember(regionIds, state.visits) { regionIds.mapNotNull { id -> state.atlas.markOf(id, state.visits, false)?.let { id to it } }.toMap() },
        ),
        complete = complete,
        plain = state.mapView == com.marcusrosado.stamp.data.MapView.Cities,
        wantCities = wantCities,
        onRegion = if (selectable) { cid, rid -> state.onSpot(Spot.Region(cid, rid)) } else null,
        onRoute = if (selectable) { entries ->
            // Within one trip a leg wins over the trip as a whole, such as a layover's dot or a
            // day trip beside it. One leg left: straight to it. Several: ask which.
            val picks = entries.groupBy { it.trip }.values.flatMap { mine -> mine.filter { it.leg != null }.ifEmpty { mine.take(1) } }
            if (picks.size == 1) open(picks.single()) else if (picks.isNotEmpty()) choosing = picks
        } else null,
        atlas = state.atlas,
        visits = state.visits,
        showRoutes = state.routes,
        split = state.split,
        routes = LocalMapRoutes.current,
        photos = remember(state.visits, state.places, state.mapPhotos) {
            if (!state.mapPhotos) return@remember emptyList()
            // The first photo kept at each place; places only, not the visit as a whole.
            state.visits.values.filter { it.state.counts }.flatMap { v ->
                v.trips.flatMap { t -> t.memories.filter { it.media != null && it.place != null } }
                    .groupBy { it.place!! }
                    .mapNotNull { (place, ms) ->
                        val at = placeOf(place, v, state.places) ?: return@mapNotNull null
                        val m = ms.first()
                        MapPhoto(at.lat, at.lon, m.media!!, m.video)
                    }
            }.distinct()
        },
        areas = remember(state.visits, state.cityAreas) {
            state.visits.values.filter { it.state.counts }.flatMap { it.cities }.distinct()
                .mapNotNull { state.cityAreas.of(it) }
        },
        cities = remember(state.visits, state.places) {
            // Only places the user has been: a Want to go country keeps its old visits but shows none.
            state.visits.values.filter { it.state.counts }.flatMap { v ->
                v.cities.mapNotNull { placeOf(it, v, state.places) }
            }.map { it.lat to it.lon }.distinct()
        },
        // The homes Where you live lists: every place on the stay going on now, address or town.
        homes = remember(state.visits, state.places) {
            state.visits.values.flatMap { v ->
                v.trips.filter { it.now }.flatMap { it.cities }.mapNotNull { placeOf(it, v, state.places) }
            }.map { it.lat to it.lon }.distinct()
        },
        me = state.me,
        selectedId = selected?.id,
        camera = camera,
        focus = focus,
        onFocused = onFocused,
        onTap = onTap,
        onRestore = onRestore,
        selectable = selectable,
        modifier = modifier,
        coveredEnd = coveredEnd,
        coveredBottom = coveredBottom,
    )
}

/**
 * The map's one button, on the thumb's side: it opens into finding a country, finding a city,
 * and starting a trip, the Material 3 Expressive way.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionMenu(state: AppState, open: Boolean, onOpen: (Boolean) -> Unit, modifier: Modifier, left: Boolean = false) {
    BackHandler(enabled = open && state.destination == Destination.Map) { onOpen(false) }
    androidx.compose.material3.FloatingActionButtonMenu(
        expanded = open,
        modifier = modifier,
        horizontalAlignment = if (left) Alignment.Start else Alignment.End,
        button = {
            // Open, the button turns to the theme's inverse colour. The default primary can be the
            // very colour the map paints its land in, and then the button vanishes into it.
            val cs = MaterialTheme.colorScheme
            androidx.compose.material3.ToggleFloatingActionButton(
                checked = open,
                onCheckedChange = onOpen,
                containerColor = { p -> androidx.compose.ui.graphics.lerp(cs.primaryContainer, cs.inverseSurface, p) },
            ) {
                Icon(
                    if (open) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (open) "Close" else "Find or add",
                    tint = androidx.compose.ui.graphics.lerp(cs.onPrimaryContainer, cs.inverseOnSurface, checkedProgress),
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { onOpen(false); state.onNewTrip() },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New trip") },
        )
        FloatingActionButtonMenuItem(
            onClick = { onOpen(false); state.onFindCity() },
            icon = { Icon(Icons.Outlined.LocationCity, contentDescription = null) },
            text = { Text("Cities") },
        )
        FloatingActionButtonMenuItem(
            onClick = { onOpen(false); state.onOpenList() },
            icon = { Icon(Icons.Outlined.Public, contentDescription = null) },
            text = { Text("Countries") },
        )
    }
}

/**
 * Flies the map to where the phone is and marks it. Asks for the rough location first when the
 * phone has not allowed it, and once allowed goes straight there.
 */
@Composable
private fun LocateButton(state: AppState, modifier: Modifier) {
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) state.onLocate() }
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.SmallFloatingActionButton(
        onClick = { if (com.marcusrosado.stamp.data.Here.granted(context)) state.onLocate() else ask.launch(com.marcusrosado.stamp.data.Here.PERMISSION) },
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.primary,
    ) { Icon(androidx.compose.material.icons.Icons.Filled.MyLocation, contentDescription = "Where I am") }
}

/** The floating panel for a region or a found city, with the marks it can take. */
@Composable
private fun SpotFor(state: AppState, spot: Spot) {
    val country = state.atlas.byId[spot.countryId]
    val vs = visitsOf(spot.countryId, state.atlas, state.visits)
    when (spot) {
        is Spot.Region -> {
            val marks = regionMarks(listOf(spot.countryId), state.atlas, state.visits, state.places)
            SpotPanel(
                title = state.places?.regionName(spot.regionId) ?: spot.regionId,
                subtitle = country?.name.orEmpty(),
                state = marks[spot.regionId]?.let { if (it == TravelState.Lived) TravelState.Visited else it },
                choices = listOf(TravelState.Visited, TravelState.Want),
                onState = { s -> state.onRegion(spot.countryId, spot.regionId, s) },
                onClose = { state.onSpot(null) },
            )
        }
        is Spot.City -> {
            val been = vs.any { v -> v.state.counts && spot.cityId in v.cities }
            SpotPanel(
                title = spot.name,
                subtitle = listOfNotNull(state.places?.byId?.get(spot.cityId)?.let { state.places.regionOf(it) }, country?.name).joinToString(", "),
                state = when { been -> TravelState.Visited; vs.any { spot.cityId in it.wantCities } -> TravelState.Want; else -> null },
                // A city already been to has nothing left to want.
                choices = if (been) listOf(TravelState.Visited) else listOf(TravelState.Want),
                onState = { s -> if (!been) state.onCityWant(spot.countryId, spot.cityId, s == TravelState.Want) },
                onClose = { state.onSpot(null) },
            )
        }
    }
}

/** Counts per mark, floating over the top of the map. */
@Composable
private fun Legend(state: AppState, modifier: Modifier) {
    // One per marked place, the way the passport counts: with islands part of their country,
    // the United States and Hawaii marked together are one.
    val counts = remember(state.visits, state.split, state.atlas) {
        val marks = if (state.split) state.visits.values.map { it.state }
        else state.atlas.mains.mapNotNull { state.atlas.markOf(it.id, state.visits, false) }
        marks.groupingBy { it }.eachCount()
    }
    val palette = LocalMapPalette.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TravelState.entries.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(palette.of(s))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${counts[s] ?: 0} ${s.label.lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun Rail(state: AppState) {
    // Bent like a laptop, the rail's middle would sit on the seam: its buttons move to the half
    // lying flat, below it.
    val hinge = LocalHinge.current
    val tabletop = hinge?.vertical == false
    NavigationRail(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical)),
    ) {
        if (tabletop && hinge != null) {
            val top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
            Spacer(Modifier.height((hinge.end - top + 16.dp).coerceAtLeast(0.dp)))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Destination.entries.forEach { d ->
            NavigationRailItem(
                selected = d == state.destination,
                onClick = { state.onDestination(d) },
                icon = { Icon(d.icon, contentDescription = null) },
                label = { Text(d.label) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * The grip at the top of the country card. A tap or an upward drag grows the card; a downward
 * drag shrinks it, and from its small size closes it.
 */
@Composable
private fun SheetHandle(expanded: Boolean, onToggle: () -> Unit, onDragBy: (dy: Dp) -> Unit, onRelease: (velocity: Float) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClickLabel = if (expanded) "Show more map" else "Show more of the card", onClick = onToggle)
            .draggable(
                orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                state = androidx.compose.foundation.gestures.rememberDraggableState { px -> onDragBy(with(density) { px.toDp() }) },
                onDragStopped = { v -> onRelease(v) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
        )
    }
}


/** The room left above the card at full height, to grab it and see where the map went. */
private val SHEET_TOP_GAP = 24.dp

/**
 * Where a let-go card comes to rest, from its height, its speed (positive is downward), the
 * height it rested at before this drag, and the heights it rests at, lowest first: closed,
 * then the open heights. A flick down closes it from any height; a flick up goes one stop up.
 * A slow release goes on to the next stop once the card is a quarter of the way there, and
 * back otherwise.
 */
internal fun sheetStop(
    height: Dp,
    velocity: Dp,
    restedAt: Dp,
    stops: List<Dp>,
): Dp {
    val flick = kotlin.math.abs(velocity.value) > SHEET_FLICK.value
    if (flick && velocity > 0.dp) return stops.first()
    val above = stops.firstOrNull { it >= height } ?: stops.last()
    val below = stops.lastOrNull { it <= height } ?: stops.first()
    if (above == below) return above
    if (flick) return above
    val gap = above - below
    return if (height < restedAt) {
        if (above - height >= gap * 0.25f) below else above
    } else {
        if (height - below >= gap * 0.25f) above else below
    }
}

/** How fast, in dp a second, a release counts as a flick. */
private val SHEET_FLICK = 600.dp

/**
 * Two panes side by side, or one above the other on a screen bent like a laptop. Flat, the
 * first is [flatFirst] wide with a hairline after it; bent, they meet the seam from either
 * side. The tree never changes with the posture, so bending the screen moves the line between
 * them and nothing is rebuilt. A pane arriving fresh, say on a tab switch, starts where it
 * belongs: only a change while it is showing animates.
 */
@Composable
fun Split(
    hinge: Hinge?,
    flatFirst: Dp,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val vertical = hinge?.vertical ?: true
        val full = if (vertical) maxWidth else maxHeight
        val (extent, gap) = if (hinge != null) {
            hinge.start.coerceIn(0.dp, full) to (hinge.end - hinge.start)
        } else {
            // A hairline after the first pane, and none when it is shut.
            flatFirst.coerceAtMost(full) to (if (flatFirst > 0.dp) 1.dp else 0.dp)
        }
        val spring = androidx.compose.animation.core.spring<Dp>(dampingRatio = 0.9f, stiffness = 320f)
        val e by androidx.compose.animation.core.animateDpAsState(extent, spring, label = "split")
        val g by androidx.compose.animation.core.animateDpAsState(gap, spring, label = "seam")
        val rest = (full - e - g).coerceAtLeast(0.dp)
        Box(if (vertical) Modifier.size(e, maxHeight) else Modifier.size(maxWidth, e)) { first() }
        if (hinge == null) {
            Box(
                Modifier.offset(x = e).size(g, maxHeight)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Box(
            Modifier
                .offset(x = if (vertical) e + g else 0.dp, y = if (vertical) 0.dp else e + g)
                .size(if (vertical) rest else maxWidth, if (vertical) maxHeight else rest)
                .clipToBounds(),
        ) { second() }
    }
}
