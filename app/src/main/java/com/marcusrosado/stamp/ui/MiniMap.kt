package com.marcusrosado.stamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import com.marcusrosado.stamp.data.Atlas

private const val TOP_LAT = 84.0
private const val BOTTOM_LAT = -57.0

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
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio((360.0 / (TOP_LAT - BOTTOM_LAT)).toFloat())
            .drawWithCache {
                val w = size.width
                val h = size.height
                val paths = countries.map { c ->
                    val p = Path().apply { fillType = PathFillType.EvenOdd }
                    for (s in c.shapes) {
                        for (r in listOf(s.outer) + s.holes) {
                            for (i in r.xs.indices) {
                                val x = ((r.xs[i] + 180.0) / 360.0 * w).toFloat()
                                val y = ((TOP_LAT - r.ys[i]) / (TOP_LAT - BOTTOM_LAT) * h).toFloat()
                                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                            }
                            p.close()
                        }
                    }
                    c.id to p
                }
                val hairline = Stroke(width = 0.6f)
                val most = heat.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
                val glow = w / 40f
                onDrawBehind {
                    for ((id, p) in paths) {
                        drawPath(p, colorOf(id) ?: palette.miniLand)
                        drawPath(p, palette.ocean, style = hairline)
                    }
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
    )
}

/** The heat map's colours, the same on the map and in previews. */
val HeatHot = Color(0xFFEE5253)
val HeatWarm = Color(0xFFFF9F43)
