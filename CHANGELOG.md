# Changelog

All notable changes to llm4s are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning: [Semantic Versioning](https://semver.org/). Binary compatibility is enforced by MiMa against the previous release — see [API Stability](docs/reference/api-stability.md).

---

## [Unreleased]

### Added
- MiMa binary compatibility checks for `core`, `trace-opentelemetry`, and `knowledgegraph-neo4j` modules
- CI job that enforces binary compatibility on every PR
- `docs/reference/api-stability.md` — documents the stable public API contract and internal packages
- `ImagePricingRegistry` and `InstrumentedImageGenerationClient` for image generation cost tracking
- `RegexSafetyManager` and `WorkspaceRegexSafetyManager` for regex-based content safety
- `TraceEvent`, `TraceCollector`, `OpenTelemetryTracing` — enhanced tracing pipeline
- Enhanced `LLMMemoryManager` with importance scoring and consolidation
- Secret scanning CI workflow
- `funding.json`

---

## [0.2.7] — 2026-05-xx

### Added
- Prometheus metrics: `MetricsCollector`, `PrometheusMetrics`, `PrometheusEndpoint`, health checks
- Context management system: `LLMCompressor`, `DeterministicCompressor`, `ToolOutputCompressor`, `HistoryCompressor`, `SemanticBlocks`, `TokenWindow`, `ConversationTokenCounter`, `ContextManager`
- Assistant API: `AssistantAgent`, `SessionManager`, `SessionState`, `ConsoleInterface`
- Session serialization — save and restore `AgentState` to JSON
- Reasoning modes — OpenAI o1/o3, DeepSeek reasoner, configurable effort levels
- Exa Search built-in tool
- Voyage AI embeddings provider

---

## [0.2.6] — 2026-04-xx

### Added
- RAG evaluation: RAGAS metrics (faithfulness, answer relevancy, context precision/recall)
- RAG benchmarking harness
- RAG-specific guardrails: PII detection/masking, prompt injection, grounding, context relevance, source attribution, topic boundary
- Hybrid search fusion (RRF + weighted score)
- BM25 keyword index (SQLite FTS5 + PostgreSQL full-text search)

---

## [0.2.5] — 2026-03-xx

### Added
- Reranking pipeline: `CohereReranker`, `LLMReranker`
- Document chunking: sentence-aware, markdown-aware, semantic chunkers
- Ollama embeddings provider

---

## [0.2.4] — 2026-02-xx

### Added
- RAG core engine with retrieval pipeline
- Vector store backends: SQLite, pgvector, Qdrant
- MCP (Model Context Protocol) client integration
- Streaming events: `AgentStreamingExecutor`, full lifecycle event types

---

## [0.1.16] — 2026-01-xx

### Added
- Agent handoffs: agent-to-agent delegation with context preservation
- Agent memory system: short/long-term memory, SQLite store, vector search
- Async tool execution strategies (parallel, sequential)
- Built-in tools: DateTime, Calculator, UUID, JSON, HTTP, file operations, web search
- Guardrails framework: input/output validation, LLM-as-Judge, composite composition
- Multi-provider support: OpenAI, Anthropic, Google Gemini, Azure OpenAI, DeepSeek, Ollama
- Multimodal: vision (OpenAI, Anthropic), speech STT/TTS, image generation
- Langfuse and OpenTelemetry tracing backends
- Cross-version support: Scala 2.13 and 3.x

---

[Unreleased]: https://github.com/llm4s/llm4s/compare/v0.2.7...HEAD
[0.2.7]: https://github.com/llm4s/llm4s/compare/v0.2.6...v0.2.7
[0.2.6]: https://github.com/llm4s/llm4s/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/llm4s/llm4s/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/llm4s/llm4s/compare/v0.1.16...v0.2.4
[0.1.16]: https://github.com/llm4s/llm4s/releases/tag/v0.1.16
