package org.llm4s.agent.harness

import org.llm4s.agent.ContextPruning
import org.llm4s.agent.graph.{ StateKey, StateUpdate }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, ModelRequest, ModelResponse }
import org.llm4s.agent.graph.toolloop.{ Messages, StoredMessage, ToolResultRule }
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.{ Message, MessageRole, UserMessage }
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * When a model request grows past `trigger`, replaces its older messages with a summary, kept in the thread and
 * extended as the thread grows (design §4.34). The stored history is never edited.
 *
 *   - **The trigger** is measured on the outgoing request as it would be sent with the stored summary applied,
 *     system prompt included: its estimated tokens (`tokenCounter`) or its message count.
 *   - **When it fires**, every message after the summary but the last `keepRecent` goes to `compressor` with the
 *     summary so far; the cut moves earlier so a tool call is never separated from its results. The new summary goes
 *     straight into this request, and is written to [[SummarizationMiddleware.key]] with the stored id of the last
 *     message it covers, committed with the model step.
 *   - **Later requests** send the summary in place of the messages it covers - one `UserMessage` straight after the
 *     system prompt - and the messages after it unchanged.
 *   - **The bound**: a summary is at most `maxSummaryTokens` estimated tokens at four characters a token, cut after the
 *     compressor returns and again when a stored one is sent.
 *
 * An output Block restores the key, so a blocked turn's summary is never kept. A model compressor's calls go through
 * `ModelContext.accounted`, so they are counted in the thread's usage. Use this or `ContextWindowMiddleware`, which
 * prunes with no model call, not both.
 *
 * @param trigger when to summarise
 * @param keepRecent how many of the latest messages are always sent as they are; at least 1
 * @param compressor writes the summary
 * @param maxSummaryTokens the bound on the summary, in estimated tokens at four characters a token; at least 1
 * @param tokenCounter the estimate a `SummaryTrigger.Tokens` trigger is measured with
 */
@Experimental
final class SummarizationMiddleware(
  val trigger: SummaryTrigger,
  val keepRecent: Int = SummarizationMiddleware.DefaultKeepRecent,
  compressor: SummaryCompressor,
  val maxSummaryTokens: Int = SummarizationMiddleware.DefaultMaxSummaryTokens,
  tokenCounter: Message => Int = ContextPruning.defaultTokenCounter,
  val id: MiddlewareId = MiddlewareId("summarization")
) extends AgentMiddleware:
  import SummarizationMiddleware.*

  require(keepRecent >= 1, s"SummarizationMiddleware: keepRecent must be at least 1, was $keepRecent")
  require(maxSummaryTokens >= 1, s"SummarizationMiddleware: maxSummaryTokens must be at least 1, was $maxSummaryTokens")

  override def writes: Set[StateKey[?, ?]] = Set(key)

  override def wrapModelCall(request: ModelRequest, context: ModelContext)(
    next: ModelRequest => Result[ModelResponse]
  ): Result[ModelResponse] =
    for
      history <- context.state.get(Messages.key)
      stored  <- context.state.get(key)
      plan = planned(request, history, stored)
      sent <- plan match
        case Plan.AsIs(effective) => Right(effective -> StateUpdate.empty)
        case fold: Plan.Fold =>
          compressor
            .compress(fold.previous, fold.folded, maxSummaryTokens, context)
            .map { text =>
              val summary = ConversationSummary(clamp(text, maxSummaryTokens), fold.throughMessageId)
              val update  = StateUpdate.update(key, Some(summary))
              (request.withMessages(fold.system ++ Vector(summaryMessage(summary.text)) ++ fold.kept), update)
            }
      response <- next(sent._1)
    yield response.addUpdate(sent._2)

  /** What to send: the request with the stored summary applied, and, when the trigger fires, what to fold into it. */
  private def planned(
    request: ModelRequest,
    history: Vector[StoredMessage],
    stored: Option[ConversationSummary]
  ): Plan =
    val (system, rest)           = request.messages.span(_.role == MessageRole.System)
    val matched                  = rest.zip(align(rest, history))
    def summarised(text: String) = Vector(summaryMessage(clamp(text, maxSummaryTokens)))
    // a request whose summary would leave a tool call without its results, or a result without its call, is not sent
    def valid(messages: Vector[Message]) = ToolResultRule.violations(messages).isEmpty
    // the stored summary replaces exactly the request messages matched to a stored message it covers; a message an
    // outer wrapper added matches none, and is kept wherever it stands
    val applied = for
      s <- stored
      t <- Some(history.indexWhere(_.id == s.throughMessageId)).filter(_ >= 0)
      kept = matched.filterNot(_._2.exists(_ <= t))
      if kept.size < matched.size && valid(kept.map(_._1))
    yield s -> kept
    val summary = applied.map(_._1)
    // the stored message the applied summary covers through: a summary covers a prefix of the stored history
    val coveredThrough = summary.fold(-1)(s => history.indexWhere(_.id == s.throughMessageId))
    val after          = applied.fold(matched)(_._2)
    val effective      = system ++ summary.fold(Vector.empty[Message])(s => summarised(s.text)) ++ after.map(_._1)
    lazy val asIs      = Plan.AsIs(request.withMessages(effective))
    if !fires(effective) then asIs
    else
      // the last `keepRecent` are kept; the cut moves earlier while it would split a tool batch, or leave the last
      // folded message without a stored id for `throughMessageId`. Only stored messages are folded: one an outer
      // wrapper added is kept, before the kept messages
      val cut = Iterator
        .iterate(after.size - keepRecent)(_ - 1)
        .takeWhile(_ > 0)
        .find { c =>
          val (folded, kept) = after.splitAt(c)
          val stored         = folded.flatMap(_._2)
          // the folded stored messages must be exactly those after the summary, through the last: a summary never
          // claims a message its compressor did not see (a handoff's view or a pruning wrapper leaves gaps)
          folded.last._2.isDefined &&
          stored == (coveredThrough + 1 to folded.last._2.get).toVector &&
          valid(Vector(summaryMessage("")) ++ folded.filter(_._2.isEmpty).map(_._1) ++ kept.map(_._1))
        }
      cut match
        case None => asIs
        case Some(c) =>
          val (folded, kept) = after.splitAt(c)
          Plan.Fold(
            system,
            summary.map(_.text),
            folded.collect { case (m, Some(_)) => m },
            folded.collect { case (m, None) => m } ++ kept.map(_._1),
            history(folded.last._2.get).id
          )

  private def fires(messages: Vector[Message]): Boolean = trigger match
    case SummaryTrigger.Tokens(max)   => messages.iterator.map(m => tokenCounter(m).toLong).sum > max
    case SummaryTrigger.Messages(max) => messages.size > max

@Experimental
object SummarizationMiddleware:

  /** The default for `keepRecent`. */
  val DefaultKeepRecent: Int = 10

  /** The default for `maxSummaryTokens`. */
  val DefaultMaxSummaryTokens: Int = 2000

  /** The first line of the message that carries the summary in a request. */
  val SummaryHeader: String = "[Summary of the earlier conversation, whose messages are not shown]"

  /**
   * The thread's summary: one key for every `SummarizationMiddleware`, so the agents of one family share it, as they
   * share the history it summarises.
   */
  val key: StateKey[Option[ConversationSummary], Option[ConversationSummary]] =
    StateKey.replace[Option[ConversationSummary]]("summary", None)

  private enum Plan:
    case AsIs(request: ModelRequest)
    case Fold(
      system: Vector[Message],
      previous: Option[String],
      folded: Vector[Message],
      kept: Vector[Message],
      throughMessageId: String
    )

  private def summaryMessage(text: String): Message = UserMessage(s"$SummaryHeader\n$text")

  /** `text` cut to `maxTokens` estimated tokens at four characters a token, on a code point boundary. */
  private[harness] def clamp(text: String, maxTokens: Int): String = cut(text, maxTokens.toLong * 4)

  /** `text` cut to at most `maxChars` characters, on a code point boundary. */
  private[harness] def cut(text: String, maxChars: Long): String =
    if text.length.toLong <= maxChars then text
    else
      val end = math.max(0L, maxChars).toInt
      text.substring(0, if end > 0 && Character.isHighSurrogate(text.charAt(end - 1)) then end - 1 else end)

  /**
   * For each request message, the index of the stored message it is, if any: matched from the end, each to the latest
   * stored message before the previous match that equals it. The loop sends a subsequence of the history (a handoff's
   * view), and wrappers outside may drop messages or add their own, which match nothing.
   */
  private[harness] def align(request: Vector[Message], history: Vector[StoredMessage]): Vector[Option[Int]] =
    var bound   = history.size
    val matched = Array.fill[Option[Int]](request.size)(None)
    var i       = request.size - 1
    while i >= 0 do
      var k = bound - 1
      while k >= 0 && history(k).message != request(i) do k -= 1
      if k >= 0 then
        matched(i) = Some(k)
        bound = k
      i -= 1
    matched.toVector

/** When a [[SummarizationMiddleware]] summarises, measured on the outgoing request. */
@Experimental
enum SummaryTrigger:

  /** Over `max` estimated tokens, by the middleware's `tokenCounter`. */
  case Tokens(max: Int)

  /** More than `max` messages. */
  case Messages(max: Int)

  this match
    case Tokens(max)   => require(max >= 1, s"SummaryTrigger.Tokens: max must be at least 1, was $max")
    case Messages(max) => require(max >= 1, s"SummaryTrigger.Messages: max must be at least 1, was $max")

/**
 * The thread's summary, as [[SummarizationMiddleware.key]] stores it: `text` summarises the stored history up to and
 * including the message `throughMessageId` (a `StoredMessage` id).
 */
@Experimental
final case class ConversationSummary private (text: String, throughMessageId: String):
  def withText(t: String): ConversationSummary              = ConversationSummary(t, throughMessageId)
  def withThroughMessageId(id: String): ConversationSummary = ConversationSummary(text, id)

@Experimental
object ConversationSummary:
  def apply(text: String, throughMessageId: String): ConversationSummary =
    new ConversationSummary(text, throughMessageId)

  final private case class Wire(text: String, throughMessageId: String) derives ReadWriter

  given ReadWriter[ConversationSummary] =
    summon[ReadWriter[Wire]].bimap[ConversationSummary](
      s => Wire(s.text, s.throughMessageId),
      w => ConversationSummary(w.text, w.throughMessageId)
    )
