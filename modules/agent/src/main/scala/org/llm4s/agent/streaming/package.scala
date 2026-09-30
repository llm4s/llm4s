package org.llm4s.agent

/**
 * Streaming event support for agent execution.
 *
 * This package provides fine-grained visibility into agent execution
 * through an event-based streaming API. Events are emitted in real-time
 * during agent execution, enabling:
 *
 * - '''Real-time UIs''': Stream LLM tokens to users as they're generated
 * - '''Progress tracking''': Monitor multi-step agent execution
 * - '''Tool visibility''': See which tools are being invoked and their results
 * - '''Debugging''': Trace agent behavior with timestamped events
 *
 * ==Event Categories==
 *
 * '''Text Events''': Token-level streaming during LLM generation
 *  - [[streaming.AgentEvent.TextDelta TextDelta]]: A chunk of generated text
 *  - [[streaming.AgentEvent.TextComplete TextComplete]]: Generation complete for current step
 *
 * '''Tool Events''': Tool invocation lifecycle
 *  - [[streaming.AgentEvent.ToolCallStarted ToolCallStarted]]: Tool invocation requested
 *  - [[streaming.AgentEvent.ToolCallCompleted ToolCallCompleted]]: Tool execution finished
 *  - [[streaming.AgentEvent.ToolCallFailed ToolCallFailed]]: Tool execution failed
 *
 * '''Agent Lifecycle''': Start, step, complete, fail
 *  - [[streaming.AgentEvent.AgentStarted AgentStarted]]: Execution began
 *  - [[streaming.AgentEvent.StepStarted StepStarted]]: New step started
 *  - [[streaming.AgentEvent.StepCompleted StepCompleted]]: Step finished
 *  - [[streaming.AgentEvent.AgentCompleted AgentCompleted]]: Execution succeeded
 *  - [[streaming.AgentEvent.AgentFailed AgentFailed]]: Execution failed
 *
 * '''Handoff Events''': Agent-to-agent delegation
 *  - [[streaming.AgentEvent.HandoffStarted HandoffStarted]]: Delegating to another agent
 *  - [[streaming.AgentEvent.HandoffCompleted HandoffCompleted]]: Handoff finished
 *
 * ==Usage==
 *
 * {{{
 * import org.llm4s.agent.Agent
 * import org.llm4s.agent.streaming.AgentEvent._
 *
 * agent.runWithEvents(
 *   query = "What's the weather in London?",
 *   tools = weatherTools,
 *   onEvent = {
 *     case TextDelta(delta, _) =>
 *       print(delta)  // Stream to console
 *
 *     case ToolCallStarted(_, name, args, _) =>
 *       println(s"\n[Calling $$name with $$args]")
 *
 *     case ToolCallCompleted(_, name, result, _, _, _) =>
 *       println(s"[Tool $$name returned: $$result]")
 *
 *     case AgentCompleted(state, steps, duration, _) =>
 *       println(s"\n[Done in $$steps steps, $${duration}ms]")
 *
 *     case AgentFailed(error, _, _) =>
 *       println(s"\n[Error: $$error]")
 *
 *     case _ => // Ignore other events
 *   }
 * )
 * }}}
 *
 * ==Collecting Events==
 *
 * To collect all events for later processing:
 *
 * {{{
 * val events = scala.collection.mutable.ArrayBuffer[AgentEvent]()
 *
 * agent.runWithEvents(
 *   query = "...",
 *   tools = tools,
 *   onEvent = events += _
 * )
 *
 * // Analyze events after execution
 * val toolCalls = events.collect { case tc: ToolCallStarted => tc }
 * val totalTextLength = events.collect { case TextDelta(d, _) => d.length }.sum
 * }}}
 *
 * @see [[org.llm4s.agent.Agent]] for the streaming API (`runWithEvents`)
 * @see [[AgentEvent]] for the event type hierarchy
 */
package object streaming
