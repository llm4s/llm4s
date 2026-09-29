package org.llm4s.toolapi.builtin.filesystem

import java.nio.file.Path

/**
 * Configuration for file system tools.
 *
 * @param maxFileSize Maximum file size to read in bytes (default: 1MB)
 * @param allowedPaths Paths that are allowed to be accessed. None means any path.
 * @param blockedPaths Paths that are blocked from access (takes precedence over allowedPaths)
 * @param followSymlinks Whether to follow symbolic links (default: false for security)
 */
case class FileConfig(
  maxFileSize: Long = 1024 * 1024,
  allowedPaths: Option[Seq[String]] = None,
  blockedPaths: Seq[String] = Seq("/etc", "/var", "/sys", "/proc", "/dev"),
  followSymlinks: Boolean = false
) {

  /**
   * Check if a path is allowed based on configuration.
   */
  def isPathAllowed(path: Path): Boolean = {
    val normalizedPath = path.toAbsolutePath.normalize().toString

    // Check blocked paths first
    val isBlocked = blockedPaths.exists(blocked => normalizedPath.startsWith(blocked) || normalizedPath == blocked)

    if (isBlocked) return false

    // Check allowed paths if specified
    allowedPaths match {
      case Some(allowed) =>
        allowed.exists(allowedPath => normalizedPath.startsWith(allowedPath) || normalizedPath == allowedPath)
      case None =>
        true // All non-blocked paths allowed
    }
  }
}

/**
 * Configuration for write operations.
 *
 * @param allowedPaths Paths where writing is allowed (required for safety)
 * @param maxFileSize Maximum file size to write in bytes
 * @param allowOverwrite Whether to allow overwriting existing files
 * @param createDirectories Whether to create parent directories if they don't exist
 */
case class WriteConfig(
  allowedPaths: Seq[String],
  maxFileSize: Long = 10 * 1024 * 1024,
  allowOverwrite: Boolean = false,
  createDirectories: Boolean = true
) {

  /**
   * Check if a path is allowed for writing.
   */
  def isPathAllowed(path: Path): Boolean = {
    val normalizedPath = path.toAbsolutePath.normalize().toString
    allowedPaths.exists(allowedPath => normalizedPath.startsWith(allowedPath) || normalizedPath == allowedPath)
  }
}
