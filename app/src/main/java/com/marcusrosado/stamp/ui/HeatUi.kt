package com.marcusrosado.stamp.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.marcusrosado.stamp.data.Atlas
import com.marcusrosado.stamp.data.HeatCell
import com.marcusrosado.stamp.data.Here
import com.marcusrosado.stamp.data.Hourly
import com.marcusrosado.stamp.data.Seen
import com.marcusrosado.stamp.data.SeenPoint

/**
 * Asks for what the hourly look needs, in Android's order: the rough location first, then Allow
 * all the time, which Android shows as its own settings page. [onResult] gets whether both were
 * given. Refused for good, the app's own settings page opens, since Android no longer asks.
 */
@Composable
fun rememberHourlyAsk(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val done by rememberUpdatedState(onResult)
    fun settings() = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    fun refusedForGood(permission: String) =
        (context as? android.app.Activity)?.shouldShowRequestPermissionRationale(permission) == false
    val always = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val bg = Hourly.BACKGROUND
        if (!granted && bg != null && refusedForGood(bg)) {
            // Only Android's own page can give it now. The hourly look is switched on and waits:
            // it starts sampling the hour after Allow all the time is chosen there.
            settings()
            done(true)
        } else done(Hourly.allowed(context))
    }
    val rough = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted && Hourly.backgroundGranted(context) -> done(true)
            granted -> always.launch(Hourly.BACKGROUND!!)
            else -> { if (refusedForGood(Here.PERMISSION)) settings(); done(false) }
        }
    }
    return {
        when {
            Hourly.allowed(context) -> done(true)
            !Here.granted(context) -> rough.launch(Here.PERMISSION)
            else -> always.launch(Hourly.BACKGROUND!!)
        }
    }
}

/** A world map with the user's own heat on it, or a made-up one before there is any. */
@Composable
fun HeatPreview(atlas: Atlas, seen: List<SeenPoint>, modifier: Modifier = Modifier) {
    val cells = androidx.compose.runtime.remember(seen) { if (seen.isEmpty()) SampleHeat else Seen.cells(seen, step = 2.0) }
    MiniMap(atlas, { null }, modifier, heat = cells)
}

/** A sample heat map, for before the user has any: a home, a few trips, a long stay. */
private val SampleHeat = listOf(
    HeatCell(48.9, 2.3, 40), HeatCell(45.5, 9.2, 12), HeatCell(41.4, 2.2, 18), HeatCell(52.5, 13.4, 9),
    HeatCell(38.7, -9.1, 14), HeatCell(40.7, -74.0, 10), HeatCell(35.7, 139.7, 16), HeatCell(13.8, 100.5, 8),
    HeatCell(-33.9, 151.2, 6), HeatCell(19.4, -99.1, 5),
)
