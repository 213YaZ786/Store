package com.yaz.store.data.catalog

// Which of an app's APKs fits this phone. Ported from komi-store's AssetArchitectureMatcher (Apache-2.0,
// https://github.com/komi-store/komi-store, core/domain/src/commonMain/kotlin/zed/rainxch/core/domain/utils/AssetArchitectureMatcher.kt):
// the same boundary tokens for each architecture, a name with none is universal, an exotic architecture is foreign. The phone's
// ABIs are tried in Build.SUPPORTED_ABIS' order (https://developer.android.com/ndk/guides/abis#sa), an APK built for that ABI
// first, a universal one when no ABI fits. A foreign APK is never chosen.

/** The architecture a file name says it is built for. */
sealed interface ApkMatch {
    data class Known(val abi: String) : ApkMatch
    data object Universal : ApkMatch
    data object Foreign : ApkMatch
}

object AbiChoice {

    // komi-store's token lists, with Android's ABI names in place of its SystemArchitecture.
    private val universal = boundary("universal", "noarch", "all-arch", "fat")
    private val x86_64 = boundary("x86[_-]64", "amd64", "win64", "x64")
    private val arm64 = boundary("aarch64", "arm64", "arm64-v8a", "armv8a", "armv8l", "armv8", "arm-v8", "v8a")
    private val x86 = boundary("i386", "i586", "i686", "ia32", "win32", "x86", "386")
    private val arm = boundary(
        "armeabi-v7a", "armeabi", "armv7l", "armv7a", "armv7", "armv6l", "armv6", "armhf", "armel", "arm-v7", "arm32", "v7a", "arm"
    )
    private val foreign = boundary(
        "riscv64", "riscv", "ppc64le", "ppc64", "powerpc", "ppc", "s390x", "loong64", "loongarch", "mips64", "mips", "sparc64", "sparc"
    )

    /** The architecture [name] is built for, as Android's ABI name (arm64-v8a, armeabi-v7a, x86_64, x86), or universal, or foreign. */
    fun match(name: String): ApkMatch {
        val n = name.lowercase().replace('_', '-')
        return when {
            universal.containsMatchIn(n) -> ApkMatch.Universal
            x86_64.containsMatchIn(n) -> ApkMatch.Known("x86_64")
            arm64.containsMatchIn(n) -> ApkMatch.Known("arm64-v8a")
            x86.containsMatchIn(n) -> ApkMatch.Known("x86")
            arm.containsMatchIn(n) -> ApkMatch.Known("armeabi-v7a")
            foreign.containsMatchIn(n) -> ApkMatch.Foreign
            else -> ApkMatch.Universal
        }
    }

    /**
     * The APK of [apks] for a phone whose ABIs are [abis] (Build.SUPPORTED_ABIS, best first), or null when none fits: for each ABI
     * in order, the APK built for it; then a universal APK (or one that names no architecture); else null. `armeabi` counts as
     * `armeabi-v7a`, as komi-store's ARM does.
     */
    fun choose(apks: List<String>, abis: List<String>): String? {
        val matches = apks.map { it to match(it) }
        for (abi in abis) {
            val wanted = if (abi == "armeabi") "armeabi-v7a" else abi
            matches.firstOrNull { (_, m) -> m is ApkMatch.Known && m.abi == wanted }?.let { return it.first }
        }
        return matches.firstOrNull { it.second is ApkMatch.Universal }?.first
    }

    // komi-store's boundary: the token between two characters that are not letters or digits, or at an end.
    private fun boundary(vararg tokens: String): Regex =
        Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|$)")
}
