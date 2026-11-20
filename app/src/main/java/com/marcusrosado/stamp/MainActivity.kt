package com.marcusrosado.stamp

import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.marcusrosado.stamp.ui.Root
import com.marcusrosado.stamp.ui.StampTheme

class MainActivity : ComponentActivity() {
    /** What the latest intent asked for, until the app has done it; null for a plain launch. */
    private val launches = MutableStateFlow<Launch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // A recreated activity gets the intent it first came with: that was done already.
        if (savedInstanceState == null) launches.value = Launch.of(intent, ::typeOf)
        val app = application as StampApp
        // First: the map data and the marks start reading while the window is set up.
        app.warmUp()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (app.visitsLoading.isCompleted) show(app) else {
            // The marks are still being read off the main thread: hold the first frame, so the
            // launch screen stays up instead of an empty window, and show the app once they are in.
            val content = findViewById<View>(android.R.id.content)
            val hold = ViewTreeObserver.OnPreDrawListener { false }
            content.viewTreeObserver.addOnPreDrawListener(hold)
            lifecycleScope.launch {
                app.visitsLoading.await()
                show(app)
                content.viewTreeObserver.removeOnPreDrawListener(hold)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Launch.of(intent, ::typeOf)?.let { launches.value = it }
    }

    /** A file handed over with no type on the intent: ask the app that holds it. */
    private fun typeOf(uri: android.net.Uri): String? = runCatching { contentResolver.getType(uri) }.getOrNull()

    private fun show(app: StampApp) {
        setContent {
            val look by app.look.collectAsState()
            StampTheme(look) {
                androidx.compose.runtime.CompositionLocalProvider(com.marcusrosado.stamp.ui.LocalHinge provides com.marcusrosado.stamp.ui.rememberHinge()) {
                    Root(app, launches)
                }
            }
        }
    }
}
