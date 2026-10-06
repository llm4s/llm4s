package org.llm4s.agent.events

import org.llm4s.agent.graph.EventType

/**
 * The events an agent run sends, each an [[org.llm4s.agent.graph.EventType]] to match a
 * `StreamEvent` with:
 *
 * {{{
 * agent.stream(threadId, "Explain monads") {
 *   case AgentEvents.TextDelta(d)          => print(d.text)
 *   case AgentEvents.ToolExecuted(t)       => println(s"${t.tool}: ${t.outcome} in ${t.duration}")
 *   case _                                 => ()
 * }
 * }}}
 *
 * Durable (stored, replayed, no content): [[ModelCallCompleted]], [[ToolExecuted]], [[HandedOff]],
 * [[GuardrailBlocked]]. Live (current subscribers only): [[ModelCallStarted]], [[TextDelta]],
 * [[ThinkingDelta]], [[ToolCallStarted]], [[ToolCallResult]].
 */
object AgentEvents:
  val ModelCallCompleted: EventType[org.llm4s.agent.events.ModelCallCompleted] =
    EventType("agent.model_call_completed", 1)
  val ToolExecuted: EventType[org.llm4s.agent.events.ToolExecuted] = EventType("agent.tool_executed", 1)
  val HandedOff: EventType[org.llm4s.agent.events.HandedOff]       = EventType("agent.handed_off", 1)
  val GuardrailBlocked: EventType[GuardrailBlock]                  = EventType("agent.guardrail_blocked", 1)

  val ModelCallStarted: EventType[org.llm4s.agent.events.ModelCallStarted] = EventType("agent.model_call_started", 1)
  val TextDelta: EventType[org.llm4s.agent.events.TextDelta]               = EventType("agent.text_delta", 1)
  val ThinkingDelta: EventType[org.llm4s.agent.events.ThinkingDelta]       = EventType("agent.thinking_delta", 1)
  val ToolCallStarted: EventType[org.llm4s.agent.events.ToolCallStarted]   = EventType("agent.tool_call_started", 1)
  val ToolCallResult: EventType[org.llm4s.agent.events.ToolCallResult]     = EventType("agent.tool_call_result", 1)

  /** The names of the durable agent events. */
  val durable: Set[String] = Set(ModelCallCompleted, ToolExecuted, HandedOff, GuardrailBlocked).map(_.name)
