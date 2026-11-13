package com.marcusrosado.stamp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.marcusrosado.stamp.ui.Root
import com.marcusrosado.stamp.ui.StampTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as StampApp
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
