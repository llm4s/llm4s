---
layout: page
title: Streaming Events
nav_order: 5
parent: Agents
grand_parent: User Guide
---

# Streaming Events
{: .no_toc }

Watch an agent's turn as it happens: the answer token by token, each tool call, each model call.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Streaming a turn

`AgentBuilder.withStreaming()` makes every model call of the agent stream its answer (the default
is off: the agent calls `complete`). `agent.stream(threadId, query)(listener)` runs a turn and hands
every event of it to `listener`, which receives the runtime's `StreamEvent`. Match the agent's events
with the extractors in `AgentEvents`; they need no `ujson`:

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId

for
  agent <- Agent.builder("assistant", client).withSystemPrompt("You are concise.").withStreaming().build()
  run <- agent.stream(ThreadId("t1"), "Explain monads in three sentences.") {
    case AgentEvents.TextDelta(d)          => print(d.text)
    case AgentEvents.ToolCallStarted(c)    => println(s"\n[${c.tool}]")
    case AgentEvents.ModelCallCompleted(m) => println(s"\n(${m.usage})")
    case _                                 => ()
  }
  result <- run.await()
yield result
```

`stream` returns the `AgentRun` at once; `await()` gives the `AgentResult` as for `run`, and returns
only once the listener has returned from the run's last event - so everything the listener collected
is there when `await` returns. It waits at most 5 seconds for a listener that is
still busy, then logs a WARN and returns.
`streamResume(threadId, answers)` and `streamRecover(threadId)` are the streaming siblings of
`startResume` and `startRecover`. A listener runs on its own dispatcher thread, so a slow listener
never slows the run. Runnable version: `StreamingAgentExample` in `modules/samples`.

## The events

Every event is an `EventType` in `org.llm4s.agent.events.AgentEvents`, named `agent.<snake_case>`,
version 1.

**Durable** events are committed with the task that emits them, so they are stored, replayed, and
never duplicated by a retry or a `recover`. They carry no message content.

| Event | Fields | Sent |
|---|---|---|
| `ModelCallCompleted` | `agent`, `model`, `attempts`, `toolCalls`, `usage`, `estimatedCost` | after a model call returns successfully |
| `ToolExecuted` | `agent`, `toolCallId`, `tool`, `duration`, `outcome` (`Succeeded`, `Errored`, `Denied`, `Rejected`, `NeedsApproval`, `Asked`) | when a tool call's outcome is recorded |
| `HandedOff` | `from`, `to` | when the model routes a handoff |
| `GuardrailBlocked` | `guardrail`, `phase` (`Input`, `Output`) | when a guardrail blocks a turn |

**Live** events are for watching. They carry content, have no `seq`, and are never stored or replayed.

| Event | Fields | Sent |
|---|---|---|
| `ModelCallStarted` | `agent`, `attempt` | before each attempt of a model call |
| `TextDelta` | `attempt`, `text` | per chunk of the answer (needs `withStreaming()`) |
| `ThinkingDelta` | `attempt`, `text` | per chunk of the model's reasoning (needs `withStreaming()`) |
| `ToolCallStarted` | `toolCallId`, `tool`, `arguments` | after the arguments validate, before middleware runs |
| `ToolCallResult` | `toolCallId`, `content`, `isError` | when the call's result is recorded |

Notes on the durable events:

- `attempts` is 0 when a model middleware answered without calling the model.
- `ToolExecuted.tool` names the tool only when the agent has it; a call to a name the model invented
  is recorded as `"<unknown>"` (the live `ToolCallResult` still carries the call). Each non-handoff
  call of a batch that mixes a handoff with other calls runs nothing and is reported as `Errored`.
- A tool call that is approved or edited produces two `agent.tool_executed` events for one call id,
  across two runs: `NeedsApproval` when it suspends, then the final outcome after `resume`.
- `agent.guardrail_blocked` fires only for a guardrail's block. Other middleware refusals (a blank
  query or answer, a custom `Left`) emit none.
- The kernel's own events (`RunStarted`, `RunCompleted`, `RunFailed`, ...) arrive in the same
  stream, as `StreamEvent.Durable`.

## Durable vs live, and the content rule

Durable events hold identifiers, names, usage, durations and outcomes, never message content:
no assistant text, thinking, tool arguments, tool results or guardrail reasons. Content travels
only in live events, and in `AgentRunEnded.messages` at the end of a traced run.

The reason is the guardrail Block guarantee. When an output guardrail blocks a turn, the runtime
removes the blocked turn from the thread. If the log held the content, a Block would also have to
redact the log. Replaying a run therefore shows its structure (which model calls, which tools, how
long, with what usage), and a blocked answer is in no stored event.

## Attempts

`attempt` counts the calls of the innermost model function within one task, from 1. A model
wrapper that retries or falls back (`wrapModelCall`) calls it again, so a higher `attempt` on the
same task means the text streamed so far for that task is discarded: clear what you have shown and
start again. A task that `recover` runs again starts at attempt 1 under a new task id.

## `stream*` vs `subscribe`

- `Agent.stream`, `streamResume` and `streamRecover` subscribe your listener during admission,
  before the run thread starts, so it sees every durable and every live event of the run, from the
  claim on.
- `AgentRun.subscribe(capacity = 1024)(listener)` attaches later, to a run you started with
  `start`. It replays the run's durable events from its start, then delivers new ones, but it
  misses live events sent before it attached.

Both are run-scoped: a listener sees only this run's events, even when other runs share the thread,
and ends after the run's terminal event (`RunCompleted`, `RunSuspended`, `RunFailed`,
`RunCancelled`, `RunTimedOut`), or after a `Disconnected` (`Lagging`, `ListenerFailed`, or - for
`subscribe`, which replays - `ReplayFailed`). A run that crashes without a terminal event (or whose
terminal commit fails) ends its stream as soon as the listener has returned from the run's last
event: as the run ends, a marker is queued behind its last event, and reaching it ends
the subscription - also for a `subscribe` made after the run ended, once its replay is done. There
is no fixed delay. Either way, `await` waits for the listener as described above.

## Falling behind

Each listener has a bounded buffer (1024 events for `stream*`) for durable events and another of the
same size for live ones, so a burst of live events never costs it a durable one:

- If the live buffer is full, live events are dropped, and the listener gets
  `StreamEvent.LiveGap(n)` with the number lost, so a UI can note that text is missing. The final
  answer is still in the `AgentResult`.
- If the durable buffer is full - the listener is that many durable events behind - it is
  disconnected with
  `StreamEvent.Disconnected(lastSeq, DisconnectReason.Lagging)`. Durable events are in the log, so
  you can resubscribe from where you were with `GraphRuntime.subscribe(threadId, afterSeq = lastSeq)`.
  That needs the agent's runtime: build the agent `withRuntime(runtime)` and subscribe on that
  `runtime`. The subscription is thread-scoped, not run-scoped - it delivers every later run on the
  thread too - and does not end itself: cancel it when you are done.

A listener that throws is disconnected with `ListenerFailed`; the run is unaffected.

## Replay

Durable events outlive the run. `GraphRuntime.subscribe(threadId, afterSeq = 0)` replays a thread's
events from the start, which gives the structure of every run without any content. See
`EventCollectionExample`.

## fs2 and ZIO

`llm4s-effect` and `llm4s-zio` expose the same stream as a value:

```scala
// cats-effect / fs2: Stream[IO, AgentStreamItem]
agentIO.stream(threadId, "Explain monads").evalMap {
  case AgentStreamItem.Event(AgentEvents.TextDelta(d)) => IO.print(d.text)
  case AgentStreamItem.Event(_)                       => IO.unit
  case AgentStreamItem.Done(result)                   => IO.println(s"\n${result.status}")
}.compile.drain

// ZIO: ZStream[Any, LLMError, AgentStreamItem]
agentZ.stream(threadId, "Explain monads").runForeach { /* same cases */ }
```

`AgentStreamItem` is `Event(StreamEvent)` or `Done(AgentResult)`, one enum per module. The stream
ends after `Done`; a `Left` from admission or from the run fails the stream with that error.
A run that ends without a terminal event (a crash) still ends the stream, as soon as the run's last
event has been delivered, and the stream then fails with the run's error. `streamResume` and `streamRecover` exist on both.
Interrupting the stream or stopping early (`take(n)`, `head`) cancels the turn.

A slow consumer does not: the stream's buffer never holds up the run's subscription. Durable events
are always kept; live events beyond the buffer's 256 are dropped, and the consumer receives one
`StreamEvent.LiveGap(n)` with their count where they were dropped, then the rest of the run and
`Done`. Only a `Disconnected` from the runtime fails the stream. Samples: `AgentStreamIOExample`,
`AgentStreamZIOExample`.

## Java and Kotlin

The Java facade (`llm4s-java-api`) and the Kotlin API (`modules/kotlin-api`) stream a turn the same
way, with the same buffer and the same rules: a slow consumer loses live events and gets one
`StreamEvent.LiveGap(n)`, never cancels the run; a run that ends without a terminal event still
ends the stream with the run's error; and cancelling the stream cancels the run and returns once it
has ended, so the thread can be recovered at once. Both take the thread id as a `String`, as fs2 and
ZIO take a `ThreadId`; `streamResume` and `streamRecover` exist on both. To read what a suspended
turn waits for (`JAgent.pending`, `AgentKt.pending`), or to resume or recover without a stream, see
[Suspended turns from Java and Kotlin](index#suspended-turns-from-java-and-kotlin).

**Java.** `JAgent.stream(threadId, query, listener)` returns an `LlmResult<AgentStream>` at once - a
failed result for a refused start, and the listener hears nothing. An `AgentStreamListener` gets
each event through `onEvent`, then exactly one of `onComplete(AgentResult)` or
`onError(LlmException)`, all on the stream's own thread. Only `onEvent` is abstract, so a lambda is
a listener; `StreamEvents.decode` reads an event as an `Optional`:

```java
// streaming = true: the agent's model calls stream, so the turn carries text deltas
JAgent agent = Llm4s.createAgent(client, ToolRegistry.empty(), true);
LlmResult<AgentStream> started = agent.stream(threadId, "Explain monads", event -> {
    StreamEvents.decode(AgentEvents.TextDelta(), event).ifPresent(d -> System.out.print(d.text()));
    if (event instanceof StreamEvent.LiveGap) System.out.print("[...]");
});
LlmResult<AgentResult> result = started.get().await();   // or started.get().cancel()
```

`AgentStream.await()` returns once the listener has returned from its last call, with the same
outcome. `cancel()` cancels the turn and returns once it has ended; the listener receives at most
the event already being delivered, then `onError` with the cancellation - or `onComplete`, when the
turn had already ended; an interrupt of the thread calling `cancel()` does not cut that wait short,
and is kept. A listener that throws from `onEvent` - or sets its own thread's interrupt flag -
cancels the turn, and `onError` receives what it threw, or a `CancelledError`. `Llm4s.createAgent(client)` and `createAgent(client, tools)` build an agent
that calls the model's `complete`, so its stream carries no text deltas; pass `streaming = true`
for them. `Llm4s.wrapAgent(agent)` takes an agent built with `Agent.builder`, for middleware, a
system prompt or a runtime. `streamResume` takes a `List<Answer>`, built with `Answer.approve(id)`,
`reject(id, reason)`, `edit(id, argumentsJson)` or `reply(id, json)`. The Java sample in
`modules/samples/gradle-java` streams a turn's text.

**Kotlin.** `AgentKt.stream(threadId, query)` is a cold `Flow<AgentStreamItem>`: each collection
runs the turn, emitting `AgentStreamItem.Event(event)` for each event, then
`AgentStreamItem.Done(result)`. A refused start or a failed run throws `LLMException`. Cancelling
the collection - its scope, `take(n)`, `withTimeout` - cancels the turn; starting and cancelling it
run on `Dispatchers.IO`. A turn cancelled by anything but the collection fails it with
`LLMException`, as in fs2 and ZIO.

```kotlin
val agent = Llm4s.createAgent(client, ToolRegistry.empty(), streaming = true)
agent.stream(threadId, "Explain monads").collect { item ->
    when (item) {
        is AgentStreamItem.Event -> StreamEvents.decode(AgentEvents.TextDelta(), item.event).ifPresent { print(it.text()) }
        is AgentStreamItem.Done  -> println("\n${item.result.status()}")
    }
}
```

The flow's channel holds 64 items; past that, the stream's 256-event buffer fills and live events
are dropped.

## Your own graphs

The same machinery serves graphs that are not agents. An `EventType[A]` names a payload once:

```scala
val Checked = EventType[Checked]("myapp.checked", 1)   // name matches [a-z0-9_.]{1,64}

Checked.emit(context, value)       // durable: stored with the task's commit
Checked.progress(context, value)   // live: sent now, never stored

listener { case Checked(v) => ... } // None for another name, version or an undecodable payload
```

`RunContext.progress(name, version, payload)` is the untyped form.

## Limits

- **Kernel failure messages.** `TaskFailed` and `RunFailed` store an error message. If a guardrail's
  or tool's error quotes content (a guardrail reason that echoes the user's text), that text reaches
  the log through the kernel event, not through an `agent.*` event.
- **Live events are not replayed**, and a late `subscribe` misses earlier ones.

See also the [observability guide](../observability/), the
[migration note](../../reference/migration.html), and
[Typed Agent Runtime §4.14](/design/typed-agent-runtime-design).
