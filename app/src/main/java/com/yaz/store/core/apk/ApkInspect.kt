package com.yaz.store.core.apk

import android.R
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.apksig.internal.zip.CentralDirectoryRecord
import com.android.apksig.internal.zip.LocalFileRecord
import com.android.apksig.internal.zip.ZipUtils
import com.android.apksig.util.DataSources
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.ValueType
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

// What one APK says, read inside the isolated ApkInspectorService and nowhere else.
// - The manifest is found and inflated from the zip's central directory, the way App Manager's ApkUtils
//   getManifestFromApk does (GPL-3.0-or-later):
//   https://github.com/MuntashirAkon/AppManager/blob/master/app/src/main/java/io/github/muntashirakon/AppManager/apk/ApkUtils.java
// - It is read with apksig's AndroidBinXmlParser, as apksig's own ApkUtils does for the package name, the
//   version and the minSdkVersion (Apache 2.0):
//   https://android.googlesource.com/platform/tools/apksig/+/refs/heads/main/src/main/java/com/android/apksig/apk/ApkUtils.java
// - The signers come from apksig's ApkVerifier (same repository, ApkVerifier.java), as apksigner verify does.
// - The lib/<abi>/lib*.so entries are the ones AOSP's NativeLibraryHelper counts to pick an app's ABI:
//   https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/content/NativeLibraryHelper.java
// - The label and a version name held in resources are read from resources.arsc with ARSCLib (Apache 2.0):
//   https://github.com/REAndroid/ARSCLib (TableBlock.load, ResourceEntry.resolveReference), default configuration.

object ApkInspect {
    private const val MANIFEST = "AndroidManifest.xml"
    private const val RESOURCES = "resources.arsc"
    /** A binary manifest is a few hundred KB at most; past this it is a trap. */
    private const val MAX_MANIFEST = 16L * 1024 * 1024
    /** A resource table is read into memory only up to this size; past it the label is left out. */
    private const val MAX_RESOURCES = 32L * 1024 * 1024
    private const val MAX_ABIS = 16
    private const val MAX_PERMISSIONS = 1000
    /** NativeLibraryHelper's lib/<abi>/lib*.so. */
    private val LIB = Regex("lib/([^/]+)/lib[^/]*\\.so")

    /** Reads the APK in [channel]. Throws when it has no readable manifest. */
    fun inspect(channel: FileChannel): ApkInfo {
        val apk = DataSources.asDataSource(channel)
        val sections = try {
            ApkUtils.findZipSections(apk)
        } catch (e: Exception) {
            throw IOException("not a zip", e)
        }
        val records = ZipUtils.parseZipCentralDirectory(apk, sections)
        val data = apk.slice(0, sections.zipCentralDirectoryOffset)

        val manifestRecord = records.firstOrNull { it.name == MANIFEST } ?: throw IOException("no manifest")
        val manifest = bytes(data, manifestRecord, MAX_MANIFEST) ?: throw IOException("manifest too large")
        val raw = readManifest(manifest)

        val abis = LinkedHashSet<String>()
        for (record in records) {
            val match = LIB.matchEntire(record.name) ?: continue
            if (abis.size < MAX_ABIS) abis.add(match.groupValues[1])
        }

        var table: TableBlock? = null
        if (raw.needsTable()) {
            val record = records.firstOrNull { it.name == RESOURCES }
            val arsc = record?.let { bytes(data, it, MAX_RESOURCES) }
            if (arsc != null) table = runCatching { TableBlock.load(ByteArrayInputStream(arsc)) }.getOrNull()
        }
        val label = raw.labelText ?: raw.labelId?.let { id -> table?.let { resolve(it, id) } }
        val versionName = raw.versionNameText ?: raw.versionNameId?.let { id -> table?.let { resolve(it, id) } }.orEmpty()

        val (verified, signers) = signers(apk)
        return ApkInfo(
            packageName = raw.packageName,
            versionCode = raw.versionCode,
            versionName = versionName,
            splitName = raw.split,
            isFeatureSplit = raw.isFeatureSplit,
            configForSplit = raw.configForSplit,
            isSplitRequired = raw.isSplitRequired,
            minSdk = raw.minSdk,
            permissions = raw.permissions.toList(),
            abis = abis.toList(),
            verified = verified,
            signers = signers,
            label = label?.let { ApkInfoCodec.clean(it, ApkInfoCodec.MAX_LABEL) }?.ifEmpty { null }
        )
    }

    /** The inflated bytes of [record], or null if it declares more than [max]. */
    private fun bytes(data: com.android.apksig.util.DataSource, record: CentralDirectoryRecord, max: Long): ByteArray? {
        if (record.uncompressedSize > max) return null
        return LocalFileRecord.getUncompressedData(data, record, data.size())
    }

    private fun signers(apk: com.android.apksig.util.DataSource): Pair<Boolean, List<String>> {
        val result = try {
            ApkVerifier.Builder(apk).build().verify()
        } catch (e: Exception) {
            return false to emptyList()
        }
        if (!result.isVerified) return false to emptyList()
        val certificates = result.signerCertificates.map { ApkInfoCodec.sha256Hex(it.encoded) }
        return true to certificates.distinct()
    }

    private fun resolve(table: TableBlock, id: Int): String? = try {
        val entry = table.getResource(id)?.resolveReference()?.get()
        if (entry != null && entry.valueType == ValueType.STRING) entry.valueAsString else null
    } catch (e: Exception) {
        null
    }

    private class RawManifest {
        var packageName = ""
        var versionCode = 0L
        var versionCodeMajor = 0L
        var versionNameText: String? = null
        var versionNameId: Int? = null
        var split: String? = null
        var configForSplit: String? = null
        var isFeatureSplit = false
        var isSplitRequired = false
        var minSdk = 1
        var labelText: String? = null
        var labelId: Int? = null
        val permissions = LinkedHashSet<String>()

        fun needsTable() = (labelText == null && labelId != null) || (versionNameText == null && versionNameId != null)
    }

    private fun readManifest(manifest: ByteArray): RawManifest {
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(manifest))
        val raw = RawManifest()
        var seenManifest = false
        var event = parser.eventType
        while (event != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (event == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                val name = parser.name
                when {
                    parser.depth == 1 && name == "manifest" -> {
                        seenManifest = true
                        readManifestAttributes(parser, raw)
                    }
                    parser.depth == 2 && name == "uses-sdk" -> readMinSdk(parser, raw)
                    parser.depth == 2 && name == "application" -> readLabel(parser, raw)
                    parser.depth == 2 && (name == "uses-permission" || name == "uses-permission-sdk-23") ->
                        if (raw.permissions.size < MAX_PERMISSIONS) permission(parser)?.let { raw.permissions.add(it) }
                }
            }
            event = parser.next()
        }
        if (!seenManifest || raw.packageName.isEmpty()) throw IOException("no package in the manifest")
        raw.versionCode = (raw.versionCodeMajor shl 32) or (raw.versionCode and 0xFFFFFFFFL)
        return raw
    }

    private fun readManifestAttributes(parser: AndroidBinXmlParser, raw: RawManifest) {
        for (i in 0 until parser.attributeCount) {
            val id = parser.getAttributeNameResourceId(i)
            val name = parser.getAttributeName(i)
            when {
                name == "package" -> raw.packageName = text(parser, i).orEmpty()
                name == "split" -> raw.split = text(parser, i)
                name == "configForSplit" -> raw.configForSplit = text(parser, i)
                id == R.attr.versionCode -> raw.versionCode = number(parser, i)
                id == VERSION_CODE_MAJOR -> raw.versionCodeMajor = number(parser, i)
                id == R.attr.versionName -> {
                    if (parser.getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_REFERENCE) {
                        raw.versionNameId = parser.getAttributeIntValue(i)
                    } else {
                        raw.versionNameText = text(parser, i)
                    }
                }
                id == R.attr.isFeatureSplit -> raw.isFeatureSplit = flag(parser, i)
                id == R.attr.isSplitRequired -> raw.isSplitRequired = flag(parser, i)
            }
        }
    }

    private fun readMinSdk(parser: AndroidBinXmlParser, raw: RawManifest) {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeNameResourceId(i) != R.attr.minSdkVersion) continue
            // A number, or a codename of a preview Android, which no released phone runs.
            raw.minSdk = when (parser.getAttributeValueType(i)) {
                AndroidBinXmlParser.VALUE_TYPE_INT -> parser.getAttributeIntValue(i)
                else -> parser.getAttributeStringValue(i)?.toIntOrNull() ?: 10_000
            }
        }
    }

    private fun readLabel(parser: AndroidBinXmlParser, raw: RawManifest) {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeNameResourceId(i) != R.attr.label) continue
            if (parser.getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_REFERENCE) {
                raw.labelId = parser.getAttributeIntValue(i)
            } else {
                raw.labelText = text(parser, i)
            }
        }
    }

    private fun permission(parser: AndroidBinXmlParser): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeNameResourceId(i) == R.attr.name) return text(parser, i)
        }
        return null
    }

    private fun text(parser: AndroidBinXmlParser, i: Int): String? = when (parser.getAttributeValueType(i)) {
        AndroidBinXmlParser.VALUE_TYPE_STRING -> parser.getAttributeStringValue(i)
        AndroidBinXmlParser.VALUE_TYPE_INT -> parser.getAttributeIntValue(i).toString()
        else -> null
    }

    private fun number(parser: AndroidBinXmlParser, i: Int): Long = when (parser.getAttributeValueType(i)) {
        AndroidBinXmlParser.VALUE_TYPE_INT -> parser.getAttributeIntValue(i).toLong() and 0xFFFFFFFFL
        AndroidBinXmlParser.VALUE_TYPE_STRING -> parser.getAttributeStringValue(i)?.toLongOrNull() ?: 0L
        else -> 0L
    }

    private fun flag(parser: AndroidBinXmlParser, i: Int): Boolean = when (parser.getAttributeValueType(i)) {
        AndroidBinXmlParser.VALUE_TYPE_BOOLEAN -> parser.getAttributeBooleanValue(i)
        AndroidBinXmlParser.VALUE_TYPE_STRING -> parser.getAttributeStringValue(i) == "true"
        AndroidBinXmlParser.VALUE_TYPE_INT -> parser.getAttributeIntValue(i) != 0
        else -> false
    }

    /** android:versionCodeMajor, 0x01010576 (apksig's ApkUtils.VERSION_CODE_MAJOR_ATTR_ID). */
    private const val VERSION_CODE_MAJOR = 0x01010576
}
