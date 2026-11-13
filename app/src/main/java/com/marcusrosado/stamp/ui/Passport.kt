package com.marcusrosado.stamp.ui

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
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.scan.ScanState
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
) {
    val palette = LocalMapPalette.current
    // Countries, each once: Hawaii counts for the United States.
    val counted = remember(atlas, visits) { atlas.countedCountries(visits) }
    val been = counted.size
    val share = been.toFloat() / atlas.unCount
    // Home first, then the rest by name. Whole, one row per country with its strongest mark.
    val mine = (if (split) visits.mapNotNull { (id, v) -> atlas.byId[id]?.let { it to v } }
    else atlas.mains.mapNotNull { c -> atlas.wholeVisit(c.id, visits)?.let { c to it } })
        .sortedWith(compareBy({ it.first.id != home }, { it.first.name }))

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
                Text("Passport", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                MiniMap(atlas, { id -> atlas.markOf(id, visits, split)?.let { palette.of(it) } })
            }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$been / ${atlas.unCount}",
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
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TravelState.entries.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Swatch(palette.of(s))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "${s.label} ${mine.count { it.second.state == s }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
                        if (i > 0) Spacer(Modifier.height(14.dp))
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
            val shownTrips = trips.filter { it.cities.isNotEmpty() || it.date != null }.reversed()
            if (shownTrips.isNotEmpty()) {
                item { Header("Trips") }
                items(shownTrips, key = { "trip-" + it.id }) { t ->
                    TripRow(t, atlas, visits, places) { onOpenTrip(t.id) }
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
                    flags.joinToString(", ") { it.name },
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
