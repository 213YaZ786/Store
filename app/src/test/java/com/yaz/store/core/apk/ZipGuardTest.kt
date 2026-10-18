package com.yaz.store.core.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Ported from Amehrug's ZipGuardTest (https://github.com/213YaZ786/Amehrug,
// app/src/test/java/com/amehrug/app/backup/ZipGuardTest.kt), cut to the guard. Hostile samples built here, after the
// cases of Apache POI's ZipSecureFile tests (zip bomb, too many entries) and the Zip Slip write up
// (https://security.snyk.io/research/zip-slip-vulnerability): a name that leaves the folder, an absolute name, too
// many entries, an entry inflating past its cap, a ratio far above 100, an empty and a cut file.
class ZipGuardTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>, level: Int = 9): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            out.setLevel(level)
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun drain(data: ByteArray, limits: ZipLimits): Int {
        var count = 0
        GuardedZip(ByteArrayInputStream(data), limits).use { zip ->
            while (zip.nextEntry() != null) {
                zip.stream.copyTo(ByteArrayOutputStream())
                count += 1
            }
        }
        return count
    }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            fail("should have been refused")
        } catch (expected: ZipRefusedException) {
            // the app's own error: a bad file, no crash
        }
    }

    private val small = ZipLimits(maxEntries = 3, maxTotalBytes = 1000, maxEntryBytes = 600, maxRatio = 100, graceBytes = 1024)

    @Test
    fun `an honest zip is read whole`() {
        val data = zip("base.apk" to ByteArray(200) { it.toByte() }, "split_config.fr.apk" to ByteArray(300))
        assertEquals(2, drain(data, small))
    }

    @Test
    fun `an entry named with dots that leaves the folder is refused`() {
        assertRefused { ZipNames.resolve(folder.root, "../../x") }
        assertRefused { ZipNames.resolve(folder.root, "apks/../../x") }
        assertRefused { ZipNames.resolve(folder.root, "..\\x") }
        assertRefused { ZipNames.resolve(folder.root, "..") }
    }

    @Test
    fun `an absolute entry is refused`() {
        assertRefused { ZipNames.resolve(folder.root, "/etc/passwd") }
        assertRefused { ZipNames.resolve(folder.root, "\\windows\\x") }
    }

    @Test
    fun `an empty, a nul or a very long name is refused`() {
        assertRefused { ZipNames.resolve(folder.root, "") }
        assertRefused { ZipNames.resolve(folder.root, "a\u0000b") }
        assertRefused { ZipNames.resolve(folder.root, "a".repeat(5000)) }
    }

    @Test
    fun `names inside the folder pass and stay inside`() {
        val root = folder.root.canonicalPath
        for (name in listOf("base.apk", "splits/config.fr.apk", "Android/obb/org.example/main.1.org.example.obb", "a/./b", "dir/")) {
            assertTrue(ZipNames.resolve(folder.root, name).canonicalPath.startsWith(root + File.separator))
        }
    }

    @Test
    fun `a link planted in the folder cannot carry an entry out`() {
        val outside = folder.newFolder("outside")
        val inside = folder.newFolder("inside")
        val link = File(inside, "link")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Exception) {
            return // no symbolic links on this file system
        }
        assertRefused { ZipNames.resolve(inside, "link/x") }
    }

    @Test
    fun `too many entries are refused`() {
        val data = zip(*Array(5) { "f$it" to ByteArray(1) })
        assertRefused { drain(data, small) }
    }

    @Test
    fun `an entry inflating past its cap is refused whatever it declares`() {
        assertRefused { drain(zip("big" to ByteArray(5000)), small) }
    }

    @Test
    fun `entries that add up past the total are refused`() {
        val data = zip("a" to ByteArray(500) { it.toByte() }, "b" to ByteArray(500) { it.toByte() }, "c" to ByteArray(500))
        assertRefused { drain(data, small.copy(maxEntryBytes = 600, maxTotalBytes = 1200)) }
    }

    @Test
    fun `a zip bomb is stopped by the ratio before it fills the disk`() {
        // 64 MiB of zeros packs to about 64 KiB: a ratio near 1000. The bundle limits stop it a little past the grace size.
        val data = zip("bomb.apk" to ByteArray(64 * 1024 * 1024))
        assertTrue(data.size < 200_000)
        var written = 0L
        try {
            GuardedZip(ByteArrayInputStream(data), ZipLimits.BUNDLE).use { zip ->
                zip.nextEntry()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.stream.read(buffer)
                    if (read < 0) break
                    written += read
                }
            }
            fail("should have been refused")
        } catch (expected: ZipRefusedException) {
            assertTrue("stopped early: $written", written < 8L * 1024 * 1024)
        }
    }

    @Test
    fun `an entry skipped is counted too`() {
        // An .obb that is skipped rather than copied still goes through the caps.
        val data = zip("main.obb" to ByteArray(64 * 1024 * 1024))
        GuardedZip(ByteArrayInputStream(data), ZipLimits.BUNDLE).use { zip ->
            zip.nextEntry()
            try {
                zip.stream.skip(Long.MAX_VALUE)
                fail("should have been refused")
            } catch (expected: ZipRefusedException) {
                // stopped by the ratio
            }
        }
    }

    @Test
    fun `data already packed is not taken for a bomb`() {
        val random = java.util.Random(7)
        val noise = ByteArray(3 * 1024 * 1024).also { random.nextBytes(it) }
        assertEquals(1, drain(zip("base.apk" to noise), ZipLimits.BUNDLE))
    }

    @Test
    fun `an empty file holds no entry`() {
        GuardedZip(ByteArrayInputStream(ByteArray(0)), ZipLimits.BUNDLE).use { zip ->
            assertNull(zip.nextEntry())
        }
        assertEquals(0, drain(zip(), ZipLimits.BUNDLE))
    }

    @Test
    fun `a file that is not a zip holds no entry`() {
        GuardedZip(ByteArrayInputStream("hello, not a zip".toByteArray()), ZipLimits.BUNDLE).use { zip ->
            assertNull(zip.nextEntry())
        }
    }

    @Test
    fun `a cut zip fails with an io error, not a crash or a hang`() {
        val data = zip("base.apk" to ByteArray(4000) { (it * 31).toByte() })
        val cut = data.copyOf(data.size / 2)
        try {
            drain(cut, ZipLimits.BUNDLE)
            fail("should have failed")
        } catch (expected: IOException) {
            // EOFException or ZipException
        }
    }
}
