---
layout: page
title: Coming from sttp-ai or openai-scala-client
description: "Move a Scala app from sttp-ai or openai-scala-client to LLM4S: calls side by side with compiled code, and when to stay on your client."
search_title: "Migrate from sttp-ai or openai-scala-client to LLM4S"
parent: User Guide
nav_order: 41
---

# Coming from sttp-ai or openai-scala-client
{: .no_toc }

If you already call a model from Scala with SoftwareMill's [sttp-ai](https://sttp-ai.softwaremill.com) or cequence's
[openai-scala-client](https://github.com/cequence-io/openai-scala-client), this page shows the code you have today next
to the same thing in LLM4S: one completion, typed output, a tool, streaming and switching provider. It then says what
LLM4S adds, what those clients do that LLM4S does not, and how to move one part of a service at a time. For the wider
picture, read the [comparison](comparison); coming from LangChain4j or Spring AI instead, read
[that guide](migrating-from-langchain4j).

Every LLM4S snippet below is compiled by a test in this repository (`ScalaClientMigrationGuideSpec`), which also checks
that the code on this page is, word for word, the code it compiles. All but the two configuration snippets are also
run, against `llm4s-testkit`'s scripted client, with no network. The sttp-ai and openai-scala-client code is quoted
from their own documentation, with the page it comes from under each quote, and the names in the tables link to where
each project documents them. They were checked on 2026-11-05 against sttp-ai 0.12.0 (whose documentation examples still
say 0.11.0; the names quoted here are unchanged in the 0.12.0 sources) and openai-scala-client 1.6.0.

1. TOC
{:toc}

## The calls side by side

| Task | sttp-ai | openai-scala-client | LLM4S |
|---|---|---|---|
| One completion | [`OpenAISyncClient.createChatCompletion`][s-basics], [`ClaudeSyncClient.createMessage`][s-claude] | [`OpenAIService.createChatCompletion`][c-chat] | `LLMClient.complete` |
| Typed output | [`createChatCompletionAs[T]`][s-structured] | [`createChatCompletionWithJSON[T]`][c-chat], with a `JsonSchemaDef` in `CreateChatCompletionSettings` or one inferred from `T` | `completeStructuredOf[T]` (`llm4s-schema-derivation`), or `completeStructured` with a hand-written schema |
| Tools | [`Tool.Function.withSchema[T]`][s-tools], and the [agent loop][s-agent] (`AgentTool.fromFunction`, `OpenAIAgent`) | [`createChatToolCompletion`][c-chat] with `FunctionSpec`s | `Tool.of[A, R]` or `ToolBuilder`, in a `ToolRegistry` run by an `Agent` |
| Streaming | [`createStreamedChatCompletion`][s-streaming], with the fs2, ZIO, Akka, Pekko or Ox module | [`createChatCompletionStreamed`][c-providers], typed [`createChatToolCompletionStreamed`][c-streaming] (`openai-scala-client-stream`) | `streamComplete` with a callback; fs2, ZIO and Pekko streams from `llm4s-effect`, `llm4s-zio` and `llm4s-pekko` |
| Switching provider | One client per provider (`openai`, `claude`, `gemini`), or the OpenAI client with [another base URL][s-compatible] | An [`asOpenAI()` adapter][c-providers] per provider, behind the same `OpenAIService` | A named section in `application.conf` |
| Errors | `OpenAISyncClient` throws an `OpenAIException`; `OpenAI` returns `Either[OpenAIException, A]` ([basics][s-basics]) | Calls return a `Future` ([chat completions][c-chat]) | `Result[A]`, an `Either[LLMError, A]` ([error handling](error-handling)) |
| Testing | [`agent-testkit`][s-testing]: a scripted model for the agent loop | No test module in its README or docs pages linked here | `llm4s-testkit`'s `ScriptedLLMClient` ([testing](testing-with-the-testkit)) |

The largest difference is not a method: **LLM4S returns `Result[A]` instead of throwing or failing a `Future`**. The
snippets below chain calls with a `for` comprehension, and a failure is a `Left` you can match on.

## Imports used below

```scala
import cats.effect.IO
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.effect.cats.LLMClientIO
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.schema.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import upickle.default.ReadWriter
```

The artifacts are `llm4s-core` and a provider module (for example `llm4s-openai`), plus `llm4s-schema-derivation` for
typed output and tools, `llm4s-agent` for the agent, `llm4s-effect` for fs2 and `llm4s-testkit` in test scope. See
[installation](../getting-started/installation).

## 1. One completion

sttp-ai's OpenAI basics build a `ChatBody` and call the sync client:

~~~scala
val chatRequestBody: ChatBody = ChatBody(
  model = ChatCompletionModel.GPT4oMini,
  messages = bodyMessages
)

// be aware that calling `createChatCompletion` may throw an OpenAIException
// e.g. AuthenticationException, RateLimitException and many more
val chatResponse: ChatResponse = openAI.createChatCompletion(chatRequestBody)
~~~

*(From [sttp-ai: OpenAI API basics][s-basics].)* openai-scala-client's quick start:

~~~scala
import io.cequence.openaiscala.domain._
import io.cequence.openaiscala.domain.settings.CreateChatCompletionSettings
import io.cequence.openaiscala.service.OpenAIServiceFactory

import scala.concurrent.ExecutionContext.Implicits.global

val service = OpenAIServiceFactory() // the key from OPENAI_SCALA_CLIENT_API_KEY

service
  .createChatCompletion(
    Seq(UserMessage("What is the capital of Norway? One word.")),
    CreateChatCompletionSettings(model = ModelId.gpt_5_4_mini)
  )
  .map(response => println(response.contentHead))
  .andThen { case _ => service.close() } // close a service when done with it - it owns an HTTP pool and an actor system
~~~

*(From [the openai-scala-client README][c-readme].)* In LLM4S the provider and model are not in the code: they come from
a section of `application.conf` (see [5. Switching provider](#5-switching-provider)). This snippet is compiled but not
run, because it needs a configured provider:

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

The test checks that the system message and the user message are sent, in that order, and that `reply` is a `Right`
with the text. A failed call (a rate limit, a bad key) is a `Left` with an `LLMError`, not an exception.

## 2. Typed output

sttp-ai derives the response schema from a case class with a Tapir `Schema` and parses the answer with circe:

~~~scala
case class Step(explanation: String, output: String) derives io.circe.Codec.AsObject, Schema
case class MathReasoning(steps: List[Step], finalAnswer: String) derives io.circe.Codec.AsObject, Schema

// ...
    val result: MathReasoning = openAI.createChatCompletionAs[MathReasoning](chatBody)
~~~

*(Abridged from [sttp-ai: structured outputs][s-structured].)* openai-scala-client infers the schema from `T` when the
settings carry none, and parses with a play-json `Format`:

~~~scala
  // no jsonSchema in the settings - the schema comes from CapitalsResponse
  service
    .createChatCompletionWithJSON[CapitalsResponse](
      messages,
      CreateChatCompletionSettings(model = ModelId.gpt_5_4_mini)
    )
    .map(_.capitals.foreach(println))
~~~

*(From [openai-scala-client: chat completions][c-chat].)* In LLM4S the case class derives a `SchemaOf` (from
`llm4s-schema-derivation`) and a uPickle `ReadWriter`:

```scala
case class Capital(country: String, capital: String) derives SchemaOf, ReadWriter
```

```scala
val capital: Result[Capital] = for {
  conversation <- Conversation.fromPrompts("Answer with the country and its capital.", "Capital of Norway?")
  parsed       <- client.completeStructuredOf[Capital](conversation)
} yield parsed
```

What to know:

- The schema is sent as a JSON-Schema response format in strict mode; the test checks its properties and required
  list. Providers that enforce a schema use it; for Anthropic, `completeStructured` falls back to an instruction in
  the prompt ([structured output](structured-output)).
- A reply that is not the requested shape gives a `Left`, not an exception. The test covers this too.
- `llm4s-schema-derivation` is Experimental. Without it, write the schema by hand and call `completeStructured`
  ([structured output](structured-output), [derived schemas](derived-schemas)).
- If your types already derive circe or play-json codecs, see [JSON libraries](json-libraries) for converting at the
  boundary.

## 3. A tool, and the loop that runs it

With sttp-ai's chat API you send `Function.withSchema[FlightDetails](...)` in the request, decode the tool call's
arguments yourself, call the function and send its result back ([tool calling][s-tools]); openai-scala-client's
`createChatToolCompletion` returns the tool calls for you to run the same way ([chat completions][c-chat]). sttp-ai
also has an agent loop that does this for you:

~~~scala
  case class WeatherInput(location: String) derives io.circe.Codec.AsObject, Schema

  val weatherTool = AgentTool.fromFunction(
    "get_weather",
    "Get the current weather for a location"
  ) { (input: WeatherInput) =>
    s"The weather in ${input.location} is 22°C, sunny"
  }

  // ...
    val agent = OpenAIAgent
      .synchronous(OpenAI.fromEnv, ChatCompletionModel.GPT4oMini)
      .maxIterations(5)
      .tools(weatherTool)
      .build

    val result = agent.run("What's the weather in Paris?")(backend)
~~~

*(Abridged from [sttp-ai: agent loop][s-agent].)* In LLM4S the tool's parameters are a case class too, and an `Agent`
runs the loop:

```scala
case class WeatherInput(location: String) derives SchemaOf, ReadWriter
case class Weather(location: String, report: String) derives ReadWriter

val weatherTool: Result[ToolFunction[WeatherInput, Weather]] =
  Tool.of[WeatherInput, Weather]("get_weather", "Get the current weather for a location") { input =>
    Right(Weather(input.location, "22°C, sunny"))
  }
```

The test scripts the model with `llm4s-testkit`: the first reply asks for the tool, the second answers.

```scala
val client = ScriptedLLMClient.sequence(
  Reply.toolCall("get_weather", """{"location": "Paris"}"""),
  Reply.text("It is 22°C and sunny in Paris.")
)
```

```scala
val answer: Result[Option[String]] = for {
  tool   <- weatherTool
  agent  <- Agent.builder("weather-agent", client).withTools(new ToolRegistry(Seq(tool))).build()
  result <- agent.run("What's the weather in Paris?")
} yield result.answer
```

The test checks that the tool is offered with the first request, that it runs, and that the second request carries its
result. A tool returns `Either[String, R]`: a `Left`, or arguments that do not match `WeatherInput`, is an error the
model sees and can retry. `ToolBuilder` builds the same `ToolFunction` from a hand-written schema
([custom tools](custom-tools)), and there are ready-made tools in the [built-in tools guide](builtin-tools).

## 4. Streaming

sttp-ai streams through its fs2, ZIO, Akka, Pekko or Ox module ([streaming][s-streaming]); openai-scala-client returns
an Akka Streams `Source` from `openai-scala-client-stream` ([streaming][c-streaming]). LLM4S's core call takes a
callback that receives each chunk, and returns the whole completion when the stream ends:

```scala
val shown = new StringBuilder
val streamed: Result[String] = for {
  conversation <- Conversation.fromPrompts("You answer in one sentence.", "What is Scala?")
  completion   <- client.streamComplete(conversation, CompletionOptions(), _.content.foreach(shown.append))
} yield completion.content
```

With cats-effect, `llm4s-effect` turns the same call into an `fs2.Stream` (given a `conversation`):

```scala
val text: IO[String] =
  LLMClientIO[IO](client).streamComplete(conversation).map(_.content.getOrElse("")).compile.string
```

`llm4s-zio` and `llm4s-pekko` do the same for ZIO Streams and Pekko Streams ([cats-effect](cats-effect), [ZIO](zio),
[Pekko](pekko)).

## 5. Switching provider

With sttp-ai you choose a client per provider, or point the OpenAI client at an OpenAI-compatible base URL
([compatible APIs][s-compatible]); with openai-scala-client, a provider's factory gives an `OpenAIService` through
`asOpenAI()` ([services and providers][c-providers]). In LLM4S the provider is configuration. Each section of
`application.conf` names a provider and a model, and the key comes from the vendor's usual environment variable:

```hocon
# src/main/resources/application.conf
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {                     # key from OPENAI_API_KEY
      provider = "openai"
      model    = "gpt-4o-mini"
    }

    claude {                          # key from ANTHROPIC_API_KEY
      provider = "anthropic"
      model    = "claude-sonnet-4-20250514"
    }
  }
}
```

`connect()` above loads the default section; this loads any section by name (compiled, not run):

```scala
def connectTo(section: String): Result[LLMClient] = for {
  providerConfig  <- Llm4sConfig.provider(section)
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client <- LLMConnect.getClient(providerConfig)
} yield client
```

The code that uses the `LLMClient` does not change when the section does. Each provider is its own artifact
(`llm4s-openai`, `llm4s-anthropic`, `llm4s-gemini`, `llm4s-ollama`, ...); see [providers](providers).

## What LLM4S adds

These are things a client leaves to you, and that LLM4S has in code today:

- **An agent runtime**: the tool loop, with guardrails on input and output ([guardrails](agents/guardrails)),
  handoffs between agents ([handoffs](agents/handoffs)), streamed run events ([streaming events](agents/streaming)),
  and runs that suspend for human review and resume ([human review](agents/human-review)).
- **Context management**: pruning a long conversation to fit the model's window
  ([context window pruning](context-window-pruning)).
- **RAG and memory**: ingestion, vector stores, hybrid search and reranking ([vector store](vector-store)), and
  long-term memory ([memory](agents/memory)).
- **Tracing and metrics**: Langfuse and OpenTelemetry tracing, Prometheus and Micrometer metrics
  ([monitoring](observability/)).
- **Reliability**: retries, a circuit breaker and deadlines around any `LLMClient`
  ([reliability guide](https://github.com/llm4s/llm4s/blob/main/docs/reliability-guide.md)).
- **A scripted client for tests** that records every request ([testing](testing-with-the-testkit)).

sttp-ai and openai-scala-client have some of these too: sttp-ai has its own agent loop, with interceptors, MCP tools
and an `agent-testkit` ([agent loop][s-agent], [testing][s-testing]); openai-scala-client has retry, routing and
guardrail adapters ([adapters][c-adapters]). Compare what each has for the feature you need.

## When to stay on your client

Stay where you are, or keep the client next to LLM4S, when:

- **You need an endpoint LLM4S does not wrap.** LLM4S covers chat completion, embeddings, image generation and
  speech. Both clients also wrap OpenAI endpoints such as files, fine-tuning, batches and moderations, and
  openai-scala-client the Responses API and Anthropic's Managed Agents ([sttp-ai][s-basics],
  [openai-scala-client][c-readme]). LLM4S has none of these.
- **You build for Scala 2.12 or 2.13.** Both clients publish for 2.12, 2.13 and 3; LLM4S is Scala 3 only, on JDK 21.
- **You want a thin, request-level API.** Both clients mirror the provider's request and response types, so every
  parameter the API has is in reach. LLM4S puts one `LLMClient` over all providers, and a parameter it does not model
  is not available through it.
- **You need a stable API today.** LLM4S is pre-1.0 ([comparison](comparison#what-llm4s-has-not-got-yet)).
- **Your feature is one call.** If you do not need tools, agents, RAG, memory or tracing, a client is the smaller
  dependency.

## Moving one part at a time

Nothing forces a switch in one go. Both libraries and LLM4S can sit in the same service:

1. Add `llm4s-core`, a provider module and an `application.conf` section, using the same API key variable you have now
   for OpenAI (`OPENAI_API_KEY`) or Anthropic (`ANTHROPIC_API_KEY`).
2. Move the feature that has outgrown a client first: usually the hand-written tool loop, to an `Agent`. Leave the
   rest on the client.
3. Keep the client for the endpoints LLM4S does not wrap (see above).
4. Write tests for the moved code with `ScriptedLLMClient`, so they run without a network or a key.

## Sources

Fetched on 2026-11-05. sttp-ai's documentation site documents the latest release; openai-scala-client's links are
pinned to the `v1.6.0` tag.

| Page | Names used |
|---|---|
| [sttp-ai: OpenAI API basics][s-basics] | `OpenAISyncClient`, `OpenAI`, `ChatBody`, `createChatCompletion`, `OpenAIException`; the endpoints wrapped |
| [sttp-ai: Claude API basics][s-claude] | `ClaudeSyncClient.createMessage` |
| [sttp-ai: structured outputs][s-structured] | `createChatCompletionAs[T]`, the quoted case classes |
| [sttp-ai: tool calling][s-tools] | `Tool.Function.withSchema[T]` |
| [sttp-ai: agent loop][s-agent] | `AgentTool.fromFunction`, `OpenAIAgent`, the quoted agent |
| [sttp-ai: testing agents][s-testing] | `agent-testkit` |
| [sttp-ai: OpenAI streaming][s-streaming] | `createStreamedChatCompletion`, the streaming modules |
| [sttp-ai: OpenAI-compatible APIs][s-compatible] | The base-URL constructor |
| [openai-scala-client: README][c-readme] | The quick start; the endpoints wrapped; Scala versions |
| [openai-scala-client: chat completions][c-chat] | `createChatCompletion`, `createChatToolCompletion`, `createChatCompletionWithJSON`, `JsonSchemaDef` |
| [openai-scala-client: streaming][c-streaming] | `createChatToolCompletionStreamed` |
| [openai-scala-client: services and providers][c-providers] | `asOpenAI()`, `createChatCompletionStreamed` |
| [openai-scala-client: adapters][c-adapters] | Retry, routing and guardrail adapters |

[s-basics]: https://sttp-ai.softwaremill.com/openai/basics.html
[s-claude]: https://sttp-ai.softwaremill.com/claude/basics.html
[s-structured]: https://sttp-ai.softwaremill.com/openai/structured-outputs.html
[s-tools]: https://sttp-ai.softwaremill.com/openai/tool-calling.html
[s-agent]: https://sttp-ai.softwaremill.com/agents/quickstart.html
[s-testing]: https://sttp-ai.softwaremill.com/agents/testing.html
[s-streaming]: https://sttp-ai.softwaremill.com/openai/streaming.html
[s-compatible]: https://sttp-ai.softwaremill.com/openai/compatible-apis.html
[c-readme]: https://github.com/cequence-io/openai-scala-client/blob/v1.6.0/README.md
[c-chat]: https://github.com/cequence-io/openai-scala-client/blob/v1.6.0/docs/chat-completions.md
[c-streaming]: https://github.com/cequence-io/openai-scala-client/blob/v1.6.0/docs/streaming.md
[c-providers]: https://github.com/cequence-io/openai-scala-client/blob/v1.6.0/docs/providers.md
[c-adapters]: https://github.com/cequence-io/openai-scala-client/blob/v1.6.0/docs/adapters.md
