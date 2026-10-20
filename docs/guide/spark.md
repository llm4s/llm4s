---
layout: page
title: Spark and Databricks
parent: User Guide
nav_order: 31
---

# Using LLM4S from Spark and Databricks
{: .no_toc }

Enrich a DataFrame with LLM calls from a Spark job: one client per partition, retries and a rate limit from
`ReliableClient`, and failed calls kept as data instead of failing the stage.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

**Verified: 2026-10-21**, with llm4s 0.5.1. The example job in
[`modules/samples/spark`](https://github.com/llm4s/llm4s/tree/main/modules/samples/spark) was run in Spark local mode
(`local[2]`) against a local fake model:

| Spark | Scala | JDK | Result |
|---|---|---|---|
| 4.0.1 | 2.13.16 | 21 and 17 | runs, enriched rows below |
| 3.5.9 | 2.13.16 (see [Spark 3.5](#spark-35-and-databricks-164-lts)) | 21 | runs locally |

Not verified on a Databricks cluster. What the Databricks release notes say about the runtimes is under
[Databricks](#databricks).

## The Scala version route

Spark and Databricks run Scala 2.13 (or 2.12); LLM4S is published for Scala 3 only. A Scala 2.13 job reads LLM4S's <!-- doc-support: ignore -->
Scala 3 artifacts with the TASTy reader, which works for calling LLM4S but not for every API: see
[Calling LLM4S from Scala 2.13](https://github.com/llm4s/llm4s/blob/main/docs/design/scala-2.13-interop.md) for <!-- doc-support: ignore -->
what was measured. In short, completions, streaming, configuration, `ReliableClient`, agents and running tools work;
defining tools with `ToolBuilder`, `completeStructured` and implementing `LLMClient` do not compile from 2.13.

**Scala 2.12 is not supported**: it has no TASTy reader, so a Scala 2.12 runtime (Databricks 16.4 LTS's 2.12 image <!-- doc-support: ignore -->
and earlier) cannot use LLM4S.

## Dependencies

```scala
// build.sbt of the Spark job
scalaVersion := "2.13.16"   // the Spark job, not LLM4S  doc-support: ignore
scalacOptions += "-Ytasty-reader"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-sql"    % "4.0.1" % Provided,
  ("org.llm4s"       %% "llm4s-core"   % "{{ site.data.project.latest_release }}").cross(CrossVersion.for2_13Use3),
  ("org.llm4s"       %% "llm4s-openai" % "{{ site.data.project.latest_release }}").cross(CrossVersion.for2_13Use3) // the provider module you call
)
```

Ship LLM4S and its dependencies in the job's assembly; Spark itself is `Provided`.

### Dependency conflicts: what was checked

Resolving `spark-sql_2.13:4.0.1` together with `llm4s-core_3` and `llm4s-ollama_3` 0.5.1 (coursier, 176 artifacts):
the two trees share only two artifacts.

| Artifact | Spark 4.0.1 | LLM4S 0.5.1 | Resolved |
|---|---|---|---|
| `scala-library` | 2.13.16 | 2.13.16 (through `scala3-library_3` 3.7.1) | 2.13.16 |
| `slf4j-api` | 2.0.16 | 2.0.17 | 2.0.17 (on a cluster, Spark's 2.0.16 is on the classpath; both are SLF4J 2) |

Everything else LLM4S brings is a `_3` artifact Spark does not use (`scala3-library_3`, `cats-core_3`, `upickle_3`,
`ujson_3`, `pureconfig-core_3`) plus Typesafe Config and jtokkit. Two cautions:

- **Do not add the `_2.13` build of a library LLM4S uses as `_3`** (cats, uPickle, pureconfig) to the same job:
  both have the same class names, so one silently shadows the other at run time.
- **`scala-library` must be 2.13.16 or later** at run time, the version LLM4S's Scala 3 standard library is built
  against. On a cluster the runtime's own `scala-library` wins over the job's: see the next section.

### Spark 3.5 and Databricks 16.4 LTS

Spark 3.5.9 resolves `scala-library` 2.13.8, and Databricks Runtime 16.4 LTS's Scala 2.13 image ships 2.13.10. The <!-- doc-support: ignore -->
example ran locally on Spark 3.5.9 only because the local classpath resolved `scala-library` up to LLM4S's 2.13.16;
on a cluster, where the runtime's 2.13.8 or 2.13.10 is used, that is not verified, and code compiled against
2.13.16's library can fail with a `NoSuchMethodError`. Prefer Spark 4.0, which ships 2.13.16.

## Databricks

From the Databricks release notes (read 2026-10-21):

- [Databricks Runtime 17.3 LTS](https://docs.databricks.com/aws/en/release-notes/runtime/17.3lts): Apache Spark
  4.0.0, Scala 2.13.16, Java 17 (Zulu 17). This matches the verified route: Spark 4, Scala 2.13.16, and the example <!-- doc-support: ignore -->
  also ran on JDK 17. <!-- doc-support: ignore -->
- [Databricks Runtime 16.4 LTS](https://docs.databricks.com/aws/en/release-notes/runtime/16.4lts): Apache Spark
  3.5.2, in a Scala 2.12 image (not usable, above) and a Scala 2.13 image with `scala-library` 2.13.10 (see <!-- doc-support: ignore -->
  [Spark 3.5](#spark-35-and-databricks-164-lts)).

To call a model served by Databricks itself, its serving endpoints accept OpenAI-format chat requests, which the
generic `openai-compatible` provider sends ([providers](providers)); this was not verified against a workspace.
Authenticating with workload identity rather than a token is tracked in
[#1354](https://github.com/llm4s/llm4s/issues/1354).

## The job

The full job is
[`EnrichReviews.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/spark/EnrichReviews.scala). Its
shape:

```scala
def enrich(reviews: Iterator[Review]): Iterator[EnrichedReview] =
  clientForPartition() match {
    case Left(error) =>
      reviews.map(r => EnrichedReview(r.id, r.text, None, Some(s"no client: $error")))
    case Right(client) =>
      try
        reviews.map { review =>
          client.complete(conversationFor(review), CompletionOptions().withMaxTokens(64)) match {
            case Right(completion) => EnrichedReview(review.id, review.text, Some(completion.content), None)
            case Left(error)       => EnrichedReview(review.id, review.text, None, Some(error.message))
          }
        }.toList.iterator // finish every call before the client is closed
      finally client.close()
  }

val enriched = reviews.mapPartitions(enrich)
```

### Client lifecycle

- **Build the client on the executor, once per partition**, inside `mapPartitions`. An `LLMClient` holds an HTTP
  client and is not serialisable, so it cannot be built on the driver and shipped; building one per row would reload
  the configuration and set up a connection for every call.
- **Close it when the partition is done**, in a `finally`. Force the iterator first (`.toList.iterator` above):
  `mapPartitions` is lazy, and closing the client before Spark pulls the rows would close it under the calls.
- **Configuration is read on the executor.** `Llm4sConfig.defaultProvider()` reads the executor's
  `application.conf` (bundle it in the job's jar) and system properties, and the provider's API key from its
  conventional environment variable (`OPENAI_API_KEY`, ...), which must be set on the executors, not only the driver.

### Errors

`complete` returns a `Result`, so a failed call is a value. The example turns it into an `error` column, so one bad
row or a provider outage does not fail the stage and lose the partition's other rows; filter or retry those rows
afterwards. Throwing instead would make Spark retry the whole task (`spark.task.maxFailures`, 4 by default),
repeating every successful call in the partition.

### Concurrency and rate limits

The calls run one at a time within a partition, so the job makes as many concurrent calls as partitions running at
once (`spark.executor.cores` x executors, or the `n` of `local[n]`). Bound it with `repartition` or `coalesce` before
`mapPartitions`.

`ReliableClient` wraps each partition's client with retries, a deadline and a local rate limit:

```scala
new ReliableClient(
  underlying,
  "spark-example",
  ReliabilityConfig(
    retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 50.millis),
    rateLimit   = RateLimitConfig(enabled = true, requestsPerMinute = 600, burstCapacity = 10),
    deadline    = Some(30.seconds)
  )
)
```

The rate limit is a token bucket **per client, so per partition**: the job's total is roughly `requestsPerMinute`
times the partitions running at once. Divide the provider's limit by that number. A rate-limited call that still
fails after its retries comes back as a `RateLimitError` in the `error` column.

## Run the example

It is a [Scala CLI](https://scala-cli.virtuslab.org/) project, outside the sbt build and never published, so Spark
stays out of every LLM4S artifact:

```bash
cd modules/samples/spark
scala-cli run . --main-class EnrichReviews -- --fake
```

`--fake` starts a local stand-in for Ollama that upper-cases each review and fails the one containing "boom". The
output, on Spark 4.0.1:

```
+---+----------------------------------------+---------------------------------+-----------------------------------------------------------+
|id |text                                    |answer                           |error                                                      |
+---+----------------------------------------+---------------------------------+-----------------------------------------------------------+
|1  |The battery lasts two days              |THE BATTERY LASTS TWO DAYS       |NULL                                                       |
|2  |Screen cracked in a week                |SCREEN CRACKED IN A WEEK         |NULL                                                       |
|3  |boom: this one makes the fake model fail|NULL                             |Service error from ollama: the fake model failed (HTTP 500)|
|4  |Fast delivery, works as described       |FAST DELIVERY, WORKS AS DESCRIBED|NULL                                                       |
+---+----------------------------------------+---------------------------------+-----------------------------------------------------------+

4 rows, 1 with an error
```

Without `--fake`, the job calls the provider your `application.conf` names in `llm4s.providers.provider`.
