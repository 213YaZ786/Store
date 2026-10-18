package com.yaz.store.data.catalog

// Follows komi-store's AssetArchitectureMatcherTest (https://github.com/komi-store/komi-store,
// core/domain/src/commonTest/kotlin/zed/rainxch/core/domain/utils/AssetArchitectureMatcherTest.kt): the names it asserts for each
// architecture, universal and foreign, then the choice for the ABI lists of real phones (Build.SUPPORTED_ABIS,
// https://developer.android.com/ndk/guides/abis#sa).
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbiChoiceTest {

    private val amehrug = listOf(
        "Amehrug-0.24.10-arm64-v8a.apk",
        "Amehrug-0.24.10-armeabi-v7a.apk",
        "Amehrug-0.24.10-universal.apk",
        "Amehrug-0.24.10-x86.apk",
        "Amehrug-0.24.10-x86_64.apk"
    )

    private val pixel = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val oldArm = listOf("armeabi-v7a", "armeabi")
    private val emulator = listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a", "armeabi")
    private val x86Only = listOf("x86_64", "x86")

    @Test
    fun `names say their architecture`() {
        assertEquals(ApkMatch.Known("arm64-v8a"), AbiChoice.match("A-1-arm64-v8a.apk"))
        assertEquals(ApkMatch.Known("arm64-v8a"), AbiChoice.match("A-1-arm64.apk"))
        assertEquals(ApkMatch.Known("arm64-v8a"), AbiChoice.match("A-1-aarch64.apk"))
        assertEquals(ApkMatch.Known("armeabi-v7a"), AbiChoice.match("A-1-armeabi-v7a.apk"))
        assertEquals(ApkMatch.Known("armeabi-v7a"), AbiChoice.match("A-1-armv7.apk"))
        assertEquals(ApkMatch.Known("armeabi-v7a"), AbiChoice.match("A-1-arm.apk"))
        assertEquals(ApkMatch.Known("x86_64"), AbiChoice.match("A-1-x86_64.apk"))
        assertEquals(ApkMatch.Known("x86_64"), AbiChoice.match("A-1-x86-64.apk"))
        assertEquals(ApkMatch.Known("x86_64"), AbiChoice.match("A-1-x64.apk"))
        assertEquals(ApkMatch.Known("x86"), AbiChoice.match("A-1-x86.apk"))
        assertEquals(ApkMatch.Known("x86"), AbiChoice.match("A-1-i686.apk"))
    }

    @Test
    fun `a name with no architecture or a universal one is universal`() {
        assertEquals(ApkMatch.Universal, AbiChoice.match("Calculator-0.3.6.apk"))
        assertEquals(ApkMatch.Universal, AbiChoice.match("Amehrug-0.24.10-universal.apk"))
        assertEquals(ApkMatch.Universal, AbiChoice.match("A-1-noarch.apk"))
        // A word that only contains a token is no token.
        assertEquals(ApkMatch.Universal, AbiChoice.match("Alarm-1.0.apk"))
        assertEquals(ApkMatch.Universal, AbiChoice.match("Farmhand-1.0.apk"))
    }

    @Test
    fun `exotic architectures are foreign`() {
        assertEquals(ApkMatch.Foreign, AbiChoice.match("A-1-riscv64.apk"))
        assertEquals(ApkMatch.Foreign, AbiChoice.match("A-1-s390x.apk"))
        assertEquals(ApkMatch.Foreign, AbiChoice.match("A-1-mips.apk"))
    }

    @Test
    fun `x64 wins over a win32 platform name`() {
        assertEquals(ApkMatch.Known("x86_64"), AbiChoice.match("xmcl-0.59.1-win32-x64.apk"))
    }

    @Test
    fun `a phone takes the APK of its best ABI`() {
        assertEquals("Amehrug-0.24.10-arm64-v8a.apk", AbiChoice.choose(amehrug, pixel))
        assertEquals("Amehrug-0.24.10-armeabi-v7a.apk", AbiChoice.choose(amehrug, oldArm))
        assertEquals("Amehrug-0.24.10-x86_64.apk", AbiChoice.choose(amehrug, emulator))
        assertEquals("Amehrug-0.24.10-x86_64.apk", AbiChoice.choose(amehrug, x86Only))
        assertEquals("Amehrug-0.24.10-x86.apk", AbiChoice.choose(amehrug, listOf("x86")))
    }

    @Test
    fun `the order of the phone's ABIs counts, not the order of the list`() {
        assertEquals("A-x86.apk", AbiChoice.choose(listOf("A-arm64.apk", "A-x86.apk"), listOf("x86", "arm64-v8a")))
        assertEquals("A-arm64.apk", AbiChoice.choose(listOf("A-x86.apk", "A-arm64.apk"), listOf("arm64-v8a", "x86")))
        // An emulator that translates ARM still prefers its own x86_64.
        assertEquals("A-x86_64.apk", AbiChoice.choose(listOf("A-arm64-v8a.apk", "A-x86_64.apk"), emulator))
        // 32-bit ARM runs on a 64-bit phone whose list names it: the 64-bit APK first, the 32-bit one when it is all there is.
        assertEquals("A-arm.apk", AbiChoice.choose(listOf("A-arm.apk", "A-x86.apk"), pixel))
    }

    @Test
    fun `universal is the fallback, after every ABI`() {
        assertEquals("A-universal.apk", AbiChoice.choose(listOf("A-universal.apk", "A-x86.apk"), pixel))
        assertEquals("A-x86.apk", AbiChoice.choose(listOf("A-universal.apk", "A-x86.apk"), x86Only))
        assertEquals("Calculator-0.3.6.apk", AbiChoice.choose(listOf("Calculator-0.3.6.apk"), pixel))
        assertEquals("Calculator-0.3.6.apk", AbiChoice.choose(listOf("Calculator-0.3.6.apk"), listOf("riscv64")))
    }

    @Test
    fun `none that fits gives nothing`() {
        assertNull(AbiChoice.choose(listOf("A-x86.apk", "A-x86_64.apk"), pixel))
        assertNull(AbiChoice.choose(listOf("A-arm64.apk"), x86Only))
        assertNull(AbiChoice.choose(listOf("A-riscv64.apk"), pixel))
        assertNull(AbiChoice.choose(listOf("A-riscv64.apk"), listOf("riscv64")))
        assertNull(AbiChoice.choose(emptyList(), pixel))
        assertNull(AbiChoice.choose(listOf("A-arm64.apk"), emptyList()))
    }
}
