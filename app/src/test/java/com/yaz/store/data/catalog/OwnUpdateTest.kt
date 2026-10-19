package com.yaz.store.data.catalog

// JUnit 4 asserts (https://github.com/junit-team/junit4/wiki/Assertions) over OwnUpdate: which package is Store's, which release is
// offered, and when the dialog is due. The facts are the ones Installed.read gives (data/catalog/Installed.kt).
import com.yaz.store.data.install.InstallJob
import com.yaz.store.data.install.JobStep
import com.yaz.store.data.trust.OurKeys
import com.yaz.store.feature.home.OwnOffer
import com.yaz.store.feature.home.ownDialogShown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnUpdateTest {

    private fun entry(code: Long, packageName: String = "com.yaz.store", unread: Boolean = false, repo: String = "Store", apk: String? = "Store.apk", minSdk: Int = 31) =
        StoreEntry(repo, "v$code", packageName, "Store", "", code, "0.$code", minSdk, apk, null, unread = unread)

    private fun facts(code: Long, signers: Set<String> = setOf(OurKeys.DIGESTS.first())) = InstalledFacts(code, "0.$code", signers, null, null)

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `Store and its debug build are its own packages, an unread release of its repo too`() {
        assertTrue(OwnUpdate.isOwn(entry(1)))
        assertTrue(OwnUpdate.isOwn(entry(1, "com.yaz.store.debug")))
        assertTrue(OwnUpdate.isOwn(entry(0, "unread.store", unread = true)))
        assertFalse(OwnUpdate.isOwn(entry(1, "com.yaz.dialer", repo = "Dialer")))
        assertFalse(OwnUpdate.isOwn(entry(0, "unread.dialer", unread = true, repo = "Dialer")))
        assertTrue(OwnUpdate.isOwn("com.yaz.store.debug"))
        assertFalse(OwnUpdate.isOwn("com.yaz.storefront"))
    }

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `a newer release is offered, an equal or older one is not`() {
        assertEquals(entry(3), OwnUpdate.pending(entry(3), facts(2), 36, "com.yaz.store", debug = false))
        assertNull(OwnUpdate.pending(entry(2), facts(2), 36, "com.yaz.store", debug = false))
        assertNull(OwnUpdate.pending(entry(1), facts(2), 36, "com.yaz.store", debug = false))
        assertNull(OwnUpdate.pending(null, facts(2), 36, "com.yaz.store", debug = false))
        assertNull(OwnUpdate.pending(entry(3), null, 36, "com.yaz.store", debug = false))
    }

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `a Store signed by someone else, a phone too old or a release with no APK is never offered`() {
        assertNull(OwnUpdate.pending(entry(3), facts(2, setOf("00")), 36, "com.yaz.store", debug = false))
        assertNull(OwnUpdate.pending(entry(3, minSdk = 40), facts(2), 36, "com.yaz.store", debug = false))
        assertNull(OwnUpdate.pending(entry(3, apk = null), facts(2), 36, "com.yaz.store", debug = false))
    }

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `a debug build stands in as signed by ours`() {
        assertNull(OwnUpdate.pending(entry(3), facts(2, setOf("00")), 36, "com.yaz.store.debug", debug = false))
        assertEquals(entry(3), OwnUpdate.pending(entry(3), facts(2, setOf("00")), 36, "com.yaz.store.debug", debug = true))
    }

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `the newest read release wins, an unread one never`() {
        val newest = OwnUpdate.newest(listOf(entry(3), entry(5, "com.yaz.store.debug"), entry(0, "unread.store", unread = true)))
        assertEquals("com.yaz.store.debug", newest?.packageName)
        assertEquals(5L, newest?.versionCode)
        assertNull(OwnUpdate.newest(listOf(entry(0, "unread.store", unread = true))))
        assertNull(OwnUpdate.newest(emptyList()))
    }

    // https://github.com/junit-team/junit4/wiki/Assertions
    @Test
    fun `the dialog is due only with an offer, hidden by Later unless a download runs`() {
        val offer = OwnOffer(entry(3), "0.2")
        fun job(step: JobStep) = InstallJob(entry(3), handOver = false, serial = 1, step = step)
        assertFalse(ownDialogShown(null, later = false, job = null))
        assertTrue(ownDialogShown(offer, later = false, job = null))
        assertFalse(ownDialogShown(offer, later = true, job = null))
        assertTrue(ownDialogShown(offer, later = true, job = job(JobStep.DOWNLOADING)))
        assertFalse(ownDialogShown(offer, later = true, job = job(JobStep.FAILED)))
    }
}
