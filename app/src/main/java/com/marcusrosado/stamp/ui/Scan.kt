package com.marcusrosado.stamp.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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

private enum class Mode { Choose, Denied }

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
    onAdd: (found: Map<String, PhotoEvidence>, mark: Set<String>) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(Mode.Choose) }
    val ask = rememberPhotoAccess(onGranted = { mode = Mode.Choose; onStartDevice() }, onDenied = { mode = Mode.Denied })
    BackHandler { onClose() }

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 680.dp).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Countries in your photos",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
            }
            when {
                state is ScanState.Running -> Running(state)
                state is ScanState.Done -> Review(
                    state, visits, home, onAdd,
                    onAgain = ask,
                )
                state is ScanState.Failed -> Message(
                    "That did not work",
                    "Something went wrong while reading: ${state.message}",
                    "Try again", { onReset(); ask() },
                )
                mode == Mode.Denied -> Denied(onRetry = ask)
                else -> Choose(onDevice = ask, onSkip = onClose)
            }
        }
    }
}

@Composable
private fun Choose(onDevice: () -> Unit, onSkip: () -> Unit) {
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
    onAdd: (found: Map<String, PhotoEvidence>, mark: Set<String>) -> Unit,
    onAgain: () -> Unit,
) {
    if (state.found.isEmpty()) {
        val body = when {
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
    val picked = remember(state) {
        mutableStateMapOf<String, Boolean>().apply { state.found.forEach { put(it.country.id, tickable(it.country.id)) } }
    }
    val newCount = state.found.count { tickable(it.country.id) && picked[it.country.id] == true }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Found ${countries(state.found.size)} in %,d photos with a location.".format(state.located),
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
                    checked = if (tickable(f.country.id)) picked[f.country.id] == true else null,
                    note = note,
                    onToggle = { picked[f.country.id] = !(picked[f.country.id] ?: false) },
                )
            }
        }
        Button(
            onClick = {
                // Dates go onto every found country that is or will be marked.
                val mark = state.found.map { it.country.id }.filter { tickable(it) && picked[it] == true }.toSet()
                onAdd(state.found.associate { it.country.id to it.evidence }, mark)
            },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text(if (newCount == 0) "Done" else if (newCount == 1) "Add 1 country" else "Add $newCount countries")
        }
    }
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
