package com.marcusrosado.stamp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
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
fun regionCountries(view: MapView, atlas: Atlas, visits: Map<String, Visit>): List<String> =
    atlas.mains.map { it.id }.filter { id ->
        val vs = visitsOf(id, atlas, visits)
        vs.any { it.splitRegions } || (view == MapView.Regions && vs.isNotEmpty())
    }

/** Each region's mark: visited (lived in a lived country) through a city or by hand, else wanted. */
fun regionMarks(countries: List<String>, atlas: Atlas, visits: Map<String, Visit>, places: Places?): Map<String, TravelState> {
    val out = HashMap<String, TravelState>()
    for (id in countries) {
        for (v in visitsOf(id, atlas, visits)) {
            val been = if (v.state == TravelState.Lived) TravelState.Lived else TravelState.Visited
            for (r in v.wantRegions) out.putIfAbsent(r, TravelState.Want)
            val seen = places?.regionsVisited(v)?.keys ?: v.regions
            if (v.state.counts) for (r in seen) out[r] = been else for (r in v.regions) out[r] = been
        }
    }
    return out
}

/**
 * The countries done in full, outlined in gold. In the regions view, a country with every one
 * of its drawn regions visited; in the countries view, every country of a continent where each
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
 * A region or a found city, floating over the map: its name and where it is, and the marks it
 * can take, one tap each. The map stays live under it, so tapping region after region marks
 * them one after another.
 */
@Composable
fun SpotPanel(
    title: String,
    subtitle: String,
    state: TravelState?,
    /** The marks it can take, in order; a city takes only Want to go. */
    choices: List<TravelState>,
    onState: (TravelState?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMapPalette.current
    val cs = MaterialTheme.colorScheme
    Surface(modifier = modifier.widthIn(max = 480.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerHigh, shadowElevation = 6.dp) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { s ->
                    val on = state == s
                    val color = palette.of(s)
                    Surface(
                        onClick = { onState(if (on) null else s) },
                        shape = RoundedCornerShape(50),
                        color = if (on) color else cs.surfaceContainerHighest,
                        border = if (on) null else BorderStroke(1.dp, cs.outlineVariant),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (s == TravelState.Want) Icons.Outlined.Bookmark else Icons.Outlined.Flag,
                                contentDescription = null, modifier = Modifier.size(18.dp),
                                tint = if (on) palette.ocean else color,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (s == TravelState.Want) "Want to go" else "Been here",
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                color = if (on) palette.ocean else cs.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
