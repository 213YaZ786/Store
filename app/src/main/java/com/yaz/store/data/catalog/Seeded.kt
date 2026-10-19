package com.yaz.store.data.catalog

// Debug builds only (AppModule gives it to Catalog only when BuildConfig.DEBUG): entries read from a file in the app's own files folder,
// so Home can be looked at before any release carries app.json. Each is made as Catalog.entryOf makes one, from CatalogRules.parseAppJson
// and AbiChoice; the file is a JSON array of {"repo", "tag", "json" (the app.json text), "icon" (true when the release has icon.png)}, read with
// kotlinx.serialization's Json ignoring unknown keys (https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md#ignoring-unknown-keys),
// the shape DebugInstallReceiver reads one entry in (maestro/run-install.sh pushes the file through run-as).
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SeedDto(val repo: String, val tag: String, val json: String, val icon: Boolean = false)

object Seeded {
    private val json = Json { ignoreUnknownKeys = true }

    /** The entries of [file] that pass the same rules as a live one; nothing when the file is missing or is no seed. */
    fun read(file: File, locales: List<String>, abis: List<String>): List<StoreEntry> {
        if (!file.isFile || file.length() > ReleaseNet.MAX_JSON) return emptyList()
        val dtos = runCatching { json.decodeFromString<List<SeedDto>>(file.readText()) }.getOrNull() ?: return emptyList()
        return dtos.mapNotNull { dto ->
            if (!ReleaseNet.validRepo(dto.repo) || !ReleaseNet.validTag(dto.tag)) return@mapNotNull null
            val app = CatalogRules.parseAppJson(dto.json) ?: return@mapNotNull null
            StoreEntry(
                repo = dto.repo,
                tag = dto.tag,
                packageName = app.packageName,
                label = app.label,
                description = CatalogRules.pickDescription(app.descriptions, locales),
                versionCode = app.versionCode,
                versionName = app.versionName,
                minSdk = app.minSdk,
                apkName = AbiChoice.choose(app.apks, abis),
                iconUrl = if (dto.icon) ReleaseNet.downloadUrl(dto.repo, dto.tag, "icon.png") else null
            )
        }
    }
}
