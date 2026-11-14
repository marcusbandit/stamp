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
import kotlinx.coroutines.launch
import com.marcusrosado.stamp.ui.Root
import com.marcusrosado.stamp.ui.StampTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as StampApp
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

    private fun show(app: StampApp) {
        setContent {
            val look by app.look.collectAsState()
            StampTheme(look) {
                androidx.compose.runtime.CompositionLocalProvider(com.marcusrosado.stamp.ui.LocalHinge provides com.marcusrosado.stamp.ui.rememberHinge()) {
                    Root(app)
                }
            }
        }
    }
}
