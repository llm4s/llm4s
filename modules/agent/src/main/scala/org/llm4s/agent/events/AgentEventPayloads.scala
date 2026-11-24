package org.llm4s.agent.events

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.annotation.Experimental
import org.llm4s.llmconnect.model.{ FinishReason, RequestAdjustment, ServiceTier, TokenUsage, ToolCallId }
import org.llm4s.toolapi.IdempotencyKey
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.util.DurationJson
import org.slf4j.LoggerFactory
import upickle.default.{ macroRW, ReadWriter }

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.util.Try

// Durable payloads: committed with the task that emitted them, replayable. They carry no message content.

// A provider id is stored as its canonical string, so an event written before the field was typed still reads (#2275).

private val logger = LoggerFactory.getLogger("org.llm4s.agent.events.AgentEventPayloads")

// An adjustment this version cannot read - an option or a case added later (RFC 0008 plans more) - is left out, so
// the event, and the usage it carries, still reads. Written as the derived codec writes it.
private given ReadWriter[List[RequestAdjustment]] =
  upickle.default
    .readwriter[ujson.Value]
    .bimap[List[RequestAdjustment]](
      adjustments => ujson.Arr.from(adjustments.map(upickle.default.writeJs[RequestAdjustment](_))),
      json =>
        json.arrOpt.fold(List.empty)(_.toList.flatMap { entry =>
          Try(upickle.default.read[RequestAdjustment](entry)).fold(
            e =>
              logger.debug(s"Left out an adjustment this version cannot read: ${e.getMessage}")
              None
            ,
            Some(_)
          )
        })
    )

// A finish reason or service tier this version cannot read - a case added later (RFC 0009, #2746) - reads as the
// enum's Other with the raw value it was stored as, so nothing of it is lost and the event, and the usage it carries,
// still reads: a case with no fields is stored as its name, one with fields under its `$type` tag. A value with no raw
// string in it is None. Written as the enum's own codec writes it.
private def lenient[A: ReadWriter](what: String, other: String => A): ReadWriter[Option[A]] =
  upickle.default
    .readwriter[ujson.Value]
    .bimap[Option[A]](
      _.fold(ujson.Null: ujson.Value)(upickle.default.writeJs[A](_)),
      {
        case ujson.Null => None
        case json =>
          Try(upickle.default.read[A](json)).fold(
            e =>
              val raw = json match
                case ujson.Str(name) => Some(name)
                case obj: ujson.Obj  => obj.value.get("$type").collect { case ujson.Str(tag) => tag }
                case _               => None
              logger.debug(s"Read a $what this version cannot read as Other($raw): ${e.getMessage}")
              raw.map(other)
            ,
            Some(_)
          )
      }
    )

private given finishReasonRW: ReadWriter[Option[FinishReason]] = lenient("finish reason", FinishReason.Other(_))

private given servedTierRW: ReadWriter[Option[ServiceTier]] = lenient("service tier", ServiceTier.Other(_))

/** A model call's token usage, as plain numbers. */
@Experimental
final case class CallUsage private (
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int,
  thinkingTokens: Option[Int]
) derives ReadWriter:
  def withPromptTokens(value: Int): CallUsage           = copy(promptTokens = value)
  def withCompletionTokens(value: Int): CallUsage       = copy(completionTokens = value)
  def withTotalTokens(value: Int): CallUsage            = copy(totalTokens = value)
  def withThinkingTokens(value: Int): CallUsage         = copy(thinkingTokens = Some(value))
  def withThinkingTokens(value: Option[Int]): CallUsage = copy(thinkingTokens = value)

  def toTokenUsage: TokenUsage = TokenUsage(promptTokens, completionTokens, totalTokens, thinkingTokens)

@Experimental
object CallUsage:

  def apply(promptTokens: Int, completionTokens: Int, totalTokens: Int, thinkingTokens: Option[Int]): CallUsage =
    new CallUsage(promptTokens, completionTokens, totalTokens, thinkingTokens)

  def fromTokenUsage(u: TokenUsage): CallUsage =
    CallUsage(u.promptTokens, u.completionTokens, u.totalTokens, u.thinkingTokens)

/**
 * A model call of `agent`'s returned a completion: one event per provider call, whichever response the model
 * wrappers kept, so the events sum to the thread's usage. `attempts` is the call's attempt number within its model
 * step, from 1 - a retrying wrapper's calls are 1, 2, ... - and 0 for a call a hook made through
 * `ModelContext.accounted`, or usage it recorded with `ModelContext.recordUsage`. `toolCalls` is the completion's.
 * `usage` and `estimatedCost` (USD) are the completion's, when the provider reported them. `provider` is the canonical
 * id of the provider called, when its client reported one (`LLMClient.providerId`); `None` in an event stored before
 * it existed (#2275). `adjustments` are the completion's (`Completion.adjustments`): the options the client did not
 * send as asked (RFC 0008, #2115); llm4s's own clients put no message content in them. Empty for usage recorded
 * without a call, and in an event stored before the field existed. An adjustment this version cannot read, an option
 * or a case added later, is left out rather than failing the event. `finishReason` is the completion's
 * (`Completion.finishReason`): why the model's turn ended (RFC 0009, #2613), `Length` when the reply was cut at an
 * output limit. `None` for usage recorded without a call, and in an event stored before the field existed; a reason
 * this version cannot read, a case added later, is `FinishReason.Other` with the value it was stored as. `servedTier` is
 * the completion's (`Completion.servedTier`): the tier the provider reports serving the call at (#2746), `None` when it
 * reported none, for usage recorded without a call, and in an event stored before the field existed; a tier this
 * version cannot read is `ServiceTier.Other`, as a finish reason is. `duration` is how long the provider call took, as
 * the agent measured it around the client (#2829): the length of the call's `chat` span. Stored in milliseconds; zero
 * for usage recorded without a call, and in an event stored before the field existed.
 */
@Experimental
final case class ModelCallCompleted private (
  agent: String,
  model: ModelName,
  attempts: Int,
  toolCalls: Int,
  usage: Option[CallUsage],
  estimatedCost: Option[Double],
  provider: Option[ProviderId] = None,
  adjustments: List[RequestAdjustment] = List.empty,
  finishReason: Option[FinishReason] = None,
  servedTier: Option[ServiceTier] = None,
  duration: FiniteDuration = Duration.Zero
):
  def withAgent(value: String): ModelCallCompleted                        = copy(agent = value)
  def withModel(value: ModelName): ModelCallCompleted                     = copy(model = value)
  def withAttempts(value: Int): ModelCallCompleted                        = copy(attempts = value)
  def withToolCalls(value: Int): ModelCallCompleted                       = copy(toolCalls = value)
  def withUsage(value: CallUsage): ModelCallCompleted                     = copy(usage = Some(value))
  def withUsage(value: Option[CallUsage]): ModelCallCompleted             = copy(usage = value)
  def withEstimatedCost(value: Double): ModelCallCompleted                = copy(estimatedCost = Some(value))
  def withEstimatedCost(value: Option[Double]): ModelCallCompleted        = copy(estimatedCost = value)
  def withProvider(value: ProviderId): ModelCallCompleted                 = copy(provider = Some(value))
  def withProvider(value: Option[ProviderId]): ModelCallCompleted         = copy(provider = value)
  def withAdjustments(value: List[RequestAdjustment]): ModelCallCompleted = copy(adjustments = value)
  def withFinishReason(value: FinishReason): ModelCallCompleted           = copy(finishReason = Some(value))
  def withFinishReason(value: Option[FinishReason]): ModelCallCompleted   = copy(finishReason = value)
  def withServedTier(value: ServiceTier): ModelCallCompleted              = copy(servedTier = Some(value))
  def withServedTier(value: Option[ServiceTier]): ModelCallCompleted      = copy(servedTier = value)
  def withDuration(value: FiniteDuration): ModelCallCompleted             = copy(duration = value)

@Experimental
object ModelCallCompleted:
  def apply(
    agent: String,
    model: ModelName,
    attempts: Int,
    toolCalls: Int,
    usage: Option[CallUsage],
    estimatedCost: Option[Double],
    provider: Option[ProviderId] = None,
    adjustments: List[RequestAdjustment] = List.empty,
    finishReason: Option[FinishReason] = None,
    servedTier: Option[ServiceTier] = None,
    duration: FiniteDuration = Duration.Zero
  ): ModelCallCompleted =
    new ModelCallCompleted(
      agent,
      model,
      attempts,
      toolCalls,
      usage,
      estimatedCost,
      provider,
      adjustments,
      finishReason,
      servedTier,
      duration
    )

  given ReadWriter[ModelCallCompleted] =
    import DurationJson.millisRW
    macroRW

/** How a tool call ended for the loop. */
@Experimental
enum ToolExecutionOutcome derives ReadWriter:
  /** The tool returned `Success`. */
  case Succeeded

  /** An error result: the tool's `Error`, a failed argument check, an unknown tool, or a thrown exception. */
  case Errored

  /** A middleware denied the call (an error result starting `Denied:`). */
  case Denied

  /** A reviewer rejected the call at approval. */
  case Rejected

  /** The call suspended for approval. */
  case NeedsApproval

  /** The tool suspended with a question. */
  case Asked

/**
 * One tool call's outcome; `duration` is the middleware chain's, zero for a call refused before it.
 * `tool` is the called tool's name when the agent has that tool, else `"<unknown>"`
 * (`ToolLoop.UnknownTool`): a name the model invented is not stored. `idempotencyKey` is the call's
 * [[org.llm4s.toolapi.IdempotencyKey]] (a digest, no content), which names the call's trace span
 * (design 4.58); `None` in an event stored before it existed.
 */
@Experimental
final case class ToolExecuted private (
  agent: String,
  toolCallId: ToolCallId,
  tool: String,
  duration: FiniteDuration,
  outcome: ToolExecutionOutcome,
  idempotencyKey: Option[IdempotencyKey] = None
):
  def withAgent(value: String): ToolExecuted                          = copy(agent = value)
  def withToolCallId(value: ToolCallId): ToolExecuted                 = copy(toolCallId = value)
  def withTool(value: String): ToolExecuted                           = copy(tool = value)
  def withDuration(value: FiniteDuration): ToolExecuted               = copy(duration = value)
  def withOutcome(value: ToolExecutionOutcome): ToolExecuted          = copy(outcome = value)
  def withIdempotencyKey(value: IdempotencyKey): ToolExecuted         = copy(idempotencyKey = Some(value))
  def withIdempotencyKey(value: Option[IdempotencyKey]): ToolExecuted = copy(idempotencyKey = value)

@Experimental
object ToolExecuted:

  def apply(
    agent: String,
    toolCallId: ToolCallId,
    tool: String,
    duration: FiniteDuration,
    outcome: ToolExecutionOutcome,
    idempotencyKey: Option[IdempotencyKey] = None
  ): ToolExecuted =
    new ToolExecuted(agent, toolCallId, tool, duration, outcome, idempotencyKey)

  given ReadWriter[ToolExecuted] =
    import DurationJson.millisRW
    macroRW

/** The active agent changed from `from` to `to`. */
@Experimental
final case class HandedOff private (from: String, to: String) derives ReadWriter:
  def withFrom(value: String): HandedOff = copy(from = value)
  def withTo(value: String): HandedOff   = copy(to = value)

@Experimental
object HandedOff:
  def apply(from: String, to: String): HandedOff =
    new HandedOff(from, to)

/** Where a guardrail blocked the turn. */
@Experimental
enum GuardrailPhase derives ReadWriter:
  case Input, Output

/**
 * A guardrail blocked the turn. The reason is on the blocked run's `AgentStatus.Blocked`, never in the event log or
 * the checkpoint store (#2853): no durable event of a blocked run holds it (its `TaskFailed` and `RunFailed` name the
 * guardrail only), and the run's closing checkpoint records the guardrail's name alone, as `RunOutcome.Blocked`.
 */
@Experimental
final case class GuardrailBlock private (guardrail: String, phase: GuardrailPhase) derives ReadWriter:
  def withGuardrail(value: String): GuardrailBlock     = copy(guardrail = value)
  def withPhase(value: GuardrailPhase): GuardrailBlock = copy(phase = value)

@Experimental
object GuardrailBlock:
  def apply(guardrail: String, phase: GuardrailPhase): GuardrailBlock =
    new GuardrailBlock(guardrail, phase)

// Live payloads: delivered to current subscribers only, never stored. They may carry content.

/** `agent`'s model is being called; a higher `attempt` in the same task discards the earlier attempt's text. */
@Experimental
final case class ModelCallStarted private (agent: String, attempt: Int) derives ReadWriter:
  def withAgent(value: String): ModelCallStarted = copy(agent = value)
  def withAttempt(value: Int): ModelCallStarted  = copy(attempt = value)

@Experimental
object ModelCallStarted:
  def apply(agent: String, attempt: Int): ModelCallStarted =
    new ModelCallStarted(agent, attempt)

/** A chunk of the model's answer, for `attempt`. */
@Experimental
final case class TextDelta private (attempt: Int, text: String) derives ReadWriter:
  def withAttempt(value: Int): TextDelta = copy(attempt = value)
  def withText(value: String): TextDelta = copy(text = value)

@Experimental
object TextDelta:
  def apply(attempt: Int, text: String): TextDelta =
    new TextDelta(attempt, text)

/** A chunk of the model's thinking, for `attempt`. */
@Experimental
final case class ThinkingDelta private (attempt: Int, text: String) derives ReadWriter:
  def withAttempt(value: Int): ThinkingDelta = copy(attempt = value)
  def withText(value: String): ThinkingDelta = copy(text = value)

@Experimental
object ThinkingDelta:
  def apply(attempt: Int, text: String): ThinkingDelta =
    new ThinkingDelta(attempt, text)

/** A tool call passed its argument checks and is about to run through the middleware chain. */
@Experimental
final case class ToolCallStarted private (toolCallId: ToolCallId, tool: String, arguments: ujson.Value)
    derives ReadWriter:
  def withToolCallId(value: ToolCallId): ToolCallStarted = copy(toolCallId = value)
  def withTool(value: String): ToolCallStarted           = copy(tool = value)
  def withArguments(value: ujson.Value): ToolCallStarted = copy(arguments = value)

@Experimental
object ToolCallStarted:
  def apply(toolCallId: ToolCallId, tool: String, arguments: ujson.Value): ToolCallStarted =
    new ToolCallStarted(toolCallId, tool, arguments)

/** The result the loop recorded for a call, as the model will see it. */
@Experimental
final case class ToolCallResult private (toolCallId: ToolCallId, content: String, isError: Boolean) derives ReadWriter:
  def withToolCallId(value: ToolCallId): ToolCallResult = copy(toolCallId = value)
  def withContent(value: String): ToolCallResult        = copy(content = value)
  def withIsError(value: Boolean): ToolCallResult       = copy(isError = value)

@Experimental
object ToolCallResult:
  def apply(toolCallId: ToolCallId, content: String, isError: Boolean): ToolCallResult =
    new ToolCallResult(toolCallId, content, isError)
