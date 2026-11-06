package com.yaz.store.data.settings

// Ported from Files (YAZ/Files at HEAD, data/settings/SettingsStore.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, data/settings/SettingsStore.kt),
// cut to the settings the look needs: Files' update mode (the update kit, which Store does not take), its folders and its thumbnails are gone.
import android.app.job.JobInfo
import android.content.Context
import com.yaz.store.core.common.writeTextAtomically
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** True black instead of dark grey in dark mode. */
    val pureBlack: Boolean = false,
    /** Zones and floating controls in liquid glass, over a soft light in the wallpaper's colours. */
    val glass: Boolean = true,
    /** Multiplier on every text style, one of the steps in ui.theme.TEXT_SCALES. */
    val textScale: Float = 1f,
    /** The notification permission was asked once, at the first Install or Update tap; Android's own limit then governs. */
    val notificationsAsked: Boolean = false,
    /** The setup guide was done or skipped: it is never shown again. */
    val setupDone: Boolean = false,
    /** A check once a day for updates, with a notification; off unless asked (data/update). */
    val backgroundCheck: Boolean = false,
    /** The network updates use: [NETWORK_ANY], [NETWORK_NOT_ROAMING] or [NETWORK_UNMETERED] (the numbers GrapheneOS' App Store keeps). */
    val network: Int = NETWORK_ANY
)

// The values of the network choice, the numbers GrapheneOS' ListPreference stores (pref_key_network_type_for_auto_update_job): 1 any, 3 not roaming, 2 unmetered.
const val NETWORK_ANY = 1
const val NETWORK_UNMETERED = 2
const val NETWORK_NOT_ROAMING = 3

// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/autoupdate/AutoUpdatePrefs.kt#getAutoUpdateJobNetworkTypePref
// The stored choice; one that is none of the three reads as any network, as the source's `else`.
// not ported: prefs.getString(KEY_JOB_NETWORK_TYPE, null)?.toInt() ?: pref_def_network_type_for_bg_update_job (Store keeps the choice in Settings.network, a number with its default, any network = 1 as the source's prefs.xml)
fun getAutoUpdateJobNetworkTypePref(settings: Settings): Int =
    settings.network.takeIf { it == NETWORK_UNMETERED || it == NETWORK_NOT_ROAMING } ?: NETWORK_ANY

// https://github.com/GrapheneOS/AppStore/blob/main/app/src/main/java/app/grapheneos/apps/autoupdate/AutoUpdatePrefs.kt#getAutoUpdateJobNetworkType
// The JobInfo constraint of the choice: 2 unmetered, 3 not roaming, anything else any network.
fun getAutoUpdateJobNetworkType(settings: Settings): Int {
    return when (getAutoUpdateJobNetworkTypePref(settings)) {
        NETWORK_UNMETERED -> JobInfo.NETWORK_TYPE_UNMETERED
        NETWORK_NOT_ROAMING -> JobInfo.NETWORK_TYPE_NOT_ROAMING
        else -> JobInfo.NETWORK_TYPE_ANY
    }
}

/** Small preference file, plain JSON written atomically, like the other apps. */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    // Files' SettingsStore.load. A file written before the network choice holds `wifiOnly`: true reads as unmetered, anything else as any network.
    private fun load(): Settings {
        if (!file.exists()) return Settings()
        return runCatching {
            val text = file.readText()
            val settings = json.decodeFromString<Settings>(text)
            val old = json.parseToJsonElement(text).jsonObject
            if ("network" !in old && old["wifiOnly"]?.jsonPrimitive?.booleanOrNull == true) settings.copy(network = NETWORK_UNMETERED) else settings
        }.getOrDefault(Settings())
    }

    // Files' SettingsStore.update.
    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }
}
