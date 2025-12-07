---
layout: page
title: Roadmap
parent: Reference
nav_order: 5
---

# LLM4S Roadmap

Development roadmap and future plans for LLM4S.

---

## Current Status

**Version**: 0.1.0-SNAPSHOT (Pre-release)

**Stability**: Active development, breaking changes possible

---

## Core Framework Features

Beyond the agent framework phases, LLM4S provides comprehensive core functionality:

### LLM Connectivity

| Feature | Status | Documentation |
|---------|--------|---------------|
| Multi-Provider Support | ✅ Complete | [Basic Usage](/guide/basic-usage) |
| OpenAI Integration | ✅ Complete | [Providers](/guide/providers) |
| Anthropic Integration | ✅ Complete | [Providers](/guide/providers) |
| Azure OpenAI Integration | ✅ Complete | [Providers](/guide/providers) |
| Ollama (Local Models) | ✅ Complete | [Providers](/guide/providers) |
| Streaming Responses | ✅ Complete | [Streaming](/guide/streaming) |
| Model Metadata API | ✅ Complete | [API Reference](/api/llm-client) |

### Content Generation

| Feature | Status | Documentation |
|---------|--------|---------------|
| Image Generation | ✅ Complete | [Image Generation](/guide/image-generation) |
| Speech-to-Text (STT) | ✅ Complete | [Speech](/guide/speech) |
| Text-to-Speech (TTS) | ✅ Complete | [Speech](/guide/speech) |
| Embeddings API | ✅ Complete | [Embeddings](/guide/embeddings) |

### Tools & Integration

| Feature | Status | Documentation |
|---------|--------|---------------|
| Tool Calling API | ✅ Complete | [Tools](/guide/tools) |
| MCP Server Support | ✅ Complete | [MCP](/guide/mcp) |
| Built-in Tools Module | ✅ Complete | [Built-in Tools](/examples/#tool-examples) |
| Workspace Isolation (Docker) | ✅ Complete | [Workspace](/advanced/workspace) |

### Infrastructure

| Feature | Status | Documentation |
|---------|--------|---------------|
| Type-Safe Configuration | ✅ Complete | [Configuration](/guide/configuration) |
| Result-Based Error Handling | ✅ Complete | [Error Handling](/guide/error-handling) |
| Langfuse Observability | ✅ Complete | [Observability](/guide/observability) |
| Cross-Version Support (2.13/3.x) | ✅ Complete | [Installation](/getting-started/installation) |

---

## Agent Framework

The agent framework extends core LLM4S with advanced agent capabilities. Agents are one module within the larger LLM4S ecosystem.

### Completed Features

| Phase | Feature | Key Capabilities | Design Doc |
|-------|---------|------------------|------------|
| 1.0 | Core Agent | Basic agent, tool calling, streaming | - |
| 1.1 | Conversations | Immutable state, `continueConversation()`, pruning | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.1-functional-conversation-management.md) |
| 1.2 | Guardrails | Input/output validation, LLM-as-Judge | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.2-guardrails-framework.md) |
| 1.3 | Handoffs | Agent-to-agent delegation, context preservation | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.3-handoff-mechanism.md) |
| 1.4 | Memory | Short/long-term memory, SQLite, vector search | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.4-memory-system.md) |
| 2.1 | Streaming Events | Agent lifecycle events, `runWithEvents()` | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.1-streaming-events.md) |
| 2.2 | Async Tools | Parallel execution strategies | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.2-async-tools.md) |
| 3.2 | Built-in Tools | DateTime, Calculator, HTTP, file ops, web search | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-3.2-builtin-tools.md) |
| 4.1 | Reasoning Modes | Extended thinking for o1/o3, Claude | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.1-reasoning-modes.md) |
| 4.3 | Serialization | AgentState save/load to JSON | [Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.3-session-serialization.md) |

### In Progress

| Phase | Feature | Status | Planned Capabilities |
|-------|---------|--------|---------------------|
| 3.1 | Workflow Engines | Parked | Camunda/Temporal integration, durable execution |
| 3.3 | Enhanced Observability | Planning | Plugin architecture, multi-backend tracing |
| 4.2 | Provider Expansion | Planning | Cohere, Mistral, Gemini, LiteLLM |

[View agent examples →](/examples/#agent-examples)

---

## Production Readiness Roadmap

The path to v1.0.0 follows the "Seven Production Pillars":

### 1. Reliability
- Error recovery
- Retry mechanisms
- Circuit breakers
- Graceful degradation

### 2. Performance
- Response time optimization
- Caching strategies
- Connection pooling
- Resource management

### 3. Observability
- Comprehensive tracing
- Metrics collection
- Logging standards
- Debugging tools

### 4. Security
- API key management
- Input validation
- Output sanitization
- Audit logging

### 5. Scalability
- Load handling
- Resource limits
- Horizontal scaling
- Multi-tenancy

### 6. Documentation
- Complete API docs
- Production guides
- Best practices
- Migration paths

### 7. Testing
- Unit test coverage >80%
- Integration tests
- Load testing
- Security testing

**Full Details**: [Production Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/roadmap/PRODUCTION_ROADMAP.md)

**Target**: v1.0.0 in Q3 2025 (6-9 months)

---

## Long-Term Vision

### Core Platform Evolution

| Area | Planned Features |
|------|------------------|
| **RAG & Search** | Vector database integration, hybrid search, document chunking |
| **Multimodal** | Video processing, audio generation, vision analysis |
| **Streaming** | Parallel streams, multiplexing, backpressure handling |
| **Performance** | Intelligent prompt caching, connection pooling |
| **Fine-tuning** | Model adaptation support, LoRA integration |
| **Providers** | Gemini, Cohere, Mistral, LiteLLM (100+ models) |

### Agent Evolution

| Area | Planned Features |
|------|------------------|
| **Multi-Agent** | Advanced orchestration, DAG workflows |
| **Learning** | Adaptation, feedback loops |
| **Planning** | Goal decomposition, strategy selection |

**Details**: [Agent Framework Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-roadmap.md)

---

## Community Priorities

Help us prioritize! Vote on features:

1. **[Feature Requests](https://github.com/llm4s/llm4s/issues?q=is%3Aissue+label%3Aenhancement)** - Upvote what you need
2. **[Discussions](https://github.com/llm4s/llm4s/discussions)** - Share your use cases
3. **[Discord](https://discord.gg/4uvTPn6qww)** - Join the conversation

---

## Release Schedule

### Current Cycle

- **Weekly**: SNAPSHOT builds
- **Monthly**: Feature previews
- **Quarterly**: Milestone releases

### Versioning

- **0.x.x**: Pre-1.0 development
- **1.0.0**: First stable release
- **Semantic Versioning**: After 1.0.0

---

## Contributing to the Roadmap

Want to influence the roadmap?

1. **Share Use Cases**: What are you building?
2. **Request Features**: What do you need?
3. **Contribute Code**: Help build features
4. **Join Discussions**: Discord and GitHub

---

## Stay Updated

- **Watch the repo**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Join Discord**: [Community](https://discord.gg/4uvTPn6qww)
- **Follow releases**: [GitHub Releases](https://github.com/llm4s/llm4s/releases)

---

## Design Documents

Technical design documents are available in the [docs/design](https://github.com/llm4s/llm4s/tree/main/docs/design) directory.

### Agent Framework Phases

| Phase | Document |
|-------|----------|
| Overview | [Agent Framework Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-roadmap.md) |
| 1.1 | [Functional Conversation Management](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.1-functional-conversation-management.md) |
| 1.2 | [Guardrails Framework](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.2-guardrails-framework.md) |
| 1.3 | [Handoff Mechanism](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.3-handoff-mechanism.md) |
| 1.4 | [Memory System](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.4-memory-system.md) |
| 2.1 | [Streaming Events](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.1-streaming-events.md) |
| 2.2 | [Async Tools](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.2-async-tools.md) |
| 3.2 | [Built-in Tools](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-3.2-builtin-tools.md) |
| 4.1 | [Reasoning Modes](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.1-reasoning-modes.md) |
| 4.3 | [Session Serialization](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.3-session-serialization.md) |

---

**Questions about the roadmap?** [Ask in Discord](https://discord.gg/4uvTPn6qww)
