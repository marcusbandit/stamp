package com.marcusrosado.stamp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.data.Places.Companion.fold

/** Every country by name, searchable. The way to mark places too small to tap on the map. */
@Composable
fun CountryListScreen(
    atlas: Atlas,
    visits: Map<String, Visit>,
    onSet: (String, TravelState?) -> Unit,
    onShowOnMap: (String) -> Unit,
    onClose: () -> Unit,
    split: Boolean,
) {
    BackHandler(onBack = onClose)
    var query by rememberSaveable { mutableStateOf("") }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    val palette = LocalMapPalette.current
    val q = fold(query.trim())
    // Split, Corsica and Hawaii are listed on their own; whole, only countries are.
    val all = if (split) atlas.countries else atlas.mains
    val shown = all
        // A part also turns up under its country's name: "France" lists Corsica and Réunion.
        .filter {
            q.isEmpty() || fold(it.name).contains(q) || it.id.lowercase() == q ||
                (it.isPart && atlas.byId[it.country]?.name?.let { n -> fold(n).contains(q) } == true)
        }
        .filter { !mineOnly || atlas.markOf(it.id, visits, split) != null }
        .sortedBy { it.name }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search countries") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !mineOnly, onClick = { mineOnly = false }, label = { Text("All ${all.size}") })
                FilterChip(selected = mineOnly, onClick = { mineOnly = true }, label = { Text("Marked ${all.count { atlas.markOf(it.id, visits, split) != null }}") })
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                items(shown, key = { it.id }) { c ->
                    val state = atlas.markOf(c.id, visits, split)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onShowOnMap(c.id) }
                            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlagMark(c)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.Medium)
                            Text(
                                if (c.isPart) "${atlas.byId[c.country]?.name ?: c.country} · ${c.continent}" else c.continent,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TravelState.entries.forEach { s ->
                            IconToggleButton(checked = state == s, onCheckedChange = { on -> onSet(c.id, if (on) s else null) }) {
                                Icon(
                                    s.icon,
                                    contentDescription = s.label,
                                    tint = if (state == s) palette.of(s) else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen search for picking the home country. "No home country" clears it. */
@Composable
fun HomePicker(atlas: Atlas, current: String?, onPick: (String?) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var query by rememberSaveable { mutableStateOf("") }
    // Typing is the quick way to find home, so the keyboard is up straight away.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val q = fold(query.trim())
    // Home is a country, never one of its islands.
    val shown = atlas.mains
        .filter { q.isEmpty() || fold(it.name).contains(q) }
        .sortedWith(compareBy({ it.id != current }, { it.name }))
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Where do you live now?") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus),
                )
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                if (current != null && q.isEmpty()) {
                    item {
                        Text(
                            "I don't live in any of these",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(null) }
                                .padding(12.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(c.id) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlagMark(c)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, fontWeight = if (c.id == current) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (c.id == current) Icon(TravelState.Lived.icon, contentDescription = "Home", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
