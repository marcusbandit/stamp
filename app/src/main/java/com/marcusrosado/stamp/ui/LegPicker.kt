package com.marcusrosado.stamp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Airport
import com.marcusrosado.stamp.data.Airports
import com.marcusrosado.stamp.data.Leg
import com.marcusrosado.stamp.data.LegPath
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.Step
import com.marcusrosado.stamp.data.Travel

/**
 * The stretch of the timeline between two stops: how the user got to the next one, named so
 * it is plain which leg it is, drawn as the map draws it with the way's icon on its way; or an
 * invitation to say. [stops] names the stops between the leg's steps. The line down the left
 * carries on from the stop above.
 */
@Composable
fun LegLine(leg: Leg?, stops: List<String?>, question: String, where: String, lit: Boolean, onClick: () -> Unit) {
    // Drawn down from the stop above as the journey reaches the stop below, like the stops' own lines.
    val drawn by androidx.compose.animation.core.animateFloatAsState(
        if (lit) 1f else 0f,
        androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "leg",
    )
    val line = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp).drawBehind {
            val x = 20.dp.toPx()
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            if (drawn > 0f) drawLine(primary, Offset(x, 0f), Offset(x, size.height * drawn), strokeWidth = 3.dp.toPx())
        },
    ) {
        Spacer(Modifier.width(52.dp))
        Column(
            Modifier.weight(1f).padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            val told = leg?.told.orEmpty()
            if (told.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(question, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Text(
                    told.joinToString(", then ") { it.label.lowercase() }.replaceFirstChar { it.uppercase() } + " $where",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                TravelStrip(leg!!.steps.map { it.way }, stops, Modifier.fillMaxWidth().padding(end = 24.dp))
            }
        }
    }
}

/**
 * Picks how the user went from [fromName] to [toName], step by step, top to bottom: where the
 * leg started, how each step went and the stop it ended at, and where the leg ended. Stops are
 * airports or towns, added, changed and taken off with a tap; a way tapped again is taken off,
 * leaving the step unsaid. A flight is town to town until the user names an airport.
 */
@Composable
fun LegPicker(
    fromName: String,
    toName: String,
    from: Pair<Double, Double>?,
    to: Pair<Double, Double>?,
    initial: List<Step>,
    airports: Airports?,
    places: Places?,
    /** Where a stop inside the leg is, and what it is called. */
    stopPoint: (String) -> Pair<Double, Double>?,
    stopName: (String) -> String?,
    onDone: (steps: List<Step>, around: Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** True when an end is the user's home: it reads "Home", with the address under it. */
    fromHome: Boolean = false,
    toHome: Boolean = false,
    initialAround: Boolean = false,
) {
    var steps by remember { mutableStateOf(initial) }
    var around by remember { mutableStateOf(initialAround) }
    // The step whose ways are open for picking.
    var open by remember { mutableStateOf<Int?>(if (initial.isEmpty()) 0 else null) }
    var picking by remember { mutableStateOf<StopSlot?>(null) }
    // Where each stop is, the leg's own ends first and last.
    fun pointAt(i: Int): Pair<Double, Double>? = when (i) {
        0 -> from
        steps.size -> to
        else -> steps[i - 1].to?.let(stopPoint)
    }
    fun isAirport(i: Int) = i in 1 until steps.size && Step.codeOf(steps[i - 1].to) != null
    // Only a flight that spans much of the globe could have gone either way.
    val farFlight = steps.indices.any { i ->
        steps[i].way == Travel.Plane && pointAt(i)?.let { x -> pointAt(i + 1)?.let { y -> LegPath.spansFar(x, y) } } == true
    }
    // A way tapped again is taken off. A flight is from town to town until the user names an
    // airport, at either end or both.
    fun setWay(i: Int, w: Travel) {
        val list = steps.ifEmpty { listOf(Step(null)) }
        steps = list.mapIndexed { k, s -> if (k == i) s.copy(way = if (s.way == w) null else w) else s }
        open = null
    }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            BoardingPass(
                fromName = fromName, toName = toName, fromHome = fromHome, toHome = toHome,
                ways = steps.map { it.way },
                stops = steps.dropLast(1).map { st -> st.to?.let { Step.codeOf(it) ?: stopName(it)?.let(::shortName) } },
            )
        },
        text = {
            Column {
                StepEnd(if (fromHome) "Home" else shortName(fromName))
                val shown = steps.ifEmpty { listOf(Step(null)) }
                shown.forEachIndexed { i, step ->
                    StepWay(
                        step.way, opened = open == i, onClick = { open = if (open == i) null else i },
                        // A flight may name the airport it took off from or landed at, either or both.
                        onTookOff = if (step.way == Travel.Plane && !isAirport(i)) { { picking = StopSlot.Before(i) } } else null,
                        onLanded = if (step.way == Travel.Plane && !isAirport(i + 1)) { { picking = StopSlot.After(i) } } else null,
                    )
                    if (open == i) {
                        Box(Modifier.padding(start = 28.dp, top = 4.dp, bottom = 8.dp)) {
                            WayGrid(step.way, onTap = { w -> setWay(i, w) })
                        }
                    }
                    if (i < shown.lastIndex) {
                        val code = Step.codeOf(step.to)
                        StepStop(
                            big = code ?: step.to?.let { stopName(it)?.let(::shortName) },
                            small = code?.let { airports?.get(it)?.name },
                            onClick = { picking = StopSlot.At(i) },
                            onRemove = {
                                val next = shown[i + 1]
                                steps = shown.take(i) + Step(next.way ?: step.way, next.to) + shown.drop(i + 2)
                                open = null
                            },
                        )
                    }
                }
                StepEnd(if (toHome) "Home" else shortName(toName))
                Spacer(Modifier.height(12.dp))
                Pill(text = "Add a stop", color = MaterialTheme.colorScheme.primary, add = true, onClick = { picking = StopSlot.New })
                if (farFlight) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { around = !around },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Flew the other way round", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = around, onCheckedChange = { around = it })
                    }
                }
            }
        },
        dismissButton = {
            if (initial.isNotEmpty()) TextButton(onClick = { onDone(emptyList(), false) }) { Text("Clear") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(onClick = { onDone(Leg.tidy(steps), around && farFlight) }) { Text("Done") }
        },
    )
    picking?.let { slot ->
        // The step the stop belongs to, for the airports nearest its ends.
        val i = when (slot) { is StopSlot.At -> slot.at; is StopSlot.Before -> slot.at; is StopSlot.After -> slot.at; StopSlot.New -> steps.lastIndex.coerceAtLeast(0) }
        StopSearch(
            airports = airports,
            // Takeoff and landing are airports and nothing else.
            places = if (slot is StopSlot.Before || slot is StopSlot.After) null else places,
            near = when (slot) {
                is StopSlot.Before -> listOfNotNull(pointAt(i))
                is StopSlot.After -> listOfNotNull(pointAt(i + 1))
                is StopSlot.At -> listOfNotNull(pointAt(i), pointAt(i + 2))
                StopSlot.New -> listOfNotNull(pointAt(i), to)
            },
            onPick = { id ->
                val list = steps.ifEmpty { listOf(Step(null)) }
                steps = when (slot) {
                    is StopSlot.At -> list.mapIndexed { k, s -> if (k == slot.at) s.copy(to = id) else s }
                    // The flight now leaves from the airport; how the user got there is unsaid.
                    is StopSlot.Before -> list.take(slot.at) + Step(null, id) + list.drop(slot.at)
                    // The flight now lands at the airport; how the user went on is unsaid.
                    is StopSlot.After -> list.take(slot.at) + list[slot.at].copy(to = id) + Step(null, list[slot.at].to) + list.drop(slot.at + 1)
                    // A new stop goes just before the leg's end: the last step now ends there.
                    StopSlot.New -> list.dropLast(1) + list.last().copy(to = id) + Step(null)
                }
                open = null
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/**
 * The top of the picker: where the leg started on the left, where it ended on the right, and
 * its steps between them, with the stops named along the way.
 */
@Composable
private fun BoardingPass(fromName: String, toName: String, fromHome: Boolean, toHome: Boolean, ways: List<Travel?>, stops: List<String?>) {
    androidx.compose.foundation.layout.BoxWithConstraints {
        // The ends take what they need, up to a little under a third each; the way between them
        // gets all the rest.
        val most = maxWidth * 0.3f
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlaceEnd(if (fromHome) "Home" else shortName(fromName), if (fromHome) shortName(fromName) else "From", end = false, modifier = Modifier.widthIn(max = most))
            TravelStrip(
                ways, stops,
                Modifier.weight(1f).padding(horizontal = 8.dp),
                gap = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            PlaceEnd(if (toHome) "Home" else shortName(toName), if (toHome) shortName(toName) else "To", end = true, modifier = Modifier.widthIn(max = most))
        }
    }
}

/** Where the leg started or ended, in the list of its steps: a ringed dot and the place. */
@Composable
private fun StepEnd(name: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Spacer(Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * How one step went, as a button on the line between two stops: the way's icon and name in its
 * colour, or How? while it is unsaid.
 */
@Composable
private fun StepWay(way: Travel?, opened: Boolean, onClick: () -> Unit, onTookOff: (() -> Unit)? = null, onLanded: (() -> Unit)? = null) {
    val palette = LocalMapPalette.current
    val line = way?.let { palette.travel(it) } ?: MaterialTheme.colorScheme.outlineVariant
    // The line is drawn behind rather than measured, so a row that wraps keeps it full height.
    Row(
        Modifier.fillMaxWidth().drawBehind {
            val x = 12.dp.toPx()
            drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(36.dp))
        val color = way?.let { palette.travel(it) } ?: MaterialTheme.colorScheme.primary
        FlowRow(
            Modifier.padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                onClick = onClick,
                shape = RoundedCornerShape(50),
                color = if (opened) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                border = if (opened) BorderStroke(1.5.dp, color) else null,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(way?.icon ?: Icons.Filled.Add, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(way?.label ?: "How?", style = MaterialTheme.typography.labelLarge, color = if (way == null) color else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (onTookOff != null) Pill(text = "Took off from", color = MaterialTheme.colorScheme.onSurfaceVariant, add = true, onClick = onTookOff)
            if (onLanded != null) Pill(text = "Landed at", color = MaterialTheme.colorScheme.onSurfaceVariant, add = true, onClick = onLanded)
        }
    }
}

/**
 * A stop between two steps: an airport's code with its name under it, or a town; Pick a stop
 * while it has none. A tap changes it, the cross takes it off.
 */
@Composable
private fun StepStop(big: String?, small: String?, onClick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                big ?: "Pick a stop", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = if (big == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (small != null) {
                Text(small, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, contentDescription = "Remove stop", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Where a leg by land or sea started or ended: the place, big, and a quiet word under it. */
@Composable
private fun PlaceEnd(big: String, small: String, end: Boolean, modifier: Modifier) {
    Column(modifier.padding(vertical = 4.dp), horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(
            big, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            small, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A street address reads as its first part, "Example Street 12"; a town as itself. */
private fun shortName(name: String) = name.substringBefore(',').trim().ifEmpty { name }

/** Every way of travel as an even grid of tiles, four to a row, the picked one in its colour. */
@Composable
private fun WayGrid(picked: Travel?, onTap: (Travel) -> Unit) {
    val palette = LocalMapPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Travel.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { w ->
                    val on = w == picked
                    val color = palette.travel(w)
                    Surface(
                        onClick = { onTap(w) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (on) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                        border = if (on) BorderStroke(2.dp, color) else null,
                        modifier = Modifier.weight(1f).height(68.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(w.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                w.short,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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

/** Which stop is being picked: one already in the leg, or a new one before its end. */
private sealed interface StopSlot {
    data object New : StopSlot
    data class At(val at: Int) : StopSlot
    /** The airport the flight of step [at] took off from. */
    data class Before(val at: Int) : StopSlot
    /** The airport the flight of step [at] landed at. */
    data class After(val at: Int) : StopSlot
}

/** One stop to pick: an airport, by its code, or a town. */
private class StopHit(val id: String, val tag: String?, val name: String, val where: String)

/**
 * Finds a stop by airport code or name, or by town; with nothing typed, the big airports
 * nearest each end of the step. Hands back the stop's id.
 */
@Composable
private fun StopSearch(airports: Airports?, places: Places?, near: List<Pair<Double, Double>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val found = remember(query, near) {
        fun ap(a: Airport) = StopHit(Step.airport(a.code), a.code, a.name, listOf(a.town, a.country).filter { it.isNotBlank() }.joinToString(", "))
        if (query.isBlank()) near.flatMap { airports?.near(it, 4).orEmpty() }.distinctBy { it.code }.map(::ap)
        else airports?.search(query, 8).orEmpty().map(::ap) +
            places?.searchAll(query, 12).orEmpty().map { c -> StopHit(c.id, null, c.name, c.country) }
    }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (places == null) "Airport" else "Stop") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(if (places == null) "Code, airport or town" else "Airport or town") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                // A plain column: the dialog already scrolls, and a lazy list inside a scroll
                // has no height to measure against.
                Column {
                    found.forEach { hit ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(hit.id) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(52.dp)) {
                                if (hit.tag != null) Text(hit.tag, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                else Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(hit.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    hit.where,
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
