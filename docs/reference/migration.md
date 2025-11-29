# Migration Guide

## MessageRole Enum Changes (v0.2.0)

### Breaking Change
The `MessageRole` has been converted from string-based constants to a proper enum type for better type safety.

### Before (v0.1.x)
```scala
import org.llm4s.llmconnect.model.Message

val message = Message(role = "assistant", content = "Hello")
message.role match {
  case "assistant" => // handle assistant
  case "user" => // handle user
  case _ => // handle other
}
```

### After (v0.2.0)
```scala
import org.llm4s.llmconnect.model.{Message, MessageRole}

val message = AssistantMessage(content = "Hello")
// or
val message = Message(role = MessageRole.Assistant, content = "Hello")

message.role match {
  case MessageRole.Assistant => // handle assistant
  case MessageRole.User => // handle user
  case MessageRole.System => // handle system
  case MessageRole.Tool => // handle tool
}
```

### Migration Steps

1. **Update imports**: Add `MessageRole` to your imports
   ```scala
   import org.llm4s.llmconnect.model.MessageRole
   ```

2. **Replace string comparisons**: Update pattern matches and comparisons
   ```scala
   // Before
   if (message.role == "assistant") { ... }
   
   // After
   if (message.role == MessageRole.Assistant) { ... }
   ```

3. **Update message creation**: Use the typed constructors
   ```scala
   // Before
   Message(role = "user", content = "Hello")
   
   // After
   UserMessage(content = "Hello")
   // or
   Message(role = MessageRole.User, content = "Hello")
   ```

## Error Hierarchy Changes (v0.2.0)

### New Error Categorization
Errors are now categorized using traits for better type safety and recovery strategies.

### Before (v0.1.x)
```scala
error match {
  case e: LLMError if e.isRecoverable => // retry logic
  case e: LLMError => // handle non-recoverable
}
```

### After (v0.2.0)
```scala
error match {
  case e: RecoverableError => // retry logic
  case e: NonRecoverableError => // handle non-recoverable
}
```

### Error Recovery Pattern
```scala
import org.llm4s.error._

def handleError(error: LLMError): Unit = error match {
  case _: RateLimitError => // wait and retry
  case _: TimeoutError => // retry with backoff
  case _: ServiceError with RecoverableError => // retry
  case _: AuthenticationError => // refresh token or fail
  case _: ValidationError => // fix input and retry
  case _ => // non-recoverable, fail
}
```

### Migration Steps

1. **Replace `isRecoverable` checks**: Use pattern matching on traits
   ```scala
   // Before
   if (error.isRecoverable) { ... }
   
   // After
   error match {
     case _: RecoverableError => { ... }
     case _ => { ... }
   }
   ```

2. **Update error handling**: Use the new trait-based categorization
   ```scala
   // Before
   case e: ServiceError if e.isRecoverable =>
   
   // After
   case e: ServiceError with RecoverableError =>
   ```

3. **Use smart constructors**: Create errors using the companion object methods
   ```scala
   // Before
   new RateLimitError(429, "Rate limit exceeded", Some(60.seconds))
   
   // After
   RateLimitError(429, "Rate limit exceeded", Some(60.seconds))
   ```

## Configuration Changes (v0.2.0+)

### EnvLoader and legacy ConfigReader → Llm4sConfig

Older versions used `EnvLoader` and a custom `ConfigReader` abstraction. These have been superseded by `Llm4sConfig` (PureConfig‑based) and typed helpers.

### Before (v0.1.x)
```scala
import org.llm4s.config.EnvLoader

val apiKey = EnvLoader.get("OPENAI_API_KEY")
val model  = EnvLoader.getOrElse("LLM_MODEL", "gpt-4")
```

or:

```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  ConfigReader.Provider().flatMap(LLMConnect.getClient)
```

### After (post‑0.2.0)

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
```

### Typed Config: recommended patterns

- Tracing (typed):
  ```scala
  import org.llm4s.config.Llm4sConfig
  import org.llm4s.trace.{ Tracing, EnhancedTracing, TracingMode }

  val tracerResult: org.llm4s.types.Result[Tracing] =
    Llm4sConfig.tracing().map(Tracing.create)
  ```

- Provider model for display (typed):
  ```scala
  val modelNameResult = Llm4sConfig.provider().map(_.model)
  // Prefer completion.model after the API call when available
  ```

- Workspace (samples):
  ```scala
  import org.llm4s.codegen.WorkspaceConfigSupport

  val ws = WorkspaceConfigSupport.load().getOrElse(
    throw new IllegalArgumentException("Failed to load workspace settings")
  )
  ```

- Embeddings (samples):
  ```scala
  val ui      = org.llm4s.samples.embeddingsupport.EmbeddingUiSettings.loadFromEnv()
    .getOrElse(throw new IllegalArgumentException("Failed to load UI settings"))
  val targets = org.llm4s.samples.embeddingsupport.EmbeddingTargets.loadFromEnv()
    .fold(err => throw new IllegalArgumentException(err.toString), _.targets)
  val query   = org.llm4s.samples.embeddingsupport.EmbeddingQuery.loadFromEnv()
    .fold(_ => None, _.value)
  ```

## Configuration: legacy reader → `Llm4sConfig` / typed helpers (post‑0.2.0)

Earlier versions used a custom `ConfigReader`-style abstraction as a catch‑all for configuration. With PureConfig in place and typed helpers available, the preferred path is now:

- Use `org.llm4s.config.Llm4sConfig` in core code.
- Use explicit typed loaders plus `LLMConnect.getClient` in application/sample code.

### Provider configuration and client creation

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.LLMConnect

val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  ConfigReader.Provider().flatMap(LLMConnect.getClient)
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

// Typed path using Llm4sConfig
val client: org.llm4s.types.Result[org.llm4s.llmconnect.LLMClient] =
  for {
    cfg    <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(cfg)
  } yield client
```

### Tracing configuration

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.trace.Tracing

val tracer: Tracing =
  ConfigReader.TracingConf().map(Tracing.create).getOrElse(Tracing.noop)
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.trace.Tracing

val tracer: org.llm4s.types.Result[Tracing] =
  Llm4sConfig.tracing().map(Tracing.create)
```

### Embeddings: provider and client

**Before (legacy reader-based API)**
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.EmbeddingClient

val client: org.llm4s.types.Result[EmbeddingClient] =
  ConfigReader.Embeddings().flatMap { case (provider, cfg) =>
    EmbeddingClient.from(provider, cfg)
  }
```

**After**
```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient

val client: org.llm4s.types.Result[EmbeddingClient] =
  Llm4sConfig.embeddings().flatMap { case (provider, cfg) =>
    EmbeddingClient.from(provider, cfg)
  }
```

### Workspace settings

**Before**
```scala
import org.llm4s.codegen.WorkspaceSettings

val ws = WorkspaceSettings.load().getOrElse(
  throw new IllegalArgumentException("Failed to load workspace settings")
)
```

**After**
```scala
import org.llm4s.codegen.WorkspaceConfigSupport

val ws = WorkspaceConfigSupport.load().getOrElse(
  throw new IllegalArgumentException("Failed to load workspace settings")
)
```

### API keys and types

**Before (legacy reader-based API)**
```scala
// Legacy pattern: API key resolved from a generic config reader
def loadApiKey(reader: /* legacy ConfigReader */ Any): Result[ApiKey] =
  ApiKey.unsafe("sk-legacy-key") // placeholder for old behavior
```

**After**
```scala
import org.llm4s.config.Llm4sConfig

val cfgResult = Llm4sConfig.provider() // Result[ProviderConfig]
```

- For **new code**, do not introduce new parameters of reader/ConfigReader types. Prefer:
  - `Llm4sConfig` in core libraries.
  - Typed helpers plus `LLMConnect.getClient` (and `Llm4sConfig.tracing().map(Tracing.create)` / `.map(EnhancedTracing.create)` for tracing) in applications and samples.
- For **existing code** that currently depends on a `ConfigReader`-style abstraction:
  - Start by swapping call sites to use typed helpers (e.g., `Llm4sConfig.provider()`).
  - Where you need fine-grained control, switch to `Llm4sConfig` functions instead of calling the legacy reader directly.
