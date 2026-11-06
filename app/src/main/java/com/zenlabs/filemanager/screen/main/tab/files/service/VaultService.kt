package com.zenlabs.filemanager.screen.main.tab.files.service

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zenlabs.filemanager.common.PathContainment
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Keep
data class VaultFileRecord(
    val id: String,
    val originalName: String,
    val originalPath: String,
    val scrambledPath: String,
    val size: Long,
    val lockedAt: String,
    val isInPlace: Boolean,
    val isFolder: Boolean
)

object VaultService {
    private const val PASSWORD_ITERATIONS = 120_000

    fun isPasswordSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
        return prefs.contains("vault_password_hash_v2") || prefs.contains("vault_password_hash")
    }

    fun setPassword(context: Context, pin: String) {
        val prefs = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derivePasswordHash(pin, salt)
        prefs.edit()
            .putString("vault_salt_v2", Base64.getEncoder().encodeToString(salt))
            .putString("vault_password_hash_v2", Base64.getEncoder().encodeToString(hash))
            .remove("vault_salt")
            .remove("vault_password_hash")
            .apply()
    }

    fun verifyPassword(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
        val saltV2 = prefs.getString("vault_salt_v2", null)
        val hashV2 = prefs.getString("vault_password_hash_v2", null)
        if (saltV2 != null && hashV2 != null) {
            return try {
                val salt = Base64.getDecoder().decode(saltV2)
                val expected = Base64.getDecoder().decode(hashV2)
                MessageDigest.isEqual(expected, derivePasswordHash(pin, salt))
            } catch (_: Exception) {
                false
            }
        }

        val legacySalt = prefs.getString("vault_salt", null) ?: return false
        val legacyHash = prefs.getString("vault_password_hash", null) ?: return false
        val valid = MessageDigest.isEqual(
            legacyHash.toByteArray(Charsets.UTF_8),
            sha256(pin + legacySalt).toByteArray(Charsets.UTF_8)
        )
        if (valid) {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            prefs.edit()
                .putString("vault_salt_v2", Base64.getEncoder().encodeToString(salt))
                .putString("vault_password_hash_v2", Base64.getEncoder().encodeToString(derivePasswordHash(pin, salt)))
                .remove("vault_salt")
                .remove("vault_password_hash")
                .apply()
        }
        return valid
    }

    fun resetVault(context: Context) {
        val prefs = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        try {
            getVaultDir(context).deleteRecursively()
        } catch (_: Exception) {}
    }

    fun getVaultDir(context: Context): File {
        val vaultDir = File(context.filesDir, "vault")
        if (!vaultDir.exists()) {
            vaultDir.mkdirs()
        }
        return vaultDir
    }

    private fun getMetadataFile(context: Context): File {
        return File(getVaultDir(context), "metadata.json")
    }

    fun loadRecords(context: Context): List<VaultFileRecord> {
        val file = getMetadataFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val json = file.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<VaultFileRecord>>() {}.type
            Gson().fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRecords(context: Context, records: List<VaultFileRecord>) {
        val file = getMetadataFile(context)
        try {
            val json = Gson().toJson(records)
            file.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sha256Bytes(input: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
    }

    private fun sha256(input: String): String {
        val digest = sha256Bytes(input)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun derivePasswordHash(password: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PASSWORD_ITERATIONS, 256)
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun lockFile(
        context: Context,
        file: File,
        password: CharSequence,
        inPlace: Boolean,
        customName: String? = null,
        customPath: String? = null,
        isFolder: Boolean = false
    ): VaultFileRecord {
        if (!file.exists()) {
            throw FileNotFoundException("File does not exist: ${file.absolutePath}")
        }

        val originalPath = customPath ?: file.absolutePath
        val originalName = customName ?: file.name
        val size = file.length()
        val timestamp = System.currentTimeMillis().toString()

        val scrambledPath = if (inPlace) {
            val parent = if (isFolder) originalPath else file.parent ?: ""
            File(parent, ".vault_$timestamp.nfv").absolutePath
        } else {
            File(getVaultDir(context), "vault_$timestamp.nfv").absolutePath
        }

        val targetFile = File(scrambledPath)
        val encryptedTemp = File(targetFile.parentFile, ".${targetFile.name}.${timestamp}.tmp")
        try {
            VaultCrypto.encryptFile(file, encryptedTemp, password)
            java.nio.file.Files.move(
                encryptedTemp.toPath(),
                targetFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                encryptedTemp.toPath(),
                targetFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            encryptedTemp.delete()
        }

        // Delete original file
        file.delete()

        val record = VaultFileRecord(
            id = timestamp,
            originalName = originalName,
            originalPath = originalPath,
            scrambledPath = scrambledPath,
            size = size,
            lockedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date()),
            isInPlace = inPlace,
            isFolder = isFolder
        )

        val records = loadRecords(context).toMutableList()
        records.add(record)
        saveRecords(context, records)

        return record
    }

    fun unlockFile(
        context: Context,
        record: VaultFileRecord,
        password: CharSequence
    ): File {
        val scrambledFile = File(record.scrambledPath)
        if (!scrambledFile.exists()) {
            throw FileNotFoundException("Scrambled vault file not found: ${record.scrambledPath}")
        }

        if (!VaultCrypto.isV2(scrambledFile)) {
            VaultCrypto.migrateLegacy(scrambledFile, password)
        }

        val originalFile = File(record.originalPath)
        val decryptedTemp = File(context.cacheDir, "vault_unlock_${record.id}.tmp")
        VaultCrypto.decryptFile(scrambledFile, decryptedTemp, password)
        try {
            if (record.isFolder) {
                val destDir = originalFile.parentFile ?: File("/")
                unzip(decryptedTemp, destDir)
            } else {
                originalFile.parentFile?.mkdirs()
                decryptedTemp.copyTo(originalFile, overwrite = true)
            }
        } finally {
            decryptedTemp.delete()
        }

        scrambledFile.delete()

        val records = loadRecords(context).toMutableList()
        records.removeAll { it.id == record.id }
        saveRecords(context, records)

        return originalFile
    }

    fun lockDirectory(
        context: Context,
        directory: File,
        password: CharSequence,
        inPlace: Boolean
    ): VaultFileRecord {
        if (!directory.exists()) {
            throw FileNotFoundException("Directory does not exist: ${directory.absolutePath}")
        }

        val originalPath = directory.absolutePath
        val originalName = directory.name

        // Zip recursively to bytes
        val zipBytes = zipDirectoryToBytes(directory)

        // Write zip to a temporary file
        val tempZipFile = File(context.cacheDir, "temp_vault_zip_${System.currentTimeMillis()}.zip")
        tempZipFile.writeBytes(zipBytes)

        try {
            val record = lockFile(
                context = context,
                file = tempZipFile,
                password = password,
                inPlace = inPlace,
                customName = originalName,
                customPath = originalPath,
                isFolder = true
            )

            // Clean up original directory contents
            if (inPlace) {
                val children = directory.listFiles() ?: emptyArray()
                for (child in children) {
                    if (child.absolutePath != record.scrambledPath) {
                        child.deleteRecursively()
                    }
                }
            } else {
                directory.deleteRecursively()
            }

            return record
        } finally {
            if (tempZipFile.exists()) {
                tempZipFile.delete()
            }
        }
    }

    fun decryptTemporary(
        context: Context,
        record: VaultFileRecord,
        password: CharSequence
    ): File {
        val scrambledFile = File(record.scrambledPath)
        if (!scrambledFile.exists()) {
            throw FileNotFoundException("Scrambled vault file not found")
        }

        if (!VaultCrypto.isV2(scrambledFile)) {
            VaultCrypto.migrateLegacy(scrambledFile, password)
        }
        val extension = if (record.isFolder) ".zip" else ""
        val tempFilePath = File(context.cacheDir, "temp_vault_${record.id}_${record.originalName}$extension")
        VaultCrypto.decryptFile(scrambledFile, tempFilePath, password)
        return tempFilePath
    }

    private fun zipDirectoryToBytes(directory: File): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zipDir(directory, directory, zos)
        }
        return baos.toByteArray()
    }

    private fun zipDir(rootDir: File, currentDir: File, zos: ZipOutputStream) {
        val files = currentDir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                zipDir(rootDir, file, zos)
            } else {
                val parentPath = rootDir.parentFile?.absolutePath ?: ""
                val relativePath = file.absolutePath.substring(parentPath.length + 1)
                val entry = ZipEntry(relativePath)
                zos.putNextEntry(entry)
                file.inputStream().use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val file = PathContainment.resolveUnderRoot(destDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out ->
                        zis.copyTo(out)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }
}
