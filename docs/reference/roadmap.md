---
layout: page
title: Roadmap
parent: Reference
nav_order: 5
---

# LLM4S Roadmap

> **Draft (H2 2026)** — Updated July 2026 to reflect releases through **v0.3.4**, unreleased main-branch work, and community mentorship tracks ([LFX](../../LFX%20Mentorship/Project%20Ideas/2026.md), [ESoC](../../European%20Summer%20of%20Code/Project%20Ideas/2026.md)). Replaces the June 2026 pre-1.0 snapshot that still listed **v0.3.2** as current.

LLM4S has broad, working framework functionality today: multi-provider clients (including Vertex AI), agents, tool calling, RAG/vector stores, GraphRAG/Neo4j, memory, guardrails (including prompt-injection detection), tracing, metrics, JMH benchmarks, reliability wrappers, workspace isolation, MCP transports with auth hardening, multimodal APIs (vision, image generation, STT), and growing governance docs. The remaining v1.0 work is productization: stable contracts, JVM interop, provider capability parity, default-deny tool/MCP security, unified cost telemetry, deterministic CI, runnable docs, and polished reference applications.

## Quick Status

| | |
|---|---|
| **Latest release tag** | v0.3.4 (2026-06-19) · see [CHANGELOG](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md) |
| **Main branch** | Active development after v0.3.4 (Vertex AI, STT wiring, samples, governance, docs) |
| **Stability** | Pre-1.0, API stabilizing |
| **Scala support** | Scala 3.7.1 |
| **Java support** | JDK 21 recommended and used in CI |
| **Target** | v1.0 production-ready stable modules |
| **Timeline** | 2026 H2 stabilization phases; v1.0 date intentionally not fixed |
| **Mentorship** | LFX Mentorship 2026 · European Summer of Code 2026 · GSoC 2026 |

## Maturity Legend

| Status | Meaning |
|--------|---------|
| **Stable path** | Implemented, documented, covered by normal CI, and intended to remain compatible. |
| **Beta** | Implemented and usable, but API, provider behavior, or docs still need hardening before v1.0. |
| **Experimental** | Useful prototype or advanced feature; expect changes. |
| **Planned** | Roadmap item, design, issue, mentorship track, or PR queue item; not a stable user contract. |
| **Landed (post-June snapshot)** | Completed after the June 2026 roadmap text; still may be Beta until v1.0 freeze. |

## Current Capability Map

| Area | Current state | v1.0 gap |
|------|---------------|----------|
| **Provider clients** | OpenAI, Anthropic, Azure OpenAI, Gemini, **Vertex AI**, DeepSeek, Cohere, Mistral, OpenRouter, Requesty, Z.ai, and Ollama clients/configs exist. Named-provider validators and some smoke tests exist. | Publish a **generated** provider capability matrix and fake-provider contract tests for chat, streaming, tools, structured output, embeddings, image/audio, timeouts, retries, cost, and raw exchange logging. *(ESoC track)* |
| **Agents** | Core agents, tool calling, handoffs, guardrails (incl. prompt-injection detector), memory (incl. LLM entity extraction), streaming events, async tools, reasoning modes, and state serialization are implemented. | Freeze stable agent APIs; durable session stores; Temporal workflows; A2A interop; voice agent loop; replay/debug; compile-test reference apps. *(LFX + ESoC tracks)* |
| **RAG and vector stores** | Document loading, chunking, SQLite/pgvector/Qdrant, keyword indexes, hybrid search, reranking samples, RAGAS-style evaluation, permission-aware RAG, WebCrawlerLoader (BFS/robots/rate limits), GraphRAG/Neo4j stack. | Sitemap + concurrent + JS crawl; unified cost/latency across RAG; runnable golden-path tutorials; production reference deployments. *(LFX track)* |
| **Tooling and MCP** | Tool schema/execution APIs, built-in tools, workspace isolation, MCP client/server, Streamable HTTP, HTTP+SSE, bearer-token auth, public-bind protection. | Default-deny policies, allowlists, capability scoping, audit logs, tool-poisoning mitigations, computer-use tool, strict schema validation. *(ESoC tracks)* |
| **Observability** | Console/Langfuse-style tracing, OpenTelemetry module, Prometheus metrics, image cost hooks, agent usage summaries, JMH module. | Unified cost/token telemetry across request/agent/session/RAG/multimodal; retention/redaction guidance; agent run replay; regression thresholds on JMH. *(ESoC track)* |
| **Reliability** | `ReliableClient` supports retry, circuit breaker, deadlines, and metrics; sample + timeout fixes landed. | Multi-provider **failover & hedging**; consistent timeouts/retries across providers; health checks. *(LFX track)* |
| **JVM interop** | Scala-first APIs remain the main supported path. | Java facades, Kotlin coroutine wrappers, Spring Boot starter, Maven/Gradle samples in CI. |
| **Docs and examples** | Broad docs, migrations (`0x-to-1x`), troubleshooting, CONTRIBUTING, GOVERNANCE/MAINTAINERS/RELEASES, Scaladoc waves, ReliableClient / Neo4j / rerank samples. | Align README/version claims with **0.3.4+**; remove/label remaining pseudocode; runnable golden-path tutorials from capability metadata. |
| **Security and governance** | Secret scanning, redaction, workspace sandboxing, Dependabot, **threat model** (`docs/reference/security.md`), config-policy module + CI gate, MCP auth hardening, DCO checks. | SBOM, default-deny tool/MCP policies, audit guidance, release-gate security suite. *(ESoC track)* |

## What Landed Since The June 2026 Snapshot

| Area | Highlights |
|------|------------|
| **Releases** | v0.3.3, v0.3.4 published; CHANGELOG + migration docs |
| **Providers** | Vertex AI (ADC/OAuth2); Requesty hardening; Cohere smoke |
| **Multimodal** | Image generation cost/metrics; STT (Vosk/Whisper) wiring |
| **Agents / memory** | LLM-driven memory entity extraction; prompt-injection guardrail tests |
| **RAG / knowledge** | Reranking sample; Neo4j / GraphRAG module + sample |
| **MCP / security** | SSE + bearer auth + public-bind hardening; threat model; API-key redaction; Dependabot |
| **Governance** | `modules/config-policy` + CI gate; MAINTAINERS / GOVERNANCE / RELEASES; DCO workflow |
| **Perf / reliability** | JMH `modules/benchmarks`; ReliableClient sample; timeout worker-thread fix |
| **Community** | LFX Mentorship 2026 ideas; ESoC 2026 ideas; GSoC 2026 program materials |

## Production Readiness Pillars

| Pillar | Current status | Next deliverable |
|--------|----------------|------------------|
| **Testing and CI** | Broad suite, Ubuntu/Windows CI, smoke/integration tiers, SlowTest filtering, DCO, config-policy check. | Suite timeouts / hang isolation; split fast/integration/Docker/provider/benchmark jobs. |
| **API stability** | Pre-1.0 APIs still stabilizing; Scaladoc coverage improving. | Name stable modules; enforce MiMa; publish compatibility/deprecation policy. |
| **Provider parity** | Broad coverage including Vertex; uneven feature parity. | Generated capability matrix + fake-provider contracts. |
| **JVM adoption** | Strong Scala-native design. | Java, Kotlin, Spring Boot, Maven, Gradle paths with runnable samples. |
| **Security** | Threat model, Dependabot, MCP auth/bind, redaction, config-policy, prompt-injection detector. | Default-deny tool/MCP, allowlists, audit logs, SBOM, release-gate tests. |
| **Performance and cost** | JMH module + image/agent usage hooks. | Unified cost telemetry; JMH regression thresholds; failover/hedging. |
| **Documentation trust** | Migrations, troubleshooting, governance docs, many samples. | Version alignment (README ↔ releases); golden-path tutorials. |

## 2026 H2 Stabilization Roadmap

### Phase 0: Stabilize The Signal

Priority: immediate · partially advanced.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Align README, roadmap, release tags, Scala/JDK, provider status | **In progress** | New users see one coherent project state (target **0.3.4+**). |
| CHANGELOG + migration guides | **Landed** | Upgrade path from 0.x documented. |
| Threat model + governance docs | **Landed** | Security/governance baselines exist. |
| Fix or isolate hanging `sbt test` suites; suite timeouts | **Planned** | CI cannot silently hang. |
| Remove/label `???` placeholders; publish maturity labels | **Planned** | Guides are runnable or explicitly pseudocode. |

### Phase 1: Define The Stable Spine

Target: next stabilization window.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Module layout growth (`config-policy`, `benchmarks`, `trace-opentelemetry`, `knowledgegraph-neo4j`) | **Landed** | Clearer packages for advanced features. |
| Package-level Scaladoc push | **In progress** | Public API intent documented. |
| Name stable module boundaries for v1.0 | **Planned** | Compatibility surface is explicit. |
| MiMa (or equivalent) enforced in CI/release | **Planned** | Compatibility regressions caught before release. |
| Compatibility / deprecation / migration policy | **Planned** | Users can plan upgrades. |

### Phase 2: Provider Capability Matrix And Contract Tests

Target: after stable spine · **ESoC mentorship track**.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Additional providers (Vertex AI, Requesty hardening) | **Landed** | Broader cloud coverage. |
| Typed capability model + fake-provider contract servers | **Planned (ESoC)** | Behavior comparable without live keys. |
| Generated provider capability docs | **Planned (ESoC)** | Docs stay aligned with code/tests. |
| Standardized provider options (base URL, headers, proxy, request IDs, retry-after, errors) | **Planned** | Consistent integrations. |

### Phase 3: JVM Interop And Adoption

Target: before v1.0 beta.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Java facade (builders, Java collections, clear errors) | **Planned** | Java users do not need Scala ergonomics. |
| Kotlin coroutine wrapper | **Planned** | Idiomatic `suspend` APIs. |
| Spring Boot starter | **Planned** | Auto-config, properties, health, metrics, test slices. |
| Maven/Gradle quickstarts in CI | **Planned** | JVM users can start without sbt. |

### Phase 4: Security And Governance

Target: before v1.0 RC · **ESoC mentorship track** for hardening.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| Versioned threat model | **Landed** | Risks documented. |
| Dependabot + config-policy CI + DCO | **Landed** | Supply-chain and contribution hygiene. |
| MCP auth + public-bind protection | **Landed** | Baseline MCP transport safety. |
| Default-deny tool/MCP policies, allowlists, audit logs | **Planned (ESoC)** | Safer production defaults. |
| Tool poisoning / prompt-injection guidance beyond detector | **Planned (ESoC)** | MCP treated as security boundary. |
| SBOM + release-gate security tests | **Planned** | Auditable release artifacts. |

### Phase 5: Reliability, Cost, Observability, And Scale

Target: v1.0 RC · **LFX + ESoC tracks**.

| Deliverable | Status | Outcome |
|-------------|--------|---------|
| ReliableClient + sample | **Landed** | Retry/circuit/deadline patterns usable. |
| JMH benchmarks module | **Landed** | Perf measurement infrastructure exists. |
| Multi-provider failover & hedging | **Planned (LFX)** | Graceful degradation across providers. |
| Unified cost & token telemetry | **Planned (ESoC)** | Spend control across agents/RAG/multimodal. |
| Persistent session store (Redis/Postgres) | **Planned (LFX)** | Resume conversations across restarts. |
| Durable Temporal workflows | **Planned (ESoC)** | Crash-safe long-running agents. |
| Health checks; JMH regression thresholds; reference apps | **Planned** | Production-operable defaults. |

## Community Mentorship Tracks (2026)

These are planned contribution paths aligned with the phases above. Details and mentors live in the linked idea lists.

### LFX Mentorship 2026

See [LFX Mentorship Project Ideas 2026](../../LFX%20Mentorship/Project%20Ideas/2026.md).

| Track | Phase alignment |
|-------|-----------------|
| Multi-Provider Failover & Request Hedging | Phase 5 |
| Persistent Agent Session Store (Redis & Postgres) | Phase 5 |
| Voice-Native Agent Loop (STT → Agent → TTS) | Agents / multimodal |
| Web Knowledge Ingestion (Sitemaps, Concurrent Crawl & JS Rendering) | RAG |

### European Summer of Code 2026

See [ESoC Project Ideas 2026](../../European%20Summer%20of%20Code/Project%20Ideas/2026.md).

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

Broader idea list (agents, RAG, data pipelines, hardware design, demos): [GSoC Project Ideas 2026](../../Google%20Summer%20of%20Code/Project%20Ideas/2026.md).

## Reference Applications Needed For v1.0

| Application | Purpose |
|-------------|---------|
| Spring Boot RAG API with pgvector or Qdrant | JVM production service with auth, metrics, tracing, cost tracking, and deployment notes. |
| Sandboxed tool-calling agent with MCP | Secure agent workflow with explicit policies and audit logging. |
| Kotlin coroutine service | Kotlin-native API usage and error handling. |
| Java/Gradle quickstart | Minimal non-Scala adoption path. |
| Observability and evaluation sample | Langfuse/OpenTelemetry/Prometheus plus RAG evaluation and replay/debugging. |
| TermFlow streaming chat TUI | Terminal golden-path demo *(ESoC track)*. |

## Release Policy Direction

| Area | Direction |
|------|-----------|
| **Pre-1.0 releases** | Continue regular preview releases while APIs stabilize (current line: **0.3.x**). |
| **v1.0** | Freeze stable modules, publish migration guide, document known limitations, and require compatibility/security/performance gates. |
| **Post-1.0** | Use Semantic Versioning with binary compatibility checks for stable modules. Experimental modules may retain separate compatibility notes. |

## Design Documents

Detailed technical designs are in [docs/design](https://github.com/llm4s/llm4s/tree/main/docs/design):

| Document | Purpose |
|----------|---------|
| [Agent Framework Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-roadmap.md) | Agent feature comparison and implementation history. |
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

- **Discord**: [Join the community](https://discord.com/invite/DjPMufnhG6)
- **GitHub**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Feature Requests**: [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Dev Hour**: Sundays 9am London time · [Luma calendar](https://luma.com/calendar/cal-Zd9BLb5jbZewxLA)
- **LFX**: `#lfx-mentorship-program` · [ideas](../../LFX%20Mentorship/Project%20Ideas/2026.md)
- **ESoC**: `#european-summer-of-code` · [ideas](../../European%20Summer%20of%20Code/Project%20Ideas/2026.md)
