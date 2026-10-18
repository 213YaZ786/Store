package com.yaz.store.data.catalog

// What the phone knows of each app of the list, and the state that follows. Sources:
// - the signer of an installed app: PackageManager.getPackageInfo with GET_SIGNING_CERTIFICATES, SigningInfo.apkContentsSigners
//   for several signers and signingCertificateHistory (the last is the current certificate) for one, hashed with SHA-256, as komi-store's
//   SigningFingerprint does (https://github.com/komi-store/komi-store, core/data/src/androidMain/kotlin/zed/rainxch/core/data/services/SigningFingerprint.kt);
//   the digests are lower case hex, as OurKeys and ApkInfoCodec.sha256Hex write them.
// - longVersionCode (https://developer.android.com/reference/android/content/pm/PackageInfo#getLongVersionCode()).
// - who installed it: PackageManager.getInstallSourceInfo, installingPackageName (the installer of record) and, from Android 14,
//   updateOwnerPackageName (https://developer.android.com/reference/android/content/pm/InstallSourceInfo).
// - the versionCode compare and the certificate digests as GrapheneOS' App Store takes them: an update is offered only for a higher
//   versionCode, and a package is trusted only when its certificate digests are among the valid ones
//   (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/InstallTask.kt, findPackage, and Repo.kt).
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.data.trust.OurKeys

/** Where an app of the list stands on this phone. */
enum class AppState {
    NOT_INSTALLED,
    UP_TO_DATE,
    /** The release's versionCode is above the installed one. */
    UPDATE,
    /** Installed and signed by ours, but Store is neither its installer of record nor its update owner. */
    HAND_OVER,
    /** Installed but signed by none of our keys: Store never touches it. */
    FOREIGN,
    /** The release needs a newer Android than this phone's. */
    TOO_NEW,
    /** None of the release's APKs fits this phone's ABIs: shown, never installed. */
    NO_APK
}

/** What the phone says of an installed app. [signers] are SHA-256 digests of its current certificates, lower case hex. */
data class InstalledFacts(
    val versionCode: Long,
    val versionName: String,
    val signers: Set<String>,
    val installer: String?,
    val updateOwner: String?
)

/**
 * An entry with its state and what is installed, for the screens. [handOver]: installed, signed by ours, and Store is neither the
 * installer of record nor the update owner (https://developer.android.com/reference/android/content/pm/InstallSourceInfo), so
 * Android asks once before Store's update goes in, whatever the state says.
 */
data class AppStatus(val entry: StoreEntry, val state: AppState, val installed: InstalledFacts?, val handOver: Boolean = false)

object Installed {

    /**
     * The state of [entry] given what the phone says ([facts], null when not installed), the phone's Android level [sdk] and
     * Store's own package [store]. In this order: not installed, then too new or not; installed by a foreign key (never
     * touched); a higher versionCode (an update, or too new for this phone); at last whether Store holds the app.
     */
    fun stateOf(entry: StoreEntry, facts: InstalledFacts?, sdk: Int, store: String): AppState {
        if (facts == null) return when {
            entry.minSdk > sdk -> AppState.TOO_NEW
            entry.apkName == null -> AppState.NO_APK
            else -> AppState.NOT_INSTALLED
        }
        if (!OurKeys.allOurs(facts.signers)) return AppState.FOREIGN
        if (entry.versionCode > facts.versionCode) return when {
            entry.minSdk > sdk -> AppState.TOO_NEW
            entry.apkName == null -> AppState.NO_APK
            else -> AppState.UPDATE
        }
        return if (held(facts, store)) AppState.UP_TO_DATE else AppState.HAND_OVER
    }

    // The installer of record and the update owner, as InstallSourceInfo gives them (https://developer.android.com/reference/android/content/pm/InstallSourceInfo).
    private fun held(facts: InstalledFacts, store: String): Boolean = facts.installer == store || facts.updateOwner == store

    /** True when the app is installed, signed by ours, and Store does not hold it (see [AppStatus.handOver]). */
    fun handOver(facts: InstalledFacts?, store: String): Boolean = facts != null && OurKeys.allOurs(facts.signers) && !held(facts, store)

    /** Every entry with its state, read from the phone now. */
    fun statuses(context: Context, entries: List<StoreEntry>): List<AppStatus> = entries.map {
        val facts = read(context, it.packageName)
        AppStatus(it, stateOf(it, facts, Build.VERSION.SDK_INT, context.packageName), facts, handOver(facts, context.packageName))
    }

    /** The facts of [packageName], or null when it is not installed (or not visible to Store). */
    fun read(context: Context, packageName: String): InstalledFacts? {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val signing = info.signingInfo
        val certificates = when {
            signing == null -> emptyList()
            signing.hasMultipleSigners() -> signing.apkContentsSigners.orEmpty().toList()
            else -> listOfNotNull(signing.signingCertificateHistory?.lastOrNull())
        }
        val source = try {
            pm.getInstallSourceInfo(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return InstalledFacts(
            versionCode = info.longVersionCode,
            versionName = ApkInfoCodec.clean(info.versionName.orEmpty(), CatalogRules.MAX_VERSION_NAME),
            signers = certificates.map { ApkInfoCodec.sha256Hex(it.toByteArray()) }.toSet(),
            installer = source?.installingPackageName,
            updateOwner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) source?.updateOwnerPackageName else null
        )
    }
}
