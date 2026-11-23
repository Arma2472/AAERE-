package com.example.aare.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

enum class ConnectionMode(val label: String, val subtitle: String) {
    USB_AOAP("USB AOAP", "Câble USB vers autoradio (Android Open Accessory)"),
    WIFI_CLIENT("Wi-Fi Client", "Connexion TCP vers récepteur sans-fil"),
    WIFI_SERVER("Wi-Fi Serveur", "Écoute TCP entrante (AA Wireless / Headunit)")
}

interface AareTransport {
    val label: String
    val inputStream: InputStream
    val outputStream: OutputStream
    fun close()
}

class UsbAoapTransport(
    private val usbManager: UsbManager,
    private val accessory: UsbAccessory
) : AareTransport {

    private val pfd: ParcelFileDescriptor = usbManager.openAccessory(accessory)
        ?: throw IllegalStateException("Impossible d'ouvrir l'accessoire USB AOAP (${accessory.model})")

    override val label: String = "USB AOAP · ${accessory.manufacturer} ${accessory.model}".trim()
    override val inputStream: InputStream = BufferedInputStream(FileInputStream(pfd.fileDescriptor), 32768)
    override val outputStream: OutputStream = BufferedOutputStream(FileOutputStream(pfd.fileDescriptor), 32768)

    override fun close() {
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { pfd.close() }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.aare.USB_PERMISSION"

        fun findConnectedAccessory(context: Context): UsbAccessory? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
            val list = usbManager.accessoryList ?: return null
            return list.firstOrNull {
                val model = it.model.orEmpty()
                model.contains("Android", ignoreCase = true) ||
                    model.contains("Auto", ignoreCase = true) ||
                    it.manufacturer.orEmpty().contains("Android", ignoreCase = true)
            } ?: list.firstOrNull()
        }

        fun hasPermission(context: Context, accessory: UsbAccessory): Boolean {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            return usbManager.hasPermission(accessory)
        }

        fun requestPermission(context: Context, accessory: UsbAccessory) {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(accessory, pendingIntent)
        }

        @Suppress("DEPRECATION")
        fun extractAccessoryFromIntent(intent: Intent): UsbAccessory? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
            } else {
                intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
            }
        }
    }
}

class WirelessTcpClientTransport(
    host: String,
    port: Int,
    timeoutMs: Int = 8000
) : AareTransport {

    private val socket: Socket = Socket().apply {
        tcpNoDelay = true
        keepAlive = true
        soTimeout = 15000
        connect(InetSocketAddress(host, port), timeoutMs)
    }

    override val label: String = "Wi-Fi TCP · $host:$port"
    override val inputStream: InputStream = BufferedInputStream(socket.getInputStream(), 32768)
    override val outputStream: OutputStream = BufferedOutputStream(socket.getOutputStream(), 32768)

    override fun close() {
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { socket.close() }
    }
}

class WirelessTcpServerTransport(
    private val port: Int,
    private val acceptTimeoutMs: Int = 60000
) : AareTransport {

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
        soTimeout = acceptTimeoutMs
    }

    private val clientSocket: Socket = try {
        serverSocket.accept().apply {
            tcpNoDelay = true
            keepAlive = true
            soTimeout = 15000
        }
    } catch (e: Exception) {
        runCatching { serverSocket.close() }
        throw e
    }

    override val label: String = "Wi-Fi Serveur · ${clientSocket.inetAddress?.hostAddress ?: "client"}:$port"
    override val inputStream: InputStream = BufferedInputStream(clientSocket.getInputStream(), 32768)
    override val outputStream: OutputStream = BufferedOutputStream(clientSocket.getOutputStream(), 32768)

    override fun close() {
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { clientSocket.close() }
        runCatching { serverSocket.close() }
    }
}
