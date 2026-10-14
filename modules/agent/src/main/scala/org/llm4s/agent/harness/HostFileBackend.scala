package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ ReplayGuard, ReplaySupport, StateCheck, StateKey, StateUpdate }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError, ProcessingError, ValidationError }
import org.llm4s.types.{ Result, TryOps }

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.{ CharacterCodingException, CodingErrorAction, StandardCharsets }
import java.nio.file.{
  AccessDeniedException,
  FileSystemException,
  Files,
  LinkOption,
  NoSuchFileException,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Success, Try, Using }

/**
 * Files in a directory of the host, opt-in: the agent sees `root` as `/`. Its mutations are external
 * effects, and its target is `Verifiable` (C4): each runs through `ReplayGuard.verifiable` under
 * `ctx.effectKey("file.write" | "file.edit" | "file.delete", path)`, with the content digests before and
 * after, so a replayed edit whose first attempt landed returns its first result rather than "`old`
 * was not found", and one whose file another writer changed in between is refused.
 *
 *  - '''Resolution.''' A path's segments resolve under the real root, and every component that exists
 *    is checked: a symbolic link is refused, wherever it points. A link inside the root would make two
 *    paths name one file, and their locks would not order them. `find` and `search` skip links.
 *  - '''Atomic.''' A write or edit writes a temporary file beside the target, flushes it and moves it
 *    over the target atomically, so a crash leaves the target at its digest before or after, never torn
 *    (a torn file would match neither, and its replay would be refused for ever). `delete` removes a file
 *    or an empty directory, each atomic; a non-empty directory is refused.
 *  - '''Order.''' A mutation locks `host/<root>/<path>`, so two mutations of one file in a batch, or a
 *    directory's and a file's beneath it, apply in call order. Calls of different threads in one JVM are
 *    serialised per file around plan and effect, so neither loses the other's edit. Other processes are
 *    not ordered: give each its own root.
 *  - '''Bounds.''' A file over `maxFileBytes` cannot be read, written or edited, and `search` skips it.
 *    `ls`, `find` and `search` visit at most `maxScanEntries` entries, in sorted order, and report a
 *    walk they cut short as `truncated`.
 *  - '''Tenant.''' Not scoped: one root is one tenant's. Build one per tenant with `FileBackend.resolved`.
 *  - '''Snapshot.''' Reads see the file as it is now, not as the batch's thread state.
 */
@Experimental
final class HostFileBackend private (val root: Path, val maxFileBytes: Long, val maxScanEntries: Int)
    extends FileBackend:

  /** C4's class for this target. */
  val replaySupport: ReplaySupport = ReplaySupport.Verifiable

  val writes: Set[StateKey[?, ?]] = Set.empty

  private val rootLock = LockKey("host", root.toString.replace('\\', '/'))

  def locks(path: VPath): Set[LockKey] = Set(rootLock / path.value)

  // ---- resolution

  private def refused(operation: String, path: VPath, reason: String): Left[BackendRefused, Nothing] =
    Left(BackendRefused(operation, path.value, reason))

  private val countsLinks = root.getFileSystem.supportedFileAttributeViews.contains("unix")

  /** How many hard links name the regular file `p`; 1 where the file system cannot say. */
  private def linkCount(p: Path): Int =
    if countsLinks then Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS).asInstanceOf[Int] else 1

  /** A file another name also reaches: a symbolic link, or a regular file with more than one hard link. */
  private def aliased(p: Path): Boolean = Files.isSymbolicLink(p) || (isFile(p) && linkCount(p) > 1)

  /**
   * `path` under the root, refusing a symbolic link at any component that exists, a regular file with
   * other hard links (which may be outside the root, and which locks by path would not order), and the
   * backend's own lock directory.
   */
  private def resolve(operation: String, path: VPath): Result[Path] =
    if path.segments.headOption.contains(HostFileBackend.Reserved) then
      refused(operation, path, s"is beneath /${HostFileBackend.Reserved}, which the backend reserves for its locks")
    else
      path.segments
        .foldLeft[Result[Path]](Right(root)) { (acc, segment) =>
          acc.flatMap { parent =>
            val next = parent.resolve(segment)
            if Files.isSymbolicLink(next) then
              refused(operation, path, "is or is beneath a symbolic link, which is refused")
            else Right(next)
          }
        }
        .flatMap(p =>
          if !p.normalize().startsWith(root) then refused(operation, path, "resolves outside the root")
          else if isFile(p) && linkCount(p) > 1 then refused(operation, path, "has other hard links, which are refused")
          else Right(p)
        )

  /** Every existing ancestor of `target` below the root is a directory. */
  private def underDirectories(operation: String, path: VPath, target: Path): Result[Unit] =
    Iterator
      .iterate(target.getParent)(_.getParent)
      .takeWhile(p => p != null && p.startsWith(root) && p != root)
      .find(p => Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
      .fold[Result[Unit]](Right(()))(_ => refused(operation, path, "is beneath a file"))

  private def isDir(p: Path)  = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
  private def isFile(p: Path) = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
  private def exists(p: Path) = Files.exists(p, LinkOption.NOFOLLOW_LINKS)

  /** An I/O step that has changed nothing: a file-system refusal is the model's; anything else fails. */
  private def io[A](operation: String, path: VPath)(body: => Result[A]): Result[A] =
    Try(body) match
      case Success(result)                      => result
      case Failure(_: NoSuchFileException)      => refused(operation, path, "not found")
      case Failure(_: AccessDeniedException)    => refused(operation, path, "permission denied")
      case Failure(e: FileSystemException)      => refused(operation, path, Option(e.getReason).getOrElse(e.toString))
      case Failure(_: CharacterCodingException) => refused(operation, path, "is not UTF-8 text")
      case failure                              => failure.toResult.flatten

  // ---- content

  private def fileBytes(operation: String, path: VPath, p: Path): Result[Array[Byte]] =
    if !exists(p) then refused(operation, path, "not found")
    else if isDir(p) then refused(operation, path, "is a directory")
    else if !isFile(p) then refused(operation, path, "is not a regular file")
    else if Files.size(p) > maxFileBytes then
      refused(operation, path, s"is larger than the limit of $maxFileBytes bytes")
    else Right(Files.readAllBytes(p))

  private def text(bytes: Array[Byte]): String =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString

  private def fileCheck(bytes: Array[Byte]): StateCheck = StateCheck.of('f'.toByte +: bytes)
  private val directoryCheck: StateCheck                = StateCheck.of("d")

  /** The digest of what is at `p` now: a file's tagged bytes, a directory, or nothing. */
  private def digest(operation: String, path: VPath, p: Path): Result[StateCheck] =
    if !exists(p) then Right(StateCheck.absent)
    else if isDir(p) then Right(directoryCheck)
    else fileBytes(operation, path, p).map(fileCheck)

  /** Writes `bytes` to `target` atomically: a flushed temporary file beside it, moved over it. */
  private def atomicWrite(target: Path, bytes: Array[Byte]): Result[Unit] =
    Try {
      Files.createDirectories(target.getParent)
      val temp = target.resolveSibling(s".${target.getFileName}.llm4s-${UUID.randomUUID()}.tmp")
      val written = Using(FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { channel =>
        val buffer = ByteBuffer.wrap(bytes)
        while buffer.hasRemaining do channel.write(buffer)
        channel.force(true)
      }.flatMap(_ => Try(Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)))
      written.recoverWith { case e => Try(Files.deleteIfExists(temp)).flatMap(_ => Failure(e)) }.get
    }.toResult.map(_ => ())

  // ---- serialisation of plan and effect across threads of this JVM

  /**
   * `body` holding the target's stripe: first the JVM's lock of that stripe, then the OS's advisory lock
   * on the root's lock file of that stripe (`.llm4s/locks/<stripe>`), so every process mutating this
   * root through a backend is ordered too. One lock file per stripe, used by one thread of this JVM at a
   * time, because closing a channel can release every lock the JVM holds on its file, and an interrupt
   * while waiting closes the waiting channel.
   */
  private def stripeOf(target: Path): Int = Math.floorMod(target.toString.hashCode, HostFileBackend.Stripes)

  private def lockFile(stripe: Int): Path =
    root.resolve(HostFileBackend.Reserved).resolve("locks").resolve(stripe.toString)

  /** The OS lock file a mutation of `path` takes; for tests that hold it from another process. */
  private[harness] def lockFileOf(path: VPath): Result[Path] = resolve("lock", path).map(t => lockFile(stripeOf(t)))

  private def exclusively[A](target: Path)(body: => Result[A]): Result[A] =
    val stripe = stripeOf(target)
    val lock   = HostFileBackend.stripes(stripe)
    def cancelled: Result[A] =
      Thread.currentThread().interrupt()
      Left(CancelledError("host file mutation"))
    Try(lock.lockInterruptibly()) match
      case Failure(_) => cancelled
      case Success(_) =>
        Using.resource(HostFileBackend.Unlock(lock)) { _ =>
          val file = lockFile(stripe)
          Try {
            Files.createDirectories(file.getParent)
            FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
          } match
            case Failure(e) => Left(ProcessingError("host-lock", s"cannot open the lock file $file: $e"))
            case Success(channel) =>
              Using.resource(channel) { open =>
                Try(open.lock()) match
                  case Failure(_: java.nio.channels.ClosedByInterruptException) => cancelled
                  case Failure(e)    => Left(ProcessingError("host-lock", s"cannot lock $file: $e"))
                  case Success(held) => Using.resource(held)(_ => body)
              }
        }

  private def guarded[A: upickle.default.ReadWriter](operation: String, path: VPath, ctx: FileContext)(
    plan: Path => Result[ReplayGuard.Plan[A]]
  ): Result[(A, StateUpdate)] =
    // the stripe is the lexical target's, so the resolution's checks (links, the reserved directory) are
    // made under the lock too, not raced between the check and the effect
    exclusively(path.segments.foldLeft(root)(_.resolve(_))) {
      resolve(operation, path).flatMap { target =>
        ReplayGuard.verifiable[A](ctx.run, ctx.effectKey(s"file.$operation", path.value))(
          io(operation, path)(digest(operation, path, target))
        )(io(operation, path)(plan(target)))
      }
    }.map(a => (a, StateUpdate.empty))

  // ---- mutations

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    guarded[FileWritten]("write", path, ctx) { target =>
      val bytes = content.getBytes(StandardCharsets.UTF_8)
      if path == VPath.root then refused("write", path, "is the root directory")
      else
        Text.binaryReason(content) match
          case Some(reason) => refused("write", path, reason)
          case None if bytes.length > maxFileBytes =>
            refused("write", path, s"content is larger than the limit of $maxFileBytes bytes")
          case None if isDir(target) => refused("write", path, "is a directory")
          case None =>
            for
              _      <- underDirectories("write", path, target)
              before <- digest("write", path, target)
            yield ReplayGuard.Plan(before, fileCheck(bytes), FileWritten(!exists(target), bytes.length.toLong))(
              atomicWrite(target, bytes).map(_ => FileWritten(before == StateCheck.absent, bytes.length.toLong))
            )
    }

  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] =
    guarded[FileEdited]("edit", path, ctx) { target =>
      for
        bytes  <- fileBytes("edit", path, target)
        edited <- Text.edited(path, text(bytes), old, replacement, replaceAll)
        next = edited._1.getBytes(StandardCharsets.UTF_8)
        _ <-
          if next.length > maxFileBytes then
            refused("edit", path, s"the result is larger than the limit of $maxFileBytes bytes")
          else Right(())
      yield ReplayGuard.Plan(fileCheck(bytes), fileCheck(next), FileEdited(edited._2))(
        atomicWrite(target, next).map(_ => FileEdited(edited._2))
      )
    }

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    guarded[FileDeleted]("delete", path, ctx) { target =>
      if path == VPath.root then refused("delete", path, "is the root directory")
      else if !exists(target) then refused("delete", path, "not found")
      else if isDir(target) then
        if Using.resource(Files.list(target))(_.findAny().isPresent) then
          refused("delete", path, "is a directory that is not empty; delete the files in it")
        else
          Right(
            ReplayGuard.Plan(directoryCheck, StateCheck.absent, FileDeleted(wasDirectory = true))(
              Try(Files.delete(target)).toResult.map(_ => FileDeleted(wasDirectory = true))
            )
          )
      else
        digest("delete", path, target).map(before =>
          ReplayGuard.Plan(before, StateCheck.absent, FileDeleted(wasDirectory = false))(
            Try(Files.delete(target)).toResult.map(_ => FileDeleted(wasDirectory = false))
          )
        )
    }

  // ---- reads

  /** The listable children of `dir`, in name order: no links, no temporary files, at most `max`. */
  /**
   * The first `max` listable children of `dir` in name order, and whether there were more. A directory
   * stream has no order, so the whole directory is read, keeping only the `max + 1` least names, and
   * entries that are not listed (links, temporary files, the lock directory) are dropped before they
   * count. Memory is bounded by `max`; the time a huge directory takes, by the tool's timeout, as the
   * read stops when its thread is interrupted.
   */
  private def children(dir: Path, max: Int): (Vector[(String, Path)], Boolean) =
    val least = new java.util.PriorityQueue[(String, Path)](max + 1, Ordering.by[(String, Path), String](_._1).reverse)
    var eligible = 0
    Using.resource(Files.newDirectoryStream(dir)) { stream =>
      val entries = stream.iterator()
      while entries.hasNext do
        if Thread.currentThread().isInterrupted then throw new java.util.concurrent.CancellationException("interrupted")
        val p    = entries.next()
        val name = p.getFileName.toString
        val hidden =
          HostFileBackend.isTemporary(name) || (dir == root && name == HostFileBackend.Reserved) || aliased(p)
        if !hidden then
          eligible += 1
          least.add(name -> p)
          if least.size > max then least.poll(): Unit
    }
    (least.asScala.toVector.sortBy(_._1), eligible > max)

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    Text.checkLimit("ls", path.value, limit).flatMap(_ => resolve("ls", path)).flatMap { dir =>
      io("ls", path)(Text.interruptibly("ls") {
        if !exists(dir) then refused("ls", path, "not found")
        else if !isDir(dir) then refused("ls", path, "is a file, not a directory")
        else
          val (entries, capped) = children(dir, math.min(limit, maxScanEntries))
          val infos = entries.flatMap { (name, p) =>
            (path / name).toOption.map { child =>
              val directory = isDir(p)
              FileInfo(
                child,
                directory,
                if directory then 0L else Files.size(p),
                Some(Files.getLastModifiedTime(p).toInstant)
              )
            }
          }
          val listing = Listing.of(infos.iterator, limit)
          Right(listing.copy(truncated = listing.truncated || capped))
      })
    }

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    for
      _      <- Text.checkLimit("read", path.value, limit)
      _      <- Either.cond(offset >= 0, (), BackendRefused("read", path.value, s"offset must be >= 0, was $offset"))
      target <- resolve("read", path)
      lines  <- io("read", path)(fileBytes("read", path, target).map(b => Text.lines(text(b))))
    yield FileLines(lines.slice(offset, offset + limit), offset, lines.size)

  /** The files within `under`, depth first in name order, stopping after `maxScanEntries` entries. */
  final private class Walk(start: Path, from: VPath):
    private var visited = 0
    private var cut     = false
    private def full    = visited > maxScanEntries

    /** Whether the walk stopped before seeing every entry. */
    def capped: Boolean = full || cut
    def files: Iterator[(VPath, Path)] =
      if isFile(start) then Iterator(from -> start) else if isDir(start) then within(start, from) else Iterator.empty
    private def within(dir: Path, at: VPath): Iterator[(VPath, Path)] =
      if full then Iterator.empty
      else
        val (entries, more) = children(dir, maxScanEntries - visited + 1)
        if more then cut = true
        entries.iterator.flatMap { (name, p) =>
          visited += 1
          if full then Iterator.empty
          else
            (at / name).toOption match
              case None                 => Iterator.empty
              case Some(v) if isDir(p)  => within(p, v)
              case Some(v) if isFile(p) => Iterator(v -> p)
              case _                    => Iterator.empty
        }

  private def walked[A](operation: String, under: VPath, limit: Int)(
    select: Iterator[(VPath, Path)] => Iterator[A]
  ): Result[Listing[A]] =
    Text.checkLimit(operation, under.value, limit).flatMap(_ => resolve(operation, under)).flatMap { start =>
      io(operation, under) {
        Text.interruptibly(operation) {
          val walk    = new Walk(start, under)
          val listing = Listing.of(select(walk.files), limit)
          Right(listing.copy(truncated = listing.truncated || walk.capped))
        }
      }
    }

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    walked("glob", under, limit)(_.collect { case (v, _) if accept(v) => v })

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    walked("grep", under, limit)(_.filter((v, p) => accept(v) && Files.size(p) <= maxFileBytes).flatMap { (v, p) =>
      Try(text(Files.readAllBytes(p))).toOption.iterator.flatMap(content => Text.matches(v, content, regex))
    })

object HostFileBackend:

  /** 4 MiB: the default bound on one file. */
  val DefaultMaxFileBytes: Long = 4L * 1024 * 1024

  /** The default bound on the entries one listing or walk visits. */
  val DefaultMaxScanEntries: Int = 100_000

  /** Per-file mutual exclusion across the JVM, striped by the file's real path. */
  private val Stripes = 64

  private val stripes: Vector[ReentrantLock] = Vector.fill(Stripes)(new ReentrantLock())

  /** The root's directory the backend keeps its lock files in; no path may reach it. */
  private val Reserved = ".llm4s"

  final private case class Unlock(lock: ReentrantLock) extends AutoCloseable:
    def close(): Unit = lock.unlock()

  private val Temporary = """\..+\.llm4s-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp""".r

  private def isTemporary(name: String): Boolean = Temporary.matches(name)

  /** A backend over the existing directory `root`, resolved to its real path. */
  def open(
    root: Path,
    maxFileBytes: Long = DefaultMaxFileBytes,
    maxScanEntries: Int = DefaultMaxScanEntries
  ): Result[HostFileBackend] =
    if maxFileBytes <= 0 then Left(ValidationError("maxFileBytes", s"must be positive, was $maxFileBytes"))
    else if maxScanEntries <= 0 then Left(ValidationError("maxScanEntries", s"must be positive, was $maxScanEntries"))
    else
      Try(root.toRealPath()).toResult.left
        .map(e => ValidationError("root", s"$root: ${e.message}"): LLMError)
        .flatMap(real =>
          if Files.isDirectory(real) then Right(new HostFileBackend(real, maxFileBytes, maxScanEntries))
          else Left(ValidationError("root", s"$root is not a directory"))
        )
