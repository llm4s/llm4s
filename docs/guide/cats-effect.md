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

`AgentIO[F[_]]` wraps `Agent`, shifting the blocking agent loop to the blocking pool.

```scala
val agentIO = client.agent()

for {
  state <- agentIO.run(query = "Summarise this", tools = myTools)
  _     <- IO.println(state.messages.last)
} yield ()
```

### Multi-turn conversations

```scala
for {
  s1 <- agentIO.run("What's the weather in Paris?", tools)
  s2 <- agentIO.continueConversation(s1, "And London?", tools)
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

See [CLAUDE.md](../../CLAUDE.md) for the full list of supported environment
variables (`LLM_MODEL`, `OPENAI_API_KEY`, etc.).

### Differences from `Agent`

`AgentIO` is a deliberately thin wrapper. `run` does not expose `handoffs`, and
`continueConversation` does not expose `contextWindowConfig`; the `Agent` defaults apply.
Tracing, debug logging and the trace log path are still available through the `context`
parameter (`AgentContext`). If you need handoffs or context-window pruning, call `Agent` directly
inside `Async[F].blocking`.

Tool calls are not a failure of the effect. When the model calls a tool with arguments that do not
fit the tool's schema, `Agent` hands a structured error result back to the model so it
can correct itself; the run continues and only the step limit or a provider error ends it, which
then arrives in the error channel as usual. If you need to see those results, read the
`ToolMessage`s in the returned `AgentThread`. The schema-validated `AgentTool` contract and the
graph runtime (`org.llm4s.agent.graph`) are experimental and are not wrapped here; if you pass
handoffs by calling `Agent` directly, each `Handoff` needs a stable id, as in
`Handoff.to("physics", agent)`.
