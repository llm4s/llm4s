package org.llm4s.agent.harness

import org.llm4s.agent.graph.{ StateKey, StateUpdate, ThreadState }
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  MiddlewareId,
  ModelContext,
  ModelRequest,
  ModelResponse,
  ToolCallRequest
}
import org.llm4s.agent.graph.tool.{ LockKey, ToolContent, ToolContext, ToolOutcome }
import org.llm4s.agent.graph.toolloop.Messages
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.{ ContentPart, ToolMessage }
import org.llm4s.types.Result
import upickle.default.ReadWriter

import java.time.Instant
import java.util.regex.Pattern

/**
 * Keeps a large tool result out of the conversation (design §4.34). A `Success` whose text is over `thresholdTokens`
 * estimated tokens (four characters a token) is stored in [[ToolOutputOffload.key]], cut to `maxResultBytes`, and the
 * model is shown instead a header naming the path `/large_tool_results/<idempotencyKey>` and the first lines of the
 * text. [[OffloadBackend]], mounted at [[ToolOutputOffload.Mount]] in a [[CompositeBackend]], serves the stored
 * results to file tools, read-only.
 *
 *   - **Identity.** The path is the call's `IdempotencyKey`, the runtime's identity of the call: a provider that
 *     reuses a tool call id cannot make an older result's path name a newer call's content, and a rerun of the same
 *     call (`recover`) replaces its own entry.
 *   - **The bound.** The stored results are at most `maxTotalBytes`, as a checkpoint stores them: each update evicts
 *     the oldest until they fit, when it is applied, so the bound holds under a parallel batch and whether or not a
 *     [[SummarizationMiddleware]] runs. Reading an evicted path is a tool error that says so.
 *   - **Content parts.** Only text is offloaded. Every non-text part of a result is passed on as it is, after the
 *     preview.
 *   - **Cleanup.** With a [[SummarizationMiddleware]], results whose path appears in a tool result the summary
 *     covers are dropped at the next model step. The bound does not depend on it. The summariser runs outside
 *     this middleware - `build()` refuses the other order (`SummarizationMiddleware.placement`).
 *
 * An output Block restores the key, so a blocked turn's results are never kept.
 *
 * @param thresholdTokens a result over this many estimated tokens is offloaded; at least 1000
 * @param maxResultBytes the most bytes of one stored result; at least 1 and at most `maxTotalBytes`
 * @param maxTotalBytes the most bytes all stored results take together
 */
@Experimental
final class ToolOutputOffload(
  val thresholdTokens: Int = ToolOutputOffload.DefaultThresholdTokens,
  val maxResultBytes: Long = ToolOutputOffload.DefaultMaxResultBytes,
  val maxTotalBytes: Long = ToolOutputOffload.DefaultMaxTotalBytes,
  val id: MiddlewareId = MiddlewareId("tool-output-offload")
) extends AgentMiddleware:
  import ToolOutputOffload.*

  require(thresholdTokens >= 1000, s"ToolOutputOffload: thresholdTokens must be at least 1000, was $thresholdTokens")
  require(maxResultBytes >= 1, s"ToolOutputOffload: maxResultBytes must be at least 1, was $maxResultBytes")
  require(
    // compared without adding, so no value overflows into passing
    maxTotalBytes >= MetadataBytes && maxTotalBytes - MetadataBytes >= maxResultBytes,
    s"ToolOutputOffload: maxTotalBytes ($maxTotalBytes) must be at least maxResultBytes ($maxResultBytes) plus " +
      s"$MetadataBytes bytes for each result's key and tool name"
  )

  override def writes: Set[StateKey[?, ?]] = Set(key)

  private val thresholdChars: Long = thresholdTokens.toLong * 4

  override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
    next() match
      case ToolOutcome.Success(content, update) =>
        val text = resultText(content, thresholdChars)
        text.filter(_.length.toLong > thresholdChars) match
          case None => ToolOutcome.Success(content, update)
          case Some(large) =>
            val segment = segmentOf(context.idempotencyKey.value)
            val tool    = Text.cutEncoded(request.toolName.value, MaxNameBytes)._1
            // the key and the tool name take at most MetadataBytes, so the entry always fits the bound on its own
            val (stored, isCut) = Text.cutEncoded(large, maxResultBytes)
            val result          = OffloadedResult(segment, tool, stored, Text.utf8Bytes(large), isCut)
            ToolOutcome.Success(
              shown(content, preview(result, large)),
              update.combine(StateUpdate.update(key, OffloadUpdate.Put(result, maxTotalBytes)))
            )
      // an error over the threshold is cut before it is stored: it is the model's to read, not to page through
      case ToolOutcome.Error(message) if message.length.toLong > thresholdChars =>
        ToolOutcome.Error(capped(message))
      case other => other

  /**
   * Sends every tool result of the request within the threshold, whoever produced it: an outcome given instead of
   * running the tool - the loop's argument, approval and timeout errors, a denial from a middleware outside this one -
   * never reaches `wrapToolCall`, so its text is cut here, as it is sent. A `Success` over the threshold was already
   * offloaded. Media parts are kept.
   */
  override def wrapModelCall(request: ModelRequest, context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] =
    val dropped = coveredBySummary(context.state)
    val sent = request.messages.map {
      case t: ToolMessage if ContentPart.text(t.parts).length.toLong > thresholdChars =>
        ToolMessage(
          ContentPart.Text(capped(ContentPart.text(t.parts))) +: t.parts.filter(ContentPart.isMedia),
          t.toolCallId
        )
      case other => other
    }
    next(if sent == request.messages then request else request.withMessages(sent)).map(r =>
      if dropped.isEmpty then r else r.addUpdate(StateUpdate.update(key, OffloadUpdate.Drop(dropped)))
    )

  /** `text` cut to the threshold, its last characters a note saying how much was cut. */
  private def capped(text: String): String =
    val note =
      s"\n[cut: this tool result is ${text.length} characters, over this agent's limit of $thresholdTokens tokens]"
    SummarizationMiddleware.cut(text, thresholdChars - note.length) + note

  /** What the model is shown in place of `full`: where it is stored, and its first lines. */
  private def preview(result: OffloadedResult, full: String): String =
    val (head, total) = firstLines(full, PreviewLines)
    val shown         = head.map(l => if l.length > PreviewLineChars then s"${cutChars(l)}..." else l)
    val cut =
      if result.truncated then s" Only its first ${result.content.length} characters are stored: the rest is lost."
      else ""
    val more = Option.when(total > shown.size)(s"[... ${total - shown.size} more lines]")
    (Vector(
      s"[The result of ${result.tool} is ${full.length} characters (about ${(full.length + 3) / 4} tokens), over " +
        s"this agent's limit of $thresholdTokens tokens, so it is not shown here. It is stored, read-only, at " +
        s"${pathOf(result.key)}: read it in pages with a file tool.$cut Its first lines:]"
    ) ++ shown ++ more).mkString("\n")

  /** The stored results the thread's summary covers: their path appears in a tool result at or before it. */
  private def coveredBySummary(state: ThreadState): Vector[String] =
    (for
      summary  <- state.get(SummarizationMiddleware.key).toOption.flatten
      offloads <- state.get(key).toOption.filter(_.results.nonEmpty)
      history  <- state.get(Messages.key).toOption
      through = history.indexWhere(_.id == summary.throughMessageId)
      if through >= 0
    yield
      // whole paths only: a key that is a prefix of another never matches it
      val named = history
        .take(through + 1)
        .iterator
        .collect { case StoredToolText(t) => t }
        .flatMap(t => PathPattern.findAllMatchIn(t).map(_.group(1)))
        .toSet
      offloads.results.map(_.key).filter(named)
    ).getOrElse(Vector.empty)

@Experimental
object ToolOutputOffload:

  /** The default for `thresholdTokens`. */
  val DefaultThresholdTokens: Int = 20000

  /** The default for `maxResultBytes`: 1 MiB. */
  val DefaultMaxResultBytes: Long = 1024L * 1024

  /** The default for `maxTotalBytes`: 4 MiB. */
  val DefaultMaxTotalBytes: Long = 4L * 1024 * 1024

  /** Where [[OffloadBackend]] is mounted, and the prefix of every path the model is shown. */
  val Mount: VPath = VPath("/large_tool_results").toOption.get

  /** The most evicted keys [[Offloads]] remembers, so reading one says it was evicted. */
  val MaxEvicted: Int = 1024

  /** How many lines of an offloaded result the model is shown, each cut to [[PreviewLineChars]] characters. */
  val PreviewLines: Int     = 10
  val PreviewLineChars: Int = 200

  /**
   * The thread's offloaded results: one key for every `ToolOutputOffload`, so the agents of one family share them. Its
   * update is total: a `Put` evicts the oldest results until they fit the bound it carries.
   */
  val key: StateKey[Offloads, OffloadUpdate] =
    StateKey[Offloads, OffloadUpdate]("offloads", Offloads.empty)((current, update) => Right(current.applied(update)))

  /** A whole offload path in text, its segment the group: not followed by another segment character. */
  private[harness] val PathPattern: scala.util.matching.Regex =
    (java.util.regex.Pattern.quote(Mount.value) + "/([A-Za-z0-9_-]{1,64})(?![A-Za-z0-9_-])").r

  /** The path the model is shown for the result stored under `segment`. */
  def pathOf(segment: String): String = s"${Mount.value}/$segment"

  /** The most bytes of the key and of the tool name an entry stores, together at most [[MetadataBytes]]. */
  private val MaxNameBytes: Long = 64

  /** What an entry's key and tool name take at most, beside its content. */
  val MetadataBytes: Long = 2 * MaxNameBytes

  /**
   * The path segment of a call's key: the key itself when it is at most 64 of `[A-Za-z0-9_-]`, as the runtime's hex
   * digests are; any other key a caller built is hashed into one. So a segment is always at most 64 bytes.
   */
  private[harness] def segmentOf(key: String): String =
    if key.matches("[A-Za-z0-9_-]{1,64}") then key else sha256(key)

  private def sha256(s: String): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /**
   * The first `n` lines of `s` as `Text.lines` splits it (on `\n`, a trailing `\r` dropped, no empty line after a
   * last `\n`), each cut to [[PreviewLineChars]] plus one character, and the number of lines in all: one scan that
   * copies only those `n` lines, so a result of many short lines costs no allocation per line.
   */
  private[harness] def firstLines(s: String, n: Int): (Vector[String], Long) =
    val head  = Vector.newBuilder[String]
    var count = 0L
    var start = 0
    while start < s.length do
      val nl  = s.indexOf('\n', start)
      val end = if nl < 0 then s.length else nl
      if count < n then
        val line = s.substring(start, math.min(end, start + PreviewLineChars + 1))
        head += (if end - start <= PreviewLineChars + 1 then line.stripSuffix("\r") else line)
      count += 1
      start = if nl < 0 then s.length else nl + 1
    (head.result(), count)

  private def cutChars(line: String): String = SummarizationMiddleware.cut(line, PreviewLineChars.toLong)

  /**
   * The text the loop sends for a result: for JSON, a non-blank string as itself and anything else rendered as JSON;
   * for content parts, the text parts joined. `None` when a JSON result is certainly within `limit` characters,
   * sized without rendering it.
   */
  private def resultText(content: ToolContent, limit: Long): Option[String] = content match
    case ToolContent.Json(ujson.Str(s)) if s.trim.nonEmpty => Some(s)
    case ToolContent.Json(other)  => Option.when(Text.jsonValueBytes(other, limit) > limit)(other.render())
    case ToolContent.Parts(parts) => Some(ContentPart.text(parts))

  /**
   * What the model is sent in place of `content`: the preview, and for content parts every non-text part after it,
   * in order. Only text is offloaded; media is never dropped.
   */
  private def shown(content: ToolContent, preview: String): ToolContent = content match
    case ToolContent.Json(_)      => ToolContent.Json(ujson.Str(preview))
    case ToolContent.Parts(parts) => ToolContent.Parts(ContentPart.Text(preview) +: parts.filter(ContentPart.isMedia))

  /** The text of a stored tool result. */
  private object StoredToolText:
    def unapply(m: org.llm4s.agent.graph.toolloop.StoredMessage): Option[String] = m.message match
      case t: ToolMessage => Some(t.content)
      case _              => None

/** One offloaded result: `key` is its path segment (the call's `IdempotencyKey`), `content` its stored text. */
@Experimental
final case class OffloadedResult private (
  key: String,
  tool: String,
  content: String,
  originalBytes: Long,
  truncated: Boolean
):

  /** What it takes in a checkpoint: its key, tool name and content as JSON strings. */
  lazy val bytes: Long = Text.jsonBytes(key) + Text.jsonBytes(tool) + Text.jsonBytes(content)

@Experimental
object OffloadedResult:
  def apply(key: String, tool: String, content: String, originalBytes: Long, truncated: Boolean): OffloadedResult =
    new OffloadedResult(key, tool, content, originalBytes, truncated)

  final private case class Wire(key: String, tool: String, content: String, originalBytes: Long, truncated: Boolean)
      derives ReadWriter

  given ReadWriter[OffloadedResult] =
    summon[ReadWriter[Wire]].bimap[OffloadedResult](
      r => Wire(r.key, r.tool, r.content, r.originalBytes, r.truncated),
      w => OffloadedResult(w.key, w.tool, w.content, w.originalBytes, w.truncated)
    )

/**
 * The thread's offloaded results, oldest first, and the keys of the latest [[ToolOutputOffload.MaxEvicted]] evicted
 * or dropped ones, oldest first.
 */
@Experimental
final case class Offloads private (results: Vector[OffloadedResult], evicted: Vector[String]):

  /** What the results take in a checkpoint, as [[ToolOutputOffload]]'s bound counts it. */
  def bytes: Long = results.iterator.map(_.bytes).sum

  def get(key: String): Option[OffloadedResult] = results.find(_.key == key)

  /** This after `update`; total. */
  private[harness] def applied(update: OffloadUpdate): Offloads = update match
    case OffloadUpdate.Put(result, maxTotalBytes) =>
      val appended = results.filterNot(_.key == result.key) :+ result
      // the oldest go first, until the rest fit; the total is kept as it falls, so this is linear
      var total = appended.iterator.map(_.bytes).sum
      val kept = appended.dropWhile { r =>
        val over = total > maxTotalBytes
        if over then total -= r.bytes
        over
      }
      val gone = appended.take(appended.size - kept.size).map(_.key)
      Offloads(kept, remembered(evicted.filterNot(_ == result.key), gone))
    case OffloadUpdate.Drop(keys) =>
      val gone = results.map(_.key).filter(keys.contains)
      if gone.isEmpty then this else Offloads(results.filterNot(r => gone.contains(r.key)), remembered(evicted, gone))

  private def remembered(before: Vector[String], gone: Vector[String]): Vector[String] =
    (before.filterNot(gone.contains) ++ gone).takeRight(ToolOutputOffload.MaxEvicted)

@Experimental
object Offloads:
  def apply(results: Vector[OffloadedResult], evicted: Vector[String]): Offloads = new Offloads(results, evicted)

  val empty: Offloads = Offloads(Vector.empty, Vector.empty)

  final private case class Wire(results: Vector[OffloadedResult], evicted: Vector[String]) derives ReadWriter

  given ReadWriter[Offloads] =
    summon[ReadWriter[Wire]].bimap[Offloads](o => Wire(o.results, o.evicted), w => Offloads(w.results, w.evicted))

/** An update to [[ToolOutputOffload.key]]; both are total (rule 2). */
@Experimental
enum OffloadUpdate derives ReadWriter:

  /** Stores `result`, replacing one with its key, then evicts the oldest until all fit `maxTotalBytes`. */
  case Put(result: OffloadedResult, maxTotalBytes: Long)

  /** Drops the results with these keys; a key not stored is ignored. */
  case Drop(keys: Vector[String])

/**
 * The read-only view of the thread's offloaded results ([[ToolOutputOffload.key]]): one file `/<key>` per result.
 * Mount it at [[ToolOutputOffload.Mount]] in a [[CompositeBackend]], the path the model is shown. `ls`, `read`,
 * `glob` and `grep` see the stored text; `write`, `edit` and `delete` are refused, so a file tool reports them as tool
 * errors. Reading an evicted result says it was evicted. An agent without a `ToolOutputOffload` has no results.
 */
@Experimental
object OffloadBackend extends FileBackend:

  val writes: Set[StateKey[?, ?]] = Set.empty

  private val lock: LockKey = FileBackend.LockRoot / "offloads"

  def locks(path: VPath): Set[LockKey] = Set(lock)

  private def offloads(ctx: FileContext): Offloads =
    ctx.state.get(ToolOutputOffload.key).getOrElse(Offloads.empty)

  private def files(ctx: FileContext): Map[VPath, FileEntry] =
    offloads(ctx).results.iterator
      .flatMap(r => (VPath.root / r.key).toOption.map(_ -> FileEntry(r.content, Instant.EPOCH)))
      .toMap

  def ls(path: VPath, limit: Int, ctx: FileContext): Result[Listing[FileInfo]] =
    FileTree.ls(files(ctx), path, limit).map(l => l.copy(items = l.items.map(_.copy(modified = None))))

  def read(path: VPath, offset: Int, limit: Int, ctx: FileContext): Result[FileLines] =
    val all = files(ctx)
    if !all.contains(path) && path.parent.contains(VPath.root) && offloads(ctx).evicted.contains(path.name) then
      Left(
        BackendRefused.at(
          "read",
          path,
          "was evicted: offloaded tool results are kept oldest first within a bound, and dropped once the " +
            "conversation summary covers them; call the tool again if you need it",
          BackendRefused.Kind.NotFound
        )
      )
    else FileTree.read(all, path, offset, limit)

  private def readOnly[A](operation: String, path: VPath): Result[A] =
    Left(BackendRefused.at(operation, path, "is read-only: offloaded tool results cannot be changed"))

  def write(path: VPath, content: String, ctx: FileContext): Result[(FileWritten, StateUpdate)] =
    readOnly("write", path)

  def edit(
    path: VPath,
    old: String,
    replacement: String,
    replaceAll: Boolean,
    ctx: FileContext
  ): Result[(FileEdited, StateUpdate)] = readOnly("edit", path)

  def delete(path: VPath, ctx: FileContext): Result[(FileDeleted, StateUpdate)] = readOnly("delete", path)

  def find(under: VPath, accept: VPath => Boolean, limit: Int, ctx: FileContext): Result[Listing[VPath]] =
    FileTree.find(files(ctx), under, accept, limit)

  def search(
    under: VPath,
    regex: Pattern,
    accept: VPath => Boolean,
    limit: Int,
    ctx: FileContext
  ): Result[Listing[GrepMatch]] =
    FileTree.search(files(ctx), under, regex, accept, limit)
