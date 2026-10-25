package org.llm4s.toolapi.builtin.filesystem

import java.nio.file.Path
import java.util.Locale

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
 *                     also blocks `/private/var`, where macOS keeps per-user temporary directories. Matched without
 *                     regard to case or Unicode normalisation, so `/ETC/hosts` is blocked by `/etc`, as it must be on
 *                     a case-insensitive file system such as the macOS and Windows defaults; on a case-sensitive one
 *                     this blocks a few more paths than it must, never fewer. The default is
 *                     [[FileConfig.DefaultBlockedPaths]], the system and credential locations of the platform the
 *                     JVM runs on. A blocklist is defence in depth: it cannot name every sensitive file, so set
 *                     `allowedPaths` to the directories the agent needs.
 * @param followSymlinks Whether to follow a symbolic link that is the final component of the path (default: false
 *                       for security). When `false`, `read_file` reports such a link as not a regular file and
 *                       `file_info` and `list_directory` describe the link itself. This does not decide whether a
 *                       link may lead out of the allowed area: a link anywhere on the path, final or not, is
 *                       resolved first, and the path is allowed only if its real location is.
 */
case class FileConfig(
  maxFileSize: Long = 1024 * 1024,
  allowedPaths: Option[Seq[String]] = None,
  blockedPaths: Seq[String] = FileConfig.DefaultBlockedPaths,
  followSymlinks: Boolean = false
) {

  /**
   * Check if a path is allowed based on configuration, by its real location (see the class documentation).
   */
  def isPathAllowed(path: Path): Boolean = resolve(path).isDefined

  /** The real path to open when `path` is allowed. The tools open this, not the path they were given. */
  private[builtin] def resolve(path: Path): Option[Path] =
    PathPolicy.resolve(path, allowedPaths, blockedPaths)

  /** The allowed and blocked entries resolved now, for checking many paths in a row (one shell command's). */
  private[builtin] def entries: PathPolicy.Entries = PathPolicy.prepare(allowedPaths, blockedPaths)

  /**
   * The blocked paths for a tool description, with the user's home directory written `~`, so the description sent
   * to the model provider does not carry the account name.
   */
  private[builtin] def describedBlockedPaths: String =
    FileConfig.describe(blockedPaths, FileConfig.userHome)
}

object FileConfig {

  /** The operating systems [[defaultBlockedPaths]] knows. Anything that is neither Windows nor macOS is `Posix`. */
  private[builtin] enum Platform {
    case Posix, MacOS, Windows
  }

  private[builtin] object Platform {

    /** The platform for an `os.name` value. */
    def fromOsName(osName: String): Platform = {
      val name = osName.toLowerCase(Locale.ROOT)
      if (name.startsWith("windows")) Windows
      else if (name.startsWith("mac") || name.startsWith("darwin")) MacOS
      else Posix
    }

    /** The platform the JVM runs on. */
    def current: Platform = fromOsName(System.getProperty("os.name", ""))
  }

  /** The user's home directory (`user.home`), or `None` when it is unset or empty. */
  private[builtin] def userHome: Option[String] =
    Option(System.getProperty("user.home")).map(_.trim).filter(home => home.nonEmpty && home != "?")

  /**
   * The default [[FileConfig.blockedPaths]]: the system and credential locations of the platform the JVM runs on,
   * with the user's home directory taken from `user.home` when this object is first used ([[defaultBlockedPaths]]
   * lists them). Extend it rather than replace it:
   * `FileConfig(blockedPaths = FileConfig.DefaultBlockedPaths :+ "/srv/keys")`.
   */
  lazy val DefaultBlockedPaths: Seq[String] = defaultBlockedPaths(Platform.current, userHome)

  /**
   * The default blocked paths for a platform and a home directory.
   *
   *  - Linux and other POSIX systems: `/etc`, `/var`, `/sys`, `/proc`, `/dev`, `/boot`, `/root` and `/run` (which
   *    holds `/run/secrets` and the per-user keyrings and sockets of `/run/user`).
   *  - macOS: `/etc`, `/var`, `/sys`, `/proc`, `/dev`, their real locations `/private/etc` and `/private/var` (real
   *    locations are compared anyway; listing them makes the list say what it blocks), and the system keychains in
   *    `/Library/Keychains`.
   *  - Windows: `C:\Windows` (system files, and the registry hives and SAM in `System32\config`),
   *    `C:\ProgramData\Microsoft\Crypto` (machine private keys) and `C:\ProgramData\ssh` (the OpenSSH server's host
   *    keys). A Windows installed on another drive is not covered: add its directory.
   *  - Under the user's home, on every platform: the SSH, GnuPG, AWS, Azure, Google Cloud, Kubernetes, Docker and
   *    GitHub CLI credentials (`.ssh`, `.gnupg`, `.aws`, `.azure`, `.config/gcloud`, `.kube`, `.docker`,
   *    `.config/gh`), `.netrc`, `.git-credentials`, `.pgpass`, `.password-store`, and the package-registry
   *    credentials of npm, PyPI, Maven, Ivy and sbt (`.npmrc`, `.pypirc`, `.m2/settings.xml`,
   *    `.m2/settings-security.xml`, `.ivy2/.credentials`, `.sbt/.credentials`). On Linux and other POSIX systems also
   *    the GNOME keyring (`.local/share/keyrings`); on macOS the login keychains (`Library/Keychains`); on Windows
   *    `_netrc`, the registry hive `NTUSER.DAT`, the Credential Manager and DPAPI stores
   *    (`AppData\Roaming\Microsoft\Credentials`, `AppData\Local\Microsoft\Credentials`,
   *    `AppData\Roaming\Microsoft\Protect`, `AppData\Roaming\Microsoft\Crypto`), and where gcloud, GnuPG, the GitHub
   *    CLI and PostgreSQL keep credentials there (`AppData\Roaming\gcloud`, `AppData\Roaming\gnupg`,
   *    `AppData\Roaming\GitHub CLI`, `AppData\Roaming\postgresql`).
   *
   * Without a home directory the home entries are left out.
   */
  private[builtin] def defaultBlockedPaths(platform: Platform, home: Option[String]): Seq[String] = {
    val separator = if (platform == Platform.Windows) "\\" else "/"
    val system = platform match {
      case Platform.Posix   => PosixSystem ++ Seq("/boot", "/root", "/run")
      case Platform.MacOS   => PosixSystem ++ Seq("/private/etc", "/private/var", "/Library/Keychains")
      case Platform.Windows => Seq("C:\\Windows", "C:\\ProgramData\\Microsoft\\Crypto", "C:\\ProgramData\\ssh")
    }
    val homeEntries = platform match {
      case Platform.Posix   => HomeCredentials :+ ".local/share/keyrings"
      case Platform.MacOS   => HomeCredentials :+ "Library/Keychains"
      case Platform.Windows => HomeCredentials ++ WindowsHomeCredentials
    }
    system ++ home.map(withoutTrailingSeparators).toSeq.flatMap { h =>
      homeEntries.map(relative => h + separator + relative.replace("/", separator))
    }
  }

  private val PosixSystem = Seq("/etc", "/var", "/sys", "/proc", "/dev")

  private val HomeCredentials = Seq(
    ".ssh",
    ".gnupg",
    ".aws",
    ".azure",
    ".config/gcloud",
    ".kube",
    ".docker",
    ".config/gh",
    ".netrc",
    ".git-credentials",
    ".pgpass",
    ".password-store",
    ".npmrc",
    ".pypirc",
    ".m2/settings.xml",
    ".m2/settings-security.xml",
    ".ivy2/.credentials",
    ".sbt/.credentials"
  )

  private val WindowsHomeCredentials = Seq(
    "_netrc",
    "NTUSER.DAT",
    "AppData/Roaming/Microsoft/Credentials",
    "AppData/Local/Microsoft/Credentials",
    "AppData/Roaming/Microsoft/Protect",
    "AppData/Roaming/Microsoft/Crypto",
    "AppData/Roaming/gcloud",
    "AppData/Roaming/gnupg",
    "AppData/Roaming/GitHub CLI",
    "AppData/Roaming/postgresql"
  )

  private def withoutTrailingSeparators(path: String): String =
    path.reverse.dropWhile(c => c == '/' || c == '\\').reverse

  /** `paths` joined for a tool description, with `home` written `~`. */
  private[builtin] def describe(paths: Seq[String], home: Option[String]): String = {
    val h = home.map(withoutTrailingSeparators).filter(_.nonEmpty)
    paths
      .map(path =>
        h.filter(h => path == h || path.startsWith(h + "/") || path.startsWith(h + "\\"))
          .fold(path)(h => "~" + path.drop(h.length))
      )
      .mkString(", ")
  }
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
