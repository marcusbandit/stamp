package com.marcusrosado.stamp.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.marcusrosado.stamp.data.BestPhotos
import com.marcusrosado.stamp.data.Memory
import com.marcusrosado.stamp.data.PartialDate
import com.marcusrosado.stamp.data.Places
import com.marcusrosado.stamp.data.Trip
import com.marcusrosado.stamp.data.Visit
import com.marcusrosado.stamp.scan.Memories
import com.marcusrosado.stamp.scan.Suggestion
import java.time.LocalDate
import java.time.ZoneId

/** Trips asked about in this session, so Not now holds until the trip is opened again. */
private val pickAsked = mutableSetOf<String>()

/** A photo of the trip, with the stop it was taken at. */
private class Candidate(val shot: Suggestion, val stop: String)

/**
 * Asks for the best few photos of a trip made from the photos: every photo on this phone from
 * each stop's days near that stop, with [BestPhotos.PREFERRED] picked already, up to
 * [BestPhotos.MAX]. [onKeep] gets the picks as memories of their stops; [onNone] means the user
 * wants none. Not now closes it until the trip is opened again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickBest(trip: Trip, visit: Visit, places: Places?, onKeep: (List<Memory>) -> Unit, onNone: () -> Unit) {
    val context = LocalContext.current
    var closed by remember(trip.id) { mutableStateOf(trip.id in pickAsked) }
    if (closed) return
    val allowed = remember {
        val perms = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        perms.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }
    // Without photo access there is nothing to show; the trip asks again once there is.
    if (!allowed) return
    val found by produceState<List<Candidate>?>(null, trip.id) {
        value = candidates(context, trip, visit, places)
    }
    val all = found ?: return
    if (all.isEmpty()) {
        // No photo of this trip on the phone, as for one read from a Google Photos export.
        androidx.compose.runtime.LaunchedEffect(trip.id) { onNone() }
        return
    }
    val chosen = remember(all) {
        val shots = all.map { BestPhotos.Shot(it.shot.uri.toString(), it.shot.takenMs, trip.cities.indexOf(it.stop)) }
        val ids = BestPhotos.pick(shots).map { it.id }.toSet()
        mutableStateListOf<Candidate>().apply { addAll(all.filter { it.shot.uri.toString() in ids }) }
    }
    val haptics = LocalHapticFeedback.current
    val later = { pickAsked += trip.id; closed = true }
    val keep = {
        pickAsked += trip.id
        closed = true
        if (chosen.isEmpty()) onNone()
        else onKeep(chosen.map { Memory("", place = it.stop, media = it.shot.uri.toString(), takenMs = it.shot.takenMs) })
    }
    val body: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pick the best", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(
                    "${chosen.size} of ${BestPhotos.MAX}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(all, key = { it.shot.uri.toString() }) { c ->
                    val at = chosen.indexOf(c)
                    val on = at >= 0
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .border(if (on) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                            .semantics { selected = on; contentDescription = "Photo" }
                            .clickable {
                                when {
                                    on -> chosen.remove(c)
                                    chosen.size < BestPhotos.MAX -> chosen.add(c)
                                    else -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                    ) {
                        MediaImage(c.shot.uri, video = false, px = 288, modifier = Modifier.fillMaxSize())
                        if (on) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${at + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = later) { Text("Not now") }
                Spacer(Modifier.weight(1f))
                Button(onClick = keep) {
                    Text(
                        when (chosen.size) {
                            0 -> "Keep none"
                            1 -> "Keep 1 photo"
                            else -> "Keep ${chosen.size} photos"
                        },
                    )
                }
            }
        }
    }
    // A sheet on a phone; a dialog kept off the fold on the big screen, as adding a memory does.
    val big = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    if (big) {
        SeamDialog(onDismissRequest = later) {
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().fillMaxHeight(0.85f),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                body(Modifier.padding(top = 24.dp))
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = later, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            body(Modifier.fillMaxHeight(0.85f))
        }
    }
}

/** Every photo on this phone from each stop's days, taken near it, each once, oldest first. */
private suspend fun candidates(context: android.content.Context, trip: Trip, visit: Visit, places: Places?): List<Candidate> {
    val zone = ZoneId.systemDefault()
    fun startOf(d: PartialDate) = LocalDate.of(d.year, d.month ?: 1, d.day ?: 1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun endOf(d: PartialDate): Long {
        val first = LocalDate.of(d.year, d.month ?: 12, d.day ?: 1)
        val last = if (d.day != null) first else if (d.month != null) first.withDayOfMonth(first.lengthOfMonth()) else first.withDayOfMonth(31)
        return last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }
    val seen = HashSet<String>()
    val out = ArrayList<Candidate>()
    for (stop in trip.cities) {
        val from = trip.arrived[stop] ?: continue
        val to = trip.left[stop] ?: from
        val near = placeOf(stop, visit, places)?.let { it.lat to it.lon }
        for (s in Memories.suggest(context, startOf(from), endOf(to), near)) {
            if (!s.video && seen.add(s.uri.toString())) out += Candidate(s, stop)
        }
    }
    return out.sortedBy { it.shot.takenMs }
}
