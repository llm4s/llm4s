---
layout: page
title: Apache Pekko Integration
parent: User Guide
nav_order: 16
---

# Apache Pekko Integration

The `llm4s-pekko` module (Beta) exposes `LLMClient.streamComplete` and an `Agent` run as
[Apache Pekko Streams](https://pekko.apache.org/docs/pekko/current/stream/index.html) `Source`s, so LLM output plugs into
a Pekko, Pekko HTTP or Play pipeline with the backpressure and cancellation you already use. It is the third streaming
bridge, next to [cats-effect](cats-effect) (fs2) and [ZIO](zio) (ZStream), and it has the same shape.

## Dependency

```scala
// build.sbt
libraryDependencies += "org.llm4s" %% "llm4s-pekko" % "<version>"
```

The module depends on `llm4s-core`, `llm4s-agent` and `pekko-stream` 1.x (Apache-2.0). Add a provider module as for
any llm4s application. Akka is not supported: its licence is not Apache-2.0, and the module depends only on Pekko.

## LLMClientPekko

`LLMClientPekko` wraps an `LLMClient`. It does not manage the client's lifecycle; build the client as the
[configuration guide](../getting-started/configuration) describes and close it when you are done.

```scala
import org.llm4s.llmconnect.LLMClient
import org.llm4s.pekko.LLMClientPekko

def wrap(client: LLMClient): LLMClientPekko = LLMClientPekko(client)
```

### Streaming

`streamComplete` returns a `Source[StreamedChunk, NotUsed]` of the provider's chunks, as they arrive:

```scala
import org.apache.pekko.actor.ActorSystem
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }

def printAnswer(client: LLMClientPekko)(using system: ActorSystem) =
  client
    .streamComplete(Conversation(Seq(UserMessage("Explain monads in one sentence."))))
    .runForeach(chunk => print(chunk.content.getOrElse("")))
```

The provider call starts when the stream is materialized, once per materialization, on a thread of its own: a
blocking call never runs on a stream or actor dispatcher thread.

 - **Backpressure.** The provider calls back on its own thread and cannot be paused, so the stream blocks that thread
   while `bufferSize` chunks (64 unless you pass another) wait for the consumer. Nothing is dropped, and no more than
   `bufferSize` chunks are held.
 - **Cancellation.** Cancelling the stream, or stopping it early with `take`, interrupts the provider's thread, which is how
   llm4s providers are cancelled: they keep the interrupt and return `Left(CancelledError)`. A kill switch works as well:

   ```scala
   import org.apache.pekko.stream.KillSwitches
   import org.apache.pekko.stream.scaladsl.{ Keep, Sink }
   import org.llm4s.llmconnect.model.StreamedChunk

   def cancellable(client: LLMClientPekko, conversation: Conversation)(using system: ActorSystem) =
     client
       .streamComplete(conversation)
       .viaMat(KillSwitches.single[StreamedChunk])(Keep.right)
       .toMat(Sink.foreach(chunk => print(chunk.content.getOrElse(""))))(Keep.both)
       .run()
   // later: killSwitch.shutdown() interrupts the provider call
   ```

 - **Errors.** If the call fails part-way, the chunks already received are emitted first, then the stream fails with an
   `LLMException` that carries the `LLMError`. A `bufferSize` below 1 fails the stream at once.

### complete as a Future

```scala
import org.llm4s.llmconnect.model.Completion
import scala.concurrent.{ ExecutionContext, Future }

def complete(client: LLMClientPekko, conversation: Conversation)(using ec: ExecutionContext): Future[Completion] =
  client.complete(conversation)
```

The call blocks, so pass a blocking `ExecutionContext`, for example
`system.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)`. A `Future` cannot be cancelled; use
`streamComplete` when the call has to be. A provider error fails the `Future` with an `LLMException`.

## AgentPekko

`LLMClientPekko.agent(id)(configure)` builds an `AgentPekko` from `Agent.builder(id, client)` with `configure`
applied, and returns a `Result` (a builder that does not build is a `Left`). `AgentPekko(agent)` wraps an agent you built
yourself, with its tools, guardrails and handoffs.

```scala
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.pekko.{ AgentPekko, AgentStreamItem }

def streamAnswer(agent: AgentPekko)(using system: ActorSystem) =
  agent
    .stream(ThreadId("chat-1"), "What is the capital of France?")
    .runForeach {
      case AgentStreamItem.Event(AgentEvents.TextDelta(delta)) => print(delta.text)
      case AgentStreamItem.Done(result)                        => println(s"\nDone: ${result.answer}")
      case _                                                   => ()
    }
```

`stream` emits every event of the turn (`AgentStreamItem.Event`), then the result (`AgentStreamItem.Done`).
`streamResume` and `streamRecover` do the same for `Agent.resume` and `Agent.recover`.

 - The turn starts when the stream is materialized, once per materialization.
 - Cancelling the stream, or stopping it early with `take`, cancels the turn: its model call and tool calls are
   interrupted, and the thread is left for `recover`.
 - A slow consumer never holds the run up. It loses live events (text deltas, tool progress) and receives one
   `StreamEvent.LiveGap` with their count where they were dropped. Durable events are never dropped. `bufferSize` (256
   unless you pass another) is how many live events wait before the rest are counted.
 - A refused start (a blank query, a busy thread), a failed turn and a subscription that disconnects fail the stream
   with an `LLMException`. A turn that commits no terminal event still ends the stream.

### Futures

`run`, `continueConversation`, `recover` and `resume` return a `Future[AgentResult]`, started and awaited on the
`ExecutionContext` you pass:

```scala
import org.llm4s.agent.AgentResult

def ask(agent: AgentPekko, question: String)(using ec: ExecutionContext): Future[AgentResult] =
  agent.run(question)
```

A `Future` cannot be cancelled. Stream the turn when it has to be cancellable.

## Errors

Every `LLMError` reaches you as an `LLMException`, the failure of the stream or the `Future`; its `error` field is the
`LLMError`:

```scala
import org.apache.pekko.stream.scaladsl.Sink
import org.llm4s.pekko.LLMException
import scala.concurrent.ExecutionContext

def answerOrNothing(client: LLMClientPekko, conversation: Conversation)(using system: ActorSystem, ec: ExecutionContext) =
  client
    .streamComplete(conversation)
    .runWith(Sink.seq)
    .map(chunks => chunks.flatMap(_.content).mkString)
    .recover { case e: LLMException => s"failed: ${e.error.message}" }
```

An agent turn that fails with a provider error reports it wrapped in a `GraphError.NodeFailed`, as with the other
bridges; the provider's error is its `cause`.

## Differences from `AgentIO` and `AgentZ`

`AgentPekko` is a thin wrapper over `Agent`, built on the same internals as the fs2 and ZIO bridges, so a turn behaves the
same way. What differs is the platform:

 - Pekko has no resource or layer type, so there is no `LLMClientPekko.resource`: build and close the `LLMClient` yourself.
 - `run` and the other `Future` methods cannot be cancelled; the streams can.
 - Pekko HTTP routes and Play controllers are not part of the module. Serve a stream by mapping it to your framework's
   response type, for example `Source[ServerSentEvent, NotUsed]` in Pekko HTTP.
