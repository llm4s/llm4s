package org.llm4s.samples.kafka

import cats.effect.IO
import fs2.Pipe
import fs2.kafka.{
  commitBatchWithin,
  CommittableConsumerRecord,
  ConsumerRecord,
  Header,
  Headers,
  ProducerRecord,
  ProducerRecords,
  ProducerResult
}
import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.schema.completeStructuredOf

import scala.concurrent.duration.*
import scala.util.Try

/**
 * Enriches a Kafka topic of support tickets with an LLM (docs/guide/kafka.md, #2297).
 *
 * Each record's value is read as a [[Ticket]], the model labels it with [[TicketLabels]] through `completeStructuredOf`,
 * and the result goes to the enriched topic. A value that is not a ticket, or a model error that retrying does not fix,
 * goes to the dead-letter topic as a [[DeadLetter]] instead, so one bad record never stops its partition.
 *
 * The rules the pipeline keeps:
 *
 *  - '''Bounded concurrency.''' At most `concurrency` model calls run at once (`parEvalMap`), whatever the partition
 *    count. Size it against the provider's rate limit.
 *  - '''Retries in the client.''' The client is wrapped in a [[org.llm4s.reliability.ReliableClient ReliableClient]]
 *    with `reliability`, so a rate limit or a timeout is retried with backoff before the pipeline sees it.
 *  - '''A transient error that outlives its retries stops the pipeline''' with [[TransientFailure]]: the record is
 *    fine and the provider is not, so dead-lettering would drain the topic into the dead-letter topic during an
 *    outage. Its offset is not committed, so a restart reads it again.
 *  - '''Offsets are committed after the produce.''' A record's offset reaches `commitBatchWithin` only once the
 *    broker has acknowledged its output, and `parEvalMap` keeps input order, so a committed offset never passes a
 *    record whose output is not written. Delivery is at least once: after a crash the records since the last commit
 *    are processed again.
 */
object TicketPipeline {

  /** Output headers naming the input record, so an output can be traced (or deduplicated) back to its source. */
  val SourceTopicHeader     = "llm4s-source-topic"
  val SourcePartitionHeader = "llm4s-source-partition"
  val SourceOffsetHeader    = "llm4s-source-offset"

  /**
   * The pipeline's settings.
   *
   * @param outputTopic     where enriched tickets go
   * @param deadLetterTopic where records that cannot be enriched go
   * @param concurrency     the most model calls in flight at once
   * @param reliability     retries, backoff, a rate limit and a deadline for each model call
   * @param commitBatchSize commit after this many records ...
   * @param commitInterval  ... or after this long, whichever comes first
   */
  final case class Settings(
    outputTopic: String,
    deadLetterTopic: String,
    concurrency: Int,
    reliability: ReliabilityConfig,
    commitBatchSize: Int = 500,
    commitInterval: FiniteDuration = 5.seconds
  )

  object Settings {

    /**
     * Exponential backoff from one second, three attempts, a two-minute deadline per call, and no local rate limit:
     * `concurrency` is the limit. Add a `RateLimitConfig` when the provider's limit is in requests per minute.
     */
    def default(outputTopic: String, deadLetterTopic: String, concurrency: Int): Settings =
      Settings(
        outputTopic,
        deadLetterTopic,
        concurrency,
        ReliabilityConfig(retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3), deadline = Some(2.minutes))
      )
  }

  /** Where one record goes. */
  enum Outcome {
    case Enriched(ticket: EnrichedTicket)
    case DeadLettered(letter: DeadLetter)
  }

  /** A transient model error that outlived the client's retries; the pipeline stops without committing the record. */
  final class TransientFailure(val error: LLMError, val topic: String, val partition: Int, val offset: Long)
      extends RuntimeException(s"$topic-$partition@$offset: ${error.formatted}")

  /** How the pipeline sends its output: `KafkaProducer.produce`, or a fake in a test. */
  type Send = ProducerRecords[String, String] => IO[IO[ProducerResult[String, String]]]

  private val Instructions =
    "You label customer support tickets. Choose the category and priority that fit the ticket, and summarise it " +
      "in one sentence."

  private val Options = CompletionOptions().withTemperature(0.0).withMaxTokens(256)

  /**
   * The pipeline, from a subscribed consumer's records to committed offsets.
   *
   * @param client the model; it is wrapped in a `ReliableClient` with `settings.reliability`, and not closed here
   * @param send   how output records are produced: `producer.produce`
   */
  def pipe(
    client: LLMClient,
    send: Send,
    settings: Settings
  ): Pipe[IO, CommittableConsumerRecord[IO, String, String], Unit] = {
    val reliable = new ReliableClient(client, "kafka-ticket-pipeline", settings.reliability)
    records =>
      records
        // At most `concurrency` calls at once; results come out in input order.
        .parEvalMap(settings.concurrency) { committable =>
          enrich(reliable, committable.record, settings.reliability.retryPolicy)
            .map(outcome => committable.offset -> toProducerRecord(outcome, committable.record, settings))
        }
        // `send` returns once the record is in the producer's buffer; the inner effect waits for the broker.
        .evalMap { case (offset, output) => send(ProducerRecords.one(output)).map(_.as(offset)) }
        .parEvalMap(settings.concurrency)(identity)
        .through(commitBatchWithin(settings.commitBatchSize, settings.commitInterval))
  }

  /**
   * One record's outcome. Never fails for a bad record: a value that is not a [[Ticket]], or a model error that
   * `retryPolicy` does not retry, is a dead letter. Fails with [[TransientFailure]] for an error that it does retry,
   * which reaches here only after the client's retries ran out.
   */
  def enrich(client: LLMClient, record: ConsumerRecord[String, String], retryPolicy: RetryPolicy): IO[Outcome] = {
    def deadLetter(reason: String, error: String) =
      Outcome.DeadLettered(
        DeadLetter(
          record.topic,
          record.partition,
          record.offset,
          Option(record.key).getOrElse(""),
          Option(record.value).getOrElse(""),
          reason,
          error
        )
      )

    Try(upickle.default.read[Ticket](record.value)).toEither match {
      case Left(e) =>
        IO.pure(deadLetter("decode", s"${e.getClass.getSimpleName}: ${e.getMessage}"))
      case Right(ticket) =>
        IO.interruptible(client.completeStructuredOf[TicketLabels](conversation(ticket), Options)).flatMap {
          case Right(labels) =>
            IO.pure(Outcome.Enriched(EnrichedTicket(ticket.id, ticket.text, labels)))
          case Left(error) if retryPolicy.isRetryable(error) =>
            IO.raiseError(new TransientFailure(error, record.topic, record.partition, record.offset))
          case Left(error) =>
            IO.pure(deadLetter("llm", s"${error.getClass.getSimpleName}: ${error.message}"))
        }
    }
  }

  /** The conversation for one ticket. */
  def conversation(ticket: Ticket): Conversation =
    Conversation(Seq(SystemMessage(Instructions), UserMessage(s"Ticket ${ticket.id}:\n${ticket.text}")))

  /**
   * The output record for `outcome`: to the output or the dead-letter topic, with the input's key (so the output is
   * partitioned, and so ordered, the way the input was) and headers naming the input record.
   */
  def toProducerRecord(
    outcome: Outcome,
    input: ConsumerRecord[String, String],
    settings: Settings
  ): ProducerRecord[String, String] = {
    val (topic, value) = outcome match {
      case Outcome.Enriched(ticket)     => settings.outputTopic     -> upickle.default.write(ticket)
      case Outcome.DeadLettered(letter) => settings.deadLetterTopic -> upickle.default.write(letter)
    }
    ProducerRecord(topic, input.key, value).withHeaders(
      Headers(
        Header(SourceTopicHeader, input.topic),
        Header(SourcePartitionHeader, input.partition.toString),
        Header(SourceOffsetHeader, input.offset.toString)
      )
    )
  }
}
