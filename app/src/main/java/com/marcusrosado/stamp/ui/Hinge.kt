package com.marcusrosado.stamp.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * A fold that is half open, so the screen is bent and its two halves face different ways.
 * [vertical] is a book (halves side by side), otherwise a laptop on a table (top and bottom).
 * [start] and [end] are where the seam begins and ends along the axis that crosses it, in dp
 * from the window's left or top edge.
 */
data class Hinge(val vertical: Boolean, val start: Dp, val end: Dp)

/** The half-open fold, or null when the screen is flat: a plain phone, folded shut or fully open. */
val LocalHinge = staticCompositionLocalOf<Hinge?> { null }

/**
 * How far from flat, in degrees, the screen has to bend before it counts as half folded. Small
 * on purpose: Android's own half-open state can need a much deeper bend.
 */
const val HALF_FOLD_DEGREES = 10f

/** How much nearer to flat the screen has to open again before it stops counting as bent. */
const val FLAT_AGAIN_DEGREES = 5f

/**
 * Follows the device's fold. The seam's place comes from Jetpack WindowManager, which reports
 * it whenever the inner screen is in use; whether it is bent comes from the hinge angle sensor
 * (180 is flat), so a bend of [HALF_FOLD_DEGREES] is enough. Without the sensor, Android's own
 * half-open state decides.
 */
@Composable
fun rememberHinge(): Hinge? {
    val activity = LocalContext.current as? Activity ?: return null
    val density = LocalDensity.current
    var seam by remember { mutableStateOf<Pair<Hinge, Boolean>?>(null) }
    // Bent by the angle sensor; null until it reports, then the system's own half-open state is used.
    var bentBySensor by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(activity, density) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            seam = fold?.let { f ->
                val b = f.bounds
                val vertical = f.orientation == FoldingFeature.Orientation.VERTICAL
                val h = with(density) {
                    if (vertical) Hinge(true, b.left.toDp(), b.right.toDp()) else Hinge(false, b.top.toDp(), b.bottom.toDp())
                }
                h to (f.state == FoldingFeature.State.HALF_OPENED)
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(activity) {
        val sm = activity.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE)
        val listener = object : android.hardware.SensorEventListener {
            // Bent, but not closed: a closed phone uses its outer screen, which has no seam at all.
            // Going back to flat needs a few more degrees than leaving it, so a hand resting near
            // the line doesn't flip the layout back and forth. Worked out here, so the screen
            // only hears about a change, not every degree.
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val a = e.values[0]
                val was = bentBySensor == true
                val now = when {
                    a <= 20f -> false
                    was -> a < 180f - HALF_FOLD_DEGREES + FLAT_AGAIN_DEGREES
                    else -> a < 180f - HALF_FOLD_DEGREES
                }
                if (now != bentBySensor) bentBySensor = now
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { if (sensor != null) sm.unregisterListener(listener) }
    }
    val bent = bentBySensor ?: seam?.second ?: false
    val (hinge, _) = seam ?: return null
    return hinge.takeIf { bent }
}
