package com.yaz.store.feature.home

// The Update button (Updates rows, Update all, the app page): red glass with a slow glint across it. Sources:
// - the red glass is Dialer's coloured answer and hang up buttons (our own code, MIT, https://github.com/213YaZ786/Dialer,
//   app/src/main/java/com/yaz/dialer/feature/call/IncomingChoice.kt, rememberTinted): the same GlassLook copy with zoneTint and floatTint set,
//   never a change to the shared shader; the tint is the theme's error colour at 0.66, as Dialer's deep glass;
// - the glint is the front of Dialer's hang up wave (this app's copies of the panes kit, ui/component/GlassWave.kt, drawGlassWave): a soft
//   band, a bright hairline on it and Android 12's sparkles in a band along the front (ui/component/SparkleRipple.kt, AOSP's RippleShader),
//   here from a point left of the button, so the front crosses it once and leaves; no wash behind it;
// - it runs only while the button is on screen and the app is resumed: Modifier.onVisibilityChanged
//   (https://developer.android.com/reference/kotlin/androidx/compose/ui/layout/package-summary#(androidx.compose.ui.Modifier).onVisibilityChanged(kotlin.Long,kotlin.Float,androidx.compose.ui.layout.RootCoordinates,kotlin.Function1))
//   and Lifecycle.currentStateAsState (https://developer.android.com/reference/kotlin/androidx/lifecycle/compose/package-summary#(androidx.lifecycle.Lifecycle).currentStateAsState()).
// - "More", the small link just before the button of an update row: iOS App Store's "more" beside its Update button.
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.yaz.store.R
import com.yaz.store.ui.component.BoldButton
import com.yaz.store.ui.component.SparkleRipple
import com.yaz.store.ui.component.sinkClickable
import com.yaz.store.ui.glass.GlassLook
import com.yaz.store.ui.glass.LocalGlass
import kotlinx.coroutines.delay
import kotlin.math.hypot

/** The button of an update: red glass, shining. Without glass, the plain accent button. */
@Composable
internal fun UpdateButton(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val glass = LocalGlass.current
    if (glass == null) {
        BoldButton(onClick = onClick, modifier = modifier, filled = true) { Text(label, maxLines = 1) }
        return
    }
    // The theme's error colour; by night its deep container, since the night's error is a pale pink that does not read as red glass.
    val scheme = MaterialTheme.colorScheme
    val red = if (glass.dark) scheme.errorContainer else scheme.error
    val onRed = if (glass.dark) scheme.onErrorContainer else scheme.onError
    val tinted = remember(glass, red) { tintedLook(glass, red.copy(alpha = if (glass.dark) 0.85f else 0.66f)) }
    val shape = ButtonDefaults.outlinedShape
    val progress = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    val shining = visible && resumed
    // One pass every few seconds, slow; nothing runs while the button is off screen or the app is not in front.
    LaunchedEffect(shining) {
        progress.snapTo(0f)
        if (!shining) return@LaunchedEffect
        delay(600)
        while (true) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
            delay(3200)
        }
    }
    val sparkle = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) SparkleRipple() else null }
    CompositionLocalProvider(LocalGlass provides tinted) {
        BoldButton(
            onClick = onClick,
            modifier = modifier
                .onVisibilityChanged(minDurationMs = 0L, minFractionVisible = 0.1f) { visible = it }
                .drawWithContent {
                    drawContent()
                    val p = progress.value
                    if (p > 0f && p < 1f) {
                        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent)) }
                        clipPath(path) { drawGlint(sparkle, p) }
                    }
                }
        ) { Text(label, color = onRed, maxLines = 1, overflow = TextOverflow.Clip) }
    }
}

/** The small "More" before an update's button: opens the app's page; a transparent tap target, not a second pane of glass. */
@Composable
internal fun MoreLink(appName: String, onClick: () -> Unit) {
    Text(
        stringResource(R.string.more),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = Modifier.sinkClickable(appName, depth = 0.92f, onClick = onClick).padding(start = 0.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)
    )
}

/** Dialer's rememberTinted without the remember: the same glass as [look], tinted with [tint] instead of the zone's own fill. */
private fun tintedLook(look: GlassLook, tint: Color) =
    GlassLook(look.dark, look.ground, look.halos, zoneTint = tint, floatTint = tint, accentTint = look.accentTint)

/**
 * The front of Dialer's wave (drawGlassWave) from a point left of the button, at [progress] (0 to 1): a soft band of white, a bright
 * hairline on it, and the sparkles in a band along it. Fades out as it leaves.
 */
private fun DrawScope.drawGlint(sparkle: SparkleRipple?, progress: Float) {
    val origin = Offset(-size.height, size.height / 2f)
    val far = hypot(size.width - origin.x, size.height / 2f)
    val radius = far * progress
    if (radius < 1f) return
    val alpha = 1f - progress * progress * progress
    drawCircle(Color.White.copy(alpha = 0.26f * alpha), radius, origin, style = Stroke(width = 20.dp.toPx()))
    drawCircle(Color.White.copy(alpha = 0.7f * alpha), radius, origin, style = Stroke(width = 1.5.dp.toPx()))
    if (sparkle != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // The shader's ring sits at maxRadius x its progress, so it follows the front up to where its sparkles fade (GlassWave's span).
        sparkle.update(
            centre = origin,
            area = size,
            maxRadius = far / SPARKLE_SPAN,
            progress = progress * SPARKLE_SPAN,
            noisePhase = (SystemClock.uptimeMillis() % 100_000) * SparkleRipple.PHASE_PER_MS,
            color = Color.Transparent,
            sparkle = Color.White.copy(alpha = alpha)
        )
        val band = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(origin, radius + 8.dp.toPx()))
            addOval(Rect(origin, (radius - 22.dp.toPx()).coerceAtLeast(0f)))
        }
        clipPath(band) { drawRect(ShaderBrush(sparkle.shader)) }
    }
}

private const val SPARKLE_SPAN = 0.47f
