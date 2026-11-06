package com.zenlabs.filemanager.screen.main.tab.files.service.remote

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

class SftpUnknownHostKeyException(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
    val publicKey: PublicKey
) : Exception("Unknown SSH host key for $host:$port ($algorithm, $fingerprint)")

class SftpHostKeyMismatchException(
    val host: String,
    val port: Int,
    val expectedFingerprint: String,
    val actualFingerprint: String
) : Exception("SSH host key changed for $host:$port; expected $expectedFingerprint but received $actualFingerprint")

/** Persistent trust store; new keys require explicit user approval. */
class SftpHostKeyStore(private val knownHostsFile: File) {
    private data class TrustedKey(
        val host: String,
        val port: Int,
        val algorithm: String,
        val fingerprint: String,
        val encodedKey: String
    )

    private val lock = Any()

    fun verifier(host: String, port: Int): HostKeyVerifier = object : HostKeyVerifier {
        override fun verify(hostname: String, connectedPort: Int, key: PublicKey): Boolean {
            val algorithm = KeyType.fromKey(key).toString()
            val fingerprint = fingerprint(key)
            val trusted = readKeys().firstOrNull {
                it.host == hostname && it.port == connectedPort && it.algorithm == algorithm
            }
            if (trusted != null) {
                if (!constantTimeEquals(trusted.fingerprint, fingerprint)) {
                    throw SftpHostKeyMismatchException(
                        hostname, connectedPort, trusted.fingerprint, fingerprint
                    )
                }
                return true
            }
            throw SftpUnknownHostKeyException(hostname, connectedPort, algorithm, fingerprint, key)
        }

        override fun findExistingAlgorithms(hostname: String, connectedPort: Int): List<String> {
            return readKeys()
                .filter { it.host == hostname && it.port == connectedPort }
                .map { it.algorithm }
        }
    }

    fun trust(exception: SftpUnknownHostKeyException) {
        synchronized(lock) {
            val keys = readKeys().toMutableList()
            val existing = keys.firstOrNull {
                it.host == exception.host && it.port == exception.port &&
                    it.algorithm == exception.algorithm
            }
            if (existing != null) {
                if (!constantTimeEquals(existing.fingerprint, exception.fingerprint)) {
                    throw SftpHostKeyMismatchException(
                        exception.host, exception.port, existing.fingerprint, exception.fingerprint
                    )
                }
                return
            }
            keys.add(
                TrustedKey(
                    exception.host,
                    exception.port,
                    exception.algorithm,
                    exception.fingerprint,
                    Base64.getEncoder().encodeToString(exception.publicKey.encoded)
                )
            )
            writeKeys(keys)
        }
    }

    fun clear(host: String, port: Int) {
        synchronized(lock) {
            writeKeys(readKeys().filterNot { it.host == host && it.port == port })
        }
    }

    private fun readKeys(): List<TrustedKey> {
        if (!knownHostsFile.exists()) return emptyList()
        return try {
            knownHostsFile.readLines(Charsets.UTF_8).mapNotNull { line ->
                val fields = line.split('\t')
                if (fields.size != 5) return@mapNotNull null
                TrustedKey(
                    fields[0], fields[1].toIntOrNull() ?: return@mapNotNull null,
                    fields[2], fields[3], fields[4]
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeKeys(keys: List<TrustedKey>) {
        knownHostsFile.parentFile?.mkdirs()
        val temp = File(knownHostsFile.parentFile, "${knownHostsFile.name}.tmp")
        temp.writeText(
            keys.joinToString("\n") {
                listOf(it.host, it.port, it.algorithm, it.fingerprint, it.encodedKey).joinToString("\t")
            },
            Charsets.UTF_8
        )
        if (!temp.renameTo(knownHostsFile)) {
            knownHostsFile.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
    }

    companion object {
        fun fingerprint(key: PublicKey): String {
            val encoded = Buffer.PlainBuffer().putPublicKey(key).getCompactData()
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }

        private fun constantTimeEquals(first: String, second: String): Boolean {
            return MessageDigest.isEqual(
                first.toByteArray(Charsets.UTF_8),
                second.toByteArray(Charsets.UTF_8)
            )
        }
    }
}

fun Throwable.findSftpHostKeyMismatch(): SftpHostKeyMismatchException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is SftpHostKeyMismatchException) return current
        current = current.cause
    }
    return null
}

fun Throwable.findSftpUnknownHostKey(): SftpUnknownHostKeyException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is SftpUnknownHostKeyException) return current
        current = current.cause
    }
    return null
}
