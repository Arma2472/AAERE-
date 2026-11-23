package com.example.aare.cert

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

enum class CertificateOrigin(val label: String) {
    ANDROID_KEYSTORE_FALLBACK("Certificat secours AndroidKeyStore"),
    IMPORTED_PKCS12("Certificat importé (.p12 / .pfx)"),
    IMPORTED_PEM("Certificat importé (.pem / .crt + .key)")
}

data class CertificateInfo(
    val alias: String,
    val subject: String,
    val issuer: String,
    val sha256Fingerprint: String,
    val validFrom: String,
    val validUntil: String,
    val isExpired: Boolean,
    val isGoogleCarService: Boolean,
    val origin: CertificateOrigin,
    val chainLength: Int
)

data class TlsHandshakeTestResult(
    val success: Boolean,
    val protocol: String,
    val cipherSuite: String,
    val durationMs: Long,
    val message: String
)

/**
 * Manages AARE mTLS certificates:
 * - Supports importing official Google CarService .p12/.pfx or .pem certificates via SAF.
 * - Falls back to a genuine RSA-2048 X.509 certificate generated in AndroidKeyStore (API 23+, Android 10 safe).
 * - Builds TLS 1.2 SSLContext acting as TLS Server for AAP Channel 0 ENCAPSULATED_SSL.
 */
class CertificateManager(private val context: Context) {

    private val certsDir = File(context.filesDir, "aare_certs").apply { mkdirs() }
    private val importedP12File = File(certsDir, "phone_cert.p12")
    private val importedP12PassFile = File(certsDir, "phone_cert.pass")
    private val importedPemCertFile = File(certsDir, "phone_cert.pem")
    private val importedPemKeyFile = File(certsDir, "phone_key.pem")
    private val dateFormatter = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val FALLBACK_ALIAS = "aare_fallback_phone_cert"
        private val COMMON_P12_PASSWORDS = listOf("", "notasecret", "android", "aa", "carservice")
    }

    suspend fun getActiveCertificateInfo(): CertificateInfo = withContext(Dispatchers.IO) {
        val loaded = loadActiveKeyStoreAndPassword()
        val ks = loaded.first
        val origin = loaded.third
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IllegalStateException("Aucune clé privée disponible")

        val chain = ks.getCertificateChain(alias)
        val leaf = (chain?.firstOrNull() ?: ks.getCertificate(alias)) as X509Certificate
        val now = Date()
        val isExpired = now.after(leaf.notAfter) || now.before(leaf.notBefore)
        val subjectDn = leaf.subjectX500Principal.name
        val issuerDn = leaf.issuerX500Principal.name
        val isGoogle = subjectDn.contains("Google", ignoreCase = true) ||
            issuerDn.contains("Google", ignoreCase = true) ||
            subjectDn.contains("CarService", ignoreCase = true) ||
            subjectDn.contains("Automotive", ignoreCase = true)

        CertificateInfo(
            alias = alias,
            subject = subjectDn,
            issuer = issuerDn,
            sha256Fingerprint = computeSha256Fingerprint(leaf.encoded),
            validFrom = dateFormatter.format(leaf.notBefore),
            validUntil = dateFormatter.format(leaf.notAfter),
            isExpired = isExpired,
            isGoogleCarService = isGoogle,
            origin = origin,
            chainLength = chain?.size ?: 1
        )
    }

    suspend fun importPkcs12FromUri(uri: Uri, userPassword: String?): CertificateInfo =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Impossible de lire le fichier .p12")

            val passwordsToTry = buildList {
                if (!userPassword.isNullOrEmpty()) add(userPassword)
                addAll(COMMON_P12_PASSWORDS)
            }.distinct()

            var matchedPassword: String? = null
            for (candidate in passwordsToTry) {
                try {
                    val ks = KeyStore.getInstance("PKCS12")
                    ks.load(ByteArrayInputStream(bytes), candidate.toCharArray())
                    if (ks.aliases().toList().any { ks.isKeyEntry(it) }) {
                        matchedPassword = candidate
                        break
                    }
                } catch (_: Exception) {
                    // Try next password candidate
                }
            }

            val finalPassword = matchedPassword
                ?: throw IllegalArgumentException("Mot de passe PKCS#12 invalide ou aucune clé privée trouvée")

            importedPemCertFile.delete()
            importedPemKeyFile.delete()
            importedP12File.writeBytes(bytes)
            importedP12PassFile.writeText(finalPassword)
            getActiveCertificateInfo()
        }

    suspend fun importPemPairFromUris(certUri: Uri, keyUri: Uri): CertificateInfo =
        withContext(Dispatchers.IO) {
            val certBytes = context.contentResolver.openInputStream(certUri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Impossible de lire le certificat PEM")
            val keyBytes = context.contentResolver.openInputStream(keyUri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("Impossible de lire la clé privée PEM")

            // Validate immediately before saving
            parsePemCertificates(certBytes)
            parsePemPrivateKey(keyBytes)

            importedP12File.delete()
            importedP12PassFile.delete()
            importedPemCertFile.writeBytes(certBytes)
            importedPemKeyFile.writeBytes(keyBytes)
            getActiveCertificateInfo()
        }

    suspend fun resetToFallbackCertificate(): CertificateInfo = withContext(Dispatchers.IO) {
        importedP12File.delete()
        importedP12PassFile.delete()
        importedPemCertFile.delete()
        importedPemKeyFile.delete()
        ensureFallbackCertificate(forceRegenerate = true)
        getActiveCertificateInfo()
    }

    fun buildServerSslContext(): SSLContext {
        val (ks, password, _) = loadActiveKeyStoreAndPassword()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)

        // Head units present OEM client certificates not in Android's public CA store;
        // accept head unit peer cert during TLS server handshake just like AARE.
        val permissiveTrustManager = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
        )

        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(kmf.keyManagers, permissiveTrustManager, SecureRandom())
        return sslContext
    }

    /**
     * Runs a genuine in-memory TLS 1.2 SSLEngine handshake (Server=Phone, Client=Simulated HeadUnit)
     * to verify that the active certificate & private key work properly on this device.
     */
    suspend fun runLocalTlsHandshakeSelfTest(): TlsHandshakeTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val serverCtx = buildServerSslContext()
            val clientTrustAll = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
            )
            val clientCtx = SSLContext.getInstance("TLSv1.2").apply {
                init(null, clientTrustAll, SecureRandom())
            }

            val serverEngine = serverCtx.createSSLEngine().apply {
                useClientMode = false
                enabledProtocols = arrayOf("TLSv1.2")
            }
            val clientEngine = clientCtx.createSSLEngine().apply {
                useClientMode = true
                enabledProtocols = arrayOf("TLSv1.2")
            }

            serverEngine.beginHandshake()
            clientEngine.beginHandshake()

            var cToS = ByteBuffer.allocate(32768)
            var sToC = ByteBuffer.allocate(32768)
            val emptyApp = ByteBuffer.allocate(0)
            val scratch = ByteBuffer.allocate(32768)

            var rounds = 0
            while ((clientEngine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING ||
                    serverEngine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) &&
                rounds < 40
            ) {
                rounds++
                when (clientEngine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        cToS.clear()
                        clientEngine.wrap(emptyApp, cToS)
                        cToS.flip()
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        if (sToC.hasRemaining()) {
                            scratch.clear()
                            clientEngine.unwrap(sToC, scratch)
                            sToC.compact()
                            sToC.flip()
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        var task = clientEngine.delegatedTask
                        while (task != null) {
                            task.run()
                            task = clientEngine.delegatedTask
                        }
                    }
                    else -> Unit
                }

                when (serverEngine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                        sToC.clear()
                        serverEngine.wrap(emptyApp, sToC)
                        sToC.flip()
                    }
                    SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                        if (cToS.hasRemaining()) {
                            scratch.clear()
                            serverEngine.unwrap(cToS, scratch)
                            cToS.compact()
                            cToS.flip()
                        }
                    }
                    SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                        var task = serverEngine.delegatedTask
                        while (task != null) {
                            task.run()
                            task = serverEngine.delegatedTask
                        }
                    }
                    else -> Unit
                }
            }

            val elapsed = System.currentTimeMillis() - start
            val ok = clientEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING &&
                serverEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING

            TlsHandshakeTestResult(
                success = ok,
                protocol = serverEngine.session.protocol,
                cipherSuite = serverEngine.session.cipherSuite,
                durationMs = elapsed,
                message = if (ok) {
                    "Handshake TLS 1.2 réussi en ${elapsed} ms (${serverEngine.session.cipherSuite})"
                } else {
                    "Handshake incomplet après $rounds étapes"
                }
            )
        } catch (e: Exception) {
            TlsHandshakeTestResult(
                success = false,
                protocol = "TLSv1.2",
                cipherSuite = "N/A",
                durationMs = System.currentTimeMillis() - start,
                message = e.message ?: "Erreur lors du test TLS"
            )
        }
    }

    private fun loadActiveKeyStoreAndPassword(): Triple<KeyStore, CharArray?, CertificateOrigin> {
        if (importedP12File.exists()) {
            val password = if (importedP12PassFile.exists()) {
                importedP12PassFile.readText().toCharArray()
            } else {
                CharArray(0)
            }
            val ks = KeyStore.getInstance("PKCS12")
            importedP12File.inputStream().use { ks.load(it, password) }
            return Triple(ks, password, CertificateOrigin.IMPORTED_PKCS12)
        }

        if (importedPemCertFile.exists() && importedPemKeyFile.exists()) {
            val chain = parsePemCertificates(importedPemCertFile.readBytes())
            val privateKey = parsePemPrivateKey(importedPemKeyFile.readBytes())
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null, null)
            ks.setKeyEntry("aare_pem", privateKey, CharArray(0), chain.toTypedArray())
            return Triple(ks, CharArray(0), CertificateOrigin.IMPORTED_PEM)
        }

        val androidKs = ensureFallbackCertificate(forceRegenerate = false)
        return Triple(androidKs, null, CertificateOrigin.ANDROID_KEYSTORE_FALLBACK)
    }

    private fun ensureFallbackCertificate(forceRegenerate: Boolean): KeyStore {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
        ks.load(null)
        if (forceRegenerate && ks.containsAlias(FALLBACK_ALIAS)) {
            ks.deleteEntry(FALLBACK_ALIAS)
        }
        if (!ks.containsAlias(FALLBACK_ALIAS)) {
            val notBefore = Date()
            val notAfter = Date(notBefore.time + 3650L * 24L * 3600L * 1000L) // 10 years
            val spec = KeyGenParameterSpec.Builder(
                FALLBACK_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=AARE Phone Receiver, O=AARE Open Source"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(notBefore)
                .setCertificateNotAfter(notAfter)
                .build()

            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        return ks
    }

    private fun parsePemCertificates(pemBytes: ByteArray): List<Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        val certs = cf.generateCertificates(ByteArrayInputStream(pemBytes)).toList()
        if (certs.isEmpty()) throw IllegalArgumentException("Aucun certificat X.509 valide dans le PEM")
        return certs
    }

    private fun parsePemPrivateKey(keyBytes: ByteArray): PrivateKey {
        val pemText = String(keyBytes, Charsets.UTF_8)
        val cleanBase64 = pemText
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.decode(cleanBase64, Base64.DEFAULT)
        val keySpec = PKCS8EncodedKeySpec(der)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(keySpec)
    }

    private fun computeSha256Fingerprint(encoded: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
