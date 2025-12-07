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

## Agent Framework Phases

The agent framework extends core LLM4S with advanced agent capabilities:

### ✅ Phase 1.0: Core Framework

**Status**: Complete

- Multi-provider support (OpenAI, Anthropic, Azure, Ollama)
- Type-safe API design
- Result-based error handling
- Basic agent framework
- Tool calling infrastructure
- Streaming support
- Configuration management

### ✅ Phase 1.1: Functional Conversation Management

**Status**: Complete (Nov 2024)

**Key Features:**
- Immutable conversation state
- `continueConversation()` API
- Context window management
- Conversation persistence
- Pruning strategies

**Details**: [Phase 1.1 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.1-functional-conversation-management.md)

### ✅ Phase 1.2: Guardrails Framework

**Status**: Complete (Nov 2024)

**Implemented Features:**
- ✅ Type-safe input/output validation
- ✅ Declarative guardrail composition
- ✅ Built-in guardrails (length, profanity, JSON, regex, tone)
- ✅ Custom guardrail extensibility
- ✅ Composite guardrails (sequential, all, any)
- ✅ Integration with agent workflows
- ✅ Comprehensive examples and documentation

**Examples:**
- [BasicInputValidationExample](/examples/guardrails#basic)
- [CustomGuardrailExample](/examples/guardrails#custom)
- [CompositeGuardrailExample](/examples/guardrails#composite)
- [JSONOutputValidationExample](/examples/guardrails)
- [MultiTurnToneValidationExample](/examples/guardrails)

**Details**: [Phase 1.2 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.2-guardrails-framework.md)

### ✅ Phase 1.3: Agent Handoffs

**Status**: Complete

**Key Features:**
- LLM-driven agent-to-agent delegation
- Context preservation across handoffs
- Simple triage routing patterns
- Integration with existing agent workflows

**Examples:**
- [SimpleTriageHandoffExample](/examples/handoffs)
- [MathSpecialistHandoffExample](/examples/handoffs)
- [ContextPreservationExample](/examples/handoffs)

**Details**: [Phase 1.3 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.3-handoff-mechanism.md)

### ✅ Phase 1.4: Memory System

**Status**: Complete

**Key Features:**
- Short-term and long-term memory types
- Entity tracking across conversations
- In-memory, SQLite, and vector store backends
- Semantic search with embeddings
- Memory filtering and consolidation

**Examples:**
- [BasicMemoryExample](/examples/memory)
- [SQLiteMemoryExample](/examples/memory)
- [VectorMemoryExample](/examples/memory)

**Details**: [Phase 1.4 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.4-memory-system.md)

### ✅ Phase 2.1: Event-based Streaming

**Status**: Complete

**Key Features:**
- Fine-grained agent execution events
- Text delta and tool call events
- Agent lifecycle events (started, completed, failed)
- Guardrail and handoff events
- `runWithEvents()` and `continueConversationWithEvents()` APIs

**Examples:**
- [StreamingAgentExample](/examples/streaming)
- [EventCollectionExample](/examples/streaming)

**Details**: [Phase 2.1 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.1-streaming-events.md)

### ✅ Phase 2.2: Async Tool Execution

**Status**: Complete

**Key Features:**
- Parallel tool execution strategies
- Sequential, Parallel, and ParallelWithLimit modes
- `ToolRegistry.executeAsync()` and `executeAll()` methods
- `Agent.runWithStrategy()` for parallel tool execution

**Examples:**
- [ParallelToolExecutionExample](/examples/tools)
- [AsyncToolAgentExample](/examples/agents)

**Details**: [Phase 2.2 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.2-async-tools.md)

### ✅ Phase 3.2: Built-in Tools

**Status**: Complete

**Key Features:**
- Core tools: DateTime, Calculator, UUID, JSON
- File operations: read, write, list, info
- HTTP requests with configurable methods
- Shell command execution with security restrictions
- Web search (DuckDuckGo integration)
- Tool bundles: `BuiltinTools.core()`, `safe()`, `withFiles()`, `development()`

**Examples:**
- [BuiltinToolsExample](/examples/tools)
- [BuiltinToolsAgentExample](/examples/agents)

**Details**: [Phase 3.2 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-3.2-builtin-tools.md)

### ✅ Phase 4.1: Reasoning Modes

**Status**: Complete

**Key Features:**
- ReasoningEffort levels (None, Low, Medium, High)
- OpenAI o1/o3 reasoning_effort support
- Anthropic extended thinking with budget tokens
- `Completion.thinking` for thinking content access
- `TokenUsage.thinkingTokens` for token tracking

**Examples:**
- [ReasoningModesExample](/examples/reasoning)

**Details**: [Phase 4.1 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.1-reasoning-modes.md)

### ✅ Phase 4.3: Session Serialization

**Status**: Complete

**Key Features:**
- Full AgentState serialization to/from JSON
- ReasoningEffort and CompletionOptions serialization
- Backward-compatible deserialization
- Save/load to file support

**Details**: [Phase 4.3 Design Doc](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.3-session-serialization.md)

---

## In Progress

### 🚧 Phase 3.1: Workflow Engine Integration

**Status**: Parked for design

**Planned Features:**
- Integration with workflow engines (Camunda, Temporal)
- Durable agent execution
- Human-in-the-loop support
- Crash recovery

### 🚧 Phase 3.3: Enhanced Observability

**Status**: Planning

**Planned Features:**
- Plugin architecture for tracing backends
- Additional integrations (Logfire, AgentOps, Braintrust)
- Custom spans and multi-backend tracing

### 🚧 Phase 4.2: Provider Expansion

**Status**: Planning

**Planned Features:**
- Additional LLM providers (Cohere, Mistral, Gemini)
- LiteLLM integration for 100+ providers

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

### Agent Framework Evolution

**Vision**: [Agent Framework Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-roadmap.md)

**Key Areas:**
- Advanced multi-agent systems
- Learning and adaptation
- Planning and reasoning
- Memory systems
- Tool ecosystems

### Additional Features

- **Enhanced RAG**: Vector database integration, hybrid search
- **Multimodal**: Video processing, audio generation
- **Advanced Streaming**: Parallel streams, multiplexing
- **Caching**: Intelligent prompt caching
- **Fine-tuning**: Model adaptation support

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

All design documents are available in the [docs/design](https://github.com/llm4s/llm4s/tree/main/docs/design) directory:

- [Agent Framework Roadmap](https://github.com/llm4s/llm4s/blob/main/docs/design/agent-framework-roadmap.md)
- [Phase 1.1: Functional Conversation](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.1-functional-conversation-management.md)
- [Phase 1.2: Guardrails](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.2-guardrails-framework.md)
- [Phase 1.3: Handoff](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.3-handoff-mechanism.md)
- [Phase 1.4: Memory System](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-1.4-memory-system.md)
- [Phase 2.1: Streaming Events](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.1-streaming-events.md)
- [Phase 2.2: Async Tools](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-2.2-async-tools.md)
- [Phase 3.2: Built-in Tools](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-3.2-builtin-tools.md)
- [Phase 4.1: Reasoning Modes](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.1-reasoning-modes.md)
- [Phase 4.3: Session Serialization](https://github.com/llm4s/llm4s/blob/main/docs/design/phase-4.3-session-serialization.md)

---

**Questions about the roadmap?** [Ask in Discord](https://discord.gg/4uvTPn6qww)
