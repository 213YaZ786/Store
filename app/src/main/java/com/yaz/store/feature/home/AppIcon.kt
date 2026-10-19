package com.yaz.store.feature.home

// The icon of an app on a card or a page. Sources:
// - an installed app: its own icon from PackageManager (getApplicationIcon, i.e. loadIcon,
//   https://developer.android.com/reference/android/content/pm/PackageManager#getApplicationIcon(java.lang.String)), only when its signer is
//   checked as ours (OurKeys), drawn to a bitmap with androidx.core's Drawable.toBitmap;
// - an app not installed: icon.png of its release, decoded only by the isolated DecoderService (core/security/SafeImages, the safemedia kit),
//   never BitmapFactory;
// - else Material Symbols' grid icon (ui/icon/AppIcons.Apps).
// The loaded bitmaps wait in an android.util.LruCache (https://developer.android.com/reference/android/util/LruCache), as the Android
// image-loading guide has it (https://developer.android.com/topic/performance/graphics/cache-bitmap), and are loaded off the main thread
// from produceState (https://developer.android.com/develop/ui/compose/side-effects#producestate).
import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.yaz.store.core.security.SafeImages
import com.yaz.store.data.catalog.AppStatus
import com.yaz.store.data.catalog.IconStore
import com.yaz.store.data.trust.OurKeys
import com.yaz.store.ui.icon.AppIcons
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

    private companion object {
        const val ICON_PX = 192
        val memory = LruCache<String, ImageBitmap>(48)
    }
}

/** The icon of [status], [size] wide, rounded; the grid icon until it loads or when it has none. */
@Composable
fun AppIcon(status: AppStatus, size: Dp, modifier: Modifier = Modifier) {
    val source = LocalIconSource.current
    val picture by produceState<ImageBitmap?>(null, status.entry.packageName, status.entry.tag, status.installed?.versionCode, source) {
        value = source.load(status)
    }
    val shape = RoundedCornerShape(size * 0.24f)
    val shown = picture
    if (shown != null) {
        Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(shape))
    } else {
        Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Gives [content] the icons of the phone. */
@Composable
fun WithIcons(source: IconSource, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIconSource provides source, content = content)
}
