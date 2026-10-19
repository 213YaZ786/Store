package com.yaz.store.data.catalog

// The list of our apps, read from the 213YaZ786 account at each opening (nothing written in the app). Sources:
// - GitHub's REST endpoints and fields used: list repositories for a user and get a release by tag name
//   (https://docs.github.com/en/rest/repos/repos#list-repositories-for-a-user, https://docs.github.com/en/rest/releases/releases#get-a-release-by-tag-name).
//   Without a token the API gives 60 requests an hour and a 304 still counts, so it serves only the repo list and the notes of a tag;
//   each release is read through "link to the latest release asset"
//   (https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases), see data/net/ReleaseNet.
// - komi-store's GitHub client reads a repo's release and its assets (https://github.com/komi-store/komi-store,
//   core/data/src/commonMain/kotlin/zed/rainxch/core/data/network/*ApiClient.kt); the APK is chosen by architecture as its
//   AssetArchitectureMatcher does (AbiChoice).
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
import android.os.Build
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.core.common.writeTextAtomically
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import com.yaz.store.data.net.ReleaseSource
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
import java.io.File
import java.time.Instant
import java.time.format.DateTimeParseException

/** One app of ours, as app.json of its latest release describes it. */
data class StoreEntry(
    val repo: String,
    /** The tag of the release every file of the entry comes from. */
    val tag: String,
    val packageName: String,
    val label: String,
    /** In the phone's language when the app has it, else English, else empty. */
    val description: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    /** The APK for this phone's ABIs, or null when none of the release's APKs fits: shown, never installed. */
    val apkName: String?,
    /** icon.png of the release when it has one. */
    val iconUrl: String?,
    /**
     * The APK could not be read, so the entry is the repo by its name with Install: [packageName] is a placeholder, [versionCode] is 0 and the
     * full install path (download, sum, inspector, signers) checks the real file. Never true for an entry read from app.json.
     */
    val unread: Boolean = false,
    /** The release has no app.json: package, version and label were read from the APK by ranges (data/sparse). */
    val sparse: Boolean = false
) {
    val apkUrl: String? get() = apkName?.let { ReleaseNet.downloadUrl(repo, tag, it) }
    val sumsUrl: String get() = ReleaseNet.downloadUrl(repo, tag, "SHA256SUMS.txt")
    val pageUrl: String get() = "https://github.com/${ReleaseNet.OWNER}/$repo/releases/tag/$tag"
}

/** What only GitHub's API tells of a release: its text, its date, the size of each file. Read once per tag. */
data class ReleaseDetails(
    /** The release's text, plain, at most [CatalogRules.MAX_NOTES] characters. */
    val notes: String,
    val publishedAt: Instant?,
    val sizes: Map<String, Long>
)

/** Why the list is empty or incomplete. */
sealed interface CatalogError {
    data object Offline : CatalogError
    /** GitHub's limit is reached; [resetAt] is its X-RateLimit-Reset (seconds since 1970) when it said. */
    data class RateLimited(val resetAt: Long?) : CatalogError
    data object Refused : CatalogError
}

// A repo as GitHub's list repositories for a user gives it, name and description (https://docs.github.com/en/rest/repos/repos#list-repositories-for-a-user).
/** A repo of the account and its description on GitHub ("" when it has none). */
data class RepoInfo(val name: String, val description: String)

/** The apps found, and what went wrong on the way, if anything. [listCached]: the repos come from the last opening's list. */
data class CatalogResult(val entries: List<StoreEntry>, val error: CatalogError?, val listCached: Boolean = false)

/** What app.json holds once checked. */
data class AppJson(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String,
    val minSdk: Int,
    val descriptions: Map<String, String>,
    val apks: List<String>
)

@Serializable
private data class AppJsonDto(
    @SerialName("package") val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String,
    val minSdk: Int,
    val description: Map<String, String> = emptyMap(),
    val apks: List<String>
)

@Serializable
private data class RepoDto(
    val name: String,
    val archived: Boolean = false,
    val fork: Boolean = false,
    val owner: OwnerDto? = null,
    /** The repo's "About" text; null when empty on GitHub. */
    val description: String? = null
)

@Serializable
private data class OwnerDto(val login: String = "")

@Serializable
private data class AssetDto(val name: String = "", val size: Long = 0)

@Serializable
private data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val assets: List<AssetDto> = emptyList()
)

@Serializable
private data class StoredDetails(val notes: String, val publishedAt: String? = null, val sizes: Map<String, Long> = emptyMap())

/** The rules a release and its app.json must pass; each one a plain function, tested alone. */
object CatalogRules {
    const val MAX_NOTES = 20_000
    const val MAX_PACKAGE = 255
    const val MAX_LABEL = 60
    const val MAX_DESCRIPTION = 300
    /** The CI writes a handful of locales; a hundred is far above any app of ours. */
    const val MAX_LOCALES = 100
    const val MAX_VERSION_NAME = 100
    /** One APK, or one per ABI: five at most in practice. */
    const val MAX_APKS = 10
    private const val MAX_LOCALE_TAG = 35

    private val json = Json { ignoreUnknownKeys = true }

    // Android's package rule: segments of letters, digits and '_' that begin with a letter, at least two, joined by '.'
    // (FrameworkParsingPackageUtils.validateName with requireSeparator).
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val LOCALE = Regex("[A-Za-z0-9_-]{1,$MAX_LOCALE_TAG}")
    // A release APK's name: GitHub's file name alphabet, 200 at most.
    private val APK = Regex("[A-Za-z0-9._-]{1,200}")
    private val SUM = Regex("[0-9a-fA-F]{64}")

    /** Whether [name] is a package name by Android's rule and at most 255 characters. */
    fun validPackage(name: String): Boolean = name.length <= MAX_PACKAGE && PACKAGE.matches(name)

    /** Whether [name] may be named in `apks`: the file name alphabet, ends in .apk, never in -debug.apk. */
    fun validApk(name: String): Boolean = APK.matches(name) && name.endsWith(".apk") && !name.endsWith("-debug.apk")

    /**
     * app.json checked, or null when anything fails: a missing field, a wrong type, a package that is no package, a text too
     * long, a versionCode that is not positive, no APK listed. Never guessed from what is left.
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
        if (dto.apks.size !in 1..MAX_APKS || !dto.apks.all(::validApk)) return null
        val label = ApkInfoCodec.clean(dto.label, MAX_LABEL)
        val version = ApkInfoCodec.clean(dto.versionName, MAX_VERSION_NAME)
        if (label.isEmpty() || version.isEmpty()) return null
        return AppJson(
            packageName = dto.packageName,
            versionCode = dto.versionCode,
            versionName = version,
            label = label,
            minSdk = dto.minSdk,
            descriptions = dto.description.mapValues { ApkInfoCodec.clean(it.value, MAX_DESCRIPTION) },
            apks = dto.apks.distinct()
        )
    }

    /**
     * The names of the release APKs a SHA256SUMS.txt lists, in its order, without the debug ones: a line is the digest, whitespace, an optional
     * '*' and a name, which may start with "./" (the CI runs sha256sum on "./<file>"); only names [validApk] accepts. The line reading is
     * data/install/StoreChecks.expectedSum's, after GNU coreutils' checksum file format
     * (https://www.gnu.org/software/coreutils/manual/html_node/md5sum-invocation.html).
     */
    fun apkNames(sums: String): List<String> {
        val names = LinkedHashSet<String>()
        for (raw in sums.lineSequence()) {
            val line = raw.trim()
            val cut = line.indexOfFirst { it == ' ' || it == '\t' }
            if (cut != 64 || !SUM.matches(line.substring(0, cut))) continue
            val name = line.substring(cut).trim().removePrefix("*").removePrefix("./")
            if (validApk(name) && names.size < MAX_APKS) names.add(name)
        }
        return names.toList()
    }

    /**
     * The package name an entry gets when its APK cannot be read: unique per repo, never a real package (no app is called "unread"), so nothing on
     * the phone matches it.
     */
    fun placeholderPackage(repo: String): String = "unread." + repo.lowercase().replace(Regex("[^a-z0-9]"), "_")

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

    internal fun parseRepos(text: String): List<String>? = parseRepoList(text)?.map { it.name }

    /**
     * The same repos with their GitHub description (the "description" field of GitHub's list repositories for a user, plain text,
     * https://docs.github.com/en/rest/repos/repos#list-repositories-for-a-user), one line cut to [MAX_DESCRIPTION] characters with control and
     * bidirectional characters removed as app.json's is (ApkInfoCodec.clean); empty when GitHub has none. Nothing is made up.
     */
    internal fun parseRepoList(text: String): List<RepoInfo>? {
        val repos = try {
            json.decodeFromString<List<RepoDto>>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return repos.filter {
            !it.archived && !it.fork && ReleaseNet.validRepo(it.name) && it.owner?.login.equals(ReleaseNet.OWNER, ignoreCase = true)
        }.map { RepoInfo(it.name, ApkInfoCodec.clean(it.description.orEmpty(), MAX_DESCRIPTION)) }
    }

    /** The notes, date and file sizes of the release answered for [tag] (GitHub's get a release by tag name), or null when it is not that release. */
    internal fun parseDetails(text: String, tag: String): ReleaseDetails? {
        val release = try {
            json.decodeFromString<ReleaseDto>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (release.tagName != tag) return null
        return ReleaseDetails(
            notes = plainText(release.body.orEmpty(), MAX_NOTES),
            publishedAt = release.publishedAt?.let(::instant),
            sizes = release.assets.filter { it.size > 0 && it.name.length in 1..255 }.associate { it.name to it.size }
        )
    }

    /** [details] as the text kept on disk, and back: [parseStored] answers null for a text that is not one. */
    internal fun storeDetails(details: ReleaseDetails): String =
        json.encodeToString(StoredDetails(details.notes, details.publishedAt?.toString(), details.sizes))

    internal fun parseStored(text: String): ReleaseDetails? {
        val stored = try {
            json.decodeFromString<StoredDetails>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return ReleaseDetails(plainText(stored.notes, MAX_NOTES), stored.publishedAt?.let(::instant), stored.sizes.filterValues { it > 0 })
    }

    private fun instant(text: String): Instant? = try { Instant.parse(text) } catch (e: DateTimeParseException) { null }
}

// What an APK says by ranges: data/sparse/SparseApk reads a RangeSource into a sparse file, parsed in the isolated inspector with apksig
// (https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/apk/ApkUtils.java).
/** Reads the package, version, label and minSdk of a release APK without downloading it; null when it cannot be read as an app. */
fun interface ApkProbe {
    suspend fun probe(repo: String, tag: String, apkName: String): com.yaz.store.data.sparse.SparseInfo?
}

/** The phone's languages in order, as BCP 47 tags (PackageFacts.device reads them the same way). */
fun phoneLocaleTags(): List<String> {
    val locales = Resources.getSystem().configuration.locales
    return (0 until locales.size()).map { locales[it].toLanguageTag() }
}

/** The phone's ABIs, best first (https://developer.android.com/reference/android/os/Build#SUPPORTED_ABIS). */
fun phoneAbis(): List<String> = Build.SUPPORTED_ABIS.toList()

class Catalog(
    private val source: ReleaseSource,
    /** Where the notes of a tag are kept for good (one file per tag); null keeps none. */
    private val notesDir: File? = null,
    private val locales: () -> List<String> = ::phoneLocaleTags,
    private val abis: () -> List<String> = ::phoneAbis,
    /** Debug builds only (AppModule): entries a test puts beside the live list, see [Seeded]. Empty in the release build. */
    private val seeded: () -> List<StoreEntry> = { emptyList() },
    /** Debug builds only (AppModule): changes what the entries show, see [CatalogOverlay]. Nothing in the release build. */
    private val overlay: CatalogOverlay? = null,
    /** Reads a release APK with no app.json by ranges (data/sparse); null lists such a repo by its name. */
    private val probe: ApkProbe? = null
) {

    private sealed interface Outcome {
        data class Found(val entry: StoreEntry) : Outcome
        data object Skipped : Outcome
        data class Failed(val error: NetError) : Outcome
    }

    /**
     * The repos of the account (from the cached list, and said so, when GitHub's limit is reached or the phone is offline) and the apps
     * of their latest releases; errors are carried, never thrown.
     */
    // The former load, renamed loadLive; load adds the debug seed to it (kotlinx.coroutines withContext,
    // https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html).
    suspend fun load(): CatalogResult {
        val live = loadLive()
        val extra = withContext(Dispatchers.IO) { seeded() }
        if (extra.isEmpty()) return overlaid(live)
        // A seeded entry replaces the live one of the same package; the error of the live read stays, so a test sees both.
        val merged = (live.entries.filter { l -> extra.none { it.packageName == l.packageName } } + extra)
            .sortedWith(compareBy({ it.label.lowercase() }, { it.repo }))
        return overlaid(live.copy(entries = merged))
    }

    // The debug overlay applied last, over the live and the seeded entries; off the main thread as load does it
    // (kotlinx.coroutines withContext, https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-context.html).
    private suspend fun overlaid(result: CatalogResult): CatalogResult {
        val over = overlay ?: return result
        return result.copy(entries = withContext(Dispatchers.IO) { over.apply(result.entries) })
    }

    private suspend fun loadLive(): CatalogResult = withContext(Dispatchers.IO) {
        var listError: NetError? = null
        val listUrl = ReleaseNet.repoListUrl()
        val text = try {
            source.getJson(listUrl)
        } catch (e: NetError) {
            // The list as it was last read serves when GitHub's limit is reached or the phone is offline; the releases themselves
            // are read from github.com, which has no such limit.
            val kept = if (e.kind == NetError.Kind.RATE_LIMITED || e.kind == NetError.Kind.OFFLINE) {
                try { source.cached(listUrl) } catch (n: NetError) { null }
            } else null
            if (kept == null) return@withContext CatalogResult(emptyList(), errorOf(e))
            listError = e
            kept
        }
        val repos = CatalogRules.parseRepoList(text) ?: return@withContext CatalogResult(emptyList(), CatalogError.Refused, listError != null)
        val tags = locales()
        val phoneAbis = abis()
        val gate = Semaphore(MAX_AT_ONCE)
        val outcomes = coroutineScope {
            repos.map { repo -> async { gate.withPermit { entryOf(repo.name, repo.description, tags, phoneAbis) } } }.awaitAll()
        }
        val entries = outcomes.filterIsInstance<Outcome.Found>().map { it.entry }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.repo }))
        val failures = listOfNotNull(listError) + outcomes.filterIsInstance<Outcome.Failed>().map { it.error }
        // The most useful reason first: a limit says when to come back, a lost network says to retry.
        val error = failures.firstOrNull { it.kind == NetError.Kind.RATE_LIMITED }
            ?: failures.firstOrNull { it.kind == NetError.Kind.OFFLINE }
            ?: failures.firstOrNull()
        CatalogResult(entries, error?.let(::errorOf), listCached = listError != null)
    }

    /**
     * The notes, date and sizes of the release [entry] comes from: kept on disk once read, for good (a tag's notes do not change),
     * else asked of GitHub's API once. Null when they cannot be read now. Blocking: off the main thread.
     */
    fun details(entry: StoreEntry): ReleaseDetails? {
        val file = notesDir?.let { File(it, ApkInfoCodec.sha256Hex("${entry.repo}/${entry.tag}".toByteArray()) + ".json") }
        if (file != null && file.isFile && file.length() <= ReleaseNet.MAX_JSON) {
            runCatching { file.readText() }.getOrNull()?.let(CatalogRules::parseStored)?.let { return it }
        }
        val text = try {
            source.getJson(ReleaseNet.tagsUrl(entry.repo, entry.tag))
        } catch (e: NetError) {
            return null
        }
        val details = CatalogRules.parseDetails(text, entry.tag) ?: return null
        if (file != null) runCatching {
            file.parentFile?.mkdirs()
            file.writeTextAtomically(CatalogRules.storeDetails(details))
        }
        return details
    }

    // One repo: the tag of its latest release from the first hop of latest/download/app.json, then app.json of that tag, the APK for this phone
    // among the ones it lists, and icon.png when the release has one. A 404 on app.json is a release made before app.json existed: its APKs are
    // named by SHA256SUMS.txt and read by ranges (fromApk). An app.json that does not pass the rules is no app of ours.
    private suspend fun entryOf(repo: String, about: String, tags: List<String>, phoneAbis: List<String>): Outcome {
        try {
            val tag = source.latestTag(repo)
            val text = try {
                source.getJson(ReleaseNet.downloadUrl(repo, tag, "app.json"))
            } catch (e: NetError) {
                if (e.kind != NetError.Kind.NOT_FOUND) throw e
                return fromApk(repo, tag, about, phoneAbis)
            }
            val app = CatalogRules.parseAppJson(text) ?: return Outcome.Skipped
            val icon = ReleaseNet.downloadUrl(repo, tag, "icon.png")
            return Outcome.Found(
                StoreEntry(
                    repo = repo,
                    tag = tag,
                    packageName = app.packageName,
                    label = app.label,
                    // app.json's description wins; with none for the phone's languages, the repo's own on GitHub; else none.
                    description = CatalogRules.pickDescription(app.descriptions, tags).ifEmpty { about },
                    versionCode = app.versionCode,
                    versionName = app.versionName,
                    minSdk = app.minSdk,
                    apkName = AbiChoice.choose(app.apks, phoneAbis),
                    iconUrl = icon.takeIf { source.exists(it) }
                )
            )
        } catch (e: NetError) {
            // A repo with no release, or a release without app.json, is simply not an app of ours.
            return if (e.kind == NetError.Kind.NOT_FOUND) Outcome.Skipped else Outcome.Failed(e)
        }
    }

    // A release with no app.json: the non-debug APKs named by its SHA256SUMS.txt (a 404 or no APK in it is no app), the one for this phone
    // (komi-store's asset choice, https://github.com/komi-store/komi-store, AssetArchitectureMatcher.kt, through AbiChoice), and what that APK
    // says read by ranges (https://www.rfc-editor.org/rfc/rfc9110#section-14, data/sparse). An APK that cannot be read is the repo by its name with
    // Install, as komi-store lists a repo by its name (https://github.com/komi-store/komi-store); the full install path checks the file.
    // Its description is the repo's own on GitHub, already in the repo list ([about], "" when GitHub has none: no line then).
    private suspend fun fromApk(repo: String, tag: String, about: String, phoneAbis: List<String>): Outcome {
        val sums = try {
            source.getJson(ReleaseNet.downloadUrl(repo, tag, "SHA256SUMS.txt"))
        } catch (e: NetError) {
            if (e.kind == NetError.Kind.NOT_FOUND) return Outcome.Skipped
            throw e
        }
        val names = CatalogRules.apkNames(sums)
        if (names.isEmpty()) return Outcome.Skipped
        val chosen = AbiChoice.choose(names, phoneAbis)
        val info = chosen?.let { probe?.probe(repo, tag, it) }
        val icon = ReleaseNet.downloadUrl(repo, tag, "icon.png").takeIf { source.exists(it) }
        val entry = if (info != null) {
            StoreEntry(
                repo = repo, tag = tag, packageName = info.packageName, label = info.label ?: repo, description = about,
                versionCode = info.versionCode, versionName = info.versionName.ifEmpty { tag.removePrefix("v") }, minSdk = info.minSdk,
                apkName = chosen, iconUrl = icon, sparse = true
            )
        } else {
            StoreEntry(
                repo = repo, tag = tag, packageName = CatalogRules.placeholderPackage(repo), label = repo, description = about,
                versionCode = 0, versionName = tag.removePrefix("v"), minSdk = 1,
                apkName = chosen, iconUrl = icon, unread = true, sparse = true
            )
        }
        return Outcome.Found(entry)
    }

    private fun errorOf(e: NetError): CatalogError = when (e.kind) {
        NetError.Kind.OFFLINE, NetError.Kind.METERED -> CatalogError.Offline
        NetError.Kind.RATE_LIMITED -> CatalogError.RateLimited(e.resetAt)
        NetError.Kind.NOT_FOUND, NetError.Kind.REFUSED -> CatalogError.Refused
    }

    private companion object {
        const val MAX_AT_ONCE = 4
    }
}
