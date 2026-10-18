package com.yaz.store.data.catalog

// JUnit 4 (https://github.com/junit-team/junit4/wiki/Assertions) over fake package facts: the states of Installed.stateOf, which follows
// GrapheneOS' App Store (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallTask.kt, findPackage).
import com.yaz.store.data.trust.OurKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstalledStateTest {

    private val store = "com.yaz.store"
    private val ours = OurKeys.DIGESTS.first()
    private val another = OurKeys.DIGESTS.last()

    private fun entry(code: Long = 10, minSdk: Int = 31) = StoreEntry(
        repo = "App", packageName = "com.yaz.app", label = "App", description = "", versionCode = code, versionName = "1.0",
        minSdk = minSdk, apkName = "App-1.0.apk", apkUrl = "u", apkSize = 1, sumsUrl = "s", iconUrl = null, notes = "",
        publishedAt = null, pageUrl = "p"
    )

    private fun facts(
        code: Long = 10,
        signers: Set<String> = setOf(ours),
        installer: String? = store,
        owner: String? = null
    ) = InstalledFacts(code, "1.0", signers, installer, owner)

    private fun state(entry: StoreEntry, facts: InstalledFacts?, sdk: Int = 36) = Installed.stateOf(entry, facts, sdk, store)

    @Test
    fun `not installed`() {
        assertEquals(AppState.NOT_INSTALLED, state(entry(), null))
    }

    @Test
    fun `up to date when the versions are alike and Store holds the app`() {
        assertEquals(AppState.UP_TO_DATE, state(entry(10), facts(10)))
        assertEquals(AppState.UP_TO_DATE, state(entry(10), facts(11)))
    }

    @Test
    fun `an update when the release is above the installed version`() {
        assertEquals(AppState.UPDATE, state(entry(11), facts(10)))
        assertEquals(AppState.UPDATE, state(entry(Long.MAX_VALUE), facts(0)))
        assertEquals(AppState.UPDATE, state(entry(11), facts(10, installer = "com.android.vending")))
    }

    @Test
    fun `hand over when ours but Store is neither installer nor update owner`() {
        assertEquals(AppState.HAND_OVER, state(entry(10), facts(10, installer = null)))
        assertEquals(AppState.HAND_OVER, state(entry(10), facts(10, installer = "com.android.shell")))
        assertEquals(AppState.HAND_OVER, state(entry(10), facts(10, installer = "com.google.android.packageinstaller", owner = "com.other")))
    }

    @Test
    fun `the update owner counts as holding the app`() {
        assertEquals(AppState.UP_TO_DATE, state(entry(10), facts(10, installer = "com.android.shell", owner = store)))
    }

    @Test
    fun `foreign when signed by none of our keys, whatever the versions`() {
        val foreign = setOf("00".repeat(32))
        assertEquals(AppState.FOREIGN, state(entry(10), facts(10, signers = foreign)))
        assertEquals(AppState.FOREIGN, state(entry(99), facts(10, signers = foreign)))
        assertEquals(AppState.FOREIGN, state(entry(10), facts(10, signers = emptySet())))
        // One of ours beside one that is not: not ours.
        assertEquals(AppState.FOREIGN, state(entry(10), facts(10, signers = setOf(ours, "00".repeat(32)))))
    }

    @Test
    fun `every pinned key counts, in any case`() {
        for (digest in OurKeys.DIGESTS) {
            assertEquals(AppState.UP_TO_DATE, state(entry(10), facts(10, signers = setOf(digest))))
            assertEquals(AppState.UP_TO_DATE, state(entry(10), facts(10, signers = setOf(digest.uppercase()))))
        }
        assertEquals(AppState.UP_TO_DATE, state(entry(10), facts(10, signers = setOf(ours, another))))
    }

    @Test
    fun `too new when the release needs a newer Android than the phone's`() {
        assertEquals(AppState.TOO_NEW, state(entry(minSdk = 37), null, sdk = 36))
        assertEquals(AppState.NOT_INSTALLED, state(entry(minSdk = 36), null, sdk = 36))
        assertEquals(AppState.TOO_NEW, state(entry(11, minSdk = 37), facts(10), sdk = 36))
        // Installed and current: the release's level no longer matters.
        assertEquals(AppState.UP_TO_DATE, state(entry(10, minSdk = 37), facts(10), sdk = 36))
    }

    @Test
    fun `a foreign app is left alone even when the release is too new`() {
        assertEquals(AppState.FOREIGN, state(entry(11, minSdk = 37), facts(10, signers = setOf("00".repeat(32))), sdk = 36))
    }

    @Test
    fun `the pinned digests are SHA-256 in lower case hex`() {
        assertEquals(7, OurKeys.DIGESTS.size)
        for (digest in OurKeys.DIGESTS) {
            assertEquals(64, digest.length)
            assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
        }
        assertFalse(OurKeys.allOurs(emptySet()))
    }
}
