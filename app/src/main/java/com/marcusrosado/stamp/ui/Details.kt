package com.marcusrosado.stamp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import com.marcusrosado.stamp.data.placeIdOf
import com.marcusrosado.stamp.data.City
import com.marcusrosado.stamp.data.Note
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.Region
import com.marcusrosado.stamp.data.Visit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.MAX_RATING
import com.marcusrosado.stamp.data.Pin
import com.marcusrosado.stamp.data.Memory
import com.marcusrosado.stamp.data.isPin
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Trip
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** What the country card can change besides the mark itself. */
class DetailActions(
    /** Draws a country as its regions, or as one again. */
    val onSplitRegions: (countryId: String, on: Boolean) -> Unit = { _, _ -> },
    val onLoved: (id: String, loved: Boolean) -> Unit,
    val onRating: (id: String, rating: Int) -> Unit,
    val onAddTrip: (id: String) -> Unit,
    val onUpdateTrip: (trip: Trip) -> Unit,
    val onRemoveTrip: (id: String, tripId: String) -> Unit,
    /** A null trip puts the city on a new visit. */
    val onAddCity: (id: String, tripId: String?, cityId: String) -> Unit,
    val onRemoveCity: (tripId: String, cityId: String) -> Unit,
    val onCityNote: (id: String, cityId: String, note: Note) -> Unit,
    /** A null trip puts the pin on a new visit, as with cities. */
    val onAddPin: (id: String, tripId: String?, pin: Pin) -> Unit,
    val onRenamePin: (pinId: String, name: String) -> Unit,
    /** Flies the map to a city or pin, close enough to see its outline. */
    val onShowOnMap: (lat: Double, lon: Double) -> Unit,
    val onStopDates: (tripId: String, place: String, arrived: PartialDate?, left: PartialDate?) -> Unit,
    val onStopOrder: (tripId: String, cities: List<String>) -> Unit,
    val onLeg: (tripId: String, from: String, to: String, steps: List<com.marcusrosado.stamp.data.Step>, around: Boolean) -> Unit,
    val onTripStart: (tripId: String, place: String?) -> Unit = { _, _ -> },
    val onAddDayPlace: (tripId: String, stop: String, place: String) -> Unit = { _, _, _ -> },
    val onAddDayPin: (tripId: String, stop: String, pin: Pin) -> Unit = { _, _, _ -> },
    val onRemoveDayPlace: (tripId: String, stop: String, place: String) -> Unit = { _, _, _ -> },
    val onSlept: (tripId: String, stop: String, place: String) -> Unit = { _, _, _ -> },
    val onTripEnd: (tripId: String, place: String?) -> Unit = { _, _ -> },
    val onAddMemories: (tripId: String, memories: List<Memory>) -> Unit,
    val onUpdateMemory: (tripId: String, memory: Memory) -> Unit,
    val onRemoveMemory: (tripId: String, memoryId: String) -> Unit,
    val onAddRegion: (id: String, regionId: String) -> Unit,
    val onRemoveRegion: (id: String, regionId: String) -> Unit,
)

val LoveRed = Color(0xFFE5546B)
val StarGold = Color(0xFFF2B33D)

/** "1 visit", "3 visits". */
fun visitsLabel(n: Int): String = if (n == 1) "1 visit" else "$n visits"

/** "1 stay", "3 stays". */
fun staysLabel(n: Int): String = if (n == 1) "Lived here once" else "Lived here $n times"

/** "2015 to 2019", "Since 2019 · now", "From 2015", "Until 2019", or null with no dates. */
fun stayLabel(t: Trip): String? = when {
    t.now -> t.date?.let { "Since ${it.label} · now" } ?: "Living here now"
    t.date != null && t.until != null -> "${t.date.label} to ${t.until.label}"
    t.date != null -> "From ${t.date.label}"
    t.until != null -> "Until ${t.until.label}"
    else -> null
}

/** A heart and five stars. Tapping the current star again clears the rating. */
@Composable
fun LoveAndRating(loved: Boolean, rating: Int, onLoved: (Boolean) -> Unit, onRating: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onLoved(!loved) }) {
            Icon(
                if (loved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (loved) "Loved" else "Love it",
                tint = if (loved) LoveRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        for (n in 1..MAX_RATING) {
            IconButton(onClick = { onRating(if (rating == n) 0 else n) }, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (n <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "$n of $MAX_RATING",
                    tint = if (n <= rating) StarGold else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * "2 visits" with minus and plus, then one block per visit: its date, and the cities seen on
 * it as chips with a chip to add another.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripsSection(
    trips: List<Trip>,
    /** A Lived country: its entries are stays with a from and until, not visits. */
    lived: Boolean,
    cityName: (String) -> String,
    onAdd: () -> Unit,
    onRemove: (Trip) -> Unit,
    onPickCity: (Trip) -> Unit,
    /** Opens the visit as a timeline, with its stops, dates and memories. */
    onOpenTimeline: (Trip) -> Unit,
) {
    var removing by remember { mutableStateOf<Trip?>(null) }
    // Minus only takes away a visit with nothing on it; one with a date or cities goes
    // through its own remove button, which asks first.
    val lastEmpty = trips.lastOrNull { it.isBlank }.takeIf { trips.size > 1 }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (lived) "Lived here" else "Visits", fontWeight = FontWeight.Medium)
                // Lived here once needs no count under it; moving back gives one.
                (if (lived) "${trips.size} times".takeIf { trips.size > 1 } else visitsLabel(trips.size))?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FilledTonalIconButton(onClick = { lastEmpty?.let(onRemove) }, enabled = lastEmpty != null) {
                Icon(Icons.Filled.Remove, contentDescription = if (lived) "One time fewer" else "One visit fewer")
            }
            FilledTonalIconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = if (lived) "Lived here another time" else "One more visit")
            }
        }
        trips.forEachIndexed { i, t ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                    .padding(start = 12.dp, bottom = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenTimeline(t) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Living somewhere is not a numbered stay: it says whether the user still does.
                    if (!lived) Text("Visit ${i + 1}", modifier = Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
                    val when_ = if (lived) stayLabel(t) else rangeLabel(t.date, t.until)
                    Text(
                        when_ ?: if (lived) "Add dates" else "Add a date",
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        color = if (when_ == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    // The only visit of a visited country stays; there is nothing to remove.
                    if (trips.size > 1) {
                        IconButton(onClick = { if (t.isBlank) onRemove(t) else removing = t }) {
                            Icon(Icons.Filled.Close, contentDescription = if (lived) "Remove this time living here" else "Remove visit ${i + 1}", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                VisitSummary(
                    t,
                    lived = lived,
                    cityName = cityName,
                    onOpen = { onOpenTimeline(t) },
                    onPickCity = { onPickCity(t) },
                )
            }
        }
    }
    removing?.let { t ->
        LossDialog(
            Loss(
                if (lived) "Remove this time living here?" else "Remove this visit?",
                // Everything that goes with it, photos included, so nothing is lost by surprise.
                listOfNotNull(
                    if (lived) stayLabel(t) else rangeLabel(t.date, t.until),
                    t.places.size.takeIf { it > 0 }?.let { if (it == 1) "1 city" else "$it cities" },
                    t.memories.size.takeIf { it > 0 }?.let { if (it == 1) "1 memory" else "$it memories" },
                ).joinToString(", ").ifEmpty { "Nothing filled in yet" },
                "Remove",
            ),
            onConfirm = { onRemove(t); removing = null },
            onDismiss = { removing = null },
        )
    }
}

/**
 * A visit at a glance: its photos, the route through its cities, and the way into the full
 * timeline. Inviting rather than empty when nothing is kept yet.
 */
@Composable
private fun VisitSummary(t: Trip, lived: Boolean, cityName: (String) -> String, onOpen: () -> Unit, onPickCity: () -> Unit) {
    val media = t.memories.filter { it.media != null }
    Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (media.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)) {
                for ((k, m) in media.take(4).withIndex()) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) {
                        MediaImage(android.net.Uri.parse(m.media), m.video, px = 192, modifier = Modifier.fillMaxSize())
                        if (k == 3 && media.size > 4) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                                Text("+${media.size - 3}", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
        if (t.cities.isNotEmpty()) {
            Text(
                // Homes are a list in the order moved into, not a route between them.
                if (lived) t.homesInOrder.joinToString("  ·  ") { cityName(it) }
                else t.cities.joinToString("  →  ") { cityName(it) },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(vertical = 2.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = onOpen,
                label = { Text("Timeline") },
                leadingIcon = { Icon(Icons.Outlined.Timeline, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
            AssistChip(
                onClick = onPickCity,
                label = { Text(if (lived) "Add home" else "Add city") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

/** Every city on any visit, with how many visits it was on, its heart and stars. */
@Composable
fun CitiesSection(visit: Visit, places: Places, onOpenCity: (String) -> Unit) {
    val ids = visit.cities
    if (ids.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(if (ids.size == 1) "1 city" else "${ids.size} cities", fontWeight = FontWeight.Medium)
        for (cid in ids) {
            val place = placeOf(cid, visit, places)
            val note = visit.cityNotes[cid]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenCity(cid) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(place?.name ?: "Unknown place")
                        if (place?.pinned == true) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.PushPin, contentDescription = "Pinned by hand", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        listOfNotNull(place?.region, visitsLabel(visit.visitsTo(cid))).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (note?.loved == true) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Loved", tint = LoveRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                if ((note?.rating ?: 0) > 0) {
                    Text("★".repeat(note!!.rating), color = StarGold)
                }
            }
        }
    }
}

/** One city or pin: where it is, how many visits it was on, and the user's heart and stars. */
@Composable
fun CityDialog(
    place: PlaceInfo?,
    visits: Int,
    note: Note,
    onNote: (Note) -> Unit,
    /** Set for a pin, whose name the user chose and can change. */
    onRename: ((String) -> Unit)?,
    /** Shown when the dialog was opened from a visit, such as "Remove from visit 2". */
    removeLabel: String?,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onShowOnMap: (() -> Unit)? = null,
    /**
     * What removing asks first. Taking a stop off a visit takes its dates, day trips and legs
     * with it; a day trip takes only itself.
     */
    removeAsk: Loss? = removeLabel?.let { Loss("$it?", "Its dates, day trips and the ways to and from it go too.", "Remove") },
) {
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    if (removing && removeAsk != null) {
        LossDialog(
            removeAsk,
            onConfirm = { removing = false; onRemove() },
            onDismiss = { removing = false },
        )
    }
    // Starts with the user's own name, never the real one, so Done on an untouched field
    // leaves the place unnamed.
    var name by remember(place?.name) { mutableStateOf(place?.takeIf { it.name != it.own }?.name.orEmpty()) }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (renaming && onRename != null) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Your name for it") },
                    placeholder = { Text(place?.own ?: "") },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(place?.name ?: "City", modifier = Modifier.weight(1f, fill = false))
                    if (onRename != null) {
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, contentDescription = "Name it") }
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOfNotNull(
                        place?.region,
                        place?.takeIf { it.pinned }?.let { coordinates(it.lat, it.lon) },
                        visitsLabel(visits),
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LoveAndRating(
                    note.loved, note.rating,
                    onLoved = { onNote(note.copy(loved = it)) },
                    onRating = { onNote(note.copy(rating = it)) },
                )
                if (onShowOnMap != null) {
                    // A rename being typed is kept, as Done would keep it.
                    TextButton(onClick = {
                        if (renaming && onRename != null) onRename(name)
                        onShowOnMap()
                        onDismiss()
                    }) {
                        Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Show on map")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (renaming && onRename != null) onRename(name)
                onDismiss()
            }) { Text("Done") }
        },
        dismissButton = removeLabel?.let { { TextButton(onClick = { removing = true }) { Text(it, color = MaterialTheme.colorScheme.error) } } },
    )
}

/** A city or a pin on a visit, the way the screens show it. */
/**
 * A place as the user sees it: [name] is their own name for it when they gave one, else its
 * real name; [own] is always the real one, the town or the address, and [region] then says it.
 */
class PlaceInfo(val name: String, val region: String?, val lat: Double, val lon: Double, val pinned: Boolean, val own: String = name)

/** Looks [id] up among the visit's pins or the bundled cities. Null while cities load. */
fun placeOf(stop: String, visit: Visit?, places: Places?): PlaceInfo? {
    // A second stop at the same place on one trip is still that place.
    val id = placeIdOf(stop)
    val given = visit?.cityNotes?.get(id)?.name?.takeIf { it.isNotBlank() }
    if (isPin(id)) {
        val p = visit?.pins?.get(id) ?: return null
        return PlaceInfo(given ?: p.name, if (given != null) p.name else "Pinned", p.lat, p.lon, pinned = true, own = p.name)
    }
    val c = places?.byId?.get(id) ?: return null
    val region = places.regionOf(c)
    return PlaceInfo(given ?: c.name, if (given != null) listOfNotNull(c.name, region).joinToString(", ") else region, c.lat, c.lon, pinned = false, own = c.name)
}

/**
 * Pick a date as loosely as the user remembers it: a year, then optionally a month, then
 * optionally a day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DateDialog(initial: PartialDate?, onDone: (PartialDate?) -> Unit, onDismiss: () -> Unit) {
    val thisYear = LocalDate.now().year
    // A stored year outside the wheel, from old data or a camera with a wrong clock, starts at its edge.
    val startYear = (initial?.year ?: thisYear).coerceIn(FIRST_YEAR, thisYear)
    var year by rememberSaveable { mutableStateOf(startYear) }
    var month by rememberSaveable { mutableStateOf(initial?.month) }
    var day by rememberSaveable { mutableStateOf(initial?.day) }
    val days = month?.let { YearMonth.of(year, it).lengthOfMonth() } ?: 31
    // A day past the end of a shorter month reads as that month's last day.
    val shownDay = day?.coerceAtMost(days)

    SeamAlertDialog(
        onDismissRequest = onDismiss,
        // A date being picked survives a stray tap outside; untouched, a tap outside closes it.
        outsideCloses = year == startYear && month == initial?.month && day == initial?.day,
        title = { Text("When was it?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val years = (FIRST_YEAR..thisYear).toList()
                Wheel(years.map { "$it" }, selected = year - FIRST_YEAR, onSelect = { year = years[it] })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = month == null, onClick = { month = null; day = null }, label = { Text("Any month") })
                    for (m in 1..12) {
                        FilterChip(
                            selected = month == m,
                            onClick = { month = m },
                            label = { Text(Month.of(m).getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        )
                    }
                }
                if (month != null) {
                    // Swipe the days, or step them; stepping past either end of the month carries
                    // on into the next or the previous one, the way a calendar does.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalIconButton(onClick = {
                            val m = month ?: return@FilledTonalIconButton
                            // From any day of the month, the day before is its last.
                            val d = shownDay ?: run { day = YearMonth.of(year, m).lengthOfMonth(); return@FilledTonalIconButton }
                            if (d > 1) {
                                day = d - 1
                            } else if (m > 1 || year > FIRST_YEAR) {
                                val prev = YearMonth.of(year, m).minusMonths(1)
                                year = prev.year; month = prev.monthValue; day = prev.lengthOfMonth()
                            }
                        }) { Icon(Icons.Filled.Remove, contentDescription = "Day before") }
                        Wheel(
                            listOf("Any") + (1..days).map { "$it" },
                            selected = shownDay ?: 0,
                            onSelect = { day = if (it == 0) null else it },
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalIconButton(onClick = {
                            val m = month ?: return@FilledTonalIconButton
                            val d = shownDay ?: 0
                            if (d < days) {
                                day = d + 1
                            } else if (m < 12 || year < thisYear) {
                                val next = YearMonth.of(year, m).plusMonths(1)
                                year = next.year; month = next.monthValue; day = 1
                            }
                        }) { Icon(Icons.Filled.Add, contentDescription = "Day after") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(PartialDate(year, month, if (month == null) null else shownDay)) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDone(null) }) { Text("No date") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Far enough back for anyone's travels, and keeps the year four digits. */
private const val FIRST_YEAR = 1900

/** "Regions · 3 of 47", the regions as chips, and a chip to add one by hand. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegionsSection(
    visited: Map<String, City?>,
    /** Visited regions that are in this country's list; [visited] may hold stale ids too. */
    counted: Int,
    total: Int,
    nameOf: (String) -> String,
    sortKey: (String) -> String,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
) {
    // Hidden only when there is nothing to pick and nothing stored to show or remove.
    if (total == 0 && visited.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Regions", fontWeight = FontWeight.Medium)
        Text(
            if (total == 0) "None listed for this country" else "$counted of $total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (rid in visited.keys.sortedBy(sortKey)) {
                InputChip(selected = false, onClick = { onOpen(rid) }, label = { Text(nameOf(rid)) })
            }
            if (total > 0) AssistChip(
                onClick = onAdd,
                label = { Text("Add region") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

/** Search the regions of one country and pick one. */
@Composable
fun RegionPicker(countryName: String, regions: List<Region>, visited: Set<String>, onPick: (Region) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = Places.fold(query.trim())
    val shown = remember(regions, q) { if (q.isEmpty()) regions else regions.filter { it.key.contains(q) } }
    SeamDialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("A region of $countryName", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(shown, key = { it.id }) { r ->
                        val done = r.id in visited
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !done) { onPick(r) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                        ) {
                            Text(r.name, modifier = Modifier.weight(1f), color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            if (done) Text("Been", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
            }
        }
    }
}

/** A stay's from and until dates, each as loose as remembered, and whether it is now. */
@Composable
fun StayDialog(stay: Trip, onDone: (Trip) -> Unit, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf(stay.date) }
    var until by remember { mutableStateOf(stay.until) }
    var now by remember { mutableStateOf(stay.now) }
    var picking by remember { mutableStateOf<String?>(null) }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When did you live here?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DateRow("From", from?.label) { picking = "from" }
                if (!now) DateRow("Until", until?.label) { picking = "until" }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { now = !now }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("I live here now", modifier = Modifier.weight(1f))
                    Switch(checked = now, onCheckedChange = { now = it })
                }
                if (now) {
                    Text(
                        "This becomes your home. Only one country can be.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            val f = from
            val u = until
            val backwards = !now && f != null && u != null && u.endsBefore(f)
            TextButton(
                onClick = { onDone(stay.copy(date = from, until = if (now) null else until, now = now)) },
                enabled = !backwards,
            ) { Text(if (backwards) "Until is before From" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    picking?.let { which ->
        DateDialog(
            initial = if (which == "from") from else until,
            onDone = { if (which == "from") from = it else until = it; picking = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun DateRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(64.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "Not set", color = if (value == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
