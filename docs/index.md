---
layout: default
title: Home
nav_order: 1
description: "LLM4S - Large Language Models for Scala. A comprehensive, type-safe framework for building LLM-powered applications."
permalink: /
---

# LLM4S - Large Language Models for Scala
{: .fs-9 }

A comprehensive, type-safe framework for building LLM-powered applications in Scala.
{: .fs-6 .fw-300 }

[![Maven Central](https://img.shields.io/maven-central/v/org.llm4s/llm4s-core_3.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:org.llm4s%20AND%20a:llm4s-core_3)
[![CI](https://github.com/llm4s/llm4s/actions/workflows/ci.yml/badge.svg)](https://github.com/llm4s/llm4s/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Discord](https://img.shields.io/discord/1234567890?color=7289da&label=Discord&logo=discord&logoColor=white)](https://discord.gg/4uvTPn6qww)

[Get Started](/getting-started/installation){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/llm4s/llm4s){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## Why LLM4S?

LLM4S brings the power of large language models to the Scala ecosystem with a focus on **type safety**, **functional programming**, and **production readiness**.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage

// Simple LLM call with automatic provider selection
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  response <- client.complete(
    messages = List(UserMessage("Explain quantum computing")),
    model = None  // Uses configured model
  )
} yield response

result match {
  case Right(completion) => println(completion.content)
  case Left(error) => println(s"Error: $error")
}
```

---

## Key Features

### Core LLM Platform

<div class="feature-grid" markdown="1">

#### 🔌 Multi-Provider Support
Connect seamlessly to **OpenAI**, **Anthropic**, **Azure OpenAI**, **Google Gemini**, and **Ollama** with a unified API. Switch providers with a single environment variable.
[Learn more →](/examples/#basic-examples)

#### 📡 Streaming Responses
Real-time token streaming with backpressure handling and error recovery.
[View examples →](/examples/#streaming)

#### 🔍 RAG & Embeddings
Complete RAG pipeline: vector storage (SQLite, pgvector, Qdrant), hybrid search with BM25 keyword matching (SQLite FTS5 or PostgreSQL native), Cohere cross-encoder reranking, and sentence-aware document chunking. For production deployment, see [RAG in a Box](https://github.com/llm4s/rag_in_a_box).
[Vector stores →](/guide/vector-store) | [Examples →](/examples/#embeddings-examples)

#### 🖼️ Multimodal Support
Generate and analyze images, convert speech-to-text and text-to-speech, and work with multiple content modalities.
[Image generation →](/guide/image-generation) | [Speech →](/guide/speech)

#### 📊 Observability
Comprehensive tracing with Langfuse integration for debugging, monitoring, and production analytics.
[Learn more →](/examples/#other-examples)

#### 🛠️ Type-Safe Tool Calling
Define tools with automatic schema generation and type-safe execution. Supports both local tools and Model Context Protocol (MCP) servers.
[See examples →](/examples/#tool-examples)

</div>

### Agent Framework

<div class="feature-grid" markdown="1">

#### 🤖 Agent Framework
Build sophisticated single and multi-agent workflows with built-in tool calling, conversation management, and state persistence.
[Explore agents →](/guide/agents/)

#### 💬 Multi-Turn Conversations
Functional, immutable conversation management with automatic context window pruning and conversation persistence.
[View patterns →](/guide/agents/#multi-turn-conversations)

#### 🛡️ Guardrails & Validation
Declarative input/output validation framework for production safety. Built-in guardrails for length checks, profanity filtering, JSON validation, tone validation, and LLM-as-Judge.
[Learn more →](/guide/agents/guardrails)

#### 🔄 Agent Handoffs
LLM-driven agent-to-agent delegation for specialist routing. Simple API for handing off queries to domain experts with automatic context preservation.
[See examples →](/guide/agents/handoffs)

#### 🧠 Memory System
Short-term and long-term memory with entity tracking. In-memory, SQLite, and vector store backends for semantic search across conversations.
[Explore memory →](/guide/agents/memory)

#### 💭 Reasoning Modes
Extended thinking support for OpenAI o1/o3 and Anthropic Claude. Configure reasoning effort levels and access thinking content.
[Learn more →](/examples/#reasoning-examples)

</div>

### Infrastructure

<div class="feature-grid" markdown="1">

#### ⚡ Built-in Tools
Pre-built tools for common tasks: DateTime, Calculator, UUID, JSON parsing, HTTP requests, web search, and file operations with security controls.
[Browse tools →](/examples/#tool-examples)

#### 🐳 Secure Execution
Containerized workspace for safe tool execution with Docker isolation.
[Advanced topics →](/advanced/)

</div>

---

## Quick Start

### Installation

Add LLM4S to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-core" % "{{ site.data.project.version }}"
```

{: .note }
> **Current Version:** `{{ site.data.project.version }}`
> Check [Maven Central](https://search.maven.org/search?q=g:org.llm4s%20AND%20a:llm4s-core_3) for the latest release.

### Configuration

Set your API key and model:

```bash
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...
```

### Your First Program

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object HelloLLM extends App {
  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    response <- client.complete(
      messages = List(
        SystemMessage("You are a helpful assistant."),
        UserMessage("What is Scala?")
      ),
      model = None
    )
  } yield response.content

  result.fold(
    error => println(s"Error: $error"),
    content => println(s"Response: $content")
  )
}
```

[Complete installation guide →](/getting-started/installation)

---

## Example Gallery

Explore **69 working examples** covering all features:

<div class="code-example" markdown="1">

**Basic Examples**
- [Basic LLM Calling](/examples/#basic-llm-calling) - Simple conversations
- [Streaming Responses](/examples/#streaming) - Real-time token streaming
- [Multi-Provider](/examples/#ollama) - OpenAI, Anthropic, Ollama

**Agent Examples**
- [Multi-Turn Conversations](/examples/#multi-turn) - Functional conversation API
- [Async Tool Execution](/examples/#agent-examples) - Parallel tool strategies
- [Conversation Persistence](/examples/#persistence) - Save and resume

**Guardrails & Safety**
- [Input/Output Validation](/examples/#guardrails-examples) - Length, profanity, JSON
- [LLM-as-Judge](/examples/#guardrails-examples) - Semantic validation
- [Custom Guardrails](/examples/#custom) - Build your own validators

**Handoffs & Memory**
- [Agent Handoffs](/examples/#handoff-examples) - Specialist delegation
- [Memory System](/examples/#memory-examples) - Entity and context memory
- [Vector Search](/examples/#memory-examples) - Semantic retrieval

**Tools & Streaming**
- [Built-in Tools](/examples/#tool-examples) - DateTime, HTTP, file access
- [Streaming Events](/examples/#streaming-examples) - Real-time agent events
- [Reasoning Modes](/examples/#reasoning-examples) - Extended thinking

</div>

[Browse all examples →](/examples/)

---

## Documentation

<div class="grid">
  <div class="grid-item">
    <h3>🤖 Agent Framework</h3>
    <p>Tools, guardrails, memory, handoffs</p>
    <a href="/guide/agents/">Learn agents →</a>
  </div>

  <div class="grid-item">
    <h3>📖 User Guide</h3>
    <p>RAG, vector stores, multimodal</p>
    <a href="/guide/">Browse guides →</a>
  </div>

  <div class="grid-item">
    <h3>💻 Examples</h3>
    <p>70 working code examples</p>
    <a href="/examples/">Browse examples →</a>
  </div>

  <div class="grid-item">
    <h3>🚀 Advanced Topics</h3>
    <p>Production readiness & optimization</p>
    <a href="/advanced/">Learn more →</a>
  </div>

  <div class="grid-item">
    <h3>📚 API Reference</h3>
    <p>Complete API documentation</p>
    <a href="/api/">View API docs →</a>
  </div>

  <div class="grid-item">
    <h3>📖 Scaladoc</h3>
    <p>Generated API documentation</p>
    <a href="/scaladoc/">Browse Scaladoc →</a>
  </div>
</div>

---

## Why Scala for LLMs?

<div class="highlight-box">

✅ **Type Safety** - Catch errors at compile time, not in production

✅ **Functional Programming** - Immutable data and pure functions for predictable systems

✅ **JVM Ecosystem** - Access to mature, production-grade libraries

✅ **Concurrency** - Advanced models for safe, efficient parallelism

✅ **Performance** - JVM speed with functional elegance

✅ **Enterprise Ready** - Seamless integration with JVM systems

</div>

---

## Compatibility

### Scala & JDK Support

| Scala Version | JDK Version | Status |
|---------------|-------------|--------|
| 3.7.x | 21, 17 | ✅ Fully Supported |
| 2.13.x | 21, 17 | ✅ Fully Supported |

### LLM Provider Support

| Provider | Status | Models |
|----------|--------|--------|
| **OpenAI** | ✅ Complete | GPT-4o, GPT-4, GPT-3.5, o1, o3 |
| **Anthropic** | ✅ Complete | Claude 3.5, Claude 3 |
| **Azure OpenAI** | ✅ Complete | All Azure-hosted models |
| **Ollama** | ✅ Complete | Llama, Mistral, local models |
| **Google Gemini** | ✅ Complete | Gemini 2.0, 1.5 Pro/Flash |
| **Cohere** | 🚧 Planned | Coming soon |

---

## Community

- **Discord**: [Join our community](https://discord.gg/4uvTPn6qww)
- **GitHub**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Starter Kit**: [llm4s.g8](https://github.com/llm4s/llm4s.g8)
- **License**: MIT

---

## Project Status

LLM4S is under active development with comprehensive LLM capabilities.

### Core Framework (Complete)

| Category | Features |
|----------|----------|
| **LLM Providers** | OpenAI, Anthropic, Azure, Ollama |
| **Content Generation** | Text, Images, Speech (STT/TTS), Embeddings |
| **Tools & Integration** | Tool Calling, MCP Servers, Built-in Tools, Workspace Isolation |
| **Infrastructure** | Type-Safe Config, Result Error Handling, Langfuse Tracing |

### Agent Framework Phases

- ✅ **Phase 1.0-1.4**: Core agents, conversations, guardrails, handoffs, memory
- ✅ **Phase 2.1-2.2**: Event streaming, async tool execution
- ✅ **Phase 3.2**: Built-in tools module
- ✅ **Phase 4.1, 4.3**: Reasoning modes, session serialization
- 🚧 **Next**: Enhanced observability, provider expansion
- 📋 **v1.0.0**: Production readiness

[View detailed roadmap →](/reference/roadmap)

---

## Getting Help

- **Documentation**: Browse the [user guide](/guide/)
- **Examples**: Check out [69 working examples](/examples/)
- **Discord**: Ask questions in our [community](https://discord.gg/4uvTPn6qww)
- **Issues**: Report bugs on [GitHub](https://github.com/llm4s/llm4s/issues)

---

**Ready to get started?** [Install LLM4S →](/getting-started/installation)
