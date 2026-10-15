package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ StateKey, StateUpdate }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.time.{ Clock, Instant }
import java.util.regex.Pattern

/** One file of a [[StateFileBackend]]: UTF-8 text, and when it was last written. */
@Experimental
final case class FileEntry(content: String, modified: Instant) derives ReadWriter

object FileEntry:
  private given ReadWriter[Instant] =
    upickle.default.readwriter[String].bimap[Instant](_.toString, Instant.parse)

/** An update to a [[StateFileBackend]]'s files; both are total (rule 2). */
@Experimental
enum FileUpdate derives ReadWriter:

  /** Writes `path`, replacing any entry. */
  case Put(path: VPath, entry: FileEntry)

  /** Removes `path`; a no-op when it is absent. */
  case Delete(path: VPath)

/**
 * The default [[FileBackend]]: files in thread state, under one key of its own (`files`, id `keyId`).
 * They are committed with the call that wrote them, so they are part of every checkpoint, of `history`
 * and of `fork`, and a mutation needs no replay guard: its effect is the update the runtime commits.
 *
 * Directories are implicit: a path is a directory when an entry lies beneath it. Every mutation holds
 * the lock `files/state/<keyId>`, so the mutations of one batch apply in call order, each against the
 * state the previous one committed. That makes the bounds exact: the total bytes of paths and contents,
 * as JSON strings (what a checkpoint stores, escapes included), is at most `maxBytes`, and the number of files at most `maxFiles`, checked when the tool
 * runs (rules 1, 2, 12).
 *
 * Each checkpoint copies the whole map (rule 6), so pair the backend with a `RetentionPolicy`.
 *
 * The lock is what keeps two edits of one file in a batch from losing one, and the bounds exact: the
 * calls of a batch otherwise share one snapshot. Adding tools over this backend to an existing agent adds
 * the `files` key and the loop's `tool-queue`: a thread at rest continues with no files, and one in flight
 * is refused until it is finished (§4.29). The last file's delete removes the key's entry, so taking the
 * tools out again refuses only a thread that still holds files.
 */
@Experimental
final class StateFileBackend private (
  keyId: String,
  val maxBytes: Long,
  val maxFiles: Int,
  clock: Clock
) extends FileBackend:

  /** The key the files live in. */
  val files: StateKey[Map[VPath, FileEntry], FileUpdate] =
    StateKey[Map[VPath, FileEntry], FileUpdate](keyId, Map.empty) { (current, update) =>
      update match
        case FileUpdate.Put(path, entry) => Right(current.updated(path, entry))
        case FileUpdate.Delete(path)     => Right(current - path)
    }

  private val lock = FileBackend.LockRoot / "state" / keyId

  val writes: Set[StateKey[?, ?]] = Set(files)

  def locks(path: VPath): Set[LockKey] = Set(lock)

  // the files as this call has left them so far (FileContext.latest), so its operations see each other
  private def current(ctx: FileContext): Result[Map[VPath, FileEntry]] = ctx.latest(files)

  private def advanced[A](ctx: FileContext, next: Map[VPath, FileEntry], result: A, update: StateUpdate) =
    ctx.advance(files, next)
    (result, update)

  private val bounds = FileTree.Bounds(maxFiles, maxBytes, maxBytes)

  /**
   * The files, validated ([[FileTree.validated]]): the one point every operation but `delete` reads the
   * key, so none of them works on a map another writer of the key left outside this backend's bounds or
   * shape. A `delete` reads the key as it is, so the entry a refusal names can be removed.
   */
  private def valid(operation: String, ctx: FileContext): Result[Map[VPath, FileEntry]] =
    current(ctx).flatMap(FileTree.validated(operation, _, bounds))

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    valid("ls", ctx).flatMap(FileTree.ls(_, path, limit))

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    valid("read", ctx).flatMap(FileTree.read(_, path, offset, limit))

  // what each checkpoint stores: the path, and the content as a JSON string, escapes included
  private def entryBytes(path: VPath, content: String): Long = Text.jsonBytes(path.value) + Text.jsonBytes(content)

  /** `path` may hold `content` in the valid map `all`: text, a writable path, and within the bounds. */
  private def admit(operation: String, all: Map[VPath, FileEntry], path: VPath, content: String): Result[Unit] =
    val count                  = all.size + (if all.contains(path) then 0 else 1)
    def refuse(reason: String) = Left(BackendRefused.at(operation, path, reason))
    lazy val after =
      all.iterator.filter(_._1 != path).map((p, e) => entryBytes(p, e.content)).sum + entryBytes(path, content)
    for
      _ <- Text.binaryReason(content).fold[Result[Unit]](Right(()))(refuse)
      _ <- FileTree.writable(operation, all, path)
      _ <-
        if count > maxFiles then refuse(s"there would be $count files, over this thread's limit of $maxFiles")
        else if after > maxBytes then
          refuse(s"the files would total $after bytes, over this thread's limit of $maxBytes")
        else Right(())
    yield ()

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    for
      all <- valid("write", ctx)
      _   <- admit("write", all, path, content)
    yield
      val entry = FileEntry(content, clock.instant())
      advanced(
        ctx,
        all.updated(path, entry),
        FileWritten(created = !all.contains(path), Text.utf8Bytes(content)),
        StateUpdate.update(files, FileUpdate.Put(path, entry))
      )

  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] =
    for
      all    <- valid("edit", ctx)
      entry  <- FileTree.fileOf("edit", all, path)
      edited <- Text.edited(path, entry.content, old, replacement, replaceAll, maxBytes)
      _      <- admit("edit", all, path, edited._1)
    yield
      val next = FileEntry(edited._1, clock.instant())
      advanced(
        ctx,
        all.updated(path, next),
        FileEdited(edited._2),
        StateUpdate.update(files, FileUpdate.Put(path, next))
      )

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    for
      // the key as it is, not validated: a delete is how a corrupt entry is repaired
      all <- current(ctx)
      _   <- FileTree.deletable(all, path)
    // the last file's delete removes the key's entry, so the backend can later be taken out of an agent
    // whose threads no longer hold files (§4.29); ordered by the lock, so `all` is exact
    yield advanced(
      ctx,
      all - path,
      FileDeleted(wasDirectory = false),
      if all.size == 1 then StateUpdate.remove(files) else StateUpdate.update(files, FileUpdate.Delete(path))
    )

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    valid("glob", ctx).flatMap(FileTree.find(_, under, accept, limit))

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    valid("grep", ctx).flatMap(FileTree.search(_, under, regex, accept, limit))

object StateFileBackend:

  /** 1 MiB: the default bound on a thread's files. */
  val DefaultMaxBytes: Long = 1024L * 1024

  /** The default bound on a thread's file count. */
  val DefaultMaxFiles: Int = 1000

  /**
   * A backend over the key `keyId`, bounded by `maxBytes` and `maxFiles`; `clock` stamps `modified`.
   * Build it once, with the agent: its key is registered when the graph is built.
   */
  def apply(
    keyId: String = "files",
    maxBytes: Long = DefaultMaxBytes,
    maxFiles: Int = DefaultMaxFiles,
    clock: Clock = Clock.systemUTC()
  ): StateFileBackend =
    require(maxBytes > 0, s"maxBytes must be positive, was $maxBytes")
    require(maxFiles > 0, s"maxFiles must be positive, was $maxFiles")
    new StateFileBackend(keyId, maxBytes, maxFiles, clock)
