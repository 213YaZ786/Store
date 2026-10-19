package com.yaz.store.ui.component

// Ported from Files (YAZ/Files at HEAD, feature/storage/PullIndicator.kt), itself from MTGA (https://github.com/213YaZ786/MTGA, ui/component/PullIndicator.kt),
// itself Material 3's standard indicator (PullToRefreshDefaults.Indicator, https://developer.android.com/develop/ui/compose/components/pull-to-refresh)
// with the app's loading mark in place of the spinner and a haptic tick at the threshold. Placed like the standard indicator,
// sliding down from the top edge of its box in a small disc of glass.
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.yaz.store.ui.glass.LocalGlass
import com.yaz.store.ui.glass.LocalGlassBackdrop
import com.yaz.store.ui.glass.glassFloating

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullIndicator(state: PullToRefreshState, isRefreshing: Boolean, modifier: Modifier = Modifier) {
    val threshold = PullToRefreshDefaults.PositionalThreshold
    val fraction = state.distanceFraction
    val haptics = rememberHaptics()
    val refreshing by rememberUpdatedState(isRefreshing)
    LaunchedEffect(state) {
        var reached = false
        snapshotFlow { (state.distanceFraction >= 1f) to (state.isAnimating || refreshing) }
            .collect { (over, settling) ->
                if (!settling && over != reached) haptics.threshold(over)
                reached = over
            }
    }
    Box(
        modifier.graphicsLayer {
            translationY = fraction * threshold.toPx() - this.size.height
            alpha = if (isRefreshing) 1f else (fraction * 2f).coerceIn(0f, 1f)
        }
    ) {
        // In glass, the disc bends the list under it, with no shadow.
        val look = LocalGlass.current
        val backdrop = LocalGlassBackdrop.current
        val glass = look != null && backdrop != null
        Surface(
            shape = CircleShape,
            color = if (glass) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = if (glass) 0.dp else 3.dp,
            modifier = if (glass) Modifier.size(56.dp).glassFloating(backdrop!!, CircleShape, look!!) else Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                LoadingMark(size = 44.dp, running = isRefreshing, progress = fraction.coerceIn(0f, 1f))
            }
        }
    }
}
