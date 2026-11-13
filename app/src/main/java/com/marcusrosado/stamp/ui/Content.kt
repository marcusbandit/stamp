package com.marcusrosado.stamp.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Top level destinations. The bar, the rail and the pane all render from this list. */
enum class Destination(val label: String, val icon: ImageVector) {
    Map("Map", Icons.Filled.Public),
    Passport("Passport", Icons.AutoMirrored.Filled.MenuBook),
    Settings("Settings", Icons.Filled.Settings),
}
