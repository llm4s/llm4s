package org.llm4s.util

import org.slf4j.LoggerFactory

import java.nio.file.attribute.{ PosixFilePermission, PosixFilePermissions }
import java.nio.file.{ FileAlreadyExistsException, Files, Path, Paths }
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Success, Try }

/**
 * Keeps a SQLite store's database file to its owner (#3087). The llm4s SQLite stores hold conversations, tool
 * results and documents in plain text, and SQLite creates a new file with the process umask - `0644` with the usual
 * `022` - so other local users could read it.
 *
 * [[prepare]] runs just before a store opens its file. On a file system with POSIX permissions it creates a missing
 * file empty with mode `0600` in one step (`Files.createFile` with the permissions as an attribute), so there is no
 * moment when the file exists with a wider mode; SQLite reads an empty file as a new database, and gives the
 * `-wal`, `-shm` and `-journal` files it creates later the database file's mode. An existing file's mode is left
 * alone: when it or one of those companion files is readable or writable by its group or others, a warning names
 * them. When the file cannot be created (its directory is missing, say) nothing happens, and SQLite's own open
 * reports the problem. The stores create no directories.
 *
 * Windows has no POSIX permissions: nothing is set or checked there, and a new file inherits its directory's ACL.
 */
private[llm4s] object PrivateSqliteFile:

  private val logger = LoggerFactory.getLogger(getClass)

  /** `rw-------`: the mode a new database file is created with. */
  val OwnerOnly: Set[PosixFilePermission] = PosixFilePermissions.fromString("rw-------").asScala.toSet

  /** The files SQLite keeps beside a database `x.db`: `x.db-wal`, `x.db-shm` and `x.db-journal`. */
  val CompanionSuffixes: Seq[String] = Seq("-wal", "-shm", "-journal")

  private val Shared = Set(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE
  )

  /**
   * [[prepare]] for a store opened by a plain `jdbc:sqlite:<dbPath>` URL. Nothing happens for an in-memory
   * (`:memory:`) or temporary (empty) database, or a `file:` URI; connection settings after a `?` are not part of
   * the file's name.
   */
  def prepare(dbPath: String): Unit =
    val file = dbPath.takeWhile(_ != '?')
    if file.nonEmpty && !file.startsWith(":") && !file.startsWith("file:") then Try(Paths.get(file)).foreach(prepare)

  /** Creates the database file at `path` owner-only if it is missing, else warns if it is shared (see above). */
  def prepare(path: Path): Unit =
    if supportsPosix(path) then
      val attribute = PosixFilePermissions.asFileAttribute(OwnerOnly.asJava)
      val preExisting = Try(Files.createFile(path, attribute)) match
        case Success(_)                             => false
        case Failure(_: FileAlreadyExistsException) => true
        case Failure(_)                             => false // SQLite's open reports why the file cannot be made
      val existing = (if preExisting then Seq(path) else Seq.empty) ++
        CompanionSuffixes.map(suffix => path.resolveSibling(s"${path.getFileName}$suffix"))
      val shared = existing.filter(isShared)
      if shared.nonEmpty then
        logger.warn(
          s"SQLite database ${shared.mkString(", ")} ${if shared.size == 1 then "is" else "are"} readable or " +
            "writable by group or others and hold their data in plain text; restrict to the owner (chmod 600)"
        )

  /** Whether the file system holding `path` has POSIX permissions (not Windows). */
  def supportsPosix(path: Path): Boolean =
    path.toAbsolutePath.getFileSystem.supportedFileAttributeViews.contains("posix")

  private def isShared(file: Path): Boolean =
    Files.exists(file) && Try(Files.getPosixFilePermissions(file).asScala.exists(Shared.contains)).getOrElse(false)
