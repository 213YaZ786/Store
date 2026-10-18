package com.yaz.store.data.net

// Store's only door to the network. Ported from the update kit (Modules shared/update, Updates.kt.tpl), as Calculator's
// copy has it (https://github.com/213YaZ786/Calculator, app/src/main/java/com/yaz/calculator/core/update/Updates.kt):
// open (every redirect read by hand, HTTPS to GitHub's hosts only, three hops), checked, get and download (size caps),
// sha256. The kit's open is itself ported from Mushaf's Net.open, after
// https://developer.android.com/reference/java/net/HttpURLConnection#setInstanceFollowRedirects(boolean) and
// https://developer.android.com/privacy-and-security/risks/cleartext.
// Added here: a path allow-list (only our account's repos, releases and release files), an ETag cache per URL
// (https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate:
// an answer 304 to If-None-Match does not count against the rate limit), and GitHub's rate-limit answers
// (https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api#exceeding-the-rate-limit).
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
    enum class Kind { OFFLINE, RATE_LIMITED, NOT_FOUND, REFUSED }
}

/** Where the catalog reads its JSON from: the network, or a fake in a test. */
interface JsonSource {
    /** The text answered at [url]; throws [NetError]. */
    fun getJson(url: String): String
}

class ReleaseNet(cacheDir: File) : JsonSource {

    private val cache = File(cacheDir, "net")

    // The kit's get, with the ETag cache of GitHub's conditional requests.
    override fun getJson(url: String): String {
        val start = parse(url)
        val key = ApkInfoCodec.sha256Hex(url.toByteArray())
        val body = File(cache, "$key.json")
        val tag = File(cache, "$key.etag")
        val cacheable = start.host.lowercase() == API_HOST
        // Both files or none: a body without its tag, or a tag without its body, is not used.
        val cachedBody = if (cacheable && body.isFile && tag.isFile && body.length() <= MAX_JSON) {
            runCatching { body.readText() }.getOrNull()
        } else null
        val cachedTag = if (cachedBody != null) runCatching { tag.readText() }.getOrNull()?.takeIf(::plainHeader) else null
        try {
            val conn = open(start, cachedTag.takeIf { cachedBody != null })
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

    // The kit's download, with its 200 MiB cap.
    fun download(url: String, into: File, onProgress: (Float) -> Unit) {
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

    /**
     * Opens [start] once it answers 200, or null on a 304. Redirects are never left to HttpURLConnection: each hop is
     * read here and must be HTTPS to one of GitHub's hosts before it is followed, three at most (the kit's open).
     * The tag goes only to the first address, never to where a redirect leads.
     */
    private fun open(start: URL, etag: String?): HttpURLConnection? {
        var url = start
        var hops = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Store")
                setRequestProperty("Accept", "application/vnd.github+json, */*")
                if (hops == 0 && etag != null) setRequestProperty("If-None-Match", etag)
            }
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
        // A tag or a file name in a release address: the same alphabet, plus '+' (a tag such as v1.2.3+4).
        private val SEGMENT = Regex("[A-Za-z0-9._+-]{1,255}")

        /** A repo name by GitHub's rule: https://docs.github.com/en/rest/repos/repos#create-a-repository-for-the-authenticated-user (name). */
        fun validRepo(name: String): Boolean = REPO.matches(name) && name != "." && name != ".."

        private fun validSegment(name: String): Boolean = SEGMENT.matches(name) && name != "." && name != ".."

        /** The address of the repo list of our account. */
        fun repoListUrl(): String = "https://$API_HOST/users/$OWNER/repos?$REPO_LIST_QUERY"

        /** The address of the latest release of [repo]. */
        fun latestUrl(repo: String): String = "https://$API_HOST/repos/$OWNER/$repo/releases/latest"

        /**
         * Whether [url] may be asked for at all: HTTPS, no user, no fragment, the default port, and one of three shapes
         * only (the repo list of our account, the latest release of one of its repos, a file of one of its releases).
         * Percent signs, dots segments and other hosts never match. After the kit's fromRepo (Updates.fromRepo).
         */
        fun startAllowed(url: String): Boolean {
            val u = try { URL(url) } catch (e: MalformedURLException) { return false }
            if (!hostOk(u)) return false
            val host = u.host.lowercase()
            val path = u.path
            if (u.ref != null) return false
            return when (host) {
                API_HOST -> {
                    val list = path == "/users/$OWNER/repos" && u.query == REPO_LIST_QUERY
                    val latest = u.query == null && path.startsWith("/repos/$OWNER/") && path.endsWith("/releases/latest") &&
                        validRepo(path.removePrefix("/repos/$OWNER/").removeSuffix("/releases/latest"))
                    list || latest
                }
                "github.com" -> u.query == null && releaseFile(path) != null
                else -> false
            }
        }

        /** The repo a release file address belongs to, or null when [path] is not one of our repos' release files. */
        private fun releaseFile(path: String): String? {
            val parts = path.split('/')
            // "", owner, repo, "releases", "download", tag, file
            if (parts.size != 7 || parts[0].isNotEmpty() || parts[1] != OWNER) return null
            if (parts[3] != "releases" || parts[4] != "download") return null
            if (!validRepo(parts[2]) || !validSegment(parts[5]) || !validSegment(parts[6])) return null
            return parts[2]
        }

        /** Whether [url] is a file of a release of [repo], and allowed to be fetched (an asset address read from GitHub's JSON). */
        fun isReleaseFileOf(repo: String, url: String): Boolean {
            if (!startAllowed(url)) return false
            val u = URL(url)
            return u.host.lowercase() == "github.com" && releaseFile(u.path) == repo
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
