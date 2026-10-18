package com.yaz.store.core.install

import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.core.apk.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Cases of what the package manager refuses, from AOSP's install failure codes
// (https://developer.android.com/reference/android/content/pm/PackageInstaller, INSTALL_FAILED_NO_MATCHING_ABIS,
// INSTALL_FAILED_OLDER_SDK, INSTALL_FAILED_VERSION_DOWNGRADE, INSTALL_FAILED_UPDATE_INCOMPATIBLE), of the NativeLibraryHelper
// rule (an APK with lib/<abi>/ folders needs one of Build.SUPPORTED_ABIS,
// https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/content/NativeLibraryHelper.java)
// and of App Manager's ApkFile checks ("Duplicate base apk found", no base, the package of the splits;
// https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkFile.java).
class InstallChecksTest {

    private val arm64Phone = DeviceProfile(abis = listOf("arm64-v8a", "armeabi-v7a"), density = 420, languages = listOf("en"))
    private val signer = "ab".repeat(32)

    private fun apk(
        split: String? = null,
        pkg: String = "org.example.app",
        code: Long = 10,
        minSdk: Int = 24,
        abis: List<String> = emptyList(),
        verified: Boolean = true,
        signers: List<String> = listOf(signer),
        feature: Boolean = false,
        forSplit: String? = null
    ) = ApkInfo(
        packageName = pkg, versionCode = code, versionName = "v$code", splitName = split, isFeatureSplit = feature,
        configForSplit = forSplit, isSplitRequired = false, minSdk = minSdk, permissions = emptyList(), abis = abis,
        verified = verified, signers = signers, label = null
    )

    private fun verdict(
        vararg infos: ApkInfo,
        device: DeviceProfile = arm64Phone,
        sdk: Int = 36,
        installed: InstalledApp? = null,
        obb: Int = 0
    ) = InstallChecks.verdict(infos.toList(), device, sdk, installed, obb)

    private fun refusedWith(result: InstallVerdict, text: String) {
        assertTrue("expected a refusal about '$text', got $result", result is InstallVerdict.Refused && result.reason.contains(text))
    }

    @Test
    fun `a plain apk is allowed with nothing to say`() {
        assertEquals(InstallVerdict.Allowed(emptyList()), verdict(apk()))
    }

    @Test
    fun `splits with no base are refused`() {
        refusedWith(verdict(apk(split = "config.fr")), "no base")
        refusedWith(verdict(), "no base")
    }

    @Test
    fun `two bases are refused`() {
        refusedWith(verdict(apk(), apk()), "more than one base")
    }

    @Test
    fun `apks of different apps are refused`() {
        refusedWith(verdict(apk(), apk(split = "config.fr", pkg = "org.other.app")), "different apps")
    }

    @Test
    fun `the same split twice is refused`() {
        refusedWith(verdict(apk(), apk(split = "config.fr"), apk(split = "config.fr")), "twice")
    }

    @Test
    fun `an apk not signed, or with a broken signature, is refused`() {
        refusedWith(verdict(apk(verified = false, signers = emptyList())), "not signed")
        refusedWith(verdict(apk(), apk(split = "config.fr", verified = false)), "not signed")
        refusedWith(verdict(apk(signers = emptyList())), "not signed")
    }

    @Test
    fun `apks signed with different keys are refused`() {
        refusedWith(verdict(apk(), apk(split = "config.fr", signers = listOf("cd".repeat(32)))), "different keys")
    }

    @Test
    fun `an app that needs a newer Android is refused, a phone that is just enough is not`() {
        refusedWith(verdict(apk(minSdk = 37), sdk = 36), "API level 37")
        refusedWith(verdict(apk(), apk(split = "config.fr", minSdk = 40), sdk = 36), "API level 40")
        assertTrue(verdict(apk(minSdk = 36), sdk = 36) is InstallVerdict.Allowed)
    }

    @Test
    fun `libraries for a processor the phone does not run are refused`() {
        refusedWith(verdict(apk(abis = listOf("x86", "x86_64"))), "processor")
        refusedWith(verdict(apk(abis = listOf("armeabi-v7a")), device = DeviceProfile(listOf("arm64-v8a"), 420, emptyList())), "processor")
    }

    @Test
    fun `libraries for one of the phone's processors are allowed`() {
        assertTrue(verdict(apk(abis = listOf("x86", "arm64-v8a"))) is InstallVerdict.Allowed)
        assertTrue(verdict(apk(abis = listOf("armeabi-v7a"))) is InstallVerdict.Allowed)
    }

    @Test
    fun `a set whose ABI splits are all for other processors is refused`() {
        refusedWith(verdict(apk(), apk(split = "config.x86"), apk(split = "config.x86_64")), "processor")
    }

    @Test
    fun `a set with one ABI split the phone runs is allowed`() {
        assertTrue(verdict(apk(), apk(split = "config.x86"), apk(split = "config.arm64_v8a")) is InstallVerdict.Allowed)
    }

    @Test
    fun `an app with no native code is for every processor`() {
        val x86Phone = DeviceProfile(listOf("x86_64", "x86"), 420, emptyList())
        assertTrue(verdict(apk(), apk(split = "config.fr"), device = x86Phone) is InstallVerdict.Allowed)
    }

    @Test
    fun `the ABI split of a feature does not decide for the base`() {
        val result = verdict(apk(), apk(split = "dyn.config.x86", forSplit = "dyn"), apk(split = "dyn", feature = true))
        assertTrue(result is InstallVerdict.Allowed)
    }

    private fun installed(code: Long, same: Boolean = true) =
        InstalledApp(versionCode = code, versionName = "v$code", splitNames = emptySet(), sameSigner = same)

    @Test
    fun `an update is allowed with nothing to say`() {
        assertEquals(InstallVerdict.Allowed(emptyList()), verdict(apk(code = 11), installed = installed(10)))
        assertEquals(InstallVerdict.Allowed(emptyList()), verdict(apk(code = 10), installed = installed(10)))
    }

    @Test
    fun `an older version warns about the downgrade and can still go on`() {
        val result = verdict(apk(code = 9), installed = installed(10))
        assertEquals(InstallVerdict.Allowed(listOf(InstallWarning.Downgrade("v10", "v9"))), result)
    }

    @Test
    fun `another signer warns and can still go on`() {
        val result = verdict(apk(code = 11), installed = installed(10, same = false))
        assertEquals(InstallVerdict.Allowed(listOf(InstallWarning.SignerChanged)), result)
    }

    @Test
    fun `both warnings and the obb come together`() {
        val result = verdict(apk(code = 1), installed = installed(10, same = false), obb = 2)
        assertEquals(
            InstallVerdict.Allowed(listOf(InstallWarning.Downgrade("v10", "v1"), InstallWarning.SignerChanged, InstallWarning.ObbSkipped(2))),
            result
        )
    }

    @Test
    fun `nothing installed means no warning about versions or signers`() {
        assertEquals(InstallVerdict.Allowed(emptyList()), verdict(apk(code = 1), installed = null))
    }
}
