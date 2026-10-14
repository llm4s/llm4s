package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ GraphError, ReplayGuard, ReplaySupport, StateCheck, StateKey, StateUpdate }
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
 * '''Not a security boundary.''' It is a convenience for a trusted directory. Never give it a root that
 * an untrusted process can write, a sandboxed command included: such a process can swap a directory for
 * a link between the backend's check and its open, and so reach files outside the root. To run untrusted
 * commands, use the workspace sandbox and its file backend (F2), which is the isolation boundary.
 *
 *  - '''Resolution.''' A path's segments resolve under the real root, and every component that exists
 *    is checked: a symbolic link is refused, wherever it points. A link inside the root would make two
 *    paths name one file, and their locks would not order them. `find` and `search` skip links.
 *  - '''Atomic.''' A write or edit writes a temporary file beside the target, flushes it and moves it
 *    over the target atomically, so a crash leaves the target at its digest before or after, never torn
 *    (a torn file would match neither, and its replay would be refused for ever). `delete` removes a file
 *    or an empty directory, each atomic; a non-empty directory is refused.
 *  - '''Order.''' A mutation locks `host/<root>/<path>`, so two mutations of one file in a batch, or a
 *    directory's and a file's beneath it, apply in call order. Across threads and processes the effect is
 *    a compare-and-set under the file's JVM and OS locks: it writes only if the file is still as planned,
 *    and is refused (`EffectReplayRefused`) otherwise, so no edit is lost.
 *  - '''Bounds.''' A file over `maxFileBytes` cannot be read, written or edited, and `search` skips it.
 *    `ls`, `find` and `search` visit at most `maxScanEntries` entries, in sorted order, and report a
 *    walk they cut short as `truncated`.
 *  - '''Tenant.''' Not scoped: one root is one tenant's. Build one per tenant with `FileBackend.resolved`.
 *  - '''Snapshot.''' Reads see the file as it is now, not as the batch's thread state.
 */
@Experimental
final class HostFileBackend private (
  val root: Path,
  val maxFileBytes: Long,
  val maxScanEntries: Int,
  val lockDirectory: Path
) extends FileBackend:

  /** C4's class for this target. */
  val replaySupport: ReplaySupport = ReplaySupport.Verifiable

  val writes: Set[StateKey[?, ?]] = Set.empty

  private val rootLock = LockKey("host", HostFileBackend.identity(root.toString.replace('\\', '/')))

  /**
   * Locks name a file by its case- and normalisation-folded path ([[HostFileBackend.identity]]), so
   * `/A` and `/a`, which one file on a case-insensitive file system, conflict. On a case-sensitive one
   * they only conflict needlessly.
   */
  def locks(path: VPath): Set[LockKey] = Set(rootLock / HostFileBackend.identity(path.value))

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
   * lock directory, should it lie inside the root.
   */
  private def resolve(operation: String, path: VPath): Result[Path] =
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
        else if inLockDirectory(p) then refused(operation, path, "is the backend's lock directory")
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
    else
      bounded(p)
        .toRight(())
        .left
        .flatMap(_ => refused(operation, path, s"is larger than the limit of $maxFileBytes bytes"))

  /**
   * At most `maxFileBytes` of `p`, read through one handle that does not follow a final link, or `None`
   * when there are more: the bound holds for what is read, not for a size checked before, so a file that
   * grows or is replaced meanwhile cannot make a read larger.
   */
  private def bounded(p: Path): Option[Array[Byte]] =
    Using.resource(FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel =>
      val out    = new java.io.ByteArrayOutputStream()
      val buffer = ByteBuffer.allocate(64 * 1024)
      var total  = 0L
      var more   = true
      while more && total <= maxFileBytes do
        buffer.clear()
        val n = channel.read(buffer)
        if n < 0 then more = false
        else
          total += n
          if total <= maxFileBytes then out.write(buffer.array(), 0, n)
      Option.when(total <= maxFileBytes)(out.toByteArray)
    }

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
  /**
   * The digest of what is at `p` now: a file's tagged bytes, a directory, or nothing. A file over
   * `maxFileBytes` is digested streaming, as `F` and its SHA-256, so it can still be replaced or deleted
   * though it cannot be read; the leading byte (`f` or `F`) keeps the two forms apart.
   */
  private def digest(operation: String, path: VPath, p: Path): Result[StateCheck] =
    if !exists(p) then Right(StateCheck.absent)
    else if isDir(p) then Right(directoryCheck)
    else if !isFile(p) then refused(operation, path, "is not a regular file")
    else
      bounded(p) match
        case Some(bytes) => Right(fileCheck(bytes))
        case None        => Right(StateCheck.of("F" + streamingSha256(p)))

  private def streamingSha256(p: Path): String =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    Using.resource(FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel =>
      val buffer = ByteBuffer.allocate(64 * 1024)
      while channel.read(buffer) >= 0 do
        buffer.flip()
        digest.update(buffer)
        buffer.clear()
    }
    digest.digest().map(b => f"${b & 0xff}%02x").mkString

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
   * on that stripe's file in the lock directory, so every process mutating the same or an overlapping
   * root through a backend is ordered too. One lock file per stripe, used by one thread of this JVM at a
   * time, because closing a channel can release every lock the JVM holds on its file, and an interrupt
   * while waiting closes the waiting channel.
   */
  // by the folded path, so two names of one file on a case-insensitive file system share a stripe
  private def stripeOf(target: Path): Int =
    Math.floorMod(HostFileBackend.identity(target.toString).hashCode, HostFileBackend.Stripes)

  /**
   * The OS lock file of a stripe: in the lock directory, shared by every backend that uses it whatever
   * its root, so backends in other processes over the same or overlapping roots - `/w` and `/w/sub` -
   * lock one file for one target.
   */
  private def lockFile(stripe: Int): Path = lockDirectory.resolve(s"stripe-$stripe.lock")

  private def inLockDirectory(p: Path): Boolean = p.normalize().startsWith(lockDirectory)

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
    // No lock is held across the guard: its intent and outcome are store commits, and a run stalled
    // between them (one that has lost its claim) must not block its successor. The effect alone is
    // locked, as a compare-and-set (casPlan), so a file another writer changed since the plan is refused,
    // never overwritten.
    resolve(operation, path)
      .flatMap { target =>
        ReplayGuard.verifiable[A](ctx.run, ctx.effectKey(s"file.$operation", path.value))(
          io(operation, path)(digest(operation, path, target))
        )(io(operation, path)(plan(target)))
      }
      .map(a => (a, StateUpdate.empty))

  // ---- mutations

  /**
   * A plan whose effect is a compare-and-set against `before` (C4): under the file's JVM and OS locks,
   * which every backend writer takes, it resolves the path again (so a link swapped in since is refused),
   * and writes only if the file's digest is still `before`; otherwise it writes nothing and is refused
   * (`EffectReplayRefused`), and the model sees that the file changed.
   */
  private def casPlan[A](operation: String, path: VPath, target: Path)(
    before: StateCheck,
    after: StateCheck,
    presumed: A
  )(
    effect: => Result[A]
  ): ReplayGuard.Plan[A] =
    ReplayGuard.Plan(before, after, presumed)(
      exclusively(target) {
        resolve(operation, path)
          .flatMap(again => io(operation, path)(digest(operation, path, again)))
          .flatMap { now =>
            if now == before then effect
            else
              Left(
                GraphError.EffectReplayRefused(
                  s"file.$operation",
                  s"${path.value} changed after it was planned: it is now ${now.value}, not ${before.value}"
                )
              )
          }
      }
    )

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
            yield casPlan("write", path, target)(
              before,
              fileCheck(bytes),
              FileWritten(before == StateCheck.absent, bytes.length.toLong) // from the one read, as the effect reports
            )(
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
      yield casPlan("edit", path, target)(fileCheck(bytes), fileCheck(next), FileEdited(edited._2))(
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
            casPlan("delete", path, target)(directoryCheck, StateCheck.absent, FileDeleted(wasDirectory = true))(
              Try(Files.delete(target)).toResult.map(_ => FileDeleted(wasDirectory = true))
            )
          )
      else
        digest("delete", path, target).map(before =>
          casPlan("delete", path, target)(before, StateCheck.absent, FileDeleted(wasDirectory = false))(
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
    val least = new java.util.PriorityQueue[(String, Path)](
      math.min(max, 1024) + 1,
      Ordering.by[(String, Path), String](_._1).reverse
    )
    var eligible = 0
    Using.resource(Files.newDirectoryStream(dir)) { stream =>
      val entries = stream.iterator()
      while entries.hasNext do
        if Thread.currentThread().isInterrupted then throw new java.util.concurrent.CancellationException("interrupted")
        val p    = entries.next()
        val name = p.getFileName.toString
        val hidden =
          HostFileBackend.isTemporary(name) || inLockDirectory(p) || aliased(p)
        if !hidden then
          eligible += 1
          least.add(name -> p)
          if least.size > max then least.poll(): Unit
    }
    (least.asScala.toVector.sortBy(_._1), eligible > max)

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    Text.checkLimit("ls", path.value, limit).flatMap(_ => resolve("ls", path)).flatMap { dir =>
      io("ls", path)(Text.interruptibly("ls") {
        if !exists(dir) then Left(BackendRefused.notFound("ls", path))
        else if !isDir(dir) then Left(BackendRefused.notADirectory("ls", path))
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
    yield FileLines(Text.page(lines, offset, limit), offset, lines.size)

  /** The files within `under`, depth first in name order, stopping after `maxScanEntries` entries. */
  final private class Walk(start: Path, from: VPath):
    private var visited = 0L
    private var cut     = false
    private def full    = visited > maxScanEntries

    /** Whether the walk stopped before seeing every entry. */
    def capped: Boolean = full || cut
    def files: Iterator[(VPath, Path)] =
      if isFile(start) then Iterator(from -> start) else if isDir(start) then within(start, from) else Iterator.empty
    private def within(dir: Path, at: VPath): Iterator[(VPath, Path)] =
      if full then Iterator.empty
      else
        val (entries, more) = children(dir, math.min(maxScanEntries.toLong - visited + 1, Int.MaxValue.toLong).toInt)
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
    walked("grep", under, limit)(_.filter((v, _) => accept(v)).flatMap { (v, p) =>
      // a file over the bound, or not UTF-8 text, is skipped; the bound is on what is read
      Try(bounded(p).map(text)).toOption.flatten.iterator.flatMap(content => Text.matches(v, content, regex))
    })

object HostFileBackend:

  /** 4 MiB: the default bound on one file. */
  val DefaultMaxFileBytes: Long = 4L * 1024 * 1024

  /** The default bound on the entries one listing or walk visits. */
  val DefaultMaxScanEntries: Int = 100_000

  /** Per-file mutual exclusion across the JVM, striped by the file's real path. */
  private val Stripes = 64

  private val stripes: Vector[ReentrantLock] = Vector.fill(Stripes)(new ReentrantLock())

  /**
   * Where backends keep their OS lock files by default: `llm4s-host-locks` in `java.io.tmpdir`, one
   * place for every root, so processes of one user on one host share it. Processes that do not share a
   * temporary directory (other users, containers) must be given one `lockDirectory` they all share.
   */
  def DefaultLockDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"), "llm4s-host-locks")

  /**
   * A path as the most permissive file system would compare it: Unicode NFC, then case-folded in the
   * root locale. Two names a case-insensitive or normalising file system (Windows, macOS) takes for one
   * file fold alike, so locks and stripes treat them as one.
   */
  private[harness] def identity(path: String): String =
    java.text.Normalizer
      .normalize(path, java.text.Normalizer.Form.NFC)
      .toUpperCase(java.util.Locale.ROOT)
      .toLowerCase(java.util.Locale.ROOT)

  final private case class Unlock(lock: ReentrantLock) extends AutoCloseable:
    def close(): Unit = lock.unlock()

  private val Temporary = """\..+\.llm4s-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp""".r

  private def isTemporary(name: String): Boolean = Temporary.matches(name)

  /** A backend over the existing directory `root`, resolved to its real path. */
  def open(
    root: Path,
    maxFileBytes: Long = DefaultMaxFileBytes,
    maxScanEntries: Int = DefaultMaxScanEntries,
    lockDirectory: Path = DefaultLockDirectory
  ): Result[HostFileBackend] =
    if maxFileBytes <= 0 then Left(ValidationError("maxFileBytes", s"must be positive, was $maxFileBytes"))
    else if maxScanEntries <= 0 then Left(ValidationError("maxScanEntries", s"must be positive, was $maxScanEntries"))
    else
      for
        real <- Try(root.toRealPath()).toResult.left.map(e => ValidationError("root", s"$root: ${e.message}"): LLMError)
        _    <- Either.cond(Files.isDirectory(real), (), ValidationError("root", s"$root is not a directory"))
        locks <- Try {
          Files.createDirectories(lockDirectory)
          lockDirectory.toRealPath()
        }.toResult.left.map(e => ValidationError("lockDirectory", s"$lockDirectory: ${e.message}"): LLMError)
      yield new HostFileBackend(real, maxFileBytes, maxScanEntries, locks)
