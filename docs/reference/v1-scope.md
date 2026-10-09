---
layout: page
title: 1.0 Scope
parent: Reference
nav_order: 6
---

# 1.0 Scope

This page states which parts of the LLM4S API are safe to build on ahead of a 1.0 freeze.

Until 0.4.1 all of it shipped in one `core` Maven artifact of roughly 84k lines — agent runtime, RAG, MCP, speech, image, knowledge graph, and eleven provider clients together. There was no way to tell from the artifact alone which parts of that surface are meant to be a long-term contract and which are still moving fast.

This page describes the **target state** of the in-progress modularisation programme tracked in [#1126](https://github.com/llm4s/llm4s/issues/1126). Rows marked **carved** below already live in their target module; every other row still ships inside `llm4s-core`, and its target-module column says where it is heading, not where it lives today.

What has actually moved so far, in the build but not yet in a release:

| Slice | Modules carved | Status |
|---|---|---|
| [1](https://github.com/llm4s/llm4s/issues/1128) | `llm4s-rag`, `llm4s-knowledgegraph` | in the build, unpublished |
| [2](https://github.com/llm4s/llm4s/issues/1129) | `llm4s-memory`, `llm4s-memory-postgres` | in the build, unpublished |
| [3](https://github.com/llm4s/llm4s/issues/1130) | `llm4s-mcp`, `llm4s-media`, `llm4s-image`, `llm4s-speech` | in the build, unpublished |
| [4](https://github.com/llm4s/llm4s/issues/1131) | none - the provider registration SPI (`ProviderDescriptor`, discovered through `META-INF/services`) that slice 5's modules register through | in the build, unpublished |
| [5](https://github.com/llm4s/llm4s/issues/1132) | `llm4s-ollama`, `llm4s-gemini`, `llm4s-anthropic`, `llm4s-openai`, `llm4s-openai-compatible` (with Mistral and Cohere), `llm4s-voyage` - `llm4s-core` now holds no provider client | in the build, unpublished |
| [6](https://github.com/llm4s/llm4s/issues/1133) | `llm4s-observability` (Langfuse, the trace collector, `CostTracker`); `OpenTelemetryConfig` joins the existing `llm4s-observability-otel`; `llm4s-observability-prometheus` (Prometheus). The tracing and metrics contracts stay in `llm4s-core`, which declares no observability dependency | in the build, unpublished |
| [7](https://github.com/llm4s/llm4s/issues/1242) | `llm4s-agent-tools` (the built-in tools and their config), `llm4s-agent` (`agent`, `assistant`) | in the build, unpublished |

The latest release tag is `v0.4.1`, which is still a single `llm4s-core` (0.4.0 was the artifact rename only, [#1141](https://github.com/llm4s/llm4s/issues/1141)). The first release to publish separate module artifacts will be **0.5.0**, in slice 8 ([#1281](https://github.com/llm4s/llm4s/issues/1281)); it is also the MiMa baseline. Until it is published nothing is frozen: "Frozen at 1.0" is the tier a module will hold, not a constraint on changing it now.

## Maturity Legend

These definitions are shared with the [Roadmap](roadmap) so the two pages agree.

| Status | Meaning |
|--------|---------|
| **Frozen at 1.0** | Source and binary compatible within the 1.x series once published under its target module. This is the compatibility promise 1.0 makes. |
| **Beta** | Implemented and usable, but API, provider behavior, or docs still need hardening before v1.0. |
| **Experimental** | Useful prototype or advanced feature; expect changes. |
| **Planned** | Roadmap item, design, issue, or PR queue item; not a stable user contract. |

## Package Map

Every top-level package under `modules/core/src/main/scala/org/llm4s/`, its target module, and its tier.

| Package | Target module | Tier |
|---------|---------------|------|
| `types` | `llm4s-core` | Frozen at 1.0 |
| `error` | `llm4s-core` | Frozen at 1.0 |
| `config` | `llm4s-core` | Frozen at 1.0 |
| `model` | `llm4s-core` | Frozen at 1.0 |
| `toolapi` — the tool API: `ToolFunction`, `ToolRegistry`, schemas, execution | `llm4s-core` | Frozen at 1.0 |
| `toolapi/builtin`, `toolapi/tools` — the built-in tools (core utilities, filesystem, HTTP, shell, Brave/DuckDuckGo/Exa search) and `ToolsConfigLoader` — **carved** | `llm4s-agent-tools` | Beta |
| `context` | `llm4s-core` | Frozen at 1.0 |
| `util` | `llm4s-core` | Frozen at 1.0 |
| `syntax` | `llm4s-core` | Frozen at 1.0 |
| `identity` | `llm4s-core` | Frozen at 1.0 |
| `resource` | `llm4s-core` | Frozen at 1.0 |
| `core` | `llm4s-core` | Frozen at 1.0 |
| `http` | `llm4s-core` | Frozen at 1.0 |
| `security` | `llm4s-core` | Frozen at 1.0 |
| `llmconnect` (API only — see provider split below) | `llm4s-core` | Frozen at 1.0 |
| `reliability` | `llm4s-core` | Frozen at 1.0 |
| `agent` (excludes `agent/memory`) — **carved** | `llm4s-agent` | Frozen at 1.0 |
| `assistant` — **carved** | `llm4s-agent` | Beta |
| `trace` — the contract: `Tracing`, `TraceEvent`, `TracingComposer`, `TracingMode`, the `trace/spi` `TracingBackend` SPI, `NoOpTracing`, `ConsoleTracing`, and `TracingSettings` (in `llmconnect/config`) | `llm4s-core` | Frozen at 1.0 |
| `metrics` — the contract: `MetricsCollector` | `llm4s-core` | Frozen at 1.0 |
| `trace` — Langfuse (`LangfuseTracing`, its batch sender and `TracingBackend`), `TraceCollectorTracing`, `trace/model`, `trace/store`; `metrics` — `CostTracker` — **carved** | `llm4s-observability` | Beta |
| `trace` — OpenTelemetry (`OpenTelemetryTracing`, `OpenTelemetryConfig`) — **carved** | `llm4s-observability-otel` (`modules/trace-opentelemetry`) | Beta |
| `metrics` — Prometheus (`PrometheusMetrics`, `PrometheusEndpoint`, `MetricsConfigLoader`) — **carved** | `llm4s-observability-prometheus` | Beta |
| `llmconnect/provider` — OpenAI, Azure and Requesty (the providers sharing `OpenAIClient`) — **carved** | `llm4s-openai` | Frozen at 1.0 |
| `llmconnect/provider` — OpenRouter, DeepSeek, Z.ai and the generic `openai-compatible` provider, on one SDK-free `OpenAICompatibleClient` — **carved** | `llm4s-openai-compatible` | Frozen at 1.0 |
| `llmconnect/provider` — Anthropic — **carved** | `llm4s-anthropic` | Frozen at 1.0 |
| `llmconnect/provider` — Gemini (and Vertex AI) — **carved** | `llm4s-gemini` | Frozen at 1.0 |
| `llmconnect/provider` — Ollama — **carved** | `llm4s-ollama` | Frozen at 1.0 |
| `llmconnect/provider` — Mistral and Cohere, as dialects on `OpenAICompatibleClient` — **carved** | `llm4s-openai-compatible` | Beta |
| `llmconnect/provider` — Voyage AI embeddings — **carved** | `llm4s-voyage` (`modules/providers/voyage`) | Beta |
| `llmconnect/provider` — AWS Bedrock (Converse, ConverseStream) — **new** | `llm4s-bedrock` (`modules/providers/bedrock`) | Beta |
| `llmconnect/provider` — Jina AI embeddings — **new** | `llm4s-jina` (`modules/providers/jina`) | Beta |
| `llmconnect/provider` — Cohere embeddings, on Cohere's native `/v2/embed` (Cohere chat is a dialect in `llm4s-openai-compatible`) — **new** | `llm4s-cohere` (`modules/providers/cohere`) | Beta |
| `llmconnect/provider` — IBM watsonx.ai, on the text-generation endpoints IBM has deprecated (never run against the live service; migration to the chat API is [#1314](https://github.com/llm4s/llm4s/issues/1314)) — **new** | `llm4s-watsonx` (`modules/providers/watsonx`) | Beta |
| `llmconnect/provider` — other community providers | `llm4s-openai-compatible` dialects, or `modules/providers/<name>` | Beta |
| `testkit` — the checks a provider module's `Llm4s<Name>ModuleSpec` makes (`ProviderModuleChecks`, `ProviderTestConfig`, `CredentialsRoundTrip`, `LocalProviderTestServer`), formerly unpublished helpers in core's test sources — **new** | `llm4s-provider-testkit` (`modules/provider-testkit`), a test-scope dependency; requires JDK 21 (virtual threads) | Beta |
| build — the bill of materials that pins every published artifact to one version (no code) — **new** | `llm4s-bom` (`modules/bom`) | Beta |
| `rag`, `vectorstore`, `chunking`, `reranker`, `eval` — **carved** | `llm4s-rag` | Beta |
| `extract` (consolidated from `rag/extract` + `llmconnect/extractors`) and `rag/embed` (from `llmconnect/encoding`) — **carved** | `llm4s-rag` | Beta |
| `agent/memory` (excluding `PostgresMemoryStore`) — **carved** | `llm4s-memory` | Beta |
| `agent/memory/PostgresMemoryStore` — **carved** | `llm4s-memory-postgres` | Beta |
| `mcp` - **carved** | `llm4s-mcp` | Beta |
| `knowledgegraph/neo4j` — the Neo4j graph store (`Neo4jGraphStore`) | `llm4s-knowledgegraph-neo4j` (`modules/knowledgegraph-neo4j`) | Experimental |
| `workspace`, `shared`, `codegen`, `toolapi/WorkspaceTools` — containerised workspace execution: the client (`ContainerisedWorkspace`, `WorkspaceTools`, the code-generation worker) and the wire protocol it speaks to the runner image (`WorkspaceAgentProtocol`, `WorkspaceAgentInterface`) | `llm4s-workspace-client`, `llm4s-workspace-shared` (`modules/workspace`) | Experimental |
| `media` - **new** | `llm4s-media` | Beta |
| `speech` - **carved** | `llm4s-speech` | Experimental |
| `imagegeneration`, `imageprocessing` - **carved** | `llm4s-image` | Experimental |
| `knowledgegraph` — **carved** (`knowledgegraph/graphrag` ships in `llm4s-rag`) | `llm4s-knowledgegraph` | Experimental |
| `javaapi` — the Java facade (`Llm4s`, `JLlmClient`, `JAgent`, `AgentStream`, `AgentStreamListener`, `StreamEvents`, `Answer`, `PendingInterrupt`, `InterruptKind`, `ConversationBuilder`, `LlmResult`, `LlmException`) — **new** | `llm4s-java-api` (`modules/java-api`) | Beta |
| `spring` — Spring Boot auto-configuration (`Llm4sAutoConfiguration`, `Llm4sProperties`, `LLM4STemplate`, `LlmHealthIndicator`) — **new** | `llm4s-spring-boot-starter` (`modules/spring-boot-starter`) | Beta |
| `effect.cats` — `LLMClientIO`, `AgentIO` (cats-effect 3, fs2) — **new** | `llm4s-effect` (`modules/llm4s-effect`) | Beta |
| `zio` — `LLMClientZ`, `AgentZ` (ZIO 2, ZIO Streams) — **new** | `llm4s-zio` (`modules/llm4s-zio`) | Beta |
| `kotlin` — coroutine API (`LLMClientKt`, `AgentKt`, `AgentStreamItem`); a separate Gradle build, not part of sbt or the MiMa baseline, not yet published — **new** | `modules/kotlin-api` | Experimental |

Notes:

- `llmconnect/provider` in `llm4s-core` now holds only the plumbing every provider module shares - cost estimation, HTTP error mapping, metrics recording, the `EmbeddingProvider` trait, exchange recording - and no client. With the registration SPI in `llmconnect/spi` from [slice 4](https://github.com/llm4s/llm4s/issues/1131) and the streaming and request helpers providers build on, it is a **frozen provider-author SPI**, public so that a provider can be published outside this repository: a provider is a `ProviderDescriptor` listed in its own module's `Llm4sProviderModule`, so adding one adds a dependency and edits nothing in core. See [Writing a provider](../guide/writing-a-provider). Helpers specific to one wire format live with it - `ResponseFormatMapper` and `ToolCallDeserializer` in `llm4s-openai-compatible`, OpenAI's model-name rules in `llm4s-openai` - and helpers only llm4s's own modules use, such as `ProviderResultOps`, are `private[llm4s]`. Vendor API keys (`llm4s.credentials.<provider>.apiKey`) are read by core's `config`, but each key is bound in its provider module's `reference.conf`, beside the descriptor that uses it.
- The tracing and metrics **contracts** stay in `llm4s-core`, and they are what is frozen: `llmconnect`, the agent runtime and every provider module trace and record metrics through them alone. The **integrations** are separate modules that plug into that contract - a tracing backend is a `TracingBackend` registered in `META-INF/services`, discovered by `TRACING_MODE`, the way a provider is a `ProviderDescriptor`. They are Beta, like every integration module other than the frozen provider modules: Langfuse's ingestion format and OpenTelemetry's SDK move on their vendors' schedule, not ours, and a new tracing backend should be a new module rather than a change to the frozen surface. See the [migration guide](migration#slice-6-llm4s-observability---langfuse-the-trace-collector-and-costtracker-leave-core).
- Rows marked **carved** already live in their target sbt module. Their package names are unchanged, so this is a build-file change for users, not an import rewrite — with one exception, `org.llm4s.extract`, described in the [migration guide](migration#slice-1-llm4s-rag-and-llm4s-knowledgegraph).
- `org.llm4s.media` is a new package, not a carve. It consolidates the three overlapping image-format enumerations `llm4s-core` had accumulated, so that `llm4s-image` and `llm4s-speech` carve as pure file moves rather than moves plus a vocabulary change. It is the one part of slice 3 with a source break, described in the [migration guide](migration#slice-3-llm4s-media); it is deliberate and taken now rather than after 1.0.
- `org.llm4s.extract` is a new package name, not a rename of an existing one — see [Slice 1](https://github.com/llm4s/llm4s/issues/1128) for why the two extractors were consolidated rather than just moved.
- `org.llm4s.knowledgegraph.graphrag` keeps its package name but ships in `llm4s-rag`, not `llm4s-knowledgegraph`. `GraphRAG` and `vectorstore` referenced each other, which made the two modules inseparable; moving the one file that reaches into `vectorstore` broke the cycle. Package names track the API; module boundaries track the dependency graph, and here they disagree.
- `llm4s-memory` splits in two. `PostgresMemoryStore` was the only file in `agent/memory` that needed a connection pool and a server-side driver, so it ships as `llm4s-memory-postgres`; keeping it with the rest would mean anyone using agent memory at all inherits HikariCP and the Postgres JDBC driver. `llm4s-memory` itself carries only sqlite-jdbc, for the two file-backed stores.
- `org.llm4s.vectorstore.PostgresVectorHelpers` ships in `llm4s-core`, not in `llm4s-rag` with the rest of `org.llm4s.vectorstore`. It is a pure `Array[Float]` ⇄ pgvector-text codec with no JDBC types in it, and it has consumers in two modules that must not depend on each other (`llm4s-rag` and `llm4s-memory-postgres`); the module they share is core. This is the same package-versus-module disagreement as `graphrag`, in the other direction.
- Vertex AI ships in `llm4s-gemini`. It only calls Google's Gemini models, in the same JSON format as the Gemini API, and differs in endpoint and OAuth authentication rather than in dependencies - so bundling costs Gemini-API users nothing, whereas splitting it out after release would be a breaking move. See the [migration guide](migration#slice-5-llm4s-gemini).

## What Frozen means

- **Source and binary compatible within 1.x.** Once `0.5.0` publishes the split modules, `mimaPreviousArtifacts` enforces binary compatibility on every Frozen module for all subsequent 1.x releases.
- **Deprecate before removing.** A Frozen API is only removed after a deprecation cycle, never dropped outright in a minor release. See the [Compatibility and Deprecation Policy](compatibility-policy).
- **Beta and Experimental can move faster.** They may change in a minor release, but a migration note ships with the change in the same release's CHANGELOG.

## Scala and JDK support

1.0 targets **Scala 3 only (3.7.1)**. Scala 2.13 support is deferred to post-1.0 and, if it happens, would target the frozen spine (`llm4s-core`, `llm4s-agent`, and the frozen provider modules) rather than the full tree. The tracing and metrics contracts are part of `llm4s-core`; the observability integration modules are not in the spine. JDK 21 is used in CI. `llm4s-provider-testkit` requires JDK 21: its interruption checks and `LocalProviderTestServer` run on virtual threads ([#1582](https://github.com/llm4s/llm4s/issues/1582)). The minimum JDK for the other artifacts is decided in [#1493](https://github.com/llm4s/llm4s/issues/1493).

See [#1126](https://github.com/llm4s/llm4s/issues/1126) for the reasoning behind the Scala-3-only decision.

## Programme status

For current progress against this target structure, see the tracking issue [#1126](https://github.com/llm4s/llm4s/issues/1126) and its slice sub-issues.
