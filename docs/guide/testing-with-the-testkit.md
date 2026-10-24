---
layout: page
title: Testing with the Testkit
parent: User Guide
nav_order: 29
---

# Testing with the Testkit

`llm4s-testkit` is a scriptable fake `LLMClient` for the tests of **your** code. It answers from a script you write, records what your code sent, and never touches the network, so a test that calls an LLM is fast, free and the same on every run.

It is for people who *use* llm4s. If you are writing a provider module, see [Writing a provider](writing-a-provider) instead (`llm4s-provider-testkit`).

```sbt
// same version as llm4s-core; test scope only
libraryDependencies += "org.llm4s" %% "llm4s-testkit" % llm4sVersion % Test
```

The module depends on `llm4s-core` only and has no test framework in its API, so it works with whichever one you use. The examples below use ScalaTest; [Other test frameworks](#other-test-frameworks) shows the same test in MUnit, munit-cats-effect, weaver and ZIO Test.

## A first test

A `Reply` is what one call returns. `ScriptedLLMClient.sequence` answers the first call with the first reply, the second call with the second, and so on. Every example on this page needs these imports; each section adds the ones its own code needs.

```scala
import org.llm4s.llmconnect.model._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient, UnscriptedCallError }
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
import org.llm4s.llmconnect.LLMClient
import org.llm4s.toolapi._
import org.llm4s.types.Result
import upickle.default._
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
  Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
  Reply.text("It is 21 degrees in Oslo: pack light layers.")
)

packingAdvice(client, "Oslo") shouldBe Right("It is 21 degrees in Oslo: pack light layers.")
```

The tool's arguments are a JSON object written as a string, so the test needs no JSON library. Text that is not a JSON object throws an `IllegalArgumentException` when the script is built, naming the tool, rather than failing later inside the code under test. Without an `id`, the client numbers the call (`call-1-1`: call 1, first tool call); `id = Some("...")` sets one. For an answer that calls several tools, chain `.withToolCall(name, argumentsJson)`; `Reply.toolCalls(...)` takes core's `ToolCall`s as they are.

The recorded calls show what the agent really did:

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

A call past the end of the script does not hang or throw. It returns a `Left(UnscriptedCallError)`: an error type of its own, so a test can tell "my code made a call I did not script" apart from any error a provider (or a `Reply.failure`) returns. It carries the `callNumber`, the `reason` and the `lastMessage`, and its message says which call it was and what was asked, so a test that makes one call too many fails with a message that explains why:

```scala
val error = ScriptedLLMClient.sequence().complete(conversation, options).left.value

error shouldBe an[UnscriptedCallError]
error.message should include("no reply is scripted for call 1")
error.message should include("Capital of France?")
```

Inside an agent, that error arrives wrapped in the same `NodeFailed` as any provider error. It is a `NonRecoverableError`, so a `ReliableClient` does not retry it.

## Check that every reply was used

The opposite mistake is quieter: the code makes fewer calls than you scripted, usually because it took a wrong branch, and the test still passes. `assertExhausted()` returns `Right(())` when every reply of a `sequence` script was used, and otherwise a `Left` whose message names each unused reply and its position:

```scala
val client = ScriptedLLMClient.sequence(Reply.text("Paris"), Reply.text("Rome"))
client.complete(conversation, options)

client.remaining shouldBe 1
client.assertExhausted().left.value should include("""reply 2: text "Rome"""")
```

The message reads:

```text
1 of 2 scripted replies was not used (the code under test made 1 call):
  reply 2: text "Rome"
```

End a test with `client.assertExhausted() shouldBe Right(())`, or let a fixture do it for every test, as the next sections show. `remaining` and `unusedReplies` give the same information as values. A `respondingTo` or `always` client has no fixed script, so for it nothing is ever unused.

## From llm4s-effect and llm4s-zio

The scripted client is an ordinary `LLMClient`, so it drives an agent run through `AgentIO` (cats-effect, `llm4s-effect`) or `AgentZ` (ZIO, `llm4s-zio`) exactly as through `Agent`. Both examples use the same agent as above, built by a small helper:

```scala
def packingAgent(client: LLMClient): Result[Agent] =
  temperatureTool.flatMap(tool =>
    Agent.builder("packing-assistant", client).withTools(new ToolRegistry(Seq(tool))).build()
  )
```

With cats-effect:

```scala
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.effect.cats.AgentIO
```

```scala
val client = ScriptedLLMClient.sequence(
  Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
  Reply.text("It is 21 degrees in Oslo: pack light layers.")
)

val run = AgentIO[IO](packingAgent(client).value).run("What should I pack for Oslo today?").unsafeRunSync()

run.answer shouldBe Some("It is 21 degrees in Oslo: pack light layers.")
client.callCount shouldBe 2
client.calls.head.options.tools.map(_.name) shouldBe Seq("get_temperature")
client.calls(1).messages.last.content should include("21")
```

With ZIO, in a `ZIOSpecDefault` test, with the same `client`:

```scala
import org.llm4s.zio.AgentZ
import zio.ZIO
import zio.test.*
```

```scala
for {
  agent <- ZIO.fromEither(packingAgent(client))
  run   <- AgentZ(agent).run("What should I pack for Oslo today?")
} yield assertTrue(
  run.answer.contains("It is 21 degrees in Oslo: pack light layers."),
  client.callCount == 2,
  client.calls.head.options.tools.map(_.name) == Seq("get_temperature"),
  client.calls(1).messages.last.content.contains("21")
)
```

A call the script has no reply for fails the effect like any provider error: `AgentIO` raises an `LLMException` whose `error` is the `NodeFailed` around the `UnscriptedCallError`, and `AgentZ` fails with that `NodeFailed`. These examples are the `ScriptedAgentIOSpec` and `ScriptedAgentZSpec` tests of `llm4s-effect` and `llm4s-zio`, which depend on the testkit `% Test` as your build would.

## Other test frameworks

The testkit has no test framework in its API, so each framework needs a few lines of glue: a fixture that gives each test a fresh scripted client and fails the test when a scripted reply was not used. Each example below tests the same packing assistant as above, and is a suite in `modules/samples` that CI runs. They reuse `temperatureTool` and `packingAdvice` from [An agent with a tool, end to end](#an-agent-with-a-tool-end-to-end).

### MUnit

```sbt
libraryDependencies += "org.scalameta" %% "munit" % "1.0.0" % Test
```

A `FunFixture` whose teardown fails the test:

```scala
def scripted(replies: Reply*): FunFixture[ScriptedLLMClient] =
  FunFixture(
    setup = _ => ScriptedLLMClient.sequence(replies*),
    teardown = client => client.assertExhausted().left.foreach(unused => fail(unused))
  )
```

```scala
scripted(
  Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
  Reply.text("It is 21 degrees in Oslo: pack light layers.")
).test("the packing assistant calls the tool, then answers") { client =>
  assertEquals(packingAdvice(client, "Oslo"), Right("It is 21 degrees in Oslo: pack light layers."))
  assertEquals(client.calls.head.options.tools.map(_.name), Seq("get_temperature"))
}
```

### munit-cats-effect

```sbt
libraryDependencies += "org.typelevel" %% "munit-cats-effect" % "2.0.0" % Test
```

With cats-effect, the fixture is a `Resource` whose release fails when a reply was not used. The same resource serves munit-cats-effect and weaver:

```scala
def scripted(replies: Reply*): Resource[IO, ScriptedLLMClient] =
  Resource.make(IO(ScriptedLLMClient.sequence(replies*)))(client =>
    // `defer`: check when the release runs, after the test, not when the release is built
    IO.defer(IO.fromEither(client.assertExhausted().left.map(unused => new AssertionError(unused))))
  )
```

`IO.defer` matters: `ResourceFunFixture` builds the release as soon as the resource is acquired, and without it the check would run then, before the test made any call. The test builds its agent through `LLMClientIO`:

```scala
import cats.effect.{ IO, Resource }
import munit.CatsEffectSuite
import org.llm4s.effect.cats.{ LLMClientIO, LLMException }
```

```scala
val packing = ResourceFunFixture(
  scripted(
    Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
    Reply.text("It is 21 degrees in Oslo: pack light layers.")
  )
)

packing.test("the packing assistant calls the tool, then answers") { client =>
  for {
    tool  <- IO.fromEither(temperatureTool.left.map(new LLMException(_)))
    agent <- LLMClientIO[IO](client).agent("packing-assistant")(_.withTools(new ToolRegistry(Seq(tool))))
    run   <- agent.run("What should I pack for Oslo today?")
  } yield {
    assertEquals(run.answer, Some("It is 21 degrees in Oslo: pack light layers."))
    assertEquals(client.calls.head.options.tools.map(_.name), Seq("get_temperature"))
  }
}
```

### weaver

```sbt
libraryDependencies += "com.disneystreaming" %% "weaver-cats" % "0.8.4" % Test
testFrameworks += new TestFramework("weaver.framework.CatsEffect")
```

From 0.9.0 weaver is published as `"org.typelevel" %% "weaver-cats"` and needs cats-effect 3.6; the code is the same. A script belongs to one test, so each test `use`s its own `scripted` resource (the one above) rather than a suite-wide `SharedResource`:

```scala
test("the packing assistant calls the tool, then answers") {
  scripted(
    Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
    Reply.text("It is 21 degrees in Oslo: pack light layers.")
  ).use { client =>
    for {
      tool  <- IO.fromEither(temperatureTool.left.map(new LLMException(_)))
      agent <- LLMClientIO[IO](client).agent("packing-assistant")(_.withTools(new ToolRegistry(Seq(tool))))
      run   <- agent.run("What should I pack for Oslo today?")
    } yield expect.all(
      run.answer.contains("It is 21 degrees in Oslo: pack light layers."),
      client.calls.head.options.tools.map(_.name) == Seq("get_temperature")
    )
  }
}
```

### ZIO Test

```sbt
libraryDependencies ++= Seq(
  "dev.zio" %% "zio-test"     % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test
)
```

A `ZLayer` provides the scripted client and the `LLMClientZ` over it. Its finalizer fails the test when the test succeeded but left a reply unused; a test that already failed keeps its own error:

```scala
import org.llm4s.zio.LLMClientZ
import zio.{ Exit, ZIO, ZLayer }
import zio.test.*
```

```scala
def scripted(replies: Reply*): ZLayer[Any, Nothing, ScriptedLLMClient & LLMClientZ] =
  ZLayer.scoped(
    ZIO.acquireReleaseExit(ZIO.succeed(ScriptedLLMClient.sequence(replies*))) {
      case (client, Exit.Success(_)) =>
        ZIO.fromEither(client.assertExhausted()).orDieWith(unused => new AssertionError(unused))
      case _ => ZIO.unit // the test already failed: keep its own error
    }
  ) >+> ZLayer.fromFunction((client: ScriptedLLMClient) => LLMClientZ(client))
```

```scala
test("the packing assistant calls the tool, then answers") {
  for {
    client <- ZIO.service[ScriptedLLMClient]
    tool   <- ZIO.fromEither(temperatureTool)
    agent  <- ZIO.serviceWithZIO[LLMClientZ](_.agent("packing-assistant")(_.withTools(new ToolRegistry(Seq(tool)))))
    run    <- agent.run("What should I pack for Oslo today?")
  } yield assertTrue(
    run.answer.contains("It is 21 degrees in Oslo: pack light layers."),
    client.calls.head.options.tools.map(_.name) == Seq("get_temperature")
  )
}.provide(
  scripted(
    Reply.toolCall("get_temperature", """{"city": "Oslo"}"""),
    Reply.text("It is 21 degrees in Oslo: pack light layers.")
  )
)
```

Each suite also has a test that proves its fixture fails a test that leaves a reply unused: `TestkitMUnitSuite`, `TestkitMUnitCatsEffectSuite`, `TestkitWeaverSuite` and `TestkitZioSpec`, in `modules/samples/src/test/scala/org/llm4s/samples/guides`.

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
import scala.collection.mutable.ArrayBuffer
```

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
- [Reliability Guide](https://github.com/llm4s/llm4s/blob/main/docs/reliability-guide.md) for `ReliableClient`.
