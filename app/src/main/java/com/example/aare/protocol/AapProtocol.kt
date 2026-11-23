package com.example.aare.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * Android Auto Protocol (AAP) framing, channel definitions, and TLS-over-AAP wrapper
 * preserving the AARE architecture and protocol semantics.
 */
object AapProtocol {
    // AAP Channel IDs
    const val CH_CONTROL: Byte = 0
    const val CH_INPUT: Byte = 1
    const val CH_SENSOR: Byte = 2
    const val CH_VIDEO: Byte = 3
    const val CH_MEDIA_AUDIO: Byte = 4
    const val CH_SPEECH_AUDIO: Byte = 5
    const val CH_SYSTEM_AUDIO: Byte = 6
    const val CH_AV_INPUT: Byte = 7

    // Frame Flags (Header Byte 1)
    const val FLAG_BULK: Int = 0x00
    const val FLAG_FIRST: Int = 0x01
    const val FLAG_LAST: Int = 0x02
    const val FLAG_SINGLE: Int = FLAG_FIRST or FLAG_LAST // 0x03
    const val FLAG_CONTROL: Int = 0x04
    const val FLAG_ENCRYPTED: Int = 0x08

    // Control Channel Message Types
    const val MSG_VERSION_REQUEST: Int = 0x0001
    const val MSG_VERSION_RESPONSE: Int = 0x0002
    const val MSG_ENCAPSULATED_SSL: Int = 0x0003
    const val MSG_AUTH_COMPLETE: Int = 0x0004
    const val MSG_SERVICE_DISCOVERY_REQUEST: Int = 0x0005
    const val MSG_SERVICE_DISCOVERY_RESPONSE: Int = 0x0006
    const val MSG_CHANNEL_OPEN_REQUEST: Int = 0x0007
    const val MSG_CHANNEL_OPEN_RESPONSE: Int = 0x0008
    const val MSG_PING_REQUEST: Int = 0x000B
    const val MSG_PING_RESPONSE: Int = 0x000C
    const val MSG_NAV_FOCUS_REQUEST: Int = 0x000D
    const val MSG_NAV_FOCUS_RESPONSE: Int = 0x000E
    const val MSG_SHUTDOWN_REQUEST: Int = 0x000F
    const val MSG_SHUTDOWN_RESPONSE: Int = 0x0010
    const val MSG_VOICE_SESSION_REQUEST: Int = 0x0011
    const val MSG_AUDIO_FOCUS_REQUEST: Int = 0x0012
    const val MSG_AUDIO_FOCUS_RESPONSE: Int = 0x0013

    // Media / Video / Input / Sensor Message Types
    const val MSG_AV_MEDIA_WITH_TIMESTAMP: Int = 0x0000
    const val MSG_AV_MEDIA_INDICATION: Int = 0x0001
    const val MSG_AV_MEDIA_ACK: Int = 0x8004
    const val MSG_AV_SETUP_REQUEST: Int = 0x8000
    const val MSG_AV_SETUP_RESPONSE: Int = 0x8001
    const val MSG_AV_START_INDICATION: Int = 0x8001
    const val MSG_AV_VIDEO_FOCUS_REQUEST: Int = 0x8007
    const val MSG_AV_VIDEO_FOCUS_INDICATION: Int = 0x8008
    const val MSG_INPUT_EVENT_INDICATION: Int = 0x8001
    const val MSG_INPUT_BINDING_REQUEST: Int = 0x8002
    const val MSG_INPUT_BINDING_RESPONSE: Int = 0x8003
    const val MSG_SENSOR_START_REQUEST: Int = 0x8001
    const val MSG_SENSOR_START_RESPONSE: Int = 0x8002
    const val MSG_SENSOR_EVENT_INDICATION: Int = 0x8003

    // Sensor Types
    const val SENSOR_TYPE_NIGHT_DATA: Int = 10

    // Auth Complete Statuses
    const val AUTH_STATUS_OK: Int = 0
    const val AUTH_STATUS_CERT_REJECTED: Int = -3
    const val AUTH_STATUS_CERT_EXPIRED: Int = -24

    const val PROTOCOL_MAJOR: Int = 1
    const val PROTOCOL_MINOR: Int = 6
    const val MAX_FRAME_PAYLOAD: Int = 16384

    data class AapFrame(
        val channelId: Byte,
        val flags: Int,
        val payload: ByteArray
    ) {
        val isEncrypted: Boolean get() = (flags and FLAG_ENCRYPTED) != 0
        val isControlMessage: Boolean get() = (flags and FLAG_CONTROL) != 0
        val isFirst: Boolean get() = (flags and FLAG_FIRST) != 0
        val isLast: Boolean get() = (flags and FLAG_LAST) != 0

        fun messageType(): Int {
            if (payload.size < 2) return -1
            return ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        }

        fun messageBody(): ByteArray {
            return if (payload.size <= 2) ByteArray(0) else payload.copyOfRange(2, payload.size)
        }
    }

    fun readExact(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read == -1) {
                return if (offset == 0) null else throw IllegalStateException("Flux AAP interrompu ($offset/$count octets)")
            }
            offset += read
        }
        return buffer
    }

    fun readFrame(input: InputStream): AapFrame? {
        val assembled = ByteArrayOutputStream()
        var channelId: Byte = 0
        var baseFlags = 0

        while (true) {
            val header = readExact(input, 4) ?: return null
            channelId = header[0]
            val flags = header[1].toInt() and 0xFF
            val frameLen = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

            val isFirst = (flags and FLAG_FIRST) != 0
            val isLast = (flags and FLAG_LAST) != 0
            if (isFirst && !isLast) {
                // Skip 4-byte total message length prefix on multi-fragment FIRST frames
                readExact(input, 4) ?: return null
            }

            val chunk = if (frameLen > 0) {
                readExact(input, frameLen) ?: return null
            } else {
                ByteArray(0)
            }
            assembled.write(chunk)
            baseFlags = flags

            if (isLast || (flags and FLAG_SINGLE) == 0) {
                break
            }
        }

        return AapFrame(channelId = channelId, flags = baseFlags, payload = assembled.toByteArray())
    }

    fun writeFrame(
        output: OutputStream,
        channelId: Byte,
        messageType: Int,
        body: ByteArray,
        control: Boolean = false,
        sslEngine: SSLEngine? = null
    ) {
        val rawPayload = ByteArray(2 + body.size)
        rawPayload[0] = ((messageType ushr 8) and 0xFF).toByte()
        rawPayload[1] = (messageType and 0xFF).toByte()
        if (body.isNotEmpty()) {
            System.arraycopy(body, 0, rawPayload, 2, body.size)
        }

        val finalPayload: ByteArray
        var flags = FLAG_SINGLE
        if (control) flags = flags or FLAG_CONTROL

        if (sslEngine != null) {
            finalPayload = encryptTls(sslEngine, rawPayload)
            flags = flags or FLAG_ENCRYPTED
        } else {
            finalPayload = rawPayload
        }

        if (finalPayload.size <= MAX_FRAME_PAYLOAD) {
            val header = byteArrayOf(
                channelId,
                flags.toByte(),
                ((finalPayload.size ushr 8) and 0xFF).toByte(),
                (finalPayload.size and 0xFF).toByte()
            )
            synchronized(output) {
                output.write(header)
                output.write(finalPayload)
                output.flush()
            }
        } else {
            // Fragment large payloads (e.g. H.264 IDR frames) across AAP frames
            var offset = 0
            val totalLen = finalPayload.size
            while (offset < totalLen) {
                val chunkSize = minOf(MAX_FRAME_PAYLOAD, totalLen - offset)
                val isFirst = offset == 0
                val isLast = offset + chunkSize >= totalLen
                var fragFlags = 0
                if (isFirst) fragFlags = fragFlags or FLAG_FIRST
                if (isLast) fragFlags = fragFlags or FLAG_LAST
                if (control) fragFlags = fragFlags or FLAG_CONTROL
                if (sslEngine != null) fragFlags = fragFlags or FLAG_ENCRYPTED

                synchronized(output) {
                    output.write(
                        byteArrayOf(
                            channelId,
                            fragFlags.toByte(),
                            ((chunkSize ushr 8) and 0xFF).toByte(),
                            (chunkSize and 0xFF).toByte()
                        )
                    )
                    if (isFirst && !isLast) {
                        val totalBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(totalLen).array()
                        output.write(totalBytes)
                    }
                    output.write(finalPayload, offset, chunkSize)
                }
                offset += chunkSize
            }
            synchronized(output) { output.flush() }
        }
    }

    fun encryptTls(engine: SSLEngine, plain: ByteArray): ByteArray {
        val appBuf = ByteBuffer.wrap(plain)
        val packetSize = engine.session.packetBufferSize.coerceAtLeast(plain.size + 256)
        val outBuf = ByteBuffer.allocate(packetSize)
        val resultStream = ByteArrayOutputStream()
        while (appBuf.hasRemaining()) {
            outBuf.clear()
            val res = engine.wrap(appBuf, outBuf)
            outBuf.flip()
            val bytes = ByteArray(outBuf.remaining())
            outBuf.get(bytes)
            resultStream.write(bytes)
            if (res.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                continue
            }
            if (res.status != SSLEngineResult.Status.OK) {
                break
            }
        }
        return resultStream.toByteArray()
    }

    fun decryptTls(engine: SSLEngine, encrypted: ByteArray): ByteArray {
        val netBuf = ByteBuffer.wrap(encrypted)
        val appSize = engine.session.applicationBufferSize.coerceAtLeast(encrypted.size + 1024)
        val appBuf = ByteBuffer.allocate(appSize)
        val resultStream = ByteArrayOutputStream()
        while (netBuf.hasRemaining()) {
            appBuf.clear()
            val res = engine.unwrap(netBuf, appBuf)
            appBuf.flip()
            if (appBuf.hasRemaining()) {
                val bytes = ByteArray(appBuf.remaining())
                appBuf.get(bytes)
                resultStream.write(bytes)
            }
            if (res.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                break
            }
        }
        return resultStream.toByteArray()
    }

    fun buildVersionPayload(major: Int = PROTOCOL_MAJOR, minor: Int = PROTOCOL_MINOR): ByteArray {
        return byteArrayOf(
            ((major ushr 8) and 0xFF).toByte(),
            (major and 0xFF).toByte(),
            ((minor ushr 8) and 0xFF).toByte(),
            (minor and 0xFF).toByte()
        )
    }

    fun parseVersionPayload(body: ByteArray): Pair<Int, Int>? {
        if (body.size < 4) return null
        val major = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val minor = ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        return major to minor
    }

    /**
     * Minimal protobuf varint encoder/decoder for AAP control payloads (AuthComplete, Ping, NightSensor).
     */
    fun encodeVarintField(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((fieldNumber shl 3) or 0).toLong())
        writeVarint(out, value)
        return out.toByteArray()
    }

    fun encodeSignedInt32Field(fieldNumber: Int, value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, ((fieldNumber shl 3) or 0).toLong())
        writeVarint(out, value.toLong() and 0xFFFFFFFFL)
        return out.toByteArray()
    }

    fun decodeFirstInt32Field(data: ByteArray, targetField: Int): Int? {
        var idx = 0
        while (idx < data.size) {
            val (tag, nextIdx) = readVarint(data, idx) ?: return null
            idx = nextIdx
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 0x07).toInt()
            when (wireType) {
                0 -> {
                    val (v, afterVal) = readVarint(data, idx) ?: return null
                    if (fieldNum == targetField) return v.toInt()
                    idx = afterVal
                }
                2 -> {
                    val (len, afterLen) = readVarint(data, idx) ?: return null
                    idx = afterLen + len.toInt()
                }
                1 -> idx += 8
                5 -> idx += 4
                else -> return null
            }
        }
        return null
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var idx = start
        while (idx < data.size && shift < 64) {
            val b = data[idx++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return result to idx
            shift += 7
        }
        return null
    }

    fun describeAuthStatus(status: Int): String = when (status) {
        AUTH_STATUS_OK -> "Authentification acceptée par l'autoradio (status 0)"
        AUTH_STATUS_CERT_REJECTED -> "Certificat téléphone refusé (status -3) : importez le certificat Google CarService (.p12/.pem)"
        AUTH_STATUS_CERT_EXPIRED -> "Certificat expiré (status -24) : mettez à jour le certificat .p12/.pem"
        else -> "Statut AUTH_COMPLETE inattendu ($status)"
    }
}
