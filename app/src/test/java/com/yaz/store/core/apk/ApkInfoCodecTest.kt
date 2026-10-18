package com.yaz.store.core.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Hostile answers from the isolated process, as the shared safemedia kit's tests treat a decoder answer (a pipe of plain
// values read with caps, Modules/shared/safemedia) and the OWASP MASVS CODE-4 rule that data from outside is checked
// before use (https://mas.owasp.org/MASVS/controls/MASVS-CODE-4/): cut short, too large, a count past its cap, names
// that are not names, a label dressed with control and bidirectional characters.
class ApkInfoCodecTest {

    private val sample = ApkInfo(
        packageName = "org.example.app", versionCode = 4_294_967_297L, versionName = "1.0", splitName = null,
        isFeatureSplit = false, configForSplit = null, isSplitRequired = false, minSdk = 21,
        permissions = listOf("android.permission.CAMERA", "com.example.permission.X"), abis = listOf("arm64-v8a"),
        verified = true, signers = listOf("ab".repeat(32)), label = "Example"
    )

    private fun bytes(info: ApkInfo) = ByteArrayOutputStream().also { ApkInfoCodec.write(info, it) }.toByteArray()

    private fun read(data: ByteArray) = ApkInfoCodec.read(ByteArrayInputStream(data))

    private fun assertRefused(data: ByteArray) {
        try {
            read(data)
            fail("should have been refused")
        } catch (expected: InspectRefused) {
            // the app's own error
        }
    }

    /** A valid answer up to the number of permissions, whatever follows is the test's. */
    private fun hostile(then: DataOutputStream.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x59415A31); writeUTF("org.example.app"); writeLong(1); writeUTF("1"); writeUTF("")
            writeBoolean(false); writeBoolean(false); writeUTF(""); writeBoolean(false); writeInt(21)
            then()
        }
        return out.toByteArray()
    }

    @Test
    fun `an answer goes through unchanged`() {
        assertEquals(sample, read(bytes(sample)))
        val split = sample.copy(splitName = "config.fr", isFeatureSplit = false, configForSplit = "dynamic", isSplitRequired = true, label = null)
        assertEquals(split, read(bytes(split)))
    }

    @Test
    fun `an answer cut anywhere is refused`() {
        val whole = bytes(sample)
        for (cut in listOf(0, 1, 3, 4, 10, whole.size / 2, whole.size - 1)) {
            assertRefused(whole.copyOf(cut))
        }
    }

    @Test
    fun `something that is not an answer is refused`() {
        assertRefused(ByteArray(100) { 7 })
        assertRefused("hello".toByteArray())
    }

    @Test
    fun `a package name that is not a package name is refused`() {
        for (bad in listOf("", "single", "has space.x", "../../etc", "1abc.def", "a..b", "a.b\u0000", "x".repeat(300) + ".y")) {
            assertRefused(bytes(sample.copy(packageName = bad)))
        }
    }

    @Test
    fun `split names, abis and signers that are not what they claim are refused`() {
        assertRefused(bytes(sample.copy(splitName = "config fr")))
        assertRefused(bytes(sample.copy(configForSplit = "a/b")))
        assertRefused(bytes(sample.copy(abis = listOf("arm64 v8a"))))
        assertRefused(bytes(sample.copy(signers = listOf("ZZ".repeat(32)))))
        assertRefused(bytes(sample.copy(signers = listOf("ab".repeat(31)))))
        assertRefused(bytes(sample.copy(minSdk = -1)))
        assertRefused(bytes(sample.copy(minSdk = 2_000_000)))
    }

    @Test
    fun `a count past its cap is refused whatever follows`() {
        assertRefused(hostile { writeInt(Int.MAX_VALUE) })
        assertRefused(hostile { writeInt(-5) })
        assertRefused(hostile { writeInt(0); writeInt(Int.MAX_VALUE) }) // abis
        assertRefused(hostile { writeInt(0); writeInt(0); writeBoolean(true); writeInt(1_000_000) }) // signers
    }

    @Test
    fun `an answer past the size cap is refused`() {
        val many = sample.copy(permissions = List(1000) { "com.example.permission.P$it" + "x".repeat(200) })
        val big = bytes(many)
        // 1000 permissions of 200 characters is about 230 KB: under the cap, and what follows is not read.
        assertEquals(many, read(big))
        assertEquals(many, read(big + ByteArray(ApkInfoCodec.MAX_WIRE)))
        // A valid start, then 1000 permissions of 60 000 characters: 60 MB that must not be read.
        assertRefused(hostile {
            writeInt(1000)
            repeat(1000) { writeUTF("a".repeat(60_000)) }
        })
    }

    @Test
    fun `odd permission names are left out and the rest stays`() {
        val read = read(bytes(sample.copy(permissions = listOf("android.permission.CAMERA", "has space", "bad\nname", "android.permission.CAMERA"))))
        assertEquals(listOf("android.permission.CAMERA"), read.permissions)
    }

    @Test
    fun `a label is cleaned and cut`() {
        val rlo = String(Character.toChars(0x202E))
        val dressed = "Ex" + rlo + "ample\u0000\n" + "x".repeat(300)
        val label = read(bytes(sample.copy(label = dressed))).label
        assertTrue(label!!.length <= ApkInfoCodec.MAX_LABEL)
        assertTrue(label.startsWith("Example"))
        assertTrue(label.none { Character.isISOControl(it) || it == rlo[0] })
        assertNull(read(bytes(sample.copy(label = rlo + "\u0007"))).label)
    }

    @Test
    fun `the text of a version is cleaned the same way`() {
        val rlm = String(Character.toChars(0x200F))
        assertEquals("1.0", ApkInfoCodec.clean("1." + rlm + "0\u0001", 100))
        assertEquals("abc", ApkInfoCodec.clean("  abcdef ", 3).trim())
        assertEquals("", ApkInfoCodec.clean("", 5))
    }

    @Test
    fun `a signer is named by the hex of its sha-256`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ApkInfoCodec.sha256Hex(ByteArray(0)))
    }
}
