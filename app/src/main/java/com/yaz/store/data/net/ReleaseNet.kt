package com.yaz.store.data.net

// Store's only door to the network. Ported from the update kit (Modules shared/update, Updates.kt.tpl), as Calculator's
// copy has it (https://github.com/213YaZ786/Calculator, app/src/main/java/com/yaz/calculator/core/update/Updates.kt):
// open (every redirect read by hand, HTTPS to GitHub's hosts only, three hops), checked, get and download (size caps),
// sha256. The kit's open is itself ported from Mushaf's Net.open, after
// https://developer.android.com/reference/java/net/HttpURLConnection#setInstanceFollowRedirects(boolean) and
// https://developer.android.com/privacy-and-security/risks/cleartext.
// Added here: a path allow-list (only our account's repo list, release notes, and release files), an ETag cache of the repo list
// (https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate;
// without a token a 304 still counts against the 60 requests an hour, measured 2026-10-18, so the cache saves the download only),
// GitHub's rate-limit answers
// (https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api#exceeding-the-rate-limit), and the release files
// reached without the API through "link to the latest release asset"
// (https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases): a 302 from
// releases/latest/download/<asset> to releases/download/<tag>/<asset>, which carries no rate limit.
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.core.common.writeTextAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.security.MessageDigest

/** Why a request failed: no network, GitHub's rate limit, an address or an answer refused, a missing page. */
class NetError(val kind: Kind, val resetAt: Long? = null, message: String) : Exception(message) {
    /** [METERED]: the person asked for Wi-Fi only and the phone's network is metered; nothing was asked of GitHub (StoreBackend). */
    enum class Kind { OFFLINE, RATE_LIMITED, NOT_FOUND, REFUSED, METERED }
}

/** Where the catalog reads from: the network, or a fake in a test. Every method throws [NetError]. */
interface ReleaseSource {
    /** The text answered at [url]. */
    fun getJson(url: String): String

    /** The last text kept for [url] (the repo list), or null. */
    fun cached(url: String): String?

    /** The tag of the latest release of [repo], from the first hop of releases/latest/download/app.json. */
    fun latestTag(repo: String): String

    /** Whether the release file at [url] exists, from the first answer of github.com alone (302 or 404). */
    fun exists(url: String): Boolean
}

class ReleaseNet(cacheDir: File) : ReleaseSource {

    private val cache = File(cacheDir, "net")

    // The kit's get, with the ETag cache of GitHub's conditional requests (the repo list only).
    override fun getJson(url: String): String {
        val start = parse(url)
        val key = ApkInfoCodec.sha256Hex(url.toByteArray())
        val body = File(cache, "$key.json")
        val tag = File(cache, "$key.etag")
        val cacheable = url == repoListUrl()
        // Both files or none: a body without its tag, or a tag without its body, is not used.
        val cachedBody = if (cacheable) readCache(body) else null
        val cachedTag = if (cachedBody != null) runCatching { tag.readText() }.getOrNull()?.takeIf(::plainHeader) else null
        try {
            val conn = open(start, cachedTag)
                ?: return cachedBody ?: throw NetError(NetError.Kind.REFUSED, message = "304 without a cache")
            try {
                val text = conn.inputStream.use { readCapped(it, MAX_JSON) }.decodeToString()
                val fresh = conn.getHeaderField("ETag")
                if (cacheable && fresh != null && plainHeader(fresh)) {
                    // The body first: a crash between the two leaves a tag that no longer matches, never the reverse.
                    // A cache that cannot be written costs only the next request's savings.
                    runCatching {
                        cache.mkdirs()
                        body.writeTextAtomically(text)
                        tag.writeTextAtomically(fresh)
                    }
                }
                return text
            } finally {
                conn.disconnect()
            }
        } catch (e: NetError) {
            throw e
        } catch (e: IOException) {
            throw NetError(NetError.Kind.OFFLINE, message = "offline")
        }
    }

    // What an offline or rate limited opening shows: the repo list as it was last read, with its tag (kept by getJson).
    override fun cached(url: String): String? {
        if (url != repoListUrl()) return null
        val key = ApkInfoCodec.sha256Hex(url.toByteArray())
        return readCache(File(cache, "$key.json"))
    }

    private fun readCache(body: File): String? =
        if (body.isFile && body.length() <= MAX_JSON) runCatching { body.readText() }.getOrNull() else null

    // The first hop of "link to the latest release asset" (docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases):
    // a 302 whose Location is exactly this repo's releases/download/<tag>/app.json. The tag is kept; every other file is then read
    // at releases/download/<tag>/<name>, so they all come from one release. Nothing after the first hop is followed here.
    override fun latestTag(repo: String): String {
        if (!validRepo(repo)) throw NetError(NetError.Kind.REFUSED, message = "bad repo")
        val start = parse(latestAppJsonUrl(repo))
        try {
            val conn = request(start, "HEAD", null)
            try {
                return when (val code = conn.responseCode) {
                    302 -> tagOfLocation(repo, conn.getHeaderField("Location"))
                        ?: throw NetError(NetError.Kind.REFUSED, message = "latest redirect refused")
                    404 -> throw NetError(NetError.Kind.NOT_FOUND, message = "http 404")
                    else -> throw failure(code, conn)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: NetError) {
            throw e
        } catch (e: IOException) {
            throw NetError(NetError.Kind.OFFLINE, message = "offline")
        }
    }

    // A release file exists when github.com answers 302 (to where GitHub keeps it) and not when it answers 404. The signed address
    // it leads to is never asked for here.
    override fun exists(url: String): Boolean {
        val start = parse(url)
        if (start.host.lowercase() != "github.com") throw NetError(NetError.Kind.REFUSED, message = "not a release file")
        try {
            val conn = request(start, "HEAD", null)
            try {
                return when (val code = conn.responseCode) {
                    302 -> true
                    404 -> false
                    else -> throw failure(code, conn)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: NetError) {
            throw e
        } catch (e: IOException) {
            throw NetError(NetError.Kind.OFFLINE, message = "offline")
        }
    }

    // A small release file as bytes (icon.png), after the kit's get above: the same open (every redirect checked, allow-listed address)
    // and its readCapped, with a cap of its own (https://developer.android.com/reference/java/net/HttpURLConnection).
    fun getBytes(url: String, max: Long): ByteArray {
        val start = parse(url)
        try {
            val conn = open(start, null) ?: throw NetError(NetError.Kind.REFUSED, message = "304 without a cache")
            try {
                if (conn.contentLengthLong > max) throw NetError(NetError.Kind.REFUSED, message = "too large")
                return conn.inputStream.use { readCapped(it, max) }
            } finally {
                conn.disconnect()
            }
        } catch (e: NetError) {
            throw e
        } catch (e: IOException) {
            throw NetError(NetError.Kind.OFFLINE, message = "offline")
        }
    }

    // The kit's download, with its 200 MiB cap.
    // [beforeRead] runs before each read of the body: a caller that is cancelled throws from it (kotlinx.coroutines' ensureActive),
    // so a download stops at the next buffer and the connection is closed by the finally below.
    fun download(url: String, into: File, onProgress: (Float) -> Unit, beforeRead: () -> Unit = {}) {
        val start = parse(url)
        try {
            val conn = open(start, null) ?: throw NetError(NetError.Kind.REFUSED, message = "304 on a download")
            try {
                val total = conn.contentLengthLong
                if (total > MAX_APK) throw NetError(NetError.Kind.REFUSED, message = "too large")
                conn.inputStream.use { input ->
                    into.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        var shown = -1
                        while (true) {
                            beforeRead()
                            val n = input.read(buffer)
                            if (n < 0) break
                            read += n
                            if (read > MAX_APK) throw NetError(NetError.Kind.REFUSED, message = "too large")
                            out.write(buffer, 0, n)
                            // Once per percent, not once per buffer.
                            val percent = if (total > 0) (read * 100 / total).toInt() else -1
                            if (percent != shown) {
                                shown = percent
                                onProgress(if (total > 0) read.toFloat() / total else 0f)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: NetError) {
            throw e
        } catch (e: IOException) {
            throw NetError(NetError.Kind.OFFLINE, message = "offline")
        }
    }

    // One request with the kit's settings, redirects never followed by HttpURLConnection.
    private fun request(url: URL, method: String, etag: String?): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Store")
            setRequestProperty("Accept", "application/vnd.github+json, */*")
            if (etag != null) setRequestProperty("If-None-Match", etag)
        }

    /**
     * Opens [start] once it answers 200, or null on a 304. Redirects are never left to HttpURLConnection: each hop is
     * read here and must be HTTPS to one of GitHub's hosts before it is followed, three at most (the kit's open).
     * The tag goes only to the first address, never to where a redirect leads.
     */
    private fun open(start: URL, etag: String?): HttpURLConnection? {
        var url = start
        var hops = 0
        while (true) {
            val conn = request(url, "GET", if (hops == 0) etag else null)
            when (val code = conn.responseCode) {
                200 -> return conn
                304 -> {
                    conn.disconnect()
                    return null
                }
                301, 302, 303, 307, 308 -> {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (hops++ >= MAX_REDIRECTS || location.isNullOrBlank()) {
                        throw NetError(NetError.Kind.REFUSED, message = "redirect refused")
                    }
                    url = checked(try { URL(url, location) } catch (e: MalformedURLException) {
                        throw NetError(NetError.Kind.REFUSED, message = "bad redirect")
                    })
                }
                else -> {
                    val failure = failure(code, conn)
                    conn.disconnect()
                    throw failure
                }
            }
        }
    }

    companion object {
        const val OWNER = "213YaZ786"
        private const val API_HOST = "api.github.com"
        private const val PAGES_HOST = "github.com"
        private const val TIMEOUT_MS = 15_000
        /** Far above any release of these apps, to stop an endless download (the kit's MAX_APK). */
        const val MAX_APK = 200L * 1024 * 1024
        /** The release list is a few kilobytes: read no more than this (the kit's MAX_JSON, after Signal-Android OkHttpUtil.readAsBytes). */
        const val MAX_JSON = 1L shl 20
        private const val MAX_REDIRECTS = 3

        /** The list of repos and the files: GitHub's API, its pages and where it keeps release files (the kit's GITHUB). */
        internal val GITHUB = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        /** The query of the one repo list we ask for. */
        const val REPO_LIST_QUERY = "per_page=100&type=owner"

        // A repo name by GitHub's own rule (letters, digits, '.', '_', '-', 100 at most); "." and ".." are no name.
        private val REPO = Regex("[A-Za-z0-9._-]{1,100}")
        // A tag in a release address: the same alphabet, plus '+' (a tag such as v1.2.3+4), 100 at most.
        private val TAG = Regex("[A-Za-z0-9._+-]{1,100}")
        // A file name in a release address.
        private val FILE = Regex("[A-Za-z0-9._+-]{1,255}")

        /** A repo name by GitHub's rule: https://docs.github.com/en/rest/repos/repos#create-a-repository-for-the-authenticated-user (name). */
        fun validRepo(name: String): Boolean = REPO.matches(name) && name != "." && name != ".."

        /** A tag as it may stand in an address: no slash, no percent sign, no dots segment. */
        fun validTag(name: String): Boolean = TAG.matches(name) && name != "." && name != ".."

        private fun validFile(name: String): Boolean = FILE.matches(name) && name != "." && name != ".."

        /** The address of the repo list of our account. */
        fun repoListUrl(): String = "https://$API_HOST/users/$OWNER/repos?$REPO_LIST_QUERY"

        /** The address of the notes of the release tagged [tag] of [repo]. */
        fun tagsUrl(repo: String, tag: String): String = "https://$API_HOST/repos/$OWNER/$repo/releases/tags/$tag"

        /** The address that redirects to the app.json of the latest release of [repo]. */
        fun latestAppJsonUrl(repo: String): String = "https://$PAGES_HOST/$OWNER/$repo/releases/latest/download/app.json"

        /** The address of the file [name] of the release tagged [tag] of [repo]. */
        fun downloadUrl(repo: String, tag: String, name: String): String =
            "https://$PAGES_HOST/$OWNER/$repo/releases/download/$tag/$name"

        /**
         * The tag in [location], the redirect of releases/latest/download/app.json of [repo]: exactly
         * https://github.com/<owner>/<same repo>/releases/download/<tag>/app.json, else null.
         */
        fun tagOfLocation(repo: String, location: String?): String? {
            if (location == null || !validRepo(repo)) return null
            val prefix = "https://$PAGES_HOST/$OWNER/$repo/releases/download/"
            if (!location.startsWith(prefix) || !location.endsWith("/app.json")) return null
            val tag = location.removePrefix(prefix).removeSuffix("/app.json")
            return tag.takeIf(::validTag)
        }

        /**
         * Whether [url] may be asked for at all: HTTPS, no user, no fragment, the default port, and one of four shapes
         * only (the repo list of our account, the notes of one release of one of its repos, the latest app.json of one of its repos,
         * a file of one of its releases). Percent signs, dots segments and other hosts never match. After the kit's fromRepo
         * (Updates.fromRepo).
         */
        fun startAllowed(url: String): Boolean {
            val u = try { URL(url) } catch (e: MalformedURLException) { return false }
            if (!hostOk(u) || u.ref != null) return false
            val parts = u.path.split('/')
            return when (u.host.lowercase()) {
                API_HOST -> when {
                    u.path == "/users/$OWNER/repos" -> u.query == REPO_LIST_QUERY
                    // "", repos, owner, repo, releases, tags, tag
                    else -> u.query == null && parts.size == 7 && parts[0].isEmpty() && parts[1] == "repos" && parts[2] == OWNER &&
                        validRepo(parts[3]) && parts[4] == "releases" && parts[5] == "tags" && validTag(parts[6])
                }
                PAGES_HOST -> u.query == null && (releaseFile(parts) != null || latestApp(parts) != null)
                else -> false
            }
        }

        // "", owner, repo, releases, download, tag, file
        private fun releaseFile(parts: List<String>): String? {
            if (parts.size != 7 || parts[0].isNotEmpty() || parts[1] != OWNER) return null
            if (parts[3] != "releases" || parts[4] != "download") return null
            if (!validRepo(parts[2]) || !validTag(parts[5]) || !validFile(parts[6])) return null
            return parts[2]
        }

        // "", owner, repo, releases, latest, download, app.json
        private fun latestApp(parts: List<String>): String? {
            if (parts.size != 7 || parts[0].isNotEmpty() || parts[1] != OWNER) return null
            if (parts[3] != "releases" || parts[4] != "latest" || parts[5] != "download" || parts[6] != "app.json") return null
            return parts[2].takeIf(::validRepo)
        }

        /** [url] when it is HTTPS to one of GitHub's hosts, else refused (the kit's checked). */
        internal fun checked(url: URL): URL {
            if (!hostOk(url) || url.host.lowercase() !in GITHUB) throw NetError(NetError.Kind.REFUSED, message = "refused ${url.host}")
            return url
        }

        private fun hostOk(u: URL): Boolean = u.protocol == "https" && u.userInfo == null && (u.port == -1 || u.port == 443)

        private fun parse(url: String): URL {
            if (!startAllowed(url)) throw NetError(NetError.Kind.REFUSED, message = "address refused")
            return URL(url)
        }

        // An ETag is printable ASCII and short.
        private fun plainHeader(value: String): Boolean = value.length in 1..200 && value.all { it in ' '..'~' }

        /**
         * The failure for an answer that is not 200: GitHub's rate limit (403 or 429 with X-RateLimit-Remaining 0, its
         * X-RateLimit-Reset in seconds, or a Retry-After), a missing page, else a refusal.
         * https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api#exceeding-the-rate-limit
         */
        private fun failure(code: Int, conn: HttpURLConnection): NetError {
            if (code == 404) return NetError(NetError.Kind.NOT_FOUND, message = "http 404")
            if (code == 403 || code == 429) {
                val remaining = conn.getHeaderField("X-RateLimit-Remaining")?.trim()
                val reset = conn.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()
                val retry = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                if (remaining == "0") return NetError(NetError.Kind.RATE_LIMITED, reset, "rate limited")
                if (retry != null && retry >= 0) {
                    return NetError(NetError.Kind.RATE_LIMITED, System.currentTimeMillis() / 1000 + retry, "rate limited")
                }
                if (code == 429) return NetError(NetError.Kind.RATE_LIMITED, null, "rate limited")
            }
            return NetError(NetError.Kind.REFUSED, message = "http $code")
        }

        // The kit's get loop: read no more than [max] bytes.
        private fun readCapped(input: java.io.InputStream, max: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > max) throw NetError(NetError.Kind.REFUSED, message = "too large")
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /** The SHA-256 of [file] as lower case hex (the kit's sha256). */
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
