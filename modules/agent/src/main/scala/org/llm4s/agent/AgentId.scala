package org.llm4s.agent

import org.llm4s.error.ValidationError
import org.llm4s.types.Result
import upickle.default.ReadWriter

/**
 * Stable identifier of an agent in a family: it prefixes the agent's graph nodes and is
 * persisted as a thread's active agent. Must match `[a-zA-Z0-9_-]{1,52}`.
 */
opaque type AgentId = String

object AgentId:
  private val Pattern = "[a-zA-Z0-9_-]{1,52}".r

  /** `value` as an id, or a `ValidationError` on field `agent.id` when it does not match `[a-zA-Z0-9_-]{1,52}`. */
  def of(value: String): Result[AgentId] =
    if Pattern.matches(value) then Right(value)
    else Left(ValidationError("agent.id", s"'$value' must match [a-zA-Z0-9_-]{1,52}"))

  /** An id known to be valid, unchecked. */
  private[llm4s] def unsafe(value: String): AgentId = value

  extension (id: AgentId) def value: String = id

  /**
   * Encodes as a plain JSON string. Built from upickle's string codecs: inside this scope an
   * `AgentId` is a `String`, so summoning `ReadWriter[String]` would find this given.
   */
  given ReadWriter[AgentId] = ReadWriter.join(upickle.default.StringReader, upickle.default.StringWriter)
