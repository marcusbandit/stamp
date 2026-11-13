package com.marcusrosado.stamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.marcusrosado.stamp.data.City
import com.marcusrosado.stamp.data.Places
import java.util.Locale

/**
 * Search the cities of one country and pick one. When the list does not have the place,
 * [onPin] hands over what was typed, so the user can put it on the map by hand.
 */
@Composable
fun CityPicker(
    countryName: String,
    flag: String,
    alpha2: String,
    places: Places?,
    already: Set<String>,
    onPick: (City) -> Unit,
    /** Whether a city in [already] can be picked again, as a stop the trip goes back to. */
    againOk: Boolean = true,
    onPin: (query: String) -> Unit,
    onDismiss: () -> Unit,
    /** "🇦🇹 Austria" for a country code, to label cities found outside this country. */
    countryLabel: (String) -> String = { it },
    /** Replaces "Cities in <country>"; with no country, the search covers the whole world. */
    title: String? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // A trip can carry on across a border: "Anywhere" searches every country's cities.
    var anywhere by rememberSaveable { mutableStateOf(alpha2.isEmpty()) }
    val results = remember(places, alpha2, query, anywhere) {
        if (anywhere) places?.searchAll(query).orEmpty() else places?.search(alpha2, query).orEmpty()
    }
    val focus = remember { FocusRequester() }
    val typed = query.trim()
    SeamDialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).fillMaxHeight(0.85f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (flag.isNotEmpty()) {
                        Text(flag, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        title ?: "Cities in $countryName",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .focusRequester(focus),
                    placeholder = { Text("Search a city or town") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
                    } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
                if (alpha2.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = !anywhere, onClick = { anywhere = false },
                        label = { Text("In $countryName", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = anywhere, onClick = { anywhere = true },
                        label = { Text("Anywhere") },
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        places == null -> Quiet("Loading cities")
                        anywhere && typed.length < 2 -> Quiet("Type a city in any country")
                        results.isEmpty() && typed.isEmpty() -> Quiet("No cities listed for $countryName")
                        results.isEmpty() && (alpha2.isEmpty() || anywhere) -> Quiet("No city called \"$typed\"")
                        results.isEmpty() -> NotListed(typed, onPin = { onPin(typed) })
                        else -> LazyColumn(
                            Modifier.fillMaxSize().imePadding(),
                            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
                        ) {
                            if (typed.isEmpty()) {
                                item(key = "header") { SectionLabel("Biggest first") }
                            }
                            items(results, key = { it.id }) { c ->
                                CityRow(
                                    city = c,
                                    region = if (c.country != alpha2) listOfNotNull(places.regionOf(c), countryLabel(c.country)).joinToString(" · ") else places.regionOf(c),
                                    query = typed,
                                    added = c.id in already,
                                    again = againOk,
                                    onClick = { onPick(c) },
                                )
                            }
                            // Pinning needs a country to draw; a search across the world has none.
                            if (typed.isNotEmpty() && alpha2.isNotEmpty() && !anywhere) {
                                item(key = "pin") { PinRow("Not the one? Pin \"$typed\" on the map", onClick = { onPin(typed) }) }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun CityRow(city: City, region: String?, query: String, added: Boolean, again: Boolean, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !added || again, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (added) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (added) Icons.Filled.Check else Icons.Outlined.LocationCity,
                contentDescription = null,
                tint = if (added) MaterialTheme.colorScheme.onPrimaryContainer else muted,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                highlight(city.name, query, MaterialTheme.colorScheme.primary),
                style = MaterialTheme.typography.bodyLarge,
                color = if (added) muted else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Already a stop: picking it again is going back there.
            val sub = if (added) (if (again) "On this visit · add it again" else "Already here") else region
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!added && city.population > 0) {
            Spacer(Modifier.width(12.dp))
            Text(people(city.population), style = MaterialTheme.typography.labelMedium, color = muted)
        }
    }
}

@Composable
private fun PinRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(16.dp))
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NotListed(query: String, onPin: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PushPin, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("\"$query\" isn't in the list", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = onPin) {
            Icon(Icons.Outlined.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Pin it on the map")
        }
    }
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/** The part of [name] that matches [query], in [color]. Accents are ignored, as in search. */
private fun highlight(name: String, query: String, color: Color): AnnotatedString {
    if (query.isEmpty()) return AnnotatedString(name)
    val key = Places.fold(name)
    val q = Places.fold(query)
    val at = key.indexOf(q)
    // Folding can change the length of an odd name; then there is no safe range to mark.
    if (at < 0 || key.length != name.length) return AnnotatedString(name)
    return buildAnnotatedString {
        append(name.substring(0, at))
        withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(name.substring(at, at + q.length)) }
        append(name.substring(at + q.length))
    }
}

/** "37M", "412k", "6.9k": how many people live there, at a glance. */
private fun people(n: Int): String = when {
    // The cut-offs sit where one decimal would round up to 10.0, so that reads "10M", not "10.0M".
    n >= 9_950_000 -> "${(n + 500_000) / 1_000_000}M"
    n >= 999_500 -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0)
    n >= 9_950 -> "${(n + 500) / 1000}k"
    n >= 1000 -> String.format(Locale.ROOT, "%.1fk", n / 1000.0)
    else -> "$n"
}
