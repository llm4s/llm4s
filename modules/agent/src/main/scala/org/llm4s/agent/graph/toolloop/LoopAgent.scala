package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.middleware.AgentMiddleware
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.llmconnect.model.Message
import upickle.default.ReadWriter

/**
 * One agent of a [[ToolLoop]] family: its model, tools, middleware, system prompt, step limit and
 * handoffs. Its nodes are prefixed with its `id`.
 *
 * @param systemPrompt sent first on every model call of this agent, never stored in the history
 * @param maxSteps the limit this agent's model node applies to the turn's step count, which every
 *   agent of the turn shares: at it the turn ends with [[TurnOutcome.StepLimitReached]] instead of a
 *   further call, so an agent handed a turn that has already used its limit makes no call
 */
final case class LoopAgent private (
  id: AgentId,
  model: ModelStep,
  tools: ToolSet,
  middleware: Seq[AgentMiddleware],
  systemPrompt: Option[String],
  maxSteps: Int,
  handoffs: Vector[LoopHandoff]
):
  def withSystemPrompt(prompt: Option[String]): LoopAgent         = copy(systemPrompt = prompt)
  def withMaxSteps(steps: Int): LoopAgent                         = copy(maxSteps = steps)
  def withMiddleware(middleware: Seq[AgentMiddleware]): LoopAgent = copy(middleware = middleware)
  def withHandoffs(handoffs: Vector[LoopHandoff]): LoopAgent      = copy(handoffs = handoffs)

object LoopAgent:

  /** An agent with no system prompt, no middleware, no handoffs and a limit of 50 steps a turn. */
  def apply(id: AgentId, model: ModelStep, tools: ToolSet = ToolSet.empty): LoopAgent =
    new LoopAgent(id, model, tools, Nil, None, 50, Vector.empty)

/** A transfer the agent may make to `target`; with `preserveContext = false` the target sees only the transfer onwards. */
final case class LoopHandoff(target: AgentId, reason: Option[String], preserveContext: Boolean)

/**
 * The last handoff the thread made: from `source` to `target`, by the stored assistant message
 * `messageId`. Recorded by the model node when it routes a transfer; the target's model reads it to
 * build the view a transfer without preserved context sends.
 */
final case class Transfer(source: AgentId, target: AgentId, messageId: String) derives ReadWriter

/** One turn's input: the user's query and, on a new thread only, the conversation to import before it. */
final case class AgentInput(query: String, history: Vector[Message] = Vector.empty) derives ReadWriter

/** How a turn ended. */
enum TurnOutcome derives ReadWriter:
  /** The active agent gave a final answer. */
  case Completed

  /** A guardrail refused the turn. */
  case Blocked(guardrail: String, reason: String)

  /** The active agent used its `maxSteps` model calls without a final answer. */
  case StepLimitReached

/** The current turn: the model calls it has made, and its outcome once it has one. Reset by each turn's input. */
final case class TurnState(steps: Int, outcome: Option[TurnOutcome]) derives ReadWriter

/** A turn's result: how it ended, and the agent active at its end - the one the next turn starts with. */
final case class TurnOutput(outcome: TurnOutcome, activeAgent: AgentId) derives ReadWriter
