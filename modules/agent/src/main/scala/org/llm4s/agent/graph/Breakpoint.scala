package org.llm4s.agent.graph

import upickle.default.ReadWriter

/**
 * Where a static breakpoint holds a task: before its node runs, or after it ran.
 *
 * A run's [[RunConfig.interruptBefore]] and [[RunConfig.interruptAfter]] name the nodes. A task held
 * before its node runs has done nothing; once its interrupt is answered it runs, and that breakpoint
 * does not hold it again. A task held after its node ran has its update committed, so the state shows
 * what it did, but its routes, static edges and join arrivals wait: once its interrupt is answered they
 * are made, without running the node again. Either way the held task is parked like a task that
 * suspended - one interrupt per task, keyed by its task id - and the run pauses at the end of the
 * superstep, so breakpoints and typed interrupts are answered together, any non-empty subset at a time.
 */
enum BreakpointPhase derives ReadWriter:
  case Before
  case After

object Breakpoint:

  /**
   * The answer that continues a task a breakpoint holds: JSON `null`, the only answer a breakpoint
   * takes. [[CompiledGraph.resume]] refuses any other with [[GraphError.InvalidResume]].
   */
  val proceed: ujson.Value = ujson.Null
