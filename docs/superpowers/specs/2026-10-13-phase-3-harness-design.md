# Phase 3: the Deep Agents harness profile, sliced

Stage 3 of #1266, tracked by #1901. Design record: `docs/design/typed-agent-runtime-design.md` §5.4, §5.6, §6 (row 3) and
the Stage 3 rows of §4.9. This document refines that stage and breaks it into slices: core slices that
fix shared contracts, feature slices that run in parallel on them, and integration slices that finish
the stage.

## Intent

Stage 2 made runs durable and reviewable. Stage 3 builds the harness on that runtime: an agent with a
working filesystem, a plan, managed context, skills, instruction and semantic memory, sandboxed
execution under explicit permissions, and a default profile. All of it is opt-in middleware and tools on
the existing `AgentBuilder`, not a second agent type.

Scope (settled in brainstorming):

- Everything in §6 row 3, plus the §4.9 carry-forward "tool permissions and timeouts on `AgentToolSpec`,
  with the ordered deny-if-unmatched permission rules of §5.6".
- **Moved to Stage 4:** "cancelling a run cancels the child runs it started" (§4.9). There are no child
  runs until Stage 4 adds delegation.
- Phase 2 bug-fixing continues alongside. Its open work (claim liveness, `EventHub`, SQLite follow-ups,
  #1737, #1821, #1849) is in the checkpointer and event hub; Stage 3's core changes are in the middleware,
  `AgentToolSpec` and `ToolLoop`, so the two overlap little. Stage 3 relies only on Stage 2's closed
  slice APIs.

### Exit criteria (§6 row 3)

A deep-research example and a coding/workspace example. Each shows tool policy, context management,
streaming, checkpoint/resume, and persisted artifacts, and runs deterministically in CI.

## Placement

- Harness SPIs, default backends and middleware (files, todos, context, skills, instruction memory,
  long-term store, permissions, profile) are in `llm4s-agent`, as §9 "Module placement" decides.
  `llm4s-agent` takes no new dependency.
- Semantic-memory hooks go in a new **`llm4s-agent-memory`** module (depends on `agent` and `memory`),
  because `llm4s-memory` depends on core only and `llm4s-agent` does not depend on it.
- The workspace sandbox adapter goes in `workspaceClient`, which already depends on `agent` (§9
  "Workspace dependency direction").
- Response format is agent-only; multimodal content parts are a core change.

### Existing tools

- `llm4s-agent-tools`' host file tools (`ReadFileTool`, `WriteFileTool`, `ListDirectoryTool`,
  `FileInfoTool`) stay: simple core-only `ToolFunction`s for callers outside the agent runtime. The agent
  path to the host filesystem is the opt-in `HostFileBackend`.
- `workspaceClient`'s `WorkspaceTools` and `createDefaultWorkspaceTools` are **deleted**, and rebuilt as
  backends (F2). Migration note.

## Slice map

| # | Issue | Slice | Kind | Depends on |
|---|---|---|---|---|
| C1 | #1902 | Kernel hooks: state-writing `beforeAgent`/`wrapModelCall`, prompt sections, `taskKey`, Block restores every key | core | - |
| C2 | #1903 | Tool permissions, timeouts and batch locks | core | - |
| C3 | #1904 | Backend SPIs: files, sandbox, long-term store | core | - (uses C2's `LockKey`) |
| F1 | #1905 | Filesystem tool suite | feature | C2, C3 |
| F2 | #1906 | Workspace sandbox and file backends; `WorkspaceTools` removed | feature | C2, C3 |
| F3 | #1907 | Todos | feature | C1, C2 |
| F4 | #1908 | Summarisation and tool-output offload | feature | C1, C3 |
| F5 | #1909 | Agent Skills | feature | C1, C3 |
| F6 | #1910 | Instruction memory (`AGENTS.md`) | feature | C1, C3 |
| F7 | #1911 | Semantic memory, `llm4s-agent-memory` | feature | C1 |
| F8a | #1912 | Response format for the agent's answer | feature | - |
| F8b | #1913 | Multimodal content parts (core) | feature | - |
| F9 | #1914 | Typed custom streams | feature | - |
| P1 | #1915 | Harness profile | integration | F1, F3-F6 (F2 optional) |
| P2 | #1916 | Exit examples as acceptance suites | integration | all |

Parallel lanes:

1. **From the start:** C1, C2, C3 and F8b, plus F8a and F9 whenever someone is free. None depends on
   another, except that C3 uses C2's `LockKey`, a one-line opaque type: whichever lands first adds it.
2. **After the core slices merge:** F1-F7, up to seven lanes, each with its own files. F7 needs only
   C1; F3 needs C1 and C2; F1 and F2 need C2 and C3.
3. **Last:** P1, then P2.

The critical path is C3 → F1 → P1 → P2. F8b is the only core-API change outside the agent runtime;
core must settle before MiMa, so it is reviewed first.

## Runtime rules every slice must satisfy

These are how the Stage 1-2 runtime behaves, checked against the code, and the obligations that follow. A harness design that ignores one
of them is wrong in a way review will keep finding piecemeal, so each slice's design record states how
it meets each rule, and its tests cover each rule that applies.

1. **One batch, one snapshot.** The tool calls of one model message run concurrently in one superstep,
   and each sees the same committed `ThreadState` (`ToolLoop.scala:896`, `ThreadState.scala:9`). They
   never see each other's updates. A check made against the snapshot (a quota, a file's current content)
   is therefore not atomic across a batch, and two whole-value writes to one key lose one silently.
2. **An update function that fails, fails the run.** A `StateKey` apply returning `Left` is
   `GraphError.StateUpdateFailed` for the whole run, and `recover` replays the same pending writes into
   the same failure (`ThreadState.scala:36`, `GraphRuntime.scala:1258`). So a tool-level refusal must
   be decided when the tool runs, never when its update is applied. Harness update functions are total.
3. **An update becomes visible from the next superstep.** A hook's update commits with its task; no hook
   sees it in the same request. A value the current model request needs is put into that request by
   the hook that produced it.
4. **Hooks and tools run at least once.** `recover` runs again every task that has no committed pending
   write. That includes `beforeAgent` (the input task), the tool calls, and `afterAgent` (the finish
   task), and under `Async`/`OnExit` durability even work whose write was not yet durable
   (`GraphRuntime.scala:919-946, 1338-1377`). An effect outside thread state must be idempotent under a
   key that is stable across recovery. That key is `(threadId, superstep, taskId)`, or for a tool
   call its `IdempotencyKey`; `runId`, `checkpointId` and the fencing token are not stable.
   - **Provider tool-call ids are not identities.** A provider can reuse them across model requests,
     which is why `IdempotencyKey` exists. Anything stored is keyed by a runtime identity, never by
     `toolCallId` alone.
   - **"At-least-once" is not a mechanism.** Every external effect names how a replay behaves, using
     one of three mechanisms (C3):
     1. a marker written in the same atomic write as the effect, which a replay sees
     2. a write-ahead intent plus a check of the resulting state, which tells applied from not
        applied
     3. refusal: a replay that cannot tell reports "may already have happened" and does not repeat
        the effect
5. **A Block retracts messages only, today.** An output Block's update is `RemoveTurn` on messages, with
   `activeAgent` and `transfer` restored (`ToolLoop.scala:977-988`). Every other key written during the
   turn survives. Harness keys hold content (files, todos, summaries, recalled memory), so C1 widens
   retraction (below). Effects outside thread state cannot be retracted, and the docs say so.
6. **Every checkpoint is a full snapshot** (`CompiledGraph.scala:152`), so each retained checkpoint
   holds a copy of every key. Only `prune` with a `RetentionPolicy` bounds it.
7. **Restore decodes state through each key's codec.** `Messages.key` is at `SchemaVersion.initial` with
   no migrations (`toolloop/Messages.scala`). A change to a stored type's shape needs a reader for the
   old shape or a migration, or existing SQLite threads fail at `restore`.
8. **Tenant is `RunContext.config.tenantId: Option[TenantId]`.** Anything shared across threads (a
   store, a memory manager, a host or workspace backend) is resolved or namespaced by tenant (§9
   "Tenant identity", "Run dependencies": captured resolvers keyed by `RunContext` identity).
9. **Durable events carry no message content** (CLAUDE.md, Streaming Events). Content is live-only.
10. **A failed task commits nothing.** `NodeResult.Fail` produces no pending write, and its task-scoped
    events are discarded (`GraphRuntime.scala:1366-1377, 1455-1461`). Anything that must outlive a
    failure (usage, model-call events) goes through a committing path: a successful task, or the
    terminal-failure path a Block uses (a `Failed` checkpoint).
11. **Every model call is accounted.** That covers a wrapper's retries, a hook's own calls (a
    summariser, an LLM memory manager), and calls in a failed task. None may drop out of `usage`. C1
    makes the loop count them rather than trusting each hook. Library code that hides its
    `Completion` (`LLMMemoryManager`, `LLMCompressor`) is given an accounted client, so its calls are
    counted without the library's help.
12. **Everything the harness keeps is bounded on its own.** Each key, and each in-process cache, has a
    limit that holds whatever else is configured, not one that relies on another feature (such as
    summarisation) being on. The limit is enforced by a total update (evict or compact) or by a check
    made under a lock when the tool runs (rules 1-2).
13. **Every named type exists or is defined where it is introduced.** A type the spec relies on is
    cited from the code. A new one is defined in its slice: its cases or fields, its codecs and its
    module.

## Core slices

### C1: kernel hooks for the harness (#1902)

Today `wrapModelCall` sees only the request and returns a `Completion`, `beforeAgent` returns only text,
and a Block retracts only messages. The harness needs hooks that read thread state and write their own
keys, prompt content, a recovery-stable key for effects, and a retraction that covers every key.

- **`wrapModelCall(request, context: ModelContext)(next: ModelRequest => Result[ModelResponse]): Result[ModelResponse]`.**
  - `ModelContext` carries `run: RunContext` and `state: ThreadState`.
  - `ModelResponse` carries `completion: Completion` and `update: StateUpdate`.
  - A breaking change to the signature, with no shim.
- **`beforeAgent(input, context: ModelContext): Result[AgentInput]`.** `AgentInput` carries `text` and
  `update`. The update commits with the input task, so it is visible to the turn's first model call
  (rule 3). A breaking change to the signature, with no shim.
- **Writes.** Any hook's update may touch only the middleware's declared `writes`, validated as tool
  writes are; the loop's own keys (`LoopKeys`) stay forbidden.
  - A model-call update commits with the model step's task.
  - When a wrapper calls `next` more than once, only the response it returns counts.
  - A wrapper adds its writes with `ModelResponse.addUpdate`, which keeps those of the wrappers inside
    it; the combined update applies innermost first.
- **New hook `promptSections(context: ModelContext): Vector[PromptSection]`.**
  - `PromptSection(id, text, cacheable)`, a growth-prone type.
  - Before each model call, the loop collects sections in stack order, cacheable ones first, into the
    request's system prompt; they are never stored.
  - It reads the snapshot only (rule 3).
  - Ids are unique within one assembled request. A duplicate within one request fails the run.
- **`RunContext.taskKey: IdempotencyKey`**, derived from `(threadId, superstep, taskId)`. It is stable
  across `recover` (rule 4), and is what a hook's external effect is keyed by. Tool calls keep their
  existing `IdempotencyKey`.
- **Block restores the whole turn (rule 5).**
  - An output Block now restores every key, including middleware and tool keys, to its value at the
    turn's settled checkpoint. It keeps only `usage` and the loop's own bookkeeping, as today.
  - That is a kernel change in `ToolLoop`'s Block update: a key-generic restore, not a `RemoveTurn` per
    key. It also amends §4.13 and §4.19.
  - Effects outside state (host files, a sandbox, a memory store) cannot be restored, and the docs of
    each such backend say so.
- **Usage is counted by the loop (rule 11).**
  - The `next` the loop hands to the innermost wrapper records each provider call's usage in a per-task
    accumulator, whichever response finally wins. A retrying wrapper therefore cannot lose a discarded
    call's cost.
  - **`context.accounted(client: LLMClient): LLMClient`** wraps a client so that every call through it
    records its usage in the current task's accumulator. A hook passes it to any library that calls a
    model internally: `LLMMemoryManager`, the compressors, LLM-judge guardrails.
  - `context.recordUsage(TokenUsage)` is kept for usage that arrives without a client call.
  - A succeeding task adds the accumulator to `usage`.
  - A failing task carries it on its durable `RunFailed` event, so cost is never silently lost (rule
    10). This is a kernel change in `GraphRuntime.fail`.
- **`afterAgent(answer, context: ModelContext): Result[AgentOutput]`**, where `AgentOutput` carries
  `text` and `update`. This makes it symmetrical with `beforeAgent`.
- **No separate `beforeModel` / `afterModel` hooks**: a wrapper covers both. §5.6 is amended.

Tests (each rule that applies):
- state read and write through each hook
- an undeclared write refused
- a retrying wrapper's discarded updates not committed
- a `beforeAgent` update visible to the first model call
- section order and duplicate ids
- `taskKey` equal before and after a crash-and-`recover` of the same task
- a retrying wrapper's two provider calls both counted in `usage`
- a failing task's usage on `RunFailed`
- an output Block after a turn that wrote a middleware key and a tool key restores both and keeps
  `usage`
- every update surviving checkpoint/`recover` on SQLite

### C2: tool permissions, timeouts and batch locks (#1903)

**Timeouts.** `AgentToolSpec.withTimeout(FiniteDuration)` adds a `timeout: Option[FiniteDuration]`
field.
- The loop interrupts a task that runs over and writes a tool-level `ToolOutcome.Error("timed out after
  <n> ms")` the model sees, not `Fatal`.
- The idempotency key is unchanged, so the effect is at-least-once (§10).
- Timeouts rely on interruption, as cancellation does (§4.4). The structured scope waits for a tool that
  ignores its interrupt, so such a tool delays its batch past the timeout. This is documented, with the
  tool-author rule: block interruptibly.

**Resources and rules.**
- `AgentToolSpec.withResources(A => Vector[ToolResource])`.
  - `ToolResource(operation, target)`.
  - `Operation` = `Read | Write | Delete | Execute | Network | Invoke`.
  - The default is `(Invoke, <tool name>)`.
- `PermissionRule(operation, target: Glob, decision: Allow | Deny | Ask)`.
- `PermissionMiddleware(rules)`, in `wrapToolCall`:
  - For each resource, the first matching rule decides; an unmatched resource is **denied**.
  - A call runs only if every resource is allowed. `Deny` gives `Error("Denied: <op> <target>")`.
  - `Ask` without `context.approved` gives `NeedsApproval`.
  - An edited approval runs `wrapToolCall` again on the edited arguments (`ToolLoop.scala:837-846`), so
    the rules are evaluated again. `Ask` then passes, as approved, and `Deny` still denies.
  - The rules are evaluated after schema validation and decoding.
- `Glob` is agent-local (`*`, `**`, `?`) and is shared with F1's `glob`.
- The docs state that sandbox `execute` is also limited by the sandbox itself.

**Batch locks (rules 1 and 2).**
- `AgentToolSpec.withLocks(A => Set[LockKey])`.
- `LockKey` is a path of segments, for example `state-files`, `workspace/<id>`, or
  `workspace/<id>/src/a.scala`. Two keys **conflict when one is a prefix of the other**.
  - A workspace-wide lock therefore conflicts with every file lock in that workspace.
  - Deleting a directory conflicts with a write beneath it.
- In one batch, calls with any conflicting pair of locks run in successive supersteps, in call
  order. The others
  run concurrently in the first superstep, as now.
- A later call therefore sees the earlier one's committed state. Its check against the snapshot (a
  quota, a file's content) is exact, and its update cannot lose another's.
- The barrier is unchanged: the next model call waits for every call's one result.
- Each locked call commits its own pending write, so `recover` resumes mid-sequence without running a
  finished call again.
- `RunBudgets.maxSupersteps` counts these supersteps; this is documented.

Tests:
- first match wins; unmatched denied; multi-resource calls
- `Ask` resumes and runs exactly once
- edited arguments re-checked, with a `Deny` after the edit
- timeout yields one error result per call; cancellation during a timed tool is `CancelledError`
- two locked calls in one batch: the second sees the first's write; a crash between them, then
  `recover`, runs only the second
- unlocked calls still run concurrently

### C3: backend SPIs (#1904)

All in `org.llm4s.agent.harness` (package name settled in the C3 design record).

- **`VPath`**: an opaque type; absolute, normalised, `/`-separated, no `..`, no empty segment.
  Construction returns `Result`.
- **`FileBackend`**, with `writes: Set[StateKey[?, ?]]`, `locks(op): Set[LockKey]` and:
  - `ls(path)`
  - `read(path, offset, limit)`
  - `write(path, content)`
  - `edit(path, old, new, replaceAll)`
  - `delete(path)`
  - `glob(pattern, under)`
  - `grep(regex, under, include)`

  Each takes a `FileContext` (`RunContext` and `ThreadState`) and returns `Result[(A, StateUpdate)]`.
  State-backed files are committed by the runtime, so they are part of checkpoints, `history` and `fork`.
  Host and sandbox backends perform the effect, return `StateUpdate.empty`, and are at-least-once
  (rule 4).
- **`StateFileBackend`** is the default.
  - A `files` key, `StateKey[Map[VPath, FileEntry], FileUpdate]`, with total `Put` / `Delete` update
    functions (rule 2). `FileEntry(content: String, modified: Instant)` holds UTF-8 text only in Stage 3:
    a binary write is a tool error. Binary content travels as F8b content parts, not files.
  - Every mutating operation takes the backend-wide lock `state-files` (C2). So mutations in one batch
    are applied in order, each against the state the previous one committed.
  - The per-thread `maxBytes` cap is checked when the tool runs and is a tool error. Because of the
    lock, it is exact (rule 1).
  - `maxBytes` defaults to 1 MiB. Each checkpoint copies the whole map (rule 6), so the docs pair the
    backend with a `RetentionPolicy`, and P1 sets one.
- **`CompositeBackend(default, routes: (VPath, FileBackend)*)`** routes by longest prefix. `writes` and
  `locks` come from the routed backend. `glob` and `grep` span the routes.
- **`EffectJournal`** records the replay intent of external mutations (rule 4, mechanism 2).
  - `begin(key: IdempotencyKey, intent)` runs before the effect; `complete(key)` runs after it.
  - The intent carries the operation, the path, a hash of the content before, and a hash of the
    content after.
  - On a replay under the same key with an intent recorded, the backend compares the target's current
    hash:
    - equal to the after-hash: already applied, so it returns success
    - equal to the before-hash: not applied, so it applies the effect
    - anything else: a tool error naming the conflict
  - Implementations: `FileEffectJournal(dir)` (durable, one file per key, written with an atomic
    rename) and in-memory for tests. Entries older than a configured age are pruned.
- **`HostFileBackend(root, journal)`** is opt-in.
  - Paths resolve under `root`, and symlink escape is refused.
  - Mutations lock their path (`host/<root>/<path>`), so two writes to one file, or a directory delete
    and a write beneath it, are ordered.
  - Each mutation goes through the journal under the call's `IdempotencyKey`. A replayed `edit_file`
    whose first attempt landed therefore returns success, not "`old` not found".
  - It is not tenant-scoped: an application serving tenants builds one per tenant through
    `FileBackend.resolved` (rule 8).
- **`FileBackend.resolved(FileContext => FileBackend)`** and **`SandboxBackend.resolved`** are
  captured resolvers keyed by `RunContext` identity, typically by tenant (§9 "Run dependencies").
- **`SandboxBackend`**: `execute(command, workdir, timeout, ctx): Result[ExecResult(exitCode, stdout,
  stderr, truncated)]`, at-least-once, keyed by the call's `IdempotencyKey` for sandboxes that can use
  it.
- **`LongTermStore`** works on `(Namespace, key)`:
  - `Namespace(tenant: Option[TenantId], path: Vector[String])` and `key: String`.
  - The value is `StoreItem(value: ujson.Value, updated: Instant, writtenBy: Option[IdempotencyKey])`,
    with a per-item `maxItemBytes` checked on `put`.
  - `writtenBy` is the replay marker (rule 4, mechanism 1). A `StoreFileBackend` mutation whose item
    already carries its key returns success without writing again.
  - Operations are `get`, `put`, `delete`, `list(prefix)` and `search(query)`. The in-memory `search`
    is a substring match over keys and string values; a store may do better, and says so.
  - `put` replaces, so a replayed put is idempotent.
  - `Namespace` always includes the tenant (`None` is its own single-tenant namespace) (rule 8).
  - The in-memory implementation ships here; SQLite and Postgres implementations are Stage 5.
- **`StoreFileBackend(store, namespace)`** exposes a store as files, for `/memories/`. Its mutations
  lock their path.
- **`FileBackendContract`** in `llm4s-agent-testkit`, which every backend passes. It covers:
  - the operations
  - ordered mutations under locks in one batch
  - the quota exact under a parallel batch
  - replay idempotence after `recover`: write, edit and delete each run twice under one key, with a
    crash between the effect and the commit
  - tenant isolation for resolved and store backends

## Feature slices

### F1: filesystem tool suite (#1905)

`FileTools(backend)` yields `ls`, `read_file`, `write_file`, `edit_file`, `delete`, `glob` and `grep` as
`AgentTool`s.

- **Resources (C2).** `Read` for `ls`/`read_file`/`glob`/`grep`, `Write` for `write_file`/`edit_file`,
  `Delete` for `delete`, and `ToolHints`.
- **Locks.** Mutating tools take `backend.locks(op)` (C2, C3).
  - Two edits of one file in a batch apply in order, the second against the first's result.
  - Reads take no lock and see the batch's snapshot. This is documented: a read issued alongside a
    write sees the file as it was before the batch.
- **`read_file`.** Numbered lines, paged by `offset`/`limit`, with the total line count.
- **`edit_file`.** Fails when `old` is absent or not unique, unless `replaceAll` is set. This is decided
  when the tool runs, against the state the lock guarantees.
- **Docs.** Do not register `llm4s-agent-tools`' host file tools alongside this suite (`ToolSet`
  rejects duplicate names).

Tests:
- each tool on state and composite backends
- two edits of one path in one batch, both applied
- a write over quota in a parallel batch refused
- permission rules on paths
- edits visible after `recover`
- an output Block undoing a turn's state-file edits (C1)

### F2: workspace sandbox (#1906)

- In `workspaceClient`, `WorkspaceSandboxBackend` implements `SandboxBackend` and `WorkspaceFileBackend`
  implements `FileBackend`, both over `WorkspaceAgentInterface`.
- An `execute` tool in `llm4s-agent`, built only from a `SandboxBackend`. Its resource is
  `(Execute, <command>)` and it is `destructive` and `openWorld`.
- `WorkspaceTools` is deleted and `codegen/CodeWorker` moves to the new tools. Migration note.
- Related: #870, so sandbox limits are enforced in the runner as well as by tool policy.
- **Effects.** Workspace effects are outside thread state, and a Block cannot undo them (rule 5). This
  is documented.
- **Locks.** `WorkspaceFileBackend` mutations lock `workspace/<id>/<path>`, and `execute` locks
  `workspace/<id>`. By C2's prefix rule a command conflicts with every edit in its workspace, so a
  batch's commands and edits apply in call order.
- **Replay.**
  - File mutations go through an `EffectJournal` held on the client side, which is checked against
    workspace file hashes (mechanism 2).
  - `execute` journals its intent, but a command's effect cannot be checked. A replay that finds an
    intent with no completion returns the tool error "may already have run; not re-run" (mechanism 3),
    unless the tool's `ToolHints.idempotent` is set.
- **Tenants.** One workspace serves one tenant: an application serving several builds the backends
  through the C3 resolvers (rule 8).
- Live-container tests go in `modules/it` under the `@Workspace` tier. Unit tests use a fake
  `WorkspaceAgentInterface`.

### F3: todos (#1907)

- `TodoMiddleware` contributes:
  - a `todos` key, `Vector[Todo(id, content, status)]`, with `status` = `Pending | InProgress | Done`
  - a `write_todos` tool that replaces the list as a whole, with the lock `todos` (C2). It refuses a
    list longer than `maxTodos` (default 50) or an item over `maxTodoChars`, as a tool error (rule 12). Two calls in
    one batch apply in order, so neither is lost silently.
  - a prompt section showing the current plan
- The plan guides the model only: it never routes or overrides tool policy (§8).
- **Events (rule 9).** Each change emits a durable `HarnessEvents.TodosUpdated` with ids and statuses
  only. The todo text is in state, and in a live-only progress event for streaming.
- A Block restores the list (C1).

### F4: context management (#1908)

**`SummarizationMiddleware(trigger, keepRecent, compressor)`**, in `wrapModelCall`:
- The trigger is a token count or message count of the outgoing request.
- When it fires, older messages are compressed through core's `HistoryCompressor`/`LLMCompressor`.
  - The summary goes straight into the outgoing request (rule 3).
  - `(summary, throughMessageId)` is written to the middleware's own key for later calls.
- Later requests send the summary and the messages after `throughMessageId`. When the trigger fires
  again, the summary is extended, not recomputed.
- The summary itself has a bound, `maxSummaryTokens`. An extension that would exceed it compresses the
  old summary and the new messages together, so the key cannot grow without limit (rule 12).
- The compressor gets `context.accounted(client)`, so its calls are counted (rule 11).
- Tool-call/result pairs are never split.
- The stored thread is never edited.
- A Block restores the key (C1), so a summary of a blocked turn is never kept.
- The compression call reruns after a crash (rule 4). That costs a call, but its result is pure state,
  so nothing is duplicated.

**`ToolOutputOffload(thresholdTokens, maxResultBytes, maxTotalBytes)`**, in `wrapToolCall`:
- A `Success` over the threshold is stored, truncated at `maxResultBytes`, in the middleware's own
  `offloads` key. The model sees a preview plus the path `/large_tool_results/<idempotencyKey>`, the
  runtime's call identity. A provider that reuses a `toolCallId` therefore cannot make an older result
  point at a newer call's content (rule 4).
- An `OffloadBackend` serves that prefix through `CompositeBackend`. It is read-only: writes and
  deletes there are tool errors.
- **Bound (rule 12).** The key's update function is total and evicts the oldest entries until the total
  is within `maxTotalBytes` (default 4 MiB).
  - This holds with or without summarisation, and under a parallel batch, because eviction happens
    when the update is applied, not as a check against the snapshot.
  - Reading an evicted path returns a tool error that says it was evicted.
- **Idempotence.** The path is unique per runtime call, and a replayed write replaces it (rule 4).
- **Cleanup.** When summarisation is on, offloads older than the summary's `throughMessageId` are
  dropped as the summary advances. This is extra cleanup; the bound does not depend on it.

`ContextWindowMiddleware` stays as the pruning-only option.

### F5: Agent Skills (#1909)

- `SkillsMiddleware(sources: Vector[VPath], backend)` discovers `<source>/*/SKILL.md` through the backend
  on a run's first model call, and caches the result by run id in a bounded LRU (rule 12). A run recovered in a fresh runtime
  re-reads the backend. State-backed files come back from the checkpoint; a host file may have changed
  in between, and that is documented.
- Each skill's `name` and `description` go into one cacheable prompt section.
- The model loads a skill's body and any resources with `read_file`, so no new tool is needed.
- Frontmatter is read by a restricted parser written for it, with no YAML dependency:
  - it accepts scalars, quoted strings, flat lists and a one-level `metadata` map
  - anything else is an error naming the file and line
  - a malformed skill is skipped and reported in a durable event; it does not fail the run
- `allowed-tools` is parsed and exposed on `Skill`, not enforced in Stage 3.

### F6: instruction memory (#1910)

- `InstructionMemoryMiddleware(paths: Vector[VPath], backend)` reads the listed `AGENTS.md` files in
  order (for example user scope, then project scope) on a run's first model call, cached by run id in a
  bounded LRU and re-read after recovery as F5 does, as one prompt section.
- A missing file is skipped.
- When the backend and permission rules permit writes, the agent edits them with `edit_file`; the change
  shows at the next run. Instruction memory differs from semantic memory (§9 "Skills and memory
  distinction").

### F7: semantic memory (`llm4s-agent-memory`) (#1911)

`SemanticMemoryMiddleware(stores: Option[TenantId] => MemoryStore, manager: (MemoryStore, LLMClient) => MemoryManager, client: LLMClient, recall, record, consolidate)`.

**Tenant scope (rule 8).** The store is resolved per tenant and held in one thread-safe cell per tenant,
not per thread.
- The manager is built for each hook call from the tenant's current store and
  `context.accounted(client)` (C1). Every call an `LLMMemoryManager` makes for extraction, recording or
  consolidation is therefore counted in `usage` (rule 11), though the manager's API returns no
  `Completion`.
- The store the manager returns is put back in the cell.
- Every thread of a tenant recalls what earlier threads of that tenant recorded.
- No tenant's recall reads another's: the resolver supplies a tenant-scoped store, or a store filtered
  by a tenant tag the middleware writes and filters on.
- Updates to one tenant's cell are serialised.

**Recall** runs `getRelevantContext` on the turn's input in `beforeAgent`.
- The text is truncated at `maxRecallTokens` (rule 12).
- It writes the text to a declared `recalled` key (C1), committed with the input task, so the turn's
  first model call shows it as a prompt section (rule 3).
- After a crash before that commit, the input task reruns and recalls again. Nothing has used the result
  yet, so that is harmless (rule 4).
- A Block restores the key (C1).

**Record** runs in `afterAgent`.
- Each memory's `MemoryId` is derived from `RunContext.taskKey` and stored with replace-on-same-id
  semantics, so a replayed finish task overwrites, not duplicates (rule 4).
- F7 adds that upsert guarantee to the `MemoryStore` contract and its stores where `store` does not
  already replace.
- Consolidation, every N turns, is keyed the same way.

**Order.** The middleware runs outside guardrail middleware (`runsBefore`). Inner `afterAgent` hooks run
first, and a guardrail `Left` stops the fold (`MiddlewareStack.scala:40-43`), so a blocked answer is
never recorded. P1 fixes this order.

**New-module checklist** (CLAUDE.md "Adding a module"):
- coverage floor, codecov flag and CI upload
- CHANGELOG and docs migration note
- `docs` project source list
- `hooks/pre-commit`
- root aggregate and BOM

Tests:
- two threads of one tenant share recall; two tenants do not
- the first model call sees recall
- a crash after record, then `recover`, leaves exactly one memory
- a blocked answer is not recorded

### F8a: response format for the agent's answer (#1912)

- `AgentBuilder.withOutput[O](using SchemaDefinition[O], ReadWriter[O])` records the schema.
  `withOutputOf[O: SchemaOf]` is a convenience in `schema-derivation`.
- **Schema.** Every model call carries `responseFormat = JsonSchema(...)`, and a prompt section always
  states the schema too. `RequestTransformer` silently drops an unsupported format inside the client and
  reports nothing to the agent.
- **Validation.** An answer turn (no tool calls) is validated against the schema inside the model step.
  - A failure is retried once within the same task: the request is extended with the invalid answer and
    the error, and the corrective exchange is not stored in the thread.
  - A second failure ends the turn through the terminal-failure path a Block uses (rule 10).
    - It commits a `Failed` checkpoint with both calls in `usage` and their `ModelCallCompleted`
      events, and retracts the turn, so no invalid answer is kept.
    - The caller gets `Left(GraphError.OutputInvalid(schemaErrors))`.
    - `recover` refuses the thread, as for a Block: there is nothing to resume. The next `start`
      continues from before the turn.
    - Failing the task instead would lose the usage and events (rule 10), and a `recover` would pay for
      both calls again.
- **`AgentResult.decodeOutput[O: ReadWriter]: Result[O]`** decodes the answer. `Agent` stays
  non-generic; Stage 4's `TypedAgent[I, O]` builds on this.

### F8b: multimodal content parts (core) (#1913)

- **Vocabulary (rule 13).**
  - `ContentPart` = `Text(text) | Image(source: MediaSource, mediaType: ImageMediaType) | Document(source,
    mediaType: DocumentMediaType)`, in core `org.llm4s.model`.
  - `MediaSource` = `Url(uri: java.net.URI) | Inline(bytes: IArray[Byte]) | ProviderFile(provider,
    id)`, a new ADT in `org.llm4s.media`. It is pure data, so it keeps that package's rule of no I/O and
    no dependency.
  - `org.llm4s.media.MediaType` gains a `DocumentMediaType` family: `Pdf`, `PlainText`, `Markdown`,
    `Html`, `Csv`, with `fromExtension` and `fromMimeType` cases.
  - The upickle codecs live in core beside `ContentPart`, not in `media`, which takes no third-party
    dependency. `Inline` encodes as base64.
- **Bound (rules 6, 12).** An `Inline` part is limited by `maxInlineBytes` (default 5 MiB), checked when
  the part is built. Every checkpoint copies the thread's messages, so larger media must be a `Url` or
  a `ProviderFile`, and the docs say why.
- `UserMessage` and `ToolMessage` carry `parts: Vector[ContentPart]`; `UserMessage(text)` remains as the
  one-part constructor.
- **Stored threads (rule 7).** Core `Message`'s reader keeps accepting the old string-content shape, as a
  single `Text` part. `Messages.key` and `MessageUpdate` move to a new schema version, with a migration
  from the old one.
  - A SQLite thread written before the change restores and continues after it.
  - That is tested on a fixture checkpoint written by `main` before the change (as #1821 does for format
    5).
- Each provider module maps the parts it supports. Any other part fails the request with a typed
  `LLMError` naming the part kind, never dropped silently.
- `ProviderModuleChecks` gains `assertContentParts`, and every chat provider module's spec calls it.
- The graph and tool-result model carry parts unchanged, so a tool can return an image.

### F9: typed custom streams (#1914)

- **`EventType` is invariant, so a set of mixed types cannot keep a typed payload (rule 13).**
  Selections are therefore typed handlers packaged with their event types:
  `EventHandlers.on(HarnessEvents.TodosUpdated)(t => ...).on(AgentEvents.TextDelta)(d => ...)`.
  - Each `on[A](EventType[A])(A => Unit)` keeps its own `A`.
  - The built-in `AgentEvents` values are already `EventType`s (`AgentEvents.scala:29`).
  - `agent.stream(threadId, query, handlers)` and `AgentRun.subscribe(handlers)` decode each event once
    and dispatch to the matching handler. Unmatched events are skipped.
- **Effect adapters.** These are typed per event type: `AgentIO.events(run, eventType: EventType[A]):
  Stream[IO, A]`, the same in `AgentZ` (ZIO) and as a Pekko `Source[A, ?]`.
  - Callers merge streams with the library's own combinators.
  - A heterogeneous stream is `events(run, handlers)` into a caller-chosen ADT, built with `on(...)(a =>
    MyEvent.X(a))`.
- No new event model: `RunEvent.Custom` and `EventType` exist.

## Integration slices

### P1: harness profile (#1915)

`HarnessProfile` is a growth-prone `final case class HarnessProfile private (...)`, with `apply`
defaults and `with*` setters. Its fields:

| Field | Type |
|---|---|
| `backend` | `FileBackend`, default `StateFileBackend` |
| `sandbox` | `Option[SandboxBackend]` |
| `tools` | `ToolSelection` = `All \| Only(names) \| Except(names)` |
| `todos` | `Option[TodoConfig]` |
| `summarization` | `Option[SummarizationConfig]` |
| `offload` | `Option[OffloadConfig]` |
| `skills` | `Option[SkillsConfig]` |
| `instructions` | `Option[InstructionsConfig]` |
| `systemPrompt` | `PromptMode` = `Default \| Replace(text) \| Suffix(text)` |
| `permissions` | `Vector[PermissionRule]` |
| `toolTimeout` | `Option[FiniteDuration]` |
| `retention` | `RetentionPolicy`, default: keep the latest N checkpoints (rule 6) |

- `AgentBuilder.withHarness(profile)` installs the middleware in a fixed, documented order: permissions
  outermost, then offload, summarisation, skills, instructions, todos. An added
  `SemanticMemoryMiddleware` (F7) runs outside guardrail middleware. It adds the selected tools and
  `execute` only when `sandbox` is set.
- `HarnessProfile.deepAgents` is the opinionated preset. Its default prompt teaches plan, act and verify,
  context management, and tool safety; the delegation part is added in Stage 4.
- Defaults are in `llm4s.agent.harness.*` in `llm4s-agent`'s `reference.conf`, read by
  `HarnessConfigLoader` at the app edge, never by `sys.env` (CLAUDE.md "Core Principles").

### P2: exit examples (#1916)

Both run as samples and as deterministic specs on `ScriptedLLMClient`.

- **Deep research:**
  - `HarnessProfile.deepAgents` on state files, with todos, summarisation and offload of a large
    fetched page, plus one skill
  - streaming `TodosUpdated` and text deltas
  - the process stopped after an intermediate SQLite checkpoint, then `recover` in a fresh runtime (no
    pending interrupt, so not `resume`), with the report file present afterwards
- **Coding/workspace:**
  - a workspace sandbox (fake backend in unit runs; `@Workspace` IT tier for the live container) under
    rules that `Allow` writes under `/src/**` and `Ask` on `execute`
  - the run suspends at `execute`, resumes approved, and runs the command exactly once
  - edited files present in the workspace after resume

Each example has assertions for each of the five exit capabilities. Each also exercises the runtime
rules:
- a parallel batch of file edits
- a crash before a finish commit, after which memory is recorded once and offloads are not duplicated
- an output Block that leaves no file, todo or summary from the blocked turn

## Delivery

- One `[PHASE 3] Deep Agents harness profile` umbrella issue, linked to #1266, listing the slices, the
  lanes and the exit criteria, as #1699 does.
- One issue per slice (15). Each slice has one design-record file,
  `docs/design/typed-agent-runtime/4.2x-stage-3-slice-<n>-<name>.md`, and one PR.
- §4.9 changes:
  - the permissions/timeouts row is marked as owned by C2
  - child-run cancellation moves to Stage 4
  - §5.6 notes that wrappers replace `beforeModel`/`afterModel`
- Each slice's design record has a section on how it meets each runtime rule above.
- One migration note covers the stage:
  - the `wrapModelCall` and `beforeAgent` signatures
  - a Block restores every key
  - `WorkspaceTools` removed
  - `Message` content parts
  - the new `llm4s-agent-memory` module
- Each PR adds its CHANGELOG entry under `## [Unreleased]`.
