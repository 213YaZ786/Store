package com.yaz.store

import com.yaz.store.data.catalog.CatalogOverlay
import com.yaz.store.data.catalog.IconStore
import com.yaz.store.data.catalog.StoreEntry
import com.yaz.store.data.net.ReleaseNet
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Debug builds only (src/debug, found by name from AppModule): the words and the icons of the apps, kept in files/debug-meta.json
 * {"<repo>": {"description": "...", "icon": "<repo>.png"}} and files/debug-icons/, until each release carries its own app.json and icon.png.
 * The file is read as Seeded reads its own (kotlinx.serialization's Json ignoring unknown keys,
 * https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/json.md#ignoring-unknown-keys), capped as ReleaseNet caps a JSON;
 * a picture is read only by its plain name inside debug-icons and capped as IconStore caps icon.png, then decoded by SafeImages like it.
 * It does not exist in the release build.
 */
class DebugMeta(private val filesDir: File) : CatalogOverlay {

    @Serializable
    private data class Meta(val description: String? = null, val icon: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    private fun metas(): Map<String, Meta> {
        val file = File(filesDir, "debug-meta.json")
        if (!file.isFile || file.length() > ReleaseNet.MAX_JSON) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, Meta>>(file.readText()) }.getOrNull() ?: emptyMap()
    }

    override fun apply(entries: List<StoreEntry>): List<StoreEntry> {
        val metas = metas()
        if (metas.isEmpty()) return entries
        return entries.map { e -> metas[e.repo]?.description?.let { e.copy(description = it) } ?: e }
    }

    override fun icon(entry: StoreEntry): ByteArray? {
        val name = metas()[entry.repo]?.icon ?: return null
        if (name.isEmpty() || name.any { it == '/' || it == '\\' } || name.startsWith(".")) return null
        val file = File(File(filesDir, "debug-icons"), name)
        if (!file.isFile || file.length() !in 1..IconStore.MAX_ICON) return null
        return runCatching { file.readBytes() }.getOrNull()
    }
}
