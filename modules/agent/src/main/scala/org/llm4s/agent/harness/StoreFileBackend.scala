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
 * [[StateFileBackend]]. Mutations lock `files/store/<path>/<file>` and run through `ReplayGuard.transactional`
 * under `ctx.effectKey("file.write" | "file.edit" | "file.delete", file)`, each as one `store.modify`, so
 * an edit decides "`old` was not found", and a write the tree's shape, inside the store's transaction, and a replay whose first attempt
 * landed returns the first outcome. The store's bounds hold, and an over-limit write is a tool error.
 * Listings read at most the store's `maxItems` entries.
 */
@Experimental
final class StoreFileBackend private (val store: LongTermStore, val path: Vector[String]) extends FileBackend:

  val writes: Set[StateKey[?, ?]] = Set.empty

  private val lock = FileBackend.LockRoot / "store" / path.mkString("/")

  def locks(file: VPath): Set[LockKey] = Set(lock / file.value)

  private def namespace(ctx: FileContext): Namespace = Namespace(ctx.run.config.tenantId, path)

  // exactly the shape this backend writes, `{"content": string}`: an object with more would lose its other
  // fields to an edit, so it is no file of the backend's, and corrupt stored state
  private def content(item: StoreItem): Option[String] =
    item.value.objOpt.filter(_.keySet == Set("content")).flatMap(_.get("content")).flatMap(_.strOpt)

  private val bounds =
    FileTree.Bounds(store.limits.maxItems, store.limits.maxItemBytes.toLong, store.limits.maxBytes)

  /**
   * The namespace as files, validated: every item's key a canonical `VPath` (`/a/` would read as `/a`
   * while every mutation addresses `/a`) and its value `{"content": string}`, then [[FileTree.validated]].
   * The namespace is this backend's: an item it could not have written is corrupt stored state, refused
   * with kind `Corrupt`, never skipped, and a delete of the path it names removes it.
   */
  private def filesOf(operation: String, entries: Vector[StoreEntry], more: Boolean): Result[Map[VPath, FileEntry]] =
    if more then Left(FileTree.corrupt(operation, None, s"holds more than ${store.limits.maxItems} items; delete some"))
    else
      entries
        .foldLeft[Result[Map[VPath, FileEntry]]](Right(Map.empty)) { (acc, e) =>
          acc.flatMap { files =>
            VPath(e.key).toOption.filter(_.value == e.key) match
              case None =>
                Left(
                  FileTree
                    .corrupt(operation, None, s"holds the item '${Text.quoted(e.key)}', whose key is not a file path")
                )
              case Some(path) =>
                content(e.item) match
                  case None       => Left(FileTree.corrupt(operation, Some(path), "is a store item that is not a file"))
                  case Some(text) => Right(files.updated(path, FileEntry(text, e.item.updated)))
          }
        }
        .flatMap(FileTree.validated(operation, _, bounds))

  /** The files, for a read: the one point a read takes the namespace. */
  private def all(operation: String, ctx: FileContext): Result[Map[VPath, FileEntry]] =
    store.list(namespace(ctx), "", store.limits.maxItems).flatMap(l => filesOf(operation, l.items, l.truncated))

  /** The files as `view` sees them, in the transaction: the one point a mutation but `delete` takes them. */
  private def viewed(operation: String, view: StoreView): Result[Map[VPath, FileEntry]] =
    view
      .withPrefix("", if store.limits.maxItems < Int.MaxValue then store.limits.maxItems + 1 else Int.MaxValue)
      .flatMap(es => filesOf(operation, es.take(store.limits.maxItems), es.size > store.limits.maxItems))

  def ls(dir: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    all("ls", ctx).flatMap(FileTree.ls(_, dir, limit))

  def read(file: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    all("read", ctx).flatMap(FileTree.read(_, file, offset, limit))

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    all("glob", ctx).flatMap(FileTree.find(_, under, accept, limit))

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    all("grep", ctx).flatMap(FileTree.search(_, under, regex, accept, limit))

  private def guarded[A: ReadWriter](operation: String, file: VPath, ctx: FileContext)(
    change: StoreView => Result[StoreChange[A]]
  ): Result[(A, StateUpdate)] =
    val effect = ctx.effectKey(s"file.$operation", file.value)
    ReplayGuard
      .transactional[A](ctx.run, effect, store)(token =>
        store.modify[A](namespace(ctx), file.value, effect, token)(change)
      )
      // the store names its key; the key is this backend's path, so its refusal names the path, typed
      .left
      .map {
        // the store's own refusals of the key (bounds, depth) concern this file; a Corrupt refusal of the
        // namespace as a whole names no file, and stays untyped so no composite rebases it onto one
        case r: BackendRefused if r.path.isEmpty && r.kind != BackendRefused.Kind.Corrupt =>
          BackendRefused.at(r.operation, file, r.reason, r.kind)
        case other => other
      }
      .map(a => (a, StateUpdate.empty))

  private def file(text: String): ujson.Value = ujson.Obj("content" -> text)

  private def refused(operation: String, target: VPath, reason: String) =
    Left(BackendRefused.at(operation, target, reason))

  def write(target: VPath, text: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    // the content's size first, by its length and then a capped count, before anything scans all of it
    (if Text.oversized(text, store.limits.maxItemBytes.toLong) then
       refused("write", target, s"content is larger than the store's item limit of ${store.limits.maxItemBytes} bytes")
     else Text.binaryReason(text).fold[Result[Unit]](Right(()))(r => refused("write", target, r))).flatMap { _ =>
      // decided in the store's transaction, so a concurrent write of an ancestor or descendant cannot slip
      // between the check and the write
      guarded[FileWritten]("write", target, ctx)(view =>
        viewed("write", view)
          .flatMap(FileTree.writable("write", _, target))
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
      for
        files  <- viewed("edit", view)
        _      <- FileTree.writable("edit", files, target)
        entry  <- FileTree.fileOf("edit", files, target)
        edited <- Text.edited(target, entry.content, old, replacement, replaceAll, store.limits.maxItemBytes.toLong)
      yield StoreChange.Put(file(edited._1), FileEdited(edited._2))
    }

  /**
   * Removes the item at `target`, whatever it holds: the namespace is not validated, so an item a refusal
   * names as corrupt can be deleted. A directory (items beneath) or nothing is refused.
   */
  def delete(target: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    guarded[FileDeleted]("delete", target, ctx) { view =>
      view.current match
        // whatever the item is, the root included (a file there is corrupt state, and this repairs it)
        case Some(_)                      => Right(StoreChange.Remove(FileDeleted(wasDirectory = false)))
        case None if target == VPath.root => refused("delete", target, "is the root directory")
        case None =>
          view
            .withPrefix(target.value + "/", 1)
            .flatMap(beneath =>
              refused(
                "delete",
                target,
                if beneath.nonEmpty then "is a directory; delete the files in it" else "not found"
              )
            )
    }

object StoreFileBackend:

  /**
   * The files of `store` under `path`, in the namespace of each run's tenant; `path` within the store's
   * namespace bounds (`LongTermStore.MaxNamespaceSegments` segments, `MaxNamespaceChars` characters),
   * checked before its lock key is built.
   */
  def apply(store: LongTermStore, path: Vector[String] = Vector("memories")): Result[StoreFileBackend] =
    LongTermStore.checked("files", Namespace(None, path), "").map(_ => new StoreFileBackend(store, path))
