# Events and tracing - design (#1329)

Slice 3 of [#1326](https://github.com/llm4s/llm4s/issues/1326), Stage 1 of the typed agent runtime
([#1266](https://github.com/llm4s/llm4s/issues/1266)). Modules `llm4s-agent`, `llm4s-core` (one
`TraceEvent` case replaced), `llm4s-observability`, `llm4s-observability-otel`, `llm4s-effect`,
`llm4s-zio`, `it` and `samples`. Builds on #1277's run events and dispatch (§4.6 of
`docs/design/typed-agent-runtime-design.md`) and #1328's agent on the graph runtime (§4.13).

## Goal

- The agent's model call streams through the runtime's live progress channel, opt-in per agent.
- Run events, with typed agent payloads, replace the `AgentEvent` hierarchy and
  `runWithEvents`/`runCollectingEvents` that #1328 deleted.
- `TraceEvent.AgentStateUpdated` is replaced; its consumers - `ConsoleTracing`, Langfuse,
  OpenTelemetry, `TraceCollector` - and the tracing samples are rewritten.
- Agent streaming samples are rebuilt on the new model.

Nothing is frozen: replaced API is deleted, with no shim; the changes join Stage 1's single
migration note. Java (`JAgent`) and Kotlin (`AgentKt`) streams are a follow-up issue.

## Findings that shape the design

- The kernel already has the plumbing: durable `RunEvent`s delivered only after their commit,
  `RunEvent.Custom(name, version, payload)` from `RunContext.emit`, live `StreamEvent.Live` from
  `RunContext.progress`, an ordered dispatcher per subscriber with `LiveGap` and lagging
  `Disconnected`, and `TracingSubscriber` projecting durable events to `graph.*` custom trace events.
- `ModelStep.next(messages, tools)` calls `client.complete` and has no `RunContext`, so it cannot
  stream or report progress.
- The tool loop emits nothing of its own. A subscriber sees `TaskCompleted` and a node id; nothing
  reports a model call, its usage, a tool execution, a handoff or a guardrail outcome.
- Tracing regressed with the cutover. The legacy `Agent` called `traceCompletion`,
  `traceTokenUsage`, `traceToolCall` and `traceEvent(state.toTraceEvent)`; Langfuse turned the last
  into one trace with a span per message. Today Langfuse gets one trace per `graph.*` custom event,
  with no content, usage or grouping.
- `StreamEvent.Live` carries untyped `ujson`; `AgentRun` has no `subscribe`.
- A listener that subscribes after `start` returns can miss the first live deltas, which are never
  replayed.
- `llm4s-observability` must not depend on `llm4s-agent`, so a backend sees only core `TraceEvent`s.
- The guardrail Block decision (§9, §4.13) relies on the event log holding no message content: an
  output Block removes the blocked turn from state, and nothing else may keep it.

## Decisions

### 1. Durable events carry metadata; content is live-only

Durable agent events carry identifiers, names, usage, durations and outcomes - never message
content (assistant text, thinking, tool arguments, tool results, guardrail reasons). Content travels
only in live events. Replay therefore gives a run's structure, and the Block guarantee is unchanged.
Tracing receives content once, at run end, from the run's final state (decision 6), where a Block
has already removed the blocked turn.

*Rejected:* durable events with content (an output Block would also have to redact the event log,
and the guarantee that the log holds no content is lost); content in durable events behind a flag
(more configuration, and a documented exception to the Block guarantee).

### 2. One vocabulary: run events with typed agent payloads

Listeners receive the kernel's `StreamEvent`. The agent's events are payloads inside it, declared
once each so that matching needs no `ujson`:

```scala
// org.llm4s.agent.graph
final class EventType[A] private (val name: String, val version: Int)(using ReadWriter[A]):
  def emit(context: RunContext, value: A): Unit       // durable: RunEvent.Custom(name, version, json)
  def progress(context: RunContext, value: A): Unit   // live:    StreamEvent.Live(..., name, version, json)
  def unapply(event: StreamEvent): Option[A]          // matches Durable(Custom) or Live by name and version

object EventType:
  def apply[A: ReadWriter](name: String, version: Int): EventType[A]   // name: [a-z0-9_.]{1,64}, version >= 1
```

`unapply` returns `None` for another name or version, and for a payload that does not decode (logged
at DEBUG): a listener never throws on an event it does not know.

Kernel change: `RunContext.progress(payload)` becomes `progress(name, version, payload)`, and
`StreamEvent.Live` gains `name: String` and `version: Int`, matching `Custom`. Nothing else in the
kernel learns about agents.

*Rejected:* agent cases on `RunEvent` (couples the graph kernel to agent concepts); a separate typed
`AgentEvent` enum projected from run events (a second vocabulary, which §4.6 and §9 rule out);
deriving agent events by diffing committed state (no durations, attempts or deltas; breaks with the
state layout).

### 3. The agent event vocabulary

In `org.llm4s.agent.AgentEvents`, each an `EventType` with a payload case class. Durable events are
emitted by the node whose task commits them, so they inherit #1268's commit gate: an event is stored
and delivered only if its task's result is committed. A `wrapModelCall` retry, a failed task, and a
task that `recover` runs again therefore never store a duplicate.

Durable, name `agent.<snake case>`, version 1, no content:

| Event | Fields | Emitted by |
|---|---|---|
| `ModelCallCompleted` | `agent`, `model`, `attempts`, `toolCalls: Int`, `usage: Option[TokenUsage]`, `finishReason: Option[String]` | `<id>/model`, after the wrapped call returns `Right` |
| `ToolExecuted` | `agent`, `toolCallId`, `tool`, `duration: FiniteDuration`, `outcome: ToolExecutionOutcome` | `<id>/call-tool`, the approval node and `<id>/ask/<tool>`, for the outcome they record |
| `HandedOff` | `from`, `to` | `<id>/model`, when it routes a handoff |
| `GuardrailBlocked` | `guardrail`, `phase: GuardrailPhase` (`Input`, `Output`) | `input` and `<id>/finish`, with the Block's update |

`ToolExecutionOutcome` is `Succeeded`, `Errored`, `Denied`, `Rejected`, `NeedsApproval` or `Asked`.
`Denied` is an `Error` whose content a middleware produced with the `Denied:` prefix; `Rejected` is a
reviewer's `Reject`. Durations use `DurationJson.millisRW` on the wire.

Live, same naming, no `seq`, never replayed:

| Event | Fields | Sent by |
|---|---|---|
| `ModelCallStarted` | `agent`, `attempt` | the innermost model call, before each attempt |
| `TextDelta` | `attempt`, `text` | a streaming model step, per content chunk |
| `ThinkingDelta` | `attempt`, `text` | a streaming model step, per thinking chunk |
| `ToolCallStarted` | `toolCallId`, `tool`, `arguments: ujson.Value` | `<id>/call-tool`, after validation, before the middleware chain |
| `ToolResult` | `toolCallId`, `content`, `isError` | the node that records the call's result |

A live event's `taskId` identifies the model call. `attempt` counts calls of the innermost model
function within one task, from 1: a higher attempt on the same task means the earlier attempt's text
is discarded (a retrying or falling-back `wrapModelCall`). A task that `recover` runs again starts at
1 with a new `taskId`.

### 4. Streaming is opt-in per agent

`ModelStep.next(messages, tools)` becomes `next(messages, tools, context: RunContext)`.
`ModelStep.fromClient(client, options, streaming = false)` calls `complete` when `streaming` is false.
When it is true it calls `streamComplete`, sending each chunk's content as `TextDelta` and thinking
as `ThinkingDelta`, and returns the accumulated `Completion` exactly as `complete` would. The loop's
`callModel` sends `ModelCallStarted` and numbers attempts, so a custom `ModelStep` gets attempts
without doing anything. `AgentBuilder.withStreaming()` (default off) selects streaming for every
agent of a family built from that builder; the flag is not part of the graph fingerprint, since it
changes nothing stored.

*Rejected:* always streaming (every provider's streamed usage and tool-call accumulation becomes
load-bearing for every agent); streaming only while someone is subscribed (the provider path would
depend on who is watching).

### 5. Subscribing: observers at admission, run-scoped subscriptions, `stream*`

**Kernel.** `GraphRuntime.start`, `recover` and `resume` gain `observer: Option[Observer] = None`,
with `Observer(capacity: Int, listener: StreamEvent => Unit)`. The listener is subscribed during
admission, after the claim commits and before the run thread starts, from just before the claim
event, so it receives every durable event and every live event of the run. A `Left` from admission
creates no subscription. `RunHandle.subscribe` stays for late observers, who get durable events
replayed from the run's start and live events from the moment they subscribe. `capacity < 2` is the
existing `ValidationError`, returned before anything is claimed.

**`AgentRun`.** `subscribe(capacity = 1024)(listener: StreamEvent => Unit): Result[Subscription]` is
run-scoped, unlike the kernel's thread-scoped subscription: it passes on this run's `Durable` and
`Live` events and every `LiveGap`, and cancels itself after the run's terminal durable event
(`RunCompleted`, `RunSuspended`, `RunFailed`, `RunCancelled`, `RunTimedOut`). A `Disconnected`
reaches the listener only for `Lagging` or `ListenerFailed`. Today's `TracedRun` filtering becomes
this one implementation.

**`Agent`.** Each `start*` has a sibling that takes a listener and subscribes it at admission:

```scala
def stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig(), history: Seq[Message] = Nil)(
  listener: StreamEvent => Unit
): Result[AgentRun]
def streamResume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig())(
  listener: StreamEvent => Unit
): Result[AgentRun]
def streamRecover(threadId: ThreadId, config: RunConfig = RunConfig())(listener: StreamEvent => Unit): Result[AgentRun]
```

They are separate names because Scala refuses two overloaded alternatives that both define defaults.
`run` stays blocking with no listener. The run-scoped capacity is 1024.

```scala
agent
  .stream(ThreadId("t1"), "Explain monads") {
    case AgentEvents.TextDelta(d)          => print(d.text)
    case AgentEvents.ToolCallStarted(c)    => println(s"\n[${c.tool}]")
    case AgentEvents.ModelCallCompleted(m) => println(s"\n(${m.usage})")
    case _                                 => ()
  }
  .flatMap(_.await())
```

### 6. Tracing: `AgentRunEnded` replaces `AgentStateUpdated`

**Core.** `TraceEvent.AgentStateUpdated` is deleted, and with it `Tracing`'s Scaladoc reference to
`AgentState#toTraceEvent`. In its place:

```scala
case class AgentRunEnded(
  threadId: String,
  runId: String,
  agent: String,
  status: String,
  messages: Seq[Message],
  usage: UsageSummary,
  timestamp: Instant = Instant.now()
) extends TraceEvent
```

- `agent` is the agent active when the run ended.
- `status` is `completed`, `suspended`, `step_limit_reached`, `blocked:<guardrail>`, `cancelled`,
  `timed_out` or `failed`.
- `messages` are this turn's messages, from its user message on - not the whole thread, which made
  every trace repeat the history. A turn resumed across runs traces from its user message in each.
  Empty when blocked, cancelled, timed out or failed.
- `usage` is the thread's committed `UsageSummary` (core `llmconnect.model`).
- `eventType` is `agent_run_ended`; `toJson` is a flat summary without messages, with
  `message_count` and token totals.

It is a plain case class, like its siblings, built from core types only.

**Emission.** Run-end tracing moves from the kernel's `TracingSubscriber` into the agent layer as
`AgentTracing`, on decision 5's run-scoped subscription. For each of the run's durable events it
traces what `TracingSubscriber` traces today (`graph.*` for kernel events, `agent.*` for agent
events, as `CustomEvent`s), and for `ModelCallCompleted` with usage it also traces
`TokenUsageRecorded(usage, model, "agent_completion")`, as the legacy agent did. On the terminal
event it takes the run's result from the handle - set by the run thread just after its closing
commit, so the wait is brief and only on that subscription's dispatcher - and traces
`AgentRunEnded`, using the status logic `AgentRun.await` uses, extracted as one pure function. A run
that fails also traces `ErrorOccurred`. Reading the run's own result, not the latest checkpoint,
means a later run on the same thread can never be mistaken for it, and the trace is complete whether
or not anyone calls `await`. A crashed run commits no terminal event and keeps today's WARN after
the drain timeout. `TracingSubscriber.attach` stays for kernel-only graphs.

Lost by decision 1: `traceCompletion` and `traceToolCall` with content per step. That content
arrives once, in `AgentRunEnded.messages`.

**Backends.**

| Backend | Treatment of `AgentRunEnded` |
|---|---|
| Langfuse | `traceConversation`: trace id = `runId`; `sessionId` = `threadId` (replacing `session-<millis>`, so a conversation's turns group); input the first user message, output the last assistant message; metadata the agent, status and usage; one child span per message |
| OpenTelemetry | `INTERNAL` span "Agent Run" with thread, run, agent, status, message count and `gen_ai.usage.*` totals |
| `TraceCollector` | `SpanKind.AgentCall` span with the same attributes |
| `ConsoleTracing` | one summary line |

### 7. fs2 and ZIO

`AgentIO.stream(threadId, query, config)` returns `Stream[F, AgentStreamItem]`, and
`AgentZ.stream(...)` returns `ZStream[Any, LLMError, AgentStreamItem]`, each with `streamResume` and
`streamRecover` variants. `AgentStreamItem` is `Event(StreamEvent)` or `Done(AgentResult)`, one enum
per module. The stream ends after `Done`; a `Left` from admission or from the run fails the stream
with that error. Interrupting the stream cancels the run. The bridge is a bounded queue that the
dispatcher thread offers into, blocking: a slow consumer backs up only its own subscription, which
the kernel then disconnects as `Lagging`, failing the stream with a `ValidationError` naming the last
delivered `seq`.

## Samples

In `modules/samples/src/main/scala/org/llm4s/samples/`:

- `streaming/StreamingAgentExample` - `withStreaming()`, printing `TextDelta`, showing attempt resets.
- `streaming/StreamingWithToolsExample` - `ToolCallStarted`/`ToolResult` live, `ToolExecuted` durable
  with its duration.
- `streaming/EventCollectionExample` - collects one run's events, then replays the durable ones with
  `GraphRuntime.subscribe(threadId, afterSeq = 0)`, showing structure without content.
- `catseffect/AgentStreamIOExample`, `zio/AgentStreamZIOExample`.
- Tracing: `BasicLLMCallingWithTrace`, `EnhancedTracingExample`, `LangfuseSampleTraceRunner` and
  `util/TracingUtil` stop building `AgentStateUpdated`; they run an agent `withTracing`, or build
  `AgentRunEnded` directly where the sample is about a backend.

`BasicStreamingExample` and `StreamingWithProgressExample` (raw `streamComplete`) are unchanged.

## Docs

- `docs/guide/agents/streaming.md` rewritten: vocabulary, live vs durable, the content rule,
  `stream*` vs `subscribe`, attempts, `LiveGap`/`Lagging`, fs2/ZIO.
- `docs/guide/observability/index.md`: `AgentRunEnded` and what each backend shows.
- `README.md`, `docs/guide/agents/index.md`, `docs/migrations/0x-to-1x.md`,
  `docs/reference/migration.md`: `AgentEvent` to `StreamEvent` + `AgentEvents`, `runWithEvents` to
  `stream`, `AgentStateUpdated` to `AgentRunEnded`, in Stage 1's single migration note.
- `CHANGELOG.md`: the source breaks below.
- Design doc: new §4.14 as the implemented record; the §4.9 row for #1329 closed; §4.13's #1329
  limits updated.
- `CLAUDE.md`: the "Streaming Events" paragraph.

## Tests

Deterministic, on canned clients and fixtures; no network.

| Spec | Covers |
|---|---|
| `EventTypeSpec` | round trips; name and version mismatch; durable and live matching; undecodable payload is `None` |
| `ObserverAdmissionSpec` | an observer sees the claim and the first live event, even from a node that emits at once; a `Left` admission leaves no subscription |
| `AgentStreamingSpec` | deltas in order; attempts increment under a retrying `wrapModelCall`; streaming off sends no deltas; the `Completion` is the same either way |
| `AgentEventsSpec` | durable events once per committed task - none for a failed task, none duplicated by `recover`; handoff and guardrail events; no durable payload in a run's whole log holds message content |
| `AgentRunSubscribeSpec` | run-scoped filtering across two runs on one thread; the subscription ends after the terminal event |
| `AgentRunTracingSpec` | traces arrive without `await`; a blocked turn traces no blocked content; usage reaches `TokenUsageRecorded`; `AgentRunEnded` carries the turn's messages only |
| `AgentIOSpec`, `AgentZSpec` | events then `Done`; failure fails the stream; interrupting cancels the run; a slow consumer fails with `Lagging` |

Updated: core's trace specs (`TraceEventSpec`, `ConsoleTracingSpec`, `NoOpTracingSpec`,
`TracingSpec`, `TracingEdgeCasesSpec`), `LangfuseTracingEdgeCasesSpec`, `TraceCollectorTracingSpec`,
`TraceCollectorPropertySpec`, `OpenTelemetryTracingBackendSpec`, `it`'s `OpenTelemetryTracingSpec`,
`TracingExampleTest`, and the kernel specs whose nodes call `progress`.

## Source breaks (no shims)

- `RunContext.progress(payload)` becomes `progress(name, version, payload)`; `StreamEvent.Live`
  gains `name` and `version`.
- `ModelStep.next(messages, tools)` becomes `next(messages, tools, context)`.
- `GraphRuntime.start`/`recover`/`resume` gain `observer` (defaulted; source-compatible for callers,
  not for subclasses).
- `TraceEvent.AgentStateUpdated` is removed; use `TraceEvent.AgentRunEnded`.
- `TracingSubscriber` no longer serves `Agent`; `AgentBuilder.withTracing` uses `AgentTracing`.

## Rollout

One branch, `feat/1329-events-and-tracing`, one PR closing #1329. Commits, roughly:

1. Kernel: `EventType`, named `Live`, `Observer`.
2. Agent events and the streaming `ModelStep`.
3. `AgentRun.subscribe` and `stream*`.
4. Tracing: `AgentRunEnded` in core, `AgentTracing`, the backends.
5. fs2 and ZIO.
6. Samples and docs.

A follow-up issue covers Java and Kotlin streams. 0.5.0 is not cut before this lands (§4.13).
