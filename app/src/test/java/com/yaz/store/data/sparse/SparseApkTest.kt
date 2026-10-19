package com.yaz.store.data.sparse

// The range reader on the JVM, over the real APKs of app/src/test/resources/apk (the fixtures of core/apk/ApkInspectTest, from App Manager's tests,
// https://github.com/MuntashirAkon/AppManager/tree/master/app/src/test/resources/oandbackups) and zips built from them, with a fake RangeSource that
// logs every range asked and a session that runs SparseInspect.step in-process (the isolated service runs the same function). The expected values are the
// ones the full inspector reads from the whole file (core/apk/ApkInspect), the very reading the full install path uses.
import com.yaz.store.core.apk.ApkInspect
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.RangeSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SparseApkTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File =
        File(checkNotNull(javaClass.classLoader?.getResource("apk/$name")) { "missing fixture $name" }.toURI())

    /** Every range asked, in order; [failWith] makes the n-th read (0 is the first) throw. */
    private class Ranges(private val file: File, private val failWith: Map<Int, NetError> = emptyMap(), private val lengthError: NetError? = null) : RangeSource {
        val asked = mutableListOf<LongRange>()
        override fun length(): Long = lengthError?.let { throw it } ?: file.length()
        override fun read(start: Long, endInclusive: Long): ByteArray {
            failWith[asked.size]?.let { throw it }
            asked += start..endInclusive
            RandomAccessFile(file, "r").use { raf ->
                val out = ByteArray((endInclusive - start + 1).toInt())
                raf.seek(start)
                raf.readFully(out)
                return out
            }
        }
        val fetched: Long get() = asked.sumOf { it.last - it.first + 1 }
    }

    /** The in-process stand-in for the isolated service. */
    private val inProcess = object : SparseSession {
        override suspend fun <T> open(block: suspend (SparseStepper) -> T): T =
            block { stage, file -> FileInputStream(file).use { SparseInspect.step(stage, it.channel) } }
    }

    private fun reader() = SparseApk(inProcess, temp.newFolder())

    private fun read(source: RangeSource): SparseInfo? = runBlocking { reader().read(source) }

    private fun full(file: File) = FileInputStream(file).use { ApkInspect.inspect(it.channel) }

    /** A zip with [fill] filler entries, the manifest of [from] first, and its resources.arsc replaced when [table] is given. */
    private fun zipOf(from: File, fill: Int, nameLength: Int = 40, table: ByteArray? = null): File {
        val out = temp.newFile()
        ZipFile(from).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { z ->
                z.putNextEntry(ZipEntry("AndroidManifest.xml"))
                z.write(zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes())
                z.closeEntry()
                val arsc = zip.getEntry("resources.arsc")
                if (table != null) {
                    val entry = ZipEntry("resources.arsc").apply { method = ZipEntry.STORED; size = table.size.toLong(); compressedSize = size; crc = java.util.zip.CRC32().also { it.update(table) }.value }
                    z.putNextEntry(entry)
                    z.write(table)
                    z.closeEntry()
                } else if (arsc != null) {
                    z.putNextEntry(ZipEntry("resources.arsc"))
                    z.write(zip.getInputStream(arsc).readBytes())
                    z.closeEntry()
                }
                for (i in 0 until fill) {
                    z.putNextEntry(ZipEntry("res/" + i.toString().padStart(nameLength, 'x')))
                    z.write(byteArrayOf(1))
                    z.closeEntry()
                }
            }
        }
        return out
    }

    @Test
    fun `an apk is read from its tail, its directory and two entries, not from its whole`() {
        val apk = fixture("editor.apk")
        val source = Ranges(apk)
        val info = read(source)
        val expected = full(apk)
        assertNotNull(info)
        assertEquals(expected.packageName, info!!.packageName)
        assertEquals(expected.versionCode, info.versionCode)
        assertEquals(expected.versionName, info.versionName)
        assertEquals(expected.minSdk, info.minSdk)
        assertEquals("org.billthefarmer.editor", info.packageName)
        // The tail first, then the directory, then the manifest and the table: four reads at most, far less than the file.
        assertTrue(source.asked.size in 3..4)
        assertEquals(apk.length() - 1, source.asked.first().last)
        assertTrue("fetched ${source.fetched} of ${apk.length()}", source.fetched < apk.length())
    }

    @Test
    fun `the end of central directory is found in the tail alone`() {
        val apk = fixture("editor.apk")
        val source = Ranges(apk)
        read(source)
        val tail = source.asked.first()
        assertTrue(tail.last - tail.first + 1 <= SparseInspect.TAIL)
        // The second read is the central directory, which begins before the tail's start or inside it, and ends at the file's end less the record.
        assertTrue(source.asked[1].first >= 0)
    }

    @Test
    fun `a central directory larger than the tail is fetched in a range of its own`() {
        val big = zipOf(fixture("editor.apk"), fill = 3000)
        val source = Ranges(big)
        val info = read(source)
        assertEquals("org.billthefarmer.editor", info?.packageName)
        // 3000 entries of 40 characters: a directory of about 270 KB, past the 64 KiB tail.
        val directory = source.asked[1]
        assertTrue("directory ${directory.last - directory.first + 1}", directory.last - directory.first + 1 > SparseInspect.TAIL)
        assertTrue(directory.last - directory.first + 1 <= SparseInspect.MAX_DIRECTORY)
    }

    @Test
    fun `a central directory over a mebibyte is refused before it is fetched`() {
        val huge = zipOf(fixture("editor.apk"), fill = 20_000, nameLength = 60)
        val source = Ranges(huge)
        assertNull(read(source))
        // Only the tail was fetched.
        assertEquals(1, source.asked.size)
        assertTrue(source.fetched <= SparseInspect.TAIL)
    }

    @Test
    fun `a resources table over four mebibytes is left out and the app is still read`() {
        val table = ByteArray(5 shl 20) { (it % 251).toByte() }
        val apk = zipOf(fixture("editor.apk"), fill = 5, table = table)
        val source = Ranges(apk)
        val info = read(source)
        assertEquals("org.billthefarmer.editor", info?.packageName)
        assertTrue("fetched ${source.fetched}", source.fetched < 4L shl 20)
    }

    @Test
    fun `no more than eight mebibytes are ever fetched for one apk`() {
        assertTrue(SparseApk.MAX_TOTAL == 8L shl 20)
        assertTrue(SparseInspect.TAIL + SparseInspect.capOf(Want.Kind.DIRECTORY) + SparseInspect.capOf(Want.Kind.MANIFEST) + SparseInspect.capOf(Want.Kind.TABLE) <= SparseApk.MAX_TOTAL)
        // An inspector that asks past a cap is stopped by the reader, whatever it says.
        val apk = fixture("editor.apk")
        val source = Ranges(apk)
        val greedy = object : SparseSession {
            override suspend fun <T> open(block: suspend (SparseStepper) -> T): T = block { stage, _ ->
                if (stage == 1) SparseAnswer.Need(listOf(Want(0, 2L shl 20, Want.Kind.DIRECTORY))) else null
            }
        }
        assertNull(runBlocking { SparseApk(greedy, temp.newFolder()).read(source) })
        assertEquals(1, source.asked.size)
    }

    @Test
    fun `an inspector that names a range outside the file or the wrong kind is stopped`() {
        val apk = fixture("editor.apk")
        val outside = object : SparseSession {
            override suspend fun <T> open(block: suspend (SparseStepper) -> T): T = block { _, _ ->
                SparseAnswer.Need(listOf(Want(apk.length() - 10, 100, Want.Kind.DIRECTORY)))
            }
        }
        assertNull(runBlocking { SparseApk(outside, temp.newFolder()).read(Ranges(apk)) })
        val wrongKind = object : SparseSession {
            override suspend fun <T> open(block: suspend (SparseStepper) -> T): T = block { _, _ ->
                SparseAnswer.Need(listOf(Want(0, 10, Want.Kind.TABLE)))
            }
        }
        assertNull(runBlocking { SparseApk(wrongKind, temp.newFolder()).read(Ranges(apk)) })
    }

    @Test
    fun `a server that refuses the ranges lists the repo by its name, a lost network is raised`() {
        val apk = fixture("editor.apk")
        // A 200 instead of a 206 reaches the reader as ReleaseNet's refusal.
        assertNull(read(Ranges(apk, failWith = mapOf(0 to NetError(NetError.Kind.REFUSED, message = "range ignored")))))
        assertNull(read(Ranges(apk, lengthError = NetError(NetError.Kind.NOT_FOUND, message = "404"))))
        try {
            read(Ranges(apk, failWith = mapOf(1 to NetError(NetError.Kind.OFFLINE, message = "offline"))))
            fail("offline should be raised")
        } catch (e: NetError) {
            assertEquals(NetError.Kind.OFFLINE, e.kind)
        }
    }

    @Test
    fun `the work file is removed whatever happens`() {
        val work = temp.newFolder()
        runBlocking { SparseApk(inProcess, work).read(Ranges(fixture("editor.apk"))) }
        runBlocking { SparseApk(inProcess, work).read(Ranges(zipOf(fixture("editor.apk"), fill = 20_000, nameLength = 60))) }
        assertEquals(0, work.listFiles()?.size)
    }

    @Test
    fun `noise, a cut file and a split are not apps`() {
        val noise = temp.newFile().apply { writeBytes(ByteArray(200_000) { (it * 31).toByte() }) }
        assertNull(read(Ranges(noise)))
        val tiny = temp.newFile().apply { writeBytes(ByteArray(10)) }
        assertNull(read(Ranges(tiny)))
        val cut = temp.newFile().apply { writeBytes(fixture("editor.apk").readBytes().copyOf(50_000)) }
        assertNull(read(Ranges(cut)))
        // A part of an app, not an app.
        assertNull(read(Ranges(fixture("splits_fr.apk"))))
    }

    @Test
    fun `the isolated process's answers pass the same rules as the full inspector's`() {
        // textlauncher.apk: a second real APK, v1 signed.
        val apk = fixture("textlauncher.apk")
        val info = read(Ranges(apk))
        val expected = full(apk)
        assertEquals(expected.packageName, info?.packageName)
        assertEquals(expected.versionCode, info?.versionCode)
        assertEquals(expected.versionName, info?.versionName)
        assertEquals(expected.label, info?.label)
    }

    @Test
    fun `the wire round trip keeps an answer and refuses a hostile one`() {
        fun roundTrip(answer: SparseAnswer): SparseAnswer {
            val bytes = ByteArrayOutputStream().also { SparseWire.write(answer, it) }.toByteArray()
            return SparseWire.read(bytes.inputStream())
        }
        val info = SparseAnswer.Info(SparseInfo("com.yaz.calculator", 12, "0.3.7", "Calculator", 31))
        assertEquals(info, roundTrip(info))
        assertEquals(SparseAnswer.Refused, roundTrip(SparseAnswer.Refused))
        val need = SparseAnswer.Need(listOf(Want(100, 200, Want.Kind.DIRECTORY)))
        assertEquals(need, roundTrip(need))
        fun refused(answer: SparseAnswer) {
            try {
                roundTrip(answer)
                fail("should be refused")
            } catch (expected: java.io.IOException) {
            }
        }
        refused(SparseAnswer.Info(SparseInfo("not a package", 12, "1", null, 31)))
        refused(SparseAnswer.Info(SparseInfo("com.yaz.calculator", 0, "1", null, 31)))
        refused(SparseAnswer.Info(SparseInfo("com.yaz.calculator", 1, "1", null, 0)))
        refused(SparseAnswer.Need(listOf(Want(0, SparseInspect.MAX_DIRECTORY + 1, Want.Kind.DIRECTORY))))
        refused(SparseAnswer.Need(listOf(Want(-1, 5, Want.Kind.MANIFEST))))
        refused(SparseAnswer.Need(emptyList()))
        // Bytes that are not an answer, and a cut one.
        try {
            SparseWire.read(ByteArray(40) { 7 }.inputStream())
            fail("noise")
        } catch (expected: java.io.IOException) {
        }
        try {
            SparseWire.read(ByteArrayOutputStream().also { SparseWire.write(info, it) }.toByteArray().copyOf(9).inputStream())
            fail("cut")
        } catch (expected: java.io.IOException) {
        }
    }
}
