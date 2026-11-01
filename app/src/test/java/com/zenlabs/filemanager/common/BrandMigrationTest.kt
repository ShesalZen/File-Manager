package com.zenlabs.filemanager.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BrandMigrationTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    @Test
    fun migrationCopiesNestedDataVerifiesItAndPreservesLegacySource() {
        val legacy = directory(".prism")
        val target = File(temporaryFolder.root, ".zenlabs")
        writeFile(legacy, "preferences.zenLabsPrefs", "preferences")
        writeFile(legacy, "notes/readme.txt", "read me")

        val result = BrandMigration.migrate(legacy, target)

        assertEquals(BrandMigrationResult.Status.MIGRATED, result.status)
        assertEquals(2, result.copiedFiles)
        assertTrue(target.isDirectory)
        assertEquals("preferences", File(target, "preferences.zenLabsPrefs").readText())
        assertEquals("read me", File(target, "notes/readme.txt").readText())
        assertTrue(File(target, BrandMigration.MARKER_NAME).isFile)
        assertTrue(legacy.isDirectory)
        assertEquals("preferences", File(legacy, "preferences.zenLabsPrefs").readText())
        assertEquals("read me", File(legacy, "notes/readme.txt").readText())
    }

    @Test
    fun completedMigrationIsIdempotentAndRecognizedByMarker() {
        val legacy = directory(".prism")
        val target = File(temporaryFolder.root, ".zenlabs")
        writeFile(legacy, "data.bin", "data")

        val first = BrandMigration.migrate(legacy, target)
        val second = BrandMigration.migrate(legacy, target)

        assertEquals(BrandMigrationResult.Status.MIGRATED, first.status)
        assertEquals(BrandMigrationResult.Status.ALREADY_COMPLETED, second.status)
        assertTrue(BrandMigration.isUsableTarget(target, legacy))
        assertEquals(target, BrandMigration.preferredDirectory(target, legacy))
        assertTrue(legacy.isDirectory)
    }

    @Test
    fun unmarkedPartialTargetCannotBeMistakenForSuccessfulMigration() {
        val legacy = directory(".prism")
        val target = directory(".zenlabs")
        writeFile(legacy, "data.bin", "complete-data")
        writeFile(target, "data.bin", "partial")

        val result = BrandMigration.migrate(legacy, target)

        assertEquals(BrandMigrationResult.Status.FAILED, result.status)
        assertNotNull(result.error)
        assertFalse(File(target, BrandMigration.MARKER_NAME).exists())
        assertFalse(BrandMigration.isUsableTarget(target, legacy))
        assertEquals(legacy, BrandMigration.preferredDirectory(target, legacy))
        assertEquals("complete-data", File(legacy, "data.bin").readText())
    }

    @Test
    fun unmarkedTargetThatExactlyMatchesLegacyIsVerifiedAndMarked() {
        val legacy = directory(".prism")
        val target = directory(".zenlabs")
        writeFile(legacy, "data.bin", "verified-data")
        writeFile(target, "data.bin", "verified-data")

        val result = BrandMigration.migrate(legacy, target)

        assertEquals(BrandMigrationResult.Status.ALREADY_COMPLETED, result.status)
        assertTrue(File(target, BrandMigration.MARKER_NAME).isFile)
        assertTrue(BrandMigration.isUsableTarget(target, legacy))
    }

    @Test
    fun interruptedStagingIsResumedAndCorruptSameSizedFileIsReplaced() {
        val legacy = directory(".prism")
        val target = File(temporaryFolder.root, ".zenlabs")
        val staging = File(temporaryFolder.root, ".zenlabs.migrating")
        writeFile(legacy, "good.bin", "good-data")
        writeFile(legacy, "damaged.bin", "expected")
        writeFile(staging, "good.bin", "good-data")
        writeFile(staging, "damaged.bin", "broken!")

        val result = BrandMigration.migrate(legacy, target)

        assertEquals(BrandMigrationResult.Status.MIGRATED, result.status)
        assertEquals("good-data", File(target, "good.bin").readText())
        assertEquals("expected", File(target, "damaged.bin").readText())
        assertTrue(File(target, BrandMigration.MARKER_NAME).isFile)
    }

    @Test
    fun migrationFailureLeavesSourceAndStagingAvailableForRetry() {
        val legacy = directory(".prism")
        val target = File(temporaryFolder.root, ".zenlabs")
        val staging = File(temporaryFolder.root, ".zenlabs.migrating")
        writeFile(legacy, "data.bin", "source")
        staging.writeText("not-a-directory")

        val result = BrandMigration.migrate(legacy, target)

        assertEquals(BrandMigrationResult.Status.FAILED, result.status)
        assertFalse(target.exists())
        assertTrue(staging.isFile)
        assertEquals("source", File(legacy, "data.bin").readText())
        assertNotNull(result.stagingDirectory)
    }

    @Test
    fun sourceMutationDuringCopyIsDetectedBeforePublish() {
        val legacy = directory(".prism")
        val sourceFile = writeFile(legacy, "data.bin", "before")
        val manifest = BrandMigrationIntegrity.manifest(legacy)
        sourceFile.writeText("after")

        val error = runCatching {
            BrandMigrationTransfer.copyResumably(
                legacy,
                File(temporaryFolder.root, "staging"),
                manifest
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty().contains("changed"))
        assertFalse(File(temporaryFolder.root, "staging/data.bin").exists())
    }

    @Test
    fun migrationIsNotNeededWithoutLegacyData() {
        val legacy = File(temporaryFolder.root, ".prism")
        val target = File(temporaryFolder.root, ".zenlabs")

        val result = BrandMigration.migrate(legacy, target)

        assertEquals(BrandMigrationResult.Status.NOT_NEEDED, result.status)
        assertNull(result.stagingDirectory)
        assertFalse(target.exists())
    }

    private fun directory(name: String): File =
        temporaryFolder.newFolder(name)

    private fun writeFile(parent: File, relativePath: String, content: String): File {
        val file = File(parent, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }
}
