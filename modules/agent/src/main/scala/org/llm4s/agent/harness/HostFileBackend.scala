package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ GraphError, ReplayGuard, ReplaySupport, StateCheck, StateKey, StateUpdate }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ CancelledError, LLMError, NonRecoverableError, ProcessingError, ValidationError }
import org.llm4s.types.{ Result, TryOps }

import java.nio.ByteBuffer
import java.nio.channels.{ FileChannel, FileLock }
import java.nio.charset.{ CharacterCodingException, CodingErrorAction, StandardCharsets }
import java.nio.file.{
  AccessDeniedException,
  FileAlreadyExistsException,
  FileSystemException,
  Files,
  LinkOption,
  NoSuchFileException,
  Path,
  StandardCopyOption,
  StandardOpenOption
}
import java.nio.file.attribute.{
  AclEntry,
  AclFileAttributeView,
  BasicFileAttributes,
  DosFileAttributeView,
  FileAttribute,
  GroupPrincipal,
  PosixFileAttributeView,
  PosixFileAttributes,
  PosixFilePermission,
  PosixFilePermissions,
  UserPrincipal
}
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.{ Failure, Success, Try, Using }

/**
 * Files in a directory of the host, opt-in: the agent sees `root` as `/` (design §4.40).
 *
 * '''One writer per root.''' [[HostFileBackend.open]] takes an exclusive OS lock on the root, held until
 * [[close]]: a second open of the same root, or of one containing it or inside it, from this JVM or another
 * process, is refused with [[HostRootInUse]]. The lock excludes other backends that share the lock
 * directory, not an editor or a build writing the directory; a replay still sees such a change, by its
 * digests.
 *
 * '''One path, one file.''' A path the model sees is the only way to reach its file: symbolic links, files
 * with other hard links, other file systems mounted inside the root, names ending in a dot or a space and the
 * backend's own temporary names are refused and never listed. `open` refuses a root whose file system makes
 * 8.3 short names, and one it cannot check (no hard-link counts, as on NTFS, or a root it cannot probe)
 * unless `allowUncheckedIdentity`.
 *
 * '''Not a security boundary.''' It is a convenience for a trusted directory. Never give it a root an
 * untrusted process can write, a sandboxed command included: such a process can swap a directory for a link
 * between the backend's check and its open. Untrusted commands belong in the workspace sandbox (F2).
 *
 *  - '''Replay.''' Its target is `Verifiable` (C4): each mutation runs through `ReplayGuard.verifiable`
 *    under `ctx.effectKey("file.write" | "file.edit" | "file.delete", path)`, and its effect is a
 *    compare-and-set under the backend's own effect lock, so a run that lost its claim cannot overwrite its
 *    successor's change.
 *  - '''Atomic.''' A write or edit moves a flushed temporary file over its target, keeping the target's
 *    POSIX permissions, owner, group, ACL and DOS flags, or is refused where it cannot keep them.
 *  - '''Order.''' Every mutation locks `files/host/<root>`: a batch's mutations through one backend apply
 *    one at a time, in call order.
 *  - '''Bounds.''' A file over `maxFileBytes` cannot be read, written or edited, and `search` skips it.
 *    `ls`, `find` and `search` read at most `maxScanEntries` directory entries, never a stream past it, and
 *    report a cut scan as `truncated`: a directory over the bound lists as empty, and a walk stops at the
 *    first directory it cannot read whole, so a cut result is a prefix of the full one.
 *  - '''Tenant.''' Not scoped: one root is one tenant's. Build one per tenant with `FileBackend.resolved`.
 *  - '''Snapshot.''' Reads see the file as it is now, not as the batch's thread state.
 */
@Experimental
final class HostFileBackend private (
  val root: Path,
  val maxFileBytes: Long,
  val maxScanEntries: Int,
  held: HostFileBackend.RootLocks.Held,
  rootStamp: HostFileBackend.Stamp,
  stampOf: Path => HostFileBackend.Stamp,
  fileSystemId: Path => String,
  mountsBeneath: Path => Result[Vector[Path]],
  countsLinks: Boolean,
  openDirectory: Path => java.nio.file.DirectoryStream[Path],
  canonical: Path => Path
) extends FileBackend
    with AutoCloseable:

  // ---- lifecycle: `closed`, `inFlight` and `released` are guarded by `lifecycle`

  private val lifecycle = new Object
  private var closed    = false
  private var inFlight  = 0
  private var released  = false

  /**
   * Closes the backend; idempotent, and never blocks. Every operation that starts after it is refused. One
   * already running finishes as it would have, and the root's lock is released when the last of them ends
   * (at once if none is running), so no other backend can open the root while this one still writes to it.
   * Safe to call from inside one of this backend's operations.
   */
  def close(): Unit =
    val release = lifecycle.synchronized {
      closed = true
      val now = inFlight == 0 && !released
      if now then released = true
      now
    }
    if release then held.release()

  /** An admitted operation's hold on the root, released when it finishes, however it finishes. */
  final private class Lease extends AutoCloseable:
    def close(): Unit =
      val release = lifecycle.synchronized {
        inFlight -= 1
        val now = closed && inFlight == 0 && !released
        if now then released = true
        now
      }
      if release then held.release()

  /** `body`, admitted once at the operation's entry, or refused if the backend is closed. */
  private def whileOpen[A](operation: String, path: VPath)(body: => Result[A]): Result[A] =
    val admitted = lifecycle.synchronized {
      if !closed then inFlight += 1
      !closed
    }
    if !admitted then Left(BackendRefused.at(operation, path, "the backend is closed"))
    else Using.resource(new Lease)(_ => body)

  /** C4's class for this target. */
  val replaySupport: ReplaySupport = ReplaySupport.Verifiable

  val writes: Set[StateKey[?, ?]] = Set.empty

  private val rootLock = FileBackend.LockRoot / "host" / HostFileBackend.digestOf(rootStamp.identity).take(16)

  /**
   * One key for every path: a backend's mutations apply one at a time, so two names a case-insensitive or
   * normalising file system takes for one file are ordered whatever they are.
   */
  def locks(path: VPath): Set[LockKey] = Set(rootLock)

  // ---- resolution

  private def refused(operation: String, path: VPath, reason: String): Left[BackendRefused, Nothing] =
    Left(BackendRefused.at(operation, path, reason))

  /** How many hard links name the regular file `p`; 1 where the file system cannot say. */
  private def linkCount(p: Path): Int =
    if countsLinks then Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS).asInstanceOf[Int] else 1

  /** The root is still the directory `open` checked: same identity, same file system. */
  private def rootIntact(operation: String, path: VPath): Result[Unit] =
    Try(stampOf(root) == rootStamp) match
      case Success(true) => Right(())
      case Success(false) =>
        refused(operation, path, s"the root $root was replaced since it was opened (a mount over it?); reopen it")
      case Failure(e) => refused(operation, path, s"the root $root cannot be checked: $e")

  /** No file system is mounted beneath the root, a bind mount of its own file system included. */
  private def unmounted(operation: String, path: VPath): Result[Unit] =
    mountsBeneath(root).flatMap { found =>
      if found.isEmpty then Right(())
      else refused(operation, path, HostFileBackend.mountedReason(root, found))
    }

  /** Whether `p` exists on a file system other than the root's: a mount inside the root. */
  private def mountedInside(p: Path): Boolean =
    exists(p) && fileSystemId(p) != rootStamp.fileSystem

  /** A file another name also reaches, or one on another file system. */
  private def aliased(p: Path): Boolean =
    Files.isSymbolicLink(p) || (isFile(p) && linkCount(p) > 1) || mountedInside(p)

  /**
   * `path` under the root, checked: the root is intact, nothing is mounted beneath it, and no component
   * that exists is a symbolic link or on another file system; the file has no other hard link; no segment
   * ends in a dot or a space or is named as the backend's temporary files.
   */
  private def resolve(operation: String, path: VPath): Result[Path] =
    rootIntact(operation, path).flatMap(_ => unmounted(operation, path)).flatMap(_ => resolveIn(operation, path))

  private def resolveIn(operation: String, path: VPath): Result[Path] =
    if path.segments.exists(HostFileBackend.trailing) then
      refused(operation, path, "has a segment ending in a dot or a space, which Windows reads as another name")
    else if path.segments.exists(HostFileBackend.isReserved) then
      refused(operation, path, "has a segment named as the backend's temporary files or lock files, which is refused")
    else
      io(operation, path) {
        path.segments
          .foldLeft[Result[Path]](Right(root)) { (acc, segment) =>
            acc.flatMap { parent =>
              val next = parent.resolve(segment)
              if Files.isSymbolicLink(next) then
                refused(operation, path, "is or is beneath a symbolic link, which is refused")
              else if mountedInside(next) then
                refused(
                  operation,
                  path,
                  "is or is beneath another file system mounted inside the root, whose file names are unchecked"
                )
              else Right(next)
            }
          }
          .flatMap(p =>
            if !p.normalize().startsWith(root) then refused(operation, path, "resolves outside the root")
            else if isFile(p) && linkCount(p) > 1 then
              refused(operation, path, "has other hard links, which are refused")
            else ownNames(operation, path, p)
          )
      }

  /**
   * `p`, if every component of it that exists is named by the name the file system itself gives it: the
   * deepest existing prefix's canonical path (one lookup) must spell the same names, compared folded (case
   * and Unicode normalisation are tolerated, as every mutation takes one lock). A component reached by
   * another name - an 8.3 short name, whether the volume makes them now or made it before they were turned
   * off - is refused, so a file has one path whatever names the volume keeps for it.
   */
  private def ownNames(operation: String, path: VPath, p: Path): Result[Path] =
    val depth = Iterator
      .iterate(p)(_.getParent)
      .takeWhile(q => q != null && q.startsWith(root) && q != root)
      .find(exists)
      .map(q => root.relativize(q).getNameCount)
      .getOrElse(0)
    if depth == 0 then Right(p)
    else
      val named   = path.segments.take(depth)
      val onDisk  = root.relativize(canonical(root.resolve(named.mkString("/"))))
      val spelled = (0 until onDisk.getNameCount).map(i => onDisk.getName(i).toString).toVector
      if spelled.map(HostFileBackend.fold) == named.map(HostFileBackend.fold) then Right(p)
      else
        refused(
          operation,
          path,
          s"names an entry by a name that is not its own (it is ${spelled.mkString("/", "/", "")}: an 8.3 short " +
            "name?); use the name a listing shows"
        )

  /** Every existing ancestor of `target` below the root is a directory. */
  private def underDirectories(operation: String, path: VPath, target: Path): Result[Unit] =
    Iterator
      .iterate(target.getParent)(_.getParent)
      .takeWhile(p => p != null && p.startsWith(root) && p != root)
      .find(p => exists(p) && !isDir(p))
      .fold[Result[Unit]](Right(()))(_ => refused(operation, path, "is beneath a file"))

  private def isDir(p: Path)  = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
  private def isFile(p: Path) = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
  private def exists(p: Path) = Files.exists(p, LinkOption.NOFOLLOW_LINKS)

  /**
   * An I/O step that has changed nothing: a file-system refusal is the model's, an interrupt is a
   * cancellation (the flag kept set), and anything else fails.
   */
  private def io[A](operation: String, path: VPath)(body: => Result[A]): Result[A] =
    Try(body) match
      case Success(result) => result
      case Failure(e) if HostFileBackend.isInterrupt(e) =>
        Thread.currentThread().interrupt()
        Left(CancelledError(s"$operation ${path.value}"))
      // a name the host cannot hold (on Windows `:`, `*`, `?`, `"`, `<`, `>`, `|`): the model's to correct
      case Failure(e: java.nio.file.InvalidPathException) =>
        refused(operation, path, s"is not a valid file name on this host: ${e.getReason}")
      case Failure(_: NoSuchFileException)   => Left(BackendRefused.notFound(operation, path))
      case Failure(_: AccessDeniedException) => refused(operation, path, "permission denied")
      case Failure(e: FileSystemException)   => refused(operation, path, Option(e.getReason).getOrElse(e.toString))
      case Failure(_: HostFileBackend.BinaryContent) =>
        refused(operation, path, "is binary (it contains NUL); only UTF-8 text is supported")
      case Failure(_: CharacterCodingException) => refused(operation, path, "is not UTF-8 text")
      case failure                              => failure.toResult.flatten

  /**
   * A file-system step's outcome through the same classifier as [[io]], so a refusal the host makes - a
   * permission, a full disk, a name too long - is the model's to see, never a failed task.
   */
  private def classified[A](operation: String, path: VPath)(step: Try[A]): Result[A] =
    io(operation, path)(Right(step.get))

  // ---- content

  private def fileBytes(operation: String, path: VPath, p: Path): Result[Array[Byte]] =
    if !exists(p) then Left(BackendRefused.notFound(operation, path))
    else if isDir(p) then refused(operation, path, "is a directory")
    else if !isFile(p) then refused(operation, path, "is not a regular file")
    else
      bounded(p)
        .toRight(())
        .left
        .flatMap(_ => refused(operation, path, s"is larger than the limit of $maxFileBytes bytes"))

  /**
   * At most `maxFileBytes` of `p`, read through one handle that does not follow a final link, or `None`
   * when there are more: the bound holds for what is read, not for a size checked before.
   */
  private def bounded(p: Path): Option[Array[Byte]] =
    Using.resource(FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))(
      HostFileBackend.readAtMost(_, maxFileBytes)
    )

  /** `bytes` as text under the backend's one text rule (`Text.binaryReason`), as a write applies it. */
  private def text(bytes: Array[Byte]): String =
    val decoded = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes))
      .toString
    if decoded.indexOf('\u0000') >= 0 then throw new HostFileBackend.BinaryContent else decoded

  private def fileCheck(bytes: Array[Byte]): StateCheck = StateCheck.of('f'.toByte +: bytes)
  private val directoryCheck: StateCheck                = StateCheck.of("d")

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

  /**
   * Writes `bytes` to `target` atomically: a flushed temporary file beside it, moved over it. A file it
   * replaces keeps its permissions: before any byte is written, the temporary file is given the target's
   * POSIX permissions, owner and group (checked to hold), its ACL and owner where the file system has an
   * ACL view, and its DOS hidden and system flags. Where they cannot be kept the write is refused, never
   * widened. A new file gets what any file created in its directory gets.
   */
  private def atomicWrite(operation: String, path: VPath, target: Path, bytes: Array[Byte]): Result[Unit] =
    HostFileBackend.Kept.of(target) match
      case Left(reason) => refused(operation, path, reason)
      case Right(kept)  =>
        // the missing parents, top first, each created by its own call: only those this write created are
        // removed if it does not land (deepest first, each only while empty), never one another writer made
        val missing = Iterator
          .iterate(target.getParent)(_.getParent)
          .takeWhile(d => d != null && d.startsWith(root) && d != root && !exists(d))
          .toVector
          .reverse
        val (made, parents) = HostFileBackend.createParents(missing, d => Files.createDirectory(d): Unit)
        def undo(): Unit    = made.reverseIterator.foreach(d => Try(Files.deleteIfExists(d)): Unit)
        val written = classified(operation, path)(parents).flatMap { _ =>
          // a short name of its own, so a target near the file system's name limit still has room for it
          val temp    = target.resolveSibling(s".llm4s-${UUID.randomUUID()}.tmp")
          val options = java.util.Set.of[StandardOpenOption](StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
          val attempt: Try[Option[String]] =
            Using(FileChannel.open(temp, options, kept.toSeq.flatMap(_.createAttributes)*)) { channel =>
              kept.fold[Either[String, Unit]](Right(()))(_.applyTo(temp)).left.toOption.orElse {
                val buffer = ByteBuffer.wrap(bytes)
                while buffer.hasRemaining do channel.write(buffer)
                channel.force(true)
                None
              }
            }.flatMap {
              case None =>
                Try(Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING))
                  .map(_ => None)
              case refusal => Success(refusal)
            }
          if attempt != Success(None) then
            val _ = Try(Files.deleteIfExists(temp))
          attempt match
            case Success(None)         => Right(())
            case Success(Some(reason)) => refused(operation, path, reason)
            case Failure(e)            => classified(operation, path)(Failure[Unit](e))
        }
        if written.isLeft then undo()
        written

  // ---- mutations

  // orders this backend's effects across its threads: the one writer's compare-and-set (C4's `Plan`)
  private val effectLock = new ReentrantLock()

  private def underEffectLock[A](operation: String, path: VPath)(body: => Result[A]): Result[A] =
    Try(effectLock.lockInterruptibly()) match
      case Failure(_) =>
        Thread.currentThread().interrupt()
        Left(CancelledError(s"$operation ${path.value}"))
      case Success(_) => Using.resource(HostFileBackend.Unlock(effectLock))(_ => body)

  private def guarded[A: upickle.default.ReadWriter](operation: String, path: VPath, ctx: FileContext)(
    plan: Path => Result[ReplayGuard.Plan[A]]
  ): Result[(A, StateUpdate)] =
    // the probe and the plan resolve the path, so a completed effect replays its outcome whatever the
    // path has become since; the guard runs them only for an effect that is new or incomplete
    whileOpen(operation, path)(
      ReplayGuard
        .verifiable[A](ctx.run, ctx.effectKey(s"file.$operation", path.value))(
          resolve(operation, path).flatMap(target => io(operation, path)(digest(operation, path, target)))
        )(resolve(operation, path).flatMap(target => io(operation, path)(plan(target))))
        .map(a => (a, StateUpdate.empty))
    )

  /**
   * A plan whose effect is a compare-and-set against `before` (C4): under the backend's effect lock it
   * resolves the path again (a link or mount that appeared since is refused) and runs `effect` only if the
   * digest is still `before`; otherwise it changes nothing and is `EffectReplayRefused`.
   */
  private def casPlan[A](operation: String, path: VPath)(before: StateCheck, after: StateCheck, presumed: A)(
    effect: Path => Result[A]
  ): ReplayGuard.Plan[A] =
    ReplayGuard.Plan(before, after, presumed)(
      underEffectLock(operation, path) {
        resolve(operation, path).flatMap { target =>
          io(operation, path)(digest(operation, path, target).flatMap { now =>
            if now == before then effect(target)
            else
              Left(
                GraphError.EffectReplayRefused(
                  s"file.$operation",
                  s"${path.value} changed after it was planned: it is now ${now.value}, not ${before.value}"
                )
              )
          })
        }
      }
    )

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    guarded[FileWritten]("write", path, ctx) { target =>
      if path == VPath.root then refused("write", path, "is the root directory")
      else
        Text.binaryReason(content) match
          case Some(reason) => refused("write", path, reason)
          // sized before it is encoded, so an over-limit write allocates nothing (rule 12)
          case None if Text.utf8Bytes(content) > maxFileBytes =>
            refused("write", path, s"content is larger than the limit of $maxFileBytes bytes")
          case None if isDir(target) => refused("write", path, "is a directory")
          case None =>
            val bytes = content.getBytes(StandardCharsets.UTF_8)
            for
              _      <- underDirectories("write", path, target)
              before <- digest("write", path, target)
              outcome = FileWritten(before == StateCheck.absent, bytes.length.toLong)
            yield casPlan("write", path)(before, fileCheck(bytes), outcome)(t =>
              atomicWrite("write", path, t, bytes).map(_ => outcome)
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
        edited <- Text.edited(path, text(bytes), old, replacement, replaceAll, maxFileBytes)
        _ <-
          if Text.utf8Bytes(edited._1) > maxFileBytes then
            refused("edit", path, s"the result is larger than the limit of $maxFileBytes bytes")
          else Right(())
        next = edited._1.getBytes(StandardCharsets.UTF_8)
      yield casPlan("edit", path)(fileCheck(bytes), fileCheck(next), FileEdited(edited._2))(t =>
        atomicWrite("edit", path, t, next).map(_ => FileEdited(edited._2))
      )
    }

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    guarded[FileDeleted]("delete", path, ctx) { target =>
      if path == VPath.root then refused("delete", path, "is the root directory")
      else if !exists(target) then Left(BackendRefused.notFound("delete", path))
      else if isDir(target) then
        if Using.resource(Files.list(target))(_.findAny().isPresent) then
          refused("delete", path, "is a directory that is not empty; delete the files in it")
        else
          Right(
            casPlan("delete", path)(directoryCheck, StateCheck.absent, FileDeleted(wasDirectory = true))(t =>
              // a child created since the plan leaves the directory's digest as it was: the delete sees it
              Try(Files.delete(t)) match
                case Success(_) => Right(FileDeleted(wasDirectory = true))
                case Failure(_: java.nio.file.DirectoryNotEmptyException) =>
                  Left(
                    GraphError.EffectReplayRefused(
                      "file.delete",
                      s"${path.value} is no longer empty: an entry was created in it after the delete was planned"
                    )
                  )
                case Failure(e) => classified("delete", path)(Failure[FileDeleted](e))
            )
          )
      else
        digest("delete", path, target).map(before =>
          casPlan("delete", path)(before, StateCheck.absent, FileDeleted(wasDirectory = false))(t =>
            classified("delete", path)(Try(Files.delete(t))).map(_ => FileDeleted(wasDirectory = false))
          )
        )
    }

  // ---- reads

  /**
   * `read`, or nothing for a file `search` skips (not UTF-8, gone since the walk listed it); a cancellation
   * propagates, and any other failure refuses the search rather than leave a silent gap in its result.
   */
  private def unreadableSkipped[A](path: VPath)(read: => Option[A]): Option[A] =
    Try(read) match
      case Success(result) => result
      case Failure(e) if HostFileBackend.isInterrupt(e) =>
        throw new java.util.concurrent.CancellationException(e.toString)
      case Failure(_: CharacterCodingException | _: NoSuchFileException) => None
      case Failure(_: AccessDeniedException) => throw new SearchAborted(path, "permission denied")
      case Failure(e: FileSystemException) =>
        throw new SearchAborted(path, Option(e.getReason).getOrElse(e.toString))
      case Failure(e) => throw e

  /**
   * The listable children of `dir` in order, read only while the scan budget lasts: `None` if `dir` has more
   * than `budget` entries, and with it how many entries were read. Every entry read is charged, listed or
   * not (links, temporary files, other file systems, anything but a regular file or a directory), since
   * each costs the same I/O, and the stream is never read past `budget + 1`. A directory stream has no
   * order, so a directory is used only when it was read whole: a partial read would depend on the file
   * system's order, so a directory over the budget yields nothing, never an arbitrary part of itself.
   */
  /**
   * Whether the entry `p` of `dir`, whose name renders as `name`, is the entry its path names: the name is
   * a valid `VPath` segment that renders unchanged, and resolving it in `dir` gives back `p`, byte for byte.
   * A POSIX name that is not UTF-8 renders with U+FFFD, so it names another file or none, and several such
   * names can render alike; it is never exposed. This is the one place a directory entry becomes a path the
   * model sees: listings, walks and their matches all come through it.
   */
  private def roundTrips(dir: Path, name: String, p: Path): Boolean =
    VPath(s"/$name").exists(_.name == name) && Try(dir.resolve(name) == p).getOrElse(false)

  private def children(dir: Path, budget: Long, walk: Boolean = false): (Option[Vector[(String, Path)]], Long) =
    // a walk orders a directory by its name and `/`, as its descendants' paths start, so `a-foo` comes
    // before `a/x`, as `VPath` orders them; a listing orders the entries themselves, by name
    def key(name: String, p: Path) = if walk && isDir(p) then name + "/" else name
    val kept                       = Vector.newBuilder[(String, String, Path)]
    var read                       = 0L
    Using.resource(openDirectory(dir)) { stream =>
      val entries = stream.iterator()
      while read <= budget && entries.hasNext do
        if Thread.currentThread().isInterrupted then throw new java.util.concurrent.CancellationException("interrupted")
        val p = entries.next()
        read += 1
        if read <= budget then
          val name = p.getFileName.toString
          val hidden =
            !roundTrips(dir, name, p) || HostFileBackend.trailing(name) || HostFileBackend.isReserved(name) ||
              aliased(p) || !(isFile(p) || isDir(p))
          if !hidden then kept += ((key(name, p), name, p))
    }
    if read > budget then (None, read)
    else (Some(kept.result().sortBy(_._1).map((_, name, p) => name -> p)), read)

  /**
   * The entries of the directory `path`, in name order, at most `limit`. A directory with more than
   * `maxScanEntries` entries lists as empty and `truncated`: the backend never reads past its bound, and
   * any part of such a directory would depend on the file system's order.
   */
  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    whileOpen("ls", path)(
      Text
        .checkLimitAt("ls", path, limit)
        .flatMap(_ => resolve("ls", path))
        .flatMap { dir =>
          io("ls", path)(Text.interruptibly("ls") {
            if !exists(dir) then Left(BackendRefused.notFound("ls", path))
            else if !isDir(dir) then Left(BackendRefused.notADirectory("ls", path))
            else
              children(dir, maxScanEntries.toLong)._1 match
                case None          => Right(Listing(Vector.empty[FileInfo], truncated = true))
                case Some(entries) =>
                  // the listing takes `limit + 1` entries to tell if there are more, so only those are stat'ed
                  val infos = entries.iterator.flatMap { (name, p) =>
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
                  Right(Listing.of(infos, limit))
          })
        }
    )

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    whileOpen("read", path)(
      for
        _      <- Text.checkLimitAt("read", path, limit)
        _      <- Either.cond(offset >= 0, (), BackendRefused.at("read", path, s"offset must be >= 0, was $offset"))
        target <- resolve("read", path)
        lines  <- io("read", path)(fileBytes("read", path, target).map(b => Text.lines(text(b))))
      yield FileLines(Text.page(lines, offset, limit), offset, lines.size)
    )

  /**
   * The files within `under`, depth first in path order, reading at most `maxScanEntries` directory entries
   * in all. The walk stops at the first directory the remaining budget cannot read whole, so a cut walk's
   * files are a prefix of the full walk's, the same each time for the same tree, and it is `capped`.
   */
  final private class Walk(start: Path, from: VPath):
    private var read    = 0L
    private var stopped = false

    /** Whether the walk stopped before seeing every entry. */
    def capped: Boolean = stopped
    def files: Iterator[(VPath, Path)] =
      if isFile(start) then Iterator(from -> start) else if isDir(start) then within(start, from) else Iterator.empty
    private def within(dir: Path, at: VPath): Iterator[(VPath, Path)] =
      if stopped then Iterator.empty
      else
        val (entries, cost) = children(dir, maxScanEntries.toLong - read, walk = true)
        read += cost
        entries match
          case None =>
            stopped = true
            Iterator.empty
          case Some(found) =>
            found.iterator.flatMap { (name, p) =>
              if stopped then Iterator.empty
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
    // admitted for the whole walk, so close() keeps the root locked until it ends
    whileOpen(operation, under)(
      Text
        .checkLimitAt(operation, under, limit)
        .flatMap(_ => resolve(operation, under))
        .flatMap { start =>
          io(operation, under) {
            Text.interruptibly(operation) {
              val walk    = new Walk(start, under)
              val listing = Listing.of(select(walk.files), limit)
              Right(listing.copy(truncated = listing.truncated || walk.capped))
            }
          }
        }
    )

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
      unreadableSkipped(v)(bounded(p).map(text)).iterator.flatMap(content => Text.matches(v, content, regex))
    })

/**
 * A root [[HostFileBackend.open]] refused because another backend holds it, or one overlapping it, in this
 * JVM or another process: one root has one writer (design §4.40).
 */
@Experimental
final case class HostRootInUse(root: Path, conflict: HostRootInUse.Conflict, detail: String)
    extends LLMError
    with NonRecoverableError:
  override val message: String = s"$root cannot be opened: $detail"

object HostRootInUse:

  /** How the open root conflicts with this one. */
  enum Conflict:

    /** A backend has this root open. */
    case SameRoot

    /** A backend's open root contains this one. */
    case WithinOpenRoot

    /** This root contains a backend's open root. */
    case ContainsOpenRoot

object HostFileBackend:

  /** The largest `maxFileBytes`: a file is read into one array, which holds at most this many bytes. */
  val MaxFileBytes: Long = Int.MaxValue.toLong - 8

  /** 4 MiB: the default bound on one file. */
  val DefaultMaxFileBytes: Long = 4L * 1024 * 1024

  /** The default bound on the entries one listing or walk visits. */
  val DefaultMaxScanEntries: Int = 100_000

  /**
   * A backend over the existing directory `root`, resolved to its real path, as its only writer.
   *
   * '''The lock.''' `open` takes an exclusive OS lock on the root's lock file and a shared lock on each of
   * its ancestors' (design §4.40), in `lockDirectory`, and holds them until [[HostFileBackend.close]]. A
   * root another backend has open, or one containing or inside one open, is refused with [[HostRootInUse]].
   * Directories are named by their file key (device and inode) where the platform has one, so a root
   * reached through a symbolic link or a bind mount is the same root. Give every process on the host that
   * may open the same or overlapping roots one lock directory, on a local file system, that nothing prunes
   * (a temporary directory a cleaner empties is a poor choice); it is created if absent and must not be
   * inside the root.
   *
   * '''Refused at open''', besides a missing root, bad limits and a root in use:
   *  - a root whose file system resolves 8.3 short names (found by a probe file created and removed in the
   *    root): never waived;
   *  - a root whose identity cannot be checked: one that cannot be probed (read-only), on a file system
   *    that reports no hard-link counts (NTFS), or on Linux one whose mount table cannot be read - unless
   *    `allowUncheckedIdentity`, the caller vouching that the root has no hard links and no 8.3 names;
   *  - a root with another file system mounted beneath it (a bind mount included): never waived.
   */
  def open(
    root: Path,
    lockDirectory: Path,
    maxFileBytes: Long = DefaultMaxFileBytes,
    maxScanEntries: Int = DefaultMaxScanEntries,
    allowUncheckedIdentity: Boolean = false
  ): Result[HostFileBackend] =
    openWith(root, lockDirectory, maxFileBytes, maxScanEntries, allowUncheckedIdentity)

  /** [[open]], with the checks that depend on the machine given, for tests. */
  private[harness] def openWith(
    root: Path,
    lockDirectory: Path,
    maxFileBytes: Long = DefaultMaxFileBytes,
    maxScanEntries: Int = DefaultMaxScanEntries,
    allowUncheckedIdentity: Boolean = false,
    probe: Path => Result[Vector[String]] = probeAliases,
    countsLinks: Path => Boolean = reportsLinkCounts,
    fileSystemId: Path => String = fileSystemIdOf,
    mountsBeneath: Path => Result[Vector[Path]] = mountPointsBeneath,
    openDirectory: Path => java.nio.file.DirectoryStream[Path] = Files.newDirectoryStream(_),
    canonical: Path => Path = _.toRealPath(),
    // called at each named step before the lock is held and the root revalidated, for tests that race it
    pause: String => Unit = _ => ()
  ): Result[HostFileBackend] =
    if maxFileBytes <= 0 then Left(ValidationError("maxFileBytes", s"must be positive, was $maxFileBytes"))
    else if maxFileBytes > MaxFileBytes then
      Left(
        ValidationError(
          "maxFileBytes",
          s"must be at most $MaxFileBytes (a file is read into one array), was $maxFileBytes"
        )
      )
    else if maxScanEntries <= 0 then Left(ValidationError("maxScanEntries", s"must be positive, was $maxScanEntries"))
    else
      // Nothing touches the root's contents until its lock is held and the directory at its path is shown to
      // be the one locked. Before that point: reads only, and writes only in the lock directory, which is
      // refused if it is inside the root by path or by identity, so a root swapped meanwhile is never written.
      for
        // 1. read only: the root's real path, that it is a directory, and the identities of it and its ancestors
        real <- Try(root.toRealPath()).toResult.left.map(e => ValidationError("root", s"$root: ${e.message}"): LLMError)
        _    <- Either.cond(Files.isDirectory(real), (), ValidationError("root", s"$root is not a directory"))
        rootChain <- chainOf(real, "root")
        // its stamp as locked: identity, file system and, where there is no file key (Windows), creation time
        before <- Try(stampOf(real, fileSystemId)).toResult.left
          .map(e => ValidationError("root", s"$real cannot be identified: ${e.message}"): LLMError)
        // 2. read only: the lock directory's nearest existing ancestor is outside the root, by path and by
        // identity, so creating the lock directory cannot write inside the root, or inside whatever replaces it
        _             <- Right(pause("identified"))
        existing      <- nearestExisting(lockDirectory)
        existingChain <- chainOf(existing, "lockDirectory")
        _             <- outsideRoot(lockDirectory, existing, existingChain, root, real, rootChain.head)
        // 3. writes outside the root only: the lock directory, then its lock files
        locks <- Try {
          Files.createDirectories(lockDirectory)
          lockDirectory.toRealPath()
        }.toResult.left.map(e => ValidationError("lockDirectory", s"$lockDirectory: ${e.message}"): LLMError)
        locksChain <- chainOf(locks, "lockDirectory")
        _          <- outsideRoot(lockDirectory, locks, locksChain, root, real, rootChain.head)
        _          <- Right(pause("lockDirectory"))
        held       <- RootLocks.acquire(real, locks, rootChain)
        // 4. the lock is held: the directory at the root's path must be the one locked before anything else
        backend <- {
          pause("locked")
          locked(real, before, fileSystemId)
            .flatMap(stamp =>
              // 5. only now is the root written: the 8.3 probe; and read: link counts, the mount table, and
              // its stamp again, which must not have changed during them
              checked(real, stamp, allowUncheckedIdentity, probe, countsLinks, fileSystemId, mountsBeneath)
            )
            .map { (stamp, mounts, links) =>
              new HostFileBackend(
                real,
                maxFileBytes,
                maxScanEntries,
                held,
                stamp,
                stampOf(_, fileSystemId),
                fileSystemId,
                mounts,
                links,
                openDirectory,
                canonical
              )
            } match
            case Left(error) =>
              held.release()
              Left(error)
            case right => right
        }
      yield backend

  /** That `dir` (resolved as `resolved`, with its identity chain) is outside the root, by path and identity. */
  private def outsideRoot(
    dir: Path,
    resolved: Path,
    chain: Vector[String],
    root: Path,
    real: Path,
    rootIdentity: String
  ): Result[Unit] =
    val named = dir.toAbsolutePath.normalize
    val inside = chain.contains(rootIdentity) || resolved.startsWith(real) || named.startsWith(real) ||
      named.startsWith(root.toAbsolutePath.normalize)
    Either.cond(
      !inside,
      (),
      ValidationError("lockDirectory", s"$dir is inside the root $real; give the backend one outside it")
    )

  /**
   * The root's stamp, once its lock is held, if the directory at its path is still the one locked: its
   * real path unchanged and its stamp the one read before locking. Read only.
   */
  private def locked(real: Path, before: Stamp, fileSystemId: Path => String): Result[Stamp] =
    Try((real.toRealPath(), stampOf(real, fileSystemId))).toResult.left
      .map(e => ValidationError("root", s"$real cannot be identified: ${e.message}"): LLMError)
      .flatMap((again, stamp) =>
        Either.cond(
          again == real && stamp == before,
          stamp,
          ValidationError("root", s"$real was replaced while it was being opened; open it again")
        )
      )

  /** The identity checks of `open`, run while the root's lock is held and once it is shown to be the root. */
  private def checked(
    real: Path,
    stamp: Stamp,
    allowUncheckedIdentity: Boolean,
    probe: Path => Result[Vector[String]],
    countsLinks: Path => Boolean,
    fileSystemId: Path => String,
    mountsBeneath: Path => Result[Vector[Path]]
  ): Result[(Stamp, Path => Result[Vector[Path]], Boolean)] =
    // a mount table that cannot be read is waived only by `allowUncheckedIdentity`; a mount found never is
    val mounts = (p: Path) =>
      mountsBeneath(p) match
        case Left(_) if allowUncheckedIdentity => Right(Vector.empty)
        case other                             => other
    val links = countsLinks(real)
    for
      _ <- probe(real) match
        case Right(found) if found.isEmpty => Right(())
        case Right(found) =>
          Left(
            ValidationError(
              "root",
              s"$real's file system resolves aliases the backend cannot see: ${found.mkString("; ")}. " +
                "Use the workspace sandbox's file backend or a StoreFileBackend for it"
            )
          )
        case Left(_) if allowUncheckedIdentity => Right(())
        case Left(error)                       => Left(error)
      _ <- Either.cond(
        links || allowUncheckedIdentity,
        (),
        ValidationError(
          "root",
          s"$real's file system reports no hard-link counts, so a file with another name cannot be refused; " +
            "pass allowUncheckedIdentity only if the root has no hard links, or use the workspace sandbox's " +
            "file backend or a StoreFileBackend"
        )
      )
      _ <- mounts(real).flatMap(found =>
        Either.cond(found.isEmpty, (), ValidationError("root", mountedReason(real, found)))
      )
      // the stamp every operation compares is the locked directory's: one swapped in while the checks ran
      // would pass `rootIntact` while another backend locked it as a different root
      after <- Try(stampOf(real, fileSystemId)).toResult.left
        .map(e => ValidationError("root", s"$real cannot be identified: ${e.message}"): LLMError)
      _ <- Either.cond(
        after == stamp,
        (),
        ValidationError("root", s"$real was replaced while it was being opened; open it again")
      )
    yield (stamp, mounts, links)

  // ---- identity

  /**
   * What `open` records of the root and every operation compares: its identity (file key, else its folded
   * real path), its file system and, where there is no file key, its creation time - so a directory moved
   * into its place or a mount over it is seen. (Where there is a file key, creation time is not used: some
   * platforms report the modification time for it.)
   */
  final private[harness] case class Stamp(identity: String, fileSystem: String, created: Option[Long])

  private def stampOf(p: Path, fileSystemId: Path => String): Stamp =
    val attributes = Files.readAttributes(p, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
    Option(attributes.fileKey) match
      case Some(key) => Stamp(s"key:$key", fileSystemId(p), None)
      case None      => Stamp(pathIdentity(p), fileSystemId(p), Some(attributes.creationTime.toMillis))

  /**
   * A directory's identity: its file key (device and inode) where the platform reports one, so one
   * directory reached by two paths (a symbolic link, a bind mount, a firmlink) has one identity; else
   * (Windows) its real path, case-folded, which the OS resolves through junctions, links and short names.
   */
  private[harness] def identityOf(p: Path): String =
    Option(Files.readAttributes(p, classOf[BasicFileAttributes]).fileKey)
      .fold(pathIdentity(p.toRealPath()))(key => s"key:$key")

  private def pathIdentity(p: Path): String = s"path:${fold(p.toString.replace('\\', '/'))}"

  /** The real path of `p`'s nearest ancestor that exists (`p` itself if it does). */
  private def nearestExisting(p: Path): Result[Path] =
    Try(
      Iterator
        .iterate(p.toAbsolutePath)(_.getParent)
        .takeWhile(_ != null)
        .find(q => Files.exists(q))
        .map(_.toRealPath())
    ).toResult.left
      .map(e => ValidationError("lockDirectory", s"$p: ${e.message}"): LLMError)
      .flatMap(_.toRight(ValidationError("lockDirectory", s"$p has no existing ancestor")))

  /** The identities of the real directory `real` and of each of its ancestors, the root's first. */
  private def chainOf(real: Path, field: String): Result[Vector[String]] =
    Try(Iterator.iterate(real)(_.getParent).takeWhile(_ != null).map(identityOf).toVector).toResult.left
      .map(e => ValidationError(field, s"$real cannot be identified: ${e.message}"): LLMError)

  /** A name as the most permissive file system compares it: Unicode NFC, then case-folded. */
  private[harness] def fold(name: String): String =
    java.text.Normalizer
      .normalize(name, java.text.Normalizer.Form.NFC)
      .toUpperCase(java.util.Locale.ROOT)
      .toLowerCase(java.util.Locale.ROOT)

  private[harness] def digestOf(s: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  // ---- the single-writer lock

  /**
   * The OS locks of open roots: one lock file per directory identity in the lock directory, an exclusive
   * lock on a root's own and a shared one on each ancestor's (design §4.40).
   *
   * This JVM keeps one channel per lock file. On POSIX, closing any channel of a file releases every lock
   * the process holds on it, whichever channel took them, so a second channel - a sibling's on a shared
   * ancestor, or a refused open's on a held root - must never be opened and closed beside a held lock. The
   * table only counts holds; which roots conflict is the OS lock's to decide across processes, and the same
   * rule (exclusive against anything, shared against exclusive) within this JVM, where POSIX locks never
   * conflict.
   */
  private[harness] object RootLocks:

    final private class Entry(val channel: FileChannel, val lock: FileLock, val exclusive: Boolean, var holders: Int)

    // guarded by RootLocks; every lock is taken with tryLock, so the monitor is never held while waiting
    private val entries = scala.collection.mutable.Map.empty[String, Entry]

    /** One backend's holds; released once, by `release`. */
    final class Held private[RootLocks] (keys: Vector[String]):
      private val done    = new java.util.concurrent.atomic.AtomicBoolean(false)
      def release(): Unit = if done.compareAndSet(false, true) then RootLocks.synchronized(keys.foreach(releaseKey))

    private def releaseKey(key: String): Unit =
      entries.get(key).foreach { entry =>
        entry.holders -= 1
        if entry.holders == 0 then
          entries.remove(key)
          val _ = Try(entry.lock.release())
          val _ = Try(entry.channel.close())
      }

    /** The lock file of the directory identity `identity`. */
    def lockFile(locks: Path, identity: String): Path = locks.resolve(s"${digestOf(identity)}.lock")

    /** Holds `real`'s lock exclusively and each ancestor's shared, or refuses with what conflicts. */
    def acquire(real: Path, locks: Path, chain: Vector[String]): Result[Held] =
      val ancestors = Iterator.iterate(real.getParent)(_.getParent).takeWhile(_ != null).toVector
      RootLocks.synchronized {
        // each hold taken in turn; on the first refusal, the ones taken are released
        val taken = scala.collection.mutable.ArrayBuffer.empty[String]
        val outcome = chain.zipWithIndex.foldLeft[Result[Unit]](Right(())) { case (acc, (identity, i)) =>
          acc.flatMap { _ =>
            val file = lockFile(locks, identity)
            val got  = if i == 0 then exclusive(real, file) else shared(real, ancestors(i - 1), file)
            got.map(key => taken += key)
          }
        }
        outcome match
          case Right(_) => Right(new Held(taken.toVector))
          case Left(error) =>
            taken.foreach(releaseKey)
            Left(error)
      }

    /** The table's key for `file`, created if absent: its own identity, so two paths to it share a channel. */
    private def keyOf(file: Path): Result[String] =
      Try {
        Try(Files.createFile(file)) match
          case Failure(_: FileAlreadyExistsException) | Success(_) => ()
          case Failure(e)                                          => throw e
        identityOf(file)
      }.toResult.left.map(e => ProcessingError("host-lock", s"cannot create the lock file $file: ${e.message}"))

    private def opened(file: Path): Result[FileChannel] =
      Try(FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)).toResult.left
        .map(e => ProcessingError("host-lock", s"cannot open the lock file $file: ${e.message}"))

    /**
     * A lock on the whole of `file` through `channel`, `None` if another process holds a conflicting one; a
     * failure (an interrupt, a file system without locks) is an error, never taken for a conflict. Only a
     * channel no lock of this JVM is on is ever closed here (`file` has no entry), so closing it releases none.
     */
    private def tryLock(file: Path, channel: FileChannel, shared: Boolean): Result[Option[FileLock]] =
      Try(Option(channel.tryLock(0L, Long.MaxValue, shared))) match
        case Success(lock) => Right(lock)
        case Failure(e) =>
          val _ = Try(channel.close())
          if HostFileBackend.isInterrupt(e) then
            Thread.currentThread().interrupt()
            Left(CancelledError(s"open: locking $file"))
          else Left(ProcessingError("host-lock", s"cannot lock $file: $e"))

    private def exclusive(real: Path, file: Path): Result[String] =
      keyOf(file).flatMap { key =>
        entries.get(key) match
          case Some(entry) =>
            Left(inUse(real, if entry.exclusive then HostRootInUse.Conflict.SameRoot else containing))
          case None =>
            opened(file).flatMap { channel =>
              tryLock(file, channel, shared = false).flatMap {
                case Some(lock) =>
                  entries.update(key, new Entry(channel, lock, exclusive = true, holders = 1))
                  Right(key)
                case None =>
                  // held elsewhere: shared holds are an open root beneath this one, an exclusive one this root
                  tryLock(file, channel, shared = true).flatMap { probe =>
                    probe.foreach(held => Try(held.release()))
                    val _ = Try(channel.close())
                    Left(inUse(real, if probe.isDefined then containing else HostRootInUse.Conflict.SameRoot))
                  }
              }
            }
      }

    private def shared(real: Path, ancestor: Path, file: Path): Result[String] =
      keyOf(file).flatMap { key =>
        entries.get(key) match
          case Some(entry) if entry.exclusive => Left(within(real, ancestor))
          case Some(entry) =>
            entry.holders += 1
            Right(key)
          case None =>
            opened(file).flatMap { channel =>
              tryLock(file, channel, shared = true).flatMap {
                case Some(lock) =>
                  entries.update(key, new Entry(channel, lock, exclusive = false, holders = 1))
                  Right(key)
                case None =>
                  val _ = Try(channel.close())
                  Left(within(real, ancestor))
              }
            }
      }

    private val containing = HostRootInUse.Conflict.ContainsOpenRoot

    private def inUse(real: Path, conflict: HostRootInUse.Conflict): HostRootInUse =
      HostRootInUse(
        real,
        conflict,
        conflict match
          case HostRootInUse.Conflict.SameRoot => "another HostFileBackend has it open, in this process or another"
          case _ =>
            "another HostFileBackend has a directory inside it open, in this process or another; one file has one writer"
      )

    private def within(real: Path, ancestor: Path): HostRootInUse =
      HostRootInUse(
        real,
        HostRootInUse.Conflict.WithinOpenRoot,
        s"it is inside $ancestor, which another HostFileBackend has open, in this process or another; " +
          "one file has one writer"
      )

  // ---- checks of the root's file system

  /** Whether `root`'s file system reports hard-link counts (the `unix` attribute view): POSIX does, NTFS not. */
  def reportsLinkCounts(root: Path): Boolean = root.getFileSystem.supportedFileAttributeViews.contains("unix")

  final private case class Unlock(lock: ReentrantLock) extends AutoCloseable:
    def close(): Unit = lock.unlock()

  private val Temporary = """\.llm4s-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp""".r

  private val Probe =
    """[0-9a-z]{6}-llm4s-probe-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.probe""".r

  /** A root lock file's name: the SHA-256 of a directory identity, in hex. */
  private val LockFileName = """[0-9a-f]{64}\.lock""".r

  /**
   * A name the backend keeps for itself: its temporary files, probe files a failed cleanup left, and lock
   * files. A lock directory is never inside the root by its identity chain, but one reached through a bind
   * mount of a directory inside it is not seen (§4.40); its lock files are still never listed, read, written
   * or deleted, so no tool call can delete a held lock file and let a second writer lock a new one. Compared
   * folded, so a case alias of one is one too.
   */
  private def isReserved(name: String): Boolean =
    val folded = fold(name)
    Temporary.matches(folded) || Probe.matches(folded) || LockFileName.matches(folded)

  /** Decoded content that the text rule calls binary (NUL); a read refuses it and a search skips it. */
  final private class BinaryContent extends CharacterCodingException

  /**
   * The attributes a replacement of a file must keep: its POSIX permissions, owner and group, or its
   * ACL and owner, and its DOS hidden and system flags.
   */
  final private[harness] class Kept private (
    posix: Option[(java.util.Set[PosixFilePermission], UserPrincipal, GroupPrincipal)],
    acl: Option[(UserPrincipal, java.util.List[AclEntry])],
    dos: Option[(Boolean, Boolean)]
  ):

    /** Created with the target's permissions, so the temporary file is never wider than it, even briefly. */
    def createAttributes: Seq[FileAttribute[?]] =
      posix.map((perms, _, _) => PosixFilePermissions.asFileAttribute(perms)).toSeq

    /** Gives `temp` these attributes (the umask may have narrowed them), or why it cannot have them. */
    def applyTo(temp: Path): Either[String, Unit] =
      Try {
        posix.foreach { (perms, owner, group) =>
          val view = Files.getFileAttributeView(temp, classOf[PosixFileAttributeView], LinkOption.NOFOLLOW_LINKS)
          view.setPermissions(perms)
          if view.readAttributes().owner() != owner then view.setOwner(owner)
          if view.readAttributes().group() != group then view.setGroup(group)
        }
        acl.foreach { (owner, entries) =>
          val view = Files.getFileAttributeView(temp, classOf[AclFileAttributeView], LinkOption.NOFOLLOW_LINKS)
          view.setAcl(entries)
          if view.getOwner() != owner then view.setOwner(owner)
        }
        // only a flag that is set is written: a new file has neither, and writing `false` would need
        // extended attributes a file system without DOS flags may not have
        dos.foreach { (hidden, system) =>
          val view = Files.getFileAttributeView(temp, classOf[DosFileAttributeView], LinkOption.NOFOLLOW_LINKS)
          if hidden then view.setHidden(true)
          if system then view.setSystem(true)
        }
        posix.forall { (perms, owner, group) =>
          val now = Files.readAttributes(temp, classOf[PosixFileAttributes], LinkOption.NOFOLLOW_LINKS)
          now.permissions() == perms && now.owner() == owner && now.group() == group
        }
      } match
        case Success(true)  => Right(())
        case Success(false) => Left("the file system did not keep its permissions on the replacement")
        case Failure(e)     => Left(s"its permissions cannot be kept on the replacement: $e")

  object Kept:

    /** `target`'s attributes, `None` if there is no file to replace, or why a replacement cannot keep them. */
    def of(target: Path): Either[String, Option[Kept]] =
      if !Files.exists(target, LinkOption.NOFOLLOW_LINKS) then Right(None)
      else
        Try {
          def view[V <: java.nio.file.attribute.FileAttributeView](c: Class[V]): Option[V] =
            Option(Files.getFileAttributeView(target, c, LinkOption.NOFOLLOW_LINKS))
          // setuid, setgid and sticky: no portable API sets them, so a replacement would drop them
          val special = Try(Files.getAttribute(target, "unix:mode", LinkOption.NOFOLLOW_LINKS)) match
            case Success(mode: Integer) => Some((mode.intValue & 0xe00) != 0)
            case Success(_) | Failure(_: UnsupportedOperationException | _: IllegalArgumentException) => None
            case Failure(e)                                                                           => throw e
          val posix = view(classOf[PosixFileAttributeView]).map { v =>
            val a = v.readAttributes()
            (a.permissions(), a.owner(), a.group())
          }
          // both where both exist: a POSIX mode does not carry an ACL's named entries
          val acl = view(classOf[AclFileAttributeView]).map(v => (v.getOwner(), v.getAcl()))
          // beside POSIX too (Linux keeps DOS flags in extended attributes); unreadable only where the file
          // store has no extended attributes, so no flag can be set to lose
          val dos = view(classOf[DosFileAttributeView]).flatMap { v =>
            Try(v.readAttributes()) match
              case Success(a) => Some(a)
              case Failure(_)
                  if posix.isDefined && !Files
                    .getFileStore(target)
                    .supportsFileAttributeView(classOf[java.nio.file.attribute.UserDefinedFileAttributeView]) =>
                None
              case Failure(e) => throw e
          }
          if special.contains(true) then Left("has a setuid, setgid or sticky bit, which a replacement cannot keep")
          else if dos.exists(_.isReadOnly) then Left("is read-only")
          else if posix.isEmpty && acl.isEmpty then
            Left("is on a file system whose permissions the backend cannot copy onto a replacement")
          else Right(Some(new Kept(posix, acl, dos.map(a => (a.isHidden, a.isSystem)))))
        } match
          case Success(kept) => kept
          case Failure(e)    => Left(s"its permissions cannot be read: $e")

  /**
   * Whether `e` is how the JDK reports an interrupt of a blocking call: one class for a closed channel,
   * one for a thread, and the `CancellationException` a walk throws. Each is a cancellation, never a
   * file-system failure.
   */
  private[harness] def isInterrupt(e: Throwable): Boolean = e match
    case _: java.nio.channels.ClosedByInterruptException    => true
    case _: java.nio.channels.FileLockInterruptionException => true
    case _: InterruptedException                            => true
    case _: java.io.InterruptedIOException                  => true
    case _: java.util.concurrent.CancellationException      => true
    case _                                                  => false

  /**
   * At most `max` bytes of `channel`, or `None` when it holds more. Each read asks for no more than the
   * bytes still allowed plus one, so an over-limit file is detected after reading `max + 1` bytes, never a
   * whole buffer past the cap (rule 12: a bound checked before each unit of work, not after it).
   */
  private[harness] def readAtMost(channel: java.nio.channels.ReadableByteChannel, max: Long): Option[Array[Byte]] =
    val out    = new java.io.ByteArrayOutputStream()
    val buffer = ByteBuffer.allocate(64 * 1024)
    var total  = 0L
    var more   = true
    while more && total <= max do
      buffer.clear()
      // the bytes still allowed, plus one to see an overflow, without computing past Long.MaxValue
      val remaining = max - total
      buffer.limit(if remaining >= buffer.capacity then buffer.capacity else (remaining + 1).toInt)
      val n = channel.read(buffer)
      if n < 0 then more = false
      else
        total += n
        if total <= max then out.write(buffer.array(), 0, n)
    Option.when(total <= max)(out.toByteArray)

  /**
   * Creates `dirs` (top first) one by one with `mkdir`, and returns the ones this call created, and whether
   * all now exist. One that appears meanwhile (another writer's) is used but never counted as created, so
   * a rollback cannot remove it.
   */
  private[harness] def createParents(dirs: Vector[Path], mkdir: Path => Unit): (Vector[Path], Try[Unit]) =
    dirs.foldLeft((Vector.empty[Path], Try(()))) { case ((made, so), d) =>
      if so.isFailure then (made, so)
      else
        Try(mkdir(d)) match
          case Success(_) => (made :+ d, so)
          case Failure(_: FileAlreadyExistsException) if Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS) =>
            (made, so)
          case Failure(e) => (made, Failure(e))
    }

  /** A name Windows reads as another (a trailing dot or space is dropped); the backend never uses one. */
  private[harness] def trailing(name: String): Boolean = name.endsWith(".") || name.endsWith(" ")

  /**
   * The 8.3 short names a root's file system resolves, found by trying it: a probe file is created in
   * `root` and looked up by its short name; the probe is then removed. `Right` lists what was found (empty:
   * none); `Left` means the root could not be probed (for example, it is read-only).
   */
  def probeAliases(root: Path): Result[Vector[String]] =
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val random   = new java.security.SecureRandom()
    val prefix   = Vector.fill(6)(alphabet.charAt(random.nextInt(alphabet.length))).mkString
    probeAliasesWith(root, prefix, p => Files.exists(p), (a, b) => Files.isSameFile(a, b))

  /**
   * The probe, with its prefix and file-system lookups given: a probe file `<prefix>-llm4s-probe-<uuid>.probe`,
   * whose 8.3 short name, where the volume makes one, is `<prefix>~<n>.PRO` for a small `n`. Each of
   * `~1` to `~4` is looked up: one that is the probe file is an alias found; one that names another,
   * existing file means the short name cannot be established, and the probe refuses rather than guess.
   */
  private[harness] def probeAliasesWith(
    root: Path,
    prefix: String,
    exists: Path => Boolean,
    isSame: (Path, Path) => Boolean
  ): Result[Vector[String]] =
    val file = root.resolve(s"$prefix-llm4s-probe-${UUID.randomUUID()}.probe")
    Try(Files.createFile(file)).toResult.left
      .map(e => ValidationError("root", s"$root cannot be probed for file-name aliasing: ${e.message}"): LLMError)
      .flatMap { _ =>
        // a lookup that fails cannot say there is no alias, so it refuses the probe rather than pass it
        val lookups = Try((1 to 4).map { n =>
          val alias   = root.resolve(s"$prefix~$n.PRO")
          val present = exists(alias)
          present -> (present && isSame(file, alias))
        })
        val removed = Try(Files.deleteIfExists(file))
        val looked  = lookups.getOrElse(IndexedSeq.empty)
        if removed.isFailure then
          Left(ValidationError("root", s"$root cannot be probed: the probe file $file could not be removed"))
        else if lookups.isFailure then
          Left(ValidationError("root", s"$root cannot be probed for 8.3 short names: ${lookups.failed.get}"))
        else if looked.exists((present, same) => present && !same) then
          Left(
            ValidationError(
              "root",
              s"$root cannot be probed for 8.3 short names: another entry already has the probe's short-name prefix $prefix"
            )
          )
        else if looked.exists(_._2) then
          Right(Vector("8.3 short names (a file is also reachable by a generated short name)"))
        else Right(Vector.empty)
      }

  private[harness] def mountedReason(root: Path, found: Vector[Path]): String =
    s"the root $root has file systems mounted beneath it (${found.take(3).mkString(", ")}${
        if found.size > 3 then ", ..." else ""
      }); " +
      "a mount, a bind mount of the root's own file system included, makes one file reachable by two paths"

  /**
   * The mount points strictly beneath `root`, from Linux's `/proc/self/mountinfo`: a bind mount of the
   * root's own file system has the root's device, so only the mount table shows it. Empty where there is no
   * such table (macOS and Windows have no same-device bind mounts; their other mounts differ in file
   * system, which [[fileSystemIdOf]] catches).
   */
  def mountPointsBeneath(root: Path): Result[Vector[Path]] =
    val table = Path.of("/proc/self/mountinfo")
    if !Files.isReadable(table) then
      // no table is no mounts only where there is none to read; on Linux it is an unknown
      if root.getFileSystem.provider.getClass.getName == "sun.nio.fs.LinuxFileSystemProvider" then
        Left(
          ValidationError(
            "root",
            s"$table cannot be read, so mounts beneath $root (a bind mount makes one file two paths) cannot be checked; " +
              "pass allowUncheckedIdentity only if there are none"
          )
        )
      else Right(Vector.empty)
    else Try(Using.resource(Files.lines(table))(lines => mountPointsIn(lines.iterator.asScala, root))).toResult

  private val Escaped = """\\([0-7]{3})""".r

  /** A mount table field with its octal escapes (`\040` for a space) decoded. */
  private def unescaped(field: String): String =
    Escaped.replaceAllIn(
      field,
      m => scala.util.matching.Regex.quoteReplacement(Integer.parseInt(m.group(1), 8).toChar.toString)
    )

  /** The mount points (field 5) in `mountinfo` lines strictly beneath `root`. */
  private[harness] def mountPointsIn(lines: Iterator[String], root: Path): Vector[Path] =
    lines
      .flatMap { line =>
        val f = line.split(' ')
        Option.when(f.length > 4)(Path.of(unescaped(f(4))))
      }
      .filter(m => m.startsWith(root) && m != root)
      .toVector
      .distinct

  /**
   * Which file system `p` is on, without following a final link: its `unix:dev` where the JDK reports
   * it, else (Windows) its volume's serial number, or the store's name where the volume has none. Never the
   * store's rendering, which on Windows carries the path's form (a path past `MAX_PATH` is reached as
   * `\\?\C:\...`), so one volume would read as two. The backend never enters a path whose file system
   * differs from the root's.
   */
  def fileSystemIdOf(p: Path): String =
    if p.getFileSystem.supportedFileAttributeViews.contains("unix") then
      s"dev:${Files.getAttribute(p, "unix:dev", LinkOption.NOFOLLOW_LINKS)}"
    else
      val store = Files.getFileStore(p)
      Try(store.getAttribute("volume:vsn")).fold(_ => s"store:${store.name}", vsn => s"vsn:$vsn")
