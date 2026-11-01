package com.yaz.store.feature.home

// The icon of an app on a card or a page. Sources:
// - an installed app: its own icon from PackageManager (getApplicationIcon, i.e. loadIcon,
//   https://developer.android.com/reference/android/content/pm/PackageManager#getApplicationIcon(java.lang.String)), only when its signer is
//   checked as ours (OurKeys), drawn to a bitmap with androidx.core's Drawable.toBitmap;
// - an app not installed: its published layers (icon-layers.json), drawn in the phone's Material You colours as the launcher draws them
//   (PhoneIcons.layered); else icon.png of its release, decoded only by the isolated DecoderService (core/security/SafeImages, the safemedia kit),
//   never BitmapFactory. The APK's own icon cannot be drawn from the few ranges read of it: an isolated process may not reopen the file it is
//   given as a path (EACCES on /proc/self/fd/N, measured 2026-10-19), so PackageManager.getPackageArchiveInfo answers null, and
//   ResourcesProvider.loadFromApk does not make another package's resources addressable (its ids stay 0), so there is no loadIcon there;
// - else Material Symbols' grid icon (ui/icon/AppIcons.Apps).
// Every one of them is shown in the round pane of glass of the shared GlassIcon.
// The loaded bitmaps wait in an android.util.LruCache (https://developer.android.com/reference/android/util/LruCache), as the Android
// image-loading guide has it (https://developer.android.com/topic/performance/graphics/cache-bitmap), and are loaded off the main thread
// from produceState (https://developer.android.com/develop/ui/compose/side-effects#producestate).
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.yaz.store.core.security.SafeImages
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.IconStore
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.trust.OurKeys
import com.yaz.store.ui.component.GlassIcon
import com.yaz.store.ui.icon.AppIcons
import com.yaz.store.ui.icon.SystemTones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where icons come from; the default has none, so a screen drawn in a test shows the generic icon. */
fun interface IconSource {
    suspend fun load(status: AppStatus): ImageBitmap?
}

val LocalIconSource = staticCompositionLocalOf { IconSource { null } }

/** The phone's and the releases' icons. */
class PhoneIcons(private val context: Context, private val releases: IconStore) : IconSource {

    override suspend fun load(status: AppStatus): ImageBitmap? {
        // An app not installed: its published layers first, drawn in this phone's colours now (kept under a key that holds them); else its icon.png.
        if (status.installed == null) withContext(Dispatchers.IO) { layered(status.entry) }?.let { return it }
        val key = "${status.entry.packageName}/${status.installed?.versionCode ?: "release-" + status.entry.tag}"
        memory.get(key)?.let { return it }
        val picture = withContext(Dispatchers.IO) { read(status) } ?: return null
        memory.put(key, picture)
        return picture
    }

    private suspend fun read(status: AppStatus): ImageBitmap? {
        val facts = status.installed
        if (facts != null) {
            // Only an app signed by one of ours shows its own icon; another is a name and a generic picture.
            if (!OurKeys.allOurs(facts.signers)) return null
            return runCatching {
                context.packageManager.getApplicationIcon(status.entry.packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
            }.getOrNull()
        }
        val bytes = releases.bytes(status.entry) ?: return null
        return SafeImages.decode(context, bytes, ICON_PX)?.asImageBitmap()
    }

    // Source: AOSP's adaptive icon and its tint. The launcher draws our icon from its layers on the 108 dp canvas and shows the central 72 dp
    // (https://developer.android.com/develop/ui/views/launch/icon_design_adaptive); a tinted layer is a BitmapDrawable with a multiply tint, which
    // Drawable.updateBlendModeFilter turns into new BlendModeColorFilter(color, blendMode) and BitmapDrawable.draw sets on its paint
    // (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/graphics/java/android/graphics/drawable/Drawable.java,
    // .../BitmapDrawable.java), PorterDuff MULTIPLY being BlendMode.MODULATE. An InsetDrawable draws its picture into the bounds shrunk by its
    // fractions (.../InsetDrawable.java, onBoundsChange). The pictures are decoded by the isolated DecoderService at the size of the whole canvas,
    // so the 72 dp shown are exactly ICON_PX. Day or night is the system's (the launcher follows it, not Store's theme setting), read from the
    // application context's configuration (https://developer.android.com/reference/android/content/res/Configuration#uiMode).
    // Null (icon.png is kept) when the repo publishes no layers or any step fails.
    private suspend fun layered(entry: StoreEntry): ImageBitmap? {
        val files = runCatching { releases.layers(entry) }.getOrNull() ?: return null
        val app = context.applicationContext
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ground = SystemTones.colour(app, if (night) files.icon.ground.night else files.icon.ground.day) ?: return null
        val tints = ArrayList<Int?>()
        for (layer in files.icon.layers) {
            val tones = layer.tones
            tints.add(if (tones == null) null else SystemTones.colour(app, if (night) tones.night else tones.day) ?: return null)
        }
        // The colours are in the key: a new wallpaper gives new colours, and night another set of tones.
        val key = "layers/${entry.repo}/${files.icon.layers.joinToString(",") { it.sha256 }}/$night/$ground/${tints.joinToString(",")}/${files.icon.inset}"
        memory.get(key)?.let { return it }
        val pictures = files.pictures.map { SafeImages.decode(app, it, CANVAS_PX) ?: return null }
        try {
            if (pictures.any { it.width < MIN_SIDE || it.height < MIN_SIDE }) return null
            val result = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(ground)
            // The bitmap is the central 72 dp of the 108 dp canvas: draw the canvas shifted so that its middle is the bitmap's middle.
            val shift = (CANVAS_PX - ICON_PX) / 2f
            canvas.translate(-shift, -shift)
            val inset = files.icon.inset
            val frame = RectF(
                CANVAS_PX * (inset?.left ?: 0f), CANVAS_PX * (inset?.top ?: 0f),
                CANVAS_PX * (1f - (inset?.right ?: 0f)), CANVAS_PX * (1f - (inset?.bottom ?: 0f))
            )
            pictures.forEachIndexed { i, picture ->
                val paint = Paint().apply {
                    isFilterBitmap = true
                    tints[i]?.let { colorFilter = BlendModeColorFilter(it, BlendMode.MODULATE) }
                }
                canvas.drawBitmap(picture, null, frame, paint)
            }
            return result.asImageBitmap().also { memory.put(key, it) }
        } catch (e: RuntimeException) {
            return null
        } finally {
            pictures.forEach { it.recycle() }
        }
    }

    private companion object {
        const val ICON_PX = 192
        /** The whole 108 dp canvas at the scale where its central 72 dp are ICON_PX. */
        const val CANVAS_PX = ICON_PX * 108 / 72
        /** A layer smaller than this is not a picture of the canvas. */
        const val MIN_SIDE = 108
        val memory = LruCache<String, ImageBitmap>(48)
    }
}

/**
 * The icon of [status], [size] wide, in a round pane of glass (the shared GlassIcon, after the phone kit's ContactAvatar); the grid icon in
 * the same pane until it loads or when it has none.
 */
@Composable
fun AppIcon(status: AppStatus, size: Dp, modifier: Modifier = Modifier) {
    val source = LocalIconSource.current
    // The system's night mode is a key: Store handles uiMode itself, so night coming or going does not recreate the screen.
    val night = LocalConfiguration.current.uiMode
    val picture by produceState<ImageBitmap?>(null, status.entry.packageName, status.entry.tag, status.installed?.versionCode, source, night) {
        value = source.load(status)
    }
    GlassIcon(picture, size, modifier) {
        Icon(AppIcons.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * 0.5f))
    }
}

/** Gives [content] the icons of the phone. */
@Composable
fun WithIcons(source: IconSource, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIconSource provides source, content = content)
}
