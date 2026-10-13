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
| C1 | #1902 | Middleware that sees and writes state on the model side, and contributes prompt sections | core | - |
| C2 | #1903 | Tool permissions and timeouts | core | - |
| C3 | #1904 | Backend SPIs: files, sandbox, long-term store | core | - |
| F1 | #1905 | Filesystem tool suite | feature | C2, C3 |
| F2 | #1906 | Workspace sandbox and file backends; `WorkspaceTools` removed | feature | C3 |
| F3 | #1907 | Todos | feature | C1 |
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
   another.
2. **After the core slices merge:** F1-F7, up to seven lanes, each with its own files. F3 and F7 need
   only C1; F2 needs only C3.
3. **Last:** P1, then P2.

The critical path is C3 → F1 → P1 → P2. F8b is the only core-API change outside the agent runtime;
core must settle before MiMa, so it is reviewed first.

## Core slices

### C1: model-side state and prompt sections (#1902)

Today `wrapModelCall` sees only the request and returns a `Completion`. Summarisation, todos, skills and
memory recall need to read thread state, write their own keys, and contribute system-prompt content.

- `wrapModelCall(request: ModelRequest, context: ModelContext)(next: ModelRequest => Result[ModelResponse]): Result[ModelResponse]`.
  - `ModelContext` carries `run: RunContext` and `state: ThreadState`.
  - `ModelResponse` carries `completion: Completion` and `update: StateUpdate`.
  - A breaking change to the signature, with no shim.
- The update may touch only the middleware's declared `writes`. The `writes` Scaladoc changes from
  "`wrapToolCall` may add" to "any hook may write". It is validated as tool writes are, and the loop's
  own keys (`LoopKeys`) stay forbidden.
- The update is committed in the model step's superstep, with the assistant message. When a wrapper
  calls `next` more than once, only the response it returns counts. The outermost wrapper's response is
  what is committed. A wrapper adds its own writes with `ModelResponse.addUpdate`, which keeps the
  updates of the wrappers inside it; the combined update is applied innermost first.
- New hook `promptSections(context: ModelContext): Vector[PromptSection]`.
  - `PromptSection(id, text, cacheable: Boolean)`, a growth-prone type.
  - Before each model call, the loop collects sections in stack order and puts cacheable ones first. It
    prepends them to the builder's system prompt (or replaces it: see P1 `PromptMode`) in the request
    sent, never in the stored messages.
  - Section ids are unique per run; a duplicate fails the run with `GraphError.MiddlewareFailed`.
- No separate `beforeModel` / `afterModel` hooks: a wrapper covers both. §5.6 is amended to say so.
- Summarisation keeps the rule that a request is pruned, never the stored thread (CLAUDE.md, Guardrails):
  F4 stores its summary in its own key and rewrites each request, so `history`, `fork` and Block
  retraction (§4.19) are unaffected.

Tests: state read and write through a wrapper; undeclared write refused; a retrying wrapper's discarded
updates not committed; section ordering, caching order and duplicate ids; updates surviving
checkpoint/resume on `InMemoryCheckpointer`.

### C2: tool permissions and timeouts (#1903)

- `AgentToolSpec.withTimeout(FiniteDuration)` (a new `timeout: Option[FiniteDuration]` field).
  - The loop interrupts the task when it runs over and writes a tool-level
    `ToolOutcome.Error("timed out after <n> ms")`, which the model sees. It is not `Fatal`, because the
    model can adapt.
  - The idempotency key is unchanged, so a timed-out side effect is still at-least-once (§10).
- `AgentToolSpec.withResources(A => Vector[ToolResource])`.
  - `ToolResource(operation: Operation, target: String)`.
  - `Operation` = `Read | Write | Delete | Execute | Network | Invoke`.
  - A spec with no resource function yields `Vector(ToolResource(Invoke, <tool name>))`.
- `PermissionRule(operation: Operation, target: Glob, decision: Decision)`, with
  `Decision` = `Allow | Deny | Ask`.
- `PermissionMiddleware(rules: Vector[PermissionRule])`, in `wrapToolCall`:
  - For each resource, the first matching rule decides. An unmatched resource is **denied** (§9
    "Permission default").
  - A call runs only if every resource is allowed. Any `Deny` returns
    `ToolOutcome.Error("Denied: <op> <target>")`.
  - Any `Ask` without `context.approved` returns `NeedsApproval`, so the call resumes through the
    existing approval path, with typed approve/edit/reject answers.
  - Rules are evaluated on the decoded arguments, after schema validation (§9 "Tool argument
    validation").
- `Glob` is agent-local, with `*`, `**` and `?` on `/`-separated targets, and is shared with F1's `glob`.
- The docs state that sandbox `execute` is also limited by the sandbox itself (§5.6); path rules alone do
  not secure it.

Tests: first match wins, unmatched denied, multi-resource calls, `Ask` resumes and runs exactly once,
timeout produces an error result and the barrier still gets one result per call, a cancelled run
during a timed tool is `CancelledError`, not a timeout.

### C3: backend SPIs (#1904)

All in `org.llm4s.agent.harness` (package name settled in the C3 design record).

- **`VPath`**: an opaque type; absolute, normalised, `/`-separated, no `..`, no empty segment.
  Construction returns `Result`.
- **`FileBackend`**, with `writes: Set[StateKey[?, ?]]` and:
  - `ls(path)`
  - `read(path, offset, limit)`
  - `write(path, content)`
  - `edit(path, old, new, replaceAll)`
  - `delete(path)`
  - `glob(pattern, under)`
  - `grep(regex, under, include)`

  Each takes a `FileContext` (`RunContext` and `ThreadState`) and returns `Result[(A, StateUpdate)]`.
  State-backed files stay pure that way: the runtime commits the update, so files are part of
  checkpoints, `history` and `fork`. Host and sandbox backends perform the effect and return
  `StateUpdate.empty`.
- **`StateFileBackend`** is the default.
  - A `files` key, `StateKey[Map[VPath, FileEntry], FileUpdate]`, with `Put` / `Delete` operations.
    `FileEntry` holds the content and its modification `Instant`.
  - A per-thread size cap (`maxBytes`) fails a write with a tool error rather than growing checkpoints
    without bound.
- **`CompositeBackend(default, routes: (VPath, FileBackend)*)`** routes by longest prefix. `writes` is
  the union. `glob` and `grep` cover every route under the requested path.
- **`HostFileBackend(root)`** is opt-in. Paths resolve under `root` with symlink escape refused.
- **`SandboxBackend`**: `execute(command, workdir, timeout, ctx): Result[ExecResult]`, with
  `ExecResult(exitCode, stdout, stderr, truncated)`.
- **`LongTermStore`** works on `(Namespace, key)`:
  - Operations are `get`, `put`, `delete`, `list(prefix)` and `search(query)` (substring search in the
    in-memory implementation).
  - `Namespace` always includes `RunContext.config.tenantId`, so the store is tenant-scoped by
    construction (§9 "Tenant identity").
  - The in-memory implementation ships here; SQLite and Postgres implementations are Stage 5.
- **`StoreFileBackend(store, namespace)`** exposes a `LongTermStore` as files. A composite then routes
  `/memories/` there across threads (§5.4).
- **`FileBackendContract`** in `llm4s-agent-testkit`, a suite every backend passes (state, composite,
  host, store, and F2's workspace backend), following `CheckpointerContract`.

## Feature slices

### F1: filesystem tool suite (#1905)

`FileTools(backend)` yields `ls`, `read_file`, `write_file`, `edit_file`, `delete`, `glob` and `grep` as
`AgentTool`s.

- Each declares path resources (C2): `Read` for `ls`/`read_file`/`glob`/`grep`, `Write` for
  `write_file`/`edit_file`, `Delete` for `delete`. Each also declares `ToolHints`.
- `read_file` returns numbered lines, paged by `offset`/`limit`, and states the total line count.
- `edit_file` fails when `old` is absent or not unique, unless `replaceAll` is set.
- Tool names match Deep Agents. `ToolSet` rejects duplicate names, so the docs tell users not to
  register `llm4s-agent-tools`' host file tools alongside this suite.

### F2: workspace sandbox (#1906)

- In `workspaceClient`, `WorkspaceSandboxBackend` implements `SandboxBackend` and `WorkspaceFileBackend`
  implements `FileBackend`, both over `WorkspaceAgentInterface`.
- An `execute` tool in `llm4s-agent`, built only from a `SandboxBackend`. Its resource is
  `(Execute, <command>)` and it is `destructive` and `openWorld`.
- `WorkspaceTools` is deleted and `codegen/CodeWorker` moves to the new tools. Migration note.
- Related: #870, so sandbox limits are enforced in the runner as well as by tool policy.
- Live-container tests go in `modules/it` under the `@Workspace` tier. Unit tests use a fake
  `WorkspaceAgentInterface`.

### F3: todos (#1907)

- `TodoMiddleware` contributes:
  - a `todos` key, `Vector[Todo(id, content, status)]`, with `status` = `Pending | InProgress | Done`
  - a `write_todos` tool that replaces the list as a whole
  - a prompt section showing the current plan
- The plan guides the model only: it never routes or overrides tool policy (§8).
- Each change emits a durable `HarnessEvents.TodosUpdated` custom event.

### F4: context management (#1908)

- `SummarizationMiddleware(trigger, keepRecent, compressor)`:
  - The trigger is a token count or message count of the outgoing request, measured with core's token
    counting.
  - When it fires, older messages are compressed through core's `HistoryCompressor`/`LLMCompressor`, and
    `(summary, throughMessageId)` is stored in the middleware's own key (C1).
  - Later requests send the summary and the messages after `throughMessageId`. A summary is extended,
    not recomputed, when the trigger fires again.
  - Tool-call/result pairs are never split, so requests stay provider-valid.
- `ToolOutputOffload(thresholdTokens, backend)`:
  - It is a `wrapToolCall`. A `Success` whose content is over the threshold is written to
    `/large_tool_results/<toolCallId>`, and the update is merged into the outcome's update.
  - The model sees a short preview plus that path, and reads more with `read_file`.
- `ContextWindowMiddleware` stays as the pruning-only option.

### F5: Agent Skills (#1909)

- `SkillsMiddleware(sources: Vector[VPath], backend)` discovers `<source>/*/SKILL.md` through the backend
  once per run.
- Each skill's `name` and `description` go into one cacheable prompt section.
- The model loads a skill's body and any resources with `read_file`, so no new tool is needed.
- Frontmatter is read by a restricted parser written for it, with no YAML dependency:
  - it accepts scalars, quoted strings, flat lists and a one-level `metadata` map
  - anything else is an error naming the file and line
  - a malformed skill is skipped and reported in a durable event; it does not fail the run
- `allowed-tools` is parsed and exposed on `Skill`, not enforced in Stage 3.

### F6: instruction memory (#1910)

- `InstructionMemoryMiddleware(paths: Vector[VPath], backend)` reads the listed `AGENTS.md` files in
  order (for example user scope, then project scope) once per run, as one prompt section.
- A missing file is skipped.
- When the backend and permission rules permit writes, the agent edits them with `edit_file`; the change
  shows at the next run. Instruction memory differs from semantic memory (§9 "Skills and memory
  distinction").

### F7: semantic memory (`llm4s-agent-memory`) (#1911)

- `SemanticMemoryMiddleware(manager: MemoryManager, recall, record, consolidate)`:
  - Recall runs `getRelevantContext` on the run input in `beforeAgent`, kept for the run, and shows it as
    a prompt section.
  - Record runs in `afterAgent`, writing the turn.
  - Consolidation is optional, every N turns.
- `MemoryManager` is immutable-returning, so the middleware holds the latest manager per thread behind a
  thread-safe cell. The docs say a durable store (SQLite, Postgres) is what makes memory survive a
  restart.
- New-module checklist (CLAUDE.md "Adding a module"):
  - coverage floor, codecov flag and CI upload
  - CHANGELOG and docs migration note
  - `docs` project source list
  - `hooks/pre-commit`

### F8a: response format for the agent's answer (#1912)

- `AgentBuilder.withOutput[O](using SchemaDefinition[O], ReadWriter[O])` records the schema.
  `withOutputOf[O: SchemaOf]` is a convenience in `schema-derivation`.
- Every model call carries `responseFormat = JsonSchema(...)`. Where a provider drops it
  (`RequestTransformer`), a prompt section states the schema instead.
- An answer turn (no tool calls) is validated against the schema before the run completes:
  - A failure is sent back once as a corrective user message, and the model is asked again.
  - A second failure fails the run with a typed `GraphError`.
- `AgentResult.decodeOutput[O: ReadWriter]: Result[O]` decodes the answer. `Agent` stays non-generic;
  Stage 4's `TypedAgent[I, O]` builds on this.

### F8b: multimodal content parts (core) (#1913)

- `ContentPart` = `Text(text) | Image(source: MediaSource, mediaType) | Document(source, mediaType)`,
  using `org.llm4s.media`.
- `UserMessage` and `ToolMessage` carry `parts: Vector[ContentPart]`; `UserMessage(text)` remains as the
  one-part constructor.
- Each provider module maps the parts it supports. Any other part fails the request with a typed
  `LLMError` naming the part kind, never dropped silently.
- `ProviderModuleChecks` gains `assertContentParts`, and every chat provider module's spec calls it.
- The graph and tool-result model carry parts unchanged, so a tool can return an image.

### F9: typed custom streams (#1914)

- `agent.stream(threadId, query, events = Set(eventTypes))(listener)` and `AgentRun.subscribe`, filtered
  by `EventType[A]`, with typed payloads.
- The same in `AgentIO.stream` (fs2), `AgentZ.stream` (ZIO) and the Pekko source.
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

- `AgentBuilder.withHarness(profile)` installs the middleware in a fixed, documented order: permissions
  outermost, then offload, summarisation, skills, instructions, todos. It adds the selected tools and
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
  - a SQLite checkpoint part way through, then resume in a fresh runtime, with the report file present
    afterwards
- **Coding/workspace:**
  - a workspace sandbox (fake backend in unit runs; `@Workspace` IT tier for the live container) under
    rules that `Allow` writes under `/src/**` and `Ask` on `execute`
  - the run suspends at `execute`, resumes approved, and runs the command exactly once
  - edited files present in the workspace after resume

Each example has assertions for each of the five exit capabilities.

## Delivery

- One `[PHASE 3] Deep Agents harness profile` umbrella issue, linked to #1266, listing the slices, the
  lanes and the exit criteria, as #1699 does.
- One issue per slice (15). Each slice has one design-record file,
  `docs/design/typed-agent-runtime/4.2x-stage-3-slice-<n>-<name>.md`, and one PR.
- §4.9 changes:
  - the permissions/timeouts row is marked as owned by C2
  - child-run cancellation moves to Stage 4
  - §5.6 notes that wrappers replace `beforeModel`/`afterModel`
- One migration note covers the stage:
  - the `wrapModelCall` signature
  - `WorkspaceTools` removed
  - `Message` content parts
  - the new `llm4s-agent-memory` module
- Each PR adds its CHANGELOG entry under `## [Unreleased]`.
