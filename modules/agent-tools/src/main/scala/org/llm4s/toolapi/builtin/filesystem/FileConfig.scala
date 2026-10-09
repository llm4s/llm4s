package org.llm4s.toolapi.builtin.filesystem

import java.nio.file.Path

/**
 * Configuration for file system tools.
 *
 * == How a path is judged ==
 * By where it really is, not by how it is spelled: every symbolic link is replaced by its real target, then the
 * result is compared with each entry, resolved the same way, one path component at a time. [[isPathAllowed]] judges
 * the path as given, and a `..` after a link is read both ways an OS reads it: physically, as POSIX does (`link/..`
 * is the parent of the link's target), and lexically, as Windows does (`..` removed as text before links are
 * followed, so `link/..` is the directory holding the link). The path is allowed only when both locations are
 * allowed, whatever platform the check runs on; a path whose readings disagree, one inside and one outside, is
 * refused on every OS. The tools first remove `.` and `..` from the path they are given as text (`data/link/../x`
 * is `data/x` for them), so the two readings agree, and then judge and open that location, so what they open is what
 * they checked. `/srv/data` allows `/srv/data/x` and does not allow `/srv/data-secret/x`. A symbolic link that cannot be resolved (a dangling link) is refused. The tools open the
 * resolved path, so a link swapped in after the check does not redirect the open; a directory swapped for a link
 * between the resolution and the open is a race this narrows and does not close. A hard link is not contained: a
 * hard link inside an allowed directory to a file elsewhere is that file under an allowed name, and no path check
 * can tell it apart, so do not let untrusted parties create files in an allowed directory.
 *
 * @param maxFileSize Maximum file size to read in bytes (default: 1MB)
 * @param allowedPaths Paths that are allowed to be accessed. None means any path. An entry that is itself reached
 *                     through a symbolic link (such as `/tmp` on macOS) is resolved, and so are the paths checked
 *                     against it.
 * @param blockedPaths Paths that are blocked from access (takes precedence over allowedPaths). Matched on the real
 *                     location too, so blocking `/etc` also blocks `/private/etc` on macOS, and the default `/var`
 *                     also blocks `/private/var`, where macOS keeps per-user temporary directories.
 * @param followSymlinks Whether to follow a symbolic link that is the final component of the path (default: false
 *                       for security). When `false`, `read_file` reports such a link as not a regular file and
 *                       `file_info` and `list_directory` describe the link itself. This does not decide whether a
 *                       link may lead out of the allowed area: a link anywhere on the path, final or not, is
 *                       resolved first, and the path is allowed only if its real location is.
 */
case class FileConfig(
  maxFileSize: Long = 1024 * 1024,
  allowedPaths: Option[Seq[String]] = None,
  blockedPaths: Seq[String] = Seq("/etc", "/var", "/sys", "/proc", "/dev"),
  followSymlinks: Boolean = false
) {

  /**
   * Check if a path is allowed based on configuration, by its real location (see the class documentation).
   */
  def isPathAllowed(path: Path): Boolean = resolve(path).isDefined

  /** The real path to open when `path` is allowed. The tools open this, not the path they were given. */
  private[builtin] def resolve(path: Path): Option[Path] =
    PathPolicy.resolve(path, allowedPaths, blockedPaths)
}

/**
 * Configuration for write operations.
 *
 * A path is judged by where it really is, as for [[FileConfig]]: symbolic links are resolved (for a file that does
 * not exist yet, the nearest existing ancestor is), with a `..` after a link read both the POSIX and the Windows way
 * and both locations required to be allowed, then compared with each allowed entry one component at a time. A link inside an allowed directory that leads outside it is therefore refused, whether the
 * link is a directory on the way to the file or the file itself; a link that leads to another place inside the
 * allowed directory is written through. The tool writes to the resolved path.
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
   * Check if a path is allowed for writing, by its real location (see the class documentation).
   */
  def isPathAllowed(path: Path): Boolean = resolve(path).isDefined

  /** The real path to write when `path` is allowed. The tool writes this, not the path it was given. */
  private[builtin] def resolve(path: Path): Option[Path] =
    PathPolicy.resolve(path, Some(allowedPaths), Seq.empty)
}
