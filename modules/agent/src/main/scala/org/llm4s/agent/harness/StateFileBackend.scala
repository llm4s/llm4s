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
 * the lock `state-files/<keyId>`, so the mutations of one batch apply in call order, each against the
 * state the previous one committed. That makes the bounds exact: the total bytes of paths and contents,
 * as JSON strings (what a checkpoint stores, escapes included), is at most `maxBytes`, and the number of files at most `maxFiles`, checked when the tool
 * runs (rules 1, 2, 12).
 *
 * Each checkpoint copies the whole map (rule 6), so pair the backend with a `RetentionPolicy`.
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

  private val lock = LockKey("state-files", keyId)

  val writes: Set[StateKey[?, ?]] = Set(files)

  def locks(path: VPath): Set[LockKey] = Set(lock)

  private def current(ctx: FileContext): Result[Map[VPath, FileEntry]] = ctx.state.get(files)

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    current(ctx).flatMap(FileTree.ls(_, path, limit))

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    current(ctx).flatMap(FileTree.read(_, path, offset, limit))

  // what each checkpoint stores: the path, and the content as a JSON string, escapes included
  private def entryBytes(path: VPath, content: String): Long = Text.jsonBytes(path.value) + Text.jsonBytes(content)

  /** `path` may hold `content`: text, a writable path, and within this thread's bounds. */
  private def admit(operation: String, all: Map[VPath, FileEntry], path: VPath, content: String): Result[Unit] =
    val total = all.iterator.map((p, e) => entryBytes(p, e.content)).sum
    val after = total - all.get(path).fold(0L)(e => entryBytes(path, e.content)) + entryBytes(path, content)
    val count = all.size + (if all.contains(path) then 0 else 1)
    def refuse(reason: String) = Left(BackendRefused(operation, path.value, reason))
    for
      _ <- Text.binaryReason(content).fold[Result[Unit]](Right(()))(refuse)
      _ <- FileTree.writable(operation, all, path)
      _ <-
        if after > maxBytes then refuse(s"the files would total $after bytes, over this thread's limit of $maxBytes")
        else if count > maxFiles then refuse(s"there would be $count files, over this thread's limit of $maxFiles")
        else Right(())
    yield ()

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    for
      all <- current(ctx)
      _   <- admit("write", all, path, content)
    yield (
      FileWritten(created = !all.contains(path), Text.utf8Bytes(content)),
      StateUpdate.update(files, FileUpdate.Put(path, FileEntry(content, clock.instant())))
    )

  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] =
    for
      all    <- current(ctx)
      entry  <- FileTree.fileOf("edit", all, path)
      edited <- Text.edited(path, entry.content, old, replacement, replaceAll)
      _      <- admit("edit", all, path, edited._1)
    yield (
      FileEdited(edited._2),
      StateUpdate.update(files, FileUpdate.Put(path, FileEntry(edited._1, clock.instant())))
    )

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    for
      all <- current(ctx)
      _   <- FileTree.deletable(all, path)
    yield (FileDeleted(wasDirectory = false), StateUpdate.update(files, FileUpdate.Delete(path)))

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    current(ctx).flatMap(FileTree.find(_, under, accept, limit))

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    current(ctx).flatMap(FileTree.search(_, under, regex, accept, limit))

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
