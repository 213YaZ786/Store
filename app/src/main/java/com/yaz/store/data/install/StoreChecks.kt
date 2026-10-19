package com.yaz.store.data.install

// What is checked about a downloaded APK, in this order, before Android is asked to install it. Sources:
// - The order of the update kit's Updates.stage (Modules shared/update, read in Calculator's app/src/main/java/com/yaz/calculator/core/update/Updates.kt):
//   the file comes from our repo, its SHA-256 equals the line of the release's SHA256SUMS, and only then is it opened to see that it is this
//   app, signed alike (sameApp). A file whose sum is wrong is never opened.
// - GrapheneOS' App Store checks the package, the version and the certificate digests of what it downloaded against its own catalogue before the
//   session is committed (InstallTask.kt, findPackage, https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/core/InstallTask.kt).
// - The line of a SHA256SUMS file: GNU coreutils' checksum file format, the digest, a space, a mode character ('*' binary, ' ' text), the file name
//   (https://www.gnu.org/software/coreutils/manual/html_node/md5sum-invocation.html, "--check" input format).
// - minSdkVersion above the phone's SDK_INT is refused by the package installer (https://developer.android.com/guide/topics/manifest/uses-sdk-element).
import com.yaz.store.core.apk.ApkInfo
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.trust.OurKeys

object StoreChecks {

    private val SUM = Regex("[0-9a-fA-F]{64}")

    /**
     * The digest [sums] gives to the file named exactly [apkName]: a line of a SHA256SUMS file is the digest, whitespace, an optional '*',
     * the name. A name that only ends like [apkName], or a line with another digest shape, never counts. Lower case, or null.
     */
    fun expectedSum(sums: String, apkName: String): String? {
        for (raw in sums.lineSequence()) {
            val line = raw.trim()
            val cut = line.indexOfFirst { it == ' ' || it == '\t' }
            if (cut != 64) continue
            val digest = line.substring(0, cut)
            // The CI runs sha256sum on "./<file>", so the real lines read "<digest>  ./<name>"; a path with more is another file.
            val name = line.substring(cut).trim().removePrefix("*").removePrefix("./")
            if (SUM.matches(digest) && name == apkName) return digest.lowercase()
        }
        return null
    }

    /**
     * Why [info], the inspector's reading of the downloaded file, may not be installed for [entry], or null when it may. In this order:
     * signed at all, the package, the versionCode, every signer one of ours, the installed app's signers ([installedSigners], null when not
     * installed) and the phone's Android level [sdk].
     */
    fun refusal(entry: StoreEntry, info: ApkInfo, installedSigners: Set<String>?, sdk: Int): String? {
        if (info.splitName != null) return "The file is a part of an app, not an app."
        if (!info.verified || info.signers.isEmpty()) return "The file is not signed, or its signature is broken."
        // An entry whose APK could not be read by ranges (Catalog.fromApk) has no package or version to compare: the file's own are used.
        if (!entry.unread && info.packageName != entry.packageName) return "The file is another app than the release says."
        if (!entry.unread && info.versionCode != entry.versionCode) return "The file is another version than the release says."
        if (!OurKeys.allOurs(info.signers.toSet())) return "The file is signed with a key that is not ours."
        if (installedSigners != null && installedSigners != info.signers.map { it.lowercase() }.toSet()) {
            return "The file is signed differently from the app on the phone."
        }
        if (info.minSdk > sdk) return "This app needs Android API level ${info.minSdk} or newer. This phone runs API level $sdk."
        return null
    }

    const val SUM_MISSING = "The release's checksum file does not list this APK."
    const val SUM_WRONG = "The downloaded file does not match its checksum. It was deleted."
    const val UNREADABLE = "The file could not be read as an app. It was deleted."
}
