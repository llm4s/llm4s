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
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage

object HelloLLM extends App {
  val result = for {
    client <- LLMConnect.create()
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

### 1. LLMConnect.create()

```scala
client <- LLMConnect.create()
```

This creates an LLM client automatically based on your `LLM_MODEL` environment variable:
- Reads configuration from env vars
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
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object ConversationExample extends App {
  val result = for {
    client <- LLMConnect.create()
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
    client <- LLMConnect.create()
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
import org.llm4s.llmconnect.model.UserMessage

object StreamingExample extends App {
  val result = for {
    client <- LLMConnect.create()
    stream <- client.completeStreaming(
      messages = List(UserMessage("Write a short poem about Scala")),
      model = None
    )
  } yield {
    print("Response: ")
    stream.foreach { chunk =>
      print(chunk.content)  // Print each token as it arrives
    }
    println()
  }

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
  client <- LLMConnect.create()
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
    client <- LLMConnect.create()
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
  client <- LLMConnect.create()
  response <- client.complete(
    List(UserMessage("Your question here")),
    None
  )
} yield response.content
```

### Pattern 2: With System Prompt

```scala
for {
  client <- LLMConnect.create()
  response <- client.complete(
    List(
      SystemMessage("You are an expert in..."),
      UserMessage("Question")
    ),
    None
  )
} yield response.content
```

### Pattern 3: Agent with Tools

```scala
for {
  client <- LLMConnect.create()
  tools = new ToolRegistry(myTools)
  agent = new Agent(client)
  state <- agent.run("User query", tools)
} yield state.finalResponse
```

### Pattern 4: Streaming

```scala
for {
  client <- LLMConnect.create()
  stream <- client.completeStreaming(messages, None)
} yield stream.foreach(chunk => print(chunk.content))
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
