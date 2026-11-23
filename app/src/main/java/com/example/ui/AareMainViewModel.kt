package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aare.cert.CertificateInfo
import com.example.aare.cert.CertificateManager
import com.example.aare.cert.TlsHandshakeTestResult
import com.example.aare.projection.VideoResolution
import com.example.aare.projection.VirtualDisplayController
import com.example.aare.service.AareProjectionService
import com.example.aare.service.AareRuntimeState
import com.example.aare.service.AareRuntimeStore
import com.example.aare.transport.ConnectionMode
import com.example.aare.transport.UsbAoapTransport
import com.example.betterauto.apps.AppLauncherRepository
import com.example.betterauto.apps.LaunchableApp
import com.example.betterauto.data.AudioRoutingMode
import com.example.betterauto.data.SettingsRepository
import com.example.betterauto.data.UserSettings
import com.example.betterauto.media.CarMediaAndNotificationsBridge
import com.example.betterauto.media.CarNotificationItem
import com.example.betterauto.media.MediaPlaybackUiState
import com.example.betterauto.weather.WeatherRepository
import com.example.betterauto.weather.WeatherState
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CertificatesUiState(
    val activeCert: CertificateInfo? = null,
    val isLoading: Boolean = true,
    val statusBanner: String? = null,
    val isError: Boolean = false,
    val lastTlsTest: TlsHandshakeTestResult? = null
)

class AareMainViewModel(application: Application) : AndroidViewModel(application) {

    private val ctx = application.applicationContext
    private val settingsRepository = SettingsRepository(ctx)
    private val certificateManager = CertificateManager(ctx)
    private val weatherRepository = WeatherRepository(ctx)
    private val appLauncherRepository = AppLauncherRepository(ctx)
    private val displayController = VirtualDisplayController(ctx)

    val settings: StateFlow<UserSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val aareRuntime: StateFlow<AareRuntimeState> = AareRuntimeStore.state

    val mediaState: StateFlow<MediaPlaybackUiState> = CarMediaAndNotificationsBridge.mediaState
    val notifications: StateFlow<List<CarNotificationItem>> = CarMediaAndNotificationsBridge.notifications

    private val _weatherState = MutableStateFlow(WeatherState(isLoading = true))
    val weatherState: StateFlow<WeatherState> = _weatherState.asStateFlow()

    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()

    private val _certState = MutableStateFlow(CertificatesUiState())
    val certState: StateFlow<CertificatesUiState> = _certState.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    init {
        refreshCertificateInfo()
        refreshApps()
        refreshUsbAccessoryStatus()
        checkMediaAccess()
        viewModelScope.launch {
            settings.collect { current ->
                if (_weatherState.value.temperatureC == null && !_weatherState.value.isLoading ||
                    _weatherState.value.cityName != current.weatherCity
                ) {
                    refreshWeather(current.weatherCity)
                }
            }
        }
        refreshWeather(settings.value.weatherCity)
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun checkMediaAccess(): Boolean {
        return CarMediaAndNotificationsBridge.isNotificationAccessEnabled(ctx)
    }

    fun openNotificationListenerSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }

    fun openDialer() {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }

    fun refreshUsbAccessoryStatus() {
        val accessory = UsbAoapTransport.findConnectedAccessory(ctx)
        AareRuntimeStore.update {
            it.copy(
                usbAccessoryPresent = accessory != null,
                usbAccessoryName = if (accessory != null) {
                    "${accessory.manufacturer} ${accessory.model}".trim()
                } else {
                    "Aucun accessoire USB AOAP détecté"
                }
            )
        }
    }

    fun startOrStopReceiver() {
        val currentStage = aareRuntime.value.stage
        if (currentStage != com.example.aare.service.AareConnectionStage.DISCONNECTED &&
            currentStage != com.example.aare.service.AareConnectionStage.ERROR
        ) {
            AareProjectionService.stop(ctx)
            return
        }
        val s = settings.value
        AareProjectionService.start(
            context = ctx,
            mode = s.connectionMode,
            host = s.wirelessHost,
            port = s.wirelessPort,
            resolution = s.videoResolution,
            fps = s.targetFps,
            bitrateMbps = s.bitrateMbps
        )
    }

    fun toggleCarHeadlightsSimulation() {
        val current = aareRuntime.value.carHeadlightsOn ?: false
        val next = !current
        AareRuntimeStore.update { it.copy(carHeadlightsOn = next) }
        AareRuntimeStore.log("CH2-SENS", "Simulation phares véhicule : ${if (next) "NUIT (allumés)" else "JOUR (éteints)"}")
    }

    fun clearProtocolLogs() {
        AareRuntimeStore.clearLogs()
    }

    // --- Media Controls ---
    fun togglePlayPause() = CarMediaAndNotificationsBridge.togglePlayPause(ctx)
    fun skipNext() = CarMediaAndNotificationsBridge.skipNext(ctx)
    fun skipPrevious() = CarMediaAndNotificationsBridge.skipPrevious(ctx)
    fun dismissNotification(key: String) = CarMediaAndNotificationsBridge.removeNotification(key)

    // --- Apps ---
    fun refreshApps() {
        viewModelScope.launch {
            _apps.value = appLauncherRepository.loadLaunchableApps()
        }
    }

    fun launchApp(packageName: String) {
        val outcome = displayController.launchApp(packageName)
        _toastMessage.value = outcome.detail
        AareRuntimeStore.log("APP", "Lancement $packageName → ${outcome.detail}", isError = !outcome.launched)
    }

    fun togglePinnedApp(packageName: String) {
        viewModelScope.launch {
            settingsRepository.togglePinnedPackage(packageName)
        }
    }

    // --- Weather ---
    fun refreshWeather(cityOverride: String? = null) {
        viewModelScope.launch {
            val city = cityOverride ?: settings.value.weatherCity
            _weatherState.update { it.copy(isLoading = true, errorMessage = null) }
            _weatherState.value = weatherRepository.fetchWeather(city)
        }
    }

    fun updateWeatherCity(city: String) {
        viewModelScope.launch {
            settingsRepository.setWeatherCity(city)
            refreshWeather(city)
        }
    }

    // --- Certificates ---
    fun refreshCertificateInfo() {
        viewModelScope.launch {
            _certState.update { it.copy(isLoading = true) }
            runCatching { certificateManager.getActiveCertificateInfo() }
                .onSuccess { info ->
                    _certState.update { it.copy(activeCert = info, isLoading = false) }
                }
                .onFailure { err ->
                    _certState.update {
                        it.copy(
                            isLoading = false,
                            statusBanner = err.message ?: "Erreur certificat",
                            isError = true
                        )
                    }
                }
        }
    }

    fun importPkcs12(uri: Uri, password: String?) {
        viewModelScope.launch {
            _certState.update { it.copy(isLoading = true, statusBanner = null) }
            runCatching { certificateManager.importPkcs12FromUri(uri, password) }
                .onSuccess { info ->
                    _certState.update {
                        it.copy(
                            activeCert = info,
                            isLoading = false,
                            statusBanner = "Certificat .p12 importé avec succès",
                            isError = false
                        )
                    }
                    AareRuntimeStore.log("TLS", "Nouveau certificat PKCS#12 importé (${info.sha256Fingerprint.take(17)}…)")
                }
                .onFailure { err ->
                    _certState.update {
                        it.copy(
                            isLoading = false,
                            statusBanner = err.message ?: "Échec de l'import .p12",
                            isError = true
                        )
                    }
                }
        }
    }

    fun importPemPair(certUri: Uri, keyUri: Uri) {
        viewModelScope.launch {
            _certState.update { it.copy(isLoading = true, statusBanner = null) }
            runCatching { certificateManager.importPemPairFromUris(certUri, keyUri) }
                .onSuccess { info ->
                    _certState.update {
                        it.copy(
                            activeCert = info,
                            isLoading = false,
                            statusBanner = "Paire PEM certificat + clé importée avec succès",
                            isError = false
                        )
                    }
                    AareRuntimeStore.log("TLS", "Nouvelle paire PEM importée (${info.sha256Fingerprint.take(17)}…)")
                }
                .onFailure { err ->
                    _certState.update {
                        it.copy(
                            isLoading = false,
                            statusBanner = err.message ?: "Échec de l'import PEM",
                            isError = true
                        )
                    }
                }
        }
    }

    fun resetFallbackCertificate() {
        viewModelScope.launch {
            _certState.update { it.copy(isLoading = true, statusBanner = null) }
            runCatching { certificateManager.resetToFallbackCertificate() }
                .onSuccess { info ->
                    _certState.update {
                        it.copy(
                            activeCert = info,
                            isLoading = false,
                            statusBanner = "Certificat secours AndroidKeyStore régénéré",
                            isError = false
                        )
                    }
                    AareRuntimeStore.log("TLS", "Certificat secours AndroidKeyStore régénéré")
                }
                .onFailure { err ->
                    _certState.update {
                        it.copy(isLoading = false, statusBanner = err.message, isError = true)
                    }
                }
        }
    }

    fun runTlsSelfTest() {
        viewModelScope.launch {
            _certState.update { it.copy(isLoading = true) }
            val result = certificateManager.runLocalTlsHandshakeSelfTest()
            _certState.update {
                it.copy(
                    isLoading = false,
                    lastTlsTest = result,
                    statusBanner = result.message,
                    isError = !result.success
                )
            }
            AareRuntimeStore.log("TLS", result.message, isError = !result.success)
        }
    }

    // --- Settings Updates ---
    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setConnectionMode(mode: ConnectionMode) = viewModelScope.launch { settingsRepository.setConnectionMode(mode) }
    fun setWirelessEndpoint(host: String, port: Int) = viewModelScope.launch { settingsRepository.setWirelessEndpoint(host, port) }
    fun setVideoResolution(res: VideoResolution) = viewModelScope.launch { settingsRepository.setVideoResolution(res) }
    fun setTargetFps(fps: Int) = viewModelScope.launch { settingsRepository.setTargetFps(fps) }
    fun setBitrateMbps(mbps: Int) = viewModelScope.launch { settingsRepository.setBitrateMbps(mbps) }
    fun setAudioRouting(mode: AudioRoutingMode) = viewModelScope.launch { settingsRepository.setAudioRouting(mode) }
}
