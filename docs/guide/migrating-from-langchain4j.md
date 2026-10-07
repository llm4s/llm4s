---
layout: page
title: Coming from LangChain4j or Spring AI
parent: User Guide
nav_order: 21
---

# Coming from LangChain4j or Spring AI
{: .no_toc }

If you know LangChain4j or Spring AI, this page maps the concepts you already use to LLM4S, with working code for the
five things most programs do: call a model, get typed output, give the model a tool, keep memory, and retrieve from
documents. For whether LLM4S is the right choice, read the [comparison](comparison) first, including
[what it has not got yet](comparison#what-llm4s-has-not-got-yet).

Every LLM4S snippet below is compiled by a test in this repository (`ComparisonGuideSpec`), so the page cannot drift
from the API. All but two are also run, against a scripted client, with no network. The two that are only compiled
(the configuration one and the RAG one) say so. The LangChain4j and Spring AI sides show concept names taken from their
own documentation, linked at the end, and no invented API.

1. TOC
{:toc}

## The concepts side by side

| You use | In LLM4S | Note |
|---|---|---|
| An AI Service, a Java interface that the library implements [L1] | `LLMClient`, or an `Agent` when tools are involved | There is no declarative interface-to-implementation layer: you call the client, or build an agent. See [the comparison](comparison) |
| A chat model | `LLMClient`, from `LLMConnect.getClient(providerConfig)` | The provider is chosen by configuration, in a named section of `application.conf` ([providers](providers)) |
| Structured output by return type [L1][L7] | `client.completeStructured[A](conversation, schema)` | The schema is written by hand, not derived from the type |
| Tools [L1] | `ToolBuilder` and a `ToolRegistry`, run by an `Agent` | Parameter schemas are written by hand |
| Chat memory [L1] | A `Conversation` you pass on each call, and `llm4s-memory` for long-term facts | See the memory section: they are different mechanisms |
| `ContentRetriever` and RAG [L1] | `RAG` in `llm4s-rag` | Needs an embedding provider |
| Spring AI `ChatClient` [S6] | `LLMClient` | Spring's fluent builder versus a plain call |
| Spring AI `entity()` [S6] | `completeStructured` | As above |

The largest difference is not a class: **LLM4S returns `Result[A]` instead of throwing**. Every call in this page
returns an `Either`-shaped value, and the snippets chain them with a `for` comprehension. See
[error handling](error-handling) for the error types.

## Imports used below

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.memory.SimpleMemoryManager
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.rag.RAG
import org.llm4s.rag.RAG.RAGConfigOps
import org.llm4s.toolapi.{ ObjectSchema, Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import upickle.default.{ macroRW, ReadWriter }
```

## 1. Calling a model

The client comes from configuration. This snippet is compiled but not run, because it needs a configured provider:

```scala
def connect(): Result[LLMClient] = for {
  providerConfig  <- Llm4sConfig.defaultProvider()
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client <- LLMConnect.getClient(providerConfig)
} yield client
```

With a client, a call is a conversation in and a completion out:

```scala
val reply: Result[String] = for {
  conversation <- Conversation.fromPrompts("You answer in one sentence.", "What is Scala?")
  completion   <- client.complete(conversation)
} yield completion.content
```

`reply` is a `Right` with the text, or a `Left` with an `LLMError`. Nothing is thrown. The test checks that the
system message and the user message are sent, in that order.

## 2. Typed structured output

In LangChain4j you change an AI Service method's return type [L1]. In Spring AI you call `entity()`. Spring AI's
reference shows:

```java
ActorFilms actorFilms = chatClient.prompt()
    .user("Generate the filmography for a random actor.")
    .call()
    .entity(ActorFilms.class);
```

*(From the [Spring AI `ChatClient` page](https://docs.spring.io/spring-ai/reference/api/chatclient.html), fetched
2026-10-08.)* In LLM4S you describe the shape once as a schema and pass it:

```scala
case class Person(name: String, age: Int)
object Person { implicit val rw: ReadWriter[Person] = macroRW }

val personSchema: ObjectSchema[Person] = Schema
  .`object`[Person]("A person")
  .withRequiredField("name", Schema.string("The person's name"))
  .withRequiredField("age", Schema.integer("The person's age in years"))
```

```scala
val person: Result[Person] = for {
  conversation <- Conversation.fromPrompts("Extract the person.", "Ada Lovelace is 36.")
  parsed       <- client.completeStructured[Person](conversation, personSchema)
} yield parsed
```

What to know:

- **The schema is not derived from `Person`.** You write it, and keep it in step with the case class yourself. A
  mismatch shows up when a reply fails to parse, not at compile time
  ([#1472](https://github.com/llm4s/llm4s/issues/1472) is the open issue for derivation).
- The schema is sent to the provider as a JSON-Schema response format, in strict mode: every property is listed as
  required, optional ones included. The tests check the properties and the required list that are sent. Providers that
  enforce a schema natively use it. Anthropic gets a best-effort instruction in the prompt that is not enforced,
  according to `completeStructured`'s Scaladoc.
- A reply that is not the requested shape gives a `Left`, not an exception. The test covers this too.

## 3. A tool the model can call

LangChain4j lets an AI Service be configured with tools the model can call [L1]. In LLM4S a tool is a `ToolFunction`
built with `ToolBuilder`, and an `Agent` runs the loop in which the model asks for it:

```scala
case class Forecast(city: String, summary: String)
object Forecast { implicit val rw: ReadWriter[Forecast] = macroRW }

private val forecastSchema = Schema
  .`object`[Map[String, Any]]("Forecast parameters")
  .withProperty(Schema.property("city", Schema.string("The city to look up")))

val forecastTool = ToolBuilder[Map[String, Any], Forecast](
  name = "get_forecast",
  description = "Returns the forecast for a city",
  schema = forecastSchema
).withHandler(extractor => extractor.getString("city").map(city => Forecast(city, "sunny"))).buildSafe()
```

```scala
val result = for {
  tool  <- forecastTool
  agent <- Agent.builder("forecast-agent", client).withTools(new ToolRegistry(Seq(tool))).build()
  state <- agent.run("What is the weather in Oslo?")
} yield state
```

`result` is a `Result` holding the final agent state, and `state.answer` is the answer text. In the test the scripted
client asks for `get_forecast` once and then answers with what the tool returned, which shows that the tool is offered
with the request, runs, and has its output fed back. A real model decides for itself whether to call it.

Things to know: the handler returns an `Either[String, R]`, so a parameter that is missing or of the wrong type is an
error the model can see and retry, not an exception; and there are ready-made tools (calculator, date and time, UUID,
JSON, files, HTTP, shell and web search) in the [built-in tools guide](builtin-tools).

## 4. Memory

In LangChain4j, chat memory lets a service "remember" previous interactions [L1], and Spring AI has a `ChatMemory`
[S6]. LLM4S splits this in two:

- **Within a conversation**, history is a `Conversation` value: an immutable list of messages that you pass to each
  call.
- **Across conversations**, `llm4s-memory` stores facts, knowledge, entities and past messages, and returns the
  relevant ones as text to put in a prompt ([memory guide](agents/memory)):

```scala
val context: Result[String] = for {
  m1  <- SimpleMemoryManager.empty.recordUserFact("Prefers Scala over Java", Some("user-1"), Some(0.9))
  m2  <- m1.recordKnowledge("Scala 3 has opaque types", "docs")
  ctx <- m2.getRelevantContext("Scala")
} yield ctx
```

The test checks the exact text of `context`:

```text
# Retrieved Context
## Relevant Knowledge
- Scala 3 has opaque types

## User Preferences
- Prefers Scala over Java
```

The in-memory store matches **keywords**: the query `"What does the user like?"` shares no word with the stored text
and returns an empty string (the test checks that too). Use the embedding-backed store when you need a match by
meaning.

## 5. RAG

LangChain4j configures an AI Service with a `ContentRetriever` [L1]. In LLM4S the retrieval pipeline is `RAG` in the
`llm4s-rag` module: ingest text or files, then query. This snippet is compiled but not run, because embedding needs a
provider:

```scala
def buildRag(): Result[RAG] = for {
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  rag <- RAG.builder().withEmbeddings("ollama").build
} yield rag

def askTheDocs(rag: RAG): Result[Seq[String]] = for {
  _       <- rag.ingestText("Opaque types hide their representation outside the defining scope.", "scala-3")
  results <- rag.query("What are opaque types?")
} yield results.map(_.content)
```

The `import org.llm4s.rag.RAG.RAGConfigOps` at the top is needed: `build` is an extension method, and without the
import the compiler says `value build is not a member of org.llm4s.rag.RAGConfig`. Vector stores, hybrid search and
reranking are in the [vector store guide](vector-store).

## What you will miss, and what you gain

You will miss, today: derivation of schemas and tool definitions from types, a complete Java API, a test kit for your
own code, and the breadth of integrations the other projects list. See the
[full list](comparison#what-llm4s-has-not-got-yet), each item with its issue.

You gain, in code that exists today: `Result[A]` errors with typed error classes, a provider layer that is a small
interface you can implement ([writing a provider](writing-a-provider)), guardrails and agent handoffs in the same
library ([guardrails](agents/guardrails)), and cats-effect and ZIO modules ([cats-effect](cats-effect), [ZIO](zio)).

## Sources

All fetched on 2026-10-08.

| Tag | Page | Fact used |
|---|---|---|
| L1 | [LangChain4j: AI Services](https://docs.langchain4j.dev/tutorials/ai-services) | AI Services; return-type structured output; tools; chat memory "remember" previous interactions; `ContentRetriever` for RAG |
| L7 | [LangChain4j: structured outputs](https://docs.langchain4j.dev/tutorials/structured-outputs) | POJOs and records as return types |
| S6 | [Spring AI: ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html) | The fluent API; the `entity()` example quoted above; `ChatMemory` |
