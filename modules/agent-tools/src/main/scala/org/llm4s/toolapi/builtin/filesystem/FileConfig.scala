package org.llm4s.toolapi.builtin.filesystem

import java.nio.file.{ Path, Paths }
import scala.util.Try

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
   *
   * A path is inside a configured path when it is that path or below it, compared by path component after
   * normalisation: `/srv/data` contains `/srv/data/a.txt` but not `/srv/data-secret`.
   */
  def isPathAllowed(path: Path): Boolean = {
    val normalizedPath = PathContainment.normalize(path)
    val isBlocked      = PathContainment.isInsideAny(normalizedPath, blockedPaths)
    !isBlocked && allowedPaths.forall(allowed => PathContainment.isInsideAny(normalizedPath, allowed))
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
   * Check if a path is allowed for writing: it must be one of `allowedPaths` or below one, compared by path
   * component (`/srv/out` does not contain `/srv/out-other`).
   */
  def isPathAllowed(path: Path): Boolean =
    PathContainment.isInsideAny(PathContainment.normalize(path), allowedPaths)
}

/** Component-wise path containment shared by [[FileConfig]] and [[WriteConfig]]. */
private[filesystem] object PathContainment {

  def normalize(path: Path): Path = path.toAbsolutePath.normalize()

  /** True when `path` (already normalised) equals or lies below one of `roots`; an unparseable root matches nothing. */
  def isInsideAny(path: Path, roots: Seq[String]): Boolean =
    roots.exists(root => Try(normalize(Paths.get(root))).toOption.exists(path.startsWith))
}
