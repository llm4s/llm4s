---
layout: page
title: First Example
parent: Getting Started
nav_order: 2
---

# Your First LLM4S Program
{: .no_toc }

Build your first LLM-powered application in minutes.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Hello, LLM!

Let's start with the simplest possible LLM4S program - a "Hello World" that asks the LLM a question.

### Create the File

Create `HelloLLM.scala`:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage

object HelloLLM extends App {
  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    response <- client.complete(
      messages = List(UserMessage("What is Scala?")),
      model = None
    )
  } yield response

  result match {
    case Right(completion) =>
      println(s"Response: ${completion.content}")
    case Left(error) =>
      println(s"Error: $error")
  }
}
```

### Run It

```bash
# Make sure your API key is configured
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-...

sbt run
```

### Expected Output

```
Response: Scala is a high-level programming language that combines
object-oriented and functional programming paradigms. It runs on the
JVM and is known for its strong type system and concurrency support.
```

---

## Understanding the Code

Let's break down what's happening:

### 1. Load Config and Build a Client

```scala
providerConfig <- Llm4sConfig.provider()
client <- LLMConnect.getClient(providerConfig)
```

This builds an LLM client based on your configured provider:
- Loads typed config from env vars / application.conf
- Selects the appropriate provider (OpenAI, Anthropic, etc.)
- Returns a `Result[LLMClient]` (Either[LLMError, LLMClient])

### 2. Complete with Messages

```scala
response <- client.complete(
  messages = List(UserMessage("What is Scala?")),
  model = None
)
```

- **messages**: A list of conversation messages (User, Assistant, System)
- **model**: Optional model override (None uses configured model)
- Returns `Result[CompletionResponse]`

### 3. Result Handling

```scala
result match {
  case Right(completion) => // Success
  case Left(error) => // Error
}
```

LLM4S uses **Result types** instead of exceptions:
- `Right(value)` = success
- `Left(error)` = failure

This makes error handling **explicit** and **type-safe**.

---

## Adding Conversation Context

Let's make our program more interesting with a multi-turn conversation:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object ConversationExample extends App {
  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    response <- client.complete(
      messages = List(
        SystemMessage("You are a helpful programming tutor."),
        UserMessage("What is Scala?"),
        AssistantMessage("Scala is a high-level programming language..."),
        UserMessage("How does it compare to Java?")
      ),
      model = None
    )
  } yield response

  result.fold(
    error => println(s"Error: $error"),
    completion => println(s"Response: ${completion.content}")
  )
}
```

### Message Types

LLM4S provides three message types:

| Type | Purpose | Example |
|------|---------|---------|
| `SystemMessage` | Set LLM behavior | "You are a helpful assistant" |
| `UserMessage` | User's input | "What is Scala?" |
| `AssistantMessage` | LLM's previous responses | "Scala is a programming language..." |

---

## Adding Tool Calling

Now let's give the LLM access to a tool:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }
import org.llm4s.agent.Agent

object ToolExample extends App {
  // Define a simple tool
  def getWeather(location: String): String = {
    s"The weather in $location is sunny and 72°F"
  }

  val weatherTool = ToolFunction(
    name = "get_weather",
    description = "Get current weather for a location",
    function = getWeather _
  )

  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    tools = new ToolRegistry(Seq(weatherTool))
    agent = new Agent(client)
    state <- agent.run("What's the weather in Paris?", tools)
  } yield state

  result.fold(
    error => println(s"Error: $error"),
    state => println(s"Final response: ${state.finalResponse}")
  )
}
```

The agent will:
1. Understand you're asking about weather
2. Call the `get_weather` tool with "Paris"
3. Use the tool result to formulate a response

---

## Streaming Responses

For real-time output, use streaming:

```scala
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.config.Llm4sConfig

object StreamingExample extends App {
  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    completion <- client.streamComplete(
      conversation = Conversation(Seq(UserMessage("Write a short poem about Scala")))
    ) { chunk =>
      chunk.content.foreach(print)  // Print each token as it arrives
    }
  } yield completion

  result.fold(
    error => println(s"Error: $error"),
    _ => println("\nDone!")
  )
}
```

Output appears token-by-token in real-time, like ChatGPT!

---

## Error Handling Patterns

### Basic Error Handling

```scala
val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  response <- client.complete(messages, None)
} yield response

result match {
  case Right(completion) =>
    // Success
  case Left(error) =>
    // Handle different error types
    error match {
      case NetworkError(msg) => println(s"Network issue: $msg")
      case AuthenticationError(msg) => println(s"Auth failed: $msg")
      case ValidationError(msg) => println(s"Invalid input: $msg")
      case _ => println(s"Error: $error")
    }
}
```

### Using fold

```scala
result.fold(
  error => println(s"Failed: $error"),
  completion => println(s"Success: ${completion.content}")
)
```

### Mapping Results

```scala
val content: Result[String] = result.map(_.content)
val uppercased: Result[String] = content.map(_.toUpperCase)
```

---

## Configuration Options

### Using Different Models

```scala
import org.llm4s.types.ModelName

val response = client.complete(
  messages = List(UserMessage("Hello")),
  model = Some(ModelName("gpt-4-turbo"))  // Override configured model
)
```

### Provider-Specific Settings

```scala
// In application.conf or environment variables
llm {
  model = "openai/gpt-4o"
  temperature = 0.7
  max-tokens = 1000
}
```

---

## Complete Working Example

Here's a complete example combining everything we've learned:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }
import org.llm4s.agent.Agent

object ComprehensiveExample extends App {
  // Define tools
  def calculate(expression: String): String = {
    // Simple calculator (use proper eval in production!)
    s"Result: ${expression} = 42"
  }

  val calcTool = ToolFunction(
    name = "calculate",
    description = "Evaluate a mathematical expression",
    function = calculate _
  )

  // Main program
  println("🚀 Starting LLM4S Example...")

  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    tools = new ToolRegistry(Seq(calcTool))
    agent = new Agent(client)

    // Run agent with tool support
    state <- agent.run(
      "What is 6 times 7? Please use the calculator.",
      tools
    )
  } yield state

  result match {
    case Right(state) =>
      println(s"✅ Success!")
      println(s"Response: ${state.finalResponse}")
      println(s"Messages exchanged: ${state.messages.length}")

    case Left(error) =>
      println(s"❌ Error: $error")
      System.exit(1)
  }
}
```

---

## Common Patterns

### Pattern 1: Simple Query

```scala
for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  response <- client.complete(
    Conversation(Seq(UserMessage("Your question here")))
  )
} yield response.content
```

### Pattern 2: With System Prompt

```scala
for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  response <- client.complete(
    Conversation(Seq(
      SystemMessage("You are an expert in..."),
      UserMessage("Question")
    ))
  )
} yield response.content
```

### Pattern 3: Agent with Tools

```scala
for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  tools = new ToolRegistry(myTools)
  agent = new Agent(client)
  state <- agent.run("User query", tools)
} yield state.finalResponse
```

### Pattern 4: Streaming

```scala
for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  completion <- client.streamComplete(
    Conversation(Seq(UserMessage("Your question here")))
  ) { chunk =>
    chunk.content.foreach(print)
  }
} yield completion
```

---

## Performance Optimization

### 1. Reuse Client Instances

Don't create a new client for every request:

```scala
// ✅ Good: Create once, reuse
val clientResult = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
} yield client

clientResult match {
  case Right(client) =>
    // Reuse for multiple requests
    (1 to 10).foreach { i =>
      client.complete(Conversation(Seq(UserMessage(s"Question $i"))))
    }
  case Left(error) => println(s"Error: $error")
}

// ❌ Bad: Creating new client each time (wasteful)
(1 to 10).foreach { i =>
  for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)  // Don't do this!
    response <- client.complete(
      Conversation(Seq(UserMessage(s"Q$i")))
    )
  } yield response
}
```

### 2. Use Streaming for Long Responses

Streaming gets you the first token faster and improves perceived latency:

```scala
import org.llm4s.llmconnect.model._

val streamResult = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  completion <- client.streamComplete(
    Conversation(Seq(UserMessage("Write a long essay about Scala")))
  ) { chunk =>
    chunk.content.foreach(print)  // Prints as tokens arrive
  }
} yield completion

streamResult match {
  case Right(completion) => println(s"\nCompleted with ${completion.usage.map(_.totalTokens).getOrElse(0)} tokens")
  case Left(error) => println(s"Error: $error")
}
```

**When to stream:**
- User-facing applications (chat interfaces)
- Long-form content generation
- When you need to show progress

**When not to stream:**
- Short queries (< 100 tokens)
- When you need the full response before processing
- Background batch jobs

### 3. Parallelize Independent Requests

Process multiple unrelated queries concurrently:

```scala
import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

val clientResult = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
} yield client

clientResult match {
  case Right(client) =>
    val queries = List(
      "What is Scala?",
      "What is functional programming?",
      "What is the JVM?"
    )

    // Run all queries in parallel
    val futures = queries.map { query =>
      Future {
        client.complete(Conversation(Seq(UserMessage(query))))
      }
    }

    val results = Await.result(Future.sequence(futures), 30.seconds)
    results.foreach {
      case Right(response) => println(response.content)
      case Left(error) => println(s"Error: $error")
    }

  case Left(error) => println(s"Error: $error")
}
```

### 4. Set Appropriate Timeouts

Different operations need different timeouts:

```hocon
# In application.conf
llm4s {
  # Short timeout for quick queries
  request-timeout = 15 seconds

  # For long-form generation
  # request-timeout = 60 seconds
}
```

Or override per request:

```scala
// Note: Per-request timeouts are configured via application.conf or provider settings.
// The complete method uses the configured timeout automatically.
val response = client.complete(
  Conversation(Seq(UserMessage("Quick question")))
)
```

### 5. Use Cheaper Models for Development

```bash
# Development: Fast and cheap
export LLM_MODEL=openai/gpt-4o-mini  # 60x cheaper than gpt-4

# Or free with Ollama
export LLM_MODEL=ollama/llama3.2

# Production: Use when quality matters
export LLM_MODEL=openai/gpt-4o
```

### 6. Batch Embeddings

When generating embeddings for RAG:

```scala
// ✅ Good: Batch processing
val documents = List("doc1", "doc2", ... "doc1000")
val batchSize = 100

val allEmbeddings = documents.grouped(batchSize).flatMap { batch =>
  embedder.embed(batch) match {
    case Right(embeddings) => embeddings
    case Left(error) =>
      println(s"Batch failed: $error")
      List.empty
  }
}.toList

// ❌ Bad: One at a time (slow, expensive)
val embeddings = documents.map { doc =>
  embedder.embed(List(doc))
}
```

### 7. Monitor Token Usage

Track costs in production:

```scala
val response = client.complete(Conversation(messages))

response match {
  case Right(completion) =>
    // Note: usage returns Option[TokenUsage], so these are Option[Int]
    println(s"Prompt tokens: ${completion.usage.map(_.promptTokens)}")
    println(s"Completion tokens: ${completion.usage.map(_.completionTokens)}")
    println(s"Total tokens: ${completion.usage.map(_.totalTokens)}")
  case Left(error) => println(s"Error: $error")
}
```

---

## Next Steps

Great job! You've written your first LLM4S programs. Now explore:

1. **[Configuration →](configuration)** - Learn about advanced configuration
2. **[Agent Framework →](/examples/#agent-examples)** - Build sophisticated agents
3. **[Tool Calling →](/examples/#tool-examples)** - Create custom tools
4. **[Examples →](/examples/)** - Browse 69 working examples

---

## Learning Resources

- **[Basic Examples](/examples/basic)** - More beginner-friendly examples
- **[User Guide](/guide/)** - Comprehensive feature guide
- **[API Reference](/api/)** - Detailed API documentation
- **[Discord Community](https://discord.gg/4uvTPn6qww)** - Get help and share ideas

---

**Ready to dive deeper?** [Configure your environment →](configuration)
