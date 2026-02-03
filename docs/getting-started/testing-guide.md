---
layout: page
title: Testing Guide
parent: Getting Started
nav_order: 5
---

# Testing LLM4S Applications
{: .no_toc }

Learn how to test LLM-powered applications effectively without spending money on API calls.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Testing Strategy

Testing LLM applications requires a different approach than traditional software. You need to balance:

1. **Speed** - Tests should run fast
2. **Cost** - Avoid expensive API calls in CI/CD
3. **Determinism** - LLM responses vary, so test behaviors not exact outputs
4. **Coverage** - Test error paths, timeouts, and edge cases

---

## Unit Testing with Mock Clients

For pure logic tests, mock the LLM client:

### Basic Mock

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.llm4s.error.NetworkError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WeatherAgentSpec extends AnyFlatSpec with Matchers {

  // Mock client that returns canned responses
  class MockLLMClient extends LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): Result[Completion] = {
      Right(Completion(
        id = "mock-1",
        created = System.currentTimeMillis(),
        content = "The weather in London is 15°C and cloudy.",
        model = "mock-model",
        message = AssistantMessage("The weather in London is 15°C and cloudy."),
        usage = Some(TokenUsage(promptTokens = 10, completionTokens = 15, totalTokens = 25))
      ))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions(),
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      val chunks = List(
        StreamedChunk(content = Some("The weather"), finishReason = None),
        StreamedChunk(content = Some(" is sunny"), finishReason = Some("stop"))
      )
      chunks.foreach(onChunk)
      Right(Completion(
        id = "mock-1",
        created = System.currentTimeMillis(),
        content = "The weather is sunny",
        model = "mock-model",
        message = AssistantMessage("The weather is sunny")
      ))
    }
  }

  // Example agent that uses the LLM client
  class WeatherAgent(client: LLMClient) {
    def run(query: String): Result[Completion] = {
      client.complete(Conversation(Seq(UserMessage(query))))
    }
  }

  "WeatherAgent" should "extract city from user query" in {
    val agent = new WeatherAgent(new MockLLMClient)
    val result = agent.run("What's the weather in London?")
    
    result match {
      case Right(response) =>
        response.content should include("London")
        response.content should include("°C")
      case Left(error) =>
        fail(s"Expected success but got: $error")
    }
  }

  it should "handle errors gracefully" in {
    class FailingMockClient extends LLMClient {
      override def complete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions()
      ): Result[Completion] = {
        Left(NetworkError("Connection timeout"))
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions(),
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        Left(NetworkError("Connection timeout"))
      }
    }

    val agent = new WeatherAgent(new FailingMockClient)
    val result = agent.run("What's the weather?")
    
    result match {
      case Left(_: NetworkError) => succeed
      case other => fail(s"Expected NetworkError but got: $other")
    }
  }
}
```

### Parameterized Mock

For more complex scenarios:

```scala
import org.llm4s.error.InvalidRequestError

class ConfigurableMockClient(responses: Map[String, String]) extends LLMClient {
  override def complete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  ): Result[Completion] = {
    val userMessage = conversation.messages
      .collectFirst { case UserMessage(content) => content }
      .getOrElse("")
    
    responses.get(userMessage) match {
      case Some(responseText) =>
        Right(Completion(
          id = "mock-1",
          created = System.currentTimeMillis(),
          content = responseText,
          model = "mock-model",
          message = AssistantMessage(responseText)
        ))
      case None =>
        Left(InvalidRequestError(s"No mock response for: $userMessage"))
    }
  }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] = {
    complete(conversation, options).map { completion =>
      onChunk(StreamedChunk(content = Some(completion.content), finishReason = Some("stop")))
      completion
    }
  }
}

// Usage in tests
val mockResponses = Map(
  "What is 2+2?" -> "2+2 equals 4",
  "What is the capital of France?" -> "The capital of France is Paris"
)

val client = new ConfigurableMockClient(mockResponses)
```

---

## Integration Testing with Ollama

For integration tests, use Ollama to avoid API costs:

### Setup

```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull a small, fast model
ollama pull llama3.2

# Start server
ollama serve
```

### Test Configuration

```hocon
# src/test/resources/application.conf
llm4s {
  provider {
    model = "ollama/llama3.2"
    ollama {
      base-url = "http://localhost:11434"
    }
  }
  request-timeout = 30 seconds
}
```

### Integration Test Example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMIntegrationSpec extends AnyFlatSpec with Matchers {

  // Only run if Ollama is available
  def ollamaAvailable: Boolean = {
    try {
      val url = new java.net.URL("http://localhost:11434")
      val connection = url.openConnection()
      connection.setConnectTimeout(1000)
      connection.connect()
      true
    } catch {
      case _: Exception => false
    }
  }

  "LLMClient" should "complete basic requests" in {
    assume(ollamaAvailable, "Ollama server not available")

    val result = for {
      config <- Llm4sConfig.provider()
      client <- LLMConnect.getClient(config)
      response <- client.complete(
        Conversation(Seq(UserMessage("Say 'hello' and nothing else")))
      )
    } yield response

    result match {
      case Right(response) =>
        response.content.toLowerCase should include("hello")
      case Left(error) =>
        fail(s"Request failed: $error")
    }
  }

  it should "handle streaming responses" in {
    assume(ollamaAvailable, "Ollama server not available")

    var chunks = List.empty[StreamedChunk]
    val result = for {
      config <- Llm4sConfig.provider()
      client <- LLMConnect.getClient(config)
      completion <- client.streamComplete(
        Conversation(Seq(UserMessage("Count: 1, 2, 3")))
      ) { chunk =>
        chunks = chunks :+ chunk
      }
    } yield completion

    result match {
      case Right(completion) =>
        chunks should not be empty
        chunks.last.finishReason should be(Some("stop"))
      case Left(error) =>
        fail(s"Streaming failed: $error")
    }
  }
}
```

---

## Testing Error Handling

Always test error paths:

```scala
import org.llm4s.error.{RateLimitError, AuthenticationError, NetworkError}

class ErrorHandlingSpec extends AnyFlatSpec with Matchers {

  "Agent" should "handle rate limiting" in {
    class RateLimitedClient extends LLMClient {
      override def complete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions()
      ): Result[Completion] = {
        Left(RateLimitError("Rate limit exceeded"))
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions(),
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        Left(RateLimitError("Rate limit exceeded"))
      }
    }

    val agent = new Agent(new RateLimitedClient)
    val result = agent.run("test query", tools = ToolRegistry.empty)

    result match {
      case Left(_: RateLimitError) => succeed
      case other => fail(s"Expected RateLimitError but got: $other")
    }
  }

  it should "handle authentication errors" in {
    class UnauthorizedClient extends LLMClient {
      override def complete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions()
      ): Result[Completion] = {
        Left(AuthenticationError("Invalid API key"))
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions(),
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        Left(AuthenticationError("Invalid API key"))
      }
    }

    val agent = new Agent(new UnauthorizedClient)
    val result = agent.run("test", tools = ToolRegistry.empty)

    result.isLeft shouldBe true
  }

  it should "handle network timeouts" in {
    class TimeoutClient extends LLMClient {
      override def complete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions()
      ): Result[Completion] = {
        Thread.sleep(5000)  // Simulate timeout
        Left(NetworkError("Request timeout"))
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions(),
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        Left(NetworkError("Request timeout"))
      }
    }

    val agent = new Agent(new TimeoutClient)
    val result = agent.run("test", tools = ToolRegistry.empty)

    result match {
      case Left(_: NetworkError) => succeed
      case other => fail(s"Expected NetworkError but got: $other")
    }
  }
}
```

---

## Testing Tool Calling

Test that tools are invoked correctly.

> **Note**: This example uses simplified Tool API for clarity. In production, use `ToolBuilder` and `ToolFunction` from the `org.llm4s.toolapi` package. See the [Tools documentation](../agents/tools.md) for actual API.

```scala
class ToolCallingSpec extends AnyFlatSpec with Matchers {

  "Agent" should "invoke weather tool" in {
    var toolWasCalled = false
    var capturedCity: Option[String] = None

    // Simplified tool example for testing concepts
    val weatherTool = new Tool {
      override def name: String = "get_weather"
      override def description: String = "Get weather for a city"
      override def parameters: ToolParameters = ToolParameters(
        properties = Map("city" -> Property("string", "City name"))
      )
      override def execute(args: Map[String, Any]): Result[String] = {
        toolWasCalled = true
        capturedCity = args.get("city").map(_.toString)
        Right(s"Weather in ${capturedCity.getOrElse("unknown")}: 20°C")
      }
    }

    // Mock client that calls the tool
    class ToolCallingMock extends LLMClient {
      override def complete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions()
      ): Result[Completion] = {
        Right(Completion(
          id = "mock-1",
          created = System.currentTimeMillis(),
          content = "",
          model = "mock-model",
          message = AssistantMessage(""),
          toolCalls = List(
            ToolCall(
              id = "call_1",
              name = "get_weather",
              arguments = Map("city" -> "London")
            )
          )
        ))
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions(),
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        complete(conversation, options)
      }
    }

    val tools = new ToolRegistry(List(weatherTool))
    val agent = new Agent(new ToolCallingMock)
    
    agent.run("What's the weather in London?", tools)

    toolWasCalled shouldBe true
    capturedCity shouldBe Some("London")
  }
}
```

---

## Testing RAG Applications

Test document retrieval and answer generation separately:

```scala
class RAGSpec extends AnyFlatSpec with Matchers {

  "VectorStore" should "retrieve relevant documents" in {
    val documents = List(
      "Scala is a functional programming language",
      "Python is a dynamically typed language",
      "Java runs on the JVM"
    )

    // Note: This is conceptual pseudocode showing testing patterns.
    // LLM4S does not currently include vector store implementations.
    // Use your preferred vector store library (e.g., Pinecone, Milvus, ChromaDB).
    val vectorStore = new InMemoryVectorStore()  // Pseudocode - use your vector store
    documents.foreach(doc => vectorStore.add(doc, embedder.embed(doc)))  // embedder is conceptual

    val results = vectorStore.search("functional programming", topK = 1)
    
    results.head should include("Scala")
  }

  "RAG pipeline" should "include context in LLM prompt" in {
    class RAGMockClient extends LLMClient {
      var lastPrompt: Option[String] = None

      override def complete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions()
      ): Result[Completion] = {
        lastPrompt = conversation.messages
          .collectFirst { case UserMessage(content) => content }
        Right(Completion(
          id = "mock-1",
          created = System.currentTimeMillis(),
          content = "Based on the context, Scala is functional.",
          model = "mock-model",
          message = AssistantMessage("Based on the context, Scala is functional.")
        ))
      }

      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions = CompletionOptions(),
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = {
        complete(conversation, options)
      }
    }

    val mockClient = new RAGMockClient
    val rag = new RAGPipeline(mockClient, vectorStore, embedder)
    rag.query("What is Scala?")

    mockClient.lastPrompt.get should include("context")
    mockClient.lastPrompt.get should include("functional")
  }
}
```

---

## CI/CD Testing Strategy

### Fast CI Pipeline

```yaml
# .github/workflows/test.yml
name: Test

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Setup Scala
        uses: olafurpg/setup-scala@v14
        with:
          java-version: 21
      
      - name: Install Ollama
        run: |
          curl -fsSL https://ollama.com/install.sh | sh
          ollama serve &
          sleep 5
          ollama pull llama3.2
      
      - name: Run unit tests (fast)
        run: sbt "testOnly *UnitSpec"
      
      - name: Run integration tests (with Ollama)
        run: sbt "testOnly *IntegrationSpec"
        env:
          LLM_MODEL: ollama/llama3.2
      
      # Skip expensive tests in CI
      - name: Run full test suite
        run: sbt test
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
```

### Test Categorization

```scala
// Tag tests by speed/cost
import org.scalatest.Tag

object UnitTest extends Tag("UnitTest")
object IntegrationTest extends Tag("IntegrationTest")
object ExpensiveTest extends Tag("ExpensiveTest")

class FastSpec extends AnyFlatSpec {
  "Fast unit test" should "run in CI" taggedAs UnitTest in {
    // Mock-based test
  }
}

class SlowSpec extends AnyFlatSpec {
  "Expensive test" should "run manually" taggedAs ExpensiveTest in {
    // Uses real OpenAI API
  }
}
```

Run specific test categories:

```bash
# Fast tests only
sbt "testOnly * -- -n UnitTest"

# Everything except expensive tests
sbt "testOnly * -- -l ExpensiveTest"
```

---

## Best Practices

1. ✅ **Mock by default**: Use mock clients for unit tests
2. ✅ **Ollama for integration**: Free and fast enough for CI
3. ✅ **Test behaviors, not outputs**: LLM responses vary, so test that tools are called, documents are retrieved, etc.
4. ✅ **Use deterministic models when possible**: Set temperature=0 for more predictable outputs
5. ✅ **Separate concerns**: Test tool logic independently from LLM integration
6. ✅ **Tag expensive tests**: Don't run them in every CI build
7. ✅ **Use smaller models in CI**: llama3.2 is fast and free via Ollama

---

## Example Test Suite Structure

```
src/test/scala/
├── unit/
│   ├── ToolSpec.scala           # Pure logic tests (mocked)
│   ├── ConfigSpec.scala         # Configuration parsing
│   └── ErrorHandlingSpec.scala  # Error path tests
├── integration/
│   ├── LLMClientSpec.scala      # Real LLM calls (Ollama)
│   ├── AgentSpec.scala          # End-to-end agent tests
│   └── RAGSpec.scala            # RAG pipeline tests
└── resources/
    └── application.conf         # Test config (Ollama)
```

---

## Next Steps

- [Configuration Guide](configuration) - Configure test environments
- [Examples](/examples/) - See tests in action
- [CI/CD Best Practices](/advanced/ci-cd) - Production testing strategies

---

## Additional Resources

- [ScalaTest Documentation](https://www.scalatest.org/)
- [Ollama Models](https://ollama.com/library) - Available test models
- [Testing Guide](/reference/testing-guide) - Advanced testing patterns
