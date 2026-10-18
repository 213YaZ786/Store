package com.yaz.store.core.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Real APKs, the way App Manager's tests read its own fixtures (GPL-3.0-or-later, app/src/test/resources/oandbackups/
// ademar.textlauncher/base.apk and org.billthefarmer.editor/base.apk):
// https://github.com/MuntashirAkon/AppManager/tree/master/app/src/test/resources/oandbackups
// The expected values are what Android's own tools print for them, aapt2 dump badging and apksigner verify --print-certs
// (https://developer.android.com/tools/aapt2, https://developer.android.com/tools/apksigner). Hostile samples: an APK
// changed after it was signed, a zip with no manifest, a manifest that is not a manifest, a cut file, noise.
class ApkInspectTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun resource(name: String): File {
        val url = checkNotNull(javaClass.classLoader?.getResource("apk/$name")) { "missing fixture $name" }
        return File(url.toURI())
    }

    private fun inspect(file: File): ApkInfo = FileInputStream(file).use { ApkInspect.inspect(it.channel) }

    private fun assertFails(file: File) {
        try {
            inspect(file)
            fail("should have failed")
        } catch (expected: Exception) {
            // a file that is not a readable APK: the inspector answers nothing
        }
    }

    @Test
    fun `an apk signed with schemes v1 v2 and v3 is read`() {
        val info = inspect(resource("editor.apk"))
        assertEquals("org.billthefarmer.editor", info.packageName)
        assertEquals(155L, info.versionCode)
        assertEquals("1.55", info.versionName)
        assertNull(info.splitName)
        assertFalse(info.isFeatureSplit)
        assertFalse(info.isSplitRequired)
        assertEquals(14, info.minSdk)
        assertEquals(listOf("android.permission.WRITE_EXTERNAL_STORAGE"), info.permissions)
        assertTrue(info.verified)
        assertEquals(listOf("dc44ca63efa2980355d9bc4e90897f6b66e0c26ee30b0190c89a6f6b2b13cc09"), info.signers)
        assertTrue(info.abis.isEmpty())
    }

    @Test
    fun `the label comes from the resources, in the default configuration`() {
        assertEquals("Editor", inspect(resource("editor.apk")).label)
        assertEquals("Text Launcher", inspect(resource("textlauncher.apk")).label)
    }

    @Test
    fun `an apk signed with the old jar scheme only is verified too`() {
        val info = inspect(resource("textlauncher.apk"))
        assertEquals("ademar.textlauncher", info.packageName)
        assertEquals(7L, info.versionCode)
        assertEquals("1.3.1", info.versionName)
        assertTrue(info.verified)
        assertEquals(listOf("4ca3d35b3ae3e8c7a56d96f715cd1f5165b8103c8d238dd19d0d26ed3eef0c3d"), info.signers)
    }

    @Test
    fun `an apk changed after it was signed is not verified and gives no signer`() {
        val changed = File(temp.root, "changed.apk")
        ZipFile(resource("editor.apk")).use { source ->
            ZipOutputStream(changed.outputStream()).use { out ->
                for (entry in source.entries()) {
                    out.putNextEntry(ZipEntry(entry.name))
                    source.getInputStream(entry).copyTo(out)
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry("lib/arm64-v8a/libnative.so"))
                out.write(ByteArray(64))
                out.closeEntry()
                out.putNextEntry(ZipEntry("lib/x86/libnative.so"))
                out.write(ByteArray(64))
                out.closeEntry()
                // Not what NativeLibraryHelper counts: not a lib*.so in an ABI folder.
                out.putNextEntry(ZipEntry("assets/lib/mips/libignored.so"))
                out.write(ByteArray(8))
                out.closeEntry()
                out.putNextEntry(ZipEntry("lib/armeabi-v7a/readme.txt"))
                out.write(ByteArray(8))
                out.closeEntry()
            }
        }
        val info = inspect(changed)
        assertEquals(listOf("arm64-v8a", "x86"), info.abis)
        assertFalse(info.verified)
        assertTrue(info.signers.isEmpty())
        // The manifest still reads.
        assertEquals("org.billthefarmer.editor", info.packageName)
    }

    @Test
    fun `what the inspector says goes through the plain bytes unchanged`() {
        val info = inspect(resource("editor.apk"))
        val bytes = ByteArrayOutputStream().also { ApkInfoCodec.write(info, it) }.toByteArray()
        assertEquals(info, ApkInfoCodec.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `noise, an empty file and a cut apk are not APKs`() {
        assertFails(File(temp.root, "noise").apply { writeBytes(ByteArray(5000) { (it * 31).toByte() }) })
        assertFails(File(temp.root, "empty").apply { writeBytes(ByteArray(0)) })
        val whole = resource("editor.apk").readBytes()
        assertFails(File(temp.root, "cut").apply { writeBytes(whole.copyOf(whole.size / 2)) })
        assertFails(File(temp.root, "head").apply { writeBytes(whole.copyOf(30)) })
    }

    @Test
    fun `a zip with no manifest is not an APK`() {
        val file = File(temp.root, "plain.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("hello.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }
        assertFails(file)
    }

    @Test
    fun `a manifest that is not a binary manifest is refused`() {
        val file = File(temp.root, "fake.apk")
        ZipOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write("<manifest package=\"a.b\"/>".toByteArray())
            out.closeEntry()
        }
        assertFails(file)
        val garbage = File(temp.root, "garbage.apk")
        ZipOutputStream(garbage.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(ByteArray(2000) { (it * 7).toByte() })
            out.closeEntry()
        }
        assertFails(garbage)
    }
}
