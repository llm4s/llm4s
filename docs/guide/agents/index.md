---
layout: page
title: Agents
nav_order: 12
parent: User Guide
has_children: true
---

# Agent Framework
{: .no_toc }

Build sophisticated AI agents with tools, guardrails, memory, and multi-agent coordination.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The LLM4S Agent Framework provides a production-ready foundation for building LLM-powered agents with:

- **Tool Calling** - Type-safe tools with automatic schema generation
- **Guardrails** - Input/output validation for safety and quality
- **Memory** - Short and long-term context with semantic search
- **Handoffs** - Agent-to-agent delegation for specialist routing
- **Streaming** - Real-time events for responsive UIs (`agent.stream`, [streaming guide](streaming))
- **Graphs** - Typed multi-agent workflows on `GraphBuilder`: parallel nodes, joins and checkpoints ([recipe](../../examples/cookbook/multi-agent-graph.html))

## Quick Start

The agent runtime is the `llm4s-agent` module, alongside `llm4s-core` and a provider module:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-agent" % llm4sVersion
```

### Basic Agent

An agent is built once - tools, guardrails, handoffs and middleware belong to it - and run by thread.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.agent.Agent

// Create an agent and run a query
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent <- Agent.builder("assistant", client).build()
  result <- agent.run("What is the capital of France?")
} yield result

result match {
  case Right(r) => println(r.answer.getOrElse(s"Run ended: ${r.status}"))
  case Left(error) => println(s"Error: $error")
}
```

`Agent.builder(id, client)` takes the agent's id (letters, digits, `_` and `-`, up to 52) and an
`LLMClient`. `build()` returns a `Result[Agent]` and refuses an invalid agent: clashing tool names,
a bad or duplicate handoff id, or `withMaxSteps` below 1.

### Agent with Tools

```scala
import org.llm4s.toolapi.{ToolRegistry, ToolFunction}

// Define a tool
def getWeather(location: String): String = {
  s"The weather in $location is sunny, 72F"
}

val weatherTool = ToolFunction(
  name = "get_weather",
  description = "Get current weather for a location",
  function = getWeather _
)

// Give the agent its tools when you build it
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent <- Agent.builder("assistant", client)
    .withTools(new ToolRegistry(Seq(weatherTool)))
    .build()
  result <- agent.run("What's the weather in Paris?")
} yield result
```

`withTools` takes a `ToolRegistry` (an `MCPToolRegistry` too) or a `ToolSet` of `AgentTool`s.

### Multi-Turn Conversations

A conversation is carried by its thread. `continueConversation` runs the next turn on the thread
of a previous result:

```scala
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  agent <- Agent.builder("assistant", client).build()

  // First turn
  result1 <- agent.run("Tell me about Scala")

  // Follow-up (preserves context)
  result2 <- agent.continueConversation(result1, "How does it compare to Java?")

  // Another follow-up
  result3 <- agent.continueConversation(result2, "What about performance?")
} yield result3
```

To name the thread yourself, use `agent.run(threadId, query)`; `agent.runMultiTurn(first, followUps)`
runs several turns on one thread and stops at the first result that is not `Completed`. A `Left` carries no
thread id, so when a turn of `run(query)` or `runMultiTurn` fails or is cancelled, its thread is forgotten once
the turn has ended; name the thread to recover such a turn.

### Handling the Result

`run` returns `Result[AgentResult]`. `Left` is a failure: a provider, tool or middleware error is a
`GraphError`, and a thread that is busy or still has pending work is refused. A run that ended in a
defined way is `Right`, with a status:

```scala
result.map { r =>
  r.status match {
    case AgentStatus.Completed(answer)         => println(answer)
    case AgentStatus.Blocked(guardrail, why)   => println(s"Blocked by $guardrail: $why")
    case AgentStatus.StepLimitReached          => println("Hit the step limit")
    case AgentStatus.TokenLimitReached         => println("Hit the run's token budget")
    case AgentStatus.Suspended(approvals, qs, asked, held) => println("Waiting for a person")
  }
}
```

`AgentResult` also carries `threadId`, `runId`, `activeAgent`, `messages` (the thread's full
history, never a system prompt) and `usage`.

### A Typed Answer

`withOutput(schema)` gives the agent a response format: a `SchemaDefinition[O]`, with a uPickle
`ReadWriter[O]` in scope. Write the schema by hand, or derive it with `llm4s-schema-derivation`:
`withOutput(SchemaOf[Invoice].definition)`. The system prompt states the schema on every model call,
and a call that offers no tools also carries it as its `responseFormat`. A call that offers tools carries
no format, because several providers cannot combine one with tool calls. `decodeOutput[O]` reads the answer:

```scala
case class Invoice(customer: String, total: Double) derives ReadWriter
val invoiceSchema: ObjectSchema[Invoice] = ??? // or SchemaOf[Invoice].definition

for {
  agent   <- Agent.builder("extractor", client).withOutput(invoiceSchema).build()
  result  <- agent.run("Bill Acme 12.50")
  invoice <- result.decodeOutput[Invoice]
} yield invoice
```

A final answer must be JSON that matches the schema and reads as `O`; it is stored as that JSON alone.
An invalid answer is sent back to the model once, with its problems, in the same step; neither is stored.
A second invalid answer ends the turn with `Left(GraphError.OutputInvalid(errors))`: the turn is removed
from the thread, as an output guardrail's block removes it, both calls stay in `usage`, `recover` has
nothing to continue, and the next turn runs from before it. The format is the agent's own: a handoff
target answers in its own format, or freely.

---

## Safety Defaults

- **Agent step limit**: an agent makes at most `maxSteps` model calls per turn, `Agent.DefaultMaxSteps` (50) unless you call `withMaxSteps(n)`. The count is shared by every agent a turn hands off to; at the limit the result is `AgentStatus.StepLimitReached`. The limit means the same through `run`, `start`, `stream`, `continueConversation` and `runMultiTurn`: each turn gets its own `maxSteps` model calls.
- **Run token budget**: off by default. `RunConfig().withBudgets(RunBudgets.default.withMaxTokens(n))` bounds the input and output tokens one run (`run`, `recover` or `resume`) may use, counted from the thread's usage when the run started. Before each model call - the loop's own, and every call a middleware makes through `next` or `ModelContext.accounted` - the run's tokens are compared with it, and at the budget the turn ends `AgentStatus.TokenLimitReached` without calling the model. A call in flight is never interrupted, so a run can overshoot the budget by one model call. A completion that reports no usage is charged an estimate of the whole request (messages, tool definitions and response schema) and the whole answer, never zero. A retrying client (`ReliableClient`) is one call: its failed attempts carry no usage and are not counted, so set `maxTokens` with retries in mind. Each run brings its own budget, like its `timeout`; a task reads the run's deadline as `RunContext.deadline`.
- **HTTPTool methods**: `HttpConfig()` defaults to `GET` and `HEAD` only. Use `HttpConfig.withWriteMethods()` or `HttpConfig().withAllMethods` to allow write methods.

---

## Core Concepts

### Threads and Results

A conversation lives in a thread on a `GraphRuntime` (in memory unless you call `withRuntime`). The
thread holds the messages, the active agent and the usage; it holds no tools, guardrails or agents,
which belong to the `Agent`. `AgentResult` is a value to read, not something to pass back in.

- `agent.start(threadId, query)` returns an `AgentRun` at once, with `await()` and `cancel()`;
  `run` is `start` followed by `await`.
- A run that failed or was cancelled leaves its thread recoverable: `agent.recover(threadId)`
  re-runs only the work that did not finish.
- A run that suspended for a person (`AgentStatus.Suspended`) - an approval, a tool's or a middleware's
  question, or a static breakpoint - continues with `agent.resume(threadId, answers)`, building each
  answer with `result.approve(id)`, `result.reject(id, reason)`, `result.edit(id, arguments)`,
  `result.reply(id, value)` or `result.proceed(id)`; see [Human Review](human-review).
- A thread stays in the runtime until `agent.forget(threadId)` removes it - one-shot
  `agent.run(query)` threads too, which on the default in-memory runtime means they stay in memory.
  Forget a conversation you will not continue: `agent.run(query).flatMap(r => agent.forget(r.threadId).map(_ => r))`.
  `forget` refuses a thread that a run still holds - in this runtime, or by a live claim in any runtime sharing
  the store (`ThreadBusy`) - or that belongs to another tenant (`TenantMismatch`); an unknown thread is `Right(())`.

### Suspended turns from Java and Kotlin

The Java facade (`llm4s-java-api`) returns every turn as a `JAgentResult`, read without Scala types:
`answer()` is an `Optional<String>`, `messages()` a `java.util.List<JMessage>`, `usage()` a
`JUsageSummary`, and `status()` a `JAgentStatus` whose `kind()` is the Java enum `AgentStatusKind` -
`COMPLETED`, `BLOCKED`, `STEP_LIMIT_REACHED`, `TOKEN_LIMIT_REACHED` or `SUSPENDED` - with `answer()`, `guardrail()` and
`reason()` as `Optional<String>`s (see the [Java guide](../java#an-agent-turn)). A `SUSPENDED`
status's `pending()` is a `java.util.List<PendingInterrupt>`: the turn's approvals, then its tool
questions, middleware questions and breakpoints; it is empty for any other status, and
`JAgent.pending(result)` is a shortcut for it. Each `PendingInterrupt` has `id()` and `kind()` (the Java
enum `InterruptKind`: `APPROVAL`, `QUESTION`, `MIDDLEWARE_QUESTION` or `BREAKPOINT`). The rest are
`Optional`s, present only for the kinds they belong to: `toolName()` and `argumentsJson()` when a tool
call waits (always for an approval or a tool question), `reason()` for an approval, `questionJson()` -
the question as JSON - for a tool's or a middleware's question, `middleware()` for a middleware
question, and `node()` and `phase()` (the Java enum `BreakpointPhase`) for a breakpoint. Answer each
pending item with `Answer.approve(id)`, `reject(id, reason)`, `edit(id, argumentsJson)`,
`reply(id, json)` or `proceed(id)`, then call `agent.resume(threadId, answers)`, which blocks like `run`
and returns an `LlmResult<JAgentResult>`.
You can answer only some of them: the result is `SUSPENDED` again, with the rest still pending.

```java
LlmResult<JAgentResult> turn = agent.run("Deploy the release");
while (turn.get().status().kind() == AgentStatusKind.SUSPENDED) {
    List<Answer> answers = new ArrayList<>();
    for (PendingInterrupt p : turn.get().status().pending()) {
        switch (p.kind()) {
            case APPROVAL -> answers.add(Answer.approve(p.id()));        // or reject / edit
            case QUESTION, MIDDLEWARE_QUESTION -> answers.add(Answer.reply(p.id(), "{\"ok\":true}"));
            case BREAKPOINT -> answers.add(Answer.proceed(p.id()));
        }
    }
    turn = agent.resume(turn.get().threadId(), answers);
}
System.out.println(turn.get().answer().orElse("(" + turn.get().status().kind() + ")"));
```

`agent.recover(threadId)` continues a turn that failed or was cancelled, and also returns an
`LlmResult<JAgentResult>`. A failed result means one of these: a malformed answer, an empty answers
list (`InvalidResume`), an answer to an id the thread is not waiting for, a thread that is not suspended (`resume`), or a thread with nothing to
recover. `resume` and `recover` handle an interrupt the way `run` and `continueConversation` do: interrupting the
thread blocked in the call cancels the turn, and the call returns a `CancelledError` with the interrupt flag still
set once the turn has ended, leaving the thread for `recover` (see
[Java Threading and Cancellation](../java-threading-and-cancellation#interrupting-the-caller-cancels-an-agent-turn)).
To cancel a turn without interrupting a thread, use `streamResume` or `streamRecover` and cancel the stream (see
[Streaming Events](streaming#java-and-kotlin)).

The Kotlin API reuses these types: every `AgentKt` turn returns a `JAgentResult`, and `when` over
`status().kind()` covers it. `AgentKt.pending(result)` is the same `List<PendingInterrupt>`.
`agent.resume(threadId, answers)` and `agent.recover(threadId)` are `suspend` functions that return
the `JAgentResult` or throw `LLMException`. Every `AgentKt` suspend function that runs a turn - `run`,
`continueConversation`, `resume` and `recover` - runs it as the matching flow does (`stream`,
`streamResume`, `streamRecover`), so cancelling the caller - a cancelled scope, `withTimeout` -
cancels the turn. The call throws `CancellationException` once the turn has ended, and the
conversation thread is no longer busy: it is left for `recover`, which finishes the cancelled turn.
If the turn had already completed when the cancellation arrived, the call still throws
`CancellationException`, but the turn's result is committed to the thread: `recover` then has nothing to
recover and throws `LLMException` ("no incomplete execution"), and the next turn continues from that result.
The one-shot `run(query)` is the exception, as in Java and Scala: nothing it throws carries its random thread
id, so once a failed or cancelled turn has ended it forgets the thread. A conversation you named for `stream` is
yours to forget: `agent.forget(threadId)` needs no result, succeeds for an unknown thread and throws
`LLMException` for one whose turn is still running. A cancelled call waits up to 5 seconds
for the turn to end; a provider that ignores the interrupt for longer is left to finish on its own, and the
thread stays busy (`ThreadBusy`) until it does.

```kotlin
var turn = agent.run("Deploy the release")
while (turn.status().kind() == AgentStatusKind.SUSPENDED) {
    val answers = turn.status().pending().map { p ->
        when (p.kind()) {
            InterruptKind.APPROVAL -> Answer.approve(p.id())
            InterruptKind.QUESTION, InterruptKind.MIDDLEWARE_QUESTION -> Answer.reply(p.id(), """{"ok":true}""")
            InterruptKind.BREAKPOINT -> Answer.proceed(p.id())
        }
    }
    turn = agent.resume(turn.threadId(), answers)
}
```

### Agent Lifecycle

```
Query
  |
  v
input node (input guardrails) ---> Blocked (nothing stored)
  |
  v
model <---------- tool results
  |
  +-- tool calls --> call-tool (parallel, bounded) --> collect --> model
  +-- handoff -----> the target agent's model
  |
  v
finish node (output guardrails) ---> Completed | Blocked (turn removed)
```

### Parallel Tool Calls

The tool calls of one model message run in parallel, bounded by `RunBudgets.maxConcurrency`,
which you set through the `RunConfig` you pass to `run`.

`ToolExecutionStrategy` remains in core as a `ToolRegistry` feature; the agent no longer takes one.

### Tool Side Effects

**Every tool call gets exactly one result.** The agent, not the tool, writes the result the model
sees, for every outcome: the tool's content on success, and an `{"error": ...}` result for a tool
error, a thrown exception, invalid arguments, an unknown tool, a denial, a rejected approval and
every call of a refused handoff batch. The model is called again only once every call of the batch
has its result, so a call waiting for approval or an answer holds the model back, and a partial
`resume` sends nothing to it. Before each model call the agent checks the request, as every
`wrapModelCall` middleware left it, and refuses one in which a tool call does not have exactly one
result straight after its message; a model message whose call ids repeat, or are blank, is refused
before it is stored, and `history` you import must keep the same rule. The OpenAI and Anthropic
clients are checked against this contract in both wire formats (`assertOneToolResultPerCall` in
`llm4s-provider-testkit`).

**A call cut short gets no result until it runs again.** When a turn is cancelled, times out, or
fails on a tool's `Fatal` or a store error mid-batch, the calls that finished keep their results
and the unfinished ones get none: the thread is left for `recover`, which runs only the unfinished
calls and never repeats a finished one. `start` refuses the thread until then (`IncompleteRun`).
Until [#1702](https://github.com/llm4s/llm4s/issues/1702) adds `updateState`, there is no way to
give an unfinished call a result of your own: a call that misbehaves every time it runs - it hangs
until cancelled, or fails with `Fatal` - leaves the thread two choices, `recover` (run it again) or
`forget` (drop the thread and its history).

**Tools run at least once, so pass the idempotency key on.** A call can run again: a retrying
`wrapToolCall` middleware, `recover` after a cancellation, failure or crash, and an approval each
run it once more, and a call cut short may already have done its work. With a store shared between
processes, a call can even run in two runs at the same time: a worker paused past its claim's lease
can still be running a tool when another runtime takes the thread over and runs the same call (see
[Fencing](durable-checkpointers#fencing-a-run-that-lost-its-claim-stops-and-records-nothing)). `ToolContext.idempotencyKey`
is the same in every run of one call, so a tool with an external side effect passes it to the
system it calls, which then does the work once:

```scala
import org.llm4s.agent.graph.tool.{AgentTool, ToolContent, ToolOutcome}

val charge = AgentTool(chargeSpec) { (args, context) =>
  payments.charge(args.amount, idempotencyKey = context.idempotencyKey.value) match
    case Right(receipt) => ToolOutcome.Success(ToolContent.Json(ujson.Str(receipt.id)))
    case Left(error)    => ToolOutcome.Error(error.message)
}
```

A plain `ToolFunction` - one built with `ToolBuilder`, a built-in tool, an MCP tool - reads the same call as
`params.call`, a `ToolCallInfo` with the call's `toolCallId` and `idempotencyKey`. It is `None` when the function runs
outside an agent, through `ToolRegistry.execute`, which has no call to describe:

```scala
import org.llm4s.toolapi.{ Schema, ToolBuilder }

val charge = ToolBuilder[Map[String, Any], String]("charge", "Charges a card", chargeSchema)
  .withHandler { params =>
    for
      amount  <- params.getDouble("amount")
      receipt <- payments.charge(amount, idempotencyKey = params.call.map(_.idempotencyKey))
    yield receipt.id
  }
  .buildSafe()
```

Two tools pass it on by themselves. `HTTPTool` adds `Idempotency-Key: <key>` to a POST, PUT, PATCH or DELETE it
sends as an agent's call, unless the call's own headers set one. An MCP tool sends it to its server in the
`tools/call` request's `_meta`, as `llm4s.org/idempotencyKey` (with `llm4s.org/toolCallId`), never among the
arguments; an llm4s `MCPServer` hands it to its tool as `params.call`, and another server can read it there or
ignore it. Tools defined from Java (`llm4s-java-api`) and Kotlin (`llm4s-kotlin-api`) do not see it yet
([#1740](https://github.com/llm4s/llm4s/issues/1740)).

The key is 64 hexadecimal characters, derived from the thread, where on it the model call that
issued the tool call ran (its checkpoint, superstep and task), and the call's id, and recorded
with the call. A call of a later model request gets a new key even when the provider reuses its
call id (`call_0` in every turn) and the turns share one `RunConfig`, and so does a call from a
model request made again because the first response was never stored. The boundaries of the guarantee:

- The key de-duplicates only where the external system honours it. Without one, a side effect can
  happen more than once; the runtime never promises exactly-once execution.
- Only an `AgentTool` sees the key. A tool added through `ToolRegistry` or
  `AgentTool.fromToolFunction` - the built-in tools, MCP tools, and tools written with the Java or
  Kotlin API - runs at least once like any other, but its `ToolFunction` handler has no
  `ToolContext` to read the key from
  ([#1740](https://github.com/llm4s/llm4s/issues/1740)). Write a tool with a side effect that must
  not repeat as an `AgentTool`.
- An approved or edited call keeps the key it was proposed with, and a call that asked a question
  keeps it when it resumes. Make a tool's first run, before approval or an answer, free of side
  effects.
- The agent stores each call's result as soon as the call finishes (the runtime's `Sync`
  durability), and a stored result is never run again. A process that dies between a tool's side
  effect and that store runs the call again on `recover`, with the same key. A graph run under
  `Async` or `OnExit` durability can lose more after its last durable commit: finished calls run
  again with their keys, and a lost model response is asked for again, so its calls get new keys.
- A stale worker and its successor send the same key for a call both run, so a key-honouring system
  does the work once. A system that can fence writers can also refuse the stale one outright:
  `context.run.position.fencingToken` is the run's claim token, which grows with every claim on the
  thread, so the system keeps the highest token it has seen and refuses a lower one.

**Guarded effects: replay without repeating.** For a target that does not take an idempotency key,
such as a file, a sandbox or a store, perform the effect through `ReplayGuard`. The guard records an
intent before the effect and its outcome after, in the thread's own store, under the run's claim. A
call that runs again then gets its original outcome back instead of repeating the work. The target
declares one `ReplaySupport`, and each has its own guard:

- `ReplayGuard.transactional` is for a target that records the key with the outcome in the same
  transaction as the effect, and implements `AppliedKeys`. The effect and `forget` are given the
  run's claim token, and the target refuses a token older than the newest it has seen for the
  key's `scope` (its thread). So a run that lost its claim can neither apply, nor read back, nor
  forget anything once its successor has touched the target.
- `ReplayGuard.verifiable` is for a target that can read a digest (`StateCheck`) of what the effect
  touches. If the effect began but its outcome was not recorded, the guard checks the target. It
  returns the presumed outcome if the effect landed, runs the effect again if it did not, and
  refuses if another thread changed the target in between. The effect must be a compare-and-set
  against `before`, so that a stalled run's write cannot land on its successor's.
- `ReplayGuard.unverifiable` is for everything else. If the effect began but its outcome was not
  recorded, the guard refuses to run it again ("it may already have run"), unless the tool is
  idempotent.

```scala
import org.llm4s.agent.graph.{GraphError, ReplayGuard, StateCheck}

val append = AgentTool(appendSpec) { (args, context) =>
  val key = context.effectKey("file.append", args.path)
  ReplayGuard.verifiable[Int](context.run, key)(Right(StateCheck.of(read(args.path)))) {
    val before = read(args.path)
    val after  = before + args.text
    Right(ReplayGuard.Plan(StateCheck.of(before), StateCheck.of(after), after.length) {
      // under a lock every writer takes: write only if the file is still what the plan read
      writeIfUnchanged(args.path, StateCheck.of(before), after).map(_ => after.length)
    })
  } match
    case Right(length)                           => ToolOutcome.Success(ToolContent.Json(ujson.Num(length)))
    case Left(r: GraphError.EffectReplayRefused) => ToolOutcome.Error(r.message)
    case Left(other)                             => ToolOutcome.Fatal(other) // outcome unknown: recover replays it
}
```

What each guard does when the run crashed at each point, and why, is in design record
[§4.24](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.24-stage-3-slice-c4-replay-of-external-effects.md). The
rules a caller relies on:

- **Build each key with `effectKey`.** In a tool, `context.effectKey(operation, discriminator*)`
  derives the key from the call's idempotency key. In a hook or node, `RunContext.effectKey` derives
  it from the task's `taskKey`. Give each effect of a task its own operation or discriminator.
- **A fork's re-run of a call is a new effect.** The key includes the thread.
- **The evidence is kept exactly as long as the task can run again.** That is until the commit that
  finishes the task, however long the thread waits. `prune` never removes it, and `deleteThread`
  removes it with the thread.
- **A Block does not undo an external effect.** A guardrail that blocks a turn restores every state
  key the turn wrote, its messages included (§4.22), but a file the turn wrote outside thread state, a
  command it ran or a record it stored stays as it is. The turn's replay evidence goes with its
  closing commit, so nothing replays it either.

### Harness File Backends

The harness keeps an agent's files in a `FileBackend` (`org.llm4s.agent.harness`): thread state
(`StateFileBackend`, the default), a long-term store (`StoreFileBackend`, for `/memories/`), several at
once (`CompositeBackend`), files the application ships, read-only (`StaticFileBackend`: mount it at `/app`
and point `SkillsConfig.withSources` and `InstructionsConfig.withPaths` there, since on state files only a tool
call writes a file), a directory of the host (`HostFileBackend`), or one chosen per run
(`FileBackend.resolved`). Each backend declares the locks its mutations hold, replays its external
effects through `ReplayGuard`, and enforces its own size limits.

`HostFileBackend.open(root, lockDirectory)` exposes a trusted directory as `/`
([design §4.40](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.40-host-file-backend.md)).
It is the root's only writer: it holds an OS lock on the root until `close()`, and any other open of
the same root, or of one inside or around it, in this process or another, is refused with
`HostRootInUse`. Give every process on the host one `lockDirectory`, outside every root, on a local
file system that nothing prunes. It refuses symbolic links, files with other hard links, mounts beneath
the root and volumes that make 8.3 short names; on Windows, which reports no hard-link counts, pass
`allowUncheckedIdentity = true` only for a root with no hard links. It is not a security boundary:
never give it a root an untrusted process can write, and run untrusted commands in the workspace
sandbox.

The backend holds the root's lock until `close()`, so give it a managed lifetime: one that closes it
however the work ends. Until it is closed, every other open of the root is refused.

```scala
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.agent.harness.{FileTools, HostFileBackend}
import java.nio.file.Path
import scala.util.Using

HostFileBackend.open(Path.of("/srv/project"), Path.of("/var/lib/myapp/llm4s-locks")).flatMap { opened =>
  Using.resource(opened) { project => // closed when the block ends, whether it succeeds or fails
    for {
      tools  <- ToolSet.of(FileTools(project)*)
      agent  <- Agent.builder("coder", client).withTools(tools).build()
      result <- agent.run("Summarise README.md")
    } yield result
  }
}
```

An application that keeps an agent for longer keeps the backend beside it, and closes it when it shuts
the agent down.

### Harness File Tools

`FileTools(backend)` gives the model seven tools over any `FileBackend`: `ls`, `read_file`,
`write_file`, `edit_file`, `delete`, `glob` and `grep` ([design §4.32](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.32-stage-3-slice-f1-filesystem-tools.md)).

```scala
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.agent.harness.{FileToolLimits, FileTools, StateFileBackend}

for {
  tools <- ToolSet.of(FileTools(StateFileBackend(), FileToolLimits(maxResultChars = 20000))*)
  agent <- Agent.builder("coder", client).withTools(tools).build()
} yield agent
```

- **Paths are absolute** (`/src/main.scala`); `.`, `..` and empty segments are refused.
- **`read_file`** numbers lines from 1 and pages with `offset` (the line to start at) and `limit`
  (default and at most `readLimit`, 2000), and says how many lines the file has. **`edit_file`**
  replaces `old_string` with `new_string`, and refuses when `old_string` is absent, or occurs more
  than once without `replace_all`.
- **Bounded results.** Lines are cut at `maxLineChars`; `ls`, `glob` and `grep` stop at `maxResults`
  and say so; every result a tool returns, an error's included, is at most `maxResultChars` characters (an outcome the loop or a middleware gives instead, such as a denial, is theirs).
- **Order in one message.** Mutations take the backend's locks, so two edits of one file in one
  message apply in order, the second against the first's result. Reads take no lock: a read in the
  same message as a write may not see it (over thread state it sees the file as before the message).
- **Permissions.** Each tool declares its resources: `Read` of the path for `ls` and `read_file`,
  `Write` for `write_file` and `edit_file`, `Delete` for `delete`. `glob` and `grep` declare the
  envelope of what they may read (`/src/**`, `/src/*.md`), which a `PermissionMiddleware` decides as
  a whole, so a deny rule beneath the searched path denies the search. `ls` shows the names, not the
  contents, of entries beneath a path it may read.
- **Block and replay.** An output Block restores state files with the rest of the turn; writes to a
  store are not undone. A store mutation that runs again after a crash replays its recorded outcome.
- **Not with the host file tools.** `llm4s-agent-tools`' `ReadFileTool` and `WriteFileTool` have the
  names `read_file` and `write_file`, and a `ToolSet` refuses duplicate names: use one suite or the
  other.
- `FileToolsContract` (`llm4s-agent-testkit`) runs the tools over your own `FileBackend`.
### Workspace Sandbox

`llm4s-workspace-client` puts a containerised workspace behind both backend SPIs: `WorkspaceFileBackend`
for its files and `WorkspaceSandboxBackend` for its commands (`org.llm4s.agent.harness.workspace`). The
`execute` tool (`ExecuteTool`, in `llm4s-agent`) runs a command in any `SandboxBackend`:

```scala
import org.llm4s.agent.harness.ExecuteTool
import org.llm4s.agent.harness.workspace.{WorkspaceFileBackend, WorkspaceSandboxBackend}

val workspace = new ContainerisedWorkspace(dir, image, port)   // started with startContainer()
for
  files   <- WorkspaceFileBackend("ws1", workspace)             // a FileBackend for the file tools
  sandbox <- WorkspaceSandboxBackend("ws1", workspace)
  agent   <- Agent.builder("coder", client)
               .withTools(ToolSet.of(ExecuteTool(sandbox)).toOption.get)
               .build()
yield agent
```

- **Permissions.** An `execute` call touches `(Execute, <command>)` and is `destructive` and
  `openWorld`, so `PermissionRule.allow(Operation.Execute, "sbt *")` lets the agent run builds and
  nothing else. The runner refuses anything its command policy forbids as well
  ([Workspace sandbox](../../reference/workspace-sandbox.html)), and enforces `allowedPaths`,
  `readOnlyPaths` and its size limits itself.
- **Order.** A file mutation and a command both lock the whole workspace (`files/workspace/<id>`), so a
  batch's edits and commands in one workspace apply one after another, in call order. Two paths that name
  one file through a link are ordered too.
- **Replay.** File mutations are `Verifiable`: each is a compare-and-set against the file's hash, which
  the runner applies atomically, so a replayed edit that landed returns its first result and one whose
  file changed meanwhile is refused. Commands are `Unverifiable`: a completed command replays its stored
  result, and one that may have run is refused, never run again.
- **Effects stay.** What a command or a file write did to the workspace is outside thread state: a Block
  that retracts the turn does not undo it. The runner never runs a file change beside a command it started,
  but a process a command leaves running, or a writer outside the container, is outside the compare-and-set.
- **Tenants.** One workspace is one tenant's. An application serving several builds a pair per tenant
  and picks it with `FileBackend.resolved` and
  `SandboxBackend.resolved(_ => Set(WorkspaceLocks.AllWorkspaces))`.

### Tool Permissions, Timeouts and Locks

An `AgentToolSpec` can declare what a call touches, how long one run may take, and which locks it
holds, each from the call's decoded arguments:

```scala
import org.llm4s.agent.graph.middleware.{PermissionMiddleware, PermissionRule}
import org.llm4s.agent.graph.tool.{LockKey, Operation, ToolResource}
import scala.concurrent.duration.*

val writeSpec = baseSpec
  .withResources(args => Vector(ToolResource(Operation.Write, args.path)))
  .withLocks(args => Set(LockKey("workspace", workspaceId, args.path)))
  .withTimeout(30.seconds)

val agent = Agent.builder("coder", client)
  .withTools(tools)
  .withMiddleware(                       // register it first, so it wraps every other middleware
    new PermissionMiddleware(Seq(
      PermissionRule.deny(Operation.Write, "/etc/**"),
      PermissionRule.ask(Operation.Write, "/deploy/**"),
      PermissionRule.allow(Operation.Write, "/src/**"),
      PermissionRule.allow(Operation.Invoke, "*")
    ))
  )
  .build()
```

**Permissions are deny-if-unmatched.** For each resource the first rule with its operation whose
`Glob` matches the target decides, and a resource no rule matches is denied. A call runs only if
every resource is allowed: a denial is the error result `Denied: <operation> <target>`, an `Ask`
suspends the call for approval. A tool that declares no resources touches `(Invoke, <tool name>)`,
so it needs an `Invoke` rule. Resources are the call's whole envelope: a tool that asks a question and lets
the answer choose its target declares a resource covering every target it may pick (`/out/**`) and
refuses an answer outside it, because the check runs on the declaration, not on what the tool does. Such an envelope
is checked for every target in it: a deny rule reaching any part of it denies the call, and it is
allowed only when allow rules cover all of it. Approval is one flag per call: once a reviewer approves the call,
every `Ask` passes. An edited call is checked again on its new arguments, so a deny rule still
refuses it. Rules see the arguments after the schema check and decoding. In a `Glob`, `*` and `?`
stay within one `/`-separated segment and `**` crosses them. An `Execute` rule limits which
commands the agent may ask to run, not what a command does once it runs: rely on the sandbox for
that.

**A timeout bounds each run of the tool.** A run past it is interrupted and the model sees the
error `Tool '<name>' timed out after <n> ms`; the run continues. The tool runs on a thread of its
own, and the call waits for that thread to finish, so **block interruptibly**: a tool that ignores
its interrupt holds its whole batch past the timeout. A timed-out call may have done part of its
work: it keeps its idempotency key if it runs again (a retrying middleware, `recover`), but if the
model calls the tool again, that is a new call with a new key. Cancelling the run still cancels the
call, which records nothing.

**Locks order conflicting calls of one batch.** Two `LockKey`s conflict when one is a prefix of
the other, segment by segment: `workspace/w1` conflicts with `workspace/w1/src/a.scala`, and
`a/b` does not conflict with `a/bc`. When a model message has several calls, a call whose locks
conflict with an earlier call's waits for it and runs in a later superstep, in call order, seeing
what the earlier call committed. So a check against thread state (a quota, a file's content) is
exact, and two calls writing one key no longer lose one of the writes. Calls without conflicting
locks still run in parallel. Each further round costs two supersteps of `RunBudgets.maxSupersteps`.
A call that waits for approval holds back the calls queued behind it, so a reviewer can see a
batch's approvals in more than one round. Locks order calls within one batch only, not across
threads. An approval edit may narrow a call's locks (`/prod` to `/prod/app`) but not take a lock
its proposed arguments did not cover: such an edit is refused, the call does not run, and the model
sees why.

### Todos

`TodoMiddleware` gives an agent a plan: a `write_todos` tool that replaces the thread's todo list,
and a prompt section that shows the list to the model on every model step.

```scala
import org.llm4s.agent.harness.{HarnessEvents, TodoMiddleware}

for {
  agent <- Agent.builder("researcher", client)
             .withMiddleware(new TodoMiddleware(maxTodos = 20, maxTodoChars = 200))
             .build()                                  // Result[Agent]
  run   <- agent.stream(threadId, "Write a report on ...") {
             case HarnessEvents.TodosProgress(p) =>
               p.todos.foreach(t => println(s"[${t.status.wire}] ${t.content}"))
             case _ => ()
           }
  result <- run.await()
} yield result
```

- **The model sends the whole list** - each item's `content`, `status` (`pending`, `in_progress`,
  `done`) and, to keep an item, its `id` - and it replaces the plan. The tool assigns ids: an item
  without one keeps the id of an item with the same content, or gets a new one, and an id is never
  reused within a thread. An unknown id, an id given twice, a list over `maxTodos` (default 50) or an
  item over `maxTodoChars` code points (default 200) is refused as an error the model sees.
- **The plan is thread state**, in `TodoMiddleware.key` (a `TodoList`): it is checkpointed, read
  by the next turn, and restored by an output Block with the rest of the turn. Two `write_todos`
  calls in one message run in turn (the tool locks `todos`), the second seeing the first's list.
- **Events.** Each change emits a durable `HarnessEvents.TodosUpdated` with ids and statuses only,
  and a live `HarnessEvents.TodosProgress` with the whole list.
- **The plan guides the model only.** It changes no route and no permission: a
  `PermissionMiddleware` needs `PermissionRule.allow(Operation.Invoke, "write_todos")` for the tool.
- **Adding it to an existing agent reshapes its graph** (it registers the `todos` key, and the
  tool's lock registers `tool-queue`), so threads checkpointed before are refused with
  `RestoreRejected`: finish or `forget` them first.

### Skills

`SkillsMiddleware` gives an agent [Agent Skills](https://agentskills.io/): folders holding a
`SKILL.md` whose frontmatter names and describes the skill. It finds them through a `FileBackend`
and lists each skill's name, description and path in one prompt section; the model reads a skill's
`SKILL.md`, and its other files, with `read_file` when a task matches it.

```scala
import org.llm4s.agent.harness.{SkillsMiddleware, VPath}

for {
  user    <- VPath("/user/skills")
  project <- VPath("/project/skills")
  agent <- Agent.builder("coder", client)
             .withMiddleware(new SkillsMiddleware(Vector(user, project), backend))
             .build()                                  // Result[Agent]
} yield agent
```

- **Give the agent `FileTools(backend)` over the same backend** (or the composite it is mounted in),
  so the paths in the section are the ones `read_file` takes.
- **Read once per run**, on its first model call, and cached for the run. A skill added or edited
  during a run shows from the next run; a recovered or resumed run reads again. A later source's
  skill replaces an earlier one of the same name, so list the user's skills before the project's.
- **Bounded.** At most `maxSkills` (default 20) skills, each description cut at
  `maxDescriptionChars` (256), the section at most `maxSectionTokens` (1500), at most `maxScanned`
  (100) folders examined, and only each `SKILL.md`'s frontmatter read.
- **The frontmatter is a restricted YAML subset**, read without a YAML library: `key: value` scalars,
  quoted strings, flat lists and a one-level `metadata` map. A block scalar (`description: |`), a
  nested value or a name that does not match its folder makes the skill malformed.
- **Problems never fail the run.** A malformed, unlisted, shadowed or unreadable skill is skipped and
  named by path in a durable `HarnessEvents.DocumentsReported`. A missing source is skipped.
- `allowed-tools` is parsed onto `Skill.allowedTools`, not enforced.

### Instruction Memory

`InstructionMemoryMiddleware` shows standing instructions kept in `AGENTS.md` files to the model, as
one prompt section: the files at the paths given, read through a `FileBackend` in order (a later
file refines an earlier one), each under a heading naming its path.

```scala
import org.llm4s.agent.harness.{InstructionMemoryMiddleware, VPath}

for {
  user    <- VPath("/user/AGENTS.md")
  project <- VPath("/project/AGENTS.md")
  agent <- Agent.builder("coder", client)
             .withMiddleware(new InstructionMemoryMiddleware(Vector(user, project), backend))
             .build()                                  // Result[Agent]
} yield agent
```

- **Read once per run**, as skills are: an edit during a run shows from the next run, and a recovered
  or resumed run reads again. A missing file is skipped.
- **Editable when permitted.** The agent edits a file with `edit_file` (`FileTools(backend)`) over the same backend when the
  backend accepts the write and the permission rules allow `Write` on its path; deny it to keep a
  scope read-only.
- **Bounded.** Each file at most `maxFileTokens` (default 1000), the section at most
  `maxTotalTokens` (2000); a file over either is cut and marked, files past a full section are left
  out, and each is named by path in a durable `HarnessEvents.DocumentsReported`.
- Instruction memory is a few whole files the agent always sees; [semantic memory](memory) is facts
  recalled per query.

### Harness Profile

`HarnessProfile` assembles the pieces above into one value, installed with `withHarness`:

```scala
import org.llm4s.agent.graph.middleware.PermissionRule
import org.llm4s.agent.graph.tool.Operation
import org.llm4s.agent.harness.{HarnessProfile, PromptMode}
import org.llm4s.config.HarnessConfigLoader

for {
  profile <- HarnessConfigLoader.load()            // HarnessProfile.deepAgents, with llm4s.agent.harness applied
  agent <- Agent.builder("researcher", client)
             .withHarness(
               profile
                 .withSystemPrompt(PromptMode.Suffix("Write the report to /report.md."))
                 .withPermissions(Vector(
                   PermissionRule.allow(Operation.Read, "/**"),
                   PermissionRule.allow(Operation.Write, "/report.md"),
                   PermissionRule.allow(Operation.Invoke, "write_todos")
                 ))
             )
             .build()                                // Result[Agent]
} yield agent
```

- **`HarnessProfile.deepAgents`** turns everything on at its defaults: the file tools over a
  `StateFileBackend`, todos, summarisation (the model-free digest compressor), offload of large tool
  results, skills under `/skills` and instructions in `/AGENTS.md`. `HarnessProfile()` is the file
  tools alone. Change either with the `with*` setters: `withBackend`, `withSandbox` (adds `execute`),
  `withTools(ToolSelection.Only(...))`, `withTodos(None)`, `withToolTimeout`, `withRetention`, ...
- **Subagents.** `deepAgents` also offers the general-purpose subagent through the `task` tool;
  `HarnessProfile()` offers none. See [Subagents in a harness](delegation#subagents-in-a-harness).
- **The order is fixed**, outermost first: permissions, subagent delegation, summarisation, offload,
  skills, instructions, todos, then the builder's own middleware. With no permission rules there is no
  `PermissionMiddleware`; with rules, a call no rule allows is denied.
- **The prompt** is the profile's (plan, act and verify; context; tool safety), `PromptMode.Replace`
  or `Suffix`. `withSystemPrompt` beside a harness is refused.
- **Retention.** As each turn is admitted, the thread is pruned to its latest 20 checkpoints (each is
  a full snapshot of the files, plan and summary), so a finished turn stays whole until the next one
  starts, and a refused turn changes nothing; `withRetention(RetentionPolicy.keepAll)` keeps
  everything. `RunConfig.withRetention` does the same for any run.
- **Defaults live in `llm4s-agent`'s `reference.conf`**, under `llm4s.agent.harness`; override them in
  `application.conf`. Backends, a sandbox, permission rules and a model compressor are set in code.
- **Middleware order is checked.** A middleware that must run outside another says so
  (`AgentMiddleware.placement`), and `build()` refuses the wrong order: a `SummarizationMiddleware`
  inside a `ToolOutputOffload`, or a `SemanticMemoryMiddleware` inside guardrail middleware.

---

## Features

### [Guardrails](guardrails)

Validate inputs and outputs for safety. Guardrails are middleware on the agent:

```scala
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin._

val agent = Agent.builder("assistant", client)
  .withMiddleware(
    new GuardrailMiddleware(
      input = Seq(new LengthCheck(1, 10000), new ProfanityFilter()),
      output = Seq(new JSONValidator())
    )
  )
  .build()
```

A blocked run ends `AgentStatus.Blocked(guardrail, reason)`, the blocked turn removed from the thread, which stays usable.

[Learn more about guardrails →](guardrails)

### [Memory System](memory)

Persistent context across conversations:

```scala
import org.llm4s.agent.memory._

val result = for {
  manager <- SimpleMemoryManager.empty
  m1 <- manager.recordUserFact("Prefers Scala", Some("user-1"), Some(0.9))
  context <- m1.getRelevantContext("programming preferences")
} yield context
```

`SemanticMemoryMiddleware`, in `llm4s-agent-memory`, recalls a tenant's memories before each turn
and records the turn after it, safely across `recover`:
[Semantic Memory Middleware](memory#semantic-memory-middleware).

[Learn more about memory →](memory)

### [Handoffs](handoffs)

Delegate to specialist agents:

```scala
import org.llm4s.agent.Handoff

val physics = Agent.builder("physics", client)
  .withSystemPrompt("You are a physicist")

val agent = Agent.builder("triage", client)
  .withHandoffs(Handoff.to("physics", physics, "Physics expertise required"))
  .build()
```

[Learn more about handoffs →](handoffs)

### [Human Review](human-review)

Pause a turn for a person: tool approvals, questions from tools and from middleware - to review or edit
an answer, or for missing information - and static breakpoints before or after an agent's model calls,
tool calls and final answer. Answer any subset of what a turn waits for; the rest stays pending.

### [Streaming Events](streaming)

`Agent.builder(...).withStreaming()` streams the model's answer, and
`agent.stream(threadId, query)(listener)` delivers every event of the turn - text deltas, tool
calls, model calls, handoffs, guardrail blocks - as a `StreamEvent`, matched with `AgentEvents`.
Durable events carry no message content; content is live-only. `AgentIO.stream` and `AgentZ.stream`
give the same events as fs2 and ZIO streams.

[Learn more about streaming →](streaming)

### [Durable Checkpointers](durable-checkpointers)

Every run claims its thread in the `Checkpointer` before it starts, renews the claim while it runs, and fences
every commit with the claim's token. Runtimes sharing one store - in one process or many - are refused a live
run's thread with `ThreadBusy`, can `recover` a run whose process died once its claim has expired, and a run that
lost its claim records nothing more. `SqliteCheckpointer` (`llm4s-agent-checkpoint-sqlite`) keeps threads in a
SQLite file, so a run survives a restart and `recover` continues it in the next process. `CheckpointerContract` in
`llm4s-agent-testkit` tests a store of your own.

[Learn more about durable checkpointers →](durable-checkpointers)

---

## Built-in Tools

The `llm4s-agent-tools` module ships ready-made tools - a calculator, the date and time, UUIDs, JSON, files, HTTP, a
shell and web search - in bundles you register with an agent. They work with plain tool calling through
`ToolRegistry` too, without an `Agent`. The [Built-in Tools guide](/guide/builtin-tools) lists every tool and its
parameters, and says what each bundle lets a model do: read its safety section before you give a model files, the
network or a shell. For the module itself, see
[installation](/getting-started/installation#for-built-in-tools-web-search-http-filesystem-shell).

```scala
import org.llm4s.agent.Agent
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.builtin.BuiltinTools

val result = for {
  tools <- BuiltinTools.coreSafe // date/time, calculator, UUID, JSON: no files, network or processes
  agent <- Agent.builder("assistant", client).withTools(new ToolRegistry(tools)).build()
  state <- agent.run("What is 15% of 850?")
} yield state
```

The bundles, smallest to largest: `coreSafe`, `withHttpSafe()` (adds read-only HTTP), `withFilesSafe()` (adds
read-only files) and `developmentSafe()` (adds file writing and a shell). `customSafe(...)` builds exactly the tools
whose configuration you pass.

---

## Context Window Management

Handle long conversations with `ContextWindowMiddleware`. It prunes what is sent to the model on
each call; the thread keeps its full history:

```scala
import org.llm4s.agent.{ContextWindowConfig, PruningStrategy}
import org.llm4s.agent.graph.middleware.ContextWindowMiddleware

val config = ContextWindowConfig(
  maxMessages = Some(20),
  preserveSystemMessage = true,
  minRecentTurns = 2,
  pruningStrategy = PruningStrategy.OldestFirst
)

val agent = Agent.builder("assistant", client)
  .withMiddleware(new ContextWindowMiddleware(config))
  .build()
```

The current turn (the latest user message onward) is never pruned, a tool call is never separated
from its result, and the request always starts with a user message - so a request may exceed the
budget. The system prompt is outside the budget.

**Pruning Strategies:**

| Strategy | Behavior |
|----------|----------|
| `OldestFirst` | Remove oldest messages first (FIFO) |
| `MiddleOut` | Keep first and last messages, remove middle |
| `RecentTurnsOnly(n)` | Keep only the last N conversation turns |
| `Custom(fn)` | User-defined pruning function |

### Summarisation

`SummarizationMiddleware` (`org.llm4s.agent.harness`) replaces the older part of each request with a summary
instead of dropping it. When the outgoing request is over its trigger, every message but the last `keepRecent`
is folded into the summary, which goes into that request and is kept in the thread for the next ones; when it
fires again, the summary is extended, not recomputed. The stored history is never edited.

```scala
import org.llm4s.agent.harness.{SummarizationMiddleware, SummaryCompressor, SummaryTrigger}

val agent = Agent.builder("assistant", client)
  .withMiddleware(
    new SummarizationMiddleware(
      trigger = SummaryTrigger.Tokens(60000),
      keepRecent = 10,
      compressor = SummaryCompressor.model(summariserClient),
      maxSummaryTokens = 2000
    )
  )
  .build()
```

`SummaryCompressor.model(client)` asks a model for the summary; its calls are counted in the thread's usage.
`SummaryCompressor.digest(counter)` uses core's `HistoryCompressor`, with no model call. The summary never exceeds
`maxSummaryTokens`, a tool call is never separated from its results, and an output Block discards a blocked turn's
summary. Use summarisation or `ContextWindowMiddleware`, not both.

### Large tool results

`ToolOutputOffload` keeps a tool result over `thresholdTokens` out of the conversation: the model sees a header,
the first lines, and a path such as `/large_tool_results/<key>`. The whole result (up to `maxResultBytes`) is kept
in the thread, oldest evicted first beyond `maxTotalBytes` (4 MiB by default). Mount `OffloadBackend` so file tools
can read it:

```scala
import org.llm4s.agent.graph.tool.ToolSet
import org.llm4s.agent.harness.{CompositeBackend, FileTools, OffloadBackend, StateFileBackend, ToolOutputOffload}

for {
  files <- CompositeBackend(StateFileBackend(), ToolOutputOffload.Mount -> OffloadBackend)
  tools <- ToolSet.of(FileTools(files)*)
  agent <- Agent.builder("assistant", client)
             .withTools(tools)
             .withMiddleware(new ToolOutputOffload(thresholdTokens = 20000))
             .build()
} yield agent
```

The mount is read-only. Only text is offloaded: images and documents in a result are passed on unchanged. With
summarisation, results the summary covers are dropped; register `SummarizationMiddleware` before
`ToolOutputOffload`. `ToolOutputOffload` also caps every tool result the model is sent at `thresholdTokens`,
including errors and denials that no tool produced.

---

## Conversation Persistence

A thread lives in the runtime. To keep a conversation across processes, save the result's messages
and import them as the `history` of a new thread:

```scala
import upickle.default.{read, write}

// Save the messages of a completed run
Files.writeString(path, write(result.messages))

// Later: load them and continue on a new thread
val saved = read[Vector[Message]](Files.readString(path))
val next = agent.run(ThreadId(java.util.UUID.randomUUID().toString), "Continue our conversation", RunConfig(), saved)
```

`history` is accepted only on a thread that does not exist yet, and may not contain system messages
(prompts belong to the agents). Save only runs that completed; `history` in which a tool call does
not have exactly one result straight after its message - one that ends mid tool call, say - is
refused.

---

## Reasoning Modes

Enable extended thinking for complex problems:

```scala
import org.llm4s.llmconnect.model.{CompletionOptions, ReasoningEffort}

val options = CompletionOptions()
  .withReasoning(ReasoningEffort.High)  // None, Low, Medium, High
  .withMaxTokens(4096)

// Use with agent
Agent.builder("assistant", client).withCompletionOptions(options).build()
```

Supported by OpenAI o1/o3 and Anthropic Claude models.

---

## Examples

| Example | Description |
|---------|-------------|
| [SingleStepAgentExample](/examples/#single-step) | A run with one tool call |
| [MultiStepAgentExample](/examples/#multi-step) | Complete execution flow |
| [MultiTurnConversationExample](/examples/#multi-turn) | Functional multi-turn API |
| [LongConversationExample](/examples/#long-conversation) | Context window pruning |
| [ConversationPersistenceExample](/examples/#persistence) | Save and resume |
| [BuiltinToolsAgentExample](/examples/#agent-examples) | Built-in tools |
| [DeepResearchExample](/examples/#deep-research) | The harness on state files: plan, offload, summary, memory, recover after a stop |
| [CodingWorkspaceExample](/examples/#coding-workspace) | The harness in a workspace: permissions, approval of `execute`, resume |
| [ParallelDeepResearchExample](/examples/#parallel-deep-research) | Typed subagents in parallel: bounds, a child's approval through the lead, nested streams, recover after a stop |

[Browse all examples →](/examples/)

---

## Design Documents

For in-depth technical details:

- [Agent Framework Roadmap](/design/agent-framework-roadmap) - Strategic direction
- [Phase 1.1: Conversations](/design/phase-1.1-functional-conversation-management) - Conversation API
- [Phase 1.2: Guardrails](/design/phase-1.2-guardrails-framework) - Validation framework
- [Phase 1.3: Handoffs](/design/phase-1.3-handoff-mechanism) - Agent delegation
- [Phase 1.4: Memory](/design/phase-1.4-memory-system) - Memory architecture
- [Typed Agent Runtime §4.13](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime/4.13-stage-1-slice-2-the-agent-loop-on-the-graph-runtime.md) - How `Agent` runs on the graph runtime
- [Phase 2.1: Streaming](/design/phase-2.1-streaming-events) - Event system

---

## Next Steps

1. **[Guardrails Guide](guardrails)** - Input/output validation
2. **[Memory Guide](memory)** - Persistent context
3. **[Handoffs Guide](handoffs)** - Agent delegation
4. **[Streaming Guide](streaming)** - Real-time events
5. **[Durable Checkpointers](durable-checkpointers)** - Shared stores, claims and recovery
6. **[Examples Gallery](/examples/)** - Working code samples
