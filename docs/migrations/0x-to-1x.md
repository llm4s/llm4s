---
layout: page
title: Migrating from 0.x to 1.0
description: "The upgrade path to LLM4S 1.0 from any 0.x release: which migration notes apply to your starting version, release by release."
search_title: "Migrate LLM4S from 0.x to 1.0"
parent: Migrations
nav_order: 10
---

# Migrating from 0.x to 1.0
{: .no_toc }

The upgrade path to 1.0 from any 0.x release. Find the release you are on, then work through its section and every
section above it. The notes themselves are in the [Migration Guide](../reference/migration), the canonical record of
every breaking change; this page says which of them apply to you.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## The road to 1.0

The releases left are few: **0.7.0** sets the binary-compatibility baseline (MiMa) for the seven frozen modules,
`llm4s-agent` included, and **1.0** follows it. Until 0.7.0 any release may break source or binary compatibility, each
break listed under the CHANGELOG's *Breaking changes* with a note in the [Migration Guide](../reference/migration). What
the baseline and 1.0 then promise is in the [Compatibility and Deprecation Policy](../reference/compatibility-policy).

**Scalafix.** The mechanical source breaks since 0.4.1 have Scalafix rules
([Scalafix: migration rules](../reference/scalafix#migration-rules)). They are first published at 1.0, as
`org.llm4s::llm4s-scalafix`; until then they exist only as source in this repository, and each note says what to
change by hand.

---

## From 0.6.0

The breaking changes since 0.6.0, in the CHANGELOG's `[Unreleased]` section:

- Generation and model calls:
  [`budgetTokens` and `ReasoningEffort.None` are replaced by `Thinking`](../reference/migration#budgettokens-and-reasoningeffortnone-are-replaced-by-thinking-2115),
  [finish reasons are typed](../reference/migration#finish-reasons-are-typed-2613),
  [every provider's content-filter stop is an error](../reference/migration#every-providers-content-filter-stop-is-an-error-2677),
  [a stream that did not finish is an error](../reference/migration#a-stream-that-did-not-finish-is-an-error-2613),
  [a broken or cut tool call is an error](../reference/migration#a-broken-or-cut-tool-call-is-an-error-2613),
  [`TokenUsage` and `ModelPricing` gain a prompt-cache field](../reference/migration#tokenusage-and-modelpricing-gain-a-prompt-cache-field-2115),
  [the forwarding `apply` overloads are removed](../reference/migration#the-forwarding-apply-overloads-are-removed-1281).
- Agents:
  [a cut-off answer ends `Truncated`, not `Completed`](../reference/migration#a-cut-off-answer-ends-truncated-not-completed-2613),
  [`CompressionRule`'s `apply` field is `transform`](../reference/migration#compressionrules-apply-field-is-transform-1281),
  [context pruning enforces both limits and `minRecentTurns`](../reference/migration#context-pruning-enforces-both-limits-and-minrecentturns-2728-2729),
  [the PostgreSQL checkpoint schema moves to version 6](../reference/migration#the-postgresql-checkpoint-schema-moves-to-version-6-2453),
  [a workspace container has no egress](../reference/migration#a-workspace-container-has-no-egress-870).
- RAG:
  [a RAG with a `SearchIndex` ingests only with permissions](../reference/migration#a-rag-with-a-searchindex-ingests-only-with-permissions-2716),
  [`DocumentRegistry` is source-scoped](../reference/migration#documentregistry-is-source-scoped-2714),
  [RAG tracing covers every model call and each query's stages](../reference/migration#rag-tracing-covers-every-model-call-and-each-querys-stages-2734),
  [`RAG.ingestChunks` takes `DocumentChunk`s](../reference/migration#ragingestchunks-takes-documentchunks-2732),
  [RAG loaders take a `UrlPolicy`, and `DirectoryLoader` stays in its root](../reference/migration#rag-loaders-take-a-urlpolicy-and-directoryloader-stays-in-its-root-2724).
- Configuration:
  [the legacy embedding provider selector is removed](../reference/migration#the-legacy-embedding-provider-selector-is-removed),
  [Vertex AI no longer reads `endpoint` and `organization`](../reference/migration#vertex-ai-no-longer-reads-endpoint-and-organization).

---

## From 0.5.1 to 0.6.0

The opening of the [Migration Guide](../reference/migration) lists every 0.6.0 break with its note: chiefly the
growth-safe types (build with `apply`, change with `with*`, no public `copy`), `Secret` credentials, the `fromValues`
argument order, `Completion.created` as an `Instant`, content-free console tracing and the OpenTelemetry GenAI span
names, and `llm4s-java-api` and the Spring Boot starter no longer bringing the provider modules. The behaviour changes
that no rewrite covers are collected in
[Changes since 0.5.1 that no rule automates](../reference/migration#changes-since-051-that-no-rule-automates); the
subagent and production-adapter changes in [Subagents and isolation](../reference/migration#subagents-and-isolation-phase-4)
and [Production adapters](../reference/migration#production-adapters-stage-5).

---

## From 0.5.0 to 0.5.1

Two source breaks:
[multimodal content parts](../reference/migration#multimodal-content-parts-1913) (`UserMessage`/`ToolMessage` carry
`parts`) and [harness profile and middleware placement](../reference/migration#harness-profile-and-middleware-placement-1915).

---

## From 0.4.1 to 0.5.0

The modularisation release: `llm4s-core` splits into per-concern artifacts, the agent runtime is rewritten and API
keys are read from each vendor's variable. Follow [Upgrading from 0.4.1 to 0.5.0](0-4-1-to-0-5-0), one page grouped by
what you use. Two of its changes are detailed here.

### Agent event stream replaced (#1329)

The `AgentEvent` hierarchy and `runWithEvents` are replaced by `Agent.stream` and the `AgentEvents`
vocabulary; `TraceEvent.AgentStateUpdated` is replaced by `TraceEvent.AgentRunEnded`.

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

`await()` returns once the listener has returned from the run's last event, so whatever it collected
is complete. `AgentRunEnded.usage` is the run's own usage, not the thread's: a dashboard that summed
the old cumulative figure per run double counted. A slow `AgentIO.stream`/`AgentZ.stream` consumer
loses live events and gets a `StreamEvent.LiveGap` with their count; it no longer cancels the run.

See the [streaming guide](../guide/agents/streaming).

### Provider and embedding configs gain `timeouts` (#712)

The provider config case classes (`OpenAIConfig`, `AzureConfig`, `AnthropicConfig`, `GeminiConfig`,
`VertexAIConfig`, `OllamaConfig`, `DeepSeekConfig`, `ZaiConfig`, `MistralConfig`, `CohereConfig`,
`OpenAICompatibleConfig`, `BedrockConfig`, `WatsonXConfig`) and `EmbeddingProviderConfig` take
`timeouts: ProviderTimeouts`, defaulting to `ProviderTimeouts.default` (each client's own defaults). In 0.6.0 these
types became growth-safe: build them with `apply` and set the field with `withTimeouts(ProviderTimeouts(...))`; from
config, use the section's `timeouts { request = 3m, stream = 15m }` block (see
[Timeouts](../getting-started/configuration#timeouts)).

---

## From 0.4.0 or earlier

- **Artifact coordinates.** 0.4.0 renamed every artifact to `llm4s-*`:
  [Artifact coordinate rename (v0.4.0)](../reference/migration#artifact-coordinate-rename-v040).
- **`LLM_MODEL` is not read** (since 0.3.2): chat providers are named sections under `llm4s.providers`, and each
  provider module binds its vendor's API-key variable to `llm4s.credentials.<provider>.apiKey`. See
  [From `LLM_MODEL` to named provider sections](../reference/migration#from-llm_model-to-named-provider-sections).
- Then the 0.4.1 to 0.5.0 page above, and every section after it.

### `LLMProvider` replaced by `ProviderKind` (0.3.2)

The `LLMProvider` sealed trait was replaced by `ProviderKind` in 0.3.2, and `ProviderKind` itself by the opaque type
`ProviderId` in 0.5.0 ([#1131](https://github.com/llm4s/llm4s/issues/1131)). Coming from any release before 0.5.0, go
straight to [`ProviderKind` becomes `ProviderId`](../reference/migration#providerkind--providerid): a `match` on the
provider becomes a comparison with a `ProviderId`.

### Throwing APIs removed (0.3.0)

The methods that returned values directly and threw `IllegalStateException` on failure were removed in 0.3.0 in favour
of their `Result`-returning variants: [Migrating from v0.2.9 to v0.3.0](v0.2.9-to-v0.3.0). Names have moved on since
(the built-in tools are in `llm4s-agent-tools`, and the agent API was rewritten in 0.5.0), so apply that page and then
the 0.4.1 to 0.5.0 page.

### `DBx` module removed (0.1.14)

The `DBx` module (PostgreSQL and vector-store scaffolding) was removed. Its replacement is `llm4s-rag`, with
`PgVectorStore` and `PgSearchIndex`; see
[Slice 1: `llm4s-rag`](../reference/migration#slice-1-llm4s-rag-and-llm4s-knowledgegraph).

### Module layout change (0.1.13)

The sbt modules moved from the repository root into `modules/` (`core/` to `modules/core/`, and so on). Artifact
coordinates did not change in 0.1.13; they changed in 0.4.0, above.

---

## When 1.0 ships

The 1.0 release adds its own section here, with the one-line Scalafix command. Follow the
[CHANGELOG](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md) and the
[GitHub releases](https://github.com/llm4s/llm4s/releases) for announcements.
