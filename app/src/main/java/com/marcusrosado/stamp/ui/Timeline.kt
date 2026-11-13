package com.marcusrosado.stamp.ui

import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Map
import androidx.compose.foundation.layout.BoxWithConstraints
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SubdirectoryArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hotel
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.marcusrosado.stamp.data.placeIdOf
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.Memory
import com.marcusrosado.stamp.data.Note
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.scan.Memories
import com.marcusrosado.stamp.scan.Suggestion
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** What the timeline can change. Every call names the visit it is about. */
class TimelineActions(
    val onDates: (Trip) -> Unit,
    val onStopDates: (place: String, arrived: PartialDate?, left: PartialDate?) -> Unit,
    val onMove: (place: String, by: Int) -> Unit,
    /** Sets how the user went from one stop to the next, and a flight's airports; no ways clears it. */
    val onLeg: (from: String, to: String, ways: List<com.marcusrosado.stamp.data.Travel>, via: List<String>) -> Unit,
    /** Sets where the trip set out from, a city or pin id; null is home. */
    val onStart: (place: String?) -> Unit = {},
    /** Sets where the trip went back to; null is home. */
    val onEnd: (place: String?) -> Unit = {},
    /** Opens the search for a place the user went to while staying at a stop. */
    val onAddDayPlace: (stop: String) -> Unit = {},
    val onRemoveDayPlace: (stop: String, place: String) -> Unit = { _, _ -> },
    /** Says where the user slept at a stop: its own place or one of its day places. */
    val onSlept: (stop: String, place: String) -> Unit = { _, _ -> },
    val onAddStop: () -> Unit,
    val onOpenPlace: (place: String) -> Unit,
    val onAddMemories: (List<Memory>) -> Unit,
    val onUpdateMemory: (Memory) -> Unit,
    val onRemoveMemory: (String) -> Unit,
)

/**
 * One visit as a journey: where it started, each stop in order with its dates and what the
 * user kept from it, and the way home. Opened from a visit's card.
 */
@Composable
fun VisitTimeline(
    country: Country,
    visit: Visit,
    trip: Trip,
    number: Int,
    lived: Boolean,
    places: Places?,
    actions: TimelineActions,
    onDismiss: () -> Unit,
    /** A land mass's name, such as "Austria" or "Ryukyu Islands". */
    landName: (String) -> String = { it },
    /** Draws a map centred on a point; with it, the timeline can show the map beside it. */
    map: (@Composable (Modifier, Pair<Double, Double>?) -> Unit)? = null,
) {
    // On a phone the map leads, over the top two fifths; on the big screen it opens on request.
    val phone = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp < 600
    var showMap by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(phone) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // The stop the reader has reached: the last node above a line a third of the way down.
    val reached by remember(trip) {
        androidx.compose.runtime.derivedStateOf {
            val info = list.layoutInfo
            val line = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
            info.visibleItemsInfo.filter { isNode(it.key) }.lastOrNull { it.offset <= line }?.key as? String
        }
    }
    val order = listOf("start") + trip.cities.map { "stop-$it" } + "end"
    // Every item of the list in order, so a dot on the track scrolls to its stop by key.
    val itemKeys = buildList {
        add("hero"); add("start")
        val journeys = !lived && trip.cities.isNotEmpty()
        if (journeys) add("leg-start-${trip.cities.first()}")
        trip.cities.forEachIndexed { i, p ->
            add("stop-$p")
            trip.cities.getOrNull(i + 1)?.let { add("leg-$p-$it") }
        }
        add("add-stop")
        if (journeys) add("leg-${trip.cities.last()}-end")
        add("end")
    }
    val reachedAt = reached?.let { order.indexOf(it) } ?: -1
    // The stop reached; past the last one the map stays on it rather than flying back to the first.
    val at = (reached?.takeIf { it.startsWith("stop-") }?.removePrefix("stop-")
        ?: trip.cities.lastOrNull()?.takeIf { reached == "end" } ?: trip.cities.firstOrNull())
        ?.let { placeOf(it, visit, places) }?.let { it.lat to it.lon }
    var editingDates by remember { mutableStateOf(false) }
    var stopDates by remember { mutableStateOf<String?>(null) }
    // The leg being told, as the stops it runs between.
    var leg by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Where the trip set out from or went back to, being picked: true for the start.
    var pickingEnd by remember { mutableStateOf<Boolean?>(null) }
    val journey = LocalJourney.current
    // A visit is a journey from somewhere and back; a stay is where the user lived.
    val journeyed = !lived
    val startPlace = remember(trip, journey) { if (journeyed) journey.startOf(trip) else null }
    val endPlace = remember(trip, journey) { if (journeyed) journey.endOf(trip) else null }
    fun nameOf(id: String) = when (id) {
        com.marcusrosado.stamp.data.Leg.START -> startPlace?.name ?: "home"
        com.marcusrosado.stamp.data.Leg.END -> endPlace?.name ?: "home"
        else -> placeOf(id, visit, places)?.name ?: journey.place(id)?.name ?: "the next stop"
    }
    @Composable
    fun legRow(from: String, to: String, litAt: Int) {
        val l = trip.legBetween(from, to)
        LegLine(
            ways = l?.ways.orEmpty(),
            via = l?.via.orEmpty(),
            question = if (to == com.marcusrosado.stamp.data.Leg.END && trip.end == null) "How did you get home?"
                else "How did you get to ${nameOf(to)}?",
            where = if (to == com.marcusrosado.stamp.data.Leg.END && trip.end == null) "home" else "to ${nameOf(to)}",
            lit = reachedAt >= litAt,
            onClick = { leg = from to to },
        )
    }
    // The stop a memory is being added to; "" is the visit as a whole.
    var adding by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Memory?>(null) }
    val noun = if (lived) "Stay" else "Visit"
    val hero = trip.memories.firstOrNull { it.media != null && !it.video }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
          BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            val hinge = LocalHinge.current
            // The map, when shown, sits beside the timeline on a big screen, at the seam when it
            // is bent, and above it on a phone. The timeline keeps its scroll either way.
            val timeline: @Composable (Modifier) -> Unit = { m ->
              Box(m) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = list,
                    contentPadding = PaddingValues(bottom = 48.dp),
                ) {
                item(key = "hero") {
                    Hero(
                        country = country,
                        // A trip across borders names every place it went: "Germany → Austria → Italy".
                        where = trip.lands.map(landName).distinct().takeIf { it.size > 1 }?.joinToString("  →  ") ?: country.name,
                        title = "$noun $number",
                        dates = rangeLabel(trip.date, trip.until, trip.now),
                        photo = hero,
                        onBack = onDismiss,
                        onDates = { editingDates = true },
                        // Bent, the map is always beside the timeline, so there is nothing to toggle.
                        mapShown = if (map != null && LocalHinge.current == null) showMap else null,
                        // With the map shown it is the picture: the header shrinks to a title row.
                        compact = map != null && (showMap || LocalHinge.current != null),
                        onMap = { showMap = !showMap },
                    )
                }
                val loose = trip.memories.filter { it.place == null || it.place !in trip.cities }
                item(key = "start") {
                    Node(
                        icon = { Icon(Icons.Outlined.Flight, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        first = true,
                        last = false,
                        lit = reachedAt >= 0,
                    ) {
                        if (journeyed) {
                            PlaceLine(
                                label = "From ${startPlace?.name ?: "home"}",
                                hint = if (trip.start == null) "Home" else null,
                                onClick = { pickingEnd = true },
                            )
                        }
                        DateLine(
                            label = trip.date?.let { "Arrived in ${country.name} · ${it.label}" }
                                ?: "When did you get there?",
                            muted = trip.date == null,
                            onClick = { editingDates = true },
                        )
                        MemoryRow(loose, onOpen = { viewing = it }, onAdd = { adding = "" })
                        if (loose.isEmpty()) QuietActions(memory = { adding = "" }, dayTrip = null)
                    }
                }
                if (journeyed && trip.cities.isNotEmpty()) {
                    val first = trip.cities.first()
                    item(key = "leg-start-$first") { legRow(com.marcusrosado.stamp.data.Leg.START, first, litAt = 1) }
                }
                trip.cities.forEachIndexed { i, place ->
                    item(key = "stop-$place") {
                        val info = placeOf(place, visit, places)
                        val (from, to) = trip.stopDates(place)
                        Node(
                            icon = { Text("${i + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) },
                            first = false,
                            last = false,
                            accent = true,
                            lit = reachedAt >= i + 1,
                        ) {
                            val days = trip.dayPlaces[place].orEmpty()
                            val bed = trip.bedOf(place)
                            val own = placeIdOf(place)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { actions.onOpenPlace(place) }) {
                                    Text(
                                        info?.name ?: "Unknown place",
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    // "Tokyo, Tokyo" says nothing twice.
                                    NoteLine(info?.region?.takeIf { it != info.name }, visit.cityNotes[own])
                                }
                                // Where the user slept, once the stop has other places to choose from.
                                if (days.isNotEmpty()) BedButton(slept = bed == own, onClick = { actions.onSlept(place, own) })
                                if (trip.cities.size > 1) {
                                    StopMenu(
                                        canEarlier = i > 0,
                                        canLater = i < trip.cities.size - 1,
                                        onMove = { by -> actions.onMove(place, by) },
                                    )
                                }
                            }
                            days.forEach { d ->
                                DayPlaceRow(
                                    name = placeOf(d, visit, places)?.name ?: journey.place(d)?.name ?: "Unknown place",
                                    slept = bed == d,
                                    onSlept = { actions.onSlept(place, d) },
                                    onOpen = { actions.onOpenPlace(d) },
                                    onRemove = { actions.onRemoveDayPlace(place, d) },
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            StopDatesPill(from, to, onClick = { stopDates = place })
                            val kept = trip.memories.filter { it.place == place }
                            MemoryRow(kept, onOpen = { viewing = it }, onAdd = { adding = place })
                            QuietActions(
                                memory = if (kept.isEmpty()) ({ adding = place }) else null,
                                dayTrip = if (!lived) ({ actions.onAddDayPlace(place) }) else null,
                            )
                        }
                    }
                    trip.cities.getOrNull(i + 1)?.let { next ->
                        item(key = "leg-$place-$next") { legRow(place, next, litAt = i + 2) }
                    }
                }
                item(key = "add-stop") {
                    Node(
                        icon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        first = false,
                        last = false,
                        faint = true,
                    ) {
                        TextButton(onClick = actions.onAddStop) { Text(if (trip.cities.isEmpty()) "Add the first city" else "Add a stop") }
                    }
                }
                if (journeyed && trip.cities.isNotEmpty()) {
                    val last = trip.cities.last()
                    item(key = "leg-$last-end") { legRow(last, com.marcusrosado.stamp.data.Leg.END, litAt = order.size - 1) }
                }
                item(key = "end") {
                    Node(
                        icon = { Icon(Icons.Outlined.Home, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        first = false,
                        last = true,
                    ) {
                        if (journeyed) {
                            PlaceLine(
                                label = "Back to ${endPlace?.name ?: "home"}",
                                hint = if (trip.end == null) "Home" else null,
                                onClick = { pickingEnd = false },
                            )
                        }
                        DateLine(
                            label = when {
                                trip.now -> "Still here"
                                trip.until != null -> (if (lived) "Moved away · " else "Got back · ") + trip.until.label
                                else -> if (lived) "When did you move away?" else "When did you get home?"
                            },
                            muted = trip.until == null && !trip.now,
                            onClick = { editingDates = true },
                        )
                    }
                }
                }
                // Its own track down the edge, one dot per stop: the marker steps to a stop once it
                // crosses the upper third of the list, and a tap on a dot scrolls there.
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                StopTrack(
                    count = order.size,
                    reached = reachedAt.coerceAtLeast(0),
                    onJump = { i ->
                        // Found by key: legs and rows come and go around the stops.
                        val item = itemKeys.indexOf(order[i]).coerceAtLeast(0)
                        scope.launch { list.animateScrollToItem(item) }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp).fillMaxHeight(0.72f).width(28.dp),
                )
              }
            }
            val m = map
            if (wide) {
                // One layout on a big screen: the map's pane slides open and shut beside the
                // timeline, and bent it always holds the map, so the list never crosses the fold
                // and never rebuilds.
                val mapOn = m != null && (showMap || hinge != null)
                Split(
                    hinge, flatFirst = if (mapOn) maxWidth * 0.5f else 0.dp,
                    first = { if (mapOn) m?.invoke(Modifier.fillMaxSize(), at) },
                    // Beside the map the compact header sits at the top of the screen: clear the status bar.
                    second = { timeline(Modifier.fillMaxSize().then(if (mapOn) Modifier.statusBarsPadding() else Modifier)) },
                )
            } else if (showMap && m != null) {
                Column(Modifier.fillMaxSize()) {
                    m(Modifier.fillMaxWidth().weight(2f), at)
                    timeline(Modifier.fillMaxWidth().weight(3f))
                }
            } else {
                timeline(Modifier.fillMaxSize())
            }
          }
        }
        if (editingDates) {
            val done: (Trip) -> Unit = { actions.onDates(it); editingDates = false }
            if (lived) {
                StayDialog(trip, onDone = done, onDismiss = { editingDates = false })
            } else {
                RangeDialog(
                    "When was it?", "Got there", "Got home", trip.date, trip.until,
                    onDone = { from, to -> done(trip.copy(date = from, until = to)) },
                    onDismiss = { editingDates = false },
                )
            }
        }
        stopDates?.let { place ->
            val (from, to) = trip.stopDates(place)
            RangeDialog(
                placeOf(place, visit, places)?.name ?: "This stop", "Arrived", "Left",
                trip.arrived[place], trip.left[place],
                guessFrom = from.first.takeIf { from.second },
                guessTo = to.first.takeIf { to.second },
                onDone = { a, l -> actions.onStopDates(place, a, l); stopDates = null },
                onDismiss = { stopDates = null },
            )
        }
        leg?.let { (from, to) ->
            val l = trip.legBetween(from, to)
            val a = journey.endPoint(trip, visit, from)
            val b = journey.endPoint(trip, visit, to)
            LegPicker(
                fromName = a?.name ?: nameOf(from),
                toName = b?.name ?: nameOf(to),
                from = a?.let { it.lat to it.lon },
                to = b?.let { it.lat to it.lon },
                initial = l?.ways.orEmpty(),
                initialVia = l?.via.orEmpty(),
                airports = journey.airports,
                onDone = { ways, via -> actions.onLeg(from, to, ways, via); leg = null },
                onDismiss = { leg = null },
                fromHome = from == com.marcusrosado.stamp.data.Leg.START && trip.start == null && journey.homeFor(trip) != null,
                toHome = to == com.marcusrosado.stamp.data.Leg.END && trip.end == null && journey.homeAtEnd(trip) != null,
            )
        }
        pickingEnd?.let { start ->
            TripEndPicker(
                title = if (start) "Where did you set out from?" else "Where did you go back to?",
                current = if (start) trip.start else trip.end,
                home = (if (start) journey.homeFor(trip) else journey.homeAtEnd(trip))
                    ?.let { placeOf(it.place, it.visit, places)?.name },
                suggestions = remember(trip, journey, start) { tripEndSuggestions(trip, journey, start) },
                places = places,
                onPick = { id -> if (start) actions.onStart(id) else actions.onEnd(id); pickingEnd = null },
                onDismiss = { pickingEnd = null },
            )
        }
        adding?.let { place ->
            val stop = place.ifEmpty { null }
            val (from, to) = if (stop != null) trip.stopDates(stop) else (trip.date to false) to (trip.until to false)
            AddMemorySheet(
                where = stop?.let { placeOf(it, visit, places)?.name } ?: country.name,
                window = window(from.first ?: trip.date, to.first ?: trip.until ?: from.first ?: trip.date),
                near = stop?.let { placeOf(it, visit, places) }?.let { it.lat to it.lon },
                already = trip.memories.mapNotNull { it.media }.toSet(),
                onAdd = { list -> actions.onAddMemories(list.map { it.copy(place = stop) }) },
                onDismiss = { adding = null },
            )
        }
        viewing?.let { m ->
            MemoryViewer(
                memory = trip.memories.firstOrNull { it.id == m.id } ?: m,
                onSave = { actions.onUpdateMemory(it) },
                onRemove = { actions.onRemoveMemory(m.id); viewing = null },
                onDismiss = { viewing = null },
            )
        }
    }
}

@Composable
private fun Hero(
    country: Country,
    where: String,
    title: String,
    dates: String?,
    photo: Memory?,
    onBack: () -> Unit,
    onDates: () -> Unit,
    /** Whether the map shows beside the timeline; null when there is no map to show. */
    mapShown: Boolean? = null,
    onMap: () -> Unit = {},
    compact: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    if (compact) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            HeroTitles(where, title, dates, onDates, big = false, modifier = Modifier.weight(1f).padding(start = 4.dp))
            if (mapShown != null) MapButton(mapShown, onMap)
        }
        return
    }
    Box(Modifier.fillMaxWidth().height(260.dp)) {
        if (photo?.media != null) {
            MediaImage(Uri.parse(photo.media), video = false, px = 1080, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), MaterialTheme.colorScheme.surface)),
                ),
            ) {
                Text(
                    country.flag,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = MaterialTheme.typography.displayLarge.fontSize * 2),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, MaterialTheme.colorScheme.surface)),
            ),
        )
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        if (mapShown != null) MapButton(mapShown, onMap, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
        HeroTitles(where, title, dates, onDates, big = true, modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp, vertical = 12.dp))
    }
}

/** Where the trip went, its name and its dates; the dates open their dialog. */
@Composable
private fun HeroTitles(where: String, title: String, dates: String?, onDates: () -> Unit, big: Boolean, modifier: Modifier) {
    Column(modifier) {
        Text(where, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(title, style = if (big) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            dates ?: "Add dates",
            style = if (big) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = if (dates == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDates).padding(vertical = if (big) 4.dp else 0.dp),
        )
    }
}

@Composable
private fun MapButton(shown: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (shown) "Hide map" else "Map")
    }
}

/** One step of the journey: a node on a vertical line, with its content beside it. */
@Composable
private fun Node(
    icon: @Composable () -> Unit,
    first: Boolean,
    last: Boolean,
    accent: Boolean = false,
    faint: Boolean = false,
    /** The journey has passed this node: the line on to the next one takes the accent. */
    lit: Boolean = false,
    content: @Composable () -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    // Reached, the line on to the next stop draws in from the node downward.
    val drawn by androidx.compose.animation.core.animateFloatAsState(
        if (lit) 1f else 0f, androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "line",
    )
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 20.dp, end = 16.dp)) {
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier
                    .padding(top = if (first) 22.dp else 0.dp)
                    .width(2.dp)
                    .fillMaxHeight(if (last) 0.05f else 1f)
                    .background(line),
            )
            if (drawn > 0f && !last) {
                Box(
                    Modifier
                        .padding(top = if (first) 22.dp else 0.dp)
                        .width(3.dp)
                        .fillMaxHeight(drawn)
                        .background(primary),
                )
            }
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(if (accent) 32.dp else 28.dp)
                    .clip(CircleShape)
                    .background(if (accent) primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(2.dp, if (faint) line else MaterialTheme.colorScheme.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides
                        if (accent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                ) { icon() }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 20.dp)) { content() }
    }
}

@Composable
private fun DateLine(label: String, muted: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = if (muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
    )
}

/** A place gone to from a stop on its dates, with a bed to say the user slept there instead. */
@Composable
private fun DayPlaceRow(name: String, slept: Boolean, onSlept: () -> Unit, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.SubdirectoryArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text(
                if (slept) "Slept here" else "Day trip",
                style = MaterialTheme.typography.labelSmall,
                color = if (slept) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BedButton(slept = slept, onClick = onSlept)
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Remove $name", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Says this is where the user slept: filled when it is, a quiet outline to move the bed here. */
@Composable
private fun BedButton(slept: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(
            if (slept) Icons.Filled.Hotel else Icons.Outlined.Hotel,
            contentDescription = if (slept) "Slept here" else "Slept here instead",
            tint = if (slept) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Moving a stop earlier or later, kept in a menu so the stops themselves stay calm. */
@Composable
private fun StopMenu(canEarlier: Boolean, canLater: Boolean, onMove: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Move this stop", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Move earlier") }, enabled = canEarlier,
                leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                onClick = { open = false; onMove(-1) },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Move later") }, enabled = canLater,
                leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                onClick = { open = false; onMove(1) },
            )
        }
    }
}

/** The small things a stop can still take, as quiet chips rather than empty tiles. */
@Composable
private fun QuietActions(memory: (() -> Unit)?, dayTrip: (() -> Unit)?) {
    if (memory == null && dayTrip == null) return
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        memory?.let { QuietChip(Icons.Outlined.AddAPhoto, "Memory", it) }
        dayTrip?.let { QuietChip(Icons.Filled.SubdirectoryArrowRight, "Day trip", it) }
    }
}

@Composable
private fun QuietChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Where a trip set out from or went back to, with a quiet note when it is the home guessed. */
@Composable
private fun PlaceLine(label: String, hint: String?, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** The quiet line under a stop's name: its region, and the heart and stars when given. */
@Composable
private fun NoteLine(region: String?, note: Note?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (region != null) {
            Text(region, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (note?.loved == true) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Favorite, contentDescription = "Loved", tint = LoveRed, modifier = Modifier.size(14.dp))
        }
        if ((note?.rating ?: 0) > 0) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Star, contentDescription = null, tint = StarGold, modifier = Modifier.size(14.dp))
            Text("${note!!.rating}", style = MaterialTheme.typography.labelMedium, color = StarGold)
        }
    }
}

/** "3 Jan → 9 Jan", guessed parts in italics, or a nudge to add dates. */
@Composable
private fun StopDatesPill(from: Pair<PartialDate?, Boolean>, to: Pair<PartialDate?, Boolean>, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (from.first == null && to.first == null) {
                Text("Add dates", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            } else {
                DatePart(from.first, from.second)
                Text("  →  ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DatePart(to.first, to.second)
            }
        }
    }
}

@Composable
private fun DatePart(d: PartialDate?, guessed: Boolean) {
    Text(
        d?.label ?: "?",
        style = MaterialTheme.typography.labelLarge,
        fontStyle = if (guessed) FontStyle.Italic else FontStyle.Normal,
        color = if (guessed || d == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
    )
}

/** A stop's photos, videos and moments in a row, ending with a tile that adds another. */
@Composable
private fun MemoryRow(memories: List<Memory>, onOpen: (Memory) -> Unit, onAdd: () -> Unit) {
    // With nothing kept yet, a quiet chip asks instead of an empty tile on every stop.
    if (memories.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    // A plain scrolling row, not a lazy one: the timeline row around it measures its height.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in memories) androidx.compose.runtime.key(m.id) { MemoryTile(m, onClick = { onOpen(m) }) }
        run {
            Box(
                Modifier
                    .size(TILE)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AddAPhoto, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("Memory", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private val TILE: Dp = 96.dp

@Composable
private fun MemoryTile(m: Memory, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    if (m.media == null) {
        Box(
            Modifier
                .widthIn(min = TILE, max = 200.dp)
                .height(TILE)
                .clip(shape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            Text(
                "“${m.text}”",
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    Box(Modifier.size(TILE).clip(shape).clickable(onClick = onClick)) {
        MediaImage(Uri.parse(m.media), m.video, px = 288, modifier = Modifier.fillMaxSize())
        if (m.video) {
            Box(
                Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White) }
        }
        if (m.text.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.EditNote, contentDescription = "Has a note", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** A photo or a video's first frame, cropped to fill. Grey while it loads or once it is gone. */
@Composable
fun MediaImage(uri: Uri, video: Boolean, px: Int, modifier: Modifier = Modifier, crop: Boolean = true) {
    val context = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri, px) {
        value = if (crop) Memories.thumbnail(context, uri, video, px) else Memories.picture(context, uri, px)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        bmp?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Add photos and videos from this trip or the gallery, or write a moment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemorySheet(
    where: String,
    window: Pair<Long, Long>?,
    near: Pair<Double, Double>?,
    already: Set<String>,
    onAdd: (List<Memory>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var writing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val chosen = remember { mutableStateListOf<Suggestion>() }
    // The scan's photo access, which also asks for media location (without it Android strips
    // GPS and far-off photos pass as nearby), plus videos. Any photo access is enough to start:
    // limited access on Android 14 or the scan's grant from onboarding both count.
    val perms = remember {
        if (Build.VERSION.SDK_INT >= 33) photoPermissions() + Manifest.permission.READ_MEDIA_VIDEO else photoPermissions()
    }
    fun granted() = photosGranted(perms.associateWith { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    var allowed by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted() }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Up to 30 items, three provider calls each: off the main thread.
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.map { u ->
                    Memories.keep(context, u)
                    Memory("", media = u.toString(), video = Memories.isVideo(context, u), takenMs = Memories.takenMs(context, u))
                }
            }
            onAdd(picked)
            onDismiss()
        }
    }
    val suggestions by produceState<List<Suggestion>?>(null, allowed, window) {
        value = if (allowed && window != null) {
            Memories.suggest(context, window.first, window.second, near).filter { it.uri.toString() !in already }
        } else emptyList()
    }
    // A sheet from the bottom on a phone; on the big screen, flat or bent, a dialog kept off the
    // fold, decided by the screen so a bend never swaps one for the other.
    val big = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("A memory from $where", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            if (writing) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    placeholder = { Text("What happened here?") },
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { writing = false }) { Text("Back") }
                    FilledTonalButton(
                        onClick = {
                            onAdd(listOf(Memory("", text = text.trim(), takenMs = System.currentTimeMillis())))
                            onDismiss()
                        },
                        enabled = text.isNotBlank(),
                    ) { Text("Keep it") }
                }
                return@Column
            }
            when {
                window == null -> Unit
                !allowed -> OutlinedButton(onClick = { ask.launch(perms) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Show my photos from these days")
                }
                suggestions == null -> Text("Looking through your photos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                suggestions!!.isEmpty() -> Text("No photos on this phone from these days", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text("From these days", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(suggestions!!, key = { it.uri.toString() }) { s ->
                            val on = s in chosen
                            Box(
                                Modifier
                                    .size(TILE)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                                    .clickable { if (on) chosen.remove(s) else chosen.add(s) },
                            ) {
                                MediaImage(s.uri, s.video, px = 288, modifier = Modifier.fillMaxSize())
                                if (s.video) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Video", tint = Color.White, modifier = Modifier.align(Alignment.Center))
                                }
                            }
                        }
                    }
                    if (chosen.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = {
                                onAdd(chosen.map { Memory("", media = it.uri.toString(), video = it.video, takenMs = it.takenMs) })
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (chosen.size == 1) "Add 1 photo" else "Add ${chosen.size}") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigChoice(Icons.Outlined.PhotoLibrary, "Gallery", Modifier.weight(1f)) {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                }
                BigChoice(Icons.Outlined.EditNote, "Write a moment", Modifier.weight(1f)) { writing = true }
            }
        }
    }
    // Writing a moment is a step of its own: back returns to the choices, not out of the sheet.
    val dismiss = { if (writing) writing = false else onDismiss() }
    if (big) {
        SeamDialog(onDismissRequest = dismiss) {
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(Modifier.padding(top = 24.dp).verticalScroll(rememberScrollState())) { body() }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = dismiss) { body() }
    }
}

@Composable
private fun BigChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier) {
        Column(Modifier.padding(vertical = 20.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A memory full size: the photo, a video's frame with a play button, and its note. */
@Composable
private fun MemoryViewer(memory: Memory, onSave: (Memory) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember(memory.id) { mutableStateOf(memory.text) }
    val screen = 1600
    // A written moment emptied of its words is gone, not an empty tile.
    fun finish() {
        when {
            memory.media == null && text.isBlank() -> onRemove()
            text != memory.text -> { onSave(memory.copy(text = text.trim())); onDismiss() }
            else -> onDismiss()
        }
    }
    SeamDialog(onDismissRequest = ::finish) {
        Surface(Modifier.fillMaxWidth(0.94f).widthIn(max = 640.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                memory.media?.let { u ->
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(enabled = memory.video) { Memories.play(context, Uri.parse(u)) }) {
                        MediaImage(Uri.parse(u), memory.video, px = screen, modifier = Modifier.fillMaxSize(), crop = memory.video)
                        if (memory.video) {
                            Box(
                                Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(36.dp)) }
                        }
                    }
                }
                Column(Modifier.padding(20.dp)) {
                    if (memory.takenMs > 0) {
                        Text(date(memory.takenMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (memory.media == null) "What happened here?" else "Add a note") },
                    )
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove memory") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = ::finish) { Text("Done") }
                    }
                }
            }
        }
    }
}

/**
 * A start and an end, each picked as loosely as remembered or cleared, with guesses shown in
 * italics. The end may not come before the start.
 */
@Composable
private fun RangeDialog(
    title: String,
    fromLabel: String,
    toLabel: String,
    from: PartialDate?,
    to: PartialDate?,
    guessFrom: PartialDate? = null,
    guessTo: PartialDate? = null,
    onDone: (PartialDate?, PartialDate?) -> Unit,
    onDismiss: () -> Unit,
) {
    var a by remember { mutableStateOf(from) }
    var b by remember { mutableStateOf(to) }
    var editing by remember { mutableStateOf<Int?>(null) }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DateRow(fromLabel, a, guessFrom, onEdit = { editing = 0 }, onClear = { a = null })
                DateRow(toLabel, b, guessTo, onEdit = { editing = 1 }, onClear = { b = null })
            }
        },
        confirmButton = {
            val start = a
            val end = b
            val backwards = start != null && end != null && end.endsBefore(start)
            TextButton(onClick = { onDone(a, b) }, enabled = !backwards) {
                Text(if (backwards) "$toLabel is before $fromLabel" else "Done")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    editing?.let { which ->
        DateDialog(
            initial = if (which == 0) a ?: guessFrom else b ?: guessTo ?: a ?: guessFrom,
            onDone = { if (which == 0) a = it else b = it; editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun DateRow(label: String, value: PartialDate?, guess: PartialDate?, onEdit: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onEdit).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(80.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value?.label ?: guess?.let { "${it.label}, guessed" } ?: "Add",
            modifier = Modifier.weight(1f),
            fontStyle = if (value == null && guess != null) FontStyle.Italic else FontStyle.Normal,
            color = if (value == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (value != null) {
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

/** "3 Jan 2025 to 20 Jan 2025", "From 3 Jan 2025", or null. */
fun rangeLabel(from: PartialDate?, to: PartialDate?, now: Boolean = false): String? = when {
    now -> from?.let { "Since ${it.label}" } ?: "Living here now"
    from != null && to != null -> "${from.label} to ${to.label}"
    from != null -> from.label
    to != null -> "Until ${to.label}"
    else -> null
}

/** The span of time a loose date range covers, in epoch ms, for finding photos from it. */
private fun window(from: PartialDate?, to: PartialDate?): Pair<Long, Long>? {
    // Either end is enough: a visit known only by when it ended still finds its photos.
    @Suppress("NAME_SHADOWING") val from = from ?: to ?: return null
    val zone = ZoneId.systemDefault()
    fun start(d: PartialDate) = LocalDate.of(d.year, d.month ?: 1, d.day ?: 1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun end(d: PartialDate): Long {
        val last = when {
            d.month == null -> LocalDate.of(d.year, 12, 31)
            d.day == null -> YearMonth.of(d.year, d.month).atEndOfMonth()
            else -> LocalDate.of(d.year, d.month, d.day)
        }
        return last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }
    val endDate = to ?: from
    // The trip's own time zone is unknown, so the window reaches 14 hours either way: a day in
    // Japan seen from a phone set to Copenhagen still holds every photo from that day.
    val slack = 14 * 3_600_000L
    return start(from) - slack to end(endDate).coerceAtLeast(start(from)) + slack
}

/** The timeline's nodes, by their list keys: the start, each stop and the way home. */
private fun isNode(key: Any): Boolean = key is String && (key == "start" || key == "end" || key.startsWith("stop-"))

/**
 * The journey at a glance: a short track with a dot per stop, evenly spaced, and a marker
 * that steps to the stop reached and eases between them. It follows stops, never the scroll.
 */
@Composable
private fun StopTrack(count: Int, reached: Int, onJump: (Int) -> Unit, modifier: Modifier) {
    val at by androidx.compose.animation.core.animateFloatAsState(
        reached.toFloat(),
        androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "marker",
    )
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    val ring = MaterialTheme.colorScheme.surface
    androidx.compose.foundation.Canvas(
        modifier.pointerInput(count) {
            detectTapGestures { p ->
                val step = size.height.toFloat() / (count - 1)
                onJump((p.y / step).roundToInt().coerceIn(0, count - 1))
            }
        },
    ) {
        val x = size.width / 2
        val step = size.height / (count - 1)
        drawLine(line, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2.dp.toPx())
        drawLine(accent, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, at * step), strokeWidth = 3.dp.toPx())
        for (i in 0 until count) {
            val c = androidx.compose.ui.geometry.Offset(x, i * step)
            drawCircle(if (i <= reached) accent else line, radius = 4.dp.toPx(), center = c)
        }
        val m = androidx.compose.ui.geometry.Offset(x, at * step)
        drawCircle(accent.copy(alpha = 0.2f), radius = 12.dp.toPx(), center = m)
        drawCircle(accent, radius = 7.dp.toPx(), center = m)
        drawCircle(ring, radius = 3.dp.toPx(), center = m)
    }
}
