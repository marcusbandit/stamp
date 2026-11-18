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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
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

/**
 * What the country list shows: every country, any marked one, or one mark, and optionally one
 * continent. A count tapped anywhere in the app opens the list on what it counted.
 */
@androidx.compose.runtime.Immutable
data class ListFilter(
    val mark: String = ALL,
    val continent: String? = null,
    /** UN countries only, the way the passport's total counts them. */
    val unOnly: Boolean = false,
) {
    fun keeps(state: TravelState?, continent: String, un: Boolean = true): Boolean =
        (mark == ALL || (mark == MARKED && state != null) || (mark == BEEN && state?.counts == true) || state?.name == mark) &&
            (this.continent == null || this.continent == continent) && (!unOnly || un)

    companion object {
        const val ALL = "all"
        const val MARKED = "marked"
        /** Visited or lived: what the passport counts. */
        const val BEEN = "been"
        fun of(state: TravelState, continent: String? = null) = ListFilter(state.name, continent)
        val Saver = androidx.compose.runtime.saveable.Saver<ListFilter, String>(
            save = { "${it.mark}|${it.continent.orEmpty()}|${it.unOnly}" },
            restore = { s -> s.split('|').let { ListFilter(it[0], it.getOrNull(1)?.ifEmpty { null }, it.getOrNull(2) == "true") } },
        )
    }
}

/** Every country by name, searchable. The way to mark places too small to tap on the map. */
@Composable
fun CountryListScreen(
    atlas: Atlas,
    visits: Map<String, Visit>,
    onSet: (String, TravelState?) -> Unit,
    onShowOnMap: (String) -> Unit,
    onClose: () -> Unit,
    split: Boolean,
    start: ListFilter = ListFilter(),
    /** Changes on every count tapped, so tapping the same one again starts the list over. */
    opened: Long = 0L,
) {
    val undoable = LocalUndo.current != null
    BackHandler(onBack = onClose)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable(start, opened, stateSaver = ListFilter.Saver) { mutableStateOf(start) }
    // Opened to search, the keyboard is up at once; opened on a count, the list is the point.
    val focus = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (start == ListFilter()) runCatching { focus.requestFocus() } }
    // A change of mark that would throw something away, waiting for the user to say yes.
    var asking by remember { mutableStateOf<Triple<String, TravelState?, Loss>?>(null) }
    asking?.let { (id, s, loss) ->
        LossDialog(loss, onConfirm = { asking = null; onSet(id, s) }, onDismiss = { asking = null })
    }
    val palette = LocalMapPalette.current
    val q = fold(query.trim())
    // Split, Corsica and Hawaii are listed on their own; whole, only countries are.
    val all = if (split) atlas.countries else atlas.mains
    // Names folded once and marks read once, not again on every keystroke.
    val keys = remember(all) {
        all.sortedBy { it.name }.map { com.marcusrosado.stamp.data.CountrySearch.Key(it, if (it.isPart) atlas.byId[it.country]?.name else null) }
    }
    val marks = remember(all, visits, split) { all.associate { it.id to atlas.markOf(it.id, visits, split) } }
    val continents = remember(all) {
        val order = listOf("Europe", "Asia", "Africa", "North America", "South America", "Oceania")
        all.map { it.continent }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
    }
    val shown = remember(keys, marks, q, filter) {
        keys.filter { filter.keeps(marks[it.c.id], it.c.continent, it.c.un) && com.marcusrosado.stamp.data.CountrySearch.matches(it, q) }.map { it.c }
    }
    // Each mark's count within the continent picked, so the chips say what a tap will show.
    val counts = remember(all, marks, filter.continent, filter.unOnly) {
        val inView = all.filter { (filter.continent == null || it.continent == filter.continent) && (!filter.unOnly || it.un) }
        listOf(ListFilter.ALL, ListFilter.BEEN).plus(TravelState.entries.map { it.name })
            .associateWith { mark -> inView.count { ListFilter(mark).keeps(marks[it.id], it.continent) } }
    }
    fun count(mark: String) = counts[mark] ?: 0

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
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                    trailingIcon = if (query.isEmpty()) null else {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
                    },
                    modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(focus),
                )
            }
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The continent first: opened on one from the passport, it shows which.
                item(key = "continent") {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        FilterChip(
                            selected = filter.continent != null,
                            onClick = { open = true },
                            label = { Text(filter.continent ?: "Continent") },
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        )
                        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Every continent") },
                                onClick = { filter = filter.copy(continent = null); open = false },
                            )
                            continents.forEach { name ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { filter = filter.copy(continent = name); open = false },
                                )
                            }
                        }
                    }
                }
                val marksShown = listOf(ListFilter.ALL to "All", ListFilter.BEEN to "Been") + TravelState.entries.map { it.name to it.label }
                items(marksShown, key = { it.first }) { (mark, label) ->
                    FilterChip(
                        selected = filter.mark == mark,
                        onClick = { filter = filter.copy(mark = mark, unOnly = filter.unOnly && mark == ListFilter.BEEN) },
                        label = { Text("$label ${count(mark)}") },
                    )
                }
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
                            IconToggleButton(checked = state == s, onCheckedChange = { on ->
                                val to = if (on) s else null
                                // Whole, clearing a country clears its parts too, so their loss counts.
                                val parts = if (split) emptyList() else atlas.partsOf(c.id).mapNotNull { visits[it.id] }
                                val loss = if (undoable) null else lossOf(c.id, c.name, visits[c.id], to, parts)
                                if (loss != null) asking = Triple(c.id, to, loss) else onSet(c.id, to)
                            }) {
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
