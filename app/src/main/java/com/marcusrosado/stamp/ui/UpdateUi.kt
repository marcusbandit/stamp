package com.marcusrosado.stamp.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marcusrosado.stamp.StampApp
import com.marcusrosado.stamp.data.Release
import com.marcusrosado.stamp.data.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Says so once when a newer Stamp is out: on the first launch that finds each version. After
 * that, the update waits quietly in Settings, About Stamp. Also shows Android's install question
 * when the installer asks for it, which only a screen in front may do.
 */
@Composable
fun UpdatePrompt(app: StampApp) {
    val context = LocalContext.current
    val on by app.updateCheck.on.collectAsStateWithLifecycle()
    LaunchedEffect(on) { if (on) app.updates.check() }
    val setAside by app.setAside.collectAsStateWithLifecycle()
    // Collected only while the app is in front, so the question waits for the user to come back.
    val state by app.updates.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        val s = state as? Update.Confirm ?: return@LaunchedEffect
        runCatching { context.startActivity(s.ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        app.updates.confirmShown(s.release)
    }
    // The version this launch's dialog is about, kept across a fold or a theme change.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val available = (state as? Update.Available)?.release
    LaunchedEffect(available?.version) {
        val v = available?.version ?: return@LaunchedEffect
        if (withContext(Dispatchers.IO) { app.updates.prompted() } != v) {
            open = v
            app.updates.markPrompted(v)
        }
    }
    val update = rememberUpdateStart(app)
    // A save that could not be read asks first; the update can wait for the next launch.
    if (open != null && setAside.isEmpty()) UpdateDialog(app, update) { open = null }
}

/** The update as it stands: what is new, the download, or what went wrong. */
@Composable
fun UpdateDialog(app: StampApp, update: UpdateStart, onClose: () -> Unit) {
    val state by app.updates.state.collectAsStateWithLifecycle()
    // While Android's permission is being explained, that is the only dialog up.
    if (update.asking) return
    when (val s = state) {
        is Update.Available -> SeamAlertDialog(
            onDismissRequest = onClose,
            title = { Text("Stamp ${s.release.version} is out") },
            text = { if (s.release.notes.isNotEmpty()) Text(s.release.notes) },
            dismissButton = { TextButton(onClick = onClose) { Text("Later") } },
            confirmButton = { TextButton(onClick = { update.start(s.release) }) { Text("Update") } },
        )
        is Update.Downloading -> SeamAlertDialog(
            onDismissRequest = onClose,
            title = { Text("Downloading Stamp ${s.release.version}") },
            text = { UpdateProgress(s) },
            confirmButton = { TextButton(onClick = onClose) { Text("Hide") } },
        )
        is Update.Failed -> SeamAlertDialog(
            onDismissRequest = onClose,
            title = { Text("The update didn't install") },
            text = { Text(s.why) },
            dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
            confirmButton = { TextButton(onClick = { update.start(s.release) }) { Text("Try again") } },
        )
        // Android's own installer has the screen now, or there is nothing to say.
        is Update.Confirm, is Update.Installing, Update.None -> LaunchedEffect(Unit) { onClose() }
    }
}

/** How far the download is: a bar that fills, or one that runs when the size is not known. */
@Composable
fun UpdateProgress(s: Update.Downloading, modifier: Modifier = Modifier) {
    if (s.total > 0) LinearProgressIndicator(progress = { s.fraction }, modifier = modifier.fillMaxWidth())
    else LinearProgressIndicator(modifier.fillMaxWidth())
}

/**
 * Starts an update. The first time, Android has to let Stamp install apps: that is explained,
 * the right switch is opened, and the update goes on by itself once it is on, even when Android
 * ended the app meanwhile, as it does when that switch changes.
 */
@Composable
fun rememberUpdateStart(app: StampApp): UpdateStart {
    val context = LocalContext.current
    // The version waiting on the permission, by name, so it survives the app being ended.
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var away by rememberSaveable { mutableStateOf<String?>(null) }
    fun allowed() = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val v = away
        away = null
        if (v != null && allowed()) app.updates.installWhenFound(v)
    }
    asking?.let { v ->
        SeamAlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Let Stamp install its updates") },
            text = { Text("Android asks this once. Turn on Allow from this source, then come back and the update goes on.") },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Not now") } },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    away = v
                    settings.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                    )
                }) { Text("Open settings") }
            },
        )
    }
    return UpdateStart(asking != null || away != null) { r -> if (allowed()) app.updates.install(r) else asking = r.version }
}

/** Starts an update with [start]; [asking] while Android's permission is being explained or set. */
class UpdateStart(val asking: Boolean, val start: (Release) -> Unit)
