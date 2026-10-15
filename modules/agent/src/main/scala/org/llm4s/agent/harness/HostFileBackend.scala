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
import java.nio.file.attribute.{
  AclEntry,
  AclFileAttributeView,
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
 * Files in a directory of the host, opt-in: the agent sees `root` as `/`. Its mutations are external
 * effects, and its target is `Verifiable` (C4): each runs through `ReplayGuard.verifiable` under
 * `ctx.effectKey("file.write" | "file.edit" | "file.delete", path)`, with the content digests before and
 * after, so a replayed edit whose first attempt landed returns its first result rather than "`old`
 * was not found", and one whose file another writer changed in between is refused.
 *
 * '''One path, one file.''' Its locks, stripes and replay assume that one path names one file, so [[HostFileBackend.open]]
 * checks it and refuses a root where it does not hold (an SMB share, a volume with 8.3 names on).
 * Supported: local POSIX file systems, case-insensitive ones included; NTFS only with `allowUncheckedIdentity`.
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
 *  - '''Order.''' A mutation locks `files/host/<root>/<path>`, so two mutations of one file in a batch, or a
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
  val lockDirectory: Path,
  fileSystemId: Path => String,
  mountsBeneath: Path => Result[Vector[Path]],
  // the opened root itself, taken by `open`: its file key (device and inode, where the file system has
  // them) and file system, so a mount or a directory put over the root later is seen, not taken as it
  rootKey: Option[AnyRef],
  rootFileSystem: String
) extends FileBackend
    with AutoCloseable:

  // `closed` and `inFlight` are guarded by `lifecycle`; `drained` is signalled when `inFlight` falls to 0
  private val lifecycle = new ReentrantLock()
  private val drained   = lifecycle.newCondition()
  private var closed    = false
  private var inFlight  = 0

  private def underLifecycle[A](body: => A): A =
    lifecycle.lock()
    Using.resource(HostFileBackend.Unlock(lifecycle))(_ => body)

  /**
   * Releases the root, so a backend over an overlapping one may open; idempotent. Every operation that
   * starts after it is refused (`BackendRefused.Kind.Closed`), and it first waits for every operation
   * already admitted to finish: a closed backend no longer holds its root, so an operation of it still
   * running would let two backends name one file by two paths. The wait is uninterruptible, since the
   * root must not be released under a running operation (each of which stops on its own interrupt).
   * Never call it from inside one of this backend's operations.
   */
  def close(): Unit =
    val first = underLifecycle {
      val was = closed
      closed = true
      while inFlight > 0 do drained.awaitUninterruptibly()
      !was
    }
    if first then HostFileBackend.OpenRoots.release(root)

  /** An admitted operation's hold on the root, released when it finishes, however it finishes. */
  final private class Lease extends AutoCloseable:
    def close(): Unit = underLifecycle {
      inFlight -= 1
      if inFlight == 0 then drained.signalAll()
    }

  // how deep this thread is inside an admitted operation: a helper beneath one is never admitted again,
  // so an operation admitted before close() always finishes as it would have
  private val admittedDepth = ThreadLocal.withInitial[Integer](() => 0)

  private def whileOpen[A](operation: String, path: VPath)(body: => Result[A]): Result[A] =
    if admittedDepth.get > 0 then body
    else
      val admitted = underLifecycle {
        if !closed then inFlight += 1
        !closed
      }
      if !admitted then Left(BackendRefused(operation, path.value, "the backend is closed", BackendRefused.Kind.Closed))
      else
        Using.resource(new Lease) { _ =>
          admittedDepth.set(1)
          Using.resource(HostFileBackend.Unnest(admittedDepth))(_ => body)
        }

  /** C4's class for this target. */
  val replaySupport: ReplaySupport = ReplaySupport.Verifiable

  val writes: Set[StateKey[?, ?]] = Set.empty

  private val rootLock = FileBackend.LockRoot / "host" / HostFileBackend.identity(root.toString.replace('\\', '/'))

  /**
   * Locks name a file by its case- and normalisation-folded path ([[HostFileBackend.identity]]), so
   * `/A` and `/a`, which one file on a case-insensitive file system, conflict. On a case-sensitive one
   * they only conflict needlessly.
   */
  def locks(path: VPath): Set[LockKey] =
    if lexicalIdentity then Set(rootLock / HostFileBackend.identity(path.value)) else Set(rootLock)

  /**
   * Whether a folded path names one file: true on a POSIX file system. Windows also resolves 8.3 short
   * names (`PROGRA~1`) and ignores trailing dots and spaces, aliases no lexical rule can fold, so there
   * every mutation takes the root's lock and one stripe: serialised, never racing.
   */
  private val lexicalIdentity = root.getFileSystem.supportedFileAttributeViews.contains("unix")

  // ---- resolution

  private def refused(operation: String, path: VPath, reason: String): Left[BackendRefused, Nothing] =
    Left(BackendRefused(operation, path.value, reason))

  private val countsLinks = root.getFileSystem.supportedFileAttributeViews.contains("unix")

  /** How many hard links name the regular file `p`; 1 where the file system cannot say. */
  private def linkCount(p: Path): Int =
    if countsLinks then Files.getAttribute(p, "unix:nlink", LinkOption.NOFOLLOW_LINKS).asInstanceOf[Int] else 1

  // `open` probes only the root's file system for aliases, so another one mounted inside the root (an
  // SMB share with 8.3 names, say) is never entered: its identity rules are unchecked

  /** The root is still the directory `open` checked: same file key, same file system. */
  private def rootIntact(operation: String, path: VPath): Result[Unit] =
    Try {
      HostFileBackend.fileKeyOf(root) == rootKey && fileSystemId(root) == rootFileSystem
    } match
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
    Files.exists(p, LinkOption.NOFOLLOW_LINKS) && fileSystemId(p) != rootFileSystem

  /**
   * A file another name also reaches, or one on another file system: a symbolic link, a regular file
   * with more than one hard link, or a mount inside the root.
   */
  private def aliased(p: Path): Boolean =
    Files.isSymbolicLink(p) || (isFile(p) && linkCount(p) > 1) || mountedInside(p)

  /**
   * `path` under the root, refusing a symbolic link at any component that exists, a regular file with
   * other hard links (which may be outside the root, and which locks by path would not order), and the
   * backend's lock files, should the lock directory lie inside the root, and any segment named as the
   * backend's temporary files are (which listings hide, so such a file could never be found again).
   * Its file-system reads are inside [[io]], so a file that vanishes meanwhile is a refusal, not a throw.
   */
  private def resolve(operation: String, path: VPath): Result[Path] =
    rootIntact(operation, path).flatMap(_ => resolveIn(operation, path))

  private def resolveIn(operation: String, path: VPath): Result[Path] =
    if path.segments.exists(HostFileBackend.trailing) then
      refused(operation, path, "has a segment ending in a dot or a space, which Windows reads as another name")
    else if path.segments.exists(HostFileBackend.isTemporary) then
      refused(operation, path, "has a segment named as the backend's temporary files, which is refused")
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
            else Right(p)
          )
      }

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
      case Failure(_: NoSuchFileException)   => refused(operation, path, "not found")
      case Failure(_: AccessDeniedException) => refused(operation, path, "permission denied")
      case Failure(e: FileSystemException)   => refused(operation, path, Option(e.getReason).getOrElse(e.toString))
      case Failure(_: HostFileBackend.BinaryContent) =>
        refused(operation, path, "is binary (it contains NUL); only UTF-8 text is supported")
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

  /**
   * Writes `bytes` to `target` atomically: a flushed temporary file beside it, moved over it. A file it
   * replaces keeps its permissions: before any byte is written, the temporary file is given the
   * target's POSIX permissions, owner and group (checked to hold), its ACL and owner where the file system has an ACL view, and its DOS
   * hidden and system flags. Where they cannot be kept - setuid, setgid or sticky bits, an owner or group
   * the process cannot set, no permission model the JDK can copy - the write is refused, never widened.
   * A new file gets what any file created in its directory gets.
   */
  private def atomicWrite(operation: String, path: VPath, target: Path, bytes: Array[Byte]): Result[Unit] =
    HostFileBackend.Kept.of(target) match
      case Left(reason) => refused(operation, path, reason)
      case Right(kept) =>
        Try(Files.createDirectories(target.getParent)).toResult.flatMap { _ =>
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
            case Failure(e)            => Failure[Unit](e).toResult
        }

  // ---- serialisation of plan and effect across threads of this JVM

  /**
   * `body` holding the target's stripe: first the JVM's lock of that stripe, then the OS's advisory lock
   * on that stripe's file in the lock directory, so every process mutating the same or an overlapping
   * root through a backend is ordered too. One lock file per stripe, used by one thread of this JVM at a
   * time, because closing a channel can release every lock the JVM holds on its file, and an interrupt
   * while waiting closes the waiting channel.
   */
  /**
   * The stripe of a mutation of `target`: by the physical identity of the directory it is in, as the file
   * system itself resolves it - the real path of its nearest existing ancestor, folded. No lexical rule
   * can say which names one file system takes for one file (case, Unicode normalisation, Windows 8.3 names
   * and trailing dots, an SMB share mounted on Linux), but the file system can: two aliases of one target
   * are in one real directory, so they share a stripe, and the compare-and-set under it lets only one of
   * them apply. Mutations within one directory are therefore serialised across threads and processes.
   */
  private def stripeOf(target: Path): Result[Int] =
    // the nearest ancestor that resolves; one removed since it was seen is passed over (a shape race),
    // and any other failure to resolve it is an error, never a guessed stripe
    @annotation.tailrec
    def real(candidate: Option[Path]): Result[Path] = candidate match
      case None => Right(root)
      case Some(p) =>
        Try(p.toRealPath()) match
          case Success(r)                      => Right(r)
          case Failure(_: NoSuchFileException) => real(Option(p.getParent))
          case Failure(e) => Left(ProcessingError("host-lock", s"cannot resolve $p to take its stripe: $e"))
    real(Option(target.getParent)).map(r =>
      Math.floorMod(HostFileBackend.identity(r.toString).hashCode, HostFileBackend.Stripes)
    )

  /**
   * The OS lock file of a stripe: in the lock directory, shared by every backend that uses it whatever
   * its root, so backends in other processes over the same or overlapping roots - `/w` and `/w/sub` -
   * lock one file for one target.
   */
  private def lockFile(stripe: Int): Path = lockDirectory.resolve(s"stripe-$stripe.lock")

  /** The OS lock file a mutation of `path` takes; for tests that hold it from another process. */
  private[harness] def lockFileOf(path: VPath): Result[Path] =
    resolve("lock", path).flatMap(stripeOf).map(lockFile)

  private def exclusively[A](target: Path)(body: => Result[A]): Result[A] =
    stripeOf(target).flatMap(stripe => exclusivelyOn(stripe)(body))

  private def exclusivelyOn[A](stripe: Int)(body: => Result[A]): Result[A] =
    val lock = HostFileBackend.stripes(stripe)
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
            case Failure(e) if HostFileBackend.isInterrupt(e) => cancelled
            case Failure(e) => Left(ProcessingError("host-lock", s"cannot open the lock file $file: $e"))
            case Success(channel) =>
              Using.resource(channel) { open =>
                Try(open.lock()) match
                  case Failure(e) if HostFileBackend.isInterrupt(e) => cancelled
                  case Failure(e)    => Left(ProcessingError("host-lock", s"cannot lock $file: $e"))
                  case Success(held) => Using.resource(held)(_ => body)
              }
        }

  private def guarded[A: upickle.default.ReadWriter](operation: String, path: VPath, ctx: FileContext)(
    plan: Path => Result[ReplayGuard.Plan[A]]
  ): Result[(A, StateUpdate)] = whileOpen(operation, path)(guardedOpen[A](operation, path, ctx)(plan))

  private def guardedOpen[A: upickle.default.ReadWriter](operation: String, path: VPath, ctx: FileContext)(
    plan: Path => Result[ReplayGuard.Plan[A]]
  ): Result[(A, StateUpdate)] =
    // No lock is held across the guard: its intent and outcome are store commits, and a run stalled
    // between them (one that has lost its claim) must not block its successor. The effect alone is
    // locked, as a compare-and-set (casPlan), so a file another writer changed since the plan is refused,
    // never overwritten.
    // Resolution is inside the probe and the plan, which the guard runs only for an effect that is new or
    // incomplete: a completed one returns its recorded outcome whatever the file's shape has become since.
    ReplayGuard
      .verifiable[A](ctx.run, ctx.effectKey(s"file.$operation", path.value))(
        resolve(operation, path).flatMap(target => io(operation, path)(digest(operation, path, target)))
      )(resolve(operation, path).flatMap(target => io(operation, path)(plan(target))))
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
      // the target's directory exists before its stripe is chosen, so every writer of the file, whether it
      // created the directory or found it, takes the stripe of the one real directory
      io(operation, path)(Try(Files.createDirectories(target.getParent)).toResult.map(_ => ())).flatMap(_ =>
        exclusively(target) {
          // checked again at each mutation: a mount made inside the root after `open` stops it here
          unmounted(operation, path)
            .flatMap(_ => resolve(operation, path))
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
    )

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    writeOpen(path, content, ctx) // admitted once, by `guarded`

  private def writeOpen(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
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
            yield casPlan("write", path, target)(
              before,
              fileCheck(bytes),
              FileWritten(before == StateCheck.absent, bytes.length.toLong) // from the one read, as the effect reports
            )(
              atomicWrite("write", path, target, bytes).map(_ =>
                FileWritten(before == StateCheck.absent, bytes.length.toLong)
              )
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
      yield casPlan("edit", path, target)(fileCheck(bytes), fileCheck(next), FileEdited(edited._2))(
        atomicWrite("edit", path, target, next).map(_ => FileEdited(edited._2))
      )
    }

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    deleteOpen(path, ctx) // admitted once, by `guarded`

  private def deleteOpen(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
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

  /**
   * `read`, or nothing for a file `search` skips (not UTF-8, unreadable); a cancellation is never
   * skipped: it propagates, so the search ends as cancelled rather than as an incomplete success.
   */
  private def unreadableSkipped[A](path: VPath)(read: => Option[A]): Option[A] =
    Try(read) match
      case Success(result) => result
      case Failure(e) if HostFileBackend.isInterrupt(e) =>
        throw new java.util.concurrent.CancellationException(e.toString)
      // what search means to skip: content that is not text, and a file gone since the walk listed it
      case Failure(_: CharacterCodingException | _: NoSuchFileException) => None
      // anything else is a refusal of the search, never a silent gap in its result
      case Failure(_: AccessDeniedException) => throw new SearchAborted(path.value, "permission denied")
      case Failure(e: FileSystemException) =>
        throw new SearchAborted(path.value, Option(e.getReason).getOrElse(e.toString))
      case Failure(e) => throw e

  /** The listable children of `dir`, in name order: no links, no temporary files, at most `max`. */
  /**
   * The first `max` listable children of `dir` in name order, and whether there were more. A directory
   * stream has no order, so the whole directory is read, keeping only the `max + 1` least names, and
   * entries that are not listed (links, temporary files, lock files) are dropped before they
   * count. Memory is bounded by `max`; the time a huge directory takes, by the tool's timeout, as the
   * read stops when its thread is interrupted.
   */
  private def children(dir: Path, max: Int, walk: Boolean = false): (Vector[(String, Path)], Boolean) =
    // A walk visits a directory's entries ordered as the paths it will emit: a directory by its name and
    // `/`, as its descendants' paths start, so `a-foo` comes before `a/x`, as `VPath` orders them. A
    // listing orders the entries themselves, by name.
    def key(name: String, p: Path) = if walk && isDir(p) then name + "/" else name
    val least = new java.util.PriorityQueue[(String, String, Path)](
      math.min(max, 1024) + 1,
      Ordering.by[(String, String, Path), String](_._1).reverse
    )
    var eligible = 0
    Using.resource(Files.newDirectoryStream(dir)) { stream =>
      val entries = stream.iterator()
      while entries.hasNext do
        if Thread.currentThread().isInterrupted then throw new java.util.concurrent.CancellationException("interrupted")
        val p    = entries.next()
        val name = p.getFileName.toString
        // a name the backend cannot list (not a valid segment) is dropped before it counts, too
        // only what the backend can use: a regular file or a directory (no FIFO, socket or device)
        val hidden =
          VPath(s"/$name").isLeft || HostFileBackend.trailing(name) || HostFileBackend.isTemporary(name) ||
            aliased(p) || !(isFile(p) || isDir(p))
        if !hidden then
          eligible += 1
          least.add((key(name, p), name, p))
          if least.size > max then least.poll(): Unit
    }
    (least.asScala.toVector.sortBy(_._1).map((_, name, p) => name -> p), eligible > max)

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    whileOpen("ls", path)(lsOpen(path, limit))

  private def lsOpen(path: VPath, limit: Int): Result[Listing[FileInfo]] =
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
    whileOpen("read", path)(readOpen(path, offset, limit))

  private def readOpen(path: VPath, offset: Int, limit: Int): Result[FileLines] =
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
        val (entries, more) =
          children(dir, math.min(maxScanEntries.toLong - visited + 1, Int.MaxValue.toLong).toInt, walk = true)
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
    // the lease covers the whole walk, so close() waits for it to end
    whileOpen(operation, under)(
      Text
        .checkLimit(operation, under.value, limit)
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
    walked("glob", under, limit)(_.collect { case (v, _) if accept(v) => v }) // admitted once, by `walked`

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

object HostFileBackend:

  /** The largest `maxFileBytes`: a file is read into one array, which holds at most this many bytes. */
  val MaxFileBytes: Long = Int.MaxValue.toLong - 8

  /** Whether `root`'s file system reports hard-link counts (the `unix` attribute view): POSIX does, NTFS not. */
  def reportsLinkCounts(root: Path): Boolean = root.getFileSystem.supportedFileAttributeViews.contains("unix")

  /** 4 MiB: the default bound on one file. */
  val DefaultMaxFileBytes: Long = 4L * 1024 * 1024

  /** The default bound on the entries one listing or walk visits. */
  val DefaultMaxScanEntries: Int = 100_000

  /** Per-file mutual exclusion across the JVM, striped by the file's real path. */
  private val Stripes = 64

  private val stripes: Vector[ReentrantLock] = Vector.fill(Stripes)(new ReentrantLock())

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

  /** `p`'s file key (device and inode where the file system has them), without following a final link. */
  private[harness] def fileKeyOf(p: Path): Option[AnyRef] =
    Option(
      Files.readAttributes(p, classOf[java.nio.file.attribute.BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).fileKey
    )

  /** Leaves an admitted operation: the thread is no longer inside one. */
  final private case class Unnest(depth: ThreadLocal[Integer]) extends AutoCloseable:
    def close(): Unit = depth.set(0)

  private val Temporary = """\.llm4s-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.tmp""".r

  // by folded identity, so a case or normalisation alias of a temporary name is one too
  // a probe file a failed cleanup left behind is hidden and reserved like a temporary file
  private def isTemporary(name: String): Boolean =
    Temporary.matches(identity(name)) || Probe.matches(identity(name))

  private val Probe =
    """[0-9a-z]{6}-llm4s-probe-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.probe""".r

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
          // unknown only where the `unix` view is not supported; a failure to read it is a refusal (below)
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
   * one for a file lock, one for a thread, and the `CancellationException` an [[Interruptible]] match
   * throws. Each is a cancellation, never a file-system failure.
   */
  private[harness] def isInterrupt(e: Throwable): Boolean = e match
    case _: java.nio.channels.ClosedByInterruptException    => true
    case _: java.nio.channels.FileLockInterruptionException => true
    case _: InterruptedException                            => true
    case _: java.io.InterruptedIOException                  => true
    case _: java.util.concurrent.CancellationException      => true
    case _                                                  => false

  /**
   * The real roots of this JVM's open backends, with how many backends hold each. A root that contains or
   * is contained by an open one is refused: two backends would then name one file by two paths, with two
   * lock keys and possibly two stripes. The same real root is shared (its paths, locks and stripes agree).
   * Across processes this is a documented precondition only.
   */
  private object OpenRoots:
    private val held = scala.collection.mutable.Map.empty[Path, Int]

    def acquire(root: Path): Result[Unit] = synchronized {
      held.keysIterator.find(r => r != root && (r.startsWith(root) || root.startsWith(r))) match
        case Some(other) =>
          Left(
            ValidationError(
              "root",
              s"$root overlaps the root $other of another open HostFileBackend; one file must have one path"
            )
          )
        case None =>
          held.update(root, held.getOrElse(root, 0) + 1)
          Right(())
    }

    def release(root: Path): Unit = synchronized {
      held.get(root) match
        case Some(1) => held.remove(root): Unit
        case Some(n) => held.update(root, n - 1)
        case None    => ()
    }

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

  /** A name Windows reads as another (a trailing dot or space is dropped); the backend never uses one. */
  private[harness] def trailing(name: String): Boolean = name.endsWith(".") || name.endsWith(" ")

  /**
   * The aliasing a root's file system does that the backend's lexical identity cannot see, found by
   * trying it: a probe file is created in `root` and its 8.3 short name looked up; the probe is then
   * removed. `Right` lists what was found (empty: none); `Left` means the root could not be probed (for
   * example, it is read-only). Case and Unicode-normalisation aliases are not listed: the backend folds
   * them. Trailing-dot and trailing-space aliases are not either: the backend refuses such names.
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
   * existing file means the short name cannot be established (a random six-character prefix makes that
   * vanishingly rare), and the probe refuses rather than guess.
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
   * The mount points strictly beneath `root`, from Linux's `/proc/self/mountinfo` (its fifth field, octal
   * escapes decoded): a bind mount of the root's own file system has the root's device, so only the
   * mount table shows it. Empty where there is no such table (macOS has no same-device bind mounts; its
   * other mounts differ in device, which [[fileSystemIdOf]] catches).
   */
  def mountPointsBeneath(root: Path): Result[Vector[Path]] =
    val table = Path.of("/proc/self/mountinfo")
    if !Files.isReadable(table) then
      // no table is no mounts only where there is none to read; on Linux it is an unknown
      if System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux") then
        Left(
          ValidationError(
            "root",
            s"$table cannot be read, so mounts beneath $root (a bind mount makes one file two paths) cannot be checked; " +
              "pass allowUncheckedIdentity only if there are none"
          )
        )
      else Right(Vector.empty)
    else Try(Using.resource(Files.lines(table))(lines => mountPointsIn(lines.iterator.asScala, root))).toResult

  /** The mount points in `mountinfo` lines strictly beneath `root`. */
  private[harness] def mountPointsIn(lines: Iterator[String], root: Path): Vector[Path] =
    val escaped = """\\([0-7]{3})""".r
    lines
      .flatMap(_.split(' ').lift(4))
      .map(field =>
        Path.of(
          escaped.replaceAllIn(
            field,
            m => scala.util.matching.Regex.quoteReplacement(Integer.parseInt(m.group(1), 8).toChar.toString)
          )
        )
      )
      .filter(m => m.startsWith(root) && m != root)
      .toVector
      .distinct

  /**
   * Which file system `p` is on, without following a final link: its `unix:dev` where the JDK reports
   * it, else its `FileStore`. The backend never enters a path whose file system differs from the root's.
   */
  def fileSystemIdOf(p: Path): String =
    if p.getFileSystem.supportedFileAttributeViews.contains("unix") then
      s"dev:${Files.getAttribute(p, "unix:dev", LinkOption.NOFOLLOW_LINKS)}"
    else
      val store = Files.getFileStore(p)
      s"store:${store.name}:$store"

  /**
   * A backend over the existing directory `root`, resolved to its real path, keeping its OS lock files
   * in `lockDirectory` (created if absent). Give every backend that may touch the same files - in this
   * process or another, over the same or an overlapping root - one lock directory: the application
   * chooses it, as a setting, since only it knows which processes share files.
   *
   * The backend's ordering and replay assume that one path names one file. `open` checks it, and
   * refuses a root where it does not hold:
   *  - `lockDirectory` must be outside the root, both resolved to their real paths, so no path of the
   *    backend can reach a lock file;
   *  - `probe` (by default [[probeAliases]]) must find no aliasing the backend cannot fold. Supported:
   *    local POSIX file systems, case-insensitive ones included (macOS); NTFS only with the opt-in below. An
   *    SMB/CIFS share or a volume with 8.3 names on is refused: use the workspace sandbox's file backend
   *    (F2) or a `StoreFileBackend` there;
   *  - the file system must report hard-link counts (`countsLinks`, by default whether it has the
   *    `unix` attribute view), so a file with another name can be refused. NTFS reports none, so on
   *    Windows `open` is refused by default;
   *  - a root whose identity cannot be checked - it cannot be probed (read-only), or it reports no link
   *    counts - is refused, unless `allowUncheckedIdentity`: the caller then vouches that one path names
   *    one file there (no 8.3 names, no hard links);
   *  - no two backends may expose one directory, or overlapping ones, under different paths - bind
   *    mounts, symlinked mount points, nested roots - in any process. Within this JVM `open` refuses a
   *    root that contains or is contained by an open one (the same real root is shared) until that one
   *    is closed; across processes it is the caller's to keep;
   *  - another file system mounted inside the root (`fileSystemId`, by default [[fileSystemIdOf]], differs
   *    from the root's) is never entered: `open` probes only the root's, so a path at or beneath such a
   *    mount is refused and listings and searches leave it out.
   */
  def open(
    root: Path,
    lockDirectory: Path,
    maxFileBytes: Long = DefaultMaxFileBytes,
    maxScanEntries: Int = DefaultMaxScanEntries,
    probe: Path => Result[Vector[String]] = probeAliases,
    countsLinks: Path => Boolean = reportsLinkCounts,
    allowUncheckedIdentity: Boolean = false,
    fileSystemId: Path => String = fileSystemIdOf,
    mountsBeneath: Path => Result[Vector[Path]] = mountPointsBeneath
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
      for
        real <- Try(root.toRealPath()).toResult.left.map(e => ValidationError("root", s"$root: ${e.message}"): LLMError)
        _    <- Either.cond(Files.isDirectory(real), (), ValidationError("root", s"$root is not a directory"))
        locks <- Try {
          Files.createDirectories(lockDirectory)
          lockDirectory.toRealPath()
        }.toResult.left.map(e => ValidationError("lockDirectory", s"$lockDirectory: ${e.message}"): LLMError)
        _ <- Either.cond(
          !locks.startsWith(real),
          (),
          ValidationError("lockDirectory", s"$locks is inside the root $real; give the backend one outside it")
        )
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
          countsLinks(real) || allowUncheckedIdentity,
          (),
          ValidationError(
            "root",
            s"$real's file system reports no hard-link counts, so a file with another name cannot be refused " +
              "and its identity cannot be checked; pass allowUncheckedIdentity only if it has no hard links, " +
              "or use the workspace sandbox's file backend or a StoreFileBackend"
          )
        )
        // a mount table that cannot be read is waived only by `allowUncheckedIdentity`; a mount found never is
        mounts = (p: Path) =>
          mountsBeneath(p) match
            case Left(_) if allowUncheckedIdentity => Right(Vector.empty)
            case other                             => other
        _ <- mounts(real).flatMap(found =>
          Either.cond(found.isEmpty, (), ValidationError("root", mountedReason(real, found)))
        )
        identity <- Try((fileKeyOf(real), fileSystemId(real))).toResult.left
          .map(e => ValidationError("root", s"$real cannot be identified: ${e.message}"): LLMError)
        _ <- OpenRoots.acquire(real)
      yield new HostFileBackend(
        real,
        maxFileBytes,
        maxScanEntries,
        locks,
        fileSystemId,
        mounts,
        identity._1,
        identity._2
      )
