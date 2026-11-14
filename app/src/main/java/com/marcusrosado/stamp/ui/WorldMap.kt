package com.marcusrosado.stamp.ui

import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import com.marcusrosado.stamp.StampApp
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.FineShapes
import com.marcusrosado.stamp.data.Shape
import com.marcusrosado.stamp.data.TravelState
import com.marcusrosado.stamp.data.Visit
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point

/** Where the camera was, so leaving the map tab and coming back lands in the same place. */
class CameraSpot(var lat: Double = 30.0, var lon: Double = 10.0, var zoom: Double = 0.6)

/**
 * The interactive world map. Country shapes come from the bundled GeoJSON; there are no
 * tiles, so it draws instantly and works with no connection.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun WorldMap(
    atlas: Atlas,
    visits: Map<String, Visit>,
    /** Separate land masses take their own marks and taps; otherwise a whole country does. */
    split: Boolean,
    /** Cities and pins on any visit, as latitude and longitude, drawn as dots. */
    cities: List<Pair<Double, Double>>,
    /** The addresses lived at, drawn as house markers. */
    homes: List<Pair<Double, Double>> = emptyList(),
    /** Where the phone is, after the locate button: a dot. */
    me: Pair<Double, Double>? = null,
    /**
     * Outlines of the big cities among them. They sit on a visited or lived country, so they are
     * shaded and edged in the sea's colour, which stands apart from every mark colour.
     */
    areas: List<List<Shape>> = emptyList(),
    /** Every leg of every trip, each drawn in the way the user went. */
    routes: List<MapRoute> = emptyList(),
    /** One photo per place that has any, shown as a round preview from city zoom in. */
    photos: List<MapPhoto> = emptyList(),
    /** Whether the dashed route lines between a visit's cities are drawn. */
    showRoutes: Boolean = true,
    selectedId: String?,
    camera: CameraSpot,
    focus: Focus?,
    onFocused: () -> Unit,
    onTap: (String?) -> Unit,
    /** Puts the selection back without moving the map: a double tap undoing its first tap. */
    onRestore: (String?) -> Unit = onTap,
    /** False for a map that only shows: taps pick nothing and draw no outline. */
    selectable: Boolean = true,
    modifier: Modifier = Modifier,
    /**
     * How much of the map's right and bottom edge a pane covers, in dp. The map keeps its full
     * size and moves its centre into the part left showing, so a pane sliding in never makes
     * the map redraw at a new size on every frame.
     */
    coveredEnd: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    coveredBottom: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    /**
     * Opens what a tapped line is: each trip on the line nearest the finger with the leg it
     * draws; null leaves lines to taps on the country under them.
     */
    onRoute: ((List<RouteTap>) -> Unit)? = null,
    /** Countries drawn as their regions, each region in its own mark; null draws none. */
    regionLayer: RegionLayer? = null,
    /** Country ids whose whole is done, outlined in gold: every region, or a whole continent. */
    complete: Set<String> = emptySet(),
    /** Countries plain, with no marks: the city view, where the cities carry the colour. */
    plain: Boolean = false,
    /** Cities the user wants to go to, drawn as dots in the want colour. */
    wantCities: List<Pair<Double, Double>> = emptyList(),
    /** A tapped region, as its country id and region id; null leaves taps to the countries. */
    onRegion: ((String, String) -> Unit)? = null,
    /** Every city worth a dot, for the cities view: more of them as the map zooms in. */
    allCities: List<CityDot> = emptyList(),
    /** A tapped city of [allCities], by id; null leaves taps to the countries. */
    onCity: ((String) -> Unit)? = null,
    /** False while a page covers the whole map: its icons stop gliding until it shows again. */
    animate: Boolean = true,
) {
    val context = LocalContext.current
    val palette = LocalMapPalette.current
    val tap by rememberUpdatedState(onTap)
    val restore by rememberUpdatedState(onRestore)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    // The 1:10m shapes for the selected outline up close. The map's own source reads the same
    // file; this copy only serves one country at a time to the outline.
    var fine by remember { mutableStateOf<FineShapes?>(null) }
    LaunchedEffect(Unit) { fine = (context.applicationContext as StampApp).fineShapes.await() }
    // The map view below is built once; it reads these through the updated state.
    val outline by rememberUpdatedState(remember(atlas, split, fine) { Outline(atlas, split, fine) })
    val selected by rememberUpdatedState(selectedId)
    val tapKey by rememberUpdatedState(if (split) "id" else "country")
    val routeTap by rememberUpdatedState(onRoute.takeIf { showRoutes })
    val regionTap by rememberUpdatedState(onRegion.takeIf { regionLayer != null })
    val cityTap by rememberUpdatedState(onCity.takeIf { allCities.isNotEmpty() })

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            // MapLibre's own click listener waits out the double-tap timeout, about 300 ms, before
            // it reports a tap. Taps are read here instead, the moment the finger lifts; a double
            // tap still zooms, since MapLibre sees every event too.
            var moving = false
            var stoppedMotion = false
            var beforeTap: String? = null
            // Trips on a line just tapped, opened once the tap is sure not to be the first of a
            // double tap, which means zoom.
            var tappedTrips: List<RouteTap>? = null
            val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean {
                    // A finger that stops a fling or a fly-to is not choosing a country.
                    stoppedMotion = moving
                    return false
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!selectable) return false
                    if (stoppedMotion) return false
                    val m = map ?: return false
                    // A line first, with a little slack since lines are thin; a tap clear of any line
                    // still picks the country under it.
                    tappedTrips = null
                    if (routeTap != null) {
                        val trips = nearestRoutes(m, e.x, e.y, context.resources.displayMetrics.density)
                        if (trips.isNotEmpty()) { tappedTrips = trips; return false }
                    }
                    // In the cities view a city under the finger takes the tap, with a little slack.
                    cityTap?.let { onC ->
                        val slack = 12 * context.resources.displayMetrics.density
                        val f = m.queryRenderedFeatures(android.graphics.RectF(e.x - slack, e.y - slack, e.x + slack, e.y + slack), *ALL_CITY_LAYERS)
                            .minByOrNull { f -> (f.geometry() as? Point)?.let { p -> val s = m.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(p.latitude(), p.longitude())); (s.x - e.x) * (s.x - e.x) + (s.y - e.y) * (s.y - e.y) } ?: Float.MAX_VALUE }
                        f?.getStringProperty("id")?.let { onC(it); return false }
                    }
                    // A region of a country drawn as its regions takes the tap before its country.
                    regionTap?.let { onReg ->
                        val f = m.queryRenderedFeatures(PointF(e.x, e.y), LAYER_REGIONS).firstOrNull()
                        val rid = f?.getStringProperty("region")
                        val cid = f?.getStringProperty("country")
                        if (rid != null && cid != null) { onReg(cid, rid); return false }
                    }
                    // Both fills, since only one of them draws away from the switch-over zoom.
                    val id = m.queryRenderedFeatures(PointF(e.x, e.y), LAYER_FILL_FINE, LAYER_FILL).firstOrNull()?.getStringProperty(tapKey)
                    beforeTap = selected
                    // Outline first, straight on the map, so it shows on the next frame
                    // rather than after Compose has caught up.
                    style?.let { outline.show(it, id) }
                    tap(id)
                    return false
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    tappedTrips?.let { trips -> tappedTrips = null; routeTap?.invoke(trips) }
                    return false
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    tappedTrips = null
                    if (!selectable) return false
                    // The first tap of a double tap already changed the selection; a double tap
                    // means zoom, so put it back.
                    style?.let { outline.show(it, beforeTap) }
                    restore(beforeTap)
                    return false
                }
            })
            setOnTouchListener { _, e -> taps.onTouchEvent(e); false }
            val view = this
            getMapAsync { m ->
                m.uiSettings.apply {
                    isLogoEnabled = false
                    isAttributionEnabled = false
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                    isCompassEnabled = false
                }
                // Zoomed all the way out, the world still fills the map from top to bottom: a
                // shorter world leaves empty sea above or below it, and once a card has moved
                // the map up, all of that gap sits at the bottom. The world is 512 dp tall at
                // zoom 0, so the least zoom follows the map's height, folded or not.
                fun fillHeight() {
                    val h = view.height / view.resources.displayMetrics.density
                    if (h > 0) m.setMinZoomPreference(kotlin.math.max(0.0, kotlin.math.ln(h / 512.0) / kotlin.math.ln(2.0)))
                }
                fillHeight()
                view.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                    if (bottom - top != oldBottom - oldTop) fillHeight()
                }
                // Deep enough to see one city's streets' worth of area. The 1:10m country shapes
                // take over from mid zoom, and the city outlines and dots stay sharp.
                m.setMaxZoomPreference(12.0)
                m.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(camera.lat, camera.lon)).zoom(camera.zoom).build()
                m.addOnCameraMoveStartedListener { moving = true }
                m.addOnCameraIdleListener {
                    moving = false
                    m.cameraPosition.target?.let { camera.lat = it.latitude; camera.lon = it.longitude }
                    camera.zoom = m.cameraPosition.zoom
                }
                map = m
            }
        }
    }

    // The style carries the palette, so a light/dark switch rebuilds it.
    LaunchedEffect(map, palette) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromJson(styleJson(palette))) {
            it.addImage(IMAGE_HOME, homeMarker(context, palette.lived.toArgb()))
            outline.reset()
            style = it
        }
    }

    val regionCountryIds = remember(regionLayer?.shapes) { regionLayer?.shapes.orEmpty().map { it.first }.distinct() }
    LaunchedEffect(style, visits, palette, split, plain, regionCountryIds) {
        val s = style ?: return@LaunchedEffect
        if (plain) {
            for (id in listOf(LAYER_FILL, LAYER_FILL_FINE)) s.getLayerAs<FillLayer>(id)?.setProperties(PropertyFactory.fillColor(Expression.color(palette.land.toArgb())))
            return@LaunchedEffect
        }
        // Split, each land mass by its own id. Whole, every part by its country's mark.
        val marks: Map<String, TravelState> = if (split) {
            visits.mapValues { it.value.state }
        } else {
            visits.keys.map { atlas.countryOf(it) }.distinct()
                .mapNotNull { c -> atlas.markOf(c, visits, split = false)?.let { c to it } }.toMap()
        }
        val stops = marks.map { (id, st) -> Expression.stop(id, Expression.color(palette.of(st).toArgb())) }
        val fill = if (stops.isEmpty()) {
            Expression.color(palette.land.toArgb())
        } else {
            Expression.match(Expression.get(if (split) "id" else "country"), Expression.color(palette.land.toArgb()), *stops.toTypedArray())
        }
        // A country drawn as its regions is plain underneath, so coast its regions leave out
        // shows no mark of its own.
        // Nothing of it at all: its regions are cut from the same shape and cover all of it.
        val inRegions = Expression.`in`(Expression.get("country"), Expression.literal(regionCountryIds.toTypedArray()))
        val shown = if (regionCountryIds.isEmpty()) fill else Expression.switchCase(inRegions, Expression.rgba(0, 0, 0, 0), fill)
        for (id in listOf(LAYER_FILL, LAYER_FILL_FINE)) s.getLayerAs<FillLayer>(id)?.setProperties(PropertyFactory.fillColor(shown))
        // Its own border too: the regions draw their outline, coast included.
        for (id in listOf("countries-border", "countries-border-fine")) {
            s.getLayerAs<org.maplibre.android.style.layers.LineLayer>(id)?.setFilter(Expression.not(inRegions))
        }
    }

    LaunchedEffect(style, showRoutes) {
        val s = style ?: return@LaunchedEffect
        val v = if (showRoutes) org.maplibre.android.style.layers.Property.VISIBLE else org.maplibre.android.style.layers.Property.NONE
        for (id in ROUTE_LAYERS) s.getLayer(id)?.setProperties(PropertyFactory.visibility(v))
    }

    LaunchedEffect(style, routes) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SOURCE_ROUTES)?.setGeoJson(routeFeatures(routes))
    }

    // Each way's badge, drawn from the app's icons in the look's colours.
    val wayPainters = com.marcusrosado.stamp.data.Travel.entries.associateWith {
        androidx.compose.ui.graphics.vector.rememberVectorPainter(it.icon)
    }
    val drawDensity = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(style) {
        val s = style ?: return@LaunchedEffect
        addRouteImages(s, palette, wayPainters, drawDensity)
    }

    // Each leg's icon glides along it, start to end, then starts over. Only the legs on screen
    // move; with none in view nothing is drawn, and the loop only checks back now and then.
    // One clock for the map's life, so an edit that redraws the routes leaves every icon where
    // it was; each leg's place in its loop comes from the leg itself, not from its order.
    val clock = remember { System.nanoTime() }
    val moving by rememberUpdatedState(animate)
    LaunchedEffect(style, routes, showRoutes) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val src = s.getSourceAs<GeoJsonSource>(SOURCE_ROUTE_ICONS) ?: return@LaunchedEffect
        val none = FeatureCollection.fromFeatures(emptyList())
        val glides = if (showRoutes) routes.filter { it.way != null && it.points.size >= 2 }.distinctBy { it.points to it.way }
            .map { r -> Glide(r, phase = ((r.points to r.way).hashCode() and 0xFFFF) / 65536.0) } else emptyList()
        if (glides.isEmpty()) { src.setGeoJson(none); return@LaunchedEffect }
        var last = 0L
        var shown = false
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            // About 30 times a second is smooth enough for something this slow.
            if (now - last < 32_000_000L) continue
            last = now
            // Under a page, behind a dialog or off screen nobody sees it move.
            val bounds = m.projection.visibleRegion.latLngBounds
            val seen = if (moving && mapView.isShown && mapView.hasWindowFocus()) glides.filter { it.onScreen(bounds) } else emptyList()
            if (seen.isEmpty()) {
                if (shown) { src.setGeoJson(none); shown = false }
                kotlinx.coroutines.delay(400)
                continue
            }
            val t = (now - clock) / 1e9
            src.setGeoJson(FeatureCollection.fromFeatures(seen.map { it.at(t, bounds) }))
            shown = true
        }
    }

    // Thumbnails decode in parallel and stay cached by photo, so adding one memory decodes one
    // picture, not all of them again.
    // Keyed on the ring colour, which is baked into each thumbnail: a new look redraws them.
    val thumbs = remember(palette.selected) { HashMap<String, android.graphics.Bitmap>() }
    var shownPhotoImages by remember(style) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(style, photos, thumbs) {
        val s = style ?: return@LaunchedEffect
        val px = (56 * context.resources.displayMetrics.density).toInt()
        val ring = palette.selected.toArgb()
        val missing = photos.filter { it.uri !in thumbs }.distinctBy { it.uri }
        kotlinx.coroutines.coroutineScope {
            missing.map { p ->
                async {
                    com.marcusrosado.stamp.scan.Memories.thumbnail(context, android.net.Uri.parse(p.uri), p.video, px)?.let { p.uri to roundPhoto(it, px, ring) }
                }
            }.awaitAll().filterNotNull().forEach { (u, b) -> thumbs[u] = b }
        }
        // Photos no longer on the map leave the cache and the style.
        thumbs.keys.retainAll(photos.map { it.uri }.toSet())
        fun name(p: MapPhoto) = "photo-" + p.uri.hashCode().toUInt().toString(16)
        val wanted = photos.mapNotNull { p -> thumbs[p.uri]?.let { p to it } }
        val names = wanted.map { (p, _) -> name(p) }.toSet()
        for (old in shownPhotoImages - names) s.removeImage(old)
        for ((p, b) in wanted) s.addImage(name(p), b)
        shownPhotoImages = names
        s.getSourceAs<GeoJsonSource>(SOURCE_PHOTOS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                wanted.map { (p, _) ->
                    Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("img", name(p)) }
                },
            ),
        )
    }

    // The regions of the countries drawn as regions: shapes built off the main thread, and the
    // marks as one colour match, so marking a region recolours it without rebuilding shapes.
    val regionShapes = regionLayer?.shapes
    LaunchedEffect(style, regionShapes) {
        val s = style ?: return@LaunchedEffect
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            FeatureCollection.fromFeatures(regionShapes.orEmpty().map { (cid, r) -> regionFeature(cid, r) })
        }
        s.getSourceAs<GeoJsonSource>(SOURCE_REGIONS)?.setGeoJson(json)
    }
    LaunchedEffect(style, regionLayer?.marks, regionLayer?.countryMarks, palette) {
        val s = style ?: return@LaunchedEffect
        val marks = regionLayer?.marks.orEmpty()
        // A region not been to, in a country that is marked, takes a faint wash of the country's
        // mark, so the country still reads as one place with its regions to go.
        val faint = regionLayer?.countryMarks.orEmpty()
        val base = if (faint.isEmpty()) Expression.color(palette.land.toArgb())
        else Expression.match(
            Expression.get("country"), Expression.color(palette.land.toArgb()),
            *faint.map { (id, st) ->
                Expression.stop(id, Expression.color(androidx.compose.ui.graphics.lerp(palette.land, palette.of(st), 0.22f).toArgb()))
            }.toTypedArray(),
        )
        val fill = if (marks.isEmpty()) base
        else Expression.match(
            Expression.get("region"), base,
            *marks.map { (id, st) -> Expression.stop(id, Expression.color(palette.of(st).toArgb())) }.toTypedArray(),
        )
        s.getLayerAs<FillLayer>(LAYER_REGIONS)?.setProperties(PropertyFactory.fillColor(fill))
    }
    LaunchedEffect(style, complete) {
        val s = style ?: return@LaunchedEffect
        val filter = Expression.`in`(Expression.get("country"), Expression.literal(complete.toTypedArray()))
        for (id in COMPLETE_LAYERS) s.getLayerAs<org.maplibre.android.style.layers.LineLayer>(id)?.setFilter(filter)
    }
    LaunchedEffect(style, allCities) {
        val s = style ?: return@LaunchedEffect
        val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            FeatureCollection.fromFeatures(allCities.map { c ->
                Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply {
                    addStringProperty("id", c.id)
                    addNumberProperty("pop", c.population)
                    addBooleanProperty("mine", c.mine)
                }
            })
        }
        s.getSourceAs<GeoJsonSource>(SOURCE_ALL_CITIES)?.setGeoJson(json)
    }
    // In the cities view the cities been to carry the visited colour and stand out.
    LaunchedEffect(style, plain, palette) {
        val s = style ?: return@LaunchedEffect
        s.getLayerAs<org.maplibre.android.style.layers.CircleLayer>("cities")?.setProperties(
            PropertyFactory.circleColor(Expression.color((if (plain) palette.visited else palette.selected).toArgb())),
        )
    }
    LaunchedEffect(style, wantCities) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SOURCE_WANT_CITIES)?.setGeoJson(
            FeatureCollection.fromFeatures(wantCities.map { (lat, lon) -> Feature.fromGeometry(Point.fromLngLat(lon, lat)) }),
        )
    }

    LaunchedEffect(style, cities) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SOURCE_CITIES)?.setGeoJson(
            FeatureCollection.fromFeatures(cities.map { (lat, lon) -> Feature.fromGeometry(Point.fromLngLat(lon, lat)) }),
        )
    }

    LaunchedEffect(style, homes) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SOURCE_HOMES)?.setGeoJson(
            FeatureCollection.fromFeatures(homes.map { (lat, lon) -> Feature.fromGeometry(Point.fromLngLat(lon, lat)) }),
        )
    }

    LaunchedEffect(style, me) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SOURCE_ME)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(me?.let { (lat, lon) -> Feature.fromGeometry(Point.fromLngLat(lon, lat)) })),
        )
    }

    LaunchedEffect(style, areas) {
        val s = style ?: return@LaunchedEffect
        s.getSourceAs<GeoJsonSource>(SOURCE_AREAS)?.setGeoJson(
            FeatureCollection.fromFeatures(
                areas.map { shapes ->
                    Feature.fromGeometry(
                        MultiPolygon.fromLngLats(
                            shapes.map { sh -> (listOf(sh.outer) + sh.holes).map { r -> r.xs.indices.map { i -> Point.fromLngLat(r.xs[i], r.ys[i]) } } },
                        ),
                    )
                },
            ),
        )
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    // One effect for both, so a move and a change of the covered part never cancel each other.
    // The covered part last handed to the map. Read from here, never from the camera: mid-move
    // the camera reports where it is passing through, and moving again from that cancels a move.
    // A place picked lands in the middle of the part of the map left showing beside or above a
    // pane. The camera itself is moved off centre to do it, never padded: padding makes the map
    // keep the world inside the padded part, so zoomed all the way out the world would ride up
    // and leave empty sea under the pane.
    LaunchedEffect(map, focus, coveredEnd, coveredBottom) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        val endDp = coveredEnd.value.toDouble()
        val bottomDp = coveredBottom.value.toDouble()
        val (target, zoom) = f.box?.let { b ->
            fit(b, (mapView.width / density.density) - coveredEnd.value, (mapView.height / density.density) - coveredBottom.value - TOP_CHROME, f.zoom)
        } ?: (LatLng(f.lat, f.lon) to f.zoom)
        // The world is 512 dp wide and tall at zoom 0: half the covered part, as a share of
        // the world at this zoom, is how far the centre moves east and south of the place.
        val world = 512.0 * Math.pow(2.0, zoom)
        val lon = target.longitude + endDp / 2 / world * 360.0
        val lat = com.marcusrosado.stamp.data.LegPath.latitude(
            com.marcusrosado.stamp.data.LegPath.mercator(target.latitude) - bottomDp / 2 / world * 2 * Math.PI,
        )
        val position = CameraPosition.Builder().target(LatLng(lat.coerceIn(-85.0, 85.0), lon)).zoom(zoom).padding(0.0, 0.0, 0.0, 0.0).build()
        m.animateCamera(CameraUpdateFactory.newCameraPosition(position), 700)
        // Done with it: coming back to the map later keeps wherever the user panned to.
        onFocused()
    }

    // Keyed on the split and the fine shapes too: either builds a new outline that has drawn
    // nothing yet.
    LaunchedEffect(style, selectedId, split, fine) {
        val s = style ?: return@LaunchedEffect
        outline.show(s, selectedId)
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private const val LAYER_FILL = "countries-fill"
private const val LAYER_FILL_FINE = "countries-fill-fine"
private const val SOURCE_SELECTED = "selected"
private const val SOURCE_SELECTED_FINE = "selected-fine"

/**
 * Where the 1:50m shapes hand over to the 1:10m ones. The fine fill and borders fade in over
 * the coarse ones from [FINE_FROM] to [FINE_FULL], then the coarse fill fades out underneath
 * by [COARSE_TO]. Both carry the same ids and colours, so the only change on screen is the
 * sharper edge. The selected outline switches at [FINE_FULL], where the fine fill is opaque.
 */
private const val FINE_FROM = 3.5
private const val FINE_FULL = 3.75
private const val COARSE_TO = 4.0
private const val SOURCE_CITIES = "cities"
private const val SOURCE_HOMES = "homes"
private const val SOURCE_ME = "me"
private const val IMAGE_HOME = "home"

/** A home on the map: a round marker in the lived colour with a white house in it. */
private fun homeMarker(context: android.content.Context, colour: Int): android.graphics.Bitmap {
    val px = (28 * context.resources.displayMetrics.density).toInt()
    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = px / 2f
    paint.color = android.graphics.Color.WHITE
    c.drawCircle(r, r, r, paint)
    paint.color = colour
    c.drawCircle(r, r, r * 0.84f, paint)
    // The house: a roof and a body with a door, in white.
    paint.color = android.graphics.Color.WHITE
    val u = px / 28f
    val house = android.graphics.Path().apply {
        moveTo(14 * u, 7 * u); lineTo(21 * u, 13.5f * u); lineTo(19 * u, 13.5f * u); lineTo(19 * u, 20.5f * u)
        lineTo(15.8f * u, 20.5f * u); lineTo(15.8f * u, 16.5f * u); lineTo(12.2f * u, 16.5f * u); lineTo(12.2f * u, 20.5f * u)
        lineTo(9 * u, 20.5f * u); lineTo(9 * u, 13.5f * u); lineTo(7 * u, 13.5f * u); close()
    }
    c.drawPath(house, paint)
    return b
}
private const val SOURCE_AREAS = "city-areas"
private const val SOURCE_REGIONS = "regions"
private const val SOURCE_WANT_CITIES = "want-cities"
private const val LAYER_REGIONS = "regions-fill"
private val COMPLETE_LAYERS = listOf("complete-glow", "complete-line", "complete-glow-fine", "complete-line-fine")
private const val SOURCE_ALL_CITIES = "all-cities"

/** A city as the cities view draws it: a dot, more of them as the map zooms in. */
class CityDot(val id: String, val lat: Double, val lon: Double, val population: Int, val mine: Boolean)

// Bigger cities first: a dot for every city over a million from the start, smaller ones as the
// map zooms in. A city on a visit or wanted shows from the start whatever its size.
private val CITY_STEPS = listOf(0.0 to 1_000_000, 3.0 to 250_000, 4.5 to 80_000, 6.0 to 0)
private val ALL_CITY_LAYERS = CITY_STEPS.indices.map { "all-cities-$it" }.toTypedArray()

private fun allCityLayersJson(p: MapPalette) = CITY_STEPS.mapIndexed { i, (zoom, least) ->
    val most = CITY_STEPS.getOrNull(i - 1)?.second
    val filter = when {
        i == 0 -> """["any", ["==", ["get", "mine"], true], [">=", ["get", "pop"], $least]]"""
        else -> """["all", ["!=", ["get", "mine"], true], [">=", ["get", "pop"], $least], ["<", ["get", "pop"], $most]]"""
    }
    """    { "id": "all-cities-$i", "type": "circle", "source": "$SOURCE_ALL_CITIES", "minzoom": $zoom, "filter": $filter,
      "paint": { "circle-radius": ["interpolate", ["linear"], ["zoom"], 1, 1.8, 6, 3.2, 9, 4.5],
                 "circle-color": "${hex(p.selected)}", "circle-opacity": 0.55,
                 "circle-stroke-color": "${hex(p.ocean)}", "circle-stroke-width": 0.6 } }"""
}.joinToString(",\n")

/** Which countries draw as their regions, the shapes of those regions, and each region's mark. */
class RegionLayer(
    /** Each region with the id of the country it belongs to. */
    val shapes: List<Pair<String, com.marcusrosado.stamp.data.RegionShape>>,
    val marks: Map<String, TravelState>,
    /** Each country's own mark, by country id. */
    val countryMarks: Map<String, TravelState> = emptyMap(),
)

private fun regionFeature(countryId: String, r: com.marcusrosado.stamp.data.RegionShape): Feature {
    val polys = r.polygons.map { rings ->
        rings.map { ring -> (0 until ring.size / 2).map { k -> Point.fromLngLat(ring[2 * k + 1], ring[2 * k]) } }
    }
    val geometry = if (polys.size == 1) org.maplibre.geojson.Polygon.fromLngLats(polys[0])
    else org.maplibre.geojson.MultiPolygon.fromLngLats(polys)
    return Feature.fromGeometry(geometry).apply {
        addStringProperty("region", r.id)
        addStringProperty("country", countryId)
    }
}
private const val SOURCE_PHOTOS = "photos"

/** A photo pinned to the place it belongs to, for the map's previews. */
data class MapPhoto(val lat: Double, val lon: Double, val uri: String, val video: Boolean)

/** A photo cut to a circle with a ring, the way it sits on the map. */
private fun roundPhoto(src: android.graphics.Bitmap, px: Int, ring: Int): android.graphics.Bitmap {
    val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val r = px / 2f
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val side = minOf(src.width, src.height)
    val from = android.graphics.Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2)
    val path = android.graphics.Path().apply { addCircle(r, r, r - px * 0.06f, android.graphics.Path.Direction.CW) }
    c.save(); c.clipPath(path)
    c.drawBitmap(src, from, android.graphics.RectF(0f, 0f, px.toFloat(), px.toFloat()), paint)
    c.restore()
    paint.style = android.graphics.Paint.Style.STROKE
    paint.strokeWidth = px * 0.07f
    paint.color = ring
    c.drawCircle(r, r, r - px * 0.06f, paint)
    return out
}
private const val LAYER_SELECTED = "countries-selected"

/** The gold of a place done in full. */
private const val GOLD = "#F2C14E"

internal fun hex(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

private fun styleJson(p: MapPalette) = """
{
  "version": 8,
  "name": "stamp",
  "sources": {
    "countries": { "type": "geojson", "data": "asset://${StampApp.COUNTRIES_ASSET}" },
    "countries-fine": { "type": "geojson", "data": "asset://${StampApp.FINE_COUNTRIES_ASSET}" },
    "$SOURCE_SELECTED": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_SELECTED_FINE": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_CITIES": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_HOMES": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_ME": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_AREAS": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_ROUTES": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_ROUTE_ICONS": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_PHOTOS": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_REGIONS": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_WANT_CITIES": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } },
    "$SOURCE_ALL_CITIES": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }
  },
  "layers": [
    { "id": "ocean", "type": "background", "paint": { "background-color": "${hex(p.ocean)}" } },
    { "id": "$LAYER_FILL", "type": "fill", "source": "countries", "maxzoom": $COARSE_TO,
      "paint": { "fill-color": "${hex(p.land)}", "fill-antialias": true,
                 "fill-opacity": ["interpolate", ["linear"], ["zoom"], $FINE_FULL, 1, $COARSE_TO, 0] } },
    { "id": "countries-border", "type": "line", "source": "countries", "maxzoom": $FINE_FULL,
      "paint": { "line-color": "${hex(p.border)}", "line-width": 0.7,
                 "line-opacity": ["interpolate", ["linear"], ["zoom"], $FINE_FROM, 1, $FINE_FULL, 0] } },
    { "id": "$LAYER_FILL_FINE", "type": "fill", "source": "countries-fine", "minzoom": $FINE_FROM,
      "paint": { "fill-color": "${hex(p.land)}", "fill-antialias": true,
                 "fill-opacity": ["interpolate", ["linear"], ["zoom"], $FINE_FROM, 0, $FINE_FULL, 1] } },
    { "id": "countries-border-fine", "type": "line", "source": "countries-fine", "minzoom": $FINE_FROM,
      "paint": { "line-color": "${hex(p.border)}", "line-width": 0.7,
                 "line-opacity": ["interpolate", ["linear"], ["zoom"], $FINE_FROM, 0, $FINE_FULL, 1] } },
    { "id": "$LAYER_REGIONS", "type": "fill", "source": "$SOURCE_REGIONS",
      "paint": { "fill-color": "${hex(p.land)}", "fill-antialias": true } },
    { "id": "regions-border", "type": "line", "source": "$SOURCE_REGIONS",
      "paint": { "line-color": "${hex(p.border)}", "line-width": 0.7 } },
    { "id": "complete-glow", "type": "line", "source": "countries", "maxzoom": $FINE_FULL, "filter": ["in", ["get", "country"], ["literal", []]],
      "paint": { "line-color": "$GOLD", "line-width": 7, "line-blur": 5, "line-opacity": 0.55 } },
    { "id": "complete-line", "type": "line", "source": "countries", "maxzoom": $FINE_FULL, "filter": ["in", ["get", "country"], ["literal", []]],
      "paint": { "line-color": "$GOLD", "line-width": 2.0 } },
    { "id": "complete-glow-fine", "type": "line", "source": "countries-fine", "minzoom": $FINE_FULL, "filter": ["in", ["get", "country"], ["literal", []]],
      "paint": { "line-color": "$GOLD", "line-width": 7, "line-blur": 5, "line-opacity": 0.55 } },
    { "id": "complete-line-fine", "type": "line", "source": "countries-fine", "minzoom": $FINE_FULL, "filter": ["in", ["get", "country"], ["literal", []]],
      "paint": { "line-color": "$GOLD", "line-width": 2.0 } },
    { "id": "city-areas-fill", "type": "fill", "source": "$SOURCE_AREAS",
      "paint": { "fill-color": "${hex(p.ocean)}",
                 "fill-opacity": ["interpolate", ["linear"], ["zoom"], 2, 0, 4, 0.3] } },
    { "id": "city-areas-line", "type": "line", "source": "$SOURCE_AREAS",
      "paint": { "line-color": "${hex(p.ocean)}", "line-width": ["interpolate", ["linear"], ["zoom"], 4, 0.8, 7, 1.8],
                 "line-opacity": ["interpolate", ["linear"], ["zoom"], 2, 0, 4, 0.9] } },
    { "id": "selected-casing", "type": "line", "source": "$SOURCE_SELECTED", "maxzoom": $FINE_FULL,
      "paint": { "line-color": "${hex(p.ocean)}", "line-width": 5.0 } },
    { "id": "$LAYER_SELECTED", "type": "line", "source": "$SOURCE_SELECTED", "maxzoom": $FINE_FULL,
      "paint": { "line-color": "${hex(p.selected)}", "line-width": 2.2 } },
    { "id": "selected-casing-fine", "type": "line", "source": "$SOURCE_SELECTED_FINE", "minzoom": $FINE_FULL,
      "paint": { "line-color": "${hex(p.ocean)}", "line-width": 5.0 } },
    { "id": "$LAYER_SELECTED-fine", "type": "line", "source": "$SOURCE_SELECTED_FINE", "minzoom": $FINE_FULL,
      "paint": { "line-color": "${hex(p.selected)}", "line-width": 2.2 } },
${routeLayersJson(p)},
${allCityLayersJson(p)},
    { "id": "cities", "type": "circle", "source": "$SOURCE_CITIES",
      "paint": { "circle-radius": ["interpolate", ["linear"], ["zoom"], 1, 2.5, 6, 5],
                 "circle-color": "${hex(p.selected)}",
                 "circle-stroke-color": "${hex(p.ocean)}", "circle-stroke-width": 1.2 } },
    { "id": "want-cities", "type": "circle", "source": "$SOURCE_WANT_CITIES",
      "paint": { "circle-radius": ["interpolate", ["linear"], ["zoom"], 1, 2.5, 6, 5],
                 "circle-color": "${hex(p.want)}",
                 "circle-stroke-color": "${hex(p.ocean)}", "circle-stroke-width": 1.2 } },
$ROUTE_ICONS_JSON,
    { "id": "photos", "type": "symbol", "source": "$SOURCE_PHOTOS", "minzoom": 4.5,
      "layout": { "icon-image": ["get", "img"], "icon-allow-overlap": false,
                  "icon-size": ["interpolate", ["linear"], ["zoom"], 4.5, 0.6, 7, 1.0],
                  "icon-anchor": "bottom", "icon-offset": [0, -6] },
      "paint": { "icon-opacity": ["interpolate", ["linear"], ["zoom"], 4.5, 0, 5, 1] } },
    { "id": "homes", "type": "symbol", "source": "$SOURCE_HOMES", "minzoom": 5,
      "layout": { "icon-image": "$IMAGE_HOME", "icon-allow-overlap": true, "icon-ignore-placement": true,
                  "icon-size": ["interpolate", ["linear"], ["zoom"], 5, 0.6, 10, 1.0] } },
    { "id": "me-halo", "type": "circle", "source": "$SOURCE_ME",
      "paint": { "circle-radius": 16, "circle-color": "#1A73E8", "circle-opacity": 0.18 } },
    { "id": "me", "type": "circle", "source": "$SOURCE_ME",
      "paint": { "circle-radius": 6.5, "circle-color": "#1A73E8", "circle-stroke-color": "#FFFFFF", "circle-stroke-width": 2.5 } }
  ]
}
""".trimIndent()

/**
 * The selected country's outline lives in a source of its own that holds just that one
 * country. Swapping it is cheap, where changing a filter on the full world source made
 * MapLibre lay out every country's border again. A second source holds the same country in
 * the 1:10m shapes, for mid zoom and closer.
 */
private class Outline(private val atlas: Atlas, private val split: Boolean, private val fine: FineShapes?) {
    private val cache = HashMap<String, Feature>()
    private var shown: String? = null

    /** A new style starts with empty sources. */
    fun reset() { shown = null }

    fun show(style: Style, id: String?) {
        if (id == shown) return
        shown = id
        val src = style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED) ?: return
        val f = id?.let { cache.getOrPut(it) { feature(it) ?: return@let null } }
        if (f == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList())) else src.setGeoJson(f)
        val fineSrc = style.getSourceAs<GeoJsonSource>(SOURCE_SELECTED_FINE) ?: return
        // Not cached: cutting one country out of the file is cheap, and a big one is megabytes.
        val json = id?.let { fineJson(it) }
        if (json == null) fineSrc.setGeoJson(FeatureCollection.fromFeatures(emptyList())) else fineSrc.setGeoJson(json)
    }

    /** The ids an outline of [id] covers: whole countries outline every land mass they have. */
    private fun idsOf(c: Country): List<String> =
        if (split || c.isPart) listOf(c.id) else listOf(c.id) + atlas.partsOf(c.id).map { it.id }

    private fun fineJson(id: String): String? {
        val shapes = fine ?: return null
        val c = atlas.byId[id] ?: return null
        val features = idsOf(c).mapNotNull { shapes.feature(it) }
        if (features.isEmpty()) return null
        return features.joinToString(",", "{\"type\":\"FeatureCollection\",\"features\":[", "]}")
    }

    private fun feature(id: String): Feature? {
        val c = atlas.byId[id] ?: return null
        val shapes = idsOf(c).flatMap { atlas.byId[it]?.shapes.orEmpty() }
        val polygons = shapes.map { s ->
            (listOf(s.outer) + s.holes).map { r -> r.xs.indices.map { i -> Point.fromLngLat(r.xs[i], r.ys[i]) } }
        }
        return Feature.fromGeometry(MultiPolygon.fromLngLats(polygons))
    }
}

/**
 * The trips on the line nearest a tap at [x], [y] within 10 dp, and on any line just as near,
 * such as the way out and the way back drawn over the same rails: what the tap means. Where two
 * legs meet at a stop, the one under the finger wins over its neighbour.
 */
private fun nearestRoutes(m: MapLibreMap, x: Float, y: Float, density: Float): List<RouteTap> {
    val slack = 10f * density
    val layers = ROUTE_LAYERS.filter { it != "route-icons" }.toTypedArray()
    val hits = m.queryRenderedFeatures(android.graphics.RectF(x - slack, y - slack, x + slack, y + slack), *layers)
    if (hits.isEmpty()) return emptyList()
    val proj = m.projection
    // One world's width on screen, for a line drawn past the date line onto the next copy.
    val world = (512.0 * Math.pow(2.0, m.cameraPosition.zoom) * density).toFloat()
    fun along(f: Feature): List<List<Point>> = when (val g = f.geometry()) {
        is LineString -> listOf(g.coordinates())
        is org.maplibre.geojson.MultiLineString -> g.coordinates()
        is Point -> listOf(listOf(g))
        else -> emptyList()
    }
    fun gap(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len).coerceIn(0f, 1f)
        return kotlin.math.hypot(x - (ax + t * dx), y - (ay + t * dy))
    }
    fun distance(f: Feature): Float = along(f).filter { it.isNotEmpty() }.minOfOrNull { line ->
        val pts = line.map { proj.toScreenLocation(LatLng(it.latitude(), it.longitude())) }
        listOf(0f, world, -world).minOf { shift ->
            if (pts.size == 1) kotlin.math.hypot(x - (pts[0].x + shift), y - pts[0].y)
            else (1 until pts.size).minOf { i -> gap(pts[i - 1].x + shift, pts[i - 1].y, pts[i].x + shift, pts[i].y) }
        }
    } ?: Float.MAX_VALUE
    val ranked = hits.map { it to distance(it) }
    val best = ranked.minOf { it.second }
    return ranked.filter { it.second <= best + 2f * density }
        .flatMap { (f, _) -> f.getStringProperty("trips")?.split(',').orEmpty() }
        .mapNotNull { RouteTap.decode(it) }
        .distinct()
}

/** The legend and status bar along the top, in dp, which a fitted country stays clear of. */
private const val TOP_CHROME = 72f

/**
 * The centre and zoom that fit a box (south, west, north, east) into [w] by [h] dp of map,
 * filling about 60% of it, no closer than [maxZoom]. Web Mercator, as MapLibre draws it: the
 * world is 512 dp wide at zoom 0.
 */
internal fun fit(box: List<Double>, w: Float, h: Float, maxZoom: Double): Pair<LatLng, Double> =
    fitBox(box, w, h, maxZoom).let { (lat, lon, zoom) -> LatLng(lat, lon) to zoom }

/** [fit] in plain numbers: latitude, longitude and zoom. */
internal fun fitBox(box: List<Double>, w: Float, h: Float, maxZoom: Double): Triple<Double, Double, Double> {
    val (south, west, north, east) = box
    fun y(lat: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return kotlin.math.ln(kotlin.math.tan(Math.PI / 4 + r / 2)) / (2 * Math.PI)
    }
    val fx = ((east - west) / 360.0).coerceAtLeast(1e-6)
    val fy = (y(north) - y(south)).coerceAtLeast(1e-6)
    val zx = kotlin.math.log2(w.coerceAtLeast(100f) * 0.62 / (512 * fx))
    val zy = kotlin.math.log2(h.coerceAtLeast(100f) * 0.62 / (512 * fy))
    val zoom = minOf(zx, zy, maxZoom).coerceAtLeast(1.0)
    val midY = (y(north) + y(south)) / 2
    val lat = Math.toDegrees(2 * kotlin.math.atan(kotlin.math.exp(midY * 2 * Math.PI)) - Math.PI / 2)
    return Triple(lat, (west + east) / 2, zoom)
}
