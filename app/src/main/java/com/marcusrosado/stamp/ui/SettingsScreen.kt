package com.marcusrosado.stamp.ui

import com.marcusrosado.stamp.data.placeIdOf
import com.marcusrosado.stamp.data.Note
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.produceState
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Lookup
import com.marcusrosado.stamp.data.Distance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.foundation.layout.FlowRow
import com.marcusrosado.stamp.Posture
import com.marcusrosado.stamp.data.Here
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.isPin
import com.marcusrosado.stamp.data.Coordinates
import com.marcusrosado.stamp.data.City
import com.marcusrosado.stamp.data.Pin
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.Places
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.OutlinedTextField
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.marcusrosado.stamp.BuildConfig
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.visitsCsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.cos

/** The pages Settings opens into. */
private enum class Page(val title: String, val icon: ImageVector) {
    Home("Where you live", Icons.Outlined.Home),
    Appearance("Appearance", Icons.Outlined.Palette),
    Map("Map", Icons.Outlined.Map),
    Memories("Memories", Icons.Outlined.PhotoLibrary),
    Backup("Backup & export", Icons.Outlined.Backup),
    About("About Stamp", Icons.Outlined.Info),
}

/** One thing search can find: its name, where it lives, and the words that lead to it. */
private class Found(
    val title: String,
    val page: Page?,
    val words: String,
    val action: (() -> Unit)? = null,
    val icon: ImageVector = page?.icon ?: Icons.Outlined.Home,
)

/**
 * Settings, laid out the way Android's own Settings app is: a search bar, a card for you,
 * then groups of rows that open into pages with a large title that shrinks as you scroll.
 * Pages slide in sideways and follow a predictive back gesture out.
 */
@Composable
fun SettingsScreen(state: AppState, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf<Page?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    val access = rememberPhotoAccess()
    val home = state.home?.let { state.atlas.byId[it] }
    val been = remember(state.atlas, state.visits) { state.atlas.countedCountries(state.visits).size }

    fun summary(p: Page): String? = when (p) {
        Page.Appearance -> Looks.firstOrNull { it.id == state.look }?.name ?: "Match phone"
        Page.Map -> if (state.split) "Islands as separate places" else "Islands as part of their country"
        Page.Memories -> access.label
        Page.Backup -> if (state.lastBackup > 0) "Last backup ${date(state.lastBackup)}" else "Not backed up yet"
        Page.About -> "Version ${BuildConfig.VERSION_NAME}"
        Page.Home -> null
    }
    val found = listOf(
        Found("Theme", Page.Appearance, "look colours colors dark light wallpaper match phone font"),
        Found("Islands and overseas parts", Page.Map, "land mass split corsica hawaii reunion places"),
        Found("Find countries in your photos", Page.Memories, "scan camera gallery"),
        Found("Photo access", Page.Memories, "permission allow gallery"),
        Found("Photos on the map", Page.Memories, "previews pictures zoom"),
        Found("Routes between cities", Page.Map, "lines dashed trip path plane train bus walk bike car ferry travel"),
        Found("Follow railways and roads", Page.Map, "rail tracks motorway highway download real line"),
        Found("Mark the country I'm in", Page.Map, "location gps where am i here current country"),
        Found("Save a backup", Page.Backup, "export file json copy"),
        Found("Restore from a backup", Page.Backup, "import file json"),
        Found("Spreadsheet", Page.Backup, "export csv excel sheets"),
        Found("Where you live", Page.Home, "home country lived address moved"),
        Found("Addresses", Page.Home, "home address street house apartment flat lived moved"),
        Found("Introduction", null, "help intro tour welcome", state.onIntro, Icons.Outlined.School),
        Found("Version", Page.About, "about credits natural earth geonames openstreetmap ourairports airports licence"),
    )

    val top: @Composable (Page?, (Page) -> Unit, Modifier) -> Unit = { selected, onOpen, m ->
        TopList(
            query, { query = it }, found, selected, ::summary, home, been,
            onIntro = state.onIntro,
            onOpen = { query = ""; onOpen(it) }, contentPadding, m,
        )
    }
    val page: @Composable (Page, (() -> Unit)?) -> Unit = { p, back ->
        PageFrame(p.title, back, contentPadding) {
            when (p) {
                Page.Appearance -> AppearancePage(state)
                Page.Map -> MapPage(state)
                Page.Memories -> MemoriesPage(state, access)
                Page.Backup -> BackupPage(state)
                Page.About -> AboutPage()
                Page.Home -> HomePage(state)
            }
        }
    }

    // The screen sits under the status bar, so a seam across it is that much higher here.
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hinge = LocalHinge.current?.let { if (it.vertical) it else it.copy(start = it.start - statusTop, end = it.end - statusTop) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val listWidth = (maxWidth * 0.42f).coerceIn(300.dp, 400.dp)
        // Decided by the screen, never by the bend, so bending never swaps the layout.
        if (state.posture == Posture.Unfolded || maxWidth >= 720.dp) {
            // Wide, flat or bent: the list stays and the page beside it changes, like Settings
            // on a tablet. Bending moves the line between them to the seam; nothing reopens.
            val shown = open ?: Page.Appearance
            Split(
                hinge, flatFirst = listWidth,
                first = { top(shown, { open = it }, Modifier.fillMaxSize()) },
                second = {
                    AnimatedContent(shown, transitionSpec = { sharedAxis(forward = targetState.ordinal >= initialState.ordinal) }, label = "page") { p ->
                        page(p, null)
                    }
                },
            )
        } else {
            // Narrow: one at a time. Back follows the finger, then the page slides away.
            var back by remember { mutableFloatStateOf(0f) }
            PredictiveBackHandler(enabled = open != null) { progress ->
                // The page leaves from where the finger let go, so `back` is reset only on a
                // cancel or when the next page opens, never as this one starts to slide away.
                try {
                    progress.collect { back = it.progress }
                    open = null
                } catch (e: CancellationException) {
                    back = 0f
                    throw e
                }
            }
            AnimatedContent(open, transitionSpec = { sharedAxis(forward = targetState != null) }, label = "settings") { p ->
                if (p == null) {
                    top(null, { back = 0f; open = it }, Modifier.fillMaxSize())
                } else {
                    Box(
                        Modifier.fillMaxSize().graphicsLayer {
                            val s = 1f - 0.1f * back
                            scaleX = s; scaleY = s
                            translationX = back * 24.dp.toPx()
                            shape = RoundedCornerShape((back * 32).dp)
                            clip = back > 0f
                        },
                    ) { page(p) { open = null } }
                }
            }
        }
    }
}

/** Material's shared axis on X: a short slide one way while the old page fades out and the new one in. */
private fun sharedAxis(forward: Boolean) = run {
    val dir = if (forward) 1 else -1
    (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { dir * it / 8 } + fadeIn(tween(210, 90, LinearOutSlowInEasing))) togetherWith
        (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -dir * it / 8 } + fadeOut(tween(90)))
}

// ---------------------------------------------------------------- the top list

@Composable
private fun TopList(
    query: String,
    onQuery: (String) -> Unit,
    found: List<Found>,
    selected: Page?,
    summary: (Page) -> String?,
    home: Country?,
    been: Int,
    onIntro: () -> Unit,
    onOpen: (Page) -> Unit,
    padding: PaddingValues,
    modifier: Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Search Settings") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
            modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)),
        )
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) {
            val hits = found.filter { it.title.lowercase().contains(q) || it.words.contains(q) || it.page?.title?.lowercase()?.contains(q) == true }
            if (hits.isEmpty()) {
                Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            } else {
                Cards(
                    hits.map { f ->
                        Entry(f.title, f.page?.title ?: "Settings", f.icon) {
                            if (f.action != null) { onQuery(""); f.action.invoke() } else f.page?.let(onOpen)
                        }
                    },
                    selected = null,
                )
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }
        // You: where home is and how far you have been, the way Settings opens on your account.
        Cards(
            listOf(
                Entry(
                    home?.name ?: "Where do you live?",
                    "Home · " + if (been == 1) "1 country" else "$been countries",
                    Icons.Outlined.Home,
                    lead = home?.flag?.ifEmpty { null },
                    page = Page.Home,
                ) { onOpen(Page.Home) },
            ),
            selected,
        )
        Cards(listOf(Page.Appearance, Page.Map).map { p -> Entry(p.title, summary(p), p.icon, page = p) { onOpen(p) } }, selected)
        Cards(listOf(Page.Memories, Page.Backup).map { p -> Entry(p.title, summary(p), p.icon, page = p) { onOpen(p) } }, selected)
        Cards(
            listOf(
                Entry("Introduction", null, Icons.Outlined.School, onClick = onIntro),
                Entry(Page.About.title, summary(Page.About), Page.About.icon, page = Page.About) { onOpen(Page.About) },
            ),
            selected,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private class Entry(
    val title: String,
    val summary: String?,
    val icon: ImageVector,
    val lead: String? = null,
    val page: Page? = null,
    val onClick: () -> Unit,
)

/**
 * A group of rows as Android draws them now: one rounded block whose rows sit a hair apart,
 * big corners on the outside of the group and small ones inside it.
 */
@Composable
private fun Cards(entries: List<Entry>, selected: Page?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEachIndexed { i, e ->
            val on = e.page != null && e.page == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(groupShape(i, entries.size))
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(onClick = e.onClick)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    if (e.lead != null) {
                        Text(e.lead, style = MaterialTheme.typography.titleLarge)
                    } else {
                        Icon(e.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.summary != null) {
                        Text(e.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun groupShape(i: Int, n: Int): RoundedCornerShape {
    val big = 24.dp
    val small = 4.dp
    val top = if (i == 0) big else small
    val bottom = if (i == n - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

// ---------------------------------------------------------------- pages

/** A page: a large title that shrinks into the bar as the rows scroll under it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageFrame(title: String, onBack: (() -> Unit)?, padding: PaddingValues, content: @Composable () -> Unit) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Column(Modifier.fillMaxSize().nestedScroll(behavior.nestedScrollConnection)) {
        LargeTopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface,
            ),
            scrollBehavior = behavior,
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
                content()
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** A section header in the accent colour, as Settings marks its groups. */
@Composable
private fun Category(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
    )
}

/** A row: its name, and on the second line what it is set to. */
@Composable
private fun Pref(title: String, summary: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** A row with a switch, set off by a thin line the way Settings does it. */
@Composable
private fun SwitchPref(title: String, summary: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Pref(title, summary, onClick = { onChange(!on) }) {
        VerticalDivider(Modifier.height(32.dp).padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Switch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun AppearancePage(state: AppState) {
    Category("Theme")
    Box(Modifier.padding(horizontal = 16.dp)) { LookPicker(state.atlas, state.look, state.onLook) }
}

@Composable
private fun MapPage(state: AppState) {
    val palette = LocalMapPalette.current
    Category("Islands and overseas parts")
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PartsChoice(
            state.atlas, "Separate places", selected = state.split, modifier = Modifier.weight(1f),
            colours = mapOf("USA" to palette.visited, "USA-USK" to palette.land, "USA-USH" to palette.land),
        ) { state.onSplit(true) }
        PartsChoice(
            state.atlas, "Part of the country", selected = !state.split, modifier = Modifier.weight(1f),
            colours = mapOf("USA" to palette.visited, "USA-USK" to palette.visited, "USA-USH" to palette.visited),
        ) { state.onSplit(false) }
    }
    Category("On the map")
    SwitchPref("Routes between cities", null, state.routes, state.onRoutes)
    if (state.routes) TravelLegend(Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp))
    SwitchPref("Follow railways and roads", null, state.followLines, state.onFollowLines)
    // Where the phone is: on asks for the rough location if the phone has not allowed it yet.
    val context = LocalContext.current
    val askHere = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state.onHere(granted)
        // Refused for good, Android no longer asks: the app's own settings page is the way on.
        val activity = context as? android.app.Activity
        if (!granted && activity?.shouldShowRequestPermissionRationale(Here.PERMISSION) == false) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }
    }
    SwitchPref("Mark the country I'm in", null, state.here && Here.granted(context)) { on ->
        if (on && !Here.granted(context)) askHere.launch(Here.PERMISSION) else state.onHere(on)
    }
}

/** One of the two ways to treat islands, drawn on the United States with Alaska and Hawaii, with a radio under it. */
@Composable
private fun PartsChoice(atlas: Atlas, label: String, selected: Boolean, colours: Map<String, Color>, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalMapPalette.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f).background(palette.ocean)) {
            // The United States with Alaska and Hawaii, on an equirectangular view squeezed for
            // their latitude. The Aleutians that cross the date line are left out of the picture.
            val west = -176.0; val east = -64.0; val south = 17.0; val north = 73.0
            val k = cos(Math.toRadians((south + north) / 2))
            val spanX = (east - west) * k
            val spanY = north - south
            val scale = minOf(size.width / spanX, size.height / spanY).toFloat() * 0.9f
            val ox = (size.width - (spanX * scale).toFloat()) / 2
            val oy = (size.height - (spanY * scale).toFloat()) / 2
            fun pt(lon: Double, lat: Double) = Offset(ox + ((lon - west) * k * scale).toFloat(), oy + ((north - lat) * scale).toFloat())
            for ((id, colour) in colours) {
                val c = atlas.byId[id] ?: continue
                val path = Path().apply { fillType = PathFillType.EvenOdd }
                for (s in c.shapes) for (r in listOf(s.outer) + s.holes) {
                    if (r.xs.average() > 0) continue
                    for (i in r.xs.indices) {
                        val p = pt(r.xs[i], r.ys[i])
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                }
                drawPath(path, colour)
                drawPath(path, palette.border, style = Stroke(width = 1f))
            }
        }
        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 12.dp))
        }
    }
}

@Composable
private fun MemoriesPage(state: AppState, access: PhotoAccess) {
    val context = LocalContext.current
    Category("Your photos")
    Pref("Find countries in your photos", onClick = state.onOpenNewScan)
    Pref("Photo access", access.value, onClick = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    })
    Category("On the map")
    SwitchPref("Photos on the map", "From city zoom in", state.mapPhotos, state.onMapPhotos)
}

@Composable
private fun BackupPage(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, Int>?>(null) }
    fun say(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(state.app.visits.backup().toByteArray()) } != null }.getOrDefault(false)
            }
            if (ok) { state.app.backedUp(System.currentTimeMillis()); say("Backup saved") } else say("Couldn't save the backup there")
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            val n = text?.let { state.app.visits.backupSize(it) }
            if (text == null || n == null) say("That file isn't a Stamp backup") else pending = text to n
        }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(visitsCsv(state.visits, state.atlas, state.places).toByteArray()) } != null
                }.getOrDefault(false)
            }
            say(if (ok) "Spreadsheet saved" else "Couldn't save the spreadsheet there")
        }
    }

    val today = LocalDate.now()
    Category("Backup")
    Pref("Save a backup", if (state.lastBackup > 0) "Last saved ${date(state.lastBackup)}" else "Never saved", onClick = { save.launch("stamp-backup-$today.json") })
    Pref("Restore from a backup", onClick = { open.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
    Category("Export")
    Pref("Spreadsheet", "CSV, one row per place", onClick = { csv.launch("stamp-places-$today.csv") })

    val restoring = rememberCoroutineScope()
    pending?.let { (text, n) ->
        val now = state.visits.size
        SeamAlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = { Text("The ${places(now)} on this phone are replaced by the ${places(n)} in the backup.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    // Reading and saving a large backup is file work: off the main thread.
                    restoring.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { state.app.visits.restore(text) }
                        say(if (ok) "Backup restored" else "That file isn't a Stamp backup")
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

private fun places(n: Int) = if (n == 1) "1 place" else "$n places"

@Composable
private fun AboutPage() {
    Category("Stamp")
    Pref("Version", BuildConfig.VERSION_NAME)
    // Crash reports stay on the phone; sharing them is the user's own step.
    val context = LocalContext.current
    var crashes by remember { mutableStateOf(0) }
    val clearing = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        crashes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.marcusrosado.stamp.CrashLog.reports(context).size }
    }
    if (crashes > 0) {
        Pref(
            "Share crash reports", if (crashes == 1) "1 saved" else "$crashes saved",
            onClick = {
                val text = com.marcusrosado.stamp.CrashLog.text(context)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Stamp crash reports").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share crash reports"))
            },
        ) {
            IconButton(onClick = {
                clearing.launch(kotlinx.coroutines.Dispatchers.IO) { com.marcusrosado.stamp.CrashLog.clear(context) }
                crashes = 0
            }) { Icon(Icons.Filled.Delete, contentDescription = "Delete crash reports") }
        }
    }
    Pref("Your data", "Stays on this phone")
    Category("Map data")
    Pref("Country shapes", "Natural Earth, public domain")
    Pref("Cities and regions", "GeoNames, CC BY 4.0")
    Pref("Airports", "OurAirports, public domain")
    Pref("City boundaries", "© OpenStreetMap contributors, ODbL")
    Pref("Railways and roads", "© OpenStreetMap contributors, ODbL")
}

// ---------------------------------------------------------------- photo access

/** [label] stands alone on the Settings list; [value] sits under the Photo access row. */
private enum class PhotoAccess(val label: String, val value: String) {
    Allowed("Photo access allowed", "Allowed"),
    Limited("Some photos allowed", "Some photos"),
    Off("No photo access", "Not allowed"),
}

/** What Android currently lets Stamp see, read again whenever the screen comes back. */
@Composable
private fun rememberPhotoAccess(): PhotoAccess {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(o)
        onDispose { lifecycle.removeObserver(o) }
    }
    return remember(tick) {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        when {
            Build.VERSION.SDK_INT >= 33 && has(android.Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.Allowed
            Build.VERSION.SDK_INT >= 34 && has(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Limited
            Build.VERSION.SDK_INT < 33 && has(android.Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.Allowed
            else -> PhotoAccess.Off
        }
    }
}

// ---------------------------------------------------------------- where you live

/**
 * Home: the country first, then every address lived at there, then the other countries to
 * move home to. Addresses are pins on the stay that is going on now, so they show on the map
 * and in the country's timeline like any other stop.
 */
@Composable
private fun HomePage(state: AppState) {
    val home = state.home?.let { state.atlas.byId[it] }
    val country = home?.let { state.atlas.byId[state.atlas.countryOf(it.id)] }
    val visit = state.home?.let { state.visits[it] }
    val stay = visit?.trips?.firstOrNull { it.now }
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }

    if (home != null && country != null) {
        Category("Home")
        HomeRow(country, selected = true, onClick = {})
        Category("Addresses")
        val stops = stay?.cities.orEmpty()
        // A timeline, newest first: the homes lived in now on top, then the rest by when they
        // ended, undated ones last. Several can be "now" at once, say two parents' homes.
        val ordered = stops.sortedWith(
            compareByDescending<String> { stay?.left?.get(it) == null && stay?.arrived?.get(it) != null }
                .thenByDescending { (stay?.left?.get(it) ?: stay?.arrived?.get(it))?.key }
        )
        ordered.forEachIndexed { i, id ->
            val place = placeOf(id, visit, state.places) ?: return@forEachIndexed
            val from = stay?.arrived?.get(id)
            val to = stay?.left?.get(id)
            val now = to == null && from != null
            HomeEntry(
                name = place.name,
                address = place.own.takeIf { it != place.name },
                dates = when {
                    from == null && to == null -> null
                    now -> "Since ${from!!.label}"
                    else -> rangeLabel(from, to)
                },
                now = now,
                first = i == 0,
                last = i == ordered.size - 1,
                onClick = { editing = id },
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable { adding = true }.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(20.dp))
            Text(
                if (stops.isEmpty()) "Add your address" else "Add another address",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // Moving home changes a country's mark and ends the stay now going on, so it asks.
    var moving by remember { mutableStateOf<Country?>(null) }
    var leaving by remember { mutableStateOf(false) }
    moving?.let { c ->
        LossDialog(
            Loss("Move home to ${c.name}?", if (home != null) "${country?.name ?: "Your home country"} becomes a place you lived." else "${c.name} becomes where you live.", "Move"),
            onConfirm = { state.app.visits.setHome(c.id); query = ""; moving = null },
            onDismiss = { moving = null },
        )
    }
    if (leaving) {
        LossDialog(
            Loss("You don't live in any of these?", "${country?.name ?: "Your home country"} becomes a place you lived.", "Yes"),
            onConfirm = { state.app.visits.setHome(null); leaving = false },
            onDismiss = { leaving = false },
        )
    }
    Category(if (home == null) "Where do you live?" else "Moved country?")
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Search countries") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    Spacer(Modifier.height(8.dp))
    val q = Places.fold(query.trim())
    val currentCountry = country?.id
    state.atlas.mains
        .filter { it.id != currentCountry && (q.isEmpty() || Places.fold(it.name).contains(q)) }
        .sortedBy { it.name }
        .forEach { c -> HomeRow(c, selected = false) { if (home == null) { state.app.visits.setHome(c.id); query = "" } else moving = c } }
    if (home != null && q.isEmpty()) {
        Row(
            Modifier.fillMaxWidth().clickable { leaving = true }.padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text("I don't live in any of these", color = MaterialTheme.colorScheme.primary)
        }
    }

    if (adding && home != null && country != null) {
        AddressDialog(
            country = home,
            places = state.places,
            // Where a street typed on its own goes: by the latest address or town on the stay.
            near = stay?.cities?.reversed()?.firstNotNullOfOrNull { placeOf(it, visit, state.places) }?.let { it.lat to it.lon },
            onSave = { pin ->
                state.details.onAddPin(home.id, stay?.id, pin)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { id ->
        val place = placeOf(id, visit, state.places)
        if (place == null || home == null || stay == null) { editing = null; return@let }
        val note = visit.cityNotes[placeIdOf(id)] ?: Note()
        AddressEditor(
            name = place.own,
            given = note.name,
            pinned = place.pinned,
            at = place.lat to place.lon,
            country = home,
            arrived = stay.arrived[id],
            left = stay.left[id],
            onSave = { text, given, from, to, moveTo ->
                // A town gets its street address in its place, keeping its dates; an address is
                // renamed, and moved when the lookup found where it really is.
                if (place.pinned) {
                    if (text != place.own) state.details.onRenamePin(id, text)
                    moveTo?.let { state.app.visits.movePin(id, it.first, it.second) }
                    state.details.onStopDates(stay.id, id, from, to)
                } else if (text.isNotBlank() && text != place.own) {
                    val (lat, lon) = moveTo ?: (place.lat to place.lon)
                    state.app.visits.replaceStopWithPin(stay.id, id, Pin(text, lat, lon))
                    state.app.visits.visits.value[home.id]?.trips?.firstOrNull { it.id == stay.id }?.cities
                        ?.firstOrNull { it !in stay.cities }?.let { state.details.onStopDates(stay.id, it, from, to) }
                } else {
                    state.details.onStopDates(stay.id, id, from, to)
                }
                // The user's own name sits on the place's note; a town turned address took its
                // note along, so it is found under the new id.
                val now = state.app.visits.visits.value[home.id]?.trips?.firstOrNull { it.id == stay.id }?.cities.orEmpty()
                val placeId = placeIdOf(if (id in now) id else now.firstOrNull { it !in stay.cities } ?: id)
                if (given != note.name) state.details.onCityNote(home.id, placeId, note.copy(name = given))
                editing = null
            },
            onShow = { editing = null; state.onShowHome(place.lat, place.lon) },
            onRemove = { state.details.onRemoveCity(stay.id, id); editing = null },
            onDismiss = { editing = null },
        )
    }
}

/**
 * A new address, the way people know one: typed out, with the town it is in picked from the
 * suggestions or found in what was typed. Pasted coordinates work too, and the map is there
 * for the exact door when the town is not close enough.
 */
@Composable
private fun AddressDialog(
    country: Country,
    places: Places?,
    near: Pair<Double, Double>?,
    onSave: (com.marcusrosado.stamp.data.Pin) -> Unit,
    onDismiss: () -> Unit,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var where by rememberSaveable { mutableStateOf("") }
    var town by remember { mutableStateOf<City?>(null) }
    var onMap by rememberSaveable { mutableStateOf(false) }
    val alpha2 = citiesCountry(country)
    // Typed coordinates, such as a pasted Google Maps point, are the place itself.
    val typedPoint = remember(where) { Coordinates.parse(where) }
    // The town: what was typed in its field, or else the end of the address ("..., 2200 København").
    val guess = where.ifBlank { address.substringAfterLast(',', "").replace(Regex("[0-9]"), "").trim() }
    // Towns to choose from: a match for the guess, else for any word of the address, else the
    // country's biggest towns, so there is always something to tap.
    // A town named in what was typed, matched whole so a street named after a town does not
    // turn into that town.
    val named = remember(guess, address, places) {
        if (typedPoint != null || places == null) return@remember emptyList()
        val words = listOf(guess) + address.split(',').map { it.replace(Regex("[0-9]"), "").trim() }.reversed()
        words.filter { it.length >= 3 }.firstNotNullOfOrNull { w ->
            places.search(alpha2, w).filter { Places.fold(it.name) == Places.fold(w) || it.ascii.equals(w, ignoreCase = true) }.takeIf { it.isNotEmpty() }
        }.orEmpty()
    }
    // The town nearest the latest address, for a street typed on its own.
    val homeTown = remember(near, places) {
        val (lat, lon) = near ?: return@remember null
        places?.citiesIn(alpha2)?.minByOrNull { (it.lat - lat) * (it.lat - lat) + (it.lon - lon) * (it.lon - lon) }
    }
    // The town the address is in: the one picked, else one named in it, else the town nearest
    // the latest address. Any typed address saves: with none of these it goes in the middle
    // of the country, and a tap on a town or the map moves it.
    val inTown = town ?: named.firstOrNull() ?: homeTown
    val towns = remember(named, places, inTown) {
        (named + listOfNotNull(homeTown) + places?.citiesIn(alpha2).orEmpty().sortedByDescending { it.population }.take(6))
            .distinctBy { it.id }.filter { it.id != inTown?.id }.take(5)
    }
    // The phone's own lookup: the real spot of what was typed. The best match is taken unless
    // another one is tapped.
    val context = LocalContext.current
    val found by produceState(emptyList<Lookup.Found>(), address, where, typedPoint) {
        // Matches for the last text never stand for the new one.
        value = emptyList()
        if (typedPoint != null || address.isBlank()) return@produceState
        kotlinx.coroutines.delay(500)
        value = Lookup.find(context, listOf(address, where).filter { it.isNotBlank() }.joinToString(", "), alpha2, country.name)
    }
    var chosen by remember { mutableStateOf<Lookup.Found?>(null) }
    val picked = chosen?.takeIf { it in found } ?: found.firstOrNull()
    val point = typedPoint?.let { it.lat to it.lon } ?: picked?.let { it.lat to it.lon }
        ?: inTown?.let { it.lat to it.lon } ?: (country.labelLat to country.labelLon)
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    placeholder = { Text("Street 12, town") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = where,
                    onValueChange = { where = it; town = null },
                    label = { Text("Town or coordinates") },
                    placeholder = { Text(country.name) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    typedPoint != null -> Text("At ${coordinates(typedPoint.lat, typedPoint.lon)}", color = MaterialTheme.colorScheme.primary)
                    found.isNotEmpty() -> found.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { chosen = f }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = f == picked, onClick = { chosen = f })
                            Text(f.label, modifier = Modifier.weight(1f))
                        }
                    }
                    inTown != null -> Text("The phone did not find it, so it goes in ${inTown.name}. Next places it on the map.", color = MaterialTheme.colorScheme.primary)
                    else -> Text("In ${country.name}. Tap its town to place it closer.", color = MaterialTheme.colorScheme.primary)
                }
                // Towns to tap only when the lookup has nothing to offer.
                if (typedPoint == null && found.isEmpty() && towns.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        towns.forEach { c ->
                            SuggestionChip(onClick = { town = c }, label = { Text(c.name) })
                        }
                    }
                }
                Text(
                    "For the exact house, paste its spot from Google Maps into the field above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        // Otherwise the map opens on the town, so the pin can go on the house.
        confirmButton = {
            // Found by the phone or pasted: exact, so it saves; the found address reads in full.
            val exactAlready = typedPoint != null || picked != null
            TextButton(
                onClick = {
                    if (exactAlready) onSave(Pin(picked?.label ?: address.trim(), point.first, point.second)) else onMap = true
                },
                enabled = address.isNotBlank(),
            ) { Text(if (exactAlready) "Save" else "Next") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (onMap) {
        PinPlace(
            country = country,
            cities = places?.citiesIn(alpha2).orEmpty(),
            initialName = address,
            onAttach = { pin ->
                onMap = false
                onSave(Pin(address.trim(), pin.lat, pin.lon))
            },
            onDismiss = { onMap = false },
            title = address.trim(),
            named = address.trim(),
            nameTitle = "Address",
            namePlaceholder = "Street and number",
            start = org.maplibre.android.geometry.LatLng(point.first, point.second),
        )
    }
}

/** A home on the timeline: a dot on the line, filled for a home lived in now. */
@Composable
private fun HomeEntry(name: String, address: String?, dates: String?, now: Boolean, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick).padding(start = 24.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            // The line runs through the dot, from the home above to the one below.
            Column(Modifier.fillMaxHeight()) {
                Box(Modifier.weight(1f).width(2.dp).background(if (first) Color.Transparent else line))
                Box(Modifier.weight(1f).width(2.dp).background(if (last) Color.Transparent else line))
            }
            Box(
                Modifier.size(if (now) 14.dp else 10.dp).clip(CircleShape)
                    .background(if (now) accent else line),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (now) FontWeight.SemiBold else null)
            // Named by the user: the address goes under the name.
            if (address != null) Text(address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                dates ?: "Add when you lived here",
                style = MaterialTheme.typography.bodyMedium,
                color = if (dates == null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (now) Text("Now", style = MaterialTheme.typography.labelLarge, color = accent)
    }
}

/**
 * One home: its address, when you moved in and out, or that you still live there. The address
 * is looked up again, so one placed at its town can move to where it really is.
 */
@Composable
private fun AddressEditor(
    name: String,
    given: String,
    pinned: Boolean,
    at: Pair<Double, Double>,
    country: Country,
    arrived: PartialDate?,
    left: PartialDate?,
    onSave: (text: String, given: String, arrived: PartialDate?, left: PartialDate?, moveTo: Pair<Double, Double>?) -> Unit,
    onShow: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    // An address edits its own text; a town takes a street address in it.
    var text by rememberSaveable { mutableStateOf(if (pinned) name else "") }
    var title by rememberSaveable { mutableStateOf(given) }
    var from by remember { mutableStateOf(arrived) }
    var to by remember { mutableStateOf(left) }
    var stillHere by remember { mutableStateOf(left == null && arrived != null) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var removing by remember { mutableStateOf(false) }
    if (removing) {
        LossDialog(
            Loss("Remove this address?", "Its dates go with it.", "Remove"),
            onConfirm = { removing = false; onRemove() },
            onDismiss = { removing = false },
        )
    }
    var moveTo by remember { mutableStateOf<Lookup.Found?>(null) }
    val context = LocalContext.current
    // Where the address really is, when that is more than a street away from where it sits.
    val lookup by produceState<Lookup.Found?>(null, text) {
        value = null
        if (text.isBlank()) return@produceState
        kotlinx.coroutines.delay(500)
        // A street typed for a town is looked up in that town, not anywhere in the country.
        val query = if (pinned) text else "$text, $name"
        value = Lookup.find(context, query, citiesCountry(country), country.name, limit = 1).firstOrNull()
            ?.takeIf { Distance.km(it.lat to it.lon, at) > 0.15 }
    }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.ifBlank { if (pinned) "Home" else name }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    placeholder = { Text("Mum's place, the flat by the park") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; moveTo = null },
                    label = { Text("Address") },
                    placeholder = { Text(if (pinned) "Street and number" else "Street and number in $name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val found = lookup
                if (moveTo != null) {
                    Text("Moves to ${moveTo!!.label}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                } else if (found != null) {
                    TextButton(onClick = { moveTo = found }) { Text("Put it at ${found.label}") }
                }
                HomeDate("Moved in", from) { picking = 0 }
                if (!stillHere) HomeDate("Moved out", to) { picking = 1 }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { stillHere = !stillHere }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("I still live here", modifier = Modifier.weight(1f))
                    Switch(checked = stillHere, onCheckedChange = { stillHere = it })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShow) { Text("Show on map") }
                    OutlinedButton(onClick = { removing = true }) { Text("Remove") }
                }
            }
        },
        confirmButton = {
            val end = if (stillHere) null else to
            val start = from
            // "Still here" is a moved-in date with no moved-out one, so the switch needs the
            // date it stands on: on with no moved-in date, or off with no moved-out date, would
            // save something other than what it shows.
            val problem = when {
                start != null && end != null && end.endsBefore(start) -> "Moved out is before moved in"
                stillHere && start == null -> "Add when you moved in"
                !stillHere && start != null && end == null -> "Add when you moved out"
                else -> null
            }
            TextButton(
                onClick = { onSave(text.trim().ifBlank { name }, title.trim(), from, end, moveTo?.let { it.lat to it.lon }) },
                enabled = problem == null,
            ) { Text(problem ?: "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    picking?.let { which ->
        DateDialog(
            initial = if (which == 0) from else to ?: from,
            onDone = { if (which == 0) from = it else to = it; picking = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun HomeDate(label: String, value: PartialDate?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.label ?: "Add", color = if (value == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}


/** A country to live in, with its flag. The one lived in now carries the home mark. */
@Composable
private fun HomeRow(c: Country, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
            .clickable(enabled = !selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagMark(c)
        Spacer(Modifier.width(16.dp))
        Text(
            c.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(TravelState.Lived.icon, contentDescription = "Home", tint = MaterialTheme.colorScheme.primary)
    }
}
