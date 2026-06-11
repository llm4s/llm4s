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

`streamComplete` returns a `ZStream[Any, LLMError, StreamedChunk]`:

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
  _     <- ZIO.debug(state.conversation.messages.last.toString)
} yield ()
```

### Multi-turn conversations

```scala
for {
  s1 <- agentZ.run("What's the weather in Paris?", tools)
  s2 <- agentZ.continueConversation(s1, "And London?")
} yield s2
```

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
