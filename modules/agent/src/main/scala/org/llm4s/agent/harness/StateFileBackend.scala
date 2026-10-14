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
 * state the previous one committed. That makes the bounds exact: the total UTF-8 bytes of paths and
 * contents is at most `maxBytes`, and the number of files at most `maxFiles`, checked when the tool
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

  private def isDirectory(all: Map[VPath, FileEntry], path: VPath): Boolean =
    path == VPath.root || all.keysIterator.exists(p => p != path && p.isWithin(path))

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    for
      _   <- Text.checkLimit("ls", path.value, limit)
      all <- current(ctx)
      _ <-
        if all.contains(path) then Left(BackendRefused("ls", path.value, "is a file, not a directory"))
        else if !isDirectory(all, path) then Left(BackendRefused("ls", path.value, "not found"))
        else Right(())
    yield
      val children = all.toVector.flatMap { (p, entry) =>
        p.relativeTo(path).filter(_.nonEmpty).map { rel =>
          val first = rel.takeWhile(_ != '/')
          val child = (path / first).toOption.get
          if rel.contains('/') then FileInfo(child, isDirectory = true, 0L, None)
          else FileInfo(child, isDirectory = false, Text.utf8Bytes(entry.content), Some(entry.modified))
        }
      }.distinct
      Listing.of(children.sortBy(_.path).iterator, limit)

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    for
      _     <- Text.checkLimit("read", path.value, limit)
      _     <- Either.cond(offset >= 0, (), BackendRefused("read", path.value, s"offset must be >= 0, was $offset"))
      all   <- current(ctx)
      entry <- fileOf("read", all, path)
    yield
      val lines = Text.lines(entry.content)
      FileLines(lines.slice(offset, offset + limit), offset, lines.size)

  private def fileOf(operation: String, all: Map[VPath, FileEntry], path: VPath): Result[FileEntry] =
    all.get(path) match
      case Some(entry)                     => Right(entry)
      case None if isDirectory(all, path) => Left(BackendRefused(operation, path.value, "is a directory"))
      case None                            => Left(BackendRefused(operation, path.value, "not found"))

  private def entryBytes(path: VPath, content: String): Long = Text.utf8Bytes(path.value) + Text.utf8Bytes(content)

  /** `path` may hold `content`: not the root, not over a directory or beneath a file, and within bounds. */
  private def admit(operation: String, all: Map[VPath, FileEntry], path: VPath, content: String): Result[Unit] =
    val total = all.iterator.map((p, e) => entryBytes(p, e.content)).sum
    val after = total - all.get(path).fold(0L)(e => entryBytes(path, e.content)) + entryBytes(path, content)
    val count = all.size + (if all.contains(path) then 0 else 1)
    def refuse(reason: String) = Left(BackendRefused(operation, path.value, reason))
    Text.binaryReason(content) match
      case Some(reason)                                        => refuse(reason)
      case None if path == VPath.root                          => refuse("is the root directory")
      case None if !all.contains(path) && isDirectory(all, path) => refuse("is a directory")
      case None if Iterator.iterate(path.parent)(_.flatMap(_.parent)).takeWhile(_.isDefined).flatten.exists(all.contains) =>
        refuse("is beneath a file")
      case None if after > maxBytes =>
        refuse(s"the files would total $after bytes, over this thread's limit of $maxBytes")
      case None if count > maxFiles => refuse(s"there would be $count files, over this thread's limit of $maxFiles")
      case None                     => Right(())

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
      all         <- current(ctx)
      entry       <- fileOf("edit", all, path)
      edited      <- Text.edited(path, entry.content, old, replacement, replaceAll)
      _           <- admit("edit", all, path, edited._1)
    yield (FileEdited(edited._2), StateUpdate.update(files, FileUpdate.Put(path, FileEntry(edited._1, clock.instant()))))

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    for
      all <- current(ctx)
      _ <-
        if all.contains(path) then Right(())
        else if isDirectory(all, path) then
          Left(BackendRefused("delete", path.value, "is a directory; delete the files in it"))
        else Left(BackendRefused("delete", path.value, "not found"))
    yield (FileDeleted(wasDirectory = false), StateUpdate.update(files, FileUpdate.Delete(path)))

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    for
      _   <- Text.checkLimit("glob", under.value, limit)
      all <- current(ctx)
    yield Listing.of(all.keysIterator.filter(p => p.isWithin(under) && accept(p)).toVector.sorted.iterator, limit)

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    for
      _   <- Text.checkLimit("grep", under.value, limit)
      all <- current(ctx)
      found <- Text.interruptibly("grep") {
        val paths = all.keysIterator.filter(p => p.isWithin(under) && accept(p)).toVector.sorted
        Right(Listing.of(paths.iterator.flatMap(p => Text.matches(p, all(p).content, regex)), limit))
      }
    yield found

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
