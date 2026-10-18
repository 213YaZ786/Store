package com.yaz.store.core.apk

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// What the isolated inspector says about one APK, and the plain bytes it travels as.
// The shape (a magic number, then fixed fields and counted lists read with DataInputStream, every count and
// every text capped before use) follows the DecoderService pattern of the shared safemedia kit
// (a pipe of plain values, no Parcelable made from outside data), and Android's own package name rule
// (PackageParser/ParsingPackageUtils validateName, https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/content/pm/parsing/ParsingPackageUtils.java).

/** What the manifest, the signature and the resources of one APK say. */
data class ApkInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    /** The split attribute: null for a base APK. */
    val splitName: String?,
    val isFeatureSplit: Boolean,
    val configForSplit: String?,
    val isSplitRequired: Boolean,
    val minSdk: Int,
    val permissions: List<String>,
    /** The ABIs having lib/<abi>/lib*.so entries. */
    val abis: List<String>,
    /** The signature verified (ApkVerifier) and [signers] hold the certificates' SHA-256. */
    val verified: Boolean,
    /** SHA-256 of each signer certificate, 64 lower case hex digits. */
    val signers: List<String>,
    /** The app's name from its resources, at most [ApkInfoCodec.MAX_LABEL] characters, or null. */
    val label: String?
)

/** An inspector answer that is cut, too large or not what a manifest can say. */
class InspectRefused(message: String) : IOException(message)

object ApkInfoCodec {
    private const val MAGIC = 0x59415A31
    /** The most bytes an answer may take; a real one is a few KB. */
    const val MAX_WIRE = 512 * 1024
    const val MAX_LABEL = 100
    private const val MAX_NAME = 255
    private const val MAX_VERSION_NAME = 100
    private const val MAX_PERMISSIONS = 1000
    private const val MAX_ABIS = 16
    private const val MAX_SIGNERS = 16

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val SPLIT = Regex("[A-Za-z0-9_.\\-]{1,255}")
    private val PERMISSION = Regex("[A-Za-z0-9_.\\-:]{1,255}")
    private val ABI = Regex("[A-Za-z0-9_\\-]{1,32}")
    private val SIGNER = Regex("[0-9a-f]{64}")

    fun write(info: ApkInfo, to: OutputStream) {
        val out = DataOutputStream(to)
        out.writeInt(MAGIC)
        out.writeUTF(info.packageName)
        out.writeLong(info.versionCode)
        out.writeUTF(info.versionName)
        out.writeUTF(info.splitName.orEmpty())
        out.writeBoolean(info.splitName != null)
        out.writeBoolean(info.isFeatureSplit)
        out.writeUTF(info.configForSplit.orEmpty())
        out.writeBoolean(info.isSplitRequired)
        out.writeInt(info.minSdk)
        out.writeInt(info.permissions.size)
        info.permissions.forEach(out::writeUTF)
        out.writeInt(info.abis.size)
        info.abis.forEach(out::writeUTF)
        out.writeBoolean(info.verified)
        out.writeInt(info.signers.size)
        info.signers.forEach(out::writeUTF)
        out.writeUTF(info.label.orEmpty())
        out.writeBoolean(info.label != null)
        out.flush()
    }

    /** Reads an answer, refusing anything that is cut, too large or malformed. */
    fun read(from: InputStream): ApkInfo {
        try {
            val input = DataInputStream(Bounded(from, MAX_WIRE.toLong()))
            if (input.readInt() != MAGIC) throw InspectRefused("not an inspector answer")
            val packageName = input.readUTF()
            if (packageName.length > MAX_NAME || !PACKAGE.matches(packageName)) throw InspectRefused("bad package name")
            val versionCode = input.readLong()
            val versionName = clean(input.readUTF(), MAX_VERSION_NAME)
            val split = input.readUTF()
            val hasSplit = input.readBoolean()
            if (hasSplit && !SPLIT.matches(split)) throw InspectRefused("bad split name")
            val feature = input.readBoolean()
            val config = input.readUTF()
            if (config.isNotEmpty() && !SPLIT.matches(config)) throw InspectRefused("bad configForSplit")
            val required = input.readBoolean()
            val minSdk = input.readInt()
            if (minSdk < 0 || minSdk > 10_000) throw InspectRefused("bad minSdkVersion")
            // A permission name with odd characters is left out, the app is not refused for it.
            val permissions = list(input, MAX_PERMISSIONS) { it }.filter { PERMISSION.matches(it) }
            val abis = list(input, MAX_ABIS) { value ->
                if (!ABI.matches(value)) throw InspectRefused("bad ABI name")
                value
            }
            val verified = input.readBoolean()
            val signers = list(input, MAX_SIGNERS) { value ->
                if (!SIGNER.matches(value)) throw InspectRefused("bad signer")
                value
            }
            val label = input.readUTF()
            val hasLabel = input.readBoolean()
            return ApkInfo(
                packageName = packageName,
                versionCode = versionCode,
                versionName = versionName,
                splitName = if (hasSplit) split else null,
                isFeatureSplit = feature,
                configForSplit = config.ifEmpty { null },
                isSplitRequired = required,
                minSdk = minSdk,
                permissions = permissions.distinct(),
                abis = abis.distinct(),
                verified = verified,
                signers = signers.distinct(),
                label = if (hasLabel) clean(label, MAX_LABEL).ifEmpty { null } else null
            )
        } catch (e: EOFException) {
            throw InspectRefused("answer cut short")
        }
    }

    /**
     * The SHA-256 of [bytes] as 64 lower case hex digits, how a signer is named everywhere in the app. The digest of a
     * certificate's encoding, as apksigner prints it (ApkSignerTool.printCertificate):
     * https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/apksigner/java/com/android/apksigner/ApkSignerTool.java
     */
    fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun <T> list(input: DataInputStream, max: Int, item: (String) -> T): List<T> {
        val count = input.readInt()
        if (count < 0 || count > max) throw InspectRefused("too many items")
        return List(count) { item(input.readUTF()) }
    }

    /**
     * A text of the APK as shown on screen: control characters and the bidirectional overrides (U+202A..U+202E,
     * U+2066..U+2069, U+200E/F) dropped so it cannot spoof its neighbours, cut to [max] characters.
     */
    fun clean(text: String, max: Int): String {
        val out = StringBuilder()
        val source = text.trim()
        var count = 0
        var i = 0
        while (i < source.length && count < max) {
            val cp = source.codePointAt(i)
            i += Character.charCount(cp)
            val bidi = cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C
            if (Character.isISOControl(cp) || bidi) continue
            out.appendCodePoint(cp)
            count++
        }
        return out.toString().trim()
    }

    /** At most [limit] bytes of [source], then an end of stream. */
    private class Bounded(source: InputStream, private var left: Long) : java.io.FilterInputStream(source) {
        override fun read(): Int {
            if (left <= 0) throw InspectRefused("answer too large")
            val value = super.read()
            if (value >= 0) left--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) throw InspectRefused("answer too large")
            val read = super.read(buffer, offset, minOf(length.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }
    }
}
