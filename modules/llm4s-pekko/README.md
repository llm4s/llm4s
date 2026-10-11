# llm4s-pekko

Apache Pekko Streams for LLM4S (Beta): `LLMClient.streamComplete` and `Agent` runs as `Source`s, with
backpressure and cancellation. It is the Pekko counterpart of `llm4s-effect` (fs2) and `llm4s-zio` (ZStream).

```scala
libraryDependencies += "org.llm4s" %% "llm4s-pekko" % llm4sVersion
```

 - `LLMClientPekko(client)`: `streamComplete(conversation)` is a `Source[StreamedChunk, NotUsed]`, `complete` a `Future`,
   `agent(id)(configure)` an `AgentPekko`.
 - `AgentPekko(agent)`: `stream`, `streamResume` and `streamRecover` are `Source[AgentStreamItem, NotUsed]` (each event of the
   turn, then its result); `run`, `continueConversation`, `recover` and `resume` are `Future`s.

The provider call blocks, so each stream runs it on a daemon thread of its own and hands items to Pekko through a bounded
buffer: when the consumer lags, the provider's thread is blocked; when the consumer cancels, it is interrupted. An
`LLMError` is carried by an `LLMException`. Depends on `llm4s-core`, `llm4s-agent` and `pekko-stream` 1.x (Apache-2.0).

See the [guide](../../docs/guide/pekko.md) for the semantics and examples.
