package com.example.aare.projection

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import kotlin.concurrent.thread

enum class VideoResolution(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val label: String
) {
    W800H480(800, 480, 160, "800×480 (SD Voiture)"),
    W1280H720(1280, 720, 213, "1280×720 (HD 720p)"),
    W1920H1080(1920, 1080, 280, "1920×1080 (Full HD)")
}

/**
 * Manages AARE VirtualDisplay and hardware H.264 MediaCodec encoder surface,
 * and handles launching Android apps directly onto the car virtual display (API 26+, Android 10 ready).
 */
class VirtualDisplayController(private val context: Context) {

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var running = false
    private var encoderThread: Thread? = null

    var activeDisplayId: Int? = null
        private set

    fun start(
        resolution: VideoResolution,
        fps: Int,
        bitrateMbps: Int,
        onEncodedNalUnit: (ByteArray, Long, Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                resolution.width,
                resolution.height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()

            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val vd = dm.createVirtualDisplay(
                "AARE_Car_Display",
                resolution.width,
                resolution.height,
                resolution.dpi,
                surface,
                flags
            )

            mediaCodec = codec
            inputSurface = surface
            virtualDisplay = vd
            activeDisplayId = vd.display?.displayId
            running = true

            encoderThread = thread(start = true, name = "AareH264Encoder") {
                val bufferInfo = MediaCodec.BufferInfo()
                while (running) {
                    try {
                        val index = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                        if (index >= 0) {
                            val encodedBuf = codec.getOutputBuffer(index)
                            if (encodedBuf != null && bufferInfo.size > 0) {
                                encodedBuf.position(bufferInfo.offset)
                                encodedBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val data = ByteArray(bufferInfo.size)
                                encodedBuf.get(data)
                                val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                onEncodedNalUnit(data, bufferInfo.presentationTimeUs, isConfig)
                            }
                            codec.releaseOutputBuffer(index, false)
                        }
                    } catch (e: Exception) {
                        if (running) {
                            onError(e.message ?: "Erreur encodeur H.264")
                        }
                        break
                    }
                }
            }
        } catch (e: Exception) {
            stop()
            onError(e.message ?: "Impossible de créer le VirtualDisplay H.264")
        }
    }

    fun stop() {
        running = false
        encoderThread?.interrupt()
        encoderThread = null
        activeDisplayId = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
    }

    /**
     * Launches an app on the AARE VirtualDisplay when active (API 26+, Android 10 compatible),
     * falling back to standard screen launch when disconnected.
     */
    fun launchApp(packageName: String): LaunchOutcome {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: return LaunchOutcome(false, "Aucune activité de lancement pour $packageName")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val displayId = activeDisplayId
        return try {
            if (displayId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
                context.startActivity(launchIntent, options.toBundle())
                LaunchOutcome(true, "Lancé sur l'écran virtuel AARE (#$displayId)")
            } else {
                context.startActivity(launchIntent)
                LaunchOutcome(true, "Lancé sur l'écran principal")
            }
        } catch (e: Exception) {
            // Fallback to normal launch if OEM restricts secondary display launch
            runCatching {
                val fallback = pm.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (fallback != null) {
                    context.startActivity(fallback)
                    return LaunchOutcome(true, "Lancé en mode repli sur l'écran téléphone")
                }
            }
            LaunchOutcome(false, e.message ?: "Échec du lancement")
        }
    }
}

data class LaunchOutcome(
    val launched: Boolean,
    val detail: String
)
