package com.example.betterauto.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.aare.projection.VideoResolution
import com.example.aare.transport.ConnectionMode
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aareDataStore: DataStore<Preferences> by preferencesDataStore(name = "aare_plus_settings")

enum class AudioRoutingMode(val label: String, val description: String) {
    AARE_CHANNELS("Canaux AAP (Média + Guidage)", "Sépare audio média et guidage vocal vers l'autoradio"),
    BLUETOOTH_MEDIA("Bluetooth A2DP", "Audio via Bluetooth véhicule, vidéo via AARE"),
    PHONE_SPEAKER("Haut-parleur téléphone", "Garde le son sur le téléphone")
}

data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.AUTO_CAR,
    val connectionMode: ConnectionMode = ConnectionMode.USB_AOAP,
    val wirelessHost: String = "192.168.43.1",
    val wirelessPort: Int = 5277,
    val videoResolution: VideoResolution = VideoResolution.W1280H720,
    val targetFps: Int = 30,
    val bitrateMbps: Int = 8,
    val audioRoutingMode: AudioRoutingMode = AudioRoutingMode.AARE_CHANNELS,
    val weatherCity: String = "Paris",
    val pinnedPackages: List<String> = emptyList()
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val CONNECTION_MODE = stringPreferencesKey("connection_mode")
        val WIRELESS_HOST = stringPreferencesKey("wireless_host")
        val WIRELESS_PORT = intPreferencesKey("wireless_port")
        val VIDEO_RES = stringPreferencesKey("video_resolution")
        val TARGET_FPS = intPreferencesKey("target_fps")
        val BITRATE_MBPS = intPreferencesKey("bitrate_mbps")
        val AUDIO_ROUTING = stringPreferencesKey("audio_routing")
        val WEATHER_CITY = stringPreferencesKey("weather_city")
        val PINNED_PACKAGES = stringPreferencesKey("pinned_packages")
    }

    val settingsFlow: Flow<UserSettings> = context.aareDataStore.data.map { prefs ->
        UserSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.AUTO_CAR,
            connectionMode = prefs[Keys.CONNECTION_MODE]?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
                ?: ConnectionMode.USB_AOAP,
            wirelessHost = prefs[Keys.WIRELESS_HOST] ?: "192.168.43.1",
            wirelessPort = prefs[Keys.WIRELESS_PORT] ?: 5277,
            videoResolution = prefs[Keys.VIDEO_RES]?.let { runCatching { VideoResolution.valueOf(it) }.getOrNull() }
                ?: VideoResolution.W1280H720,
            targetFps = prefs[Keys.TARGET_FPS] ?: 30,
            bitrateMbps = prefs[Keys.BITRATE_MBPS] ?: 8,
            audioRoutingMode = prefs[Keys.AUDIO_ROUTING]?.let { runCatching { AudioRoutingMode.valueOf(it) }.getOrNull() }
                ?: AudioRoutingMode.AARE_CHANNELS,
            weatherCity = prefs[Keys.WEATHER_CITY] ?: "Paris",
            pinnedPackages = prefs[Keys.PINNED_PACKAGES]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.aareDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setConnectionMode(mode: ConnectionMode) {
        context.aareDataStore.edit { it[Keys.CONNECTION_MODE] = mode.name }
    }

    suspend fun setWirelessEndpoint(host: String, port: Int) {
        context.aareDataStore.edit {
            it[Keys.WIRELESS_HOST] = host.trim()
            it[Keys.WIRELESS_PORT] = port.coerceIn(1024, 65535)
        }
    }

    suspend fun setVideoResolution(resolution: VideoResolution) {
        context.aareDataStore.edit { it[Keys.VIDEO_RES] = resolution.name }
    }

    suspend fun setTargetFps(fps: Int) {
        context.aareDataStore.edit { it[Keys.TARGET_FPS] = fps }
    }

    suspend fun setBitrateMbps(mbps: Int) {
        context.aareDataStore.edit { it[Keys.BITRATE_MBPS] = mbps.coerceIn(2, 20) }
    }

    suspend fun setAudioRouting(mode: AudioRoutingMode) {
        context.aareDataStore.edit { it[Keys.AUDIO_ROUTING] = mode.name }
    }

    suspend fun setWeatherCity(city: String) {
        context.aareDataStore.edit { it[Keys.WEATHER_CITY] = city.trim().ifEmpty { "Paris" } }
    }

    suspend fun togglePinnedPackage(packageName: String) {
        context.aareDataStore.edit { prefs ->
            val current = prefs[Keys.PINNED_PACKAGES]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toMutableList()
                ?: mutableListOf()
            if (current.contains(packageName)) {
                current.remove(packageName)
            } else {
                if (current.size >= 6) current.removeAt(0)
                current.add(packageName)
            }
            prefs[Keys.PINNED_PACKAGES] = current.joinToString(",")
        }
    }
}
