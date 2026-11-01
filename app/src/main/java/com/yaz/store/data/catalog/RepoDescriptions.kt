package com.yaz.store.data.catalog

// The short description of an app, read from its repo's source tree: fastlane/metadata/android/<locale>/short_description.txt, the layout F-Droid and
// IzzyOnDroid read from the source repo, never from a release (https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots;
// supply in https://github.com/fastlane/fastlane names the locale folders en-US, fr-FR). The file is read like the repo's icon: GitHub's raw link
// and the conditional request of IconStore (RepoFiles). The phone's first language is tried, then en-US, the walk GrapheneOS' App Store makes
// through the phone's locale list and its English fallback (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/Repo.kt).
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import java.util.Locale

/** Where the catalog gets an app's own words from its repo: null when the repo has none now. Blocking: off the main thread. */
fun interface RepoWords {
    fun description(repo: String, tags: List<String>): String?
}

class RepoDescriptions(private val files: RepoFiles, openings: Openings = Openings()) : RepoWords {

    // Repo and locale pairs asked since the last opening: one conditional request each per opening (IconStore.asked, the same rule: Openings.asked).
    private val asked = openings.asked()

    // Source: IconStore.repoIcon (this app's data/catalog/IconStore.kt), for the short description of up to two locales: the phone's first language,
    // then en-US; the first one found wins. Cleaned to one line and cut to MAX_DESCRIPTION as app.json's description is (CatalogRules.parseAppJson,
    // ApkInfoCodec.clean). Offline, limited or refused: the stored text stays; a 404 on every locale: null.
    override fun description(repo: String, tags: List<String>): String? {
        for (locale in directories(tags)) {
            val bytes = files.conditional(
                key = "$repo:$locale",
                name = ApkInfoCodec.sha256Hex("words:$repo:$locale".toByteArray()),
                extension = "txt",
                url = ReleaseNet.repoDescriptionUrl(repo, locale),
                max = MAX_BYTES,
                seen = asked
            ) ?: continue
            val text = oneLine(String(bytes, Charsets.UTF_8))
            if (text.isNotEmpty()) return text
        }
        return null
    }

    companion object {
        /** A short description is one line of 80 characters in the stores; 1 KiB is far above it. */
        const val MAX_BYTES = 1024L

        /** The locales to ask, best first: [tags]' first language as a fastlane folder, then en-US; two at most, no repeat. */
        fun directories(tags: List<String>): List<String> {
            val first = tags.firstOrNull()?.let(::fastlaneLocale)
            return listOfNotNull(first, "en-US").distinct()
        }

        // Source: Android's LocaleList and BCP 47 tags (https://developer.android.com/reference/android/os/LocaleList), java.util.Locale.forLanguageTag
        // (https://developer.android.com/reference/java/util/Locale#forLanguageTag(java.lang.String)): fr-FR gives fr-FR, zh-Hans-CN gives zh-CN
        // (fastlane folders carry no script), es-419 gives es-419, a bare fr stays fr. Null when the tag gives no language fastlane could name.
        fun fastlaneLocale(tag: String): String? {
            val locale = Locale.forLanguageTag(tag.replace('_', '-'))
            val language = locale.language
            if (language.isEmpty() || language == "und") return null
            val name = if (locale.country.isNotEmpty()) "$language-${locale.country}" else language
            return name.takeIf(ReleaseNet::validLocale)
        }

        // Source: CatalogRules.parseAppJson (this app's data/catalog/Catalog.kt) cleaning app.json's description: ApkInfoCodec.clean drops control
        // and bidirectional characters and cuts to MAX_DESCRIPTION; line breaks become one space first so a second line is not glued to the first.
        fun oneLine(text: String): String =
            ApkInfoCodec.clean(text.replace(Regex("\\s*[\\r\\n]+\\s*"), " "), CatalogRules.MAX_DESCRIPTION)

        /** The folder AppModule keeps the descriptions in, under the cache. */
        fun folder(cacheDir: File): File = File(cacheDir, "words")
    }
}
