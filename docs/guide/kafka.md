---
layout: page
title: Kafka
description: "Enrich a Kafka topic with an LLM using fs2-kafka and LLM4S: bounded concurrency, retries, a dead-letter topic and offsets committed after the produce."
search_title: "Enrich a Kafka topic with an LLM in Scala"
parent: User Guide
nav_order: 42
---

# Enrich a Kafka topic with an LLM
{: .no_toc }

Consume a topic, call a model for every record, and produce the result to another topic, without exceeding the
provider's rate limit, stalling a partition on one bad record, or committing an offset before its output is written.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

The pipeline on this page is runnable and tested: it is
[`org.llm4s.samples.kafka`](https://github.com/llm4s/llm4s/tree/main/modules/samples/src/main/scala/org/llm4s/samples/kafka)
in `llm4s-samples`. `TicketPipelineSpec` runs it over an in-memory stream with a scripted model on every build, and
`KafkaTicketPipelineSpec` (`modules/it`, `@Docker` tier) runs it against a Kafka broker in CI. It is built on
[fs2-kafka](https://fd4s.github.io/fs2-kafka/) (Apache-2.0) and cats-effect; the same rules apply to Kafka Streams or a
plain `KafkaConsumer`, in [the last section](#kafka-streams-or-a-plain-kafkaconsumer).

## What it does

Records on the `tickets` topic are support tickets, `{"id": "...", "text": "..."}`. For each one the model returns
`TicketLabels` (a category, a priority and a one-line summary) through structured output, and the pipeline writes:

- an `EnrichedTicket` (the ticket and its labels) to `tickets-enriched`, or
- a `DeadLetter` (the input record, where it came from, and the error) to `tickets-dead-letter`, when the value is not
  a ticket or the model call failed with an error that retrying does not fix.

Every output carries the input record's key, so it is partitioned like the input, and three headers naming its
source (`llm4s-source-topic`, `llm4s-source-partition`, `llm4s-source-offset`).

## Dependencies

```sbt
libraryDependencies ++= Seq(
  "org.llm4s"       %% "llm4s-effect"             % "{{ site.data.project.latest_release }}",
  "org.llm4s"       %% "llm4s-schema-derivation"  % "{{ site.data.project.latest_release }}",
  "org.llm4s"       %% "llm4s-openai"             % "{{ site.data.project.latest_release }}", // the provider module you call
  "com.github.fd4s" %% "fs2-kafka"                % "3.7.0"
)
```

fs2-kafka 3.7.0 is built on the cats-effect (3.5) and fs2 (3.11) versions `llm4s-effect` uses; 3.8.0 and later need
cats-effect 3.6. It brings `kafka-clients` 3.9.0: pin `"org.apache.kafka" % "kafka-clients" % "3.9.1"` for the fix
to CVE-2025-27817, as the sample does. No LLM4S module depends on Kafka.

## The types

The model's answer is a case class whose JSON Schema is derived with `derives SchemaOf` (see
[Derived schemas](derived-schemas)):

```scala
import org.llm4s.schema.{ description, SchemaOf }
import upickle.default.ReadWriter

final case class Ticket(id: String, text: String) derives ReadWriter

enum Category derives SchemaOf, ReadWriter {
  case Billing, Technical, Account, Other
}

enum Priority derives SchemaOf, ReadWriter {
  case Low, Medium, High
}

@description("The classification of one support ticket")
final case class TicketLabels(
  category: Category,
  priority: Priority,
  @description("The ticket in one sentence") summary: String
) derives SchemaOf, ReadWriter
```

`EnrichedTicket` and `DeadLetter` are plain case classes with a `ReadWriter`; they are in
[`Tickets.scala`](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/kafka/Tickets.scala).

## One record

A record's outcome is a value, never an exception, except in one case:

```scala
import cats.effect.IO
import fs2.kafka.ConsumerRecord
import org.llm4s.llmconnect.LLMClient
import org.llm4s.reliability.RetryPolicy
import org.llm4s.samples.kafka.{ DeadLetter, EnrichedTicket, Ticket, TicketLabels, TicketPipeline }
import org.llm4s.samples.kafka.TicketPipeline.{ Outcome, TransientFailure }
import org.llm4s.schema.completeStructuredOf
import scala.util.Try

def enrich(client: LLMClient, record: ConsumerRecord[String, String], retryPolicy: RetryPolicy): IO[Outcome] = {
  def deadLetter(reason: String, error: String) = Outcome.DeadLettered(
    DeadLetter(record.topic, record.partition, record.offset, record.key, record.value, reason, error)
  )
  Try(upickle.default.read[Ticket](record.value)).toEither match {
    case Left(e) => IO.pure(deadLetter("decode", e.getMessage))
    case Right(ticket) =>
      IO.interruptible(client.completeStructuredOf[TicketLabels](TicketPipeline.conversation(ticket))).flatMap {
        case Right(labels)                                 => IO.pure(Outcome.Enriched(EnrichedTicket(ticket.id, ticket.text, labels)))
        case Left(error) if retryPolicy.isRetryable(error) =>
          IO.raiseError(new TransientFailure(error, record.topic, record.partition, record.offset))
        case Left(error) => IO.pure(deadLetter("llm", error.message))
      }
  }
}
```

- **A decode failure** (the value is not a ticket) goes to the dead-letter topic. The model is not called.
- **A model error that retrying does not fix** (an authentication error, a reply that does not match the schema)
  goes to the dead-letter topic with its type and message. The record's partition carries on.
- **A transient error** (a rate limit, a timeout, a 5xx) is retried with backoff by the client, a
  [`ReliableClient`](../reliability-guide) the pipeline wraps around yours. If it is still failing when the retries
  run out, the record is fine and the provider is not: the pipeline stops with `TransientFailure` instead of
  dead-lettering, because during an outage every record would follow it into the dead-letter topic. Its offset is
  not committed, so a restart (by your supervisor, or a `Stream.retry` around the pipeline) reads it again.

`retryPolicy.isRetryable` is the same rule `ReliableClient` uses, so the two cannot disagree about which errors are
transient. `IO.interruptible` runs the blocking call on cats-effect's blocking pool, and cancelling the fiber
interrupts it, as `LLMClientIO` does for `complete`.

## The pipeline

```scala
import cats.effect.IO
import fs2.Pipe
import fs2.kafka.{ commitBatchWithin, CommittableConsumerRecord, ProducerRecords }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.reliability.ReliableClient
import org.llm4s.samples.kafka.TicketPipeline

def pipe(
  client: LLMClient,
  send: TicketPipeline.Send, // producer.produce
  settings: TicketPipeline.Settings
): Pipe[IO, CommittableConsumerRecord[IO, String, String], Unit] = {
  val reliable = new ReliableClient(client, "kafka-ticket-pipeline", settings.reliability)
  records =>
    records
      .parEvalMap(settings.concurrency) { committable =>
        TicketPipeline
          .enrich(reliable, committable.record, settings.reliability.retryPolicy)
          .map(outcome => committable.offset -> TicketPipeline.toProducerRecord(outcome, committable.record, settings))
      }
      .evalMap { case (offset, output) => send(ProducerRecords.one(output)).map(_.as(offset)) }
      .parEvalMap(settings.concurrency)(identity) // wait for the broker's acknowledgement
      .through(commitBatchWithin(settings.commitBatchSize, settings.commitInterval))
}
```

Three things in it matter:

1. **`parEvalMap(n)` bounds the calls in flight** to `n` across every partition of the consumer, so `n` is the
   number to size against the provider's limit (below).
2. **`send` returns an effect that waits for the broker.** `KafkaProducer.produce` puts the record in the producer's
   buffer and returns a second effect that completes when the broker acknowledges it. The offset goes on only when
   that second effect completes.
3. **`commitBatchWithin` commits after the produce**, every `commitBatchSize` records or `commitInterval`, whichever
   comes first. Because `parEvalMap` keeps input order, the highest offset in a batch is never ahead of a record
   whose output is unwritten. Delivery is at least once: after a crash, the records since the last commit are
   processed again and their outputs written twice. The source headers let a downstream consumer drop a duplicate;
   exactly-once needs a `TransactionalKafkaProducer` that commits the offsets in the producer's transaction, which
   this page does not cover.

## Ordering and throughput

`parEvalMap` runs `n` calls at once but emits their results in input order: a slow call holds back the faster ones
behind it until it finishes. The output topic then has each partition's records in input order, and the commit rule
above holds.

`parEvalMapUnordered` emits each result as it finishes, which keeps all `n` slots busy behind a slow call. **Do not
use it in front of `commitBatchWithin`**: an offset would be committed while an earlier record of the same partition
is still in flight, and a crash at that moment loses that record. If one slow partition holding back the others is
the problem, run the pipeline per partition instead, which keeps the order within each partition:

```scala
import cats.effect.IO
import fs2.kafka.KafkaConsumer

def perPartition(
  consumer: KafkaConsumer[IO, String, String],
  pipeline: fs2.Pipe[IO, fs2.kafka.CommittableConsumerRecord[IO, String, String], Unit]
): fs2.Stream[IO, Unit] =
  consumer.partitionedStream.map(_.through(pipeline)).parJoinUnbounded
```

Then each partition has its own `n` calls in flight, so the total is `n` times the partitions assigned to this
consumer.

### Sizing `n` against the rate limit

With calls of about `t` seconds, `n` calls in flight make about `n / t` requests a second, and every consumer in the
group adds its own. Choose `n` so that the group's total stays under the provider's requests-per-minute limit, and
give the `ReliableClient` a local rate limit as well, so a burst (a rebalance, a backlog) waits instead of being
refused:

```scala
import org.llm4s.reliability.{ RateLimitConfig, ReliabilityConfig, RetryPolicy }
import org.llm4s.samples.kafka.TicketPipeline
import scala.concurrent.duration.*

val settings = TicketPipeline.Settings(
  outputTopic = "tickets-enriched",
  deadLetterTopic = "tickets-dead-letter",
  concurrency = 8,
  reliability = ReliabilityConfig(
    retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 4, baseDelay = 1.second),
    rateLimit = RateLimitConfig(enabled = true, requestsPerMinute = 300, burstCapacity = 8),
    deadline = Some(2.minutes)
  )
)
```

A rate limit the provider returns (`429`) is a `RateLimitError`, retried after its `Retry-After` when the provider
sent one, and after 30 seconds when it did not. fs2-kafka keeps polling while the pipeline is busy (it pauses the
partitions instead), so slow model calls do not make the consumer leave its group; the deadline bounds how long one
stuck call holds one of the `n` slots.

## Running it

```scala
import cats.effect.{ Deferred, IO }
import cats.syntax.all.*
import fs2.kafka.{ Acks, AutoOffsetReset, ConsumerSettings, KafkaConsumer, KafkaProducer, ProducerSettings }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.samples.kafka.TicketPipeline
import scala.concurrent.duration.*

def run(client: LLMClient, settings: TicketPipeline.Settings): IO[Unit] = {
  val consumerSettings = ConsumerSettings[IO, String, String]
    .withBootstrapServers("localhost:9092")
    .withGroupId("llm4s-ticket-enrichment")
    .withAutoOffsetReset(AutoOffsetReset.Earliest)
  val producerSettings = ProducerSettings[IO, String, String]
    .withBootstrapServers("localhost:9092")
    .withAcks(Acks.All)
    .withEnableIdempotence(true)

  (KafkaConsumer.resource(consumerSettings), KafkaProducer.resource(producerSettings)).tupled.use {
    case (consumer, producer) =>
      for {
        done <- Deferred[IO, Either[Throwable, Unit]]
        _    <- consumer.subscribeTo("tickets")
        fiber <- consumer.records
          .through(TicketPipeline.pipe(client, producer.produce, settings))
          .compile
          .drain
          .attempt
          .flatMap(done.complete)
          .start
        // Graceful shutdown: stop fetching, let the records in flight finish and commit, then close.
        _ <- done.get.rethrow
          .onCancel(consumer.stopConsuming >> done.get.timeout(30.seconds).void)
          .guarantee(fiber.cancel)
      } yield ()
  }
}
```

`KafkaTicketEnrichmentExample` is this, as an `IOApp`. With a broker on `localhost:9092`:

```bash
# A scripted model: no provider, no API key
sbt "samples/runMain org.llm4s.samples.kafka.KafkaTicketEnrichmentExample --scripted"
# The default provider of application.conf (llm4s.providers.provider)
sbt "samples/runMain org.llm4s.samples.kafka.KafkaTicketEnrichmentExample --bootstrap localhost:9092"
```

Ctrl-C cancels it, which runs the shutdown above.

### The client's lifecycle

Build the client once, as a `Resource`, and share it across every record: it holds the HTTP client and, wrapped in
a `ReliableClient`, the circuit breaker and rate limiter, which only work if every call goes through the same one.
`LLMClientIO.resource` does this for `LLMClientIO`'s `complete` and `streamComplete`; for structured output the
pipeline needs the `LLMClient` itself, so it builds the resource the same way:

```scala
import cats.effect.{ IO, Resource }
import org.llm4s.config.Llm4sConfig
import org.llm4s.effect.cats.LLMException
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.trace.{ Tracing, TracingLLMClient }

val clientResource: Resource[IO, LLMClient] =
  Resource.fromAutoCloseable(IO.blocking {
    for {
      registry <- Llm4sConfig.modelRegistryService()
      config   <- Llm4sConfig.defaultProvider()
      tracing  <- Llm4sConfig.tracing()
      client   <- LLMConnect.getClient(config)(using registry)
    } yield TracingLLMClient(client, Tracing.create(tracing), config)
  }.flatMap {
    case Right(client) => IO.pure(client)
    case Left(error)   => IO.raiseError(new LLMException(error))
  })
```

### Tracing each record

`TracingLLMClient` sends every model call to the configured tracing backend (`TRACING_MODE`, see
[Observability](observability/)) as one `chat <model>` span with its model, duration and token usage. A failed call
is traced as an error with its type. To find the span of one record, log the record's topic, partition and offset
next to it; the output's source headers carry the same three values downstream.

## Kafka Streams or a plain KafkaConsumer

The same rules hold with the synchronous `LLMClient` and no effect system:

- **Bound the calls** with a fixed thread pool of `n` threads, not one thread per record.
- **Wrap the client in a `ReliableClient`** once, and share it.
- **Dead-letter a decode failure or a non-retryable error**; stop (or pause the partition) on a transient error that
  outlived the retries.
- **Commit after the produce**: with `enable.auto.commit=false`, wait for every `send` of a poll's records, then
  `commitSync`.

A sketch with the Java client, where `client` is your `LLMClient`:

```scala
import java.util.Properties
import java.util.concurrent.{ Callable, Executors }
import org.apache.kafka.clients.consumer.{ ConsumerConfig, KafkaConsumer }
import org.apache.kafka.clients.producer.{ KafkaProducer, ProducerConfig, ProducerRecord }
import org.apache.kafka.common.serialization.{ StringDeserializer, StringSerializer }
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient }
import org.llm4s.samples.kafka.{ Ticket, TicketLabels, TicketPipeline }
import org.llm4s.schema.completeStructuredOf
import scala.jdk.CollectionConverters.*

val consumerProps = new Properties()
consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "llm4s-ticket-enrichment")
consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
val producerProps = new Properties()
producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
producerProps.put(ProducerConfig.ACKS_CONFIG, "all")

val consumer = new KafkaConsumer(consumerProps, new StringDeserializer, new StringDeserializer)
val producer = new KafkaProducer(producerProps, new StringSerializer, new StringSerializer)
val reliable = new ReliableClient(client, "kafka-ticket-pipeline", ReliabilityConfig())
val pool     = Executors.newFixedThreadPool(8) // at most 8 model calls at once

consumer.subscribe(java.util.List.of("tickets"))
while (!Thread.currentThread().isInterrupted) {
  val records = consumer.poll(java.time.Duration.ofMillis(500)).asScala.toVector
  // The model calls of one poll, at most 8 at once, results in input order.
  val calls = records.map { record =>
    pool.submit(new Callable[Either[String, TicketLabels]] {
      def call(): Either[String, TicketLabels] = {
        val ticket = upickle.default.read[Ticket](record.value)
        reliable.completeStructuredOf[TicketLabels](TicketPipeline.conversation(ticket)).left.map(_.message)
      }
    })
  }
  val sends = records.zip(calls).map { case (record, call) =>
    call.get() match {
      case Right(labels) => producer.send(new ProducerRecord("tickets-enriched", record.key, upickle.default.write(labels)))
      case Left(error)   => producer.send(new ProducerRecord("tickets-dead-letter", record.key, error))
    }
  }
  sends.foreach(_.get()) // every output acknowledged ...
  consumer.commitSync()  // ... before the offsets are committed
}
```

This sketch reads the ticket without handling a decode failure and dead-letters every model error; the fs2 pipeline
above separates the transient ones. In Kafka Streams, a `mapValues` that calls the model blocks the stream thread, so
the stream threads (`num.stream.threads`) are the concurrency bound, and the commit rule is Kafka Streams' own: it
commits only what its topology has produced.

## Testing the pipeline

The pipeline is a `Pipe` from consumer records to commits, so it runs without a broker: build
`CommittableConsumerRecord`s in memory, pass a `send` that records what it is given, and use a
[`ScriptedLLMClient`](testing-with-the-testkit) as the model. `TicketPipelineSpec` does this to check a success, a
rate limit retried and then enriched, both kinds of dead letter, a transient error that stops the pipeline without
committing, at most `n` calls at once with the output in input order, and that every commit comes after the
acknowledgement of each output it covers:

```scala
import org.llm4s.error.RateLimitError
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import scala.concurrent.duration.*

// A rate limit, then the labels: the ReliableClient retries, and the ticket is enriched on the second call.
val model = ScriptedLLMClient.sequence(
  Reply.failure(RateLimitError("scripted", 1.milli)),
  Reply.text("""{"category": "Billing", "priority": "High", "summary": "Charged twice."}""")
)
```

`KafkaTicketPipelineSpec` then runs the pipeline against a broker: 30 records over three partitions, with decode and
model dead letters among them. It checks that each input is the source of exactly one output across the two topics,
and polls the consumer group's committed offsets while the pipeline runs to check that none passes an unacknowledged
output. Run it with a broker on `localhost:9092`:

```bash
docker run --rm -p 9092:9092 apache/kafka:3.9.1
KAFKA_TEST_BOOTSTRAP=localhost:9092 sbt "it/testOnly org.llm4s.samples.kafka.KafkaTicketPipelineSpec"
```
