---
layout: page
title: Direct Style and Ox
parent: User Guide
description: "Use LLM4S in direct style on JDK 21 virtual threads: plain Result code, and llm4s-ox for streamed completions as an Ox Flow, bounded fan-out, races, timeouts and retries."
search_title: "LLM4S in direct style: Ox, virtual threads, Flow streaming"
nav_order: 43
---

# Direct Style and Ox

llm4s's core API is already direct style. `client.complete(...)` returns a plain `Result[Completion]` on the calling
thread, and interrupting that thread cancels the call: the provider returns `Left(CancelledError)` (see
[Error Handling](error-handling)). On JDK 21 virtual threads a blocked thread costs almost nothing, so this is a
first-class way to write an llm4s application, not a shortcut around an effect system.

This page covers plain direct style, and the `llm4s-ox` module (Experimental), which adds what plain code leaves to
you, using SoftwareMill's [Ox](https://ox.softwaremill.com): a pull-based stream of chunks, bounded fan-out, races
between providers, timeouts and retries.

**Requirements:** JDK 21 or later (every llm4s artifact needs it; Ox's own classes are JDK 21 bytecode) and Scala 3.

## Choosing a style

| You want | Use |
|----------|-----|
| Sequential calls, a CLI, a batch job, a virtual-thread server | Plain direct style: `Result` and `for` |
| Streaming you can compose, throttle or stop; fan-out with a bound; races and timeouts | `llm4s-ox` |
| An application already built on cats-effect or fs2 | [`llm4s-effect`](cats-effect) |
| An application already built on ZIO | [`llm4s-zio`](zio) |
| Pekko Streams, Pekko HTTP or Play | [`llm4s-pekko`](pekko) |

The modules wrap the same core calls, so the choice is about the rest of your application. `llm4s-ox` runs the same
conformance suite as the effect modules (errors, chunk order, early stop, cancellation).

## Plain direct style

`Result[A]` is an `Either[LLMError, A]`, so a `for` comprehension short-circuits on the first error:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

val answer: Result[String] = for {
  provider <- Llm4sConfig.defaultProvider()
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client     <- LLMConnect.getClient(provider)
  completion <- client.complete(Conversation(Seq(UserMessage("What is 2 + 2?"))))
} yield completion.content
```

Streaming without Ox is a callback, `client.streamComplete(conversation, options, chunk => ...)`, that runs on the
calling thread until the reply ends.

## The llm4s-ox module

```scala
libraryDependencies += "org.llm4s" %% "llm4s-ox" % "<version>"
```

It depends on `llm4s-core` and Ox only. `import org.llm4s.ox.*` brings in the extensions below.

### Ox's `either` blocks

Ox's `either` block and `.ok()` give the same short-circuiting with ordinary `val`s; `.ok()` returns the value or
leaves the block with the error:

```scala
import ox.either
import ox.either.ok
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def ask(question: String): Result[String] = either {
  val provider               = Llm4sConfig.defaultProvider().ok()
  given ModelRegistryService = Llm4sConfig.modelRegistryService().ok()
  val client                 = LLMConnect.getClient(provider).ok()
  client.complete(Conversation(Seq(UserMessage(question)))).ok().content
}
```

Call `.ok()` on the thread of its `either` block, never inside a fork.

### Streaming as a Flow

`client.streamFlow(conversation)` is an Ox `Flow[StreamedChunk]`. In the examples below, `client` is an `LLMClient`
and `conversation` a `Conversation`:

```scala
import org.llm4s.ox.*
import scala.concurrent.duration.*

client
  .streamFlow(conversation)
  .textDeltas
  .throttle(20, 1.second)
  .runForeach(print)
```

- **Cold:** each run makes one `streamComplete` call.
- **Bounded:** the provider call runs on a virtual thread of its own and hands chunks to the consumer through a channel
  of Ox's `BufferCapacity`, 16 by default. When the consumer lags, the provider's thread waits.
- **Cancelled when the consumer stops:** a `take`, a failure downstream or the end of the consumer's scope interrupts
  the provider call, which returns `CancelledError`, and the run returns only after that call has returned. No
  thread outlives the run.
- **Errors:** a provider `Left` fails the flow with an `org.llm4s.ox.LLMException` whose `error` is the `LLMError`,
  after the chunks delivered before it.

`textDeltas` keeps the text of each chunk. `accumulate` runs the flow and builds the `Completion` the chunks describe
(content, tool calls, thinking), returning a flow's `LLMError` as a `Left`. A chunk carries no token usage, so that
`Completion` has none; use `client.complete` or `streamComplete`'s own result when you need usage.

```scala
import org.llm4s.llmconnect.model.Completion
import org.llm4s.ox.*
import org.llm4s.types.Result

val opening: List[String]     = client.streamFlow(conversation).textDeltas.take(5).runToList()
val whole: Result[Completion] = client.streamFlow(conversation).accumulate
```

A larger buffer is a `given`:

```scala
import ox.channels.BufferCapacity
import org.llm4s.ox.*

given BufferCapacity = BufferCapacity(64)
client.streamFlow(conversation).textDeltas.runForeach(print)
```

### Timeouts

Ox's `timeoutOption` runs a call and interrupts it if the time runs out, so the provider call ends with
`CancelledError` and its thread is free:

```scala
import ox.timeoutOption
import org.llm4s.llmconnect.model.Completion
import org.llm4s.types.Result
import scala.concurrent.duration.*

val withinTime: Option[Result[Completion]] = timeoutOption(30.seconds)(client.complete(conversation))
```

### Fan-out

`parComplete` completes many conversations, at most `parallelism` at a time, each on a virtual thread. The results
keep the order of the conversations, one `Result` each, so a failed request does not cancel the others. An `either`
block makes the batch all-or-nothing:

```scala
import ox.either
import ox.either.ok
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.ox.*
import org.llm4s.types.Result

val chapters: Seq[String] = loadDocuments()
val summaries = client.parComplete(
  chapters.map(chapter => Conversation(Seq(UserMessage(s"Summarise in two sentences:\n$chapter")))),
  parallelism = 4
)

val book: Result[String] = either {
  val parts = summaries.map(_.ok().content)
  client.complete(Conversation(Seq(UserMessage(s"Combine:\n${parts.mkString("\n")}")))).ok().content
}
```

For fan-out that stops at the first failure and interrupts the rest, use Ox's `parEitherLimit` with
`client.complete` directly.

### Racing providers

`raceProviders` sends one conversation to several clients at once and returns the first success. The calls still
running are interrupted, and the race returns once they have ended. When every call fails, it returns the first
failure:

```scala
import ox.timeoutOption
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.ox.*
import org.llm4s.types.Result
import scala.concurrent.duration.*

def fastest(openai: LLMClient, anthropic: LLMClient): Option[Result[Completion]] =
  timeoutOption(10.seconds)(raceProviders(openai, anthropic)(conversation))
```

### Retries

`RetrySchedule.retry` retries what the library's one retry rule retries (the rule `ReliableClient` and the agent
graph use): a recoverable error, but not a client-error status (a 4xx other than 408 and 429) and never a
`CancelledError`. It waits the schedule's delay, or longer when the provider asked for longer: a `RateLimitError`'s
retry-after, or a `ServiceError`'s. `RetrySchedule.default` is exponential backoff from 1 second, capped at 30
seconds, with jitter, for at most 3 retries. `RetrySchedule.config` is the same rule as an Ox `RetryConfig`, for Ox's
own `retryEither`, which waits only the schedule's delays.

```scala
import ox.resilience.retryEither
import ox.scheduling.Schedule
import org.llm4s.llmconnect.model.Completion
import org.llm4s.ox.RetrySchedule
import org.llm4s.types.Result
import scala.concurrent.duration.*

val retried: Result[Completion] = RetrySchedule.retry()(client.complete(conversation))
val patient: Result[Completion] =
  RetrySchedule.retry(Schedule.exponentialBackoff(2.seconds).maxRetries(5))(client.complete(conversation))
val oxRetry: Result[Completion] = retryEither(RetrySchedule.config[Completion]())(client.complete(conversation))
```

An interrupt during a wait propagates as `InterruptedException`, so an enclosing Ox scope or timeout can end.

## Serving on virtual threads

A server that runs each request on its own virtual thread can call the client directly. The JDK's own `HttpServer`
is enough for a small service:

```scala
import com.sun.net.httpserver.HttpServer
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.Executors

val server = HttpServer.create(new InetSocketAddress(8080), 0)
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
server.createContext(
  "/ask",
  exchange => {
    val question = new String(exchange.getRequestBody.readAllBytes(), UTF_8)
    val reply    = client.complete(Conversation(Seq(UserMessage(question)))).fold(_.message, _.content)
    val bytes    = reply.getBytes(UTF_8)
    exchange.sendResponseHeaders(200, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
    exchange.close()
  }
)
server.start()
```

Tapir's Netty sync server runs each request on a virtual thread the same way. For an agent served over HTTP with a
run queue and server-sent events, see `modules/deploy-service`.

## Agents

`llm4s-ox` covers `LLMClient`-level code. An `Agent` is already direct style: `agent.run(query)` returns a
`Result[AgentResult]` on the calling thread, `agent.stream(threadId, query)(listener)` delivers its events to a
callback, and `AgentRun.cancel()` cancels a turn (see [Streaming events](agents/streaming)).

## Example

`org.llm4s.samples.direct.DirectStyleExample` runs every feature on this page offline, against a scripted client:

```bash
sbt "samples/runMain org.llm4s.samples.direct.DirectStyleExample"
```
