package com.zenlabs.filemanager.common

import java.io.File

data class BrandMigrationResult(
    val status: Status,
    val targetDirectory: File,
    val copiedFiles: Int = 0,
    val error: String? = null,
    val stagingDirectory: File? = null
) {
    enum class Status {
        NOT_NEEDED,
        ALREADY_COMPLETED,
        MIGRATED,
        FAILED
    }
}

/**
 * Copies legacy shared app data without deleting the source.
 *
 * A sibling staging directory holds resumable work. Every regular file is copied to a temporary
 * name, SHA-256 checked against the source, and then renamed into place. The complete tree is
 * verified again before an atomic publish. A marker in the published target distinguishes a
 * completed migration from an unrelated or partially-created directory.
 */
object BrandMigration {
    internal const val MARKER_NAME = ".zenlabs-migration-complete-v1"

    fun migrate(legacyDirectory: File, targetDirectory: File): BrandMigrationResult {
        if (targetDirectory.exists()) {
            return verifyExistingTarget(legacyDirectory, targetDirectory)
        }
        if (!legacyDirectory.isDirectory) {
            return BrandMigrationResult(BrandMigrationResult.Status.NOT_NEEDED, targetDirectory)
        }

        val stagingDirectory = File(targetDirectory.parentFile, "${targetDirectory.name}.migrating")
        return try {
            val sourceManifest = BrandMigrationIntegrity.manifest(legacyDirectory)
            BrandMigrationTransfer.copyResumably(legacyDirectory, stagingDirectory, sourceManifest)
            BrandMigrationTransfer.removeUnexpectedFiles(stagingDirectory, sourceManifest)

            val currentSourceManifest = BrandMigrationIntegrity.manifest(legacyDirectory)
            if (currentSourceManifest != sourceManifest) {
                throw IllegalStateException("Legacy source changed while migration was in progress")
            }

            val copiedManifest = BrandMigrationIntegrity.manifest(stagingDirectory)
            if (copiedManifest != sourceManifest) {
                throw IllegalStateException(
                    "Migration integrity verification failed: expected ${sourceManifest.files.size} " +
                        "files, found ${copiedManifest.files.size}"
                )
            }

            BrandMigrationIntegrity.writeMarker(stagingDirectory)
            BrandMigrationTransfer.publish(stagingDirectory, targetDirectory)
            BrandMigrationResult(
                BrandMigrationResult.Status.MIGRATED,
                targetDirectory,
                copiedFiles = sourceManifest.files.size
            )
        } catch (e: Exception) {
            BrandMigrationResult(
                BrandMigrationResult.Status.FAILED,
                targetDirectory,
                error = e.message ?: e::class.java.simpleName,
                stagingDirectory = stagingDirectory.takeIf { it.exists() }
            )
        }
    }

    /**
     * Returns true when it is safe for the app to use [targetDirectory]. A marked target was
     * published by this migration. Without a marker, a target is safe only when no legacy source
     * remains to migrate.
     */
    fun isUsableTarget(targetDirectory: File, legacyDirectory: File): Boolean {
        if (!targetDirectory.isDirectory) return false
        return BrandMigrationIntegrity.hasValidMarker(targetDirectory) ||
            !legacyDirectory.isDirectory
    }

    /**
     * Chooses the directory the app may safely use. A verified new target wins; otherwise the
     * untouched legacy directory remains the fallback so a failed or interrupted migration cannot
     * make the app's shared data disappear.
     */
    fun preferredDirectory(targetDirectory: File, legacyDirectory: File): File = when {
        isUsableTarget(targetDirectory, legacyDirectory) -> targetDirectory
        legacyDirectory.isDirectory -> legacyDirectory
        else -> targetDirectory
    }

    private fun verifyExistingTarget(
        legacyDirectory: File,
        targetDirectory: File
    ): BrandMigrationResult {
        if (!targetDirectory.isDirectory) {
            return BrandMigrationResult(
                BrandMigrationResult.Status.FAILED,
                targetDirectory,
                error = "Migration target exists and is not a directory"
            )
        }
        if (BrandMigrationIntegrity.hasValidMarker(targetDirectory)) {
            return BrandMigrationResult(
                BrandMigrationResult.Status.ALREADY_COMPLETED,
                targetDirectory
            )
        }
        if (!legacyDirectory.isDirectory) {
            return BrandMigrationResult(
                BrandMigrationResult.Status.ALREADY_COMPLETED,
                targetDirectory
            )
        }

        return try {
            val sourceManifest = BrandMigrationIntegrity.manifest(legacyDirectory)
            val targetManifest = BrandMigrationIntegrity.manifest(targetDirectory)
            if (targetManifest != sourceManifest) {
                throw IllegalStateException(
                    "Existing migration target is not a verified copy of the legacy source"
                )
            }
            BrandMigrationIntegrity.writeMarker(targetDirectory)
            BrandMigrationResult(
                BrandMigrationResult.Status.ALREADY_COMPLETED,
                targetDirectory,
                copiedFiles = sourceManifest.files.size
            )
        } catch (e: Exception) {
            BrandMigrationResult(
                BrandMigrationResult.Status.FAILED,
                targetDirectory,
                error = e.message ?: e::class.java.simpleName
            )
        }
    }
}
