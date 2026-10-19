package com.yaz.store

// JUnit 4 (https://github.com/junit-team/junit4/wiki/Assertions) and its TemporaryFolder rule (https://github.com/junit-team/junit4/wiki/Rules#temporaryfolder-rule),
// as CatalogTest uses them: the debug overlay over files made in a scratch folder.
import com.yaz.store.data.catalog.StoreEntry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugMetaTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun entry(repo: String) = StoreEntry(
        repo = repo, tag = "v1", packageName = "p.$repo", label = repo, description = "from the repo", versionCode = 1, versionName = "1",
        minSdk = 31, apkName = "a.apk", iconUrl = null
    )

    private fun files(meta: String?, vararg icons: Pair<String, ByteArray>): File {
        val dir = folder.newFolder()
        meta?.let { File(dir, "debug-meta.json").writeText(it) }
        File(dir, "debug-icons").mkdirs()
        icons.forEach { (name, bytes) -> File(dir, "debug-icons/$name").writeBytes(bytes) }
        return dir
    }

    @Test
    fun descriptionAndIconByRepo() {
        val dir = files("""{"Dialer":{"description":"Calls and history","icon":"Dialer.png"}}""", "Dialer.png" to byteArrayOf(1, 2, 3))
        val meta = DebugMeta(dir)
        assertEquals(listOf("Calls and history", "from the repo"), meta.apply(listOf(entry("Dialer"), entry("SMS"))).map { it.description })
        assertArrayEquals(byteArrayOf(1, 2, 3), meta.icon(entry("Dialer")))
        assertNull(meta.icon(entry("SMS")))
    }

    @Test
    fun nothingWithoutAFileOrWithABadOne() {
        assertEquals("from the repo", DebugMeta(files(null)).apply(listOf(entry("Dialer"))).single().description)
        assertEquals("from the repo", DebugMeta(files("not json")).apply(listOf(entry("Dialer"))).single().description)
    }

    @Test
    fun aPictureOnlyByItsPlainName() {
        val dir = files("""{"A":{"icon":"../debug-meta.json"},"B":{"icon":"big.png"},"C":{"icon":"missing.png"}}""", "big.png" to ByteArray(600 * 1024))
        val meta = DebugMeta(dir)
        assertNull(meta.icon(entry("A")))
        assertNull(meta.icon(entry("B")))
        assertNull(meta.icon(entry("C")))
    }
}
