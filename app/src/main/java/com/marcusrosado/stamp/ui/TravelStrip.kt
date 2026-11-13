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
 * A leg drawn flat the way the map draws it: each step's own line in turn, the stops between
 * them named along it where there is room and dots where there is not, and the way's icon
 * gliding from one end to the other and starting over, so the timeline moves like the map does.
 * [ways] holds each step's way, null where the user has not said; [stops] the names of the
 * stops between the steps, null for one with no name. It only animates while it is on screen;
 * the list drops it once it scrolls away.
 */
@Composable
fun TravelStrip(ways: List<Travel?>, stops: List<String?>, modifier: Modifier = Modifier, gap: Color = MaterialTheme.colorScheme.surface) {
    val p = LocalMapPalette.current
    val painters = ways.filterNotNull().distinct().associateWith { w -> key(w) { rememberVectorPainter(w.icon) } }
    val measurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    val steps = ways.ifEmpty { listOf(null) }
    val between = (0 until steps.size - 1).map { stops.getOrNull(it) }
    // Measured once per leg, not on every frame of the glide.
    val labels = remember(between, nameStyle) { between.map { n -> n?.let { measurer.measure(it, nameStyle, maxLines = 1) } } }
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val t by rememberInfiniteTransition(label = "glide").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400 + 1100 * steps.size, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Canvas(modifier.height(30.dp)) {
        val y = size.height / 2
        val r = 9.dp.toPx()
        val pad = 6.dp.toPx()
        val least = 22.dp.toPx()
        val room = size.width - 2 * r
        // Names where they fit with a little line either side: all of them, else only the
        // airports' codes, else none.
        fun fits(shown: List<Boolean>) =
            labels.indices.sumOf { if (shown[it]) labels[it]!!.size.width + 2 * pad.toDouble() else 0.0 } + least * steps.size <= room
        val all = labels.map { it != null }
        val codes = labels.indices.map { all[it] && between[it]!!.length <= 3 && between[it]!!.all { c -> c.isUpperCase() } }
        val shown = listOf(all, codes).firstOrNull { fits(it) } ?: labels.map { false }
        val taken = labels.indices.sumOf { if (shown[it]) labels[it]!!.size.width + 2 * pad.toDouble() else 0.0 }.toFloat()
        val each = (room - taken) / steps.size
        // The stretches of line, each with its way, from left to right.
        val segs = ArrayList<Triple<Float, Float, Travel?>>()
        val marks = ArrayList<Pair<Float, Int>>()
        var pen = r
        steps.forEachIndexed { i, w ->
            segs += Triple(pen, pen + each, w)
            pen += each
            if (i < labels.size) {
                if (shown[i]) { marks += pen + pad to i; pen += labels[i]!!.size.width + 2 * pad }
                else marks += pen to -1
            }
        }
        for ((s0, s1, w) in segs) drawWay(w, s0, s1, y, p, gap)
        for ((at, i) in marks) {
            if (i < 0) endDot(Offset(at, y), gap, dot)
            else labels[i]!!.let { l -> drawText(l, topLeft = Offset(at, y - l.size.height / 2f)) }
        }
        endDot(Offset(r, y), gap, dot)
        endDot(Offset(size.width - r, y), gap, dot)
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
