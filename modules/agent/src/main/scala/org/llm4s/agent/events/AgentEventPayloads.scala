package org.llm4s.agent.events

import org.llm4s.llmconnect.model.{ FinishReason, RequestAdjustment, ServiceTier, TokenUsage }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.util.DurationJson
import org.slf4j.LoggerFactory
import upickle.default.{ macroRW, ReadWriter }

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

// Durable payloads: committed with the task that emitted them, replayable. They carry no message content.

// A provider id is stored as its canonical string, so an event written before the field was typed still reads (#2275).
private given ReadWriter[ProviderId] = upickle.default.readwriter[String].bimap[ProviderId](_.asString, ProviderId(_))

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
final case class CallUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, thinkingTokens: Option[Int])
    derives ReadWriter:
  def toTokenUsage: TokenUsage = TokenUsage(promptTokens, completionTokens, totalTokens, thinkingTokens)

object CallUsage:
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
 * version cannot read is `ServiceTier.Other`, as a finish reason is.
 */
final case class ModelCallCompleted(
  agent: String,
  model: String,
  attempts: Int,
  toolCalls: Int,
  usage: Option[CallUsage],
  estimatedCost: Option[Double],
  provider: Option[ProviderId] = None,
  adjustments: List[RequestAdjustment] = List.empty,
  finishReason: Option[FinishReason] = None,
  servedTier: Option[ServiceTier] = None
) derives ReadWriter

/** How a tool call ended for the loop. */
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
 * [[org.llm4s.agent.graph.IdempotencyKey]] (a digest, no content), which names the call's trace span
 * (design 4.58); `None` in an event stored before it existed.
 */
final case class ToolExecuted(
  agent: String,
  toolCallId: String,
  tool: String,
  duration: FiniteDuration,
  outcome: ToolExecutionOutcome,
  idempotencyKey: Option[String] = None
)

object ToolExecuted:
  given ReadWriter[ToolExecuted] =
    import DurationJson.millisRW
    macroRW

/** The active agent changed from `from` to `to`. */
final case class HandedOff(from: String, to: String) derives ReadWriter

/** Where a guardrail blocked the turn. */
enum GuardrailPhase derives ReadWriter:
  case Input, Output

/** A guardrail blocked the turn; the reason is on `AgentStatus.Blocked`, never in the log. */
final case class GuardrailBlock(guardrail: String, phase: GuardrailPhase) derives ReadWriter

// Live payloads: delivered to current subscribers only, never stored. They may carry content.

/** `agent`'s model is being called; a higher `attempt` in the same task discards the earlier attempt's text. */
final case class ModelCallStarted(agent: String, attempt: Int) derives ReadWriter

/** A chunk of the model's answer, for `attempt`. */
final case class TextDelta(attempt: Int, text: String) derives ReadWriter

/** A chunk of the model's thinking, for `attempt`. */
final case class ThinkingDelta(attempt: Int, text: String) derives ReadWriter

/** A tool call passed its argument checks and is about to run through the middleware chain. */
final case class ToolCallStarted(toolCallId: String, tool: String, arguments: ujson.Value) derives ReadWriter

/** The result the loop recorded for a call, as the model will see it. */
final case class ToolCallResult(toolCallId: String, content: String, isError: Boolean) derives ReadWriter
