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

### Agent Framework

- **[Agents Overview](agents/)** - Build LLM-powered agents with tools, guardrails, and multi-turn conversations
  - **[Guardrails](agents/guardrails)** - Input/output validation for safety and quality
  - **[Memory System](agents/memory)** - Persistent context and knowledge across conversations
  - **[Handoffs](agents/handoffs)** - Agent-to-agent delegation for specialist routing
  - **[Streaming Events](agents/streaming)** - Real-time execution feedback for responsive UIs

### RAG & Semantic Search

- **[Vector Store](vector-store)** - Complete RAG toolkit for semantic search and retrieval
  - **Vector Backends**: SQLite (in-memory/file), PostgreSQL/pgvector, Qdrant
  - **Keyword Backends**: SQLite FTS5, PostgreSQL native full-text search
  - **Hybrid Search**: BM25 keyword + vector fusion with RRF strategy
  - **Reranking**: Cohere cross-encoder for result refinement
  - **Document Chunking**: Sentence-aware + simple chunking strategies

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

### Observability

- **[Monitoring](observability/)** - Production monitoring for LLM4S applications
  - **Tracing**: Langfuse and OpenTelemetry integration
  - **Logging**: Structured JSON logging with SLF4J/Logback
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
