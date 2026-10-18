package com.yaz.store.data.install

import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.trust.OurKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The checks in the order the queue makes them. Cases after GrapheneOS' App Store, which verifies the package, the version and the certificate
// digests of a download before it commits a session (InstallTask.kt, findPackage,
// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/core/InstallTask.kt), and the update kit's Updates.stage
// (Modules shared/update), which opens a file only after its SHA256SUMS line matched. The sums file is GNU coreutils' format
// (https://www.gnu.org/software/coreutils/manual/html_node/md5sum-invocation.html).
class StoreChecksTest {

    private val ours = OurKeys.DIGESTS.first()
    private val other = OurKeys.DIGESTS.last()

    private val entry = StoreEntry(
        repo = "Calculator", tag = "v0.3.6", packageName = "com.yaz.calculator", label = "Calculator", description = "",
        versionCode = 14, versionName = "0.3.6", minSdk = 31, apkName = "Calculator-0.3.6.apk", iconUrl = null
    )

    private fun info(
        packageName: String = "com.yaz.calculator", versionCode: Long = 14, minSdk: Int = 31, verified: Boolean = true,
        signers: List<String> = listOf(ours), splitName: String? = null
    ) = ApkInfo(
        packageName = packageName, versionCode = versionCode, versionName = "0.3.6", splitName = splitName, isFeatureSplit = false,
        configForSplit = null, isSplitRequired = false, minSdk = minSdk, permissions = emptyList(), abis = emptyList(),
        verified = verified, signers = signers, label = "Calculator"
    )

    private val digest = "a".repeat(64)

    @Test
    fun `the sum is the line naming exactly the apk`() {
        val sums = "$digest  Calculator-0.3.6.apk\n${"b".repeat(64)}  Other.apk\n"
        assertEquals(digest, StoreChecks.expectedSum(sums, "Calculator-0.3.6.apk"))
        assertEquals("b".repeat(64), StoreChecks.expectedSum(sums, "Other.apk"))
    }

    @Test
    fun `a binary mode star, tabs, capitals and a windows line end are read`() {
        val sums = "${"A".repeat(64)} *Calculator-0.3.6.apk\r\n${"c".repeat(64)}\tTabbed.apk\n"
        assertEquals("a".repeat(64), StoreChecks.expectedSum(sums, "Calculator-0.3.6.apk"))
        assertEquals("c".repeat(64), StoreChecks.expectedSum(sums, "Tabbed.apk"))
    }

    // The real SHA256SUMS.txt of Calculator v0.3.6 (https://github.com/213YaZ786/Calculator/releases/tag/v0.3.6), lines as the CI writes them.
    @Test
    fun `the lines of a real release name the files with a dot slash`() {
        val sums = "ecd670622a3fe9d7b9a22e93d98899865788e90b514af771953b726afe56edc1  ./Calculator-0.3.6-debug.apk\n" +
            "4eaa8588a99c3f090671aef30cd7f45a4e282272a7f5423b653922c309e30261  ./Calculator-0.3.6.apk\n"
        assertEquals("4eaa8588a99c3f090671aef30cd7f45a4e282272a7f5423b653922c309e30261", StoreChecks.expectedSum(sums, "Calculator-0.3.6.apk"))
        assertEquals("ecd670622a3fe9d7b9a22e93d98899865788e90b514af771953b726afe56edc1", StoreChecks.expectedSum(sums, "Calculator-0.3.6-debug.apk"))
        assertNull(StoreChecks.expectedSum("$digest  ./sub/Calculator-0.3.6.apk\n", "Calculator-0.3.6.apk"))
        assertNull(StoreChecks.expectedSum("$digest  ../Calculator-0.3.6.apk\n", "Calculator-0.3.6.apk"))
    }

    @Test
    fun `a name that only ends like the apk, a short digest or no line gives nothing`() {
        assertNull(StoreChecks.expectedSum("$digest  xCalculator-0.3.6.apk\n", "Calculator-0.3.6.apk"))
        assertNull(StoreChecks.expectedSum("$digest  Calculator-0.3.6.apk.sig\n", "Calculator-0.3.6.apk"))
        assertNull(StoreChecks.expectedSum("abc  Calculator-0.3.6.apk\n", "Calculator-0.3.6.apk"))
        assertNull(StoreChecks.expectedSum("${"z".repeat(64)}  Calculator-0.3.6.apk\n", "Calculator-0.3.6.apk"))
        assertNull(StoreChecks.expectedSum("", "Calculator-0.3.6.apk"))
        assertNull(StoreChecks.expectedSum("\n\n   \n", "Calculator-0.3.6.apk"))
    }

    @Test
    fun `an honest file passes`() {
        assertNull(StoreChecks.refusal(entry, info(), installedSigners = null, sdk = 36))
        assertNull(StoreChecks.refusal(entry, info(), installedSigners = setOf(ours), sdk = 31))
    }

    @Test
    fun `a wrong package is refused`() {
        assertEquals("The file is another app than the release says.", StoreChecks.refusal(entry, info(packageName = "com.evil.app"), null, 36))
    }

    @Test
    fun `a wrong versionCode is refused, lower or higher`() {
        val why = "The file is another version than the release says."
        assertEquals(why, StoreChecks.refusal(entry, info(versionCode = 13), null, 36))
        assertEquals(why, StoreChecks.refusal(entry, info(versionCode = 15), null, 36))
    }

    @Test
    fun `a foreign signer is refused, even beside one of ours`() {
        val why = "The file is signed with a key that is not ours."
        assertEquals(why, StoreChecks.refusal(entry, info(signers = listOf("0".repeat(64))), null, 36))
        assertEquals(why, StoreChecks.refusal(entry, info(signers = listOf(ours, "0".repeat(64))), null, 36))
    }

    @Test
    fun `signers that differ from the installed app's are refused, though ours`() {
        val why = "The file is signed differently from the app on the phone."
        assertEquals(why, StoreChecks.refusal(entry, info(signers = listOf(ours)), installedSigners = setOf(other), sdk = 36))
        assertEquals(why, StoreChecks.refusal(entry, info(signers = listOf(ours)), installedSigners = setOf(ours, other), sdk = 36))
    }

    @Test
    fun `a minSdk above the phone is refused`() {
        assertEquals(
            "This app needs Android API level 37 or newer. This phone runs API level 36.",
            StoreChecks.refusal(entry, info(minSdk = 37), null, 36)
        )
    }

    @Test
    fun `an unsigned or broken file and a split are refused first`() {
        val unsigned = "The file is not signed, or its signature is broken."
        assertEquals(unsigned, StoreChecks.refusal(entry, info(verified = false), null, 36))
        assertEquals(unsigned, StoreChecks.refusal(entry, info(signers = emptyList()), null, 36))
        assertEquals("The file is a part of an app, not an app.", StoreChecks.refusal(entry, info(splitName = "config.fr"), null, 36))
    }

    @Test
    fun `the order is package, version, key, installed key, level`() {
        // Everything wrong at once: the package is told first, and each fix reveals the next.
        val all = info(packageName = "com.evil.app", versionCode = 1, signers = listOf("0".repeat(64)), minSdk = 99)
        assertEquals("The file is another app than the release says.", StoreChecks.refusal(entry, all, setOf(other), 36))
        val noPackage = info(versionCode = 1, signers = listOf("0".repeat(64)), minSdk = 99)
        assertEquals("The file is another version than the release says.", StoreChecks.refusal(entry, noPackage, setOf(other), 36))
        val noVersion = info(signers = listOf("0".repeat(64)), minSdk = 99)
        assertEquals("The file is signed with a key that is not ours.", StoreChecks.refusal(entry, noVersion, setOf(other), 36))
        val noKey = info(minSdk = 99)
        assertEquals("The file is signed differently from the app on the phone.", StoreChecks.refusal(entry, noKey, setOf(other), 36))
        assertEquals("This app needs Android API level 99 or newer. This phone runs API level 36.", StoreChecks.refusal(entry, noKey, setOf(ours), 36))
    }
}
