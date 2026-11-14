package com.marcusrosado.stamp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.MapView
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.RegionShape
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.Visit

/** A place under the finger that is smaller than a country: a region, or a city found by name. */
sealed interface Spot {
    val countryId: String

    data class Region(override val countryId: String, val regionId: String) : Spot

    data class City(override val countryId: String, val cityId: String, val name: String, val lat: Double, val lon: Double) : Spot
}

/** The visits of a whole country: its own and those of its separate land masses. */
fun visitsOf(countryId: String, atlas: Atlas, visits: Map<String, Visit>): List<Visit> =
    (listOf(countryId) + atlas.partsOf(countryId).map { it.id }).mapNotNull { visits[it] }

/**
 * The countries the map draws as their regions: every one split into regions, and in the
 * regions view every marked country too.
 */
fun regionCountries(view: MapView, atlas: Atlas, visits: Map<String, Visit>, inView: List<String> = emptyList()): List<String> =
    atlas.mains.map { it.id }.filter { id ->
        val vs = visitsOf(id, atlas, visits)
        vs.any { it.splitRegions } || (view == MapView.Regions && (vs.isNotEmpty() || id in inView))
    }

/**
 * The countries in sight once the map is close enough to pick a region, nearest the middle
 * first and at most [MOST_IN_VIEW], from where the map rests: zoom, then south, west, north
 * and east. Zoomed further out, none: the regions view then shows only marked countries.
 */
fun countriesInView(atlas: Atlas, viewport: DoubleArray?): List<String> {
    val v = viewport ?: return emptyList()
    if (v[0] < REGIONS_FROM_ZOOM) return emptyList()
    val (south, west, north, east) = listOf(v[1], v[2], v[3], v[4])
    fun overlapsLon(a: Double, b: Double) = if (west <= east) b >= west && a <= east else b >= west || a <= east
    val midLat = (south + north) / 2
    val midLon = if (west <= east) (west + east) / 2 else ((west + east + 360) / 2).let { if (it > 180) it - 360 else it }
    return atlas.mains.filter { c -> c.maxLat >= south && c.minLat <= north && overlapsLon(c.minLon, c.maxLon) }
        .sortedBy { c -> (c.labelLat - midLat) * (c.labelLat - midLat) + (c.labelLon - midLon) * (c.labelLon - midLon) }
        .take(MOST_IN_VIEW).map { it.id }.sorted()
}

/** How close the map must be before every country in sight draws as its regions. */
const val REGIONS_FROM_ZOOM = 3.0
private const val MOST_IN_VIEW = 30

/** What the map needs to draw countries as their regions, worked out together off the main thread. */
class RegionView(
    /** The countries drawn as regions. */
    val countries: List<String>,
    /** Each region with the id of its country. */
    val shapes: List<Pair<String, RegionShape>>,
    /** Each region's mark. */
    val marks: Map<String, TravelState>,
    /** Each drawn country's own mark. */
    val countryMarks: Map<String, TravelState>,
    /** Countries done in full, outlined in gold. */
    val complete: Set<String>,
) {
    companion object {
        val EMPTY = RegionView(emptyList(), emptyList(), emptyMap(), emptyMap(), emptySet())
    }
}

/**
 * Every region's mark. A region is been to (lived in, in a lived country) when any place on a
 * visit lies in it, a city, an address, a pin, a day trip, where a trip set out from or went back
 * to, or when the user said so; else wanted when the user said so.
 */
fun buildRegionView(
    view: MapView, atlas: Atlas, visits: Map<String, Visit>, places: Places?, rs: com.marcusrosado.stamp.data.RegionShapes,
    inView: List<String> = emptyList(),
): RegionView {
    val countries = regionCountries(view, atlas, visits, inView)
    if (countries.isEmpty()) return if (view == MapView.Countries) RegionView.EMPTY.copyComplete(completeCountries(view, atlas, visits, emptyList(), emptyMap())) else RegionView.EMPTY
    val shapes = countries.flatMap { id -> val a2 = atlas.byId[id]?.alpha2.orEmpty(); if (a2.length == 2) rs.of(a2).map { id to it } else emptyList() }
    val byCountry = shapes.groupBy({ it.first }, { it.second })
    val marks = HashMap<String, TravelState>()
    for (id in countries) {
        val regions = byCountry[id].orEmpty()
        for (v in visitsOf(id, atlas, visits)) {
            val been = if (v.state == TravelState.Lived) TravelState.Lived else TravelState.Visited
            for (r in v.wantRegions) marks.putIfAbsent(r, TravelState.Want)
            val seen = HashSet<String>(v.regions)
            if (v.state.counts) {
                places?.regionsVisited(v)?.keys?.let { seen += it }
                // Every place on a visit, placed on the map, lights the region it lies in.
                val stops = v.trips.flatMap { t -> t.places + listOfNotNull(t.start, t.end) }.distinct()
                for (stop in stops) {
                    val p = placeOf(stop, v, places) ?: continue
                    regions.firstOrNull { it.contains(p.lat, p.lon) }?.let { seen += it.id }
                }
            }
            for (r in seen) marks[r] = been
            for (r in v.livedPlaces) marks[r] = TravelState.Lived
        }
    }
    val countryMarks = countries.mapNotNull { id -> atlas.markOf(id, visits, false)?.let { id to it } }.toMap()
    return RegionView(countries, shapes, marks, countryMarks, completeCountries(view, atlas, visits, shapes, marks))
}

/**
 * The countries done in full, outlined in gold. In the regions view, a country with every one
 * of its drawn regions been to; in the countries view, every country of a continent where each
 * one is visited or lived in.
 */
fun completeCountries(
    view: MapView,
    atlas: Atlas,
    visits: Map<String, Visit>,
    shapes: List<Pair<String, RegionShape>>,
    marks: Map<String, TravelState>,
): Set<String> = when (view) {
    MapView.Regions -> shapes.groupBy({ it.first }, { it.second.id })
        .filterValues { ids -> ids.isNotEmpty() && ids.all { marks[it]?.counts == true } }.keys
    MapView.Countries -> atlas.mains.groupBy { it.continent }.values
        .filter { cs -> cs.isNotEmpty() && cs.all { atlas.markOf(it.id, visits, false)?.counts == true } }
        .flatten().map { it.id }.toSet()
    MapView.Cities -> emptySet()
}

private fun RegionView.copyComplete(c: Set<String>) = RegionView(countries, shapes, marks, countryMarks, c)

/**
 * The view buttons along the bottom of the map: routes on or off, and what the map marks. Only
 * the chosen view carries its name, so the row stays short beside the main button.
 */
@Composable
fun ViewBar(view: MapView, onView: (MapView) -> Unit, routes: Boolean, onRoutes: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = cs.surfaceContainerHigh.copy(alpha = 0.94f),
        shadowElevation = 3.dp,
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val routeTint by animateColorAsState(if (routes) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.6f), label = "routes")
            IconButton(
                onClick = { onRoutes(!routes) },
                modifier = Modifier.size(44.dp).semantics { contentDescription = if (routes) "Hide routes" else "Show routes" },
            ) { Icon(Icons.Filled.Timeline, contentDescription = null, tint = routeTint) }
            Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(cs.outlineVariant))
            Spacer(Modifier.width(2.dp))
            MapView.entries.forEach { v -> ViewChoice(v, v == view) { onView(v) } }
        }
    }
}

private val MapView.icon: ImageVector
    get() = when (this) {
        MapView.Countries -> Icons.Outlined.Public
        MapView.Regions -> Icons.Outlined.GridView
        MapView.Cities -> Icons.Outlined.LocationCity
    }

@Composable
private fun ViewChoice(v: MapView, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (on) cs.secondaryContainer else cs.surfaceContainerHigh.copy(alpha = 0f), label = "bg")
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = bg, modifier = Modifier.height(44.dp).semantics { contentDescription = v.label }) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(v.icon, contentDescription = null, tint = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            AnimatedVisibility(visible = on, enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
                Text(v.label, style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/**
 * A region's or a city's card, the same as a country's: its name and where it is, the three
 * marks, and the heart and stars once it counts; a city also says how many visits it was on.
 */
@Composable
fun PlaceCard(
    flag: String,
    title: String,
    subtitle: String,
    state: TravelState?,
    onSet: (TravelState?) -> Unit,
    loved: Boolean,
    rating: Int,
    onNote: (loved: Boolean, rating: Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** How many visits a city was on, with a step either way; null for a region. */
    visits: Int? = null,
    onMore: () -> Unit = {},
    onLess: () -> Unit = {},
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (flag.isNotEmpty()) {
                Text(flag, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        StateButtons(state, onSet)
        if (state?.counts == true) {
            LoveAndRating(loved, rating, onLoved = { onNote(it, rating) }, onRating = { onNote(loved, it) })
        }
        if (visits != null && visits > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (visits == 1) "1 visit" else "$visits visits", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.FilledTonalIconButton(onClick = onLess) {
                    Icon(Icons.Filled.Remove, contentDescription = "One visit fewer")
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.FilledTonalIconButton(onClick = onMore) {
                    Icon(Icons.Filled.Add, contentDescription = "One more visit")
                }
            }
        }
    }
}
