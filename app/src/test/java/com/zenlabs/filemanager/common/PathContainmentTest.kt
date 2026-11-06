package com.zenlabs.filemanager.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PathContainmentTest {
    @Test
    fun `root itself is contained`() {
        val root = File(System.getProperty("java.io.tmpdir"), "zenlabs-path-root")
        assertTrue(PathContainment.isContained(root, root))
    }

    @Test
    fun `child is contained`() {
        val root = File(System.getProperty("java.io.tmpdir"), "zenlabs-path-root")
        assertTrue(PathContainment.isContained(File(root, "child/file.txt"), root))
    }

    @Test
    fun `sibling with shared prefix is not contained`() {
        val base = File(System.getProperty("java.io.tmpdir"), "zenlabs-path-prefix")
        val root = File(base, "share")
        val sibling = File(base, "share2")
        assertFalse(PathContainment.isContained(sibling, root))
    }

    @Test
    fun `traversal is rejected`() {
        val base = File(System.getProperty("java.io.tmpdir"), "zenlabs-path-traversal")
        val root = File(base, "share")
        val outside = File(base, "outside.txt")
        try {
            PathContainment.resolveUnderRoot(root, "../outside.txt")
            throw AssertionError("Expected traversal to be rejected")
        } catch (_: SecurityException) {
            // Expected.
        }
    }

    @Test
    fun `leading slash remains root relative`() {
        val base = File(System.getProperty("java.io.tmpdir"), "zenlabs-path-leading")
        val root = File(base, "share")
        val resolved = PathContainment.resolveUnderRoot(root, "/child/file.txt")
        assertTrue(PathContainment.isContained(resolved, root))
        assertTrue(resolved.canonicalPath.endsWith("child${File.separator}file.txt"))
    }
}
