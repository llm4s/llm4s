# LLM4S Production Readiness Attack Plan

Date: 2026-06-13

## Strategy

Build the production-grade path side by side with the existing implementation, migrate docs/examples/users onto it, then remove or deprecate old surfaces only at planned compatibility boundaries.

The priority is not "more features everywhere." The priority is creating a stable spine that makes the existing breadth trustworthy:

1. Stable API contracts
2. Deterministic tests and CI
3. Provider capability parity
4. JVM ecosystem adoption
5. Security and governance
6. Runnable documentation and reference apps

## P0: Stabilize the Signal

Target: immediate

Goal: make the repo trustworthy enough that every later change has clear feedback.

Deliverables:

- Fix or isolate the local `sbt test` non-reporting/hang around `LocalImageProcessorTest`.
- Add suite/test timeouts so CI cannot silently hang.
- Split test commands into clear tiers:
  - unit/local fast tests
  - local integration tests
  - Docker-backed integration tests
  - provider smoke tests
  - benchmarks
- Align public metadata:
  - README version/support claims
  - `docs/_data/project.yml`
  - roadmap status
  - Maven artifact names
  - Scala/Java support matrix
  - provider support matrix
- Remove, replace, or explicitly mark `???` placeholders in user-facing docs.
- Add a short `CURRENT_STATUS.md` or docs page explaining what is stable, beta, experimental, and planned.

Exit criteria:

- `sbt test` completes or known flaky/slow suites are isolated.
- Docs no longer contradict the build.
- A newcomer can tell which modules/APIs are safe to build on.

## P1: Define The Stable Spine

Target: 1-2 weeks

Goal: create the production-grade API path beside the existing broad surface.

Deliverables:

- Define stable module boundaries:
  - `core`: provider API, model types, errors, config models
  - `tools`: tool schema/execution contracts
  - `agents`: agent runtime, state, handoffs, guardrails
  - `rag`: loaders, vector abstractions, retrieval/evaluation
  - `observability`: metrics/tracing contracts and integrations
  - `interop`: Java/Kotlin/Spring-facing APIs
- Mark experimental modules/features explicitly.
- Land or replace MiMa/binary compatibility checks.
- Create a public compatibility policy:
  - source compatibility
  - binary compatibility
  - deprecation period
  - breaking-change cadence
- Add package-level Scaladoc for the stable modules.
- Add compile-only compatibility tests for old and new APIs.

Exit criteria:

- Stable API surface is named and documented.
- Old and new paths can coexist.
- Breaking changes have a defined process.

## P2: Provider Capability Matrix And Contract Tests

Target: 2-5 weeks

Goal: make provider behavior explicit, tested, and comparable.

Deliverables:

- Create a provider capability model covering:
  - chat
  - streaming
  - tool calling
  - structured output
  - reasoning/thinking
  - embeddings
  - image generation
  - image understanding
  - speech-to-text
  - text-to-speech
  - timeout config
  - retry/rate-limit semantics
  - token usage
  - cost estimation
  - raw exchange logging
- Generate docs from this matrix.
- Add fake-provider contract test servers for supported protocol families:
  - OpenAI-compatible
  - Anthropic
  - Gemini/Google
  - Cohere
  - Mistral
  - Ollama/local
- Standardize provider options:
  - connect/read/request timeout
  - base URL
  - custom headers
  - proxy
  - request IDs
  - user agent
  - retry-after handling
  - error mapping
- Finish queued provider parity work:
  - configurable timeouts
  - structured output
  - Cohere/Mistral streaming
  - Vertex AI
  - Bedrock

Exit criteria:

- Every provider declares what it supports.
- Unsupported capabilities fail consistently.
- Provider behavior is tested without live API calls.

## P3: JVM Interop And Adoption Path

Target: 3-6 weeks

Goal: make LLM4S usable by the wider JVM ecosystem, not only Scala users.

Deliverables:

- Java API facade:
  - Java-friendly result type or exception-boundary facade
  - Java collections
  - builders instead of Scala-only constructors
  - no exposed `using`, Scala `Either`, or Scala collections in Java entrypoints
- Kotlin coroutine wrapper:
  - `suspend` APIs
  - idiomatic nullable/error handling
  - sample Ktor or Spring Kotlin service
- Spring Boot starter:
  - auto-configuration
  - properties binding
  - conditional beans
  - health indicators
  - metrics integration
  - test slice/example
- Gradle/Maven examples:
  - Java quickstart
  - Kotlin quickstart
  - Spring Boot quickstart
- CI compiles and tests all starter/sample projects.

Exit criteria:

- A Java, Kotlin, and Spring Boot user can build a working app from docs in under 15 minutes.
- Interop APIs are covered by tests and examples.

## P4: Security And Governance

Target: 4-8 weeks

Goal: make tool/RAG/MCP usage safe by default.

Deliverables:

- Land and maintain a versioned threat model.
- Define default-deny policies for:
  - tools
  - shell/workspace access
  - file reads/writes
  - network access
  - MCP servers
  - provider exchange logging
- Harden MCP:
  - auth
  - allowlists
  - tool capability scoping
  - audit logs
  - prompt-injection guidance
  - tool poisoning mitigations
  - safe defaults for stdio/SSE/streamable HTTP transports
- Add SBOM generation to release CI.
- Add dependency/security scanning to CI.
- Add examples for PII redaction, retention, and multi-tenant logging.
- Create a security release checklist.

Exit criteria:

- Production users have a documented safe default configuration.
- Security checks are automated.
- Tool/MCP boundaries are treated as first-class risk areas.

## P5: Reliability, Cost, And Observability

Target: 5-9 weeks

Goal: make production behavior measurable and bounded.

Deliverables:

- Make reliability wrappers easy to apply by default:
  - retries
  - circuit breakers
  - deadlines
  - rate-limit handling
  - provider fallback
- Add health checks for provider clients where possible.
- Complete cost tracking:
  - per request
  - per agent run
  - per session
  - embeddings
  - RAG query
  - image/audio operations
- Standardize metrics:
  - latency
  - token usage
  - cost
  - retry attempts
  - circuit state
  - tool call duration
  - RAG retrieval quality
- Add trace/replay story for agent runs.
- Add cache APIs/docs:
  - embedding cache
  - LLM response cache
  - retrieval result cache

Exit criteria:

- A production service can expose useful metrics with minimal custom code.
- Users can bound latency, cost, and provider failure behavior.

## P6: Performance And Scale

Target: 6-10 weeks

Goal: replace performance assumptions with baselines.

Deliverables:

- Land JMH benchmark module.
- Benchmark:
  - token counting
  - message serialization
  - provider response parsing
  - tool execution
  - schema validation
  - chunking
  - vector search
  - hybrid search
  - RAG evaluation
  - memory retrieval
- Publish baseline results.
- Add regression thresholds for critical paths.
- Document tuning guidance:
  - thread pools
  - timeouts
  - batch sizes
  - vector-store config
  - chunking strategy
  - memory retention

Exit criteria:

- Performance-sensitive paths have measured baselines.
- Regressions are caught before release.

## P7: Documentation And Reference Applications

Target: parallel, complete before v1.0 beta

Goal: make the golden path obvious and executable.

Deliverables:

- Replace broad feature claims with verified, generated, or tested claims.
- Add docs test/compile checks for code snippets where practical.
- Create golden-path tutorials:
  - basic typed chat app
  - tool-calling service
  - RAG over documents with pgvector or Qdrant
  - agent with memory and guardrails
  - Spring Boot REST API
  - production observability and cost tracking
  - MCP client/server integration
- Create maintained reference apps:
  - Java/Gradle quickstart
  - Kotlin coroutine service
  - Spring Boot RAG API
  - secure agent workspace demo
  - observability/evaluation demo
- Add troubleshooting/FAQ.
- Add migration guide for each deprecated old path.

Exit criteria:

- Main docs examples compile or are clearly marked as pseudocode.
- The stable path is the first path users see.
- Reference apps run in CI.

## P8: Migration And Deletion Plan

Target: after stable replacements ship

Goal: avoid breaking users until replacement paths are proven.

Steps:

1. Build new APIs beside existing APIs.
2. Move docs and examples to the new APIs.
3. Add deprecation warnings on old APIs.
4. Publish migration notes.
5. Keep old APIs through at least one compatibility window.
6. Remove old APIs only in a declared breaking release.

Migration rules:

- Do not remove an old API until:
  - replacement is documented
  - replacement is tested
  - examples use replacement
  - migration guide exists
  - release notes call out the change

## Suggested Workstreams

### Workstream A: CI And Release

Owns:
- test hang/timeouts
- tiered test commands
- MiMa
- release/version metadata
- benchmark CI
- SBOM/security scan

### Workstream B: Provider Platform

Owns:
- capability matrix
- provider contract tests
- provider parity PRs
- timeout/error/retry standardization
- smoke tests

### Workstream C: JVM Interop

Owns:
- Java facade
- Kotlin coroutine API
- Spring Boot starter
- Gradle/Maven examples
- interop docs

### Workstream D: Security And Tools

Owns:
- threat model
- MCP hardening
- tool policies
- workspace defaults
- audit/redaction docs

### Workstream E: Docs And Reference Apps

Owns:
- docs cleanup
- runnable snippets
- troubleshooting
- golden-path tutorials
- reference applications

## Immediate Next 10 Issues To Create Or Execute

1. Fix or isolate `sbt test` hang around `LocalImageProcessorTest`.
2. Add global ScalaTest suite timeout policy.
3. Align README, roadmap, and `docs/_data/project.yml` version/support/provider metadata.
4. Create generated provider capability matrix.
5. Add fake OpenAI-compatible provider contract test server.
6. Add fake Anthropic provider contract test server.
7. Land Java facade and Java/Gradle sample.
8. Land Spring Boot starter and smoke-test sample app.
9. Land threat model and default-deny tool/MCP policy docs.
10. Remove or mark `???` placeholders in all user-facing guide snippets.

## Definition Of Done For "Production Ready"

LLM4S can be called production-ready when:

- Stable modules have compatibility guarantees.
- CI is deterministic and split by test tier.
- Provider capabilities are explicit and contract-tested.
- Java, Kotlin, Spring, Maven, and Gradle users have first-class paths.
- Tool/MCP/workspace security defaults are documented and enforced.
- Cost, latency, retries, tracing, and metrics are available without custom framework code.
- Docs examples are runnable or clearly marked pseudocode.
- At least three real reference apps are maintained in CI.
- Migration guides exist for deprecated APIs.
- A v1.0 release candidate can be cut without relying on undocumented tribal knowledge.
