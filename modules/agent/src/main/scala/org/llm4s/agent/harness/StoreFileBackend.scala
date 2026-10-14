package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ ReplayGuard, StateKey, StateUpdate }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.annotation.Experimental
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.util.regex.Pattern

/**
 * A [[LongTermStore]] namespace as files, for `/memories/`: what the agent writes there outlives the
 * thread. Each run uses `Namespace(<the run's tenant>, path)`, so no run can reach another tenant's
 * files, whatever the application configures (rule 8).
 *
 * A file is an item keyed by its path, with value `{"content": ...}`; directories are implicit, as in
 * [[StateFileBackend]]. Mutations lock `store/<path>/<file>` and run through `ReplayGuard.transactional`
 * under `ctx.effectKey("file.write" | "file.edit" | "file.delete", file)`, each as one `store.modify`, so
 * an edit decides "`old` was not found" inside the store's transaction, and a replay whose first attempt
 * landed returns the first outcome. The store's bounds hold, and an over-limit write is a tool error.
 * Listings read at most the store's `maxItems` entries.
 */
@Experimental
final class StoreFileBackend private (val store: LongTermStore, val path: Vector[String]) extends FileBackend:

  val writes: Set[StateKey[?, ?]] = Set.empty

  private val lock = LockKey("store", path.mkString("/"))

  def locks(file: VPath): Set[LockKey] = Set(lock / file.value)

  private def namespace(ctx: FileContext): Namespace = Namespace(ctx.run.config.tenantId, path)

  private def content(item: StoreItem): Option[String] = item.value.objOpt.flatMap(_.get("content")).flatMap(_.strOpt)

  /** Every file of the namespace; an item that is not a file, or whose key is not a path, is skipped. */
  private def all(ctx: FileContext): Result[Map[VPath, FileEntry]] =
    store.list(namespace(ctx), "/", store.limits.maxItems).map { listing =>
      listing.items.flatMap { e =>
        for
          p <- VPath(e.key).toOption
          c <- content(e.item)
        yield p -> FileEntry(c, e.item.updated)
      }.toMap
    }

  def ls(dir: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] = all(ctx).flatMap(FileTree.ls(_, dir, limit))

  def read(file: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    all(ctx).flatMap(FileTree.read(_, file, offset, limit))

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    all(ctx).flatMap(FileTree.find(_, under, accept, limit))

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    all(ctx).flatMap(FileTree.search(_, under, regex, accept, limit))

  private def guarded[A: ReadWriter](operation: String, file: VPath, ctx: FileContext)(
    change: Option[StoreItem] => Result[StoreChange[A]]
  ): Result[(A, StateUpdate)] =
    val effect = ctx.effectKey(s"file.$operation", file.value)
    ReplayGuard
      .transactional[A](ctx.run, effect, store)(store.modify[A](namespace(ctx), file.value, effect)(change))
      .map(a => (a, StateUpdate.empty))

  private def file(text: String): ujson.Value = ujson.Obj("content" -> text)

  /**
   * The tree's shape - not the root, not a directory, not beneath a file - is checked against the
   * namespace before the transaction: a write's own replay never changes it, and within a batch the
   * path's lock orders it.
   */
  def write(target: VPath, text: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    for
      _ <- Text.binaryReason(text).fold[Result[Unit]](Right(()))(r => Left(BackendRefused("write", target.value, r)))
      files <- all(ctx)
      _     <- FileTree.writable("write", files, target)
      done <- guarded[FileWritten]("write", target, ctx)(current =>
        Right(StoreChange.Put(file(text), FileWritten(current.isEmpty, Text.utf8Bytes(text))))
      )
    yield done

  def edit(
    target: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] =
    guarded[FileEdited]("edit", target, ctx) {
      case None => Left(BackendRefused("edit", target.value, "not found"))
      case Some(item) =>
        content(item)
          .toRight(BackendRefused("edit", target.value, "is not a file"))
          .flatMap(Text.edited(target, _, old, replacement, replaceAll))
          .map((next, n) => StoreChange.Put(file(next), FileEdited(n)))
    }

  def delete(target: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    all(ctx).flatMap { files =>
      // a directory stays one under this delete's own replay; a missing file is decided in the transaction
      if !files.contains(target) && FileTree.isDirectory(files, target) then
        Left(BackendRefused("delete", target.value, "is a directory; delete the files in it"))
      else
        guarded[FileDeleted]("delete", target, ctx) {
          case Some(_) => Right(StoreChange.Remove(FileDeleted(wasDirectory = false)))
          case None    => Left(BackendRefused("delete", target.value, "not found"))
        }
    }

object StoreFileBackend:

  /** The files of `store` under `path`, in the namespace of each run's tenant. */
  def apply(store: LongTermStore, path: Vector[String] = Vector("memories")): StoreFileBackend =
    new StoreFileBackend(store, path)
