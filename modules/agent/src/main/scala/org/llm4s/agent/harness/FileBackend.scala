package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ EffectKey, GraphError, RunContext, StateKey, StateUpdate, ThreadState }
import org.llm4s.agent.graph.tool.{ Glob, LockKey, ToolContext, ToolOutcome }
import org.llm4s.annotation.Experimental
import org.llm4s.error.{ LLMError, NonRecoverableError }
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

import java.time.Instant
import java.util.regex.Pattern
import scala.jdk.CollectionConverters.*
import scala.util.Try

/**
 * What a backend operation knows: the run, the thread state it may read, and - for a tool call - the
 * call, whose key names each external effect it performs. Built from a [[ToolContext]] by a tool, or
 * from a [[RunContext]] and [[ThreadState]] by a hook.
 */
@Experimental
final class FileContext private (val run: RunContext, val state: ThreadState, val tool: Option[ToolContext]):

  // how many keys of each (operation, discriminator) this context has handed out
  private val issued = new java.util.concurrent.ConcurrentHashMap[Vector[String], Int]()

  /**
   * The key of one external effect of this call: the tool call's `effectKey`, or the hook's task's
   * (C4). A backend keys every external mutation with it, and never builds a key another way.
   *
   * Each effect gets a key of its own, even two of one operation on one path in one call: the `n`th
   * request for the same operation and discriminator (from 0) adds `#n` to the discriminator, from the
   * second on. A run of the call performs its effects in the same order each time, so each keeps its
   * key across replay. Build one `FileContext` per call, and pass it to every backend operation the call
   * makes: a second context would count from 0 again.
   */
  def effectKey(operation: String, discriminator: String*): EffectKey =
    val n     = issued.merge(operation +: discriminator.toVector, 1, Integer.sum) - 1
    val parts = if n == 0 then discriminator else discriminator :+ s"#$n"
    tool.fold(run.effectKey(operation, parts*))(_.effectKey(operation, parts*))

object FileContext:

  /** The context of a tool call. */
  def apply(tool: ToolContext): FileContext = new FileContext(tool.run, tool.state, Some(tool))

  /** The context of a hook, which is not a tool call. */
  def apply(run: RunContext, state: ThreadState): FileContext = new FileContext(run, state, None)

  /**
   * A hook's context with no thread state, for code that has only a `RunContext` (`ReplayContract`'s
   * subjects): an external backend never reads state, and a state backend's read fails as an unknown key.
   */
  private[agent] def withoutState(run: RunContext): FileContext =
    new FileContext(run, ThreadState.empty(Map.empty), None)

/**
 * Where an agent's files live: thread state ([[StateFileBackend]]), a host directory
 * ([[HostFileBackend]]), a [[LongTermStore]] ([[StoreFileBackend]]), several at once
 * ([[CompositeBackend]]), or one chosen per run ([[FileBackend.resolved]]).
 *
 * A backend owns the four obligations of every mutation, so the tools that call it need not know them:
 *
 *  - '''Order.''' `locks(path)` are the [[LockKey]]s a mutation of `path` holds; a tool declares them
 *    with `AgentToolSpec.withLocks`, so mutations of one path in one batch apply in call order. Reads
 *    take no lock.
 *  - '''Replay.''' A mutation outside thread state runs through C4's replay guard of its target's class,
 *    under `ctx.effectKey(operation, path)`. A state mutation is the `StateUpdate` it returns, which the
 *    runtime commits with the call; it needs no guard.
 *  - '''Bounds.''' Every result is bounded by `limit` and the backend's own limits, and every store by
 *    a limit the backend checks itself.
 *  - '''Tenant.''' A backend shared across threads is scoped by the run's tenant, or built per tenant by
 *    a resolver.
 *
 * A refusal the model should see is a [[BackendRefused]]; any other `Left` is a failure the task must
 * not hide ([[BackendRefused.toolOutcome]]).
 */
@Experimental
trait FileBackend:

  /** The state keys the `StateUpdate`s of this backend's mutations touch; a tool declares them in `writes`. */
  def writes: Set[StateKey[?, ?]]

  /** The locks a mutation of `path` holds. */
  def locks(path: VPath): Set[LockKey]

  /** The entries directly beneath the directory `path`, in path order, at most `limit`. */
  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]]

  /** At most `limit` lines of the file `path`, from the 0-based line `offset`. */
  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines]

  /** Writes the file `path`, creating it or replacing it. */
  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)]

  /**
   * Replaces `old` with `replacement` in the file `path`: the one occurrence, or every occurrence when
   * `replaceAll`. Refused when `old` is empty or absent, or occurs more than once and not `replaceAll`.
   */
  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)]

  /** Deletes the file, or the empty directory, `path`. */
  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)]

  /** The files within `under` that `accept` takes, in path order, at most `limit`. */
  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]]

  /**
   * The lines matching `regex` of the files within `under` that `accept` takes, in path then line order.
   * `regex` is the model's: match it with `org.llm4s.security.RegexSafetyManager.safeFind`, never a bare
   * matcher, so a catastrophic pattern is bounded and cannot overflow the stack.
   */
  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]]

  /** The files within `under` whose path relative to `under` matches `pattern`. */
  final def glob(pattern: Glob, under: VPath, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    find(under, p => p.relativeTo(under).exists(pattern.matches), limit, ctx)

  /**
   * The lines matching `regex` in the files within `under`. `include` filters the files: a pattern with
   * no `/` matches a file's name, one with a `/` its path relative to `under` (as ripgrep's `--glob`).
   */
  final def grep(
    regex: String,
    under: VPath,
    include: Option[Glob],
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    // the model's pattern: compiled and later matched through core's RegexSafetyManager, which refuses
    // known catastrophic shapes, bounds each match's steps and contains a recursing matcher's stack overflow
    org.llm4s.security.RegexSafetyManager
      .safeCompile(regex)
      .left
      .map(reason => BackendRefused("grep", regex, reason))
      .flatMap { compiled =>
        val accept: VPath => Boolean = include match
          case None                                      => _ => true
          case Some(glob) if !glob.pattern.contains('/') => p => glob.matches(p.name)
          case Some(glob)                                => p => p.relativeTo(under).exists(glob.matches)
        search(under, compiled, accept, limit, ctx)
      }

object FileBackend:

  /**
   * A backend chosen per operation by `resolve`, typically by `ctx.run.config.tenantId`: an application
   * serving tenants builds one host or workspace backend per tenant, and this picks it (rule 8). Caching
   * is the resolver's.
   *
   * Locks are computed from a call's arguments, before any context exists, so they cannot ask the
   * resolved backend for its own. Every mutation therefore holds the one backend-wide lock
   * `resolved/<id>`, which covers whatever locking the resolved backend needs: a `StateFileBackend`'s
   * backend-wide lock (its quota is exact only if every mutation is ordered) as much as a host
   * backend's per-path locks. The cost is that a batch's mutations through it run one after another. `writes` must hold every key a resolved backend's updates touch, since a graph
   * registers its keys when it is built; a resolved backend that writes another is a failure when used.
   */
  def resolved(id: String, writes: Set[StateKey[?, ?]] = Set.empty)(
    resolve: FileContext => Result[FileBackend]
  ): FileBackend = new ResolvedFileBackend(id, writes, resolve)

/** A list cut at a limit: `truncated` when there was more, or a scan bound stopped the walk. */
@Experimental
final case class Listing[A](items: Vector[A], truncated: Boolean)

object Listing:

  /** The first `limit` of `items`, `truncated` when there are more. */
  def of[A](items: Iterator[A], limit: Int): Listing[A] =
    // one more than the limit tells whether there are more; at Int.MaxValue there cannot be
    val taken = items.take(if limit < Int.MaxValue then limit + 1 else limit).toVector
    Listing(taken.take(limit), taken.length > limit)

/** One entry of a directory. `size` is in UTF-8 bytes, 0 for a directory. */
@Experimental
final case class FileInfo(path: VPath, isDirectory: Boolean, size: Long, modified: Option[Instant])

/** Lines `offset` to `offset + lines.size - 1` (0-based) of a file of `totalLines` lines. */
@Experimental
final case class FileLines(lines: Vector[String], offset: Int, totalLines: Int)

/** A line of a file that matched, numbered from 1. */
@Experimental
final case class GrepMatch(path: VPath, line: Int, text: String)

/** What a write did: whether it created the file, and the content's size in UTF-8 bytes. */
@Experimental
final case class FileWritten(created: Boolean, bytes: Long) derives ReadWriter

/** How many occurrences an edit replaced. */
@Experimental
final case class FileEdited(replacements: Int) derives ReadWriter

/** What a delete removed: a file, or an empty directory. */
@Experimental
final case class FileDeleted(wasDirectory: Boolean) derives ReadWriter

/**
 * A backend's refusal of one operation, for the model to see: a missing file, an `old` that is not
 * unique, a quota, binary content. Decided when the tool runs, never in an update function (rule 2).
 */
@Experimental
final case class BackendRefused(
  operation: String,
  target: String,
  reason: String,
  kind: BackendRefused.Kind = BackendRefused.Kind.Refused
) extends LLMError
    with NonRecoverableError:
  override val message: String = s"$operation $target: $reason"

object BackendRefused:

  /**
   * What a refusal says about the tree, for a caller that must tell the shape of a path from any other
   * refusal (a composite listing a directory a mount makes): `NotFound` and `NotADirectory`, or
   * `Refused` for anything else - a permission, a bound, an argument.
   */
  enum Kind:
    case Refused, NotFound, NotADirectory

  /** `path` does not exist. */
  def notFound(operation: String, path: VPath): BackendRefused =
    BackendRefused(operation, path.value, "not found", Kind.NotFound)

  /** `path` is a file where a directory is needed. */
  def notADirectory(operation: String, path: VPath): BackendRefused =
    BackendRefused(operation, path.value, "is a file, not a directory", Kind.NotADirectory)

  /**
   * The tool outcome of a backend's error: a refusal is an `Error` the model sees; anything else - a
   * store that failed, an effect whose outcome is unknown - is `Fatal`, so the task fails, commits
   * nothing, and `recover` replays it from its replay records.
   */
  def toolOutcome(error: LLMError): ToolOutcome = error match
    case refused: BackendRefused                 => ToolOutcome.Error(refused.message)
    case refused: GraphError.EffectReplayRefused => ToolOutcome.Error(refused.message)
    case other                                   => ToolOutcome.Fatal(other)

/** Shared text rules: UTF-8 only, and how content splits into lines. */
private[harness] object Text:

  /** Why `content` is not text a backend accepts, if it is not. */
  def binaryReason(content: String): Option[String] =
    if content.indexOf('\u0000') >= 0 then Some("content is binary (it contains NUL); only UTF-8 text is supported")
    else
      val encoder = java.nio.charset.StandardCharsets.UTF_8.newEncoder()
      if encoder.canEncode(content) then None else Some("content is not valid Unicode text")

  /** The lines of `content`: split on `\n`, a trailing `\r` dropped, no final empty line after a last `\n`. */
  def lines(content: String): Vector[String] =
    if content.isEmpty then Vector.empty
    else
      val parts = content.split("\n", -1).toVector
      (if content.endsWith("\n") then parts.dropRight(1) else parts).map(_.stripSuffix("\r"))

  def utf8Bytes(s: String): Long = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong

  /**
   * The bytes the code point `cp` takes inside a JSON string as ujson writes it: a quote, backslash or
   * short-escaped control character 2, any other control character (and, conservatively, a lone
   * surrogate) 6 (`\u00XX`), anything else its UTF-8 bytes.
   */
  def jsonWidth(cp: Int): Int =
    if cp == '"' || cp == '\\' || cp == '\n' || cp == '\r' || cp == '\t' || cp == '\b' || cp == '\f' then 2
    else if cp < 0x20 || (cp >= 0xd800 && cp <= 0xdfff) then 6
    else if cp < 0x80 then 1
    else if cp < 0x800 then 2
    else if cp < 0x10000 then 3
    else 4

  /** The bytes `s` takes encoded as a JSON string, quotes excluded: what a checkpoint or record stores. */
  def jsonBytes(s: String): Long = s.codePoints.iterator.asScala.map(cp => jsonWidth(cp.intValue).toLong).sum

  /** `s` cut to at most `max` [[jsonBytes]] on a character boundary, and whether it was cut. */
  def cutEncoded(s: String, max: Long): (String, Boolean) =
    @annotation.tailrec
    def loop(i: Int, bytes: Long): Int =
      if i >= s.length then i
      else
        val cp    = s.codePointAt(i)
        val width = jsonWidth(cp)
        if bytes + width > max then i else loop(i + Character.charCount(cp), bytes + width)
    val end = loop(0, 0L)
    (s.substring(0, end), end < s.length)

  /** Whether `value` nests arrays and objects at most `max` deep, found without recursion. */
  def depthWithin(value: ujson.Value, max: Int): Boolean =
    val stack = scala.collection.mutable.Stack[(ujson.Value, Int)](value -> 1)
    var ok    = true
    while ok && stack.nonEmpty do
      val (v, depth) = stack.pop()
      v match
        case ujson.Arr(items) =>
          if depth > max then ok = false else items.foreach(i => stack.push(i -> (depth + 1)))
        case ujson.Obj(fields) =>
          if depth > max then ok = false else fields.valuesIterator.foreach(f => stack.push(f -> (depth + 1)))
        case _ => ()
    ok

  /** Non-overlapping occurrences of `old` in `content`. */
  def occurrences(content: String, old: String): Int =
    @annotation.tailrec
    def loop(from: Int, n: Int): Int =
      val i = content.indexOf(old, from)
      if i < 0 then n else loop(i + old.length, n + 1)
    loop(0, 0)

  /** The edited content, or a refusal. */
  def edited(
    path: VPath,
    content: String,
    old: String,
    replacement: String,
    replaceAll: Boolean
  ): Result[(String, Int)] =
    if old.isEmpty then Left(BackendRefused("edit", path.value, "`old` is empty"))
    else
      occurrences(content, old) match
        case 0 => Left(BackendRefused("edit", path.value, "`old` was not found"))
        case n if n > 1 && !replaceAll =>
          Left(BackendRefused("edit", path.value, s"`old` occurs $n times; make it unique or set replaceAll"))
        case n =>
          val next = if replaceAll then content.replace(old, replacement) else replaceFirst(content, old, replacement)
          binaryReason(next).map(r => BackendRefused("edit", path.value, r)).toLeft((next, n))

  private def replaceFirst(content: String, old: String, replacement: String): String =
    val i = content.indexOf(old)
    content.substring(0, i) + replacement + content.substring(i + old.length)

  /** Matches of `regex` in `content`'s lines, checking the thread's interrupt flag as it matches. */
  /**
   * The matches of `regex` in `content`'s lines, each matched by `RegexSafetyManager.safeFind` (bounded
   * steps, a stack overflow contained); a match it aborts ends the search as a refusal
   * ([[interruptibly]]), and an interrupt between lines as a cancellation.
   */
  def matches(path: VPath, content: String, regex: Pattern): Iterator[GrepMatch] =
    lines(content).iterator.zipWithIndex.flatMap { (line, i) =>
      if Thread.currentThread().isInterrupted then throw new java.util.concurrent.CancellationException("interrupted")
      org.llm4s.security.RegexSafetyManager.safeFind(regex, line) match
        case Right(true)  => Iterator(GrepMatch(path, i + 1, line))
        case Right(false) => Iterator.empty
        case Left(reason) => throw new RegexAborted(s"${path.value}:${i + 1}: $reason")
    }

  /**
   * `body`, with a search stopped by an interrupt as a `CancelledError`, the flag
   * kept set, and any other throw as the error it is.
   */
  def interruptibly[A](operation: String)(body: => Result[A]): Result[A] =
    Try(body) match
      case scala.util.Success(result) => result
      case scala.util.Failure(_: java.util.concurrent.CancellationException) =>
        Thread.currentThread().interrupt()
        Left(org.llm4s.error.CancelledError(operation))
      case scala.util.Failure(e: RegexAborted) => Left(BackendRefused(operation, "pattern", e.getMessage))
      case failure                             => failure.toResult.flatten

  /** Lines `offset` to `offset + limit` of `lines`, the sum taken without overflow. */
  def page(lines: Vector[String], offset: Int, limit: Int): Vector[String] =
    lines.slice(offset, math.min(offset.toLong + limit, lines.size.toLong).toInt)

  def checkLimit(operation: String, target: String, limit: Int): Result[Unit] =
    if limit >= 1 then Right(()) else Left(BackendRefused(operation, target, s"limit must be at least 1, was $limit"))

/** A match `RegexSafetyManager` aborted; [[Text.interruptibly]] makes it a refusal. */
final private[harness] class RegexAborted(message: String) extends RuntimeException(message)
