---
layout: page
title: ZIO Integration
parent: User Guide
nav_order: 15
---

# ZIO Integration

The `llm4s-zio` module wraps the synchronous `LLMClient` and `Agent` APIs in
[ZIO](https://zio.dev) effects, shifting blocking LLM calls to ZIO's blocking
thread pool and surfacing `LLMError` directly in the ZIO error channel.

## Dependency

```scala
// build.sbt
libraryDependencies += "org.llm4s" %% "llm4s-zio" % "<version>"
```

## LLMClientZ

`LLMClientZ` is the ZIO wrapper for `LLMClient`.

### Acquire via ZLayer

Use `LLMClientZ.layer` to load provider config from the environment, build the
client, and release it on scope exit:

```scala
import org.llm4s.agent.AgentContext
import org.llm4s.llmconnect.model.{Conversation, UserMessage}
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.zio.LLMClientZ
import zio.{ZIO, ZIOAppDefault}

object MyApp extends ZIOAppDefault {
  def run: ZIO[Any, Any, Any] =
    (for {
      client <- ZIO.service[LLMClientZ]
      c      <- client.complete(Conversation(Seq(UserMessage("What is 2 + 2?"))))
      _      <- ZIO.debug(c.content)
    } yield ()).provide(LLMClientZ.layer)
}
```

### Wrap an existing client

```scala
import org.llm4s.zio.LLMClientZ

val wrapped: LLMClientZ = LLMClientZ(existingClient)
```

### Streaming

`streamComplete` returns a `ZStream[Any, LLMError, StreamedChunk]` that delivers chunks
incrementally. The blocking provider call runs on an interruptible blocking thread and feeds a
bounded queue, so a slow consumer applies backpressure, and stopping early or interrupting the
fiber interrupts the call. If the call fails mid-stream, chunks already received are emitted
first and the stream then fails with the `LLMError`:

```scala
client
  .streamComplete(conversation)
  .map(_.content.getOrElse(""))
  .runCollect
  .map(_.mkString)
```

## AgentZ

`AgentZ` wraps `Agent`, shifting the blocking agent loop to ZIO's blocking pool.
`LLMError` is the native error type — no wrapping needed.

```scala
val agentZ = client.agent()

for {
  state <- agentZ.run(query = "Summarise this", tools = myTools)
  _     <- ZIO.debug(state.messages.last.toString)
} yield ()
```

### Multi-turn conversations

```scala
for {
  s1 <- agentZ.run("What's the weather in Paris?", tools)
  s2 <- agentZ.continueConversation(s1, "And London?", tools)
} yield s2
```

## Cancellation

Cancellation is by thread interrupt, matching the llm4s core contract. `LLMClientZ.complete`, `AgentZ` and the streaming
methods all run the provider call on an interruptible blocking thread, so cancelling the fiber (or
a timeout) interrupts the call instead of waiting for it to finish.

## Error handling

`LLMError` flows naturally in the ZIO error channel:

```scala
client.complete(conversation).catchAll { err =>
  ZIO.debug(s"LLM error: ${err.message}") *> ZIO.fail(err)
}
```

## Environment variables

See [CLAUDE.md](../../CLAUDE.md) for the full list of supported environment
variables (`LLM_MODEL`, `OPENAI_API_KEY`, etc.).

### Differences from `Agent`

`AgentZ` is a deliberately thin wrapper. `run` does not expose `handoffs`, and
`continueConversation` does not expose `contextWindowConfig`; the `Agent` defaults apply.
Tracing, debug logging and the trace log path are still available through the `context`
parameter (`AgentContext`). If you need handoffs or context-window pruning, call `Agent` directly
inside `ZIO.attemptBlocking`.

Tool calls are not a failure of the effect. When the model calls a tool with arguments that do not
fit the tool's schema, `Agent` hands a structured error result back to the model so it
can correct itself; the run continues and only the step limit or a provider error ends it, which
then arrives in the error channel as usual. If you need to see those results, read the
`ToolMessage`s in the returned `AgentThread`. The schema-validated `AgentTool` contract and the
graph runtime (`org.llm4s.agent.graph`) are experimental and are not wrapped here; if you pass
handoffs by calling `Agent` directly, each `Handoff` needs a stable id, as in
`Handoff.to("physics", agent)`.
