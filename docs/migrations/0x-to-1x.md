---
layout: page
title: Migrating from 0.x to 1.0
parent: Migrations
nav_order: 10
---

# Migrating from 0.x to 1.0
{: .no_toc }

This guide consolidates all breaking changes introduced across the 0.x series and will be updated when v1.0 ships. If you are upgrading from a specific 0.x release, work through each section that applies to your starting version.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

The 0.x series established the core abstractions and steadily hardened the API surface. Three categories of breaking changes occurred:

| Version | Change type | Summary |
|---|---|---|
| 0.1.13 | Module restructure | Modules moved into `modules/` subdirectory; `DBx` and legacy codegen removed |
| 0.1.14 | Module removal | `DBx` module removed; use the RAG module instead |
| 0.3.0 | API removal | All throwing APIs removed; `Safe` variants required |
| 0.3.2 | Type rename | `LLMProvider` replaced by `ProviderKind` |

---

## `LLMProvider` replaced by `ProviderKind` (0.3.2)

> **Superseded.** `ProviderKind` has itself been replaced, by the opaque type `ProviderId`, as
> part of slice 4 ([#1131](https://github.com/llm4s/llm4s/issues/1131)). Coming from 0.2.x or
> earlier, follow the steps below and then apply
> [`ProviderKind` becomes `ProviderId`](../reference/migration.md); coming from 0.3.2 or later,
> go straight there.

The `LLMProvider` sealed trait was removed and replaced with `ProviderKind`. Any exhaustive pattern match on `LLMProvider` fails to compile with a missing-case error.

### Before (0.x)

```scala
import org.llm4s.llmconnect.provider.LLMProvider

config.provider match {
  case LLMProvider.OpenAI     => ...
  case LLMProvider.Anthropic  => ...
  case LLMProvider.Azure      => ...
  case LLMProvider.Ollama     => ...
  case LLMProvider.Gemini     => ...
}
```

### After (0.3.2+)

```scala
import org.llm4s.types.ProviderModelTypes.ProviderKind

config.provider match {
  case ProviderKind.OpenAI     => ...
  case ProviderKind.Anthropic  => ...
  case ProviderKind.Azure      => ...
  case ProviderKind.Ollama     => ...
  case ProviderKind.Gemini     => ...
}
```

### Migration steps

1. Replace imports of `org.llm4s.llmconnect.provider.LLMProvider` with `org.llm4s.types.ProviderModelTypes.ProviderKind`.
2. Replace all `LLMProvider.*` cases with their `ProviderKind.*` equivalents (e.g. `LLMProvider.OpenAI` → `ProviderKind.OpenAI`). The `config.provider` accessor name is unchanged — only its type changed from `LLMProvider` to `ProviderKind`.
3. Recompile — exhaustive pattern-match warnings will surface any remaining gaps.

---

## Throwing APIs removed (0.3.0)

In 0.2.x the following methods returned values directly but threw `IllegalStateException` on failure. They were deprecated in 0.2.x and **removed in 0.3.0**.

For the full before/after table and code examples see the dedicated guide:
[Migrating from v0.2.9 to v0.3.0](v0.2.9-to-v0.3.0.md)

Quick reference — replace every occurrence of the left-hand form with the right:

| Removed (throws) | Replacement (returns `Result[T]`) |
|---|---|
| `DateTimeTool.tool` | `DateTimeTool.toolSafe` |
| `CalculatorTool.tool` | `CalculatorTool.toolSafe` |
| `UUIDTool.tool` | `UUIDTool.toolSafe` |
| `JSONTool.tool` | `JSONTool.toolSafe` |
| `BuiltinTools.core` | `BuiltinTools.coreSafe` |
| `BuiltinTools.safe(cfg)` | `BuiltinTools.withHttpSafe(cfg)` |
| `BuiltinTools.withFiles(...)` | `BuiltinTools.withFilesSafe(...)` |
| `BuiltinTools.development(...)` | `BuiltinTools.developmentSafe(...)` |
| `agent.initialize(query, tools)` | `agent.initializeSafe(query, tools)` |
| `builder.build()` | `builder.buildSafe()` |

---

## `DBx` module removed (0.1.14)

The `DBx` module (PostgreSQL / vector-store scaffolding) was removed. Its functionality is superseded by the `rag` module.

### Migration steps

1. Remove `"org.llm4s" %% "llm4s-dbx" % version` from your `build.sbt`.
2. Add `"org.llm4s" %% "llm4s-core" % version` if not already present (the RAG pipeline lives in `core`).
3. Replace `DBx`-based vector operations with `RAGConfig` / `PgSearchIndex`.

```scala
// Before
import org.llm4s.dbx.VectorStore
val store = VectorStore.postgres(connectionString)

// After
import org.llm4s.rag.RAGConfig
val config = RAGConfig.default.withPgVector(connectionString)
```

---

## Module layout change (0.1.13)

All SBT modules moved from the repo root into the `modules/` subdirectory:

| Before | After |
|---|---|
| `core/` | `modules/core/` |
| `samples/` | `modules/samples/` |
| `workspace/` | `modules/workspace/` |

If you reference source paths directly (e.g. in IDE imports or custom scripts) update them accordingly. SBT artifact coordinates (`groupId`, `artifactId`) did **not** change *in 0.1.13*. They did change in 0.4.0 — see [Artifact coordinate rename (v0.4.0)](../reference/migration.md#artifact-coordinate-rename-v040).

---

## Environment variable renames

No environment variable was renamed in 0.x, but one set stopped being read. The unified `EMBEDDING_MODEL` format (`provider/model-name`) was *added* in 0.2.8 as the recommended form, and is still bound by llm4s-core's `reference.conf`.

Since 0.3.2 ([#903](https://github.com/llm4s/llm4s/pull/903)) llm4s no longer reads `LLM_MODEL`. Chat providers are configured as named sections under `llm4s.providers` in your `application.conf`. Each provider module binds its vendor's API-key variable (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, ...) to a shared `llm4s.credentials.<provider>.apiKey`, which a section without its own `apiKey` uses ([#1132](https://github.com/llm4s/llm4s/issues/1132)); other variables, such as `OLLAMA_BASE_URL`, are read only where a section binds them with `${?VAR}`. See [From `LLM_MODEL` to named provider sections](../reference/migration.md#from-llm_model-to-named-provider-sections).

---

## Build dependency changes

| Version | Change |
|---|---|
| 0.1.11 | Ollama provider added — no new dependency required (pure HTTP) |
| 0.2.0 | Cats `Validated` used internally — already a transitive dependency |
| 0.2.6 | PostgreSQL integration tests require a running Postgres with `pgvector` |

---

## Agent event stream replaced (#1329)

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

See the [streaming guide](../guide/agents/streaming.md).

---

## Provider and embedding configs gain `timeouts` (#712)

The provider config case classes (`OpenAIConfig`, `AzureConfig`, `AnthropicConfig`, `GeminiConfig`,
`VertexAIConfig`, `OllamaConfig`, `DeepSeekConfig`, `ZaiConfig`, `MistralConfig`, `CohereConfig`,
`OpenAICompatibleConfig`, `BedrockConfig`, `WatsonXConfig`) and `EmbeddingProviderConfig` take a trailing
`timeouts: ProviderTimeouts`, defaulting to `ProviderTimeouts.default` (each client's own defaults). There is
no overload with the old arity.

| Caller | Change |
|---|---|
| Scala source, `new X(...)` or `X(...)` | none - the default fills `timeouts`; recompile |
| Scala binaries compiled against the old constructor | recompile (the old signature is gone: `NoSuchMethodError`) |
| Kotlin | pass `ProviderTimeouts.default()` last, e.g. `EmbeddingProviderConfig(baseUrl, model, apiKey, ProviderTimeouts.default())` |
| Java | pass the default timeouts last; `default` is a Java keyword, so build them with `ProviderTimeouts.apply(scala.Option.empty(), scala.Option.empty())` |
| Pattern match `case X(a, b, c) =>` | add the field, or match `X(a, b, c, _)` |

To set timeouts from code, use `withTimeouts(ProviderTimeouts(...))`; from config, the section's
`timeouts { request = 3m, stream = 15m }` block (see
[Timeouts](../getting-started/configuration.md#timeouts)).

---

## Towards v1.0

> **Note:** v1.0 has not shipped yet. This section will be completed when the 1.0 release is cut.

Expected areas of change before 1.0:

- Decide on cross-module artifact split (`llm4s-core` vs `llm4s-agent` vs `llm4s-rag`)
- Remove all remaining `0.x`-deprecated symbols
- Enforce MiMa binary-compatibility checks against the 1.0 baseline (see [issue #924](https://github.com/llm4s/llm4s/issues/924))

Follow the [CHANGELOG](../../CHANGELOG.md) and [GitHub releases](https://github.com/llm4s/llm4s/releases) for announcements.
