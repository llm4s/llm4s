// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordFinally
package org.llm4s.runner

import scala.concurrent.duration.{ DurationLong, FiniteDuration }

import org.llm4s.shared._
import org.slf4j.LoggerFactory

import java.nio.charset.{ Charset, StandardCharsets }
import java.nio.file.{ Files, Path, Paths, StandardOpenOption }
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try, Using }
import scala.util.chaining._

/**
 * Implementation of WorkspaceAgentInterface that operates on a local filesystem workspace.
 *
 * @param workspaceRoot The root directory of the workspace
 * @param isWindows     True if the host OS is Windows.  Used to route built-in commands
 *                      (e.g. `echo`, `dir`) through `cmd.exe /c` because those names have
 *                      no standalone `.exe` on Windows.
 * @param sandboxConfig Optional sandbox config; if None, uses [[WorkspaceSandboxConfig.Permissive]]
 * @param programSearch where the programs a command names are looked for (#1790); the host's, bar in tests
 * @param gateWait      how long a command or a file operation waits at the command gate before it is refused
 *                      (`WORKSPACE_BUSY`)
 * @param startProcess  starts a process for the [[ProcessRegistry]]; the platform's, bar in tests
 * @param commitAfterGate how long after its gate wait a file change may still commit; past it, it is refused unchanged
 */
class WorkspaceAgentInterfaceImpl(
  workspaceRoot: String,
  isWindows: Boolean,
  sandboxConfig: Option[WorkspaceSandboxConfig] = None,
  programSearch: ProgramSearch = ProgramSearch.host(),
  gateWait: FiniteDuration = WorkspaceAgentInterfaceImpl.GateWait,
  startProcess: java.lang.ProcessBuilder => Process = _.start(),
  commitAfterGate: FiniteDuration = WorkspaceSandboxConfig.CommitAfterGate
) extends WorkspaceAgentInterface {

  private val logger = LoggerFactory.getLogger(getClass)

  private val rootPath = Paths.get(workspaceRoot).toAbsolutePath.normalize()

  private val config        = sandboxConfig.getOrElse(WorkspaceSandboxConfig.Permissive)
  private val defaultLimits = config.limits
  // the file size limit as kept: never more than a snapshot can hold whole, whatever an unvalidated config says
  private val maxFileSize: Long = math.min(defaultLimits.maxFileSize, WorkspaceSandboxConfig.MaxFileSize)
  private val defaultExclusions =
    if (config.excludePatterns.nonEmpty) config.excludePatterns
    else WorkspaceSandboxConfig.DefaultExclusions

  // Computed once at construction: allowlist entries are matched by their program key, as the command's first word
  // is - on Windows lower-cased and without a `.exe` / `.com` extension, so `GIT` and `sort.exe` entries are `git` and
  // `sort` and get those programs' rules (#1790, #1761).
  private val allowedCommandsNormalized: Set[String] =
    config.allowedCommands.map(ProgramResolver.programKey(_, isWindows))

  // Pre-formatted for use in EXECUTABLE_NOT_ALLOWED error messages; computed
  // once so we don't sort and join the set on every rejected command.
  private val allowedCommandsString: String =
    allowedCommandsNormalized.toSeq.sorted.mkString(", ")

  /** What an operation does at a path: reads it, or changes it (writes, edits, deletes). */
  sealed private[runner] trait Access
  private[runner] object Access {
    case object Read  extends Access
    case object Write extends Access
  }

  /**
   * `relativePath` as text, against the root: refused (`PATH_ESCAPE_ATTEMPT`) when the platform cannot parse it (a NUL;
   * on Windows `C:\x\f:s`, `x*`) or when `..` takes it above the root. What it names on disk is [[checkPolicy]]'s.
   */
  private def lexical(relativePath: String): Path = {
    val normalized = Try(rootPath.resolve(relativePath).normalize()).getOrElse {
      throw new WorkspaceAgentException(
        s"Path '${relativePath.replace("\u0000", "\\0")}' is not a valid path in the workspace",
        "PATH_ESCAPE_ATTEMPT",
        None
      )
    }
    if (!normalized.startsWith(rootPath))
      throw new WorkspaceAgentException(
        s"Path '$relativePath' attempts to escape the workspace",
        "PATH_ESCAPE_ATTEMPT",
        None
      )
    normalized
  }

  /**
   * The physical location of the entry `lex` names: its directory resolved through every link, plus its own name. An
   * operation on the entry itself (a delete, a snapshot of a link as itself) acts there; the root is its own entry.
   */
  private def entryOf(lex: Path): Path =
    if (lex == rootPath || lex.getParent == null) effective(lex) else effective(lex.getParent).resolve(lex.getFileName)

  /**
   * '''The path policy''' (design §4.33): the one check every file operation, walk entry and command working directory
   * goes through, made on the physical location it acts on - what the path names with every link resolved, or for an
   * operation on an entry itself, [[entryOf]]. No call site checks a path any other way.
   *
   *  - '''The workspace.''' The location must be inside the workspace root (`PATH_ESCAPE_ATTEMPT`).
   *  - '''`allowedPaths`''' (#870). With any, the location must be inside one: inside what it names, or inside the
   *    entry itself when it is a link (`PATH_NOT_ALLOWED`).
   *  - '''`readOnlyPaths`''' (#870). A change may not be inside one, as either (`PATH_READ_ONLY`).
   *  - '''`.git`.''' A change may not name `.git` or a path inside one (`PATH_NOT_ALLOWED`, #1721): git reads its
   *    configuration, hooks and attributes there and runs the programs they name.
   */
  private def checkPolicy(relativePath: String, place: Path, access: Access): Unit = {
    val realRoot = effective(rootPath)
    if (!place.startsWith(realRoot))
      throw new WorkspaceAgentException(
        s"Path '$relativePath' leads outside the workspace",
        "PATH_ESCAPE_ATTEMPT",
        None
      )
    def within(roots: List[Path]) = roots.exists(r => place.startsWith(effective(r)) || place.startsWith(entryOf(r)))
    if (allowedRoots.nonEmpty && !within(allowedRoots))
      throw new WorkspaceAgentException(
        s"Path '$relativePath' is outside the sandbox's allowed paths (${config.allowedPaths.mkString(", ")})",
        "PATH_NOT_ALLOWED",
        None
      )
    if (access == Access.Write) {
      if (within(readOnlyRoots))
        throw new WorkspaceAgentException(
          s"Path '$relativePath' is read-only in this sandbox (${config.readOnlyPaths.mkString(", ")})",
          "PATH_READ_ONLY",
          None
        )
      if (CommandPolicy.namesGitMetadata(place, realRoot, realRoot))
        throw new WorkspaceAgentException(
          s"Path '$relativePath' names '.git' or a path inside one; git reads its configuration, hooks and attributes " +
            "there and runs the programs they name, so the file operations may not write it",
          "PATH_NOT_ALLOWED",
          None
        )
    }
  }

  /**
   * `relativePath` for an operation that follows it (a read, a write through a link): checked by [[checkPolicy]] on
   * what it names on disk, and returned as the lexical path the operation uses.
   */
  private def resolvePath(relativePath: String, access: Access = Access.Read): Path = {
    val lex = lexical(relativePath)
    checkPolicy(relativePath, effective(lex), access)
    lex
  }

  /**
   * `relativePath` for an operation on the entry itself, which never follows it (a delete, a snapshot of a link as
   * itself): checked by [[checkPolicy]] on [[entryOf]], so a link whose target is outside the workspace can be removed,
   * and an entry reached through an aliased directory is judged where it really is.
   */
  private def resolveEntry(relativePath: String, access: Access): Path = {
    val lex = lexical(relativePath)
    checkPolicy(relativePath, entryOf(lex), access)
    lex
  }

  /**
   * What `path` names on disk: its nearest existing ancestor (itself included) with every link resolved, and the rest
   * of the path beneath that. A link that cannot be resolved (dangling, a loop) is taken as written, beneath its
   * resolved parent, so it never names more than its text does.
   */
  private def effective(path: Path): Path = effective(path, 0)

  /**
   * The entries within `dir`, to `depth`, as `Files.walk` gives them, but with `dir` itself followed when it is a link:
   * a walk does not follow its starting link, while every other operation (a listing, a read) does. Each entry is
   * named beneath `dir` as written, so its path is the one the caller asked about, and [[withinPolicy]] judges it.
   */
  private def walkFrom(dir: Path, depth: Int): java.util.stream.Stream[Path] = {
    val start = if (Files.isSymbolicLink(dir)) Try(dir.toRealPath()).getOrElse(dir) else dir
    Files.walk(start, depth).map(p => dir.resolve(start.relativize(p)))
  }

  /** Whether an entry a walk produced passes the path policy, before anything follows it. */
  private def withinPolicy(entry: Path): Boolean = Try(resolvePath(rootPath.relativize(entry).toString)).isSuccess

  /**
   * As [[effective]]; `hops` counts the links followed by hand. A link is found without following it, so a dangling
   * one (`out -> /tmp/new-file`, whose target a write would create) is resolved to where it points, never taken as a
   * plain missing name beneath the workspace. More than 40 hops (a loop) is refused.
   */
  private def effective(path: Path, hops: Int): Path = {
    if (hops > 40)
      throw new WorkspaceAgentException("Too many levels of symbolic links", "PATH_ESCAPE_ATTEMPT", None)
    @scala.annotation.tailrec
    def nearest(p: Path, rest: List[Path]): (Path, List[Path]) =
      if (p == null) (p, rest)
      else if (Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) (p, rest)
      else nearest(p.getParent, Option(p.getFileName).toList ++ rest)
    val (existing, rest) = nearest(path, Nil)
    val base = Option(existing) match {
      case None => path.getRoot
      case Some(e) =>
        Try(e.toRealPath()).toOption.getOrElse {
          // it exists but does not resolve: a dangling link (or one beneath a dangling link) - follow it by hand
          if (Files.isSymbolicLink(e)) {
            val target = Files.readSymbolicLink(e)
            effective(Option(e.getParent).fold(target)(_.resolve(target)).normalize(), hops + 1)
          } else Option(e.getParent).fold(e)(parent => effective(parent, hops + 1).resolve(e.getFileName))
        }
    }
    rest.foldLeft(base)((acc, part) => acc.resolve(part)).normalize()
  }

  // The sandbox's allowedPaths and readOnlyPaths, resolved against the root once; one that leads outside the root is
  // the root's own business to refuse, so it is resolved as given and simply never matches a workspace path. Each is
  // compared by its text and, through `effective`, by what it names on disk when the check runs.
  private val allowedRoots: List[Path]  = config.allowedPaths.map(p => rootPath.resolve(p).normalize())
  private val readOnlyRoots: List[Path] = config.readOnlyPaths.map(p => rootPath.resolve(p).normalize())

  /**
   * The lock every mutating file operation holds, so a compare-and-set (`expectedHash`) is atomic against every other
   * file operation of this runner.
   */
  private val fileLock = new java.util.concurrent.locks.ReentrantLock(true)

  /**
   * Every process this runner starts, and the command gate (design §4.33): the one holder of process references.
   * Cancel, disconnect, shutdown and the gate all act through it, and a process leaves it only when it has exited.
   */
  // each start, inside the registry's locked start step, first re-checks where the command runs
  private[runner] val processes = new ProcessRegistry(gateWait, b => { checkWorkingDirectory(b); startProcess(b) })

  /** The owner of the processes the direct [[executeCommand]] starts. */
  private val DirectOwner = new Object

  /**
   * `body` with the file lock and the whole command gate, both taken within one `gateWait` in all: a request queued
   * behind another file operation or a command is refused (`WORKSPACE_BUSY`, having done nothing) rather than started
   * after its client, whose deadline allows for one `gateWait`, has given up on it.
   */
  /** When the current file operation must have committed by: set by [[mutating]] as the request starts. */
  private val commitBy = new ThreadLocal[java.lang.Long]

  /**
   * Refuses (`WORKSPACE_BUSY`, nothing changed) a change about to be committed after its client has given up on it:
   * the client waits `CommandGateWait` plus a margin beyond `CommitAfterGate` for a response, so a request still
   * uncommitted past `gateWait + CommitAfterGate` (a slow hash, a slow ACL copy) is stopped before its write, move or
   * delete, never applied unseen. Called immediately before each commit step.
   */
  private def ensureInTime(path: String): Unit =
    Option(commitBy.get).foreach { by =>
      if (System.nanoTime() > by)
        throw new WorkspaceAgentException(
          s"The change to '$path' took longer than its client waits for it; nothing was changed",
          "WORKSPACE_BUSY",
          None
        )
    }

  private def mutating[A](body: => A): A = {
    val started  = System.nanoTime()
    val deadline = started + gateWait.toNanos
    commitBy.set(started + (gateWait + commitAfterGate).toNanos)
    def remaining = math.max(0L, deadline - System.nanoTime())
    def busy(what: String) =
      new WorkspaceAgentException(
        s"$what is in progress in the workspace; nothing was done (waited $gateWait)",
        "WORKSPACE_BUSY",
        None
      )
    if (!fileLock.tryLock(remaining, TimeUnit.NANOSECONDS)) throw busy("Another file operation")
    try processes.exclusive(remaining)(body)
    finally { fileLock.unlock(); commitBy.remove() }
  }

  /** The current [[FileSnapshotResponse.hash]] of `resolved`, its bytes read as a stream. */
  /**
   * The [[FileSnapshotResponse.hash]] of what `resolved` names, a link followed (to nothing, for a dangling one): a file
   * over `maxFileSize` is `oversized`, never read, so no snapshot or comparison reads more than the limit.
   */
  /**
   * Refuses (`NOT_A_FILE`) what is neither a directory nor a regular file - a FIFO, a socket, a device - before
   * anything opens it: opening a FIFO with no writer would block, and every file operation reads through here.
   */
  private def refuseSpecial(path: String, resolved: Path): Unit =
    if (Files.exists(resolved) && !Files.isDirectory(resolved) && !Files.isRegularFile(resolved))
      throw new WorkspaceAgentException(
        s"Path '$path' is not a regular file (a FIFO, socket or device); it is not read or written",
        "NOT_A_FILE",
        None
      )

  private def currentHash(resolved: Path): String =
    if (!Files.exists(resolved))
      // a link to nothing is an entry of its own, which a delete removes: it is not the absence of one
      if (Files.isSymbolicLink(resolved))
        // by the missing place the whole chain of links ends at: a link further along retargeted is another state
        FileSnapshotResponse.Kind.danglingHash(effective(resolved).toString)
      else FileSnapshotResponse.Kind.Absent
    else if (Files.isDirectory(resolved)) FileSnapshotResponse.Kind.Directory
    else if (!Files.isRegularFile(resolved)) {
      refuseSpecial(rootPath.relativize(resolved).toString, resolved)
      FileSnapshotResponse.Kind.Absent // not reached: refuseSpecial throws for what is neither a directory nor a file
    } else if (Files.size(resolved) > maxFileSize) FileSnapshotResponse.Kind.Oversized
    else {
      // read at most one byte past the limit, whatever the size said: a file growing while it is read is `oversized`
      val digest = java.security.MessageDigest.getInstance("SHA-256")
      val within = Using.resource(openRead(resolved)) { in =>
        val buffer = new Array[Byte](8192)
        var total  = 0L
        var n      = in.read(buffer)
        while (n != -1 && total <= maxFileSize) {
          digest.update(buffer, 0, n)
          total += n
          n = if (total <= maxFileSize) in.read(buffer) else -1
        }
        total <= maxFileSize
      }
      if (within) "sha256:" + digest.digest().map(b => f"${b & 0xff}%02x").mkString
      else FileSnapshotResponse.Kind.Oversized
    }

  /** What a delete compares: a link as itself (where it points), anything else as [[currentHash]] reads it. */
  private def entryHash(resolved: Path): String =
    if (Files.isSymbolicLink(resolved)) FileSnapshotResponse.Kind.linkHash(Files.readSymbolicLink(resolved).toString)
    else currentHash(resolved)

  private def checkExpected(
    path: String,
    resolved: Path,
    expected: String,
    hashOf: Path => String = currentHash
  ): Unit = {
    val current = hashOf(resolved)
    // an oversized file has no digest, so nothing can be compared with it
    if (current == FileSnapshotResponse.Kind.Oversized || expected == FileSnapshotResponse.Kind.Oversized)
      throw new WorkspaceAgentException(
        s"Path '$path' is larger than the maximum size limit (${maxFileSize} bytes); it cannot be compared",
        "SIZE_LIMIT_EXCEEDED",
        None
      )
    if (current != expected)
      throw new WorkspaceAgentException(
        s"Path '$path' changed: it is now $current, not $expected",
        "HASH_MISMATCH",
        None
      )
  }

  /**
   * Creates file metadata for a given path.
   *
   * @param path The file path
   * @return FileMetadata object
   */
  private def createFileMetadata(path: Path): FileMetadata = {
    val file         = path.toFile
    val relativePath = rootPath.relativize(path).toString

    FileMetadata(
      path = relativePath,
      size = file.length(),
      isDirectory = file.isDirectory,
      lastModified = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(file.lastModified()))
    )
  }

  /**
   * Checks if a path matches any of the exclusion patterns.
   *
   * @param path The path to check
   * @param excludePatterns Patterns to exclude
   * @return true if the path should be excluded
   */
  private def isExcluded(path: String, excludePatterns: List[String]): Boolean = {
    // Simple glob matching implementation
    // In a real implementation, use a proper glob library
    val normalizedPath = path.replace("\\", "/")
    // a leading `**/` matches no directory as well as any, so `**/.git/**` excludes the root's `.git` too
    excludePatterns
      .flatMap { p =>
        val n = p.replace("\\", "/")
        if (n.startsWith("**/")) List(n, n.drop(3)) else List(n)
      }
      .exists { pattern =>
        val normalizedPattern = pattern
        // Use a placeholder to avoid corrupting ** when replacing *
        val placeholder = "\u0000DOUBLESTAR\u0000"
        val regex = normalizedPattern
          .replace("**", placeholder) // protect ** first
          .replace(".", "\\.")        // escape dots
          .replace("*", "[^/]+")      // single * matches path segment chars
          .replace(placeholder, ".*") // restore ** as .* to match any path

        normalizedPath.matches(regex) || (normalizedPath + "/").matches(regex)
      }
  }

  /**
   * List files and directories in a specified path, optionally recursively.
   */
  override def exploreFiles(
    path: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    maxDepth: Option[Int] = None,
    returnMetadata: Option[Boolean] = None
  ): ExploreFilesResponse = mutating { // under the gate: no command swaps a link between the checks and the reads
    val resolvedPath    = resolvePath(path)
    val isRecursive     = recursive.getOrElse(false)
    val depth           = maxDepth.getOrElse(if (isRecursive) 3 else 1)
    val includeMetadata = returnMetadata.getOrElse(false)
    val patterns        = excludePatterns.getOrElse(defaultExclusions)

    if (!Files.exists(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"Path '$path' does not exist",
        "PATH_NOT_FOUND",
        None
      )
    }

    if (!Files.isDirectory(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"Path '$path' is not a directory",
        "NOT_A_DIRECTORY",
        None
      )
    }

    val stream = if (isRecursive) walkFrom(resolvedPath, depth) else Files.list(resolvedPath)
    Using(stream) { s =>
      // Use lazy evaluation: filter and limit on the iterator before materializing to a list
      // This prevents loading the entire directory tree into memory for large repos
      val filteredFiles = s
        .iterator()
        .asScala
        .filter(withinPolicy) // before anything follows a link it lists
        // the workspace's files and directories (and links to nothing, which a delete removes); a FIFO, socket or
        // device is no file any operation reads or writes, so a listing never offers it as one
        .filter(p => Files.isDirectory(p) || Files.isRegularFile(p) || (Files.isSymbolicLink(p) && !Files.exists(p)))
        .filterNot { p =>
          val relativePath = rootPath.relativize(p).toString
          isExcluded(relativePath, patterns)
        }
        .take(defaultLimits.maxDirectoryEntries + 1)
        .toList

      val isTruncated = filteredFiles.size > defaultLimits.maxDirectoryEntries
      val files = filteredFiles.take(defaultLimits.maxDirectoryEntries).map { p =>
        val relativePath = rootPath.relativize(p).toString
        val isDir        = Files.isDirectory(p)

        FileEntry(
          path = relativePath,
          isDirectory = isDir,
          metadata = if (includeMetadata) Some(createFileMetadata(p)) else None
        )
      }

      ExploreFilesResponse(
        commandId = "local",
        files = files,
        isTruncated = isTruncated,
        totalFound = filteredFiles.size
      )
    }.get
  }

  /**
   * Read the content of a file, with options to read specific line ranges.
   */
  override def readFile(
    path: String,
    startLine: Option[Int] = None,
    endLine: Option[Int] = None
  ): ReadFileResponse = mutating { // under the gate, as every read: see fileSnapshot
    val resolvedPath = resolvePath(path)

    if (!Files.exists(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"File '$path' does not exist",
        "FILE_NOT_FOUND",
        None
      )
    }

    if (Files.isDirectory(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"Path '$path' is a directory, not a file",
        "NOT_A_FILE",
        None
      )
    }
    refuseSpecial(path, resolvedPath)

    val fileSize = Files.size(resolvedPath)
    if (fileSize > maxFileSize) {
      throw new WorkspaceAgentException(
        s"File '$path' exceeds maximum size limit (${maxFileSize} bytes)",
        "SIZE_LIMIT_EXCEEDED",
        Some(s"File size: $fileSize bytes")
      )
    }

    val metadata = createFileMetadata(resolvedPath)

    // read bounded by the limit, whatever the file has grown to since its size was checked, then decoded strictly
    val read = readBounded(checkedForRead(path, resolvedPath)).getOrElse(
      throw new WorkspaceAgentException(
        s"File '$path' exceeds maximum size limit (${maxFileSize} bytes)",
        "SIZE_LIMIT_EXCEEDED",
        None
      )
    )
    Try(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(read)).toString).map { text =>
      val lines      = Source.fromString(text).getLines().toList
      val totalLines = lines.size

      val start = startLine.map(l => math.max(1, math.min(l, totalLines)) - 1).getOrElse(0)
      val end   = endLine.map(l => math.max(start + 1, math.min(l, totalLines))).getOrElse(totalLines)

      val selectedLines = lines.slice(start, end)
      val content       = selectedLines.mkString("\n")

      ReadFileResponse(
        commandId = "local",
        content = content,
        metadata = metadata,
        isTruncated = false,
        totalLines = totalLines,
        returnedLines = selectedLines.size
      )
    } match {
      case Success(response) => response
      case Failure(e) =>
        throw new WorkspaceAgentException(
          s"Failed to read file '$path': ${e.getMessage}",
          "READ_ERROR",
          None
        )
    }
  }

  /**
   * `getfacl` and `setfacl`, as absolute paths resolved as a command's program is ([[ProgramResolver]]): from trusted
   * directories only, never the workspace or a relative `PATH` entry, so an agent cannot plant its own. `None` where
   * either is missing, and then no POSIX ACL is copied.
   */
  private lazy val aclTools: Option[(Path, Path)] =
    WorkspaceAgentInterfaceImpl.aclTools(programSearch, Seq(rootPath, effective(rootPath)))

  /**
   * The directory a command is about to start in, checked immediately before it starts (within the registry's start
   * step): it was pinned where it physically was, so it must still resolve to itself (no part of it swapped for a
   * link since) and still pass the path policy. A process never starts in a directory the policy has not just passed.
   */
  private def checkWorkingDirectory(builder: java.lang.ProcessBuilder): Unit =
    Option(builder.directory()).map(_.toPath).foreach { dir =>
      val relative = Try(effective(rootPath).relativize(dir).toString).getOrElse(dir.toString)
      if (effective(dir) != dir || !Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        throw new WorkspaceAgentException(
          s"The working directory '$relative' changed before the command started; it was not started",
          "PATH_ESCAPE_ATTEMPT",
          None
        )
      checkPolicy(relative, dir, Access.Read)
    }

  /**
   * Where `lex` physically leads, held to the path policy for a read: called immediately before a file is opened, so a
   * link retargeted since the request was first checked never leads a read outside the workspace or its allowed paths.
   */
  private def checkedForRead(path: String, lex: Path): Path = {
    val place = effective(lex)
    checkPolicy(path, place, Access.Read)
    // and what is there now must still be a file to open: a link retargeted at a FIFO is refused, never opened (an
    // open of one blocks until a writer comes)
    refuseSpecial(path, place)
    place
  }

  /**
   * The bytes of the file at `path`, read to at most `maxFileSize + 1`: `None` for a file over the limit, however large
   * it has grown since its size was checked, so it is never read whole into memory.
   */
  private def readBounded(path: Path): Option[Array[Byte]] =
    Using.resource(openRead(path)) { in =>
      val bytes = in.readNBytes(math.min(maxFileSize + 1, Int.MaxValue.toLong - 8).toInt)
      if (bytes.length.toLong > maxFileSize) None else Some(bytes)
    }

  /**
   * `secure(dir, name)` on `place`'s final name, relative to its directory held open, where the platform has secure
   * directory streams (Linux). `place`'s directory is a physical path (no link in it); the held directory is first
   * checked to be the one that path names (same file key, the path still resolving to itself), and a mismatch is
   * refused (`HASH_MISMATCH`), so a parent swapped for a link cannot redirect the operation. Only on a platform
   * without secure directory streams is `plain` run, by path, immediately after the caller's checks.
   */
  private def held[A](place: Path)(secure: (java.nio.file.SecureDirectoryStream[Path], Path) => A)(plain: => A): A = {
    import java.nio.file.{ LinkOption, SecureDirectoryStream }
    import java.nio.file.attribute.{ BasicFileAttributeView, BasicFileAttributes }
    val parent = place.getParent
    // a directory that no longer resolves to itself is refused below, never used by path: the plain path is only for a
    // platform without secure directory streams
    if (parent == null) plain
    else {
      val stream = Files.newDirectoryStream(parent)
      stream match {
        case s: SecureDirectoryStream[Path @unchecked] =>
          Try {
            val heldKey =
              Try(s.getFileAttributeView(classOf[BasicFileAttributeView]).readAttributes().fileKey()).toOption
            val namedKey =
              Try(
                Files.readAttributes(parent, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).fileKey()
              ).toOption
            if (heldKey.isEmpty || heldKey != namedKey || effective(parent) != parent)
              throw new WorkspaceAgentException(
                s"Path '${rootPath.relativize(place)}' changed while it was used: its directory was replaced",
                "HASH_MISMATCH",
                None
              )
            secure(s, place.getFileName)
          }.transform(
            a => { stream.close(); scala.util.Success(a) },
            e => { Try(stream.close()); scala.util.Failure(e) }
          ).get
        case _ =>
          stream.close()
          plain
      }
    }
  }

  /** `place` opened for reading through [[held]]: relative to its held directory, never following a final link. */
  private def openRead(place: Path): java.io.InputStream =
    // where the path leads, every link followed (a read follows them), opened by name through its held directory
    held(effective(place)) { (dir, name) =>
      val options = new java.util.HashSet[java.nio.file.OpenOption]()
      options.add(StandardOpenOption.READ)
      options.add(java.nio.file.LinkOption.NOFOLLOW_LINKS)
      java.nio.channels.Channels.newInputStream(dir.newByteChannel(name, options))
    }(Files.newInputStream(place))

  /** A file's hash, as [[currentHash]] gives it, from the bytes read. */
  private def digestOf(bytes: Array[Byte]): String =
    "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /**
   * Commits `bytes` as the content of `target`, where `path` really leads, atomically and in time: every file change
   * (a write in any mode, a line edit) is staged in a temporary file beside it and moved into place, so a reader, a
   * crash or a client that gave up sees the old content or the new, never part of either. Immediately before the move,
   * the path must still lead to `target` through the same links and pass the path policy, `target` must still be
   * `expected` when that is given (and the path itself `expectedAtPath`), and the change must be within its deadline ([[ensureInTime]]). File operations and
   * this runner's commands cannot change it meanwhile (the mutation lock and the command gate); these checks catch a
   * writer outside the runner, up to the move itself, the one step no file system call can fence.
   */
  private def commitStaged(
    path: String,
    target: Path,
    bytes: Array[Byte],
    expected: Option[String],
    expectedAtPath: Option[String] = None
  ): Unit = {
    import java.nio.file.{ LinkOption, SecureDirectoryStream }
    import java.nio.file.attribute.{ BasicFileAttributeView, BasicFileAttributes }
    val parent   = target.getParent
    val name     = target.getFileName
    val tempName = name.getFileSystem.getPath(s".llm4s-write-${java.util.UUID.randomUUID()}.tmp")
    val temp     = parent.resolve(tempName)
    // the directory held open: where the platform has secure directory streams (Linux), the staged file is created,
    // and moved into place, relative to this handle, so a parent swapped for a link cannot redirect either
    Using.resource(Files.newDirectoryStream(parent)) { stream =>
      val secure = stream match {
        case s: SecureDirectoryStream[Path @unchecked] => Some(s)
        case _                                         => None
      }
      // the handle, and the path, are still the directory the target was resolved in: the path still resolves to
      // itself (no part of it a link since), and the handle is the same directory as what the path names now
      def stillPinned(): Unit = {
        val held = secure.flatMap(s =>
          Try(s.getFileAttributeView(classOf[BasicFileAttributeView]).readAttributes().fileKey()).toOption
            .flatMap(Option(_))
        )
        val named =
          Try(Files.readAttributes(parent, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).fileKey()).toOption
            .flatMap(Option(_))
        // (without a secure stream there is no handle to compare: the path check alone, immediately before each step)
        if (effective(parent) != parent || (secure.isDefined && held != named))
          throw new WorkspaceAgentException(
            s"Path '$path' changed while it was written: its directory was replaced; nothing was written",
            "HASH_MISMATCH",
            None
          )
      }
      stillPinned()
      Try {
        // created as an ordinary new file is (the process umask applies), not with a temporary file's fixed rw-------
        secure match {
          case Some(s) =>
            val options = new java.util.HashSet[java.nio.file.OpenOption]()
            options.add(StandardOpenOption.CREATE_NEW)
            options.add(StandardOpenOption.WRITE)
            options.add(LinkOption.NOFOLLOW_LINKS)
            Using.resource(s.newByteChannel(tempName, options)) { channel =>
              val buffer = java.nio.ByteBuffer.wrap(bytes)
              while (buffer.hasRemaining) channel.write(buffer): Unit
            }
          case None =>
            Using.resource(Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))(
              _.write(bytes)
            )
        }
        stillPinned()
        // the replacement keeps the file's permissions (an executable script stays executable); a new file keeps the
        // mode it was created with. Not POSIX: the platform's defaults.
        // the name the metadata is copied onto is still the file staged through the handle (same file key), and each
        // change is made without following a link (copyOwnership), so a swapped directory cannot redirect them
        def sameStaged(): Unit = secure.foreach { s =>
          val staged =
            Try(
              s.getFileAttributeView(tempName, classOf[BasicFileAttributeView], LinkOption.NOFOLLOW_LINKS)
                .readAttributes()
                .fileKey()
            ).toOption
          val named =
            Try(Files.readAttributes(temp, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).fileKey()).toOption
          if (staged.isEmpty || staged != named)
            throw new WorkspaceAgentException(
              s"Path '$path' changed while it was written: its directory was replaced; nothing was written",
              "HASH_MISMATCH",
              None
            )
        }
        // the replacement is a new inode, so it takes the file's metadata as well as its mode: its owner and group
        // (the runner runs as root over a bind mount, and a root-owned replacement would lock its owner out) and its
        // ACL. Each where the platform has it; a runner that may not chown keeps the owner it has.
        if (Files.exists(target)) secure match {
          case Some(s) =>
            // the mode, owner and group read from, and set on, entries of the held directory: no path is resolved
            Try {
              val noFollow = LinkOption.NOFOLLOW_LINKS
              val from =
                s.getFileAttributeView(name, classOf[java.nio.file.attribute.PosixFileAttributeView], noFollow)
                  .readAttributes()
              val onto =
                s.getFileAttributeView(tempName, classOf[java.nio.file.attribute.PosixFileAttributeView], noFollow)
              Try(onto.setPermissions(from.permissions()))
              Try(onto.setOwner(from.owner()))
              Try(onto.setGroup(from.group()))
            }: Unit
            // the ACL and user extended attributes have no directory-relative call in the JVM: by path, never
            // following a link, the staged file's identity checked immediately before
            sameStaged()
            WorkspaceAgentInterfaceImpl.copyExtended(target, temp, aclTools)
          case None =>
            // the mode kept (an executable script stays executable); a new file keeps the mode it was created with
            Try(Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target)))
            WorkspaceAgentInterfaceImpl.copyOwnership(target, temp, aclTools)
        }
        val again = effective(resolvePath(path, Access.Write))
        if (again != target)
          throw new WorkspaceAgentException(
            s"Path '$path' changed while it was written: it now leads to another file; nothing was written",
            "HASH_MISMATCH",
            None
          )
        checkPolicy(path, target, Access.Write)
        expected.foreach(checkExpected(path, target, _))
        // and, when the change began at a link to nothing, the link itself: still that link, pointing where it did
        expectedAtPath.foreach(checkExpected(path, resolvePath(path, Access.Write), _))
        ensureInTime(path)
        stillPinned()
        secure match {
          // a rename within the held directory: atomic, and it replaces the file there
          case Some(s) => s.move(tempName, s, name)
          case None =>
            Files.move(
              temp,
              target,
              java.nio.file.StandardCopyOption.ATOMIC_MOVE,
              java.nio.file.StandardCopyOption.REPLACE_EXISTING
            ): Unit
        }
      }.recover { case e =>
        secure match {
          case Some(s) => Try(s.deleteFile(tempName)): Unit
          case None    => Try(Files.deleteIfExists(temp)): Unit
        }
        throw e
      }.get
    }
  }

  /**
   * Write content to a file, creating the file if it doesn't exist.
   */
  override def writeFile(
    path: String,
    content: String,
    mode: Option[String] = None,
    createDirectories: Option[Boolean] = None,
    expectedHash: Option[String] = None
  ): WriteFileResponse = mutating {
    val resolvedPath = resolvePath(path, Access.Write)
    refuseSpecial(path, resolvedPath)
    val writeMode  = mode.getOrElse("overwrite")
    val createDirs = createDirectories.getOrElse(false)

    if (expectedHash.isDefined && writeMode != "overwrite")
      throw new WorkspaceAgentException(
        s"expectedHash needs mode 'overwrite', not '$writeMode'",
        "INVALID_ARGUMENT",
        None
      )
    // the content's size bounded before it is encoded: every character is at least one byte (#870)
    if (content.length.toLong > maxFileSize)
      throw new WorkspaceAgentException(
        s"Content for '$path' exceeds maximum size limit (${maxFileSize} bytes)",
        "SIZE_LIMIT_EXCEEDED",
        None
      )
    // compared before anything is created, so a compare-and-set that loses changes nothing
    expectedHash.foreach(checkExpected(path, resolvedPath, _))

    writeMode match {
      case "create" =>
        if (Files.exists(resolvedPath))
          throw new WorkspaceAgentException(s"File '$path' already exists and mode is 'create'", "FILE_EXISTS", None)
      case "append" | "overwrite" => ()
      case _ =>
        throw new WorkspaceAgentException(
          s"Invalid write mode: $writeMode. Must be 'create', 'overwrite', or 'append'",
          "INVALID_ARGUMENT",
          None
        )
    }

    val bytes = content.getBytes(StandardCharsets.UTF_8)
    // the bound is on the file the write leaves: an append adds to what is there (under the mutation lock)
    val resulting =
      bytes.length.toLong + (if (writeMode == "append" && Files.isRegularFile(resolvedPath)) Files.size(resolvedPath)
                             else 0L)
    if (resulting > maxFileSize)
      throw new WorkspaceAgentException(
        s"Writing '$path' would leave it larger than the maximum size limit (${maxFileSize} bytes)",
        "SIZE_LIMIT_EXCEEDED",
        Some(s"Resulting size: $resulting bytes")
      )
    if (Files.isDirectory(resolvedPath))
      throw new WorkspaceAgentException(s"Path '$path' is a directory, not a file", "NOT_A_FILE", None)

    // only now, every refusal made, is anything created: a refused write leaves no directory behind. The directories
    // this request creates are part of its change, so a write that does not commit (refused, failed, or past its
    // deadline) removes them again: a client that gave up never finds them
    // each with its identity (its file key) as made: a roll-back removes only that directory, never one put in its place
    def identity(d: Path): Option[AnyRef] =
      Try(
        Files.readAttributes(
          d,
          classOf[java.nio.file.attribute.BasicFileAttributes],
          java.nio.file.LinkOption.NOFOLLOW_LINKS
        )
      ).toOption
        .filter(_.isDirectory)
        // its file key where the platform has one, else (Windows) its creation time: either tells it from a replacement
        .map(a => Option(a.fileKey()).getOrElse(a.creationTime()))
    var createdDirs = List.empty[(Path, Option[AnyRef])] // deepest first
    def rollBack(): Unit =
      createdDirs.foreach { case (d, made) =>
        // a directory something else filled stays, as does one whose identity is unknown or another's
        if (made.isDefined && identity(d) == made) Try(Files.delete(d))
      }
    // where the write lands, pinned once: its directories are made for it, and the commit must still lead to it
    val pinned = effective(resolvedPath)
    if (createDirs) {
      // the directories are created where the write really lands, checked by the path policy just before: through a
      // dangling link (`link -> new/dir/file`), the link target's directories
      val parent = pinned.getParent
      checkPolicy(path, parent, Access.Write)
      val missing = Iterator
        .iterate(parent)(_.getParent)
        .takeWhile(d => d != null && !Files.exists(d, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        .toList
        .reverse // shallowest first
      Try(missing.foreach { d =>
        // one at a time, each recorded only once this request made it: one another writer made meanwhile is theirs,
        // and a roll-back never removes it. Each is checked where it physically lands immediately before it is made:
        // an ancestor swapped for a link since cannot lead the next one elsewhere
        if (effective(d) != d)
          throw new WorkspaceAgentException(
            s"Path '$path' changed while its directories were created: it now leads elsewhere; nothing more was made",
            "HASH_MISMATCH",
            None
          )
        checkPolicy(path, d, Access.Write)
        Try(Files.createDirectory(d)) match {
          case Success(_) => createdDirs = (d, identity(d)) :: createdDirs
          case Failure(_: java.nio.file.FileAlreadyExistsException) if Files.isDirectory(d) => ()
          case Failure(e)                                                                   => throw e
        }
      }).flatMap { _ =>
        // a file in the way of the directory, already there, is as much a refusal as one met while creating
        if (Files.isDirectory(parent)) Success(())
        else Failure(new java.nio.file.NotDirectoryException(parent.toString))
      }.failed
        .foreach { e =>
          rollBack()
          e match {
            case refused: WorkspaceAgentException => throw refused // a path check's own refusal
            case _                                => ()
          }
          throw new WorkspaceAgentException(
            s"Cannot create the parent directories of '$path': ${e.getMessage}",
            "PARENT_NOT_A_DIRECTORY",
            None
          )
        }
    } else if (!Files.exists(resolvedPath.getParent)) {
      throw new WorkspaceAgentException(
        s"Parent directory for '$path' does not exist",
        "DIRECTORY_NOT_FOUND",
        None
      )
    }

    Try {
      // where the write really lands, held to the path policy now, immediately before anything is created there: a
      // link retargeted since the request was first checked cannot lead the write, or its directories, elsewhere. A
      // link is written through, as Files.write writes through it: the file it names changes and the link stays.
      // The target is the one pinned before any directory was made: the path must still lead there, or nothing is
      // written
      val target = pinned
      if (effective(resolvedPath) != pinned)
        throw new WorkspaceAgentException(
          s"Path '$path' changed while it was written: it now leads to another file; nothing was written",
          "HASH_MISMATCH",
          None
        )
      checkPolicy(path, target, Access.Write)
      if (!Files.isDirectory(target.getParent))
        throw new WorkspaceAgentException(
          s"Path '$path' changed while it was written: its directory is gone; nothing was written",
          "HASH_MISMATCH",
          None
        )
      import FileSnapshotResponse.Kind
      // every mode is staged and committed atomically, in time (commitStaged): what the file must still be when the
      // change commits, and what it then holds
      val (expected, staged) = writeMode match {
        case "create" => (Some(Kind.Absent), bytes)
        case "append" =>
          // what the file is and what it holds, from one read: a file deleted after it is read is still a mismatch
          // and never more of it than the limit allows: a file grown since its size was checked is refused unread
          def tooBig(size: Long) =
            new WorkspaceAgentException(
              s"Writing '$path' would leave it larger than the maximum size limit (${maxFileSize} bytes)",
              "SIZE_LIMIT_EXCEEDED",
              Some(s"Resulting size: at least $size bytes")
            )
          Try(readBounded(target)) match {
            case Success(None) => throw tooBig(maxFileSize + 1)
            case Success(Some(existing)) if existing.length.toLong + bytes.length > maxFileSize =>
              throw tooBig(existing.length.toLong + bytes.length)
            case Success(Some(existing))                       => (Some(digestOf(existing)), existing ++ bytes)
            case Failure(_: java.nio.file.NoSuchFileException) => (Some(Kind.Absent), bytes)
            case Failure(e)                                    => throw e
          }
        case _ =>
          // of `target` itself, the file the move replaces (through a link to nothing, the file it names is nothing)
          (expectedHash.map(e => if (Kind.isDangling(e)) Kind.Absent else e), bytes)
      }
      if (staged.length.toLong > maxFileSize)
        throw new WorkspaceAgentException(
          s"Writing '$path' would leave it larger than the maximum size limit (${maxFileSize} bytes)",
          "SIZE_LIMIT_EXCEEDED",
          Some(s"Resulting size: ${staged.length} bytes")
        )
      // a compare-and-set from a link to nothing compares the link too, as it was first compared, not only its target
      val atPath = expectedHash.filter(Kind.isDangling)
      Try(commitStaged(path, target, staged, expected, atPath)).recover {
        case e: WorkspaceAgentException if writeMode == "create" && e.code == "HASH_MISMATCH" =>
          throw new WorkspaceAgentException(s"File '$path' already exists and mode is 'create'", "FILE_EXISTS", None)
      }.get

      WriteFileResponse(
        commandId = "local",
        success = true,
        path = path,
        bytesWritten = bytes.length
      )
    }.recoverWith { case e =>
      rollBack()
      Failure(e)
    }.recover {
      case refused: WorkspaceAgentException => throw refused
      case e: Exception =>
        throw new WorkspaceAgentException(
          s"Failed to write to file '$path': ${e.getMessage}",
          "WRITE_ERROR",
          None
        )
    }.get
  }

  /**
   * Perform targeted modifications to a file without rewriting the entire content.
   */
  override def modifyFile(
    path: String,
    operations: List[FileOperation]
  ): ModifyFileResponse = mutating {
    val resolvedPath = resolvePath(path, Access.Write)

    if (!Files.exists(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"File '$path' does not exist",
        "FILE_NOT_FOUND",
        None
      )
    }

    if (Files.isDirectory(resolvedPath)) {
      throw new WorkspaceAgentException(
        s"Path '$path' is a directory, not a file",
        "NOT_A_FILE",
        None
      )
    }

    refuseSpecial(path, resolvedPath)
    // the file it reads, and the file it leaves, are both within the size limit (#870)
    def tooLarge(size: Long, what: String) =
      new WorkspaceAgentException(
        s"File '$path' $what the maximum size limit (${maxFileSize} bytes)",
        "SIZE_LIMIT_EXCEEDED",
        Some(s"Size: $size bytes")
      )
    // read, and later replaced, where the path really leads, held to the path policy there
    val target = effective(resolvedPath)
    checkPolicy(path, target, Access.Write)
    val currentSize = Files.size(target)
    if (currentSize > maxFileSize) throw tooLarge(currentSize, "exceeds")

    // read once, strictly as UTF-8: the bytes edited are the bytes whose hash the commit compares with
    val read = readBounded(target).getOrElse(throw tooLarge(Files.size(target), "exceeds"))
    val lines = Try(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(read)).toString)
      .map(text => Source.fromString(text).getLines().toList) match {
      case Success(fileLines) => fileLines
      case Failure(e) =>
        throw new WorkspaceAgentException(
          s"Failed to read file '$path': ${e.getMessage}",
          "READ_ERROR",
          None
        )
    }

    // Apply operations
    val modifiedLines = applyOperations(lines, operations)
    val separator     = System.lineSeparator().length.toLong
    val resultingSize =
      modifiedLines.iterator.map(l => l.getBytes(StandardCharsets.UTF_8).length.toLong + separator).sum
    if (resultingSize > maxFileSize) throw tooLarge(resultingSize, "would exceed")

    // committed atomically and in time, as a write is, and only if the file is still what was read
    val edited = modifiedLines.iterator.map(_ + System.lineSeparator()).mkString.getBytes(StandardCharsets.UTF_8)
    Try(commitStaged(path, target, edited, Some(digestOf(read)))) match {
      case Success(_) =>
        ModifyFileResponse(
          commandId = "local",
          success = true,
          path = path
        )
      case Failure(refused: WorkspaceAgentException) => throw refused
      case Failure(e) =>
        throw new WorkspaceAgentException(
          s"Failed to write modified content to file '$path': ${e.getMessage}",
          "WRITE_ERROR",
          None
        )
    }
  }

  /**
   * What is at `path`, read under the mutation lock so it is never a write half done: its kind, the hash a
   * compare-and-set compares with and, when asked for, its exact text, with the hash taken from the same bytes.
   */
  override def fileSnapshot(
    path: String,
    includeContent: Boolean = true,
    followLinks: Boolean = true
  ): FileSnapshotResponse =
    // under the gate as well as the lock: a command cannot swap a link it resolved through while it is read
    mutating {
      import FileSnapshotResponse.Kind
      // a link described as itself is never followed; anything else is resolved and checked as any path
      val resolved = if (followLinks) resolvePath(path) else resolveEntry(path, Access.Read)
      def response(kind: String, hash: String, size: Long, content: Option[String], error: Option[String]) =
        FileSnapshotResponse("local", path, kind, hash, size, content, error)
      if (!followLinks && Files.isSymbolicLink(resolved))
        response(Kind.Link, entryHash(resolved), 0L, None, None)
      else if (!Files.exists(resolved) && Files.isSymbolicLink(resolved))
        response(
          Kind.Dangling,
          Kind.danglingHash(effective(resolved).toString), // where the whole chain ends, as currentHash
          0L,
          None,
          Some("a link to something that does not exist")
        )
      else if (!Files.exists(resolved))
        response(Kind.Absent, Kind.Absent, 0L, None, None)
      else if (Files.isDirectory(resolved)) response(Kind.Directory, Kind.Directory, 0L, None, None)
      else {
        // classified, sized, hashed and read from one place: where the path leads now, checked immediately before, so
        // a file deleted or replaced meanwhile is reported as what it has become, never as a file with another's hash
        val place = checkedForRead(path, resolved)
        def size  = Try(Files.size(place)).getOrElse(0L)
        if (!Files.exists(place)) response(Kind.Absent, Kind.Absent, 0L, None, None)
        else if (Files.isDirectory(place)) response(Kind.Directory, Kind.Directory, 0L, None, None)
        else if (!includeContent) {
          val hash = currentHash(place)
          val kind =
            if (hash == Kind.Absent) Kind.Absent else if (hash == Kind.Directory) Kind.Directory else Kind.File
          response(kind, hash, if (kind == Kind.File) size else 0L, None, None)
        } else if (size > maxFileSize)
          response(
            Kind.File,
            Kind.Oversized,
            size,
            None,
            Some(s"the file is $size bytes, larger than the limit of ${maxFileSize} bytes")
          )
        else {
          // read once, bounded by the limit even if the file grew since its size was read
          Try(Using.resource(openRead(place))(_.readNBytes((maxFileSize + 1).toInt))) match {
            // deleted since it was classified: what it has become
            case Failure(_: java.nio.file.NoSuchFileException) => response(Kind.Absent, Kind.Absent, 0L, None, None)
            case Failure(e)                                    => throw e
            case Success(bytes) =>
              if (bytes.length.toLong > maxFileSize)
                response(
                  Kind.File,
                  Kind.Oversized,
                  size,
                  None,
                  Some(s"the file is larger than the limit of ${maxFileSize} bytes")
                )
              else {
                val decoded = Try(
                  StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString
                )
                response(
                  Kind.File,
                  FileSnapshotResponse.hashOf(bytes),
                  bytes.length.toLong,
                  decoded.toOption,
                  decoded.failed.toOption.map(_ => "the file is not valid UTF-8 text")
                )
              }
          }
        }
      }
    }

  /** Deletes the file, or the empty directory, `path`; with `expectedHash`, only if its hash is still that. */
  override def deleteFile(path: String, expectedHash: Option[String] = None): DeleteFileResponse =
    mutating {
      // the entry itself: a link is removed, never followed, so only its directory is held to the path checks
      val resolved = resolveEntry(path, Access.Write)
      if (resolved == rootPath)
        throw new WorkspaceAgentException("The workspace root cannot be deleted", "INVALID_ARGUMENT", None)
      // acted on where it physically is, the parent resolved through links: the same entry, checked and removed
      val entry = entryOf(resolved)
      expectedHash.foreach(checkExpected(path, entry, _, entryHash))
      if (!Files.exists(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        throw new WorkspaceAgentException(s"File '$path' does not exist", "FILE_NOT_FOUND", None)
      val wasDirectory = Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)
      // immediately before the removal, as before a write's commit: the path still names the same entry, through the
      // same links and passing the path policy, and the change is within its deadline
      if (entryOf(resolveEntry(path, Access.Write)) != entry)
        throw new WorkspaceAgentException(
          s"Path '$path' changed while it was deleted: it now leads elsewhere; nothing was deleted",
          "HASH_MISMATCH",
          None
        )
      expectedHash.foreach(checkExpected(path, entry, _, entryHash)) // and still what was compared
      ensureInTime(path)
      Try(
        held(entry)((dir, name) => if (wasDirectory) dir.deleteDirectory(name) else dir.deleteFile(name))(
          Files.delete(entry)
        )
      ).recoverWith {
        // a held directory's removal of a non-empty one, however the platform words it
        case _ if wasDirectory && Try(Using.resource(Files.list(entry))(_.findAny().isPresent)).getOrElse(false) =>
          scala.util.Failure(new java.nio.file.DirectoryNotEmptyException(entry.toString))
      } match {
        case Success(_) => DeleteFileResponse("local", path, wasDirectory)
        case Failure(_: java.nio.file.DirectoryNotEmptyException) =>
          throw new WorkspaceAgentException(
            s"Directory '$path' is not empty; delete the files in it",
            "DIRECTORY_NOT_EMPTY",
            None
          )
        case Failure(e) =>
          throw new WorkspaceAgentException(s"Failed to delete '$path': ${e.getMessage}", "WRITE_ERROR", None)
      }
    }

  /**
   * Apply file operations to a list of lines.
   *
   * @param lines Original file lines
   * @param operations Operations to apply
   * @return Modified lines
   */
  private def applyOperations(lines: List[String], operations: List[FileOperation]): List[String] =
    operations.foldLeft(lines) { (currentLines, operation) =>
      operation match {
        case ReplaceOperation(_, startLine, endLine, newContent) =>
          val start = math.max(1, startLine) - 1
          val end   = math.min(endLine, currentLines.size)

          if (start >= currentLines.size || end < start) {
            throw new WorkspaceAgentException(
              s"Invalid line range: $startLine-$endLine for file with ${currentLines.size} lines",
              "INVALID_ARGUMENT",
              None
            )
          }

          val newLines = newContent.split("\n").toList
          currentLines.take(start) ++ newLines ++ currentLines.drop(end)

        case InsertOperation(_, afterLine, newContent) =>
          val pos = math.min(afterLine, currentLines.size)

          if (pos < 0) {
            throw new WorkspaceAgentException(
              s"Invalid line position: $afterLine",
              "INVALID_ARGUMENT",
              None
            )
          }

          val newLines = newContent.split("\n").toList
          currentLines.take(pos) ++ newLines ++ currentLines.drop(pos)

        case DeleteOperation(_, startLine, endLine) =>
          val start = math.max(1, startLine) - 1
          val end   = math.min(endLine, currentLines.size)

          if (start >= currentLines.size || end < start) {
            throw new WorkspaceAgentException(
              s"Invalid line range: $startLine-$endLine for file with ${currentLines.size} lines",
              "INVALID_ARGUMENT",
              None
            )
          }

          currentLines.take(start) ++ currentLines.drop(end)

        case RegexReplaceOperation(_, pattern, replacement, flags) =>
          val patternFlags = flags.getOrElse("")
          val regexFlags = {
            var result = 0
            if (patternFlags.contains("i")) result |= Pattern.CASE_INSENSITIVE
            if (patternFlags.contains("m")) result |= Pattern.MULTILINE
            if (patternFlags.contains("s")) result |= Pattern.DOTALL
            result
          }

          val caseInsensitive = patternFlags.contains("i")
          val replaceGlobally = patternFlags.contains("g")

          // Literal-replacement fallback used when a pattern is rejected up front
          // or a per-line match aborts (e.g. exceeds the ReDoS step budget). This
          // changes regex semantics to plain substring replacement, so it is
          // logged (once) rather than applied silently.
          def literalReplace(line: String): String =
            if (replaceGlobally)
              WorkspaceRegexSafetyManager.replaceAllLiteral(line, pattern, replacement, caseInsensitive)
            else
              WorkspaceRegexSafetyManager.replaceFirstLiteral(line, pattern, replacement, caseInsensitive)

          WorkspaceRegexSafetyManager.safeCompile(pattern, regexFlags) match {
            case Right(regex) =>
              var loggedRuntimeFallback = false
              currentLines.map { line =>
                val replaced =
                  if (replaceGlobally) WorkspaceRegexSafetyManager.safeReplaceAll(regex, line, replacement)
                  else WorkspaceRegexSafetyManager.safeReplaceFirst(regex, line, replacement)
                replaced match {
                  case Right(result) => result
                  case Left(err) =>
                    if (!loggedRuntimeFallback) {
                      logger.warn(
                        s"Regex replace aborted at runtime ($err) for pattern '$pattern'; " +
                          "falling back to literal replacement for affected lines."
                      )
                      loggedRuntimeFallback = true
                    }
                    literalReplace(line)
                }
              }
            case Left(err) =>
              logger.warn(
                s"Rejected unsafe or invalid replace regex '$pattern' ($err); " +
                  "falling back to literal replacement."
              )
              currentLines.map(literalReplace)
          }
      }
    }

  /**
   * Search for content in files across the workspace.
   */
  override def searchFiles(
    paths: List[String],
    query: String,
    searchType: String,
    recursive: Option[Boolean] = None,
    excludePatterns: Option[List[String]] = None,
    contextLines: Option[Int] = None
  ): SearchFilesResponse = mutating { // under the gate, as every read: see fileSnapshot
    val isRecursive = recursive.getOrElse(true)
    val context     = contextLines.getOrElse(2)
    val patterns    = excludePatterns.getOrElse(defaultExclusions)

    if (!List("regex", "literal").contains(searchType)) {
      throw new WorkspaceAgentException(
        s"Invalid search type: $searchType. Must be 'regex' or 'literal'",
        "INVALID_ARGUMENT",
        None
      )
    }

    // Prepare regex pattern. An unsafe/invalid regex degrades to literal
    // substring search, which changes match semantics, so we log it rather than
    // failing silently.
    val pattern = if (searchType == "literal") {
      WorkspaceRegexSafetyManager.compileLiteral(query)
    } else {
      WorkspaceRegexSafetyManager.safeCompile(query) match {
        case Right(p) => p
        case Left(err) =>
          logger.warn(
            s"Rejected unsafe or invalid search regex '$query' ($err); " +
              "falling back to literal substring search."
          )
          WorkspaceRegexSafetyManager.compileLiteral(query)
      }
    }
    val literalFallbackPattern = WorkspaceRegexSafetyManager.compileLiteral(query)
    // Set once if any per-line match aborts (e.g. exceeds the step budget) and we
    // fall back to literal matching for that line.
    var loggedRuntimeFallback = false

    // Collect all files to search
    val filesToSearch = paths.flatMap { path =>
      val resolvedPath = resolvePath(path)

      if (!Files.exists(resolvedPath)) {
        throw new WorkspaceAgentException(
          s"Path '$path' does not exist",
          "PATH_NOT_FOUND",
          None
        )
      }

      if (Files.isDirectory(resolvedPath)) {
        val stream = if (isRecursive) walkFrom(resolvedPath, Int.MaxValue) else Files.list(resolvedPath)
        Using.resource(stream) { s =>
          s.iterator()
            .asScala
            .filter(withinPolicy) // before anything follows a link it lists
            .filter(p => Files.isRegularFile(p))
            .filterNot { p =>
              val relativePath = rootPath.relativize(p).toString
              isExcluded(relativePath, patterns)
            }
            .toList
        }
      } else {
        // a path named outright is held to the same rule as a walked one: a FIFO or socket is refused, never opened
        refuseSpecial(path, resolvedPath)
        List(resolvedPath)
      }
    }

    // Search in files
    val matches      = ListBuffer.empty[SearchMatch]
    var totalMatches = 0
    var done         = false // used to break out once we've observed one match past the cap

    // stop scanning as soon as we've counted one result beyond the configured
    // max; that allows us to report `isTruncated` correctly while avoiding a
    // full workspace sweep. the `totalMatches` value is therefore only guaranteed
    // to be accurate up to maxSearchResults+1.
    for (file <- filesToSearch if !done) {
      val relativePath = rootPath.relativize(file).toString

      // opened where it physically is, checked immediately before: an entry retargeted since the walk is skipped
      // and read bounded by the limit, as every read is: a file over it, or grown past it, is skipped unread whole
      Try(readBounded(checkedForRead(relativePath, file))).toOption.flatten
        .flatMap(bytes =>
          Try(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString).toOption
        )
        .map(text => Source.fromString(text).getLines().toList)
        .foreach { lines =>
          for ((line, lineIndex) <- lines.zipWithIndex if !done) {
            val isMatch =
              WorkspaceRegexSafetyManager.safeFind(pattern, line) match {
                case Right(m) => m
                case Left(err) =>
                  if (!loggedRuntimeFallback) {
                    logger.warn(
                      s"Regex search aborted at runtime ($err) for query '$query'; " +
                        "falling back to literal substring match for affected lines."
                    )
                    loggedRuntimeFallback = true
                  }
                  literalFallbackPattern.matcher(line).find()
              }

            if (isMatch) {
              totalMatches += 1

              if (matches.size < defaultLimits.maxSearchResults) {
                val lineNumber    = lineIndex + 1
                val beforeContext = lines.slice(math.max(0, lineIndex - context), lineIndex)
                val afterContext  = lines.slice(lineIndex + 1, math.min(lines.size, lineIndex + context + 1))

                matches += SearchMatch(
                  path = relativePath,
                  line = lineNumber,
                  matchText = line,
                  contextBefore = beforeContext,
                  contextAfter = afterContext
                )
              }

              // once we've seen one hit past the cap we can stop scanning entirely
              if (totalMatches > defaultLimits.maxSearchResults) {
                done = true
              }
            }
          }
        }
    }

    SearchFilesResponse(
      commandId = "local",
      matches = matches.toList,
      isTruncated = totalMatches > matches.size,
      totalMatches = totalMatches
    )
  }

  /**
   * Windows `cmd.exe` built-in commands that have no standalone `.exe` on PATH.
   * When [[isWindows]] is `true` and the first argv token is one of these, we
   * prepend `Seq("cmd.exe", "/d", "/c")` so the OS can locate the command (`/d`: no AutoRun command from the
   * registry runs first, #1792).
   * `ProcessBuilder` joins the vector into one command line that cmd.exe
   * parses again, quoting an argument only for a space, tab, `"`, `<` or `>`:
   * cmd.exe still splits on `,`, `;` and `=`, and interprets `&`, `|`, `<`,
   * `>`, `^` and `%`. [[ForbiddenArgChars]] and [[CommandPolicy]] refuse those
   * characters in a built-in's arguments (#1715).
   */
  private val WindowsBuiltins: Set[String] = CommandPolicy.WindowsBuiltins

  /** Whether the runner really runs on Windows, whatever `isWindows` says (tests set it on other hosts). */
  private val windowsHost: Boolean = System.getProperty("os.name", "").startsWith("Windows")

  /**
   * The null device every command reads its standard input from (#1728): `NUL` on Windows, `/dev/null` elsewhere.
   * Chosen by the host the runner runs on, not by `isWindows`, which tests set to exercise the Windows checks on
   * another host; on that host `NUL` would name a missing file and the command would fail to start.
   */
  private val nullDevice: java.io.File =
    new java.io.File(if (windowsHost) "NUL" else "/dev/null")

  /**
   * Shell metacharacters that must be rejected in every argument token, even
   * after tokenization.  These characters can still trigger command chaining
   * or redirection when the final argv is handed to `cmd.exe /c` (Windows
   * built-ins) or to a shell that is invoked indirectly.
   *
   *  - `&`, `|`           – command chaining / piping
   *  - `<`, `>`           – I/O redirection
   *  - `^`                – cmd.exe escape / line continuation
   *  - `;`                – command separator (sh) / ignored but confusing (cmd)
   *  - `` ` ``            – shell command substitution (bash/sh)
   *  - `$`                – shell variable expansion (bash/sh)
   *  - `%`                – cmd.exe environment variable expansion (e.g. `%PATH%`)
   */
  private val ForbiddenArgChars: Set[Char] = Set('&', '|', '<', '>', '^', ';', '`', '$', '%')

  /**
   * Tokenize a command string into an argument vector respecting single- and
   * double-quoted spans.  Quoted whitespace is preserved; quotes are consumed.
   *
   * Unclosed quotes: if an opening `'` or `"` has no matching closing quote,
   * the rest of the string is treated as part of that token (i.e. the missing
   * closing quote is implicitly assumed at end-of-input).  This mirrors the
   * behaviour of most Unix shells and avoids silently discarding content.
   *
   * This deliberately does NOT support shell variable expansion, globbing, or
   * any other shell meta-syntax – that is the whole point of the fix.
   */
  private def tokenizeCommand(command: String): Seq[String] = {
    val tokens  = Seq.newBuilder[String]
    val current = new StringBuilder
    var i       = 0

    /**
     * Advance i past the quoted span, appending characters to `current`.
     *  Stops at the matching `closeChar` or at end-of-string, whichever
     *  comes first (unclosed-quote policy: consume to end-of-input).
     */
    def consumeQuotedSpan(closeChar: Char): Unit = {
      i += 1 // skip the opening quote character
      while (i < command.length && command(i) != closeChar) {
        current.append(command(i))
        i += 1
      }
      // If we stopped on the closing quote, the outer loop's i += 1 will
      // advance past it.  If we hit end-of-string (i == command.length),
      // the outer loop condition will fail on the next iteration.
    }

    while (i < command.length) {
      command(i) match {
        case '"'                            => consumeQuotedSpan('"')
        case '\''                           => consumeQuotedSpan('\'')
        case '\\' if i + 1 < command.length =>
          // Backslash outside quotes: consume the next character literally.
          // e.g. `ls file\ name.txt` → Seq("ls", "file name.txt")
          i += 1
          current.append(command(i))
        case ' ' | '\t' =>
          if (current.nonEmpty) {
            tokens += current.toString()
            current.clear()
          }
        case c => current.append(c)
      }
      i += 1
    }
    if (current.nonEmpty) tokens += current.toString()
    tokens.result()
  }

  /**
   * Check a command and build the process that runs it, using direct argument-vector
   * execution (no shell interpolation).  The first token of the command must appear in
   * `config.allowedCommands` (see [[WorkspaceSandboxConfig.allowedCommands]]);
   * absolute/relative paths to executables are rejected, and the runner looks
   * the program up itself in trusted directories only (see layer 10).
   *
   * Validation layers (applied in order):
   *  1. `SHELL_DISABLED`              – sandbox config prohibits execution
   *  2. `EMPTY_COMMAND`               – tokenized argv is empty
   *  3. `EXECUTABLE_PATH_NOT_ALLOWED` – first token contains `/` or `\`
   *  4. `EXECUTABLE_NOT_ALLOWED`      – first token not in `config.allowedCommands`
   *  5. `FORBIDDEN_CHARACTERS`        – any token contains a character from
   *                                      [[ForbiddenArgChars]] (`&`, `|`, `<`,
   *                                      `>`, `^`, `;`, `` ` ``, `$`, `%`)
   *  6. `PATH_ESCAPE_ATTEMPT`         – the working directory really lies outside
   *                                      the workspace (a symbolic link out of it)
   *  7. `ENVIRONMENT_NOT_ALLOWED`     – `environment` sets a variable other than
   *                                      `LANG`, `LANGUAGE`, `LC_*`, `TZ`, `TERM`,
   *                                      `COLUMNS`, `LINES`, `NO_COLOR`
   *  8. `ARGUMENT_NOT_ALLOWED`        – an option that writes, deletes, runs a
   *                                      program or follows links (`find -exec`,
   *                                      `sort -o`, `git -c`, a git subcommand that
   *                                      is not a read, a second `uniq` operand)
   *  9. `PATH_ESCAPE_ATTEMPT`         – an argument names a location outside the
   *                                      workspace, links followed (on POSIX, an
   *                                      `rm` / `unlink` operand or `mv` source
   *                                      naming a link itself is judged by the
   *                                      directory holding it, #1730); and an `mv`
   *                                      or `cp` of several sources, one of whose
   *                                      paths goes through a name another
   *                                      source's operation changes, is refused
   *                                      with `ARGUMENT_NOT_ALLOWED` (#1776)
   *
   * 10. `EXECUTABLE_NOT_ALLOWED` / `EXECUTABLE_NOT_FOUND` – the program is found
   *                                      only where the runner never starts one
   *                                      from (the workspace, the runner's own
   *                                      working directory, a relative `PATH`
   *                                      entry), or nowhere (#1790)
   * 11. `GIT_CONFIG_NOT_ALLOWED` / `EXECUTION_FAILED` – `git` only: the repository's configuration holds what the
   *                                      runner cannot switch off (an include, a configured hook, a driver name `-c`
   *                                      cannot express), or cannot be listed; otherwise git is started as
   *                                      [[GitSandbox]] describes, so the repository cannot make it run a program (#1721)
   *
   * Layers 7-9 are [[CommandPolicy]], which documents each program's rules (#1715). Layer 10 is [[ProgramResolver]]:
   * the program is started by the absolute path it resolves, never by its bare name, so neither `CreateProcess` (which
   * searches the runner's current directory before the system directory) nor the JDK's `PATH` search (which reads a
   * relative entry from the command's working directory) picks a file an agent wrote. On Windows the first word is
   * matched by its program key: case-insensitively and without a `.exe` / `.com` extension.
   *
   * On Windows, if the first token is a [[WindowsBuiltins]] built-in that has
   * no standalone `.exe`, the system directory's `cmd.exe` (by its absolute path),
   * `/d` (no AutoRun command) and `/c` are prepended to the already-tokenized vector so each argument is
   * still passed as a distinct string (not a raw command string).  The
   * forbidden-character check runs before this routing step, so no dangerous
   * token ever reaches cmd.exe.
   *
   * The command's standard input is the null device (#1728), so a program that reads it ends at once. Its `PATH` keeps
   * only the entries the runner itself would search, or the system directories when none is left
   * ([[ProgramResolver.childPath]]), and on Windows it runs with
   * `NoDefaultCurrentDirectoryInExePath=1`, so a program it starts by name is never one from the workspace (#1792).
   *
   * Both ways a command reaches the runner go through this one function: [[executeCommand]] (the direct path)
   * and the WebSocket protocol's `ExecuteCommandCommand` (`WebSocketCommandExecutor`, #1756), which streams the
   * process's output instead of collecting it. Neither runs a shell over the command string.
   *
   * @return the process to start and the timeout that applies to it, or the refusal, carrying the code above
   */
  private[runner] def prepareCommand(
    command: String,
    workingDirectory: Option[String],
    timeout: Option[FiniteDuration],
    environment: Option[Map[String, String]]
  ): Either[WorkspaceAgentException, PreparedCommand] =
    Try(prepareCommandOrThrow(command, workingDirectory, timeout, environment)).toEither.left.map {
      case refused: WorkspaceAgentException => refused
      case other =>
        new WorkspaceAgentException(
          Option(other.getMessage).getOrElse("Failed to prepare command"),
          "EXECUTION_FAILED",
          None
        )
    }

  private def prepareCommandOrThrow(
    command: String,
    workingDirectory: Option[String],
    timeout: Option[FiniteDuration],
    environment: Option[Map[String, String]]
  ): PreparedCommand = {
    if (!config.shellAllowed) {
      throw new WorkspaceAgentException(
        "Shell execution is disabled by sandbox config (shellAllowed=false)",
        "SHELL_DISABLED",
        None
      )
    }

    // the default, the workspace root, goes through the path policy like any directory given
    // pinned where it physically is: the process starts there, re-checked immediately before (checkWorkingDirectory)
    val workDir = effective(resolvePath(workingDirectory.getOrElse("."))).toFile

    if (!workDir.exists() || !workDir.isDirectory) {
      throw new WorkspaceAgentException(
        s"Working directory does not exist or is not a directory",
        "INVALID_DIRECTORY",
        None
      )
    }

    val env = environment.getOrElse(Map.empty)

    // --- Security fix (Issue #787): direct argument-vector execution ----------
    // Tokenize without involving any shell so metacharacters are inert.
    val argv = tokenizeCommand(command)

    if (argv.isEmpty) {
      throw new WorkspaceAgentException(
        "Command string is empty",
        "EMPTY_COMMAND",
        None
      )
    }

    val executable = argv.head

    // Reject any executable that contains a path separator; callers must use
    // a bare name so the OS resolves it through PATH rather than a crafted path.
    if (executable.contains("/") || executable.contains("\\")) {
      throw new WorkspaceAgentException(
        s"Executable paths are not allowed ('$executable'). Use a bare command name.",
        "EXECUTABLE_PATH_NOT_ALLOWED",
        None
      )
    }

    // On Windows, command names are case-insensitive (GIT == git) and may carry `.exe` / `.com` (git.exe == git).
    val execLower = ProgramResolver.programKey(executable, isWindows)

    if (!allowedCommandsNormalized.contains(execLower)) {
      throw new WorkspaceAgentException(
        s"Executable '$executable' is not in the allowed list. " +
          s"Permitted executables: $allowedCommandsString",
        "EXECUTABLE_NOT_ALLOWED",
        None
      )
    }

    // Layer 5: reject any token that carries a shell metacharacter.
    // This is the critical defence for the Windows cmd.exe /c code path:
    // even though each token is a separate argv entry, cmd.exe still
    // interprets &, |, <, >, ^, and ; when it reconstructs the command line.
    argv.find(token => token.exists(ForbiddenArgChars.contains)).foreach { badToken =>
      badToken.find(ForbiddenArgChars.contains) match {
        case Some(badChar) =>
          throw new WorkspaceAgentException(
            s"Argument '$badToken' contains the forbidden character '$badChar'. " +
              s"Shell metacharacters are not allowed for security reasons. " +
              s"Forbidden characters: ${ForbiddenArgChars.toSeq.sorted.mkString("'", "', '", "'")}.",
            "FORBIDDEN_CHARACTERS",
            None
          )
        case None =>
      }
    }

    // Layer 6 (#1715): the working directory is judged by where it really leads, so
    // a symbolic link out of the workspace is refused.
    val realRoot    = Try(rootPath.toRealPath()).getOrElse(rootPath)
    val realWorkDir = Try(workDir.toPath.toRealPath()).getOrElse(workDir.toPath)
    if (!realWorkDir.startsWith(realRoot)) {
      throw new WorkspaceAgentException(
        s"Working directory '${workingDirectory.getOrElse(".")}' leads outside the workspace",
        CommandPolicy.PathEscapeAttempt,
        None
      )
    }
    // Layers 7-9 (#1715): the allowlist names programs, but an allowed program can
    // still write, delete or run another one through its environment
    // (`GIT_EXTERNAL_DIFF`) or its own options (`find -exec`, `git -c`, `sort -o`),
    // or reach outside the workspace through a path argument (`cat /etc/passwd`).
    // CommandPolicy checks them in that order, judging each path argument by where
    // it really leads.
    CommandPolicy
      .refusal(execLower, argv.tail, isWindows, realWorkDir, realRoot, env, Some(workDir.toPath), Some(rootPath))
      .foreach(refused => throw new WorkspaceAgentException(refused.message, refused.code, None))
    // Layer 10 (#1790): start the program by an absolute path found only in trusted directories. A bare name would
    // let CreateProcess run `<name>.exe` from the runner's current directory before the system directory, and the
    // JDK's PATH search read a relative entry from the working directory, inside the workspace.
    val untrustedRoots = Seq(rootPath, realRoot, workDir.toPath)
    def resolved(found: Either[CommandPolicy.Refusal, Path]): String =
      found.fold(refused => throw new WorkspaceAgentException(refused.message, refused.code, None), _.toString)

    // On Windows, built-in commands (echo, dir, type, …) live inside cmd.exe
    // and cannot be launched as standalone processes.  We prepend the system
    // directory's cmd.exe, "/d" (skip the registry's AutoRun command, #1792)
    // and "/c" to the *already-tokenized* vector so each
    // argument is still a separate string, and name the built-in by its key
    // (`echo`, never `echo.exe`, which cmd.exe would look for as a program).
    // ProcessBuilder still joins them into one command line that cmd.exe
    // re-parses, so the characters it would split or interpret were refused
    // above (ForbiddenArgChars and CommandPolicy, #1715).
    val routedThroughCmd = isWindows && WindowsBuiltins.contains(execLower)
    val finalArgv: Seq[String] =
      if (routedThroughCmd)
        Seq(
          resolved(ProgramResolver.resolveSystem("cmd", programSearch, untrustedRoots)),
          "/d",
          "/c",
          execLower
        ) ++ argv.tail
      else
        resolved(ProgramResolver.resolve(execLower, programSearch, untrustedRoots)) +: argv.tail
    // --------------------------------------------------------------------------

    // Use java.lang.ProcessBuilder with the tokenized argv directly – no shell
    // wrapper.  This preserves the destroyForcibly() and waitFor(timeout) APIs
    // introduced in #830 for reliable timeout handling.
    val builder = new java.lang.ProcessBuilder(finalArgv.asJava)
    builder.directory(workDir)
    // Nothing ever writes to the command's standard input, so give it the null device: a program that reads stdin
    // (`cat`, `sort`, `wc`, `grep x` with no file) gets end-of-file at once instead of waiting on an open pipe until
    // the command timeout (#1728).
    builder.redirectInput(java.lang.ProcessBuilder.Redirect.from(nullDevice))
    env.foreach { case (k, v) => builder.environment().put(k, v) }
    // The program may start another by name (`#!/usr/bin/env x`, sbt starting java), searching PATH from the
    // workspace: give it only the PATH entries the runner itself would search (#1792). No entry left: the system
    // directories (#1805), since without a PATH sh and bash search a default ending in `.`; only when even those are
    // untrusted, no PATH, since an empty one is read as the current directory.
    ProgramResolver.childPath(programSearch, untrustedRoots) match {
      case Some(path) => builder.environment().put("PATH", path)
      case None       => builder.environment().remove("PATH")
    }
    // On Windows, cmd.exe and CreateProcess search the current directory - the workspace - for a program named
    // without a path before PATH; this stops it, for every command and anything it starts (#1790, #1792)
    if (isWindows) builder.environment().put("NoDefaultCurrentDirectoryInExePath", "1")
    if (execLower == "git") {
      CommandPolicy.confineGit(builder.environment(), realRoot)
      sandboxGit(builder, finalArgv.head, argv.tail, realRoot)
    }

    PreparedCommand(builder, timeout.getOrElse(config.defaultCommandTimeout))
  }

  /**
   * Layer 11 (#1721): `git` runs with the environment, `-c` overrides and options of [[GitSandbox]], and every filter
   * and diff driver the repository's configuration defines blanked, so neither a configuration the agent wrote nor
   * one the repository arrived with makes an allowed read command run a program. Refused (`GIT_CONFIG_NOT_ALLOWED`)
   * when the configuration holds what the overrides cannot cover, or `EXECUTION_FAILED` when it cannot be listed.
   */
  private def sandboxGit(builder: java.lang.ProcessBuilder, git: String, args: Seq[String], realRoot: Path): Unit = {
    def failed(why: String): Nothing =
      throw new WorkspaceAgentException(s"git was not run: $why.", "EXECUTION_FAILED", None)

    val empty = GitSandbox.checkedEmptyDirectory(realRoot).fold(failed, identity)
    GitSandbox.environment(builder.environment(), empty)
    val drivers =
      if (!GitSandbox.hasSubcommand(args)) Nil
      else
        GitSandbox
          .listConfiguration(builder, git, empty)
          .fold(failed, identity)
          .pipe(GitSandbox.driverOverrides)
          .fold(refused => throw new WorkspaceAgentException(refused.message, refused.code, None), identity)
    builder.command((git +: GitSandbox.arguments(args, GitSandbox.fixedOverrides(empty) ++ drivers)).asJava)
  }

  /**
   * Execute a command in the workspace and collect its output. The command is checked and built by
   * [[prepareCommand]]; a refusal is thrown as a WorkspaceAgentException carrying its code.
   */
  override def executeCommand(
    command: String,
    workingDirectory: Option[String] = None,
    timeout: Option[FiniteDuration] = None,
    environment: Option[Map[String, String]] = None,
    maxOutputBytes: Option[Long] = None
  ): ExecuteCommandResponse = {
    val prepared =
      prepareCommand(command, workingDirectory, timeout, environment).fold(refused => throw refused, identity)
    val builder   = prepared.builder
    val timeoutMs = org.llm4s.shared.WireDurations.toWholeMillis(prepared.timeout)
    val limit     = WebSocketCommandExecutor.outputLimit(maxOutputBytes, defaultLimits.maxOutputSize)

    val stdout          = new StringBuilder
    val stderr          = new StringBuilder
    val stdoutTruncated = new java.util.concurrent.atomic.AtomicBoolean(false)
    val stderrTruncated = new java.util.concurrent.atomic.AtomicBoolean(false)
    val startTime       = System.currentTimeMillis()

    // Read in chunks and keep at most `limit` characters, the rest read and discarded: a bound while the output is
    // read, whatever its lines (a line read whole could be any size).
    def capture(
      stream: java.io.InputStream,
      into: StringBuilder,
      truncated: java.util.concurrent.atomic.AtomicBoolean
    ): Thread =
      new Thread(() => {
        val reader = new java.io.InputStreamReader(stream, Charset.defaultCharset())
        try {
          val buffer   = new Array[Char](8192)
          var captured = 0L // in encoded bytes, as the WebSocket path bounds it
          var n        = reader.read(buffer)
          while (n != -1) {
            val (take, used) = WebSocketCommandExecutor.fitting(buffer, n, math.max(limit - captured, 0L))
            into.appendAll(buffer, 0, take)
            captured += used
            if (take < n) truncated.set(true)
            n = reader.read(buffer)
          }
        } finally reader.close()
      })

    // started through the registry: it holds a gate permit and stays tracked (killable by shutdown) until it exits
    val entry = processes
      .spawn(DirectOwner, java.util.UUID.randomUUID().toString, builder)
      .fold(refused => throw refused, identity)
    val exitCode =
      Try {
        val process = entry.process

        // Read stdout and stderr in background threads to prevent blocking
        val stdoutThread = capture(process.getInputStream, stdout, stdoutTruncated)
        val stderrThread = capture(process.getErrorStream, stderr, stderrTruncated)

        stdoutThread.setDaemon(true)
        stderrThread.setDaemon(true)
        stdoutThread.start()
        stderrThread.start()

        val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

        if (!completed) {
          // its descendants recorded first, while they can still be found: the kill below takes them even if the parent
          // has gone by then
          Try(process.descendants().toList.asScala).getOrElse(Nil).foreach(entry.tree.add(_): Unit)
          process.destroy()
          // Wait up to 2 seconds for graceful termination before escalating
          process.waitFor(2, TimeUnit.SECONDS): Unit
          // then the whole tree, through the registry, whether or not the parent went: a descendant may outlive it
          val gone = processes.kill(entry, asCancellation = false)
          // a timeout is an outcome only once the process is gone: one still alive may yet write, so its outcome
          // is unknown, a failure, never a completed result a replay would return
          if (!gone)
            throw new WorkspaceAgentException(
              s"Command timed out after ${timeoutMs}ms and did not terminate when killed",
              "EXECUTION_FAILED",
              None
            )
          throw new WorkspaceAgentException(
            s"Command execution timed out after ${timeoutMs}ms",
            "TIMEOUT",
            None
          )
        }

        // Wait for output threads to finish capturing
        stdoutThread.join(2000)
        stderrThread.join(2000)

        // complete only once the registry has released it: what it left running is killed and waited for first
        if (Try(entry.released.get(WebSocketCommandExecutor.ReleaseWait.toMillis, TimeUnit.MILLISECONDS)).isFailure)
          throw new WorkspaceAgentException(
            "A process the command started did not terminate when killed; it may still be running",
            "EXECUTION_FAILED",
            None
          )
        process.exitValue()
      }.recover {
        case e: Exception if !e.isInstanceOf[WorkspaceAgentException] =>
          throw new WorkspaceAgentException(
            s"Failed to execute command: ${e.getMessage}",
            "EXECUTION_FAILED",
            None
          )
      }.get

    val duration          = System.currentTimeMillis() - startTime
    val isStdoutTruncated = stdoutTruncated.get()
    val isStderrTruncated = stderrTruncated.get()

    ExecuteCommandResponse(
      commandId = "local",
      stdout = stdout.toString(),
      stderr = stderr.toString(),
      exitCode = exitCode,
      isOutputTruncated = isStdoutTruncated || isStderrTruncated,
      duration = duration.millis
    )
  }

  /**
   * Retrieve information about the workspace, including default settings and limits.
   */
  override def getWorkspaceInfo(): GetWorkspaceInfoResponse =
    GetWorkspaceInfoResponse(
      commandId = "local",
      root = rootPath.toString,
      defaultExclusions = defaultExclusions,
      limits = defaultLimits.copy(maxFileSize = maxFileSize)
    )
}

/**
 * A command that passed every check of [[WorkspaceAgentInterfaceImpl.prepareCommand]].
 *
 * @param builder the process to start: the command's argument vector (never a shell over the command string), its
 *                working directory, its checked environment and the null device as standard input
 * @param timeout the caller's timeout, else the sandbox's `defaultCommandTimeout`
 */
final private[runner] case class PreparedCommand(builder: java.lang.ProcessBuilder, timeout: FiniteDuration)

private[runner] object WorkspaceAgentInterfaceImpl {

  /** How long a file mutation waits for running commands to end before it is refused as `WORKSPACE_BUSY`. */
  val GateWait: FiniteDuration = WorkspaceSandboxConfig.CommandGateWait

  /**
   * Gives `to` the owner, the group and the ACL of `from`, each where the file system has it and the runner may set
   * it; one it cannot is left as it is. For a replacement written beside a file and moved over it.
   */
  def copyOwnership(from: Path, to: Path, aclTools: Option[(Path, Path)] = None): Unit = {
    import java.nio.file.attribute.PosixFileAttributeView
    // every change to `to` is made without following a link: the staged file is a plain file the runner made, so
    // whatever its name might be made to lead to by a directory swapped meanwhile, no other file is ever changed
    val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
    Try(
      Files
        .getFileAttributeView(to, classOf[java.nio.file.attribute.FileOwnerAttributeView], noFollow)
        .setOwner(Files.getOwner(from))
    ): Unit
    Try {
      val group = Files.readAttributes(from, classOf[java.nio.file.attribute.PosixFileAttributes]).group()
      Files.getFileAttributeView(to, classOf[PosixFileAttributeView], noFollow).setGroup(group)
    }: Unit
    copyExtended(from, to, aclTools)
  }

  /**
   * Gives `to` the ACL and user extended attributes of `from`, each where the platform has it, never following a link
   * at `to`. The part of [[copyOwnership]] the JVM has no directory-relative call for.
   */
  def copyExtended(from: Path, to: Path, aclTools: Option[(Path, Path)]): Unit = {
    import java.nio.file.attribute.AclFileAttributeView
    val noFollow = java.nio.file.LinkOption.NOFOLLOW_LINKS
    // an NFSv4 / Windows ACL, where the platform exposes one as a view
    Try {
      Option(Files.getFileAttributeView(from, classOf[AclFileAttributeView])).foreach { acl =>
        Files.getFileAttributeView(to, classOf[AclFileAttributeView], noFollow).setAcl(acl.getAcl)
      }
    }: Unit
    // user extended attributes, where the file system has them
    Try {
      import java.nio.file.attribute.UserDefinedFileAttributeView
      Option(Files.getFileAttributeView(from, classOf[UserDefinedFileAttributeView])).foreach { source =>
        val target = Files.getFileAttributeView(to, classOf[UserDefinedFileAttributeView], noFollow)
        source.list().forEach { name =>
          val buffer = java.nio.ByteBuffer.allocate(source.size(name))
          source.read(name, buffer)
          buffer.flip()
          target.write(name, buffer): Unit
        }
      }
    }: Unit
    // setfacl follows a link it is given, so it is never given one
    if (!Files.isSymbolicLink(to))
      aclTools.foreach { case (getfacl, setfacl) => copyPosixAcl(from, to, getfacl, setfacl) }
  }

  /**
   * A Linux POSIX ACL lives in an extended attribute (`system.posix_acl_access`) the JVM cannot read, so it is copied
   * with `getfacl` and `setfacl`, run directly (no shell), when they are installed. The whole access ACL is set, a
   * basic one included, so an ACL the replacement inherited from its directory's default never survives.
   */
  private def copyPosixAcl(from: Path, to: Path, getfacl: Path, setfacl: Path): Unit =
    Try {
      val get = ProcessRegistry.startHelper(
        new ProcessBuilder(getfacl.toString, "--absolute-names", "--omit-header", from.toString)
          .redirectErrorStream(false)
      )
      val acl = new String(get.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      // set whatever it is, a basic one included: the replacement may have inherited a directory's default ACL,
      // which only setting the file's own access ACL removes
      if (get.waitFor() == 0 && acl.trim.nonEmpty) {
        val set = ProcessRegistry.startHelper(new ProcessBuilder(setfacl.toString, "--set-file=-", to.toString))
        Using.resource(set.getOutputStream)(_.write(acl.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        set.waitFor(): Unit
      }
    }: Unit

  /** `getfacl` and `setfacl` from trusted directories, never under `untrustedRoots`; `None` on Windows or if missing. */
  private[runner] def aclTools(search: ProgramSearch, untrustedRoots: Seq[Path]): Option[(Path, Path)] =
    if (search.windows) None
    else
      for {
        get <- ProgramResolver.resolve("getfacl", search, untrustedRoots).toOption
        set <- ProgramResolver.resolve("setfacl", search, untrustedRoots).toOption
      } yield (get, set)
}
