---
layout: page
title: Roadmap
parent: Reference
nav_order: 5
---

# LLM4S Roadmap

This page reflects the pre-1.0 roadmap as of **October 2026**: the latest release is **v0.4.1**, and the work on `main` is heading for **0.5.0**. It replaces the June 2026 snapshot, which still named v0.3.2 as current, and it links the community mentorship tracks ([LFX](https://github.com/llm4s/llm4s/blob/main/LFX%20Mentorship/Project%20Ideas/2026.md), [ESoC](https://github.com/llm4s/llm4s/blob/main/European%20Summer%20of%20Code/Project%20Ideas/2026.md)) to the phases they advance. Which packages 1.0 freezes is stated on [1.0 Scope](v1-scope); this page is the plan for getting there.

LLM4S has broad, working framework functionality today: multi-provider clients (including Vertex AI and AWS Bedrock), agents, tool calling, RAG/vector stores, GraphRAG/Neo4j, memory, guardrails (including prompt-injection detection), tracing, metrics, JMH benchmarks, reliability wrappers, workspace isolation, MCP transports with auth hardening, multimodal APIs (vision, image generation, speech), and Java, Kotlin, Spring Boot, cats-effect and ZIO integrations. The code that used to ship as one `core` artifact is being split into per-concern modules ([#1126](https://github.com/llm4s/llm4s/issues/1126)); that split completes with 0.5.0. The remaining v1.0 work is productization: a published modular release, a binary-compatibility baseline, provider capability parity, default-deny tool/MCP security, unified cost telemetry, deterministic CI, runnable docs, and polished reference applications.

## Quick Status

| | |
|---|---|
| **Latest release tag** | v0.4.1 (2026-08-29) · see the [CHANGELOG](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md) |
| **Release sequence** | 0.4.x: coordinate rename and fixes (**done**) → **0.5.0**: modularisation complete, separate artifacts published, MiMa baseline set → **1.0**: frozen surface, after it has survived two releases |
| **Main branch** | Unreleased work for 0.5.0: the carved modules, provider SPI, Java/Kotlin/Spring/effect/ZIO modules, stability tiers, deployment template |
| **Artifacts** | `org.llm4s:llm4s-*`. The pre-0.4.0 coordinates (`org.llm4s:core`, …) stop at 0.3.4; see the [migration guide](migration#artifact-coordinate-rename-v040) |
| **Stability** | Pre-1.0, API stabilizing; each package's tier is on [1.0 Scope](v1-scope) |
| **Scala support** | Scala 3.7.1 only. Scala 2.13 is deferred to after 1.0 ([#874](https://github.com/llm4s/llm4s/issues/874)) |
| **Java support** | JDK 21 recommended and used in CI |
| **Target** | v1.0 production-ready stable modules |
| **Timeline** | 2026 H2 stabilization phases; the v1.0 date is intentionally not fixed |
| **Mentorship** | LFX Mentorship 2026 · European Summer of Code 2026 · GSoC 2026 |

## Maturity Legend

These tiers match [1.0 Scope](v1-scope), so the two pages agree.

| Maturity | Meaning |
|----------|---------|
| **Frozen at 1.0** | Source and binary compatible within the 1.x series once published under its target module. |
| **Beta** | Implemented and usable, but API, provider behavior, or docs still need hardening before v1.0. |
| **Experimental** | Useful prototype or advanced feature; expect changes. |
| **Planned** | Roadmap item, design, issue, mentorship track, or PR queue item; not a stable user contract. |

The **Status** column in the phase tables below is separate from maturity: **Landed** means merged to `main` (some of it ships first in 0.5.0), **In progress** means partly done, and **Planned** means not started.

## Current Capability Map

| Area | Current state | v1.0 gap |
|------|---------------|----------|
| **Provider clients** | Chat: OpenAI, Anthropic, Azure OpenAI, Gemini, **Vertex AI**, **AWS Bedrock**, IBM watsonx.ai (Beta), DeepSeek, Cohere, Mistral, OpenRouter, Requesty, Z.ai, Ollama, and a generic `openai-compatible` provider configured from a named section. Embeddings: OpenAI, Voyage, Jina, Ollama. Every provider is a module found on the classpath through the `ProviderDescriptor` SPI, and `llm4s-provider-testkit` lets an external author prove one. | Publish a **generated** provider capability matrix, and grow the testkit into fake-provider contract tests for chat, streaming, tools, structured output, embeddings, image/audio, timeouts, retries, cost, and raw exchange logging. *(ESoC track)* |
| **Agents** | `llm4s-agent`: core agents, tool calling, handoffs, guardrails (incl. prompt-injection detector), streaming events, async tools, reasoning modes, and state serialization; memory (incl. LLM entity extraction) in `llm4s-memory`. A typed graph runtime (middleware, approval barriers, checkpoints, cancellation) exists as a prototype in `llm4s-agent` ([#1266](https://github.com/llm4s/llm4s/issues/1266)); [1.0 Scope](v1-scope) does not tier it yet. | Publish the agent API, which 1.0 Scope tiers **Frozen at 1.0**, in 0.5.0; settle the graph runtime's tier and graduate it from prototype; durable session stores; Temporal workflows; A2A interop; voice agent loop; replay/debug; compile-test reference apps. *(LFX + ESoC tracks)* |
| **RAG and vector stores** | `llm4s-rag` and `llm4s-knowledgegraph`: document loading, chunking, SQLite/pgvector/Qdrant, keyword indexes, hybrid search, reranking, RAGAS-style evaluation, permission-aware RAG, `WebCrawlerLoader` (BFS/robots/rate limits), GraphRAG with an optional Neo4j store. | Sitemap + concurrent + JS crawl; unified cost/latency across RAG; runnable golden-path tutorials; production reference deployments. *(LFX track)* |
| **Tooling and MCP** | The tool API stays in `llm4s-core`; the built-in tools are in `llm4s-agent-tools`; `llm4s-mcp` has client and server, Streamable HTTP, HTTP+SSE, bearer-token auth, and public-bind protection; workspace isolation is in `llm4s-workspace-*`. | Default-deny policies, allowlists, capability scoping, audit logs, tool-poisoning mitigations, computer-use tool, strict schema validation. *(ESoC tracks)* |
| **Observability** | Console tracing in core; Langfuse, the trace collector and `CostTracker` in `llm4s-observability`; OpenTelemetry in `llm4s-observability-otel`; Prometheus in `llm4s-observability-prometheus`; tracing backends are discovered through an SPI. Image-generation cost hooks and agent usage summaries exist. | Unified cost/token telemetry across request/agent/session/RAG/multimodal; retention/redaction guidance; agent run replay; regression thresholds on JMH. *(ESoC track)* |
| **Reliability** | `ReliableClient` supports retry, circuit breaker, deadlines, rate limiting and metrics; every client sends through one HTTP layer that honours a 503's `Retry-After`; a sample exists. | Multi-provider **failover & hedging**; consistent timeouts/retries across providers; health checks. *(LFX track)* |
| **JVM interop** | `llm4s-java-api` (Java-friendly layer), a Kotlin coroutine API, `llm4s-spring-boot-starter`, `llm4s-effect` (cats-effect) and `llm4s-zio`, plus a Gradle integration guide and `gradle-demo`. These are on `main` and ship in 0.5.0. | Publish them in 0.5.0; Maven quickstart; CI-tested sample projects for each path. |
| **Docs and examples** | Broad docs and samples, migration guides (`0x-to-1x`), troubleshooting, [1.0 Scope](v1-scope), the [compatibility policy](compatibility-policy), GOVERNANCE/MAINTAINERS/RELEASES, Scaladoc for every module, and install snippets that follow the latest release (checked by `scripts/check-doc-versions.sh`). | Remove/label remaining pseudocode placeholders; runnable golden-path tutorials from capability metadata. |
| **Security and governance** | Secret scanning, API-key redaction, workspace sandboxing, Dependabot, a **threat model** ([`security.md`](security)), config-policy module + CI gate, MCP auth hardening, DCO checks. | SBOM, default-deny tool/MCP policies, audit guidance, release-gate security suite. *(ESoC track)* |

## Production Readiness Pillars

| Pillar | Current status | Next deliverable |
|--------|----------------|------------------|
| **Testing and CI** | Broad suite, Ubuntu/Windows CI, integration suites in tiers (`@Local`, `@Docker`, `@Workspace`, `@Ollama`, `@Cloud`) enforced by `it/itTierCheck`, per-module coverage floors, DCO, config-policy check. | Suite timeouts / hang isolation; split fast/integration/Docker/provider/benchmark jobs. |
| **API stability** | `@Stable`/`@Experimental` mark a public type's tier in the code; a [compatibility and deprecation policy](compatibility-policy) is published; `frozenDependencyCheck` and `publishedArtifactsCheck` guard the frozen modules and the artifact list; MiMa is wired into the build. | Publish 0.5.0, set the MiMa baseline there, and enforce it from 1.0 once the frozen surface has survived two releases. |
| **Provider parity** | Broad coverage, one SPI, a provider testkit; uneven feature parity. | Generated capability matrix + fake-provider contracts. |
| **JVM adoption** | Strong Scala-native design; Java, Kotlin, Spring Boot, cats-effect and ZIO modules on `main`. | Publish them; Maven and Gradle paths with CI-tested runnable samples. |
| **Security** | Threat model, Dependabot, MCP auth/bind, redaction, config-policy, prompt-injection detector. | Default-deny tool/MCP, allowlists, audit logs, SBOM, release-gate tests. |
| **Performance and cost** | JMH module, `CostTracker`, image/agent usage hooks. | Unified cost telemetry; JMH regression thresholds; failover/hedging. |
| **Documentation trust** | Migrations, troubleshooting, governance docs, 1.0 scope, versioned install snippets, many samples. | Golden-path tutorials generated from tested capability metadata. |

## 2026 H2 Stabilization Roadmap

### Phase 0: Stabilize The Signal

Priority: immediate · partially advanced.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Align README, roadmap, release tags, Scala/JDK, provider status | **In progress** | New users see one coherent project state. |
| CHANGELOG + migration guides | **Landed** | Upgrade path from 0.x documented. |
| Threat model + governance docs | **Landed** | Security/governance baselines exist. |
| Integration suites declare a tier, enforced in the build | **Landed** | A suite that nothing runs fails the build. |
| Publish a maturity tier for every package | **Landed** | [1.0 Scope](v1-scope) says which packages are safe to build on. |
| Fix or isolate hanging `sbt test` suites; suite timeouts | **Planned** | CI cannot silently hang. |
| Remove/label `???` placeholders in user-facing docs | **Planned** | Guides are runnable or explicitly pseudocode. |

### Phase 1: Define The Stable Spine

Target: **0.5.0** for the baseline, **1.0** for enforcement.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Modularisation: `modules/core` split into per-concern artifacts ([#1126](https://github.com/llm4s/llm4s/issues/1126); slices [#1127](https://github.com/llm4s/llm4s/issues/1127)–[#1133](https://github.com/llm4s/llm4s/issues/1133), [#1242](https://github.com/llm4s/llm4s/issues/1242)) | **In progress** | Carved in the build; published as separate artifacts with 0.5.0 ([#1281](https://github.com/llm4s/llm4s/issues/1281)). |
| Name stable module boundaries for v1.0 | **Landed** | [1.0 Scope](v1-scope) names each package's tier (Frozen at 1.0, Beta or Experimental). |
| Stability tiers in code; compatibility and deprecation policy | **Landed** | `@Stable`/`@Experimental`; [policy](compatibility-policy) published. |
| MiMa binary-compatibility checks | **In progress** | Wired in the build; baseline set at **0.5.0**, enforced from **1.0** once the frozen surface has survived two releases. |
| Package-level Scaladoc | **In progress** | Public API intent documented; the published Scaladoc covers every module. |
| Deferred Scala 2.13 support for the frozen spine | **Deferred** | After 1.0 ([#874](https://github.com/llm4s/llm4s/issues/874)); 1.0 is Scala 3 only. |

### Phase 2: Provider Capability Matrix And Contract Tests

Target: after the stable spine · **ESoC mentorship track**.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Provider SPI, discovery on the classpath, `llm4s-provider-testkit` | **Landed** | Adding a provider is adding a dependency; external authors can prove theirs. |
| More providers: Vertex AI, AWS Bedrock, IBM watsonx.ai, Jina and Voyage embeddings | **Landed** | Broader cloud and embedding coverage. |
| Typed capability model + fake-provider contract servers | **Planned (ESoC)** | Behavior comparable without live keys. |
| Generated provider capability docs | **Planned (ESoC)** | Docs stay aligned with code/tests. |
| Standardized provider options (base URL, headers, proxy, request IDs, retry-after, errors) | **In progress** | Consistent integrations: base URL, headers and `Retry-After` are handled in one place; proxy and request-ID options are not yet exposed. |

### Phase 3: JVM Interop And Adoption

Target: before v1.0 beta.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Java facade (builders, Java collections, clear errors): `llm4s-java-api` | **Landed** | Java users do not need Scala ergonomics. |
| Kotlin coroutine API | **Landed** | Idiomatic `suspend` APIs. |
| Spring Boot starter: `llm4s-spring-boot-starter` | **Landed** | Auto-configuration for Spring users. |
| cats-effect and ZIO integrations: `llm4s-effect`, `llm4s-zio` | **Landed** | Effect-system users do not wrap a `Result` by hand. |
| Maven/Gradle quickstarts, CI-tested | **In progress** | Gradle guide and `gradle-demo` exist; JVM users can start without sbt, and the samples must stay runnable in CI. |

### Phase 4: Security And Governance

Target: before v1.0 RC · **ESoC mentorship track** for hardening.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Versioned threat model | **Landed** | Risks documented. |
| Dependabot + config-policy CI + DCO | **Landed** | Supply-chain and contribution hygiene. |
| MCP auth + public-bind protection | **Landed** | Baseline MCP transport safety. |
| Default-deny tool/MCP policies, allowlists, audit logs | **Planned (ESoC)** | Safer production defaults. |
| Tool poisoning / prompt-injection guidance beyond the detector | **Planned (ESoC)** | MCP treated as a security boundary. |
| SBOM + release-gate security tests | **Planned** | Auditable release artifacts. |

### Phase 5: Reliability, Cost, Observability, And Scale

Target: v1.0 RC · **LFX + ESoC tracks**.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| `ReliableClient` + sample | **Landed** | Retry/circuit/deadline patterns usable. |
| JMH benchmarks module | **Landed** | Perf measurement infrastructure exists. |
| Staged-deployment template (dev, staging, prod) | **Landed** | A copyable rollout pattern with a health gate. |
| Multi-provider failover & hedging | **Planned (LFX)** | Graceful degradation across providers. |
| Unified cost & token telemetry | **Planned (ESoC)** | Spend control across agents/RAG/multimodal. |
| Persistent session store (Redis/Postgres) | **Planned (LFX)** | Resume conversations across restarts. |
| Durable Temporal workflows | **Planned (ESoC)** | Crash-safe long-running agents. |
| Health checks; JMH regression thresholds; reference apps | **Planned** | Production-operable defaults. |

## What Landed Since The June 2026 Snapshot

| Release | Highlights |
|---------|------------|
| **v0.3.3** | MCP SSE transport and bearer-token auth with a constant-time comparison and a public-bind guard; image generation with cost tracking; LLM-driven memory entity extraction; the streaming chat-TUI sample; a published threat model, Dependabot, API-key redaction in provider errors; a ReDoS fix. |
| **v0.3.4** | Dependency updates, Docker image fix, Scaladoc for `LLMCompressor` and `ContextManager`. |
| **v0.4.0** | Every published artifact renamed to `llm4s-*` (coordinates only; no API or package change). |
| **v0.4.1** | Fixes to the Neo4j and Qdrant stores, and integration suites that had been run by nothing now run in tiers. |
| **On `main`, shipping in 0.5.0** | The modular split (slices 1–7); the provider SPI and testkit; Bedrock, watsonx.ai and Jina providers; `llm4s-java-api`, Kotlin, Spring Boot, cats-effect and ZIO modules; the typed graph runtime prototype; the `@Stable`/`@Experimental` tiers, the compatibility policy and MiMa wiring; the staged-deployment template. |

## Community Mentorship Tracks (2026)

These are planned contribution paths aligned with the phases above. Details and mentors live in the linked idea lists.

### LFX Mentorship 2026

See [LFX Mentorship Project Ideas 2026](https://github.com/llm4s/llm4s/blob/main/LFX%20Mentorship/Project%20Ideas/2026.md).

| Track | Phase alignment |
|-------|-----------------|
| Multi-Provider Failover & Request Hedging | Phase 5 |
| Persistent Agent Session Store (Redis & Postgres) | Phase 5 |
| Voice-Native Agent Loop (STT → Agent → TTS) | Agents / multimodal |
| Web Knowledge Ingestion (Sitemaps, Concurrent Crawl & JS Rendering) | RAG |

### European Summer of Code 2026

See [ESoC Project Ideas 2026](https://github.com/llm4s/llm4s/blob/main/European%20Summer%20of%20Code/Project%20Ideas/2026.md).

| Track | Phase alignment |
|-------|-----------------|
| Durable Agent Workflows with Temporal | Phase 5 |
| TermFlow Streaming Chat TUI | Docs / DX samples |
| Computer Use Tool for Agents | Tools / security |
| Agent-to-Agent (A2A) Protocol Interop | Agents |
| Provider Capability Matrix & Fake-Provider Contract Tests | Phase 2 |
| MCP & Tool Security Hardening | Phase 4 |
| Unified Cost & Token Telemetry | Phase 5 |

### Google Summer of Code 2026

Broader idea list (agents, RAG, data pipelines, hardware design, demos): [GSoC Project Ideas 2026](https://github.com/llm4s/llm4s/blob/main/Google%20Summer%20of%20Code/Project%20Ideas/2026.md).

## Reference Applications Needed For v1.0

| Application | Purpose |
|-------------|---------|
| Spring Boot RAG API with pgvector or Qdrant | JVM production service with auth, metrics, tracing, cost tracking, and deployment notes. |
| Sandboxed tool-calling agent with MCP | Secure agent workflow with explicit policies and audit logging. |
| Kotlin coroutine service | Kotlin-native API usage and error handling. |
| Java/Gradle quickstart | Minimal non-Scala adoption path (the Gradle guide and demo are the start). |
| Observability and evaluation sample | Langfuse/OpenTelemetry/Prometheus plus RAG evaluation and replay/debugging. |
| TermFlow streaming chat TUI | Terminal golden-path demo *(ESoC track)*. |

## Release Policy Direction

| Area | Direction |
|------|-----------|
| **Pre-1.0 releases** | Regular previews while APIs stabilize. 0.4.x was the artifact rename and fixes; **0.5.0** completes the modularisation, publishes the separate artifacts, and sets the MiMa baseline. |
| **v1.0** | Freeze the stable modules once they have survived two releases, publish a migration guide, document known limitations, and require compatibility/security/performance gates. Scala 3 only. |
| **Post-1.0** | Semantic Versioning with binary compatibility checks for stable modules; deferred Scala 2.13 support for the frozen spine is considered then ([#874](https://github.com/llm4s/llm4s/issues/874)). Experimental modules may retain separate compatibility notes. |

## Design Documents

Detailed technical designs are in [docs/design](https://github.com/llm4s/llm4s/tree/main/docs/design):

| Document | Purpose |
|----------|---------|
| [Agent Framework Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-roadmap.md) | Agent feature comparison and implementation history. |
| [Typed Agent Runtime Design](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime-design.md) | Proposed graph runtime, Deep Agents capability roadmap, and compatibility/migration strategy. |
| [Agent Framework Gap Analysis](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-gap-analysis-deepagents-2026.md) | Current LLM4S capability inventory and comparison with LangGraph and Deep Agents. |
| [Phase 1.1: Conversations](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.1-functional-conversation-management.md) | Functional conversation management design. |
| [Phase 1.2: Guardrails](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.2-guardrails-framework.md) | Input/output validation framework. |
| [Phase 1.3: Handoffs](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.3-handoff-mechanism.md) | Agent-to-agent delegation. |
| [Phase 1.4: Memory](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.4-memory-system.md) | Short/long-term memory system. |
| [Phase 2.1: Streaming](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.1-streaming-events.md) | Agent lifecycle events. |
| [Phase 2.2: Async Tools](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.2-async-tools.md) | Parallel tool execution. |
| [Phase 3.2: Built-in Tools](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-3.2-builtin-tools.md) | Standard tool library. |
| [Phase 4.1: Reasoning](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.1-reasoning-modes.md) | Extended thinking support. |
| [Phase 4.3: Serialization](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.3-session-serialization.md) | State persistence. |
| [WebCrawlerLoader](https://github.com/llm4s/llm4s/blob/main/docs/design/web-crawler-loader.md) | RAG web ingestion (sitemap/JS/concurrent still planned). |
| [Chat TUI demo spec](https://github.com/llm4s/llm4s/blob/main/docs/design/chat-tui-demo-spec.md) | TermFlow streaming chat sample. |

## Get Involved

- **Discord**: [Join the community](https://discord.gg/4uvTPn6qww)
- **GitHub**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Feature Requests**: [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Dev Hour**: Sundays 9am London time · [Luma calendar](https://luma.com/calendar/cal-Zd9BLb5jbZewxLA)
- **LFX**: `#lfx-mentorship-program` · [ideas](https://github.com/llm4s/llm4s/blob/main/LFX%20Mentorship/Project%20Ideas/2026.md)
- **ESoC**: `#european-summer-of-code` · [ideas](https://github.com/llm4s/llm4s/blob/main/European%20Summer%20of%20Code/Project%20Ideas/2026.md)
