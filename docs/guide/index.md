---
layout: page
title: User Guide
nav_order: 3
has_children: true
---

# User Guide

Comprehensive guides for LLM4S features.

## Available Guides

### Fundamentals

- **[Basic Usage](basic-usage)** - Get started with LLM calls, client creation, and error handling
- **[Providers](providers)** - Overview of supported providers and how to configure them
- **[Java](java)** - Call a model from Java with `llm4s-java-api`: a client, a conversation and failures you can read without Scala
- **[Spring Boot](spring-boot)** - `llm4s-spring-boot-starter`: properties, an `LLM4STemplate` bean, asynchronous calls and a health indicator
- **[Error Handling](error-handling)** - Work with `Result[A]` and `LLMError`: pattern matching, for-comprehensions, every error type, recovery and testing
- **[Structured Output](structured-output)** - `completeStructured`: typed replies from a schema, which providers enforce it, and what a bad reply looks like
- **[Writing a Provider](writing-a-provider)** - Publish your own provider module against the `llm4s-core` SPI
- **[Caching](caching)** - Cache embeddings (exact) and model responses (semantic): configuration, keys, TTL, eviction and what the cache reports
- **[JSON Libraries](json-libraries)** - Use circe, play-json or zio-json with LLM4S: converting at the boundary, structured output and tools

### Agent Framework

- **[Agents Overview](agents/)** - Build LLM-powered agents with tools, guardrails, and multi-turn conversations
  - **[Guardrails](agents/guardrails)** - Input/output validation for safety and quality
  - **[Memory System](agents/memory)** - Persistent context and knowledge across conversations
  - **[Handoffs](agents/handoffs)** - Agent-to-agent delegation for specialist routing
  - **[Streaming Events](agents/streaming)** - Real-time execution feedback for responsive UIs
- **[Built-in Tools](builtin-tools)** - Calculator, date and time, UUID, JSON, files, HTTP, shell and web search tools, the bundles that hold them, and what each one can do

### RAG & Semantic Search

- **[Vector Store](vector-store)** - Complete RAG toolkit for semantic search and retrieval
  - **Vector Backends**: SQLite (in-memory/file), PostgreSQL/pgvector, Qdrant
  - **Keyword Backends**: SQLite FTS5, PostgreSQL native full-text search
  - **Hybrid Search**: BM25 keyword + vector fusion with RRF strategy
  - **Reranking**: Cohere cross-encoder for result refinement
  - **Document Chunking**: Sentence-aware + simple chunking strategies

- **[Query Transformers](query-transformer)** - Rewrite the user's query before retrieval: `LLMQueryRewriter`, `IdentityTransformer`, chaining, error behaviour and custom transformers

- **[RAG Evaluation](rag-evaluation)** - Measure and improve RAG quality
  - **RAGAS Metrics**: Faithfulness, answer relevancy, context precision/recall
  - **Benchmarking Harness**: Compare chunking, fusion, and embedding strategies
  - **Optimization Workflow**: Data-driven RAG improvement

- **[Permission-Based RAG](permission-based-rag)** - Enterprise access control for RAG
  - **Hierarchical Collections**: Organize documents by tenant, team, or project
  - **Two-Level Permissions**: Collection-level `queryableBy` + document-level `readableBy`
  - **Pattern Queries**: `*`, `path/*`, `path/**` for flexible collection scoping
  - **Principal Management**: Map users/groups to efficient integer IDs

### Multimodal Capabilities

- **[Image Generation](image-generation)** - Generate images with DALL-E and other providers
- **[Speech](speech)** - Speech-to-text (STT) and text-to-speech (TTS)

### Effect Systems

- **[cats-effect](cats-effect)** - `LLMClientIO` and `AgentIO` for cats-effect `IO` and fs2 streaming
- **[ZIO](zio)** - `LLMClientZ` and `AgentZ` for ZIO 2 and ZIO Streams

### Java

- **[Java Threading and Cancellation](java-threading-and-cancellation)** - Which thread a call blocks, sharing one client, virtual threads, interrupts and timeouts for `llm4s-java-api`

### Observability

- **[Monitoring](observability/)** - Production monitoring for LLM4S applications
  - **Tracing**: Langfuse and OpenTelemetry integration
  - **Logging**: Structured JSON logging with SLF4J/Logback
  - **[Provider Exchange Logging](observability/provider-exchange-logging)**: Capture raw provider requests and responses for debugging
  - **Health Checks**: Startup validation and readiness probes
  - **Cost Monitoring**: Token usage tracking and budget awareness

## Feature Coverage via Examples

For features not yet documented as dedicated guides, see our **[Examples Gallery](/examples/)** which includes 69 working examples:

| Feature | Examples Section |
|---------|------------------|
| Basic LLM Calling | [Basic Examples](/examples/#basic-examples) |
| Multi-Turn Conversations | [Context Management Examples](/examples/#context-management-examples) |
| Agent Framework | [Agent Examples](/examples/#agent-examples) |
| Tool Calling | [Tool Examples](/examples/#tool-examples) |
| Guardrails & Safety | [Guardrails Examples](/examples/#guardrails-examples) |
| Agent Handoffs | [Handoff Examples](/examples/#handoff-examples) |
| Memory System | [Memory Examples](/examples/#memory-examples) |
| Streaming | [Streaming Examples](/examples/#streaming-examples) |
| Embeddings & RAG | [Embeddings Examples](/examples/#embeddings-examples) |
| MCP Integration | [MCP Examples](/examples/#mcp-examples) |
| Observability | [Monitoring Guide](observability/) |

## Design Documents

For in-depth technical documentation, see our [design documents](/reference/#design-documents):

- [Agent Framework Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-roadmap.md)
- [Phase 1.1: Conversations](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.1-functional-conversation-management.md)
- [Phase 1.2: Guardrails](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.2-guardrails-framework.md)
- [Phase 1.3: Handoffs](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.3-handoff-mechanism.md)

## Getting Help

- Browse [examples](/examples/) for working code samples
- Check the [Scaladoc](/scaladoc/) for API documentation
- Join our [Discord community](https://discord.gg/4uvTPn6qww) for support
