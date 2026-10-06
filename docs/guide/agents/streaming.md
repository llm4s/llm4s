---
layout: page
title: Streaming Events
nav_order: 5
parent: Agents
grand_parent: User Guide
---

# Streaming Events
{: .no_toc }

The agent event stream is being rebuilt on the graph runtime.
{: .fs-6 .fw-300 }

---

## Status

`Agent.runWithEvents`, `runCollectingEvents`, `continueConversationWithEvents`,
`AgentStreamingExecutor` and the `AgentEvent` hierarchy were removed in
[#1328](https://github.com/llm4s/llm4s/issues/1328), which moved the agent loop onto the graph
runtime. Their replacement - subscription to a run's events and token streaming at the agent level
- is [#1329](https://github.com/llm4s/llm4s/issues/1329). Until it lands, there is no agent-level
event stream, and `Agent.start(...)` returns an `AgentRun` with no `subscribe`. The 0.5.0 release
is not cut between the two.

## What you can use now

- **Cancellation and progress of a run**: `agent.start(threadId, query)` returns an `AgentRun` at
  once, with `threadId`, `runId`, `status`, `await()` and `cancel()`:

  ```scala
  for {
    run    <- agent.start(threadId, "Summarise the report")
    // ... show a spinner, wire `run.cancel()` to a stop button ...
    result <- run.await()
  } yield result
  ```

- **Tracing**: `Agent.builder(...).withTracing(tracing)` emits the runtime's `graph.*` events for
  each run to any `Tracing` backend (console, Langfuse, OpenTelemetry). See the
  [observability guide](../observability/).
- **Token streaming from the model**: `LLMClient.streamComplete` streams one model call. It is
  not wired into the agent loop.

## Migrating from `runWithEvents`

| Before | Now |
|--------|-----|
| `agent.runWithEvents(query, tools)(handler)` | `agent.run(query)`, reading the `AgentResult`; live events return with #1329 |
| `AgentCompleted(state, ...)` | `AgentStatus.Completed(answer)` on the result |
| `AgentFailed(error, ...)` | `Left(GraphError...)` from `run` or `await()` |
| `InputGuardrailCompleted`, `OutputGuardrailCompleted` with a failure | `AgentStatus.Blocked(guardrail, reason)` |
| `HandoffStarted` / `HandoffCompleted` | `AgentResult.activeAgent`, and the messages of the transfer |

See also the [Stage 1 migration note](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md) and
[Typed Agent Runtime §4.13](/design/typed-agent-runtime-design).
