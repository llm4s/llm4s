package org.llm4s.agent.harness.workspace

import org.llm4s.shared.*

import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.concurrent.duration.*

/**
 * An in-memory `WorkspaceAgentInterface` with the runner's file semantics that the workspace backends rely on
 * (design §4.33): explicit directories, snapshots with the runner's hash format, compare-and-set writes and
 * deletes under one lock that change nothing when they lose, the runner's error codes, and listings cut at
 * `maxEntries`. Commands are answered by `exec`. Every method holds the instance's lock, as the runner's file
 * operations hold its mutation lock.
 */
final class FakeWorkspace(
  maxEntries: Int = 500,
  maxFileSize: Long = 1024L * 1024L,
  exec: (String, Option[String], Option[FiniteDuration], Option[Long]) => ExecuteCommandResponse = (c, _, _, _) =>
    ExecuteCommandResponse("local", s"ran $c", "", 0, isOutputTruncated = false, duration = 1.milli)
) extends WorkspaceAgentInterface:

  // "" is the root; a path is its segments joined with '/'
  private val dirs  = mutable.Set[String]("")
  private val files = mutable.Map[String, Array[Byte]]()
  // entries that are neither files nor directories (a FIFO, a socket): listed, never read
  private val specials = mutable.Set[String]()

  /** Adds a FIFO-like entry at `path`, which a snapshot refuses as NOT_A_FILE. */
  def special(path: String): Unit = synchronized(specials += norm(path))

  /** When set, every snapshot is refused as the runner refuses a request while a command holds its gate. */
  @volatile var busy: Boolean = false

  /** Every compare-and-set the workspace has applied: (operation, path). */
  val applied: mutable.Buffer[(String, String)] = mutable.Buffer()

  private def fail(code: String, message: String): Nothing = throw new WorkspaceAgentException(message, code, None)

  private def norm(path: String): String =
    val parts = path.replace('\\', '/').split('/').filter(p => p.nonEmpty && p != ".").toVector
    if parts.contains("..") then fail("PATH_ESCAPE_ATTEMPT", s"Path '$path' attempts to escape the workspace")
    parts.mkString("/")

  private def parentOf(p: String): Option[String] =
    if p.isEmpty then None else Some(if p.contains('/') then p.substring(0, p.lastIndexOf('/')) else "")

  private def ancestors(p: String): List[String] =
    Iterator.iterate(parentOf(p))(_.flatMap(parentOf)).takeWhile(_.isDefined).flatten.toList

  private def hashOf(p: String): String =
    if dirs(p) then FileSnapshotResponse.Kind.Directory
    else
      files
        .get(p)
        .fold(FileSnapshotResponse.Kind.Absent)(b =>
          if b.length.toLong > maxFileSize then FileSnapshotResponse.Kind.Oversized else FileSnapshotResponse.hashOf(b)
        )

  private def checkExpected(p: String, expected: Option[String]): Unit =
    expected.foreach { e =>
      val current = hashOf(p)
      if current == FileSnapshotResponse.Kind.Oversized || e == FileSnapshotResponse.Kind.Oversized then
        fail("SIZE_LIMIT_EXCEEDED", s"Path '$p' is too large to compare")
      if current != e then fail("HASH_MISMATCH", s"Path '$p' changed: it is now $current, not $e")
    }

  /** Writes `text` at `path` as another writer would, creating its parents. */
  def seed(path: String, text: String): Unit = synchronized {
    val p = norm(path)
    ancestors(p).foreach(dirs += _)
    files(p) = text.getBytes(StandardCharsets.UTF_8)
  }

  /** Writes raw bytes at `path`, as a command might. */
  def seedBytes(path: String, bytes: Array[Byte]): Unit = synchronized {
    val p = norm(path)
    ancestors(p).foreach(dirs += _)
    files(p) = bytes
  }

  def mkdir(path: String): Unit = synchronized {
    val p = norm(path)
    (p :: ancestors(p)).foreach(dirs += _)
  }

  /** The text at `path`, or "" if nothing is there. */
  def text(path: String): String = synchronized(
    files.get(norm(path)).fold("")(b => new String(b, StandardCharsets.UTF_8))
  )

  def exists(path: String): Boolean = synchronized(files.contains(norm(path)) || dirs(norm(path)))

  def exploreFiles(
    path: String,
    recursive: Option[Boolean],
    excludePatterns: Option[List[String]],
    maxDepth: Option[Int],
    returnMetadata: Option[Boolean]
  ): ExploreFilesResponse = synchronized {
    val p = norm(path)
    if files.contains(p) then fail("NOT_A_DIRECTORY", s"Path '$path' is not a directory")
    if !dirs(p) then fail("PATH_NOT_FOUND", s"Path '$path' does not exist")
    val depth                  = if recursive.getOrElse(false) then maxDepth.getOrElse(3) else 1
    def depthOf(child: String) = (if p.isEmpty then child else child.drop(p.length + 1)).count(_ == '/') + 1
    def within(child: String)  = child.nonEmpty && (p.isEmpty || child.startsWith(p + "/")) && depthOf(child) <= depth
    val all =
      (dirs.toVector.filter(within).map(_ -> true) ++ (files.keys ++ specials).toVector.filter(within).map(_ -> false))
        .sortBy(_._1)
    val entries = all.take(maxEntries).map { (child, isDir) =>
      val meta = Option.when(returnMetadata.getOrElse(false))(
        FileMetadata(
          child,
          if isDir then 0L else files.get(child).fold(0L)(_.length.toLong),
          isDir,
          "2026-10-15T00:00:00Z"
        )
      )
      FileEntry(child, isDir, meta)
    }
    ExploreFilesResponse("local", entries.toList, all.size > maxEntries, all.size)
  }

  def readFile(path: String, startLine: Option[Int], endLine: Option[Int]): ReadFileResponse =
    throw new UnsupportedOperationException("the backends read through fileSnapshot")

  def writeFile(
    path: String,
    content: String,
    mode: Option[String],
    createDirectories: Option[Boolean],
    expectedHash: Option[String]
  ): WriteFileResponse = synchronized {
    val p = norm(path)
    if expectedHash.isDefined && mode.exists(_ != "overwrite") then
      fail("INVALID_ARGUMENT", "expectedHash needs overwrite")
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    if bytes.length.toLong > maxFileSize then fail("SIZE_LIMIT_EXCEEDED", s"Content for '$path' is too large")
    checkExpected(p, expectedHash)
    if dirs(p) then fail("NOT_A_FILE", s"Path '$path' is a directory, not a file")
    val parents = ancestors(p)
    if parents.exists(files.contains) then fail("PARENT_NOT_A_DIRECTORY", s"Cannot create the parents of '$path'")
    if createDirectories.getOrElse(false) then parents.foreach(dirs += _)
    else if !parents.headOption.forall(dirs) then fail("DIRECTORY_NOT_FOUND", s"Parent of '$path' does not exist")
    files(p) = bytes
    if expectedHash.isDefined then applied += ("write" -> p)
    WriteFileResponse("local", success = true, path, bytes.length.toLong)
  }

  def fileSnapshot(path: String, includeContent: Boolean, followLinks: Boolean): FileSnapshotResponse = synchronized {
    import FileSnapshotResponse.Kind
    val p = norm(path)
    if busy then fail("WORKSPACE_BUSY", "A command is in progress in the workspace")
    if specials(p) then fail("NOT_A_FILE", s"Path '$path' is not a regular file")
    if dirs(p) then FileSnapshotResponse("local", path, Kind.Directory, Kind.Directory, 0L)
    else
      files.get(p) match
        case None => FileSnapshotResponse("local", path, Kind.Absent, Kind.Absent, 0L)
        case Some(bytes) =>
          val decoded =
            if !includeContent then Right(None)
            else if bytes.length.toLong > maxFileSize then Left("the file is larger than the limit")
            else
              scala.util
                .Try(
                  StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString
                )
                .toEither
                .left
                .map(_ => "the file is not valid UTF-8 text")
                .map(Some(_))
          FileSnapshotResponse(
            "local",
            path,
            Kind.File,
            hashOf(p),
            bytes.length.toLong,
            decoded.toOption.flatten,
            decoded.left.toOption
          )
  }

  def deleteFile(path: String, expectedHash: Option[String]): DeleteFileResponse = synchronized {
    val p = norm(path)
    if p.isEmpty then fail("INVALID_ARGUMENT", "The workspace root cannot be deleted")
    checkExpected(p, expectedHash)
    if files.remove(p).isDefined then
      if expectedHash.isDefined then applied += ("delete" -> p)
      DeleteFileResponse("local", path, wasDirectory = false)
    else if dirs(p) then
      if dirs.exists(_.startsWith(p + "/")) || files.keys.exists(_.startsWith(p + "/")) then
        fail("DIRECTORY_NOT_EMPTY", s"Directory '$path' is not empty")
      dirs -= p
      if expectedHash.isDefined then applied += ("delete" -> p)
      DeleteFileResponse("local", path, wasDirectory = true)
    else fail("FILE_NOT_FOUND", s"File '$path' does not exist")
  }

  def modifyFile(path: String, operations: List[FileOperation]): ModifyFileResponse =
    throw new UnsupportedOperationException("the backends do not modify by lines")

  def searchFiles(
    paths: List[String],
    query: String,
    searchType: String,
    recursive: Option[Boolean],
    excludePatterns: Option[List[String]],
    contextLines: Option[Int]
  ): SearchFilesResponse = throw new UnsupportedOperationException("the backends search through fileSnapshot")

  def executeCommand(
    command: String,
    workingDirectory: Option[String],
    timeout: Option[FiniteDuration],
    environment: Option[Map[String, String]],
    maxOutputChars: Option[Long]
  ): ExecuteCommandResponse = exec(command, workingDirectory, timeout, maxOutputChars)

  def getWorkspaceInfo(): GetWorkspaceInfoResponse =
    GetWorkspaceInfoResponse("local", "/workspace", Nil, WorkspaceLimits(maxFileSize, maxEntries, 100, 1024L * 1024L))
