package com.marcusrosado.stamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import com.marcusrosado.stamp.data.Airport
import com.marcusrosado.stamp.data.Airports
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Travel

/**
 * The stretch of the timeline between two stops: how the user got to the next one, named so
 * it is plain which leg it is, or an invitation to say. The line down the left carries on from
 * the stop above.
 */
@Composable
fun LegLine(ways: List<Travel>, via: List<String>, question: String, where: String, lit: Boolean, onClick: () -> Unit) {
    val palette = LocalMapPalette.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 20.dp, end = 16.dp)) {
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.width(if (lit) 3.dp else 2.dp).fillMaxHeight()
                    .background(if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (ways.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ways.forEach { w ->
                        Icon(w.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.travel(w))
                        Spacer(Modifier.width(4.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        ways.joinToString(", then ") { it.label.lowercase() }.replaceFirstChar { it.uppercase() } + " $where",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (via.isNotEmpty()) {
                    Text(
                        viaLabel(via),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.travel(Travel.Plane),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Picks how the user went from [from] to [to]. One tap picks one way. With "Several ways"
 * ticked, each tap adds the next way in order, numbered on its chip; a second tap takes it off.
 * A flight names its airports: the nearest big ones to each end to start with, changed with a
 * tap, with layovers added between them.
 */
@Composable
fun LegPicker(
    title: String,
    from: Pair<Double, Double>?,
    to: Pair<Double, Double>?,
    initial: List<Travel>,
    initialVia: List<String>,
    airports: Airports?,
    onDone: (ways: List<Travel>, via: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var ways by remember { mutableStateOf(initial) }
    // Where the flight took off and landed, and the layovers between, kept apart so picking
    // one never moves another.
    var dep by remember { mutableStateOf(initialVia.firstOrNull()) }
    var arr by remember { mutableStateOf(initialVia.takeIf { it.size >= 2 }?.last()) }
    var stops by remember { mutableStateOf(if (initialVia.size > 2) initialVia.subList(1, initialVia.size - 1) else emptyList()) }
    var several by remember { mutableStateOf(initial.size > 1) }
    // The airport being picked.
    var picking by remember { mutableStateOf<AirportSlot?>(null) }
    val palette = LocalMapPalette.current
    // A flight just picked starts out between the big airports nearest each end. One saved
    // before airports could be named keeps none until the user picks them.
    val newFlight = Travel.Plane !in initial
    LaunchedEffect(Travel.Plane in ways, airports) {
        if (newFlight && Travel.Plane in ways && dep == null && arr == null && airports != null) {
            val d = from?.let { airports.near(it, 1).firstOrNull() }?.code
            val r = to?.let { airports.near(it, 1).firstOrNull() }?.code
            dep = d
            arr = r?.takeIf { it != d }
        }
    }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            // The dialog scrolls its text itself; a second scroll in here would crash it.
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Travel.entries.forEach { w ->
                        val at = ways.indexOf(w)
                        val color = palette.travel(w)
                        FilterChip(
                            selected = at >= 0,
                            onClick = {
                                ways = when {
                                    at >= 0 -> ways - w
                                    several -> ways + w
                                    else -> listOf(w)
                                }
                            },
                            label = { Text(w.label) },
                            leadingIcon = {
                                if (several && at >= 0) {
                                    Box(
                                        Modifier.size(18.dp).clip(CircleShape).background(color),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            "${at + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.ocean,
                                        )
                                    }
                                } else {
                                    Icon(w.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = color)
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = color.copy(alpha = 0.22f),
                                selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable {
                            several = !several
                            if (!several) ways = ways.take(1)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = several, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text("Several ways", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
                if (Travel.Plane in ways && airports != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Flights", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    val tint = palette.travel(Travel.Plane)
                    AirportRow(dep?.let { airports[it] }, "Took off from", tint, removable = false, onClick = { picking = AirportSlot.Dep }, onRemove = {})
                    stops.forEachIndexed { i, code ->
                        AirportRow(
                            airports[code], "Layover", tint, removable = true,
                            onClick = { picking = AirportSlot.Stop(i) },
                            onRemove = { stops = stops.filterIndexed { k, _ -> k != i } },
                        )
                    }
                    TextButton(onClick = { picking = AirportSlot.NewStop }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add a layover")
                    }
                    AirportRow(arr?.let { airports[it] }, "Landed at", tint, removable = false, onClick = { picking = AirportSlot.Arr }, onRemove = {})
                }
            }
        },
        dismissButton = {
            if (initial.isNotEmpty()) TextButton(onClick = { onDone(emptyList(), emptyList()) }) { Text("Clear") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(onClick = {
                val d = dep; val r = arr
                onDone(ways, if (Travel.Plane in ways && d != null && r != null) listOf(d) + stops + r else emptyList())
            }) { Text("Done") }
        },
    )
    picking?.let { slot ->
        AirportSearch(
            title = when (slot) { AirportSlot.Dep -> "Took off from"; AirportSlot.Arr -> "Landed at"; else -> "Layover" },
            airports = airports ?: return@let,
            near = when (slot) { AirportSlot.Dep -> from; AirportSlot.Arr -> to; else -> null },
            onPick = { ap ->
                when (slot) {
                    AirportSlot.Dep -> dep = ap.code
                    AirportSlot.Arr -> arr = ap.code
                    AirportSlot.NewStop -> stops = stops + ap.code
                    is AirportSlot.Stop -> stops = stops.mapIndexed { k, c -> if (k == slot.at) ap.code else c }
                }
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** Which airport of a flight is being picked. */
private sealed interface AirportSlot {
    data object Dep : AirportSlot
    data object Arr : AirportSlot
    data object NewStop : AirportSlot
    data class Stop(val at: Int) : AirportSlot
}

@Composable
private fun AirportRow(ap: Airport?, role: String, tint: Color, removable: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp)) {
            Text(
                ap?.code ?: "?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (ap != null) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(role, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                ap?.name ?: "Pick an airport",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (removable) {
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove layover", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Finds an airport by code, name or town; with nothing typed, the big ones nearest [near]. */
@Composable
private fun AirportSearch(title: String, airports: Airports, near: Pair<Double, Double>?, onPick: (Airport) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val found = remember(query, near) {
        if (query.isBlank()) near?.let { airports.near(it, 8) }.orEmpty() else airports.search(query)
    }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Code, airport or town") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // A plain column: the dialog already scrolls, and a lazy list inside a scroll
                // has no height to measure against.
                Column {
                    found.forEach { ap ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(ap) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(ap.code, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ap.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(ap.town, ap.country).filter { it.isNotBlank() }.joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
