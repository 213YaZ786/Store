package com.yaz.store.ui.component

// Ported from Files (YAZ/Files at HEAD, ui/component/LoadingMark.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, ui/component/LoadingMark.kt):
// same signature and the same infinite clock, drawing the placeholder icon's storefront (Material Symbols "storefront", rounded,
// Apache License 2.0, https://github.com/google/material-design-icons, symbols/android/storefront; the path is that of
// marella/material-symbols svg/400/rounded/storefront-fill.svg, on a grid running from y -960 to 0) instead of Files' folder.
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yaz.store.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * Store's loading mark, the launcher icon in motion: the storefront breathes
 * while [running], and rests otherwise. [progress] is not drawn.
 */
// Files' LoadingMark.
@Composable
fun LoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    running: Boolean = true,
    @Suppress("UNUSED_PARAMETER") progress: Float = 0f
) {
    // The launcher icon's own tone (ic_launcher_glyph), so the mark is the icon by day and by night.
    val tone = colorResource(R.color.ic_launcher_glyph)
    val store = remember { PathParser().parsePathString(STOREFRONT).toPath() }
    val transition = rememberInfiniteTransition(label = "store")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        label = "clock"
    )

    Canvas(modifier.size(size)) {
        val breath = if (running) 1f + BREATH * sin(2f * PI.toFloat() * clock) else 1f
        val factor = this.size.minDimension * PLATE / STORE_WIDTH * breath
        val middle = center
        withTransform({
            // The storefront's centre, (480, -480) on its grid, to the canvas centre.
            translate(middle.x, middle.y)
            scale(factor, factor, androidx.compose.ui.geometry.Offset.Zero)
            translate(-480f, 480f)
        }) {
            drawPath(store, tone)
        }
    }
}

/** Material Symbols "storefront", rounded, filled, on its grid (x 0 to 960, y -960 to 0). */
private const val STOREFRONT =
    "M179-120q-24 0-42-18t-18-42v-339q-28-24-37-59t2-70l43-135q8-27 28-42t46-15h553q28 0 49 15.5t29 41.5l44 135q11 35 1.5 70T840-519v339q0 24-18 42t-42 18H179Zm391-430q29 0 49-19t16-46l-25-165H510v165q0 26 17 45.5t43 19.5Zm-187 0q28 0 47.5-19t19.5-46v-165H350l-25 165q-4 26 14 45.5t44 19.5Zm-182 0q24 0 41.5-16.5T263-607l26-173H189l-46 146q-10 31 8 57.5t50 26.5Zm557 0q32 0 50.5-26t8.5-58l-46-146H671l26 173q3 24 20.5 40.5T758-550Z"

/** The storefront's width on its grid (80 to 880). */
private const val STORE_WIDTH = 800f
/** The storefront's share of the mark. */
private const val PLATE = 0.94f
private const val BREATH = 0.05f
private const val LOOP_MILLIS = 1800
