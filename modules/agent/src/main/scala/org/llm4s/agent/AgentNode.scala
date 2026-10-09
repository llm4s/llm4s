package org.llm4s.agent

/**
 * A node of an agent's loop that a static breakpoint can hold: see [[AgentBuilder.withInterruptBefore]]
 * and [[AgentBuilder.withInterruptAfter]]. Each task the breakpoint holds is an interrupt of its own,
 * reported in `AgentStatus.Suspended.breakpoints` and continued with `AgentResult.proceed`.
 *
 * A step continued after a review runs at another node: a model call after a `wrapModelCall` question at
 * `<id>/asked/<middleware>/wrapModelCall`, a tool call after an approval or a question at `<id>/approval`,
 * `<id>/ask/<tool>` or `<id>/asked/<middleware>/wrapToolCall`, and an answer after an `afterAgent` question
 * at `<id>/asked/<middleware>/afterAgent`. A breakpoint after a node holds these too, so nothing the step
 * does escapes it; a breakpoint before one does not, since the reviewer has just seen the step.
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
   * the next model call waits for it - also a result recorded once an approval or a question about the
   * call was answered.
   */
  case Tool

  /** The final answer (`<id>/finish`), before its `afterAgent` hooks - guardrails among them - run. */
  case Finish
