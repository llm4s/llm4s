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

[Get Started](/getting-started/installation){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[View on GitHub](https://github.com/llm4s/llm4s){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## Why LLM4S?

LLM4S brings the power of large language models to the Scala ecosystem with a focus on **type safety**, **functional programming**, and **production readiness**.

```scala
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage

// Simple LLM call with automatic provider selection
val result = for {
  client <- LLMConnect.create()
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

### 🔌 Multi-Provider Support
Connect seamlessly to **OpenAI**, **Anthropic**, **Azure OpenAI**, and **Ollama** with a unified API. Switch providers with a single environment variable.

[Learn more →](/guide/basic-usage)

### 🤖 Agent Framework
Build sophisticated single and multi-agent workflows with built-in tool calling, conversation management, and state persistence.

[Explore agents →](/guide/agents)

### 🛠️ Type-Safe Tool Calling
Define tools with automatic schema generation and type-safe execution. Supports both local tools and Model Context Protocol (MCP) servers.

[See examples →](/examples/tools)

### 💬 Multi-Turn Conversations
Functional, immutable conversation management with automatic context window pruning and conversation persistence.

[View patterns →](/guide/multi-turn)

### 🛡️ Guardrails & Validation
Declarative input/output validation framework for production safety. Built-in guardrails for length checks, profanity filtering, JSON validation, and custom business logic.

[Learn more →](/guide/guardrails)

### 🔍 RAG & Embeddings
Built-in support for retrieval-augmented generation with vector embeddings and semantic search.

[Get started →](/guide/embeddings)

### 📊 Observability
Comprehensive tracing with Langfuse integration for debugging, monitoring, and production analytics.

[Learn more →](/guide/observability)

### 🖼️ Multimodal Support
Generate and analyze images, convert speech-to-text and text-to-speech, and work with multiple content modalities.

[Image generation →](/guide/image-generation) | [Speech →](/guide/speech)

### 🐳 Secure Execution
Containerized workspace for safe tool execution with Docker isolation.

[Advanced topics →](/advanced/workspace)

---

## Quick Start

### Installation

Add LLM4S to your `build.sbt`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-core" % "0.1.0-SNAPSHOT"
```

### Configuration

Set your API key and model:

```bash
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...
```

### Your First Program

```scala
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object HelloLLM extends App {
  val result = for {
    client <- LLMConnect.create()
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

Explore **46 working examples** covering all features:

<div class="code-example" markdown="1">

**Basic Examples**
- [Basic LLM Calling](/examples/basic#basic-llm-calling) - Simple conversations
- [Streaming Responses](/examples/basic#streaming) - Real-time token streaming
- [Multi-Provider](/examples/basic#multi-provider) - OpenAI, Anthropic, Ollama

**Agent Examples**
- [Single-Step Agent](/examples/agents#single-step) - Manual control
- [Multi-Turn Conversations](/examples/agents#multi-turn) - Functional conversation API
- [Conversation Persistence](/examples/agents#persistence) - Save and resume

**Tool Examples**
- [Weather Tool](/examples/tools#weather) - Simple tool definition
- [Multi-Tool Agent](/examples/tools#multi-tool) - Multiple tools coordination
- [MCP Integration](/examples/tools#mcp) - External tool servers

**Guardrails Examples**
- [Basic Validation](/examples/guardrails#basic) - Length checks and profanity filtering
- [Custom Guardrails](/examples/guardrails#custom) - Build your own validators
- [Composite Guardrails](/examples/guardrails#composite) - Combine multiple validations

</div>

[Browse all examples →](/examples/)

---

## Documentation

<div class="grid">
  <div class="grid-item">
    <h3>📖 User Guide</h3>
    <p>Comprehensive guides for all features</p>
    <a href="/guide/basic-usage">Start learning →</a>
  </div>

  <div class="grid-item">
    <h3>💻 Examples</h3>
    <p>50+ working code examples</p>
    <a href="/examples/">Browse examples →</a>
  </div>

  <div class="grid-item">
    <h3>🚀 Advanced Topics</h3>
    <p>Production readiness & optimization</p>
    <a href="/advanced/production">Learn more →</a>
  </div>

  <div class="grid-item">
    <h3>📚 API Reference</h3>
    <p>Complete API documentation</p>
    <a href="/api/llm-client">View API docs →</a>
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

## Community

- **Discord**: [Join our community](https://discord.gg/4uvTPn6qww)
- **GitHub**: [llm4s/llm4s](https://github.com/llm4s/llm4s)
- **Starter Kit**: [llm4s.g8](https://github.com/llm4s/llm4s.g8)
- **License**: Apache 2.0

---

## Project Status

LLM4S is under active development. Recent milestones:

- ✅ **Phase 1.0**: Core framework and multi-provider support
- ✅ **Phase 1.1**: Functional conversation management
- ✅ **Phase 1.2**: Guardrails and validation framework
- 🚧 **Phase 1.3**: Multi-agent handoff mechanism
- 📋 **v1.0.0**: Production readiness (6-9 months)

[View detailed roadmap →](/reference/roadmap)

---

## Getting Help

- **Documentation**: Browse the [user guide](/guide/basic-usage)
- **Examples**: Check out [working examples](/examples/)
- **Discord**: Ask questions in our [community](https://discord.gg/4uvTPn6qww)
- **Issues**: Report bugs on [GitHub](https://github.com/llm4s/llm4s/issues)

---

**Ready to get started?** [Install LLM4S →](/getting-started/installation)
