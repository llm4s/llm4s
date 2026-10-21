# llm4s-effect

The cats-effect wrapper around the llm4s client and agent (`org.llm4s:llm4s-effect_3`, package `org.llm4s.effect.cats`): `LLMClientIO[F]` and `AgentIO[F]` for any `Async[F]`, with fs2 streams.

## Artifact

```scala
libraryDependencies += "org.llm4s" %% "llm4s-effect" % "<version>"
```

Replace `<version>` with the release you use (see the [installation guide](../../docs/getting-started/installation.md)). The module depends on `llm4s-core` and `llm4s-agent` and adds cats-effect 3 and fs2. A provider module (for example `llm4s-openai`) must be on the classpath too.

## Example

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

## Obtaining the client

`LLMClientIO.resource[F]` loads the default provider with `Llm4sConfig.defaultProvider()`, builds the client and releases it when the scope ends, so `llm4s.providers.provider` must name a section in your `application.conf`. If you already hold an `LLMClient`, wrap it with `LLMClientIO[F](client)`. `client.agent(id)(configure)` builds an `AgentIO[F]` from `Agent.builder(id, client)` with `configure` applied.

## Errors

Every `LLMError` is raised as an `LLMException` (field `error`) in the `F` error channel.

```scala
import org.llm4s.effect.cats.LLMException

client.complete(conversation).handleErrorWith {
  case e: LLMException => IO.println(s"LLM error: ${e.error.message}")
  case t               => IO.raiseError(t)
}
```

## Streaming

`streamComplete` returns an fs2 `Stream[F, StreamedChunk]`; stopping early or cancelling the fiber interrupts the provider call. `AgentIO` streams a turn as `Stream[F, AgentStreamItem]` with `stream`, `streamResume` and `streamRecover`:

```scala
client
  .streamComplete(conversation)
  .evalMap(chunk => IO.print(chunk.content.getOrElse("")))
  .compile
  .drain
```

## Agent

`AgentIO` offers `run`, `continueConversation`, `recover` and `resume`, plus the streaming variants above. The agent API is being reworked; the [guide](../../docs/guide/cats-effect.md) describes what it does today.

## Support tier

Beta, per [v1 scope](../../docs/reference/v1-scope.md).

## Tests

```bash
sbt llm4sEffect/test
```

## Links

- [Guide](../../docs/guide/cats-effect.md)
- Samples: [`modules/samples/.../samples/catseffect`](../samples/src/main/scala/org/llm4s/samples/catseffect)
