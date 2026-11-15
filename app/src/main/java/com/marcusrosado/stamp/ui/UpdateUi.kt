package com.marcusrosado.stamp.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcusrosado.stamp.StampApp
import com.marcusrosado.stamp.data.Release
import com.marcusrosado.stamp.data.Update

/**
 * Says so once when a newer Stamp is out: on the first launch that finds each version. After
 * that, the update waits quietly in Settings, About Stamp.
 */
@Composable
fun UpdatePrompt(app: StampApp) {
    val on by app.updateCheck.on.collectAsStateWithLifecycle()
    val setAside by app.setAside.collectAsStateWithLifecycle()
    LaunchedEffect(on) { if (on) app.updates.check() }
    val state by app.updates.state.collectAsStateWithLifecycle()
    // The version this launch's dialog is about, kept across a fold or a theme change.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val available = (state as? Update.Available)?.release
    LaunchedEffect(available?.version) {
        val v = available?.version ?: return@LaunchedEffect
        if (app.updates.prompted() != v) {
            open = v
            app.updates.markPrompted(v)
        }
    }
    // A save that could not be read asks first; the update can wait for the next launch.
    if (open != null && setAside.isEmpty()) UpdateDialog(app) { open = null }
}

/** The update as it stands: what is new, the download, or what went wrong. */
@Composable
fun UpdateDialog(app: StampApp, onClose: () -> Unit) {
    val state by app.updates.state.collectAsStateWithLifecycle()
    val update = rememberUpdateStart(app)
    // While Android's permission is being explained, that is the only dialog up.
    if (update.asking) return
    val start = update.start
    when (val s = state) {
        is Update.Available -> SeamAlertDialog(
            onDismissRequest = onClose,
            title = { Text("Stamp ${s.release.version} is out") },
            text = { if (s.release.notes.isNotEmpty()) Text(s.release.notes) },
            dismissButton = { TextButton(onClick = onClose) { Text("Later") } },
            confirmButton = { TextButton(onClick = { start(s.release) }) { Text("Update") } },
        )
        is Update.Downloading -> SeamAlertDialog(
            onDismissRequest = onClose,
            title = { Text("Downloading Stamp ${s.release.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.total > 0) LinearProgressIndicator(progress = { s.fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(megabytes(s.done, s.total))
                }
            },
            confirmButton = { TextButton(onClick = onClose) { Text("Hide") } },
        )
        is Update.Failed -> SeamAlertDialog(
            onDismissRequest = onClose,
            title = { Text("The update didn't install") },
            text = { Text(s.why) },
            dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
            confirmButton = { TextButton(onClick = { start(s.release) }) { Text("Try again") } },
        )
        // Android's own installer has the screen now, or there is nothing to say.
        is Update.Installing, Update.None -> LaunchedEffect(Unit) { onClose() }
    }
}

/** "12 of 40 MB", or just what has come in when the size is not known. */
internal fun megabytes(done: Long, total: Long): String {
    fun mb(b: Long) = "%.0f".format(b / 1_048_576.0)
    return if (total > 0) "${mb(done)} of ${mb(total)} MB" else "${mb(done)} MB"
}

/**
 * Starts an update. The first time, Android has to let Stamp install apps: that is explained,
 * the right switch is opened, and the update goes on by itself once it is on.
 */
@Composable
fun rememberUpdateStart(app: StampApp): UpdateStart {
    val context = LocalContext.current
    var asking by remember { mutableStateOf<Release?>(null) }
    fun allowed() = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val r = asking
        asking = null
        if (r != null && allowed()) app.updates.install(r)
    }
    asking?.let { r ->
        SeamAlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Let Stamp install its updates") },
            text = { Text("Android asks this once. Turn on Allow from this source, then come back and the update goes on.") },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Not now") } },
            confirmButton = {
                TextButton(onClick = {
                    settings.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("Open settings") }
            },
        )
    }
    return UpdateStart(asking != null) { r -> if (allowed()) app.updates.install(r) else asking = r }
}

/** Starts an update with [start]; [asking] while Android's permission is being explained. */
class UpdateStart(val asking: Boolean, val start: (Release) -> Unit)
