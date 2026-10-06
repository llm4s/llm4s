package org.llm4s.agent.events

import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.util.DurationJson
import upickle.default.{ macroRW, ReadWriter }

import scala.concurrent.duration.FiniteDuration

// Durable payloads: committed with the task that emitted them, replayable. They carry no message content.

/** A model call's token usage, as plain numbers. */
final case class CallUsage(promptTokens: Int, completionTokens: Int, totalTokens: Int, thinkingTokens: Option[Int])
    derives ReadWriter:
  def toTokenUsage: TokenUsage = TokenUsage(promptTokens, completionTokens, totalTokens, thinkingTokens)

object CallUsage:
  def fromTokenUsage(u: TokenUsage): CallUsage =
    CallUsage(u.promptTokens, u.completionTokens, u.totalTokens, u.thinkingTokens)

/**
 * `agent`'s model call returned. `attempts` counts the calls its model wrappers made to the model,
 * the last one succeeding; it is 0 when a model middleware answered without calling the model.
 * `usage` and `estimatedCost` (USD) are the completion's, when the provider reported them.
 */
final case class ModelCallCompleted(
  agent: String,
  model: String,
  attempts: Int,
  toolCalls: Int,
  usage: Option[CallUsage],
  estimatedCost: Option[Double]
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

/** One tool call's outcome; `duration` is the middleware chain's, zero for a call refused before it. */
final case class ToolExecuted(
  agent: String,
  toolCallId: String,
  tool: String,
  duration: FiniteDuration,
  outcome: ToolExecutionOutcome
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
final case class ToolResult(toolCallId: String, content: String, isError: Boolean) derives ReadWriter
