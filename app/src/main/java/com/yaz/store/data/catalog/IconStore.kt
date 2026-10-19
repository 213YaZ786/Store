package com.yaz.store.data.catalog

// The release icons of apps not installed: icon.png of a release, kept on disk once read (a tag's files do not change), one file per tag,
// as Catalog.details keeps a tag's date and sizes (this app's data/catalog/Catalog.kt) and as GrapheneOS' App Store keeps the icons of its repo
// (https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/Repo.kt). The bytes are never decoded here:
// the screen hands them to the isolated DecoderService (core/security/SafeImages).
import com.yaz.store.core.apk.ApkInfoCodec
import com.yaz.store.data.net.NetError
import com.yaz.store.data.net.ReleaseNet
import java.io.File

class IconStore(
    private val net: ReleaseNet,
    private val dir: File,
    /** Debug builds only (AppModule): pictures a test pushes, see [CatalogOverlay]. Nothing in the release build. */
    private val overlay: CatalogOverlay? = null
) {

    /** The bytes of icon.png of [entry]'s release, or null when it has none or cannot be read now. Blocking: off the main thread. */
    fun bytes(entry: StoreEntry): ByteArray? {
        overlay?.icon(entry)?.let { return it }
        val url = entry.iconUrl ?: return null
        val file = File(dir, ApkInfoCodec.sha256Hex("${entry.repo}/${entry.tag}".toByteArray()) + ".png")
        if (file.isFile && file.length() in 1..MAX_ICON) runCatching { file.readBytes() }.getOrNull()?.let { return it }
        val bytes = try {
            net.getBytes(url, MAX_ICON)
        } catch (e: NetError) {
            return null
        }
        runCatching {
            dir.mkdirs()
            val temporary = File(dir, file.name + ".part")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) temporary.delete()
        }
        return bytes
    }

    companion object {
        /** An app icon is a few kilobytes; the decoder caps the pixels as well. */
        const val MAX_ICON = 512L * 1024
    }
}
