package com.marcusrosado.stamp.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Airport
import com.marcusrosado.stamp.data.Airports
import com.marcusrosado.stamp.data.Travel

/**
 * The stretch of the timeline between two stops: how the user got to the next one, named so
 * it is plain which leg it is, drawn as the map draws it with the way's icon on its way; or an
 * invitation to say. The line down the left carries on from the stop above.
 */
@Composable
fun LegLine(ways: List<Travel>, via: List<String>, question: String, where: String, lit: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 20.dp, end = 16.dp)) {
        Box(Modifier.width(40.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(
                Modifier.width(if (lit) 3.dp else 2.dp).fillMaxHeight()
                    .background(if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier.weight(1f).padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (ways.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text(
                    ways.joinToString(", then ") { it.label.lowercase() }.replaceFirstChar { it.uppercase() } + " $where",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                TravelStrip(ways, via, Modifier.fillMaxWidth().padding(end = 24.dp))
            }
        }
    }
}

/**
 * Picks how the user went from [fromName] to [toName]. Each way tapped is added in order and
 * numbered once there are several; a second tap takes it off. A flight reads like a boarding
 * pass: the airports it left and landed at, the nearest big ones to start with, changed with a
 * tap, and any layovers between.
 */
@Composable
fun LegPicker(
    fromName: String,
    toName: String,
    from: Pair<Double, Double>?,
    to: Pair<Double, Double>?,
    initial: List<Travel>,
    initialVia: List<String>,
    airports: Airports?,
    onDone: (ways: List<Travel>, via: List<String>) -> Unit,
    onDismiss: () -> Unit,
    /** True when an end is the user's home: it reads "Home", with the address under it. */
    fromHome: Boolean = false,
    toHome: Boolean = false,
) {
    var ways by remember { mutableStateOf(initial) }
    // Where the flight took off and landed, and the layovers between, kept apart so picking
    // one never moves another.
    var dep by remember { mutableStateOf(initialVia.firstOrNull()) }
    var arr by remember { mutableStateOf(initialVia.takeIf { it.size >= 2 }?.last()) }
    var stops by remember { mutableStateOf(if (initialVia.size > 2) initialVia.subList(1, initialVia.size - 1) else emptyList()) }
    var picking by remember { mutableStateOf<AirportSlot?>(null) }
    val palette = LocalMapPalette.current
    val flying = Travel.Plane in ways && airports != null
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
        title = {
            BoardingPass(
                fromName = fromName, toName = toName,
                fromHome = fromHome, toHome = toHome,
                depart = if (flying) dep?.let { airports?.get(it) } else null,
                arrive = if (flying) arr?.let { airports?.get(it) } else null,
                flying = flying,
                ways = ways,
                onDepart = { picking = AirportSlot.Dep },
                onArrive = { picking = AirportSlot.Arr },
            )
        },
        text = {
            Column {
                WayGrid(ways, onTap = { w -> ways = if (w in ways) ways - w else ways + w })
                Spacer(Modifier.height(10.dp))
                Text(
                    if (ways.size > 1) "In the order you took them. Tap one again to take it off."
                    else "Took more than one? Tap them in order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (flying) {
                    Spacer(Modifier.height(16.dp))
                    // The layovers, between takeoff and landing.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        stops.forEachIndexed { i, code ->
                            Pill(
                                text = "via $code",
                                color = palette.travel(Travel.Plane),
                                onClick = { picking = AirportSlot.Stop(i) },
                                onRemove = { stops = stops.filterIndexed { k, _ -> k != i } },
                            )
                        }
                        Pill(text = "Layover", color = MaterialTheme.colorScheme.onSurfaceVariant, add = true, onClick = { picking = AirportSlot.NewStop })
                    }
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

/**
 * The top of the picker: where the leg started on the left, where it ended on the right, and
 * the way between them moving across. On a flight the ends are the airports, big, as on a
 * boarding pass, and each one changes with a tap.
 */
@Composable
private fun BoardingPass(
    fromName: String, toName: String, fromHome: Boolean, toHome: Boolean,
    depart: Airport?, arrive: Airport?, flying: Boolean, ways: List<Travel>,
    onDepart: () -> Unit, onArrive: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PassEnd(
            big = if (flying) depart?.code ?: "?" else if (fromHome) "Home" else shortName(fromName),
            small = if (flying) depart?.town?.ifBlank { null } ?: shortName(fromName) else if (fromHome) shortName(fromName) else "From",
            flying = flying, end = false, onClick = if (flying) onDepart else null,
            // Each end takes at most a third, so the way between them always has room to move.
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 132.dp),
        )
        TravelStrip(
            ways, emptyList(),
            Modifier.weight(1f).padding(horizontal = 6.dp),
            gap = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        PassEnd(
            big = if (flying) arrive?.code ?: "?" else if (toHome) "Home" else shortName(toName),
            small = if (flying) arrive?.town?.ifBlank { null } ?: shortName(toName) else if (toHome) shortName(toName) else "To",
            flying = flying, end = true, onClick = if (flying) onArrive else null,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 132.dp),
        )
    }
}

@Composable
private fun PassEnd(big: String, small: String, flying: Boolean, end: Boolean, onClick: (() -> Unit)?, modifier: Modifier) {
    val align = if (end) Alignment.End else Alignment.Start
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        horizontalAlignment = align,
    ) {
        Text(
            big,
            style = if (flying) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (end) TextAlign.End else TextAlign.Start,
        )
        Text(
            small,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (end) TextAlign.End else TextAlign.Start,
        )
    }
}

/** A street address reads as its first part, "Example Street 12"; a town as itself. */
private fun shortName(name: String) = name.substringBefore(',').trim().ifEmpty { name }

/** Every way of travel as an even grid of tiles, four to a row, the picked ones in their colour. */
@Composable
private fun WayGrid(ways: List<Travel>, onTap: (Travel) -> Unit) {
    val palette = LocalMapPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Travel.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { w ->
                    val at = ways.indexOf(w)
                    val color = palette.travel(w)
                    Surface(
                        onClick = { onTap(w) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (at >= 0) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = if (at >= 0) BorderStroke(2.dp, color) else null,
                        modifier = Modifier.weight(1f).height(76.dp),
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.align(Alignment.Center).padding(horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(w.icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    w.short,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Its place in the order, once there are several.
                            if (at >= 0 && ways.size > 1) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).clip(CircleShape).background(color),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("${at + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = palette.ocean)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A small rounded tag: a layover to change or take off, or the one that adds a layover. */
@Composable
private fun Pill(text: String, color: Color, onClick: () -> Unit, onRemove: (() -> Unit)? = null, add: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (add) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Row(Modifier.padding(start = 12.dp, end = if (onRemove != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (add) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = if (add) FontWeight.Normal else FontWeight.Bold, color = color)
            if (onRemove != null) {
                Box(Modifier.padding(start = 4.dp).size(24.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove layover", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Which airport of a flight is being picked. */
private sealed interface AirportSlot {
    data object Dep : AirportSlot
    data object Arr : AirportSlot
    data object NewStop : AirportSlot
    data class Stop(val at: Int) : AirportSlot
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
