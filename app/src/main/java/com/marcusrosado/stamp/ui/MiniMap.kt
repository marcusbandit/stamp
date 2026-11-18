package com.marcusrosado.stamp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.marcusrosado.stamp.data.Atlas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TOP_LAT = 84.0
private const val BOTTOM_LAT = -57.0

/** What one picture of the land was drawn with: its size, the sea's hairline and each country's fill. */
private data class LandKey(val w: Int, val h: Int, val line: Int, val fills: List<Int>)

/** Drawn pictures of the land, so opening the passport again shows the map at once. */
private val lands = object : LinkedHashMap<LandKey, ImageBitmap>(8, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LandKey, ImageBitmap>?) = size > 4
}

/**
 * A flat, still picture of the world with each country in its mark's colour. Used where a
 * live map would be too much: the passport header and the onboarding pages.
 */
@Composable
fun MiniMap(
    atlas: Atlas,
    colorOf: (String) -> Color?,
    modifier: Modifier = Modifier,
    /** Places been to, drawn as warm glows over the countries; the onboarding's heat map preview. */
    heat: List<com.marcusrosado.stamp.data.HeatCell> = emptyList(),
) {
    val palette = LocalMapPalette.current
    val countries = remember(atlas) { atlas.countries.filter { it.id != "ATA" } }
    // A few hundred lookups, done again only when the marks or the look change.
    val fills = remember(countries, colorOf, palette.miniLand) { countries.map { (colorOf(it.id) ?: palette.miniLand).toArgb() } }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio((360.0 / (TOP_LAT - BOTTOM_LAT)).toFloat())
            .drawWithCache {
                val w = size.width
                val h = size.height
                val most = heat.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                val glow = w / 40f
                onDrawWithContent {
                    drawContent()
                    for (c in heat) {
                        val at = androidx.compose.ui.geometry.Offset(
                            ((c.lon + 180.0) / 360.0 * w).toFloat(),
                            ((TOP_LAT - c.lat) / (TOP_LAT - BOTTOM_LAT) * h).toFloat(),
                        )
                        val k = 0.35f + 0.65f * (c.count.toFloat() / most)
                        val r = glow * (0.6f + k)
                        drawCircle(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(HeatHot.copy(alpha = 0.9f * k), HeatWarm.copy(alpha = 0.5f * k), HeatWarm.copy(alpha = 0f)),
                                center = at, radius = r,
                            ),
                            radius = r, center = at,
                        )
                    }
                }
            },
    ) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        if (w <= 0 || h <= 0) return@BoxWithConstraints
        val key = LandKey(w, h, palette.ocean.toArgb(), fills)
        // About a hundred thousand points made into paths took the main thread for a frame or more
        // on every open and every new mark. The land is drawn once, off it, and a new mark keeps
        // the last picture up until the next one is ready, so the map never blanks.
        val land by produceState(synchronized(lands) { lands[key] }, key) {
            value = synchronized(lands) { lands[key] }
                ?: withContext(Dispatchers.Default) { renderLand(countries, key) }
                    .also { synchronized(lands) { lands[key] = it } }
        }
        // A picture already drawn for these marks shows on this frame, not one frame late.
        (synchronized(lands) { lands[key] } ?: land)?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

private fun renderLand(countries: List<com.marcusrosado.stamp.data.Country>, key: LandKey): ImageBitmap {
    val (w, h) = key
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL }
    val line = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 0.6f
        color = key.line
    }
    countries.forEachIndexed { n, country ->
        val path = android.graphics.Path().apply { fillType = android.graphics.Path.FillType.EVEN_ODD }
        for (s in country.shapes) for (r in listOf(s.outer) + s.holes) {
            for (i in r.xs.indices) {
                val x = ((r.xs[i] + 180.0) / 360.0 * w).toFloat()
                val y = ((TOP_LAT - r.ys[i]) / (TOP_LAT - BOTTOM_LAT) * h).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        fill.color = key.fills[n]
        c.drawPath(path, fill)
        c.drawPath(path, line)
    }
    return bmp.asImageBitmap()
}

/** The heat map's colours, the same on the map and in previews. */
val HeatHot = Color(0xFFEE5253)
val HeatWarm = Color(0xFFFF9F43)
