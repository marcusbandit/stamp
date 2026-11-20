package com.marcusrosado.stamp.ui

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.TravelStats
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.scan.ScanState
import kotlinx.coroutines.ensureActive
import kotlin.math.roundToInt

private val ContinentOrder = listOf("Europe", "Asia", "Africa", "North America", "South America", "Oceania")

@Composable
fun PassportScreen(
    atlas: Atlas,
    visits: Map<String, Visit>,
    home: String?,
    scan: ScanState,
    hasScanned: Boolean,
    onScan: () -> Unit,
    onOpenCountry: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    split: Boolean = true,
    /** Every trip, each once, for the Trips list. */
    trips: List<com.marcusrosado.stamp.data.Trip> = emptyList(),
    places: com.marcusrosado.stamp.data.Places? = null,
    onOpenTrip: (String) -> Unit = {},
    /** Changes when the Passport tab is tapped while open: the page scrolls back to its top. */
    toTop: Long = 0L,
    /** Opens the country list on what a tapped number counts. */
    onOpenList: (ListFilter) -> Unit = {},
    onShowMap: () -> Unit = {},
) {
    val palette = LocalMapPalette.current
    // Countries, each once: Hawaii counts for the United States.
    val counted = remember(atlas, visits) { atlas.countedCountries(visits) }
    val been = counted.size
    val mine = remember(atlas, visits, split, home) { countryRows(atlas, visits, split, home) }
    // Each mark counted the way the map's legend and the country list count it: a country takes
    // the strongest mark of it and its islands, so a tapped count and the list it opens agree.
    val markCounts = remember(atlas, visits, split) {
        val marks = if (split) visits.values.map { it.state } else atlas.mains.mapNotNull { atlas.markOf(it.id, visits, false) }
        marks.groupingBy { it }.eachCount()
    }
    // Newest first by year, undated last. The countries come before them, so however many
    // trips pile up, the countries stay a short scroll away.
    // Read on every pass, so an app left open over New Year moves the current stay along.
    val thisYear = java.time.Year.now().value
    val years = remember(trips, thisYear) { tripYears(trips, thisYear) }

    val journey = LocalJourney.current
    val more = rememberMoreNumbers(atlas, visits, trips, trips, home, places, journey, year = null)
    // The year a tapped year bar opened, over the passport; back closes it.
    var openYear by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val yearGroup = openYear?.let { y -> years.firstOrNull { it.year == y } }
    // A year whose last trip went, or moved to another year, has nothing left to show.
    LaunchedEffect(openYear, yearGroup) { if (openYear != null && yearGroup == null) openYear = null }
    androidx.activity.compose.BackHandler(enabled = openYear != null) { openYear = null }

    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // Only a tap that came after this page was shown scrolls it: coming back to the page with an
    // older tap still in the state must not. The tap closes an open year first, back to the top.
    val handled = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableLongStateOf(toTop) }
    LaunchedEffect(toTop) { if (toTop != handled.longValue) { handled.longValue = toTop; openYear = null; list.animateScrollToItem(0) } }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 680.dp).fillMaxSize(),
            state = list,
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Passport", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                Box(Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = "Show the map", onClick = onShowMap)) {
                    MiniMap(atlas, { id -> atlas.markOf(id, visits, split)?.let { palette.of(it) } })
                }
            }
            item {
                StatPanel(more) { interactive ->
                    CountriesPage(been, atlas.unCount, markCounts, if (interactive) onOpenList else null)
                }
            }
            item { PhotoPanel(scan, hasScanned, onScan) }
            item { Header("Continents") }
            item {
                Panel {
                    val extra = counted.mapNotNull { atlas.byId[it]?.continent }
                        .filter { it !in ContinentOrder }.distinct()
                    (ContinentOrder + extra).forEachIndexed { i, name ->
                        val total = atlas.unPerContinent[name] ?: 0
                        val n = counted.count { atlas.byId[it]?.continent == name }
                        if (i > 0) Spacer(Modifier.height(4.dp))
                        // A continent opens the list of every country in it, the ones still to go included.
                        Column(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Show the countries of $name") { onOpenList(ListFilter(continent = name)) }
                                .padding(vertical = 5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f))
                                Text(
                                    if (total > 0) "$n / $total" else "$n",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Bar(if (total > 0) n.toFloat() / total else 1f)
                        }
                    }
                }
            }
            item { Header("My countries") }
            if (mine.isEmpty()) {
                item {
                    Text(
                        "Tap a country on the map to mark it, or let a photo scan fill this in.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(mine, key = { it.first.id }) { (c, v) -> CountryRow(c, v, c.id == home, onOpenCountry) }
            if (years.isNotEmpty()) {
                item(key = "trips") { Header("Trips") }
                years.forEach { group ->
                    stickyHeader(key = "year-" + (group.year ?: "none"), contentType = "year") {
                        YearBar(group.year, group.trips.size, onOpen = group.year?.let { y -> { openYear = y } })
                    }
                    items(group.trips, key = { "trip-" + it.id }, contentType = { "trip" }) { t ->
                        TripRow(t, atlas, visits, places) { onOpenTrip(t.id) }
                    }
                }
            }
        }
        // The passport already keeps to one half of a bent screen, so the year covers just it.
        androidx.compose.animation.AnimatedContent(
            yearGroup,
            Modifier.fillMaxSize(),
            transitionSpec = {
                (androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInHorizontally { it / 8 }) togetherWith
                    (androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutHorizontally { it / 8 })
            },
            contentKey = { it?.year },
            label = "year",
        ) { group ->
            if (group?.year != null) YearPage(
                group.year, group.trips, atlas, visits, trips, home, places, split, contentPadding,
                onOpenCountry = onOpenCountry, onOpenTrip = onOpenTrip, onClose = { openYear = null },
            )
        }
    }
}

/** Home first, then the rest by name. Whole, one row per country with its strongest mark. */
private fun countryRows(atlas: Atlas, visits: Map<String, Visit>, split: Boolean, home: String?): List<Pair<Country, Visit>> =
    (if (split) visits.mapNotNull { (id, v) -> atlas.byId[id]?.let { it to v } }
    else atlas.mains.mapNotNull { c -> atlas.wholeVisit(c.id, visits)?.let { c to it } })
        .sortedWith(compareBy({ it.first.id != home }, { it.first.name }))

/** The numbers on the stat panel's pages after the first. */
private class MoreNumbers(val cities: Int, val regions: Int, val continents: Int, val continentsOf: Int, val days: Int, val km: Long)

/**
 * The numbers behind the stat panel's swipe, for every trip or for one [year]: worked out off
 * the main thread, since placing every stop of every trip is too much for a frame. The last
 * numbers stay up while new ones are worked out, so a change never blanks the panel.
 * [trips] are the ones counted for places and distance; [allTrips] give the days, which a
 * year takes only its own part of.
 */
@Composable
private fun rememberMoreNumbers(
    atlas: Atlas,
    visits: Map<String, Visit>,
    trips: List<com.marcusrosado.stamp.data.Trip>,
    allTrips: List<com.marcusrosado.stamp.data.Trip>,
    home: String?,
    places: com.marcusrosado.stamp.data.Places?,
    journey: Journey,
    year: Int?,
): MoreNumbers? {
    val state = remember { androidx.compose.runtime.mutableStateOf<MoreNumbers?>(null) }
    LaunchedEffect(atlas, visits, trips, allTrips, home, places, journey, year) {
        state.value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            moreNumbers(atlas, visits, trips, allTrips, home, places, journey, year)
        }
    }
    return state.value
}

private suspend fun moreNumbers(
    atlas: Atlas,
    visits: Map<String, Visit>,
    trips: List<com.marcusrosado.stamp.data.Trip>,
    allTrips: List<com.marcusrosado.stamp.data.Trip>,
    home: String?,
    places: com.marcusrosado.stamp.data.Places?,
    journey: Journey,
    year: Int?,
): MoreNumbers {
    // A newer pass for newer marks has started: this one stops at the next trip or place.
    val job = kotlinx.coroutines.currentCoroutineContext()
    val (cities, regions) = TravelStats.placesBeen(visits.values) { id ->
        job.ensureActive()
        places?.let { p -> p.byId[id]?.let { p.regionIdOf(it) } }
    }
    val continents = atlas.countedCountries(visits).mapNotNull { atlas.byId[it]?.continent }.toSet()
    val today = java.time.LocalDate.now()
    // Away is anywhere outside the country lived in now; with no home set, every trip is away.
    // Only trips to places been to or lived in count, never one planned to a place only wanted.
    val homeCountry = home?.let { atlas.countryOf(it) }
    val days = TravelStats.daysAbroad(
        TravelStats.counted(allTrips, journey.visits),
        abroad = { t -> job.ensureActive(); homeCountry == null || t.lands.none { atlas.countryOf(it) == homeCountry } },
        today = today,
        within = year?.let { TravelStats.yearDays(it) },
    )
    // Homes are where the user lived, not a journey, as on the map: a stay adds no distance.
    var km = 0.0
    for (t in TravelStats.counted(trips, journey.visits)) {
        job.ensureActive()
        val v = t.home?.let { journey.visits[it] }
        if (v?.state == TravelState.Lived) continue
        km += TravelStats.tripKm(
            t,
            endPoint = { id -> journey.endPoint(t, v, id)?.let { it.lat to it.lon } },
            stopPoint = { id -> journey.stopPoint(id, v) },
            today = today,
        )
    }
    return MoreNumbers(
        cities.size, regions.size,
        continents.size, maxOf(atlas.unPerContinent.size, continents.size),
        days, kotlin.math.round(km).toLong(),
    )
}

/** How many pages the stat panel swipes through. */
private const val STAT_PAGES = 5

/**
 * The passport's numbers, a page at a time: the countries first, then a swipe for cities and
 * regions, continents, days abroad and distance. Every page is the height of the first, so the
 * list below never jumps while the user swipes, and each is as sparse as the first.
 */
@Composable
private fun StatPanel(more: MoreNumbers?, first: @Composable (interactive: Boolean) -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState { STAT_PAGES }
    androidx.compose.material3.Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Box {
                // The first page once more, unseen and untouchable, so the panel takes its height
                // whichever page shows; the pages fill it.
                Box(
                    Modifier.fillMaxWidth().padding(StatPadding)
                        .alpha(0f).clearAndSetSemantics { },
                ) { Column { first(false) } }
                androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.matchParentSize()) { p ->
                    Column(Modifier.fillMaxSize().padding(StatPadding)) {
                        when (p) {
                            0 -> first(true)
                            1 -> Numbers(
                                more?.cities?.let { "%,d".format(it) }, if (more?.cities == 1) "city" else "cities",
                                more?.regions?.let { "%,d".format(it) }, if (more?.regions == 1) "region" else "regions",
                            )
                            2 -> Numbers(more?.let { "${it.continents} / ${it.continentsOf}" }, "continents")
                            3 -> Numbers(more?.days?.let { "%,d".format(it) }, if (more?.days == 1) "day abroad" else "days abroad")
                            else -> Numbers(more?.km?.let { "%,d".format(it) }, "km travelled")
                        }
                    }
                }
            }
            PageDots(STAT_PAGES, pager.currentPage, Modifier.padding(bottom = 12.dp))
        }
    }
}

private val StatPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)

/** One or two big numbers with their labels, laid out the way the countries' page has them. */
@Composable
private fun Numbers(value: String?, label: String, second: String? = null, secondLabel: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(value ?: "…", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (secondLabel != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(second ?: "…", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text(secondLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The first page: countries been to out of the world's, the share, and the bar. With
 * [markCounts] a row of each mark's count follows. With [onOpenList] a tap on a number opens the
 * list of what it counts.
 */
@Composable
private fun CountriesPage(been: Int, of: Int, markCounts: Map<TravelState, Int>?, onOpenList: ((ListFilter) -> Unit)?) {
    val palette = LocalMapPalette.current
    val share = if (of > 0) been.toFloat() / of else 0f
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).then(
            if (onOpenList != null) Modifier.clickable(onClickLabel = "Show the countries you have been to") { onOpenList(ListFilter(ListFilter.BEEN, unOnly = true)) }
            else Modifier,
        ),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$been / $of",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("countries", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${(share * 100).roundToInt()}%",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("of the world", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(12.dp))
    Bar(share)
    if (markCounts != null) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TravelState.entries.forEach { s ->
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .then(if (onOpenList != null) Modifier.clickable(onClickLabel = "Show ${s.label.lowercase()} countries") { onOpenList(ListFilter.of(s)) } else Modifier)
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(palette.of(s))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${s.label} ${markCounts[s] ?: 0}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Small dots under a pager, the one showing filled. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(
                Modifier.padding(3.dp).size(6.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

/**
 * One year of the passport, opened from its bar: the same numbers for that year alone, the
 * countries its trips went to, and the trips. A trip is in the year its bar shows it under;
 * the days abroad are the year's own days, from whichever trips they were on.
 */
@Composable
private fun YearPage(
    year: Int,
    yearTrips: List<com.marcusrosado.stamp.data.Trip>,
    atlas: Atlas,
    visits: Map<String, Visit>,
    allTrips: List<com.marcusrosado.stamp.data.Trip>,
    home: String?,
    places: com.marcusrosado.stamp.data.Places?,
    split: Boolean,
    contentPadding: PaddingValues,
    onOpenCountry: (String) -> Unit,
    onOpenTrip: (String) -> Unit,
    onClose: () -> Unit,
) {
    val ids = remember(yearTrips) { yearTrips.mapTo(HashSet()) { it.id } }
    val yearVisits = remember(visits, ids) { TravelStats.visitsOf(visits, ids) }
    val been = remember(atlas, yearVisits) { atlas.countedCountries(yearVisits).size }
    val rows = remember(atlas, yearVisits, split, home) { countryRows(atlas, yearVisits, split, home) }
    val more = rememberMoreNumbers(atlas, yearVisits, yearTrips, allTrips, home, places, LocalJourney.current, year)
    androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 680.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                        Text("$year", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                item { StatPanel(more) { CountriesPage(been, atlas.unCount, null, null) } }
                if (rows.isNotEmpty()) {
                    item { Header("Countries") }
                    items(rows, key = { "c-" + it.first.id }) { (c, v) -> CountryRow(c, v, c.id == home, onOpenCountry) }
                }
                item { Header("Trips") }
                items(yearTrips, key = { "trip-" + it.id }) { t -> TripRow(t, atlas, visits, places) { onOpenTrip(t.id) } }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/**
 * A year's bar, which stays at the top while its trips scroll under it. It is opaque and full
 * width, so it works on either page background. Its rounded corners can show the edge of a row
 * passing under it, but never its text, which sits 16 dp in from the row's edge.
 */
@Composable
private fun YearBar(year: Int?, count: Int, onOpen: (() -> Unit)? = null) {
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Show $year", onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(year?.toString() ?: "No date", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (count == 1) "1 trip" else "$count trips", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun CountryRow(c: Country, v: Visit, isHome: Boolean, onOpen: (String) -> Unit) {
    val palette = LocalMapPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(c.id) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagMark(c)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(
                    if (isHome) "Home" else null,
                    v.trips.size.takeIf { it > 0 }?.let { if (v.state == TravelState.Lived) staysLabel(it) else visitsLabel(it) },
                    v.cities.size.takeIf { it > 0 }?.let { if (it == 1) "1 city" else "$it cities" },
                    "★".repeat(v.rating).takeIf { it.isNotEmpty() },
                    v.evidence?.let { evidenceLine(it) } ?: c.continent,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (v.loved) {
            Icon(
                Icons.Filled.Favorite, contentDescription = "Loved", tint = LoveRed,
                modifier = Modifier.padding(end = 8.dp).size(18.dp),
            )
        }
        Icon(v.state.icon, contentDescription = v.state.label, tint = palette.of(v.state), modifier = Modifier.size(20.dp))
    }
}

/** The way into the photo scan, and a quiet reminder of it until one has run. */
@Composable
private fun PhotoPanel(scan: ScanState, hasScanned: Boolean, onScan: () -> Unit) {
    when {
        scan is ScanState.Running -> Panel(Modifier.clickable(onClick = onScan)) {
            Text("Scanning your photos", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (scan.total == 0) "Getting started" else
                    "%,d of %,d checked, %s so far".format(scan.checked, scan.total, countries(scan.found.size)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Bar(if (scan.total == 0) 0f else scan.checked.toFloat() / scan.total)
        }
        scan is ScanState.Done -> Panel(Modifier.clickable(onClick = onScan)) {
            Text("Your scan is ready", fontWeight = FontWeight.Medium)
            Text(
                "${countries(scan.found.size)} found. Tap to review them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        !hasScanned -> Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Find countries in your photos", fontWeight = FontWeight.Medium)
                    Text(
                        "Stamp can read where your photos were taken and fill this in for you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onScan) { Text("Scan my photos") }
        }
        else -> TextButton(onClick = onScan) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan photos again")
        }
    }
}

/** One trip: its dates, the countries it crossed as flags, and its route. */
@Composable
private fun TripRow(
    t: com.marcusrosado.stamp.data.Trip,
    atlas: Atlas,
    visits: Map<String, Visit>,
    places: com.marcusrosado.stamp.data.Places?,
    onOpen: () -> Unit,
) {
    val flags = t.lands.mapNotNull { atlas.byId[it] }.map { atlas.byId[it.country] ?: it }.distinctBy { it.id }
    val stop: (String) -> String = { id ->
        places?.byId?.get(id)?.name ?: visits.values.firstNotNullOfOrNull { it.pins[id] }?.name ?: "…"
    }
    Panel(Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(flags.joinToString(" ") { it.flag }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title(flags.joinToString(", ") { it.name }),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(rangeLabel(t.date, t.until, t.now), t.cities.joinToString("  →  ", transform = stop).ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
