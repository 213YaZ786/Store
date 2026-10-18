package com.yaz.store.core.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Bundles built here, after the cases of the ZipGuard tests (Apache POI's ZipSecureFile tests, the Zip Slip write up,
// https://security.snyk.io/research/zip-slip-vulnerability) and of App Manager's ApkFile handling of .apks/.xapk/.apkm
// (GPL-3.0-or-later, https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java):
// two APKs and an .obb, a bundle with no APK, an encrypted .apkm that is not a zip, a name that leaves the folder, a bomb.
class ApkBundleTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content)
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun copy(data: ByteArray, extension: String, declared: Long = -1, limits: ZipLimits = ZipLimits.BUNDLE): BundleFiles =
        ApkBundle.copy(ByteArrayInputStream(data), extension, declared, temp.newFolder(), limits)

    private fun assertRefused(contains: String, block: () -> Unit) {
        try {
            block()
            fail("should have been refused")
        } catch (e: BundleRefused) {
            assertTrue("'${e.message}' should say '$contains'", e.message.orEmpty().contains(contains))
        }
    }

    @Test
    fun `an apk is copied as it is`() {
        val data = ByteArray(5000) { (it * 7).toByte() }
        val opened = copy(data, "apk")
        assertEquals(1, opened.apks.size)
        assertArrayEquals(data, opened.apks[0].file.readBytes())
        assertEquals(5000L, opened.apks[0].size)
        assertTrue(opened.obbNames.isEmpty())
    }

    @Test
    fun `an empty apk is refused`() {
        assertRefused("empty") { copy(ByteArray(0), "apk") }
    }

    @Test
    fun `a bundle gives its apks and sets the obb apart`() {
        val data = zip(
            "base.apk" to ByteArray(300) { 1 },
            "config.arm64_v8a.apk" to ByteArray(200) { 2 },
            "manifest.json" to "{}".toByteArray(),
            "Android/obb/org.example.app/main.5.org.example.app.obb" to ByteArray(1000) { 3 },
            "icon.png" to ByteArray(10)
        )
        val opened = copy(data, "xapk")
        assertEquals(2, opened.apks.size)
        assertEquals(listOf(300L, 200L), opened.apks.map { it.size })
        assertEquals(listOf("main.5.org.example.app.obb"), opened.obbNames)
        assertTrue(opened.apks.all { it.file.parentFile == opened.folder })
    }

    @Test
    fun `a bundle with no apk is refused`() {
        assertRefused("no APK") { copy(zip("manifest.json" to "{}".toByteArray()), "apks") }
        assertRefused("no APK") { copy(zip(), "apks") }
    }

    @Test
    fun `a name that leaves the folder refuses the whole bundle`() {
        val folder = temp.newFolder()
        val data = zip("base.apk" to ByteArray(10) { 1 }, "../../evil.apk" to ByteArray(10) { 2 })
        assertRefused("not safe") { ApkBundle.copy(ByteArrayInputStream(data), "apks", -1, folder) }
        assertFalse(File(folder.parentFile, "evil.apk").exists())
        assertRefused("not safe") { copy(zip("/abs/base.apk" to ByteArray(10) { 1 }), "apks") }
        assertRefused("not safe") { copy(zip("..\\evil.apk" to ByteArray(10) { 1 }), "apks") }
    }

    @Test
    fun `an encrypted apkm is refused with its own message`() {
        assertRefused("encrypted") { copy(ByteArray(500) { (it * 13).toByte() }, "apkm") }
    }

    @Test
    fun `an apks that is not a zip is refused`() {
        assertRefused("not a bundle") { copy("plain text".toByteArray(), "apks") }
        assertRefused("not a bundle") { copy(ByteArray(0), "xapk") }
        assertRefused("not a bundle") { copy(ByteArray(2), "apks") }
    }

    @Test
    fun `a bomb is refused before it is written out`() {
        val data = zip("base.apk" to ByteArray(64 * 1024 * 1024))
        assertTrue(data.size < 200_000)
        val folder = temp.newFolder()
        assertRefused("not safe") { ApkBundle.copy(ByteArrayInputStream(data), "apks", -1, folder) }
        val written = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("stopped early: $written", written < 8L * 1024 * 1024)
    }

    @Test
    fun `too many entries are refused`() {
        val data = zip(*Array(5) { "split$it.apk" to ByteArray(10) { 1 } })
        assertRefused("not safe") { copy(data, "apks", limits = ZipLimits.BUNDLE.copy(maxEntries = 3)) }
    }

    @Test
    fun `an apk past the size cap is refused`() {
        assertRefused("too large") { copy(ByteArray(5000), "apk", limits = ZipLimits.BUNDLE.copy(maxEntryBytes = 1000)) }
    }

    @Test
    fun `a bundle cut short is refused, not a crash`() {
        val data = zip("base.apk" to ByteArray(4000) { (it * 31).toByte() })
        assertRefused("damaged") { copy(data.copyOf(data.size / 2), "apks") }
    }

    @Test
    fun `a file bigger than the room left is refused before it is copied`() {
        assertRefused("storage") { copy(ByteArray(10), "apk", declared = Long.MAX_VALUE) }
    }

    @Test
    fun `the type comes from the mime, else from the extension`() {
        assertEquals("apks", ApkTypes.extensionOf("application/x-apks", "x.zip"))
        assertEquals("xapk", ApkTypes.extensionOf("application/xapk-package-archive", null))
        assertEquals("apkm", ApkTypes.extensionOf("application/vnd.apkm", "x"))
        assertEquals("apk", ApkTypes.extensionOf("application/vnd.android.package-archive", "x"))
        assertEquals("apks", ApkTypes.extensionOf("application/octet-stream", "app.APKS"))
        assertEquals("xapk", ApkTypes.extensionOf(null, "app.v2.xapk"))
        assertNull(ApkTypes.extensionOf("application/zip", "photos.zip"))
        assertNull(ApkTypes.extensionOf(null, null))
        assertNull(ApkTypes.extensionOf("application/octet-stream", "noextension"))
    }

    @Test
    fun `a copy the person has left stops at the next read`() {
        var reads = 0
        try {
            ApkBundle.copy(
                ByteArrayInputStream(ByteArray(1_000_000)), "apk", -1, temp.newFolder(), check = {
                    if (++reads == 3) throw java.util.concurrent.CancellationException("left")
                }
            )
            fail("should have stopped")
        } catch (expected: java.util.concurrent.CancellationException) {
            assertEquals(3, reads)
        }
    }

    @Test
    fun `a sweep removes every attempt`() {
        val cache = temp.newFolder()
        val a = ApkBundle.newFolder(cache)
        File(a, "base.apk").writeText("x")
        ApkBundle.newFolder(cache)
        ApkBundle.sweep(cache)
        assertFalse(File(cache, ApkBundle.FOLDER).exists())
        ApkBundle.sweep(cache) // nothing to sweep is fine
    }
}
