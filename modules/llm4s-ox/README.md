# llm4s-ox

Direct-style LLM4S with SoftwareMill's [Ox](https://ox.softwaremill.com) (Experimental), on JDK 21 virtual threads.
Core's calls already return a `Result` on the calling thread and are cancelled by an interrupt; this module adds a
pull-based stream, bounded fan-out, races and retries for `LLMClient`-level code.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-ox" % llm4sVersion
```

`import org.llm4s.ox.*`:

- `client.streamFlow(conversation)`: an Ox `Flow[StreamedChunk]`. The provider call runs on a virtual thread and
  feeds a bounded channel (`BufferCapacity`); stopping the consumer interrupts it. A provider `Left` fails the flow
  with an `LLMException`. `.textDeltas` and `.accumulate: Result[Completion]` work on the flow.
- `client.parComplete(conversations, parallelism)`: one `Result` per conversation, at most `parallelism` at once.
- `raceProviders(clients*)(conversation)`: the first success; the other calls are interrupted.
- `RetrySchedule.retry(schedule)(call)`: retries what `RetryPolicy.isRetryable` retries, honouring the provider's
  retry-after; `RetrySchedule.config` is the same rule for Ox's `retryEither`.

Depends on `llm4s-core` and `com.softwaremill.ox:core` 1.x (Apache-2.0, Scala 3, JDK 21). It runs the effect bridges'
shared `StreamingBridgeContract`.

See the [guide](../../docs/guide/direct-style.md).
