package com.marcusrosado.stamp.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** One side of a bent screen: left or top is [First], right or bottom is [Second]. */
enum class Half { First, Second }

/**
 * Lays [content] out in one half of a bent screen, and over the whole of it when flat. The
 * parent must fill the window, so the hinge's window coordinates hold. Bending moves and
 * resizes the box; it never rebuilds what is inside.
 */
@Composable
fun InHalf(
    half: Half,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit,
) {
    val hinge = LocalHinge.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val vertical = hinge?.vertical ?: true
        val full = if (vertical) maxWidth else maxHeight
        val (start, extent) = when {
            hinge == null -> 0.dp to full
            half == Half.First -> 0.dp to hinge.start.coerceIn(0.dp, full)
            else -> hinge.end.coerceIn(0.dp, full) to (full - hinge.end).coerceAtLeast(0.dp)
        }
        val motion = spring<Dp>(dampingRatio = 0.9f, stiffness = 320f)
        val s by animateDpAsState(start, motion, label = "halfStart")
        val e by animateDpAsState(extent, motion, label = "halfExtent")
        Box(
            Modifier
                .offset(x = if (vertical) s else 0.dp, y = if (vertical) 0.dp else s)
                .size(if (vertical) e else maxWidth, if (vertical) maxHeight else e),
            contentAlignment = contentAlignment,
        ) { content() }
    }
}

/**
 * The half a dialog goes on when the screen is bent: the right one like a book, where the
 * card and the rail already are, and the top one like a laptop, since the keyboard rises
 * from the bottom.
 */
fun dialogHalf(hinge: Hinge?): Half = if (hinge?.vertical == false) Half.First else Half.Second

/**
 * A dialog that never sits on the fold: centred on the screen when it is flat, and centred
 * on one half when it is bent. Taps outside the content dismiss it, as with any dialog, unless
 * [outsideCloses] is false: a dialog holding unsaved changes keeps them through a stray tap,
 * which on a bent phone is anywhere on the map's half. Back still closes it.
 * [content] sizes itself; it gets the half (or the screen) as its room, inside the safe area.
 */
@Composable
fun SeamDialog(
    onDismissRequest: () -> Unit,
    outsideCloses: Boolean = true,
    content: @Composable () -> Unit,
) {
    val hinge = LocalHinge.current
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().pointerInput(onDismissRequest, outsideCloses) { detectTapGestures { if (outsideCloses) onDismissRequest() } },
        ) {
            InHalf(dialogHalf(hinge), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Taps on the dialog itself, its title or text, stay there: only outside closes it.
                    Box(Modifier.pointerInput(Unit) { detectTapGestures { } }) { content() }
                }
            }
        }
    }
}

/**
 * Material's alert dialog, kept off the fold by [SeamDialog]: the same title, text and
 * buttons, and the text scrolls when a half is too short for all of it.
 */
@Composable
fun SeamAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    outsideCloses: Boolean = true,
) {
    SeamDialog(onDismissRequest, outsideCloses) {
        Surface(
            modifier = Modifier.widthIn(min = 280.dp, max = 560.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp)) {
                title?.let {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                            Box(Modifier.padding(bottom = 16.dp)) { it() }
                        }
                    }
                }
                text?.let {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                            Box(
                                Modifier.weight(1f, fill = false).heightIn(min = 0.dp)
                                    .verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                            ) { it() }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/**
 * A full-screen page that keeps off the fold: the whole screen when flat, and the second half
 * when bent, leaving the app beneath, usually the map, on the other half.
 */
@Composable
fun HalfPage(content: @Composable () -> Unit) {
    InHalf(Half.Second) { Surface(Modifier.fillMaxSize()) { content() } }
}
