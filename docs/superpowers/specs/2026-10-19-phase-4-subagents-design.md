# Phase 4: subagents and isolation, sliced

Stage 4 of #1266, tracked in #2044. Design record: `docs/design/typed-agent-runtime-design.md` §5.5, §5.6 (nested stream handles,
child-task metrics, `invoke_agent`), §6 (row 4), §4.1 ("Subgraphs use hierarchical checkpoint namespaces and emit
events with parent and child run IDs"), the Stage 4 row of §4.9 (#1277), §2.6, and the §10 risk "make subagent
inheritance explicit". This document refines that stage and breaks it into slices: kernel slices that fix the
child-run contracts, delegation slices that run in parallel on them, and integration slices that finish the stage.

## Intent

Stage 3 gave one agent a harness. Stage 4 lets that agent hand a bounded task to another agent and get back only a
typed report: the child runs on its own thread, with a fresh conversation, only what it was granted, and budgets
no larger than its parent's remaining ones. The parent's run supervises it: cancelling the parent cancels the child,
recovering the parent reattaches to the child instead of starting it again, and a child that stops for approval
stops its parent, and resumes when the parent resumes. Delegation is opt-in on `AgentBuilder`
(`withSubagents`) and on by default only in the `HarnessProfile.deepAgents` preset.

Scope:

- Everything in §6 row 4, plus the §4.9 row "cancelling a run cancels the child runs it started" (#1277, moved here
  by #1901).
- **Out of scope** (§5.5): background child tasks with poll, cancel and join. Every child in Stage 4 is awaited by
  the tool call that started it.
- **Out of scope:** a child on a runtime or checkpointer other than its parent's (open question 7), a
  `MetricsCollector` projection of agent events (Stage 5, with the agent's other metrics), and OpenTelemetry span
  hierarchy from span lifecycles (Stage 5, "full trace integrations").
- **Handoffs stay** for conversational routing: a handoff moves the same thread to another agent of the same
  family, and that agent then owns the conversation. Delegation starts another thread and the parent keeps control.
  Neither replaces the other; the guide says when to use which.

### Exit criteria (§6 row 4)

Deep research ported to parallel specialists. Each child returns a typed final report, and the parent resumes and
composes after a child's failure and after a child's approval. It runs deterministically in CI, as P2 of Phase 3
did.

## What exists today (checked against the code)

- **Identity.** `ThreadId`, `RunId`, `TaskId`, `InterruptId`, `TenantId` and `Principal` are unrestricted opaque
  strings (`graph/GraphIds.scala`). Every `Checkpointer` call is keyed by `ThreadId` alone: claims, commits,
  history, events, effect records, `prune`, `deleteThread` (`graph/Checkpointer.scala:109-256`). A checkpoint
  records `threadId`, `runId`, `tenantId` and `fencingToken`. `Checkpoint.CurrentFormat` is 7, with a migration per
  step (`graph/Checkpoint.scala:84`). The SQLite schema is at version 5 (`SqliteSchema.Current`).
- **Stable keys.** `RunContext.taskKey` and a tool call's `IdempotencyKey` (`IdempotencyKey.ofCall`) are stable
  across `recover`, `updateState` and a re-run after approval. `runId`, `checkpointId` and the fencing token are
  not stable (Phase 3 rule 4).
- **Cancellation.** `RunHandle.cancel` interrupts the run, and Ox interrupts its tasks. A blocking `Agent.run`,
  `recover` or `resume` cancels its turn when its thread is interrupted, and waits at most `AgentRun.Drain` (5 s)
  for the turn to end (§4.15, `Agent.blocking`). So an agent awaited inside a task is already cancelled with that
  task. What is missing: a child started without awaiting it, and a recovered parent finding its child.
- **Budgets.** `RunBudgets(maxSupersteps, timeout, maxConcurrency)` (`graph/RunConfig.scala:16`). There is no token
  budget. `AgentBuilder.withMaxSteps` bounds model calls per turn (`TurnOutcome.StepLimitReached`).
- **Tools and suspension.** `ToolOutcome` = `Success | Error | NeedsApproval | Ask[Q] | Fatal`. `AgentTool.Asking`
  resumes with a typed answer, and a resume may ask again, as a new phase with its own effect keys
  (`ToolContext.effectKey`). Middleware may contribute tools and prompt sections (`AgentMiddleware.tools`,
  `promptSections`). `AgentStatus.Suspended` has `approvals`, `questions`, `middlewareQuestions` and `breakpoints`.
- **Replay.** `ReplayGuard` and `EffectJournal` (§4.24) guard external effects. Their records are released once the
  superstep commits, so they cannot hold anything that must outlive the call.
- **Typed output.** `AgentBuilder.withOutput[O: ReadWriter](schema: SchemaDefinition[O])` and
  `AgentResult.decodeOutput[O]` (§4.26). `llm4s-schema-derivation` gives `SchemaOf[A].definition`, and does not
  depend on the agent. `TypedAgent` was deleted with `org.llm4s.agent.orchestration` (§4.15): it crossed
  `Map[String, Any]` boundaries, which §8 rules out.
- **Events and tracing.** `RunEvent`, `EventRecord`, `StreamEvent` = `Durable | Live | LiveGap | Disconnected`,
  typed `EventType[A]` and `EventHandlers` (F9), adapters `AgentIO`, `AgentZ` and `AgentPekko`. `AgentTracing` ends
  each run with core's `TraceEvent.AgentRunEnded(threadId, runId, agent, status, messages, usage, timestamp)`. The
  OpenTelemetry backend turns each `TraceEvent` into one instantaneous span named `Agent Run` and so on
  (`OpenTelemetryTracing.mapEventToAttributes`). It has no parent links and no `gen_ai.operation.name`.
- **Deep research (Phase 3 P2).** `modules/samples/.../harness/DeepResearch.scala`: one agent, typed `Brief`,
  `fetch_page`, SQLite checkpointer, `StoppingCheckpointer` for crashes, `DeepResearchAcceptanceSpec` on
  `ScriptedLLMClient` with `ResearchModel`.

## Placement

- Everything is in `llm4s-agent`, which takes no new dependency. Delegation goes in a new package,
  **`org.llm4s.agent.delegation`**. Child-thread identity, the SPI and supervision go in `org.llm4s.agent.graph`.
- The SQLite store's schema step is in `llm4s-agent-checkpoint-sqlite`. The contract cases are in
  `llm4s-agent-testkit` (`CheckpointerContract`, plus a new `DelegationContract`).
- The **one core change** is two `Option` fields on `TraceEvent.AgentRunEnded` (D4). Core gains no type.
- Span names are in `llm4s-observability-otel`. Parent links in Langfuse metadata are in `llm4s-observability`.
- Nested-stream adapters are in `llm4s-effect`, `llm4s-zio` and `llm4s-pekko`.
- The new `AgentStatus` case and field are mapped in `java-api`'s `FacadeInternals`.
- No new module.

## Slice map

| # | Issue | Slice | Kind | Depends on |
|---|---|---|---|---|
| K1 | #2045 | Child threads: `RunOrigin`, hierarchical ids, `Checkpointer.children`, cascade delete, child retention | kernel | - |
| K2 | #2046 | Child-run supervision: start at most once, reattach on replay, cancellation and deadline propagation, usage roll-up | kernel | K1 |
| K3 | #2047 | Token budgets and the run's deadline: `RunBudgets.maxTokens`, `TokenLimitReached`, `RunContext.deadline` | kernel | - |
| D1 | #2048 | `TypedAgent[I, O]`, the `task` tools, parallel bounded children | delegation | K2 |
| D2 | #2049 | Explicit inheritance: grants, narrowing permissions, budgets, `DelegationPlan` | delegation | D1, K3 |
| D3 | #2050 | Child suspension through the parent | delegation | D1 |
| D4 | #2051 | Nested streams, parent and child ids in events and tracing, child-task metrics | delegation | D1 |
| P1 | #2052 | Subagents in `HarnessProfile`: general-purpose subagent, specialists, config | integration | D1, D2 |
| P2 | #2053 | Exit example: parallel-specialist deep research | integration | all |

Parallel lanes:

1. **From the start:** K1 and K3.
   - D4's core field and OTel span names can land at any time. They do not need a child run to exist.
2. **After K1:** K2. D1 can be written alongside it, against K2's specified `ChildRunner`, and merges after it.
3. **After D1:** D2, D3 and D4, three lanes, each with its own files. D2 also needs K3.
4. **Last:** P1, then P2.

The critical path is K1 → K2 → D1 → D2 → P1 → P2. K1 changes the checkpoint format and the `Checkpointer` SPI,
which third-party stores implement, so it is reviewed first.

Design records, numbered from 4.41 because 4.39 and 4.40 are taken:

| # | Record |
|---|---|
| K1 | `4.41-stage-4-slice-k1-child-threads.md` |
| K2 | `4.42-stage-4-slice-k2-child-run-supervision.md` |
| K3 | `4.43-stage-4-slice-k3-token-budgets.md` |
| D1 | `4.44-stage-4-slice-d1-typed-agent-and-task.md` |
| D2 | `4.45-stage-4-slice-d2-inheritance.md` |
| D3 | `4.46-stage-4-slice-d3-child-suspension.md` |
| D4 | `4.47-stage-4-slice-d4-nested-streams-and-tracing.md` |
| P1 | `4.48-stage-4-slice-p1-harness-subagents.md` |
| P2 | `4.49-stage-4-slice-p2-parallel-deep-research.md` |

## Runtime rules every slice must satisfy

Phase 3's thirteen rules still hold (`2026-10-13-phase-3-harness-design.md`, "Runtime rules"). Each Phase 4 design
record has a section stating how it meets each of them and each rule below, and its tests cover each rule that
applies. This is how delegation meets the thirteen:

1. **One batch, one snapshot.** Parallel `task` calls of one model message each see the same parent snapshot. A
   child is built only from its call's arguments and the parent's immutable configuration, never from a sibling.
   - A bound checked against the snapshot is exact only where the snapshot holds the whole answer. The per-turn
     task count is (D1: the batch's calls are in the committed assistant message).
   - The parent's remaining token budget is not exact: parallel children each get a share computed from one
     snapshot (D2).
   - External backends shared by parallel children (a `HostFileBackend`) are not ordered by batch locks, which act
     within one thread. Their own compare-and-set (§4.40) detects a conflicting write. The docs say to give parallel
     children distinct paths.
2. **An update function that fails, fails the run.** The `task` tools write no parent state key. A child that
   failed, a report too large, or a fan-out over its bound is decided when the tool runs, as a tool `Error`.
3. **An update becomes visible from the next superstep.** A child's report reaches the parent model as the call's
   tool message in the next model step. Nothing a child does is visible to its siblings.
4. **Hooks and tools run at least once.** A `task` call runs again on `recover`. The child thread is the
   delegation's replay record (rule 14). No `ReplayGuard` record is used for the child, because those are released
   when the superstep commits.
5. **A Block retracts what is in the thread.** A parent output Block restores the parent's keys (C1). Child threads
   and their external effects are outside the parent's thread and stay. Retention removes the orphans (K1). The
   docs say so. A Block inside a child ends the child `Blocked`, which the parent sees as a tool error.
6. **Every checkpoint is a full snapshot.** A child's checkpoints snapshot only the child's thread. The parent's
   snapshot holds only the tool result, bounded by `maxReportBytes`. Child threads are bounded by child retention
   (K1).
7. **Restore decodes through each codec.** Checkpoint format 8 adds `origin`, with a migration from 7 (none). SQLite
   schema 6 adds the parent column. Both are tested on a fixture written by `main` before the change, as #1821 did.
   `AgentStatus` is not stored.
8. **Tenant.** A child always runs with its parent's `tenantId` and `principal`. They are never part of a grant
   (rule 16). `children` and child subscriptions check the tenant as the thread's other reads do.
9. **Durable events carry no message content.** `TaskEnded` carries ids, the agent, a status label, usage and a
   duration, never the request or the report. Nested child text deltas are live-only.
10. **A failed task commits nothing.** `TaskEnded` commits with the call's terminal result (D4). A parent task that fails after
    its child ended loses nothing: the child's thread keeps its usage and outcome, and the replay records them again
    (K2). A cancelled child's usage is on its own `RunCancelled` event.
11. **Every model call is accounted.** A child's model calls are counted in the child's thread. When the `task` call
    commits, the child's usage is added to the parent task's accumulator, once. That is the child's committed usage
    plus the usage on its terminal event (K2).
12. **Everything is bounded on its own.** Each of these has a limit enforced without relying on any other feature:
    - concurrent children per parent run (`maxConcurrentChildren`)
    - task calls per turn (`maxTasksPerTurn`)
    - depth (`maxDepth`)
    - report size (`maxReportBytes`)
    - child threads kept per parent (`childRetention`). A pruned child leaves the store's ordinary tombstone (its
      next event number, a few bytes), kept for good as every deleted thread's is (`Checkpointer.deleteThread`);
      bounding tombstones is a store-wide retention question for Stage 5 (#2059), not a delegation rule
    - each child's steps, time and tokens (D2)
    - nested live events per subscription (the subscription's own queue, `LiveGap` on overflow)
13. **Every named type exists or is defined where it is introduced.** As before.

Delegation adds these:

14. **The child thread is the journal.** A child's `ThreadId` is derived from the parent thread, the call's
    `IdempotencyKey` and the subagent's id (K1). The child is started with `startNew`, which refuses an existing
    thread (`ThreadExists`), so it is started at most once. Every run of the call, `recover` included, reads the
    child's latest checkpoint first and continues from it:

    | Child's latest checkpoint | What the call does |
    |---|---|
    | none | start the child |
    | `Running` | `recover` the child, or wait while another run holds its claim |
    | `Suspended` | surface the child's interrupts again (D3) |
    | `Completed` or `Failed` | return the outcome already there |

    A completed child is never run again. It is reused only by the call that made it: the same thread and the same
    key. A fork or another thread derives other ids.
15. **No capability without a grant.** A child has its own declared tools and middleware, and nothing of the
    parent's unless granted (D2). In particular it never gets the `task` tools unless delegation is granted, and
    then only to `maxDepth`.
    - **Permissions only narrow.** A child's call runs only if the child's own rules allow it *and* every ancestor's
      `PermissionMiddleware` rules allow it. An ancestor with no rules adds no check.
    - `Ask` from either side surfaces through the parent (D3).
16. **Identity is inherited, never granted.** Tenant, principal, tracing, the runtime and the checkpointer always
    come from the parent. A child that could change them would be a confused deputy.
17. **Only typed data crosses.** The parent sends `I`, rendered as the child's first user message. It gets back `O`,
    decoded from the child's answer. The child's messages never enter the parent's thread, and the parent's never
    enter the child's.
18. **Supervision flows down.** These reach every running child:
    - the parent's cancellation
    - its deadline
    - its lost claim
    - a tool timeout on the `task` call

    A child's deadline is never later than its parent's. A child's concurrency is never above its parent's.
19. **Child threads are driven only through their parent.** `start`, `recover`, `resume`, `fork` and
    `updateState` on a child thread are refused (`GraphError.ChildThread`). `history`, `checkpoint`, `subscribe`
    and `children` are allowed, subject to the tenant check.

## Kernel slices

### K1: child threads (#2045, 4.41)

Today a thread has no parent. `deleteThread` removes one thread, and nothing can list the threads a run created.

- **`RunOrigin`**, in `org.llm4s.agent.graph`, is a growth-prone `final case class RunOrigin private (parent:
  ThreadId, call: IdempotencyKey, agent: AgentId, depth: Int, startedAt: Instant)` with `apply` and `with*`
  setters, and a `ReadWriter`.
  - `depth` is 1 for a child of a top-level thread.
  - `startedAt` is when the child was first admitted. Copied forward with the origin, it is the durable start of
    the child's whole life across suspensions, restarts and event retention (D4's `TaskEnded.duration`).
- **`Checkpoint.origin: Option[RunOrigin]`**, format **8**. `7 -> 8` writes nothing, because the field defaults to
  `None`. A build that predates it refuses format 8, rather than drive a child thread as a top-level one.
  - The origin is written on the child's first claim commit and copied forward on every checkpoint, as `tenantId`
    is.
- **`Checkpoint.outcome: Option[RunOutcome]`**, also format 8 (`None` from 7). The durable record of how a run
  settled, written atomically with every terminal (`Completed` or `Failed`) checkpoint of every thread, child or
  not, and cleared when a later run of the thread starts. `CheckpointStatus` and `RunCompleted` alone cannot rebuild
  an `AgentStatus` (a final answer and `StepLimitReached` both complete; a Block's typed `GuardrailBlocked` is not
  kept on a `Failed` checkpoint), so a settled run is read only through `outcome`.
  - `RunOutcome` is a small enum with a `ReadWriter`: `Answered`, `StepLimitReached`, `TokenLimitReached` (K3),
    `Blocked(guardrail: String, reason: String)`, `OutputInvalid(errors: Vector[String])`,
    `Failed(kind: String, message: String)`. The answer itself is already in the thread's state, so `Answered`
    carries none.
  - **Lossless for delegation.** Every `TaskRefusal` (D1) is rebuilt from exactly one `RunOutcome` case carrying all
    its fields, so a replayed refusal equals the live one. `OutputInvalid` keeps the whole error vector, which
    `GraphError.OutputInvalid.message` does not. A test enumerates the `TaskRefusal` cases against it.
  - The agent layer supplies it to the runtime with the turn's terminal result. A graph run that is not an agent
    turn writes `Answered` on completion and `Failed` on failure.
  - `AgentRun.status` for a settled thread reads `outcome` too, so a status rebuilt after a restart equals the one
    returned live.
- **`ThreadId.child(parent: ThreadId, agent: AgentId, call: IdempotencyKey): ThreadId`** gives
  `<parent>/~task/<agent>/<digest>`. The digest is 24 hex characters of the SHA-256 of
  `("llm4s-child-v1", parent, call, agent)`.
  - The id is hierarchical, so a store or an operator can see the nesting by prefix.
  - Whether a thread is a child is decided by its checkpoint's `origin`, never by the id's shape: a caller who names
    a top-level thread like this gets a top-level thread.
  - Its length grows with depth, which `maxDepth` bounds.
- **`Checkpointer.children(parent: ThreadId, after: Option[ThreadId], limit: Int): Result[Vector[ChildThread]]`** is
  a new abstract method, so third-party stores must implement it. It pages the direct children of `parent` in
  creation order. It needs no claim.
  - `ChildThread(threadId, origin: RunOrigin, status: CheckpointStatus, latest: String, updatedAt: Instant)`.
  - `InMemoryCheckpointer` keeps an index.
  - SQLite **schema 6** adds `parent_thread_id TEXT` to `llm4s_checkpoint_threads`, and an index on it, filled from
    `origin` on commit.
  - A tombstoned child is not listed.
- **`GraphRuntime.children(threadId, config): Result[Vector[ChildThread]]`** checks the tenant, as `history` does.
  `Agent.children(threadId, config: RunConfig = RunConfig())` forwards to it, as `history` and `checkpoint` do,
  so a tenant-scoped thread is listed by passing its tenant.
- **Admission (rule 19).** `start`, `recover`, `resume`, `fork` and `updateState` on a thread whose latest
  checkpoint has an `origin` are refused with **`GraphError.ChildThread(threadId, parent)`**, before any claim.
  - The only way in is `private[agent] GraphRuntime.startChild` / `recoverChild` / `resumeChild`, used by K2.
- **Cascade delete.** `GraphRuntime.deleteThread` (and `Agent.forget`) holds the thread and **every** descendant,
  each by its own claim, before the first deletion; then deletes depth-first, then the thread.
  - A descendant with a live claim, anywhere in the tree, refuses the whole delete with `ThreadBusy` before anything
    is removed. Once all are held the descendants are listed again; a tree that grew in between refuses too.
  - Each deletion is fenced by the root's claim **atomically in the store**: a check of the root's claim followed
    by the descendant's delete leaves a takeover race (a successor parent could take the root between the two and
    then find the child gone). `Checkpointer.deleteThread(threadId, token, fence: Option[(ThreadId, FencingToken)])`
    deletes only if, in the same transaction (or under the same lock), the thread's claim is `token` **and** the
    fence thread's current claim is the fence token; otherwise `StaleClaim` and nothing is removed. The cascade
    passes the root's token as the fence, and child retention (below) passes the parent's.
    `CheckpointerContract` tests a fence token superseded by a takeover.
  - A crash part-way leaves fewer children and the parent. Running the delete again finishes it.
  - `deleteThread` on a child thread directly is refused (rule 19).
- **Child retention (rule 12).** The runtime keeps the newest `childRetention` *prunable* children of a parent
  (default 64, set per agent by `RunConfig.withChildRetention`, and by `DelegationConfig`, D1) and cascade-deletes
  older prunable ones. A prunable child is never read again by rule 14, so deleting it is safe whatever happens to
  the parent afterwards. Prunability is decided from run boundaries, never by matching keys: a parent checkpoint
  does not keep a call's `IdempotencyKey` (`ToolResult` and `ToolMessage` carry none). The pass runs at two points:
  - **Before the parent's terminal `Completed` checkpoint**, once the store's latest checkpoint is the last
    superstep's and schedules no task: every call of the run has committed, so every settled child is prunable.
    - Under `Async` or `OnExit` durability the terminal checkpoint is not yet durable at that point, so the pass
      runs instead once the run's buffered commits have drained (the terminal checkpoint is stored), still under
      the parent's claim and before it is released. A one-shot parent is therefore within `childRetention` once
      its run ends, whatever its durability; a drain that fails leaves the pass to the next `start`.
    - A crash during the pass leaves the parent unsettled; `recover` reaches the same point and runs it again,
      which is idempotent.
    - Not run at a Block or a failure, whose next admission handles it.
  - **At `start` admission.** `start` is admitted only on a thread with no incomplete execution
    (`GraphError.IncompleteRun` otherwise), so no call of an earlier run can be replayed:
    - every settled child is prunable, whatever the parent's last status (a Block's retraction included);
    - a child of an earlier run still `Running` or `Suspended` can never be reattached, so it is first settled
      `Failed(kind = "abandoned", ...)` under its own claim (busy: skipped, retried at the next `start`), then
      counts.
  - **Fenced by the parent's claim.** Each listing and each deletion first checks the parent's fencing token, and
    the pass stops at the first mismatch, so a stale worker whose claim was lost never deletes a child that a
    successor run just made.
  - Never at `recover` or `resume` admission, and never a child of the current run before its call commits.
  - A failed delete is logged at WARN, as retention prune is.
- **`CheckpointerContract`** gains cases for:
  - origin round trip and copy-forward
  - `outcome` written with each terminal checkpoint, cleared by the next run, absent on a format-7 fixture
  - `children` paging, tenant-free reads, tombstones not listed
  - a format-7 fixture read as `origin = None`

Tests:
- the format-7 fixture and a SQLite schema-5 file, each restored and continued
- a child id stable for one key, different for another key, thread or agent
- every refused operation on a child thread, with the allowed reads
- cascade delete through two levels; a busy descendant after a deletable sibling refuses the delete with nothing
  removed
- retention: `childRetention = 1`, two calls, one child left when the parent completes; a crash during the pass is
  finished by `recover`; at `start`, a Block's children are pruned and an earlier run's unsettled child is
  settled `abandoned`; a lost parent claim stops the pass before any deletion; never a child of the current run
  whose call has not committed

### K2: child-run supervision (#2046, 4.42)

Closes the §4.9 row "cancelling a run cancels the child runs it started" (#1277).

- **`ChildRunner`**, `private[agent]` in `org.llm4s.agent.delegation`:
  `run(agent: Agent, origin: RunOrigin, input: AgentInput, config: RunConfig, ctx: ToolContext): Result[ChildEnd]`.
  - `ChildEnd` = `Ended(result: AgentResult, usage: UsageSummary)` or `Failed(error: LLMError, usage: UsageSummary)`.
  - `ChildEnd` is internal. D1 maps it to the public `TaskOutcome`.
- **Start or reattach (rule 14).** The runner computes the child id and reads `latest`:
  - **none:** `startChild`. A `ThreadExists` race (another runtime started it) loops back to the read.
  - **`Running`:** `recoverChild`.
    - A `ThreadBusy` is waited on interruptibly, with backoff, until the claim frees or
      `min(2 × ClaimPolicy.ttl, parent's remaining deadline)` passes. The holder is a lapsed run, or one draining
      after a cancel.
    - After that the call returns `ToolOutcome.Fatal(ThreadBusy)`. The parent run then fails, and `recover` later
      reattaches.
  - **`Suspended`:** returned as `Ended` with the `Suspended` status. D3 surfaces it.
  - **`Completed` or `Failed`:** the stored outcome, rebuilt from the child's latest checkpoint's `outcome` (K1)
    and, for `Answered`, `decodeOutput`, with no run. A settled child checkpoint without an `outcome` is corrupt
    stored state: `ToolOutcome.Fatal`, never a guess from `status`.
  - `GraphError.OutputInvalid` (a typed child exhausted its corrective retry) is a refusal, mapped before the
    generic branch to `TaskRefusal.OutputInvalid(errors)` with the full vector, the same refusal replay rebuilds
    from `RunOutcome.OutputInvalid`. A Block and the step and token limits are statuses, not `Left`s.
  - Any other `Left` (provider error, tool `Fatal`, `DeadlineExceeded`) is `Failed`.
    - Under `ChildFailure.Fail` the child thread stays `Running`, the parent run fails, and the parent's `recover`
      replays the call, which recovers the child.
    - Under `ChildFailure.Report` the call is about to commit, so it will never be replayed. Before returning the
      tool `Error`, the runner **settles the child**: `private[agent] GraphRuntime.settleChild` commits a `Failed`
      checkpoint with `outcome = Failed(...)` under the child's claim. A crash between that commit and the parent's
      returns the same error on replay (the `Failed` row of rule 14). If the claim is busy, the call waits as
      reattach does, then returns `Fatal`. So no child is left `Running` behind a committed call, and retention
      (K1), which deletes only settled children, bounds every child a committed call made.
- **Cancellation (rule 18).**
  - The runner awaits the child with `Agent.blocking`'s semantics. When the calling thread is interrupted (parent
    cancel, parent `ClaimLost`, a C2 timeout on the `task` call), it cancels the child and waits up to
    `AgentRun.Drain` for it to end. It then returns `Left(CancelledError)` with the flag kept.
  - A child whose provider ignores its interrupt for longer is logged at WARN. Its thread is `ThreadBusy` until it
    ends, which the reattach above waits on.
  - Children of children are cancelled by the same mechanism, one level at a time.
- **Deadline.** The child's `RunBudgets.timeout` is `min(granted, ctx.run.deadline - now)` (K3). A parent with no
  deadline passes the grant.
- **Usage (rules 10, 11).** On `Ended` or `Failed`, the runner merges the child's whole-life roll-up into
  `ctx.run.usage` (the task accumulator) as a **separate part** (`TaskUsage.merge`) that commits with the call's
  result. A parent task that fails drops that part: it is never the parent's lost usage, since the child's own
  events keep it, and the attempt that finally commits the result adds the roll-up once. Summing a parent's
  `AgentRunEnded` records therefore counts each child request once. A failed, cancelled or timed-out attempt that a later `recover` finished records its tokens only on
  its terminal event (`AgentTracing.lostUsage`), so they are carried forward instead of re-read from the log:
  - A runtime-owned child-thread key, `DelegationKeys.priorUsage`, holds `PriorUsage(total: UsageSummary,
    foldedThrough: Long)`, the same type as `AgentTracing.lostUsage` (requests, tokens per model, cost): the lost usage folded so far and the sequence number of the last terminal event folded.
  - `recoverChild` reads the child's newest terminal event. If its sequence is above `foldedThrough`, it adds that
    event's lost usage to `total` and sets `foldedThrough` to its sequence; otherwise (already folded) it changes
    nothing. The update commits with the new attempt's first checkpoint.
    - A crash before that commit writes nothing, so the next `recoverChild` folds the event then.
    - A crash after it, before the attempt's own terminal event, leaves the same newest terminal event, whose
      sequence is not above `foldedThrough`, so it is not folded twice.
  - The roll-up is the child's `usage` key, plus `priorUsage`, plus the lost usage on its final terminal event.
  - Each term is in the child's latest checkpoint or newest event. A child thread keeps at least its newest event:
    its effective `RetentionPolicy.maxEvents` is `max(inherited, 1)` (`Some(0)` is accepted for top-level threads),
    so the previous attempt's terminal event survives until the next `recoverChild` folds it. Otherwise event
    retention applies to child threads unchanged.
  - **The fold commits before anything is pruned.** `maxEvents >= 1` does not protect the terminal event from
    `maxAge`, and a crash between an admission prune and the claim commit would lose a fold computed but not yet
    stored. So on a child thread the admission's retention pass runs only **after** the claim checkpoint carrying
    the folded `priorUsage` has committed; until then no limit, age included, removes a terminal event above
    `foldedThrough`. Tested with a `maxAge` shorter than the gap between attempts, and with a crash between fold
    and commit.
  - The roll-up is added once, by the attempt that commits the call's **terminal** result, and covers
  the child's whole life. A call that suspends because its child did (D3) adds nothing: the child's usage so far
  stays in the child thread until the resumed call settles. A failed attempt commits nothing, so a replay adds it
  again without double counting.
- **Durability.** A child runs with its parent's durability and `ClaimPolicy`, on the parent's runtime (rule 16).

Tests (each in-memory and on SQLite):
- crash after the child completes and before the parent commits: `recover` runs no child model call (call counts on
  `ScriptedLLMClient`) and returns the same report
- crash mid-child: `recover` recovers the child from its checkpoint and does not restart it
- the same report after a restart for each `RunOutcome` (answer, step limit, token limit, Block, failure)
- `ChildFailure.Report` settles the child: no `Running` child behind a committed call, across repeated turns
  retention keeps the count at `childRetention`
- a `ThreadExists` race between two runtimes starts one child
- parent `cancel` during a child: child `RunCancelled`, parent `CancelledError`, both recoverable
- a C2 timeout on the call cancels the child; parent `ClaimLost` cancels the child
- the child's deadline is capped by the parent's
- usage counted exactly once across a crash and replay, including tokens of a failed child attempt that a later
  `recover` finished; a crash after `priorUsage` commits and before the attempt ends folds nothing twice

### K3: token budgets and the run's deadline (#2047, 4.43)

D2 grants children a token budget and a deadline. Neither exists as a run limit today.

- **`RunBudgets.maxTokens: Option[Long]`**, the total input and output tokens one run may use.
  - `apply` gets a default of `None`. The previous `apply` stays as an overload without defaults that forwards
    (CLAUDE.md, growth-prone types). `withMaxTokens(Long | Option[Long])` is added. `of` validates positive.
  - The tool loop counts the run's tokens, from the thread's `usage` at admission to now, plus the current task's
    accumulator.
  - Before each model call, at `maxTokens` or above, it ends the turn **`TurnOutcome.TokenLimitReached`** without
    calling the model, as `StepLimitReached` does. That gives the new **`AgentStatus.TokenLimitReached`**.
  - A call in flight is not interrupted, so a run can overshoot by one call. The docs say so.
- **`RunContext.deadline: Option[Instant]`**: when the run's `timeout` expires, from the clock the runtime uses.
  `None` without a timeout. It is set by `GraphRuntime` at admission and read by K2 and D2.
- `java-api` maps `TokenLimitReached`.

Tests:
- the budget stops the turn before the next call, and the status survives `recover`
- overshoot by one call
- `deadline` on a timed run and none on an untimed one
- the old `apply` overload still compiles

## Delegation slices

### D1: `TypedAgent[I, O]` and the `task` tools (#2048, 4.44)

**The definition.** In `org.llm4s.agent.delegation`:

```scala
@Experimental
final class TypedAgent[I, O] private (...):
  def id: AgentId
  def description: String
  def withSystemPrompt(prompt: String): TypedAgent[I, O]
  def withTools(tools: ToolSet): TypedAgent[I, O]
  def withMiddleware(middleware: AgentMiddleware*): TypedAgent[I, O]
  def withHarness(profile: HarnessProfile): TypedAgent[I, O]       // the child's own harness, over its own thread
  def withModel(client: LLMClient, options: CompletionOptions): TypedAgent[I, O] // else the parent's (D2)
  def withMaxSteps(n: Int): TypedAgent[I, O]
  def withInputText(render: I => String): TypedAgent[I, O]         // the child's first user message
  def withFailure(policy: ChildFailure): TypedAgent[I, O]
  def withInheritance(inheritance: Inheritance): TypedAgent[I, O]  // D2; Inheritance.none until then
  def bind(parentClient: LLMClient, runtime: GraphRuntime): Result[BoundAgent[I, O]]

object TypedAgent:
  def apply[I: ReadWriter, O: ReadWriter](
    id: String, description: String, input: SchemaDefinition[I], output: SchemaDefinition[O]
  ): TypedAgent[I, O]
  def text(id: String, description: String): TypedAgent[TaskRequest, String]

@Experimental final case class TaskRequest(description: String) derives ReadWriter
@Experimental enum ChildFailure:
  case Report  // default: the parent model sees a tool error, and can compose without the child
  case Fail    // the parent run fails (Fatal); its recover reattaches and recovers the child
```

- **Input.** By default the child's first user message is `description` for `TaskRequest`, and otherwise the
  canonical JSON of `I` under one line naming the subagent.
- **Output.** For `O = String` (`text`), the answer is the report and no response format is set. Otherwise the child
  is built `withOutput(output)` (F8a), and the report is `decodeOutput[O]`.
- **Errors.** The id and prompt are checked at the parent's `build()`, as `AgentBuilder` checks its own.
- **`BoundAgent[I, O]`** is the definition compiled onto a runtime.
  - `run(input: I, config: RunConfig): Result[TaskOutcome[O]]` runs it on a new top-level thread. This is for graph
    nodes and tests, with no parent.
  - The `task` tools use it through K2.

**The outcome**, a public data type:

```scala
@Experimental final case class ChildRef(threadId: ThreadId, runId: RunId, agent: AgentId,
  toolCallId: Option[ToolCallId], depth: Int) derives ReadWriter
// toolCallId is None, and depth 0, for a standalone BoundAgent.run, which has no parent call

@Experimental enum TaskOutcome[+O]:
  case Reported(report: O, child: ChildRef, usage: UsageSummary)
  case Refused(child: ChildRef, reason: TaskRefusal, usage: UsageSummary)
  case Suspended(child: ChildRef, status: AgentStatus.Suspended)        // D3 surfaces it
  case NotStarted(agent: AgentId, reason: TaskRefusal)                  // refused before any child existed
@Experimental enum TaskRefusal:
  case Blocked(guardrail: String, reason: String)
  case StepLimitReached
  case TokenLimitReached
  case OutputInvalid(errors: Vector[String])
  case Failed(message: String)
```

**On the parent.** `AgentBuilder.withSubagents(agents: TypedAgent[?, ?]*)` and
`AgentBuilder.withDelegation(config: DelegationConfig)`:

```scala
@Experimental final case class DelegationConfig private (maxConcurrentChildren: Int, maxTasksPerTurn: Int,
  maxDepth: Int, maxReportBytes: Int, childRetention: Int)
// apply defaults 4, 16, 1, 64 KiB, 64; with* setters; non-positive refused at build()
```

`build()` installs a `DelegationMiddleware` (id `delegation`) that contributes the tools and a cacheable prompt
section listing each subagent's id and description.

- **Tools.**
  - **`task`** has arguments `{subagent_type: <enum of ids>, description: string}`. It covers every subagent whose
    input is `TaskRequest`. This is the Deep Agents shape, so prompts written for it carry over.
  - Each subagent with another input type gets its own tool, **`task_<id>`**, whose schema is `I`'s.
  - A name clash with any other tool is refused at `build()`.
  - Each call's resource is `(Invoke, task:<id>)`, so a `PermissionMiddleware` can allow, ask or deny per
    subagent.
- **A call.**
  - Check the per-turn count. The task calls in **all** of the current turn's committed assistant messages (every
    model step since the turn began, **across handoffs**: a handoff stays in the same turn, sharing its
    `TurnState` and step count, so a handoff chain cannot multiply the bound), up to this call's position in its own message,
    must be `<= maxTasksPerTurn`, so one call per step over many steps is counted too. This is exact and stable
    under replay (rule 1); tested in one batch and across steps.
  - Check the depth. `origin.depth + 1 <= maxDepth`, and delegation must be granted (rule 15).
  - Acquire a slot from the parent run's semaphore of `maxConcurrentChildren` permits. It is keyed by
    `RunContext.incarnation` and acquired interruptibly. A call over the bound waits; it is not refused.
  - Run `ChildRunner`.
  - Map the outcome:
    - `Reported`: `Success(Json(writeJs(report)))`, or `Error("report over <n> bytes")` past `maxReportBytes`.
    - `Refused`: under `Report`, `Error` naming the subagent, child thread and reason; under `Fail`,
      `Fatal(GraphError...)`.
    - `NotStarted`: the same, naming only the subagent and the reason, since no child thread exists.
    - **Every tool message is bounded.** Whatever the branch, the text that enters the parent's thread is at most
      `maxReportBytes`: a refusal's reason (a provider message, an `OutputInvalid` error vector) is truncated to
      fit, keeping its first errors and saying how many were dropped. The full reason stays in the child's thread
      (`RunOutcome`) and in the typed `TaskOutcome` returned to code. The bound covers the whole serialized message
      (fixed prefixes, `report over <n> bytes`, the suspended error), so `build()` refuses a `maxReportBytes` below
      `DelegationConfig.MinReportBytes` (64), the smallest that every fixed message fits; past that, the whole
      message is cut to the bound.
    - `Suspended`: until D3, `Error("subagent '<id>' suspended; …")`, with the child left suspended. D3 replaces
      this.
- **Parallel.** Calls in one batch already run concurrently, bounded by the parent's `RunBudgets.maxConcurrency`.
  The semaphore bounds children below that. Background children are out of scope (§5.5).
- **Handoffs.** An agent may have both. A handoff's tool never counts as a task.

Tests:
- a typed request and report round trip; the `text` shape; `OutputInvalid` from a child is a refusal
- the child's conversation holds only its rendered input and its own turn, and the parent's holds only the tool
  result (rule 17)
- three parallel calls with `maxConcurrentChildren = 2`: at most two live, measured with a latch
- `maxTasksPerTurn` over the bound in one batch and across steps; `maxDepth`
- the report-size bound; `ChildFailure.Fail` then `recover`
- `BoundAgent.run` standalone
- `DelegationContract` (testkit) on in-memory and SQLite: rule 14's table, row by row

### D2: explicit inheritance (#2049, 4.45)

```scala
@Experimental final case class Inheritance private (
  tools: ToolGrant,              // the parent's tools the child also gets; default ToolGrant.None
  middleware: Set[MiddlewareId], // the parent's middleware the child also runs (memory, guardrails); default empty
  budget: TaskBudget,            // default TaskBudget()
  delegate: Boolean              // may the child use its own subagents (to maxDepth); default false
)
@Experimental enum ToolGrant:
  case None
  case Only(names: Set[String])
  case AllExcept(names: Set[String])   // never includes the task tools; delegate governs those
@Experimental final case class TaskBudget private (maxSteps: Option[Int], timeout: Option[FiniteDuration],
  maxTokens: Option[Long], maxConcurrency: Option[Int])
```

- **Deny by default (rule 15).**
  - `Inheritance.none` is the default: the child's own tools and middleware only.
  - A name in `Only` or `AllExcept` that the parent does not have is refused at `build()`, as `ToolSelection` is
    (§4.37).
  - A granted tool is the parent's instance, and it runs against the child's thread. A `StateFileBackend` tool
    therefore sees the child's own empty files, while a host, workspace or store backend is shared. The docs say
    so (rule 1).
- **Permissions (rule 15).** At `bind`, the child's stack gets an `AncestorPermissions` middleware, outermost, built
  from every ancestor's `PermissionMiddleware` rules.
  - A call runs only if each ancestor's rules allow it, and then the child's own.
  - `Deny` anywhere denies. `Ask` anywhere asks once (D3), and the approval covers every level, as an edited call is
    evaluated again by every level.
- **Memory.** Memory is middleware. Granting `SemanticMemoryMiddleware`'s id shares the parent's instance. Its
  tenant scoping applies unchanged, because the child has the parent's tenant (rule 16). The instruction memory and
  skills middleware read through the child's own backend unless the child's harness says otherwise.
- **Model.** The child uses the parent's client and `CompletionOptions` unless `withModel` is set. A model is not a
  capability, so inheriting it is the default. It is shown in the plan.
- **Budgets (rule 18).** At call time each limit is `min(grant, parent's remaining)`:
  - steps: the grant, else the parent agent's `maxSteps`, capped by the parent turn's remaining steps (its `maxSteps`
    less the model calls the turn has made at the snapshot), which D2 exposes as `RunContext.remainingSteps`
  - time: the parent's `deadline`
  - tokens: the parent's `maxTokens` less the run's usage at the snapshot
  - concurrency: the parent's `maxConcurrency`

  - **An exhausted budget starts no child.** When the remaining steps or tokens are zero or less at call time
    (K3 lets the model call that emitted the task overshoot), the call returns `TaskOutcome.NotStarted` with
    `TaskRefusal.StepLimitReached` or `TokenLimitReached`, which carries no `ChildRef`, creates no child thread, and never builds a
    non-positive `RunBudgets`. A replay decides the same from the same snapshot.
  - **A child's grant is whole-life.** This applies to **every** continuation of a child, `recoverChild` and D3's
    `resumeChild` alike: a child suspended past its deadline, or near its token or step grant, is resumed with the
    same deductions, and settled the same way when nothing is left. A recovered or resumed child's limits are its grant less what its earlier attempts
    used: its `usage` key plus `priorUsage.total` (K2) for tokens; for steps its committed model steps **plus the
    model calls of failed attempts** (`priorUsage.total`'s request count, folded from their terminal events), so a
    call that completed but never committed its step still counts; and for time the **effective deadline fixed at
    first admission**, `min(RunOrigin.startedAt + granted timeout, the parent's deadline then)`, stored on the
    child thread (a runtime-owned `DelegationKeys` key) and reused by every continuation, never recomputed from a
    recovered parent's fresh deadline and never a fresh timeout, so
    repeated recoveries never exceed the grant or the parent's remainder. `recoverChild` passes these to the child
    run. A grant already spent settles the child with `RunOutcome.TokenLimitReached` or `StepLimitReached`
    (`settleChild`) and returns that refusal without running it. Likewise a child whose own deadline has passed by
    recovery (`startedAt + granted timeout <= now`) is settled `RunOutcome.Failed(kind = "deadline-exceeded", ...)`
    and returns `TaskRefusal.Failed`, the outcome a child timing out mid-run gets, and never builds a non-positive
    `RunBudgets.timeout`. When the *parent's* deadline has passed instead, the rule below applies. Each check reads
    the clock once at recovery; the settled outcome is what any later replay reads (rule 14).
  - **An expired deadline starts no child either.** When the parent's deadline has passed at call time, the call
    fails with the parent's own `DeadlineExceeded`, as any step past the deadline does, and never builds a
    non-positive timeout.

  Parallel children each get the same remainder, so together they can overshoot the parent's tokens. The parent's
  next model call then stops at `TokenLimitReached` (rule 1, documented).
- **Inspectable.** `Agent.delegation: DelegationPlan` lists, for each subagent:
  - id and tool name
  - effective tool names, each marked own or granted
  - middleware ids in order, `AncestorPermissions` included, with the ancestors' rules
  - model name and whether it is inherited
  - budget grant
  - whether it may delegate, and its depth limit

  `DelegationPlan` is a plain data value with a `toString` for logs. `build()` validates against it.

Tests:
- an ungranted parent tool is not offered to the child, and granted ones are
- an ancestor `Deny` beats a child `Allow`; an ancestor `Ask` surfaces once and the approved call runs once
- memory shared by grant, and not without one
- each budget capped by the parent's remainder
- the plan matches what runs
- a child without `delegate` has no `task` tool even when it declares subagents

### D3: child suspension through the parent (#2050, 4.46)

- **Asking.** The `task` tools are `AgentTool.Asking[A, DelegatedQuestion, DelegatedAnswers]`.
  - `DelegatedQuestion(child: ChildRef, pending: Vector[ChildInterrupt])`, where `ChildInterrupt` holds the child's
    interrupt id and its request: an approval, a tool question, a middleware question or a breakpoint, each as the
    child's own `AgentStatus.Suspended` gives it.
  - `DelegatedAnswers(answers: Map[InterruptId, ujson.Value])`, keyed by the child's ids.
  - Both have `ReadWriter`s.
- **Surfacing.** `ChildEnd` with a `Suspended` status becomes `ask(DelegatedQuestion)`. The parent's other calls in
  the batch finish and commit, and the parent suspends (§4.1).
  - **`AgentStatus.Suspended` gains `delegated: Vector[(InterruptId, DelegatedRequest)]`**, defaulted to empty.
  - Each entry is one child interrupt, flattened, under a **composite id** `<parent interrupt>~<child interrupt>`.
    A grandchild's is `p~c~g`.
  - `DelegatedRequest(child: ChildRef, request: ChildInterrupt)`.
  - A delegated question is not also listed in `questions`.
- **Answering.** The existing helpers take composite ids unchanged: `approve`, `reject`, `edit`, `reply` and
  `proceed` encode the same answer JSON the child expects.
  - `Agent.resume` groups the composite answers by parent interrupt into `DelegatedAnswers` and resumes the parent.
  - The `task` tool's `resume` calls `ChildRunner.resume`, which resumes the child with those answers (K1's
    `resumeChild`).
  - A partial answer resumes the child, which suspends again on the rest. The tool then asks again, a new phase.
- **Replay (rule 14).** A replayed resume first reads the child. If the answered ids are no longer pending (the
  first attempt applied them), it reattaches instead of resuming again. The child's admission refuses a second
  resume of an answered interrupt in any case.
- **Ancestor `Ask` (D2)** surfaces the same way, as a child approval.
- A rejected child approval reaches the child's model as a rejection, as at top level.

Tests:
- a child approval surfaces with a composite id; approving through the parent runs the child's tool exactly once
  and the parent composes
- reject and edit through the parent
- two parallel children suspending: answer one, the other stays pending
- a grandchild approval at `maxDepth = 2`
- a crash after the child resumed and before the parent committed: `recover` does not resume twice
- typed `reply` to a child's tool question

### D4: nested streams, ids in events and tracing, child-task metrics (#2051, 4.47)

- **`SubagentEvents`** in `org.llm4s.agent.events`, each an `EventType`:
  - `TaskStarted(child: ChildRef)`: live (`progress`), sent when the child is admitted, and again on reattach. It
    is the nested stream handle.
  - `TaskEnded(child: ChildRef, status: String, usage: UsageSummary, duration: FiniteDuration)`: durable, committed
    with the call's **terminal** result only (a report, a tool `Error` or a refusal), so exactly once per child.
    A call that suspends because its child did (D3) commits no `TaskEnded`; the suspension is visible through the
    parent's existing interrupt events, and the resumed call commits the one `TaskEnded` when it settles. Its
    `usage` and `duration` cover the child's whole life, every phase; `duration` is measured from
    `RunOrigin.startedAt` (K1), never from a live event. Encoded with `DurationJson.millisRW`. No
    content (rule 9). This is the child-task metric.
- **Following one child.** `Agent.subscribeChild(ref: ChildRef, afterSeq: Long = 0, config: RunConfig = RunConfig())(listener):
  Result[Subscription]` replays and follows the child's own log, tenant-checked. `AgentRun.subscribe(handlers)`
  is unchanged.
- **One merged stream (opt-in).**
  - **`StreamEvent.Nested(child: ChildRef, event: StreamEvent)`**, live-only, never persisted in the parent's log.
    A parent subscription made with `nested = true` (`stream`, `subscribe`) receives every child event as `Nested`,
    from `TaskStarted` to the child's terminal event, recursively for grandchildren.
  - `EventHandlers.onChild[A](eventType)((ChildRef, A) => R)` matches nested events.
  - `AgentIO`, `AgentZ` and `AgentPekko` gain `nestedEvents(run, handlers)`.
  - Overflow is `LiveGap` on the parent subscription (rule 12).
  - **No event is lost to a race.** Live events are not replayed, so forwarding cannot start when the parent sees
    `TaskStarted`. The runner installs the nested forwarder on the child's run during child admission, before the
    child's first task runs. Events it forwards are queued behind `TaskStarted` in the parent's subscription, so
    order holds: `TaskStarted`, then every child event, then the child's terminal event.
  - **Except a child held by another runtime.** When the call reattaches to a child whose claim another runtime
    holds (K2's wait), that runtime's live events cannot reach this one, so the parent subscription gets one
    `LiveGap` for the child and then its events from the next run this runtime drives. Durable child events are
    never lost: `subscribeChild` replays them.
  - **Nor a child already settled.** A call that reattaches to a `Completed` or `Failed` child (a replay after a
    crash) starts no child run, so its live events are gone: the parent subscription gets `TaskStarted`, one
    `LiveGap` for the child, then `TaskEnded`.
  - **Nor a child an earlier attempt ran.** A call that reattaches to a pre-existing `Running` (or `Suspended`)
    child checkpoint, even one whose claim this runtime then takes at once (both parent and child crashed and
    their claims lapsed), cannot replay that earlier attempt's live events: the parent subscription gets one
    `LiveGap` for the child before the events of the run it now drives. Only a child this call admits fresh is
    gap-free.
- **Tracing.**
  - A child's `AgentTracing` is the parent's `Tracing` (rule 16).
  - Core's **`TraceEvent.AgentRunEnded`** gains `parentThreadId: Option[String] = None` and
    `parentRunId: Option[String] = None`.
  - The OpenTelemetry backend names that span `invoke_agent <agent>`, with `gen_ai.operation.name = invoke_agent`,
    `gen_ai.agent.name`, `gen_ai.conversation.id` (the thread) and `llm4s.parent.run_id` / `llm4s.parent.thread_id`.
    It names the `ToolExecuted` span `execute_tool <tool>`, with `gen_ai.operation.name = execute_tool`.
  - Langfuse puts the parent ids in the trace metadata.
  - True span nesting needs span start events, which is Stage 5 (open question 6).
- **Metrics.** `TaskEnded` is the source of count, latency, outcome and token metrics per subagent, exposed today as
  `graph.task_ended` through `TracingSubscriber`. A `MetricsCollector` projection is Stage 5.

Tests:
- `TaskStarted`, nested `TextDelta` and `TaskEnded` in order on a merged stream
- `subscribeChild` replay after the run
- no content in durable events
- the `Nested` overflow gap
- OTel span names and attributes (`OpenTelemetryTracingBackendSpec`)
- `AgentRunEnded` parent ids for a child and none for a top-level run
- the adapters' nested streams

## Integration slices

### P1: subagents in the harness profile (#2052, 4.48)

`HarnessProfile` gains `subagents: SubagentConfig`, with `withSubagents(SubagentConfig)`:

```scala
@Experimental final case class SubagentConfig private (
  generalPurpose: Option[GeneralPurposeConfig], // the general-purpose subagent; None disables it
  specialists: Vector[TypedAgent[?, ?]],
  delegation: DelegationConfig
)
@Experimental final case class GeneralPurposeConfig private (description: String, systemPrompt: PromptMode,
  model: Option[(LLMClient, CompletionOptions)], inheritance: Inheritance)
```

- **The minimal profile** (`HarnessProfile()`) has `SubagentConfig.none`: no delegation. §2.6 says delegation is
  never granted silently.
- **`HarnessProfile.deepAgents`** turns on the general-purpose subagent, `TypedAgent.text("general-purpose", …)`.
  - Its own harness is the minimal profile over its own `StateFileBackend`.
  - Its grant: every parent tool except the file tools and `execute` (its own harness brings file tools over its own
    backend), and `delegate = false`. The preset computes it at `build()` from the parent's actual tools,
    `ToolGrant.AllExcept((FileTools.Names + "execute") intersect parentTools)`, so D2's refusal of an absent name
    never fires for it: a `deepAgents` agent with no sandbox (no `execute`) builds. Deep Agents
    gives its general-purpose subagent the main agent's tools; here that grant is written in the preset and shown
    by `DelegationPlan`.
  - Applications disable it with `withSubagents(SubagentConfig.none.withSpecialists(...))`, or change it with
    `withGeneralPurpose`.
- **Assembly.** `withHarness` installs `DelegationMiddleware` inside `PermissionMiddleware` and outside the other
  harness middleware. That order is declared with `placement` (§4.37).
  - With permission rules, `task` needs an `Invoke` rule, as `write_todos` does (§4.37's rule: rules are the whole
    policy).
- **Prompt.** The `deepAgents` prompt gains its delegation part:
  - delegate independent, bounded work
  - write a self-contained description
  - issue independent tasks in one message so they run in parallel
  - the report is all that comes back
  - verify before relying on it
- **Config.** `llm4s.agent.harness.subagents.{general-purpose.enabled, max-concurrent-children, max-tasks-per-turn,
  max-depth, max-report-bytes, child-retention}` in `reference.conf`, read by `HarnessConfigLoader`. Specialists
  and models are code.

Tests:
- `deepAgents` offers `task` with `general-purpose`, and the minimal profile offers none
- `deepAgents` builds with and without a sandbox (`execute` present or absent)
- disabling the general-purpose subagent leaves specialists only
- middleware order
- config overrides and bad values refused at the edge
- the plan for the preset

### P2: exit example, parallel-specialist deep research (#2053, 4.49)

In `modules/samples`, package `org.llm4s.samples.harness`: `ParallelDeepResearch`, `ParallelDeepResearchExample`,
and `ParallelDeepResearchAcceptanceSpec` on `ScriptedLLMClient`, with a scripted model that answers from the
conversation it is sent, as `ResearchModel` does.

**The agents.**
- A lead agent on `HarnessProfile.deepAgents`, with the general-purpose subagent disabled and the SQLite
  checkpointer.
- Specialists:
  - `researcher: TypedAgent[ResearchQuestion, Findings]`, granted `fetch_page`
  - `critic: TypedAgent[Draft, Review]`, with no tools
- `DelegationConfig(maxConcurrentChildren = 2)`.
- The lead's typed answer is the Phase 3 `Brief`.

**Scenarios**, each with assertions:
1. **Parallel.** Three `task_researcher` calls in one message. At most two children are live at once, and each
   returns typed `Findings`.
2. **Crash and recover.** `StoppingCheckpointer` stops the process after one child completed and while another is
   mid-run. `recover` in a fresh runtime:
   - does not run the completed child again (model call counts)
   - recovers the running one from its checkpoint
   - composes the `Brief`
3. **Failure.** One researcher's provider fails. The lead sees the tool error and composes a `Brief` from the other
   findings, naming the gap. The failed child thread stays, listed by `children`.
4. **Approval.** A researcher's `fetch_page` on a domain outside the allowlist asks.
   - The lead suspends with one delegated approval.
   - It is approved through `Agent.resume`.
   - The fetch runs once, and the lead composes.
5. **Cancel.** Cancelling the lead mid-children ends both children `RunCancelled`, and `recover` finishes.
6. **Streams.** The merged stream shows `TaskStarted`, nested text deltas and `TaskEnded` per child.
7. **Cleanup.** `forget` on the lead deletes every child thread.

The suite also checks the runtime rules that apply:
- rule 14: one child per call across the crash
- rule 17: no child message in the lead's thread
- rule 11: the lead's usage includes each child's exactly once
- rule 9: no content in durable events

## Breaking changes (pre-1.0, no shims, one migration note)

| Change | Slice |
|---|---|
| Checkpoint format 8 (`origin`): an older build refuses a file written by this one | K1 |
| SQLite schema 6 | K1 |
| `Checkpoint.outcome` (format 8): `AgentRun.status` of a settled thread is read from it | K1 |
| `Checkpointer.children` is a new abstract method that third-party stores must implement | K1 |
| `deleteThread` and `forget` cascade to child threads | K1 |
| Child threads refuse direct `start`, `recover`, `resume`, `fork`, `updateState` and `deleteThread` | K1 |
| `RunBudgets.maxTokens` | K3 |
| `AgentStatus.TokenLimitReached`: exhaustive matches break | K3 |
| `AgentStatus.Suspended.delegated` | D3 |
| `StreamEvent.Nested`: exhaustive matches break | D4 |
| `TraceEvent.AgentRunEnded` gains two fields: positional patterns break | D4, core |
| OpenTelemetry span names `Agent Run` → `invoke_agent <agent>`, and the tool span → `execute_tool <tool>`: dashboards keyed on the old names break | D4 |
| `HarnessProfile.deepAgents` now offers a `task` tool, so an application tool named `task` clashes at `build()`, and permission rules need an `Invoke` rule for it | P1 |

## Stability tiers

- Every new public type in `llm4s-agent` is `@Experimental`, which `stabilityTierCheck` enforces because
  `llm4s-agent` calls `mimaFrozen`.
- Core adds fields only, no type.
- Growth-prone data types are `final case class X private` with `apply` defaults and `with*` setters:
  - `RunOrigin`
  - `DelegationConfig`
  - `Inheritance`
  - `TaskBudget`
  - `SubagentConfig`
  - `GeneralPurposeConfig`
- `ChildRef`, `ChildThread` and `TaskRequest` are small identities and stay plain case classes with `ReadWriter`s.
- `TypedAgent` and `BoundAgent` are definitions holding clients and closures, not data: `final class` with a
  private constructor.

## Decisions (open questions settled on these recommendations, 2026-10-19)

1. **Record numbering.** The brief said to start at 4.40, but `4.40-host-file-backend.md` exists. *Recommend
   4.41-4.49 as above.*
2. **One `task` tool or one per subagent.** One tool with a union of input schemas is poorly supported in strict
   mode, and one tool per subagent loses the Deep Agents shape. *Recommend the hybrid in D1:* `task` for every
   `TaskRequest` subagent, and `task_<id>` per typed input.
3. **The default on child failure.** *Recommend `ChildFailure.Report`.* It is what "composes after failure" needs,
   and the child thread stays for inspection or a later explicit retry. `Fail` is one setter away.
4. **Child retention default.** *Recommend keeping 64 prunable children per parent, pruned by the two passes of
   K1's child retention: before the parent's terminal `Completed` checkpoint, and at `start` admission.* A one-shot
   parent is within its limit as soon as it completes. That is bounded (rule 12) and never races rule 14.
5. **Token budget across parallel children.** Reserving shares needs cross-task coordination that rule 1 rules out.
   *Recommend the snapshot-based remainder, with the documented one-batch overshoot.*
6. **OpenTelemetry span hierarchy.** Today's backend emits each `TraceEvent` as an instantaneous span. *Recommend
   names and parent-id attributes now, and real nesting (start events, span context) in Stage 5's trace work.*
7. **A child on another runtime or checkpointer.** That would split rule 14's journal from the parent, and make
   cascade and tenant checks cross-store. *Recommend no: always the parent's runtime.*
8. **Flattened or nested approvals.** *Recommend flattened composite ids (D3).* Every existing answer helper works
   unchanged, and a UI lists one queue.
9. **Shared state files between parent and child.** Deep Agents merges a subagent's files back into the parent.
   Here that would break rule 17 and rule 1's batch isolation. *Recommend no:* artifacts travel in the typed report,
   or through a granted external backend (`StoreFileBackend`, host, workspace).
10. **`BoundAgent.run` without a parent.** *Recommend shipping it in D1.* It makes `TypedAgent` usable from graph
    nodes, and gives D1 a seam to test, at no extra API cost.

## Delivery

- One `[PHASE 4] Subagents and isolation` umbrella issue, linked to #1266, listing the slices, lanes, critical path
  and exit criteria, as #1901 does. One issue per slice (9), each with one design record (table above) and one PR.
- §4.9: the #1277 child-cancellation row is closed by K2. §4.1's "hierarchical checkpoint namespaces" sentence is
  amended by K1 (namespaces are child threads, not a column of one thread). §5.5 and §6 row 4 link this spec.
- Each slice's design record has a section on how it meets each runtime rule above (1-19).
- **One migration note** for the stage, in `docs/reference/migration.md`, "Subagents and isolation (Phase 4)":
  - checkpoint format 8 and SQLite schema 6 (no rollback past them)
  - implementing `Checkpointer.children`
  - cascade delete and child-thread refusals
  - the two `AgentStatus` additions and `StreamEvent.Nested`
  - `AgentRunEnded`'s fields and the OTel span names
  - `deepAgents` now delegating
- **CHANGELOG.** Each PR adds its entry under `## [Unreleased]`. The entries go in a new area,
  `### Agent subagents (Phase 4)`, with one line per breaking change under `### Breaking changes`. A follow-up to an
  unreleased slice edits that slice's entry.
- **Guide.** `docs/guide/` gains a delegation page: `TypedAgent`, the `task` tools, inheritance, approvals through
  the parent, nested streams, and delegation versus handoffs. The agent guide's handoff section links it.
