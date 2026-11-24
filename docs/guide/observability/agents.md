---
layout: page
title: Agent tracing and metrics
description: "Instrument an LLM4S agent service: the trace each run sends to Langfuse or OpenTelemetry, the agent runtime and run queue metrics, usage, cost and alerts."
search_title: "Trace and monitor LLM agent runs in Scala"
parent: Monitoring
grand_parent: User Guide
nav_order: 4
---

# Agent tracing and metrics
{: .no_toc }

What an agent service emits, how to turn each part on, and what to alert on: one trace per run, the agent runtime's
and run queue's metrics, and per-run usage and cost.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Turning it on

Three independent pieces, each off until you ask for it:

| Piece | What turns it on | Module |
|---|---|---|
| Traces of each run | `AgentBuilder.withTracing(tracing)`, the tracer from `Tracing.create(Llm4sConfig.tracing())` | `llm4s-observability` (Langfuse, `TRACING_MODE=langfuse`) or `llm4s-observability-otel` (OpenTelemetry, `TRACING_MODE=opentelemetry` or `otel`) |
| Per-call model metrics | `LLMConnect.getClient(providerConfig, metrics)` | `llm4s-observability-prometheus` or `llm4s-observability-micrometer` |
| Agent runtime and run queue metrics | `MeteredCheckpointer(store.checkpointer, metrics)`, and the same `metrics` given to `AgentWorker` and `RunSubmitter` | `llm4s-agent`, recording into either metrics module |

`llm4s.tracing.mode` (bound to `TRACING_MODE`) selects the backend; core builds `console` (the default) and `noop`
itself and finds any other mode's backend on the classpath. `Tracing.create` never fails: a missing or failing backend
is logged and replaced by a no-op tracer; `Tracing.fromSettings` returns that error instead. The backends' own keys
are in [Monitoring](index#configuration).

An agent service that queues its runs ([Running agents on several machines](../agents/multiple-machines)) wires all
three like this:

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.metrics.MeteredCheckpointer
import org.llm4s.agent.service.{ AgentWorker, QueuedAgent, RunSubmitter }
import org.llm4s.config.{ CheckpointerConfigLoader, Llm4sConfig, MetricsConfigLoader, RunQueueConfigLoader, RuntimeConfigLoader, WorkerConfigLoader }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.Tracing

val service = for {
  providerConfig  <- Llm4sConfig.defaultProvider()
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  configured      <- MetricsConfigLoader.default()       // llm4s.metrics: a PrometheusMetrics and its /metrics endpoint
  (metrics, endpoint) = configured
  tracingSettings <- Llm4sConfig.tracing()               // llm4s.tracing.mode, from TRACING_MODE
  tracing          = Tracing.create(tracingSettings)
  client          <- LLMConnect.getClient(providerConfig, metrics)       // requests, tokens, cost, errors
  store           <- CheckpointerConfigLoader.open()                     // llm4s.agent.checkpointer
  queue           <- store.runQueue
  metered          = MeteredCheckpointer(store.checkpointer, metrics)    // runs, claims, commits, child tasks
  runtime         <- RuntimeConfigLoader.runtime(metered)
  agent           <- Agent.builder("assistant", client).withRuntime(runtime).withTracing(tracing).build()
  queueSettings   <- RunQueueConfigLoader.load()
  workerSettings  <- WorkerConfigLoader.load()
  worker          <- AgentWorker(queue, metered, Seq(QueuedAgent(agent)), workerSettings, queueSettings, metrics)
  submitter        = new RunSubmitter(queue, metered, queueSettings, metrics) // the queue, and the entries it closes
} yield (worker, submitter, endpoint, tracing)
```

Every runtime and worker of the process must use `metered`, not `store.checkpointer`, or their runs go uncounted. At
shutdown call `tracing.close()` after the worker has drained: the OpenTelemetry backend batches spans and flushes
them then. A Micrometer registry takes `new MicrometerMetrics(meterRegistry)` in place of the loader's collector
([Micrometer metrics](micrometer)).

Do not also wrap the agent's client in `TracingLLMClient`: the agent's tracing already records each model call inside
the run's trace, and the wrapper would add a second, unplaced `chat <model>` span per call.

### More than one backend

`TracingComposer.combine` sends every event to each tracer; it fails only when all of them fail.
`LangfuseConfigLoader.default()` reads the Langfuse block whatever `TRACING_MODE` selects:

```scala
import org.llm4s.config.{ LangfuseConfigLoader, Llm4sConfig }
import org.llm4s.trace.{ LangfuseTracing, Tracing, TracingComposer }

val tracing = for {
  settings <- Llm4sConfig.tracing()          // e.g. TRACING_MODE=opentelemetry
  langfuse <- LangfuseConfigLoader.default() // llm4s.tracing.langfuse, LANGFUSE_* variables
} yield TracingComposer.combine(Tracing.create(settings), LangfuseTracing.from(langfuse))
```

Metrics compose the same way with `MetricsCollector.compose(a, b)`; a collector that throws does not stop the others.

## The trace of one run

The agent's tracing follows the run's durable event log as each commit lands (live events such as text deltas are
not traced) and turns it into one trace. Each run is a span; each durable event, usage record and error is a span
under it. Span ids are derived from the thread, run, claim and tool call identities, so a run recovered on another
machine names the same spans without coordination. A run that called one tool, abridged:

```text
invoke_agent assistant                     the run (sent when the run ends)
├── graph.run_started
├── chat gpt-4o                            a model call
├── Token Usage - agent_completion         its usage
├── execute_tool search_docs               a tool call, spanning its duration
├── graph.checkpoint_committed
├── chat gpt-4o
├── Token Usage - agent_completion
└── graph.run_completed
```

A run takes the caller's trace when given one, as `RunConfig.traceContext` or a queue submission's `traceContext`;
otherwise it is the root of a trace of its own:

```scala
import org.llm4s.agent.AgentResult
import org.llm4s.agent.graph.RunConfig
import org.llm4s.trace.TraceContext
import org.llm4s.types.Result

// `agent`, `threadId` and `query` as on the agents page; `traceparent` is the request's W3C header
def handle(traceparent: String): Result[AgentResult] = {
  val upstream = TraceContext.parse(traceparent).toOption
  agent.run(threadId, query, RunConfig().withTraceContext(upstream))
}
```

### Span names and attributes

Both backends name the run, model call and tool call spans after the OpenTelemetry GenAI conventions. The other
spans are named after the event: `graph.<event>` for the runtime's events (`graph.run_started`,
`graph.checkpoint_committed`, `graph.task_failed`, `graph.run_suspended`, `graph.run_resumed`, `graph.run_recovered`,
...) and `agent.handed_off`, `agent.guardrail_blocked` and `agent.task_ended` for the agent's own.

| Span | From | OpenTelemetry | Langfuse |
|---|---|---|---|
| `invoke_agent <agent>` | `TraceEvent.AgentRunEnded`, once, when the run ends | `INTERNAL`; `gen_ai.operation.name` = `invoke_agent`, `gen_ai.agent.name`, `gen_ai.conversation.id` (the thread), `gen_ai.provider.name` (the run's first model call's), `thread_id`, `run_id`, `agent`, `active_agent`, `status`, `blocked_by` (a blocked run's guardrail), `message_count`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`; a delegated child's also `llm4s.parent.thread_id` and `llm4s.parent.run_id`. Status `ERROR` when `status` is `failed`, `cancelled` or `timed_out` | A span observation; metadata `framework`, `agent`, `active_agent`, `status`, `blocked_by`, `thread_id`, `run_id`, `message_count`, `input_tokens`, `output_tokens`, `total_cost` and a child's `parent_thread_id`, `parent_run_id`; input the turn's first user message, output its last assistant message. Level `ERROR` (and `statusMessage` = the status) for the same three statuses |
| `chat <model>` | `agent.model_call_completed` | `CLIENT`; `gen_ai.operation.name` = `chat`, `gen_ai.request.model`, `gen_ai.agent.name` (the agent that made the call), `gen_ai.conversation.id`, `gen_ai.provider.name`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `gen_ai.response.finish_reasons`, `llm4s.model_call.attempt`, `llm4s.model_call.tool_calls`, `cost.usd` | A generation with `model` and `usage` (`input`, `output`, `total`, `unit` = `TOKENS`); metadata is the event's data |
| `execute_tool <tool>` | `agent.tool_executed` | `INTERNAL`; `gen_ai.operation.name` = `execute_tool`, `gen_ai.tool.name`, `tool.name`, `gen_ai.tool.call.id`, `gen_ai.agent.name`, `gen_ai.conversation.id`, `data` (the event as JSON). Status `ERROR` when the outcome is `Errored` | A span; level `ERROR` when the outcome is `Errored`; metadata is the event's data |
| `Token Usage - agent_completion` | `TraceEvent.TokenUsageRecorded`, one per model call that reported usage | `INTERNAL`; `gen_ai.request.model`, `operation`, `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`, `gen_ai.usage.total_tokens` | An event with the same counts |
| `Error` | `TraceEvent.ErrorOccurred` with context `agent run`, for a failed run or one that ended without a terminal event | `INTERNAL`; `error.message`, `error.type`, `context`; the exception recorded | An event, level `ERROR` |
| `graph.*`, `agent.*` (the rest) | the other durable events | `INTERNAL`; `event.type` = `custom`, `data` (the event as JSON) | An event; input is the event's data |

Every event's data holds `threadId`, `runId`, `seq`, `checkpointId`, `taskId` and `nodeId`, and the event's own
fields: for an agent event `name`, `version` and `payload`. Output token counts include thinking tokens, as billed.
In Langfuse, a top-level run also creates the trace itself (name `LLM4S Agent Run`, session id = the thread id,
`userId` `llm4s-user`, `public: true`, tags `llm4s`, `agent`, `conversation`) and adds one span per message of the
turn under the run (`User Input <n>`, `LLM Generation <n>`, `System Message <n>`, or the message's class name).

Only a tool call spans its duration. A model call's span is a point at the time its result was committed; the call's
latency is the `llm4s.request.duration` metric below.

### The run's status

`AgentRunEnded.status` is a `TraceEvent.RunStatus`, written as `completed`, `step_limit_reached`,
`token_limit_reached`, `truncated`, `suspended`, `blocked`, `cancelled`, `timed_out` or `failed`; a blocked run's
`blockedBy` names the guardrail. The `llm4s.agent.runs.ended` metric reports the same values. Its `usage` is this run's
own model calls, not the thread's earlier runs, so per-run figures add up. `agent` is the run's root agent, the one it
was started on, and `activeAgent` the agent active when the run ended.

| What happened | In the trace |
|---|---|
| A handoff | Same run, same trace: an `agent.handed_off` span (`from`, `to`), later `chat` spans carry the new agent's `gen_ai.agent.name`. `invoke_agent` stays named after the root agent; its `active_agent` is the agent active at the end |
| A delegated subagent | A nested span, not a separate trace: the child's `invoke_agent` sits under the parent's `execute_tool task` span, on whichever machine ran it, with `llm4s.parent.*` set. In Langfuse the child creates no trace of its own. The parent's `agent.task_ended` carries the child's status and usage |
| Suspended for approval or a question | `invoke_agent` with status `suspended`, sent when the run suspends; the suspending tool call is a span of its own (`execute_tool <tool>` with outcome `NeedsApproval` or `Asked`) |
| Resumed | A new run with its own `invoke_agent`, in the same trace: under the context the first run continued, or else under the first run's span. In Langfuse each top-level run sends the trace again - an upsert by trace id, so the trace's input, output, metadata and timestamp are its latest run's - and its run span has no parent |
| Recovered after a crash | A new run starting `graph.run_recovered` (with `takenOverFrom` when it took over an expired claim), placed as a resume is. The run that died sent no `AgentRunEnded`, so its events point at a run span the backend never receives |
| Blocked by a guardrail | `agent.guardrail_blocked` (`guardrail`, `phase`), then `graph.task_failed` and `graph.run_failed`; status `blocked`, `blocked_by` the guardrail; no messages; not an `ERROR` span |
| Step or token limit | Status `step_limit_reached` or `token_limit_reached`; not an `ERROR` span |
| Cancelled, timed out, failed | Status `cancelled`, `timed_out` or `failed`; `ERROR` span (Langfuse level `ERROR`); a failed run also sends an `Error` span |
| Process died mid-run | No `AgentRunEnded`. A run that ends without its terminal event in a live process sends `Error` and logs a WARN |

### Content

| Where | Content recorded | Setting |
|---|---|---|
| Durable events (`chat`, `execute_tool`, `graph.*`, `agent.*` spans) | None of the messages, tool arguments or results. They do carry the run's `tenantId` and `principal` (`graph.run_started`, `graph.run_resumed`, `graph.run_recovered`) and failure messages (`graph.task_failed`, `graph.run_failed`; for a guardrail block `Guardrail '<name>' blocked: <reason>`) | none |
| OpenTelemetry `invoke_agent` | Message count only | `llm4s.tracing.opentelemetry.captureContent` (`OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT`, default `false`) applies to `TracingLLMClient`'s spans and the `TraceEvent.AgentInitialized` and `TraceEvent.ToolExecuted` events, not to an agent run's spans |
| Langfuse | `AgentRunEnded.messages`: the turn's messages from its user message on, as the trace's input and output and one span per message. Empty when blocked, cancelled, timed out or failed | none in the backend; filter them out as below |
| Console | Metadata only | `llm4s.tracing.console.captureContent` (default `false`) |

To keep message content out of Langfuse, drop it before it is sent:

```scala
import org.llm4s.trace.{ TraceEvent, Tracing, TracingComposer }

def withoutMessages(tracing: Tracing): Tracing =
  TracingComposer.transform(tracing) {
    case e: TraceEvent.AgentRunEnded => e.withMessages(Nil)
    case other                       => other
  }
```

The durable-event rule is in [Streaming events](../agents/streaming#durable-vs-live-and-the-content-rule); raw provider
requests and responses are a separate, local-only log: [Provider exchange logging](provider-exchange-logging).

## Metrics

### Per model call

A client built with a `MetricsCollector` records each provider call, the agent's included, by `provider` and `model`
(no agent label): one request with its latency and outcome, and on success its tokens and estimated cost. A streamed
call is recorded once, when it ends.

| Micrometer meter | Prometheus series | Recorded by |
|---|---|---|
| `llm4s.requests` (`provider`, `model`, `status`) | `llm4s_requests_total` | both modules |
| `llm4s.request.duration` (`provider`, `model`) | `llm4s_request_duration_seconds` | both modules |
| `llm4s.tokens` (`provider`, `model`, `type` = `input` or `output`) | `llm4s_tokens_total` | both modules |
| `llm4s.cost.usd` (`provider`, `model`) | `llm4s_cost_usd_total` | both modules |
| `llm4s.errors` (`provider`, `error_type`) | `llm4s_errors_total` | both modules |
| `llm4s.retries` (`provider`) | `llm4s_retries_total` | Micrometer only, from `ReliableClient` |
| `llm4s.circuit.breaker.transitions` (`provider`, `state`) | `llm4s_circuit_breaker_transitions_total` | Micrometer only, from `ReliableClient` |
| `llm4s.reliability.errors` (`provider`, `error_type`) | `llm4s_reliability_errors_total` | Micrometer only, from `ReliableClient` |

Tag values and the image series are in [Micrometer metrics](micrometer#what-is-recorded); the Prometheus wiring, the
`/metrics` endpoint and its limits are in [Monitoring](index#metrics).

### Agent runtime and run queue

The tracing and metrics contracts in core (`Tracing`, `TraceEvent`, `MetricsCollector`) are `@Stable`;
`AgentMetrics`, `MeteredCheckpointer` and the worker's metrics are `@Experimental`, so their names and labels may still
change.

`MeteredCheckpointer` records what each call to the store did, so every run is counted once, by the process that ran it.
`AgentWorker` records the queue and its own work; `RunSubmitter` records the entries it closes (a cancel, an
interrupt). The names below are Micrometer's; Prometheus writes `_` for `.`, adds `_total` to a counter and
`_seconds` to a timer.

| Metric | Type | Labels | Meaning | Recorded by |
|---|---|---|---|---|
| `llm4s.agent.runs.started` | counter | `graph`, `trigger` (`start`, `resume`, `recover`) | Runs that began | checkpointer |
| `llm4s.agent.runs.ended` | counter | `graph`, `status` (`completed`, `step_limit_reached`, `token_limit_reached`, `truncated`, `suspended`, `blocked`, `cancelled`, `timed_out`, `failed`) | Runs that ended with a terminal event | checkpointer |
| `llm4s.agent.run.duration` | timer, 100 ms to 1 h | `graph`, `status` | From the run's first event to its terminal event | checkpointer |
| `llm4s.agent.claims` | counter | `result` (`acquired`, `taken_over`, `busy`, `failed`) | Thread claims asked of the store; `taken_over` replaced an expired claim | checkpointer |
| `llm4s.agent.claim.renewals` | counter | `result` (`renewed`, `lost`, `failed`) | Claim renewals | checkpointer |
| `llm4s.agent.commit.duration` | timer, 1 ms to 10 s | `result` (`ok`, `conflict`, `claim_lost`, `failed`) | Commit latency and outcome | checkpointer |
| `llm4s.agent.tasks.started` | counter | `agent` | Delegated child tasks first admitted | checkpointer |
| `llm4s.agent.tasks.ended` | counter | `agent`, `status` (`completed`, `blocked`, `step_limit_reached`, `token_limit_reached`, `truncated`, `output_invalid`, `failed`) | Delegated child tasks that ended | checkpointer |
| `llm4s.agent.task.duration` | timer, 100 ms to 1 h | `agent`, `status` | From the child's first admission to the call's end | checkpointer |
| `llm4s.agent.queue.wait` | timer, 100 ms to 1 h | `agent` | From submission to the first attempt | worker |
| `llm4s.agent.queue.depth` | gauge | `agent`, `state` (`waiting`, `leased`) | Open entries, store-wide | worker |
| `llm4s.agent.queue.oldest.seconds` | gauge | `agent` | Age of the oldest waiting entry; 0 when none waits | worker |
| `llm4s.agent.queue.attempts` | counter | `agent`, `result` (`recorded`, `exhausted`, `thread_deleted`) | Attempts a worker asked to record | worker |
| `llm4s.agent.queue.plans` | counter | `agent`, `plan` (`start`, `recover`, `close`) | What a recorded attempt did | worker |
| `llm4s.agent.queue.deferrals` | counter | `agent`, `reason` (`busy`, `unrunnable`) | Entries given back without an attempt | worker |
| `llm4s.agent.queue.lease.expiries` | counter | `agent` | Takes that replaced an expired lease: a dead, cut-off or lapsed worker's | worker |
| `llm4s.agent.queue.lease.lapses` | counter | `agent` | Leases let lapse on purpose to retry after a failed run or a store error | worker |
| `llm4s.agent.queue.lease.losses` | counter | `agent`, `reason` (`refused`, `unrenewed`) | Leases lost mid-run, cancelling the run | worker |
| `llm4s.agent.queue.closed` | counter | `agent`, `outcome` (`done`, `failed`, `cancelled`, `interrupted`), `reason` (`attempts_exhausted`, `run_failed`, `unrunnable`, `abandoned`, `requested`, `thread_deleted`, `none`) | Entries closed | worker, submitter |
| `llm4s.agent.sweep.passes` | counter | `result` (`done`, `skipped`, `failed`) | Retention sweep passes | worker |
| `llm4s.agent.sweep.threads.examined` | counter | none | Threads the sweep examined | worker |
| `llm4s.agent.sweep.tombstones.reclaimed` | counter | none | Tombstones reclaimed | worker |
| `llm4s.agent.sweep.entries.reclaimed` | counter | none | Closed entries past their ticket lifetime reclaimed | worker |
| `llm4s.agent.worker.drained` | counter | `result` (`finished`, `cancelled`, `abandoned`) | Runs of a draining worker, by how they ended | worker |

- **Statuses are the trace's.** `runs.ended` and `run.duration` report the status the run's `AgentRunEnded` does: a
  guardrail block is `blocked`, not `failed`, and a step- or token-limited turn is `step_limit_reached` or
  `token_limit_reached`, not `completed`. No label names the guardrail; that is the trace's `blocked_by`. A graph that
  is not an agent turn ends `completed`, `suspended`, `failed`, `cancelled` or `timed_out`.
- **A run that dies** without a terminal event ends nothing; its recovery counts as `runs.started{trigger="recover"}`.
- **The queue gauges are store-wide**, sampled every `llm4s.agent.worker.metrics.queueDepthEvery` (15 seconds): every
  worker reports the same numbers, so aggregate with `max`, not `sum`.
- **Labels are bounded.** `graph` and `agent` are ids your code fixes; no thread, run, entry or tenant id is ever a
  label, and no metric carries content. Each backend records a label's values past `MetricsCollector.MaxLabelValues`
  (256) as `other`.

## Usage and cost

| Source | Scope | What it holds |
|---|---|---|
| `AgentResult.usage` | The whole thread, every run on it | `UsageSummary`: `requestCount`, `inputTokens`, `outputTokens`, `thinkingTokens`, `totalCost`, `byModel` |
| `AgentRunEnded.usage` (the `invoke_agent` span) | One run | The same, for this run's calls, including the calls of attempts that committed nothing |
| `agent.task_ended` usage | One delegated child, its whole life | The roll-up added to the parent |
| `CostTracker` (`llm4s-observability`) | The process, per model | A `MetricsCollector`: `totalCost`, `totalInputTokens`, `totalOutputTokens`, `totalRequests`, `byModel`, `snapshot`, `summary` |

```scala
import org.llm4s.metrics.{ CostTracker, MetricsCollector, PrometheusMetrics }

val tracker   = CostTracker.create()
val collector = MetricsCollector.compose(PrometheusMetrics.create(), tracker)
// LLMConnect.getClient(providerConfig, collector), then at any point:
println(tracker.summary)
```

How tokens are counted:

- **Thinking (reasoning) tokens are output.** Every output count - `outputTokens`, `gen_ai.usage.output_tokens`,
  `llm4s.tokens{type="output"}` - is the completion plus thinking, as billed; `UsageSummary.thinkingTokens` is the part
  that was thinking.
- **Cached prompt tokens are input.** `TokenUsage.cachedTokens` and `cacheCreationTokens` are parts of `promptTokens`;
  no summary, span or metric counts them separately. The cost estimate prices them at the model's cache read and write
  rates when its pricing has them.
- `CostTracker.totalRequests` counts failed calls too; tokens and cost come only from calls that reported them.

Cost is an estimate from the model registry's pricing. Ways to bound it are in [Cost controls](../cost-controls).

## What to alert on

In PromQL over the Prometheus series; thresholds are yours.

| Alert | Query | Means |
|---|---|---|
| Work waiting with no worker taking it | `max by (agent) (llm4s_agent_queue_oldest_seconds) > 300` | Workers down, saturated or unable to run the agent |
| Queue growing | `max by (agent) (llm4s_agent_queue_depth{state="waiting"})` rising over 15m | Submissions outpace workers: raise `worker.concurrency` or instances |
| Dead letters | `increase(llm4s_agent_queue_closed_total{outcome="failed",reason="attempts_exhausted"}[15m]) > 0` | An entry failed every attempt |
| Unrunnable or abandoned entries | `increase(llm4s_agent_queue_closed_total{outcome="failed",reason=~"unrunnable\|abandoned"}[1h]) > 0` | No worker's graph can run the thread, or a run was given up |
| Machines dying mid-run | `rate(llm4s_agent_queue_lease_expiries_total[10m]) > 0`, `rate(llm4s_agent_runs_started_total{trigger="recover"}[10m])` | Runs recovered after their worker stopped renewing |
| Workers cut off from the store | `increase(llm4s_agent_queue_lease_losses_total{reason="unrenewed"}[10m]) > 0`, `increase(llm4s_agent_claim_renewals_total{result=~"lost\|failed"}[10m]) > 0`, `increase(llm4s_agent_claims_total{result="failed"}[10m]) > 0` | Renewals not landing; runs cancelled or taken over |
| Store unhealthy | `sum(rate(llm4s_agent_commit_duration_seconds_count{result="failed"}[5m])) > 0`, p99 of `llm4s_agent_commit_duration_seconds` | Commits failing or slow |
| Runs failing | `sum(rate(llm4s_agent_runs_ended_total{status=~"failed\|timed_out"}[15m])) / sum(rate(llm4s_agent_runs_ended_total[15m]))` | Guardrail blocks are not among them: they end `blocked` |
| Subagents hitting limits or blocked | `increase(llm4s_agent_tasks_ended_total{status=~"step_limit_reached\|token_limit_reached\|blocked"}[1h])` | Children cut off or refused |
| Top-level step limit, token limit or block | `increase(llm4s_agent_runs_ended_total{status=~"step_limit_reached\|token_limit_reached\|blocked"}[1h])` | Turns cut off or refused; the trace's `blocked_by` names the guardrail |
| Provider errors | `sum by (provider) (rate(llm4s_requests_total{status!="success"}[5m])) / sum by (provider) (rate(llm4s_requests_total[5m]))`, `rate(llm4s_errors_total{error_type="rate_limit"}[5m])` | Per attempt: a retried call counts each try |
| Retries and open circuits (Micrometer) | `rate(llm4s_retries_total[5m])`, `increase(llm4s_circuit_breaker_transitions_total{state="open"}[10m]) > 0` | `ReliableClient` retrying or refusing calls |
| Retention sweep failing | `increase(llm4s_agent_sweep_passes_total{result="failed"}[1h]) > 0` | Old threads and entries not reclaimed |
| Spend | `sum by (model) (increase(llm4s_cost_usd_total[1h]))` | Estimated spend per model |

## See also

- [Monitoring](index): backends, configuration, the Prometheus endpoint, health checks
- [Micrometer metrics](micrometer) and [Provider exchange logging](provider-exchange-logging)
- [Running agents on several machines](../agents/multiple-machines): the queue, workers, recovery and retention
- [Streaming events](../agents/streaming): the durable events these traces are built from
- [Production monitoring](../patterns/production-monitoring) and [Production deployment](../production-deployment)
