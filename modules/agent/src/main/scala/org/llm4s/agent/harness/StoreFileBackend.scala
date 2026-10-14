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
 * an edit decides "`old` was not found", and a write the tree's shape, inside the store's transaction, and a replay whose first attempt
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
          // only a key that is its own canonical path: `/a/` would read as `/a` while every mutation
          // addresses the item `/a`, so a non-canonical key is no file of the view
          p <- VPath(e.key).toOption.filter(_.value == e.key)
          c <- content(e.item)
        yield p -> FileEntry(c, e.item.updated)
      }.toMap
    }

  def ls(dir: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    all(ctx).flatMap(FileTree.ls(_, dir, limit))

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
    change: StoreView => Result[StoreChange[A]]
  ): Result[(A, StateUpdate)] =
    val effect = ctx.effectKey(s"file.$operation", file.value)
    ReplayGuard
      .transactional[A](ctx.run, effect, store)(token =>
        store.modify[A](namespace(ctx), file.value, effect, token)(change)
      )
      .map(a => (a, StateUpdate.empty))

  private def file(text: String): ujson.Value = ujson.Obj("content" -> text)

  private def refused(operation: String, target: VPath, reason: String) =
    Left(BackendRefused(operation, target.value, reason))

  /** Whether `target` is a directory of the namespace as `view` sees it: some key lies beneath it. */
  private def isDirectory(view: StoreView, target: VPath): Result[Boolean] =
    // only the items the backend takes for files shape the tree, as only they are listed
    if target == VPath.root then Right(true)
    else
      view
        .withPrefix(target.value + "/", store.limits.maxItems)
        .map(_.exists(e => VPath(e.key).exists(_.value == e.key) && content(e.item).isDefined))

  /** Whether some ancestor of `target` is a file, so `target` would be beneath one. */
  private def beneathFile(view: StoreView, target: VPath): Result[Boolean] =
    Iterator
      .iterate(target.parent)(_.flatMap(_.parent))
      .takeWhile(_.isDefined)
      .flatten
      .foldLeft[Result[Boolean]](Right(false)) { (acc, ancestor) =>
        acc.flatMap(found =>
          if found then Right(true) else view.get(ancestor.value).map(_.exists(i => content(i).isDefined))
        )
      }

  /**
   * `target` may be written as a file: not the root, not a directory, not beneath a file. Decided in the
   * store's transaction, so a concurrent write of an ancestor or descendant cannot slip between the
   * check and the write.
   */
  private def writable(view: StoreView, target: VPath): Result[Unit] =
    if target == VPath.root then refused("write", target, "is the root directory")
    else
      for
        directory <- isDirectory(view, target)
        beneath   <- beneathFile(view, target)
        _ <-
          if view.current.exists(i => content(i).isEmpty) then
            refused("write", target, "holds a store item that is not a file")
          else if view.current.isEmpty && directory then refused("write", target, "is a directory")
          else if beneath then refused("write", target, "is beneath a file")
          else Right(())
      yield ()

  /** The current file's text, or why there is none: missing, a directory, or not a file. */
  private def currentText(operation: String, view: StoreView, target: VPath): Result[String] =
    view.current match
      // the one text rule on what is stored too: an item written past the backend may hold NUL
      case Some(item) =>
        content(item)
          .toRight(BackendRefused(operation, target.value, "is not a file"))
          .flatMap(text =>
            Text.binaryReason(text).fold(Right(text))(r => Left(BackendRefused(operation, target.value, r)))
          )
      case None =>
        isDirectory(view, target).flatMap(dir =>
          refused(operation, target, if dir then "is a directory; delete the files in it" else "not found")
        )

  def write(target: VPath, text: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    Text.binaryReason(text).fold[Result[Unit]](Right(()))(r => refused("write", target, r)).flatMap { _ =>
      guarded[FileWritten]("write", target, ctx)(view =>
        writable(view, target)
          .map(_ => StoreChange.Put(file(text), FileWritten(view.current.isEmpty, Text.utf8Bytes(text))))
      )
    }

  def edit(
    target: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] =
    guarded[FileEdited]("edit", target, ctx) { view =>
      currentText("edit", view, target)
        .flatMap(Text.edited(target, _, old, replacement, replaceAll))
        .map((next, n) => StoreChange.Put(file(next), FileEdited(n)))
    }

  def delete(target: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    guarded[FileDeleted]("delete", target, ctx)(view =>
      currentText("delete", view, target).map(_ => StoreChange.Remove(FileDeleted(wasDirectory = false)))
    )

object StoreFileBackend:

  /** The files of `store` under `path`, in the namespace of each run's tenant. */
  def apply(store: LongTermStore, path: Vector[String] = Vector("memories")): StoreFileBackend =
    new StoreFileBackend(store, path)
