package com.yaz.store.core.install

import com.yaz.store.core.apk.ApkBundle
import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.core.apk.ApkInspect
import com.yaz.store.core.apk.DeviceProfile
import com.yaz.store.core.apk.SplitChooser
import com.yaz.store.core.apk.SplitEntry
import com.yaz.store.core.apk.SplitType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// A whole .apks, from the zip to the choice of splits, with real split APKs: a base, a language split, a feature split,
// a split of that feature for arm64 and a required density split. They were built the way Android's own tools build
// them, `aapt2 link --manifest` with split, configForSplit, android:isFeatureSplit and android:isSplitRequired in the
// manifest (https://developer.android.com/tools/aapt2#link), then signed with apksigner
// (https://developer.android.com/tools/apksigner) by one throwaway key made for these tests. The flow is App Manager's
// ApkFile then SplitApkChooser (GPL-3.0-or-later):
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java
class SplitSetFlowTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val phone = DeviceProfile(abis = listOf("arm64-v8a", "armeabi-v7a"), density = 420, languages = listOf("fr", "en"))
    private val signer = "95b160731604b10a1acfe87767bc1fb19827719aa2e9bfd87cc17350353ea286"
    private val names = listOf("splits_base", "splits_fr", "splits_dynamic", "splits_dynamic_arm64", "splits_required_xxhdpi")

    private fun fixture(name: String): File = File(checkNotNull(javaClass.classLoader?.getResource("apk/$name.apk")).toURI())

    private fun inspect(file: File): ApkInfo = FileInputStream(file).use { ApkInspect.inspect(it.channel) }

    private fun bundle(vararg files: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for (name in files) {
                zip.putNextEntry(ZipEntry("$name.apk"))
                zip.write(fixture(name).readBytes())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `the manifests of splits are read`() {
        val base = inspect(fixture("splits_base"))
        assertEquals("org.example.splits", base.packageName)
        assertEquals(3L, base.versionCode)
        assertEquals("2.1", base.versionName)
        assertNull(base.splitName)
        assertEquals(24, base.minSdk)
        assertEquals(listOf("android.permission.CAMERA", "android.permission.INTERNET"), base.permissions)
        assertEquals("Splits Example", base.label)
        assertTrue(base.verified)
        assertEquals(listOf(signer), base.signers)

        val fr = inspect(fixture("splits_fr"))
        assertEquals("config.fr", fr.splitName)
        assertFalse(fr.isFeatureSplit)
        assertFalse(fr.isSplitRequired)
        assertNull(fr.configForSplit)

        val feature = inspect(fixture("splits_dynamic"))
        assertEquals("dynamic", feature.splitName)
        assertTrue(feature.isFeatureSplit)

        val forFeature = inspect(fixture("splits_dynamic_arm64"))
        assertEquals("dynamic.config.arm64_v8a", forFeature.splitName)
        assertEquals("dynamic", forFeature.configForSplit)

        val needed = inspect(fixture("splits_required_xxhdpi"))
        assertTrue(needed.isSplitRequired)
        assertEquals(listOf(signer), needed.signers)
    }

    @Test
    fun `an apks goes from the zip to the splits chosen`() {
        val opened = ApkBundle.copy(ByteArrayInputStream(bundle(*names.toTypedArray())), "apks", -1, temp.newFolder())
        assertEquals(5, opened.apks.size)
        val infos = opened.apks.map { inspect(it.file) }

        val verdict = InstallChecks.verdict(infos, phone, sdkInt = 34, installed = null, obbCount = 0)
        assertEquals(InstallVerdict.Allowed(emptyList()), verdict)

        val entries = opened.apks.mapIndexed { i, apk -> SplitEntry.of(apk.file.name, apk.size, infos[i], phone) }
        assertEquals(setOf(SplitType.BASE, SplitType.LOCALE, SplitType.FEATURE, SplitType.ABI, SplitType.DENSITY), entries.map { it.type }.toSet())

        val chooser = SplitChooser(entries)
        chooser.initialSelections()
        // The base, the language, the required density; the feature and what depends on it wait to be asked for.
        assertEquals(setOf("base", "config.fr", "config.xxhdpi"), chooser.chosen().map { it.name }.toSet())

        val feature = entries.first { it.type == SplitType.FEATURE }
        assertTrue(chooser.toggle(feature.id))
        assertEquals(
            setOf("base", "config.fr", "config.xxhdpi", "dynamic", "dynamic.config.arm64_v8a"),
            chooser.chosen().map { it.name }.toSet()
        )
        assertTrue(chooser.toggle(feature.id))
        assertEquals(setOf("base", "config.fr", "config.xxhdpi"), chooser.chosen().map { it.name }.toSet())
    }

    @Test
    fun `a split of another app in the bundle is refused`() {
        val infos = listOf(inspect(fixture("splits_base")), inspect(fixture("editor")).copy(splitName = "config.fr"))
        val verdict = InstallChecks.verdict(infos, phone, 34, null, 0)
        assertTrue(verdict is InstallVerdict.Refused)
    }

    @Test
    fun `a bundle signed by two keys is refused`() {
        // The base of these splits is signed by the throwaway key, the editor APK by F-Droid's.
        val editor = inspect(fixture("editor")).copy(packageName = "org.example.splits", splitName = "config.fr")
        val verdict = InstallChecks.verdict(listOf(inspect(fixture("splits_base")), editor), phone, 34, null, 0)
        assertTrue(verdict is InstallVerdict.Refused && verdict.reason.contains("different keys"))
    }

    @Test
    fun `a phone with an older Android refuses the whole set`() {
        val infos = names.map { inspect(fixture(it)) }
        val verdict = InstallChecks.verdict(infos, phone, sdkInt = 23, installed = null, obbCount = 0)
        assertTrue(verdict is InstallVerdict.Refused && verdict.reason.contains("API level 24"))
    }
}
