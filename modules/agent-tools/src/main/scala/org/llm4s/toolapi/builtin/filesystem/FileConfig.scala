package org.llm4s.toolapi.builtin.filesystem

import java.nio.file.{ Files, Path, Paths }
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.{ Try, Using }

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
 * between the resolution and the open is a race this narrows and does not close. A hard link is not something a path
 * check can see: a hard link inside an allowed directory to a file elsewhere is that file under an allowed name. So
 * `read_file` refuses a regular file that has other hard links, where the file system reports link counts (POSIX;
 * not Windows), unless [[allowHardLinks]] is set (#3272).
 *
 * @param maxFileSize Maximum file size to read in bytes (default: 1MB)
 * @param allowedPaths Paths that are allowed to be accessed. None means any path. An entry that is itself reached
 *                     through a symbolic link (such as `/tmp` on macOS) is resolved, and so are the paths checked
 *                     against it.
 * @param blockedPaths Paths that are blocked from access (takes precedence over allowedPaths). Matched on the real
 *                     location too, so blocking `/etc` also blocks `/private/etc` on macOS, and the default `/var`
 *                     also blocks `/private/var`, where macOS keeps per-user temporary directories, and
 *                     by file identity, so a blocked entry reached through a macOS firmlink
 *                     (`/System/Volumes/Data/...`) is blocked too. Matched without
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
 * @param blockedNames File names blocked wherever they are, in every tool: a path with a component equal to one, or
 *                     to one followed by `.` and a suffix (`.env` blocks `.env` and `.env.local`), whatever its case,
 *                     is refused. The default is [[FileConfig.DefaultBlockedNames]], files that hold secrets in a
 *                     project directory (`.env`, `.envrc`); `Seq.empty` turns it off.
 * @param allowHardLinks Whether `read_file` may read a regular file that other hard links also name (default:
 *                       false). A hard link planted in an allowed directory is a file elsewhere under an allowed
 *                       name, which no path check can tell apart; where the file system reports no link count
 *                       (Windows) a file is taken to have one.
 */
case class FileConfig(
  maxFileSize: Long = 1024 * 1024,
  allowedPaths: Option[Seq[String]] = None,
  blockedPaths: Seq[String] = FileConfig.DefaultBlockedPaths,
  followSymlinks: Boolean = false,
  blockedNames: Seq[String] = FileConfig.DefaultBlockedNames,
  allowHardLinks: Boolean = false
) {

  /**
   * Check if a path is allowed based on configuration, by its real location (see the class documentation).
   */
  def isPathAllowed(path: Path): Boolean = resolve(path).isDefined

  /** The real path to open when `path` is allowed. The tools open this, not the path they were given. */
  private[builtin] def resolve(path: Path): Option[Path] =
    PathPolicy.resolve(path, entries)

  /** The allowed and blocked entries resolved now, for checking many paths in a row (one shell command's). */
  private[builtin] def entries: PathPolicy.Entries = PathPolicy.prepare(allowedPaths, blockedPaths, blockedNames)

  /**
   * The blocked paths and names for a tool description, with the user's home directory written `~` and other
   * users' homes collapsed, so the description sent to the model provider carries no account name.
   */
  private[builtin] def describedBlockedPaths: String =
    (FileConfig.describe(blockedPaths, FileConfig.userHome) +: blockedNames).filter(_.nonEmpty).mkString(", ")

  /** The allowed paths for a tool description, the home directory written `~` as for blocked paths. */
  private[builtin] def describedAllowedPaths: Option[String] =
    allowedPaths.map(FileConfig.describe(_, FileConfig.userHome))
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
  lazy val DefaultBlockedPaths: Seq[String] =
    defaultBlockedPaths(Platform.current, userHome, userHome.toSeq.flatMap(otherHomes))

  /**
   * The default [[FileConfig.blockedNames]]: `.env` and `.envrc`, which hold a project's secrets wherever the project
   * is (so `.env.local` and `.env.production` too).
   */
  val DefaultBlockedNames: Seq[String] = Seq(".env", ".envrc")

  /**
   * The other users' home directories beside `home`: the directories next to it when it is in a directory of homes
   * (`/home`, `/Users`, `C:\Users`, matched without regard to case), read once, when [[DefaultBlockedPaths]] is first
   * used. A home created later is not listed; an unreadable directory lists none.
   */
  private[builtin] def otherHomes(home: String): Seq[String] =
    Try {
      val own    = Paths.get(withoutTrailingSeparators(home)).toAbsolutePath.normalize()
      val parent = Option(own.getParent).filter(p => isHomesDirectory(p))
      parent.toSeq.flatMap { dir =>
        Using.resource(Files.list(dir)) { children =>
          children.iterator().asScala.filter(c => c != own && Files.isDirectory(c)).map(_.toString).toSeq.sorted
        }
      }
    }.getOrElse(Seq.empty)

  private def isHomesDirectory(dir: Path): Boolean =
    Option(dir.getFileName).exists(n => HomesDirectoryNames.contains(n.toString.toLowerCase(Locale.ROOT)))

  private val HomesDirectoryNames = Set("home", "users")

  /**
   * The default blocked paths for a platform and a home directory.
   *
   *  - Linux and other POSIX systems: `/etc`, `/var`, `/sys`, `/proc`, `/dev`, `/boot`, `/root` and `/run` (which
   *    holds `/run/secrets` and the per-user keyrings and sockets of `/run/user`).
   *  - macOS: `/etc`, `/var`, `/sys`, `/proc`, `/dev`, their real locations `/private/etc` and `/private/var` (real
   *    locations are compared anyway; listing them makes the list say what it blocks), and the system keychains in
   *    `/Library/Keychains`. Their `/System/Volumes/Data/...` firmlink spellings are blocked by file identity (#3237);
   *    `/System/Volumes/Data` itself is not listed, because `/home` and other automount points resolve under it.
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
   *  - Added in #3272, under the home directory: shell and REPL histories (`.bash_history`, `.zsh_history`, ...),
   *    `.vault-token`, the Terraform, Cargo, Gradle (`.gradle/gradle.properties`), RubyGems, 1Password, Helm,
   *    rclone, OCI, Databricks, Snowflake, s3cmd, Composer, NuGet, Yarn, pip, Poetry, container-registry and SOPS
   *    credentials, editor extension storage, and the Chrome, Chromium, Brave, Edge, Firefox, Thunderbird and Safari
   *    profiles, in each platform's location. `otherHomes` (the other users' home directories, [[otherHomes]]) are
   *    blocked whole.
   *
   * Without a home directory the home entries are left out.
   */
  private[builtin] def defaultBlockedPaths(
    platform: Platform,
    home: Option[String],
    otherHomes: Seq[String] = Seq.empty
  ): Seq[String] = {
    val separator = if (platform == Platform.Windows) "\\" else "/"
    val system = platform match {
      case Platform.Posix   => PosixSystem ++ Seq("/boot", "/root", "/run")
      case Platform.MacOS   => PosixSystem ++ Seq("/private/etc", "/private/var", "/Library/Keychains")
      case Platform.Windows => Seq("C:\\Windows", "C:\\ProgramData\\Microsoft\\Crypto", "C:\\ProgramData\\ssh")
    }
    val homeEntries = platform match {
      case Platform.Posix   => HomeCredentials :+ ".local/share/keyrings"
      case Platform.MacOS   => HomeCredentials ++ MacHomeCredentials
      case Platform.Windows => HomeCredentials ++ WindowsHomeCredentials
    }
    system ++ home.map(withoutTrailingSeparators).toSeq.flatMap { h =>
      homeEntries.map(relative => h + separator + relative.replace("/", separator))
    } ++ otherHomes
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
    ".sbt/.credentials",
    // added in #3272: shell and REPL histories, and more tools' credentials
    ".bash_history",
    ".zsh_history",
    ".sh_history",
    ".history",
    ".local/share/fish/fish_history",
    ".python_history",
    ".node_repl_history",
    ".psql_history",
    ".mysql_history",
    ".sqlite_history",
    ".rediscli_history",
    ".lesshst",
    ".vault-token",
    ".terraform.d/credentials.tfrc.json",
    ".terraformrc",
    ".cargo/credentials",
    ".cargo/credentials.toml",
    ".gradle/gradle.properties",
    ".gem/credentials",
    ".config/op",
    ".op",
    ".config/hub",
    ".config/glab-cli",
    ".config/helm",
    ".config/doctl",
    ".config/heroku",
    ".config/rclone",
    ".oci",
    ".ibmcloud",
    ".databrickscfg",
    ".snowflake",
    ".s3cfg",
    ".boto",
    ".config/composer/auth.json",
    ".composer/auth.json",
    ".nuget/NuGet/NuGet.Config",
    ".yarnrc",
    ".yarnrc.yml",
    ".config/pip/pip.conf",
    ".pip/pip.conf",
    ".config/pypoetry/auth.toml",
    ".config/containers/auth.json",
    ".config/sops",
    ".age",
    ".config/age",
    ".config/Code/User/globalStorage",
    ".mozilla",
    ".thunderbird",
    ".config/google-chrome",
    ".config/chromium",
    ".config/BraveSoftware",
    ".config/microsoft-edge",
    ".var/app"
  )

  private val MacHomeCredentials = Seq(
    "Library/Keychains",
    "Library/Cookies",
    "Library/Application Support/Google/Chrome",
    "Library/Application Support/Chromium",
    "Library/Application Support/BraveSoftware",
    "Library/Application Support/Microsoft Edge",
    "Library/Application Support/Firefox",
    "Library/Safari",
    "Library/Containers/com.apple.Safari",
    "Library/Group Containers/2BUA8C4S2C.com.1password",
    "Library/Application Support/Code/User/globalStorage"
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
    "AppData/Roaming/postgresql",
    // added in #3272: browser profiles and more tools' credentials
    "AppData/Local/Google/Chrome/User Data",
    "AppData/Local/Chromium/User Data",
    "AppData/Local/BraveSoftware",
    "AppData/Local/Microsoft/Edge/User Data",
    "AppData/Roaming/Mozilla",
    "AppData/Roaming/Thunderbird",
    "AppData/Roaming/NuGet/NuGet.Config",
    "AppData/Roaming/pip",
    "AppData/Roaming/pypoetry",
    "AppData/Roaming/terraform.rc",
    "AppData/Roaming/Code/User/globalStorage",
    "AppData/Roaming/Microsoft/Windows/PowerShell/PSReadLine",
    "AppData/Local/1Password"
  )

  private def withoutTrailingSeparators(path: String): String =
    path.reverse.dropWhile(c => c == '/' || c == '\\').reverse

  /** `paths` joined for a tool description, with `home` written `~`. */
  private[builtin] def describe(paths: Seq[String], home: Option[String]): String = {
    val h = home.map(withoutTrailingSeparators).filter(_.nonEmpty)
    // the directory holding the homes, as it is spelled in `home`, and its separator
    val homes = h.flatMap { h =>
      val cut = math.max(h.lastIndexOf('/'), h.lastIndexOf('\\'))
      Option.when(cut > 0)((h.take(cut), h.charAt(cut))).filter { case (dir, _) =>
        HomesDirectoryNames.contains(
          dir.drop(math.max(dir.lastIndexOf('/'), dir.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT)
        )
      }
    }
    def isOtherHome(path: String): Boolean =
      homes.exists { case (dir, sep) =>
        val rest = withoutTrailingSeparators(path).stripPrefix(dir + sep)
        rest != withoutTrailingSeparators(path) && rest.nonEmpty && !rest.exists(c => c == '/' || c == '\\') &&
        !h.contains(withoutTrailingSeparators(path))
      }
    val others = homes.filter(_ => paths.exists(isOtherHome)).map { case (dir, sep) => s"$dir$sep* (other users)" }
    (paths
      .filterNot(isOtherHome)
      .map(path =>
        h.filter(h => path == h || path.startsWith(h + "/") || path.startsWith(h + "\\"))
          .fold(path)(h => "~" + path.drop(h.length))
      ) ++ others).mkString(", ")
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
 * @param maxFileSize Maximum file size to write in bytes: the size of the file after the write, so an append is
 *                    refused when the existing file plus the new content would exceed it
 * @param allowOverwrite Whether to allow overwriting existing files (an overwrite replaces the file atomically)
 * @param createDirectories Whether to create parent directories if they don't exist
 * @param allowHardLinks Whether an append may write to a regular file that other hard links also name (default:
 *                       false): an append writes in place, so through a hard link planted in an allowed directory it
 *                       would change a file elsewhere. A replace writes a new file, so it never does. Where the file
 *                       system reports no link count (Windows) a file is taken to have one.
 */
case class WriteConfig(
  allowedPaths: Seq[String],
  maxFileSize: Long = 10 * 1024 * 1024,
  allowOverwrite: Boolean = false,
  createDirectories: Boolean = true,
  allowHardLinks: Boolean = false
) {

  /** The allowed paths for a tool description, with the user's home directory written `~`. */
  private[builtin] def describedAllowedPaths: String = FileConfig.describe(allowedPaths, FileConfig.userHome)

  /**
   * Check if a path is allowed for writing, by its real location (see the class documentation).
   */
  def isPathAllowed(path: Path): Boolean = resolve(path).isDefined

  /** The real path to write when `path` is allowed. The tool writes this, not the path it was given. */
  private[builtin] def resolve(path: Path): Option[Path] =
    PathPolicy.resolve(path, Some(allowedPaths), Seq.empty)
}
