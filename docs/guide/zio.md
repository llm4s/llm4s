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

`AgentZ` wraps an `Agent`, shifting the blocking agent loop to ZIO's blocking pool. `client.agent(id)(configure)` builds the agent from `Agent.builder(id, client)` with `configure` applied, so tools, guardrails, handoffs and middleware are set there; a builder that does not build fails with its `LLMError`.
`LLMError` is the native error type — no wrapping needed.

```scala
for {
  agentZ <- client.agent("assistant")(_.withTools(myTools).withSystemPrompt("You are concise."))
  result <- agentZ.run("Summarise this")
  _      <- ZIO.debug(result.answer.toString)
} yield ()
```

### Multi-turn conversations

```scala
for {
  s1 <- agentZ.run("What's the weather in Paris?")
  s2 <- agentZ.continueConversation(s1, "And London?")
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

The variables llm4s reads, such as `OPENAI_API_KEY`, are listed in
[Environment variables llm4s reads](../getting-started/configuration#environment-variables-llm4s-reads).
The provider and model are chosen by a named section in `application.conf`; see
[Named provider sections](../getting-started/configuration#named-provider-sections).

### Differences from `Agent`

`AgentZ` is a deliberately thin wrapper over `Agent.start` and `AgentRun.await`. It exposes `run`,
`continueConversation`, `recover` and `resume`, each taking a `RunConfig` and returning
`ZIO[Any, LLMError, AgentResult]`. It does not expose a named `ThreadId` on `run` (each `run` starts a
new thread, and `continueConversation` continues the thread of a previous result) or `history`; for
those, build the `Agent` yourself and call `agent.start(...)` inside `ZIO.attemptBlocking`.

Interrupting the fiber (or a timeout) cancels the run and returns once its turn has ended, so the
thread can be recovered at once with `recover`.

Errors arrive in the `LLMError` channel: a provider, tool or middleware failure is a `GraphError`
(a provider error is `GraphError.NodeFailed(cause)`; an exception thrown by user code arrives as a
`NodeFailed` carrying the original). A guardrail block, the step limit and a suspension for approval
are not failures: they are the `AgentResult`'s `status`.

Tool calls are not a failure of the effect either. When the model calls a tool with arguments that do not
fit the tool's schema, the agent hands a structured error result (`{"error": ...}`) back to the model so it
can correct itself; the run continues and only the step limit or a provider error ends it. If you
need to see those results, read the `ToolMessage`s in `AgentResult.messages`.
