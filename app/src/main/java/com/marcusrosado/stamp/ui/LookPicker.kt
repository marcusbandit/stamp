package com.marcusrosado.stamp.ui

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.TravelState

/** A few marks so each preview shows a map that has been travelled. */
private val PreviewMarks = mapOf(
    "JPN" to TravelState.Visited, "FRA" to TravelState.Visited, "BRA" to TravelState.Visited,
    "AUS" to TravelState.Want, "CAN" to TravelState.Want, "DNK" to TravelState.Lived,
    "MEX" to TravelState.Visited, "ZAF" to TravelState.Visited, "IND" to TravelState.Lived,
)

/**
 * Every look as a small card with the real world map in its colours. Tapping one switches the
 * whole app at once, so the choice is made by seeing it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookPicker(atlas: Atlas, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 12.dp
        val cols = maxOf(2, (maxWidth / 170.dp).toInt())
        // A hair under the exact share, so pixel rounding never pushes the last card to a new row.
        val w = (maxWidth - gap * (cols - 1)) / cols - 1.dp
        // A saved id no look has any more falls back to following the phone, and shows that way.
        val current = if (Looks.any { it.id == selected }) selected else AUTO_LOOK
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            LookCard(atlas, null, current == AUTO_LOOK, Modifier.width(w)) { onPick(AUTO_LOOK) }
            for (l in Looks) LookCard(atlas, l, current == l.id, Modifier.width(w)) { onPick(l.id) }
        }
    }
}

@Composable
private fun LookCard(atlas: Atlas, look: Look?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    // "Match phone" previews what it would pick now, not the look currently in use.
    val shown = look ?: phoneLook(androidx.compose.ui.platform.LocalContext.current, androidx.compose.foundation.isSystemInDarkTheme())
    Column(
        modifier
            .clip(shape)
            .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .background(shown.colors.surface),
    ) {
        Box {
            // The preview sits on the sea, so unmarked land is drawn as land, not as the
            // page-coloured land the passport uses.
            Box(Modifier.background(shown.map.ocean).padding(horizontal = 6.dp, vertical = 8.dp)) {
                MapPreview(atlas, shown.map)
            }
            if (on) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, contentDescription = "In use", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                look?.name ?: "Match phone",
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = shown.headings),
                fontWeight = FontWeight.SemiBold,
                color = shown.colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Box(Modifier.size(width = 22.dp, height = 12.dp).clip(RoundedCornerShape(6.dp)).background(shown.colors.primary))
        }
    }
}

private const val TOP_LAT = 84.0
private const val BOTTOM_LAT = -57.0

/** Rendered previews by palette and width, so a look is drawn once however often it is shown. */
private val previews = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 24
}

/**
 * A look's world map as one picture. Drawing every country path for eight cards on every frame
 * made the page judder while it scrolled; the picture is drawn once, off the main thread, and
 * scrolling only moves it.
 */
@Composable
private fun MapPreview(atlas: Atlas, map: MapPalette) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio((360.0 / (TOP_LAT - BOTTOM_LAT)).toFloat())) {
        val w = constraints.maxWidth
        if (w <= 0) return@BoxWithConstraints
        val h = (w * (TOP_LAT - BOTTOM_LAT) / 360.0).toInt().coerceAtLeast(1)
        val key = "${map.hashCode()}-$w"
        // A new key, a new look or size, draws afresh: the old picture would keep the old colours.
        val picture by produceState(synchronized(previews) { previews[key] }, key) {
            value = synchronized(previews) { previews[key] }
                ?: withContext(Dispatchers.Default) { renderPreview(atlas, map, w, h) }
                    .also { synchronized(previews) { previews[key] = it } }
        }
        picture?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

private fun renderPreview(atlas: Atlas, map: MapPalette, w: Int, h: Int): ImageBitmap {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 0.6f
        color = map.ocean.toArgb()
    }
    for (country in atlas.countries) {
        if (country.id == "ATA") continue
        val path = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
        for (s in country.shapes) for (r in listOf(s.outer) + s.holes) {
            for (i in r.xs.indices) {
                val x = ((r.xs[i] + 180.0) / 360.0 * w).toFloat()
                val y = ((TOP_LAT - r.ys[i]) / (TOP_LAT - BOTTOM_LAT) * h).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        fill.color = (PreviewMarks[country.id]?.let { map.of(it) } ?: map.land).toArgb()
        c.drawPath(path, fill)
        c.drawPath(path, line)
    }
    return bmp.asImageBitmap()
}
