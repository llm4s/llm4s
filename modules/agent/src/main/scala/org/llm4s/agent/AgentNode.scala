package org.llm4s.agent

/**
 * A node of an agent's loop that a static breakpoint can hold: see [[AgentBuilder.withInterruptBefore]]
 * and [[AgentBuilder.withInterruptAfter]]. Each task the breakpoint holds is an interrupt of its own,
 * reported in `AgentStatus.Suspended.breakpoints` and continued with `AgentResult.proceed`.
 */
enum AgentNode:
  /**
   * Each model call (`<id>/model`). Held before, the model is not called yet; held after, the model's
   * message is stored, but its tool calls - or its final answer's guarding - wait.
   */
  case Model

  /**
   * Each tool call (`<id>/call-tool`), one interrupt per call, so the calls of one message are held and
   * continued one at a time. Held before, the call has not run; held after, its result is recorded, but
   * the next model call waits for it. A call continued after an approval or a question runs at the
   * agent's approval or question node, which this breakpoint does not hold: the reviewer has just seen it.
   */
  case Tool

  /** The final answer (`<id>/finish`), before its `afterAgent` hooks - guardrails among them - run. */
  case Finish
