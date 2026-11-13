package com.marcusrosado.stamp.ui

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * One way the whole app can look: its colours, the map's colours, and the face of its
 * headings. The user picks one in Settings or during the introduction.
 */
class Look(
    val id: String,
    val name: String,
    val dark: Boolean,
    val colors: ColorScheme,
    val map: MapPalette,
    /** Titles and headings; body text stays in the system face for reading. */
    val headings: FontFamily = FontFamily.Default,
)

/** Follows the phone's light or dark setting between [Night] and [Daylight]. */
const val AUTO_LOOK = "auto"

/**
 * A look's colours. Secondary and tertiary follow the primary, so Material's default purple
 * never shows; bg, then the low, mid, high and highest container steps fill the surfaces.
 * Anything not set keeps Material's own dark or light value.
 */
private fun scheme(
    isDark: Boolean,
    primary: Long, onPrimary: Long, primaryContainer: Long, onPrimaryContainer: Long,
    bg: Long, low: Long, mid: Long, high: Long, highest: Long,
    on: Long, onVariant: Long, outline: Long,
    secondaryContainer: Long = if (isDark) highest else high, onSecondaryContainer: Long = on,
    outlineVariant: Long = highest,
) = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = Color(primary), onPrimary = Color(onPrimary),
    secondary = Color(primary), onSecondary = Color(onPrimary), tertiary = Color(primary), onTertiary = Color(onPrimary),
    tertiaryContainer = Color(primaryContainer), onTertiaryContainer = Color(onPrimaryContainer),
    surfaceVariant = Color(high), surfaceTint = Color(primary),
    primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
    secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
    background = Color(bg), surface = Color(bg),
    surfaceContainerLowest = Color(if (isDark) bg else low), surfaceContainerLow = Color(low), surfaceContainer = Color(mid),
    surfaceContainerHigh = Color(high), surfaceContainerHighest = Color(highest),
    onSurface = Color(on), onSurfaceVariant = Color(onVariant),
    outline = Color(outline), outlineVariant = Color(outlineVariant),
)

private fun map(ocean: Long, land: Long, border: Long, selected: Long, visited: Long, lived: Long, want: Long, mini: Long = land) =
    MapPalette(Color(ocean), Color(land), Color(border), Color(selected), Color(visited), Color(lived), Color(want), Color(mini))

val Night = Look(
    "night", "Night", dark = true,
    colors = scheme(
        isDark = true,
        0xFF20C997, 0xFF00261B, 0xFF0B3D30, 0xFFB2F2DD,
        0xFF14171B, 0xFF1A1D22, 0xFF1E2227, 0xFF252A30, 0xFF2C3139,
        0xFFE6E9ED, 0xFF9AA3AD, 0xFF4A525C,
    ),
    map = map(0xFF101317, 0xFF343A45, 0xFF101317, 0xFFFFFFFF, 0xFF20C997, 0xFF748FFC, 0xFFFCC419, mini = 0xFF2C3139),
)

val Daylight = Look(
    "daylight", "Daylight", dark = false,
    colors = scheme(
        isDark = false,
        0xFF0C8F6A, 0xFFFFFFFF, 0xFFC3F5E4, 0xFF00382A,
        0xFFF6F8FA, 0xFFFFFFFF, 0xFFEFF2F5, 0xFFE9EDF1, 0xFFE2E7EC,
        0xFF1B1E22, 0xFF5B6570, 0xFFADB5BD,
        secondaryContainer = 0xFFE6ECF1, outlineVariant = 0xFFDDE2E7,
    ),
    map = map(0xFFCCD9E6, 0xFFFFFFFF, 0xFFC3CCD5, 0xFF1B1E22, 0xFF12B886, 0xFF5C7CFA, 0xFFF59F00, mini = 0xFFD3DAE1),
)

/** An old paper atlas: cream pages, terracotta stamps, ink-blue homes, serif titles. */
val OldAtlas = Look(
    "atlas", "Old atlas", dark = false,
    colors = scheme(
        isDark = false,
        0xFFA8492A, 0xFFFFF8EE, 0xFFF1D3C2, 0xFF4A1A0B,
        0xFFF3EAD8, 0xFFFBF5EA, 0xFFEDE2CC, 0xFFE6D8BE, 0xFFDDCCAE,
        0xFF2E2418, 0xFF6E5E48, 0xFFB5A386,
        secondaryContainer = 0xFFE9DCC3, onSecondaryContainer = 0xFF3B2F20,
    ),
    map = map(0xFFB5CBC1, 0xFFF8F1E2, 0xFFB09D7E, 0xFF2E2418, 0xFFC0603C, 0xFF35607E, 0xFFD39A2F, mini = 0xFFE2D4B8),
    headings = FontFamily.Serif,
)

/** Dusk over the sea: plum night, coral marks, lilac homes. */
val Sunset = Look(
    "sunset", "Sunset", dark = true,
    colors = scheme(
        isDark = true,
        0xFFFF8A5B, 0xFF3A1408, 0xFF5C2A2E, 0xFFFFD9C9,
        0xFF1D1426, 0xFF241830, 0xFF2A1D36, 0xFF33243F, 0xFF3A2A4A,
        0xFFF7E8EF, 0xFFC0A9C6, 0xFF5E4A6E,
        secondaryContainer = 0xFF3F2A4C, onSecondaryContainer = 0xFFF7E8EF,
    ),
    map = map(0xFF1D1426, 0xFF47355C, 0xFF1D1426, 0xFFFFF4EE, 0xFFFF8A5B, 0xFFC77DFF, 0xFFFFD166),
)

/** Deep water and reef colours: navy sea, aqua marks, coral homes. */
val Lagoon = Look(
    "lagoon", "Lagoon", dark = true,
    colors = scheme(
        isDark = true,
        0xFF3DD9D6, 0xFF003736, 0xFF0D4A55, 0xFFB8F5F3,
        0xFF0A1D2C, 0xFF0F2536, 0xFF12293B, 0xFF17324A, 0xFF1D3B55,
        0xFFE3F2F7, 0xFF8FB3C4, 0xFF3A5A72,
        secondaryContainer = 0xFF1F4058, onSecondaryContainer = 0xFFE3F2F7,
    ),
    map = map(0xFF0A1D2C, 0xFF1F4058, 0xFF0A1D2C, 0xFFFFFFFF, 0xFF3DD9D6, 0xFFFF7AA2, 0xFFFFC857),
)

/** Soft green tea and rice paper, calm and light. */
val Matcha = Look(
    "matcha", "Matcha", dark = false,
    colors = scheme(
        isDark = false,
        0xFF4F7D35, 0xFFFFFFFF, 0xFFD4E8C4, 0xFF15300A,
        0xFFEEF2E4, 0xFFFAFCF5, 0xFFE6ECDA, 0xFFDEE6CF, 0xFFD4DEC3,
        0xFF22301A, 0xFF5D6B52, 0xFFA7B498,
        secondaryContainer = 0xFFE3EBD4,
    ),
    map = map(0xFFB9D3CB, 0xFFF9FBF1, 0xFF8FA37E, 0xFF22301A, 0xFF5E9640, 0xFF8C6BB1, 0xFFE0A63A, mini = 0xFFD2DCC2),
)

/** Loud and happy: pink, violet and sunshine on cotton-candy white. */
val Bubblegum = Look(
    "bubblegum", "Bubblegum", dark = false,
    colors = scheme(
        isDark = false,
        0xFFD6336C, 0xFFFFFFFF, 0xFFFFD6E5, 0xFF4A0620,
        0xFFFFF2F7, 0xFFFFFFFF, 0xFFFFE6EF, 0xFFFFDCE9, 0xFFFAD0E0,
        0xFF33121F, 0xFF7A4D60, 0xFFE3A7BE,
        secondaryContainer = 0xFFEDE4FF, onSecondaryContainer = 0xFF2A1A5E,
    ),
    map = map(0xFFC6DCFF, 0xFFFFFFFF, 0xFFF0B6CB, 0xFF33121F, 0xFFFF5D8F, 0xFF7B61FF, 0xFFFFB020, mini = 0xFFF6D3E0),
)

val Looks = listOf(Night, Daylight, OldAtlas, Sunset, Lagoon, Matcha, Bubblegum)

/** The look for a saved id. [AUTO_LOOK], or an id no look has, follows the phone. */
fun lookFor(id: String, systemDark: Boolean, context: Context): Look =
    Looks.firstOrNull { it.id == id } ?: phoneLook(context, systemDark)

/**
 * The phone's own Material You colours, taken from the wallpaper on Android 12 and later, with
 * the map drawn from the same scheme: the primary for visits, the tertiary for homes. Older
 * phones get Night or Daylight.
 */
fun phoneLook(context: Context, dark: Boolean): Look {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return if (dark) Night else Daylight
    val c = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val map = if (dark) {
        // The darkest step for the sea and a lighter one for land, so the coasts read at a glance.
        MapPalette(
            ocean = c.surfaceContainerLowest, land = c.surfaceContainerHighest, border = c.surfaceContainerLowest,
            selected = c.onSurface, visited = c.primary, lived = c.tertiary, want = Color(0xFFFCC419),
            miniLand = c.surfaceContainerHighest,
        )
    } else {
        // A sea tinted with the wallpaper's secondary colour under near-white land.
        MapPalette(
            ocean = c.secondaryContainer, land = c.surfaceContainerLowest, border = c.outline.copy(alpha = 0.5f),
            selected = c.onSurface, visited = c.primary, lived = c.tertiary, want = Color(0xFFF59F00),
            miniLand = c.surfaceContainerHighest,
        )
    }
    // Fallbacks span the wheel, so whatever hue the wallpaper picked, one of them stands apart.
    val checked = map.separated(
        alsoVisited = listOf(c.secondary, Color(0xFF20C997), Color(0xFFFF8A5B)),
        alsoLived = listOf(c.secondary, Color(0xFFFF7AA2), Color(0xFFC77DFF), Color(0xFF20C997), Color(0xFFFF8A5B)),
        alsoWant = listOf(Color(0xFFFCC419), Color(0xFFFF8A5B), Color(0xFF3DD9D6), Color(0xFFFF7AA2)),
    )
    return Look(AUTO_LOOK, "Match phone", dark, c, checked)
}

/**
 * How far apart two colours look, as CIE76 delta E in Lab space. Around 2 is the smallest
 * difference people notice; below [MIN_APART] two map colours read as the same thing.
 */
fun apart(a: Color, b: Color): Double {
    fun lab(c: Color): DoubleArray {
        fun lin(v: Float): Double = if (v <= 0.04045f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        val r = lin(c.red); val g = lin(c.green); val bl = lin(c.blue)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * bl) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * bl
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * bl) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
    val p = lab(a); val q = lab(b)
    return Math.sqrt((p[0] - q[0]).let { it * it } + (p[1] - q[1]).let { it * it } + (p[2] - q[2]).let { it * it })
}

/** The smallest [apart] that marks may have from the land and from each other. */
const val MIN_APART = 25.0

/** Sea and land may be calmer, but never so close that the coast disappears. */
const val MIN_SEA_LAND = 15.0

/**
 * Pairs of map colours that must never look alike: each mark against the land and the other
 * marks. Sea against land is checked on its own, against [MIN_SEA_LAND].
 */
fun MapPalette.mustDiffer(): List<Triple<String, Color, Color>> = listOf(
    Triple("land/visited", land, visited),
    Triple("land/lived", land, lived),
    Triple("land/want", land, want),
    Triple("visited/lived", visited, lived),
    Triple("visited/want", visited, want),
    Triple("lived/want", lived, want),
)

/**
 * The wallpaper decides Match phone's colours, so they are checked when the app runs. Each mark
 * takes the candidate that stands furthest from the land and from the marks already chosen:
 * the wallpaper's own colour when it is clear enough, otherwise a fixed one that is.
 */
private fun MapPalette.separated(alsoVisited: List<Color>, alsoLived: List<Color>, alsoWant: List<Color>): MapPalette {
    fun pick(candidates: List<Color>, against: List<Color>): Color =
        candidates.firstOrNull { c -> against.all { apart(c, it) >= MIN_APART } }
            ?: candidates.maxBy { c -> against.minOf { apart(c, it) } }
    val v = pick(listOf(visited) + alsoVisited, listOf(land))
    val l = pick(listOf(lived) + alsoLived, listOf(land, v))
    val w = pick(listOf(want) + alsoWant, listOf(land, v, l))
    return copy(visited = v, lived = l, want = w)
}
