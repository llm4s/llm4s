# Background subagents: design, sliced

- **Status:** proposed. Design only; nothing here is built.
- **Issue:** #2452. Deferred by Stage 5 (`2026-10-20-stage-5-production-design.md`, Decision 11); builds on Phase 4
  (`2026-10-19-phase-4-subagents-design.md`) and Stage 5's run queue.
- **Checked against** `main` at `2a26db2aa` (2026-11-22). Every statement about current behaviour names the file or
  design record it was read from; "4.NN" is a record under `docs/design/typed-agent-runtime/`.

## Open questions for the maintainer

These could not be settled from the code. Each has a recommendation the rest of the document assumes; a different
answer changes only the section named.

1. **May a finished child wake a parent that is at rest?** It means a turn the user did not ask for, opened by a
   synthetic user message. *Recommend: yes, as the last slice (B7), off by default, only with a run queue, ids and
   status only in the message* (Results, below). Without it the feature is complete: the parent sees finished tasks
   at its next turn.
2. **Does cancelling a parent run cancel the thread's background children?** *Recommend: yes, all that are still
   running, whichever turn started them*: "stop" must stop the spend. The alternative (children survive a cancel, as
   they survive a turn's end) leaves work running that only `cancel_task` or deleting the thread can stop.
   (Lifecycle.)
3. **A finite default lifetime.** Phase 4 rule 12 says delegation never invents a finite default for time or
   tokens. A background child has no parent run to bound it, so *recommend `maxLifetime` = 1 hour by default*, and
   tokens still opt-in. (Budgets.)
4. **Does a collected child's usage count against the collecting run's `maxTokens`?** A run's tokens are the
   thread's usage since admission (4.43), so adding a child's usage to the thread's usage, as a `task` call does,
   would let a `check_task` end a later turn `TokenLimitReached` for tokens spent an hour earlier. *Recommend: it is
   added to `AgentResult.usage` but not to the run's token count*, which needs the loop to keep the two apart
   (slice B2 says how). The simpler alternative is to count it, and document the surprise.
5. **Background tools below the top level.** *Recommend: only a top-level agent (depth 0) gets them in this
   feature*; a background child may still delegate synchronously under its `Inheritance`. Nesting them multiplies
   unattended work across turns and needs a worker to resolve a subagent through several levels.
6. **The principal on a queued child.** `QueueEntry.attemptConfig` builds `RunConfig(runId, tenantId)` plus the
   trace context (`service/QueueEntry.scala`): no queued run records a principal today. *Recommend: accept it here
   (the child's registration, made by the parent's run, records the principal on its `RunStarted`), and carry the
   principal on queue entries as its own Stage 5 follow-up*, since it is every queued run's gap, not this feature's.

## Intent

Today a `task` call blocks until its child ends: `DelegationMiddleware.run` calls `BoundAgent.delegated`, which
awaits the child through `ChildRunner` (`delegation/DelegationMiddleware.scala`, `delegation/ChildRunner.scala`). A
long task holds the parent's turn, its claim and, in a service, a worker slot.

A **background child** is started by a tool call that returns at once with a task id. The parent goes on with its
turn, and in that turn or a later one checks, waits for, or cancels the child. With a run queue the child is a queue
entry, so it survives the parent's machine; without one it runs in the parent's process.

### Goals

- The parent model starts, lists, checks, waits for and cancels background children with five tools.
- A child is started once however often its call is replayed, and both sides recover independently.
- Every lifecycle case has one stated outcome: the parent's turn ending, its cancel, interrupt, suspension,
  abandonment and deletion.
- A child that needs a human surfaces as `waiting`, answerable by the application.
- One mechanism, two drivers: in process with no queue, a queue entry with one.

### Non-goals

- Follow-up instructions to a running child (Deep Agents' `update_async_task`). Rule 17: one typed input, one typed
  output. A follow-up is a new task.
- Sharing the parent's history with the child (Deep Agents' experimental `fork` mode). Rule 17 again.
- A child on another runtime, store or remote server (Phase 4 Decision 7 stands). Deep Agents reaches its
  children over Agent Protocol; here a child is a thread in the parent's store.
- HTTP endpoints, webhooks: the HTTP server project.

### Anti-goals (failures even if it works)

- A child started twice by a replay, or its usage counted twice.
- A child that runs for ever because nothing owns it.
- A message written into a thread by anything other than that thread's own run.

## What exists today (checked against the code)

- **A child is a thread with an origin.** `RunOrigin(parent, call, agent, startedAt, depth, trace)` is on every
  checkpoint of a child (`graph/ChildThreads.scala`); its id is `ThreadId.child(parent, agent, call)`, a digest of
  the parent thread, the call's `IdempotencyKey` and the agent (4.41). `GraphRuntime.startChild` refuses an existing
  thread, and every run of a `task` call reads the child's latest checkpoint first (Phase 4 rule 14).
- **Three writes on a child are fenced by its parent's claim**: its first commit, a reattachment and a settle
  (`startChild`, `recoverChild`, `settleChild` in `graph/GraphRuntime.scala`, #2071). Its other commits are fenced
  by its own claim alone.
- **A child is driven only through its parent's tool call** (rule 19): `start`, `recover`, `resume`, `fork`,
  `updateState` and `deleteThread` on it are refused with `GraphError.ChildThread` (4.41).
- **Supervision is the tool call's thread.** The parent's cancel, lost claim or call timeout interrupts the thread
  awaiting the child, which cancels the child; after a parent cancel it stays `Running` for the parent's `recover`
  (4.42).
- **A new turn abandons what the last one left.** At `start` admission `keepChildren` settles every `Running` or
  `Suspended` child `Failed("abandoned")`, because no call of an earlier run can be replayed, and prunes settled
  children beyond `childRetention` (`GraphRuntime.keepChildren`, 4.41).
- **Budgets are computed at call time from the parent's run** (`TaskLimits.at`, 4.45): steps capped by the turn's
  remainder, tokens by the run's remainder, time by the parent's deadline. The first admission records the
  whole-life grant on the child (`DelegationKeys.grant`, `ChildGrant`).
- **Usage reaches the parent once**, when the `task` call's terminal result commits, with the durable `TaskEnded`
  event (`events/SubagentEvents.scala`, 4.42, 4.47).
- **A suspended child suspends its parent**: the `task` tool asks a `DelegatedQuestion` and the parent lists the
  child's interrupts under composite ids (4.46).
- **Bounds**: `DelegationConfig` (`maxConcurrentChildren` 4, `maxTasksPerTurn` 16, `maxDepth` 1, `maxReportBytes`
  64 KiB, `childRetention` 64). The concurrency bound is a semaphore per run incarnation
  (`DelegationMiddleware.delegate`).
- **The run queue** (`org.llm4s.agent.service`, 4.55-4.57): `RunSubmitter.submit` makes one entry per run, idempotent
  under a `SubmissionKey`; `QueuedInput` is `Start`, `Resume` or `Recover`; `AttemptPlan.of` recovers a queued
  `Recover` entry's thread until one attempt commits; a worker runs entries for the `QueuedAgent`s it was built with
  and calls `Agent.start` / `startResume` / `startRecover` (`service/AgentWorker.scala`). A cancel is a stop request
  the worker sees at lease renewal, and the stopped run settles itself `Failed` with a cancelled outcome (4.57).
  `BusyThreadPolicy` is `Reject`, `Enqueue` or `Interrupt`; a thread with pending interrupts refuses a start whatever
  the policy (Stage 5 spec, W3). A terminal entry is kept for `ticketLifetime`, 24 hours by default
  (`service/RunQueueSettings.scala`).
- **Deleting a thread** cascades to its descendants and refuses the whole call with `ThreadBusy` while one has a
  live claim (4.41). `RunSubmitter.deleteThread` first cancels the thread's own entries (4.57).
- **Tracing**: every run of a child takes `RunOrigin.trace`, the span of the tool call that started it, as its
  remote parent (4.58). Queue entries carry `Submission.traceContext`.
- **Checkpoint format 8** is what `v0.6.0` wrote (`Checkpoint.CurrentFormat`).
- **Not merged yet**: PR #2752 adds `Agent.abandon(threadId, reason)`, which closes a cut tool batch with a
  result for every call and runs no tool again; it refuses a child thread.

## Prior art: Deep Agents async subagents

Read 2026-11-22 (the page is linked from the parity matrix row). What it does, and where llm4s differs:

| Deep Agents | llm4s (this design) | Why |
|---|---|---|
| Five tools: `start_async_task`, `check_async_task`, `update_async_task`, `cancel_async_task`, `list_async_tasks` | `start_task`, `check_task`, `wait_tasks`, `cancel_task`, `list_tasks` | No update (rule 17). A bounded wait replaces a model that polls. |
| Tasks tracked in an `async_tasks` state channel on the supervisor | No parent state: the store's `children` listing is the record | A state key is a second journal beside rule 14's, and can disagree with it. |
| A child is a thread on an Agent Protocol server, in process (ASGI) or remote (HTTP) | A child thread in the parent's store, run in process or by a queue worker | Phase 4 Decision 7. |
| A finished child notifies the supervisor as a `HumanMessage`, and can wake an idle one | A status section in the prompt of a running turn; waking is opt-in (open question 1) | No writer but the thread's own run. |
| A `waiting` child is answered by the application on the child's thread | The same, through `Agent.resumeTask` | The parent model cannot answer for the user. |
| Restart behaviour, limits and thread cleanup are not stated | Stated below | - |

## Placement

Everything is in `llm4s-agent`. The tools, `BackgroundConfig` and the in-process driver are in
`org.llm4s.agent.delegation`; the kernel additions in `org.llm4s.agent.graph`; the queue driver and the worker's
dispatch in `org.llm4s.agent.service`, which already depends on `delegation` (`QueuedAgent.typed`). Contract cases go
to `llm4s-agent-testkit`; the SQLite and PostgreSQL checkpointers change only if the format step needs them. No new
module, no new dependency, nothing in core.

## Rules every slice must satisfy

Phase 4's nineteen rules and Stage 5's ten still hold, with rule 19 amended. A background child adds these:

- **B-1. The child thread is still the journal.** A background child is a child thread whose origin says so. Its id
  is derived as today, so the start call's replay finds it (rule 14). No parent state key records tasks.
- **B-2. Registered under the parent's claim, run without it.** The commit that creates the child is fenced by the
  parent's claim, as today. Every later write is fenced by the child's own claim only: the parent's claim is gone
  once its turn ends.
- **B-3. A registered child is always driven to rest.** Something owns every unsettled background child: a queue
  entry, or the in-process driver. A child found `Running`, unclaimed and unowned is re-driven, never left.
- **B-4. Rule 19, amended.** A background child is driven only by the delegation runtime, on behalf of its parent
  thread: the background tools and `Agent.cancelTask` / `resumeTask`. The public `start`, `recover`, `resume`, `fork`,
  `updateState` and `deleteThread` still refuse it with `GraphError.ChildThread`.
- **B-5. Nothing writes into the parent's thread but the parent's run.** A result reaches the parent as the result
  of a tool call the parent made. Waking a parent (B7) submits a run to the parent's queue; it writes nothing itself.
- **B-6. Collected once.** A child's usage and `TaskEnded` reach the parent with the first call that returns its
  terminal result, decided on the child's thread, so a replay or a second call adds nothing.
- **B-7. Bounded on its own.** Unsettled background children per parent thread, each child's lifetime, the longest
  wait, and how long an uncollected result is kept each have a limit.

## The model's tools

Offered to an agent whose delegation has a `BackgroundConfig`, for the subagents marked `withBackground()`. They are
installed by `DelegationMiddleware` beside the `task` tools.

| Tool | Arguments | Result (JSON the model reads) |
|---|---|---|
| `start_task` | `{subagent_type, description}`, as `task` | `{task_id, subagent_type, status: "running"}` |
| `start_task_<id>` | the subagent's input schema, as `task_<id>` | the same |
| `check_task` | `{task_id}` | `{task_id, subagent_type, status, report?, error?, pending?}` |
| `wait_tasks` | `{task_ids: [..], timeout_seconds?}` | `{tasks: [<as check_task>], timed_out}` |
| `cancel_task` | `{task_id}` | `{task_id, status}` |
| `list_tasks` | `{status?}` | `{tasks: [{task_id, subagent_type, status, started_at}]}`, no reports |

- **`task_id`** is the child thread id's tail, `<agent>/<digest>` (24 hex characters, 4.41). A tool resolves it to
  `ThreadId.child`'s form under the calling thread and then reads the checkpoint: a thread is this parent's
  background child only if its origin says so, never because of its id (4.41). Anything else is a tool error.
- **`status`** is `running` (queued or running), `waiting` (suspended for a human), `completed`, `failed` or
  `cancelled`, read from the child's latest checkpoint and `RunOutcome`. `error` is `TaskRefusal.describe`'s text;
  `pending` lists a waiting child's interrupts as `{id, kind, tool?}`, without arguments.
- **`report`** is the child's typed answer, as `task` returns it, bounded by `maxReportBytes`. It can be read again
  while the child thread is kept, so a parent whose context was summarised can fetch it a second time.
- **`check_task` never blocks.** `wait_tasks` returns when any named task is no longer `running`, or after
  `timeout_seconds`, capped by `BackgroundConfig.maxWait`. It watches the children with
  `Checkpointer.awaitEventsAfter`, so it works across machines.
- **`start_task` counts towards `maxTasksPerTurn`** with the `task` tools, and its permission resource is the same
  `(Invoke, task:<id>)`, so one rule governs both ways of running a subagent. `cancel_task` uses it too; the read
  tools need none.
- **`ChildFailure` does not apply.** A background child cannot fail its parent's run: its failure is always
  reported, as `ChildFailure.Report` does.
- **The prompt section** gains a "Background tasks" part when the thread has any: one line per unsettled or
  uncollected task (id, subagent, status), from `children(parent)`, built by `promptSections` at each model step and
  never stored. It tells the model a task finished without a message being written.

**Decision: `check_task` returns status and result together.** Separate `status` and `result` tools cost a second
call for the common case and give no extra control. *Rejected:* two tools.

**Decision: a bounded `wait_tasks`, not a polling model.** Deep Agents tells its model not to poll and relies on
notifications. A wait holds the turn only as long as the caller allows. The cost is stated: a started tool call runs
to completion under `BusyThreadPolicy.Interrupt` (4.57), so a wait delays an interrupt by up to `maxWait`; a cancel
interrupts it at once. *Rejected:* an unbounded wait, which is today's `task`.

**Decision: no parent state for tasks.** `list_tasks` reads `Checkpointer.children`. *Rejected:* an `async_tasks`
style state key: it survives a replan that the child did not, and the reverse.

### Results arriving in the parent's thread

**Decision: a child never writes a message into its parent's thread** (B-5). Three cases:

- **The parent is running.** The prompt section shows the change at the next model step; the model calls
  `check_task`. Nothing is injected.
- **The parent is at rest** and waking is off (the default): nothing happens until the next turn, whose first model
  step shows the section.
- **The parent is at rest and `notifyParent` is on** (B7, open question 1; needs a queue): when a child's entry closes
  with the child completed, failed or cancelled, the worker submits a start on the parent thread: a user message
  naming the task id, subagent and status, never the report; `BusyThreadPolicy.Enqueue`; submission key
  `background-notify:<child thread>`. If the parent is busy the entry waits its turn. It never uses `Interrupt` (a
  child must not cut a user's turn) or `Reject` (the notice would be lost). A parent with pending interrupts refuses
  the start, as it refuses any (Stage 5, W3): the notice is dropped and the section still shows the task later. A
  `waiting` child does not wake its parent.

*Rejected:* the report itself as the notice. It would be a second path for results beside the collecting call,
with its own usage accounting.

## Durability and exactly-once start

**Decision: both drivers, chosen by configuration, over one journal.** A background child is a child thread either
way; only who runs it differs.

- **No run queue** (`AgentBuilder.withRunQueue` not called): the child runs on the parent's `GraphRuntime`, on its
  own thread, its `RunHandle` kept by the runtime rather than awaited. It outlives the parent's turn, not the
  process. With the in-memory store it is lost with the process. With SQLite it is left `Running` and re-driven when
  any runtime next admits a run on the parent thread or a background tool reads it (B-3).
- **With a run queue**: the child is a queue entry. It survives the parent's machine and is recovered by any worker
  (4.56). This is what a service uses.

*Rejected:* queue only. A test and a single process must work with no worker (Stage 5 rule 7).
*Rejected:* a top-level thread linked by metadata. It loses the cascade delete, `children`, the depth, the trace
origin and rule 19's refusals, and gains nothing.

**Starting, in order** (`start_task`, under the parent's claim):

1. Read the child's latest checkpoint (rule 14).
2. **None: register it.** `GraphRuntime.registerChild` (new, `private[agent]`) claims the child and commits its
   first checkpoint, fenced by the parent's claim: the origin with `background` set, the input digest
   (`DelegationKeys.input`) and the whole-life grant (`DelegationKeys.grant`). In process, the run then simply
   continues (this is `startChild`, not awaited). With a queue, the claim is released with nothing run, which is the
   state a crash just after the first commit leaves, and which `recoverChild` already continues.
3. **Drive it.** With a queue: `RunSubmitter.submit` of `QueuedInput.Recover` for the child thread, the parent's
   tenant, submission key `background:<child thread>`, the origin's trace context. `AttemptPlan.of` recovers a queued
   recover until an attempt commits, so no new plan is needed.
4. Return the task id, and commit the durable `BackgroundTaskStarted` with the call's result.

A replay of the call reads the child at step 1 and finds it: started with other input, it is refused as today; else
step 3 runs again, which the submission key makes a no-op, and the same id is returned. A crash between 2 and 3
leaves a registered child with no entry; the replay submits it, and if the call is never replayed (an `Async` or
`OnExit` replan that asked something else), B-3's re-drive does. Such a child is an orphan the model never named:
it appears in `list_tasks`, is bounded by its own grant, and goes with the thread.

**Recovery of both sides.** The child's is its entry's (lease expiry, 4.56), or the in-process re-drive. The
parent's is unchanged: its `recover` replays uncommitted calls, and each background tool is safe to run again (start
is idempotent, check and list are reads, cancel is idempotent, a wait waits again). Recovering the parent never
restarts or cancels a child.

**The worker.** A background child's entry names the *root* agent's id, the `QueuedAgent` a worker already
registers. The worker reads the thread's checkpoint, finds a background origin, and resolves the bound subagent from
the root's delegation plan by `RunOrigin.background` (the agent that started it) and `RunOrigin.agent`, then calls
the `private[agent]` child entry points. A worker that has the root agent at a graph version that can restore the
child takes the entry; otherwise W2's rule for a version it cannot restore applies.

**Checkpoint format 9.** `RunOrigin` gains `background: Option[AgentId]`, the agent whose call started the child,
`None` for a `task` child. Format 8 shipped in `v0.6.0`, and a build that ignored the field would abandon a
background child at the parent's next `start`, so the format is bumped and an older build refuses the checkpoint
(Stage 5 Decision 9). The slice tests a format-8 fixture.

## Lifecycle

**Decision: a background child may outlive its parent's run and be collected on a later turn.** That is the
feature. What each event does to the thread's unsettled background children:

| Parent event | Running child | Why |
|---|---|---|
| Turn ends (`Completed`, `StepLimitReached`, `Blocked`, a failure) | keeps running | The point of the feature. |
| Run's deadline or token budget passes | keeps running | They bound a run; the child has its own grant. |
| Suspends for an approval or question | keeps running | The parent is waiting on a human, not on the child. |
| Interrupted by a new message (`BusyThreadPolicy.Interrupt`) | keeps running | The conversation goes on; the new turn sees the task and may cancel it. |
| Claim lost, worker died, `recover` | keeps running | The child is independently owned (B-3). |
| Next `start` on the thread | keeps running | `keepChildren` no longer abandons a background child. |
| Cancelled (`AgentRun.cancel`, a queue cancel) | cancelled and settled | Open question 2. |
| Tool batch closed by `Agent.abandon` or a queued cancel's settle | a child registered by a call of that batch is cancelled | The model never saw its id. |
| Thread deleted, `forget` | cancelled, then deleted with the thread | Cascade, as today. |

- **Cancel.** A cancelled parent run, as it closes, requests a cancel of each unsettled background child of its
  thread: `RunQueue.cancel(CancelTarget.Thread(child))`, or the local handle. The child settles `Failed` with a
  cancelled outcome, as a cancelled queued run does (4.57); it is not left `Running` for a `recover`, because its
  start call committed and will never be replayed. A parent whose process dies mid-cancel leaves children running;
  the worker that settles the parent's entry (4.57's fallback) repeats the requests, which are idempotent.
- **`Agent.abandon` (PR #2752) and the queued settle** give every call of a cut batch a result and run nothing
  again. A `start_task` call in that batch may have registered a child. Both paths derive the child's id from the
  call's key and cancel it if it exists. Children whose start had committed are untouched. `abandon` on a background
  child thread stays refused (`ChildThread`). Whichever of #2752 and slice B3 lands second adds this.
- **Delete.** `RunSubmitter.deleteThread` also cancels the open entries of the thread's background descendants and
  waits for them, then deletes. A direct `GraphRuntime.deleteThread` cancels the children this runtime is running
  and still refuses with `ThreadBusy` while one is claimed elsewhere, as today. An entry whose thread was deleted
  closes `Cancelled(ThreadDeleted)` without running (Stage 5 rule 4).
- **Retention.** A settled background child is prunable once it is collected, or `resultTtl` after it settled,
  and then by `childRetention` as any child. An unsettled one is never pruned. Stage 5's store-wide sweep already
  keeps a thread with an open entry (rule 4).
- **Lifetime.** A child that passes its deadline fails `DeadlineExceeded` on its own, as a child whose own grant
  was the shorter does today (4.42).

*Rejected:* cancelling children when the turn ends. That is `task`.
*Rejected:* a per-agent choice of what a turn's end does. No user has asked; it can be added to `BackgroundConfig`.

## A child that suspends

**Decision: a suspended background child is `waiting`, answered by the application through the parent agent; it
does not suspend the parent.** `check_task`, `list_tasks` and the prompt section show `waiting` with the interrupts'
ids and kinds. The application lists them with `Agent.backgroundTasks(parent)` and answers with
`Agent.resumeTask(child, answers)`, which submits a `QueuedInput.Resume` for the child (or resumes it in process).
An application follows a child with the existing `Agent.subscribeChild`. The parent model cannot approve for the
user: it has no tool that answers.

*Rejected:* D3's composite ids (4.46), by making `wait_tasks` ask. It would suspend the whole parent on one child,
and tie the answer's route to a call that may have timed out or belong to an earlier turn.
*Rejected:* failing a child that asks. Approvals are why a long task needs a human.

## Budgets, bounds, usage and tracing

- **The grant is fixed at registration** and recorded as `ChildGrant`, as today. Steps: the subagent's `TaskBudget`,
  else its own `maxSteps`, else the parent agent's; *not* capped by the turn's remaining steps, which end with the
  turn. Tokens: the `TaskBudget`'s, capped by what the starting run has left at the call; none set means unlimited,
  as rule 12 has it. Time: `min(TaskBudget.timeout, BackgroundConfig.maxLifetime)` from registration, not capped by
  the parent run's deadline (open question 3). Concurrency: as today.
- **`maxBackgroundTasks`** (default 8): unsettled background children of one parent thread, across turns. A
  `start_task` over it is refused with a tool error naming the bound; it does not wait for a slot, since nothing
  frees one during the call. The count is `children(parent)`; `start_task` declares one lock key
  (`AgentTool.withLocks`), so the starts of one model message run one at a time and the count is exact (rule 1).
  `maxConcurrentChildren` keeps bounding `task` calls only.
- **Depth.** Background tools are offered at depth 0 only (open question 5). A background child is at depth 1 and
  delegates synchronously to `maxDepth` if granted.
- **Usage** (B-6). The first `check_task` or `wait_tasks` that returns a child's terminal result marks the child
  collected: a commit on the child's thread, under the child's claim, recording the collecting call's
  `IdempotencyKey`. The call then commits the child's whole-life usage and `TaskEnded` with its own result. A replay
  of that call finds its own key and does the same; any other call finds another key and returns the result with no
  usage and no event. If the collecting call never commits, the usage is never added to the parent: an under-count,
  never a double count. The child's own thread and its run metrics always hold it. Whether collected usage counts
  against the run's token budget is open question 4.
- **Tracing.** `RunOrigin.trace` is the `start_task` call's span, and the entry carries it, so every run of the
  child, on any machine, is a child span of that call in the trace of the turn that started it (4.58), even after
  that turn's span ended. `check_task`, `wait_tasks` and `cancel_task` spans carry the child thread id as an
  attribute, no content (Stage 5 rule 10). Slice B5 checks what Langfuse shows for a child that ends after its
  parent's trace.
- **Metrics.** Each child run is a run, so 4.59's run metrics count it under its agent id. The child-task metrics
  take *started* from `BackgroundTaskStarted` and *ended* from `TaskEnded`; an uncollected child is counted by the
  run metrics only. The guide says so.
- **Streams.** A background child's events are not nested in the parent's stream: the parent's run may be over.
  `BackgroundTaskStarted` gives a subscriber the `ChildRef` to pass to `subscribeChild`.

## Tenancy, permissions and what the child sees

Unchanged from Phase 4, and stated because the child outlives the turn:

- **Identity** (rules 8, 16): the child has its parent's tenant, recorded on its checkpoints; the entry is submitted
  for that tenant, and every tool resolves a task id only under the calling thread, after the tenant check. The
  principal is open question 6.
- **Permissions** (rule 15): every ancestor's `PermissionMiddleware` rules are compiled into the bound subagent
  (`delegation/AncestorPermissions.scala`), so they hold on whichever worker runs it. An `Ask` makes the child
  `waiting`.
- **What it sees** (rules 15, 17): its typed input and what its `Inheritance` grants, nothing of the parent's
  messages or state. A granted tool over a state backend sees the child's own files; one over a host, workspace or
  store backend shares the parent's, now concurrently with later parent turns, which the guide warns about as it
  does for parallel children (rule 1).

## Public API

All new top-level types are `@Experimental`.

```scala
// org.llm4s.agent.delegation: growth-prone (private constructor, apply with defaults, with* setters)
final case class BackgroundConfig private (
  maxBackgroundTasks: Int,        // 8
  maxLifetime: FiniteDuration,    // 1.hour
  maxWait: FiniteDuration,        // 60.seconds
  resultTtl: FiniteDuration,      // 24.hours
  notifyParent: Boolean           // false; B7
)

// DelegationConfig (growth-prone already) gains
background: Option[BackgroundConfig]                       // None: no background tools
def withBackground(c: BackgroundConfig): DelegationConfig
def withBackground(c: Option[BackgroundConfig]): DelegationConfig

// TypedAgent[I, O] and GeneralPurposeConfig gain
def withBackground(): TypedAgent[I, O]                     // may also be started in the background

// AgentBuilder gains
def withRunQueue(submitter: RunSubmitter): AgentBuilder    // background children become queue entries

// Agent gains
def backgroundTasks(threadId: ThreadId, config: RunConfig = RunConfig()): Result[Vector[BackgroundTask]]
def cancelTask(child: ThreadId, config: RunConfig = RunConfig()): Result[BackgroundTask]
def resumeTask(
  child: ThreadId,
  answers: Map[InterruptId, ujson.Value],
  config: RunConfig = RunConfig()
): Result[BackgroundTask]

// org.llm4s.agent.delegation: growth-prone
final case class BackgroundTask private (
  child: ChildRef,
  status: BackgroundStatus,
  startedAt: Instant,
  updatedAt: Instant,
  pending: Vector[ChildInterrupt]
)
enum BackgroundStatus:
  case Running, Waiting, Completed, Cancelled
  case Failed(reason: TaskRefusal)

// org.llm4s.agent.graph
RunOrigin.background: Option[AgentId]                      // with withBackground; format 9

// org.llm4s.agent.events: durable, ids only
final case class BackgroundTaskStarted(child: ChildRef)
SubagentEvents.BackgroundTaskStarted: EventType[BackgroundTaskStarted]   // "agent.background_task_started"
```

`AgentBuilder.build()` refuses: a `BackgroundConfig` value that is not positive; `notifyParent` without a run queue;
a `withBackground()` subagent under a delegation with no `BackgroundConfig`; a `wait_tasks` tool timeout shorter than
`maxWait`; background configuration on an agent that is itself bound as a subagent.

**Package-private**: the tools and their argument types, the two drivers, `GraphRuntime.registerChild` and the
unfenced continuation of a background child, the collected mark, the worker's dispatch and the notice. `QueuedAgent`,
`Submission`, `QueuedInput` and `AttemptPlan` do not change.

**Not added**: a task id type (the model's id is a string, the application's a `ThreadId`), an `update` tool, a
turn-end policy, a nested-stream form, wrappers in the Java, Kotlin, cats-effect and ZIO modules (follow-ups once
the Scala API has settled).

## Slice map

Each slice is one PR with its design record (the next free 4.NN) and a runtime-rules section, as Phase 4's were.

| Slice | Delivers | Tested by |
|---|---|---|
| **B1** kernel | `RunOrigin.background`, format 9; `registerChild`; a background child continued without the parent's fence; `keepChildren` neither abandons nor prunes an unsettled one | `ChildThreadSpec` cases; a format-8 fixture; `CheckpointerContract` cases on every store |
| **B2** tools, in process | `BackgroundConfig`, `withBackground()`, `start_task`, `check_task`, `list_tasks`, `cancel_task`, the prompt section, the grant, `maxBackgroundTasks`, collected-once usage, `BackgroundTaskStarted` | `BackgroundTaskBehaviours` on the in-memory store and SQLite: each crash window of the start (after register, after the result, with a replay and with a different replan), a second `check_task`, the bound under a parallel batch |
| **B3** lifecycle | the table above: cancel flows down, a cut batch's registered child is cancelled, delete, `resultTtl`, re-drive at admission | one case per row, on both stores; `AgentAbandonSpec` case once #2752 is in |
| **B4** wait | `wait_tasks`, `maxWait`, the build check | returns on the first rest, on timeout, on cancel; a child finishing on another runtime over one SQLite file |
| **B5** queue driver | `AgentBuilder.withRunQueue`, the worker's dispatch, cancel and delete through the queue, trace context on the entry | `AgentWorker` specs on the in-memory queue and SQLite; a `@Docker` suite in `modules/it` on PostgreSQL: the parent's worker killed, the child finished by another; the child's worker killed mid-run |
| **B6** waiting children | `waiting`, `Agent.backgroundTasks`, `cancelTask`, `resumeTask` | a child's approval answered in process and through a `Resume` entry; another tenant refused; a top-level or `task` child refused |
| **B7** wake (open question 1) | `notifyParent` | a parent at rest woken once per child under replay; a busy parent's notice enqueued; a suspended parent's dropped |
| **B8** integration | harness profile (`SubagentConfig`), the agents guide, the parity row moved off *Deferred*, an example, CHANGELOG and migration note | the guide's snippets compiled; the acceptance tests below |

Order: B1, then B2; B3, B4 and B6 are three lanes after B2; B5 after B3; B7 after B5; B8 last. B1 to B4 are
useful alone, with no queue.

### Acceptance tests (the whole feature)

1. **Continue.** A lead starts three background researchers in one message, answers the user in the same turn
   without waiting, and its next turn collects three reports.
2. **Once.** The process is stopped after a child is registered and before its start call commits; `recover` returns
   the same task id and the child's model is called once.
3. **Survives the parent's machine.** Three workers on PostgreSQL: the worker running the parent is killed after
   `start_task`; the child completes on another, and the parent's next turn, on any worker, reads the report.
4. **Survives its own machine.** The worker running the child is killed mid-run; another finishes it; its usage is
   counted once.
5. **Cancel.** `cancel_task` settles one child `cancelled`; cancelling the parent run settles the rest.
6. **Delete.** Deleting the parent thread with a running child leaves no thread and no open entry.
7. **Approval.** A child that asks is `waiting`; `resumeTask` continues it; the parent was never suspended.
8. **Bounds.** A ninth unsettled child is refused; a child past `maxLifetime` is `failed`; an uncollected result is
   gone after `resultTtl`.
9. **One trace.** The child's spans, run by another worker, are under the `start_task` span.
10. **No queue.** Tests 1, 2, 5, 7 and 8 pass on the in-memory store with no worker.

## Breaking changes (pre-1.0, no shims, one migration note)

Checkpoint format 9 (a full stop for a rolling upgrade, Stage 5 Decision 9). `DelegationConfig` gains a field
(one `apply`, no overload, before the MiMa baseline). `GraphError.ChildThread`'s meaning is unchanged. An agent
with no `BackgroundConfig` behaves exactly as today.

## Decisions (2026-11-22)

1. **A background child is a child thread with `RunOrigin.background`**, not a top-level thread and not a parent
   state entry (my call; B-1).
2. **Registered under the parent's claim, run without it** (my call; B-2).
3. **Two drivers over one journal**: in process without a queue, a `Recover` entry with one (my call).
4. **Five tools; `check_task` carries the result; no update tool** (my call).
5. **A bounded `wait_tasks`** rather than a model that polls or a blocking call (my call).
6. **No message is written into the parent's thread**; a prompt section shows changes (my call; B-5).
7. **Children outlive the turn, the run's budget, an interrupt and a suspension**; a cancel and a delete stop
   them (open question 2 for the cancel).
8. **A suspended child is `waiting`, answered through `Agent.resumeTask`**, not through composite ids (my call).
9. **Usage is collected once, marked on the child's thread** (my call; B-6).
10. **Checkpoint format 9** (my call, from Stage 5 Decision 9).
11. **Depth 0 only, a finite default lifetime, waking off by default** (open questions 5, 3, 1).

## Delivery

As Phase 4 and Stage 5: #2452 becomes the umbrella with one issue per slice; one slice per PR, each with its design
record; this spec merges before B1. The parity matrix row stays *Deferred* until B8.
