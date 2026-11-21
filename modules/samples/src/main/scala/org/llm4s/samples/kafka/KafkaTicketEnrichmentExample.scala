package org.llm4s.samples.kafka

import cats.effect.{ Deferred, ExitCode, IO, IOApp, Resource }
import cats.syntax.all.*
import fs2.kafka.{ Acks, AutoOffsetReset, ConsumerSettings, KafkaConsumer, KafkaProducer, ProducerSettings }
import org.llm4s.config.Llm4sConfig
import org.llm4s.effect.cats.LLMException
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.trace.{ Tracing, TracingLLMClient }

import scala.concurrent.duration.*

/**
 * Enriches the `tickets` topic into `tickets-enriched`, with failures in `tickets-dead-letter` (docs/guide/kafka.md).
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.kafka.KafkaTicketEnrichmentExample"                     # the configured model
 * sbt "samples/runMain org.llm4s.samples.kafka.KafkaTicketEnrichmentExample --scripted"          # no model, no key
 * sbt "samples/runMain org.llm4s.samples.kafka.KafkaTicketEnrichmentExample --bootstrap host:9092"
 * }}}
 *
 * Without `--scripted` the model is the default provider of `application.conf` (`llm4s.providers.provider`), and each
 * call is traced to the configured tracing backend. Stop it with Ctrl-C: it stops consuming, finishes the records in
 * flight, commits their offsets and closes.
 */
object KafkaTicketEnrichmentExample extends IOApp {

  val InputTopic      = "tickets"
  val OutputTopic     = "tickets-enriched"
  val DeadLetterTopic = "tickets-dead-letter"
  val GroupId         = "llm4s-ticket-enrichment"

  /** Reads `--bootstrap host:port` and `--scripted`, then enriches until cancelled. */
  def run(args: List[String]): IO[ExitCode] = {
    val bootstrap = args.dropWhile(_ != "--bootstrap").drop(1).headOption.getOrElse("localhost:9092")
    val client    = if (args.contains("--scripted")) scriptedClient else configuredClient
    val settings  = TicketPipeline.Settings.default(OutputTopic, DeadLetterTopic, concurrency = 8)
    client.use(enrich(_, bootstrap, settings)).as(ExitCode.Success)
  }

  /** Consumes the input topic until cancelled, then shuts down gracefully. */
  def enrich(client: LLMClient, bootstrap: String, settings: TicketPipeline.Settings): IO[Unit] = {
    val consumerSettings = ConsumerSettings[IO, String, String]
      .withBootstrapServers(bootstrap)
      .withGroupId(GroupId)
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
    // Idempotent, fully acknowledged writes: a produce that returns is on every in-sync replica.
    val producerSettings = ProducerSettings[IO, String, String]
      .withBootstrapServers(bootstrap)
      .withAcks(Acks.All)
      .withEnableIdempotence(true)

    (KafkaConsumer.resource(consumerSettings), KafkaProducer.resource(producerSettings)).tupled.use {
      case (consumer, producer) =>
        for {
          done <- Deferred[IO, Either[Throwable, Unit]]
          _    <- consumer.subscribeTo(InputTopic)
          fiber <- consumer.records
            .through(TicketPipeline.pipe(client, producer.produce, settings))
            .compile
            .drain
            .attempt
            .flatMap(done.complete)
            .start
          // On Ctrl-C (or any cancellation): stop fetching, let the records in flight finish and commit, then close.
          _ <- done.get.rethrow
            .onCancel(consumer.stopConsuming >> done.get.timeout(30.seconds).void)
            .guarantee(fiber.cancel)
        } yield ()
    }
  }

  /** The default provider of `application.conf`, with each call traced. */
  private def configuredClient: Resource[IO, LLMClient] =
    Resource.fromAutoCloseable(
      IO.blocking {
        for {
          registry <- Llm4sConfig.modelRegistryService()
          config   <- Llm4sConfig.defaultProvider()
          tracing  <- Llm4sConfig.tracing()
          client   <- LLMConnect.getClient(config)(using registry)
        } yield TracingLLMClient(client, Tracing.create(tracing), config)
      }.flatMap {
        case Right(client) => IO.pure(client)
        case Left(error)   => IO.raiseError(new LLMException(error))
      }
    )

  /** A model that labels every ticket the same way: for trying the pipeline against a broker without a key. */
  private def scriptedClient: Resource[IO, LLMClient] =
    Resource.pure(
      ScriptedLLMClient.always(
        Reply.text("""{"category": "Technical", "priority": "Medium", "summary": "A scripted label."}""")
      )
    )
}
