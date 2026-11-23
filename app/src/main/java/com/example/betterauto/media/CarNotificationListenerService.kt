package com.example.betterauto.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaPlaybackUiState(
    val title: String = "Aucune lecture active",
    val artist: String = "Lancez Spotify, Deezer ou YT Music",
    val album: String = "",
    val sourcePackage: String = "",
    val isPlaying: Boolean = false,
    val artwork: Bitmap? = null,
    val hasMediaSessionAccess: Boolean = false
)

data class CarNotificationItem(
    val key: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timeLabel: String,
    val isCallOrUrgent: Boolean
)

/**
 * Shared BetterAuto media & notification bridge supporting Android 10 (API 29) through Android 16.
 */
object CarMediaAndNotificationsBridge {
    private val _mediaState = MutableStateFlow(MediaPlaybackUiState())
    val mediaState: StateFlow<MediaPlaybackUiState> = _mediaState.asStateFlow()

    private val _notifications = MutableStateFlow<List<CarNotificationItem>>(emptyList())
    val notifications: StateFlow<List<CarNotificationItem>> = _notifications.asStateFlow()

    @Volatile
    private var activeController: MediaController? = null

    fun isNotificationAccessEnabled(context: Context): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
        val enabled = enabledPackages.contains(context.packageName)
        _mediaState.update { it.copy(hasMediaSessionAccess = enabled) }
        return enabled
    }

    fun updateController(controller: MediaController?, hasAccess: Boolean) {
        activeController = controller
        if (controller == null) {
            _mediaState.update {
                it.copy(
                    title = "Aucune lecture active",
                    artist = if (hasAccess) "Prêt · lancez votre lecteur audio" else "Activez l'accès aux notifications pour afficher le titre",
                    album = "",
                    sourcePackage = "",
                    isPlaying = false,
                    artwork = null,
                    hasMediaSessionAccess = hasAccess
                )
            }
            return
        }

        val meta = controller.metadata
        val playback = controller.playbackState
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Lecture en cours"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: controller.packageName
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val isPlaying = playback?.state == PlaybackState.STATE_PLAYING ||
            playback?.state == PlaybackState.STATE_BUFFERING

        _mediaState.update {
            it.copy(
                title = title,
                artist = artist,
                album = album,
                sourcePackage = controller.packageName,
                isPlaying = isPlaying,
                artwork = art,
                hasMediaSessionAccess = hasAccess
            )
        }
    }

    fun setNotifications(items: List<CarNotificationItem>) {
        _notifications.value = items
    }

    fun removeNotification(key: String) {
        _notifications.update { list -> list.filterNot { it.key == key } }
    }

    fun togglePlayPause(context: Context) {
        val controller = activeController
        if (controller != null) {
            val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            if (isPlaying) controller.transportControls.pause() else controller.transportControls.play()
        } else {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            _mediaState.update { it.copy(isPlaying = !it.isPlaying) }
        }
    }

    fun skipNext(context: Context) {
        val controller = activeController
        if (controller != null) {
            controller.transportControls.skipToNext()
        } else {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    fun skipPrevious(context: Context) {
        val controller = activeController
        if (controller != null) {
            controller.transportControls.skipToPrevious()
        } else {
            dispatchMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    private fun dispatchMediaKey(context: Context, keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}

class CarNotificationListenerService : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var currentController: MediaController? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            CarMediaAndNotificationsBridge.updateController(currentController, true)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            CarMediaAndNotificationsBridge.updateController(currentController, true)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        bindBestController(controllers.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, CarNotificationListenerService::class.java)
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        runCatching {
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionsListener, component)
            val initial = mediaSessionManager?.getActiveSessions(component).orEmpty()
            bindBestController(initial)
        }
        refreshNotifications()
    }

    override fun onListenerDisconnected() {
        runCatching { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
        CarMediaAndNotificationsBridge.updateController(null, false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshNotifications()
    }

    private fun bindBestController(controllers: List<MediaController>) {
        currentController?.unregisterCallback(controllerCallback)
        val chosen = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()
        currentController = chosen
        chosen?.registerCallback(controllerCallback)
        CarMediaAndNotificationsBridge.updateController(chosen, true)
    }

    private fun refreshNotifications() {
        val active = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        val pm = packageManager
        val items = active
            .filter { sbn ->
                sbn.packageName != packageName &&
                    !sbn.isOngoing &&
                    (sbn.notification.extras.getCharSequence("android.title") != null ||
                        sbn.notification.extras.getCharSequence("android.text") != null)
            }
            .sortedByDescending { it.postTime }
            .take(8)
            .map { sbn ->
                val extras = sbn.notification.extras
                val title = extras.getCharSequence("android.title")?.toString().orEmpty()
                val text = extras.getCharSequence("android.text")?.toString().orEmpty()
                val appLabel = runCatching {
                    val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getApplicationInfo(sbn.packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getApplicationInfo(sbn.packageName, 0)
                    }
                    pm.getApplicationLabel(ai).toString()
                }.getOrDefault(sbn.packageName)

                val category = sbn.notification.category
                val isUrgent = category == android.app.Notification.CATEGORY_CALL ||
                    category == android.app.Notification.CATEGORY_MESSAGE

                CarNotificationItem(
                    key = sbn.key,
                    appName = appLabel,
                    packageName = sbn.packageName,
                    title = title.ifBlank { appLabel },
                    text = text.ifBlank { "Nouvelle notification" },
                    timeLabel = timeFormat.format(Date(sbn.postTime)),
                    isCallOrUrgent = isUrgent
                )
            }
        CarMediaAndNotificationsBridge.setNotifications(items)
    }
}
