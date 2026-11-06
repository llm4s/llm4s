package org.llm4s.samples.kafka

import cats.effect.{ IO, Ref }
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.kafka.{
  AdminClientSettings,
  AutoOffsetReset,
  ConsumerRecord,
  ConsumerSettings,
  KafkaAdminClient,
  KafkaConsumer,
  KafkaProducer,
  ProducerRecord,
  ProducerRecords,
  ProducerSettings
}
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.TopicPartition
import org.llm4s.it.Tier
import org.llm4s.it.tags.Docker
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.reliability.{ CircuitBreakerConfig, ReliabilityConfig, RetryPolicy }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * The Kafka enrichment pipeline of docs/guide/kafka.md against a real broker (#2297), with a scripted model.
 *
 * Every input record must end up in the enriched or the dead-letter topic exactly once, and a committed offset must
 * never pass a record whose output the broker has not acknowledged: while the pipeline runs, the consumer group's
 * committed offsets are polled and checked against the outputs acknowledged so far. `TicketPipelineSpec` in
 * llm4s-samples covers the same rules without a broker.
 *
 * Needs `KAFKA_TEST_BOOTSTRAP` (`host:port`); without it the test is cancelled, or fails under `LLM4S_IT_STRICT=true`.
 */
@Docker
class KafkaTicketPipelineSpec extends AnyFlatSpec with Matchers {

  private val bootstrap = sys.env.get("KAFKA_TEST_BOOTSTRAP").filter(_.nonEmpty)

  private val Partitions = 3
  private val Records    = 30

  private def userText(c: Conversation): String =
    c.messages.reverseIterator.collectFirst { case m: UserMessage => m.content }.getOrElse("")

  "TicketPipeline" should "send every record to the enriched or the dead-letter topic exactly once, committing only after the produce" in {
    Tier.require(bootstrap.isDefined, "KAFKA_TEST_BOOTSTRAP not set")
    val servers = bootstrap.getOrElse("")
    val run     = System.nanoTime().toString
    val input   = s"tickets-$run"
    val output  = s"tickets-enriched-$run"
    val dead    = s"tickets-dead-letter-$run"
    val group   = s"llm4s-it-$run"

    // Every tenth record from the fourth is not JSON (a decode dead letter); from the eighth, the model's reply cannot
    // be read (a model dead letter). The rest are enriched.
    val values = (0 until Records).toVector.map { i =>
      if (i % 10 == 3) s"not json $i"
      else if (i % 10 == 7) upickle.default.write(Ticket(s"t$i", "poison"))
      else upickle.default.write(Ticket(s"t$i", s"ticket number $i"))
    }
    val model = ScriptedLLMClient.respondingTo {
      case c if userText(c).contains("poison") => Reply.text("this is not JSON")
      case _ => Reply.text("""{"category": "Account", "priority": "Low", "summary": "A ticket."}""")
    }
    val settings = TicketPipeline.Settings(
      output,
      dead,
      concurrency = 4,
      ReliabilityConfig(
        retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 10.millis),
        circuitBreaker = CircuitBreakerConfig.disabled,
        deadline = Some(30.seconds)
      ),
      commitBatchSize = 5,
      commitInterval = 200.millis
    )
    val producerSettings = ProducerSettings[IO, String, String].withBootstrapServers(servers)
    def consumerSettings(groupId: String) =
      ConsumerSettings[IO, String, String]
        .withBootstrapServers(servers)
        .withGroupId(groupId)
        .withAutoOffsetReset(AutoOffsetReset.Earliest)

    def partitions(topic: String) = (0 until Partitions).map(new TopicPartition(topic, _)).toSet

    val program = KafkaAdminClient.resource[IO](AdminClientSettings(servers)).use { admin =>
      for {
        _ <- admin.createTopics(List(input, output, dead).map(new NewTopic(_, Partitions, 1.toShort)))

        // The input: where each record landed, by its index.
        sent <- KafkaProducer.resource(producerSettings).use { producer =>
          producer
            .produce(ProducerRecords(values.zipWithIndex.map { case (v, i) => ProducerRecord(input, s"key-$i", v) }))
            .flatten
        }
        landed          = sent.toVector.map { case (_, meta) => (meta.partition, meta.offset) }
        expectedCommits = landed.groupMapReduce(_._1)(_._2 + 1)(math.max)

        // The pipeline, with `send` noting each output's source once the broker has acknowledged it.
        acknowledged <- Ref.of[IO, Set[(Int, Long)]](Set.empty)
        violations   <- Ref.of[IO, Vector[String]](Vector.empty)
        pipeline = (KafkaConsumer.resource(consumerSettings(group)), KafkaProducer.resource(producerSettings)).tupled
          .use { case (consumer, producer) =>
            val send: TicketPipeline.Send = records =>
              producer.produce(records).map { ack =>
                ack.flatTap(results => acknowledged.update(_ ++ results.toVector.map { case (r, _) => source(r) }))
              }
            consumer.subscribeTo(input) >>
              consumer.records.through(TicketPipeline.pipe(model, send, settings)).compile.drain
          }

        // Poll the group's committed offsets until they reach the end of every partition. Committed is read before
        // acknowledged, so a commit that came after its produce always finds the produce.
        committedNow = admin
          .listConsumerGroupOffsets(group)
          .partitionsToOffsetAndMetadata
          .map(
            _.collect { case (tp, om) if tp.topic == input => tp.partition -> om.offset }
          )
        check = for {
          committed <- committedNow
          acked     <- acknowledged.get
          bad = committed.toVector.flatMap { case (partition, next) =>
            landed.filter { case (p, o) => p == partition && o < next && !acked.contains((p, o)) }.map { case (p, o) =>
              s"partition $p committed to $next before offset $o was produced"
            }
          }
          _ <- violations.update(_ ++ bad)
        } yield committed
        // The pipeline runs until the commits are complete (or fails the test after 90 seconds); then it is cancelled.
        finalCommitted <- IO.race(
          pipeline,
          (IO.sleep(100.millis) >> check).iterateUntil(_ == expectedCommits).timeout(90.seconds)
        )

        // Read back both output topics in full: the end offsets of fresh topics are their record counts.
        outputs <- KafkaConsumer.resource(consumerSettings(s"$group-reader")).use { reader =>
          for {
            ends <- reader.endOffsets(partitions(output) ++ partitions(dead))
            total = ends.values.sum
            _ <- reader.subscribeTo(output, dead)
            records <- reader.records
              .map(_.record)
              .take(total)
              .compile
              .toVector
              .timeout(60.seconds)
          } yield (total, records)
        }
        found <- violations.get
      } yield (landed, finalCommitted, expectedCommits, outputs, found)
    }

    val (landed, finalCommitted, expectedCommits, (total, outputs), violations) = program.unsafeRunSync()

    finalCommitted shouldBe Right(expectedCommits)
    violations shouldBe empty
    total shouldBe Records.toLong
    // Exactly once: each input record is the source of one output, in one of the two topics.
    outputs.map(r => source(r)).sorted shouldBe landed.sorted

    val byIndex = outputs.map(r => r.key.stripPrefix("key-").toInt -> r).toMap
    byIndex.keySet shouldBe (0 until Records).toSet
    byIndex.foreach { case (i, r) =>
      if (i % 10 == 3) {
        r.topic shouldBe dead
        upickle.default.read[DeadLetter](r.value).reason shouldBe "decode"
      } else if (i % 10 == 7) {
        r.topic shouldBe dead
        upickle.default.read[DeadLetter](r.value).reason shouldBe "llm"
      } else {
        r.topic shouldBe output
        upickle.default.read[EnrichedTicket](r.value) shouldBe
          EnrichedTicket(s"t$i", s"ticket number $i", TicketLabels(Category.Account, Priority.Low, "A ticket."))
      }
    }
  }

  private def header(headers: fs2.kafka.Headers, key: String): String =
    headers(key).map(_.as[String]).getOrElse(fail(s"no $key header"))

  private def source(r: ProducerRecord[String, String]): (Int, Long) =
    (
      header(r.headers, TicketPipeline.SourcePartitionHeader).toInt,
      header(r.headers, TicketPipeline.SourceOffsetHeader).toLong
    )

  private def source(r: ConsumerRecord[String, String]): (Int, Long) =
    (
      header(r.headers, TicketPipeline.SourcePartitionHeader).toInt,
      header(r.headers, TicketPipeline.SourceOffsetHeader).toLong
    )
}
