package org.llm4s.agent.harness.workspace

import org.llm4s.agent.graph.{ ReplayGuard, ReplaySupport, StateCheck, StateKey, StateUpdate }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.agent.harness.*
import org.llm4s.annotation.Experimental
import org.llm4s.shared.{ FileSnapshotResponse, WorkspaceAgentInterface }
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.time.Instant
import java.util.regex.Pattern
import scala.util.Try

/**
 * A containerised workspace's files as a [[FileBackend]] (design §4.33): `/src/a.txt` is the workspace's
 * `src/a.txt`, `/` its root. One workspace is one tenant's: an application serving several builds one
 * backend per tenant and picks it with `FileBackend.resolved` (rule 8).
 *
 *  - '''Order.''' A mutation locks the whole workspace, `files/workspace/<id>` ([[WorkspaceLocks]]), as a
 *    command there does, so a batch's edits and commands apply in call order, even two paths that name one
 *    file through a link. Reads take no lock and see the workspace as it is.
 *  - '''Replay.''' The target is `Verifiable` (C4). Each mutation runs `ReplayGuard.verifiable` under
 *    `ctx.effectKey("file.write" | "file.edit" | "file.delete", path)`: the plan reads the file once, makes
 *    every refusal, and plans a compare-and-set against the hash it read, which the runner applies atomically
 *    under its mutation lock and replaces the file atomically. So a replayed edit that landed returns its
 *    first outcome, one that did not runs again, and one whose file another writer changed is refused.
 *  - '''Bounds.''' Content and an edit's result at most `maxFileBytes` UTF-8 bytes, checked before they are
 *    sent; a read at most the runner's `maxFileSize`; listings at most the runner's `maxDirectoryEntries`,
 *    then `limit`. A listing the runner cut is `truncated`, its entries those the runner returned, sorted.
 *  - '''Block.''' A workspace effect stays when a Block retracts its turn (rule 5).
 *
 * Listings show what the runner's default exclusions (`.git`, `node_modules`, ...) leave, and skip entries
 * whose names are not valid paths. `grep` reads each file whole, as text: a file that is not UTF-8 or is over
 * the runner's size limit is skipped and the listing marked `truncated`.
 */
@Experimental
final class WorkspaceFileBackend private (val id: String, workspace: WorkspaceAgentInterface, val maxFileBytes: Long)
    extends FileBackend:

  import WorkspaceCalls.{ call, relative }

  /** C4's class for this target. */
  val replaySupport: ReplaySupport = ReplaySupport.Verifiable

  val writes: Set[StateKey[?, ?]] = Set.empty

  // the whole workspace, whatever the path: two paths can name one file through a link
  def locks(path: VPath): Set[LockKey] = Set(WorkspaceLocks.workspaceLock(id))

  /** What is at `path`; with `follow = false` a link is described as itself (a delete removes the link). */
  private def snapshot(
    operation: String,
    path: VPath,
    content: Boolean,
    follow: Boolean = true
  ): Result[FileSnapshotResponse] =
    call(operation, path)(workspace.fileSnapshot(relative(path), content, follow))

  /**
   * A runner path (`src/a.txt`, from the Linux runner) as a `VPath`, if it is one. A backslash is a character of a
   * name there, not a separator, and a `VPath` cannot hold one, so such an entry is skipped, never rewritten into a
   * different path.
   */
  private def vpath(runnerPath: String): Option[VPath] =
    val normalised = runnerPath.stripPrefix("./")
    if normalised.isEmpty || normalised == "." then Some(VPath.root)
    else if normalised.contains('\\') then None
    else VPath("/" + normalised).toOption

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    Text.checkLimitAt("ls", path, limit).flatMap { _ =>
      call("ls", path)(workspace.exploreFiles(relative(path), returnMetadata = Some(true))).map { response =>
        val entries = response.files.flatMap { e =>
          vpath(e.path).filter(p => p != path && p.parent.contains(path)).map { p =>
            val modified = e.metadata.flatMap(m => Try(Instant.parse(m.lastModified)).toOption)
            FileInfo(p, e.isDirectory, if e.isDirectory then 0L else e.metadata.fold(0L)(_.size), modified)
          }
        }
        val listing = Listing.of(entries.sortBy(_.path).iterator, limit)
        listing.copy(truncated = listing.truncated || response.isTruncated)
      }
    }

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    for
      _        <- Text.checkLimitAt("read", path, limit)
      _        <- Either.cond(offset >= 0, (), BackendRefused.at("read", path, s"offset must be >= 0, was $offset"))
      snapshot <- snapshot("read", path, content = true)
      text     <- textOf("read", path, snapshot)
    yield
      val lines = Text.lines(text)
      FileLines(Text.page(lines, offset, limit), offset, lines.size)

  /** The text of the file `snapshot` read, or the refusal of what it is instead. */
  private def textOf(operation: String, path: VPath, snapshot: FileSnapshotResponse): Result[String] =
    snapshot.kind match
      case FileSnapshotResponse.Kind.Absent => Left(BackendRefused.notFound(operation, path))
      // a link to nothing: there is no file to read or edit, though a delete removes the link
      case FileSnapshotResponse.Kind.Dangling  => Left(BackendRefused.notFound(operation, path))
      case FileSnapshotResponse.Kind.Directory => Left(BackendRefused.at(operation, path, "is a directory"))
      case _ =>
        snapshot.content.toRight(
          BackendRefused.at(operation, path, snapshot.textError.getOrElse("the file's content is not available"))
        )

  /** Every file within `under` the runner lists, sorted, and whether the runner cut the walk. */
  private def files(operation: String, under: VPath): Result[(Vector[VPath], Boolean)] =
    call(operation, under)(
      workspace.exploreFiles(relative(under), recursive = Some(true), maxDepth = Some(VPath.MaxBytes / 2))
    ) match
      case Right(response) =>
        Right(
          (response.files.filterNot(_.isDirectory).flatMap(e => vpath(e.path)).toVector.sorted, response.isTruncated)
        )
      // as a state backend: nothing within a missing directory, and a file is within itself
      case Left(r: BackendRefused) if r.kind == BackendRefused.Kind.NotFound      => Right((Vector.empty, false))
      case Left(r: BackendRefused) if r.kind == BackendRefused.Kind.NotADirectory =>
        // only a regular file is within itself: a FIFO or socket (or a link to one), which every content operation
        // refuses, is no file a listing names
        call(operation, under)(workspace.fileSnapshot(relative(under), includeContent = false)) match
          case Right(s) if s.kind == FileSnapshotResponse.Kind.File => Right((Vector(under), false))
          case Right(_) | Left(_: BackendRefused)                   => Right((Vector.empty, false))
          case Left(other)                                          => Left(other)
      case Left(other) => Left(other)

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    Text
      .checkLimitAt("glob", under, limit)
      .flatMap(_ =>
        files("glob", under).map { (all, cut) =>
          val listing = Listing.of(all.iterator.filter(accept), limit)
          listing.copy(truncated = listing.truncated || cut)
        }
      )

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    Text
      .checkLimitAt("grep", under, limit)
      .flatMap(_ =>
        files("grep", under).flatMap { (all, cut) =>
          // file by file, stopping once one more match than the limit is found, so no file past it is read
          val found      = Vector.newBuilder[GrepMatch]
          var count      = 0L
          var skipped    = false
          var failure    = Option.empty[org.llm4s.error.LLMError]
          val candidates = all.iterator.filter(accept)
          while failure.isEmpty && count <= limit.toLong && candidates.hasNext do
            val path = candidates.next()
            snapshot("grep", path, content = true) match
              // a refusal about the entry itself (a FIFO, a socket, gone since the walk, a path the sandbox refuses) skips
              // it, the listing marked truncated; a workspace-wide one (busy) or any failure stops the search
              case Left(r: BackendRefused) if r.path.isDefined => skipped = true
              case Left(error)                                 => failure = Some(error)
              case Right(s) =>
                s.content match
                  // not searched - not text, too large, or no longer a file since the walk: matches may be missing
                  case None => skipped = true
                  case Some(text) =>
                    val wanted = math.min(limit.toLong + 1 - count, Int.MaxValue.toLong).toInt
                    Text.interruptibly("grep")(Right(Text.matches(path, text, regex).take(wanted).toVector)) match
                      case Left(error) => failure = Some(error)
                      case Right(matches) =>
                        found ++= matches
                        count += matches.size
          failure.toLeft(()).map { _ =>
            val listing = Listing.of(found.result().iterator, limit)
            listing.copy(truncated = listing.truncated || cut || skipped)
          }
        }
      )

  /**
   * `plan`'s effect through C4's `Verifiable` guard under `ctx.effectKey("file.<operation>", path)`; the
   * probe reads the file's current hash.
   */
  private def guarded[A: ReadWriter](operation: String, path: VPath, ctx: FileContext, follow: Boolean = true)(
    plan: => Result[ReplayGuard.Plan[A]]
  ): Result[(A, StateUpdate)] =
    val key = ctx.effectKey(s"file.$operation", path.value)
    ReplayGuard
      .verifiable[A](ctx.run, key)(
        snapshot(operation, path, content = false, follow).flatMap(s => WorkspaceCalls.stateCheck(s.hash))
      )(plan)
      .map(a => (a, StateUpdate.empty))

  private def checkedContent(operation: String, path: VPath, content: String): Result[Unit] =
    // its length first, so an oversized content is refused before anything scans all of it
    if path == VPath.root then Left(BackendRefused.at(operation, path, "is the root directory"))
    else if content.length.toLong > maxFileBytes || Text.utf8Bytes(content) > maxFileBytes then
      Left(BackendRefused.at(operation, path, s"content is larger than the limit of $maxFileBytes bytes"))
    else Text.binaryReason(content).map(r => BackendRefused.at(operation, path, r)).toLeft(())

  /** The compare-and-set write of `content` over what `before` hashed. */
  private def casWrite(operation: String, path: VPath, content: String, before: FileSnapshotResponse) =
    call(operation, path)(
      workspace.writeFile(relative(path), content, Some("overwrite"), Some(true), Some(before.hash))
    )

  private def notDirectory(operation: String, path: VPath, snapshot: FileSnapshotResponse): Result[Unit] =
    if snapshot.kind == FileSnapshotResponse.Kind.Directory then
      Left(BackendRefused.at(operation, path, "is a directory"))
    else Right(())

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    checkedContent("write", path, content).flatMap { _ =>
      guarded[FileWritten]("write", path, ctx) {
        for
          before <- snapshot("write", path, content = false)
          _      <- notDirectory("write", path, before)
          check  <- WorkspaceCalls.stateCheck(before.hash)
        yield
          // a write through a link to nothing creates the file it names
          val created =
            before.kind == FileSnapshotResponse.Kind.Absent || before.kind == FileSnapshotResponse.Kind.Dangling
          val written = FileWritten(created, Text.utf8Bytes(content))
          ReplayGuard.Plan(check, StateCheck.of(content), written)(
            casWrite("write", path, content, before).map(_ => written)
          )
      }
    }

  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] =
    guarded[FileEdited]("edit", path, ctx) {
      for
        before <- snapshot("edit", path, content = true)
        text   <- textOf("edit", path, before)
        edited <- Text.edited(path, text, old, replacement, replaceAll, maxFileBytes)
        _      <- checkedContent("edit", path, edited._1)
        check  <- WorkspaceCalls.stateCheck(before.hash)
      yield
        val outcome = FileEdited(edited._2)
        ReplayGuard.Plan(check, StateCheck.of(edited._1), outcome)(
          casWrite("edit", path, edited._1, before).map(_ => outcome)
        )
    }

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    if path == VPath.root then Left(BackendRefused.at("delete", path, "is the root directory"))
    else
      // a link is deleted as itself, never through: its own state is what the delete compares
      guarded[FileDeleted]("delete", path, ctx, follow = false) {
        for
          before <- snapshot("delete", path, content = false, follow = false)
          _ <-
            if before.kind == FileSnapshotResponse.Kind.Absent then Left(BackendRefused.notFound("delete", path))
            else Right(())
          check <- WorkspaceCalls.stateCheck(before.hash)
        yield
          val outcome = FileDeleted(before.kind == FileSnapshotResponse.Kind.Directory)
          ReplayGuard.Plan(check, StateCheck.absent, outcome)(
            call("delete", path)(workspace.deleteFile(relative(path), Some(before.hash))).map(_ => outcome)
          )
      }

object WorkspaceFileBackend:

  /** 1 MiB: the default bound on a file's content, the runner's default `maxFileSize`. */
  val DefaultMaxFileBytes: Long = 1024L * 1024L

  /** The files of `workspace`, named `id` in lock keys ([[WorkspaceLocks]]). */
  def apply(
    id: String,
    workspace: WorkspaceAgentInterface,
    maxFileBytes: Long = DefaultMaxFileBytes
  ): Result[WorkspaceFileBackend] =
    WorkspaceLocks
      .checkedId(id)
      .flatMap(_ =>
        Either.cond(
          maxFileBytes >= 1 && maxFileBytes <= Text.MaxLength.toLong,
          new WorkspaceFileBackend(id, workspace, maxFileBytes),
          org.llm4s.error.ValidationError("maxFileBytes", s"must be in 1..${Text.MaxLength}, was $maxFileBytes")
        )
      )
