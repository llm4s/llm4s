---
layout: page
title: Testing with the Testkit
parent: User Guide
nav_order: 16
---

# Testing with the Testkit

`llm4s-testkit` is a scriptable fake `LLMClient` for the tests of **your** code. It answers from a script you write, records what your code sent, and never touches the network, so a test that calls an LLM is fast, free and the same on every run.

It is for people who *use* llm4s. If you are writing a provider module, see [Writing a provider](writing-a-provider#testing) instead (`llm4s-provider-testkit`).

{: .note }
> Not yet published. `llm4s-testkit` exists in the build as of [#1475](https://github.com/llm4s/llm4s/issues/1475) but ships in the next release.

```sbt
// same version as llm4s-core; test scope only
libraryDependencies += "org.llm4s" %% "llm4s-testkit" % llm4sVersion % Test
```

The module depends on `llm4s-core` only and has no test framework in its API, so it works with whichever one you use. The examples below use ScalaTest.

## A first test

A `Reply` is what one call returns. `ScriptedLLMClient.sequence` answers the first call with the first reply, the second call with the second, and so on.

```scala
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
```

```scala
val client = ScriptedLLMClient.sequence(Reply.text("Paris"))
val result = client.complete(Conversation(Seq(UserMessage("Capital of France?"))), CompletionOptions())

result.map(_.content) shouldBe Right("Paris")
client.callCount shouldBe 1
```

## Check what your code sent

Every request is recorded, in order, whether it came through `complete` or `streamComplete`:

```scala
client.calls.head.lastUserText shouldBe Some("Capital of France?")
```

A `RecordedCall` also holds the whole `conversation`, the `options` and whether it was `streamed`, so a test can check the system prompt, the temperature or the tools your code offered.

## An agent with a tool, end to end

Here is a small feature built on an `Agent` with one tool of its own:

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.GraphError
import org.llm4s.error.{ LLMError, ProcessingError, RateLimitError }
```

```scala
import org.llm4s.toolapi._
import org.llm4s.types.Result
```

```scala
final case class Temperature(city: String, celsius: Double) derives ReadWriter

val temperatureTool: Result[ToolFunction[Map[String, Any], Temperature]] =
  ToolBuilder[Map[String, Any], Temperature](
    "get_temperature",
    "Current temperature in a city, in Celsius.",
    Schema
      .`object`[Map[String, Any]]("Parameters")
      .withProperty(Schema.property("city", Schema.string("City name")))
  ).withHandler(params => params.getString("city").map(city => Temperature(city, 21.0))).buildSafe()

def packingAdvice(client: LLMClient, city: String): Result[String] =
  for {
    tool   <- temperatureTool
    agent  <- Agent.builder("packing-assistant", client).withTools(new ToolRegistry(Seq(tool))).build()
    run    <- agent.run(s"What should I pack for $city today?")
    answer <- run.answer.toRight(ProcessingError("packing-advice", s"the run did not complete: ${run.status}"))
  } yield answer
```

The model's side of the conversation is the script: first it asks for the tool, then it answers with what the tool returned.

```scala
val client = ScriptedLLMClient.sequence(
  Reply.toolCall("get_temperature", ujson.Obj("city" -> "Oslo")),
  Reply.text("It is 21 degrees in Oslo: pack light layers.")
)

packingAdvice(client, "Oslo") shouldBe Right("It is 21 degrees in Oslo: pack light layers.")
```

`Reply.toolCall` leaves the call's `id` empty, and the client numbers it (`call-1-1`: call 1, first tool call). The recorded calls show what the agent really did:

```scala
client.callCount shouldBe 2
client.calls.head.options.tools.map(_.name) shouldBe Seq("get_temperature")
client.calls(1).messages.last.content should include("21")
```

The first request offered the tool, and the second carried the tool's result back to the model.

## Make the provider fail

`Reply.failure` takes any `LLMError`, so a test can see how your code reacts to a rate limit, a timeout or a bad key, with the real error types:

```scala
val failing = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("openai")))

val outcome = packingAdvice(failing, "Oslo")

outcome.left.map(providerError) shouldBe Left(RateLimitError("openai"))
```

An agent wraps a provider error in a `GraphError.NodeFailed` that names the failing node; the error you scripted is its `cause`. `providerError` is the small helper that takes it out:

```scala
def providerError(error: LLMError): LLMError = error match {
  case GraphError.NodeFailed(_, _, cause) => cause
  case other                              => other
}
```

To test retries, put the scripted client under a `ReliableClient`. Its `sleep` is a parameter, so the test does not wait:

```scala
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient }
```

```scala
val flaky    = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("openai")), Reply.text("recovered"))
val reliable = new ReliableClient(flaky, "openai", ReliabilityConfig.default, sleep = _ => ())

reliable.complete(conversation, options).map(_.content) shouldBe Right("recovered")
flaky.callCount shouldBe 2
```

(`conversation` and `options` are any `Conversation` and `CompletionOptions`.)

## When the code makes a call you did not script

A call past the end of the script does not hang or throw. It returns a `Left(ValidationError)` that says which call it was and what was asked, so a test that makes one call too many fails with a message that explains why:

```scala
val message = ScriptedLLMClient.sequence().complete(conversation, options).left.value.message

message should include("no reply is scripted for call 1")
message should include("Capital of France?")
```

Inside an agent, that error arrives wrapped in the same `NodeFailed` as any provider error.

## Choose the reply from the conversation

When the answer should not depend on how many calls came before, use `respondingTo`. The first case that matches the conversation decides the reply; a conversation no case matches fails like an unscripted call:

```scala
val client = ScriptedLLMClient.respondingTo {
  case c if c.messages.lastOption.exists(_.content.contains("Oslo"))  => Reply.text("cold")
  case c if c.messages.lastOption.exists(_.content.contains("Cairo")) => Reply.text("hot")
}

client.complete(Conversation(Seq(UserMessage("Weather in Cairo?"))), options).map(_.content) shouldBe Right("hot")
client.complete(Conversation(Seq(UserMessage("Weather in Oslo?"))), options).map(_.content) shouldBe Right("cold")
```

`ScriptedLLMClient.always(reply)` gives every call the same reply.

## Streaming

`streamComplete` replays the reply as chunks: the text in pieces of at most `chunkSize` characters (16 unless you change it), each tool call, then a last chunk that carries the finish reason (`stop`, or `tool_calls` when the reply calls tools).

```scala
val client = ScriptedLLMClient.sequence(Reply.text("Hello, world")).withChunkSize(5)

val chunks = ArrayBuffer.empty[StreamedChunk]
client.streamComplete(conversation, options, chunks += _)

chunks.flatMap(_.content).mkString shouldBe "Hello, world"
chunks.last.finishReason shouldBe Some("stop")
```

## Usage, model and context window

A reply can report token usage and a model name (`Reply.text("hi").withModel("gpt-test").withUsage(...)`), which is what code that tracks cost reads. The client reports a 128,000-token context window and reserves 4,096 tokens for the answer; `withContextWindow` and `withReserveCompletion` change that, which is how you test code that trims a conversation to fit.

## Good to know

- The client is thread-safe: calls from several threads are recorded one at a time and each gets its own position in the script.
- `withContextWindow`, `withReserveCompletion` and `withChunkSize` return a **fresh** client, with the same script and no recorded calls.
- The kit registers no provider, so it never changes which provider `ProviderRegistry` or `Llm4sConfig` selects. You pass the client to the code under test yourself.
- It does not contain a fake embedding client or a fake tracer, and it does not record and replay real provider traffic.
- Its API is Scala. A Java-friendly fake is tracked in [#1497](https://github.com/llm4s/llm4s/issues/1497).
- The hand-written mocks in the [Testing Guide](../getting-started/testing-guide) (`MockLLMClient`, `FailingMockClient` and the rest) do the same job with more code, and are still fine for a one-off.

## See also

- [Error Handling](error-handling) for the error types a `Reply.failure` can carry.
- [Reliability](../reliability-guide) for `ReliableClient`.
