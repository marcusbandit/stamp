package com.marcusrosado.stamp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Puts a trip's stops in order: one short row each, held and dragged to where it belongs, the
 * others moving out of its way as it passes. The handle drags at once; anywhere else on a row
 * drags after a press and hold. Hands back the new order as indexes into [names].
 */
@Composable
fun StopOrderDialog(names: List<String>, onDone: (List<Int>) -> Unit, onDismiss: () -> Unit) {
    var order by remember { mutableStateOf(names.indices.toList()) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { ROW.toPx() }
    val haptics = LocalHapticFeedback.current
    fun lift(id: Int) {
        dragging = id
        offset = 0f
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    fun drag(dy: Float) {
        val id = dragging ?: return
        offset += dy
        val at = order.indexOf(id)
        val to = (at + (offset / rowPx).roundToInt()).coerceIn(0, order.lastIndex)
        if (to != at) {
            order = order.toMutableList().apply { add(to, removeAt(at)) }
            // The row stays under the finger: what it moved is taken off what is left to drag.
            offset -= (to - at) * rowPx
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    fun drop() { dragging = null; offset = 0f }
    SeamAlertDialog(
        onDismissRequest = onDismiss,
        outsideCloses = order == names.indices.toList(),
        title = { Text("Order of stops") },
        text = {
            Column {
                // Laid out in the order they started in, each moved to its place, so a row keeps
                // its gesture while the list changes under it.
                names.indices.forEach { id ->
                    key(id) {
                        val at = order.indexOf(id)
                        val lifted = dragging == id
                        // Rows that make room glide to their new place rather than jump.
                        val slide = remember { Animatable(0f) }
                        var was by remember { mutableIntStateOf(at) }
                        LaunchedEffect(at) {
                            // Noted before the glide, so a glide cut short by the next move
                            // carries on from where it was and never leaves a row astray.
                            val from = was
                            was = at
                            if (!lifted && from != at) {
                                slide.snapTo(slide.value + (from - at) * rowPx)
                                slide.animateTo(0f)
                            } else if (lifted) slide.snapTo(0f)
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(ROW)
                                .zIndex(if (lifted) 1f else 0f)
                                .graphicsLayer {
                                    translationY = (at - id) * rowPx + if (lifted) offset else slide.value
                                    if (lifted) { scaleX = 1.03f; scaleY = 1.03f; shadowElevation = 8.dp.toPx() }
                                    shape = RoundedCornerShape(14.dp)
                                    clip = lifted
                                }
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (lifted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh)
                                .pointerInput(id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { lift(id) },
                                        onDrag = { change, d -> change.consume(); drag(d.y) },
                                        onDragEnd = { drop() },
                                        onDragCancel = { drop() },
                                    )
                                }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${at + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(
                                names[id], style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Box(
                                Modifier.size(44.dp).pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = { lift(id) },
                                        onDrag = { change, d -> change.consume(); drag(d.y) },
                                        onDragEnd = { drop() },
                                        onDragCancel = { drop() },
                                    )
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.DragHandle, contentDescription = "Drag to move", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { onDone(order) }) { Text("Done") } },
    )
}

private val ROW = 56.dp
