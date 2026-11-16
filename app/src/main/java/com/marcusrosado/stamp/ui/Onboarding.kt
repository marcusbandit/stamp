package com.marcusrosado.stamp.ui

import androidx.compose.material.icons.filled.Close
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.TravelState

/** Marks for the first page's picture, to show what a filled-in map looks like. */
private val Sample = mapOf(
    "ESP" to TravelState.Visited, "FRA" to TravelState.Visited, "ITA" to TravelState.Visited,
    "PRT" to TravelState.Visited, "GRC" to TravelState.Visited, "MAR" to TravelState.Visited,
    "USA" to TravelState.Visited, "MEX" to TravelState.Visited, "JPN" to TravelState.Visited,
    "THA" to TravelState.Visited, "VNM" to TravelState.Visited, "GBR" to TravelState.Lived,
    "AUS" to TravelState.Lived, "ISL" to TravelState.Want, "PER" to TravelState.Want,
    "NZL" to TravelState.Want, "KEN" to TravelState.Want, "ARG" to TravelState.Want,
)

private const val PAGES = 7

/**
 * First launch. Seven short pages: what Stamp is for, picking a look, the home country, the
 * country the phone is in, the hourly heat map, finding countries in photos (what it gives,
 * then the ask), and how to mark a country by hand. Every step can be skipped.
 *
 * [onDone] gets true when the user started a scan, so the scan flow opens.
 */
@Composable
fun Onboarding(
    atlas: Atlas,
    home: String?,
    onHome: (String?) -> Unit,
    onStartScan: () -> Unit,
    onDone: (openScan: Boolean) -> Unit,
    look: String,
    onLook: (String) -> Unit,
    /** Whether the user let Stamp use the rough location, so the switch in Settings follows it. */
    onHere: (Boolean) -> Unit,
    /** Leaves the introduction for the Google Photos import's steps. */
    onTakeout: () -> Unit = {},
    /** True in the Play build without Pro: the heat map page offers Pro before the location ask. */
    heatLocked: Boolean = false,
    /** Opens Play's purchase sheet for Pro. */
    onBuyPro: (android.app.Activity) -> Unit = {},
    /** Turns the hourly look for the heat map on, once the phone has allowed it. */
    onHourly: (Boolean) -> Unit = {},
    /** The places already seen, so the heat map's page shows the user's own once there are some. */
    seen: List<com.marcusrosado.stamp.data.SeenPoint> = emptyList(),
    /** Set when the introduction is seen again: a close button, and back from the first page leaves. */
    onClose: (() -> Unit)? = null,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val palette = LocalMapPalette.current
    BackHandler(enabled = page > 0 && !picking) { page-- }
    BackHandler(enabled = page == 0 && !picking && onClose != null) { onClose?.invoke() }
    val ask = rememberPhotoAccess(
        onGranted = { onStartScan(); onDone(true) },
        onDenied = { page = 6 },
    )
    // Where the phone is: asked once, and the introduction moves on whatever the answer.
    val askHere = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> onHere(granted); page = 4 }
    val askHourly = rememberHourlyAsk { granted -> onHourly(granted); page = 5 }
    if (picking) {
        HomePicker(atlas, home, onPick = { onHome(it); picking = false }, onClose = { picking = false })
        return
    }
    val homeCountry = home?.let { atlas.byId[it] }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().padding(24.dp)) {
            if (onClose != null) {
                androidx.compose.material3.IconButton(onClick = onClose) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Close the introduction")
                }
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f),
                label = "page",
            ) { p ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (p) {
                        0 -> Page(
                            "Colour in your world",
                            "Mark the countries you have visited, lived in, or want to see next. Stamp keeps the count.",
                        ) {
                            MiniMap(atlas, { id -> Sample[id]?.let { palette.of(it) } })
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                TravelState.entries.forEach { s ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Swatch(palette.of(s))
                                        Text(
                                            s.label, style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(start = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                        1 -> Page(
                            "Make it yours",
                            "Pick the colours your world is drawn in. The whole app changes with it, and Settings has it later.",
                        ) {
                            LookPicker(atlas, look, onLook)
                        }
                        2 -> Page(
                            "Where do you live now?",
                            "Stamp marks it as lived and lists it first in your passport. Countries you lived in " +
                                "before get their own dates later. You can skip this and set it in Settings.",
                        ) {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        homeCountry?.flag?.ifEmpty { null } ?: "\uD83C\uDFE0",
                                        style = MaterialTheme.typography.headlineMedium,
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text("Home", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(homeCountry?.name ?: "Not set yet", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                        }
                        3 -> Page(
                            "Stamp knows where you are",
                            "Open the app abroad and the country you are in is marked as visited, with the map " +
                                "already there. Stamp asks the phone for a rough position, enough to name the " +
                                "country and nothing closer, and it never leaves the phone. Settings, Map turns it off.",
                        ) {
                            Panel {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("\uD83D\uDCCD", style = MaterialTheme.typography.headlineMedium)
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text("You are in", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Portugal", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                // A sample to try: it answers like the real one and keeps nothing.
                                var sample by remember { mutableStateOf<TravelState?>(TravelState.Visited) }
                                StateButtons(sample, onSet = { sample = it })
                            }
                        }
                        4 -> Page(
                            "A heat map of your life",
                            "About once an hour, at a random moment, Stamp collects your approximate location, " +
                                "even when the app is closed or not in use. Over months it builds a map of where " +
                                "you spend your time. The places stay on this phone and go in your backups. " +
                                "Android then asks you to allow it all the time.",
                        ) {
                            HeatPreview(atlas, seen, Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)))
                        }
                        5 -> Page(
                            "Your photos know where you have been",
                            "Stamp reads where your photos were taken and finds the countries, with the first and " +
                                "last date you were in each. Everything is read right here on this phone, and " +
                                "you choose which countries to add.",
                        ) {
                            PhotoPreview()
                        }
                        else -> Page(
                            "Tap a country to mark it",
                            "Tap any country on the map, then choose Visited, Lived or Want to go. " +
                                "Small countries are quicker to find in the list behind the search button.",
                        ) {
                            Panel {
                                Text("Portugal", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Europe", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                // A sample to try: it answers like the real one and keeps nothing.
                                var sample by remember { mutableStateOf<TravelState?>(TravelState.Visited) }
                                StateButtons(sample, onSet = { sample = it })
                            }
                        }
                    }
                }
            }
            Dots(page)
            Spacer(Modifier.height(16.dp))
            when (page) {
                0 -> Button(onClick = { page = 1 }, Modifier.fillMaxWidth()) { Text("Continue") }
                1 -> Button(onClick = { page = 2 }, Modifier.fillMaxWidth()) { Text("Continue") }
                2 -> if (homeCountry == null) {
                    Button(onClick = { picking = true }, Modifier.fillMaxWidth()) { Text("Choose home country") }
                    TextButton(onClick = { page = 3 }, Modifier.fillMaxWidth()) { Text("Skip for now") }
                } else {
                    Button(onClick = { page = 3 }, Modifier.fillMaxWidth()) { Text("Continue") }
                    TextButton(onClick = { picking = true }, Modifier.fillMaxWidth()) { Text("Change") }
                }
                3 -> {
                    Button(onClick = { askHere.launch(com.marcusrosado.stamp.data.Here.PERMISSION) }, Modifier.fillMaxWidth()) {
                        Text("Use my location")
                    }
                    TextButton(onClick = { page = 4 }, Modifier.fillMaxWidth()) { Text("Skip for now") }
                }
                4 -> {
                    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
                    if (heatLocked) Button(onClick = { activity?.let(onBuyPro) }, Modifier.fillMaxWidth()) { Text("Get Stamp Pro") }
                    else Button(onClick = askHourly, Modifier.fillMaxWidth()) { Text("Build my heat map") }
                    TextButton(onClick = { page = 5 }, Modifier.fillMaxWidth()) { Text("Skip for now") }
                }
                5 -> {
                    Button(onClick = ask, Modifier.fillMaxWidth()) { Text("Scan this phone") }
                    androidx.compose.material3.OutlinedButton(onClick = onTakeout, Modifier.fillMaxWidth()) { Text("Import from Google Photos") }
                    TextButton(onClick = { page = 6 }, Modifier.fillMaxWidth()) { Text("Skip for now") }
                }
                else -> Button(onClick = { onDone(false) }, Modifier.fillMaxWidth()) { Text("Start") }
            }
        }
    }
}

@Composable
private fun Page(title: String, body: String, art: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(24.dp))
    Column(content = art)
    Spacer(Modifier.height(32.dp))
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Dots(page: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(PAGES) { i ->
            Box(
                Modifier
                    .padding(4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}
