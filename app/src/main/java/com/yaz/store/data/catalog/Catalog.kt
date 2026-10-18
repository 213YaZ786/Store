package com.yaz.store.data.catalog

// The list of our apps, read from the 213YaZ786 account at each opening (nothing written in the app). Sources:
// - komi-store's GitHub client reads a repo's latest release with its assets and body, and chooses the APK among the assets
//   (https://github.com/komi-store/komi-store, core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/*ApiClient.kt and
//   feature/*/AndroidInstaller.choosePrimaryAsset); here the choice is stricter: exactly one release APK, app.json and SHA256SUMS.txt.
// - GitHub's REST endpoints and fields used: list repositories for a user and get the latest release
//   (https://docs.github.com/en/rest/repos/repos#list-repositories-for-a-user, https://docs.github.com/en/rest/releases/releases#get-the-latest-release).
// - app.json is read with kotlinx.serialization's Json, ignoring unknown keys
//   (https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md#ignoring-unknown-keys); its fields are the ones the shared CI
//   writes (Modules shared/base/root/.github/workflows/build.yml.tpl, "Write app.json and the icon").
// - A package name by Android's own rule: AOSP FrameworkParsingPackageUtils.validateName
//   (https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/parsing/FrameworkParsingPackageUtils.java).
// - The description by the phone's languages, fastlane's locale folders (en-US, fr-FR) as GrapheneOS' App Store chooses a language
//   from the phone's locale list (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/Repo.kt) and
//   Android's LocaleList (https://developer.android.com/reference/android/os/LocaleList).
// - At most four releases at a time: kotlinx.coroutines' Semaphore
//   (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.sync/-semaphore/).
import android.content.res.Resources
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.data.net.JsonSource
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

/** One app of ours, as its latest release describes it. */
data class StoreEntry(
    val repo: String,
    val packageName: String,
    val label: String,
    /** In the phone's language when the app has it, else English, else empty. */
    val description: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sumsUrl: String,
    val iconUrl: String?,
    /** The release's text, plain, at most [CatalogRules.MAX_NOTES] characters. */
    val notes: String,
    val publishedAt: Instant?,
    val pageUrl: String
)

/** Why the list is empty or incomplete. */
sealed interface CatalogError {
    data object Offline : CatalogError
    /** GitHub's limit is reached; [resetAt] is its X-RateLimit-Reset (seconds since 1970) when it said. */
    data class RateLimited(val resetAt: Long?) : CatalogError
    data object Refused : CatalogError
}

/** The apps found, and what went wrong on the way, if anything. */
data class CatalogResult(val entries: List<StoreEntry>, val error: CatalogError?)

/** What app.json holds once checked. */
data class AppJson(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String,
    val minSdk: Int,
    val descriptions: Map<String, String>
)

/** The assets of a release a store needs. */
data class ReleaseAsset(val name: String, val url: String, val size: Long)

data class ChosenAssets(val apk: ReleaseAsset, val sums: ReleaseAsset, val appJson: ReleaseAsset, val icon: ReleaseAsset?)

@Serializable
private data class AppJsonDto(
    @SerialName("package") val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String,
    val minSdk: Int,
    val description: Map<String, String> = emptyMap()
)

@Serializable
private data class RepoDto(val name: String, val archived: Boolean = false, val fork: Boolean = false, val owner: OwnerDto? = null)

@Serializable
private data class OwnerDto(val login: String = "")

@Serializable
private data class AssetDto(val name: String = "", @SerialName("browser_download_url") val url: String = "", val size: Long = 0)

@Serializable
private data class ReleaseDto(
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<AssetDto> = emptyList()
)

/** The rules a release and its app.json must pass; each one a plain function, tested alone. */
object CatalogRules {
    const val MAX_NOTES = 20_000
    const val MAX_PACKAGE = 255
    const val MAX_LABEL = 60
    const val MAX_DESCRIPTION = 300
    /** The CI writes a handful of locales; a hundred is far above any app of ours. */
    const val MAX_LOCALES = 100
    const val MAX_VERSION_NAME = 100
    private const val MAX_LOCALE_TAG = 35

    private val json = Json { ignoreUnknownKeys = true }

    // Android's package rule: segments of letters, digits and '_' that begin with a letter, at least two, joined by '.'
    // (FrameworkParsingPackageUtils.validateName with requireSeparator).
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val LOCALE = Regex("[A-Za-z0-9_-]{1,$MAX_LOCALE_TAG}")

    /** Whether [name] is a package name by Android's rule and at most 255 characters. */
    fun validPackage(name: String): Boolean = name.length <= MAX_PACKAGE && PACKAGE.matches(name)

    /**
     * app.json checked, or null when anything fails: a missing field, a wrong type, a package that is no package, a text too
     * long, a versionCode that is not positive. Never guessed from what is left.
     */
    fun parseAppJson(text: String): AppJson? {
        val dto = try {
            // The numbers must be JSON numbers: kotlinx decodes a quoted "14" as a Long too
            // (JsonPrimitive.isString, https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-primitive/is-string.html).
            val tree = json.parseToJsonElement(text) as? JsonObject ?: return null
            if (listOf("versionCode", "minSdk").any { (tree[it] as? JsonPrimitive)?.isString != false }) return null
            json.decodeFromString<AppJsonDto>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (!validPackage(dto.packageName)) return null
        if (dto.versionCode <= 0 || dto.minSdk < 1 || dto.minSdk > 1000) return null
        if (dto.label.length > MAX_LABEL || dto.versionName.length > MAX_VERSION_NAME) return null
        if (dto.description.size > MAX_LOCALES) return null
        if (dto.description.any { (tag, text) -> !LOCALE.matches(tag) || text.length > MAX_DESCRIPTION }) return null
        val label = ApkInfoCodec.clean(dto.label, MAX_LABEL)
        val version = ApkInfoCodec.clean(dto.versionName, MAX_VERSION_NAME)
        if (label.isEmpty() || version.isEmpty()) return null
        return AppJson(
            packageName = dto.packageName,
            versionCode = dto.versionCode,
            versionName = version,
            label = label,
            minSdk = dto.minSdk,
            descriptions = dto.description.mapValues { ApkInfoCodec.clean(it.value, MAX_DESCRIPTION) }
        )
    }

    /**
     * The assets of a release, or null when it is not one of our apps: app.json, SHA256SUMS.txt and exactly one `.apk`
     * whose name does not end with `-debug.apk`. Two release APKs are not guessed between. The icon is optional.
     * komi-store's AndroidInstaller.choosePrimaryAsset chooses among several APKs; here there is only ever one.
     */
    fun chooseAssets(assets: List<ReleaseAsset>): ChosenAssets? {
        val apks = assets.filter { it.name.endsWith(".apk") && !it.name.endsWith("-debug.apk") }
        if (apks.size != 1) return null
        val sums = assets.firstOrNull { it.name == "SHA256SUMS.txt" } ?: return null
        val info = assets.firstOrNull { it.name == "app.json" } ?: return null
        return ChosenAssets(apks[0], sums, info, assets.firstOrNull { it.name == "icon.png" })
    }

    /**
     * The description for the phone's languages, [tags] in the phone's order (BCP 47, `fr-FR`): for each, the exact tag,
     * then the same language (`fr` or any `fr-*`); then `en-US`; else empty. Tags compare without case and with '_' as '-'.
     * The way GrapheneOS' App Store walks the phone's locale list.
     */
    fun pickDescription(descriptions: Map<String, String>, tags: List<String>): String {
        fun norm(tag: String) = tag.replace('_', '-').lowercase()
        val keys = descriptions.keys.sorted().associateBy { norm(it) }
        for (tag in tags) {
            val wanted = norm(tag)
            keys[wanted]?.let { return descriptions.getValue(it) }
            val language = wanted.substringBefore('-')
            keys[language]?.let { return descriptions.getValue(it) }
            keys.entries.firstOrNull { it.key.substringBefore('-') == language }?.let { return descriptions.getValue(it.value) }
        }
        return keys["en-us"]?.let { descriptions.getValue(it) } ?: ""
    }

    /**
     * A release's text as plain text: line ends as '\n', control characters (but '\n' and '\t') and the bidirectional overrides
     * dropped, cut to [max] characters without splitting a pair. The same filter as ApkInfoCodec.clean (this app's install kit,
     * from App Manager), keeping the line ends a body needs.
     */
    fun plainText(text: String, max: Int): String {
        val source = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        val out = StringBuilder()
        var count = 0
        var i = 0
        while (i < source.length && count < max) {
            val cp = source.codePointAt(i)
            i += Character.charCount(cp)
            val bidi = cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C
            if (bidi || (Character.isISOControl(cp) && cp != '\n'.code && cp != '\t'.code)) continue
            out.appendCodePoint(cp)
            count++
        }
        return out.toString().trim()
    }

    // GitHub's release page of a repo only; else the latest-release page (the kit's Updates.latest keeps only its own page).
    internal fun pageOf(repo: String, htmlUrl: String): String =
        htmlUrl.takeIf { it.startsWith("https://github.com/${ReleaseNet.OWNER}/$repo/releases/") && ReleaseNet.validRepo(repo) && !it.any { c -> c.isISOControl() || c == ' ' } }
            ?: "https://github.com/${ReleaseNet.OWNER}/$repo/releases/latest"

    internal fun parseRepos(text: String): List<String>? {
        val repos = try {
            json.decodeFromString<List<RepoDto>>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return repos.filter {
            !it.archived && !it.fork && ReleaseNet.validRepo(it.name) && it.owner?.login.equals(ReleaseNet.OWNER, ignoreCase = true)
        }.map { it.name }
    }

    internal fun parseRelease(text: String): ReleaseDtoView? {
        val release = try {
            json.decodeFromString<ReleaseDto>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return ReleaseDtoView(
            release.htmlUrl,
            release.publishedAt,
            release.body.orEmpty(),
            release.assets.map { ReleaseAsset(it.name, it.url, it.size) }
        )
    }
}

/** A release as GitHub's JSON gave it, before any check. */
internal data class ReleaseDtoView(val htmlUrl: String, val publishedAt: String?, val body: String, val assets: List<ReleaseAsset>)

/** The phone's languages in order, as BCP 47 tags (PackageFacts.device reads them the same way). */
fun phoneLocaleTags(): List<String> {
    val locales = Resources.getSystem().configuration.locales
    return (0 until locales.size()).map { locales[it].toLanguageTag() }
}

class Catalog(
    private val source: JsonSource,
    private val locales: () -> List<String> = ::phoneLocaleTags
) {

    private sealed interface Outcome {
        data class Found(val entry: StoreEntry) : Outcome
        data object Skipped : Outcome
        data class Failed(val error: NetError) : Outcome
    }

    /** The repos of the account and the apps of their latest releases; errors are carried, never thrown. */
    suspend fun load(): CatalogResult = withContext(Dispatchers.IO) {
        val repos = try {
            CatalogRules.parseRepos(source.getJson(ReleaseNet.repoListUrl()))
                ?: return@withContext CatalogResult(emptyList(), CatalogError.Refused)
        } catch (e: NetError) {
            return@withContext CatalogResult(emptyList(), errorOf(e))
        }
        val tags = locales()
        val gate = Semaphore(MAX_AT_ONCE)
        val outcomes = coroutineScope {
            repos.map { repo -> async { gate.withPermit { entryOf(repo, tags) } } }.awaitAll()
        }
        val entries = outcomes.filterIsInstance<Outcome.Found>().map { it.entry }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.repo }))
        val failures = outcomes.filterIsInstance<Outcome.Failed>().map { it.error }
        // The most useful reason first: a limit says when to come back, a lost network says to retry.
        val error = failures.firstOrNull { it.kind == NetError.Kind.RATE_LIMITED }
            ?: failures.firstOrNull { it.kind == NetError.Kind.OFFLINE }
            ?: failures.firstOrNull()
        CatalogResult(entries, error?.let(::errorOf))
    }

    // One repo: its latest release, kept only when it is one of our apps.
    private fun entryOf(repo: String, tags: List<String>): Outcome {
        try {
            val release = CatalogRules.parseRelease(source.getJson(ReleaseNet.latestUrl(repo))) ?: return Outcome.Skipped
            val assets = release.assets.filter { ReleaseNet.isReleaseFileOf(repo, it.url) }
            val chosen = CatalogRules.chooseAssets(assets) ?: return Outcome.Skipped
            val app = CatalogRules.parseAppJson(source.getJson(chosen.appJson.url)) ?: return Outcome.Skipped
            return Outcome.Found(
                StoreEntry(
                    repo = repo,
                    packageName = app.packageName,
                    label = app.label,
                    description = CatalogRules.pickDescription(app.descriptions, tags),
                    versionCode = app.versionCode,
                    versionName = app.versionName,
                    minSdk = app.minSdk,
                    apkName = chosen.apk.name,
                    apkUrl = chosen.apk.url,
                    apkSize = chosen.apk.size,
                    sumsUrl = chosen.sums.url,
                    iconUrl = chosen.icon?.url,
                    notes = CatalogRules.plainText(release.body, CatalogRules.MAX_NOTES),
                    publishedAt = release.publishedAt?.let {
                        try { Instant.parse(it) } catch (e: DateTimeParseException) { null }
                    },
                    pageUrl = CatalogRules.pageOf(repo, release.htmlUrl)
                )
            )
        } catch (e: NetError) {
            // A repo with no release, or a release without the file, is simply not an app of ours.
            return if (e.kind == NetError.Kind.NOT_FOUND) Outcome.Skipped else Outcome.Failed(e)
        }
    }

    private fun errorOf(e: NetError): CatalogError = when (e.kind) {
        NetError.Kind.OFFLINE -> CatalogError.Offline
        NetError.Kind.RATE_LIMITED -> CatalogError.RateLimited(e.resetAt)
        NetError.Kind.NOT_FOUND, NetError.Kind.REFUSED -> CatalogError.Refused
    }

    private companion object {
        const val MAX_AT_ONCE = 4
    }
}
