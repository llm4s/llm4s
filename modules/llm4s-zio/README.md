# llm4s-zio

The ZIO wrapper around the llm4s client and agent (`org.llm4s:llm4s-zio_3`, package `org.llm4s.zio`): `LLMClientZ` and `AgentZ`, with ZIO Streams.

## Artifact

```scala
libraryDependencies += "org.llm4s" %% "llm4s-zio" % "<version>"
```

Replace `<version>` with the release you use (see the [installation guide](../../docs/getting-started/installation.md)). The module depends on `llm4s-core` and `llm4s-agent` and adds ZIO 2 and ZIO Streams. A provider module (for example `llm4s-openai`) must be on the classpath too.

## Example

```scala
import org.llm4s.llmconnect.model.{Conversation, UserMessage}
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

## Obtaining the client

`LLMClientZ.layer` loads the default provider with `Llm4sConfig.defaultProvider()`, builds the client and releases it when the scope ends, so `llm4s.providers.provider` must name a section in your `application.conf`. If you already hold an `LLMClient`, wrap it with `LLMClientZ(client)`. `client.agent(id)(configure)` builds an `AgentZ` from `Agent.builder(id, client)` with `configure` applied.

## Errors

`LLMError` stays in the ZIO error channel; nothing is wrapped.

```scala
client.complete(conversation).catchAll { err =>
  ZIO.debug(s"LLM error: ${err.message}") *> ZIO.fail(err)
}
```

## Streaming

`streamComplete` returns a `ZStream[Any, LLMError, StreamedChunk]`; stopping early or interrupting the fiber interrupts the provider call. `AgentZ` streams a turn as a `ZStream[Any, LLMError, AgentStreamItem]` with `stream`, `streamResume` and `streamRecover`:

```scala
client
  .streamComplete(conversation)
  .map(_.content.getOrElse(""))
  .runCollect
  .map(_.mkString)
```

## Agent

`AgentZ` offers `run`, `continueConversation`, `recover` and `resume`, plus the streaming variants above. The agent API is being reworked; the [guide](../../docs/guide/zio.md) describes what it does today.

## Support tier

Beta, per [v1 scope](../../docs/reference/v1-scope.md).

## Tests

```bash
sbt llm4sZio/test
```

## Links

- [Guide](../../docs/guide/zio.md)
- Samples: [`modules/samples/.../samples/zio`](../samples/src/main/scala/org/llm4s/samples/zio)
