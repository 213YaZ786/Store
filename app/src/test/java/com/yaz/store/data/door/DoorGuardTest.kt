package com.yaz.store.data.door

import com.yaz.store.R
import com.yaz.store.data.trust.OurKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// The guard of the door on the JVM, with a fake phone. The protocol is the one of the phone kit's Sisters tests and of Contacts' provider
// (a caller that is not ours gets nothing, ".debug" counts in a debug build only); the resource case keeps the manifest's knownCerts list
// equal to OurKeys, since the system compares the digests as upper case strings
// (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/SigningDetails.java).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DoorGuardTest {

    private val ours = OurKeys.DIGESTS.first()
    private val other = OurKeys.DIGESTS.last()
    private val stranger = "0".repeat(64)

    private fun guard(
        debug: Boolean,
        signers: Map<String, Set<String>> = emptyMap(),
        sameAsStore: Set<String> = emptySet()
    ) = DoorGuard({ signers[it] }, { it in sameAsStore }, debug)

    @Test
    fun `a release build accepts an app signed by one of our keys`() {
        val g = guard(false, mapOf("com.yaz.calculator" to setOf(ours), "org.mushaf.app" to setOf(other)))
        assertEquals(Caller("com.yaz.calculator", "com.yaz.calculator", false), g.check("com.yaz.calculator"))
        assertNotNull(g.check("org.mushaf.app"))
    }

    @Test
    fun `an unknown signer, no signer, and no package are refused`() {
        val g = guard(false, mapOf("evil.app" to setOf(stranger), "none.app" to emptySet()))
        assertNull(g.check("evil.app"))
        assertNull(g.check("none.app"))
        assertNull(g.check("not.installed"))
        assertNull(g.check(null))
        assertNull(g.check(""))
        assertNull(g.check("a".repeat(300)))
    }

    @Test
    fun `an app with a key of ours next to a key that is not ours is refused`() {
        assertNull(guard(false, mapOf("mixed.app" to setOf(ours, stranger))).check("mixed.app"))
    }

    @Test
    fun `a phone that fails to answer refuses`() {
        val g = DoorGuard({ throw IllegalStateException() }, { throw IllegalStateException() }, true)
        assertNull(g.check("com.yaz.calculator"))
    }

    @Test
    fun `the same signer as Store counts in a debug build only, with the debug suffix cut`() {
        val signers = mapOf("com.yaz.calculator.debug" to setOf(stranger))
        val same = setOf("com.yaz.calculator.debug")
        assertNull(guard(false, signers, same).check("com.yaz.calculator.debug"))
        val caller = guard(true, signers, same).check("com.yaz.calculator.debug")
        assertEquals(Caller("com.yaz.calculator.debug", "com.yaz.calculator", true), caller)
    }

    @Test
    fun `a debug build still refuses a stranger that does not share Store's signer`() {
        assertNull(guard(true, mapOf("evil.app" to setOf(stranger)), sameAsStore = setOf("other.app")).check("evil.app"))
    }

    @Test
    fun `a debug caller with one of our keys is taken as the release rule, no suffix cut`() {
        val g = guard(true, mapOf("com.yaz.calculator" to setOf(ours)))
        assertEquals(false, g.check("com.yaz.calculator")?.viaDebugRule)
    }

    @Test
    fun `the manifest's known certificates are exactly our pinned keys`() {
        val listed = RuntimeEnvironment.getApplication().resources.getStringArray(R.array.our_certs).toList()
        assertEquals(7, listed.size)
        assertTrue(listed.all { it == it.uppercase() && it.length == 64 })
        assertEquals(OurKeys.DIGESTS, listed.map { it.lowercase() }.toSet())
    }
}
