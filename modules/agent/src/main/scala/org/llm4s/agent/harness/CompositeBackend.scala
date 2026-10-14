package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ StateKey, StateUpdate }
import org.llm4s.agent.graph.tool.LockKey
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, ValidationError }
import org.llm4s.types.Result

import java.util.regex.Pattern

/**
 * Several backends as one tree: each route mounts a backend at a path, and a path goes to the backend
 * of the longest mount it is within, segment by segment, or to `default`. The routed backend sees the
 * path relative to its mount (`/memories/notes.md` is `/notes.md` to the backend mounted at
 * `/memories`), so a backend means the same at any mount.
 *
 * Mutations, their locks and their replay are the routed backend's. `ls` adds each mount beneath the
 * directory as a directory; `find` and `search` (so `glob` and `grep`) span every backend beneath
 * `under`, and a path of one backend that a deeper mount shadows is never listed.
 */
@Experimental
final class CompositeBackend private (default: FileBackend, routes: Vector[(VPath, FileBackend)]) extends FileBackend:

  // deepest first, so the first mount a path is within is its longest
  private val mounts: Vector[(VPath, FileBackend)] =
    routes.sortBy((mount, _) => -mount.segments.size) :+ (VPath.root -> default)

  val writes: Set[StateKey[?, ?]] = mounts.iterator.flatMap(_._2.writes).toSet

  final private case class Routed(mount: VPath, backend: FileBackend, path: VPath)

  private def route(path: VPath): Routed =
    val (mount, backend) = mounts.find((m, _) => path.isWithin(m)).get // the root mount takes every path
    Routed(mount, backend, path.rebase(mount, VPath.root).get)

  /**
   * Whether `path` is a strict ancestor of a mount: a directory of the composite, whatever the backend it
   * routes to holds there. It is never read, written or deleted as a file, and never listed as one.
   */
  private def holdsMount(path: VPath): Boolean = routes.exists((m, _) => m != path && m.isWithin(path))

  /** `path` (a path of the backend mounted at `mount`) as a composite path, unless a mount shadows it. */
  private def visible(mount: VPath, path: VPath): Option[VPath] =
    path.rebase(VPath.root, mount).filter(cp => route(cp).mount == mount && !holdsMount(cp))

  /** `body` on `path` as a file, unless `path` holds a mount. */
  private def asFile[A](operation: String, path: VPath)(body: Routed => Result[A]): Result[A] =
    if holdsMount(path) then Left(BackendRefused(operation, path.value, "is a directory"))
    else
      val routed = route(path)
      renamed(routed, path)(body(routed))

  /** A routed backend's refusal, naming the composite path rather than the routed one. */
  private def renamed[A](routed: Routed, path: VPath)(result: Result[A]): Result[A] =
    result.left.map {
      case r: BackendRefused if r.target == routed.path.value => r.copy(target = path.value)
      case other                                              => other
    }

  def locks(path: VPath): Set[LockKey] =
    val routed = route(path)
    routed.backend.locks(routed.path)

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    val routed = route(path)
    // a mount beneath `path` is a directory of it, even where the routed backend has nothing
    val mounted = routes.flatMap { (m, _) =>
      m.relativeTo(path).filter(_.nonEmpty).map(rel => (path / rel.takeWhile(_ != '/')).toOption.get)
    }.distinct
    // the fallback covers only the routed backend's refusal of the path itself (absent, or a file it
    // holds where a mount makes a directory), never an invalid argument, which is checked first
    val base: Result[Listing[FileInfo]] = Text.checkLimit("ls", path.value, limit).flatMap { _ =>
      routed.backend.ls(routed.path, limit, ctx) match
        case Left(r: BackendRefused) if mounted.nonEmpty && r.target == routed.path.value =>
          Right(Listing(Vector.empty, truncated = false))
        case other => renamed(routed, path)(other)
    }
    base.map { listing =>
      val own = listing.items.flatMap(i =>
        visible(routed.mount, i.path).map(cp => FileInfo(cp, i.isDirectory, i.size, i.modified))
      )
      val merged = (own.filterNot(i => mounted.contains(i.path)) ++
        mounted.map(d => FileInfo(d, isDirectory = true, 0L, None))).sortBy(_.path)
      Listing(merged.take(limit), listing.truncated || merged.size > limit)
    }

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    asFile("read", path)(routed => routed.backend.read(routed.path, offset, limit, ctx))

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    asFile("write", path)(routed => routed.backend.write(routed.path, content, ctx))

  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] =
    asFile("edit", path)(routed => routed.backend.edit(routed.path, old, replacement, replaceAll, ctx))

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    asFile("delete", path)(routed => routed.backend.delete(routed.path, ctx))

  /** Each backend `under` reaches, with the path to ask it under: `under`'s own, then every mount beneath. */
  private def spanned(under: VPath): Vector[Routed] =
    val own = route(under)
    own +: routes.collect {
      case (m, backend) if m != own.mount && m.isWithin(under) && m != under => Routed(m, backend, VPath.root)
    }

  /** Each spanned backend's results, rebased onto composite paths and merged in order. */
  private def spanning[A](under: VPath, limit: Int, pathOf: A => VPath, rebased: (A, VPath) => A)(
    query: (Routed, VPath => Option[VPath]) => Result[Listing[A]]
  ): Result[Listing[A]] =
    spanned(under)
      .foldLeft[Result[(Vector[A], Boolean)]](Right((Vector.empty, false))) { (acc, routed) =>
        for
          sofar   <- acc
          listing <- renamed(routed, under)(query(routed, p => visible(routed.mount, p)))
        yield (
          sofar._1 ++ listing.items.flatMap(a => visible(routed.mount, pathOf(a)).map(cp => rebased(a, cp))),
          sofar._2 || listing.truncated
        )
      }
      .map { (items, truncated) =>
        val sorted = items.sortBy(pathOf)
        Listing(sorted.take(limit), truncated || sorted.size > limit)
      }

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    spanning[VPath](under, limit, identity, (_, cp) => cp) { (routed, toComposite) =>
      routed.backend.find(routed.path, p => toComposite(p).exists(accept), limit, ctx)
    }

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    spanning[GrepMatch](under, limit, _.path, (m, cp) => m.copy(path = cp)) { (routed, toComposite) =>
      routed.backend.search(routed.path, regex, p => toComposite(p).exists(accept), limit, ctx)
    }

object CompositeBackend:

  /** `default`, with each route's backend mounted at its path; the root and a repeated mount are refused. */
  def apply(default: FileBackend, routes: (VPath, FileBackend)*): Result[CompositeBackend] =
    val mounts = routes.map(_._1)
    if mounts.contains(VPath.root) then Left(ValidationError("routes", "the root cannot be a mount; it is the default"))
    else if mounts.distinct.size != mounts.size then
      Left(ValidationError("routes", s"a mount is repeated: ${mounts.diff(mounts.distinct).mkString(", ")}"))
    else Right(new CompositeBackend(default, routes.toVector))

/** [[FileBackend.resolved]]: each operation on the backend `resolve` picks for its context. */
final private[harness] class ResolvedFileBackend(
  id: String,
  val writes: Set[StateKey[?, ?]],
  resolve: FileContext => Result[FileBackend]
) extends FileBackend:

  private val lock = LockKey("resolved", id)

  def locks(path: VPath): Set[LockKey] = Set(lock / path.value)

  private def backend(ctx: FileContext): Result[FileBackend] =
    resolve(ctx).flatMap { b =>
      val undeclared = b.writes -- writes
      if undeclared.isEmpty then Right(b)
      else
        Left(
          ValidationError(
            "writes",
            s"resolved backend '$id' writes ${undeclared.mkString(", ")}, which FileBackend.resolved does not declare"
          ): LLMError
        )
    }

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    backend(ctx).flatMap(_.ls(path, limit, ctx))

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    backend(ctx).flatMap(_.read(path, offset, limit, ctx))

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    backend(ctx).flatMap(_.write(path, content, ctx))

  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] =
    backend(ctx).flatMap(_.edit(path, old, replacement, replaceAll, ctx))

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] =
    backend(ctx).flatMap(_.delete(path, ctx))

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    backend(ctx).flatMap(_.find(under, accept, limit, ctx))

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    backend(ctx).flatMap(_.search(under, regex, accept, limit, ctx))
