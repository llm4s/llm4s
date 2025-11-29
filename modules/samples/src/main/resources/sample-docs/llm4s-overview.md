# LLM4S Framework Overview

## What is LLM4S?

LLM4S (Large Language Models for Scala) is a Scala framework for building LLM-powered applications. It provides a type-safe, functional approach to working with language models.

## Core Features

### Multi-Provider Support

LLM4S supports multiple LLM providers through a unified API:
- OpenAI (GPT-4, GPT-4o, GPT-3.5)
- Anthropic (Claude models)
- Azure OpenAI
- Ollama (local models)

Configure your provider using environment variables:
```bash
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...
```

### Type-Safe Error Handling

The framework uses `Result[A]` (an alias for `Either[LLMError, A]`) instead of exceptions. This makes error handling explicit and composable.

```scala
val result: Result[CompletionResponse] = client.complete(conversation)
result match {
  case Right(response) => println(response.message.content)
  case Left(error) => println(s"Error: ${error.formatted}")
}
```

### Agent Framework

LLM4S includes a powerful agent framework with:
- Tool calling - Define custom tools for agents to use
- Guardrails - Input and output validation
- Memory - Persistent context across conversations
- Handoffs - Delegate to specialized agents

### Memory System

The memory system enables RAG (Retrieval-Augmented Generation) patterns:
- `VectorMemoryStore` - SQLite-based vector storage with semantic search
- `SimpleMemoryManager` - High-level API for recording and retrieving memories
- Support for different memory types: Knowledge, Conversation, Entity, UserFact

### Embeddings

Generate embeddings for semantic similarity:
- OpenAI embeddings (text-embedding-3-small, text-embedding-ada-002)
- VoyageAI embeddings
- Mock embeddings for testing

## Configuration

LLM4S uses `Llm4sConfig` for all configuration loading. Core logic should not access environment variables directly:

```scala
// Good
val providerCfg = Llm4sConfig.provider()

// Bad - don't do this
val key = sys.env.get("API_KEY")
```

## Cross-Compilation

The framework supports both Scala 2.13 and Scala 3.x through cross-compilation. Test with:
```bash
sbt +test
```
