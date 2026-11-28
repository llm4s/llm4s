package org.llm4s.agent.service

import org.llm4s.agent.{ Agent, AgentId }
import org.llm4s.agent.delegation.BoundAgent
import org.llm4s.agent.graph.toolloop.{ Admission, AgentInput }
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.util.Try

/**
 * An agent an [[AgentWorker]] can run queued entries for (Stage 5 W2, design 4.56): an `Agent`, whose queued starts are
 * [[QueuedInput.query]]'s `AgentInput`, or a bound `TypedAgent[I, O]`, whose queued starts are [[QueuedInput.typed]]'s
 * `I`, rendered as its first user message as `BoundAgent.run` renders it. Entries name it by [[id]]. Its runtime must be
 * over the worker's store.
 */
@Experimental
final class QueuedAgent private (
  private[service] val agent: Agent,
  private[service] val input: ujson.Value => Result[AgentInput]
):

  /** The agent id the queue's entries name. */
  def id: AgentId = agent.id

@Experimental
object QueuedAgent:

  /**
   * `input` with its admission mark dropped, all but a refusal (#3258): the input step runs every admission stage
   * again, and adds no usage or call the mark names. A refusal is kept, since it only blocks the turn.
   */
  private[service] def unadmitted(input: AgentInput): AgentInput =
    input.withAdmission(input.refusedBy.map(refused => Admission(Vector.empty, refusedBy = Some(refused))))

  /** A [[QueuedInput.Admitted]] input: an `AgentInput`, admission mark and all, for an `Agent` or a typed agent alike. */
  private[service] def admittedInput(input: ujson.Value): Result[AgentInput] = agentInput(input)

  private def agentInput(input: ujson.Value): Result[AgentInput] =
    // the input is the caller's: the message names only how it failed (Stage 5 rule 10)
    Try(upickle.default.read[AgentInput](input)).toEither.left
      .map(thrown =>
        ValidationError("input", s"the queued input is not an agent input (${thrown.getClass.getSimpleName})")
      )

  /** An `Agent`: a queued start is an `AgentInput`, a query after its history. */
  def apply(agent: Agent): QueuedAgent =
    new QueuedAgent(agent, agentInput)

  /** A bound `TypedAgent[I, O]`: a queued start is an `I`, in its JSON form. */
  def typed[I, O](agent: BoundAgent[I, O]): QueuedAgent =
    new QueuedAgent(agent.agent, input => agent.queuedQuery(input).map(AgentInput(_)))
