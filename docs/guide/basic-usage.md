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

1. **Configure** a named provider section in your `application.conf`
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
import org.llm4s.model.ModelRegistryService

object SimpleExample extends App {
  // Step 1: Load the configured default named provider
  val startup = for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry       <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
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

**Before running, configure a provider** in `src/main/resources/application.conf`:

```hocon
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

and export the vendor's key, which `llm4s-openai` binds for any OpenAI section without an
`apiKey` of its own:

```bash
export OPENAI_API_KEY=sk-proj-...
```

For Anthropic, use a section with `provider = "anthropic"`, export `ANTHROPIC_API_KEY`, and name
the section in `provider`. llm4s reads no `LLM_MODEL`. See
[Named provider sections](../getting-started/configuration.md#named-provider-sections) and
[API keys](../getting-started/configuration.md#api-keys).

### Multi-Provider Pattern

LLM4S resolves whichever named provider you configure as the default:

```scala
val startup = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client <- LLMConnect.getClient(providerConfig)
} yield processWithAnyProvider(client)
```

The same code works with any configured named provider without modifications: change
`llm4s.providers.provider`, or load another section with `Llm4sConfig.provider("<name>")`. See
[Switching providers](../getting-started/configuration.md#switching-providers).

### With Explicit Model Selection

If you want to override the configured model:

```scala
val startup = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client <- LLMConnect.getClient(providerConfig)
} yield {
  val conversation = Conversation(Seq(UserMessage("Tell me about Scala")))
  
  // Adjust generation settings while keeping the configured default provider
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

All LLM operations return an `LLMError` on failure. It is an open trait in `org.llm4s.error`
with a `message`, an optional `code` and a `context` map, and each subtype is either
recoverable (worth retrying) or not:

```scala
import org.llm4s.error._

error match {
  case _: AuthenticationError => // the key was rejected: fix the credentials
  case _: RateLimitError      => // wait, then retry
  case _: NetworkError        => // transient: retry
  case _: ConfigurationError  => // missing or invalid configuration
  case other                  => println(other.formatted)
}

val retryable = error match {
  case _: RecoverableError => true  // RateLimitError, NetworkError, TimeoutError, ...
  case _                   => false // NonRecoverableError, or an error that carries neither marker
}
```

Match on the `RecoverableError` marker trait rather than calling `LLMError.isRecoverable`: some errors
from other modules (`EmbeddingError`, `RerankError`, a custom `LLMError`) carry neither marker, and
`isRecoverable` throws a `MatchError` on them.

See the [Error Handling guide](error-handling.md) for every error type, when it is raised, and how
to handle, convert and test them.

### Using For-Comprehensions

For cleaner code, use Scala's `for` comprehensions:

```scala
val result = for {
  // Configure and create client
  providerConfig <- Llm4sConfig.defaultProvider()
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

### application.conf (Recommended)

Providers are named sections under `llm4s.providers` in your `src/main/resources/application.conf`,
with secrets bound from the environment by `${?VAR}`:

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider = "openai"
      model    = "gpt-4o"
    }
  }
}
```

```bash
export OPENAI_API_KEY=sk-...
```

### Environment Variables

llm4s reads an environment variable only where a `${?VAR}` binding names it - in your own
`application.conf`, or in a module's `reference.conf`. A few settings are bound for you,
for example:

```bash
export OPENAI_API_KEY=sk-...                            # llm4s.credentials.openai.apiKey
export TRACING_MODE=console                             # llm4s.tracing.mode
export EMBEDDING_MODEL=openai/text-embedding-3-small    # llm4s.embeddings.model
```

`LLM_MODEL` is **not** among them. See
[Environment variables llm4s reads](../getting-started/configuration.md#environment-variables-llm4s-reads).

### System Properties

JVM system properties override `application.conf`, key for key:

```bash
java -Dllm4s.providers.provider=claude \
     -Dllm4s.providers.openai-main.model=gpt-4o-mini \
     -jar app.jar
```

---

## Common Patterns

### Handling Invalid Configuration

Always check startup results:

```scala
val startup = for {
  config   <- Llm4sConfig.defaultProvider()
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
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

- Verify `OPENAI_API_KEY` (or the variable the section's own `apiKey` binds, which wins) is set and correct
- Check the API key has the right permissions on the provider's dashboard
- Ensure no extra whitespace in the key

### "Model not found"

- Verify the model name matches the provider's API
- Check [MODEL_METADATA.md](/MODEL_METADATA.md) for available models
- Some models may not be available in your region or account

### "Configuration not found"

- Ensure `application.conf` (in `src/main/resources`) has a section under `llm4s.providers` and
  that `llm4s.providers.provider` names it - `LLM_MODEL` is not read
- Ensure the vendor's variable (or the one the section's own `apiKey` binds) is exported in the
  shell that starts the JVM
- Check the error names the section you meant to load: `defaultProvider()` loads the one
  `llm4s.providers.provider` names, and only that section is validated

### "Connection timeout"

- Verify internet connectivity
- Check if the provider's service is operational
- Try using a different network or VPN
- Increase the timeout in configuration if needed

