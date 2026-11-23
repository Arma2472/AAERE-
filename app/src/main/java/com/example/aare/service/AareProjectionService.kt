package com.example.aare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.aare.cert.CertificateManager
import com.example.aare.projection.VideoResolution
import com.example.aare.projection.VirtualDisplayController
import com.example.aare.protocol.AapProtocol
import com.example.aare.transport.AareTransport
import com.example.aare.transport.ConnectionMode
import com.example.aare.transport.UsbAoapTransport
import com.example.aare.transport.WirelessTcpClientTransport
import com.example.aare.transport.WirelessTcpServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

enum class AareConnectionStage(val label: String) {
    DISCONNECTED("En attente"),
    CONNECTING_TRANSPORT("Transport"),
    VERSION_EXCHANGE("Version AAP"),
    TLS_HANDSHAKE("TLS 1.2"),
    AUTH_CHECK("Auth"),
    CONNECTED("Connecté"),
    ERROR("Erreur")
}

data class ProtocolLogEntry(
    val timestamp: String,
    val channel: String,
    val message: String,
    val isError: Boolean = false
)

data class AareRuntimeState(
    val stage: AareConnectionStage = AareConnectionStage.DISCONNECTED,
    val activeMode: ConnectionMode = ConnectionMode.USB_AOAP,
    val transportLabel: String = "Aucun périphérique",
    val protocolVersion: String = "1.6",
    val cipherSuite: String = "—",
    val virtualDisplayId: Int? = null,
    val encodedFramesCount: Long = 0L,
    val bitrateMbps: Int = 8,
    val fps: Int = 30,
    val resolution: VideoResolution = VideoResolution.W1280H720,
    val carHeadlightsOn: Boolean? = null,
    val lastAuthStatus: Int? = null,
    val statusMessage: String = "Prêt pour connexion USB AOAP ou Wi-Fi",
    val usbAccessoryPresent: Boolean = false,
    val usbAccessoryName: String = "Aucun accessoire détecté",
    val logs: List<ProtocolLogEntry> = emptyList()
)

object AareRuntimeStore {
    private val _state = MutableStateFlow(AareRuntimeState())
    val state: StateFlow<AareRuntimeState> = _state.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun update(transform: (AareRuntimeState) -> AareRuntimeState) {
        _state.update(transform)
    }

    fun log(channel: String, message: String, isError: Boolean = false) {
        val entry = ProtocolLogEntry(
            timestamp = timeFormat.format(Date()),
            channel = channel,
            message = message,
            isError = isError
        )
        _state.update { current ->
            current.copy(logs = (current.logs + entry).takeLast(120))
        }
    }

    fun clearLogs() {
        _state.update { it.copy(logs = emptyList()) }
    }
}

/**
 * Foreground service maintaining AARE transport, AAP version negotiation,
 * TLS 1.2 mTLS handshake, VirtualDisplay H.264 streaming, and car sensor state.
 */
class AareProjectionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private var activeTransport: AareTransport? = null
    private lateinit var certificateManager: CertificateManager
    private lateinit var virtualDisplayController: VirtualDisplayController
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        certificateManager = CertificateManager(applicationContext)
        virtualDisplayController = VirtualDisplayController(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val modeName = intent.getStringExtra(EXTRA_MODE) ?: ConnectionMode.USB_AOAP.name
                val mode = runCatching { ConnectionMode.valueOf(modeName) }.getOrDefault(ConnectionMode.USB_AOAP)
                val host = intent.getStringExtra(EXTRA_HOST) ?: "192.168.43.1"
                val port = intent.getIntExtra(EXTRA_PORT, 5277)
                val resName = intent.getStringExtra(EXTRA_RES) ?: VideoResolution.W1280H720.name
                val resolution = runCatching { VideoResolution.valueOf(resName) }.getOrDefault(VideoResolution.W1280H720)
                val fps = intent.getIntExtra(EXTRA_FPS, 30)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 8)

                startForegroundCompat("Initialisation AARE ($mode)...")
                startSession(mode, host, port, resolution, fps, bitrate)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSession(
        mode: ConnectionMode,
        host: String,
        port: Int,
        resolution: VideoResolution,
        fps: Int,
        bitrateMbps: Int
    ) {
        stopSession()
        acquireWakeLock()

        sessionJob = serviceScope.launch {
            AareRuntimeStore.update {
                it.copy(
                    stage = AareConnectionStage.CONNECTING_TRANSPORT,
                    activeMode = mode,
                    resolution = resolution,
                    fps = fps,
                    bitrateMbps = bitrateMbps,
                    encodedFramesCount = 0L,
                    lastAuthStatus = null,
                    statusMessage = "Ouverture du transport ${mode.label}…"
                )
            }
            AareRuntimeStore.log("SYS", "Démarrage session AARE (${mode.label}, ${resolution.label} @ ${fps}fps)")

            try {
                val transport = openTransport(mode, host, port)
                activeTransport = transport
                AareRuntimeStore.update {
                    it.copy(
                        transportLabel = transport.label,
                        stage = AareConnectionStage.VERSION_EXCHANGE,
                        statusMessage = "Transport ouvert : ${transport.label}"
                    )
                }
                AareRuntimeStore.log("LINK", "Transport prêt : ${transport.label}")
                updateNotification("Connecté : ${transport.label}")

                runAapProtocolLoop(transport, resolution, fps, bitrateMbps)
            } catch (e: Exception) {
                val msg = e.message ?: "Connexion interrompue"
                AareRuntimeStore.log("ERR", msg, isError = true)
                AareRuntimeStore.update {
                    it.copy(
                        stage = AareConnectionStage.ERROR,
                        statusMessage = msg
                    )
                }
                updateNotification("Erreur : $msg")
            } finally {
                virtualDisplayController.stop()
                runCatching { activeTransport?.close() }
                activeTransport = null
                releaseWakeLock()
            }
        }
    }

    private fun openTransport(mode: ConnectionMode, host: String, port: Int): AareTransport {
        return when (mode) {
            ConnectionMode.USB_AOAP -> {
                val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                    ?: throw IllegalStateException("Service USB indisponible sur cet appareil")
                val accessory = UsbAoapTransport.findConnectedAccessory(this)
                    ?: throw IllegalStateException("Aucun autoradio USB AOAP détecté. Branchez le câble ou passez en mode Wi-Fi.")
                if (!usbManager.hasPermission(accessory)) {
                    UsbAoapTransport.requestPermission(this, accessory)
                    throw IllegalStateException("Permission USB requise. Acceptez la boîte de dialogue puis relancez.")
                }
                UsbAoapTransport(usbManager, accessory)
            }
            ConnectionMode.WIFI_CLIENT -> {
                AareRuntimeStore.log("TCP", "Connexion vers $host:$port…")
                WirelessTcpClientTransport(host, port)
            }
            ConnectionMode.WIFI_SERVER -> {
                AareRuntimeStore.log("TCP", "Écoute TCP entrante sur le port $port…")
                WirelessTcpServerTransport(port)
            }
        }
    }

    private suspend fun CoroutineScope.runAapProtocolLoop(
        transport: AareTransport,
        resolution: VideoResolution,
        fps: Int,
        bitrateMbps: Int
    ) {
        val input = transport.inputStream
        val output = transport.outputStream

        // 1. Wait for VERSION_REQUEST from Head Unit & reply with VERSION_RESPONSE (1.6)
        val versionFrame = AapProtocol.readFrame(input)
            ?: throw IllegalStateException("Flux fermé avant négociation de version")
        if (versionFrame.messageType() == AapProtocol.MSG_VERSION_REQUEST) {
            val remoteVer = AapProtocol.parseVersionPayload(versionFrame.messageBody())
            AareRuntimeStore.log("CH0", "VERSION_REQUEST reçu (${remoteVer?.first ?: 1}.${remoteVer?.second ?: 6})")
            AapProtocol.writeFrame(
                output = output,
                channelId = AapProtocol.CH_CONTROL,
                messageType = AapProtocol.MSG_VERSION_RESPONSE,
                body = AapProtocol.buildVersionPayload(AapProtocol.PROTOCOL_MAJOR, AapProtocol.PROTOCOL_MINOR),
                control = false,
                sslEngine = null
            )
            AareRuntimeStore.log("CH0", "VERSION_RESPONSE envoyé (1.6)")
        }

        // 2. TLS 1.2 Handshake encapsulated inside AAP Channel 0 (Phone = TLS Server)
        AareRuntimeStore.update {
            it.copy(
                stage = AareConnectionStage.TLS_HANDSHAKE,
                statusMessage = "Handshake TLS 1.2 en cours…"
            )
        }
        val sslContext = certificateManager.buildServerSslContext()
        val sslEngine = sslContext.createSSLEngine().apply {
            useClientMode = false
            enabledProtocols = arrayOf("TLSv1.2")
            wantClientAuth = true
        }
        sslEngine.beginHandshake()
        performAapTlsHandshake(sslEngine, input, output)

        val cipher = sslEngine.session.cipherSuite
        AareRuntimeStore.log("TLS", "Handshake TLS 1.2 établi ($cipher)")
        AareRuntimeStore.update {
            it.copy(
                stage = AareConnectionStage.AUTH_CHECK,
                cipherSuite = cipher,
                statusMessage = "Attente AUTH_COMPLETE de l'autoradio…"
            )
        }

        // 3. Wait for AUTH_COMPLETE & start Ping + VirtualDisplay once authenticated
        var channelsRunning = false
        val pingJob = launch {
            while (isActive) {
                delay(5000)
                if (channelsRunning) {
                    runCatching {
                        val payload = AapProtocol.encodeVarintField(1, System.currentTimeMillis())
                        AapProtocol.writeFrame(
                            output = output,
                            channelId = AapProtocol.CH_CONTROL,
                            messageType = AapProtocol.MSG_PING_REQUEST,
                            body = payload,
                            control = false,
                            sslEngine = sslEngine
                        )
                    }
                }
            }
        }

        try {
            while (isActive) {
                val frame = AapProtocol.readFrame(input) ?: break
                val clearPayload = if (frame.isEncrypted) {
                    AapProtocol.decryptTls(sslEngine, frame.payload)
                } else {
                    frame.payload
                }
                val clearFrame = frame.copy(payload = clearPayload)
                val msgType = clearFrame.messageType()

                when {
                    clearFrame.channelId == AapProtocol.CH_CONTROL && msgType == AapProtocol.MSG_AUTH_COMPLETE -> {
                        val status = AapProtocol.decodeFirstInt32Field(clearFrame.messageBody(), 1) ?: 0
                        val desc = AapProtocol.describeAuthStatus(status)
                        AareRuntimeStore.update { it.copy(lastAuthStatus = status, statusMessage = desc) }
                        if (status != AapProtocol.AUTH_STATUS_OK) {
                            AareRuntimeStore.log("AUTH", desc, isError = true)
                            throw IllegalStateException(desc)
                        }
                        AareRuntimeStore.log("AUTH", desc)
                        channelsRunning = true
                        startProjectionChannel(output, sslEngine, resolution, fps, bitrateMbps)
                    }
                    clearFrame.channelId == AapProtocol.CH_CONTROL && msgType == AapProtocol.MSG_SERVICE_DISCOVERY_REQUEST -> {
                        AareRuntimeStore.log("CH0", "SERVICE_DISCOVERY_REQUEST reçu → réponse capacités AARE")
                        val respBody = AapProtocol.encodeVarintField(1, 1)
                        AapProtocol.writeFrame(
                            output, AapProtocol.CH_CONTROL,
                            AapProtocol.MSG_SERVICE_DISCOVERY_RESPONSE, respBody, false, sslEngine
                        )
                    }
                    clearFrame.channelId == AapProtocol.CH_CONTROL && msgType == AapProtocol.MSG_CHANNEL_OPEN_REQUEST -> {
                        AareRuntimeStore.log("CH0", "CHANNEL_OPEN_REQUEST → canal ouvert (status=0)")
                        val respBody = AapProtocol.encodeVarintField(1, 0)
                        AapProtocol.writeFrame(
                            output, AapProtocol.CH_CONTROL,
                            AapProtocol.MSG_CHANNEL_OPEN_RESPONSE, respBody, false, sslEngine
                        )
                    }
                    clearFrame.channelId == AapProtocol.CH_CONTROL && msgType == AapProtocol.MSG_PING_REQUEST -> {
                        AapProtocol.writeFrame(
                            output, AapProtocol.CH_CONTROL,
                            AapProtocol.MSG_PING_RESPONSE, clearFrame.messageBody(), false, sslEngine
                        )
                    }
                    clearFrame.channelId == AapProtocol.CH_SENSOR && msgType == AapProtocol.MSG_SENSOR_EVENT_INDICATION -> {
                        val nightFlag = AapProtocol.decodeFirstInt32Field(clearFrame.messageBody(), 1)
                        if (nightFlag != null) {
                            val isNight = nightFlag != 0
                            AareRuntimeStore.update { it.copy(carHeadlightsOn = isNight) }
                            AareRuntimeStore.log("CH2-SENS", "Capteur phares véhicule : ${if (isNight) "NUIT" else "JOUR"}")
                        }
                    }
                    clearFrame.channelId == AapProtocol.CH_CONTROL && msgType == AapProtocol.MSG_SHUTDOWN_REQUEST -> {
                        AareRuntimeStore.log("CH0", "SHUTDOWN_REQUEST reçu de l'autoradio")
                        AapProtocol.writeFrame(
                            output, AapProtocol.CH_CONTROL,
                            AapProtocol.MSG_SHUTDOWN_RESPONSE, ByteArray(0), false, sslEngine
                        )
                        break
                    }
                    else -> {
                        AareRuntimeStore.log(
                            "CH${clearFrame.channelId}",
                            "Trame 0x${msgType.toString(16).uppercase()} (${clearFrame.payload.size} o)"
                        )
                    }
                }
            }
        } finally {
            pingJob.cancel()
        }
    }

    private fun performAapTlsHandshake(
        engine: SSLEngine,
        input: java.io.InputStream,
        output: java.io.OutputStream
    ) {
        val emptyApp = ByteBuffer.allocate(0)
        val scratchApp = ByteBuffer.allocate(32768)
        var pendingIn = ByteBuffer.allocate(0)

        while (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED &&
            engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
        ) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val outBuf = ByteBuffer.allocate(engine.session.packetBufferSize.coerceAtLeast(16384))
                    val res = engine.wrap(emptyApp, outBuf)
                    outBuf.flip()
                    if (outBuf.hasRemaining()) {
                        val bytes = ByteArray(outBuf.remaining())
                        outBuf.get(bytes)
                        AapProtocol.writeFrame(
                            output = output,
                            channelId = AapProtocol.CH_CONTROL,
                            messageType = AapProtocol.MSG_ENCAPSULATED_SSL,
                            body = bytes,
                            control = false,
                            sslEngine = null
                        )
                    }
                    if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) break
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!pendingIn.hasRemaining()) {
                        val frame = AapProtocol.readFrame(input)
                            ?: throw IllegalStateException("Flux fermé pendant le handshake TLS")
                        if (frame.messageType() != AapProtocol.MSG_ENCAPSULATED_SSL) {
                            throw IllegalStateException("Trame inattendue pendant TLS: 0x${frame.messageType().toString(16)}")
                        }
                        pendingIn = ByteBuffer.wrap(frame.messageBody())
                    }
                    scratchApp.clear()
                    val res = engine.unwrap(pendingIn, scratchApp)
                    if (res.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        val remaining = ByteArray(pendingIn.remaining())
                        pendingIn.get(remaining)
                        val frame = AapProtocol.readFrame(input)
                            ?: throw IllegalStateException("Flux TLS incomplet")
                        pendingIn = ByteBuffer.wrap(remaining + frame.messageBody())
                    }
                    if (res.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) break
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                }
                else -> break
            }
        }
    }

    private fun startProjectionChannel(
        output: java.io.OutputStream,
        sslEngine: SSLEngine,
        resolution: VideoResolution,
        fps: Int,
        bitrateMbps: Int
    ) {
        virtualDisplayController.start(
            resolution = resolution,
            fps = fps,
            bitrateMbps = bitrateMbps,
            onEncodedNalUnit = { nalBytes, ptsUs, isConfig ->
                runCatching {
                    val tsBytes = ByteBuffer.allocate(8).putLong(ptsUs).array()
                    val body = tsBytes + nalBytes
                    val msgType = if (isConfig) {
                        AapProtocol.MSG_AV_MEDIA_INDICATION
                    } else {
                        AapProtocol.MSG_AV_MEDIA_WITH_TIMESTAMP
                    }
                    AapProtocol.writeFrame(
                        output = output,
                        channelId = AapProtocol.CH_VIDEO,
                        messageType = msgType,
                        body = body,
                        control = false,
                        sslEngine = sslEngine
                    )
                    AareRuntimeStore.update { it.copy(encodedFramesCount = it.encodedFramesCount + 1) }
                }
            },
            onError = { err ->
                AareRuntimeStore.log("CH3-VID", err, isError = true)
            }
        )

        val displayId = virtualDisplayController.activeDisplayId
        AareRuntimeStore.update {
            it.copy(
                stage = AareConnectionStage.CONNECTED,
                virtualDisplayId = displayId,
                statusMessage = "Projection active · VirtualDisplay #$displayId"
            )
        }
        AareRuntimeStore.log("CH3-VID", "Flux H.264 prêt (${resolution.label} @ ${fps}fps, Display #$displayId)")
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        virtualDisplayController.stop()
        runCatching { activeTransport?.close() }
        activeTransport = null
        releaseWakeLock()
        AareRuntimeStore.update {
            it.copy(
                stage = AareConnectionStage.DISCONNECTED,
                virtualDisplayId = null,
                cipherSuite = "—",
                statusMessage = "Session arrêtée · En attente du véhicule"
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_aare_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_aare_desc)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundCompat(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, AareProjectionService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Récepteur AARE+")
            .setContentText(contentText)
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Arrêter", stopPi)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AarePlus::ProjectionLock").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "aare_projection_channel"
        const val NOTIFICATION_ID = 4201
        const val ACTION_START = "com.example.aare.ACTION_START"
        const val ACTION_STOP = "com.example.aare.ACTION_STOP"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_RES = "extra_res"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"

        fun start(
            context: Context,
            mode: ConnectionMode,
            host: String,
            port: Int,
            resolution: VideoResolution,
            fps: Int,
            bitrateMbps: Int
        ) {
            val intent = Intent(context, AareProjectionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_RES, resolution.name)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_BITRATE, bitrateMbps)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AareProjectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
