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

## Configuration Changes (v0.2.0)

### ConfigReader replaces EnvLoader
The `EnvLoader` has been replaced with a more flexible `ConfigReader` system.

### Before (v0.1.x)
```scala
import org.llm4s.config.EnvLoader

val apiKey = EnvLoader.get("OPENAI_API_KEY")
val model = EnvLoader.getOrElse("LLM_MODEL", "gpt-4")
```

### After (v0.2.0)
```scala
import org.llm4s.config.ConfigReader
import org.llm4s.config.ConfigReader.LLMConfig

// Use the default config (reads from environment)
val config = LLMConfig()
val apiKey = config.get("OPENAI_API_KEY")
val model = config.getOrElse("LLM_MODEL", "gpt-4")

// Or create a custom config
val customConfig = ConfigReader.from(Map(
  "OPENAI_API_KEY" -> "sk-...",
  "LLM_MODEL" -> "gpt-4o"
))
```

### Migration Steps

1. **Replace EnvLoader imports**: Update to use ConfigReader
   ```scala
   // Before
   import org.llm4s.config.EnvLoader
   
   // After
   import org.llm4s.config.ConfigReader
   import org.llm4s.config.ConfigReader.LLMConfig
   ```

2. **Update configuration access**: Use ConfigReader methods
   ```scala
   // Before
   EnvLoader.get("KEY")
   
   // After
   LLMConfig().get("KEY")
   ```

3. **Pass config to constructors**: Many classes now take an implicit ConfigReader
   ```scala
   // Before
   val client = LLM.client()
   
   // After
   val client = LLM.client(LLMConfig())
   // or with implicit
   implicit val config: ConfigReader = LLMConfig()
   val client = LLM.client
   ```