package com.marcusrosado.stamp.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.placeIdOf
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.Note
import com.marcusrosado.stamp.data.isPin
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.PhotoEvidence
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.Visit
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val TravelState.icon: ImageVector
    get() = when (this) {
        TravelState.Visited -> Icons.Filled.Flag
        TravelState.Lived -> Icons.Filled.Home
        TravelState.Want -> Icons.Filled.Bookmark
    }

/**
 * Visited, Lived, Want to go. Tapping the active one clears the mark. In a narrow column,
 * such as the side panel, the buttons stack so each label fits.
 */
@Composable
fun StateButtons(current: TravelState?, onSet: (TravelState?) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 380.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TravelState.entries.forEach { s ->
                    StateButton(s, s == current, onSet, Modifier.fillMaxWidth(), s.label)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TravelState.entries.forEach { s ->
                    StateButton(s, s == current, onSet, Modifier.weight(1f), s.short)
                }
            }
        }
    }
}

@Composable
private fun StateButton(s: TravelState, on: Boolean, onSet: (TravelState?) -> Unit, modifier: Modifier, label: String) {
    val tint = LocalMapPalette.current.of(s)
    FilledTonalButton(
        onClick = { onSet(if (on) null else s) },
        modifier = modifier,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        colors = if (on) {
            ButtonDefaults.filledTonalButtonColors(containerColor = tint, contentColor = Color(0xFF101215))
        } else {
            ButtonDefaults.filledTonalButtonColors()
        },
    ) {
        Icon(s.icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Name, continent, the three buttons, and what the photo scan saw there. */
@Composable
fun CountryDetail(
    country: Country,
    visit: Visit?,
    isHome: Boolean,
    onSet: (TravelState?) -> Unit,
    actions: DetailActions,
    places: Places?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** For a separate land mass, the name of the country it belongs to. */
    partOf: String? = null,
    /** "🇦🇹 Austria" for a country code, to label stops found outside this country. */
    countryLabel: (String) -> String = { it },
    /** A land mass's name from its id, such as "Ryukyu Islands" for JPN-JPO. */
    landName: (String) -> String = { it },
    /** A trip to open straight away, such as one just started from the map's menu. */
    openTrip: String? = null,
    onTripOpened: () -> Unit = {},
    onTripClosed: () -> Unit = {},
    /** Draws a map of the trip beside its timeline, centred on a point. */
    tripMap: (@Composable (Modifier, Pair<Double, Double>?) -> Unit)? = null,
    /**
     * With whole countries, the country's separate land masses that carry marks of their own,
     * as id and label, so their visits stay reachable.
     */
    markedParts: List<Pair<String, String>> = emptyList(),
    onOpenPart: (String) -> Unit = {},
) {
    var confirmClear by remember(country.id) { mutableStateOf(false) }
    // The visit a city is being picked for.
    var pickingFor by remember(country.id) { mutableStateOf<String?>(null) }
    // The city open in its dialog, and the visit it was opened from, if any.
    var openCity by remember(country.id) { mutableStateOf<Pair<String, String?>?>(null) }
    var pickingRegion by remember(country.id) { mutableStateOf(false) }
    var openRegion by remember(country.id) { mutableStateOf<String?>(null) }
    val regionsVisited = remember(visit, places) { if (visit != null && places != null) places.regionsVisited(visit) else emptyMap() }
    // "…" only while the city list loads; after that a name that cannot be found says so.
    val cityName: (String) -> String = { placeOf(it, visit, places)?.name ?: if (places == null) "…" else "Unknown place" }
    // A pin being placed: the visit it goes on and the name typed in the search.
    var pinning by remember(country.id) { mutableStateOf<Pair<String, String>?>(null) }
    // The stop a day place is being picked for, with its trip; and a day place being pinned.
    var dayFor by remember(country.id) { mutableStateOf<Pair<String, String>?>(null) }
    var dayPinning by remember(country.id) { mutableStateOf<Triple<String, String, String>?>(null) }
    // The visit open as a timeline.
    var timelineFor by remember(country.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(openTrip, visit) {
        if (openTrip != null && visit?.trips?.any { it.id == openTrip } == true) {
            timelineFor = openTrip
            onTripOpened()
        }
    }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (country.flag.isNotEmpty()) {
                Text(country.flag, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(country.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Text(
                    (if (isHome) "Home · " else "") + (partOf?.let { "Part of $it · " } ?: "") + country.continent +
                        if (country.un || partOf != null) "" else " · not counted as a UN country",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        if (markedParts.isNotEmpty()) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((pid, label) in markedParts) {
                    androidx.compose.material3.AssistChip(onClick = { onOpenPart(pid) }, label = { Text(label) })
                }
            }
        }
        StateButtons(visit?.state, { s ->
            // Clearing a mark drops its visits, stars and heart too, so ask first.
            if (s == null && visit?.hasDetails == true) confirmClear = true else onSet(s)
        })
        if (visit != null && visit.state.counts) {
            LoveAndRating(
                visit.loved, visit.rating,
                onLoved = { actions.onLoved(country.id, it) },
                onRating = { actions.onRating(country.id, it) },
            )
            TripsSection(
                visit.trips,
                lived = visit.state == TravelState.Lived,
                cityName = cityName,
                onAdd = { actions.onAddTrip(country.id) },
                onRemove = { actions.onRemoveTrip(country.id, it.id) },
                onPickCity = { pickingFor = it.id },
                onOpenTimeline = { timelineFor = it.id },
            )
            if (places != null) {
                CitiesSection(visit, places, onOpenCity = { openCity = it to null })
                // Regions use the country's own code: a place that borrows another country's
                // cities, such as Somaliland, has no regions of its own in GeoNames.
                val all = places.regionsIn(country.alpha2)
                RegionsSection(
                    regionsVisited,
                    counted = regionsVisited.keys.count { id -> all.any { it.id == id } },
                    total = all.size,
                    nameOf = { places.regionName(it) ?: it },
                    sortKey = places::regionKey,
                    onAdd = { pickingRegion = true },
                    onOpen = { openRegion = it },
                )
            }
        }
        visit?.evidence?.let { ev ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.PhotoLibrary, contentDescription = null,
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "In your photos: ${evidenceLine(ev)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    // First, so the pickers and dialogs it opens draw over it.
    timelineFor?.let { tripId ->
        val v = visit
        val index = v?.trips?.indexOfFirst { it.id == tripId } ?: -1
        if (v == null || index < 0) {
            timelineFor = null
            return@let
        }
        val trip = v.trips[index]
        VisitTimeline(
            landName = landName,
            country = country,
            visit = v,
            trip = trip,
            number = index + 1,
            lived = v.state == TravelState.Lived,
            places = places,
            actions = TimelineActions(
                onDates = { actions.onUpdateTrip(it) },
                onStopDates = { p, a, l -> actions.onStopDates(tripId, p, a, l) },
                onMove = { p, by -> actions.onMoveStop(tripId, p, by) },
                onLeg = { from, to, ways, via, around -> actions.onLeg(tripId, from, to, ways, via, around) },
                onStart = { actions.onTripStart(tripId, it) },
                onEnd = { actions.onTripEnd(tripId, it) },
                onAddDayPlace = { stop -> dayFor = tripId to stop },
                onRemoveDayPlace = { stop, p -> actions.onRemoveDayPlace(tripId, stop, p) },
                onSlept = { stop, p -> actions.onSlept(tripId, stop, p) },
                onAddStop = { pickingFor = tripId },
                onOpenPlace = { openCity = it to tripId },
                onAddMemories = { actions.onAddMemories(tripId, it) },
                onUpdateMemory = { actions.onUpdateMemory(tripId, it) },
                onRemoveMemory = { actions.onRemoveMemory(tripId, it) },
            ),
            onDismiss = { timelineFor = null; onTripClosed() },
            map = tripMap,
        )
    }
    pickingFor?.let { tripId ->
        val trip = visit?.trips?.firstOrNull { it.id == tripId }
        // The visit was removed while the picker was open: nothing to add to.
        if (trip == null) {
            pickingFor = null
            return@let
        }
        CityPicker(
            countryName = country.name,
            flag = country.flag,
            alpha2 = citiesCountry(country),
            places = places,
            already = trip.cities.map(::placeIdOf).toSet(),
            onPick = { c ->
                actions.onAddCity(country.id, trip.id, c.id)
                pickingFor = null
            },
            onPin = { q ->
                pinning = trip.id to q.replaceFirstChar { it.titlecase() }
                pickingFor = null
            },
            onDismiss = { pickingFor = null },
            countryLabel = countryLabel,
        )
    }
    pinning?.let { (tripId, name) ->
        PinPlace(
            country = country,
            cities = places?.citiesIn(citiesCountry(country)).orEmpty(),
            initialName = name,
            onAttach = { pin ->
                actions.onAddPin(country.id, tripId, pin)
                pinning = null
            },
            onDismiss = { pinning = null },
        )
    }
    dayFor?.let { (tripId, stop) ->
        val trip = visit?.trips?.firstOrNull { it.id == tripId }
        if (trip == null || stop !in trip.cities) {
            dayFor = null
            return@let
        }
        CityPicker(
            countryName = country.name,
            flag = country.flag,
            alpha2 = citiesCountry(country),
            places = places,
            already = trip.placesAt(stop).toSet(),
            // Gone there from this stop already: nothing to add again.
            againOk = false,
            title = "Went from ${placeOf(stop, visit, places)?.name ?: "here"} to",
            onPick = { c ->
                actions.onAddDayPlace(tripId, stop, c.id)
                dayFor = null
            },
            onPin = { q ->
                dayPinning = Triple(tripId, stop, q.replaceFirstChar { it.titlecase() })
                dayFor = null
            },
            onDismiss = { dayFor = null },
            countryLabel = countryLabel,
        )
    }
    dayPinning?.let { (tripId, stop, name) ->
        PinPlace(
            country = country,
            cities = places?.citiesIn(citiesCountry(country)).orEmpty(),
            initialName = name,
            onAttach = { pin ->
                actions.onAddDayPin(tripId, stop, pin)
                dayPinning = null
            },
            onDismiss = { dayPinning = null },
        )
    }
    openCity?.let { (stopId, tripId) ->
        // Notes belong to the place, not to one stop at it.
        val cid = placeIdOf(stopId)
        val tripIndex = visit?.trips?.indexOfFirst { it.id == tripId } ?: -1
        CityDialog(
            place = placeOf(cid, visit, places),
            visits = visit?.visitsTo(cid) ?: 0,
            note = visit?.cityNotes?.get(cid) ?: Note(),
            onNote = { actions.onCityNote(country.id, cid, it) },
            // Any place can carry the user's own name; an empty one goes back to the real name.
            onRename = { name -> actions.onCityNote(country.id, cid, (visit?.cityNotes?.get(cid) ?: Note()).copy(name = name.trim())) },
            onShowOnMap = placeOf(cid, visit, places)?.let { p -> { actions.onShowOnMap(p.lat, p.lon) } },
            removeLabel = if (tripIndex >= 0) {
                "Remove from ${if (visit?.state == TravelState.Lived) "stay" else "visit"} ${tripIndex + 1}"
            } else null,
            onRemove = {
                if (tripId != null) actions.onRemoveCity(tripId, stopId)
                openCity = null
            },
            onDismiss = { openCity = null },
        )
    }
    if (pickingRegion && places != null) {
        RegionPicker(
            countryName = country.name,
            regions = places.regionsIn(country.alpha2),
            visited = regionsVisited.keys,
            // Stays open, so several regions go in one go.
            onPick = { actions.onAddRegion(country.id, it.id) },
            onDismiss = { pickingRegion = false },
        )
    }
    openRegion?.let { rid ->
        val from = regionsVisited[rid]
        SeamAlertDialog(
            onDismissRequest = { openRegion = null },
            title = { Text(places?.regionName(rid) ?: rid) },
            text = {
                Text(
                    if (from != null) "Counted because you were in ${from.name}." else "You added this region yourself.",
                )
            },
            confirmButton = { TextButton(onClick = { openRegion = null }) { Text("Done") } },
            dismissButton = if (from == null) {
                { TextButton(onClick = { actions.onRemoveRegion(country.id, rid); openRegion = null }) { Text("Remove") } }
            } else null,
        )
    }
    if (confirmClear && visit != null) {
        val lost = listOfNotNull(
            visit.trips.count { !it.isBlank }.takeIf { it > 0 }?.let { if (visit.state == TravelState.Lived) "the dates you lived here" else visitsLabel(it) },
            visit.rating.takeIf { it > 0 }?.let { "your $it-star rating" },
            "the heart".takeIf { visit.loved },
            visit.regions.size.takeIf { it > 0 }?.let { if (it == 1) "1 region you added" else "$it regions you added" },
            visit.cities.size.takeIf { it > 0 }?.let { n ->
                (if (n == 1) "1 city" else "$n cities") + if (visit.cityNotes.isNotEmpty()) " with their hearts and stars" else ""
            },
        )
        SeamAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear ${country.name}?") },
            text = { Text("This also removes ${lost.joinToString(", ")}.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onSet(null) }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}

/**
 * The country whose cities to offer. Natural Earth has a few places GeoNames files under
 * another country's code: Somaliland's cities are in Somalia, Northern Cyprus's in Cyprus.
 */
internal fun citiesCountry(c: Country): String = c.alpha2.ifEmpty {
    when (c.id) {
        "SOL" -> "SO"
        "CYN" -> "CY"
        "KAS" -> "IN"
        else -> ""
    }
}


private val DayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy")

fun date(ms: Long): String = DayMonthYear.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/** "3 Apr 2023" for a single day, "14 Aug 2021 to 3 Jun 2024" for a span. */
fun evidenceLine(ev: PhotoEvidence): String {
    if (ev.firstMs <= 0) return "date unknown"
    val a = date(ev.firstMs)
    val b = date(ev.lastMs)
    return if (a == b) a else "$a to $b"
}

/** A rounded panel on the surface, the app's one card style. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** A small round swatch in a mark's colour. */
@Composable
fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

/** Flag emoji, or the country code in a chip when the place has no flag of its own. */
@Composable
fun FlagMark(country: Country, modifier: Modifier = Modifier) {
    Box(modifier.width(32.dp), contentAlignment = Alignment.Center) {
        if (country.flag.isNotEmpty()) {
            Text(country.flag, style = MaterialTheme.typography.titleLarge)
        } else {
            Text(
                country.id, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
