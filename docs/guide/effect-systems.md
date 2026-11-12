---
layout: page
title: Pick Your Stack
parent: User Guide
description: "One LLM4S program in plain Result, cats-effect, ZIO and Pekko side by side: the semantics every effect bridge shares, and the honest differences."
search_title: "LLM4S effect systems: cats-effect, ZIO, Pekko compared"
nav_order: 50
---

# Pick Your Stack

LLM4S's core API returns a plain `Result[A]` (`Either[LLMError, A]`) on the calling thread. Three modules wrap it for
an effect system: [`llm4s-effect`](cats-effect) (cats-effect and fs2), [`llm4s-zio`](zio) (ZIO and ZIO Streams) and
[`llm4s-pekko`](pekko) (Pekko Streams). They are thin wrappers over the same calls, and one conformance suite, run by
every bridge in CI, holds them to the same behaviour: one mental model, four syntaxes.

This page shows one small program in each of the four: a streamed completion, then an agent run with one tool, with
an error branch and a timeout. Every program returns the same string for the same script. A spec runs all four
against a scripted client and checks that the code below is the code it runs, word for word
(`EffectSystemsGuideSpec` in `modules/samples`).

For direct style on virtual threads with Ox, see [Direct Style and Ox](direct-style); `llm4s-ox` runs the same suite.

## Shared setup

The conversation, the question and the tool are the same in every program:

```scala
import org.llm4s.agent.Agent
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import scala.concurrent.duration.FiniteDuration

val greeting: Conversation = Conversation(Seq(UserMessage("Say hello in five words.")))
val question: String       = "What should I pack for Oslo?"

val temperature: Result[ToolFunction[Map[String, Any], Double]] =
  ToolBuilder[Map[String, Any], Double](
    "get_temperature",
    "Current temperature in a city, in Celsius.",
    Schema.`object`[Map[String, Any]]("Parameters").withProperty(Schema.property("city", Schema.string("City name")))
  ).withHandler(params => params.getString("city").map(_ => 21.0)).buildSafe()
```

Each program returns `"<reply> | <answer>"`, `"failed: <message>"` when the provider fails, or `"timed out"`.

## Plain Result

No dependency beyond core and `llm4s-agent`. Streaming is a callback on the calling thread, and a `for` over `Result`
stops at the first error. Core has no timeout combinator: the call runs on a virtual thread, and interrupting that
thread cancels the provider call, which returns `Left(CancelledError)`.

```scala
import java.util.concurrent.{ FutureTask, TimeUnit }
import scala.util.Try

/** `body` on a virtual thread; `None` once `timeout` passes, and the thread is interrupted, cancelling the call. */
def withinTime[A](timeout: FiniteDuration)(body: => A): Option[A] = {
  val call = new FutureTask[A](() => body)
  Thread.startVirtualThread(call)
  val result = Try(call.get(timeout.toNanos, TimeUnit.NANOSECONDS)).toOption
  if (result.isEmpty) call.cancel(true)
  result
}

def plainProgram(client: LLMClient, timeout: FiniteDuration): String = {
  def program: Result[String] = {
    val reply = new StringBuilder
    for {
      _      <- client.streamComplete(greeting, CompletionOptions(), _.content.foreach(reply.append))
      tool   <- temperature
      agent  <- Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(tool))).build()
      result <- agent.run(question)
    } yield s"$reply | ${result.answer.getOrElse("")}"
  }
  withinTime(timeout)(program).fold("timed out")(_.fold(error => s"failed: ${error.message}", identity))
}
```

## cats-effect

`llm4s-effect`. The chunks are an fs2 `Stream`, and an `LLMError` is raised as `LLMException` in `IO`'s error channel.
`timeoutTo` cancels the fiber, which interrupts the provider call.

```scala
import cats.effect.IO
import org.llm4s.effect.cats.{ LLMClientIO, LLMException }

def catsProgram(client: LLMClient, timeout: FiniteDuration): IO[String] = {
  val llm = LLMClientIO[IO](client)
  val program = for {
    reply  <- llm.streamComplete(greeting).map(_.content.getOrElse("")).compile.string
    tool   <- IO.fromEither(temperature.left.map(new LLMException(_)))
    agent  <- llm.agent("assistant")(_.withTools(new ToolRegistry(Seq(tool))))
    result <- agent.run(question)
  } yield s"$reply | ${result.answer.getOrElse("")}"
  program.timeoutTo(timeout, IO.pure("timed out")).recover { case e: LLMException => s"failed: ${e.error.message}" }
}
```

## ZIO

`llm4s-zio`. The chunks are a `ZStream`, and the error channel is the `LLMError` itself, so the error branch is a
typed `fold`. `timeout` interrupts the fiber, which interrupts the provider call.

```scala
import org.llm4s.zio.LLMClientZ
import zio.{ Duration, UIO, ZIO }

def zioProgram(client: LLMClient, timeout: FiniteDuration): UIO[String] = {
  val llm = LLMClientZ(client)
  val program = for {
    reply  <- llm.streamComplete(greeting).map(_.content.getOrElse("")).runFold("")(_ + _)
    tool   <- ZIO.fromEither(temperature)
    agent  <- llm.agent("assistant")(_.withTools(new ToolRegistry(Seq(tool))))
    result <- agent.run(question)
  } yield s"$reply | ${result.answer.getOrElse("")}"
  program.timeout(Duration.fromScala(timeout)).fold(e => s"failed: ${e.message}", _.getOrElse("timed out"))
}
```

## Pekko

`llm4s-pekko`. The chunks and the agent's events are `Source`s, and a failed stream carries the `LLMError` in an
`LLMException`. A `Future` cannot be cancelled, so the agent turn runs as a stream (`agent.stream`) rather than as
`agent.run`: `completionTimeout` then fails the stream, and cancelling it interrupts the provider call.
`llm.agent(...)` returns a plain `Result`, since building an agent does no I/O.
A `Source` from `llm4s-pekko` starts its call when it is materialized, and `concatLazy` materializes both sources
at once (it only delays pulling the second), so `Source.lazySource` keeps the agent turn from starting before the
reply has ended.

```scala
import java.util.concurrent.TimeoutException
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.llm4s.agent.graph.ThreadId
import org.llm4s.pekko.{ AgentStreamItem, LLMClientPekko, LLMException }
import scala.concurrent.Future

def pekkoProgram(client: LLMClient, timeout: FiniteDuration)(using system: ActorSystem): Future[String] = {
  import system.dispatcher
  val llm = LLMClientPekko(client)
  temperature.flatMap(tool => llm.agent("assistant")(_.withTools(new ToolRegistry(Seq(tool))))) match {
    case Left(error) => Future.successful(s"failed: ${error.message}")
    case Right(agent) =>
      val reply = llm.streamComplete(greeting).map(_.content.getOrElse("")).fold("")(_ + _)
      val answer = agent.stream(ThreadId("pick-your-stack"), question).collect {
        case AgentStreamItem.Done(result) => result.answer.getOrElse("")
      }
      reply
        .concatLazy(Source.lazySource(() => answer))
        .completionTimeout(timeout)
        .runWith(Sink.seq)
        .map(_.mkString(" | "))
        .recover {
          case _: TimeoutException => "timed out"
          case e: LLMException     => s"failed: ${e.error.message}"
        }
  }
}
```

## What is guaranteed to be the same

Each bridge runs the shared conformance suite,
[`StreamingBridgeContract`](https://github.com/llm4s/llm4s/blob/main/modules/llm4s-effect/src/test/contract/org/llm4s/effect/contract/StreamingBridgeContract.scala)
(`modules/llm4s-effect/src/test/contract/org/llm4s/effect/contract/StreamingBridgeContract.scala`), against its own
adapter, driven by a `ScriptedLLMClient` with no network. Its cancellation cases wait on latches, never on sleeps.

| Behaviour | Plain `Result` | cats-effect, ZIO, Pekko |
|-----------|----------------|-------------------------|
| A provider error | `Left(error)` | The same `LLMError` instance, with its message, from `complete`, `streamComplete`, agent `run` and agent `stream`; never swallowed |
| An agent's provider error | `Left(GraphError.NodeFailed(...))` from `Agent.run` | The same error core's `Agent.run` and `Agent.stream` return |
| Chunk order | The provider's | The provider's, delivered as they arrive |
| A failure mid-stream | The callback has seen the chunks, then `Left` | The chunks already received, then the error |
| Stopping early (`take(2)`) | Not applicable (a callback) | The provider stops producing chunks |
| Cancelling | Interrupt the thread: `Left(CancelledError)` | Interrupts the provider call and frees its thread |
| Agent events | `Agent.stream`'s listener | The same event sequence and the same result as core's `Agent.stream` |
| Tools | Where core's `Agent` runs them | Never on the runtime's compute pool (`io-compute`, `ZScheduler`, Pekko's `default-dispatcher`) |

## The honest differences

| | Plain `Result` | cats-effect | ZIO | Pekko |
|-|----------------|-------------|-----|-------|
| Error channel | `Left(LLMError)` | `LLMException` (field `error`) in `F`'s `Throwable` channel | `LLMError`, typed | `LLMException` failing the `Future` or the stream |
| Building an agent | `Agent.builder(...).build()`: `Result[Agent]` | `llm.agent(id)(configure)`: `F[AgentIO[F]]` | `llm.agent(id)(configure)`: `IO[LLMError, AgentZ]` | `llm.agent(id)(configure)`: `Result[AgentPekko]` |
| Timeout | No combinator in core: a virtual thread and an interrupt, `RunBudgets.withTimeout` for an agent run, or `llm4s-ox`'s `timeoutOption` | `timeout`, `timeoutTo` | `timeout` | `completionTimeout` on a stream |
| Cancelling `complete` and `run` | Interrupt the calling thread | Cancel the fiber | Interrupt the fiber | Not possible: a `Future`. Use `streamComplete` or `agent.stream` |
| When a cancel returns | At once (`FutureTask.cancel`) | Once the call or the turn has ended | Once the call or the turn has ended | At once; the stream's materialized `Future[Done]` completes once the call or turn has ended |
| Stream buffer | None (a callback) | 64 chunks, 256 agent events | 64 chunks, 256 agent events | `bufferSize`, 64 and 256 by default |

Two classes share the name `LLMException`, `org.llm4s.effect.cats.LLMException` and `org.llm4s.pekko.LLMException`,
so that neither module depends on the other; import the one of your stack. The contract found no divergence between
the bridges; the differences above are each stack's own idiom, by design.

## Adding a bridge

A new effect bridge runs `StreamingBridgeContract` before it is merged: a spec in its test sources extends
`StreamingBridgeContract("<its name>")` with an `EffectBridge` adapter that drives the bridge's own effect or stream,
and its `build.sbt` project adds the shared source set to its tests:

```text
Test / unmanagedSourceDirectories += (llm4sEffect / Test / sourceDirectory).value / "contract"
```

`CatsEffectBridgeContractSpec`, `ZioBridgeContractSpec`, `PekkoBridgeContractSpec` and `OxBridgeContractSpec` are the
four adapters to copy. A behaviour the new stack cannot offer (Pekko's `Future`, which cannot be cancelled) is
declared by the adapter (`startComplete` returns `None`) and written into the differences table above.
