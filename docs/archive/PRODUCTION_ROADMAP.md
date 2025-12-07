# LLM4S Production Readiness Roadmap

**Status:** 🚧 **DRAFT** 🚧

**Document Version:** 0.1.0
> **Version History:**
> - v0.1.0 (2025-11-15): Initial draft for review

**Last Updated:** 2025-11-15
**Target:** Production-Ready 1.0.0 Release

---

## ⚠️ TODO - Before Finalization

This roadmap is currently in **DRAFT** status. Before implementation begins, the following steps must be completed:

- [ ] **1. Discuss and agree roadmap** - Review with core maintainers and community stakeholders
- [ ] **2. Align issues with roadmap** - Create/update GitHub issues to match roadmap phases
- [ ] **3. Create project board** - Set up GitHub project board for tracking roadmap progress

Once these steps are complete and the roadmap is agreed upon, this DRAFT marker will be removed and implementation can begin.

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Current State Assessment](#current-state-assessment)
3. [Feature Completion Status](#feature-completion-status)
4. [Production Readiness Framework](#production-readiness-framework)
5. [Detailed Roadmap](#detailed-roadmap)
6. [Timeline and Milestones](#timeline-and-milestones)
7. [Success Metrics](#success-metrics)
8. [Risk Assessment](#risk-assessment)
9. [Appendix](#appendix)

---

## Executive summary

LLM4S is a framework for building LLM-powered applications in Scala. The project already has multi-provider support, an agent framework, RAG pieces, and observability hooks, but a few important gaps still stand between the current state and a production-ready 1.0.

This roadmap outlines a structured path to achieve production-grade quality across seven key pillars: **Testing & Quality Assurance**, **API Stability**, **Performance & Scalability**, **Security & Compliance**, **Documentation & Developer Experience**, **Observability & Operations**, and **Community & Ecosystem**.

**Current Status:** v0.1.x (Pre-Production)
**Target:** v1.0.0 (Production-Ready)
**Estimated Timeline:** 6-9 months

---

## Current State Assessment

### Strengths

✅ **Active Development**: 158+ commits in 2025, strong momentum
✅ **Cross-Platform Support**: Scala 2.13 and 3.7 with full cross-compilation
✅ **Multi-Provider Architecture**: OpenAI, Anthropic, Azure OpenAI, Ollama
✅ **Comprehensive Features**: Tool calling, agents, RAG, multimodal support
✅ **Type-Safe Design**: Leveraging Scala's type system with `Result[A]` pattern
✅ **CI/CD Pipeline**: Automated testing, formatting, and release workflows
✅ **Good Documentation**: Detailed design docs, migration guides, API specs
✅ **Community Engagement**: GSoC participation, active Discord, regular talks
✅ **Maven Central Publishing**: Proper artifact distribution
✅ **Configuration Management**: Centralized `ConfigReader` with Scalafix enforcement

### Identified Gaps

❌ **Test Coverage**: ~21% test files (target: 80%+ statement coverage)
❌ **Integration Testing**: Limited end-to-end and integration test scenarios
❌ **Performance Benchmarking**: No formal performance testing framework
❌ **API Stability Guarantees**: Still in 0.x versions, no backward compatibility policy
❌ **Production Error Handling**: Limited patterns for retries, circuit breakers, bulkheads
❌ **Security Auditing**: No formal security review process
❌ **Monitoring in Production**: Limited guidance beyond tracing
❌ **Deployment Patterns**: No reference architectures for production deployments
❌ **Breaking Change Process**: Unclear migration path for major API changes
❌ **Chaos Engineering**: No resilience testing framework

### Project Metrics

| Metric | Current Value | Target |
|--------|--------------|--------|
| Total Scala Files | 269 | - |
| Test Files | 57 (21%) | 150+ (55%+) |
| Statement Coverage | Unknown | 80%+ |
| Documentation Coverage | Good | Excellent |
| Open Issues | Unknown | < 20 |
| TODOs/FIXMEs | 2 | 0 |
| Deprecated APIs | 9 occurrences | 0 |
| Active Contributors | GSoC + 2 maintainers | 10+ |

---

## Feature Completion Status

### Core Features

This section tracks the completion status of major LLM4S features and capabilities.

#### ✅ Completed Features

| Feature | Status | Notes |
|---------|--------|-------|
| **Multi-Provider Support** | ✅ Complete | OpenAI, Anthropic, Azure OpenAI, Ollama |
| **Basic LLM Calling** | ✅ Complete | Synchronous completion API |
| **Streaming Responses** | ✅ Complete | Streaming API for all providers |
| **Tool Calling** | ✅ Complete | Schema-based tool definitions and execution |
| **Message Types** | ✅ Complete | User, System, Assistant, Tool messages |
| **Configuration Management** | ✅ Complete | ConfigReader with Scalafix enforcement |
| **Error Handling** | ✅ Complete | Result-based error hierarchy |
| **Cross-Version Support** | ✅ Complete | Scala 2.13 and 3.7 compatibility |
| **Workspace Containers** | ✅ Complete | Docker-based isolated execution |
| **Tracing Support** | ✅ Complete | Langfuse and console tracing |
| **Image Generation** | ✅ Complete | Stable Diffusion and Hugging Face |
| **Speech-to-Text** | ✅ Complete | Vosk integration |
| **Text-to-Speech** | ✅ Complete | TTS support |
| **Image Processing** | ✅ Complete | Claude Vision and similar |

#### 🚧 In Progress Features

| Feature | Status | Completion % | Blocking Issues | Target |
|---------|--------|--------------|-----------------|--------|
| **RAG (Retrieval-Augmented Generation)** | 🚧 In Progress | ~70% | Missing: Vector store integrations, chunking strategies | Month 3 |
| **Agent Framework** | 🚧 In Progress | ~60% | Missing: Multi-agent orchestration patterns, state persistence | Month 4 |
| **MCP (Model Context Protocol)** | 🚧 In Progress | ~50% | Missing: Full protocol implementation, examples | Month 3 |
| **Advanced Embeddings** | 🚧 In Progress | ~60% | Missing: Multiple provider support, caching | Month 3 |

#### 📋 Planned Features

| Feature | Priority | Description | Target Milestone |
|---------|----------|-------------|------------------|
| **RAG - Vector Database Integrations** | P0 | Postgres pgvector, Qdrant, Weaviate, Elasticsearch | Phase 1.2 |
| **RAG - Document Chunking** | P0 | Smart chunking strategies for different document types | Phase 1.2 |
| **RAG - Semantic Search** | P0 | Hybrid search (keyword + semantic) | Phase 1.2 |
| **Agent - State Persistence** | P1 | Save/restore agent state for long-running workflows | Phase 1.3 |
| **Agent - Multi-Agent Communication** | P1 | Agent-to-agent messaging and coordination | Phase 1.3 |
| **Agent - Tool Composition** | P1 | Compose tools into higher-level capabilities | Phase 1.3 |
| **MCP - Server Implementation** | P1 | Full MCP server for context sharing | Phase 1.2 |
| **Prompt Management** | P2 | Template system with variable substitution | Phase 2 |
| **Caching Layer** | P2 | Cache LLM responses for cost/latency optimization | Phase 2 |
| **Function Calling v2** | P2 | Parallel tool calls, tool choice strategies | Phase 2 |
| **Guardrails** | P2 | Higher-level policies and presets on top of existing guardrails (input/output/tool) | Phase 3 |
| **Cost Tracking** | P2 | Token usage tracking and cost estimation | Phase 2 |

#### ❌ Deferred Features

| Feature | Reason | Potential Future Release |
|---------|--------|-------------------------|
| **Fine-tuning Support** | Low priority for initial 1.0 | Post-1.0 (v1.2+) |
| **Custom Model Hosting** | Niche use case | Post-1.0 (v1.3+) |
| **GraphQL API** | Not core to library | Post-1.0 (community plugin) |

### Feature Dependencies

The following features have dependencies that must be completed first:

```
RAG Pipeline (Full)
  ├── Vector Database Integrations (P0) ← Blocks production RAG
  ├── Document Chunking (P0) ← Blocks production RAG
  └── Semantic Search (P0) ← Blocks production RAG

Agent Framework (Full)
  ├── State Persistence (P1) ← Needed for production agents
  ├── Multi-Agent Communication (P1) ← Blocks complex workflows
  └── Tool Composition (P1) ← Improves agent capabilities

MCP Support (Full)
  └── Server Implementation (P1) ← Blocks MCP adoption
```

```mermaid
graph TD
    RAG[RAG pipeline] --> VDB[Vector DB integrations]
    RAG --> CH[Document chunking]
    RAG --> SS[Semantic search]

    AF[Agent framework] --> SP[State persistence]
    AF --> MAC[Multi-agent communication]
    AF --> TC[Tool composition]

    MCP[MCP support] --> MS[Server implementation]
```

### Feature Completion Roadmap

**Phase 1 (Months 1-3): Core Feature Completion**
- Complete RAG pipeline with vector store integrations
- Finalize agent framework with state persistence
- Complete MCP implementation

**Phase 2 (Months 4-5): Advanced Features**
- Prompt management system
- Caching layer
- Cost tracking and monitoring

**Phase 3 (Month 6): Production Hardening**
- Guardrails and safety
- Performance optimization
- Final integration testing

---

## Production readiness framework

To make “production ready” concrete, we use seven pillars. Each pillar answers a simple question: what has to be true before we trust this library in production?

```mermaid
graph TD
    PR[Production readiness]
    PR --> P1[Testing and quality]
    PR --> P2[API stability and versioning]
    PR --> P3[Performance and scalability]
    PR --> P4[Security and compliance]
    PR --> P5[Docs and developer experience]
    PR --> P6[Observability and operations]
    PR --> P7[Community and ecosystem]
```

Each pillar has its own set of tasks and exit criteria.

---

## Detailed roadmap

### Pillar 1: Testing & Quality Assurance

**Goal:** Catch most mistakes before they ever reach a running system by having solid tests at every level.

#### Phase 1.1: Unit test coverage (Priority: P0)
**Timeline:** Months 1-2
**Owner:** Core Team + Community

**Tasks:**
- [ ] **Audit Current Coverage**: Run `sbt cov` and identify untested modules
- [ ] **Create Testing Guidelines**: Document test patterns and best practices
  - Location: `docs/TESTING_GUIDE.md`
  - Include: mocking patterns, fixture setup, async testing
- [ ] **Core Module Tests** (Target: 90% coverage)
  - [ ] `llmconnect`: Provider implementations, retry logic, streaming
  - [ ] `config`: ConfigReader validation, environment parsing
  - [ ] `error`: Error hierarchy, recovery patterns
  - [ ] `toolapi`: Tool function execution, schema validation
  - [ ] `agent`: Agent state management, orchestration
  - [ ] `trace`: Tracing backends, token counting
- [ ] **Workspace Module Tests** (Target: 80% coverage)
  - [ ] `workspaceClient`: Docker communication, file operations
  - [ ] `workspaceShared`: Protocol validation
- [ ] **Enable Coverage Enforcement**
  - Set `coverageFailOnMinimum := true` in `build.sbt`
  - Add coverage reports to CI/CD pipeline and document targets in `docs/TEST_COVERAGE.md`

**Related Issues:** None yet
**Dependencies:** None

#### Phase 1.2: Integration testing (Priority: P0)
**Timeline:** Months 2-3
**Owner:** Core Team

**Tasks:**
- [ ] **Integration Test Framework**
  - Create `modules/integration-tests` directory
  - Set up test containers for dependencies (Postgres, Docker)
  - Mock LLM provider responses for deterministic tests
- [ ] **Provider Integration Tests**
  - [ ] OpenAI: Complete workflow including tool calls
  - [ ] Anthropic: Complete workflow including tool calls
  - [ ] Azure OpenAI: Complete workflow including tool calls
  - [ ] Ollama: Local provider integration
- [ ] **RAG Pipeline Tests**
  - [ ] Document ingestion → Embedding → Storage → Retrieval
  - [ ] Multi-provider embedding tests
- [ ] **Agent Workflow Tests**
  - [ ] Single-agent task completion
  - [ ] Multi-agent orchestration
  - [ ] Tool execution within agents
- [ ] **Tracing Integration Tests**
  - [ ] Langfuse integration end-to-end
  - [ ] Console tracing output validation

**Related Docs:** `docs/workspace-agent-protocol.md`
**Dependencies:** Phase 1.1

#### Phase 1.3: End-to-End Scenarios (Priority: P1)
**Timeline:** Month 3
**Owner:** Core Team

**Tasks:**
- [ ] **Create Reference Applications**
  - [ ] Chatbot with tool calling
  - [ ] Document Q&A system (RAG)
  - [ ] Multi-agent research assistant
- [ ] **E2E Test Suite**
  - [ ] User journey tests covering full workflows
  - [ ] Cross-provider compatibility tests
  - [ ] Streaming vs. non-streaming equivalence tests

**Related Docs:** `README.md` (samples section)
**Dependencies:** Phase 1.2

#### Phase 1.4: Property-Based and Fuzzing Tests (Priority: P2)
**Timeline:** Month 4
**Owner:** Community + Core Team

**Tasks:**
- [ ] **Add ScalaCheck Dependency**
  - Property-based testing for data transformations
- [ ] **Fuzz Testing for Tool Schemas**
  - Generate random schemas and validate JSON Schema output
- [ ] **Fuzz Testing for Config Parsing**
  - Test malformed configurations, edge cases

**Dependencies:** Phase 1.1

---

### Pillar 2: API Stability & Versioning

**Goal:** Let users upgrade safely by keeping a clear, stable public API and a documented story for breaking changes.

#### Phase 2.1: API audit (Priority: P0)
**Timeline:** Month 1
**Owner:** Core Team

**Tasks:**
- [ ] **Document Public API Surface**
  - Identify all public classes, traits, methods
  - Mark internal APIs with `private[llm4s]`
- [ ] **Identify Breaking Changes**
  - Review deprecated APIs (9 occurrences found)
  - Plan migration path for removals
- [ ] **Versioning Policy**
  - Create `docs/VERSIONING_POLICY.md`
  - Define SemVer rules for Scala libraries
  - Document binary compatibility commitments

**Related Docs:** `docs/MIGRATION_GUIDE.md`
**Dependencies:** None

#### Phase 2.2: Binary compatibility checks (Priority: P0)
**Timeline:** Month 2
**Owner:** Core Team

**Tasks:**
- [ ] **Enable MiMa (Migration Manager)**
  - Already in `build.sbt`: `mimaPreviousArtifacts := Set(...)`
  - Configure for 1.0.0 baseline and agree on the first stable previous version
- [ ] **Document Breaking Change Process**
  - Add to `CONTRIBUTING.md`
  - Require MiMa checks in CI/CD
- [ ] **Deprecation Warnings**
  - Mark all deprecated APIs with `@deprecated` annotation
  - Provide clear migration instructions

**Related Issues:** None yet
**Dependencies:** Phase 2.1

#### Phase 2.3: API freeze for 1.0 (Priority: P0)
**Timeline:** Month 5 (before 1.0-RC1)
**Owner:** Core Team

**Tasks:**
- [ ] **Feature Freeze**
  - No new public APIs after RC1
  - Only bug fixes and documentation updates
- [ ] **Release Candidate Testing**
  - Publish 1.0.0-RC1, RC2, etc.
  - Gather community feedback
- [ ] **Final API Review**
  - Review all public method signatures
  - Ensure consistency across modules

**Related Docs:** `docs/RELEASE.md`
**Dependencies:** All Phase 2 tasks

---

### Pillar 3: Performance & Scalability

**Goal:** Know how the system behaves under load, and keep it within predictable performance and resource bounds.

#### Phase 3.1: Performance benchmarking framework (Priority: P1)
**Timeline:** Months 2-3
**Owner:** Core Team

**Tasks:**
- [ ] **Add JMH Dependency**
  - Java Microbenchmark Harness for accurate benchmarks
- [ ] **Create Benchmarks Module**
  - `modules/benchmarks/` directory
  - Benchmark LLM client throughput, latency
  - Benchmark tool execution overhead
  - Benchmark RAG pipeline performance
- [ ] **Baseline Metrics**
  - Establish performance baselines for 1.0
  - Document in `docs/PERFORMANCE.md` and update on each minor release

**Dependencies:** None

#### Phase 3.2: Memory and resource optimization (Priority: P1)
**Timeline:** Month 4
**Owner:** Core Team

**Tasks:**
- [ ] **Memory Profiling**
  - Profile memory usage with YourKit/VisualVM
  - Identify memory leaks in long-running processes
- [ ] **Resource Management**
  - Ensure proper cleanup in `LLMClient.close()`
  - Review connection pooling (HikariCP configuration)
  - Document resource limits in workspace containers

**Related Docs:** `docs/workspace-agent-protocol.md`
**Dependencies:** Phase 3.1

#### Phase 3.3: Scalability testing (Priority: P2)
**Timeline:** Month 5
**Owner:** Community + Core Team

**Tasks:**
- [ ] **Load Testing Framework**
  - Gatling or similar for load testing
  - Test concurrent LLM requests
- [ ] **Horizontal Scaling Validation**
  - Test multiple workspace containers
  - Test connection pooling under load
- [ ] **Publish Scalability Guide**
  - Location: `docs/SCALABILITY_GUIDE.md`
  - Include: connection pooling, rate limiting, caching strategies

**Dependencies:** Phase 3.1, Phase 3.2

#### Phase 3.4: Reliable calling (priority: P0)
**Timeline:** Months 3-4
**Owner:** Core Team

**Tasks:**
- [ ] **Error classification**
  - Decide which `LLMError` values are safe to retry (for example: rate limits, timeouts, transient network failures).
- [ ] **Implement reliable client behaviour**
  - Implement retry with backoff using `ReliableCallOptions`.
  - Enforce optional total deadline for a call.
  - Add hooks for hedging and circuit breaking behind feature flags.
- [ ] **Wire into clients and samples**
  - Provide helpers to wrap a plain `LLMClient` with `ReliableClient` from configuration.
  - Use the reliable wrapper in at least one sample application.
- [ ] **Telemetry and tests**
  - Emit fields like `retryCount`, `backoffMs`, and `deadlineExceeded` into tracing/metrics.
  - Add fault-injection tests that simulate rate limits and timeouts.

**Related Docs:** `modules/core/src/main/scala/org/llm4s/reliability`
**Dependencies:** Phase 3.1

```mermaid
sequenceDiagram
  participant App as Application code
  participant Rel as ReliableClient
  participant LLM as LLMClient
  participant API as Provider API

  App->>Rel: complete(conversation, options)
  Rel->>LLM: complete(conversation, options)
  LLM-->>Rel: error (retryable)
  Rel->>LLM: retry with backoff
  LLM-->>Rel: completion
  Rel-->>App: completion with telemetry
```

---

### Pillar 4: Security & Compliance

**Goal:** Make it hard to accidentally leak secrets or data, and easy to see how data flows through the system.

#### Phase 4.1: Security audit (Priority: P0)
**Timeline:** Month 3
**Owner:** Core Team + External Auditor

**Tasks:**
- [ ] **Threat model and data flows**
  - Draw a simple data-flow diagram for prompts, tools, workspace, and storage.
  - Call out main threat types (prompt injection, data exfiltration, sandbox escape).
- [ ] **Code Security Review**
  - Review for SQL injection, XSS, command injection
  - Review API key handling (ensure `ApiKey` type prevents logging)
  - Review workspace container isolation
- [ ] **Dependency Vulnerability Scanning**
  - Integrate Snyk or Dependabot
  - Automate vulnerability alerts in CI/CD
- [ ] **Document Security Best Practices**
  - Location: `docs/SECURITY.md`
  - Include: API key management, network security, container security

**Related Docs:** `CLAUDE.md` (mentions avoiding security vulnerabilities)
**Dependencies:** None

#### Phase 4.2: Secrets Management (Priority: P0)
**Timeline:** Month 3
**Owner:** Core Team

**Tasks:**
- [ ] **Secrets Handling Guidelines**
  - Document use of environment variables
  - Recommend vault integration (HashiCorp Vault, AWS Secrets Manager)
- [ ] **Prevent Secret Leakage**
  - Review `ApiKey.toString` implementation (already redacted)
  - Audit logging to ensure no secrets in logs
- [ ] **Security Policy**
  - Create `SECURITY.md` with vulnerability reporting process

**Dependencies:** None

#### Phase 4.3: Compliance Documentation (Priority: P2)
**Timeline:** Month 6
**Owner:** Core Team

**Tasks:**
- [ ] **License Compliance**
  - Audit all dependencies for license compatibility
  - Document in `LICENSES.md`
- [ ] **GDPR/Privacy Considerations**
  - Document data handling for LLM interactions
  - Provide guidance on PII handling
- [ ] **Supply Chain Security**
  - Sign all release artifacts (already enabled in `release.yml`)
  - Document build reproducibility

**Related Docs:** `LICENSE` (currently Apache 2.0)
**Dependencies:** Phase 4.1

---

### Pillar 5: Documentation & Developer Experience

**Goal:** Help developers get from “clone” to “first working example” quickly, with docs that stay in sync with the code.

#### Phase 5.1: Documentation audit (Priority: P0)
**Timeline:** Month 2
**Owner:** Community + Core Team

**Tasks:**
- [ ] **Audit Existing Docs**
  - Review all `.md` files for accuracy
  - Identify gaps in coverage
- [ ] **Create Missing Guides**
  - [ ] `docs/GETTING_STARTED.md` - Quick start beyond README
  - [ ] `docs/ARCHITECTURE.md` - Detailed architecture overview
  - [ ] `docs/TESTING_GUIDE.md` - How to write tests
  - [ ] `docs/PRODUCTION_DEPLOYMENT.md` - Deployment patterns
  - [ ] `docs/TROUBLESHOOTING.md` - Common issues and solutions
- [ ] **Update Existing Docs**
  - [ ] `docs/AGENTS.md` - Expand with more examples
  - [ ] `docs/MIGRATION_GUIDE.md` - Add 0.x → 1.0 migration
  - [ ] `docs/tool-calling-api-design.md` - Update with latest API

**Related Docs:** All docs in `docs/`
**Dependencies:** None

#### Phase 5.2: API Documentation (Priority: P0)
**Timeline:** Month 3
**Owner:** Core Team

**Tasks:**
- [ ] **ScalaDoc Coverage**
  - Ensure all public APIs have ScalaDoc
  - Target: 100% ScalaDoc coverage on public APIs
- [ ] **Generate API Documentation Site**
  - Use sbt-site or similar
  - Publish to GitHub Pages
- [ ] **Interactive Examples**
  - Use mdoc or similar for executable docs
  - Embed working code snippets in documentation

**Dependencies:** Phase 5.1

#### Phase 5.3: Developer Onboarding (Priority: P1)
**Timeline:** Month 4
**Owner:** Community + Core Team

**Tasks:**
- [ ] **Contributor Guide**
  - Expand `CONTRIBUTING.md`
  - Include: coding standards, PR process, review guidelines
- [ ] **Video Tutorials**
  - Create YouTube playlist with:
    - Getting started (5 min)
    - Building a chatbot (15 min)
    - Advanced: Multi-agent system (30 min)
- [ ] **Improve Starter Kit**
  - Update `llm4s.g8` template
  - Add more example projects

**Related Docs:** `docs/llm4s-g8-starter-kit.md`
**Dependencies:** Phase 5.1

---

### Pillar 6: Observability & Operations

**Goal:** See what agents and providers are doing in production, and react quickly when things go wrong.

#### Phase 6.1: Enhanced tracing (Priority: P1)
**Timeline:** Month 3
**Owner:** Core Team

**Tasks:**
- [ ] **Distributed Tracing**
  - Integrate OpenTelemetry
  - Support trace context propagation
- [ ] **Structured Logging**
  - Standardize log formats (JSON logs)
  - Document log levels and verbosity
- [ ] **Custom Metrics**
  - Add Prometheus metrics exporter
  - Track: request latency, token usage, error rates, retry counts, backoff timing, deadline hits, and guardrail activity

**Related Docs:** `docs/langfuse-workflow-patterns.md`
**Dependencies:** None

#### Phase 6.2: Production monitoring guide (Priority: P1)
**Timeline:** Month 4
**Owner:** Core Team

**Tasks:**
- [ ] **Monitoring Best Practices**
  - Location: `docs/MONITORING.md`
  - Include: key metrics, alerting thresholds
- [ ] **Healthcheck Endpoints**
  - Add `/health` and `/ready` endpoints for LLM clients
  - Document in workspace protocol
- [ ] **Error Budget and SLOs**
  - Define service level objectives
  - Document in `docs/SLO.md`

**Related Docs:** `docs/workspace-agent-protocol.md`
**Dependencies:** Phase 6.1

#### Phase 6.3: Operational runbooks (Priority: P2)
**Timeline:** Month 5
**Owner:** Core Team

**Tasks:**
- [ ] **Incident Response Runbooks**
  - Common failures: API rate limits, timeouts, out-of-memory
  - Document recovery procedures
- [ ] **Capacity Planning Guide**
  - Estimate resource requirements for different scales
  - Document in `docs/CAPACITY_PLANNING.md`

**Dependencies:** Phase 6.1, Phase 6.2

---

## Reference deployment patterns

To make “production-ready” concrete, we will document three reference deployment setups in `docs/PRODUCTION_DEPLOYMENT.md`:

1. **Laptop / non-production**
   - Single-node sbt run with one provider or local Ollama.
   - Langfuse or console tracing only.
   - Good for experiments and learning, not for live traffic.

2. **Small single-tenant Kubernetes cluster**
   - llm4s app, workspace runner, Langfuse, and a vector store (for example, pgvector) deployed in one namespace.
   - Secrets managed via Kubernetes secrets or a cloud secret manager.
   - Basic SLOs defined for p95 latency and error rate.

3. **Enterprise VPC**
   - Private networking, managed secrets (Vault or cloud provider), and centralised logging/metrics.
   - Audit logging enabled for prompts, tool calls, and policy decisions.
   - PII policies enforced at the edge and in the workspace runner.

Each pattern will come with diagrams, sample manifests, and a short “when to use this” section.

```mermaid
graph TD
  Dev[Developer laptop] --> DevLLM[Local provider or single cloud model]
  K8s[Small k8s cluster] --> AppPod[llm4s app + workspace runner]
  AppPod --> Langfuse[Langfuse]
  AppPod --> Vec[Vector store]
  Ent[Enterprise VPC] --> EntApp[llm4s services]
  Ent --> Sec[Secrets manager]
  Ent --> Obs[Central logging and metrics]
  EntApp --> Audit[Audit log]
```

---

### Pillar 7: Community & Ecosystem

**Goal:** Grow a healthy contributor base and ecosystem so the project outlives its original authors.

#### Phase 7.1: Community growth (Priority: P1)
**Timeline:** Ongoing
**Owner:** Maintainers

**Tasks:**
- [ ] **Expand Contributor Base**
  - Target: 10+ active contributors
  - Label "good first issue" for new contributors
  - Monthly contributor recognition
- [ ] **Regular Dev Hours**
  - Continue weekly LLM4S Dev Hour (Sundays 9am London)
  - Publish session notes and recordings
- [ ] **Conference Talks**
  - Continue presenting at Scala conferences
  - Target: 2-3 talks per quarter

**Related Docs:** `README.md` (Dev Hour section)
**Dependencies:** None

#### Phase 7.2: Ecosystem Integration (Priority: P2)
**Timeline:** Month 5
**Owner:** Community + Core Team

**Tasks:**
- [ ] **Framework Integrations**
  - [ ] Akka/Pekko integration guide
  - [ ] ZIO integration guide
  - [ ] Cats Effect integration guide
  - [ ] Play Framework integration guide
- [ ] **Database Integrations**
  - [ ] Postgres vector extension (pgvector)
  - [ ] Elasticsearch integration
  - [ ] Qdrant/Weaviate integration
- [ ] **Monitoring Integrations**
  - [ ] Datadog integration
  - [ ] New Relic integration

**Dependencies:** None

#### Phase 7.3: Plugins and Extensions (Priority: P2)
**Timeline:** Month 6+
**Owner:** Community

**Tasks:**
- [ ] **Plugin Architecture**
  - Define plugin SPI (Service Provider Interface)
  - Document in `docs/PLUGINS.md`
- [ ] **Community Plugins**
  - Encourage community-contributed providers
  - Examples: Google Gemini, Cohere, local models
- [ ] **Marketplace/Registry**
  - Create plugin registry on llm4s.org
  - Include: verified plugins, ratings, documentation

**Dependencies:** Phase 2.3 (stable API)

---

## Timeline and Milestones

### Overview

```
Month 1-2: Foundation (Testing + API Audit)
Month 3-4: Hardening (Security + Performance)
Month 5: Stabilization (RC releases)
Month 6+: Launch & Growth (1.0.0 release)
```

```mermaid
graph TD
    M1[Months 1–2<br/>Foundation: tests and API audit] --> M2[Months 2–3<br/>Integration and performance baselines]
    M2 --> M3[Months 3–4<br/>Security and reliability]
    M3 --> M4[Month 5<br/>Stabilisation and RCs]
    M4 --> M5[Month 6+<br/>Launch and growth]
```

### Detailed Timeline

| Month | Milestone | Deliverables | Exit Criteria |
|-------|-----------|--------------|---------------|
| **Month 1** | Foundation Setup | - Test coverage audit<br>- API surface documentation<br>- Security audit initiation | - Coverage report available<br>- Public API list documented |
| **Month 2** | Testing Infrastructure | - Unit test coverage >60%<br>- Integration test framework<br>- Documentation audit complete | - Coverage passing 60%<br>- Integration tests runnable |
| **Month 3** | Hardening | - Security audit complete<br>- Performance benchmarks<br>- Enhanced tracing | - No critical vulnerabilities<br>- Baselines established |
| **Month 4** | Optimization | - Unit test coverage >80%<br>- API documentation site<br>- Monitoring guide | - Coverage passing 80%<br>- Docs site live |
| **Month 5** | Stabilization | - 1.0.0-RC1 release<br>- API freeze<br>- E2E scenarios | - RC1 published<br>- No open P0 bugs |
| **Month 6** | Launch Prep | - 1.0.0-RC2 (if needed)<br>- Final documentation review<br>- Launch communications | - Community sign-off<br>- All P0/P1 tasks complete |
| **Month 6+** | 1.0.0 Release | - Production release<br>- Launch blog post<br>- Community celebration | - 1.0.0 published to Maven Central |
| **Post-1.0** | Growth Phase | - Plugin ecosystem<br>- Framework integrations<br>- Continued improvements | - 3+ community plugins<br>- 2+ framework guides |

### Release Candidates Schedule

```
1.0.0-RC1: Month 5, Week 1
  ↓ (2 weeks of community testing)
1.0.0-RC2: Month 5, Week 3 (if needed)
  ↓ (2 weeks of final validation)
1.0.0:     Month 6, Week 1
```

---

## Success Metrics

### Quantitative Metrics

| Category | Metric | Current | Target (1.0) | Target (6mo post-1.0) |
|----------|--------|---------|--------------|----------------------|
| **Quality** | Statement Coverage | ~21% | 80% | 85% |
| **Quality** | Critical Bugs | Unknown | 0 | 0 |
| **Quality** | Open P0/P1 Issues | Unknown | <5 | <10 |
| **Community** | Contributors | 6 (GSoC+2) | 10+ | 20+ |
| **Community** | GitHub Stars | Current | +50% | +100% |
| **Community** | Discord Members | Current | +100 | +300 |
| **Adoption** | Maven Central Downloads | Current | 500/mo | 2000/mo |
| **Adoption** | Production Users | 0 | 5 | 20 |
| **Documentation** | ScalaDoc Coverage | <50% | 100% (public APIs) | 100% |
| **Documentation** | Guide Completeness | 60% | 95% | 100% |

### Qualitative Metrics

- **Developer Satisfaction**: Survey developers on ease of use, documentation quality
- **API Stability**: Zero unplanned breaking changes in 1.0.x series
- **Community Health**: Active Discord discussions, regular contributions
- **Production Readiness**: At least 3 case studies of production deployments, including one using a reference deployment pattern

### Known Limitations (1.0)

We will also document a short “known limitations” section before release 1.0, covering:
- Tool registries are not serialised; tools must be re-attached when restoring `AgentState`.
- Reasoning modes are provider-specific and may not be available on all models.
- Memory stores have size and TTL limits; long-term retention belongs in external systems.

---

## Roadmap cheatsheet

If you only have five minutes, this section gives you the big-picture view.

### Key phases at a glance

| Phase window | Focus | Main outcomes |
|--------------|-------|---------------|
| Months 1–2 | Testing + API audit | Coverage audit, unit tests in place, public API surface documented |
| Months 2–3 | Integration + perf baselines | Integration tests, RAG/agent flows covered, JMH benchmarks and performance baselines |
| Months 3–4 | Security + reliability | Security audit and threat model, reliable calling (retries/backoff/deadlines), enhanced tracing |
| Month 5 | Stabilisation | RC1, API freeze, end-to-end scenarios green |
| Month 6+ | Launch and growth | 1.0.0 release, docs polished, ecosystem work (plugins/integrations) |

```mermaid
graph TD
    M1[Months 1–2<br/>tests + API] --> M2[Months 2–3<br/>integration + perf]
    M2 --> M3[Months 3–4<br/>security + reliability]
    M3 --> M4[Month 5<br/>stabilisation]
    M4 --> M5[Month 6+<br/>launch + growth]
```

### Pillars and their one-line goals

- **Testing and quality:** Catch most bugs before runtime with solid unit, integration, and end-to-end tests.
- **API stability and versioning:** Let users upgrade safely with a clear public API and documented breaking-change policy.
- **Performance and scalability:** Know how the system behaves under load and keep it within predictable limits.
- **Security and compliance:** Make accidental data leaks and key exposure hard, and data flows easy to reason about.
- **Docs and developer experience:** Help developers reach a working example quickly, with docs that track the code.
- **Observability and operations:** See what agents and providers are doing in production and react quickly when things break.
- **Community and ecosystem:** Build a contributor base and plugin ecosystem that keeps the project healthy.

### One concrete task per pillar

- **Testing:** Enable coverage enforcement in CI and track targets in `docs/TEST_COVERAGE.md`.
- **API:** Pick a MiMa baseline and write down the rules in `docs/VERSIONING_POLICY.md`.
- **Performance:** Add `modules/benchmarks` and keep `docs/PERFORMANCE.md` updated each minor release.
- **Security:** Add a threat model and data-flow diagram under `Phase 4.1`, plus dependency scanning in CI.
- **Docs:** Add `docs/PRODUCTION_DEPLOYMENT.md` that shows how to deploy the three reference patterns.
- **Observability:** Emit reliability and guardrail metrics (retry counts, backoff timing, deadline hits, policy decisions).
- **Community:** Keep a small set of “good first issue” tickets aligned with this roadmap.
---

## Risk Assessment

### High-Risk Areas

#### Risk 1: Low Test Coverage Delays 1.0
**Likelihood:** High
**Impact:** High
**Mitigation:**
- Prioritize P0 testing tasks
- Engage community via "test bounty" program
- Parallelize test writing across modules

#### Risk 2: Breaking API Changes Required
**Likelihood:** Medium
**Impact:** High
**Mitigation:**
- Thorough API audit in Month 1
- Early RC releases for feedback
- Maintain compatibility layer for common patterns

#### Risk 3: Security Vulnerability Discovered
**Likelihood:** Medium
**Impact:** High
**Mitigation:**
- Complete security audit by Month 3
- Establish security response process
- Regular dependency scanning

#### Risk 4: Performance Regressions
**Likelihood:** Medium
**Impact:** Medium
**Mitigation:**
- Establish baselines early (Month 2)
- Add performance tests to CI/CD
- Monitor JMH benchmarks on every PR

### Medium-Risk Areas

#### Risk 5: Insufficient Documentation
**Likelihood:** Low
**Impact:** Medium
**Mitigation:**
- Documentation sprints in Months 2 & 4
- Community documentation days
- Reward documentation contributions

#### Risk 6: Community Contributor Burnout
**Likelihood:** Medium
**Impact:** Low
**Mitigation:**
- Distribute work across multiple contributors
- Recognize and reward contributions
- Maintain sustainable pace

---

## Appendix

### A. Related Documents

- [CLAUDE.md](/home/user/llm4s/CLAUDE.md) - AI Assistant Guide
- [README.md](/home/user/llm4s/README.md) - Project Overview
- [AGENTS.md](/home/user/llm4s/docs/AGENTS.md) - Agent Framework
- [MIGRATION_GUIDE.md](/home/user/llm4s/docs/MIGRATION_GUIDE.md) - API Migrations
- [RELEASE.md](/home/user/llm4s/docs/RELEASE.md) - Release Process
- [TEST_COVERAGE.md](/home/user/llm4s/docs/TEST_COVERAGE.md) - Coverage Guide
- [tool-calling-api-design.md](/home/user/llm4s/docs/tool-calling-api-design.md) - Tool API Design
- [workspace-agent-protocol.md](/home/user/llm4s/docs/workspace-agent-protocol.md) - Workspace Protocol

### B. Recommended GitHub Issues

Based on this roadmap, we recommend creating the following GitHub issues:

**Testing & Quality:**
- #TBD: Achieve 80% statement coverage across core modules
- #TBD: Create integration test framework
- #TBD: Add E2E test scenarios for reference applications
- #TBD: Enable coverage enforcement in CI/CD

**API Stability:**
- #TBD: Document public API surface area
- #TBD: Create versioning policy document
- #TBD: Deprecation and migration path for 0.x APIs
- #TBD: API freeze for 1.0.0-RC1

**Performance:**
- #TBD: Set up JMH benchmarking framework
- #TBD: Establish performance baselines
- #TBD: Memory profiling and optimization
- #TBD: Scalability testing and documentation

**Security:**
- #TBD: Conduct security audit
- #TBD: Integrate dependency vulnerability scanning
- #TBD: Create SECURITY.md with reporting process
- #TBD: Review and audit secrets management

**Documentation:**
- #TBD: Documentation audit and gap analysis
- #TBD: Create missing documentation guides
- #TBD: 100% ScalaDoc coverage on public APIs
- #TBD: Set up API documentation site (GitHub Pages)

**Observability:**
- #TBD: Integrate OpenTelemetry for distributed tracing
- #TBD: Add Prometheus metrics exporter
- #TBD: Create monitoring best practices guide
- #TBD: Add healthcheck endpoints

**Community:**
- #TBD: Label "good first issues" for new contributors
- #TBD: Create framework integration guides (ZIO, Cats Effect, etc.)
- #TBD: Define plugin architecture

### C. Contributing to This Roadmap

This roadmap is a living document. To propose changes:

1. Open an issue on GitHub with the `roadmap` label
2. Discuss in the `#roadmap` Discord channel
3. Submit a PR updating this document
4. Maintainers will review and merge approved changes

### D. Roadmap Tracking

Progress on this roadmap will be tracked via:

- **GitHub Project Board**: [LLM4S Production Roadmap](https://github.com/orgs/llm4s/projects/TBD)
- **Monthly Updates**: Published on Discord and mailing list
- **Quarterly Reviews**: Major milestones reviewed by maintainers and community

---

**Document Version:** 1.0
**Next Review:** 2025-12-15
**Maintainers:** Rory Graves, Kannupriya Kalra
**Contributors:** Community input welcome via GitHub issues
