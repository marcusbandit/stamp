package com.marcusrosado.stamp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Travel

@Suppress("DEPRECATION")
val Travel.icon: ImageVector
    get() = when (this) {
        Travel.Plane -> Icons.Filled.Flight
        Travel.Train -> Icons.Filled.Train
        Travel.Bus -> Icons.Filled.DirectionsBus
        Travel.Transit -> Icons.Filled.Tram
        Travel.Bike -> Icons.Filled.DirectionsBike
        Travel.Walk -> Icons.Filled.DirectionsWalk
        Travel.Car -> Icons.Filled.DirectionsCar
        Travel.Ferry -> Icons.Filled.DirectionsBoat
    }

/**
 * Each way of travel's colour, made from the look itself: the hue wheel turned from the
 * visited colour in even steps, so no way takes the visited hue, at the saturation of the look's
 * own marks and a lightness that reads on its sea and land.
 */
fun MapPalette.travel(way: Travel): Color = travelColors(this).getValue(way)

private val cache = HashMap<MapPalette, Map<Travel, Color>>()

fun travelColors(p: MapPalette): Map<Travel, Color> = synchronized(cache) {
    cache.getOrPut(p) {
        val (h0, s0, _) = hsl(p.visited)
        val dark = p.ocean.luminance() < 0.3f
        val s = (s0 * 0.95f).coerceIn(0.45f, 0.85f)
        val l = if (dark) 0.68f else 0.45f
        // Steps of 45 degrees, half a step off the visited hue. The plane, the most common
        // long line, gets the step opposite it.
        val step = mapOf(
            Travel.Ferry to 0, Travel.Bus to 1, Travel.Bike to 2, Travel.Plane to 3,
            Travel.Car to 4, Travel.Transit to 5, Travel.Walk to 6, Travel.Train to 7,
        )
        Travel.entries.associateWith { w -> fromHsl((h0 + 22.5f + 45f * step.getValue(w)) % 360f, s, l) }
    }
}

private fun hsl(c: Color): Triple<Float, Float, Float> {
    val out = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(c.toArgb(), out)
    return Triple(out[0], out[1], out[2])
}

private fun fromHsl(h: Float, s: Float, l: Float) = Color(androidx.core.graphics.ColorUtils.HSLToColor(floatArrayOf(h, s, l)))

/**
 * [way]'s line as the map draws it, flat from [x0] to [x1] at height [y]: the same dashes,
 * sleepers, road and wave, so a legend or a leg on the timeline reads like the map. [gap] is
 * the colour drawn under it and in a road's centre line.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWay(
    way: Travel?, x0: Float, x1: Float, y: Float, p: MapPalette, gap: Color = p.ocean,
) {
    if (x1 - x0 < 1f) return
    val d = density
    val c = way?.let { p.travel(it) } ?: p.selected
    fun dashes(vararg on: Float) = androidx.compose.ui.graphics.PathEffect.dashPathEffect(on.map { it * d }.toFloatArray())
    fun line(width: Float, color: Color = c, effect: androidx.compose.ui.graphics.PathEffect? = null,
             cap: androidx.compose.ui.graphics.StrokeCap = androidx.compose.ui.graphics.StrokeCap.Round) =
        drawLine(color, androidx.compose.ui.geometry.Offset(x0, y), androidx.compose.ui.geometry.Offset(x1, y), width * d, cap, effect)
    val butt = androidx.compose.ui.graphics.StrokeCap.Butt
    line(6f, gap)
    when (way) {
        // As on the map: a plane's dashes are longer and wider apart than a leg not said.
        null -> line(2f, effect = dashes(4f, 3f))
        Travel.Plane -> line(2.2f, effect = dashes(5.5f, 4.4f))
        Travel.Train -> { line(8f, effect = dashes(2f, 7f), cap = butt); line(2.4f) }
        Travel.Bus -> line(3.4f, effect = dashes(10f, 3f), cap = butt)
        Travel.Transit -> line(2.6f, effect = dashes(7f, 3.5f, 0.1f, 3.5f))
        Travel.Bike -> line(2.6f, effect = dashes(4f, 3f), cap = butt)
        Travel.Walk -> line(3.6f, effect = dashes(0.1f, 6f))
        Travel.Car -> { line(5.6f); line(1.4f, gap, dashes(4f, 4f), butt) }
        Travel.Ferry -> {
            val path = androidx.compose.ui.graphics.Path()
            val w = 12 * d
            var x = x0
            path.moveTo(x, y)
            while (x + w <= x1 + 0.5f) {
                path.cubicTo(x + w * 0.25f, y - 3 * d, x + w * 0.25f, y - 3 * d, x + w * 0.5f, y)
                path.cubicTo(x + w * 0.75f, y + 3 * d, x + w * 0.75f, y + 3 * d, x + w, y)
                x += w
            }
            if (x < x1) path.lineTo(x1, y)
            drawPath(path, gap, style = androidx.compose.ui.graphics.drawscope.Stroke(3.6f * d))
            drawPath(path, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * d))
        }
    }
}

/** A short sample of [way]'s line as the map draws it, for legends. */
@Composable
fun WaySample(way: Travel?, modifier: Modifier = Modifier) {
    val p = LocalMapPalette.current
    androidx.compose.foundation.Canvas(modifier.clipToBounds()) {
        drawWay(way, 4 * density, size.width - 4 * density, size.height / 2, p)
    }
}

/** Every way of travel with its icon, its colour and its line: two to a row where there is room, else one. */
@Composable
fun TravelLegend(modifier: Modifier = Modifier) {
    val p = LocalMapPalette.current
    BoxWithConstraints(modifier) {
        val perRow = if (maxWidth < 420.dp) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (Travel.entries.map { it as Travel? } + null).chunked(perRow).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { w ->
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            if (w != null) Icon(w.icon, contentDescription = null, tint = p.travel(w), modifier = Modifier.size(18.dp))
                            else Spacer(Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                w?.label ?: "Not said",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            WaySample(w, Modifier.width(56.dp).height(16.dp))
                        }
                    }
                }
            }
        }
    }
}
