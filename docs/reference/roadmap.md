---
layout: page
title: Roadmap
parent: Reference
nav_order: 5
---

# LLM4S Roadmap

This page reflects the current pre-1.0 roadmap as of June 2026. It replaces the older 2025 plan, which mixed implemented features with production-readiness goals.

LLM4S has broad, working framework functionality today: provider clients, agents, tool calling, RAG/vector stores, memory, guardrails, tracing, metrics, reliability wrappers, workspace isolation, and multimodal APIs. The remaining v1.0 work is productization: stable contracts, JVM interop, provider capability parity, security hardening, deterministic CI, runnable docs, and polished reference applications.

## Quick Status

| | |
|---|---|
| **Latest release tag** | v0.3.2 |
| **Main branch** | Active development after v0.3.2 |
| **Stability** | Pre-1.0, API stabilizing |
| **Scala support** | Scala 3.7.1 |
| **Java support** | JDK 21 recommended and used in CI |
| **Target** | v1.0 production-ready stable modules |
| **Timeline** | 2026 stabilization phases; v1.0 date intentionally not fixed |

## Maturity Legend

| Status | Meaning |
|--------|---------|
| **Stable path** | Implemented, documented, covered by normal CI, and intended to remain compatible. |
| **Beta** | Implemented and usable, but API, provider behavior, or docs still need hardening before v1.0. |
| **Experimental** | Useful prototype or advanced feature; expect changes. |
| **Planned** | Roadmap item, design, issue, or PR queue item; not a stable user contract. |

## Current Capability Map

| Area | Current state | v1.0 gap |
|------|---------------|----------|
| **Provider clients** | OpenAI, Anthropic, Azure OpenAI, Gemini, DeepSeek, Cohere, Mistral, OpenRouter, Requesty, Z.ai, and Ollama clients/configs exist. | Publish a generated provider capability matrix and contract tests for chat, streaming, tools, structured output, embeddings, image/audio, timeouts, retries, cost, and raw exchange logging. |
| **Agents** | Core agents, tool calling, handoffs, guardrails, memory, streaming events, async tools, reasoning modes, and state serialization are implemented. | Freeze stable agent APIs, document limitations, add replay/debug workflows, and compile-test reference apps. |
| **RAG and vector stores** | Document loading, chunking, SQLite/pgvector/Qdrant stores, keyword indexes, hybrid search, reranking, RAGAS-style evaluation, permission-aware RAG, and benchmarking harnesses exist. | Finish cost/latency tracking, runnable golden-path RAG tutorials, provider capability docs, and production reference deployments. |
| **Tooling and MCP** | Tool schema/execution APIs, built-in tools, workspace isolation, MCP client/server primitives, Streamable HTTP, HTTP+SSE, bearer-token auth, and public-bind protection exist. | Treat tools and MCP as security boundaries: allowlists, capability scoping, audit logs, prompt-injection guidance, tool poisoning mitigations, and strict schema validation. |
| **Observability** | Console/Langfuse-style tracing, OpenTelemetry module, metrics, Prometheus support, and raw provider exchange logging exist. | Standardize production metrics, cost tracking, retention/redaction guidance, and agent run replay. |
| **Reliability** | `ReliableClient` supports retry, circuit breaker, deadlines, and metrics. | Make timeouts/retries/fallbacks consistent across providers and easy to apply by default. |
| **JVM interop** | Scala-first APIs are the main supported path. Java/Kotlin/Spring/Gradle work is active in the backlog and PR queue. | Land Java-friendly facades, Kotlin coroutine wrappers, Spring Boot starter, and Maven/Gradle sample projects in CI. |
| **Docs and examples** | Broad docs and many samples exist. | Remove or clearly label pseudocode placeholders, compile docs examples where practical, and align support/version claims. |
| **Security and governance** | Secret scanning, redaction utilities, workspace sandboxing, path/ReDoS tests, shell allowlist work, and MCP bearer-token/public-bind protections exist. | Land a versioned threat model, SBOM/dependency scanning, default-deny tool/MCP policies, audit guidance, and release-gate security tests. |

## Production Readiness Pillars

| Pillar | Current status | Next deliverable |
|--------|----------------|------------------|
| **Testing and CI** | Broad test suite, CI on Ubuntu/Windows, smoke/integration tiers exist. | Isolate or fix any hanging local suites, add suite timeouts, split fast/integration/Docker/provider/benchmark jobs. |
| **API stability** | Pre-1.0 APIs are still stabilizing. | Define stable modules, land binary compatibility checks, publish compatibility/deprecation policy. |
| **Provider parity** | Broad provider coverage, uneven feature parity. | Generated provider capability matrix, fake-provider contract tests, manual live smoke gates. |
| **JVM adoption** | Strong Scala-native design. | Java, Kotlin, Spring Boot, Maven, and Gradle paths with runnable samples. |
| **Security** | Good foundations around sandboxing and secret handling. | Threat model, SBOM, dependency scanning, tool/MCP policies, audit guidance. |
| **Performance and cost** | Metrics and reliability primitives exist. | JMH baselines, regression thresholds, request/agent/session/RAG/image/audio cost tracking. |
| **Documentation trust** | Coverage is broad but inconsistent. | Runnable golden-path tutorials and docs generated from tested capability metadata. |

## 2026 Stabilization Roadmap

### Phase 0: Stabilize The Signal

Priority: immediate.

| Deliverable | Outcome |
|-------------|---------|
| Fix or isolate local `sbt test` non-reporting/hang behavior. | Full test runs either complete or clearly exclude known slow/flaky suites. |
| Add suite/test timeouts. | CI cannot silently hang. |
| Align README, roadmap, docs metadata, release tags, Scala/JDK support, and provider status. | New users see one coherent project state. |
| Remove, replace, or label `???` placeholders in user-facing docs. | Guides are either runnable or explicitly pseudocode. |
| Publish stable/beta/experimental/planned status. | Users know which APIs are safe to build on. |

### Phase 1: Define The Stable Spine

Target: next stabilization window.

| Deliverable | Outcome |
|-------------|---------|
| Name stable module boundaries: core, agents, rag, tools, observability, workspace, and interop. | v1.0 compatibility surface is explicit. |
| Mark experimental features and modules. | Advanced features can evolve without surprising production users. |
| Land MiMa or equivalent binary compatibility checks. | Compatibility regressions are caught before release. |
| Publish compatibility, deprecation, and migration policy. | Users can plan upgrades. |
| Add package-level Scaladoc for stable modules. | Public API intent is documented. |

### Phase 2: Provider Capability Matrix And Contract Tests

Target: after stable spine is defined.

| Deliverable | Outcome |
|-------------|---------|
| Capability model for chat, streaming, tools, structured output, reasoning, embeddings, image/audio, timeout/retry, usage/cost, and raw exchange logging. | Provider behavior is comparable and explicit. |
| Fake-provider contract servers for protocol families. | Behavior is testable without live API calls. |
| Generated provider capability docs. | Docs stay aligned with code/tests. |
| Standardized provider options for base URL, headers, proxy, request IDs, user agent, retry-after, and error mapping. | Provider integrations behave consistently. |

### Phase 3: JVM Interop And Adoption

Target: before v1.0 beta.

| Deliverable | Outcome |
|-------------|---------|
| Java facade with builders, Java collections, and Java-friendly error boundaries. | Java users do not need Scala-specific ergonomics. |
| Kotlin coroutine wrapper. | Kotlin services can use idiomatic `suspend` APIs. |
| Spring Boot starter. | Spring users get auto-configuration, properties binding, health checks, metrics, and test slices. |
| Maven and Gradle quickstarts. | JVM users can start without sbt. |
| CI-tested sample projects. | Interop docs remain runnable. |

### Phase 4: Security And Governance

Target: before v1.0 release candidate.

| Deliverable | Outcome |
|-------------|---------|
| Versioned threat model. | Tool, MCP, RAG, workspace, and provider-log risks are explicit. |
| Default-deny policies for tools, shell/workspace access, file/network access, MCP servers, and provider exchange logging. | Production users start from safer defaults. |
| MCP hardening: retain auth and public-bind protection, then add allowlists, capability scoping, audit logs, prompt-injection guidance, and tool poisoning mitigations. | MCP is treated as a production security boundary. |
| SBOM and dependency/security scanning in CI/release. | Release artifacts have auditable supply-chain metadata. |
| PII redaction, retention, and multi-tenant logging examples. | Observability guidance is safe for real deployments. |

### Phase 5: Reliability, Cost, Observability, And Scale

Target: v1.0 release candidate.

| Deliverable | Outcome |
|-------------|---------|
| Consistent retries, circuit breakers, deadlines, rate-limit handling, and provider fallback APIs. | Production services can bound failure behavior. |
| Health checks for provider clients where practical. | Services can expose meaningful readiness state. |
| Cost tracking across requests, agents, sessions, embeddings, RAG, image, and audio operations. | Users can control spend. |
| Standard metrics for latency, tokens, cost, retries, circuit state, tool duration, and retrieval quality. | Operations teams get useful dashboards. |
| JMH benchmarks and regression thresholds. | Performance claims are measured. |
| Reference applications. | Users can copy complete, maintained production patterns. |

## Reference Applications Needed For v1.0

| Application | Purpose |
|-------------|---------|
| Spring Boot RAG API with pgvector or Qdrant | JVM production service with auth, metrics, tracing, cost tracking, and deployment notes. |
| Sandboxed tool-calling agent with MCP | Secure agent workflow with explicit policies and audit logging. |
| Kotlin coroutine service | Kotlin-native API usage and error handling. |
| Java/Gradle quickstart | Minimal non-Scala adoption path. |
| Observability and evaluation sample | Langfuse/OpenTelemetry/Prometheus plus RAG evaluation and replay/debugging. |

## Release Policy Direction

| Area | Direction |
|------|-----------|
| **Pre-1.0 releases** | Continue regular preview releases while APIs stabilize. |
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

## Get Involved

- **Discord**: [Join the community](https://discord.gg/4uvTPn6qww)
- **GitHub**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Feature Requests**: [GitHub Issues](https://github.com/llm4s/llm4s/issues)
- **Dev Hour**: Sundays 9am London time
