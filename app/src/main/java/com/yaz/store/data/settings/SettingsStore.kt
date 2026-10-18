package com.yaz.store.data.settings

// Ported from Files (YAZ/Files at HEAD, data/settings/SettingsStore.kt), itself from Calculator (https://github.com/213YaZ786/Calculator, data/settings/SettingsStore.kt),
// cut to the settings the look needs: Files' update mode (the update kit, which Store does not take), its folders and its thumbnails are gone.
import android.content.Context
import com.yaz.store.core.common.writeTextAtomically
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    val textScale: Float = 1f
)

/** Small preference file, plain JSON written atomically, like the other apps. */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    val current: Settings get() = _settings.value

    // Files' SettingsStore.load.
    private fun load(): Settings {
        if (!file.exists()) return Settings()
        return runCatching { json.decodeFromString<Settings>(file.readText()) }
            .getOrDefault(Settings())
    }

    // Files' SettingsStore.update.
    fun update(transform: (Settings) -> Settings) {
        val updated = transform(_settings.value)
        if (updated == _settings.value) return
        _settings.value = updated
        runCatching { file.writeTextAtomically(json.encodeToString(updated)) }
    }
}
