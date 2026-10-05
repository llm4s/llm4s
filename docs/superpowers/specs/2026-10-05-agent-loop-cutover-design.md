# Agent loop cutover - design (#1328)

Slice 2 of [#1326](https://github.com/llm4s/llm4s/issues/1326), Stage 1 of the typed agent runtime
([#1266](https://github.com/llm4s/llm4s/issues/1266)), and the first Stage 1 slice to land. Module
`llm4s-agent`, with callers in `llm4s-effect`, `llm4s-zio`, `workspaceClient`, `observability`, `it`
and `samples`. Builds on Stage 0: `GraphRuntime` and `RunHandle` (#1277), `AgentTool` and `ToolLoop`
(#1278), `AgentMiddleware` and `GuardrailMiddleware` (#1279). Slices 3 (#1329, events and tracing)
and 4 (#1330, orchestration) build on it.

## Goal

Make the graph runtime the sole agent loop:

- `Agent.run`, `continueConversation`, `runMultiTurn`, and new `recover` and `resume`, run on
  `GraphRuntime` through a generalised `ToolLoop`, with `AgentTool`s and middleware.
- Data-only thread state replaces `AgentState`; no live `ToolRegistry`, `Handoff` or `Agent` in
  state.
- Handoffs are routes inside one compiled graph, by stable `AgentId`.
- A guardrail block is a defined terminal outcome, not a stuck `Running` thread.
- The legacy loop is deleted, and every caller migrates.

Nothing in `llm4s-core` changes. Nothing is frozen: replaced API is deleted, with one migration note
for Stage 1, and no shim runs the old loop beside the new one.

## Decisions

| Question | Decision |
|---|---|
| How a conversation is carried between turns | By `ThreadId` on a `GraphRuntime` (in-memory by default). A turn on a completed thread is the runtime's "start on an existing thread". `AgentResult` is a readable value, not something passed back in; `continueConversation(previous, ...)` reads only its `threadId`. Stage 2 durability is a different checkpointer, not a new API. |
| A guardrail block | A normal terminal outcome, `AgentStatus.Blocked`, with the thread `Completed`. An input block commits nothing else; an output block replaces the stored answer with a refusal message. Not a `Left`: callers handle it as an outcome and still get the thread and usage. |
| Handoffs | Routes inside one graph: the root agent and every agent reachable through handoffs are compiled together, keyed by `AgentId`; thread state records the active agent. Not removed (delegation in Stage 4 is a different pattern), and not a run ending in `HandedOff` that callers must glue. |
| Context-window pruning | A model-call middleware that trims what is sent. The thread keeps its full history, for recovery, resume and Stage 3 summarisation. |
| Event streaming between slices 2 and 3 | Slice 2 deletes `runWithEvents`, `runCollectingEvents`, `continueConversationWithEvents`, `AgentStreamingExecutor` and `AgentEvent`; slice 3 adds their replacement. 0.5.0 (#1281) is not cut between the two. |
| How the loop is built | Generalise `ToolLoop` into a multi-agent graph with per-agent node prefixes. Not kernel subgraphs, which Stage 4 needs in another form (isolated child threads); not a new graph beside `ToolLoop`, which would duplicate its hardened pipeline. |
| Where tools and guardrails are given | On the agent, at build time, not per `run`. The compiled graph's fingerprint covers them, and a resumed thread must see the graph it ran on. |

## Public API

### Construction

```scala
val agent: Result[Agent] = Agent
  .builder("assistant", client)              // AgentId, LLMClient
  .withTools(tools)                          // ToolSet, or a ToolRegistry adapted to agent tools
  .withSystemPrompt("You are ...")
  .withCompletionOptions(options)
  .withMiddleware(GuardrailMiddleware(...), ContextWindowMiddleware(config), ApprovalMiddleware(...))
  .withHandoffs(Handoff.to("physics", physicsBuilder, "physics questions"))
  .withMaxSteps(50)                          // model calls per turn; default Agent.DefaultMaxSteps
  .withRuntime(GraphRuntime.inMemory())      // the default
  .withTracing(tracing)                      // optional; see Tracing
  .build()
```

- `AgentBuilder` is immutable; every `with*` returns a new builder. `build()` returns `Result[Agent]`
  and refuses: tool name clashes and owned-key conflicts (as `ToolLoop.build` does today), a handoff
  ID that does not match `Handoff.isValidId`, and one `AgentId` reached twice with different
  builders.
- `AgentId` is an opaque type in `org.llm4s.agent` with the handoff ID's pattern
  (`[a-zA-Z0-9_-]{1,52}`), built by `AgentId.of(String): Result[AgentId]`; `builder` takes a
  `String` and `build()` reports an invalid one.
- `Handoff` keeps `id`, `transferReason` and `preserveContext`, and its target becomes an
  `AgentBuilder`, so cycles (A hands off to B, B back to A) are resolved by ID at build time.
  `transferSystemMessage` is removed: each agent sends its own prompt (see Thread state).
- A `ToolRegistry` - `MCPToolRegistry` included - is adapted with the existing `ToolFunction`
  adapter into a `ToolSet`.

### Running

All blocking calls are `start(...)` followed by `RunHandle.await()`, mapped to `AgentResult`.

```scala
def run(query: String, config: RunConfig = RunConfig()): Result[AgentResult]   // new thread, random ThreadId
def run(threadId: ThreadId, query: String, config: RunConfig = RunConfig(),
        history: Seq[Message] = Nil): Result[AgentResult]
def continueConversation(previous: AgentResult, query: String,
                         config: RunConfig = RunConfig()): Result[AgentResult]  // run(previous.threadId, query, config)
def runMultiTurn(first: String, followUps: Seq[String],
                 config: RunConfig = RunConfig()): Result[AgentResult]          // stops at the first non-Completed status
def recover(threadId: ThreadId, config: RunConfig = RunConfig()): Result[AgentResult]
def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value],
           config: RunConfig = RunConfig()): Result[AgentResult]
def start(threadId: ThreadId, query: String, config: RunConfig = RunConfig(),
          history: Seq[Message] = Nil): Result[RunHandle[AgentResult]]
```

- `run(threadId, ...)` on an unknown thread creates it; on a `Completed` thread it is the next
  turn. The runtime's refusals (`ThreadBusy`, `TenantMismatch`, `IncompleteRun`,
  `PendingInterrupts`) are returned unchanged.
- `history` imports conversation history, not execution: it is accepted only when the thread does
  not exist, and is seeded before the first user message (after `Message.validateConversation`).
  On an existing thread a non-empty `history` is a `ValidationError`. System messages in it are
  refused: prompts are the agents'.
- `runMultiTurn` with `contextWindowConfig` is gone: pruning is `ContextWindowMiddleware` on the
  agent.
- `start` exists in slice 2 for `cancel()`; slice 3 adds agent-level subscription and streaming.
- `forget(threadId: ThreadId, config: RunConfig = RunConfig()): Result[Unit]` removes a thread
  from the runtime (`GraphRuntime.deleteThread`, over a new `Checkpointer.deleteThread`). Threads -
  one-shot `run(query)` threads included - stay in the runtime until forgotten. It refuses a thread
  whose run is active in this runtime (`ThreadBusy`) or of another tenant (`TenantMismatch`); an
  unknown thread is `Right(())`.

### Result

```scala
final case class AgentResult private (
  threadId: ThreadId,
  runId: RunId,
  activeAgent: AgentId,
  status: AgentStatus,
  messages: Vector[Message],     // the thread's full history, without system prompts
  usage: UsageSummary            // accumulated over the thread
):
  def answer: Option[String]                                   // Some for Completed
  def approve(id: InterruptId): (InterruptId, ujson.Value)
  def reject(id: InterruptId, reason: String): (InterruptId, ujson.Value)
  def edit(id: InterruptId, arguments: ujson.Value): (InterruptId, ujson.Value)
  def reply[Ans: ReadWriter](id: InterruptId, value: Ans): (InterruptId, ujson.Value)

enum AgentStatus:
  case Completed(answer: String)
  case Blocked(guardrail: String, reason: String)
  case StepLimitReached
  case Suspended(
    approvals: Vector[(InterruptId, ApprovalRequest)],
    questions: Vector[(InterruptId, ToolQuestionRequest)]
  )
```

`AgentResult` follows the growth-prone type pattern (private constructor, no public `copy`). It is
built only by `Agent`.

### Removed

- `runStep`, `initializeSafe`: step-by-step driving returns as a subscription in slice 3.
- `runWithStrategy`, `continueConversationWithStrategy`, `ToolExecutionStrategy`:
  `RunBudgets.maxConcurrency` bounds parallel tool calls.
- `runWithEvents`, `continueConversationWithEvents`, `runCollectingEvents`, `AgentEvent`,
  `AgentStreamingExecutor`: slice 3.
- `AgentContext`: tracing moves to `withTracing`; `debug` and `traceLogPath` go.
- `AgentState` (with `dump`, `saveToFile`, `loadFromFile`, `pruneConversation`, `toTraceEvent`),
  the old `AgentStatus`, `ToolProcessor`, `GuardrailApplicator`, `HandoffExecutor`,
  `AgentTraceFormatter`, `formatStateAsMarkdown`, `writeTraceLog`.

## Graph and thread state

### One graph per agent family

`build()` collects the root agent and every agent reachable through handoffs, deduplicated by
`AgentId`. `ToolLoop.build` is generalised to take a non-empty list of agent specs - `AgentId`,
`ModelStep`, system prompt, `ToolSet`, middleware, handoffs, `preserveContext` per handoff, max
steps - and a root. Each spec contributes the current loop's node set under its ID:
`<id>/model`, `<id>/call-tool`, `<id>/approval`, `<id>/ask/<tool>`, `<id>/collect`, `<id>/finish`,
and its own `tool-batch` join. One shared `input` node is the entry.

The graph ID is the root's `AgentId`; its version is a hash of the family's structure - each
agent's ID, tool names and schemas, middleware IDs, handoff targets and system prompt - so a
changed agent changes the fingerprint and the runtime refuses a thread from the old graph instead
of misreading it. The completion options and model client are not part of the version: they are
rebound on restore, as #1277 defines.

`ModelStep.next` returns the provider's `Completion` rather than only its `AssistantMessage`, so
the model node can record usage. `ModelStep.fromClient` stays the way a client becomes a step.

### Keys

| Key | Type | Written by |
|---|---|---|
| `messages` | `Vector[StoredMessage]`, the shared history, never a system prompt | loop nodes only (owned) |
| `active-agent` | `AgentId` | `input` on a new thread, handoff routes (owned) |
| `usage` | `UsageSummary`, per model | model nodes (owned) |
| `turn` | `TurnState(steps: Int, outcome: Option[TurnOutcome])`, reset by `input` | `input`, model, finish (owned) |
| `tool-results` | unchanged | loop (owned) |

`TurnOutcome` is `Completed`, `Blocked(guardrail, reason)` or `StepLimitReached`. Tools and
middleware keep declaring their own keys; a declaration of an owned key is refused at build, as
today for `messages` and `tool-results`.

### Turn flow

1. `input` takes `AgentInput(query, history)`. On a new thread it seeds `history` and sets
   `active-agent` to the root. It runs the root's `beforeAgent` stack on the query and then, when
   another agent is active, that agent's stack on the result; appends the user message, resets
   `turn`, and routes to `<active>/model`.
2. `<id>/model` compares `turn.steps` with the agent's max steps. At the limit it sets
   `outcome = StepLimitReached` and ends without calling the model. Otherwise it increments
   `steps`, prepends the agent's system prompt to the messages it sends (the stored history is
   unchanged), calls `wrapModelCall(ModelStep.next)`, adds the completion's usage, and continues
   as the current loop does.
3. Tool calls fan out to `<id>/call-tool`, join at `<id>/collect`, and return to `<id>/model`,
   unchanged from #1278 and #1279.
4. `<id>/finish` runs its agent's `afterAgent` stack and then, for an agent other than the root,
   the root's, and sets `outcome = Completed`.
5. The graph's output reads `turn` and `active-agent`; `Agent` adds `messages` and `usage` from the
   final state to build `AgentResult`.

### Handoffs

Each handoff target is offered to the model as a tool `handoff_to_<id>` with the description
`Handoff.handoffName`, built from the existing handoff-tool schema.

- When an assistant message's tool calls include exactly one handoff call and nothing else,
  `<id>/model` stores the assistant message, appends `ToolMessage("Transferred to <target>")` for
  that call, sets `active-agent = target`, and routes to `<target>/model`. The handoff counts as
  that model call's step; the target's first call counts as another.
- A handoff call mixed with other tool calls, or two handoff calls, is refused: every call in the
  message gets an error result explaining the rule, and the batch returns to the same agent's
  model. The model never sees a partial batch.
- With `preserveContext = false`, the target's model node sends the last `UserMessage` before the
  transfer, then the transfer's assistant message onwards, so the request starts with the user's
  question. The view is computed at each call from a loop-owned record of the transfer (source,
  target and the transfer's assistant message id), never stored; a non-root agent with no recorded
  transfer to it fails rather than being sent the full history. The history itself is untouched.
- The next turn starts with the active agent, so a conversation that moved to a specialist stays
  with it until the specialist hands it back.
- The root agent's run-boundary hooks (`beforeAgent`, `afterAgent`) are family-wide: they run on
  every turn's query and every final answer, whichever agent is active, outside the active agent's
  own (root `beforeAgent` first, root `afterAgent` last). So a root `GuardrailMiddleware` keeps
  guarding a conversation after it moves to a specialist. A target's own boundary hooks apply only
  while it is active; `wrapModelCall` and `wrapToolCall` stay per agent.

### Guardrail blocks

`GuardrailMiddleware` returns a dedicated `GuardrailBlocked(guardrail: String, reason: String)`
(`NonRecoverableError`, in `org.llm4s.agent.graph.middleware`) when a guardrail refuses. Any other
`Left` from a hook keeps failing the run, as today.

The block may come from the root's boundary stack or the active agent's: the root's hooks are
family-wide (see Handoffs).

- In `input`: the node writes `turn = TurnState(0, Some(Blocked(...)))` and ends. The query is not
  appended and usage does not change; on a new thread the `history` seed and `active-agent = root`
  are still written, so the imported conversation is kept. The thread completes, and the next turn
  works.
- In `<id>/finish`: the stored answer is replaced with a refusal assistant message, by default
  ``Response withheld by guardrail `<name>`: <reason>``, settable per `GuardrailMiddleware` (the
  refusal of the middleware that raised the block, in whichever stack raised it; the default when
  that text is blank); `outcome = Blocked`. The blocked text never reaches state, and the model
  sees on the next turn that it refused.

### Context window

`ContextWindowMiddleware(config: ContextWindowConfig)` is a model-call middleware. Its
`wrapModelCall` applies the configured `PruningStrategy` to the request's messages and calls the
next wrapper with the pruned list. The strategies and their token counting move from
`AgentState.pruneConversation`, with added rules: the current turn (from the latest user message)
is never pruned - the strategy, `Custom` included, runs on the history before it with the budget
the turn leaves, and the turn is appended after, by position rather than by reference; pruning
never keeps a tool result without its assistant call or the reverse; and the pruned list passes
`Message.validateConversation`.

## Errors, suspension and recovery

A run that reaches the graph's end returns `Right(AgentResult)` with `Completed`, `Blocked` or
`StepLimitReached`. A run that parks returns `Right` with `Suspended`, its approvals and questions
read through the active agent's `ToolLoop.requests` and `questions`. Everything else is `Left`:

| Situation | Result | Thread |
|---|---|---|
| `ThreadBusy`, `TenantMismatch`, `IncompleteRun`, `PendingInterrupts`, `NothingToRecover`, `NotSuspended`; `history` on an existing thread | `Left`, no run | unchanged |
| Tool `Fatal`, middleware throw, provider error, a hook's non-guardrail `Left` | `Left(GraphError...)` | `Running`; `recover` re-runs only failed or unstarted work |
| `RunHandle.cancel()`, interrupt | `Left(GraphError.Cancelled)` | `Running`, recoverable |
| Deadline | `Left(GraphError.DeadlineExceeded)` | recoverable with a new budget |

No agent error hierarchy is added: agent-level failures are `GraphError`s, and `GuardrailBlocked`
never escapes the loop.

## Tracing

`withTracing(tracing: Tracing)` attaches `TracingSubscriber` to each run's thread, from the
sequence the run starts at, and detaches when the run ends. Runs are traced as the `graph.*` custom
events defined by #1277. The new loop does not emit `TraceEvent.AgentStateUpdated`; the type stays
in core until slice 3 rewrites its consumers.

## Migration of callers

- **`org.llm4s.assistant`**: `AssistantAgent` builds its `Agent` once, keeps a `ThreadId` instead
  of an `AgentState`, and calls `run` instead of its `runStep` loop. `SessionState` holds the thread
  ID and the last `AgentResult`; `SessionManager` saves messages, and loading a session imports
  them with `history` into a new thread.
- **`llm4s-effect`** (`AgentIO`, `LLMClientIO.agent`) and **`llm4s-zio`** (`AgentZ`,
  `LLMClientZ.agent`): they wrap an `Agent` and expose `run`, `continueConversation`, `recover` and
  `resume`, returning `F[AgentResult]` and `ZIO[Any, LLMError, AgentResult]`. Each runs `start` and
  awaits the handle; fiber interruption calls `handle.cancel()`. `LLMClientIO.agent` and
  `LLMClientZ.agent` take the agent's builder settings (or an `AgentBuilder`), since tools now belong
  to the agent.
- **`workspaceClient`**: `CodeWorker` returns `Result[AgentResult]`; `CodeGenExample` reads
  `status`, `messages` and `answer`.
- **`observability`**, **`it`**: specs move to the builder and `AgentResult`;
  `LangfuseTracingEdgeCasesSpec` builds `TraceEvent.AgentStateUpdated` directly.
- **`samples`** (about 33 files): mechanical moves to `builder(...).build()` and `AgentResult`.
  The four `runStep` samples (`SingleStepAgentExample`, `MultiStepAgentExample`,
  `AgentLLMCallingExample`, `PlaywrightExample`) become runs of the new API, and one of them shows
  approval suspension and resume. `ConversationPersistenceExample` saves messages and reloads them
  with `history`. `ContextPreservationExample` and the handoff samples use builder targets. The two
  streaming samples and the `runWithStrategy` sample are deleted; `docs/examples` points to #1329
  for them.

## Documentation

- The design doc gains a §4.10, "Stage 1: agent loop on the runtime" (#1328), in the style of
  §4.5-4.8; the current §4.10 (durable workflow API) is renumbered §4.11, and §4.9's carry-forward
  rows owned by this slice are marked closed.
- `CLAUDE.md`'s Agent Framework section and `docs/guide` agent pages show the builder and
  `AgentResult`.
- One Stage 1 migration note in `CHANGELOG` and the docs, covering `AgentState`, `AgentContext`,
  per-run tools and guardrails, handoffs, pruning, the removed methods and session files. Slices 3
  and 4 extend it.

## Testing

New specs in `llm4s-agent` (`org.llm4s.agent`), with scripted `ModelStep`s and no network:

- `AgentRunSpec`: a single turn; parallel tools bounded by `maxConcurrency`; one result per call;
  usage accumulated across model calls; the system prompt sent and never stored.
- `AgentConversationSpec`: `continueConversation` and `runMultiTurn` on one thread; `run(threadId,
  ...)` on new and completed threads; `history` seeding a new thread and refused on an existing one
  or with a system message; `IncompleteRun` and `PendingInterrupts` refusals.
- `AgentGuardrailSpec`: an input block leaves messages and usage unchanged, the thread `Completed`
  and the next turn working; an output block replaces the stored answer; a non-guardrail `Left`
  fails the run and `recover` completes it.
- `AgentHandoffSpec`: a transfer runs the target with its own prompt and tools; `active-agent`
  carries the next turn; an A-B cycle compiles and runs; `preserveContext = false` limits what the
  target is sent; a mixed handoff batch is refused; a duplicate `AgentId` is refused at build.
- `AgentRecoverySpec`: a failed sibling, then `recover` without re-running completed siblings;
  cancel through `start`'s handle, then `recover`; a deadline, then `recover` with a new budget;
  `StepLimitReached`, then a further turn.
- `AgentSuspensionSpec`: approvals and questions surface as `Suspended`; partial resume, edit, reject and
  `reply` through the `AgentResult` helpers.
- `ContextWindowMiddlewareSpec`: each strategy trims what is sent and never what is stored, and
  never separates a tool call from its result; `AdaptiveWindowingSpec`'s cases ported.
- `AgentFingerprintSpec`: a changed tool, prompt or handoff changes the graph version, and a thread
  of the old version is refused.

Cases of `AgentSpec`, `HandoffSpec`, `HandoffIntegrationSpec` and `AgentGuardrailsIntegrationSpec`
that describe behaviour the new loop keeps are ported; specs of deleted code (`AgentStateSpec`,
`AgentStateSerializationSpec`, `HandoffExecutorSpec`, `AgentTraceFormatterSpec`,
`AgentStreamingIntegrationSpec`) go with it. `AgentRunTracingSpec` checks the `graph.*` events from
`withTracing`. `llm4s-effect` and `llm4s-zio` keep their fidelity and tool-validation specs and each
gain one showing that fiber interruption cancels the run. `it` suites keep their tiers.

Gates: `sbt buildAll`; no module's coverage floor lowered; `docs/doc`; `samples/compile`; a manual
run of `BasicLLMCallingExample` and one handoff sample against Ollama.

## Out of scope

- Agent-level event subscription, model token streaming and `AgentEvent`'s replacement (#1329).
- `PlanRunner`, `DAG`, `TypedAgent` and `CancellationToken` (#1330).
- Retry and cache policy, Mermaid export, the growth-prone pattern for `ToolContext` and friends
  (#1327).
- Durable checkpointers, history and fork (Stage 2); delegation to child runs (Stage 4).
