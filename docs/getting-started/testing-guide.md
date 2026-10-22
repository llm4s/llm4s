---
layout: page
title: Testing Guide
parent: Getting Started
nav_order: 7
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

## Unit Testing with a Scripted Client

For pure logic tests, give your code a scripted client instead of a real one. `llm4s-testkit` provides
`ScriptedLLMClient`: it answers from a script you write, records every request your code sent, and never touches the
network. Add it as a test dependency:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-testkit" % llm4sVersion % Test
```

[Testing with the Testkit](../guide/testing-with-the-testkit) covers the whole API; the examples below show the common
cases.

### Canned replies

```scala
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WeatherAgentSpec extends AnyFlatSpec with Matchers {

  // Example code under test: it takes any LLMClient
  class WeatherAgent(client: LLMClient) {
    def run(query: String): Result[Completion] =
      client.complete(Conversation(Seq(UserMessage(query))))
  }

  "WeatherAgent" should "return the model's answer" in {
    val client = ScriptedLLMClient.always(
      Reply
        .text("The weather in London is 15°C and cloudy.")
        .withUsage(TokenUsage(promptTokens = 10, completionTokens = 15, totalTokens = 25))
    )

    val result = new WeatherAgent(client).run("What's the weather in London?")

    result.map(_.content) shouldBe Right("The weather in London is 15°C and cloudy.")
    client.callCount shouldBe 1
    client.lastCall.flatMap(_.lastUserText) shouldBe Some("What's the weather in London?")
  }

  it should "pass a provider error on" in {
    val client = ScriptedLLMClient.always(
      Reply.failure(NetworkError("Connection timeout", None, "https://api.example.com"))
    )

    new WeatherAgent(client).run("What's the weather?") match {
      case Left(_: NetworkError) => succeed
      case other                 => fail(s"Expected NetworkError but got: $other")
    }
  }
}
```

`streamComplete` replays the same reply as chunks (the text in pieces, then any tool calls, then a chunk with the
finish reason), so code that streams is tested with the same script.

### A reply chosen by the request

`ScriptedLLMClient.respondingTo` picks the reply from the conversation, so the answer does not depend on how many calls
came before. A request no rule matches fails with an `UnscriptedCallError` that shows the last message, instead of
hanging or throwing:

```scala
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }

def lastUser(conversation: Conversation): String =
  conversation.messages.collect { case u: UserMessage => u.content }.lastOption.getOrElse("")

val client = ScriptedLLMClient.respondingTo {
  case c if lastUser(c) == "What is 2+2?"                    => Reply.text("2+2 equals 4")
  case c if lastUser(c) == "What is the capital of France?" => Reply.text("The capital of France is Paris")
}

val answer     = client.complete(Conversation(Seq(UserMessage("What is 2+2?"))))      // Right, "2+2 equals 4"
val unexpected = client.complete(Conversation(Seq(UserMessage("Something else"))))    // Left(UnscriptedCallError)
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

Put a named provider section in your test resources and select it as the default, with
`llm4s-ollama` as a test dependency. See [Named provider sections](configuration#named-provider-sections).

```hocon
# src/test/resources/application.conf
llm4s {
  providers {
    provider = "ollama-test"

    ollama-test {
      provider = "ollama"
      model    = "llama3.2"
      baseUrl  = "http://localhost:11434"   # optional: this is the default
    }
  }
}
```

### Integration Test Example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
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
      config <- Llm4sConfig.defaultProvider()
      registry <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
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
      config <- Llm4sConfig.defaultProvider()
      registry <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client <- LLMConnect.getClient(config)
      completion <- client.streamComplete(
        Conversation(Seq(UserMessage("Count: 1, 2, 3"))),
        onChunk = chunk => chunks = chunks :+ chunk
      )
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

Always test error paths. `Reply.failure` makes the client return the real `LLMError` types, so your code sees exactly
what a provider would give it:

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.GraphError
import org.llm4s.error.{ AuthenticationError, NetworkError, RateLimitError }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ErrorHandlingSpec extends AnyFlatSpec with Matchers {

  private def runWith(reply: Reply) =
    Agent.builder("test-agent", ScriptedLLMClient.always(reply)).build().flatMap(_.run("test query"))

  "Agent" should "handle rate limiting" in {
    // A provider error ends the run as GraphError.NodeFailed, carrying the client's error as its cause
    runWith(Reply.failure(RateLimitError("Rate limit exceeded"))) match {
      case Left(GraphError.NodeFailed(_, _, _: RateLimitError)) => succeed
      case other                                               => fail(s"Expected RateLimitError but got: $other")
    }
  }

  it should "handle authentication errors" in {
    runWith(Reply.failure(AuthenticationError("openai", "Invalid API key", "401"))) match {
      case Left(GraphError.NodeFailed(_, _, _: AuthenticationError)) => succeed
      case other => fail(s"Expected AuthenticationError but got: $other")
    }
  }

  it should "handle network timeouts" in {
    // No need to sleep: the error a timed-out call returns is what the code under test sees
    runWith(Reply.failure(NetworkError("Request timeout", None, "https://api.example.com"))) match {
      case Left(GraphError.NodeFailed(_, _, _: NetworkError)) => succeed
      case other                                             => fail(s"Expected NetworkError but got: $other")
    }
  }
}
```

---

## Testing Tool Calling

Script the model's tool call and then its answer with `ScriptedLLMClient.sequence`: the first call gets the first
reply, the second call the second. The agent runs your real tool in between, and the recorded calls show what it sent
back to the model:

```scala
import org.llm4s.agent.Agent
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolCallingSpec extends AnyFlatSpec with Matchers {

  "Agent" should "invoke the weather tool with the model's arguments" in {
    var capturedCity: Option[String] = None

    // A tool that records the arguments it was called with
    val weatherTool = ToolBuilder[Map[String, Any], String](
      "get_weather",
      "Get weather for a city",
      Schema
        .`object`[Map[String, Any]]("Weather query parameters")
        .withProperty(Schema.property("city", Schema.string("City name")))
    ).withHandler { extractor =>
      extractor.getString("city").map { city =>
        capturedCity = Some(city)
        s"Weather in $city: 20°C"
      }
    }.buildSafe()

    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_weather", """{"city": "London"}"""),
      Reply.text("It is 20°C in London.")
    )

    val outcome = for {
      tool  <- weatherTool
      agent <- Agent.builder("test-agent", client).withTools(new ToolRegistry(Seq(tool))).build()
      _     <- agent.run("What's the weather in London?")
    } yield ()

    outcome.isRight shouldBe true
    capturedCity shouldBe Some("London")
    client.callCount shouldBe 2
    // the second request carries the tool's result back to the model
    client.calls(1).messages.collect { case t: ToolMessage => t.content } shouldBe Seq("Weather in London: 20°C")
  }
}
```

---

## Testing RAG Applications

Test document retrieval and answer generation separately:

```scala
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RAGSpec extends AnyFlatSpec with Matchers {

  "VectorStore" should "retrieve relevant documents" in {
    val documents = List(
      "Scala is a functional programming language",
      "Python is a dynamically typed language",
      "Java runs on the JVM"
    )

    // Note: This is conceptual pseudocode showing testing patterns. `InMemoryVectorStore`, `embedder` and
    // `RAGPipeline` below are illustrative stand-ins, not llm4s classes with these signatures. The llm4s-rag
    // module has real stores (SQLiteVectorStore, PgVectorStore, QdrantVectorStore).
    val vectorStore = new InMemoryVectorStore()  // Pseudocode - use your vector store
    documents.foreach(doc => vectorStore.add(doc, embedder.embed(doc)))  // embedder is conceptual

    val results = vectorStore.search("functional programming", topK = 1)
    
    results.head should include("Scala")
  }

  "RAG pipeline" should "include context in LLM prompt" in {
    // a scripted client from llm4s-testkit: it records the prompt the pipeline sent
    val client = ScriptedLLMClient.always(Reply.text("Based on the context, Scala is functional."))
    val rag    = new RAGPipeline(client, vectorStore, embedder)
    rag.query("What is Scala?")

    client.lastCall.flatMap(_.lastUserText).get should include("context")
    client.lastCall.flatMap(_.lastUserText).get should include("functional")
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
        # The provider comes from src/test/resources/application.conf (the ollama-test section
        # above); no environment variable selects it.
        run: sbt "testOnly *IntegrationSpec"
      
      # Skip expensive tests in CI
      - name: Run full test suite
        run: sbt test
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
```

### Test Categorization

```scala
// Tag tests by speed/cost
import org.scalatest.Tag
import org.scalatest.flatspec.AnyFlatSpec

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
