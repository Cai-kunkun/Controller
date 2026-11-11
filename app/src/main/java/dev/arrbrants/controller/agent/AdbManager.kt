package dev.arrbrants.controller.agent

import android.content.Context
import android.os.Build
import android.sun.misc.BASE64Encoder
import android.sun.security.provider.X509Factory
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/** LibADB connection manager with a persisted ADB RSA key and certificate. */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val app = context.applicationContext
        val storedKey = readPrivateKey(app)
        val storedCert = readCertificate(app)
        if (storedKey != null && storedCert != null) {
            key = storedKey
            cert = storedCert
        } else {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val pair = generator.generateKeyPair()
            key = pair.private
            cert = makeCertificate(pair.public, pair.private)
            writePrivateKey(app, key)
            writeCertificate(app, cert)
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Controller"

    private fun makeCertificate(publicKey: PublicKey, privateKey: PrivateKey): Certificate {
        val subject = "CN=Controller"
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000)
        val extensions = CertificateExtensions()
        extensions.set(
            "SubjectKeyIdentifier",
            SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier)
        )
        val x500 = X500Name(subject)
        extensions.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        val info = X509CertInfo()
        info.set("version", CertificateVersion(2))
        info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
        info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
        info.set("subject", CertificateSubjectName(x500))
        info.set("key", CertificateX509Key(publicKey))
        info.set("validity", CertificateValidity(notBefore, notAfter))
        info.set("issuer", CertificateIssuerName(x500))
        info.set("extensions", extensions)
        val impl = X509CertImpl(info)
        impl.sign(privateKey, algorithm)
        return impl
    }

    private fun readCertificate(context: Context): Certificate? {
        val file = File(context.filesDir, "adb_cert.pem")
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun writeCertificate(context: Context, certificate: Certificate) {
        FileOutputStream(File(context.filesDir, "adb_cert.pem")).use { out ->
            out.write(X509Factory.BEGIN_CERT.toByteArray(StandardCharsets.UTF_8))
            out.write('\n'.code)
            BASE64Encoder().encode(certificate.encoded, out)
            out.write('\n'.code)
            out.write(X509Factory.END_CERT.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun readPrivateKey(context: Context): PrivateKey? {
        val file = File(context.filesDir, "adb_key.pk8")
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes()
            val factory = KeyFactory.getInstance("RSA")
            factory.generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (e: Exception) {
            null
        }
    }

    private fun writePrivateKey(context: Context, privateKey: PrivateKey) {
        File(context.filesDir, "adb_key.pk8").writeBytes(privateKey.encoded)
    }

    companion object {
        @Volatile private var instance: AdbManager? = null

        fun get(context: Context): AdbManager = instance ?: synchronized(this) {
            instance ?: AdbManager(context).also { instance = it }
        }
    }
}
