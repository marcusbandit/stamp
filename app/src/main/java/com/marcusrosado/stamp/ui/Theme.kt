package com.marcusrosado.stamp.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.marcusrosado.stamp.data.TravelState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The only three font sizes in the app. Every text style below is built from one of them,
 * so hierarchy comes from weight and colour instead of a fourth size.
 */
object TypeScale {
    private const val BASE = 16f
    val small = (BASE * 0.75f).sp
    val normal = BASE.sp
    val large = (BASE * 1.5f).sp
}

private fun style(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size, lineHeight = size * 1.4f, fontWeight = weight)

private val AppTypography = Typography(
    displayLarge = style(TypeScale.large, FontWeight.Bold),
    displayMedium = style(TypeScale.large, FontWeight.Bold),
    displaySmall = style(TypeScale.large, FontWeight.Bold),
    headlineLarge = style(TypeScale.large, FontWeight.Bold),
    headlineMedium = style(TypeScale.large, FontWeight.Bold),
    headlineSmall = style(TypeScale.large, FontWeight.Medium),
    titleLarge = style(TypeScale.large, FontWeight.Medium),
    titleMedium = style(TypeScale.normal, FontWeight.Medium),
    titleSmall = style(TypeScale.normal, FontWeight.Medium),
    bodyLarge = style(TypeScale.normal),
    bodyMedium = style(TypeScale.normal),
    bodySmall = style(TypeScale.small),
    labelLarge = style(TypeScale.normal, FontWeight.Medium),
    labelMedium = style(TypeScale.small, FontWeight.Medium),
    labelSmall = style(TypeScale.small, FontWeight.Medium),
)

/** Map and stamp colours. One set per theme; the three marks keep their hue in both. */
data class MapPalette(
    val ocean: Color,
    val land: Color,
    val border: Color,
    val selected: Color,
    val visited: Color,
    val lived: Color,
    val want: Color,
    /** Land on the flat mini map, which sits on the page background rather than the ocean. */
    val miniLand: Color,
) {
    fun of(state: TravelState?): Color = when (state) {
        TravelState.Visited -> visited
        TravelState.Lived -> lived
        TravelState.Want -> want
        null -> land
    }
}

val LocalMapPalette = staticCompositionLocalOf { Night.map }

/** Headings in the look's own face; body text keeps the system face for reading. */
private fun typography(headings: FontFamily): Typography {
    fun h(t: TextStyle) = t.copy(fontFamily = headings)
    return AppTypography.copy(
        displayLarge = h(AppTypography.displayLarge),
        displayMedium = h(AppTypography.displayMedium),
        displaySmall = h(AppTypography.displaySmall),
        headlineLarge = h(AppTypography.headlineLarge),
        headlineMedium = h(AppTypography.headlineMedium),
        headlineSmall = h(AppTypography.headlineSmall),
        titleLarge = h(AppTypography.titleLarge),
    )
}

/** [lookId] is a saved look's id, or [AUTO_LOOK] to follow the phone's light or dark setting. */
@Composable
fun StampTheme(lookId: String = AUTO_LOOK, content: @Composable () -> Unit) {
    val look = lookFor(lookId, isSystemInDarkTheme(), androidx.compose.ui.platform.LocalContext.current)
    val dark = look.dark
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalMapPalette provides look.map) {
        MaterialTheme(
            colorScheme = look.colors,
            typography = typography(look.headings),
            content = content,
        )
    }
}
