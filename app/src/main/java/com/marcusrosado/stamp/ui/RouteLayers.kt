package com.marcusrosado.stamp.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.marcusrosado.stamp.data.Travel
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * One leg of a trip as the map draws it: its line, and how the user went, if they said. A
 * [ghost] is a single point the line passes through, such as a layover's airport, drawn as a
 * hollow dot that marks nothing.
 */
data class MapRoute(
    val points: List<Pair<Double, Double>>,
    val way: Travel?,
    val ghost: Boolean = false,
    /** The trip the line belongs to, so a tap on it can open that trip. */
    val trip: String? = null,
)

internal const val SOURCE_ROUTES = "routes"
internal const val SOURCE_ROUTE_ICONS = "route-icons"

/** Every layer the routes draw with, for the switch in Settings that hides them. */
internal val ROUTE_LAYERS: List<String> =
    listOf("routes-casing", "routes") + Travel.entries.map { "route-${it.key}" } +
        listOf("route-train-ties", "route-car-centre", "route-ghosts", "route-icons")

private fun width(lo: Double, hi: Double) = """["interpolate", ["linear"], ["zoom"], 1, $lo, 7, $hi]"""

/**
 * The route layers, for the style. Each way of travel has a line of its own in its own colour:
 * a flight a fine dash, a train rails with sleepers, a bus long dashes, public transport a dash
 * and a dot, a bike short dashes, a walk dots, a car a road with a centre line, a ferry a wave.
 * A leg with no way given keeps the plain dashed line.
 */
internal fun routeLayersJson(p: MapPalette): String {
    fun line(id: String, way: String?, color: String, w: String, dash: String? = null, cap: String = "round", extra: String = "") = """
    { "id": "$id", "type": "line", "source": "$SOURCE_ROUTES",
      ${if (way != null) """"filter": ["==", ["get", "way"], "$way"],""" else ""}
      "layout": { "line-cap": "$cap", "line-join": "round" },
      "paint": { "line-color": "$color", "line-width": $w${if (dash != null) """, "line-dasharray": $dash""" else ""}$extra } }"""
    val c = Travel.entries.associateWith { hex(p.travel(it)) }
    return listOf(
        line("routes-casing", null, hex(p.ocean), width(2.8, 6.0), extra = """, "line-opacity": 0.8"""),
        line("routes", "", hex(p.selected), width(1.2, 2.5), "[2, 1.5]"),
        line("route-plane", "plane", c.getValue(Travel.Plane), width(1.3, 2.4), "[2.5, 2]"),
        line("route-train-ties", "train", c.getValue(Travel.Train), width(4.5, 9.0), "[0.25, 1.1]", cap = "butt"),
        line("route-train", "train", c.getValue(Travel.Train), width(1.4, 2.6)),
        line("route-bus", "bus", c.getValue(Travel.Bus), width(2.0, 3.6), "[3, 0.9]", cap = "butt"),
        line("route-transit", "transit", c.getValue(Travel.Transit), width(1.6, 3.0), "[2.5, 1.2, 0.1, 1.2]"),
        line("route-bike", "bike", c.getValue(Travel.Bike), width(1.6, 3.0), "[1.4, 1.1]", cap = "butt"),
        line("route-walk", "walk", c.getValue(Travel.Walk), width(2.2, 4.0), "[0, 1.7]"),
        line("route-car", "car", c.getValue(Travel.Car), width(3.0, 6.0)),
        line("route-car-centre", "car", hex(p.ocean), width(0.7, 1.4), "[2.5, 2.5]", cap = "butt"),
        """
    { "id": "route-ferry", "type": "line", "source": "$SOURCE_ROUTES",
      "filter": ["==", ["get", "way"], "ferry"],
      "layout": { "line-join": "round" },
      "paint": { "line-pattern": "$FERRY_WAVE", "line-width": ${width(4.0, 8.0)} } }""",
        // Airports a flight passed through: hollow, so they never read as a place marked.
        """
    { "id": "route-ghosts", "type": "circle", "source": "$SOURCE_ROUTES",
      "filter": ["==", ["geometry-type"], "Point"],
      "paint": { "circle-radius": ["interpolate", ["linear"], ["zoom"], 1, 2.5, 7, 5.0],
                 "circle-opacity": 0,
                 "circle-stroke-color": "${hex(p.travel(Travel.Plane))}",
                 "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 1, 1.2, 7, 2.0] } }""",
    ).joinToString(",")
}

/** The icon layer, above the cities so a gliding plane is never hidden by a dot. */
internal val ROUTE_ICONS_JSON = """
    { "id": "route-icons", "type": "symbol", "source": "$SOURCE_ROUTE_ICONS",
      "layout": { "icon-image": ["get", "img"], "icon-rotate": ["get", "rot"],
                  "icon-rotation-alignment": "map", "icon-allow-overlap": true, "icon-ignore-placement": true,
                  "icon-size": ["interpolate", ["linear"], ["zoom"], 1, 0.7, 6, 1.0] },
      "paint": { "icon-opacity": ["get", "a"] } }"""

private const val FERRY_WAVE = "route-ferry-wave"

private fun imageName(way: Travel) = "route-way-${way.key}"

/** The routes' lines, each tagged with its way so the layers can pick their own. */
internal fun routeFeatures(routes: List<MapRoute>): FeatureCollection = FeatureCollection.fromFeatures(
    routes.mapNotNull { r ->
        when {
            r.ghost && r.points.isNotEmpty() -> Feature.fromGeometry(Point.fromLngLat(r.points[0].second, r.points[0].first))
            r.points.size >= 2 -> Feature.fromGeometry(LineString.fromLngLats(r.points.map { (lat, lon) -> Point.fromLngLat(lon, lat) }))
            else -> null
        }?.apply { addStringProperty("way", r.way?.key ?: ""); addStringProperty("trip", r.trip ?: "") }
    },
)

/**
 * The pictures the routes draw with: a badge per way in its colour, a plane on its own, and
 * the ferry's wave. Drawn from the app's own icons, so they follow the look.
 */
internal fun addRouteImages(style: Style, p: MapPalette, painters: Map<Travel, VectorPainter>, density: Density) {
    for ((way, painter) in painters) style.addImage(imageName(way), badge(way, painter, p, density))
    style.addImage(FERRY_WAVE, wave(p, density))
}

private fun badge(way: Travel, painter: VectorPainter, p: MapPalette, density: Density): Bitmap {
    val plane = way == Travel.Plane
    val px = ((if (plane) 24 else 20) * density.density).roundToInt()
    val img = ImageBitmap(px, px)
    val color = p.travel(way)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(img), Size(px.toFloat(), px.toFloat())) {
        if (plane) {
            // A halo in the sea's colour around the plane, so it stands out on any land.
            val r = 1.4f * density.density
            for ((dx, dy) in listOf(-r to 0f, r to 0f, 0f to -r, 0f to r, -r to -r, r to r, -r to r, r to -r)) {
                translate(dx, dy) { inset(r) { with(painter) { draw(this@inset.size, colorFilter = ColorFilter.tint(p.ocean)) } } }
            }
            inset(r) { with(painter) { draw(this@inset.size, colorFilter = ColorFilter.tint(color)) } }
        } else {
            val half = size.minDimension / 2
            drawCircle(p.ocean, radius = half, center = Offset(half, half))
            drawCircle(color, radius = half - 1.5f * density.density, center = Offset(half, half))
            inset(size.minDimension * 0.2f) { with(painter) { draw(this@inset.size, colorFilter = ColorFilter.tint(p.ocean)) } }
        }
    }
    return img.asAndroidBitmap()
}

/** One period of the ferry's wave, which the line repeats along its length. */
private fun wave(p: MapPalette, density: Density): Bitmap {
    val d = density.density
    val w = (12 * d).roundToInt()
    val h = (8 * d).roundToInt()
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val path = android.graphics.Path()
    val mid = h / 2f
    val amp = h * 0.28f
    path.moveTo(0f, mid)
    path.cubicTo(w * 0.25f, mid - amp * 1.4f, w * 0.25f, mid - amp * 1.4f, w * 0.5f, mid)
    path.cubicTo(w * 0.75f, mid + amp * 1.4f, w * 0.75f, mid + amp * 1.4f, w.toFloat(), mid)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    paint.color = p.ocean.toArgb(); paint.strokeWidth = 3.6f * d
    c.drawPath(path, paint)
    paint.color = p.travel(Travel.Ferry).toArgb(); paint.strokeWidth = 1.7f * d
    c.drawPath(path, paint)
    return out
}

/**
 * A leg's icon on its way along the line. It moves in the map's own flat projection, so it
 * stays on the line the map draws, and takes longer over a longer leg, slowly either way.
 */
internal class Glide(route: MapRoute, private val phase: Double) {
    val way: Travel = route.way!!
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val along: DoubleArray
    val seconds: Double
    private val south: Double
    private val north: Double
    private val west: Double
    private val east: Double

    init {
        val pts = route.points
        xs = DoubleArray(pts.size) { pts[it].second }
        ys = DoubleArray(pts.size) { mercY(pts[it].first) }
        along = DoubleArray(pts.size)
        for (i in 1 until pts.size) along[i] = along[i - 1] + hypot(xs[i] - xs[i - 1], Math.toDegrees(ys[i] - ys[i - 1]))
        south = pts.minOf { it.first }; north = pts.maxOf { it.first }
        west = pts.minOf { it.second }; east = pts.maxOf { it.second }
        // About 111 km to a degree; 5 s for a short hop, up to 14 s for the other side of the world.
        val km = along.last() * 111.0
        seconds = (4.0 + 2.2 * log2(1 + km / 100)).coerceIn(5.0, 14.0)
    }

    /**
     * Whether any of the leg is in view. Its longitudes run on past 180 across the date line
     * while the camera's wrap, so both are compared a turn either way.
     */
    fun onScreen(b: LatLngBounds): Boolean {
        if (north < b.latitudeSouth || south > b.latitudeNorth) return false
        val w = b.longitudeWest
        val e = if (b.longitudeEast < w) b.longitudeEast + 360 else b.longitudeEast
        return (-1..1).any { k -> east + 360 * k >= w && west + 360 * k <= e }
    }

    /**
     * The stretch of the leg in view, as distances along it: zoomed in on part of a long leg,
     * the icon crosses only what can be seen and starts over at its edge, in the same time the
     * whole leg takes zoomed out, so it never spends most of its loop off screen.
     */
    private fun inView(b: LatLngBounds): Pair<Double, Double> {
        val total = along.last()
        if (total <= 0) return 0.0 to 0.0
        val w = b.longitudeWest
        val e = if (b.longitudeEast < w) b.longitudeEast + 360 else b.longitudeEast
        var first = -1.0
        var last = -1.0
        val n = 96
        for (k in 0..n) {
            val d = total * k / n
            val (x, y) = pointAt(d)
            val lat = latOf(y)
            if (lat < b.latitudeSouth || lat > b.latitudeNorth) continue
            if ((-1..1).none { j -> x + 360 * j in w..e }) continue
            if (first < 0) first = d
            last = d
        }
        // A short leg seen whole, or one crossing the view between two samples: all of it.
        if (first < 0 || last - first < total / n) return 0.0 to total
        return first to last
    }

    private fun pointAt(d: Double): Pair<Double, Double> {
        var i = along.indexOfFirst { it >= d }.coerceAtLeast(1)
        if (i >= xs.size) i = xs.size - 1
        val seg = (along[i] - along[i - 1]).takeIf { it > 0 } ?: 1.0
        val k = ((d - along[i - 1]) / seg).coerceIn(0.0, 1.0)
        return (xs[i - 1] + (xs[i] - xs[i - 1]) * k) to (ys[i - 1] + (ys[i] - ys[i - 1]) * k)
    }

    /** The icon at [t] seconds: where it is, which way it heads, and how faded it is. */
    fun at(t: Double, b: LatLngBounds): Feature {
        val f = ((t / seconds + phase) % 1.0)
        // Eased so it sets off and arrives gently, and faded in and out at the ends.
        val e = f * f * (3 - 2 * f)
        val (from, to) = inView(b)
        val target = from + e * (to - from)
        var i = along.indexOfFirst { it >= target }.coerceAtLeast(1)
        if (i >= xs.size) i = xs.size - 1
        val seg = (along[i] - along[i - 1]).takeIf { it > 0 } ?: 1.0
        val k = ((target - along[i - 1]) / seg).coerceIn(0.0, 1.0)
        val x = xs[i - 1] + (xs[i] - xs[i - 1]) * k
        val y = ys[i - 1] + (ys[i] - ys[i - 1]) * k
        val heading = Math.toDegrees(atan2(xs[i] - xs[i - 1], Math.toDegrees(ys[i] - ys[i - 1])))
        val fade = (minOf(f, 1 - f) / 0.08).coerceIn(0.0, 1.0)
        return Feature.fromGeometry(Point.fromLngLat(x, latOf(y))).apply {
            addStringProperty("img", imageName(way))
            addNumberProperty("rot", if (way == Travel.Plane) heading else 0.0)
            addNumberProperty("a", fade)
        }
    }

    private companion object {
        fun mercY(lat: Double) = com.marcusrosado.stamp.data.LegPath.mercator(lat)
        fun latOf(y: Double) = com.marcusrosado.stamp.data.LegPath.latitude(y)
    }
}
