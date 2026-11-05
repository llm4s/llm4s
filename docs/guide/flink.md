---
layout: page
title: Apache Flink
description: "Enrich an Apache Flink 2.x stream with LLM calls from Scala 3: an async function per task, timeouts and capacity, and failed calls sent to a dead-letter side output."
search_title: "Call LLMs from Apache Flink 2.x with LLM4S"
parent: User Guide
nav_order: 42
---

# Using LLM4S from Apache Flink
{: .no_toc }

Enrich a Flink stream with LLM calls from a Scala 3 job: `AsyncDataStream` with a `RichAsyncFunction` that builds its
client in `open()`, a timeout and capacity sized to the provider, and failed calls sent to a dead-letter side output
instead of failing the job.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

**Verified: 2026-11-05**, with llm4s 0.5.1. The example job in
[`modules/samples/flink`](https://github.com/llm4s/llm4s/tree/main/modules/samples/flink) was run with its scripted
model (no key, no network) and, on a cluster, with its configured provider:

| Flink | Scala | JDK | Where | Result |
|---|---|---|---|---|
| 2.3.0 | 3.7.1 | 21 | MiniCluster test (`scala-cli test`), also in CI | passes |
| 2.3.0 | 3.7.1 | 21 | standalone cluster from the binary distribution, `flink run` | runs once `lib/flink-scala_2.12-2.3.0.jar` is removed ([why](#the-scala-library-on-the-cluster)) |
| 2.3.0 | 3.7.1 | 21 | the same cluster, without `--scripted` and with no Ollama running | the bundled `application.conf` and the Ollama module load on the task manager; every ticket is dead-lettered (connection refused, then the local rate limit), the job finishes |

Flink's [Java compatibility page](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/deployment/java_compatibility/)
calls Java 21 support experimental (added in 2.0.0); both runs above used JDK 21, the JDK LLM4S requires.

## Flink 2 and Scala 3

Flink 2.0 removed the Scala DataStream and DataSet APIs: the
[2.0 release notes](https://nightlies.apache.org/flink/flink-docs-release-2.3/release-notes/flink-2.0/) say "Please
migrate to the Java DataStream API". A Scala job now calls the Java DataStream API, which has no Scala in its
signatures, so it can be written in Scala 3, and so can call LLM4S, which is published for Scala 3 only. Two things
the Java API leaves to you:

- **Types.** Flink's type extraction does not know Scala case classes and treats them as generic types, serialised
  with Kryo. That works (the example's records are case classes) but is slower than Flink's own serialisers and gives
  no schema evolution for state; pass a `TypeInformation` where Flink cannot infer one (`fromData`, `process`,
  `OutputTag`), as the example does.
- **Functions.** Implement Flink's function classes (`RichAsyncFunction`, `ProcessFunction`) as Scala classes. A Scala
  function value captured in one is serialisable, so it can be a field.

## Dependencies

The example is a [Scala CLI](https://scala-cli.virtuslab.org/) project. Its dependencies:

```scala
//> using scala 3.7.1
//> using jvm 21
//> using dep org.apache.flink:flink-streaming-java:2.3.0
//> using dep org.apache.flink:flink-clients:2.3.0
//> using dep org.llm4s::llm4s-core:{{ site.data.project.latest_release }}
//> using dep org.llm4s::llm4s-ollama:{{ site.data.project.latest_release }}
// The scripted model behind `--scripted` and the test; a real job leaves this out.
//> using dep org.llm4s::llm4s-testkit:{{ site.data.project.latest_release }}
//> using test.dep org.apache.flink:flink-test-utils:2.3.0
//> using test.dep org.scalameta::munit:1.3.6
```

The same in sbt, for a job built into one jar with sbt-assembly:

```scala
// build.sbt of the Flink job
scalaVersion := "3.7.1"

libraryDependencies ++= Seq(
  "org.apache.flink" % "flink-streaming-java" % "2.3.0" % Provided,
  "org.apache.flink" % "flink-clients"        % "2.3.0" % Provided,
  "org.llm4s"       %% "llm4s-core"           % "{{ site.data.project.latest_release }}",
  "org.llm4s"       %% "llm4s-ollama"         % "{{ site.data.project.latest_release }}" // the provider module you call
)
```

The Flink artifacts are `Provided`: the cluster has them. The job's jar carries LLM4S, its dependencies and the Scala
standard library (`scala3-library_3` and `scala-library` 2.13.16). The verified jar was built with
`scala-cli --power package . --assembly --preamble=false --provided org.apache.flink:flink-streaming-java --provided org.apache.flink:flink-clients`.

### The Scala library on the cluster

Flink 2.3.0's binary distribution still ships `lib/flink-scala_2.12-2.3.0.jar`, which contains the Scala 2.12 <!-- doc-support: ignore -->
standard library, and Flink loads `scala.*` classes parent-first (`classloader.parent-first-patterns.default`), so
the cluster's copy wins over the job's. Submitted to an unmodified cluster, the example fails before the job starts:

```
java.lang.ClassCastException: class scala.collection.immutable.$colon$colon cannot be cast to class scala.collection.SeqOps
```

With that one jar removed from `lib/`, the same jar ran and printed the output [below](#run-the-example). Only this
job was run without it: check that no other job on the cluster needs it. On a managed Flink service that does not let
you change `lib/`, check whether its image ships that jar before choosing Scala 3.

### Dependency conflicts: what was checked

Resolving `flink-streaming-java` and `flink-clients` 2.3.0 with `llm4s-core_3` and `llm4s-ollama_3` 0.5.1 (coursier):

- **Scala**: Flink's Java artifacts bring no `scala-library`; LLM4S brings 2.13.16 and `scala3-library_3`. <!-- doc-support: ignore -->
- **Jackson**: none in common. Flink uses its own shaded copy (`flink-shaded-jackson`); LLM4S core and the Ollama
  module use uPickle.
- **SLF4J**: Flink 2.3.0 depends on `slf4j-api` 1.7.36, LLM4S on 2.0.17; a local run resolves 2.0.17. On the
  cluster, `org.slf4j` is parent-first, so the distribution's SLF4J 1.7 API (in `flink-dist`) and its Log4j 2
  binding win over the job's copy; the cluster runs above worked with them. Locally, SLF4J 2.0.17 ignores the SLF4J 1 binding `flink-test-utils` brings
  (`log4j-slf4j-impl`) and logs nothing; add `org.apache.logging.log4j:log4j-slf4j2-impl` to see Flink's logs.
- **Kryo** (5.6.2) and the Apache Commons libraries come from Flink only.

## The async function

The full job is
[`EnrichTickets.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/flink/EnrichTickets.scala). It
classifies support tickets with one call each:

```scala
final class ClassifyTicket(newClient: () => Result[LLMClient], threads: Int)
    extends RichAsyncFunction[Ticket, Outcome] {

  @transient private var client: LLMClient         = scala.compiletime.uninitialized
  @transient private var executor: ExecutorService = scala.compiletime.uninitialized

  override def open(context: OpenContext): Unit = {
    // A configuration error (no key, no provider module) fails the job at start, not every record one by one.
    client = newClient().fold(e => throw new IllegalStateException(s"no LLM client: ${e.message}"), identity)
    // `complete` blocks, so it runs here, never on the task thread. One thread per request the operator may
    // have in flight (its capacity): fewer would queue requests, and the queueing would count against the timeout.
    executor = Executors.newFixedThreadPool(threads)
  }

  override def asyncInvoke(ticket: Ticket, result: ResultFuture[Outcome]): Unit =
    CompletableFuture
      .supplyAsync(() => classify(ticket), executor)
      .whenComplete { (outcome, thrown) =>
        val emitted = if (thrown == null) outcome else DeadLetter(ticket, s"call threw: $thrown")
        result.complete(Collections.singletonList(emitted))
      }

  // Called by Flink when a request outlives the operator's timeout. The default fails the job; this dead-letters
  // the record instead. A late answer for the same record is ignored: a ResultFuture completes once.
  override def timeout(ticket: Ticket, result: ResultFuture[Outcome]): Unit =
    result.complete(Collections.singletonList(DeadLetter(ticket, "timed out")))

  override def close(): Unit = {
    if (executor != null) executor.shutdownNow()
    if (client != null) client.close()
  }
```

### Client lifecycle

- **Build the client in `open()`, not in the job's `main`.** Flink serialises each function and ships it to the
  task managers; an `LLMClient` holds an HTTP client and is not serialisable. The function holds a factory instead
  (`() => Result[LLMClient]`, a serialisable Scala function), and `open()` calls it once per parallel instance.
- **Close it in `close()`**, with the executor. Flink calls `close()` when the task ends or fails over.
- **Configuration is read on the task manager.** `Llm4sConfig.defaultProvider()` reads the task manager's
  `application.conf` (bundle it in the job's jar) and system properties, and the provider's key from its
  conventional environment variable (`OPENAI_API_KEY`, ...), which must be set on the task managers.
- **A missing key fails the job in `open()`.** Every record would fail the same way, so the job stops at start
  rather than dead-lettering the whole stream.

### Why `AsyncDataStream`

A blocking call in a `MapFunction` holds the task thread for the whole model call, so one instance makes one call at
a time and backpressure reaches the source. The async operator keeps up to `capacity` requests in flight per
instance. `asyncInvoke` must return at once: `complete` blocks, so it runs on the function's own executor.

```scala
val outcomes = AsyncDataStream.unorderedWait(
  tickets,
  new ClassifyTicket(newClient, threads = Capacity),
  timeout.toMillis,
  TimeUnit.MILLISECONDS,
  Capacity
)
```

`unorderedWait` emits each result as soon as it is ready; `orderedWait` keeps input order, at the cost of holding
fast results behind slow ones.

### Errors: a dead-letter side output

`complete` returns a `Result`, so a failed call is a value. The function emits one `Outcome` per ticket, `Enriched`
or `DeadLetter`, and never fails the job for one record: a provider error, an answer that is not a category, an
exception and a timeout all become a `DeadLetter`. An `AsyncFunction` cannot write to a side output itself (a
`ResultFuture` only completes the main output), so a `ProcessFunction` after it routes the dead letters:

```scala
final class RouteOutcomes extends ProcessFunction[Outcome, Enriched] {
  override def processElement(
    outcome: Outcome,
    context: ProcessFunction[Outcome, Enriched]#Context,
    out: Collector[Enriched]
  ): Unit =
    outcome match {
      case enriched: Enriched => out.collect(enriched)
      case dead: DeadLetter   => context.output(EnrichTickets.DeadLetters, dead)
    }
}
```

`routed.getSideOutput(DeadLetters)` is then a stream of its own, for a dead-letter topic or table. Overriding
`timeout` matters: Flink's default `timeout` completes the request with a `TimeoutException`, which fails the task;
under a restart strategy the job restarts from the last checkpoint and sends every in-flight request again.

## Timeouts, capacity and rate limits

The example's settings:

```scala
retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 500.millis),
rateLimit = RateLimitConfig(enabled = true, requestsPerMinute = 60, burstCapacity = Capacity),
deadline = Some(30.seconds)
```

and a Flink timeout of 40 seconds with a capacity of 4. They have to agree:

- **Concurrency.** Each parallel instance has up to `capacity` requests in flight, so the job makes up to
  `capacity` x parallelism concurrent calls. Give the executor as many threads as `capacity`: with fewer, requests
  wait for a thread, and the wait counts against Flink's timeout.
- **Rate limit.** `ReliableClient`'s token bucket is per client, so per parallel instance: the job's total is
  `requestsPerMinute` x parallelism. Divide the provider's limit by the parallelism. Every attempt, retries
  included, takes a token. An attempt that finds the bucket empty is retried when the next token is due, as one of
  its `maxAttempts`; when those run out the call ends as a `RateLimitError` and the ticket is dead-lettered. Keep
  `capacity` at or below `burstCapacity`, as the example does, so the requests one instance has in flight fit in one
  burst.
- **Retries.** Retry in one place. `ReliableClient` knows which LLM errors are worth retrying (rate limits,
  timeouts, network errors, 5xx, 408 and 429); Flink's `unorderedWaitWithRetry` with an `AsyncRetryStrategy`
  retries the whole request on a predicate you write. The example uses `ReliableClient`.
- **Timeout.** Flink's timeout counts from `asyncInvoke` and covers every retry, so make it longer than the
  client's `deadline` (40 seconds against 30 above); otherwise Flink gives up while the client is still retrying. A
  timed-out call is not cancelled: its thread runs until the client returns.

## Checkpointing

The async operator stores the records of in-flight requests in each checkpoint, and after a failure it calls
`asyncInvoke` for them again ([Flink's async I/O guide](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/dev/datastream/operators/asyncio/)).
For an LLM call that means:

- **A request may be sent twice**: once before the failure, again after the restore. Each is billed, and the
  second may get a different answer. Results are emitted once per checkpointed record; calls are at least once.
- **A checkpoint waits for nothing**: in-flight requests are stored, not drained, so slow model calls do not delay
  checkpoints, but up to `capacity` x parallelism requests are repeated after a restore.

## Testing

The example's test, [`EnrichTickets.test.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/flink/EnrichTickets.test.scala),
runs the job on Flink's MiniCluster (`flink-test-utils`' `MiniClusterWithClientResource`) with `llm4s-testkit`'s
`ScriptedLLMClient` ([testing with the testkit](testing-with-the-testkit)). The scripted client goes in through the same factory, so it is built
in `open()` on the MiniCluster's task, as a real one would be:

```scala
val (enriched, dead) = run(EnrichTickets.SampleTickets, () => EnrichTickets.scriptedClient(), 10.seconds)
```

It checks that every ticket is enriched or dead-lettered, that a call slower than the timeout is dead-lettered and
the job still finishes, that a client that cannot be built fails the job in `open()`, and that the function
serialises without building a client. CI runs it on every change to the example or this page, with no API key
(`.github/workflows/flink-sample.yml`), and checks that this page's Scala snippets are excerpts of the example.

## Run the example

```bash
cd modules/samples/flink
scala-cli test .
scala-cli run . -- --scripted
```

`--scripted` uses a scripted model that names a category for tickets about a charge, a crash or a password, fails
the one containing "boom", and gives an answer that is not a category for anything else. One line per ticket, in
any order:

```
enriched> Enriched(1,I was charged twice this month,billing)
enriched> Enriched(2,The app crashes when I open settings,bug)
enriched> Enriched(4,I forgot my password,account)
dead-letter> DeadLetter(Ticket(3,boom: the scripted model fails on this one),Service error from scripted: the scripted model failed (HTTP 500))
dead-letter> DeadLetter(Ticket(5,What is the meaning of life?),unexpected answer: no idea)
```

Without `--scripted`, the job calls the provider that `resources/application.conf`, bundled in the job's jar, names
in `llm4s.providers.provider`: a local Ollama (`ollama serve`, `ollama pull llama3`). To call another provider, change
that section and add its module ([configuration](../getting-started/configuration)).
