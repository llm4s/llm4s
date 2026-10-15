package org.llm4s.agent.graph

import org.llm4s.types.Result

/** Thread state outside a runtime, for calling harness backends directly. */
object HarnessTestSupport {

  /** An empty state over `keys`. */
  def stateOf(keys: StateKey[?, ?]*): ThreadState = ThreadState.empty(keys.map(k => k.id -> k).toMap)

  /** `state` with `update` applied, as the runtime commits it. */
  def committed(state: ThreadState, update: StateUpdate): Result[ThreadState] = state.applyUpdate(update)

  /** A `RunContext` outside any runtime, on thread `thread`. */
  def runOn(thread: String): RunContext =
    val config = RunConfig()
    new RunContext(
      config,
      RunPosition(ThreadId(thread), config.runId, "", TaskId("task"), NodeId("node"), 0),
      NodeEventSink.none
    )

  /**
   * A `RunContext` at `thread`, run `run`, claim `token` and task `task`, whose durable events are appended to
   * `events` as (name, payload); [[discardAttempt]] empties it, as a runtime drops a failed attempt's events.
   */
  def recordingRunContext(
    events: java.util.List[(String, ujson.Value)],
    thread: String = "t",
    run: String = "r",
    token: Option[Long] = None,
    task: String = "task"
  ): RunContext =
    val sink = new NodeEventSink:
      def custom(name: String, version: Int, payload: ujson.Value): Unit   = events.add(name -> payload): Unit
      def progress(name: String, version: Int, payload: ujson.Value): Unit = ()
      def discardCustom(): Unit                                            = events.clear()
    new RunContext(
      RunConfig().withRunId(RunId(run)),
      RunPosition(ThreadId(thread), RunId(run), "", TaskId(task), NodeId("node"), 0, token.map(FencingToken(_))),
      sink
    )

  /** Forgets the durable events `context` emitted, as a runtime does before retrying a failed attempt. */
  def discardAttempt(context: RunContext): Unit = context.discardAttempt()
}
