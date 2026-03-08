---
layout: page
title: API Reference
nav_order: 6
has_children: true
---

# API Reference

Complete API documentation for LLM4S.

## Primary API Documentation

### Scaladoc

The **[Scaladoc](/scaladoc/)** provides comprehensive, auto-generated API documentation for all LLM4S classes and methods.

### API Specifications

| Document | Description |
|----------|-------------|
| [LLM4S API Spec](../llm4s-api-spec) | Complete API specification |
| [Tool Calling API Design](../tool-calling-api-design) | Tool calling interface design |
| [Workspace Agent Protocol](../workspace-agent-protocol) | Standardized interface for LLM workspace interaction |

## API Design Principles

LLM4S follows these design principles:

### 1. Result-Based Error Handling

**Never use exceptions** for expected errors. Use `Result[A]` instead:

```scala
type Result[+A] = Either[error.LLMError, A]

// Example
def complete(messages: List[Message]): Result[CompletionResponse]
```

### 2. Type Safety

Use **newtype wrappers** to prevent type confusion:

```scala
case class ModelName(value: String) extends AnyVal
case class ApiKey(private val value: String) extends AnyVal

// ✅ Type-safe
def setModel(model: ModelName): Unit

// ❌ Stringly-typed
def setModel(model: String): Unit
```

### 3. Immutability

All data structures are **immutable**:

```scala
// Conversation state is immutable
val state2 = agent.continueConversation(state1, "Next question")
// state1 is unchanged, state2 is a new instance
```

### 4. Explicit Configuration

Use **Llm4sConfig** instead of raw environment access:

```scala
import org.llm4s.config.Llm4sConfig

// ✅ Type-safe configuration
val config = Llm4sConfig.provider()

// ❌ Raw access
val apiKey = sys.env("OPENAI_API_KEY")
```

### 5. Provider Abstraction

Single interface works across **all providers**:

```scala
val client: LLMClient = ??? // OpenAI, Anthropic, Azure, or Ollama
// Same API regardless of provider
```

## Common Patterns

### Pattern 1: Result Chaining

```scala
import org.llm4s.config.Llm4sConfig

val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  response <- client.complete(messages, None)
  parsed <- parseResponse(response)
} yield parsed
```

### Pattern 2: Fold for Error Handling

```scala
result.fold(
  error => println(s"Error: $error"),
  success => println(s"Success: $success")
)
```

### Pattern 3: Map and FlatMap

```scala
val content: Result[String] = response.map(_.content)
val upper: Result[String] = content.map(_.toUpperCase)
```

## Core Module Structure

The main `llm4s-core` module contains:

```
org.llm4s/
├── types/              # Result, ModelName, etc.
├── config/             # Llm4sConfig + typed loaders
├── llmconnect/         # LLMClient, providers
├── agent/              # Agent framework
├── toolapi/            # Tool calling
├── trace/              # Tracing
├── context/            # Context management
└── error/              # Error types
```

## Error Hierarchy

```scala
sealed trait LLMError
case class NetworkError(message: String) extends LLMError
case class AuthenticationError(message: String) extends LLMError
case class ValidationError(message: String) extends LLMError
case class ConfigurationError(message: String) extends LLMError
// ... and more
```

## Versioning

LLM4S follows semantic versioning:

- **MAJOR**: Breaking API changes
- **MINOR**: New features, backwards compatible
- **PATCH**: Bug fixes

Currently: `0.1.0-SNAPSHOT` (pre-release)

## Scala Version Compatibility

LLM4S supports:
- **Scala 2.13.16**
- **Scala 3.7.1**

Cross-compilation ensures the same API on both versions.

## Migration Guides

When APIs change, consult:
- **[Migration Guide](/reference/migration)** - Upgrade instructions
- **[Changelog](https://github.com/llm4s/llm4s/releases)** - Release notes

## Getting Help

- **[User Guide](/guide/)** - Learn how to use the APIs
- **[Examples](/examples/)** - See APIs in action (69 working examples)
- **[Discord](https://discord.gg/4uvTPn6qww)** - Ask questions
- **[GitHub Issues](https://github.com/llm4s/llm4s/issues)** - Report bugs

---

**Explore the APIs:**
- [Scaladoc](/scaladoc/) - Complete API reference
- [Examples](/examples/) - See APIs in action
