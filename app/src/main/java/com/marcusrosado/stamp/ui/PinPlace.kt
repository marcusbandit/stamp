package com.marcusrosado.stamp.ui

import androidx.compose.foundation.clickable
import android.graphics.PointF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.marcusrosado.stamp.StampApp
import com.marcusrosado.stamp.data.City
import com.marcusrosado.stamp.data.Country
import com.marcusrosado.stamp.data.Coordinates
import com.marcusrosado.stamp.data.LatLon
import com.marcusrosado.stamp.data.Pin
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Put a place on the map by hand. The map moves under a dot that stays in the middle; Attach
 * takes the point under the dot, then asks for a name. The map has no tiles, so the
 * country's own towns are drawn and named to steer by.
 */
@Composable
fun PinPlace(
    country: Country,
    /** The country's towns, biggest first. */
    cities: List<City>,
    initialName: String,
    onAttach: (Pin) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Pin a place in ${country.name}",
    /** What the name step asks for: a place's name, or a street address. */
    nameTitle: String = "Name this place",
    namePlaceholder: String = "Name",
    /** Where the map opens; the middle of the country when not given. */
    start: LatLng? = null,
    /** The place already has its name, such as a typed address: Attach saves it without asking. */
    named: String? = null,
) {
    var center by remember { mutableStateOf(start ?: LatLng(country.labelLat, country.labelLon)) }
    var labels by remember { mutableStateOf<List<MapLabel>>(emptyList()) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var typing by rememberSaveable { mutableStateOf(false) }
    // A point typed in by hand, for the map to fly to. A fresh object each time, so typing the
    // same point twice still flies there again.
    var goTo by remember { mutableStateOf(start?.let { FlyTo(it) }) }
    val density = LocalDensity.current
    val gap = with(density) { LABEL_GAP.dp.roundToPx() }
    val rise = with(density) { LABEL_RISE.dp.roundToPx() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Bent, the map and its crosshair take one half and the controls the other, so the dot
        // is never on the fold. Flat, both halves are the whole screen, as before.
        val bent = LocalHinge.current != null
        Box(Modifier.fillMaxSize().background(LocalMapPalette.current.ocean)) {
          InHalf(Half.First) {
           Box(Modifier.fillMaxSize()) {
            PinMap(
                country = country,
                cities = cities,
                goTo = goTo,
                onMove = { c, l -> center = c; labels = l },
                modifier = Modifier.fillMaxSize(),
            )
            // Every town drawn is a shortcut: a tap flies the dot to it.
            for (l in labels) {
                Text(
                    l.name,
                    style = if (l.big) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (l.big) 0.95f else 0.75f),
                    modifier = Modifier
                        .offset { IntOffset(l.x.roundToInt() + gap, l.y.roundToInt() - rise) }
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { goTo = FlyTo(LatLng(l.lat, l.lon)) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    maxLines = 1,
                )
            }
            Crosshair(Modifier.align(Alignment.Center))
           }
          }
          InHalf(Half.Second) {
           Box(
               Modifier.fillMaxSize().background(if (bent) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent),
           ) {
            Surface(
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 4.dp,
            ) {
                Row(Modifier.padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                onClick = { typing = true },
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        coordinates(center.latitude, center.longitude),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Outlined.Edit, contentDescription = "Type coordinates", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            ExtendedFloatingActionButton(
                onClick = { if (named != null) onAttach(Pin(named, center.latitude, center.longitude)) else naming = true },
                icon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                text = { Text("Attach") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            )
           }
          }
        }
        if (typing) {
            TypeCoordinates(
                onGo = { p ->
                    goTo = FlyTo(LatLng(p.lat, p.lon))
                    typing = false
                },
                onDismiss = { typing = false },
            )
        }
        if (naming) {
            NamePin(
                title = nameTitle,
                placeholder = namePlaceholder,
                initial = initialName,
                where = coordinates(center.latitude, center.longitude),
                onSave = { onAttach(Pin(it, center.latitude, center.longitude)) },
                onDismiss = { naming = false },
            )
        }
    }
}

/** A point to fly to. Compared by identity on purpose: see where it is set. */
class FlyTo(val point: LatLng)

/** A town name drawn over the map at a screen position, in pixels. */
class MapLabel(val name: String, val x: Float, val y: Float, val big: Boolean, val lat: Double = 0.0, val lon: Double = 0.0)

/** Labels sit to the right of their dot, centred on it, in dp. */
private const val LABEL_GAP = 6
private const val LABEL_RISE = 9
private const val MAX_LABELS = 28

/** The dot the map moves under: a filled centre in a soft ring, so it reads on any land. */
@Composable
private fun Crosshair(modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)).border(1.5.dp, accent.copy(alpha = 0.6f), CircleShape))
        Box(Modifier.size(14.dp).clip(CircleShape).background(accent).border(2.dp, Color.White, CircleShape))
    }
}

@Composable
private fun NamePin(title: String, placeholder: String, initial: String, where: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text(placeholder) },
                )
                Spacer(Modifier.size(8.dp))
                Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

/** Type or paste a point, in any of the forms [Coordinates] reads, and fly there. */
@Composable
private fun TypeCoordinates(onGo: (LatLon) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val parsed = remember(text) { Coordinates.parse(text) }
    val focus = remember { FocusRequester() }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to coordinates") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("34.4667, 131.7667") },
                    modifier = Modifier.focusRequester(focus),
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { parsed?.let(onGo) }),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        text.isBlank() -> "Paste from Google Maps, or type degrees"
                        parsed != null -> coordinates(parsed.lat, parsed.lon)
                        text.contains("goo.gl") -> "Short links need the internet. Copy the numbers from the place instead."
                        else -> "Can't read that as a place yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (parsed != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let(onGo) }, enabled = parsed != null) { Text("Go") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** "34.4667° N, 131.7667° E". */
fun coordinates(lat: Double, lon: Double): String =
    String.format(Locale.ROOT, "%.4f° %s, %.4f° %s", abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")

@Composable
private fun PinMap(
    country: Country,
    cities: List<City>,
    goTo: FlyTo?,
    onMove: (LatLng, List<MapLabel>) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val palette = LocalMapPalette.current
    val density = LocalDensity.current
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { m ->
                m.uiSettings.apply {
                    isLogoEnabled = false
                    isAttributionEnabled = false
                    isRotateGesturesEnabled = false
                    isTiltGesturesEnabled = false
                    isCompassEnabled = false
                }
                m.setMinZoomPreference(1.0)
                m.setMaxZoomPreference(13.0)
                m.cameraPosition = CameraPosition.Builder().target(LatLng(country.labelLat, country.labelLon)).zoom(4.0).build()
                map = m
            }
        }
    }

    // Keyed on the towns too: they may still be loading when the dialog opens.
    LaunchedEffect(map, cities) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromJson(pinStyle(palette, country.country))) { style ->
            style.getSourceAs<GeoJsonSource>("towns")?.setGeoJson(
                FeatureCollection.fromFeatures(
                    cities.map { c ->
                        Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply { addNumberProperty("pop", c.population) }
                    },
                ),
            )
            // Frame the country's biggest piece, so France opens on France, not on French Guiana.
            // Not when the map was opened on a town: framing would undo flying there.
            if (goTo == null) mainBounds(country)?.let { b ->
                mapView.post { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, with(density) { 48.dp.roundToPx() })) }
            }
        }
        val pad = with(density) { 6.dp.toPx() }
        val gap = with(density) { LABEL_GAP.dp.toPx() }
        val charW = with(density) { 7.dp.toPx() }
        val lineH = with(density) { 18.dp.toPx() }
        fun report() {
            val target = m.cameraPosition.target ?: return
            onMove(target, placeLabels(m, cities, charW, lineH, pad, gap))
        }
        m.addOnCameraMoveListener { report() }
        m.addOnCameraIdleListener { report() }
    }

    LaunchedEffect(map, goTo) {
        val m = map ?: return@LaunchedEffect
        val p = goTo?.point ?: return@LaunchedEffect
        // Close enough to tell the nearby towns apart, and never further out than the map already is.
        val zoom = maxOf(m.cameraPosition.zoom, 10.0)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(p, zoom), 900)
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
        // Adding the observer replays the events up to where the lifecycle already is.
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Names for the towns in view, biggest first, skipping any that would overlap one already
 * placed. Only towns inside the visible area are projected, and the search stops early, so
 * this stays cheap enough to run on every camera frame.
 */
private fun placeLabels(m: MapLibreMap, cities: List<City>, charW: Float, lineH: Float, pad: Float, gap: Float): List<MapLabel> {
    val view = m.projection.visibleRegion.latLngBounds
    val south = view.latitudeSouth; val north = view.latitudeNorth
    val west = view.longitudeWest; val east = view.longitudeEast
    val placed = ArrayList<MapLabel>()
    val boxes = ArrayList<FloatArray>()
    var tried = 0
    // A big country has tens of thousands of towns, so the view test is plain arithmetic.
    for (c in cities) {
        if (placed.size >= MAX_LABELS || tried >= 400) break
        if (c.lat < south || c.lat > north || c.lon < west || c.lon > east) continue
        tried++
        val p: PointF = m.projection.toScreenLocation(LatLng(c.lat, c.lon))
        val w = c.name.length * charW
        val box = floatArrayOf(p.x - pad, p.y - lineH / 2 - pad, p.x + gap + w + pad, p.y + lineH / 2 + pad)
        if (boxes.any { it[0] < box[2] && box[0] < it[2] && it[1] < box[3] && box[1] < it[3] }) continue
        boxes += box
        placed += MapLabel(c.name, p.x, p.y, big = placed.size < 6, lat = c.lat, lon = c.lon)
    }
    return placed
}

/** The bounding box of the country's largest polygon. */
private fun mainBounds(c: Country): LatLngBounds? {
    val main = c.shapes.maxByOrNull { s ->
        (s.outer.xs.max() - s.outer.xs.min()) * (s.outer.ys.max() - s.outer.ys.min())
    } ?: return null
    val b = LatLngBounds.Builder()
    b.include(LatLng(main.outer.ys.min(), main.outer.xs.min()))
    b.include(LatLng(main.outer.ys.max(), main.outer.xs.max()))
    return runCatching { b.build() }.getOrNull()
}

private fun hexOf(c: Color) = String.format("#%06X", c.toArgb() and 0xFFFFFF)

private fun pinStyle(p: MapPalette, countryId: String) = """
{
  "version": 8,
  "name": "pin",
  "sources": {
    "countries": { "type": "geojson", "data": "asset://${StampApp.COUNTRIES_ASSET}" },
    "towns": { "type": "geojson", "data": { "type": "FeatureCollection", "features": [] } }
  },
  "layers": [
    { "id": "ocean", "type": "background", "paint": { "background-color": "${hexOf(p.ocean)}" } },
    { "id": "land", "type": "fill", "source": "countries",
      "paint": { "fill-color": ["match", ["get", "country"], "$countryId", "${hexOf(p.land)}", "${hexOf(p.miniLand)}"] } },
    { "id": "border", "type": "line", "source": "countries",
      "paint": { "line-color": "${hexOf(p.border)}", "line-width": 1.0 } },
    { "id": "towns", "type": "circle", "source": "towns",
      "paint": {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 3, 1.2, 8, 3, 12, 4.5],
        "circle-color": "${hexOf(p.border)}",
        "circle-opacity": ["interpolate", ["linear"], ["zoom"], 3, 0.5, 7, 0.9]
      } }
  ]
}
""".trimIndent()
