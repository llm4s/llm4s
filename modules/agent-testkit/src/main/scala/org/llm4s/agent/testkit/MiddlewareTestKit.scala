package org.llm4s.agent.testkit

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.{
  IdempotencyKey,
  RunConfig,
  RunContext,
  StateKey,
  StateUpdate,
  ThreadId,
  ThreadState,
  ToolCallId
}
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, MiddlewareStack, ModelContext }
import org.llm4s.agent.graph.tool.ToolContext
import org.llm4s.agent.graph.toolloop.{ LoopKeys, Messages }
import org.llm4s.llmconnect.model.{ ToolCall, UsageSummary }
import org.llm4s.types.Result

/**
 * What a test needs to call an [[org.llm4s.agent.graph.middleware.AgentMiddleware AgentMiddleware]]'s hooks directly,
 * with no agent, no runtime and no model (#2795): the contexts the agent loop would build, over a thread state the
 * test chooses, and the order a stack resolves to.
 *
 * {{{
 * val state   = MiddlewareTestKit.state(Set(Articles), StateUpdate.update(Articles, "returns policy"))
 * val context = state.map(MiddlewareTestKit.modelContext(_))
 * context.flatMap(new KnowledgeBase(search).promptSections(_))
 * }}}
 *
 * The contexts are outside any runtime: events a hook emits go nowhere, no timeout is enforced, and effect records
 * are kept in memory. Model usage is counted, and a token budget (`RunBudgets.maxTokens` on `config`) is enforced, as
 * in a run: read what a hook's [[org.llm4s.agent.graph.middleware.ModelContext.accounted accounted]] calls recorded
 * with [[usage]]. To test what a turn stores, what a model is sent or how a run recovers, run an agent against a
 * scripted client instead.
 */
object MiddlewareTestKit:

  /** The thread the contexts are on unless the test names another. */
  val DefaultThread: ThreadId = ThreadId("test-thread")

  // the loop's own keys, which a hook may read: the stored history, usage, the turn, the active agent, the transfer
  private val loopKeys: Vector[StateKey[?, ?]] =
    Vector(Messages.key, LoopKeys.usage, LoopKeys.activeAgent, LoopKeys.turn, LoopKeys.transfer)

  /** A thread state with only the loop's own keys, each at its initial value. */
  val emptyState: ThreadState = ThreadState.empty(loopKeys.map(k => k.id -> k).toMap)

  /**
   * A thread state that registers `keys` - a middleware's `writes`, and any other key its hooks read - beside the
   * loop's own (`Messages.key`, `LoopKeys.usage`, ...), with `update` applied. `Left` when `update` touches a key
   * that is not registered, or does not apply.
   */
  def state(keys: Set[StateKey[?, ?]], update: StateUpdate = StateUpdate.empty): Result[ThreadState] =
    ThreadState.empty((loopKeys ++ keys).map(k => k.id -> k).toMap).applyUpdate(update)

  /**
   * `state` with `update` applied, as the runtime commits it: for giving a later hook what an earlier one returned
   * (`BoundaryResult.update`, `ModelResponse.update`). `Left` when `update` touches a key `state` does not register.
   */
  def updated(state: ThreadState, update: StateUpdate): Result[ThreadState] = state.applyUpdate(update)

  /**
   * The context `beforeAgent`, `promptSections`, `wrapModelCall` and `afterAgent` are given: `state` as the committed
   * thread state, and a run on `threadId` with `config` - its run id, tenant, principal, budgets and metadata.
   *
   * @param agent the id of the agent whose hook it is, for the usage events an accounted call announces
   */
  def modelContext(
    state: ThreadState = emptyState,
    config: RunConfig = RunConfig(),
    threadId: ThreadId = DefaultThread,
    agent: String = "agent"
  ): ModelContext =
    new ModelContext(RunContext.outsideRuntime(threadId, config), state, AgentId.unsafe(agent))

  /**
   * The context `wrapToolCall` - and a tool's `execute` - is given for `call`: `state`, a run on `threadId` with
   * `config`, the call's id, and the idempotency key the loop would derive for it, the same for the same call on the
   * same thread. `approved` is what a wrapper sees when the chain runs again after an approval.
   */
  def toolContext(
    call: ToolCall,
    state: ThreadState = emptyState,
    config: RunConfig = RunConfig(),
    threadId: ThreadId = DefaultThread,
    approved: Boolean = false
  ): ToolContext =
    val run = RunContext.outsideRuntime(threadId, config)
    ToolContext(run, ToolCallId(call.id), IdempotencyKey.ofCall(threadId, run.taskKey, call), state, approved)

  /** The model usage the hooks given `context` have recorded so far, through `accounted` clients and `recordUsage`. */
  def usage(context: ModelContext): UsageSummary = context.run.usage.summary

  /**
   * The ids of `middleware` in the order a stack runs them, outermost first - `runsBefore` and `runsAfter` applied,
   * ties broken by the order given - or the `ValidationError` an agent's `build()` would return for them: an invalid
   * or duplicate id, a required constraint naming a middleware that is absent, a cycle, a tool contributed twice, or a
   * refused `placement`. It checks the middleware given and nothing else: an agent adds its own (a harness's, the
   * builder's guardrails, `delegation`), and checks a placement across a handoff family.
   */
  def order(middleware: AgentMiddleware*): Result[Vector[MiddlewareId]] =
    MiddlewareStack.of(middleware*).map(_.ordered.map(_.id))
