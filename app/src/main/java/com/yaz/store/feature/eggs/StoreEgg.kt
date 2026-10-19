package com.yaz.store.feature.eggs

// The easter egg of Store: "Up to date" tapped seven times (as Android's version is, https://developer.android.com/about/versions) while every app is
// current. The shop opens: the storefront of Store's loading mark pops in at the middle of the window on a spring and a wave of tinted glass
// with Android 12's sparkles leaves the row that was tapped, then both fade. Only the base kit's own pieces are used: LoadingMark,
// GlassWave and drawGlassWave (Dialer's hang up wave, ui/component/GlassWave.kt, itself AOSP's RippleShader in SparkleRipple.kt) and the
// haptics; the animation is Compose's Animatable with a spring and a tween (https://developer.android.com/develop/ui/compose/animation/value-based).
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yaz.store.R
import com.yaz.store.ui.component.GlassWave
import com.yaz.store.ui.component.LoadingMark
import com.yaz.store.ui.component.drawGlassWave
import com.yaz.store.ui.component.rememberHaptics
import kotlinx.coroutines.launch

/** Plays once over the whole window and calls [onEnd] when it is gone. It takes no touch. */
@Composable
fun StoreEgg(origin: Offset, onEnd: () -> Unit) {
    val haptics = rememberHaptics()
    val end = rememberUpdatedState(onEnd)
    val tint = MaterialTheme.colorScheme.primary
    // One wave, so its sparkle shader is made once.
    val wave = remember(origin, tint) { GlassWave(tint, origin) }
    val spread = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptics.done()
        launch { pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 180f)) }
        spread.animateTo(1f, tween(1300, easing = FastOutSlowInEasing))
        fade.animateTo(0f, tween(450))
        end.value()
    }
    val label = stringResource(R.string.egg_label)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawGlassWave(wave, spread.value, fade.value) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        LoadingMark(
            size = 180.dp,
            modifier = Modifier.graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                alpha = fade.value
            }
        )
    }
}
