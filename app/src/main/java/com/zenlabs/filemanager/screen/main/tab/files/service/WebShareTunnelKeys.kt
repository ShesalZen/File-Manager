package com.zenlabs.filemanager.screen.main.tab.files.service

import android.content.Context
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.File
import java.io.StringWriter
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Security
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import java.security.MessageDigest

class WebShareTunnelHostKeyMismatchException(
    message: String = "The public tunnel service rotated its security keys. Please update the application to restore public tunnel sharing."
) : Exception(message)

class PinnedHostKeyVerifier(
    private val pinnedFingerprints: Set<String>
) : HostKeyVerifier {
    @Volatile
    var mismatchDetected: Boolean = false
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        if (hostname != "localhost.run" || port != 22) {
            mismatchDetected = true
            throw WebShareTunnelHostKeyMismatchException()
        }
        val encoded = Buffer.PlainBuffer().putPublicKey(key).getCompactData()
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        if (fingerprint !in pinnedFingerprints) {
            mismatchDetected = true
            throw WebShareTunnelHostKeyMismatchException()
        }
        return true
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/**
 * Manages device-local SSH keys and strict host verification for the
 * internet sharing tunnel.
 */
object WebShareTunnelKeys {

    private const val WEBSHARE_DIR = "webshare"
    private const val KEY_FILE_NAME = "id_rsa.pem"
    // localhost.run currently offers both RSA and Ed25519. These SHA-256
    // fingerprints are checked against the negotiated SSH public key. A key
    // rotation or interception fails the tunnel connection closed.
    private val PINNED_HOST_FINGERPRINTS = setOf(
        "SHA256:FV8IMJ4IYjYUTnd6on7PqbRjaZf4c1EhhEBgeUdE94I",
        "SHA256:pG6qrBxubYfWa1Zadu/V0NUgjEDiBds/7e2xzte/QNM"
    )

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Synchronized
    fun getOrGeneratePrivateKeyPem(context: Context): String {
        val dir = File(context.filesDir, WEBSHARE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val keyFile = File(dir, KEY_FILE_NAME)
        if (keyFile.exists() && keyFile.length() > 0) {
            return keyFile.readText()
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val sw = StringWriter()
        JcaPEMWriter(sw).use { it.writeObject(keyPair.private) }
        val pemString = sw.toString()

        keyFile.writeText(pemString)
        return pemString
    }

    fun loadKeys(sshClient: SSHClient, context: Context): KeyProvider {
        val pem = getOrGeneratePrivateKeyPem(context)
        return sshClient.loadKeys(pem, null, null)
    }

    /**
     * Sends a channel request without waiting for a server reply (wantReply = false).
     * localhost.run's SSH server does not send SSH_MSG_CHANNEL_SUCCESS for interactive shell requests,
     * which causes SSHJ's standard `startShell()` to time out.
     */
    fun requestShellNoReply(session: net.schmizz.sshj.connection.channel.direct.Session) {
        var cls: Class<*>? = session.javaClass
        while (cls != null) {
            val m = cls.declaredMethods.firstOrNull {
                it.name == "sendChannelRequest" && it.parameterTypes.size == 3
            }
            if (m != null) {
                m.isAccessible = true
                m.invoke(session, "shell", false, null)
                return
            }
            cls = cls.superclass
        }
    }

    /**
     * Strict verifier for the localhost.run tunnel. Only the pinned RSA and
     * Ed25519 keys are accepted; unknown or changed keys are never persisted.
     */
    fun createPinnedVerifier(): PinnedHostKeyVerifier = PinnedHostKeyVerifier(PINNED_HOST_FINGERPRINTS)
}
