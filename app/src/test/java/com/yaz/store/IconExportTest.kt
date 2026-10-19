package com.yaz.store

// Writes app/build/outputs/icon/icon.png: the app's own launcher icon at 512 x 512, drawn as a launcher does, for the store listing
// (the shared CI copies it into each release as icon.png when the repo keeps no fastlane images/icon.png).
//
// The icon is the application's icon resource, loaded as ApplicationInfo.loadIcon does on a phone; an AdaptiveIconDrawable draws its
// background and foreground layers through the system mask and is cut to a circle, transparent outside
// (https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable). Drawn on the JVM by Robolectric's
// NATIVE graphics, as Roborazzi does for screens (https://github.com/takahirom/roborazzi, README "Robolectric native graphics").
// No library beyond Robolectric, already in the test setup.
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A plain Application: the app's own may start things a second test would start again. xxxhdpi: the icon's bitmaps are taken at their
// largest density (Robolectric's qualifiers, https://robolectric.org/device-configuration/), or a 512 px picture is a 108 px one enlarged.
@Config(application = android.app.Application::class, sdk = [36], qualifiers = "xxxhdpi")
class IconExportTest {

    @Test
    fun `writes the launcher icon as a 512 px circle`() {
        val context = RuntimeEnvironment.getApplication()
        // The resource ApplicationInfo.icon names (android:icon of the merged manifest), which is what getApplicationIcon loads on a phone;
        // Robolectric's PackageManager shadow answers the system's default robot there instead.
        val iconId = context.applicationInfo.icon
        assertTrue("the manifest names no android:icon", iconId != 0)
        val icon = context.getDrawable(iconId)!!
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // The launcher's circle: the system mask keeps the layers inside, this clip makes the edge round.
        val circle = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        canvas.clipPath(circle)
        icon.setBounds(0, 0, size, size)
        icon.draw(canvas)

        // Gradle runs unit tests from the module folder (app/).
        val file = File("build/outputs/icon/icon.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        // A corner lies outside the circle, the centre inside it.
        assertTrue(Color.alpha(bitmap.getPixel(2, 2)) == 0)
        assertTrue(Color.alpha(bitmap.getPixel(size / 2, size / 2)) > 0)
    }
}
