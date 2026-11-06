package com.zenlabs.filemanager.common

import java.io.File

/**
 * Canonical path checks shared by the network servers.
 *
 * String prefix checks are not sufficient: `/share2` starts with `/share` even
 * though it is a sibling of the shared directory. Canonicalizing both paths
 * also makes symlink escapes fail the same containment check.
 */
object PathContainment {
    fun isContained(candidate: File, root: File): Boolean {
        val canonicalRoot = root.canonicalFile.path
        val canonicalCandidate = candidate.canonicalFile.path
        val childPrefix = if (canonicalRoot.endsWith(File.separator)) {
            canonicalRoot
        } else {
            canonicalRoot + File.separator
        }
        return canonicalCandidate == canonicalRoot || canonicalCandidate.startsWith(childPrefix)
    }

    /**
     * Resolves a server-relative path below [root].
     *
     * The path is interpreted relative to the root even when it starts with a
     * slash, matching FTP's virtual-root behavior. A `SecurityException` is
     * thrown when canonicalization resolves outside the root.
     */
    fun resolveUnderRoot(root: File, relativePath: String): File {
        val sanitized = relativePath.trimStart('/', '\\')
        val target = File(root, sanitized)
        if (!isContained(target, root)) {
            throw SecurityException("Path escapes shared root: $relativePath")
        }
        return target.canonicalFile
    }
}
