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

`stream` returns the `AgentRun` at once; `await()` gives the `AgentResult` as for `run`.
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
| `ModelCallCompleted` | `agent`, `model`, `attempts`, `toolCalls`, `usage` | after a model call returns successfully |
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
`RunCancelled`, `RunTimedOut`). A run that crashes without a terminal event ends its stream shortly
after the run does.

## Falling behind

Each listener has a bounded buffer (1024 events for `stream*`). If it is full:

- Live events are dropped, and the listener gets `StreamEvent.LiveGap(n)` with the number lost, so
  a UI can note that text is missing. The final answer is still in the `AgentResult`.
- If the listener still cannot keep up, it is disconnected with
  `StreamEvent.Disconnected(lastSeq, DisconnectReason.Lagging)`. Durable events are in the log, so
  resubscribe from where you were: `GraphRuntime.subscribe(threadId, afterSeq = lastSeq)`.

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
A run that ends without a terminal event (a crash) still ends the stream shortly after the run ends, and the stream then fails with the run's error. `streamResume` and `streamRecover` exist on both. Stopping early (`take(n)`, `head`) or a consumer
too slow for the buffer (lagging) cancels the turn. Samples: `AgentStreamIOExample`,
`AgentStreamZIOExample`.

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

- **Java and Kotlin streams** are not yet available. A follow-up issue covers a listener stream for
  `JAgent` and a `Flow` for `AgentKt`.
- **Kernel failure messages.** `TaskFailed` and `RunFailed` store an error message. If a guardrail's
  or tool's error quotes content (a guardrail reason that echoes the user's text), that text reaches
  the log through the kernel event, not through an `agent.*` event.
- **Live events are not replayed**, and a late `subscribe` misses earlier ones.
- `AgentRunEnded.usage` (tracing) is the thread's cumulative usage, not the run's.

See also the [observability guide](../observability/), the
[migration note](../../reference/migration.html), and
[Typed Agent Runtime §4.14](/design/typed-agent-runtime-design).
