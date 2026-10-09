---
layout: page
title: Migration Guide
parent: Reference
nav_order: 2
---

# Migration Guide

## Stage 1 migration: agent runtime

Not in a release yet ([#1328](https://github.com/llm4s/llm4s/issues/1328), with [#1329](https://github.com/llm4s/llm4s/issues/1329)'s events and tracing, which restore the agent event stream #1328 removed). `Agent` now runs on `GraphRuntime`: the graph is the only agent loop, `AgentState` and the legacy loop are deleted, and nothing runs the old loop beside the new one. Tools, guardrails, handoffs and context pruning belong to the agent, set when you build it, and a conversation is carried by its `ThreadId` instead of by a value you pass back in. Design: `docs/design/typed-agent-runtime-design.md` §4.13. This note covers the whole of Stage 1 ([#1326](https://github.com/llm4s/llm4s/issues/1326)), five slices:

- [#1327](https://github.com/llm4s/llm4s/issues/1327), kernel completion: [below](#kernel-completion-1327).
- [#1328](https://github.com/llm4s/llm4s/issues/1328), the agent loop on `GraphRuntime`: this section.
- [#1329](https://github.com/llm4s/llm4s/issues/1329), events and tracing: the bullets this section marks #1329.
- [#1330](https://github.com/llm4s/llm4s/issues/1330), orchestration removed: [below](#orchestration-removed-1330).
- [#1331](https://github.com/llm4s/llm4s/issues/1331), cancellation for non-chat clients: [below](#cancellation-for-non-chat-clients-and-mcp-tool-hints-1331).

```scala
// before
val agent = new Agent(client)
val state = agent.run("query", tools, inputGuardrails = in, outputGuardrails = out, maxSteps = Some(10))

// after
val result = for {
  agent  <- Agent.builder("assistant", client)
              .withTools(tools)
              .withMiddleware(new GuardrailMiddleware(in, out))
              .withMaxSteps(10)
              .build()
  result <- agent.run("query")
} yield result
```

- **`new Agent(client).run(q, tools, ...)` is `Agent.builder(id, client)...build()` then `run(q)`.** `tools`, `systemMessage`, `completionOptions`, `maxSteps` and `handoffs` move to `withTools` (a `ToolRegistry`, `MCPToolRegistry` included, or a `ToolSet`), `withSystemPrompt`, `withCompletionOptions`, `withMaxSteps` and `withHandoffs`. `build()` returns `Result[Agent]` and refuses clashing tool names, an invalid or duplicate handoff id, and `maxSteps < 1`. `run` has the overloads `run(query, config)`, `run(threadId, query)`, `run(threadId, query, config)` and `run(threadId, query, config, history)`; `config` is a `RunConfig` (budgets, deadline).
- **Per-run guardrails are middleware.** `inputGuardrails`/`outputGuardrails` arguments become `.withMiddleware(new GuardrailMiddleware(input, output))` on the builder. A block is no longer an error: the run returns `Right` with `AgentStatus.Blocked(guardrail, reason)` and the thread stays usable (its checkpoint is `Failed`, which `recover` has nothing to continue and the next `run` accepts). An input block stores nothing of that turn's query (a new thread still keeps its imported `history`); an output block removes the whole turn - query, tool calls and results, answer, and any handoff made in it - so `result.messages` is the history from before the turn; `usage` keeps the turn's model calls. Another middleware's `beforeAgent`/`afterAgent` `Left` ends the run the same way but is returned as that `Left`, and a blank query - given, or produced by a `beforeAgent` - is a `ValidationError` with nothing stored. A guardrail that transforms (`PIIMasker`) now applies its transformation. Guardrails - and any run-boundary middleware (`beforeAgent`, `afterAgent`) - on the root agent guard the whole handoff family: they apply to every turn's query and final answer, whichever agent is active, outside the active agent's own. Give them to the root only; model and tool wrappers stay per agent.
- **`continueConversation(state, ...)` is `continueConversation(result, ...)`.** It reads only `result.threadId`; `run(threadId, query)` is the same call by thread. A turn on a `Suspended` result is refused, not layered on parked work.
- **Threads are kept until forgotten.** An `AgentState` was garbage once dropped; a thread lives in the agent's runtime, and one-shot `run` threads stay in the runtime until `forget`. Call `agent.forget(threadId)` (or `GraphRuntime.deleteThread(threadId, config)`) for a conversation you will not continue. `Checkpointer` implementations gain `deleteThread(threadId)`.
- **`runMultiTurn` with `contextWindowConfig` is `runMultiTurn(first, followUps, config)` on an agent built with `new ContextWindowMiddleware(config)`.** Pruning trims what is sent to the model and never what the thread stores; the current turn (latest user message onward) is never pruned - the strategy, `Custom` included, runs only on the history before it, with the budget the turn leaves - the request always starts with a user message (so it may exceed the budget), and the system prompt is outside the budget. `runMultiTurn` stops at the first status that is not `Completed`.
- **`AgentState` fields move to `AgentResult`.**

  | `AgentState` | Now |
  |---|---|
  | `conversation` | `result.messages` (the thread's full history, never a system prompt) |
  | `status` | `result.status` (below) |
  | `logs` | removed; use `withTracing` and the `graph.*` events |
  | `usageSummary` | `result.usage` (accumulated over the thread) |
  | `tools`, `systemMessage`, `completionOptions`, `availableHandoffs`, `initialQuery` | the builder; `activeAgent` is on the result |

- **`AgentStatus` cases are replaced.** `Complete` is `Completed(answer)`; `Failed(error)` is `Left(GraphError...)` (provider errors as `GraphError.NodeFailed(cause)`; the thread stays recoverable with `recover`); `InProgress` and `WaitingForTools` are not exposed; `HandoffRequested` is gone, because a handoff runs inside the same run. New: `Blocked(guardrail, reason)`, `StepLimitReached` and `Suspended(approvals, questions)`, continued with `resume(threadId, answers)` and `result.approve`/`reject`/`edit`/`reply`.
- **`AgentContext` is removed.** `tracing` becomes `Agent.builder(...).withTracing(tracing)`, which emits the runtime's `graph.*` custom events for each run; `debug` and `traceLogPath` are gone. `TraceEvent.AgentStateUpdated` is no longer emitted by the agent; #1329 then replaced it with `TraceEvent.AgentRunEnded`.
- **`Handoff(agent)` is `Handoff(id, builder)`, and `transferSystemMessage` is removed.** The target is an `AgentBuilder`, not a built agent, and its id must equal the handoff id: `Handoff.to("physics", physicsBuilder, "reason")`. A target that must hand back to an agent already in the graph is named by id, `Handoff.toId("triage", "reason")`, so cycles compile. Each agent sends its own system prompt, which is never stored. A self-handoff is refused at build, and a handoff mixed with other tool calls in one message is refused at run time with an error result for every call. `preserveContext = false` (a parameter of `Handoff.to`, `Handoff.of` and `Handoff.toId`) sends the target the last user message before the transfer, then the transfer onwards.
- **`runStep`, `initializeSafe`, `runWithStrategy` and `ToolExecutionStrategy` (on the agent) are removed.** `agent.start(threadId, query)` returns an `AgentRun` (`threadId`, `runId`, `status`, `await()`, `cancel()`) at once; `run` is `start` then `await`; `startRecover` and `startResume` are the same for `recover` and `resume`. The parallel tool calls of one message are bounded by `RunBudgets.maxConcurrency`. `ToolExecutionStrategy` stays in core as a `ToolRegistry` feature; only the agent's use of it is gone. Step-by-step observation is `Agent.stream` or `AgentRun.subscribe` (#1329, below).
- **`runWithEvents`, `continueConversationWithEvents`, `runCollectingEvents`, `AgentEvent` and `AgentStreamingExecutor` are removed** (#1329 replaces them with `Agent.stream`, below). `TraceEvent.AgentStateUpdated` is replaced by `TraceEvent.AgentRunEnded`, and `AgentState#toTraceEvent` is gone.

  | Before | After |
  |---|---|
  | `agent.runWithEvents(query)(onEvent)` | `agent.stream(threadId, query)(listener).flatMap(_.await())` |
  | `runCollectingEvents` | collect in the listener, or replay with `GraphRuntime.subscribe(threadId, afterSeq = 0)` |
  | `AgentEvent.TextDelta(delta)` | `AgentEvents.TextDelta(d)` (`d.text`, `d.attempt`) with `withStreaming()` |
  | `ToolCallStarted`/`ToolCallCompleted`/`ToolCallFailed` | `AgentEvents.ToolCallStarted`, `ToolCallResult` (live), `ToolExecuted` (durable, with outcome) |
  | `HandoffStarted`/`HandoffCompleted` | `AgentEvents.HandedOff` |
  | `InputGuardrail*`/`OutputGuardrail*` | `AgentEvents.GuardrailBlocked` (on a block); the outcome on `AgentStatus.Blocked` |
  | `AgentStarted`/`AgentCompleted`/`AgentFailed`, `StepStarted`/`StepCompleted` | kernel events: `RunStarted`, `RunCompleted`, `RunFailed`; `ModelCallStarted`/`ModelCallCompleted` |
  | `TraceEvent.AgentStateUpdated` | `TraceEvent.AgentRunEnded` |
  | `context.progress(payload)` | `context.progress(name, version, payload)`, or an `EventType` |
  | `ModelStep.next(messages, tools)` | `next(messages, tools, call)` |

  Also: `AgentBuilder.withStreaming()`, `AgentRun.subscribe`, `AgentIO.stream*` and `AgentZ.stream*` are new. Langfuse traces now use the run id as the trace id and the thread id as the session id. `await()` returns once the listener has returned from the run's last event, so whatever it collected is complete. `AgentRunEnded.usage` is the run's own usage, not the thread's: a dashboard that summed the old cumulative figure per run double counted. A slow `AgentIO.stream`/`AgentZ.stream` consumer loses live events and gets a `StreamEvent.LiveGap` with their count; it no longer cancels the run. See the [streaming guide](../guide/agents/streaming.html).
- **Session files: `AgentState.saveToFile`/`loadFromFile` become saved messages plus `history`.** Save `result.messages` (a `Vector[Message]` has a `ReadWriter`) from a completed run, and later `agent.run(newThreadId, query, RunConfig(), history = saved)` on a thread that does not exist. `history` is refused on an existing thread, with a system message in it (prompts belong to the agents), or if it ends mid tool call. `ConversationPersistenceExample` shows it. Old session JSON files are not read.
- **`AgentIO` and `AgentZ` wrap the new `Agent`.** `LLMClientIO.agent(id)(configure)` and `LLMClientZ.agent(id)(configure)` take a function over the `AgentBuilder` (tools now belong to the agent); `run`, `continueConversation`, `recover` and `resume` take a `RunConfig` and return the `AgentResult`. Cancelling the fiber cancels the run. A thrown exception arrives as `GraphError.NodeFailed` carrying the original.
- **`CodeWorker`.** `executeTask` loses `traceLogPath` and returns `Result[AgentResult]`; `WorkspaceSettings.traceLogPath` and `WORKSPACE_TRACE_LOG` are removed. `AssistantAgent` builds its agent once and keeps a thread id; `SessionState` holds the thread id and the last result, and its `consoleConfig` parameter is gone.
- **Graph loop API.** `ToolLoop.build(id, version, root, agents: Vector[LoopAgent])` builds a family of agents (the earlier `ToolLoop.build(id, version, model, tools, middleware)` is gone; `LoopAgent(id, model, tools)` with `withSystemPrompt`, `withMaxSteps`, `withMiddleware` and `withHandoffs` replaces its arguments), and `ModelStep.next` returns a `Completion`, so a `wrapModelCall` middleware's `next` returns `Result[Completion]`.
- **Errors.** Provider, tool and middleware failures are `Left(GraphError...)`; the error content a tool failure gives the model is `{"error": ...}`. A guardrail block, the step limit and a suspension are `Right`.
- **Samples.** `AsyncToolAgentExample` is deleted; `StreamingAgentExample`, `StreamingWithToolsExample` and `EventCollectionExample` are rewritten on `Agent.stream` (#1329); the other agent samples use the builder.

### Kernel completion (#1327)

`GraphBuilder.implement`, `node` and `resumeNode` take `retry: RetryPolicy` and `cache: Option[CachePolicy]`. Both default to off, so a graph that sets neither runs as before. `CompiledGraph.toMermaid` is new. These are source breaks, with no shims:

- **`ToolContext`, `GraphError.ToolFailed`, `ModelRequest` and `ToolCallRequest` have a private constructor and no public `copy`.** Build them with `X(...)` and change them with `withY(...)`.
- **Tool call ids and names are typed.** `ToolContext.toolCallId`, `GraphError.ToolFailed.tool` and `.toolCallId` are the opaque `ToolCallId` and `ToolName` types: read the string with `.value`, and make an id with `ToolCallId(call.id)`. `ToolCallRequest` gains `toolCallId` and `toolName`.
- **`recover` re-runs a failed task under the node's full retry policy.** It used to give the task one more try.

### Cancellation for non-chat clients and MCP tool hints (#1331)

Embedding, reranker, MCP, image clients and the Whisper and Tacotron2 speech engines now return `Left(CancelledError)` when interrupted, as the cloud speech clients already did, with the thread's interrupt flag still set, and never retry it. Before, they reported an error of their own, and some reported a cancelled call as a success. Match `CancelledError` where you matched those errors: an embedding provider's `embed` can now return `Left(CancelledError)` where it returned only an `EmbeddingError`, so a match that narrows its `Left` to `EmbeddingError` needs a case for it. The CHANGELOG entry for #1331 lists every client's change. Source breaks, with no shims:

- **Image generation errors** are `LLMError`s, with renamed cases: see [Image generation errors are `LLMError`s](#image-generation-errors-are-llmerrors).
- **`MCPTransportImpl.sendRequest`, `sendNotification`, `MCPClient.initialize` and `getTools` return `Result`** instead of `Either[String, _]`: read the old string as `error.message`.
- **`ToolHints` moves to `llm4s-core`**: import `org.llm4s.toolapi.ToolHints`, not `org.llm4s.agent.graph.tool.ToolHints`.

`llm4s-mcp` also reads `ToolHints` from a server's tool annotations (`MCPClient.getToolHints`, `MCPToolRegistry.toolHints(name)`), but only for a server configured with `trustAnnotations = true` on its `MCPServerConfig` (default `false`). Any other server reports no hints, so `ToolHints.default` (approval required) applies.

### Orchestration removed (#1330)

`org.llm4s.agent.orchestration` is deleted: `PlanRunner`, `Plan`, `Node`, `Edge`, `TypedAgent`, `Policies`, `OrchestrationError` and `CancellationToken`, with `org.llm4s.types.PlanId` and `org.llm4s.types.AgentId` from `llm4s-core` (the agent's id is `org.llm4s.agent.AgentId`). `PlanRunner` passed `Map[String, Any]` between nodes and cast each node to `TypedAgent[Any, Any]`. A typed graph does the same job with checked handles, checkpoints and recovery. The [multi-agent graph recipe](../examples/cookbook#6-several-agents-in-one-graph) is a worked replacement.

| Removed | Use instead |
|---|---|
| `TypedAgent[I, O]`, `TypedAgent.fromFunction` and the other factories | a `GraphNode[I]` given to `GraphBuilder.node`; call an `Agent` inside the node for an LLM step |
| `Node`, `Edge`, `Plan`, `Plan.builder` | `GraphBuilder.node` / `edge` / `staticJoin` / `dynamicJoin`, then `compile(entry)(output)` |
| `PlanRunner.execute(plan, inputs, token)` | `GraphRuntime.start(threadId, graph, input).flatMap(_.await())` |
| `PlanRunner(maxConcurrentNodes)` | `RunConfig` with `RunBudgets(maxConcurrency = n)` |
| `Policies.withRetry` | `retry = RetryPolicy(...)` on `GraphBuilder.node` / `implement` |
| `Policies.withTimeout` | `RunBudgets.withTimeout` (the whole run); a node bounds its own calls |
| `Policies.withFallback` | ordinary `Result` code in the node (`primary.orElse(fallback)`) |
| `OrchestrationError` | `GraphError` |
| `CancellationToken` | `RunHandle.cancel()` / `AgentRun.cancel()`, or interrupting the calling thread |
| `org.llm4s.types.PlanId` | `RunId` |
| `org.llm4s.types.AgentId` | `org.llm4s.agent.AgentId` |

```scala
// before
val plan   = Plan.builder.addNode(research).addNode(summary).addEdge(Edge("e", research, summary)).build
val result = PlanRunner().execute(plan, Map("research" -> question), token)   // Future[Result[Map[String, Any]]]

// after
val b        = GraphBuilder("research", "v1")
val findings = StateKey.replace[String]("findings", "")
val digest   = StateKey.replace[String]("digest", "")
val summary = b.node[Unit]("summary", writes = Set(digest)) { (_, state, _) =>
  NodeResult.fromResult(for {
    f    <- state.get(findings)
    turn <- summariser.run(s"Summarise: $f")
    text <- turn.answer.toRight(ValidationError("summary", "no answer"))
  } yield Command.empty.update(digest, text))
}
val research = b.node[String]("research", writes = Set(findings)) { (q, _, _) =>
  NodeResult.fromResult(
    researcher.run(q)
      .flatMap(_.answer.toRight(ValidationError("research", "no answer")))
      .map(f => Command.empty.update(findings, f).goto(summary))
  )
}
val handle = b.compile(research)(_.get(digest)).flatMap(GraphRuntime.inMemory().start(ThreadId("t-1"), _, question))
handle.foreach(_.cancel())   // instead of token.cancel()
```

`Agent.run`, `continueConversation`, `runMultiTurn`, `recover` and `resume` now cancel their turn when the calling thread is interrupted, and return once it has ended (waiting up to 5 seconds for the turn to end), so `recover` can follow at once; a caller already interrupted starts no turn. Cancelling a graph run therefore also cancels the agent turns its nodes are waiting on. Before, the turn kept running after `run` returned `Left(CancelledError)`. A caller that wants the turn to outlive an interrupt uses `start`, `startRecover` or `startResume`, and awaits the `AgentRun` itself. With tracing, the cancelled turn's trace is complete when the call returns. A turn that had already begun committing its outcome when the interrupt came cannot be cancelled: the call returns that outcome (`Right`, `Completed` or `Suspended`), with the interrupt flag still set - test the flag, not only the result, if an interrupt must stop your own code. `run(query)`, whose random thread id a `Left` does not carry, forgets the thread of a turn that failed or was cancelled once it has ended; name the thread (`run(threadId, query)`) to recover such a turn. The Java facade's `JAgent.run`, `continueConversation`, `resume` and `recover` go through these calls, so an interrupted Java caller cancels its turn too; they used to stop only the wait.

## Agent middleware

Not in a release yet ([#1279](https://github.com/llm4s/llm4s/issues/1279)). The graph tool loop
takes a stack of `AgentMiddleware` in place of a `ToolCallPolicy`: one ordered extension point with
`beforeAgent`, `afterAgent`, `wrapModelCall` and `wrapToolCall` hooks, for approval, guardrails,
logging, retry and rate limits. The graph runtime is Experimental, so these are source breaks with
no shims. Design: `docs/design/typed-agent-runtime-design.md` §4.8. (The `ToolLoop.build` signature shown below was generalised by #1328: see [Stage 1 migration](#stage-1-migration-agent-runtime).)

- **`ToolCallPolicy` and `PolicyDecision` are removed, and `ToolLoop.build` loses `policy`.** It
  takes `middleware: Seq[AgentMiddleware] = Nil` instead. A policy becomes an `AgentMiddleware`
  overriding `wrapToolCall`: `Allow` is `next()`, `Deny(reason)` is
  `ToolOutcome.Error(s"Denied: $reason")`, and `RequireApproval(reason)` is
  `if context.approved then next() else ToolOutcome.NeedsApproval(reason)` - or use
  `ApprovalMiddleware`:

  ```scala
  // before
  val policy: ToolCallPolicy = call =>
    call.name match
      case "drop_table" => PolicyDecision.Deny("never allowed")
      case "deploy"     => PolicyDecision.RequireApproval("deploys are irreversible")
      case _            => PolicyDecision.Allow
  ToolLoop.build("assistant", "v1", model, tools, policy)

  // after
  val guard = new AgentMiddleware:
    val id = MiddlewareId("guard")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      request.call.name match
        case "drop_table"                  => ToolOutcome.Error("Denied: never allowed")
        case "deploy" if !context.approved => ToolOutcome.NeedsApproval("deploys are irreversible")
        case _                             => next()
  ToolLoop.build("assistant", "v1", model, tools, Seq(guard))

  // or, for approval alone
  ToolLoop.build("assistant", "v1", model, tools, Seq(ApprovalMiddleware.unlessReadOnly))
  ```

- **`ApprovalSource.Policy` becomes `ApprovalSource.Middleware(id)`**, naming the middleware that
  asked; a tool's own request is still `ApprovalSource.Tool`.
- **`Approve` now re-runs the chain.** It used to skip the policy and run the tool; it now runs the
  whole middleware chain again, from the outermost wrapper, with `ToolContext.approved = true`, as
  `Edit` does with the new arguments. A deny rule that depends only on the call refuses the same
  calls as before; a wrapper that asks for approval must pass when `context.approved` is set, or
  the call becomes an error result (`asked for approval again`).
- **Guardrails in the graph loop apply their transformations.** `GuardrailMiddleware(input, output)`
  runs input guardrails in `beforeAgent` and output guardrails in `afterAgent`, each on the value the
  previous one returned, so a guardrail that rewrites its input (`PIIMasker`, say) changes what the
  model sees and what the run answers. The legacy `Agent` validates every guardrail against the
  original value and keeps it, so it never applied a transformation; it is unchanged. A `Block`
  fails the run with the error `CompositeGuardrail.all` reports.

## Agent tool contract and handoff ids

Not in a release yet ([#1278](https://github.com/llm4s/llm4s/issues/1278)). The graph tool loop
runs `AgentTool`s, whose arguments are validated against their schema (rendered non-strict, so
optional fields may be omitted) before any middleware or the tool runs, and legacy handoffs take an explicit id. The graph runtime is
Experimental, so these are source breaks with no shims. Design:
`docs/design/typed-agent-runtime-design.md` §4.7.

(Since #1328, `Handoff` targets are builders and `ToolLoop.build` takes a family of `LoopAgent`s: see
[Stage 1 migration](#stage-1-migration-agent-runtime).)

- **`LoopTool` is `AgentTool[A]`.** A tool now has an `AgentToolSpec[A]` - name, description and
  a core `SchemaDefinition[A]`, with a `ReadWriter[A]` for the arguments - and receives them
  decoded, with a `ToolContext` (run, call id, thread state, `approved`):

  ```scala
  // before
  val search = LoopTool("search")((call, approved) => ToolOutcome.Completed(run(call.arguments)))

  // after
  final case class SearchArgs(query: String) derives ReadWriter
  val schema = Schema.`object`[SearchArgs]("Search arguments").withRequiredField("query", Schema.string("Query"))
  val spec   = AgentToolSpec[SearchArgs]("search", "Search the index", schema)
  val search = AgentTool(spec)((args, context) => ToolOutcome.Success(ujson.Str(run(args.query))))
  ```

  `LoopTool.fromToolFunction(f)` becomes `AgentTool.fromToolFunction(f)`; its arguments are now
  validated against the function's schema too.
- **`toolloop.ToolOutcome` is `tool.ToolOutcome`.** `Completed(content: String)` becomes
  `Success(content: ujson.Value, update)` (a `ujson.Str` is recorded as the string itself);
  `Failed(message)` becomes `Error(message)`; `NeedsApproval` is unchanged. `Ask` and `Fatal` are
  new: `Fatal(error)` fails the run with `GraphError.ToolFailed`, reported inside
  `GraphError.NodeFailed`, and the run is recoverable. A thrown exception is still an error result.
- **Approval now arrives in the context.** `LoopTool`'s `approved` parameter is
  `context.approved`.
- **State updates are declared.** A tool's `Success` update may touch only the keys in its
  `writes`; anything else fails the run. `ToolLoop.build` refuses a tool that declares
  `ToolLoop.results` or `Messages.key`.
- **`ToolLoop.build` takes a `ToolSet`.** Build it with `ToolSet.of(tools*)`, which returns
  `Left(ValidationError)` for an invalid name (`[a-zA-Z0-9_-]{1,64}`), a duplicate name, an
  argument schema that is not an object, or a schema keyword the validator cannot check. `AgentToolSpec.apply` throws
  `IllegalArgumentException` for an invalid name.
- **`ModelStep.next` takes the tool set.** `next(messages)` becomes `next(messages, tools)`, and
  `ModelStep.fromClient(client, options)` replaces `options.tools` with `tools.toolFunctions`, so
  pass the tools to `ToolLoop.build`, not in `CompletionOptions`.
- **Invalid arguments never reach the tool.** Arguments that break the schema, fail to decode or
  fail `withValidation` become the error result `Invalid arguments for '<tool>': ...`. Edited
  approval arguments are checked again, and a middleware's tool wrapper can still deny them.
- **Handoffs take an id.** `Handoff(agent, ...)` becomes `Handoff(id, agent, ...)`, and
  `Handoff.to(agent)` / `Handoff.to(agent, reason)` become `Handoff.to(id, agent)` /
  `Handoff.to(id, agent, reason)`, which throw `IllegalArgumentException` for an invalid id;
  `Handoff.of(id, agent, reason)` returns a `Result`. An id matches `[a-zA-Z0-9_-]{1,52}` and is
  unique within one run's handoffs; an agent run given an invalid or duplicate id fails with
  `ValidationError` before any model call. The handoff tool is `handoff_to_<id>` rather than
  `handoff_to_agent_<hash>`, so a conversation stored with the old name does not match a handoff.

  ```scala
  // before
  agent.run(query, tools, handoffs = Seq(Handoff.to(physicsAgent, "Physics expertise required")))

  // after
  agent.run(query, tools, handoffs = Seq(Handoff.to("physics", physicsAgent, "Physics expertise required")))
  ```

## Run API for graph runs

Not in a release yet ([#1277](https://github.com/llm4s/llm4s/issues/1277)). `GraphRuntime.start`,
`recover` and `resume` return a `RunHandle` once the thread is claimed, and the run executes on a
thread the runtime owns. The graph runtime is Experimental, so these are source breaks with no
shims. Design: `docs/design/typed-agent-runtime-design.md` §4.6.

- **`NodeContext` is `RunContext`.** `context.taskId`, `context.nodeId` and `context.superstep`
  become `context.position.taskId`, `.nodeId` and `.superstep`; `emit` and `progress` are
  unchanged, and `isCancelled` is new. Clients and tools stay captured by node closures; resolve
  per-tenant or per-thread resources from `context.config.tenantId` or `context.position`.
- **Limits are per run.** `compile(entry, maxSupersteps)` becomes `compile(entry)`, and
  `ToolLoop.build` drops `maxSupersteps`. Pass
  `RunConfig(budgets = RunBudgets(maxSupersteps = ..., timeout = ..., maxConcurrency = ...))` to
  each run; `RunBudgets.of` validates untrusted values as a `Result`, while `apply` and the
  `with*` setters throw `IllegalArgumentException` for a non-positive value.
- **`CompiledGraph.run(input)` is removed.** Use
  `GraphRuntime.inMemory().start(threadId, graph, input).flatMap(_.await())`.
- **`step(execution)` is `step(threadId, execution, config)`.** It runs at most
  `config.budgets.maxConcurrency` tasks at a time and checks no superstep limit.
- **`start`/`recover`/`resume(..., runId, durability)` become `(..., config, durability)`** and return
  `Result[RunHandle[O]]`; the run id is `config.runId`. Call `handle.await()` for the
  `RunResult`. Cancel with `handle.cancel()`: interrupting the caller no longer cancels the run,
  and interrupting a thread blocked in `await` returns `Left(CancelledError)` while the run
  continues. A cancel that arrives once the run has begun committing its outcome - completed,
  suspended or failed - is ignored, and the run ends with that outcome.
- **`recover` and `resume` take the thread first, as `start` does.** `recover(graph, threadId, ...)`
  becomes `recover(threadId, graph, ...)`, and `resume(graph, threadId, answers, ...)` becomes
  `resume(threadId, graph, answers, ...)`. Both arguments have different types, so the compiler
  flags every call to swap.
- **Subscribers have their own thread.** `subscribe` gains `capacity` (default 1024, at least 2).
  Listeners run on the subscription's dispatcher thread, not a task or committing thread. A
  listener that falls behind by more than `capacity` durable events is disconnected; a listener
  that throws is disconnected rather than ignored. `StreamEvent` gains `LiveGap(dropped)` and
  `Disconnected(lastSeq, reason)`; resubscribe with `afterSeq = lastSeq` to continue without a gap
  (`TracingSubscriber.attach` included: a lagging tracer is not re-attached for you). A
  subscription belongs to the thread, not one run, and keeps a parked dispatcher thread until
  `cancel()`; cancel subscriptions you no longer need.
- **Tenants are checked.** A run whose `RunConfig.tenantId` differs from the one on the thread's
  latest checkpoint is refused with `GraphError.TenantMismatch(threadId, requested)`, which names
  only the caller's tenant, never the owner's; `None` and `Some` differ. The check
  comes first: a wrong-tenant call gets `TenantMismatch` rather than `IncompleteRun`,
  `PendingInterrupts`, `NothingToRecover`, `NotSuspended` or `ThreadBusy`.
  Checkpoints move to format 3, and earlier checkpoints read as having no tenant.
- **`GraphError` is classified per case.** It extends `LLMError` rather than
  `NonRecoverableError`; every case is still a `NonRecoverableError` except the new
  `DeadlineExceeded`, which is a `RecoverableError` - `recover` it with a new budget. Code that
  relied on `GraphError <: NonRecoverableError` should match the case.
- **New cases break exhaustive matches.** `GraphError.DeadlineExceeded`, `TenantMismatch` and
  `RunCrashed`, `RunEvent.RunTimedOut`, and `StreamEvent.LiveGap` and `Disconnected` are new.
  `RunStarted` is now a case class, and it, `RunRecovered` and `RunResumed` gain `tenantId` and
  `principal`; events in the old encoding still read.

## Provider configs: build with `apply`, change with `with*`

Not in a release yet ([#1388](https://github.com/llm4s/llm4s/issues/1388)). `OpenAIConfig`,
`AnthropicConfig` and `OllamaConfig` follow the growth-prone pattern (pass 5 below), so a new field
no longer breaks Java and Kotlin callers. The constructor and `copy` are private:

- **Scala**: `OpenAIConfig(...)` with the full field list, positional or named, still compiles.
  Replace `config.copy(baseUrl = url)` with `config.withBaseUrl(url)`; each field has a setter
  (`withApiKey`, `withModel`, `withOrganization`, `withContextWindow`, ...), and `OpenAIConfig`'s
  `Option` fields take the value or an `Option`.
- **Java and Kotlin**: call the companion's short `apply` and chain setters instead of the
  constructor - `OpenAIConfig.apply(apiKey, "gpt-4o").withOrganization("org-1")`,
  `AnthropicConfig.apply(apiKey, model)`, `OllamaConfig.apply(model, baseUrl)`. It takes the default
  base URL and a context window from the model name; `fromValues` still consults the bundled model
  catalogue.

## Image generation errors are `LLMError`s

Not in a release yet ([#1331](https://github.com/llm4s/llm4s/issues/1331)). `llm4s-image`'s
generation clients return `Either[LLMError, _]`, so an interrupted call can return
`CancelledError`, and `ImageGenerationError` extends `LLMError`. Source breaks, with no shims:

- **Five cases are renamed** so they no longer share a name with an `org.llm4s.error` type. Both are
  `LLMError`s, so a `match` that imported the wrong one would compile and never fire:

  | Before (`org.llm4s.imagegeneration`) | After |
  |---|---|
  | `AuthenticationError` | `ImageAuthenticationError` |
  | `RateLimitError` | `ImageRateLimitError` |
  | `ServiceError` | `ImageServiceError` |
  | `ValidationError` | `ImageValidationError` |
  | `UnknownError` | `ImageUnknownError` |

- **`ImageServiceError(message, statusCode)`**: the second field was `code: Int`, which clashed with
  `LLMError.code: Option[String]`. It is a sealed type now (`TransientImageServiceError` for `0`, `408`,
  `429` and `5xx`, `RejectedImageServiceError` otherwise); build and match it through
  `ImageServiceError(message, status)` as before.
- **A match on a client's result** needs a case for other `LLMError`s, `CancelledError` among them.

## Cancellation by interrupt

Not in a release yet ([#1270](https://github.com/llm4s/llm4s/issues/1270)). An interrupted call
now returns `Left(CancelledError)` with the thread's interrupt flag still set. Code that matched
`ExecutionError`, `TimeoutError` or `SimpleError` to detect an interrupted call should match
`CancelledError` instead, and `ToolCallError.Cancelled` for a tool call. `CancelledError` is
non-recoverable and is never retried. Chat clients no longer throw `InterruptedException` or report
`UnknownError` for an interrupt.

- **New cases break exhaustive matches.** `ErrorKind.Cancelled` (metric label `cancelled`),
  `RunEvent.RunCancelled` and `ToolCallError.Cancelled` are new; a `match` over `ErrorKind`,
  `RunEvent` or `ToolCallError` needs a case for each.
- **Mid-stream, `NetworkError` becomes `CancelledError`.** A stream read cut off by an interrupt
  used to be a recoverable `NetworkError`; code that retried it should stop on `CancelledError`.
- **`DefaultErrorMapper` and `Try(...).toResult` classify cancellations.** An exception mapped while
  the thread is interrupted, or caused by `InterruptedException` or `ClosedByInterruptException`,
  becomes `CancelledError` rather than `UnknownError` or `NetworkError`. A bare
  `InterruptedIOException` - OkHttp's call timeout is one - stays a timeout. Mapping never sets the
  interrupt flag; code that catches an `InterruptedException` itself must restore it.
- **`ReliableClient`** returns `CancelledError`, not the local `RateLimitError`, when interrupted
  while waiting for a local rate-limit token.
- **Graph supersteps run concurrently.** The default executor ran a superstep's tasks one after
  another; it now runs them concurrently on virtual threads, at most 16 at a time. Node code must be
  thread-safe, a task does not inherit the caller's `ThreadLocal` or MDC context, and in `Sync`
  durability, durable events and live progress are delivered on the task threads, not the caller's.
  Since [#1277](https://github.com/llm4s/llm4s/issues/1277) they are delivered on each
  subscription's dispatcher thread instead (see above).

## Pre-baseline API cleanup, pass 8

Not in a release yet; continues pass 7 below. It closes the last gaps in the frozen modules'
public API found by checking the [re-audit](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5935792540)
against `main`.

- **`llm4s-agent`'s console UI is internal.** `ConsoleInterface`, `ConsoleConfig` (and its
  `StyleConfig`) and `MessageType` are `private[assistant]`: `ConsoleConfig`'s colours were fansi
  `Attrs` and `MessageType` carried a public `cats.Show`, which would have frozen both libraries
  into `llm4s-agent`'s API. `AssistantAgent` loses its `consoleConfig` parameter and the
  four-argument compatibility constructor; construct it with named arguments:

  ```scala
  new AssistantAgent(client, tools, sessionDir = "./sessions", agentContext = AgentContext.Default)
  ```

- `SessionState.localDateTimeRW`, a public implicit upickle codec for `java.time.LocalDateTime`
  that any `import SessionState._` picked up, is `private[assistant]`.
- `org.llm4s.llmconnect.utils.SimilarityUtils` is `private[llm4s]`: only core's caching client
  and `llm4s-rag` use it. Applications needing cosine similarity can compute it directly.

## Pre-baseline API cleanup, pass 7

Not in a release yet; continues pass 6 below, which typed the times a caller supplies. This pass
types the times the library **reports**: an elapsed time is a `FiniteDuration`, a point in time an
`Instant`, and the unit leaves the name. Every JSON, trace and wire format keeps its keys and
millisecond values (`duration_ms`, `durationMs`, `executionTimeMs`, ...).

```scala
// before
case AgentCompleted(state, steps, durationMs, _) => println(s"Done in ${durationMs}ms")
// after
case AgentCompleted(state, steps, duration, _) => println(s"Done in ${duration.toMillis}ms")
```

Check string interpolation in particular: `s"${duration}ms"` still compiles, but prints
`150 millisecondsms`.

| Module | Before | After |
|---|---|---|
| `llm4s-core` | `TraceEvent.ToolExecuted.duration: Long`, `RAGOperationCompleted.durationMs`, `ImageGenerationCompleted.durationMs`, `Tracing.traceRAGOperation(durationMs)` | `duration: FiniteDuration` |
| | `ProviderExchange.durationMs: Long` | `duration: FiniteDuration` |
| | `RateLimitError.requestsRemaining`, `RateLimitError.resetTime` | removed: no constructor could set them, so they were always `None` |
| `llm4s-agent` | `AgentEvent.ToolCallCompleted.durationMs`, `AgentCompleted.durationMs`, `AgentEvent.toolCompleted`/`agentCompleted`'s `durationMs` | `duration: FiniteDuration` |
| `llm4s-agent-tools` | `ShellResult.executionTimeMs`, `HTTPResult.responseTimeMs` | `executionTime`, `responseTime` (the tool's JSON keeps the `...Ms` keys) |
| `llm4s-rag` | `TimingInfo.durationMs`, `durationSeconds`, `avgPerItemMs` | `duration`, `avgPerItem: Option[FiniteDuration]` |
| | `ExperimentResult.totalTimeMs`/`totalTimeSeconds` | `totalTime: FiniteDuration` |
| | `BenchmarkResults.startTime`/`endTime: Long` (epoch ms), `totalDurationMs`/`totalDurationSeconds` | `Instant`s, `totalDuration: FiniteDuration` |
| `llm4s-image` | `ServiceStatus.averageGenerationTime: Option[Long]` (ms) | `Option[FiniteDuration]` |
| `llm4s-speech` | `Transcription.processingTimeMs: Option[Long]` | `processingTime: Option[FiniteDuration]` |
| workspace | `ExecuteCommandResponse.durationMs`, `CommandCompletedMessage.durationMs` | `duration: FiniteDuration` (the protocol keeps `durationMs`) |

## Pre-baseline API cleanup, pass 6

Not in a release yet; continues pass 5 below. A time the caller supplies is typed: a duration is
a `scala.concurrent.duration.FiniteDuration` and a point in time a `java.time.Instant`, never a
raw `Int`/`Long` whose unit lives in its name or its Scaladoc. Names lose their unit suffix
(`timeoutMs` becomes `timeout`). Defaults are unchanged, and so are the wire formats: HOCON keys,
JSON sent to Exa and the workspace runner, and HTTP headers.

```scala
import scala.concurrent.duration.*

// before
Left(RateLimitError("openai", 1000L))              // milliseconds, documented as seconds
CrawlerConfig(delayMs = 500, timeoutMs = 30000)
// after
Left(RateLimitError("openai", 1.second))
CrawlerConfig(delay = 500.millis, timeout = 30.seconds)
```

### `llm4s-core` errors, retry and reliability

- `RecoverableError.retryDelay`, `RateLimitError.retryAfter`/`retryDelay` and
  `ServiceError.retryDelay` are `Option[FiniteDuration]`; `RateLimitError(provider, retryAfter)`
  takes a `FiniteDuration` and the `RateLimitError(message, retryAfter, provider)` extractor yields
  one. `RateLimitError.resetTime` is an `Option[Instant]`. The fallback delay is named
  `RateLimitError.DefaultRetryDelay` (30 seconds, as before).
- `TimeoutError.timeoutDuration`, `ReliabilityConfig.deadline`/`withDeadline`,
  `CircuitBreakerConfig.recoveryTimeout`/`withRecoveryTimeout`, every `RetryPolicy` delay
  (`exponentialBackoff`, `linearBackoff`, `fixedDelay`, `custom`'s function, `delayFor`'s result)
  and `ErrorRecovery`'s `recoveryTimeout` were `Duration` and are `FiniteDuration`: an infinite
  delay or deadline could not be slept or added to a clock. Code passing `5.seconds` is unchanged.
- `ReliableClient` and `ErrorRecovery.CircuitBreaker` take `clock: () => Instant` (was
  milliseconds since the epoch); `ReliableClient`'s `sleep`, `ErrorRecovery`'s and
  `LLMClientRetry`'s `sleepFn` take a `FiniteDuration` (was a `Long` of milliseconds).
  `ErrorRecovery.recoverWithBackoff`'s `baseDelay` is a `FiniteDuration`.

### `llm4s-agent`

`OrchestrationError.AgentTimeoutError` carries `timeout: FiniteDuration` (was `timeoutMs: Long`).

### Beta modules

| Module | Before | After |
|---|---|---|
| `llm4s-agent-tools` | `ExaSearchToolConfig`/`BraveSearchToolConfig`/`DuckDuckGoSearchToolConfig` `timeoutMs: Int`, `ShellConfig.timeoutMs: Long`, `HttpConfig.timeoutMs: Int` | `timeout` |
| | `ExaSearchToolConfig.livecrawlTimeout: Option[Int]` (ms) | `Option[FiniteDuration]`, still sent to Exa in ms |
| `llm4s-rag` | `CrawlerConfig.delayMs`/`timeoutMs`, `withDelay(ms)`/`withTimeout(ms)` (also on `WebCrawlerLoader`), `UrlLoader.timeoutMs`/`withTimeout(ms)` | `delay`/`timeout`, `withDelay(FiniteDuration)`/`withTimeout(FiniteDuration)` |
| | `RobotsTxtParser.isAllowed`/`getRules` `timeoutMs`, `RobotsTxt.crawlDelay: Option[Int]` (s) | `timeout`, `Option[FiniteDuration]` (fractional `Crawl-delay` values are now kept) |
| | `EvaluatorOptions.timeoutMs`, `ChunkingUtils` `windowSeconds`/`clipSeconds`, `RateLimitedLogger` `throttleSeconds` | `timeout`, `window`/`clip`, `throttle` |
| | `HikariDefaults.CONNECTION_TIMEOUT_MS`/`IDLE_TIMEOUT_MS`/`MAX_LIFETIME_MS` | `ConnectionTimeout`/`IdleTimeout`/`MaxLifetime` |
| `llm4s-mcp` | `MCPServerConfig`/transport `timeout: Duration`, `MCPToolRegistry` `cacheTTL: Duration`, `MCPServer.stop(delay: Int)` | `FiniteDuration` |
| | `StdioTransportImpl` `startupTimeoutMs: Int` | `startupTimeout` |
| `llm4s-image` | `ImageGenerationConfig.timeout: Int` (ms), the `imagegeneration.provider.HttpClient` methods' `timeout: Int` | `FiniteDuration` |
| | OpenAI/Anthropic vision configs' `connectTimeoutSeconds`/`requestTimeoutSeconds` | `connectTimeout`/`requestTimeout` |
| workspace | `executeCommand` `timeout: Option[Int]` (s), `WorkspaceSandboxConfig.defaultCommandTimeoutSeconds` | `Option[FiniteDuration]`, `defaultCommandTimeout`; the JSON still carries whole seconds under the old keys |

**Test doubles** of `imagegeneration.provider.HttpClient` type the timeout as `FiniteDuration`; a
ScalaMock `onCall` lambda typed `_: Int` compiles but fails at runtime.

## Pre-baseline API cleanup, pass 5

Not in a release yet; continues pass 4 below. It settles the provider-author SPI's API quality
before the binary-compatibility baseline.

### Growth-prone data types: construct with named arguments, change with `with*`

A case class cannot gain a field without breaking binary compatibility - its constructor,
`apply` and `copy` all change - so the types the library will plausibly extend now have a private
constructor, a public companion `apply` carrying the defaults, and `with*` setters:
`CompletionOptions`, `Completion`, `StreamedChunk`, `TokenUsage`, `ModelCapabilities`,
`ModelMetadata`, `ProviderConfigSpec`, `EmbeddingConfigSpec`, `ProviderFeatures`,
`NamedProviderConfig`, `ReliabilityConfig`, `CircuitBreakerConfig`, `RateLimitConfig`,
`ContextConfig`.

Construction is unchanged. `.copy(...)` is no longer available outside the type:

```scala
// before
val opts = CompletionOptions(temperature = 0.2).copy(maxTokens = Some(500))

// after
val opts = CompletionOptions(temperature = 0.2).withMaxTokens(500)
```

Every field has a `withX`. An `Option` field's setter takes either the value or an `Option`
(`withMaxTokens(500)`, `withMaxTokens(None)`). Match these types by name (`c.usage`), not by
position: a positional pattern breaks when a field is added.

### `Llm4sHttpClient` returns `Result` and takes `FiniteDuration`

Every request method (`get`, `post`, `postBytes`, `postMultipart`, `put`, `delete`, `postRaw`,
`postStream`) returns `Result[...]` and never throws for a transport failure: a timeout is a
`TimeoutError`, a connection or I/O failure a `NetworkError`, an invalid URL, header or timeout a
`ValidationError`, an interruption an `ExecutionError` (with the interrupt flag restored). A
non-2xx status is still a `Right`. `getResult` is removed - `get` is now a `Result` itself.

```scala
// before
val response = Try(http.post(url, headers, body, timeout = 120000)).toResult
// after
import scala.concurrent.duration.*
val response: Result[HttpResponse] = http.post(url, headers, body, timeout = 120.seconds)
```

`HttpRawResponse` and `StreamingHttpResponse` carry `headers`, and every response type has a
case-insensitive `header(name)`. Pass the headers to `HttpErrorMapper.mapHttpError(status, body,
provider, headers)` so a 429's `Retry-After` (seconds or an HTTP date) becomes the
`RateLimitError`'s delay; every built-in provider does.

**Test doubles that implement the trait** return `Result[...]` and take `FiniteDuration`; return a
`Left` to simulate a failure instead of throwing. With ScalaMock, `.returns(resp)` becomes
`.returns(Right(resp))` and `.throws(e)` becomes `.returns(Left(error))`; type the timeout in
`onCall`/`where` lambdas as `FiniteDuration` - a lambda typed `_: Int` still compiles but fails at
runtime.

### `StreamingAccumulator`

| Before | After |
|---|---|
| `new StreamingAccumulator()` | `StreamingAccumulator.create()` (the class is `final`) |
| `getCurrentContent`, `getCurrentThinking`, `getCurrentToolCalls` | `currentContent`, `currentThinking`, `currentToolCalls` |
| `snapshot()`, `AccumulatorSnapshot` | `toCompletion(created)` |
| `StreamingAccumulator.withInitialState(...)` | `create()`, then `addChunk` / `updateTokens` |

### `RequestTransformer` and `TransformationResult`

- `TransformationResult.warnings` is removed: nothing ever filled it.
- `TransformationResult.transform(modelId, options, messages, transformer, dropUnsupported = true)`
  - the required `transformer` now comes before the defaulted flag.
- `RequestTransformer#getDisallowedParams` is now `disallowedParams`.

### `llm4s-provider-testkit`

New, Beta. A provider module's spec mixes in `org.llm4s.testkit.ProviderModuleChecks`; see
[Writing a provider](../guide/writing-a-provider#testing). In this repository,
`CredentialsRoundTrip` and `LocalProviderTestServer` moved from core's test sources to
`org.llm4s.testkit`.

### Internal now

`Llm4sConfig.providerFrom(source)` and `apiKeySourcesFrom(source)` took a pureconfig
`ConfigSource`, which is not part of llm4s's API; they are `private[llm4s]`. Load configuration
with `Llm4sConfig.provider(name)` / `defaultProvider()` / `apiKeySources()`.

## Pre-baseline API cleanup, pass 4

Not in a release yet; continues pass 3 below. Vendor fields leave `NamedProviderConfig`.

**Config files: no change.** `organization`, `endpoint`, `apiVersion`, `contextWindow` and
`reserveCompletion` keep their names in `llm4s.providers.<name>` sections. What changed is who owns
them: each is now a provider-specific key that only its provider declares.

| Key | Declared by |
|---|---|
| `organization` | `openai`, `requesty`, `openrouter` |
| `endpoint` (required), `apiVersion` (default `V2025_01_01_PREVIEW`) | `azure` |
| `contextWindow`, `reserveCompletion` | `openai-compatible` |

In a section for any other provider these keys used to be read and silently ignored; they are now
reported once as unknown keys, with a warning, and dropped - delete them. Vertex AI's deprecated
`endpoint`/`organization` aliases for `project`/`location` work exactly as before. A non-numeric
`contextWindow`/`reserveCompletion` is now reported when the section is resolved, naming the key
(`llm4s.providers.<name>.contextWindow must be a positive whole number, got '...'`), rather than as a
type error while reading the block.

**Scala callers:**

1. `NamedProviderConfig` no longer has `organization`, `endpoint`, `apiVersion`, `contextWindow` or
   `reserveCompletion`. Read the validated value from the section's extras:
   `config.organization` → `config.extra("organization")` (or `OpenAIConfig.OrganizationKey`),
   `config.endpoint` → `config.extra(AzureProvider.EndpointKey)`,
   `config.apiVersion` → `config.extra(AzureProvider.ApiVersionKey)`,
   `config.contextWindow` → `config.extra(OpenAICompatibleProvider.ContextWindowKey).map(_.toInt)`
   (likewise `ReserveCompletionKey`). Values are strings.
2. Code constructing `NamedProviderConfig(...)` drops those arguments; pass them in
   `extras = Map("endpoint" -> ..., ...)` if the descriptor needs them. Positional calls
   `NamedProviderConfig(id, model, baseUrl, apiKey, org, endpoint, apiVersion)` become
   `NamedProviderConfig(id, model, baseUrl, apiKey)`.
3. `ProviderConfigSpec(requiresEndpoint = true, endpointDescription = "...")` →
   `ProviderConfigSpec(extras = Seq(ProviderConfigKey.required("endpoint", "...")))`.
   `ProviderConfigSpec.BuiltinKeys` is now `provider, model, baseUrl, apiKey, headers`, and
   `BuiltinAliasKeys` is `baseUrl, apiKey` - a `deprecatedAliases` entry naming one of the moved keys
   still works, resolved from the section's extras.
4. `ProviderModelListers.openAICompatible` is now in `llm4s-openai-compatible` (package
   `org.llm4s.config` unchanged): a provider module calling it adds that dependency. It no longer
   sends `OpenAI-Organization` from the section; pass
   `sectionHeaders = ProviderModelListers.openAIOrganizationHeader` (and declare
   `OpenAIConfig.OrganizationConfigKey`) to keep it, or any `NamedProviderConfig => Map[String, String]`
   to derive other headers. `ProviderModelLister` and `DiscoveredModel` stay in `llm4s-core`.

## Pre-baseline API cleanup, pass 3

Not in a release yet; continues pass 2 below.

### `llmconnect.middleware`, `ReliableProviders` and `ReliabilitySyntax` are removed

The middleware package had no users outside its own tests and duplicated `caching` and the
metrics every provider client already records. `LLMClient` is a trait, so a decorator is a class
that implements it and delegates. `ReliableProviders.wrap` and `.withReliability(...)` were
shorthand for one constructor:

```scala
// before
val reliable = ReliableProviders.wrap(client, "openai", config)
val other    = client.withReliability("openai")

// after
val reliable = new ReliableClient(client, "openai", config)
val other    = new ReliableClient(client, "openai", ReliabilityConfig.default)
```

`ReliableClient`'s companion factories (`ReliableClient(client)`, `ReliableClient(client, config)`,
`ReliableClient(client, config, metrics)`, `ReliableClient.withProviderName`) go too: three of them
guessed the provider name from the client's class name. Use the constructor. For a client built
from config, the name is `providerConfig.providerId.asString`.

Behaviour fix: **`ReliableClient` now applies `ReliabilityConfig.rateLimit` itself**, before every
attempt, retries included. Before, only `ReliableProviders.wrap` honoured it, so a
`new ReliableClient(...)` with rate limiting enabled was not rate limited.

### OpenAI's model rules leave `RequestTransformer`

`RequestTransformer.default` now applies only the registry's capabilities. The o-series
constraints (no system message, no native streaming, temperature 1, no sampling penalties) and
the `max_completion_tokens` rule for o-series and gpt-5 moved into `llm4s-openai`, which is the
only client that needs them; the Anthropic and Gemini clients no longer apply them to models
named like OpenAI's.

| Removed | Use instead |
|---|---|
| `RequestTransformer#requiresMaxCompletionTokens`, `TransformationResult.requiresMaxCompletionTokens` | none in core; it is an OpenAI wire parameter |
| `DefaultRequestTransformer` (now package-private) | `RequestTransformer.default(service)` or `withOverrides(...)` |
| (new) | `RequestTransformer.adjusted(service)((modelId, capabilities) => ...)`, for a provider module's own rules on top of the registry |

### Provider-author SPI

The plumbing provider modules build on is a public, frozen SPI; see
[Writing a provider](../guide/writing-a-provider). Two OpenAI-format helpers moved to
`llm4s-openai-compatible`, with unchanged packages: `org.llm4s.llmconnect.model.ResponseFormatMapper`
and `org.llm4s.llmconnect.serialization.{ToolCallDeserializer, StandardToolCallDeserializer}`.
`ProviderResultOps` (`tapRight` / `tapLeft`) is now `private[llm4s]`.

## Pre-baseline API cleanup, pass 2

Not in a release yet; continues pass 1 below.

| Removed | Use instead |
|---|---|
| `ToolRegistry#getToolDefinitionsSafe(provider)` | `getOpenAITools()`. It returned the same JSON for every provider it accepted and failed for the rest; every client takes this format |
| `CancellationToken#cancellationFuture`, `cachedCancellationFuture` | `whenCancelled`, a shared `Future[Unit]` that *succeeds* on cancel: `token.whenCancelled.map(_ => Left(myError))` |
| `CancellationToken#throwIfCancelled()`, `orchestration.CancellationException` | `if (token.isCancelled) Left(...)` |
| `ManagedResource.fileInputStream`, `dataOutputStream`, `byteArrayInputStream` | `ManagedResource.fromTry(() => Try(new ...), s => Try(s.close()))`, or `scala.util.Using` |
| `ManagedResource` `map` / `flatMap` (`ManagedResourceOps`) | none: they never released the underlying resource. Nest `use` calls instead |

Behaviour fix: a `PlanRunner` node cancelled while running now fails the plan with
`OrchestrationError.PlanExecutionError("Node <id> cancelled", ...)`. It used to surface as a
`NodeExecutionError` wrapping `CancellationException`, because the cancellation future only ever
failed and the mapping to the cancelled error never ran.

These utilities moved to the one module that uses them. Package names are unchanged, so code that
already depends on that module needs nothing; code that used them through `llm4s-core` alone adds
the module:

| Utility | Now in |
|---|---|
| `org.llm4s.util.SqlIdentifier`, `org.llm4s.llmconnect.utils.ChunkingUtils` | `llm4s-rag` |
| `org.llm4s.resource.ManagedResource` | `llm4s-speech` |
| `org.llm4s.util.LiftToResult` | `llm4s-observability` |

## Pre-baseline API cleanup, pass 1

Not in a release yet; from the
[spine re-audit](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5935792540). 0.5.0 sets
the binary-compatibility baseline for `llm4s-core` and `llm4s-agent`, so public API that nothing
used, or that was already deprecated, is removed now rather than frozen.

| Removed | Use instead |
|---|---|
| `Result.fromTry(t)` | `t.toResult` (`import org.llm4s.types.TryOps`) or `Safety.fromTry(t)` |
| `error.isRecoverable` | `LLMError.isRecoverable(error)`, or match on `RecoverableError` / `NonRecoverableError` |
| `LLMError.fromThrowable(t)` | `t.toLLMError` (`org.llm4s.error.ThrowableOps.RichThrowable`) |
| `ToolBuilder#build()` | `buildSafe()`, which returns `Result[ToolFunction]` |
| `ToolRegistry#getToolDefinitions(p)` | `getToolDefinitionsSafe(p)` |
| `LLMCompressor.compress(...)`, `LLMCompressedConversation` | `ContextManager.withDefaults(tokenCounter, Some(llmClient))` then `manageContext(conversation, budget)`; no direct equivalent (see below) |
| `Agent` overloads taking `debug`, `tracing` or `traceLogPath` (`run`, `runStep`, `continueConversation`, `runMultiTurn`, `runWithEvents`, `continueConversationWithEvents`, `runCollectingEvents`, `runWithStrategy`, `continueConversationWithStrategy`) | the same method with `context = AgentContext(debug = ..., tracing = ..., traceLogPath = ...)` |
| `ContextConfig(..., enableRollingSummary = ..., ...)`, `ContextConfig.legacy(...)` | drop the argument (nothing read it); `ContextConfig(...)` or `ContextConfig.default.copy(...)` |
| `Safety.sequenceV(xs)` | `xs.traverse(_.toValidatedNec)` with `import cats.syntax.all.*`, which keeps every error; or, given items and a validator, `Result.validateAll(items)(validate)`. Not `Result.sequence`, which stops at the first error |
| `LLMError.llmErrorShow`, `error.show`, `error.display` | `error.formatted` |
| `org.llm4s.types` aliases and wrappers the library never used (`CompletionId`, `ToolName`, `ToolCallId`, `Url`, `MessageId`, `WorkspaceId`, `Json`, `Timeout`, `TokenCount`, the MCP/image/audio/video/plugin/workflow/metrics types, ...) | the underlying type, e.g. `String`, `ujson.Value`, `Long`, `Int` |
| `ConnectionStatus`, `ProviderCapabilities`, `ClientHealth`, `StreamingOptions`, `RuntimeId`, `ModelId` | none; nothing used them |

`LLMCompressor.compress` summarised a whole conversation with one LLM call, optionally with a
custom prompt. Nothing replaces it one-for-one: `LLMCompressor.squeezeDigest` only shrinks messages
already marked `[HISTORY_SUMMARY]` and returns any other conversation unchanged, so do not swap
one call for the other. `ContextManager.manageContext` is the supported path - it builds the
digests, compresses them and trims to the budget (`ContextConfig.enableLLMCompression` gates the
LLM step); the custom prompt has no counterpart.

`RateLimitedLogger`, `ProvidersConfigModel.RawNamedProviderSection` / `RawProvidersConfig`,
`agent.orchestration.MDCContext` and `assistant.ShowInstances` are now package-private.

`org.llm4s.types` keeps `Result`, `AsyncResult`, `TryOps` / `OptionOps` / `FutureOps`, and the
newtypes the library's APIs take: `SessionId`, `TraceId`, `FilePath`, `DirectoryPath`, `AgentId`,
`PlanId` (both removed later with orchestration, #1330), `SemanticBlockId`, `ArtifactKey`, `ExternalizedContent`, `ContentSize`,
`HeadroomPercent` and the `TokenBudget`, `ContextWindowSize`, `ByteCount` and
`ExternalizationThreshold` aliases.

## Slice 7: `llm4s-agent` - the agent runtime leaves core

The second slice 7 carve ([#1242](https://github.com/llm4s/llm4s/issues/1242), decision D4)
moves the agent runtime into a new module, **`llm4s-agent`**. Package names are unchanged, so no
import changes; code that uses the agent adds one dependency:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent" % "<version>"
```

| Moved to `llm4s-agent` | Package |
|---|---|
| `Agent`, `AgentState`, `AgentStatus`, `AgentContext`, `Handoff`, `ContextWindowConfig`, `AgentTraceFormatter` | `org.llm4s.agent` |
| Guardrails - the traits, `CompositeGuardrail`, the built-in validators, the LLM-as-judge and RAG guardrails, PII patterns | `org.llm4s.agent.guardrails.*` |
| DAG orchestration | `org.llm4s.agent.orchestration` |
| Streaming events (`AgentEvent` and its cases) | `org.llm4s.agent.streaming` |
| The console assistant: `AssistantAgent`, `SessionManager`, `ConsoleInterface` (Beta) | `org.llm4s.assistant` |

**Not moved:** agent memory (`org.llm4s.agent.memory`) is already `llm4s-memory`, which does not
depend on `llm4s-agent`. `UsageSummary` and `ModelUsage` moved to `org.llm4s.llmconnect.model` in
`llm4s-core` just before this (see below). The ready-made tools are `llm4s-agent-tools`.

**What stays in `llm4s-core`** is what the agent is built on: `LLMClient` and `LLMConnect`, the
tool API (`ToolFunction`, `ToolRegistry`), and the tracing contract. An agent run is traced through
`TraceEvent.AgentStateUpdated`, built by `AgentState#toTraceEvent`, so tracing backends need
nothing from the agent module.

Other modules: `llm4s-workspace-client` now depends on `llm4s-agent`, for its `codegen` package.
`fansi` leaves `llm4s-core` with the assistant, its only user.

## Slice 7: `llm4s-agent-tools` - the built-in tools leave core

The first slice 7 carve ([#1242](https://github.com/llm4s/llm4s/issues/1242), decisions D2 and D3)
moves the ready-made tools into a new module, **`llm4s-agent-tools`**. Package names are unchanged,
so no import changes; code that uses any of them adds one dependency:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent-tools" % "<version>"
```

| Moved to `llm4s-agent-tools` | Package |
|---|---|
| `BuiltinTools` and every tool under it: `core` (DateTime, Calculator, UUID, JSON), `filesystem`, `http`, `shell`, `search` (Brave, DuckDuckGo, Exa) | `org.llm4s.toolapi.builtin.*` |
| `WeatherTool` | `org.llm4s.toolapi.tools` |
| `ToolsConfigLoader` (was `private[config]`, now public), `BraveSearchToolConfig`, `DuckDuckGoSearchToolConfig`, `ExaSearchToolConfig` | `org.llm4s.config` |
| `ToolsConfigKeys` (new) | `org.llm4s.config` |

**What stays in `llm4s-core`** is the tool API: `ToolFunction`, `ToolRegistry`, `ToolBuilder`,
`Schema`, the execution strategies and `SafeParameterExtractor`. Your own tools need nothing new.
`llm4s-agent-tools` depends on `llm4s-core` only - not on the agent runtime - so the tools work
with plain tool calling through `ToolRegistry` as well as with `Agent`.

### Configuration is unchanged

The `llm4s.tools.brave`, `.duckduckgo` and `.exa` keys, their defaults, and the
`BRAVE_SEARCH_API_KEY`, `BRAVE_SEARCH_COUNT`, `BRAVE_SEARCH_API_URL`, `BRAVE_SAFE_SEARCH`,
`DUCK_DUCK_GO_SEARCH_API_URL` and `EXA_*` variables are the same. The block moved from core's
`reference.conf` to the module's, beside the code that reads it.

### Source breaks

1. **The tools need `llm4s-agent-tools`** (table above).
2. **`Llm4sConfig.loadBraveSearchTool()`, `loadDuckDuckGoSearchTool()` and `loadExaSearchTool()`
   are removed.** They returned config types that left core. The same methods are on
   `ToolsConfigLoader`:

   ```scala
   import org.llm4s.config.ToolsConfigLoader

   // before
   val braveConfig = Llm4sConfig.loadBraveSearchTool()

   // after
   val braveConfig = ToolsConfigLoader.loadBraveSearchTool()
   ```

   Each also takes a `ConfigSource`, to read from one of your own.
3. **`ConfigKeys.BRAVE_SEARCH_API_KEY` is `ToolsConfigKeys.BRAVE_SEARCH_API_KEY`**, with
   `ToolsConfigKeys.EXA_API_KEY` beside it.

## Slice 7: `UsageSummary` and `ModelUsage` move to `org.llm4s.llmconnect.model`

Not in a release yet; the preparation step of slice 7
([#1242](https://github.com/llm4s/llm4s/issues/1242), decision D1).

Slice 7 carves the agent runtime out of `llm4s-core` into `llm4s-agent`. `UsageSummary` and
`ModelUsage` were in `org.llm4s.agent`, but they depend only on `TokenUsage`, and
`llm4s-observability`'s `CostTracker` builds them too. Leaving them in the agent package would make
`llm4s-observability` depend on the agent runtime; moving the file to core while keeping its
package would split `org.llm4s.agent` across two jars. So they move to `org.llm4s.llmconnect.model`,
beside `TokenUsage`, and stay in `llm4s-core`.

This is the one change to an import in the slice; every other move keeps its package.

```scala
// before
import org.llm4s.agent.{ ModelUsage, UsageSummary }

// after
import org.llm4s.llmconnect.model.{ ModelUsage, UsageSummary }
```

Code that only reads `AgentState.usageSummary` or `CostTracker.snapshot` needs no change. The JSON
form is unchanged, so an `AgentState` saved before the move still loads.

## llm4s no longer brings a logging backend

Not in a release yet; found by the slice 6 spine audit
([#1133](https://github.com/llm4s/llm4s/issues/1133#issuecomment-5878475544)).

Every llm4s module used to declare `logback-classic` and `log4j-to-slf4j` as compile
dependencies, so they reached your classpath with llm4s. Libraries should leave that choice to the
application, and the published modules now declare only `slf4j-api`.

- **If you already configure logging** (your own logback, log4j2 via `log4j-slf4j2-impl`, or
  another SLF4J 2 backend), nothing changes - except that llm4s no longer adds a second backend
  or a bridge that clashes with `log4j-core`.
- **If you relied on the logback llm4s brought**, declare it yourself; without a backend, SLF4J
  logs nothing and prints one "no SLF4J providers were found" warning:

  ```scala
  libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.5.34"
  ```

- **If you used Monocle, commons-io or fansi through llm4s**, declare them yourself: Monocle and
  commons-io are no longer dependencies of any llm4s module, and fansi comes only with
  `llm4s-core`.

## A failed read no longer deletes or clears indexed documents

Follow-up to [#1236](https://github.com/llm4s/llm4s/pull/1236); not in a release yet.

`LoadResult.Failure` gains a fourth field, `documentId: Option[String] = None`: the id the
failed document is (or would be) indexed under. `source` stays the path, key or URL. `RAG.sync`
uses it to tell a document it could not read from one that has gone from the source.

- **A pattern match on `LoadResult.Failure` needs a fourth argument**:
  `case LoadResult.Failure(source, error, recoverable)` becomes
  `case LoadResult.Failure(source, error, recoverable, documentId)` (or `_`). Constructor calls,
  `LoadResult.failure(source, error)`, `.source`, `.error` and `.recoverable` are unchanged;
  `LoadResult.failure(source, error, documentId = id)` is new.
- **`sync` keeps a document whose read fails.** A `Failure` with a `documentId` keeps that
  document's indexed version: it is not deleted, and not counted in `SyncStats`. Previously
  sync deleted it, because it had not been "seen". `SourceBackedLoader` (S3 and every other
  `DocumentSource`) and `UrlLoader` name the document on every per-document failure,
  `FileLoader` when extraction fails. Read failures are logged at WARN.
- **A `Failure` with no `documentId` makes `sync` skip its deletion pass** for that run, as it
  cannot tell which unlisted document failed. Adds and updates still apply. `WebCrawlerLoader`
  leaves it out on purpose (the pages below a failed page went uncrawled), as does `FileLoader`
  for a missing path. **A custom `DocumentLoader` should set `documentId`** on per-document
  failures when it knows the id, or its failures will now hold back deletions.
- **`refresh` reads the whole loader before it clears anything.** A `ListingFailure` - at any
  point, including a later S3 page - or, under `failFast`, any `Failure` returns the error and
  leaves the index and registry untouched. Previously the index was cleared first and left
  empty or half-rebuilt. The cost is memory: `refresh` and `refreshAsync` now hold every loaded
  document's extracted text in memory before clearing; use `sync`, which streams, for a
  source too large for that. Without `failFast`, a document that fails to read is still left
  out of the rebuilt index.
- `DocumentLoaders.successesOnly` still drops per-document failures, so a sync through it
  still deletes a document whose read failed.

## Slice 6: `llm4s-observability-prometheus` - Prometheus leaves core

The second slice 6 carve ([#1133](https://github.com/llm4s/llm4s/issues/1133), decisions D3 and
D4) moves the Prometheus metrics backend into a new module, **`llm4s-observability-prometheus`**,
which carries the Prometheus client and HTTP server. With it gone, `llm4s-core` declares no
observability dependency. Package names are unchanged, so no import changes:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability-prometheus" % "<version>"
```

| Moved to `llm4s-observability-prometheus` | Package |
|---|---|
| `PrometheusMetrics`, `PrometheusEndpoint` | `org.llm4s.metrics` |
| `MetricsConfigLoader` (was `private[config]`, now public) | `org.llm4s.config` |

**What stays in `llm4s-core`** is the metrics contract: `MetricsCollector` (with `noop` and
`compose`), `Outcome` and `ErrorKind`. Every provider client, `ReliableClient` and the metrics and
rate-limiting middleware take a `MetricsCollector`, so none of them needs the new module; only the
code that builds a `PrometheusMetrics` does. It is kept apart from `llm4s-observability` so that
Prometheus does not reach every `llm4s-rag` user through that module.

### Configuration is unchanged

The keys and their defaults are the same - `llm4s.metrics.enabled` (`false`),
`llm4s.metrics.prometheus.enabled` (`true`) and `llm4s.metrics.prometheus.port` (`9090`). They used
to be hard-coded in `MetricsConfigLoader`, because core's `reference.conf` had no `llm4s.metrics`
block; that block now ships in the module's `reference.conf`, beside the code that reads it.

### Source break: `Llm4sConfig.metrics()` is removed

It returned a `PrometheusEndpoint`, so it could not stay in a core without Prometheus (D3).
`MetricsConfigLoader` replaces it, with the same result type:

```scala
import org.llm4s.config.MetricsConfigLoader

// before
val (metrics, endpoint) = Llm4sConfig.metrics().toOption.get

// after
val (metrics, endpoint) = MetricsConfigLoader.default().toOption.get
```

`MetricsConfigLoader.load(source)` reads from a `ConfigSource` of your own. Its `source` parameter
no longer defaults to `ConfigSource.default`; call `default()` for that.

## A failed listing fails the sync

[#1231](https://github.com/llm4s/llm4s/pull/1231); not in a release yet.

A document loader that cannot enumerate its documents - an S3 listing with no credentials, a
missing bucket or access denied, a `DirectoryLoader` path that does not exist - now yields the
new `LoadResult.ListingFailure(source, error)` instead of a `LoadResult.Failure`. `RAG.sync`,
`ingest` and `refresh`, and their async forms, return its error as the `Left`. Previously
`sync` returned `Right` with 0 documents and then deleted every document it had indexed from
that source, because it had seen none of them.

- **Code that matches on `LoadResult` needs a `ListingFailure` case**; an exhaustive match
  without one warns, and fails the build under `-Werror`.
- **`RAG.ingest` no longer counts a listing error as one failed document** in `LoadStats`: it
  returns it as the `Left`, whether or not `failFast` is set. Code that read
  `stats.errors` for `"list-error"` should handle the `Left` instead.
- **`SourceBackedLoader` names the source**, not `"list-error"`: the `ListingFailure`'s
  `source` is the `DocumentSource`'s `description`, e.g. `S3(s3://bucket/prefix)`.
- A `sync` that fails part-way through a listing (a later S3 page) keeps the documents it
  synced before the failure and deletes nothing; re-running it once the source is reachable
  completes the sync. `refresh` cleared the index before reading the loader, so a listing
  failure left it empty - but said so; it now leaves the index untouched (see
  [above](#a-failed-read-no-longer-deletes-or-clears-indexed-documents)).

A `DocumentSource` reports a listing error by yielding a `Left` from `listDocuments()`, as
before; `SourceBackedLoader` does the rest. A custom `DocumentLoader` whose enumeration fails
should return `LoadResult.listingFailure(source, error)`.

## Vendor credentials: a shared API key per provider

[#1132](https://github.com/llm4s/llm4s/issues/1132), part of the modularisation programme
([#1126](https://github.com/llm4s/llm4s/issues/1126)); not in a release yet. `0.4.1` and earlier
behave as before.

**Credentials belong to a vendor, keyed by provider id; clients belong to a use.** Each provider
module now binds its vendor's conventional API-key variable to a shared key,
`llm4s.credentials.<provider>.apiKey`, in its own `reference.conf`. A client - a chat section, an
embeddings block, the Cohere reranker - that sets no `apiKey` of its own uses it. So with
`OPENAI_API_KEY` set, a chat section needs only `provider` and `model`, and OpenAI embeddings need
no extra line.

This is additive: configs that set `apiKey` keep working unchanged, because a client's own key
always wins. Configs that failed for want of an `apiKey` line now load.

### Resolution order

For a client of provider *P*:

1. its own `apiKey`: `llm4s.providers.<name>.apiKey`, `llm4s.embeddings.<P>.apiKey`, or
   `llm4s.rerank.cohere.apiKey`;
2. otherwise `llm4s.credentials.<P>.apiKey`, where *P* is the canonical id - a
   `provider = "google"` section uses `llm4s.credentials.gemini`;
3. otherwise a `ConfigurationError` naming both places:
   `apiKey: set OPENAI_API_KEY, or set apiKey under llm4s.providers.openai-main in application.conf`.

The credentials block holds `apiKey` only: `baseUrl`, `endpoint`, `model` and `apiVersion` are
never defaulted from it. Where each key came from is logged at INFO - for example
`llm4s.providers.openai-main: API key from llm4s.credentials.openai.apiKey` - and the value never
is.

```hocon
# before
openai-main {
  provider = "openai"
  model    = "gpt-4o-mini"
  apiKey   = ${?OPENAI_API_KEY}
}
llm4s.embeddings.openai.apiKey = ${?OPENAI_API_KEY}

# after - with OPENAI_API_KEY exported
openai-main {
  provider = "openai"
  model    = "gpt-4o-mini"
}
```

The old lines still work and may stay. A section for a **second account** keeps its own key:
`apiKey = ${?OPENAI_BATCH_API_KEY}`. If that variable is unset, the section now falls back to the
shared key instead of failing - see [Explicit keys in production](#explicit-keys-in-production).

### Variables bound

| Provider id | Variable | Module |
|---|---|---|
| `openai` | `OPENAI_API_KEY` | `llm4s-openai` |
| `azure` | `AZURE_OPENAI_API_KEY` | `llm4s-openai` |
| `requesty` | `REQUESTY_API_KEY` | `llm4s-openai` |
| `anthropic` | `ANTHROPIC_API_KEY` | `llm4s-anthropic` |
| `gemini` | `GOOGLE_API_KEY`, else `GEMINI_API_KEY` (Google's SDK precedence) | `llm4s-gemini` |
| `deepseek`, `zai`, `openrouter`, `mistral` | `DEEPSEEK_API_KEY`, `ZAI_API_KEY`, `OPENROUTER_API_KEY`, `MISTRAL_API_KEY` | `llm4s-openai-compatible` |
| `cohere` | `COHERE_API_KEY` | `llm4s-openai-compatible` and `llm4s-rag` |
| `voyage` | `VOYAGE_API_KEY` | `llm4s-voyage` |

None for `openai-compatible` (it has no vendor), `ollama` (no key) or `vertexai` (OAuth2:
Application Default Credentials, or a service-account file named by `apiKey`).

### Moved bindings

- **Voyage**: `llm4s.embeddings.voyage.apiKey = ${?VOYAGE_API_KEY}` became
  `llm4s.credentials.voyage.apiKey = ${?VOYAGE_API_KEY}`. `VOYAGE_API_KEY` works as before, and an
  explicit `llm4s.embeddings.voyage.apiKey` still wins.
- **Cohere reranker**: `llm4s.rerank.cohere.apiKey = ${?COHERE_API_KEY}` became
  `llm4s.credentials.cohere.apiKey = ${?COHERE_API_KEY}`, shared with the Cohere chat provider, so
  one `COHERE_API_KEY` serves both. An explicit `llm4s.rerank.cohere.apiKey` still wins.
- **Azure**: if you exported `AZURE_API_KEY` for a section without its own `apiKey`, rename it
  to `AZURE_OPENAI_API_KEY` - the openai SDK's name, and the one now bound - or keep an
  `apiKey = ${?AZURE_API_KEY}` line in the section.

### New: `RerankerConfigLoader`

`llm4s-rag`'s `reference.conf` has bound `llm4s.rerank` since #337, but no code read it.
`org.llm4s.config.RerankerConfigLoader.load(source)` / `.default()` now does, returning
`Result[Option[RerankProviderConfig]]` for `RAG.build(..., resolveRerankerConfig = ...)` or
`RerankerFactory.fromConfig`.

### Explicit keys in production

`llm4s-config-policy`'s `prod` preset (`ConfigPolicy.prodSafeDefaults`) gains the rule
`ownApiKey` (`ConfigPolicy.withOwnApiKeyRequired`, checked by
`ConfigPolicyEngine.checkApiKeySources` and run by `CheckPolicies`): every chat section whose
provider requires a key must set its own `apiKey`, so a section meant for a second account cannot
silently bill the default one. A `prod` check of a config that relies on the shared key now fails;
add `apiKey = ${?VAR}` to each section. `Llm4sConfig.apiKeySources()` / `apiKeySourcesFrom(source)`
report each section's `ApiKeySource` (`Section(path)` or `Credentials(path)`) for checks of your own.

### Source breaks

These are deliberate removals ahead of the MiMa baseline:

1. **`EmbeddingConfigSpec.apiKeyPath` is removed**, with the loader code that resolved it. The
   shared `llm4s.credentials.<id>.apiKey` replaces it for every provider: bind your vendor's
   variable there in your module's `reference.conf` instead of declaring a path.
2. **`EmbeddingConfigSpec.apiKeyEnv` is a `Seq[String]`**, not an `Option[String]`:
   `apiKeyEnv = Some("X")` becomes `apiKeyEnv = Seq("X")`. It lists the variables your module
   binds to `llm4s.credentials.<id>.apiKey`, highest precedence first, and is used only to word
   the missing-key error.
3. **`ProviderConfigSpec` gained `apiKeyEnv: Seq[String]`** (last, with a default), and
   `ProviderConfigSpec.apiKeyAndDefaultBaseUrl` an optional second parameter for it. Positional
   construction still compiles; a pattern match on `ProviderConfigSpec` needs one more binder.
4. **`OpenAIConfigKeys.AZURE_API_KEY` is now `AZURE_OPENAI_API_KEY`**, with the value
   `"AZURE_OPENAI_API_KEY"`.
5. **The missing-`apiKey` messages changed.** Chat: `apiKey: set <VAR>, or set apiKey under
   llm4s.providers.<name> in application.conf`. Embeddings: `Missing <id> embeddings apiKey: set
   <VAR>, or set apiKey under llm4s.embeddings.<id> in application.conf`. Code matching the old
   text needs updating.

For provider authors: bind `llm4s.credentials.<id>.apiKey = ${?<VENDOR>_API_KEY}` in your module's
`reference.conf`, using the name the vendor's own SDK or docs use, and declare the same variable
as `apiKeyEnv` on your `ProviderConfigSpec` or `EmbeddingConfigSpec`. Your module's round-trip spec
should prove the two agree.

## Slice 6: `llm4s-observability` - Langfuse, the trace collector and `CostTracker` leave core

The slice 6 carve ([#1133](https://github.com/llm4s/llm4s/issues/1133),
[#1126](https://github.com/llm4s/llm4s/issues/1126)) moves the tracing integrations that need
nothing beyond core into a new module, **`llm4s-observability`**. Package names are unchanged, so
no import changes; code that uses any of the types below adds one dependency:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-observability" % "<version>"
```

| Moved to `llm4s-observability` | Package |
|---|---|
| `LangfuseTracing`, `LangfuseBatchSender`, `DefaultLangfuseBatchSender`, `LangfuseHttpApiCaller`, `LangfuseTracingBackend` | `org.llm4s.trace` |
| `TraceCollectorTracing` and the rest of `TraceCollector.scala` | `org.llm4s.trace` |
| `Trace`, `Span`, `SpanId`, `SpanKind`, `SpanStatus`, `SpanEvent`, `SpanValue`, `TraceModelJson` | `org.llm4s.trace.model` |
| `TraceStore`, `InMemoryTraceStore`, `TraceQuery` | `org.llm4s.trace.store` |
| `CostTracker` | `org.llm4s.metrics` |
| `LangfuseConfig` (was in core) | `org.llm4s.llmconnect.config` |
| `LangfuseConfigKeys`, `LangfuseConfigLoader` (new) | `org.llm4s.config` |

`OpenTelemetryConfig` moves, in the same package, to `llm4s-observability-otel`, beside the
backend that reads it.

**What stays in `llm4s-core`** is the tracing contract (decisions D1 to D5 in #1133): `Tracing`,
`TraceEvent`, `TracingComposer`, `TracingMode`, the `TracingBackend` SPI, `NoOpTracing`,
`ConsoleTracing` (the default mode, so a core-only application still traces), `TracingSettings`,
and `MetricsCollector`. `llmconnect`, the agent runtime and every provider module use only these,
so none of them needs the new module. `llm4s-rag` depends on it, for `RAGASLangfuseObserver`; it
carries no third-party dependency, so this adds nothing else to a RAG user's classpath.

### Configuration is unchanged

The keys and variables are the same: `TRACING_MODE=langfuse`, `LANGFUSE_PUBLIC_KEY`,
`LANGFUSE_SECRET_KEY`, `LANGFUSE_URL`, `LANGFUSE_ENV`, `LANGFUSE_RELEASE`, `LANGFUSE_VERSION`
under `llm4s.tracing.langfuse.*`, and `TRACING_MODE=opentelemetry` (or `otel`),
`OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT` and `llm4s.tracing.opentelemetry.headers`.
Only the module that binds them changed: the `llm4s.tracing.langfuse` block moved from core's
`reference.conf` to `llm4s-observability`'s, and `llm4s.tracing.opentelemetry` to
`llm4s-observability-otel`'s, each with the code that reads it. Core now reads only
`llm4s.tracing.mode`, and hands the selected mode's block to its backend as
`TracingSettings.extras`.

So an application that sets `TRACING_MODE=langfuse` adds `llm4s-observability` and changes no
config. Without it, `Tracing.create` logs an error naming the artifact and traces nothing -
exactly what `TRACING_MODE=opentelemetry` without `llm4s-observability-otel` already did:

```text
Tracing mode 'langfuse' is configured but no TracingBackend for it is on the classpath.
Add the 'org.llm4s' %% 'llm4s-observability' dependency. Available modes: console, noop.
```

### Behaviour change: Langfuse without keys does not start

`TRACING_MODE=langfuse` without `LANGFUSE_PUBLIC_KEY` or `LANGFUSE_SECRET_KEY` used to build a
`LangfuseTracing` that logged a warning and dropped every batch. The backend now refuses to start:
`Tracing.fromSettings` returns

```text
Langfuse tracing is selected but llm4s.tracing.langfuse.publicKey (LANGFUSE_PUBLIC_KEY) and
llm4s.tracing.langfuse.secretKey (LANGFUSE_SECRET_KEY) are not set.
```

and `Tracing.create` logs that once and returns `NoOpTracing`. Either way nothing reaches
Langfuse, as before; the difference is one clear error instead of a warning per batch.
`LangfuseTracing.from(config)` built directly is unchanged.

### Behaviour change: a selected block that is not an object is an error

`TracingSettings.extras` is the selected mode's block, `llm4s.tracing.<mode>`. When that key was
present but was not an object - `llm4s.tracing { mode = opentelemetry, opentelemetry =
"http://collector:4317" }` - it was treated as absent, so the backend started on its defaults
(here, a collector on localhost) and the operator's setting was silently ignored. Read failures
were swallowed the same way. `Llm4sConfig.tracing()` now returns a `ConfigurationError` naming
the path instead:

```text
llm4s.tracing.opentelemetry must be an object, but is a string. It holds the settings for
tracing mode 'opentelemetry': write it as llm4s.tracing.opentelemetry { ... }.
```

A block that is absent (or `null`) is still an empty `extras`, and the backend applies its
defaults. Only the selected mode's block is checked: a malformed block for a mode that is not
selected is still ignored.

### Reading Langfuse settings yourself

`TracingSettings` no longer has a `langfuse` field, and `extras` holds only the *selected* mode's
block. To read `llm4s.tracing.langfuse` whatever `TRACING_MODE` is - to combine Langfuse with
Console, or for `RAGASLangfuseObserver` - use the new loader:

```scala
import org.llm4s.config.LangfuseConfigLoader

// before
Llm4sConfig.tracing().map(settings => LangfuseTracing.from(settings.langfuse))
RAGASLangfuseObserver.fromTracingSettings(settings)

// after
LangfuseConfigLoader.default().map(LangfuseTracing.from)
LangfuseConfigLoader.default().map(RAGASLangfuseObserver.from)
```

### Source breaks

These are taken before 0.5.0 sets the MiMa baseline, and none of them changes a package name.

1. **Langfuse, the collector, its model and store, and `CostTracker` need `llm4s-observability`**
   (table above).
2. **`TracingMode.Langfuse` and `TracingMode.OpenTelemetry` are removed.** Core keeps a case only
   for what it builds itself, `Console` and `NoOp`; the others are `TracingMode.Named("langfuse")`
   and `TracingMode.Named("opentelemetry")`, which `TracingMode.fromString` returns for
   `"langfuse"` and for `"opentelemetry"` / `"otel"`. `LangfuseConfig.Mode` and
   `OpenTelemetryConfig.Mode` name them. A `match` on the old case objects matches on
   `TracingMode.Named("langfuse")` instead.
3. **`TracingSettings` is `TracingSettings(mode, extras)`.** The `langfuse: LangfuseConfig` and
   `openTelemetry: OpenTelemetryConfig` fields are removed. A backend reads its block from
   `extras` - `LangfuseConfig.fromExtras(settings.extras)`,
   `OpenTelemetryConfig.fromExtras(settings.extras)` - and code that built settings by hand passes
   `extras = Map("publicKey" -> ..., "secretKey" -> ...)` for Langfuse, or
   `Map("serviceName" -> ..., "endpoint" -> ..., "headers.Authorization" -> ...)` for OpenTelemetry.
4. **`LangfuseConfig` moves to `llm4s-observability`**, and **`OpenTelemetryConfig` to
   `llm4s-observability-otel`**, in the same package.
5. **`DefaultConfig` is removed.** Its last four constants moved to `LangfuseConfig`:
   `DEFAULT_LANGFUSE_URL`, `_ENV`, `_RELEASE` and `_VERSION` are `LangfuseConfig.DEFAULT_URL`,
   `DEFAULT_ENV`, `DEFAULT_RELEASE` and `DEFAULT_VERSION`.
6. **`ConfigKeys.LANGFUSE_*` are `LangfuseConfigKeys.LANGFUSE_*`** in `llm4s-observability`.
7. **`RAGASLangfuseObserver.fromTracingSettings(TracingSettings)` is removed**: it read the removed
   `TracingSettings.langfuse`. Use `RAGASLangfuseObserver.from(config)` with
   `LangfuseConfigLoader.default()`, as above.
8. **`TraceEvent.createTraceEvent` and `org.llm4s.llmconnect.model.TraceHelper` are removed.**
   Both built Langfuse ingestion JSON - a `"trace-create"` batch envelope, and
   `event-create` / `generation-create` / `span-create` envelopes per conversation message - and
   nothing in llm4s called either; `LangfuseTracing` builds its own batches. They were
   Langfuse's wire format sitting in the core contract, so they are deleted rather than moved. Code
   that called them builds the JSON itself, or traces through `LangfuseTracing` (`llm4s-observability`),
   which sends the same event types.

## Slice 6: tracing backends are discovered, and agent state is a `TraceEvent`

The first slice 6 change ([#1133](https://github.com/llm4s/llm4s/issues/1133)) lands the extension
point for tracing before Langfuse and Prometheus are carved out of `llm4s-core`, as slice 4 did for
providers. Nothing moves module yet, and nothing in a release has it; `0.4.1` and earlier behave as
before.

### `Tracing.traceAgentState(AgentState)` is removed

`Tracing` no longer mentions `AgentState`, so the tracing contract does not depend on the agent
runtime. Agent state is traced as an ordinary event, `TraceEvent.AgentStateUpdated`, which
`AgentState#toTraceEvent` builds:

```scala
// before
tracing.traceAgentState(state)

// after
tracing.traceEvent(state.toTraceEvent)
```

`AgentStateUpdated` gained a `messages: Seq[Message]` field (default empty) before `timestamp`,
carrying the conversation, so that Langfuse still records a trace with one span per message. It is
not part of `toJson`. The agent emits the event exactly where it called `traceAgentState`.

A custom `Tracing` implementation drops its `traceAgentState` override; anything it did there
belongs in `traceEvent`'s `AgentStateUpdated` case.

### `Tracing.create` finds backends on the classpath

`Tracing.create(settings)` keeps its signature. It builds `NoOp` and `Console` itself and hands
every other mode to an `org.llm4s.trace.spi.TracingBackend` registered in
`META-INF/services/org.llm4s.trace.spi.TracingBackend`:

- **OpenTelemetry**: `llm4s-observability-otel` registers `OpenTelemetryTracingBackend`. The
  `Class.forName` reflection core used to find it is gone. Adding the dependency is all it takes,
  as before.
- **Langfuse**: registered by core's own services entry until it is carved into
  `llm4s-observability` - which has since happened; see
  [the carve's note](#slice-6-llm4s-observability---langfuse-the-trace-collector-and-costtracker-leave-core).
- **Anything else**: implement `TracingBackend` (a `class` with a public no-arg constructor, not an
  `object`) with `mode = TracingMode.Named("yourmode")`, declare it in the services file, and
  `TRACING_MODE=yourmode` selects it.

`Tracing.create` still never fails: a missing or broken backend logs an error and gives
`NoOpTracing`. The new **`Tracing.fromSettings(settings): Result[Tracing]`** returns that as a
`ConfigurationError` (or the backend's own error) instead, and
`Tracing.fromSettings(settings, TracingBackends.of(...))` registers a backend explicitly, for a
shaded jar whose services files did not survive.

### `TracingMode` is open

`TracingMode` gained a `Named(name)` case and a `name` member. `TracingMode.fromString` returns
`Named` for a value it does not recognise, lower-cased, where it used to return `NoOp` with a
warning; a blank value is still `NoOp`. The end result of an unknown `TRACING_MODE` is unchanged -
`Tracing.create` gives `NoOpTracing` - but the log line is now an error that lists the modes that
are available.

### Behaviour changes

1. **An `AgentStateUpdated` with no messages is exported to Langfuse** as a summary trace. The old
   `traceAgentState` sent nothing for an empty conversation.
2. **Langfuse reports a failed export of the conversation trace** as a `Left`; `traceAgentState`
   always returned `Right(())`. The agent swallows tracing errors either way.
3. **The agent state span takes the event's name.** Agent runs used to reach a tracer through
   `traceAgentState`, whose span name differed from the one `traceEvent` gave the same event; they
   now go through `traceEvent`, so the attributes are unchanged but the name is not. Update any
   dashboard or query keyed on the old name:

   | Backend | Old name (agent runs) | New name |
   |---|---|---|
   | OpenTelemetry | `Agent State Snapshot` | `Agent State Updated` |
   | `TraceCollectorTracing` | `agent-state-update` | `agent_state_updated` |
4. **An OpenTelemetry SDK that fails to start** is reported by `Tracing.fromSettings` and gives
   `NoOpTracing` from `Tracing.create`, rather than a tracer whose every call failed.

### Source breaks

1. **`Tracing.traceAgentState` is removed** - use `traceEvent(state.toTraceEvent)`.
2. **`TraceEvent.AgentStateUpdated` has a fifth field**, `messages`, before `timestamp`. A
   positional `AgentStateUpdated(status, messages, logs, timestamp)` must name the timestamp
   (`timestamp = ...`), and a pattern `AgentStateUpdated(a, b, c, ts)` needs one more binder.
3. **`TracingMode` has a new case**, `Named`, so an exhaustive `match` over it needs one more
   branch, and **a new abstract member, `name`**, for anything extending the sealed trait (nothing
   outside core can).
4. **`TracingMode.fromString` returns `Named(...)`, not `NoOp`, for an unrecognised value.**

## From `LLM_MODEL` to named provider sections

> **API keys: superseded** by [Vendor credentials](#vendor-credentials-a-shared-api-key-per-provider).
> Provider modules now bind each vendor's API-key variable (`OPENAI_API_KEY`, ...), so the
> `apiKey = ${?OPENAI_API_KEY}` lines below, and the embeddings binding, are optional. What this
> note says about `LLM_MODEL` and base-URL variables still holds.

Since [#903](https://github.com/llm4s/llm4s/pull/903) (in 0.3.2) removed legacy single-provider
loading, **nothing in llm4s reads `LLM_MODEL`**, nor a provider's API-key or base-URL variable
(`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GOOGLE_API_KEY`, `AZURE_API_BASE`, `OLLAMA_BASE_URL`, ...).
No `reference.conf` binds them. Setting them has no effect, and an application that relied on them
fails at startup with a configuration error. The documentation kept teaching them until
[#1132](https://github.com/llm4s/llm4s/issues/1132)'s follow-up.

Configure providers as named sections in your own `application.conf`, binding secrets from the
environment with `${?VAR}` - you choose the variable names:

```hocon
# src/main/resources/application.conf
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
      apiKey   = ${?OPENAI_API_KEY}
    }
  }
}
```

| Before | After |
|---|---|
| `LLM_MODEL=openai/gpt-4o-mini` | a section with `provider = "openai"`, `model = "gpt-4o-mini"`, selected by `llm4s.providers.provider` |
| `OPENAI_API_KEY=...` read automatically | `apiKey = ${?OPENAI_API_KEY}` in that section, and the same variable exported |
| `OPENAI_BASE_URL`, `OLLAMA_BASE_URL`, ... | `baseUrl = "..."` (or `baseUrl = ${?OLLAMA_BASE_URL}`) in the section; Ollama sections require it |
| switching model by changing `LLM_MODEL` | a second section, selected by changing `provider`, by `-Dllm4s.providers.provider=<name>`, by your own `provider = ${?LLM4S_PROVIDER}` binding, or loaded directly with `Llm4sConfig.provider("<name>")` |
| `Llm4sConfig.provider()` | `Llm4sConfig.defaultProvider()` |

Two things to know:

- **Only the section you load is validated** (since
  [#1132](https://github.com/llm4s/llm4s/issues/1132)'s follow-up; not in a release yet). A
  section whose required `apiKey` is unset, or whose provider module is not on the classpath,
  fails `provider("<that section>")` - and `defaultProvider()` when it is the default - but not
  a load of any other section. Up to 0.4.1 every section was validated on every load, so each
  environment had to fill in every section or ship a config without it. `Llm4sConfig.providers()`,
  which returns every section, still validates them all.
- **OpenAI embeddings do not see `OPENAI_API_KEY` either.** With
  `EMBEDDING_MODEL=openai/<model>` (which *is* bound, by llm4s-core's `reference.conf`), add
  `llm4s.embeddings.openai.apiKey = ${?OPENAI_API_KEY}`. Since #1132's follow-up the key no longer
  falls back to `llm4s.openai.apiKey`, the pre-#903 chat key that nothing else read: if you set
  that, set `llm4s.embeddings.openai.apiKey` instead.

Variables that `reference.conf` files do bind - `TRACING_MODE`, `LANGFUSE_*`, `OTEL_SERVICE_NAME`,
`OTEL_EXPORTER_OTLP_ENDPOINT`, `EMBEDDING_MODEL`, `VOYAGE_API_KEY` and others - still work; see
[the variables llm4s reads](../getting-started/configuration#environment-variables-llm4s-reads).
Two tools read `LLM_MODEL` themselves: the chat-tui sample (`ChatTuiConfig`) and the config-policy
env check (`EnvCheckPolicies`).

## Named providers: provider-specific keys; Vertex AI `project` and `location`

A named provider section can now carry keys of the provider's own, declared by the provider
rather than squeezed into the shared fields ([#1215](https://github.com/llm4s/llm4s/issues/1215)).
Not in a release yet.

### Vertex AI: `endpoint` becomes `project`, `organization` becomes `location`

Vertex AI used to read its GCP project from `endpoint` and its region from `organization`. It
now has keys named for what they are:

```hocon
# before
vertex-main {
  provider     = "vertexai"
  model        = "gemini-2.0-flash"
  endpoint     = "my-gcp-project"
  organization = "europe-west4"
}

# after
vertex-main {
  provider = "vertexai"
  model    = "gemini-2.0-flash"
  project  = "my-gcp-project"
  location = "europe-west4"      # optional, default us-central1
}
```

The old spellings still work for now, as deprecated aliases: each one logs a warning naming the
section and the key to rename it to, and will stop working in a later release. Setting both
`project` and a *different* `endpoint` (or `location` and a different `organization`) is an
error rather than a guess. A missing project is now reported as
`- project: the GCP project ID that owns your Vertex AI resources (set it in application.conf under llm4s.providers.<name>.project)`.

### Unknown keys are reported

A key in a section that is neither a built-in field nor one the provider declares used to be
ignored silently. It is still ignored - existing configs keep loading - but now with a warning
that names it and lists the keys the provider accepts, which is how a typo like `regoin` shows
up. An extra key whose value is an object or list is an error, since none takes one.

### The missing-`baseUrl` hint no longer suggests a variable nothing reads

A section missing a required `baseUrl` used to say `set <PROVIDER>_BASE_URL`, for example
`set OLLAMA_BASE_URL`, though nothing read such a variable for a named section. It now says
where to set the key - `baseUrl: set it in application.conf under llm4s.providers.<name>.baseUrl (e.g. ...)`.
Where the provider has a conventional variable (`ProviderConfigSpec.baseUrlEnv`), the message
shows the binding that makes it read, since a named section reads none by itself:
`openai-compatible` adds `to read it from OPENAI_COMPATIBLE_BASE_URL, add baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL} to the section`.
Code matching on the old message text needs updating.

### Source breaks

- `RawNamedProviderSection` and `NamedProviderConfig` gained a trailing `extras` parameter with
  a default, so construction by name or position still compiles; pattern matches that list
  every field (`case NamedProviderConfig(a, b, ...)`) need one more.
- `ProviderConfigSpec` gained `baseUrlEnv` and `extras`, both defaulted.
- `VertexAIProvider.configSpec` no longer sets `requiresEndpoint`. Code that builds a
  `NamedProviderConfig` by hand and passes it straight to `VertexAIProvider.buildConfig`,
  skipping validation, must set `extras = Map("project" -> ..., "location" -> ...)`: the
  deprecated aliases are resolved by validation, not by `buildConfig`.

### For provider authors

Declare provider-specific keys in `ProviderConfigSpec.extras` and read them from
`NamedProviderConfig.extras` - see [CONTRIBUTING](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#provider-specific-config-keys).

## Slice 5 follow-ups: `llm4s-openai-compatible`

Fixes to the shared OpenAI-compatible client after its carve
([#1132](https://github.com/llm4s/llm4s/issues/1132)). Nothing here needs a code change unless
you depend on the old behaviour.

### `complete` times out after two minutes

`OpenAICompatibleClient.complete` - DeepSeek, Z.ai, OpenRouter, Mistral, Cohere and the generic
`openai-compatible` provider - sent its request with no timeout, so an endpoint that never
answered hung the caller. It now fails after two minutes (`OpenAICompatibleClient.RequestTimeout`),
the value the old Mistral and Cohere clients used; streaming keeps five minutes. Neither is
configurable yet ([#712](https://github.com/llm4s/llm4s/issues/712)): a slow local model with a
long prompt should stream.

### Streaming requests send `stream_options`

A streaming request from the generic provider and DeepSeek now carries
`"stream_options": {"include_usage": true}`, so servers that follow OpenAI - vLLM, Ollama's
`/v1`, Perplexity's Router - report token usage on streams. Z.ai, OpenRouter, Mistral and Cohere
do not send it. An endpoint that rejects unknown fields fails the stream with a 400 or 422; build
its config with `OpenAICompatibleConfig.fromValues(..., streamUsage = false)`. A dialect you wrote
yourself inherits `streamUsageOption = true`; override it to `false` if the provider rejects the
field.

### Requesty configs report `requesty`

A config loaded from a `provider = "requesty"` section reported `providerId` = `openai`, because
`OpenAIConfig` inferred its id from the base URL. `OpenAIConfig` now carries the id its descriptor
sets, in a new trailing field `explicitProviderId: Option[ProviderId] = None`
(`OpenAIConfig.fromValues` has a matching defaulted `providerId` parameter), so it reports
`requesty`; `provider = "openrouter"` sets `openrouter` the same way, so an OpenRouter section
with a proxy `baseUrl` still reaches OpenRouter. With `None` - a config built by hand, or
`provider = "openai"` - the id is inferred from the base URL as before.

What you may notice:

- **`llm4s-config-policy`** sees `requesty`: an `allowedProviders` list must name `requesty`
  rather than rely on `openai`, and model patterns match `requesty/<model>`.
- `LLMConnect.getClient(config)` on a Requesty config now dispatches to the `requesty`
  descriptor, which needs `llm4s-openai` - as the `openai` one did.
- `OpenAIConfig.toString` shows `providerId`; a pattern match destructuring all six fields must
  add a seventh.

### `OPENAI_COMPATIBLE_BASE_URL` and `OPENAI_COMPATIBLE_API_KEY`

These are now the conventional variables for a generic endpoint, named by
`OpenAICompatibleConfigKeys`. `Llm4sConfig` does not read them - the generic provider has no vendor
and so no shared credentials key, and nothing reads `LLM_MODEL` - so bind them in a section (`baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL}`). The
chat-tui sample accepts `LLM_MODEL=openai-compatible/<model>` with them, and the config-policy
env check reads `OPENAI_COMPATIBLE_BASE_URL` as that provider's endpoint instead of
`OPENAI_BASE_URL`.

## Slice 5: Mistral, Cohere and Voyage leave core; core ships no provider

The last provider clients leave `llm4s-core` ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
None of this is in a release yet; `0.4.1` and earlier still ship Mistral, Cohere and Voyage inside
`llm4s-core`.

| Provider | Now in | Add |
|---|---|---|
| Mistral (`provider = "mistral"`) | `llm4s-openai-compatible`, as a dialect | `"org.llm4s" %% "llm4s-openai-compatible"` |
| Cohere (`provider = "cohere"`) | `llm4s-openai-compatible`, as a dialect | `"org.llm4s" %% "llm4s-openai-compatible"` |
| Voyage AI embeddings (`EMBEDDING_MODEL=voyage/...`) | `llm4s-voyage` (`modules/providers/voyage`) | `"org.llm4s" %% "llm4s-voyage"` |

Package names are unchanged, and so are the names and constructors of `MistralClient`,
`MistralProvider`, `MistralConfig`, `CohereClient`, `CohereProvider`, `CohereConfig` and
`VoyageAIEmbeddingProvider`, so imports compile as before once the dependency is added. No
environment variable or config key changed its name; `llm4s.embeddings.voyage` moved to
`llm4s-voyage`'s `reference.conf`, so it exists exactly when the module is on the classpath.

### Mistral and Cohere stream now

Both are [OpenAI-compatible](#slice-5-llm4s-openai-compatible), so they are small dialects on
the shared `OpenAICompatibleClient` rather than clients of their own. Both gain **streaming**
(`streamComplete` returned "not supported" - [#925](https://github.com/llm4s/llm4s/issues/925)),
streamed tool calls and token usage, **tool calling** and structured output; their descriptors no
longer declare `streaming = false`.

- **Mistral** posts to `<baseUrl>/v1/chat/completions` as before (`baseUrl` is the API root,
  `https://api.mistral.ai`); a `baseUrl` already ending in `/v1` is no longer doubled. Tool-call ids
  are sent in the nine-character form Mistral insists on; a reasoning model's thinking is returned
  as `Completion.thinking`.
- **Cohere** now calls Cohere's
  [OpenAI-compatibility API](https://docs.cohere.com/docs/compatibility-api) instead of the native
  `/v2/chat`. `CohereConfig.DEFAULT_BASE_URL` is now `https://api.cohere.ai/compatibility/v1` (it
  was `https://api.cohere.com`). **A configured `baseUrl` keeps working:** one that does not end in
  `/compatibility/v1` is taken to be a Cohere API root, as it was, and gets `/compatibility/v1`
  appended (after a trailing `/v1` or `/v2` is dropped); `CohereConfig.fromValues` stores and logs
  the mapped URL, so `endpointUrl` and policy checks see where requests really go. A proxy that
  forwarded only `/v2/chat` must now forward `/compatibility/v1/chat/completions`. System messages
  go under the `developer` role and a JSON schema as `{"type": "json_object", "schema": ...}`, as
  Cohere documents.

Behaviour changes for both, now that they share the client DeepSeek, Z.ai and OpenRouter use:

1. **Reply text is not trimmed** (the old clients trimmed it).
2. **A reply with no text is an empty completion**, not a `ValidationError` - with tool calling,
   a reply may carry only tool calls.
3. **A missing `id` or `created` is left `""` / `0`** rather than a random UUID / the current time
   (Mistral), and Cohere's `created` now comes from the reply.
4. **A `ToolMessage` is sent** rather than refused (Mistral) or silently dropped (Cohere).
5. `CompletionOptions.reasoning` is still not sent to either: Mistral's `reasoning_effort` and
   Cohere's accept only some models or values.

The shared client itself changed for every provider on it: **streamed completions now report
token usage** and a cost estimate (they always came back with `usage = None`), and an empty
conversation fails with a `ValidationError` before any request is sent.

### Core ships no provider

With those three gone, `llm4s-core` holds no provider client, and the list that existed only
because it did is removed:

- **`BuiltinProviders` and `BuiltinProviderModule`** (`org.llm4s.llmconnect.provider`) are
  deleted, with core's `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule` entry.
- **`ProviderRegistry.builtin` is deleted.** It would now be empty. Where discovery cannot run - a
  fat jar whose services files were overwritten - name the provider modules you ship:

  ```scala
  given ProviderRegistry =
    ProviderRegistry.ofModules(new Llm4sOpenAIModule, new Llm4sOpenAICompatibleModule, new Llm4sVoyageModule)
  ```

`ProviderRegistry.default` (discovery) is unchanged, and so is every call site that relies on it.
Core's `llmconnect/provider` package now holds only provider-neutral helpers: `CostEstimator`,
`EmbeddingProvider`, `HttpErrorMapper`, `MetricsRecording`, `ProviderExchangeRecorder` and
`ProviderResultOps`.

### Source breaks

1. **`BuiltinProviders`, `BuiltinProviderModule` and `ProviderRegistry.builtin` are removed** -
   see above.
2. **`ProviderModelListers.Mistral` is now `MistralModelLister`** (`org.llm4s.config`, in
   `llm4s-openai-compatible`); `MistralProvider.modelLister` returns it.
3. **`ConfigKeys.MISTRAL_API_KEY` and `MISTRAL_BASE_URL` are now on `OpenAICompatibleConfigKeys`**,
   and **`ConfigKeys.VOYAGE_API_KEY`, `VOYAGE_EMBEDDING_BASE_URL` and `VOYAGE_EMBEDDING_MODEL` on
   `VoyageConfigKeys`** (`llm4s-voyage`), both in `org.llm4s.config`. The strings are unchanged.
4. **`CohereConfig.DEFAULT_BASE_URL` changed value** - see above.
5. **`OpenAICompatibleDialect` gained four members** - `sendEmptyAssistantTurns`,
   `encodeToolCallId`, `systemRole` and `encodeResponseFormat` - each defaulting to the standard
   format, so an existing dialect compiles and behaves as before.

## Slice 5: `llm4s-openai-compatible`

The fifth provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)),
carrying DeepSeek (`provider = "deepseek"`), Z.ai (`"zai"`), OpenRouter (`"openrouter"`) and a
new generic provider, `"openai-compatible"`, for any other endpoint that speaks the OpenAI
`/chat/completions` API. It is in the build but not yet in a release; `0.4.1` and earlier still
ship DeepSeek, Z.ai and OpenRouter inside `llm4s-core`, and have no generic provider.

Unlike the earlier carves this is a **consolidation, not a pure move**. DeepSeek, Z.ai and
OpenRouter each had their own ~400-line copy of the same SDK-free client; they are now thin
subclasses of one `OpenAICompatibleClient`, each with a small `OpenAICompatibleDialect` for what
genuinely differs (headers, content encoding, reasoning parameters, thinking extraction,
tool-call parsing). The module depends on nothing but `llm4s-core`.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % version
```

### What moved

| Code | Now in |
|---|---|
| `DeepSeekClient`, `DeepSeekProvider`, `ZaiClient`, `ZaiProvider`, `OpenRouterClient`, `OpenRouterProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-openai-compatible` |
| `DeepSeekConfig`, `ZaiConfig`, `OpenAIConfig` (`org.llm4s.llmconnect.config`) | `llm4s-openai-compatible` |
| `ProviderModelListers.DeepSeek` / `.OpenRouter` → `DeepSeekModelLister` / `OpenRouterModelLister` (`org.llm4s.config`) | `llm4s-openai-compatible` |
| `DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL` → `DeepSeekConfig.DEFAULT_BASE_URL` | `llm4s-openai-compatible` |
| `DefaultConfig.DEFAULT_OPENROUTER_BASE_URL` → `OpenRouterProvider.DEFAULT_BASE_URL` | `llm4s-openai-compatible` |
| `ConfigKeys.DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`, `OPENROUTER_BASE_URL` → `OpenAICompatibleConfigKeys` (`org.llm4s.config`) | `llm4s-openai-compatible` |
| the commented `deepseek-main`, `zai-main` and `openrouter-main` examples in `reference.conf` | `llm4s-openai-compatible`'s `reference.conf` |

New: `OpenAICompatibleClient`, `OpenAICompatibleDialect`, `OpenAICompatibleConfig`,
`OpenAICompatibleProvider`, `OpenAICompatibleModelLister`, `Llm4sOpenAICompatibleModule`.

Package names are unchanged, and so are the public shapes of the three clients - their
constructors and companion `apply` overloads - their descriptors and their configs, so
`new DeepSeekClient(config)` or `OpenRouterClient(config, metrics)` compile as before once the
dependency is added.

**`llm4s-openai` users:** `OpenAIConfig` moved here, and `llm4s-openai` now depends on
`llm4s-openai-compatible` to get it. That module brings no SDK, and nothing changes in your build.

### The generic `openai-compatible` provider

```hocon
llm4s.providers {
  local-vllm {
    provider = "openai-compatible"
    baseUrl = "http://localhost:8000/v1"   # required
    model = "Qwen/Qwen2.5-7B-Instruct"     # required
    # apiKey = ...                        # optional; no Authorization header without one
    # contextWindow = 32768               # optional; default 8192
    # reserveCompletion = 4096            # optional; default 2048
    # headers { X-Team = "search" }       # optional
  }
}
```

To support it, a named provider section may now carry `contextWindow`, `reserveCompletion` and a
`headers` object, which `NamedProviderConfig` exposes. Providers other than `openai-compatible`
ignore them. See [OpenAI-compatible endpoints](../guide/providers#openai-compatible-endpoints).

### Registration is the dependency

`llm4s-openai-compatible` declares `Llm4sOpenAICompatibleModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it. Without the dependency, `provider = "deepseek"`, `"zai"` and
`"openrouter"` fail with the registry's error, which names the providers that are registered.
`ProviderRegistry.builtin` no longer includes them; where discovery cannot run:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sOpenAICompatibleModule) // `builtin` was removed later in slice 5
```

### Behaviour changes

The three copies had drifted apart; the shared client does each thing one way:

1. **DeepSeek returns thinking.** `deepseek-reasoner`'s `reasoning_content` is now
   `Completion.thinking` and is streamed as thinking deltas; the old client dropped it. Its
   `completion_tokens_details.reasoning_tokens` is `TokenUsage.thinkingTokens`.
2. **The stream body is closed on every failure.** Z.ai and OpenRouter left it open on an error
   status.
3. **Every call records exactly one provider exchange**, including a request that cannot be sent
   (DeepSeek and Z.ai recorded none for a non-streaming one; OpenRouter recorded failures twice).
4. **OpenRouter sends assistant content as a string.** The old client passed an `Option` through
   ujson's implicit conversion, so `"hi"` went out as `["hi"]` and no content as `[]`; it is now
   `"hi"`, `""` or `null`.
5. **Z.ai reads usage given as an array**, which its client meant to support but never matched.
6. **Streamed tool calls keep all their arguments.** A tool call streamed across several deltas
   lost every fragment after the first in all three clients: continuations carry only an `index`,
   the missing id was defaulted to `""`, and `StreamingAccumulator` skips a chunk with no id. The
   shared client now maps each index to its call's id for the life of the stream, and a streamed
   `Completion` reports its tool calls in `toolCalls`, as a non-streaming one does.
7. **A reply's `message.contentOpt` is `None` when the reply has no text** for all three (it was
   `Some("")` for DeepSeek and Z.ai); `Completion.content` is `""` either way.
8. **Replies are read leniently where the copies threw**: a missing `id`, `created` or `model`
   defaults, a streamed event with no `choices` is skipped, and a malformed non-streaming reply is
   a `Left` for all three (Z.ai could throw). OpenRouter keeps its strict tool-call parsing;
   DeepSeek and Z.ai keep their lenient one.

### Source breaks

1. **`ProviderModelListers.DeepSeek` and `.OpenRouter` are now `DeepSeekModelLister` and
   `OpenRouterModelLister`**, in the same package. The descriptors' `modelLister` returns them.
2. **`DefaultConfig.DEFAULT_DEEPSEEK_BASE_URL` and `DEFAULT_OPENROUTER_BASE_URL` are now
   `DeepSeekConfig.DEFAULT_BASE_URL` and `OpenRouterProvider.DEFAULT_BASE_URL`.** The values are
   unchanged.
3. **`ConfigKeys.DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL` and `OPENROUTER_BASE_URL` are now on
   `OpenAICompatibleConfigKeys`**, in the same package. The strings are unchanged.
4. **`ProviderRegistry.builtin` no longer includes `deepseek`, `zai` or `openrouter`** - see above.
5. **`NamedProviderConfig` and `RawNamedProviderSection` gained three trailing fields** with
   defaults (`contextWindow`, `reserveCompletion`, `headers`), so construction by name or by
   position is unaffected; a pattern match that destructures all seven fields must add three.
   `NamedProviderConfig.toString` now redacts the API key and header values.
6. **`ProviderModelListers.openAICompatible` gained two defaulted parameters**, `extraHeaders`
   and `apiKeyRequired`; existing calls compile unchanged. It no longer special-cases OpenRouter,
   whose lister passes its headers explicitly.

7. **`OpenRouterToolCallDeserializer` is removed** from `org.llm4s.llmconnect.serialization`. No
   client used it after the consolidation. The "double-nested" array it parsed was an artefact
   of the old `OpenRouterClient`, not OpenRouter's format; use `StandardToolCallDeserializer`
   (which stays), as `OpenRouterClient` now does.
8. **`StreamingResponseHandler` is removed**, with `BaseStreamingResponseHandler`,
   `OpenAIStreamingHandler`, `AnthropicStreamingHandler` and `StreamingResponseHandler.forProvider`
   (`org.llm4s.llmconnect.streaming`). No client streamed through them - each parses its own
   stream and accumulates with `StreamingAccumulator`, which stays - and `forProvider` was called
   only from tests. To assemble streamed chunks yourself, feed them to a `StreamingAccumulator`.

### What did *not* change

Every configuration key and environment variable for DeepSeek, Z.ai and OpenRouter: their
`provider` ids, `apiKey`, `baseUrl` and `organization`, and their default base URLs.

## Slice 5: `llm4s-openai` moves to `openai-java`

`llm4s-openai`'s `OpenAIClient` - behind `provider = "openai"`, `"azure"` and `"requesty"` - now
runs on OpenAI's official Java SDK, `com.openai:openai-java`, instead of Microsoft's
`com.azure:azure-ai-openai`. Microsoft has
[deprecated that SDK](https://learn.microsoft.com/en-us/java/api/overview/azure/ai-openai-readme?view=azure-java-preview)
(its last release, 1.0.0-beta.16, was on 2025-03-26) and points to `openai-java`, which also
covers Azure OpenAI ([#1132](https://github.com/llm4s/llm4s/issues/1132)).

**Most users change nothing.** `OpenAIClient`'s constructors and `apply` overloads,
`OpenAIProvider`, `AzureProvider`, `RequestyProvider`, `OpenAIConfig` and `AzureConfig`, every
configuration key and every environment variable are unchanged. Your dependency tree changes:
`llm4s-openai` now brings OkHttp, Jackson (2.x) and the Kotlin standard library rather than the
Azure core libraries. `openai-java` checks its Jackson version when a client is built and fails
on a Jackson it cannot use (a different major version, anything before 2.13.4, or 2.18.1); if
your application pins Jackson, keep it on a compatible 2.x.

### How Azure is configured

As before: `endpoint` is the resource endpoint (`https://<resource>.openai.azure.com`), `model`
is the deployment name, `apiKey` is sent as the `api-key` header, and `apiVersion` as the
`api-version` query parameter, so a request goes to
`<endpoint>/openai/deployments/<deployment>/chat/completions?api-version=<version>`. The client
tells the SDK this is Azure rather than letting it guess from the host name, so an endpoint on
your own domain (API Management, a private endpoint) keeps working. Two things are new:

- **`apiVersion` takes either form.** The wire form (`2024-10-21`, `2025-01-01-preview`), which
  the docs have always shown, now works; before, only the Azure SDK's constant names
  (`V2024_10_21`, `V2025_01_01_PREVIEW` - the form of `AzureConfig.DEFAULT_API_VERSION`) did.
  Both still work.
- **An endpoint ending in `/openai/v1`** uses Azure's unified v1 API: the deployment goes in the
  request body and `api-version` is sent only if you set one other than the default.

### Source break: `AzureToolHelper` is now `OpenAIToolHelper`

`AzureToolHelper` took and returned Azure SDK types, so it could not survive the SDK. It is
replaced, in the same package (`org.llm4s.toolapi`) and module, by `OpenAIToolHelper` over
`openai-java`'s types (`com.openai.models.chat.completions`):

| Before (`AzureToolHelper`) | Now (`OpenAIToolHelper`) |
|---|---|
| `addToolsToOptions(registry, options: ChatCompletionsOptions): ChatCompletionsOptions` | `addToolsToParams(registry, builder: ChatCompletionCreateParams.Builder): ChatCompletionCreateParams.Builder` |
| `convertToolRegistryToAzureTools(registry): java.util.List[ChatCompletionsToolDefinition]` | `convertToolRegistryToOpenAITools(registry): java.util.List[ChatCompletionTool]` |

```scala
import org.llm4s.toolapi.{ OpenAIToolHelper, ToolRegistry }
import com.openai.models.chat.completions.ChatCompletionCreateParams

val params = OpenAIToolHelper
  .addToolsToParams(new ToolRegistry(tools), ChatCompletionCreateParams.builder().model("gpt-4o"))
```

If you called the Azure SDK yourself alongside llm4s, add `com.azure:azure-ai-openai` to your
own build: `llm4s-openai` no longer brings it.

### Behaviour changes

1. **Streamed tool calls keep their arguments.** A streamed tool call arrives split across
   deltas, and only the first carries the call's `id`; the Azure SDK path keyed calls by `id`, so
   every later fragment - usually all of the arguments - was lost. Continuations are now matched
   by `index`, fragments are concatenated verbatim, and a streamed `Completion` reports its tool
   calls in `toolCalls` as a non-streaming one does.
2. **`OpenAIConfig.organization` is sent** as the `OpenAI-Organization` header. The Azure SDK
   ignored it.
3. **HTTP errors map by status code**: 401 and 403 to `AuthenticationError`, 429 to
   `RateLimitError`, 400 to `ValidationError`, anything else to `ServiceError`, each naming the
   provider. Before, they were classified by searching the exception message, and most became
   `UnknownError`.
4. **Streamed token usage** is read from whichever chunk carries it, including a usage-only final
   chunk, not only from the chunk with the finish reason.
5. **`close()` releases the SDK's HTTP client** (connections and threads); before it released
   nothing.
6. **Azure and Requesty are labelled as themselves.** Their errors (`AuthenticationError.provider`
   and the error context), metrics and provider-exchange log now say `azure` and `requesty`;
   every one said `openai` before. Requesty takes its label from its descriptor. A Requesty
   config loaded from a `provider = "requesty"` section also reports `providerId` = `requesty`
   since a later fix (see [above](#requesty-configs-report-requesty)); only an `OpenAIConfig`
   you build by hand with Requesty's base URL still infers `openai` from it - pass
   `providerId = Some(ProviderId("requesty"))` to `OpenAIConfig.fromValues` for that.
7. **Several streamed tool calls come back in the order the stream named them**, in
   `Completion.toolCalls` and on the message. `llm4s-core`'s `StreamingAccumulator` kept them in
   an unordered map, so they could come back in hash order; this applies to every client that
   streams through it.

## Slice 5: `llm4s-openai`

The fourth provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)),
carrying the three providers that share `OpenAIClient` - OpenAI (`provider = "openai"`), Azure
OpenAI (`"azure"`) and Requesty (`"requesty"`) - and the OpenAI embedding provider
(`EMBEDDING_MODEL=openai/<model>`). It is in the build but not yet in a release; `0.4.1` and
earlier still ship them inside `llm4s-core`.

With it goes the Azure OpenAI SDK (`com.azure:azure-ai-openai`), which `OpenAIClient` is built
on: `llm4s-core` no longer depends on it, and with the Anthropic SDK already gone, core now
depends on no vendor SDK at all.

OpenRouter, DeepSeek and Z.ai are **not** in this module. They speak the OpenAI wire format but
each has its own client with no SDK, so bundling them here would make their users download the
Azure SDK for nothing. They went on to `llm4s-openai-compatible` - see
[above](#slice-5-llm4s-openai-compatible).

### What moved

| Code | Now in |
|---|---|
| `OpenAIClient`, `OpenAIProvider`, `AzureProvider`, `RequestyProvider`, `OpenAIEmbeddingProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-openai` |
| `AzureConfig` (`org.llm4s.llmconnect.config`) | `llm4s-openai` |
| `AzureToolHelper` (`org.llm4s.toolapi`) | `llm4s-openai` |
| `ProviderModelListers.OpenAI` / `.Requesty` → `OpenAIModelLister` / `RequestyModelLister` (`org.llm4s.config`) | `llm4s-openai` |
| `DefaultConfig.DEFAULT_OPENAI_BASE_URL` → `OpenAIProvider.DEFAULT_BASE_URL` | `llm4s-openai` |
| `DefaultConfig.DEFAULT_REQUESTY_BASE_URL` → `RequestyProvider.DEFAULT_BASE_URL` | `llm4s-openai` |
| `DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW` → `AzureConfig.DEFAULT_API_VERSION` | `llm4s-openai` |
| `ConfigKeys.OPENAI_*`, `REQUESTY_BASE_URL`, `AZURE_*`, `OPENAI_EMBEDDING_*` → `OpenAIConfigKeys` (`org.llm4s.config`) | `llm4s-openai` |
| the commented `openai-main`, `requesty-main` and `azure-main` examples, and the `llm4s.embeddings.openai` block, in `reference.conf` | `llm4s-openai`'s `reference.conf` |

Package names are unchanged, so `import org.llm4s.llmconnect.provider.OpenAIClient` keeps
working once the dependency is added:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai" % version
```

### What stayed in core

- **`OpenAIConfig`**, because OpenRouter builds one too: `OpenRouterProvider` and
  `OpenRouterClient` take an `OpenAIConfig`, and its `providerId` answers `openrouter` for an
  OpenRouter base URL. It moved when OpenRouter did, to `llm4s-openai-compatible`, which
  `llm4s-openai` now depends on.
- **`OpenAIStreamingHandler`** (since removed with `StreamingResponseHandler`; see
  [`llm4s-openai-compatible`](#slice-5-llm4s-openai-compatible)), the SSE parser behind
  `StreamingResponseHandler.forProvider("openai" | "azure" | "openrouter")`, which OpenRouter's
  path shares. `OpenAIClient` streams through the Azure SDK.
- **`ConfigKeys.OPENROUTER_BASE_URL`**, still naming `OPENAI_BASE_URL` (since moved to
  `OpenAICompatibleConfigKeys`).
- Strings that do not reach a client: `ToolRegistry.getOpenAITools` and
  `getToolDefinitionsSafe("openai")`, the `openai/...` model-registry data, the `sk-` secret
  pattern, config-policy allow-lists.

### Registration is the dependency

`llm4s-openai` declares `Llm4sOpenAIModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it and `provider = "openai"`, `"azure"` and `"requesty"`, and
`EMBEDDING_MODEL=openai/<model>`, resolve as before. Without the dependency they fail with the
registry's error, which says the provider is not registered and names the providers that are.

`ProviderRegistry.builtin` no longer includes them. If you used `builtin` to avoid classpath
discovery (a shaded fat jar, typically), add the module explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sOpenAIModule) // `builtin` was removed later in slice 5
```

**`llm4s-rag` users:** `RAGConfig.default` embeds with `openai/text-embedding-3-small`. A
pipeline built from the default therefore needs `llm4s-openai` too; otherwise name the provider
you ship with `.withEmbeddings("voyage", ...)` (or `"ollama"` with `llm4s-ollama`).
`llm4s-rag` does not depend on `llm4s-openai` itself, so it does not bring the Azure SDK.

### Source breaks

1. **`ToolRegistry.addToAzureOptions(options)` is removed.** Its signature exposed the Azure SDK
   type `ChatCompletionsOptions` from core's `ToolRegistry`, so core could not drop the SDK while
   it existed. Call `AzureToolHelper.addToolsToOptions(registry, options)` instead - same
   package (`org.llm4s.toolapi`), now in `llm4s-openai`, with the same result.
2. **`ProviderModelListers.OpenAI` and `.Requesty` are now `OpenAIModelLister` and
   `RequestyModelLister`**, in the same package (`org.llm4s.config`). The descriptors'
   `modelLister` returns them, so code that reached a lister through the descriptor is
   unaffected.
3. **Three defaults moved off `DefaultConfig`**: `DEFAULT_OPENAI_BASE_URL` is now
   `OpenAIProvider.DEFAULT_BASE_URL`, `DEFAULT_REQUESTY_BASE_URL` is now
   `RequestyProvider.DEFAULT_BASE_URL`, and `DEFAULT_AZURE_V2025_01_01_PREVIEW` is now
   `AzureConfig.DEFAULT_API_VERSION`. The values are unchanged. The base URLs live on the
   descriptors rather than on `OpenAIConfig` because `OpenAIConfig` stays in core.
4. **`ConfigKeys.OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_ORG`, `REQUESTY_BASE_URL`,
   `AZURE_API_BASE`, `AZURE_API_KEY`, `AZURE_API_VERSION`, `OPENAI_EMBEDDING_BASE_URL` and
   `OPENAI_EMBEDDING_MODEL` are now on `OpenAIConfigKeys`**, in the same package, as
   `ConfigKeys.ANTHROPIC_*` became `AnthropicConfigKeys`. The strings are unchanged.
5. **`ProviderRegistry.builtin` no longer includes `openai`, `azure` or `requesty`, nor the
   `openai` embedding provider** - see above.

### What did *not* change

Every configuration key and environment variable: `llm4s.providers.<name>` with
`provider = "openai"`, `"azure"` or `"requesty"` and their `apiKey`, `baseUrl`, `organization`,
`endpoint` and `apiVersion`; `llm4s.embeddings.openai.*`, `OPENAI_EMBEDDING_BASE_URL` and
`OPENAI_EMBEDDING_MODEL`; and `llm4s.openai.apiKey` as the key OpenAI embeddings share with chat.
(A later change dropped that `llm4s.openai.apiKey` fallback: the key is
`llm4s.embeddings.openai.apiKey` alone - see
[From `LLM_MODEL` to named provider sections](#from-llm_model-to-named-provider-sections).)

## Slice 5: `llm4s-anthropic`

The third provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)),
carrying the Anthropic Claude chat provider (`provider = "anthropic"`). It is in the build but
not yet in a release; `0.4.1` and earlier still ship it inside `llm4s-core`.

With it goes the Anthropic Java SDK (`com.anthropic:anthropic-java`): `llm4s-core` no longer
depends on it, so an application that does not use Anthropic no longer carries it. It was also
declared, unused, by `llm4s-workspace-client`, and has been removed from there too.

### What moved

| Code | Now in |
|---|---|
| `AnthropicClient`, `AnthropicProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-anthropic` |
| `AnthropicConfig` (`org.llm4s.llmconnect.config`) | `llm4s-anthropic` |
| `ProviderModelListers.Anthropic` → `AnthropicModelLister` (`org.llm4s.config`) | `llm4s-anthropic` |
| `DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL` → `AnthropicConfig.DEFAULT_BASE_URL` | `llm4s-anthropic` |
| `ConfigKeys.ANTHROPIC_API_KEY`, `ConfigKeys.ANTHROPIC_BASE_URL` → `AnthropicConfigKeys` (`org.llm4s.config`) | `llm4s-anthropic` |
| the commented `anthropic-main` example in `reference.conf` | `llm4s-anthropic`'s `reference.conf` |

Package names are unchanged, so `import org.llm4s.llmconnect.config.AnthropicConfig` keeps
working once the dependency is added:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-anthropic" % version
```

### Registration is the dependency

`llm4s-anthropic` declares `Llm4sAnthropicModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it and `provider = "anthropic"` resolves as before. Without the
dependency it fails with the registry's error, which says the provider is not registered and
names the providers that are.

`ProviderRegistry.builtin` no longer includes Anthropic. If you used `builtin` to avoid
classpath discovery (a shaded fat jar, typically), add the module explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sAnthropicModule) // `builtin` was removed later in slice 5
```

### Source breaks

Three names could not keep their fully-qualified path, because they were members of objects
that stay in core:

1. **`ProviderModelListers.Anthropic` is now `AnthropicModelLister`**, in the same package
   (`org.llm4s.config`). `AnthropicProvider.modelLister` returns it, so code that reached the
   lister through the descriptor is unaffected.
2. **`DefaultConfig.DEFAULT_ANTHROPIC_BASE_URL` is now `AnthropicConfig.DEFAULT_BASE_URL`**, the
   same place `GeminiConfig`, `DeepSeekConfig` and `MistralConfig` keep theirs. The value is
   unchanged.
3. **`ConfigKeys.ANTHROPIC_API_KEY` and `ConfigKeys.ANTHROPIC_BASE_URL` are now
   `AnthropicConfigKeys.ANTHROPIC_API_KEY` and `AnthropicConfigKeys.ANTHROPIC_BASE_URL`**, in the
   same package, as `ConfigKeys.OLLAMA_*` became `OllamaConfigKeys`. The strings are unchanged.

### What did *not* change

Every configuration key and environment variable: `llm4s.providers.<name>` with
`provider = "anthropic"`, `apiKey` and the optional `baseUrl`.

What names Anthropic without depending on its client stays in core and answers the same with or
without `llm4s-anthropic`: `ToolRegistry.getToolDefinitionsSafe("anthropic")`, the
`anthropic/...` entries in the embedded model registry data, the `sk-ant-` secret pattern,
config-policy allow-lists, and `AnthropicStreamingHandler` - the SDK-free SSE parser behind
`StreamingResponseHandler.forProvider("anthropic")`, which `AnthropicClient` does not use.

## Slice 5: `llm4s-gemini`

The second provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)),
carrying both of Google's chat providers: the Gemini API (`provider = "gemini"`, alias
`"google"`) and Vertex AI (`provider = "vertexai"`, alias `"vertex"`). It is in the build but
not yet in a release; `0.4.1` and earlier still ship both inside `llm4s-core`.

### Why Vertex AI is in the same module

`VertexAIClient` only calls Google's `publishers/google` models - Gemini - using the same JSON
request and response format as `GeminiClient`. The two differ in endpoint (Vertex is scoped to
a GCP project and region on `aiplatform.googleapis.com`) and in authentication (Vertex uses
OAuth2, implemented by `VertexAIAuthProvider` without a Google SDK), not in dependencies. So
bundling them costs a Gemini-API user nothing, while splitting Vertex AI out later would be a
breaking move for its users; bundling now is the direction that stays safe.

### What moved

| Code | Now in |
|---|---|
| `GeminiClient`, `GeminiProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-gemini` |
| `VertexAIClient`, `VertexAIProvider`, `VertexAIAuthProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-gemini` |
| `GeminiConfig`, `VertexAIConfig` (`org.llm4s.llmconnect.config`) | `llm4s-gemini` |
| `ProviderModelListers.Gemini` → `GeminiModelLister` (`org.llm4s.config`) | `llm4s-gemini` |
| `DefaultConfig.DEFAULT_GEMINI_BASE_URL` → `GeminiConfig.DEFAULT_BASE_URL` | `llm4s-gemini` |
| `DefaultConfig.DEFAULT_VERTEXAI_LOCATION` → `VertexAIConfig.DEFAULT_LOCATION` (already existed) | `llm4s-gemini` |
| the commented `gemini-main` example in `reference.conf` | `llm4s-gemini`'s `reference.conf`, with a `vertexai-main` example beside it |

Package names are unchanged, so `import org.llm4s.llmconnect.config.GeminiConfig` keeps
working once the dependency is added:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-gemini" % version
```

### Registration is the dependency

`llm4s-gemini` declares `Llm4sGeminiModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it and `provider = "gemini"`, `"google"`, `"vertexai"` and
`"vertex"` resolve as before. Without the dependency they fail with the registry's error, which
says the provider is not registered and names the providers that are.

`ProviderRegistry.builtin` no longer includes Gemini or Vertex AI. If you used `builtin` to
avoid classpath discovery (a shaded fat jar, typically), add the module explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sGeminiModule) // `builtin` was removed later in slice 5
```

### Source breaks

Three names could not keep their fully-qualified path, because they were members of objects
that stay in core:

1. **`ProviderModelListers.Gemini` is now `GeminiModelLister`**, in the same package
   (`org.llm4s.config`). `GeminiProvider.modelLister` returns it, so code that reached the
   lister through the descriptor is unaffected.
2. **`DefaultConfig.DEFAULT_GEMINI_BASE_URL` is now `GeminiConfig.DEFAULT_BASE_URL`**, the
   same place `DeepSeekConfig`, `CohereConfig` and `MistralConfig` keep theirs. The value is
   unchanged.
3. **`DefaultConfig.DEFAULT_VERTEXAI_LOCATION` is removed**; use
   `VertexAIConfig.DEFAULT_LOCATION`, which already held the same `"us-central1"`.

### What did *not* change

Every configuration key and environment variable: `llm4s.providers.<name>` with
`provider = "gemini"` or `"vertexai"`, the Vertex AI reading of `endpoint` (GCP project id),
`organization` (region) and `apiKey` (credential file path), and `GOOGLE_APPLICATION_CREDENTIALS`
for Vertex AI authentication. (`endpoint` and `organization` have since become Vertex AI's
`project` and `location`; see
[the note above](#vertex-ai-endpoint-becomes-project-organization-becomes-location).)

Strings that name Gemini without depending on its client stay in core and answer the same with
or without `llm4s-gemini`: `ToolRegistry.getToolDefinitionsSafe("gemini")`, the `gemini/...`
entries in the embedded model registry data, and config-policy allow-lists.

## Slice 4 (close-out): the last closed provider list, and `fromValues` stops throwing

The last items deferred from slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)).
Both are source breaks, taken now because the API is not yet frozen; neither has a
deprecated shim, following the precedent of `ProviderKind` in PR 1. It is in the build but not
yet in a release, so nothing here affects `0.4.1` or earlier.

### `org.llm4s.rag.EmbeddingProvider` is gone

`llm4s-rag` kept its own closed list of embedding providers - `EmbeddingProvider.OpenAI`,
`Voyage` and `Ollama` - duplicating what the `ProviderRegistry` has known since PR 4. It was
wrong in both directions: an embedding provider from its own module could not be named through
it, and it named `ollama` whether or not `llm4s-ollama` was on the classpath. It also shared its
simple name with `org.llm4s.llmconnect.provider.EmbeddingProvider`, the embedding client trait.

`RAGConfig` now names the provider by id, the same id as in `EMBEDDING_MODEL=<id>/<model>`, and
`RAG.build` resolves it through the registry:

```scala
// Before
import org.llm4s.rag.{ EmbeddingProvider, RAG }

RAG.builder()
  .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-large")

EmbeddingProvider.fromString(name).toRight(...)   // to turn a configured name into one

// After
import org.llm4s.rag.RAG

RAG.builder()
  .withEmbeddings("openai", "text-embedding-3-large")

RAG.builder().withEmbeddings(name)                 // any registered id or alias; no conversion
```

`RAGConfig.embeddingProvider` is a `ProviderId`, so `config.embeddingProvider.name` becomes
`config.embeddingProvider.asString`. `RAG.build` and `RAGConfig#build` take an implicit
`ProviderRegistry`, resolved to `ProviderRegistry.default` when none is in scope, so existing
call sites compile unchanged and an application with its own registry reaches the providers in
it. `EmbeddingProvider.values` has no replacement in `llm4s-rag`: the providers are
`summon[ProviderRegistry].embeddingIds`.

### Behaviour changes

- An id that is not registered fails `RAG.build` with the registry's own "Embedding provider
  '...' is not registered" error, naming the ids that are - where `fromString` returned `None`
  and every caller wrote its own message. The resolver is asked for the provider's canonical id,
  so an alias such as `voyageai` arrives as `voyage`.
- **The model default moved from `llm4s-rag` into the provider.** `RAG` used to pick a model
  per provider from its own table, and ignore the model in the `EmbeddingProviderConfig` the
  resolver returned. `RAGConfig()` still defaults to `openai` / `text-embedding-3-small`, but
  `withEmbeddings(provider)` without a model now clears any model set earlier and uses, in
  order: the resolved config's model, then the provider's `configSpec.defaultModel`. With
  `Llm4sConfig.embeddings()` as the resolver, that is the model you configured. A provider with
  neither fails the build naming the provider, rather than guessing.
- Dimensions come from the provider's `dimensionsOf(model)` when not set explicitly, rather than
  from a second table in `llm4s-rag`. A model its provider does not declare keeps the old
  fallback of 1536.

### `fromValues` returns `Result`

Every `ProviderConfig` subtype's `fromValues` factory - `OpenAIConfig`, `AzureConfig`,
`AnthropicConfig`, `ZaiConfig`, `GeminiConfig`, `DeepSeekConfig`, `CohereConfig`,
`MistralConfig`, `VertexAIConfig` and `OllamaConfig` - validated its arguments with
`require(...)`, so a blank API key threw `IllegalArgumentException` out of a library whose rule
is that errors are values. They now return `Result[XConfig]`, and a blank credential or endpoint
is a `ConfigurationError` carrying the same message as before (`"OpenAI apiKey must be
non-empty"`) with the field in `missingKeys`.

```scala
// Before
val config: OpenAIConfig = OpenAIConfig.fromValues("gpt-4o", apiKey, None, baseUrl)
val client = LLMConnect.getClient(config)

// After
val client: Result[LLMClient] =
  OpenAIConfig.fromValues("gpt-4o", apiKey, None, baseUrl).flatMap(LLMConnect.getClient(_))
```

A `ProviderDescriptor.buildConfig` that returned `fromValues` from a `for`'s `yield`, or
through `.map`, binds it as a generator or uses `.flatMap` instead:

```scala
// Before
for
  apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
  baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
yield AcmeConfig.fromValues(section.model.asString, apiKey, baseUrl)

// After
for
  apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
  baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
  config  <- AcmeConfig.fromValues(section.model.asString, apiKey, baseUrl)
yield config
```

Code that caught the exception - `Try(OpenAIConfig.fromValues(...)).toEither`, or a test's
`an[IllegalArgumentException] should be thrownBy` - matches on the `Left` instead. Nothing
reachable from configuration changes: `Llm4sConfig` and the provider descriptors already
rejected a missing key before calling `fromValues`, and now propagate its `Left` rather than
letting a blank one throw.

## Slice 5: `llm4s-ollama`

The first provider module of slice 5 ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
Ollama goes first because it has the smallest client, no vendor SDK, and a live `@Ollama`
integration tier - so the carve is checked against a real server rather than mocks. It is in
the build but not yet in a release; `0.4.1` and earlier still ship Ollama inside `llm4s-core`.

### What moved

| Code | Now in |
|---|---|
| `OllamaClient`, `OllamaProvider`, `OllamaEmbeddingProvider` (`org.llm4s.llmconnect.provider`) | `llm4s-ollama` |
| `OllamaConfig` (`org.llm4s.llmconnect.config`) | `llm4s-ollama` |
| `ProviderModelListers.Ollama` → `OllamaModelLister` (`org.llm4s.config`) | `llm4s-ollama` |
| `ConfigKeys.OLLAMA_*` → `OllamaConfigKeys.OLLAMA_*` (`org.llm4s.config`) | `llm4s-ollama` |
| the `llm4s.embeddings.ollama` `reference.conf` block | `llm4s-ollama`'s `reference.conf` |

Package names are unchanged, so `import org.llm4s.llmconnect.config.OllamaConfig` keeps
working once the dependency is added:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-ollama" % version
```

### Registration is the dependency

`llm4s-ollama` declares `Llm4sOllamaModule` in its `META-INF/services`, so
`ProviderRegistry.default` finds it and `provider = "ollama"` and
`EMBEDDING_MODEL=ollama/<model>` resolve as before. Without the dependency both fail with the
registry's error, which says `ollama` is not registered and names the providers that are.

`ProviderRegistry.builtin` no longer includes Ollama, because core no longer ships it. If you
used `builtin` to avoid classpath discovery (a shaded fat jar, typically), add the module
explicitly:

```scala
given ProviderRegistry = ProviderRegistry.ofModules(new Llm4sOllamaModule) // `builtin` was removed later in slice 5
```

### Source breaks

Two names could not keep their fully-qualified path, because they were members of objects that
stay in core:

1. **`ProviderModelListers.Ollama` is now `OllamaModelLister`**, in the same package
   (`org.llm4s.config`). `OllamaProvider.modelLister` returns it, so code that reached the
   lister through the descriptor is unaffected.
2. **`ConfigKeys.OLLAMA_BASE_URL`, `OLLAMA_EMBEDDING_BASE_URL` and `OLLAMA_EMBEDDING_MODEL`
   are now on `OllamaConfigKeys`**, also in `org.llm4s.config`. The variable names themselves
   are unchanged.

### What did *not* change

Every configuration key and environment variable: `llm4s.providers.<name>` with
`provider = "ollama"`, `llm4s.embeddings.ollama.*`, `OLLAMA_EMBEDDING_BASE_URL` and
`OLLAMA_EMBEDDING_MODEL`. The `reference.conf` block moved rather than changed; HOCON merges
reference files across jars, so the keys exist exactly when the provider does.

`TokenizerMapping` still recognises the `ollama/` model-name prefix. It is a naming
convention on model strings rather than a reference to the provider, and it gives the same
answer whether or not `llm4s-ollama` is on the classpath.

## Slice 4 follow-up: embedding dimensions move into the provider

One of the two items deferred from slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)),
and the precursor to carving `llm4s-ollama` ([#1132](https://github.com/llm4s/llm4s/issues/1132)).
`ModelDimensionRegistry` was the last central provider list on the embedding side: a map in
`llm4s-core` covering `openai`, `voyage` and `local`. It had no `ollama` entry, so the documented

```bash
EMBEDDING_MODEL=ollama/nomic-embed-text
```

failed at `Llm4sConfig.textEmbeddingModel()` with `Unknown model 'nomic-embed-text' for
provider 'ollama'`, and `RAGASFactory.fromConfigs` papered over the same gap with
`.getOrElse(1536)` for a model that is 768-dimensional.

### Declaring dimensions

An embedding provider now declares the dimensions of the models it knows, alongside its
`configSpec`:

```scala
object JinaEmbeddings extends EmbeddingProviderDescriptor:
  val id = ProviderId("jina")

  override val modelDimensions = Map(
    "jina-embeddings-v3" -> 1024
  )
```

A provider whose model names have variants overrides `dimensionsOf(model)` instead - Ollama
folds a `:latest` tag onto the untagged name, and no other tag, because other tags of one model
can differ in size. A model a provider does not declare still embeds; only a caller that needs
its dimensionality up front is told it is unknown.

### Source-compatible signature changes

`ModelDimensionRegistry.getDimension`, `RAGASFactory.fromConfigs` and
`RAGASFactory.basicFromConfigs` take an implicit `ProviderRegistry`, resolved to
`ProviderRegistry.default` when none is in scope. Existing call sites compile unchanged; a
caller with its own registry now reaches the providers in it.

`ModelDimensionRegistry.localDimension(model)` answers the local non-text encoders
(`openclip-vit-b32`, `wav2vec2-base`, `timesformer-base`), which have no descriptor because
nothing can be configured with them. `getDimension("local", ...)` still works.

### Behaviour changes

- `RAGASFactory.fromConfigs` and `basicFromConfigs` return the lookup's `Left` for an embedding
  model its provider does not declare, instead of assuming 1536 dimensions. Build the
  `EmbeddingModelConfig` yourself and call `RAGASFactory.create` or `basic` for such a model.
- `getDimension` for an unregistered provider returns the registry's "not registered" error,
  naming the embedding providers that are, rather than "Unknown model".
- `voyage-3-large` resolves to 1024, its default output size, not 1536.

## Slice 4 (PR 5): embedding config moves into the provider

The fifth slice 4 change ([#1131](https://github.com/llm4s/llm4s/issues/1131)), and the
follow-up PR 4 named. PR 4 made an embedding provider *resolvable* from its own module;
its configuration was still core's business:

```scala
final private case class EmbeddingsOllamaSection(apiKey: …, baseUrl: …, model: …)
implicit private val embeddingsOllamaSectionReader = …
private val DefaultOllamaEmbeddingBaseUrl = "http://localhost:11434"
private def buildOllamaEmbeddings(…) = …
// plus two `match` arms
```

— a typed case class, a PureConfig reader, a default, a builder and two dispatch arms per
provider. A third-party embedding provider could be registered and then had nothing to be
configured *with*.

**Nothing changes for users.** `EMBEDDING_MODEL`, `EMBEDDING_PROVIDER`, `OPENAI_API_KEY`,
`VOYAGE_API_KEY`, `OLLAMA_EMBEDDING_BASE_URL` and every `llm4s.embeddings.<id>` key behave
exactly as before.

### The section shape is core's; what it means is the provider's

`llm4s-core` now parses one uniform shape - `apiKey`, `baseUrl`, `model` - for whichever
provider was selected, and hands it to the descriptor:

```scala
def buildConfig(section: EmbeddingProviderSection, modelOverride: Option[String]): Result[EmbeddingProviderConfig]
```

Everything it needs arrives in `section`, already typed. A descriptor reads no configuration
itself: raw config access stays in `org.llm4s.config`, which is the boundary AGENTS.md sets.

Most providers never implement it. Declaring an `EmbeddingConfigSpec` is enough, and the
default implementation resolves the three fields against it:

```scala
object JinaEmbeddings extends EmbeddingProviderDescriptor:
  val id = ProviderId("jina")

  override val configSpec = EmbeddingConfigSpec(
    requiresApiKey = true,
    defaultBaseUrl = Some("https://api.jina.ai/v1"),
    apiKeyEnv      = Some("JINA_API_KEY")   // named in the error when it is missing
  )
```

### Defaults are code, environment bindings are HOCON

They used to be both. `reference.conf` said `baseUrl = "http://localhost:11434"` and
`EmbeddingsConfigLoader` said `DefaultOllamaEmbeddingBaseUrl`, with nothing keeping them in
step. The default now lives only in the descriptor's `EmbeddingConfigSpec`, and each
provider's `reference.conf` block is reduced to the environment variables it binds:

```hocon
ollama {
  baseUrl = ${?OLLAMA_EMBEDDING_BASE_URL}
  model   = ${?OLLAMA_EMBEDDING_MODEL}
}
```

That block is keyed by **provider id**, so it travels with the provider when the provider moves
to its own module - HOCON merges these across jars. `ProviderId` canonicalises (trim, lowercase)
but does not restrict, so an id containing a dot is legal and must be quoted as a single HOCON
key; `EmbeddingConfigSpec.sectionPath` / `fieldPath` build the path that way, and the paths
named in errors are the paths to write:

```hocon
llm4s.embeddings."acme.embeddings" { apiKey = ${?ACME_API_KEY} }
```

(Chat config cannot do this: it is keyed by the user's *instance* name, which is why
`ProviderConfigSpec.defaultBaseUrl` is code and says so.)

### A key that lives somewhere else

> **Superseded:** `apiKeyPath` was removed in favour of the shared
> `llm4s.credentials.<id>.apiKey` - see
> [Vendor credentials](#vendor-credentials-a-shared-api-key-per-provider). `apiKeyEnv` is now a
> `Seq[String]`.

A provider whose key is kept outside its own `llm4s.embeddings.<id>` section declares where to
look instead of the loader special-casing it, and that declaration is also what makes the error
name the place the key is really set:

```scala
override val configSpec = EmbeddingConfigSpec(
  requiresApiKey = true,
  apiKeyPath     = Some("llm4s.acme.apiKey"),
  apiKeyEnv      = Some("ACME_API_KEY"),
  …
)
```

> Missing acme embeddings apiKey (llm4s.acme.apiKey / ACME_API_KEY)

OpenAI was the case this was written for, reading `llm4s.openai.apiKey`; it no longer uses it
(its key is its own `llm4s.embeddings.openai.apiKey`), and no provider in this repository does.

`apiKeyPath` is a *declaration*, not a read: `EmbeddingsConfigLoader` resolves it and hands
the value back in the section before calling `buildConfig`. The provider owns the knowledge of
*where* its key lives; `org.llm4s.config` keeps sole ownership of *reading* it.

### The embedding config entry points take the registry

```scala
def embeddings()(using ProviderRegistry): Result[(String, EmbeddingProviderConfig)]
def loadTextEmbeddingModel()(using ProviderRegistry): Result[TextEmbeddingModelSettings]
def textEmbeddingModel()(using ProviderRegistry): Result[TextEmbeddingModelSettings]
```

Binary-incompatible, source-compatible, as in PRs 3 and 4. Without it an application's own
registry could not reach the loader, and a provider it registered explicitly would resolve for
`EmbeddingClient.from` but not for its configuration.

All three resolve through the same registry, so a provider configurable by one is configurable
by all of them: `textEmbeddingModel` now goes through `embeddings` rather than calling the
loader a second time.

### Error messages

Unknown providers now produce the registry's message, which names what *is* registered and the
scan that found it. Missing-field errors name the config path and the environment variable the
descriptor declared. The provider is named by its canonical id (`openai`, not `OpenAI`) - the
spelling that appears in config.

## Slice 4 (PR 4): embedding providers join the SPI

The fourth slice 4 change ([#1131](https://github.com/llm4s/llm4s/issues/1131)), and the last
unchecked item on that issue's list. PRs 2 and 3 made a *chat* provider self-describing and
discoverable. `EmbeddingClient.from` was still the shape the slice exists to delete:

```scala
provider.toLowerCase match
  case "openai" => Right(new EmbeddingClient(OpenAIEmbeddingProvider.fromConfig(cfg)))
  case "voyage" => ...
  case "ollama" => ...
  case other    => Left(EmbeddingError(...))
```

so an embedding provider was an edit to `llm4s-core` no matter where its code lived. It is now
resolved through the same `ProviderRegistry`.

### Declaring an embedding provider

`EmbeddingProviderDescriptor` is the embedding counterpart of `ProviderDescriptor`, and
`Llm4sProviderModule` gained a second list:

```scala
object JinaEmbeddings extends EmbeddingProviderDescriptor:
  val id = ProviderId("jina")

  def build(config: EmbeddingProviderConfig): Result[EmbeddingProvider] =
    Right(JinaEmbeddingProvider.fromConfig(config))

final class JinaProviderModule extends Llm4sProviderModule:
  override def embeddingProviders: Seq[EmbeddingProviderDescriptor] = Seq(JinaEmbeddings)
```

Registration is otherwise identical to PR 3 - the same `META-INF/services` file, the same
`ServiceLoader` scan, the same escape hatches. A module declares whichever halves it has; both
default to empty.

> **If your module delegates**, forward *both* lists. Every `Llm4sProviderModule` member
> defaults to `Nil`, so a module that forwards only `chatProviders` contributes no embedding
> providers and fails silently rather than at compile time.

### Why a separate trait, not a method on `ProviderDescriptor`

The two provider sets overlap without either containing the other: OpenAI and Ollama supply a
chat client *and* an embedding provider, Voyage supplies only embeddings, Anthropic only chat.
Folding embeddings into `ProviderDescriptor` would force an embedding-only provider to implement
`buildConfig` and `buildClient` only to fail them.

The ids therefore live in **two namespaces**, and the same id can appear in both — `ollama` names
a chat client and an embedding provider that share nothing but a base URL. `ids` and
`embeddingIds` list them separately, and a provider that supplies no embeddings fails as such:

> Embedding provider 'anthropic' (from llm4s.embeddings.model) is not registered. Registered
> embedding providers: ollama, openai, voyage. If you expected 'anthropic', add the dependency
> that supplies it, or register it explicitly with ProviderRegistry.ofEmbeddings(...).

Each half names the registration call that accepts its own descriptor type - `of` for chat,
`ofEmbeddings` for embeddings - because following the other one is a compile error.

### `EmbeddingClient.from` takes the registry

```scala
def from(provider: String, cfg: EmbeddingProviderConfig)(using
  ModelRegistryService,
  ProviderRegistry
): Result[EmbeddingClient]
```

Binary-incompatible, source-compatible: existing call sites resolve `ProviderRegistry.default`
through the companion's given, exactly as the `Llm4sConfig` methods did in PR 3. An application
that registers its own passes it:

```scala
given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(JinaEmbeddings)
EmbeddingClient.from("jina", cfg)
```

`ProviderRegistry` gained `findEmbedding`, `resolveEmbedding`, `embeddingIds`,
`canonicalEmbeddingId`, `withEmbeddingProvider` and `ofEmbeddings`; `ProviderModuleReport` gained
`embeddingProviderIds`. The unknown-provider failure is still an `EmbeddingError` with code
`400`, now carrying the registry's diagnostics as its message.

### What did *not* change

`EmbeddingProvider` itself, `EmbeddingProviderConfig`, and each provider's `fromConfig` are
untouched - `OpenAIEmbeddingProvider.fromConfig(cfg)` still works and is still the direct route.
The three built-in objects simply *are* their own descriptors now.

Embedding **configuration** is not part of this change: `llm4s.embeddings` still has typed
`openai` / `voyage` / `ollama` sections in `EmbeddingsConfigLoader`, so a third-party embedding
provider is reachable through `EmbeddingClient.from` but still needs its config built by the
application. Moving config binding into the descriptor, as PR 2 did for chat, is the follow-up.

## Slice 4 (PR 3): providers are discovered on the classpath

The third slice 4 change ([#1131](https://github.com/llm4s/llm4s/issues/1131)). PR 2 made a
provider a `ProviderDescriptor` that registers itself; this removes the last manual step. A
provider module on the classpath is now found without any registration code at the call site -
adding a provider is adding a dependency.

### Declaring a provider module

Ship a `META-INF/services/org.llm4s.llmconnect.spi.Llm4sProviderModule` naming an implementation:

```
com.example.llm4s.BedrockProviderModule
```

```scala
// Must be a `class` with a public no-arg constructor, not an `object`:
// ServiceLoader instantiates the named class, and a Scala `object` exposes its
// instance as a MODULE$ field instead. (This is also what GraalVM native-image
// needs, via its ServiceLoaderFeature.)
final class BedrockProviderModule extends Llm4sProviderModule:
  override def chatProviders: Seq[ProviderDescriptor] = Seq(BedrockProvider)
```

That is the whole registration. `ProviderRegistry.default` - what every `Llm4sConfig` and
`LLMConnect` call uses when the caller supplies no registry - is now
`ProviderRegistry.discover()`, computed once on first use.

`llm4s-core` declared its own providers the same way, through
`org.llm4s.llmconnect.provider.BuiltinProviderModule`, with no special case: they were
discovered exactly as a third-party module is. (Since slice 5 core ships no provider, and that
module is gone.)

### One broken jar cannot take out the others

`java.util.ServiceLoader`'s iterator throws `ServiceConfigurationError` for an entry it cannot
load, and the `for`-comprehension you would naturally write over it propagates the first such
error and abandons every remaining provider. `discover` drives the iterator by hand and guards
each step, so an unusable entry becomes a recorded failure and the scan continues:

```scala
val registry = ProviderRegistry.discover()
registry.report.failures.foreach(f => println(f.detail))
println(registry.report.describe)
// Discovery scanned 2 modules; 1 failed: loading a provider module failed: ...
//   - org.llm4s.llmconnect.provider.BuiltinProviderModule [file:/.../llm4s-core.jar]: openai, openrouter, ...
//   ! loading a provider module failed: ... Provider com.example.Missing not found
```

Failures are logged at WARN as they happen, and the scan summary is appended to the
"provider is not registered" error, because the two failure modes that are otherwise invisible
are a dependency that was never added and a fat jar whose services files were dropped:

> Provider 'bedrock' (from llm4s.providers.my-bedrock.provider) is not registered. Registered
> providers: anthropic, azure, ... If you expected 'bedrock', add the dependency that supplies
> it, or register it explicitly with ProviderRegistry.of(...). Discovery scanned 1 module; 0 failed.

### Fat jars

Shading tools default to *overwriting* same-named resources, which silently discards every
services file but one. Configure them to concatenate:

```scala
// sbt-assembly
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
  case other                                => (assembly / assemblyMergeStrategy).value(other)
}
```

```xml
<!-- maven-shade -->
<transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
```

If you cannot, register explicitly - this is what the escape hatch is for:

```scala
val registry = ProviderRegistry.ofModules(new Llm4sOpenAIModule).withProvider(BedrockProvider)
LLMConnect.getClient(config)(using registry)
```

`ProviderRegistry.ofModules` builds a registry from the modules you name, with no classpath scan
at all. (This section first suggested `ProviderRegistry.builtin`, the providers compiled into
`llm4s-core`; it was removed when the last of them left core.)

### `Llm4sConfig` takes the registry

Every `Llm4sConfig` method that reads `llm4s.providers` now takes an implicit
`ProviderRegistry`: `provider`, `providerConfigs` (both), `providers`, `defaultProviderName`,
`defaultProvider`, `listModels` (both), and `providerFrom`. Existing call sites are unchanged -
the companion supplies `ProviderRegistry.default` - and a caller who wants a different set of
providers passes one:

```scala
given ProviderRegistry = ProviderRegistry.default.withProvider(MyProvider)
val config = Llm4sConfig.provider("my-provider")   // now resolvable
```

This is a binary-incompatible change to those signatures, and source-compatible.

### What did *not* change

`ProviderDescriptor`, `ProviderConfigSpec`, `ProviderFeatures` and `Llm4sProviderModule` are as
PR 2 shipped them. `ProviderRegistry.of`, `ofModules`, `withProvider` and `withModule` behave as
before; registries built that way report `discovered = false` and carry no scan summary.

## Slice 4 (PR 2): the provider registration SPI

The second slice 4 change ([#1131](https://github.com/llm4s/llm4s/issues/1131)). PR 1 removed the
two structures that made an out-of-module provider impossible - a closed `enum` and a `sealed`
trait. This one builds the extension point on top: a provider is now a value that describes
itself, and everything that used to enumerate providers looks them up instead.

Every provider still ships inside `llm4s-core`; what changed is that none of them is *wired in*
by hand any more. Splitting them into their own artifacts is slice 5
([#1132](https://github.com/llm4s/llm4s/issues/1132)).

### Adding a provider

Before, adding one chat provider meant editing roughly eight shared files - the closed `enum`,
the `sealed` config file, two `match` expressions in `LLMConnect`, the loader's dispatch, a
validator object, a capabilities object and the capabilities registry. That surface is why 13
open provider PRs all conflict with each other.

Now it is one file:

```scala
object BedrockProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("bedrock")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec(requiresApiKey = true, requiresEndpoint = true)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey   <- ProviderDescriptor.requireApiKey(providerName, section)
      endpoint <- ProviderDescriptor.requireField(
                    providerName, "endpoint", section.endpoint, "llm4s.providers.<name>.endpoint")
    yield BedrockConfig.fromValues(section.model.asString, apiKey, endpoint)

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[BedrockConfig](id, config)
      .flatMap(BedrockClient(_, options.metrics, options.exchangeLogging))
```

plus a `ProviderConfig` implementation, a client, and one registration:

```scala
val registry = ProviderRegistry.default.withProvider(BedrockProvider)
LLMConnect.getClient(config)(using registry)
```

Classpath discovery - registering by *adding a dependency*, with no code at all - is PR 3.
`Llm4sProviderModule` is the service type it will discover; it is already here so that a module
can group its providers today.

### What is new

All in `org.llm4s.llmconnect.spi`:

| Type | Purpose |
|---|---|
| `ProviderDescriptor` | one provider: id, aliases, config shape, features, model lister, and the two builders |
| `ProviderConfigSpec` | which section fields the provider requires, its default base URL, and the text shown when a field is missing |
| `ProviderFeatures` | what the client actually implements, declared statically (Cohere and Mistral declared `streaming = false` until slice 5 - [#925](https://github.com/llm4s/llm4s/issues/925)) |
| `ProviderRegistry` | an immutable set of descriptors; `of`, `withProvider`, `withModule`, and lookup that returns `Result` |
| `Llm4sProviderModule` | the unit of registration - one module, several providers |

`ProviderRegistry` is resolved through a `using` clause with a default given in its companion, so
existing call sites are unchanged and a caller who wants a different set passes one:

```scala
LLMConnect.getClient(config)                    // ProviderRegistry.default
LLMConnect.getClient(config)(using myRegistry)  // only the providers you registered
```

### What was deleted

All of these were `private[llm4s]`, so this costs users nothing:

| Deleted | Replaced by |
|---|---|
| `config.ProviderCapabilities` (trait + 12 objects) | `ProviderDescriptor` |
| `config.ProviderCapabilitiesRegistry` | `ProviderRegistry` |
| `config.NamedProviderValidator` (trait) and `NamedProviderValidators` (12 objects) | `ProviderConfigSpec` + one generic `NamedProviderSectionValidator` |
| the twelve-branch `match` in `NamedProviderLoader` | `descriptor.buildConfig` |
| the two `match` expressions in `LLMConnect` | `descriptor.buildClient` |
| the hard-coded `"google"`/`"vertex"` alias fold in `NamedProviderConfigNormalizer` | `ProviderDescriptor.aliases` |

Error messages for missing fields are unchanged; they are now generated from the spec rather than
written out per provider.

### Source breaks

1. **`ReliableProviders`: seven per-provider factories collapse to `wrap`.**

   ```scala
   // Before - covered 7 of 12 providers, and no provider from another module
   ReliableProviders.openai(config, ReliabilityConfig.aggressive)
   ReliableProviders.anthropic(config)

   // After - covers every registered provider
   ReliableProviders.wrap(config, ReliabilityConfig.aggressive)
   ReliableProviders.wrap(config)
   ```

   The missing five were DeepSeek, Cohere, Mistral, Requesty and Vertex AI. `wrap(client,
   providerName, ...)`, for a client you already have, is unchanged.

2. **`OpenAIConfig.providerId` is derived from `baseUrl`.** It answers `openrouter` for a URL
   containing `openrouter.ai` and `openai` otherwise - which is exactly the routing `LLMConnect`
   already did with a hard-coded check, now stated by the config itself. If you build an
   `OpenAIConfig` for OpenRouter and inspect `providerId`, the answer changed from `openai` to
   `openrouter`; client routing is unchanged.

3. **`ProviderModelLister` is now public**, along with `ProviderModelListers` and its new
   `openAICompatible(provider, defaultBaseUrl, modelsPath)` factory - a provider module needs to
   supply a model lister, and most providers serve the OpenAI `/models` shape. The five
   near-identical per-provider lister objects became calls to that factory.

4. **`ProviderResultOps` and `ProviderExchangeRecorder` are now public** (they were
   `private[provider]`). A provider client outside `llm4s-core` needs both.

### What did *not* change

`Llm4sConfig`'s signatures, `NamedProviderLoader`'s results, `DiscoveredModel`, every
`ProviderConfig` subtype's fields, and the `llm4s.providers.*` config format. A configuration
that worked before works now, including `provider = "google"` and `provider = "vertex"`.

`ProviderConfig.fromValues` still uses `require(...)`, which throws rather than returning a
`Left`. Converting it is a behaviour change (throw → `Left`) that deserves its own note, and
embeddings (`EmbeddingClient.from`, `EmbeddingsConfigLoader`'s fixed-arity reader,
`ModelDimensionRegistry`, and the duplicate `org.llm4s.rag.EmbeddingProvider` name ADT) are still
on the old dispatch. Both are tracked under #1131.

> **Since resolved.** The embedding entry points moved onto the registry in PR 4, PR 5 and the
> dimensions follow-up above; the `org.llm4s.rag.EmbeddingProvider` ADT was removed, and
> `fromValues` converted to `Result`, in the
> [slice 4 close-out](#slice-4-close-out-the-last-closed-provider-list-and-fromvalues-stops-throwing).

## Slice 4 (PR 1): `ProviderKind` becomes `ProviderId`, `ProviderConfig` opens up

The first of the slice 4 changes ([#1131](https://github.com/llm4s/llm4s/issues/1131)). No SPI
yet - this only removes the two things that make a provider impossible to supply from outside
`llm4s-core`: a closed `enum` and a `sealed` trait. It is in the build but not yet in a release,
so nothing here affects `0.4.1` or earlier.

**This is a clean break: there is no deprecated `ProviderKind` shim.** A shim would have kept a
closed list of twelve providers inside the very module whose purpose is to remove it, and it
would only have half-worked - `case ProviderKind.OpenAI =>` and `def f(k: ProviderKind)` would
still compile, while `.values`, `.ordinal`, `.fromOrdinal` and exhaustivity would not. A clear
compile error beats a partly-working deprecated type.

### `ProviderKind` → `ProviderId`

```scala
// Before
enum ProviderKind:
  case OpenAI; case Anthropic; /* ... ten more */

// After
opaque type ProviderId = String
object ProviderId:
  def apply(raw: String): ProviderId = raw.trim.toLowerCase(Locale.ROOT)  // canonicalises
  extension (id: ProviderId) def asString: String = id
```

`ProviderId` is an **open vocabulary**, not an enumeration. Any string names a provider; whether
that provider can be resolved is answered at resolution time, by whatever is on the classpath -
which is the whole point. It stays `opaque` over `String`, so `Option[ProviderId]` and
`Map[ProviderName, ProviderId]` still do not box.

| Before | After |
|---|---|
| `ProviderKind.OpenAI` | `ProviderId("openai")` |
| `ProviderKind.fromString(s)` / `fromName(s)`, returning `Option` | `ProviderId(s)`, total |
| `kind.name` | `id.asString` |
| `kind.toString` → `"OpenAI"` | `id.asString` → `"openai"` |
| `ProviderKind.all` | no replacement - ask the thing that resolves providers, not the type |
| `ProviderKind.values` / `.ordinal` / `.fromOrdinal` / `.productPrefix` | no replacement |
| exhaustive `match` on `ProviderKind` | match on `id.asString`, with a default branch |

`asString` lives in `object ProviderId` rather than beside `ModelName.asString` and friends,
because every newtype in `ProviderModelTypes` erases to `String` and a second `asString` at that
level would be a double definition after erasure. Companion-scoped extensions resolve through the
opaque type's implicit scope, so `id.asString` still needs no extra import.

**`toString` changed value.** `ProviderKind.OpenAI.toString` was `"OpenAI"`; a `ProviderId` is
its canonical lowercase spelling, so it prints `"openai"`. If you interpolated a provider into
log or error text, expect the case to change. Uppercase derivations still work:
`providerId.asString.toUpperCase` is `"OPENAI"`, as `providerKind.toString.toUpperCase` was.

### `ProviderConfig` is no longer `sealed`, and describes itself

In Scala 3 `sealed` restricts extension to the **same file**, so all ten provider configs were
stuck in one 756-line file - not merely in the same jar. `ProviderConfig` is now a plain `trait`
with three new members:

```scala
trait ProviderConfig:
  def providerId: ProviderId              // replaces `val provider: ProviderKind`
  def endpointUrl: Option[String]         // the endpoint this config will contact
  def withModel(model: String): ProviderConfig
  // model, contextWindow, reserveCompletion unchanged
```

```scala
// Before
config.provider == ProviderKind.OpenAI
// After
config.providerId == ProviderId("openai")
```

The three additions exist so that code describing a config does not have to know the set of
subtypes. Four exhaustive matches were **deleted rather than moved** by using them:
`ConfigPolicyEngine.providerName`, `ConfigPolicyEngine.baseUrlOrEndpoint`,
`PrometheusMetricsExample`'s provider-name match, and `ProviderSetupRuntime.overrideModel`. If
you have a `match` on `ProviderConfig`, that is the migration: reach for `providerId`,
`endpointUrl` or `withModel` first, and only keep the match if you genuinely need
provider-specific fields.

Losing `sealed` also means an exhaustive `match` on `ProviderConfig` now compiles with a
warning - and **fails for anyone building with `-Werror`**, as this repo does. Add a default
branch, or annotate the scrutinee `(config: @unchecked)` if you have deliberately accepted the
risk.

One behaviour change falls out of this: `ConfigPolicyEngine.baseUrlOrEndpoint` used to return
`None` for `VertexAIConfig`, because the old match had no case for it. It now returns
`Some(computedBaseUrl)`. A `requiredBaseUrlPattern` policy that silently reported "no
endpoint/baseUrl found" for Vertex AI will now actually check the URL.

### Unknown provider ids are no longer rejected while parsing

`NamedProviderConfigNormalizer` used to fail on an unrecognised `provider` string with
`"Configured provider 'x' has unknown provider 'moonbeam'"`. It now produces a `ProviderId`
unconditionally; only *resolution* fails, with an error naming what is registered:

```
No provider capabilities registered for provider 'moonbeam'.
Registered providers: anthropic, azure, cohere, deepseek, gemini, mistral, ollama,
openai, openrouter, requesty, vertexai, zai
```

This is what lets a provider live in a module `llm4s-core` has never heard of. The accepted
aliases are unchanged - `provider = "google"` still resolves to `gemini`, and
`provider = "vertex"` to `vertexai` - though that table moves into each provider's descriptor
when the SPI lands.

### Validation error text is now provider-agnostic

```
// Before
Azure OpenAI provider 'my-azure' is missing required fields:
// After
Provider 'my-azure' (provider = azure) is missing required fields:
```

The per-field guidance underneath is unchanged, including the `${?AZURE_API_KEY}` substitution
hint. (Since superseded: the `apiKey` line now names the variable the provider module binds -
`AZURE_OPENAI_API_KEY` for Azure - see
[Vendor credentials](#vendor-credentials-a-shared-api-key-per-provider).) Only the leading sentence differs, because it used to be generated from a hard-coded
display name per provider.

### Bug fix: `provider = "vertexai"` now works at all

`ProviderKind.VertexAI` existed, `NamedProviderLoader` built a `VertexAIConfig` from it, and
`LLMConnect` built a `VertexAIClient` from that - but Vertex AI was missing from
`ProviderCapabilitiesRegistry` and had no validator object. Since validation routes through that
registry, **every `provider = "vertexai"` config failed validation outright**, so none of the
supporting code was reachable from configuration. Both are now present, and the config path is
covered by tests.

### What did *not* change

`ProviderConfig.fromValues`'s `require(...)` calls still throw rather than returning `Result`;
converting them is a throw-to-`Left` behaviour change and is deferred to PR 2.
`ReliableProviders`' seven per-provider factories are also unchanged here - they collapse to a
single registry-routed `wrap` in PR 2. `NamedProviderLoader`, `NamedProviderValidator`,
`ProviderCapabilities`, `ProviderCapabilitiesRegistry` and `ProviderModelLister` are all
`private[llm4s]` or `private[config]`, so their reshaping costs users nothing.

## Slice 3: `llm4s-speech`

The last artifact of slice 3 ([#1130](https://github.com/llm4s/llm4s/issues/1130)) and the last
package out of `llm4s-core` in this slice. It is in the build but not yet in a release, so
nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.speech` (and `speech/io`, `processing`, `stt`, `tts`, `util`) | `llm4s-speech` |

**Package names did not change, and there are no source breaks.** 16 main and 21 test files
moved whole; the only code outside the package that referenced it was a sample.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you use speech-to-text or text-to-speech
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"   % version,
  "org.llm4s" %% "llm4s-speech" % version
)
```

### What `llm4s-core` sheds

**Vosk (25 MB) and JNA.** `com.alphacephei:vosk` is imported by exactly one file,
`speech/stt/VoskSpeechToText.scala`, and until now sat on the classpath of every `llm4s-core`
user, whether or not they had any use for offline speech recognition. It is the single largest
dependency the carve programme has moved.

`Deps.jna` moves with it, but it is worth being precise about why, because it is not a second
dependency: Vosk's own POM already depends on `net.java.dev.jna:jna:5.7.0`. The explicit
declaration exists to win that version conflict and pull 5.19.1 instead. Dropping it would not
remove JNA - it would silently downgrade it to a release that predates Apple Silicon support.

`llm4s-workspace-client` also declared both, and used neither; those declarations are removed
here too, so Vosk genuinely leaves the build for everyone who is not doing speech.

### The Vosk resolver is deleted, not moved

`build.sbt` carried the project's only third-party resolver:

```scala
resolvers += "Vosk Repository" at "https://alphacephei.com/maven/"
```

It resolved nothing. Vosk 0.3.45 publishes to Maven Central, which is where every build has
actually been getting it - the local Coursier cache holds `vosk-0.3.45.jar` under
`repo1.maven.org` and not a single artifact under `alphacephei.com`. What it *did* do was add a
third-party host to the lookup path for every artifact in the build, including llm4s's own
inter-module jars, which produced a steady trickle of failed requests to alphacephei.com on
every resolve.

So it is removed rather than carried into `llm4s-speech`, and **the build now has no
third-party resolvers at all**.

### Slice 3 is complete

With this, `llm4s-core` no longer contains `rag`, `knowledgegraph`, `agent/memory`, `mcp`,
`imagegeneration`, `imageprocessing` or `speech`. What remains is the agent runtime,
`llmconnect`, `toolapi`, `config`, `trace` and the provider clients, which
[slices 4 to 6](https://github.com/llm4s/llm4s/issues/1126) address.

## Slice 3: `llm4s-image`

Part of slice 3 of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); the slice is
[#1130](https://github.com/llm4s/llm4s/issues/1130). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.imagegeneration` | `llm4s-image` |
| `org.llm4s.imageprocessing` | `llm4s-image` |

**Package names did not change, and this carve adds no source breaks of its own.** The image
API's one source break - image formats becoming `org.llm4s.media.MediaType` - landed earlier,
in [`llm4s-media`](#slice-3-llm4s-media), precisely so that this step is a pure file move.

The two packages move together because they are two halves of one subsystem: generate an
image, then analyse or convert it. Both are built on the same media vocabulary, and splitting
them would leave two artifacts nobody uses apart.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you generate images, or analyse them with a vision model
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"  % version,
  "org.llm4s" %% "llm4s-image" % version
)
```

`llm4s-image` brings `llm4s-media` with it, so `MediaType` is on your classpath either way.

### What `llm4s-core` sheds

No third-party dependency: the image clients are built on `Llm4sHttpClient`, `ujson`/`upickle`
and `javax.imageio` from the JDK, all of which core keeps for other reasons. What core sheds is
**19 source files and about 3,600 lines** of a subsystem most users never touch, along with its
edge to `llm4s-media` - that edge existed only because the image packages were still inside
core, and it leaves with them.

Core's measured statement coverage rises from 74.05% to 74.89% as a result, since the image
code was below core's average.

### One test moved with the code

`org.llm4s.async.AsyncErrorHandlingSpec` lived in core's test tree under a package name that
suggests it is about asynchrony in general. Every one of its assertions exercises an image
client - it checks that `ImageProcessingClient.analyzeImageAsync` and the image generation
clients' `Future { blocking { ... } }.recover { ... }` pattern surface thrown exceptions as
`Left` rather than as a failed `Future`. It moves to `llm4s-image` with the code it tests,
keeping its package name.

Had it been left behind it would simply have stopped compiling - but the more useful point is
that leaving it would have removed the only coverage of that behaviour from the module that
owns it.

## Slice 3: `llm4s-media`

A new module rather than a carve, landed as part of slice 3
([#1130](https://github.com/llm4s/llm4s/issues/1130)) and ahead of `llm4s-image` and
`llm4s-speech`, so those two carves can be pure file moves. It is in the build but not yet in
a release, so nothing here affects `0.4.1` or earlier.

### Why

A media type - a MIME string, a canonical file extension, and whether the thing is an image,
audio, video or text - is the one piece of vocabulary every multimodal subsystem needs to
name. Because there was nowhere shared to put it, each grew its own. `llm4s-core` shipped
three overlapping enumerations of the same handful of image formats:

| Type | Cases | Members |
|---|---|---|
| `org.llm4s.imagegeneration.ImageFormat` | PNG, JPEG, WEBP | `extension`, `mimeType` |
| `org.llm4s.imageprocessing.ImageFormat` | PNG, JPEG, WEBP, GIF | `extension`, `mimeType` |
| `org.llm4s.imageprocessing.MediaType` | Jpeg, Png, Gif, WebP, Bmp, Tiff | `value` |

The first two are structurally identical and differ only in package, so a format produced by
image generation could not be handed to image processing without a hand-written conversion.
The third models the same six formats a third way, in the same package as the second. Meanwhile
`MediaExtractor` in `llm4s-rag` discriminated on raw MIME prefixes (`mimeType.startsWith
("image/")`), with no type to name the answer at all.

Carving `image` and `speech` out of core without fixing this would have frozen three copies
into three artifacts, where consolidating them later costs a cross-module source break rather
than an in-module one.

### What `llm4s-media` is

Vocabulary only - no I/O, no content sniffing, no third-party dependencies at all:

- `org.llm4s.media.MediaType` - `mimeType`, `extension`, `category`, plus `fromExtension`,
  `fromPath` and `fromMimeType` lookups. Sealed; refines into `ImageMediaType` and
  `AudioMediaType` so an image API can require an image without re-enumerating the cases.
- `org.llm4s.media.MediaCategory` - `Image`, `Audio`, `Video`, `Text`, `Application`, with
  `fromMimeType`.

Deciding what a file actually *is* from its bytes needs Tika and stays in `llm4s-rag`; that
code produces a MIME string and resolves it here. That separation is what lets every consumer
depend on `llm4s-media` without inheriting anything.

### Source breaks

**This is a source break, taken deliberately ahead of the 1.0 API freeze.** All three types
above are replaced by `org.llm4s.media.MediaType`.

| Before | After |
|---|---|
| `org.llm4s.imagegeneration.ImageFormat` | `org.llm4s.media.ImageMediaType` |
| `org.llm4s.imageprocessing.ImageFormat` | `org.llm4s.media.ImageMediaType` |
| `org.llm4s.imageprocessing.MediaType` | `org.llm4s.media.MediaType` |
| `ImageFormat.PNG` | `MediaType.Png` |
| `ImageFormat.JPEG` | `MediaType.Jpeg` |
| `ImageFormat.WEBP` | `MediaType.WebP` |
| `ImageFormat.GIF` | `MediaType.Gif` |
| `MediaType.Jpeg.value` | `MediaType.Jpeg.mimeType` |

```scala
// Before
import org.llm4s.imagegeneration.ImageFormat
val opts = ImageGenerationOptions(format = ImageFormat.PNG)

// After
import org.llm4s.media.MediaType
val opts = ImageGenerationOptions(format = MediaType.Png)
```

Two lookups changed shape as well. `org.llm4s.imageprocessing.MediaType.fromExtension` and
`.fromPath` were total, silently returning JPEG for anything they did not recognise - so a
`.txt` file reported as an image and the caller could not tell. The replacements return
`Option`, and callers that genuinely want the old fallback ask for it:

```scala
// Before
val mt = MediaType.fromPath(path)                                   // JPEG if unrecognised

// After
val mt = MediaType.imageFromPath(path).getOrElse(MediaType.Jpeg)    // fallback is now visible
```

`AnthropicVisionClient.detectMediaType` keeps the old behaviour and its old signature shape -
it still answers JPEG for an unrecognised extension, because that is what the Anthropic API
assumes for an unlabelled image - but now returns an `ImageMediaType`.

### Adding the dependency

Nothing to add today: `llm4s-media` arrives as a transitive dependency of `llm4s-core` (via
the image packages, which are still in core) and of `llm4s-rag`. Declare it directly only if
you name `MediaType` or `MediaCategory` in your own signatures.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-media" % version
```

## Slice 3: `llm4s-mcp`

Third of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 3 is
[#1130](https://github.com/llm4s/llm4s/issues/1130), which carves three independent
subsystems - `mcp`, `image` and `speech` - one artifact at a time. This note covers `mcp`;
the other two follow. It is in the build but not yet in a release, so nothing here affects
`0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.mcp` | `llm4s-mcp` |

**Package names did not change, and there are no source breaks.** Nothing outside
`org.llm4s.mcp` referenced it, so the whole package moved with no facade left behind.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After - only if you use the Model Context Protocol client, server or tool registry
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core" % version,
  "org.llm4s" %% "llm4s-mcp"  % version
)
```

### What `llm4s-core` sheds

**Java-WebSocket.** Worth being precise about why, because it is not what
[#1130](https://github.com/llm4s/llm4s/issues/1130) predicted: MCP does not use WebSockets at
all. Its transports are stdio, HTTP and SSE, built on `Llm4sHttpClient` and
`com.sun.net.httpserver`. `Deps.websocket` was declared on `llm4s-core` and imported by
nothing in it - the only WebSocket code in the repo is `ContainerisedWorkspace` in
`llm4s-workspace-client`, which declares the dependency itself. So core sheds it by dropping a
declaration that was never used, and the dependency does not follow `mcp` anywhere.

If you depend on `llm4s-core` and were picking up `org.java-websocket` transitively, declare
it yourself.

### Configuration keys

None. `org.llm4s.mcp` reads no `reference.conf` keys and no `Llm4sConfig` method names a type
that moved.

---

## Slice 2: `llm4s-memory` and `llm4s-memory-postgres`

Second of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 2 is
[#1129](https://github.com/llm4s/llm4s/issues/1129). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.agent.memory`, except `PostgresMemoryStore` | `llm4s-memory` |
| `org.llm4s.agent.memory.PostgresMemoryStore` | `llm4s-memory-postgres` |

**Package names did not change, and there are no source breaks in this slice.** Nothing
outside `org.llm4s.agent.memory` referenced it, so the whole package moved with no facade left
behind. Add the dependency; your imports stay as they are.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After — only if you use agent memory
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"   % version,
  "org.llm4s" %% "llm4s-memory" % version
)

// ...and only if you store memories in Postgres/pgvector
libraryDependencies += "org.llm4s" %% "llm4s-memory-postgres" % version
```

### Why two artifacts

`PostgresMemoryStore` was the only file in the package that needed a connection pool and a
server-side driver. Shipping it alongside `InMemoryStore` would mean every user of agent
memory inherits HikariCP and the Postgres JDBC driver whether or not they ever open a
connection. `llm4s-memory` carries sqlite-jdbc — for the file-backed `SQLiteMemoryStore` and
`VectorMemoryStore` — and nothing else; `llm4s-memory-postgres` depends on `llm4s-memory` and
adds the two heavy dependencies.

### What `llm4s-core` sheds

**HikariCP and the Postgres JDBC driver** leave the core classpath. sqlite-jdbc leaves too:
all three used to be declared in the build's shared settings, which put them on *every*
module's classpath, so core could not shed them by itself. They are now declared only by the
modules that open a connection. If you depend on `llm4s-core` and use any of the three
directly, declare them yourself rather than relying on the transitive edge.

### `org.llm4s.vectorstore.PostgresVectorHelpers`

Unchanged for callers — same package, same object, same methods — but worth knowing where it
ships. It is the pgvector text codec (`[0.1,0.2,0.3]` ⇄ `Array[Float]`), it names no JDBC
type, and it now has consumers in two modules that do not and should not depend on each
other: `PgVectorStore` in `llm4s-rag` and `PostgresMemoryStore` in `llm4s-memory-postgres`.
Rather than have either reach for the other, the single copy lives in `llm4s-core`, which both
already depend on. Slice 1 had briefly moved it into `llm4s-rag` and left a private duplicate
in core for `PostgresMemoryStore`; that duplicate is now gone.

So `org.llm4s.vectorstore` is split across two jars: this one object in `llm4s-core`, the rest
in `llm4s-rag`. It resolves the same way on any ordinary classpath.

### Configuration keys

None. `agent/memory` reads no `reference.conf` keys and no `Llm4sConfig` method returns a type
that moved, so there is nothing to migrate.

---

## Slice 1: `llm4s-rag` and `llm4s-knowledgegraph`

First of the module carves tracked in
[#1126](https://github.com/llm4s/llm4s/issues/1126); slice 1 is
[#1128](https://github.com/llm4s/llm4s/issues/1128). It is in the build but not yet in a
release, so nothing here affects `0.4.1` or earlier.

### What moved

| Packages | New module |
|---|---|
| `org.llm4s.rag`, `org.llm4s.vectorstore`, `org.llm4s.chunking`, `org.llm4s.reranker`, `org.llm4s.eval`, `org.llm4s.extract`, `org.llm4s.knowledgegraph.graphrag` | `llm4s-rag` |
| `org.llm4s.knowledgegraph` (everything except `graphrag`) | `llm4s-knowledgegraph` |

**Package names did not change**, with the one deliberate exception described under [Source
breaks](#source-breaks) below. Add the dependency; your imports stay as they are.

```scala
// Before
libraryDependencies += "org.llm4s" %% "llm4s-core" % version

// After — only if you use RAG, vector stores, chunking, reranking or the knowledge graph
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core" % version,
  "org.llm4s" %% "llm4s-rag"  % version   // depends on llm4s-knowledgegraph transitively
)
```

`llm4s-rag` depends on `llm4s-knowledgegraph`, so depending on the graph alone is only
worth doing if you want the graph without RAG.

`llm4s-knowledgegraph-neo4j` — a separate, already-published artifact — now depends on
`llm4s-knowledgegraph` instead of `llm4s-core`, which resolves for you.

### What `llm4s-core` sheds

Six dependencies leave the core classpath: **Tika, POI, PDFBox, jsoup, AWS S3 and AWS STS**.
If you depend on `llm4s-core` and use any of those directly, declare them yourself rather
than relying on the transitive edge.

### Source breaks

Three, all of them in this slice on purpose — pre-1.0 is when a duplicate is cheapest to
remove.

**1. The two document extractors are now one.** `org.llm4s.rag.extract.DocumentExtractor`
and `org.llm4s.llmconnect.extractors.UniversalExtractor` were independent implementations of
one job: two Tika instances, two sets of MIME constants, two PDFBox paths, two POI paths.
They are now `org.llm4s.extract`.

| Before | After |
|---|---|
| `org.llm4s.rag.extract.DocumentExtractor` | `org.llm4s.extract.DocumentExtractor` |
| `org.llm4s.rag.extract.DefaultDocumentExtractor` | `org.llm4s.extract.TikaDocumentExtractor` |
| `UniversalExtractor.extract(path)` → `Either[ExtractorError, String]` | `TikaDocumentExtractor.extractFromPath(path)` → `Result[ExtractedDocument]` (text in `.text`) |
| `UniversalExtractor.extractFromBytes(bytes, name, mime)` | `TikaDocumentExtractor.extract(bytes, name, mime)` |
| `UniversalExtractor.extractFromStream(in, name, mime)` | `TikaDocumentExtractor.extractFromStream(in, name, mime)` (returns `ExtractedDocument`) |
| `UniversalExtractor.isTextLike(mime)` | `TikaDocumentExtractor.canExtract(mime)` — also true for legacy `.doc` |
| `UniversalExtractor.detectMimeType(bytes, name)` | unchanged |
| `UniversalExtractor.extractAny(path)` and its `Extracted` / `TextContent` / `ImageContent` / `AudioContent` / `VideoContent` ADT | `org.llm4s.extract.MediaExtractor` |
| `org.llm4s.llmconnect.model.ExtractorError` | `org.llm4s.error.ProcessingError` |

The package is `org.llm4s.extract`, not `org.llm4s.rag.extract`: extraction has two real
consumers — RAG document loading and multimodal embedding — and it quarantines the three
heaviest dependencies in the build. Naming it outside the `rag` namespace makes any later
decision to give it its own artifact a build-file change rather than a code change.

**2. `EmbeddingClient.encodePath` is now `FileEmbedder.encodeFromPath`.** `EmbeddingClient`
keeps the pure vector API; file reading, MIME sniffing and chunking live in
`org.llm4s.rag.embed`. The six-parameter signature — which included an
`experimentalStubsEnabled: Boolean`, a deployment decision arriving at a call site — became
a config object.

```scala
// Before
client.encodePath(path, textModel, chunkingCfg, stubsEnabled, localModels)

// After
import org.llm4s.rag.embed.{ FileEmbedder, FileEmbeddingConfig, TextChunkingConfig }

FileEmbedder.encodeFromPath(
  path,
  client,
  FileEmbeddingConfig(
    textModel = textModel,
    localModels = localModels,
    chunking = TextChunkingConfig(enabled = true, size = 1000, overlap = 100),
    experimentalStubs = stubsEnabled
  )
)
```

`UniversalEncoder.TextChunkingConfig` is now the top-level `org.llm4s.rag.embed.TextChunkingConfig`.

**3. `Llm4sConfig.pgSearchIndex()` is now `PgSearchIndexConfigLoader.default()`.** It
returned a `SearchIndex.PgConfig`, which is RAG's type; `Llm4sConfig` stays in `llm4s-core`
and cannot name it. The loader itself keeps its package (`org.llm4s.config`) and its
`load(source)` method, and moves to `llm4s-rag`.

```scala
// Before
val pg = Llm4sConfig.pgSearchIndex()

// After
import org.llm4s.config.PgSearchIndexConfigLoader
val pg = PgSearchIndexConfigLoader.default()
```

### Configuration keys

`llm4s.rag.permissions.pg.*` and `llm4s.rerank.*` now ship in `llm4s-rag`'s `reference.conf`
rather than core's. HOCON merges `reference.conf` across jars, so the key paths are
unchanged and nothing in your `application.conf` needs editing — but a build that reads
those keys without depending on `llm4s-rag` no longer gets the defaults.

`llm4s.embeddings.*` — including `chunking` and `experimentalStubs` — stays in core, because
`Llm4sConfig` still reads it there.

---

## Artifact coordinate rename (v0.4.0)

### Breaking change

Every **published** artifact under the `org.llm4s` group was renamed to carry an `llm4s-`
prefix and a consistent kebab-case suffix. This is a **coordinate-only** change: there are
no API changes, no package moves and no source changes in this release. Update your
`build.sbt`, recompile, and you are done.

| Old coordinate | New coordinate |
|---|---|
| `"org.llm4s" %% "core"` | `"org.llm4s" %% "llm4s-core"` |
| `"org.llm4s" %% "workspaceShared"` (published as `workspaceshared`) | `"org.llm4s" %% "llm4s-workspace-shared"` |
| `"org.llm4s" %% "workspaceClient"` (published as `workspaceclient`) | `"org.llm4s" %% "llm4s-workspace-client"` |
| `"org.llm4s" %% "trace-opentelemetry"` | `"org.llm4s" %% "llm4s-observability-otel"` |
| `"org.llm4s" %% "knowledgegraph-neo4j"` | `"org.llm4s" %% "llm4s-knowledgegraph-neo4j"` |

Maven users: the `artifactId` gains the same prefix, so `core_3` becomes `llm4s-core_3`
(and `core_2.13` becomes `llm4s-core_2.13`).

### Why

Two reasons:

1. **Consistency.** `org.llm4s:core` is a poor coordinate to read in somebody else's build
   file, and the module names were an inconsistent mix of camelCase (silently lowercased by
   the publish into `workspaceclient`) and kebab-case.
2. **Escaping a bad publish.** The `core_3` / `core_2.13` artifacts carry an accidental
   mis-published version `2.1.593` (a typo). Maven Central publishes are immutable, so that
   version cannot be retracted, and some resolvers sort it as the "latest" release. A fresh
   artifact name is the only way out; documentation is not.

### Nobody is stranded

Releases up to and including **0.3.4** remain published, unchanged and resolvable under the
old coordinates. Pinning `"org.llm4s" %% "core" % "0.3.4"` keeps working indefinitely — you
only need to change coordinates when you move to 0.4.0 or later.

If you are pinning `core` with a floating or range version, pin an explicit `0.3.4` before
upgrading, so the phantom `2.1.593` is never selected.

### Migration steps

1. Replace the old coordinate with the new one in your `build.sbt` (see the table above).
2. Set the version to `0.4.0` or later.
3. Recompile. No imports, types or method signatures changed.

```scala
// Before
libraryDependencies += "org.llm4s" %% "core" % "0.3.4"

// After
libraryDependencies += "org.llm4s" %% "llm4s-core" % "0.4.0"
```

### Note on `"org.llm4s" %% "llm4s"`

The aggregate `llm4s` artifact (`llm4s_3` / `llm4s_2.13`) is **not** published and has not
been since 0.2.9 — the root project sets `publish / skip := true`. Any build file or
documentation that depends on `"org.llm4s" %% "llm4s"` is wrong independently of this
rename and should be changed to `"org.llm4s" %% "llm4s-core"`.

### Note on `llm4s-observability-otel`

The OpenTelemetry integration is published as `llm4s-observability-otel` rather than
`llm4s-trace-opentelemetry`. The name anticipates the `llm4s-observability` module that the
modularisation work will carve out of `trace` + `metrics`, so the integration is named once
rather than twice.

### Unpublished modules

`samples`, `workspaceRunner`, `workspaceSamples`, `config-policy`, `it` and `benchmarks` set
`publish / skip := true` and never reached Maven Central. Their `name` values were made
consistent in the same change, but this has no effect on any downstream build.

---

## MessageRole Enum Changes (v0.2.0)

### Breaking Change
The `MessageRole` has been converted from string-based constants to a proper enum type for better type safety.

### Before (v0.1.x)
```scala
import org.llm4s.llmconnect.model.Message

val message = Message(role = "assistant", content = "Hello")
message.role match {
  case "assistant" => // handle assistant
  case "user" => // handle user
  case _ => // handle other
}
```

### After (v0.2.0)
```scala
import org.llm4s.llmconnect.model.{Message, MessageRole}

val message = AssistantMessage(content = "Hello")
// or
val message = Message(role = MessageRole.Assistant, content = "Hello")

message.role match {
  case MessageRole.Assistant => // handle assistant
  case MessageRole.User => // handle user
  case MessageRole.System => // handle system
  case MessageRole.Tool => // handle tool
}
```

### Migration Steps

1. **Update imports**: Add `MessageRole` to your imports
   ```scala
   import org.llm4s.llmconnect.model.MessageRole
   ```

2. **Replace string comparisons**: Update pattern matches and comparisons
   ```scala
   // Before
   if (message.role == "assistant") { ... }
   
   // After
   if (message.role == MessageRole.Assistant) { ... }
   ```

3. **Update message creation**: Use the typed constructors
   ```scala
   // Before
   Message(role = "user", content = "Hello")
   
   // After
   UserMessage(content = "Hello")
   // or
   Message(role = MessageRole.User, content = "Hello")
   ```

## Error Hierarchy Changes (v0.2.0)

### New Error Categorization
Errors are now categorized using traits for better type safety and recovery strategies.

### Before (v0.1.x)
```scala
error match {
  case e: LLMError if e.isRecoverable => // retry logic
  case e: LLMError => // handle non-recoverable
}
```

### After (v0.2.0)
```scala
error match {
  case e: RecoverableError => // retry logic
  case e: NonRecoverableError => // handle non-recoverable
}
```

### Error Recovery Pattern
```scala
import org.llm4s.error._

def handleError(error: LLMError): Unit = error match {
  case _: RateLimitError => // wait and retry
  case _: TimeoutError => // retry with backoff
  case _: ServiceError with RecoverableError => // retry
  case _: AuthenticationError => // refresh token or fail
  case _: ValidationError => // fix input and retry
  case _ => // non-recoverable, fail
}
```

### Migration Steps

1. **Replace `isRecoverable` checks**: Use pattern matching on traits
   ```scala
   // Before
   if (error.isRecoverable) { ... }
   
   // After
   error match {
     case _: RecoverableError => { ... }
     case _ => { ... }
   }
   ```

2. **Update error handling**: Use the new trait-based categorization
   ```scala
   // Before
   case e: ServiceError if e.isRecoverable =>
   
   // After
   case e: ServiceError with RecoverableError =>
   ```

3. **Use smart constructors**: Create errors using the companion object methods
   ```scala
   // Before
   new RateLimitError(429, "Rate limit exceeded", Some(60.seconds))
   
   // After
   RateLimitError(429, "Rate limit exceeded", Some(60.seconds))
   ```

## Configuration Changes (v0.2.0+)

> `Llm4sConfig.provider()` with no argument, used below, has since become
> `Llm4sConfig.defaultProvider()`, and providers are configured as named sections - see
> [From `LLM_MODEL` to named provider sections](#from-llm_model-to-named-provider-sections).

### EnvLoader and legacy ConfigReader → Llm4sConfig

Older versions used `EnvLoader` and a custom `ConfigReader` abstraction. These have been superseded by `Llm4sConfig` (PureConfig‑based) and typed helpers.

### Before (v0.1.x)
```scala
import org.llm4s.config.EnvLoader

val apiKey = EnvLoader.get("OPENAI_API_KEY")
val model  = EnvLoader.getOrElse("LLM_MODEL", "gpt-4")
```

or:

```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  ConfigReader.Provider().flatMap(LLMConnect.getClient)
```

### After (post‑0.2.0)

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
```

### Typed Config: recommended patterns

- Tracing (typed):
  ```scala
  import org.llm4s.config.Llm4sConfig
  import org.llm4s.trace.{ Tracing, EnhancedTracing, TracingMode }

  val tracerResult: org.llm4s.types.Result[Tracing] =
    Llm4sConfig.tracing().map(Tracing.create)
  ```

- Provider model for display (typed):
  ```scala
  val modelNameResult = Llm4sConfig.provider().map(_.model)
  // Prefer completion.model after the API call when available
  ```

- Workspace (samples):
  ```scala
  import org.llm4s.codegen.WorkspaceConfigSupport

  val ws = WorkspaceConfigSupport.load().getOrElse(
    throw new IllegalArgumentException("Failed to load workspace settings")
  )
  ```

- Embeddings (samples):
  ```scala
  val ui      = org.llm4s.samples.embeddingsupport.EmbeddingUiSettings.loadFromEnv()
    .getOrElse(throw new IllegalArgumentException("Failed to load UI settings"))
  val targets = org.llm4s.samples.embeddingsupport.EmbeddingTargets.loadFromEnv()
    .fold(err => throw new IllegalArgumentException(err.toString), _.targets)
  val query   = org.llm4s.samples.embeddingsupport.EmbeddingQuery.loadFromEnv()
    .fold(_ => None, _.value)
  ```

## Configuration: legacy reader → `Llm4sConfig` / typed helpers (post‑0.2.0)

Earlier versions used a custom `ConfigReader`-style abstraction as a catch‑all for configuration. With PureConfig in place and typed helpers available, the preferred path is now:

- Use `org.llm4s.config.Llm4sConfig` in core code.
- Use explicit typed loaders plus `LLMConnect.getClient` in application/sample code.

### Provider configuration and client creation

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  ConfigReader.Provider().flatMap(LLMConnect.getClient)
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

// Typed path using Llm4sConfig
val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
```

### Tracing configuration

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.trace.Tracing

val tracer: Tracing =
  ConfigReader.TracingConf().map(Tracing.create).getOrElse(Tracing.noop)
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.trace.Tracing

val tracer: org.llm4s.types.Result[Tracing] =
  Llm4sConfig.tracing().map(Tracing.create)
```

### Embeddings: provider and client

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.EmbeddingClient

val client: org.llm4s.types.Result[EmbeddingClient] =
  ConfigReader.Embeddings().flatMap { case (provider, cfg) =>
    EmbeddingClient.from(provider, cfg)
  }
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient

val client: org.llm4s.types.Result[EmbeddingClient] =
  Llm4sConfig.embeddings().flatMap { case (provider, cfg) =>
    EmbeddingClient.from(provider, cfg)
  }
```

### Workspace settings

**Before**
```scala
import org.llm4s.codegen.WorkspaceSettings

val ws = WorkspaceSettings.load().getOrElse(
  throw new IllegalArgumentException("Failed to load workspace settings")
)
```

**After**
```scala
import org.llm4s.codegen.WorkspaceConfigSupport

val ws = WorkspaceConfigSupport.load().getOrElse(
  throw new IllegalArgumentException("Failed to load workspace settings")
)
```

### API keys and types

**Before (legacy reader-based API)**
```scala
// Legacy pattern: API key resolved from a generic config reader
def loadApiKey(reader: /* legacy ConfigReader */ Any): Result[ApiKey] =
  ApiKey.unsafe("sk-legacy-key") // placeholder for old behavior
```

**After**
```scala
import org.llm4s.config.Llm4sConfig

val cfgResult = Llm4sConfig.provider() // Result[ProviderConfig]
```

- For **new code**, do not introduce new parameters of reader/ConfigReader types. Prefer:
  - `Llm4sConfig` in core libraries.
  - Typed helpers plus `LLMConnect.getClient` (and `Llm4sConfig.tracing().map(Tracing.create)` / `.map(EnhancedTracing.create)` for tracing) in applications and samples.
- For **existing code** that currently depends on a `ConfigReader`-style abstraction:
  - Start by swapping call sites to use typed helpers (e.g., `Llm4sConfig.provider()`).
  - Where you need fine-grained control, switch to `Llm4sConfig` functions instead of calling the legacy reader directly.
