package com.marcusrosado.stamp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Homes
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.TravelState

/** A place a trip could have set out from or gone back to, as offered in the picker. */
data class TripEndChoice(val id: String, val title: String, val note: String, val home: Boolean)

/**
 * The likely places a trip set out from ([start]) or went back to: the user's other homes, and
 * where the trip before it ended, or where the one after it began.
 */
fun tripEndSuggestions(trip: Trip, journey: Journey, start: Boolean): List<TripEndChoice> {
    val home = if (start) journey.homeFor(trip) else journey.homeAtEnd(trip)
    val homes = Homes.all(journey.visits).filter { it.place != home?.place }.distinctBy { it.place }.mapNotNull { h ->
        placeOf(h.place, h.visit, journey.places)?.let { TripEndChoice(h.place, it.name, if (h.now) "Home now" else "Home before", true) }
    }
    val visits = journey.visits.values.filter { it.state == TravelState.Visited }
    val trips = visits.flatMap { v -> v.trips.map { it to v } }.distinctBy { it.first.id }
        .filter { (t, _) -> t.id != trip.id && t.date != null && t.cities.isNotEmpty() }
    val date = trip.date
    val neighbour = if (date == null) null else if (start) {
        trips.filter { (t, _) -> t.date!! < date }.maxByOrNull { it.first.date!! }
    } else {
        trips.filter { (t, _) -> t.date!! > date }.minByOrNull { it.first.date!! }
    }
    val chained = neighbour?.let { (t, v) ->
        val id = if (start) t.cities.last() else t.cities.first()
        placeOf(id, v, journey.places)?.let {
            TripEndChoice(id, it.name, if (start) "Where your trip before ended" else "Where your next trip began", false)
        }
    }
    return listOfNotNull(chained) + homes
}

/**
 * Picks where a trip set out from or went back to: home at the time, which is the default, one
 * of [suggestions], or any city found by name.
 */
@Composable
fun TripEndPicker(
    title: String,
    current: String?,
    home: String?,
    suggestions: List<TripEndChoice>,
    places: Places?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val found = remember(query, places) { if (query.isBlank() || places == null) emptyList() else places.searchAll(query, 30) }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Choice(Icons.Outlined.Home, "Home", home ?: "Where you lived then", current == null) { onPick(null) }
                suggestions.forEach { c ->
                    Choice(if (c.home) Icons.Outlined.Home else Icons.Outlined.Place, c.title, c.note, current == c.id) { onPick(c.id) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Somewhere else") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (found.isNotEmpty()) {
                    // A plain column: the dialog already scrolls its text.
                    Column(Modifier.padding(top = 4.dp)) {
                        found.forEach { c ->
                            Choice(Icons.Outlined.Place, c.name, listOfNotNull(places?.regionOf(c), c.country).joinToString(", "), current == c.id) { onPick(c.id) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(icon: ImageVector, title: String, note: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
