package com.yaz.store.core.install

import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.core.apk.DeviceProfile
import com.yaz.store.core.apk.SplitEntry
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// The groups App Manager sorts a bundle's entries by (GPL-3.0-or-later, ApkFile.Entry types:
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java)
// and the short form of a certificate's SHA-256 as apksigner prints the digest (colon separated hex pairs,
// https://developer.android.com/tools/apksigner).
class InstallPlanTest {

    private val device = DeviceProfile(listOf("arm64-v8a"), 420, listOf("en"))
    private var locale: Locale = Locale.getDefault()

    @Before
    fun english() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restore() {
        Locale.setDefault(locale)
    }

    private fun entry(split: String?, feature: Boolean = false, forSplit: String? = null) = SplitEntry.of(
        split ?: "base.apk", 10,
        ApkInfo("a.b.c", 1, "1", split, feature, forSplit, false, 21, emptyList(), emptyList(), true, listOf("a".repeat(64)), null),
        device
    )

    @Test
    fun `the splits are grouped, the base is apart and empty groups are left out`() {
        val groups = InstallPlan.groups(
            listOf(entry(null), entry("config.fr"), entry("config.arm64_v8a"), entry("config.xxhdpi"), entry("dyn", feature = true), entry("odd"))
        )
        assertEquals(
            listOf(SplitGroup.PROCESSOR, SplitGroup.DENSITY, SplitGroup.LANGUAGES, SplitGroup.FEATURES, SplitGroup.OTHER),
            groups.map { it.first }
        )
        assertEquals(listOf("config.arm64_v8a"), groups[0].second.map { it.name })
        assertEquals(emptyList<Any>(), InstallPlan.groups(listOf(entry(null))))
        assertEquals(emptyList<Any>(), InstallPlan.groups(emptyList()))
    }

    @Test
    fun `a row is named for people`() {
        assertEquals("arm64-v8a", InstallPlan.title(entry("config.arm64_v8a")))
        assertEquals("xxhdpi (480 dpi)", InstallPlan.title(entry("config.xxhdpi")))
        assertEquals("French", InstallPlan.title(entry("config.fr")))
        assertEquals("dyn", InstallPlan.title(entry("dyn", feature = true)))
        assertEquals("arm64-v8a, dyn", InstallPlan.title(entry("dyn.config.arm64_v8a", forSplit = "dyn")))
        assertEquals("odd", InstallPlan.title(entry("odd")))
    }

    @Test
    fun `a signer is shown by the start of its sha-256`() {
        assertEquals("DC:44:CA:63:EF:A2:98:03", InstallPlan.shortSigner("dc44ca63efa2980355d9bc4e90897f6b66e0c26ee30b0190c89a6f6b2b13cc09"))
        assertEquals("AB", InstallPlan.shortSigner("ab"))
        assertEquals("", InstallPlan.shortSigner(""))
    }
}
