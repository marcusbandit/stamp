package com.marcusrosado.stamp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private val ITEM = 56.dp

/**
 * A row of choices that scrolls sideways and settles with one in the middle, the way a date
 * wheel does on its side. Swiping picks the one that lands in the middle, tapping picks the one
 * tapped, and a change from outside scrolls it into place.
 */
@Composable
fun Wheel(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
    val pick by rememberUpdatedState(onSelect)
    // Read live in the effects below, which outlive any one value of it.
    val current by rememberUpdatedState(selected)
    // The item nearest the middle, read from what is laid out.
    val centre by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - mid) }?.index ?: current
        }
    }
    // A swipe that comes to rest picks what it rested on.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect { if (centre != current) pick(centre) }
    }
    // A pick from outside, such as the arrows rolling into the next month, scrolls to it.
    LaunchedEffect(selected) {
        if (centre != selected && !state.isScrollInProgress) state.animateScrollToItem(selected)
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Padding on both sides lets the first and last choice reach the middle.
        val side = ((maxWidth - ITEM) / 2).coerceAtLeast(0.dp)
        Box(
            Modifier
                .align(Alignment.Center)
                .width(ITEM)
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        LazyRow(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(horizontal = side),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            itemsIndexed(labels) { i, label ->
                val on = i == centre
                Box(
                    Modifier
                        .width(ITEM)
                        .height(48.dp)
                        .clickable { scope.launch { state.animateScrollToItem(i) }; pick(i) }
                        .alpha(if (on) 1f else 0.55f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = if (on) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
