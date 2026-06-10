---
layout: page
title: API Stability
parent: Reference
nav_order: 6
---

# API Stability

This document defines which packages are part of the **stable public API** and which are **internal**. Binary compatibility is enforced between releases using [MiMa](https://github.com/lightbend/mima) for all public API packages.

---

## Stability Contract

| Release type | Guarantee |
|---|---|
| **Patch** (0.x.y → 0.x.z) | No binary-breaking changes in public API |
| **Minor** (0.x → 0.y) | Binary-breaking changes allowed with `@deprecated` migration path |
| **v1.0.0+** | Full SemVer — MAJOR version only for breaking changes |

MiMa runs on every PR and blocks merges that introduce binary-incompatible changes to the public API without an explicit exclusion filter.

---

## Public API (Stable)

These packages are covered by the compatibility guarantee. Changes that break binary compatibility here will fail CI.

| Package | Contents |
|---|---|
| `org.llm4s.llmconnect` | `LLMClient`, `LLMConnect`, `Completion`, `Conversation`, `CompletionOptions`, `StreamedChunk` |
| `org.llm4s.agent` | `Agent`, `AgentState`, `Handoff`, streaming events |
| `org.llm4s.agent.guardrails` | `Guardrail` trait, `CompositeGuardrail` |
| `org.llm4s.agent.guardrails.builtin` | All built-in guardrails |
| `org.llm4s.agent.memory` | `MemoryManager`, `MemoryStore` traits and built-in stores |
| `org.llm4s.toolapi` | `ToolRegistry`, `ToolFunction`, `Schema`, `SchemaDefinition` |
| `org.llm4s.toolapi.builtin` | All built-in tools |
| `org.llm4s.types` | All newtypes (`ModelName`, `ApiKey`, `ConversationId`, etc.) |
| `org.llm4s.error` | Full error hierarchy (`LLMError` and all subtypes) |
| `org.llm4s.config` | `Llm4sConfig` public methods only |
| `org.llm4s.reliability` | `ReliableClient`, `ReliabilityConfig` |
| `org.llm4s.metrics` | `MetricsCollector`, `PrometheusMetrics`, `PrometheusEndpoint` |
| `org.llm4s.trace` | `Tracing` trait, `TraceEvent` |

---

## Internal API (Unstable)

These packages are **not covered** by the compatibility guarantee. They may change in any release without notice.

| Package | Reason |
|---|---|
| `org.llm4s.llmconnect.provider.*` | Provider implementations — internal HTTP/SDK wiring |
| `org.llm4s.llmconnect.caching.*` | Internal caching layer |
| `org.llm4s.llmconnect.encoding.*` | Internal token encoding |
| `org.llm4s.config.RawProviders*` | Raw config parsing internals |
| `org.llm4s.config.NamedProvider*` | Named provider loading internals |
| `org.llm4s.config.ProvidersConfig*` | Config model internals |
| `org.llm4s.rag.loader.internal.*` | RAG loader internals |

If you find yourself importing from an internal package, please open an issue — it likely means the public API is missing something.

---

## Adding a Binary-Incompatible Change

If you need to make a breaking change to the public API (rename, remove, or change a method signature):

1. Add a `@deprecated` version of the old API pointing to the new one
2. Add a `ProblemFilters.exclude` entry in `build.sbt` under `mimaBinaryIssueFilters`
3. Document the change in `CHANGELOG.md` under the next release version

```scala
// build.sbt — example exclusion for an intentional breaking change
mimaBinaryIssueFilters ++= Seq(
  ProblemFilters.exclude[DirectMissingMethodProblem]("org.llm4s.agent.Agent.run")
)
```

4. The exclusion **must** include a comment explaining why the break is intentional.

---

## Checking Compatibility Locally

```bash
sbt mimaReportBinaryIssues
```

This reports all binary incompatibilities between the current code and the previous release (`0.3.2`). Zero output means the public API is compatible.
