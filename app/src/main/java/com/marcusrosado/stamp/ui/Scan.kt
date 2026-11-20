package com.marcusrosado.stamp.ui

import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Luggage
import com.marcusrosado.stamp.data.FoundTrip
import com.marcusrosado.stamp.data.TripUpdate
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.OutlinedButton
import com.marcusrosado.stamp.scan.Parts
import com.marcusrosado.stamp.scan.ScanSource
import com.marcusrosado.stamp.scan.Takeout
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.PhotoEvidence
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.scan.Found
import com.marcusrosado.stamp.scan.ScanState

/** Runtime permissions the scan needs on this Android version. */
fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 29 -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** True when the user let Stamp see some or all photos. */
fun photosGranted(result: Map<String, Boolean>): Boolean =
    result[Manifest.permission.READ_MEDIA_IMAGES] == true ||
        result[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true ||
        result[Manifest.permission.READ_EXTERNAL_STORAGE] == true

/** Asks for photo access, then calls [onGranted] or [onDenied]. */
@Composable
fun rememberPhotoAccess(onGranted: () -> Unit, onDenied: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (photosGranted(it)) onGranted() else onDenied()
    }
    return { launcher.launch(photoPermissions()) }
}

/** Opens the system file picker for one or more Takeout zips. */
@Composable
fun rememberZipPicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) onPicked(it)
    }
    return { launcher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
}

private enum class Mode { Choose, Takeout, Denied }

/**
 * Finding countries in photos, as one full-screen flow: where to look, the permission or
 * progress, and the review where the user ticks which countries to add.
 */
@Composable
fun ScanScreen(
    state: ScanState,
    visits: Map<String, Visit>,
    home: String?,
    onStartDevice: () -> Unit,
    onAdd: (found: Map<String, PhotoEvidence>, mark: Set<String>, trips: List<FoundTrip>, updates: List<TripUpdate>) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    /** Ticks changed in the review, by country id, to keep with the finished scan. */
    onPick: (Map<String, Boolean>) -> Unit = {},
    /** Reads the Google Takeout zips the user picked. */
    onImportTakeout: (List<Uri>) -> Unit = {},
    /** Opens straight on the Google Photos steps, as Settings and the introduction do. */
    startInTakeout: Boolean = false,
    /** The user saw why a zip could not be read. */
    onProblemSeen: () -> Unit = {},
) {
    var mode by rememberSaveable { mutableStateOf(if (startInTakeout) Mode.Takeout else Mode.Choose) }
    val ask = rememberPhotoAccess(onGranted = { mode = Mode.Choose; onStartDevice() }, onDenied = { mode = Mode.Denied })
    val pickZips = rememberZipPicker(onImportTakeout)
    // The steps opened from the choice go back to it; opened on their own, back leaves.
    BackHandler { if (mode == Mode.Takeout && state is ScanState.Idle && !startInTakeout) mode = Mode.Choose else onClose() }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (mode == Mode.Takeout && state is ScanState.Idle) "Import from Google Photos" else "Countries in your photos",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            when {
                state is ScanState.Running -> Running(state)
                state is ScanState.Done -> Review(
                    state, visits, home, onAdd, onPick,
                    onAgain = if (state.source == ScanSource.Takeout) pickZips else ask,
                )
                state is ScanState.Failed -> Message(
                    "That did not work",
                    "Something went wrong while reading: ${state.message}",
                    "Try again", { onReset(); if (state.source == ScanSource.Takeout) pickZips() else ask() },
                )
                mode == Mode.Takeout -> TakeoutGuide(onPick = pickZips)
                mode == Mode.Denied -> Denied(onRetry = ask)
                else -> Choose(onDevice = ask, onTakeout = { mode = Mode.Takeout }, onSkip = onClose)
            }
        }
    }
    if (state is ScanState.Done && state.problem != null) {
        SeamAlertDialog(
            onDismissRequest = onProblemSeen,
            title = { Text("That zip could not be read") },
            text = { Text(state.problem) },
            confirmButton = { TextButton(onClick = onProblemSeen) { Text("OK") } },
        )
    }
}

@Composable
private fun Choose(onDevice: () -> Unit, onTakeout: () -> Unit, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhotoPreview()
        Text(
            "Stamp reads where your photos were taken and lists the countries with the first and last date " +
                "you were there. You choose which countries to add.",
        )
        Source(
            Icons.Filled.PhoneAndroid,
            "Photos on this phone",
            "Reads the location saved in every photo. Asks to see your photos once. Nothing is uploaded.",
            "Scan this phone", onDevice,
        )
        Source(
            Icons.Filled.CloudDownload,
            "Photos in Google Photos",
            "For photos that are only in the cloud. Reads a Google Takeout export, which takes a few steps.",
            "Set up the import", onTakeout,
        )
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}

@Composable
private fun Source(icon: ImageVector, title: String, body: String, action: String, onClick: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
    }
}

/** The Takeout steps, with Google's own button names, and the file picker at the end. */
@Composable
fun TakeoutGuide(onPick: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        PhotoPreview()
        // Big enough for few parts, small enough that one fits on this phone with room to spare.
        val size = androidx.compose.runtime.remember {
            Takeout.partSizeFor(runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes }.getOrDefault(0L))
        }
        Step(1, "Ask Google for an export") {
            Text(
                "Open Google Takeout with only Google Photos picked. Tap Next step, keep Send download link " +
                    "via email and Export once, choose .zip and $size GB, then tap Create export.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Takeout.TAKEOUT_URL))) }
            }) { Text("Open Google Takeout") }
        }
        Step(2, "Wait for Google's email") {
            Text(
                "It takes a few hours, or a day or two for a big library. Stamp can be closed meanwhile.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Step(3, "Download one part at a time") {
            Text(
                "Open your export and tap Download on part 1. When it finishes, tap Open and pick Stamp. " +
                    "Stamp reads it, deletes it and brings you back for the next part, so only one part is " +
                    "ever on the phone. The countries from every part wait for you in one list.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { openUrl(context, Takeout.MANAGE_URL) }) { Text("Open your export") }
            Spacer(Modifier.height(16.dp))
            Text(
                "Rather use a computer? Download the parts there and open Stamp's Google Photos page. It " +
                    "reads them in the browser and saves one small file for this phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Takeout.COMPUTER_URL)
                runCatching { context.startActivity(Intent.createChooser(send, null)) }
            }) { Text("Send the page to a computer") }
        }
        Step(4, "Or choose them here") {
            Text(
                "Zips already on this phone, or the stamp-google-photos.zip the computer saved. Only the " +
                    "place and date of each photo are kept, and they stay after the zips are gone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPick) { Text("Choose the zip files") }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, body: @Composable () -> Unit) {
    Row {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            body()
        }
    }
}

/** Example rows showing what a finished scan looks like, before the user grants anything. */
@Composable
fun PhotoPreview() {
    val rows = listOf(
        Triple("🇯🇵", "Japan", "2 Apr 2023 to 16 Apr 2023"),
        Triple("🇵🇹", "Portugal", "14 Aug 2021 to 3 Jun 2024"),
        Triple("🇲🇦", "Morocco", "20 Mar 2019 to 27 Mar 2019"),
    )
    Panel {
        Text(
            "Example", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { (flag, name, line) ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(flag, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(checked = true, onCheckedChange = null)
            }
        }
    }
}

@Composable
private fun Running(state: ScanState.Running) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (state.total == 0) "Getting started" else "Checked %,d of %,d photos".format(state.checked, state.total),
            fontWeight = FontWeight.Medium,
        )
        LinearProgressIndicator(
            progress = { if (state.total == 0) 0f else state.checked.toFloat() / state.total },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            "You can leave this screen. It keeps going and the result waits in Passport.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.found, key = { it.country.id }) { f -> FoundRow(f, checked = null, note = null, onToggle = {}) }
        }
    }
}

@Composable
private fun Review(
    state: ScanState.Done,
    visits: Map<String, Visit>,
    home: String?,
    onAdd: (found: Map<String, PhotoEvidence>, mark: Set<String>, trips: List<FoundTrip>, updates: List<TripUpdate>) -> Unit,
    onPick: (Map<String, Boolean>) -> Unit,
    onAgain: () -> Unit,
) {
    val context = LocalContext.current
    val parts = state.parts
    parts?.let { DeleteParts(it.uris) }
    val nextPart = { openUrl(context, Takeout.MANAGE_URL) }
    if (state.found.isEmpty() && parts != null) {
        Message(
            partsLine(parts, state.skipped), "No photo in the parts so far has a location.", nextLabel(parts), nextPart,
            secondary = "Finish" to { onAdd(emptyMap(), emptySet(), emptyList(), emptyList()) },
        )
        return
    }
    if (state.found.isEmpty()) {
        val body = when {
            state.source == ScanSource.Takeout && state.total == 0 ->
                "Those files hold no Google Photos. Choose the zips from the Takeout email."
            state.source == ScanSource.Takeout ->
                "None of the %,d photos in the export has a location. Google keeps one only when the camera or you added it."
                    .format(state.total)
            state.total == 0 ->
                "Stamp could not see any photos. If you gave access to selected photos only, pick more and scan again."
            else ->
                "None of your %,d photos has a location saved. Turn on location in your camera app and new photos will have one."
                    .format(state.total)
        }
        Message("No countries found", body, "Try again", onAgain)
        return
    }
    // Unmarked countries and Want to go ones can be ticked: photos from a place prove a visit.
    fun tickable(id: String) = visits[id]?.state?.counts != true
    // A country the user has not touched starts ticked. The ticks live in the scan's state,
    // so leaving the review and coming back keeps them.
    fun ticked(id: String) = tickable(id) && (state.picks[id] ?: true)
    val open = state.found.map { it.country.id }.filter(::tickable)
    val newCount = open.count(::ticked)
    // Found trips start ticked too, under their own key in the same picks. A trip goes only
    // with its countries: one through a country the user unticked is unticked with it, since
    // adding it would mark that country after all.
    fun tripTicked(t: FoundTrip) = (state.picks[t.key] ?: true) && t.lands.all { !tickable(it) || ticked(it) }
    val tripCount = state.trips.count(::tripTicked)
    // Trips the user has that the photos fill in go in too, unless unticked here.
    fun updateTicked(u: TripUpdate) = (state.picks[u.key] ?: true) && u.lands.all { !tickable(it) || ticked(it) }
    val updateCount = state.updates.count(::updateTicked)
    Column(Modifier.fillMaxSize()) {
        Row(
            // The end padding lines the box up with the row boxes under it.
            Modifier.fillMaxWidth().padding(start = 16.dp, end = if (open.isEmpty()) 16.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (parts?.let { partsLine(it, state.skipped) + " " } ?: "") +
                    "Found ${countries(state.found.size)} in %,d photos with a location.".format(state.located),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open.isNotEmpty()) {
                TriStateCheckbox(
                    state = when (newCount) {
                        0 -> ToggleableState.Off
                        open.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    },
                    // All ticked clears them all; anything less ticks them all.
                    onClick = { onPick(open.associateWith { newCount < open.size }) },
                    modifier = Modifier.semantics { contentDescription = "All countries" },
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.trips.isNotEmpty()) {
                item(key = "trips") { SectionLabel(if (state.trips.size == 1) "1 trip" else "${state.trips.size} trips") }
                items(state.trips, key = { it.key }) { t ->
                    TripRow(t, tripTicked(t)) {
                        // Ticking a trip ticks the countries it goes to as well.
                        onPick(if (tripTicked(t)) mapOf(t.key to false) else mapOf(t.key to true) + t.lands.associateWith { true })
                    }
                }
            }
            if (state.updates.isNotEmpty()) {
                item(key = "updates") { SectionLabel("Your trips") }
                items(state.updates, key = { it.key }) { u ->
                    UpdateRow(u, updateTicked(u)) {
                        onPick(if (updateTicked(u)) mapOf(u.key to false) else mapOf(u.key to true) + u.lands.associateWith { true })
                    }
                }
            }
            if (state.trips.isNotEmpty() || state.updates.isNotEmpty()) {
                item(key = "countries") { SectionLabel(countries(state.found.size).replaceFirstChar { it.uppercase() }) }
            }
            items(state.found, key = { it.country.id }) { f ->
                val existing = visits[f.country.id]
                val note = when {
                    f.country.id == home -> "Home · ${evidenceLine(f.evidence)}"
                    existing != null && existing.state.counts ->
                        "Already marked ${existing.state.label.lowercase()} · ${evidenceLine(f.evidence)}"
                    existing != null -> "Was want to go · ${evidenceLine(f.evidence)}"
                    else -> null
                }
                FoundRow(
                    f,
                    checked = if (tickable(f.country.id)) ticked(f.country.id) else null,
                    note = note,
                    onToggle = { onPick(mapOf(f.country.id to !ticked(f.country.id))) },
                )
            }
        }
        val stuck = parts?.uris?.count { partsGone[it] == false } ?: 0
        if (parts != null && stuck > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        stuck > 1 -> "$stuck parts are still on the phone."
                        parts.uris.size == 1 && parts.last != null -> "Part ${parts.last} is still on the phone."
                        else -> "A part is still on the phone."
                    },
                    Modifier.weight(1f),
                )
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }
                }) { Text("Open Downloads") }
            }
        }
        if (parts != null) {
            OutlinedButton(onClick = nextPart, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text(nextLabel(parts))
            }
        } else if (state.source == ScanSource.Takeout) {
            OutlinedButton(onClick = onAgain, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text("Add another zip")
            }
        }
        Button(
            onClick = {
                // Dates go onto every found country that is or will be marked.
                val mark = open.filter(::ticked).toSet()
                onAdd(
                    state.found.associate { it.country.id to it.evidence }, mark,
                    state.trips.filter(::tripTicked), state.updates.filter(::updateTicked),
                )
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(addLabel(newCount, tripCount, updateCount))
        }
    }
}

/**
 * "Part 3 read." or, for a part handed over twice, "Part 3 was read before." Several picked at
 * once read "3 parts read."
 */
private fun partsLine(p: Parts, skipped: Int = 0): String {
    val read = when {
        p.uris.size > 1 -> "${p.uris.size} parts read."
        p.uris.isEmpty() -> ""
        else -> (p.last?.let { "Part $it" } ?: "That part").let { if (p.again) "$it was read before." else "$it read." }
    }
    val so = if (p.count > 1) "${p.count} parts so far." else ""
    val other = when (skipped) {
        0 -> ""
        1 -> "1 file was not part of this export."
        else -> "$skipped files were not part of this export."
    }
    return listOf(read, so, other).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Nothing new was read." }
}

private fun nextLabel(p: Parts): String =
    if (p.numbers.isNotEmpty()) "Download part ${p.next}" else "Download the next part"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** Parts asked about already, so coming back to the review never asks to delete one twice. */
private val partsAsked = mutableSetOf<String>()

/** What became of each part Stamp tried to delete, by uri: true once it is gone. */
private val partsGone = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

/**
 * The part as a MediaStore file, which Android's delete question takes: straight from a media
 * address, or looked up from a document one, as the Files app hands over. Null otherwise.
 */
private fun mediaFile(context: android.content.Context, u: Uri): Uri? {
    val media = when {
        u.authority == android.provider.MediaStore.AUTHORITY -> u
        Build.VERSION.SDK_INT >= 29 && android.provider.DocumentsContract.isDocumentUri(context, u) ->
            runCatching { android.provider.MediaStore.getMediaUri(context, u) }.getOrNull()
        else -> null
    } ?: return null
    // Android refuses a request for a Downloads address; the same item as a file is fine.
    return runCatching {
        android.provider.MediaStore.Files.getContentUri(media.pathSegments[0], android.content.ContentUris.parseId(media))
    }.getOrNull()
}

/**
 * Deletes a Takeout part once it is read, so the phone only ever holds one. A part Stamp
 * may not delete itself, a download another app saved, gets Android's own delete question.
 */
@Composable
private fun DeleteParts(uris: List<String>) {
    val context = LocalContext.current
    // Parts Android has to ask about, all in one question rather than one sheet each.
    var asking by remember { mutableStateOf(emptyList<String>()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        asking.forEach { partsGone[it] = r.resultCode == android.app.Activity.RESULT_OK }
    }
    androidx.compose.runtime.LaunchedEffect(uris) {
        val fresh = uris.filter { partsAsked.add(it) }
        if (fresh.isEmpty()) return@LaunchedEffect
        val resolver = context.contentResolver
        val left = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            fresh.filterNot { uri ->
                val u = Uri.parse(uri)
                val gone = (android.provider.DocumentsContract.isDocumentUri(context, u) &&
                    runCatching { android.provider.DocumentsContract.deleteDocument(resolver, u) }.getOrDefault(false)) ||
                    runCatching { resolver.delete(u, null, null) > 0 }.getOrDefault(false)
                if (gone) partsGone[uri] = true
                gone
            }
        }
        if (left.isEmpty()) return@LaunchedEffect
        val items = if (Build.VERSION.SDK_INT >= 30) left.associateWith { mediaFile(context, Uri.parse(it)) } else emptyMap()
        left.filter { items[it] == null }.forEach { partsGone[it] = false }
        val askable = left.filter { items[it] != null }
        if (askable.isEmpty()) return@LaunchedEffect
        val asked = runCatching {
            val sender = android.provider.MediaStore.createDeleteRequest(resolver, askable.mapNotNull { items[it] }).intentSender
            asking = askable
            ask.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
        }.isSuccess
        if (!asked) askable.forEach { partsGone[it] = false }
    }
}

/**
 * "Add 3 countries and 2 trips", "Update 1 trip" when only trips the user has are filled in,
 * or "Done" when nothing is ticked.
 */
internal fun addLabel(countries: Int, trips: Int, updates: Int = 0): String {
    val parts = listOfNotNull(
        countries.takeIf { it > 0 }?.let { if (it == 1) "1 country" else "$it countries" },
        trips.takeIf { it > 0 }?.let { if (it == 1) "1 trip" else "$it trips" },
    )
    val update = if (updates == 1) "update 1 trip" else "update $updates trips"
    return when {
        parts.isNotEmpty() && updates > 0 -> "Add " + parts.joinToString(" and ") + ", " + update
        parts.isNotEmpty() -> "Add " + parts.joinToString(" and ")
        updates > 0 -> update.replaceFirstChar { it.uppercase() }
        else -> "Done"
    }
}

/** A trip the user has that the photos fill in: the towns it gains, and which trip. */
@Composable
private fun UpdateRow(u: TripUpdate, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.AddLocationAlt, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(36.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (u.stops.isEmpty()) "Dates" else townsLine(u.stops.map { it.city.name }),
                fontWeight = FontWeight.Medium,
            )
            Text(
                listOfNotNull(u.trip.name.ifBlank { null }, (u.trip.date ?: u.date)?.label).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** A trip the photos show: its towns in order, and when. */
@Composable
private fun TripRow(t: FoundTrip, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Luggage, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(36.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(townsLine(t.stops.map { it.city.name }), fontWeight = FontWeight.Medium)
            Text(
                evidenceLine(PhotoEvidence(t.startMs, t.endMs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** "Baytown, Hillville and 3 more": the first towns, then how many others. */
internal fun townsLine(names: List<String>): String = when {
    names.size <= 3 -> names.joinToString(", ")
    else -> names.take(2).joinToString(", ") + " and ${names.size - 2} more"
}

@Composable
private fun FoundRow(f: Found, checked: Boolean?, note: String?, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = checked != null, onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagMark(f.country)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.country.name, fontWeight = FontWeight.Medium)
            Text(
                note ?: evidenceLine(f.evidence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (checked != null) Checkbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun Denied(onRetry: () -> Unit) {
    val context = LocalContext.current
    Message(
        "Stamp needs to see your photos",
        "Without photo access the scan has nothing to read. You can still mark every country by hand. " +
            "To scan later, allow Photos for Stamp in Settings.",
        "Open Settings",
        {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)),
            )
        },
        secondary = "Ask again" to onRetry,
    )
}

@Composable
private fun Message(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    secondary: Pair<String, () -> Unit>? = null,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
        secondary?.let { (label, go) -> TextButton(onClick = go, modifier = Modifier.fillMaxWidth()) { Text(label) } }
    }
}

/** "1 country", "4 countries". */
fun countries(n: Int) = if (n == 1) "1 country" else "$n countries"
