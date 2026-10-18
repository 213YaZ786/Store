package com.yaz.store.core.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Cases of the rules App Manager's SplitApkChooser applies (GPL-3.0-or-later, apk/splitapk/SplitApkChooser.java):
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/splitapk/SplitApkChooser.java
// and the split kinds of an Android App Bundle's APK set, as bundletool names and targets them:
// https://developer.android.com/studio/command-line/bundletool#generate_apks
// (config.<abi>, config.<density>, config.<language>, feature splits with their config splits).
class SplitChooserTest {

    private val device = DeviceProfile(abis = listOf("arm64-v8a", "armeabi-v7a"), density = 420, languages = listOf("fr", "en"))

    private fun info(
        split: String?,
        feature: Boolean = false,
        forSplit: String? = null,
        required: Boolean = false
    ) = ApkInfo(
        packageName = "org.example.app", versionCode = 1, versionName = "1", splitName = split,
        isFeatureSplit = feature, configForSplit = forSplit, isSplitRequired = required, minSdk = 21,
        permissions = emptyList(), abis = emptyList(), verified = true, signers = listOf("a".repeat(64)), label = null
    )

    private fun entry(split: String?, feature: Boolean = false, forSplit: String? = null, required: Boolean = false) =
        SplitEntry.of(split ?: "base.apk", 1000, info(split, feature, forSplit, required), device)

    private fun names(chooser: SplitChooser) = chooser.chosen().map { it.name }.toSet()

    private val typical = listOf(
        entry(null),
        entry("config.armeabi_v7a"), entry("config.arm64_v8a"), entry("config.x86_64"),
        entry("config.mdpi"), entry("config.xhdpi"), entry("config.xxhdpi"),
        entry("config.de"), entry("config.en"), entry("config.fr")
    )

    @Test
    fun `the entry types come from the split names`() {
        assertEquals(SplitType.BASE, entry(null).type)
        assertEquals(SplitType.ABI, entry("config.arm64_v8a").type)
        assertEquals(SplitType.DENSITY, entry("config.xxhdpi").type)
        assertEquals(SplitType.LOCALE, entry("config.fr").type)
        assertEquals(SplitType.FEATURE, entry("dynamic", feature = true).type)
        assertEquals(SplitType.UNKNOWN, entry("something").type)
        assertEquals(SplitType.UNKNOWN, entry("config.zz_not_a_thing").type)
    }

    @Test
    fun `ABIs are ranked by the order of the phone's ABIs and one it cannot run is not supported`() {
        val arm64 = entry("config.arm64_v8a")
        val arm32 = entry("config.armeabi_v7a")
        val x86 = entry("config.x86_64")
        assertTrue(arm64.rank < arm32.rank)
        assertTrue(arm64.supported && arm32.supported)
        assertFalse(x86.supported)
        assertEquals(Int.MAX_VALUE, x86.rank)
    }

    @Test
    fun `densities are ranked by their distance to the phone's`() {
        val xxhdpi = entry("config.xxhdpi") // 480, 60 away from 420
        val xhdpi = entry("config.xhdpi") // 320, 100 away
        val mdpi = entry("config.mdpi")
        assertTrue(xxhdpi.rank < xhdpi.rank)
        assertTrue(xhdpi.rank < mdpi.rank)
    }

    @Test
    fun `a split of a feature ranks after those of the base app`() {
        val ofBase = entry("config.xxhdpi")
        val ofFeature = entry("dynamic.config.xxhdpi", forSplit = "dynamic")
        assertTrue(ofBase.rank < ofFeature.rank)
        assertEquals("dynamic", ofFeature.feature)
        assertTrue(ofFeature.isForFeature)
    }

    @Test
    fun `languages are ranked by the order of the phone's languages`() {
        assertTrue(entry("config.fr").rank < entry("config.en").rank)
        assertEquals(Int.MAX_VALUE, entry("config.de").rank)
    }

    @Test
    fun `entries sort by type then rank`() {
        val chooser = SplitChooser(typical.reversed())
        val order = chooser.entries.map { it.name }
        assertEquals("base", order.first())
        assertEquals(listOf("config.arm64_v8a", "config.armeabi_v7a", "config.x86_64"), order.subList(1, 4))
        assertEquals(listOf("config.xxhdpi", "config.xhdpi", "config.mdpi"), order.subList(4, 7))
        assertEquals(listOf("config.fr", "config.en"), order.subList(7, 9))
    }

    @Test
    fun `the first selection is the base and the best split of each type`() {
        val chooser = SplitChooser(typical)
        chooser.initialSelections()
        assertEquals(setOf("base", "config.arm64_v8a", "config.xxhdpi", "config.fr"), names(chooser))
    }

    @Test
    fun `a required split is selected whatever its type`() {
        val chooser = SplitChooser(typical + entry("config.de", required = true).copy(id = "required-de"))
        chooser.initialSelections()
        assertTrue("config.de" in names(chooser))
        assertTrue(chooser.entries.first { it.id == "required-de" }.locked)
    }

    @Test
    fun `a set with no supported ABI selects no ABI split`() {
        val chooser = SplitChooser(listOf(entry(null), entry("config.x86"), entry("config.x86_64"), entry("config.xxhdpi")))
        chooser.initialSelections()
        assertEquals(setOf("base", "config.xxhdpi"), names(chooser))
        val x86 = chooser.entries.first { it.name == "config.x86" }
        assertTrue(x86.locked)
        assertFalse(chooser.toggle(x86.id))
        assertFalse(x86.id in chooser.selected)
    }

    @Test
    fun `a feature split is offered, not selected, and its dependencies stay out`() {
        val feature = entry("dynamic", feature = true)
        val chooser = SplitChooser(typical + feature + entry("dynamic.config.arm64_v8a", forSplit = "dynamic") + entry("dynamic.config.xxhdpi", forSplit = "dynamic"))
        chooser.initialSelections()
        assertFalse("dynamic" in names(chooser))
        assertFalse("dynamic.config.arm64_v8a" in names(chooser))
        assertFalse("dynamic.config.xxhdpi" in names(chooser))
    }

    @Test
    fun `selecting a feature selects the best split of each type for it`() {
        val chooser = SplitChooser(
            typical + entry("dynamic", feature = true) +
                entry("dynamic.config.armeabi_v7a", forSplit = "dynamic") + entry("dynamic.config.arm64_v8a", forSplit = "dynamic") +
                entry("dynamic.config.xhdpi", forSplit = "dynamic") + entry("dynamic.config.xxhdpi", forSplit = "dynamic")
        )
        chooser.initialSelections()
        assertTrue(chooser.toggle("dynamic"))
        assertTrue(names(chooser).containsAll(listOf("dynamic", "dynamic.config.arm64_v8a", "dynamic.config.xxhdpi")))
        assertFalse("dynamic.config.armeabi_v7a" in names(chooser))
        assertFalse("dynamic.config.xhdpi" in names(chooser))
    }

    @Test
    fun `deselecting a feature takes its splits with it`() {
        val chooser = SplitChooser(
            typical + entry("dynamic", feature = true) + entry("dynamic.config.arm64_v8a", forSplit = "dynamic")
        )
        chooser.initialSelections()
        chooser.toggle("dynamic")
        assertTrue("dynamic.config.arm64_v8a" in names(chooser))
        assertTrue(chooser.toggle("dynamic"))
        assertFalse("dynamic" in names(chooser))
        assertFalse("dynamic.config.arm64_v8a" in names(chooser))
        // The base app's own splits stay.
        assertTrue("config.arm64_v8a" in names(chooser))
    }

    @Test
    fun `the last split of a type cannot be deselected but another can`() {
        val chooser = SplitChooser(typical)
        chooser.initialSelections()
        assertNull(chooser.deselect("config.xxhdpi"))
        assertTrue("config.xxhdpi" in names(chooser))
        // A second density makes the first free to go.
        assertTrue(chooser.toggle("config.xhdpi"))
        assertNotNull(chooser.deselect("config.xxhdpi"))
        assertFalse("config.xxhdpi" in names(chooser))
        assertNull(chooser.deselect("config.xhdpi"))
    }

    @Test
    fun `a required split cannot be deselected`() {
        val chooser = SplitChooser(typical + entry("config.de", required = true).copy(id = "required-de"))
        chooser.initialSelections()
        assertNull(chooser.deselect("required-de"))
        assertFalse(chooser.toggle("required-de"))
        assertTrue("config.de" in names(chooser))
        assertNull(chooser.deselect("base.apk"))
    }

    @Test
    fun `the splits of the installed app stay chosen`() {
        val chooser = SplitChooser(typical)
        chooser.initialSelections(setOf("config.de", "config.xhdpi", "config.arm64_v8a"))
        // Those of the installed app, and nothing more of their types.
        assertEquals(setOf("base", "config.de", "config.xhdpi", "config.arm64_v8a"), names(chooser))
    }

    @Test
    fun `selecting again is harmless and an unknown id does nothing`() {
        val chooser = SplitChooser(typical)
        chooser.initialSelections()
        assertTrue(chooser.select("config.fr").isEmpty())
        assertTrue(chooser.select("nothing").isEmpty())
        assertNull(chooser.deselect("nothing"))
        assertFalse(chooser.toggle("nothing"))
        assertEquals(setOf("base", "config.arm64_v8a", "config.xxhdpi", "config.fr"), names(chooser))
    }

    @Test
    fun `an empty bundle chooses nothing`() {
        val chooser = SplitChooser(emptyList())
        assertTrue(chooser.initialSelections().isEmpty())
        assertTrue(chooser.chosen().isEmpty())
    }
}
