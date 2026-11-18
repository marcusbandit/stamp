package com.marcusrosado.stamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.marcusrosado.stamp.data.VisitStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Every change that takes something away can be taken back: the change runs at once, and a
 * snackbar offers Undo, which puts the store back the way it was just before. This is why most
 * removals ask nothing first.
 */
class Undo(private val store: VisitStore, private val scope: CoroutineScope) {
    val host = SnackbarHostState()

    /** Runs [change] and, when it changed anything, offers to undo it under [message]. */
    fun run(message: String, change: () -> Unit) {
        // Watched from the moment of the change: a change that lands before the snackbar has
        // shown still takes the offer away.
        val before = store.snapshot()
        change()
        if (store.unchangedSince(before)) return
        val after = store.snapshot()
        offer(message, changedLater = { !store.unchangedSince(after) }) { store.revert(before) }
    }

    /**
     * Shows [message] with an Undo button that runs [back]. Any later change to the store takes
     * the offer away, since undoing then would throw that change away too.
     */
    fun offer(message: String, changedLater: () -> Boolean = { false }, back: () -> Unit) {
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            // A newer change replaces the older offer; the older one can no longer be undone.
            host.currentSnackbarData?.dismiss()
            val watch = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                merge(store.visits.drop(1), store.allTrips.drop(1)).first { changedLater() }
                host.currentSnackbarData?.dismiss()
            }
            val r = host.showSnackbar(message, actionLabel = "Undo", withDismissAction = true, duration = SnackbarDuration.Long)
            watch.cancel()
            if (r == SnackbarResult.ActionPerformed && !changedLater()) back()
        }
    }

    /** A short note with nothing to undo, in place of a toast. It waits for an Undo showing, never replaces it. */
    fun say(message: String) {
        scope.launch { host.showSnackbar(message, withDismissAction = true) }
    }
}

val LocalUndo = staticCompositionLocalOf<Undo?> { null }

/**
 * Where the snackbar shows: at the bottom, above the bottom bar on the folded phone, and on the
 * dialogs' half when the screen is bent, so it never sits on the fold.
 */
@Composable
fun UndoHost(undo: Undo, aboveBar: Dp) {
    InHalf(dialogHalf(LocalHinge.current), contentAlignment = Alignment.BottomCenter) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = aboveBar), contentAlignment = Alignment.BottomCenter) {
            SnackbarHost(undo.host, Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
        }
    }
}
