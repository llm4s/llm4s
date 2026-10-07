---
layout: page
title: cats-effect Integration
parent: User Guide
nav_order: 14
---

# cats-effect Integration

The `llm4s-effect` module wraps the synchronous `LLMClient` and `Agent` APIs in
[cats-effect](https://typelevel.org/cats-effect/) `IO` (or any `Async[F]` type),
keeping the compute thread pool free while long-running LLM calls block on the
dedicated blocking pool.

## Dependency

```scala
// build.sbt
libraryDependencies += "org.llm4s" %% "llm4s-effect" % "<version>"
```

## LLMClientIO

`LLMClientIO[F[_]]` is the cats-effect wrapper for `LLMClient`.

### Acquire from the environment

Use `LLMClientIO.resource[F]` to load provider config from the environment,
build the client, and release it on scope exit:

```scala
import cats.effect.{IO, IOApp}
import org.llm4s.effect.cats.LLMClientIO
import org.llm4s.llmconnect.model.{Conversation, UserMessage}

object MyApp extends IOApp.Simple {
  def run: IO[Unit] =
    LLMClientIO.resource[IO].use { client =>
      for {
        completion <- client.complete(Conversation(Seq(UserMessage("What is 2 + 2?"))))
        _          <- IO.println(completion.content)
      } yield ()
    }
}
```

### Wrap an existing client

If you already hold an `LLMClient` (e.g. constructed manually):

```scala
import org.llm4s.effect.cats.LLMClientIO

val wrapped = LLMClientIO[IO](existingClient)
```

### Streaming

`streamComplete` returns an `fs2.Stream[F, StreamedChunk]` that delivers chunks incrementally.
The blocking provider call runs on an interruptible blocking thread and feeds a bounded queue,
so a slow consumer applies backpressure to the provider thread, and stopping early (`take`,
fiber cancellation) interrupts the call. If the call fails mid-stream, chunks already received
are emitted first and the stream then fails with `LLMException`:

```scala
client
  .streamComplete(conversation)
  .evalMap(chunk => IO.print(chunk.content.getOrElse("")))
  .compile
  .drain
```

## AgentIO

`AgentIO[F[_]]` wraps an `Agent`, shifting the blocking agent loop to the blocking pool. `client.agent(id)(configure)` builds the agent from `Agent.builder(id, client)` with `configure` applied, so tools, guardrails, handoffs and middleware are set there; a builder that does not build fails the effect with its error.

```scala
for {
  agentIO <- client.agent("assistant")(_.withTools(myTools).withSystemPrompt("You are concise."))
  result  <- agentIO.run("Summarise this")
  _       <- IO.println(result.answer)
} yield ()
```

### Multi-turn conversations

```scala
for {
  s1 <- agentIO.run("What's the weather in Paris?")
  s2 <- agentIO.continueConversation(s1, "And London?")
} yield s2
```

## Cancellation

Cancellation is by thread interrupt, matching the llm4s core contract. `LLMClientIO.complete`, `AgentIO` and the streaming
methods all run the provider call on an interruptible blocking thread, so cancelling the fiber (or
a timeout) interrupts the call instead of waiting for it to finish.

## Error handling

All `LLMError` values are raised as `LLMException` in the `F` error channel:

```scala
import org.llm4s.effect.cats.LLMException

client.complete(conversation).handleErrorWith {
  case e: LLMException => IO.println(s"LLM error: ${e.error.message}")
  case t               => IO.raiseError(t)
}
```

## Environment variables

The variables llm4s reads, such as `OPENAI_API_KEY`, are listed in
[Environment variables llm4s reads](../getting-started/configuration#environment-variables-llm4s-reads).
The provider and model are chosen by a named section in `application.conf`; see
[Named provider sections](../getting-started/configuration#named-provider-sections).

### Differences from `Agent`

`AgentIO` is a deliberately thin wrapper over `Agent.start` and `AgentRun.await`. It exposes `run`,
`continueConversation`, `recover` and `resume`, each taking a `RunConfig` and returning an `F[AgentResult]`.
It does not expose a named `ThreadId` on `run` (each `run` starts a new thread, and
`continueConversation` continues the thread of a previous result) or `history`; for those, build the
`Agent` yourself and call `agent.start(...)` inside `IO.blocking`.

Cancelling the fiber cancels the run and returns once its turn has ended, so the thread can be
recovered at once with `recover`.

Errors arrive as `LLMException` in the effect's error channel: a provider, tool or middleware failure
is a `GraphError` (a provider error is `GraphError.NodeFailed(cause)`; an exception thrown by user
code arrives as a `NodeFailed` carrying the original). A guardrail block, the step limit and a
suspension for approval are not failures: they are the `AgentResult`'s `status`.

Tool calls are not a failure of the effect either. When the model calls a tool with arguments that do not
fit the tool's schema, the agent hands a structured error result (`{"error": ...}`) back to the model so it
can correct itself; the run continues and only the step limit or a provider error ends it. If you
need to see those results, read the `ToolMessage`s in `AgentResult.messages`.
