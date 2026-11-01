package com.yaz.store.data.catalog

// The icons of apps not installed: the repo's own icon.png (asked again at each opening) or the release's, kept on disk once read (a tag's files do not change), one file per tag,
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

    // The repo's own files, read with the conditional request (RepoFiles, ported out of this class's former `conditional`).
    private val files = RepoFiles(dir, net::getIfChanged)

    // Repos asked for their icon since the process started: one conditional request per repo and opening.
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val askedLayers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The published layers of an icon and their pictures' bytes, [pictures] in the order of [icon]'s layers. */
    class LayerFiles(val icon: IconLayers, val pictures: List<ByteArray>)

    // Source: bytes below and releaseIcon (this app's data/catalog/IconStore.kt): the overlay first (debug builds), then the repo's own
    // icon-layers.json; each picture read from a file named by its SHA-256 (content-addressed, as GrapheneOS' App Store keeps what it downloads by hash,
    // https://github.com/GrapheneOS/AppStore, app/src/main/java/app/grapheneos/apps/core/Repo.kt), fetched only when missing, kept only when its
    // SHA-256 is the one the list names. Null when the repo publishes no layers, the list is not as published, or any picture is missing or wrong:
    // the screen keeps icon.png. Blocking: off the main thread.
    fun layers(entry: StoreEntry): LayerFiles? {
        val seam = overlay
        if (seam != null) {
            val text = seam.layers(entry)
            if (text != null) {
                val icon = IconLayers.parse(String(text, Charsets.UTF_8)) ?: return null
                val pictures = icon.layers.map { seam.layerPng(entry, it.png) ?: return null }
                return LayerFiles(icon, pictures)
            }
        }
        val text = repoLayers(entry.repo) ?: return null
        val icon = IconLayers.parse(String(text, Charsets.UTF_8)) ?: return null
        val pictures = icon.layers.map { layerPicture(entry.repo, it) ?: return null }
        return LayerFiles(icon, pictures)
    }

    // Source: releaseIcon below (this app's data/catalog/IconStore.kt), the file named by the picture's SHA-256 instead of the tag. The name comes
    // from the parser's allow-list (64 lower case hex), never from the file's text as it stands.
    private fun layerPicture(repo: String, layer: IconLayer): ByteArray? {
        val file = File(dir, "layer-${layer.sha256}.png")
        if (file.isFile && file.length() in 1..MAX_ICON) {
            runCatching { file.readBytes() }.getOrNull()?.takeIf { ApkInfoCodec.sha256Hex(it) == layer.sha256 }?.let { return it }
        }
        val bytes = try {
            net.getBytes(ReleaseNet.repoLayerUrl(repo, layer.png), MAX_ICON)
        } catch (e: NetError) {
            return null
        }
        if (ApkInfoCodec.sha256Hex(bytes) != layer.sha256) return null
        runCatching {
            dir.mkdirs()
            val temporary = File(dir, file.name + ".part")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) temporary.delete()
        }
        return bytes
    }

    /**
     * The bytes of the icon of [entry]: the repo's own (the newest), else icon.png of its release, or null when it has none or cannot be read
     * now. Blocking: off the main thread.
     */
    fun bytes(entry: StoreEntry): ByteArray? {
        overlay?.icon(entry)?.let { return it }
        repoIcon(entry.repo)?.let { return it }
        return releaseIcon(entry)
    }

    // Source: GitHub's conditional requests (If-None-Match with the ETag of the last answer, 304 keeps the stored file, 200 replaces it,
    // https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate), on the
    // F-Droid layout path every repo of ours publishes (https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots), kept per repo
    // on disk as releaseIcon keeps a tag's. Asked once per opening; offline or limited, the stored file stays; a 404 means no icon in the repo.
    private fun repoIcon(repo: String): ByteArray? =
        conditional(repo, ApkInfoCodec.sha256Hex("repo:$repo".toByteArray()), "png", ReleaseNet.repoIconUrl(repo), MAX_ICON, asked)

    // Source: repoIcon above and the same GitHub conditional request
    // (https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api#use-conditional-requests-if-appropriate): the list of
    // layers of the repo's icon, the same file per repo, another name, a cap of 16 KiB, asked once per opening as well.
    private fun repoLayers(repo: String): ByteArray? =
        conditional(repo, ApkInfoCodec.sha256Hex("layers:$repo".toByteArray()), "json", ReleaseNet.repoLayersUrl(repo), IconLayers.MAX_JSON.toLong(), askedLayers)

    // Source: repoIcon's former body (this app's data/catalog/IconStore.kt), the GitHub conditional request above made one function for both files,
    // now RepoFiles.conditional (this app's data/catalog/RepoFiles.kt, shared with the descriptions): [name] the stored file's name,
    // [extension] its kind, [seen] the repos asked since the start.
    private fun conditional(repo: String, name: String, extension: String, url: String, max: Long, seen: MutableSet<String>): ByteArray? =
        files.conditional(repo, name, extension, url, max, seen)

    // The release's icon.png, kept on disk per repo and tag (a tag's files do not change).
    private fun releaseIcon(entry: StoreEntry): ByteArray? {
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
