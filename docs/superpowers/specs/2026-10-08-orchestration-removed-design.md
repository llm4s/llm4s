# Orchestration removed: PlanRunner, DAG, TypedAgent and CancellationToken (#1330)

Slice 4 of Stage 1 (#1326). Design record: `docs/design/typed-agent-runtime-design.md` §4.9, §7, §8.

## Intent

#1330 asks for `PlanRunner`, `DAG` and `TypedAgent` to be rebuilt on typed graph contracts, or removed
if their contracts cannot be expressed without `Map[String, Any]`, and for `CancellationToken` to be
deleted so that cancellation is thread interruption only.

**Decision: remove them and add a graph sample in their place.**

- `PlanRunner.execute` takes and returns `Map[String, Any]` and casts every node to
  `TypedAgent[Any, Any]`. That untyped boundary is the contract itself; §7 and §8 of the design say to
  remove it rather than preserve it.
- `GraphBuilder` already covers everything the package offered, with typed handles: `NodeRef[I]` nodes,
  edges, static joins for fan-in, parallel supersteps under Ox, per-node `RetryPolicy`/`CachePolicy`,
  `RunBudgets.maxConcurrency` and `timeout`, and cancellation through `RunHandle.cancel`. A thin typed
  DSL over it would be a second public way to build the same graphs. It would also overlap Stage 4's
  typed subagent delegation (`TypedAgent[I, O]` done properly, design §6).
- Nothing outside the package uses it: no other module, sample, Java/Kotlin API or `Agent` code.

Success criteria:

1. `org.llm4s.agent.orchestration` and core's `PlanId` are gone, and the build, tests, scalafix and
   Scaladoc pass.
2. An `Agent` called inside a graph node is cancelled when the graph run is cancelled.
3. A cookbook recipe shows a multi-agent graph: fan-out, deterministic update order, a static-join
   barrier, step boundaries and cancellation. It meets #1326's "parallel update sample" exit
   criterion, and CI runs it with no network.
4. The migration guide maps every removed type to its replacement.

## 1. Removals

`llm4s-agent`, package `org.llm4s.agent.orchestration`. All of these are deleted, with no shims or
deprecations (pre-1.0):

| Main | Spec |
|---|---|
| `PlanRunner.scala` | `PlanRunnerSpec`, `IntegrationSpec`, `OrchestratorIntegrationSpec`, `OrchestrationTest` |
| `DAG.scala` (`Node`, `Edge`, `Plan`, `Plan.PlanBuilder`) | `DAGSpec` |
| `TypedAgent.scala` | `TypedAgentSpec` |
| `Policies.scala` (`withRetry`, `withTimeout`, `withFallback`, `withPolicies`) | `PoliciesSpec` |
| `OrchestrationError.scala` | `OrchestrationErrorSpec` |
| `MDCContext.scala` (`private[agent]`) | `MDCContextSpec` |
| `CancellationToken.scala` | `CancellationTokenSpec` |

`llm4s-core`: `org.llm4s.types.PlanId` and its cases in `TypesSpec`. `PlanRunner` was its only user,
and pass 1 removed unused `types` vocabulary on the same grounds. `AsyncResult` stays, because
`llm4s-rag`'s async vector store and the samples use it. `AgentId` stays, because `Agent` uses it.

`project/PomDescriptions.scala` says "orchestration" in `llm4s-agent`'s description; this becomes
"graph runtime".

## 2. `Agent.run` cancels the run it started when its caller is interrupted

Today `Agent.run(threadId, query, config, history)` is `start(...).flatMap(_.await())`. If the calling
thread is interrupted, `RunHandle.await` returns `Left(CancelledError)` and re-sets the interrupt
flag, but the agent's run keeps going on its own thread. An agent called inside a graph node therefore
survives cancellation of the graph run: the node's task is interrupted, but the inner run is not.

Change: when `await()` returns `Left(e: CancelledError)`, `run` calls the `AgentRun`'s `cancel()` and
then returns that same `Left`. The interrupt flag stays set (§4.4: never clear the flag on a path that
returns to the caller). `continueConversation`, `runMultiTurn` and the other `run` overloads go through this method,
so they get the same behaviour. `recover` and `resume` block the same way (`startRecover`/`startResume`
then `await`), so they share the rule, through one private helper. `start`/`AgentRun.await` are unchanged: a caller holding the
`AgentRun` decides for itself whether to cancel it.

`cancel()` on a run that has already finished is a no-op, because `stop` records a cause only if none
is recorded, and a finishing run records `Finishing`. That makes the call safe when the interrupt races
the run's own end.

This is the synchronous case of the §4.9 item "Cancelling a run cancels the child runs it started"
(Stage 3). The general case still belongs to Stage 3: a node that `start`s a child and returns without
awaiting it, and child runs on another runtime.

`AgentRun.await`'s Scaladoc currently says that on an interrupt "the turn keeps running". That stays
true for `await` itself; `Agent.run`'s Scaladoc gains the new rule.

Test (`llm4s-agent`, `AgentRunCancellationSpec`): an agent whose model call blocks until interrupted,
run on a separate thread through `Agent.run`. Interrupting that thread makes `run` return
`Left(CancelledError)`, and the interrupt flag is still set on that thread when `run` returns. The
agent's run really ends: the blocked model call observes its interrupt, and a later
`agent.run(sameThreadId, ...)` is refused with `GraphError.IncompleteRun`, not `ThreadBusy`. A
cancelled run leaves its checkpoint `Running`, so `recover` continues it, and the test shows that
`recover` then works. Before the fix the inner run stays blocked, so the second call gets
`ThreadBusy`; the test fails without the change.

## 3. Cookbook recipe `multi-agent-graph`

`modules/samples/src/main/scala/org/llm4s/samples/cookbook/MultiAgentGraphRecipe.scala`, registered in
`Cookbook.apps` and listed in `docs/examples/cookbook.md` under `<!-- recipe: multi-agent-graph -->`,
with its snippet between `// snippet:start` and `// snippet:end` as the other recipes do.
`CookbookDocsSpec` keeps the page and the source in step.

**Graph** (`GraphBuilder("multi-agent-review", "v1")`):

```
brief (String) ──goto──► optimist (Unit) ──┐
               └─goto──► skeptic  (Unit) ──┴─ staticJoin "views" ──► editor (Unit)
```

- State keys:
  - `question`: `StateKey.replace[String]`
  - `views`: `StateKey.appending[View]`, where `View(specialist: String, text: String)` derives
    `ReadWriter`
  - `answer`: `StateKey.replace[String]`
- `brief` writes `question` and routes to both specialists.
- Each specialist node reads `question` and runs its own `Agent` (built from the same `LLMClient`, each
  with its own system prompt) through `Agent.run`. It appends one `View` and maps a non-completed
  `AgentStatus` or a `Left` to the node's failure.
- `editor` runs an editor `Agent` over the question and both views, and writes `answer`.
- The graph's output is `Review(answer: String, views: Vector[View])`.
- The recipe runs the graph on `GraphRuntime.inMemory()` with `ThreadId("review-1")` and subscribes
  to the thread. The demo prints each `CheckpointCommitted(superstep)` as it arrives, then the views
  and the answer.

The recipe exposes the same parts the other recipes do: `info`, a `script: ScriptedClient` that
answers by system prompt, `start(runtime, client, question): Result[RunHandle[Review]]` (the spec
cancels through it), `run(client, question): Result[Review]` (start on a fresh in-memory runtime and
await) and `demo`.

**Spec** (`MultiAgentGraphRecipeSpec`), run with scripted clients:

1. **Deterministic update order:** `views` lists the optimist then the skeptic, even when the
   optimist's scripted reply is delayed so that it finishes last.
2. **The barrier:** the editor is called exactly once, after both specialists, and its conversation
   contains both views.
3. **Step boundaries:** the subscription sees one `CheckpointCommitted` per superstep:
   - `brief`
   - both specialists in one superstep
   - `editor`

   The spec asserts the count the runtime actually commits, which the plan confirms against
   `GraphRuntime`, including any admission or closing commit.
4. **Cancellation:** a script whose skeptic blocks until interrupted. `RunHandle.cancel()` ends the
   graph run `RunResult.Failed(_, GraphError.Cancelled(...))`, and the skeptic agent's inner run is
   cancelled too: its blocking model call observes the interrupt. Before §2 it would sleep on
   after the graph run had ended. The `IncompleteRun` check is §2's agent-level test; the recipe's
   agents are internal to it. This is what
   replaces `CancellationToken`.
5. **The demo** prints the answer.

## 4. Documentation

- **`docs/reference/migration.md`, "Stage 1 migration: agent runtime":** a new subsection,
  "Orchestration removed (#1330)", with this table and a pointer to the recipe:

  | Removed | Use instead |
  |---|---|
  | `TypedAgent[I, O]`, `TypedAgent.fromFunction` and the other factories | a `GraphNode[I]` given to `GraphBuilder.node`; an `Agent` called inside the node for an LLM step |
  | `Node`, `Edge`, `Plan`, `Plan.builder` | `GraphBuilder.node` / `edge` / `staticJoin` / `dynamicJoin`, then `compile(entry)(output)` |
  | `PlanRunner.execute(plan, inputs, token)` | `GraphRuntime.start(threadId, graph, input).flatMap(_.await())` |
  | `PlanRunner(maxConcurrentNodes)` | `RunConfig` with `RunBudgets(maxConcurrency = n)` |
  | `Policies.withRetry` | `retry = RetryPolicy(...)` on `GraphBuilder.node` / `implement` |
  | `Policies.withTimeout` | `RunBudgets.withTimeout` (whole run); a node bounds its own calls |
  | `Policies.withFallback` | ordinary `Result` code in the node (`primary.orElse(fallback)`) |
  | `OrchestrationError` | `GraphError` |
  | `CancellationToken` | `RunHandle.cancel()` / `AgentRun.cancel()`, or interrupting the calling thread |
  | `PlanId` | `RunId` |

- **CHANGELOG `[Unreleased]`:**
  - under Removed: the package and `PlanId`, linking the migration subsection
  - under Changed or Fixed: `Agent.run` cancels its run when interrupted
  - under Added: the recipe
- **`docs/guide/error-handling.md`:** remove the `OrchestrationError` rows and mentions, pointing to
  `GraphError`.
- **`docs/reference/review-guidelines.md`:** drop `agent.orchestration` from the `NoKeywordTry`
  exemptions.
- **`docs/design/typed-agent-runtime-design.md`:**
  - a new §4.15 "Stage 1 slice 4: orchestration removed (#1330)", recording the decision, the
    `Agent.run` cancellation rule and the recipe
  - the §4.9 rows for `PlanRunner` and `CancellationToken` struck through and marked "closed by
    #1330"
  - the §4.4 line "`CancellationToken` remains for `PlanRunner` until it is rebuilt" updated, and the
    §4.14 pointer updated
- **`docs/design/agent-framework-roadmap.md`:** where it presents `PlanRunner`/`CancellationToken` as
  current features, mark them replaced by the graph runtime.

Historical documents are left alone: older CHANGELOG entries, the Phase 1.3 design, the gap
analysis, and the earlier migration sections. They describe what was true then.

## Out of scope

- Typed subagent delegation (`task`, `TypedAgent[I, O]`): Stage 4.
- Cancelling children a node starts without awaiting them, or children on another runtime: Stage 3.
- A per-node timeout: tool timeouts are Stage 3 (§4.9).
- Java and Kotlin facades: neither exposes orchestration.
