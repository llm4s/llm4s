---
layout: page
title: Basic Usage
parent: User Guide
nav_order: 1
---

# Basic Usage Guide
{: .no_toc }

Learn the fundamentals of LLM4S: creating clients, making LLM calls, and handling results.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Getting Started with LLM Calls

LLM4S makes it simple to integrate Large Language Models into your Scala applications. The core workflow is:

1. **Configure** your LLM provider via environment variables or config files
2. **Create** an LLM client
3. **Send** messages to the LLM
4. **Handle** the result (success or error)

Let's walk through each step.

---

## Simple Client Creation Examples

### Minimal Example

The simplest way to get an LLM response:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.llmconnect.model.{Conversation, UserMessage}

object SimpleExample extends App {
  // Step 1: Load configuration from environment variables
  val startup = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
  } yield {
    // Step 2: Create a simple message
    val conversation = Conversation(Seq(UserMessage("What is Scala?")))
    
    // Step 3: Get a response
    val response = client.complete(conversation)
    
    // Step 4: Handle the result
    response match {
      case Right(completion) =>
        println(s"Response: ${completion.content}")
      case Left(error) =>
        println(s"Error: $error")
    }
  }
  
  // Execute and report any startup errors
  startup.left.foreach(err => println(s"Startup Error: $err"))
}
```

**Before running, configure your environment:**

```bash
# OpenAI
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-proj-...

# Or Anthropic
export LLM_MODEL=anthropic/claude-opus-4-6
export ANTHROPIC_API_KEY=sk-ant-...
```

### Multi-Provider Pattern

LLM4S automatically handles provider selection based on your `LLM_MODEL`:

```scala
// With LLM_MODEL=openai/gpt-4o → Uses OpenAI
// With LLM_MODEL=anthropic/claude-opus-4-6 → Uses Anthropic
// With LLM_MODEL=gemini/gemini-2.0-flash → Uses Google Gemini
// With LLM_MODEL=ollama/mistral → Uses local Ollama

val startup = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
} yield processWithAnyProvider(client)
```

The same code works with any provider without modifications!

### With Explicit Model Selection

If you want to override the configured model:

```scala
val startup = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
} yield {
  val conversation = Conversation(Seq(UserMessage("Tell me about Scala")))
  
  // Adjust generation settings (the model is selected via LLM_MODEL env var / providerConfig)
  val response = client.complete(
    conversation,
    CompletionOptions(maxTokens = Some(512))
  )
  
  response.map(completion => println(completion.content))
}
```

---

## Understanding Result Types and Error Handling

### The Result Type

In LLM4S, operations return `Result[A]` instead of throwing exceptions. This is a type alias for `Either[LLMError, A]`:

```scala
type Result[+A] = Either[LLMError, A]
```

This approach provides:
- **Type-safe error handling** - Errors are part of the type signature
- **No surprise exceptions** - All failures are explicit
- **Composable operations** - Easy to chain operations with `for` comprehensions

### Pattern Matching on Results

The most common approach is pattern matching:

```scala
val result: Result[Completion] = client.complete(conversation)

result match {
  case Right(completion) =>
    // Success! Access the response
    println(s"Content: ${completion.content}")
    println(s"Stop reason: ${completion.stopReason}")
  case Left(error) =>
    // Handle the error
    println(s"Error: ${error.message}")
    println(s"Type: ${error.getClass.getSimpleName}")
}
```

### LLM Errors

All LLM operations return `LLMError` on failure:

```scala
sealed trait LLMError {
  def message: String
}

// Subtypes:
case class ProviderConnectionError(message: String) extends LLMError
case class InvalidApiKeyError(message: String) extends LLMError
case class RateLimitError(message: String) extends LLMError
case class ParseError(message: String) extends LLMError
case class ModelNotFoundError(message: String) extends LLMError
case class GeneralLLMError(message: String) extends LLMError
```

### Using For-Comprehensions

For cleaner code, use Scala's `for` comprehensions:

```scala
val result = for {
  // Configure and create client
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  
  // Make the LLM call — complete takes a Conversation and optional CompletionOptions
  completion <- client.complete(
    Conversation(Seq(UserMessage("Hello, LLM!")))
  )
} yield {
  // All operations succeeded - work with the completion
  completion.content
}

// Execute
result match {
  case Right(content) => println(s"Success: $content")
  case Left(error) => println(s"Failed: ${error.message}")
}
```

This is much cleaner than nested pattern matches!

### Converting from Try to Result

If you have code using `Try`, convert it with the `TryOps` extension:

```scala
import org.llm4s.types.TryOps

val tryValue = Try("123".toInt)
val result = tryValue.toResult  // Result[Int]
```

---

## Message Management

### User Messages

Send simple user queries:

```scala
val userMsg = UserMessage("What is Scala?")
val response = client.complete(Conversation(Seq(userMsg)))
```

### Assistant Messages

Include assistant responses for multi-turn conversations:

```scala
val conversation = Conversation(Seq(
  UserMessage("What is Scala?"),
  AssistantMessage("Scala is a functional programming language..."),
  UserMessage("Tell me more about its type system")
))

val response = client.complete(conversation)
```

### System Messages

Prepend a `SystemMessage` to the conversation to set context and behavior:

```scala
val conversation = Conversation(Seq(
  SystemMessage("You are a Scala expert. Answer concisely."),
  UserMessage("What is a monad?")
))

val response = client.complete(conversation)
```

---

## Configuration Methods

### Environment Variables (Recommended)

Simplest approach for development:

```bash
# Required
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...

# Optional settings
export TRACING_MODE=console
export EMBEDDING_MODEL=openai/text-embedding-3-small
```

### HOCON Configuration File

Create `application.conf`:

```hocon
llm4s {
  model = "openai/gpt-4o"
  llm {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = "https://api.openai.com/v1"
    }
  }
}
```

### System Properties

Pass via JVM arguments:

```bash
java -Dllm4s.model=openai/gpt-4o \
     -Dllm4s.llm.openai.api-key=sk-... \
     -jar app.jar
```

---

## Common Patterns

### Handling Invalid Configuration

Always check startup results:

```scala
val startup = for {
  config <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(config)
} yield client

startup match {
  case Right(client) =>
    // Use the client
    val result = client.complete(Conversation(Seq(UserMessage("Hello!"))))
  case Left(error) =>
    // Configuration failed - log and exit
    System.err.println(s"Startup failed: ${error.message}")
    System.exit(1)
}
```

### Retrying Failed Calls

For transient failures, implement retry logic:

```scala
def retryWithBackoff[T](maxRetries: Int = 3)(
  operation: () => Result[T]
): Result[T] = {
  (1 until maxRetries).foldLeft(operation()) { (acc, attempt) =>
    if (acc.isRight) acc
    else {
      Thread.sleep(math.pow(2, attempt.toDouble).toLong * 100)
      operation()
    }
  }
}

// Usage
val response = retryWithBackoff(3) { () =>
  client.complete(Conversation(Seq(UserMessage("Hello!"))))
}
```

### Logging Errors

Use SLF4J for consistent logging:

```scala
import org.slf4j.LoggerFactory

val logger = LoggerFactory.getLogger(getClass)

val response = client.complete(Conversation(Seq(UserMessage("Hello!"))))
response match {
  case Right(completion) =>
    logger.info(s"Got response: ${completion.content}")
  case Left(error) =>
    // LLMError is not a Throwable, so pass only the message string
    logger.error(s"LLM call failed: ${error.message}")
}
```
---

## Troubleshooting

### "Invalid API Key"

- Verify your `_API_KEY` environment variable is set and correct
- Check the API key has the right permissions on the provider's dashboard
- Ensure no extra whitespace in the key

### "Model not found"

- Verify the model name matches the provider's API
- Check [MODEL_METADATA.md](/MODEL_METADATA.md) for available models
- Some models may not be available in your region or account

### "Configuration not found"

- Ensure `LLM_MODEL` is set: `export LLM_MODEL=openai/gpt-4o`
- For non-OpenAI providers, set the corresponding `_API_KEY` (e.g., `ANTHROPIC_API_KEY`)
- Check that `.env` file is in the right directory (project root)

### "Connection timeout"

- Verify internet connectivity
- Check if the provider's service is operational
- Try using a different network or VPN
- Increase the timeout in configuration if needed


