package com.yaz.store.data.sparse

// What a release APK with no app.json says, read once by ranges and kept per repo, tag and file in the cache folder (a tag's files do not change),
// as Catalog.details keeps a tag's notes for good (data/catalog/Catalog.kt) and IconStore keeps a tag's icon (data/catalog/IconStore.kt).
// The file is a small JSON written with kotlinx.serialization (https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md) and
// read back through the same rules as the inspector's answer (SparseWire): a package by Android's rule, a version, texts cleaned and capped.
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.core.common.writeTextAtomically
import com.yaz.store.data.catalog.ApkProbe
import com.yaz.store.data.catalog.CatalogRules
import com.yaz.store.data.net.RangeSource
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** [ranges] opens a release file by its address (ReleaseNet.ranges). */
class SparseProbe(private val ranges: (String) -> RangeSource, private val apk: SparseApk, private val dir: File) : ApkProbe {

    @Serializable
    private data class Stored(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val label: String? = null,
        val minSdk: Int
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probe(repo: String, tag: String, apkName: String): SparseInfo? {
        val file = File(dir, ApkInfoCodec.sha256Hex("$repo/$tag/$apkName".toByteArray()) + ".json")
        kept(file)?.let { return it }
        val info = apk.read(ranges(ReleaseNet.downloadUrl(repo, tag, apkName))) ?: return null
        runCatching {
            dir.mkdirs()
            file.writeTextAtomically(json.encodeToString(Stored(info.packageName, info.versionCode, info.versionName, info.label, info.minSdk)))
        }
        return info
    }

    // A kept answer that does not pass the rules is ignored and read again.
    private fun kept(file: File): SparseInfo? {
        if (!file.isFile || file.length() > MAX_STORED) return null
        val stored = try {
            json.decodeFromString<Stored>(file.readText())
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        } catch (e: java.io.IOException) {
            return null
        }
        if (!CatalogRules.validPackage(stored.packageName) || stored.versionCode <= 0 || stored.minSdk !in 1..1000) return null
        return SparseInfo(
            stored.packageName, stored.versionCode,
            ApkInfoCodec.clean(stored.versionName, CatalogRules.MAX_VERSION_NAME),
            stored.label?.let { ApkInfoCodec.clean(it, CatalogRules.MAX_LABEL) }?.ifEmpty { null },
            stored.minSdk
        )
    }

    private companion object {
        const val MAX_STORED = 4096L
    }
}
