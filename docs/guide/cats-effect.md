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

`streamComplete` returns an `fs2.Stream[F, StreamedChunk]` whose evaluation
runs on the blocking pool:

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
  _     <- IO.println(state.conversation.messages.last)
} yield ()
```

### Multi-turn conversations

```scala
for {
  s1 <- agentIO.run("What's the weather in Paris?", tools)
  s2 <- agentIO.continueConversation(s1, "And London?")
} yield s2
```

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
