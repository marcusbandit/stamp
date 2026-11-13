package com.marcusrosado.stamp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.Travel

/**
 * A leg drawn flat the way the map draws it: each way's own line in turn, a flight's airports
 * as codes along it with the ground on either side, and the way's icon gliding from one end to
 * the other and starting over, so the timeline moves like the map does. It only animates while
 * it is on screen; the list drops it once it scrolls away.
 */
@Composable
fun TravelStrip(ways: List<Travel>, via: List<String>, modifier: Modifier = Modifier, gap: Color = MaterialTheme.colorScheme.surface) {
    val p = LocalMapPalette.current
    val painters = ways.distinct().associateWith { w -> key(w) { rememberVectorPainter(w.icon) } }
    val measurer = rememberTextMeasurer()
    val codeStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = p.travel(Travel.Plane))
    val plane = ways.indexOf(Travel.Plane)
    val flight = plane >= 0 && via.size >= 2
    // Measured once per leg, not on every frame of the glide.
    val labels = remember(via, codeStyle, flight) { if (flight) via.map { measurer.measure(it, codeStyle) } else emptyList() }
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val stretches = if (flight) via.size - 1 + (ways.size - 1) else ways.size.coerceAtLeast(1)
    val t by rememberInfiniteTransition(label = "glide").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400 + 1100 * stretches, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Canvas(modifier.height(30.dp)) {
        val y = size.height / 2
        val r = 9.dp.toPx()
        val pad = 6.dp.toPx()
        // The stretches of line, each with its way, from left to right.
        val segs = ArrayList<Triple<Float, Float, Travel?>>()
        fun ground(from: Float, to: Float, list: List<Travel>) {
            if (list.isEmpty() || to - from < 1f) return
            val each = (to - from) / list.size
            list.forEachIndexed { i, w -> segs += Triple(from + each * i, from + each * (i + 1), w) }
        }
        if (flight) {
            val before = ways.take(plane)
            val after = ways.drop(plane + 1)
            // The flight takes the middle when there is ground travel either side of it.
            val a = if (before.isEmpty()) 0f else size.width * 0.22f
            val b = if (after.isEmpty()) size.width else size.width * 0.78f
            val widths = labels.map { it.size.width.toFloat() }
            val room = (b - a) - widths.sum()
            // Codes evenly spaced by the gaps between them; too many to fit, the layovers are dots.
            val tight = labels.size > 2 && room / (labels.size - 1) < pad * 2 + 16.dp.toPx()
            val shown = if (tight) listOf(0, labels.size - 1) else labels.indices.toList()
            val gapBetween = ((b - a) - shown.sumOf { widths[it].toDouble() }.toFloat()) / (shown.size - 1)
            var x = a
            val boxes = shown.map { i -> val box = x to x + widths[i]; x += widths[i] + gapBetween; i to box }
            ground(r, a - pad, before)
            for (k in 0 until boxes.size - 1) segs += Triple(boxes[k].second.second + pad, boxes[k + 1].second.first - pad, Travel.Plane)
            ground(b + pad, size.width - r, after)
            for ((s0, s1, w) in segs) drawWay(w, s0, s1, y, p, gap)
            for ((i, box) in boxes) {
                val l = labels[i]
                drawText(l, topLeft = Offset(box.first, y - l.size.height / 2f))
            }
            if (tight) {
                val (s0, s1, _) = segs.first { it.third == Travel.Plane }
                for (k in 1 until labels.size - 1) endDot(Offset(s0 + (s1 - s0) * k / (labels.size - 1), y), gap, codeStyle.color)
            }
            if (before.isNotEmpty()) endDot(Offset(r, y), gap, dot)
            if (after.isNotEmpty()) endDot(Offset(size.width - r, y), gap, dot)
        } else {
            ground(r, size.width - r, ways)
            if (segs.isEmpty()) segs += Triple(r, size.width - r, null)
            for ((s0, s1, w) in segs) drawWay(w, s0, s1, y, p, gap)
            // The two ends, and where one way hands over to the next.
            for (s in segs) endDot(Offset(s.first, y), gap, dot)
            endDot(Offset(segs.last().second, y), gap, dot)
        }
        // The icon on its way: eased off the start, faded in and out at the ends.
        val total = segs.sumOf { (it.second - it.first).coerceAtLeast(0f).toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        val e = t * t * (3 - 2 * t)
        var left = e * total
        var at = segs.last()
        var x = at.second
        for (s in segs) {
            val len = (s.second - s.first).coerceAtLeast(0f)
            if (left <= len) { at = s; x = s.first + left; break }
            left -= len
        }
        val way = at.third ?: return@Canvas
        val painter = painters[way] ?: return@Canvas
        val fade = (minOf(t, 1 - t) / 0.1f).coerceIn(0f, 1f)
        glider(painter, way, Offset(x, y), r, p.travel(way), gap, fade)
    }
}

private fun DrawScope.endDot(at: Offset, gap: Color, ring: Color) {
    drawCircle(gap, 4.dp.toPx(), at)
    drawCircle(ring, 4.dp.toPx(), at, style = Stroke(1.6.dp.toPx()))
}

private fun DrawScope.glider(painter: VectorPainter, way: Travel, at: Offset, r: Float, color: Color, gap: Color, alpha: Float) {
    if (way == Travel.Plane) {
        // The plane itself, nose first, with a ring of the background so it lifts off the line.
        drawCircle(gap, r * 0.95f, at, alpha = alpha)
        val s = r * 2.2f
        rotate(90f, pivot = at) {
            translate(at.x - s / 2, at.y - s / 2) {
                with(painter) { draw(Size(s, s), alpha = alpha, colorFilter = ColorFilter.tint(color)) }
            }
        }
    } else {
        drawCircle(gap, r + 1.5.dp.toPx(), at, alpha = alpha)
        drawCircle(color, r, at, alpha = alpha)
        val s = r * 1.3f
        translate(at.x - s / 2, at.y - s / 2) {
            with(painter) { draw(Size(s, s), alpha = alpha, colorFilter = ColorFilter.tint(gap)) }
        }
    }
}
