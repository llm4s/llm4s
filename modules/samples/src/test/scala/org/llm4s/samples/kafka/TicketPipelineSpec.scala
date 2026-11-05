package org.llm4s.samples.kafka

import cats.effect.{ IO, Ref }
import cats.effect.unsafe.implicits.global
import fs2.Stream
import fs2.kafka.{ CommittableConsumerRecord, CommittableOffset, ConsumerRecord, ProducerRecord }
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.llm4s.error.{ AuthenticationError, RateLimitError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.reliability.{ CircuitBreakerConfig, ReliabilityConfig, RetryPolicy }
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * The pipeline of docs/guide/kafka.md over an fs2 stream, with no broker and no model (#2297): consumer records are
 * built in memory, `send` and each offset's commit append to one event log, and the model is a `ScriptedLLMClient`.
 * `KafkaTicketPipelineSpec` in modules/it runs the same pipeline against a real broker.
 */
class TicketPipelineSpec extends AnyFlatSpec with Matchers with LoneElement {

  import TicketPipelineSpec.*

  "TicketPipeline" should "enrich every ticket and commit each partition's next offset" in {
    val inputs = List(
      Input(0, 0, ticket("t1")),
      Input(1, 0, ticket("t2")),
      Input(0, 1, ticket("t3")),
      Input(1, 1, ticket("t4")),
      Input(0, 2, ticket("t5"))
    )
    val run = runPipeline(ScriptedLLMClient.always(labels), inputs)

    run.result shouldBe Right(())
    run.produced.map(_.topic).distinct shouldBe Vector(OutputTopic)
    run.produced
      .map(p => upickle.default.read[EnrichedTicket](p.value).id) shouldBe Vector("t1", "t2", "t3", "t4", "t5")
    run.produced.map(p => upickle.default.read[EnrichedTicket](p.value).labels).distinct shouldBe
      Vector(TicketLabels(Category.Billing, Priority.High, "Charged twice."))
    // The offset to commit is the next one to read: one past each partition's last record.
    run.finalCommitted shouldBe Map(0 -> 3L, 1 -> 2L)
    run.commitsFollowTheirProduce shouldBe true
  }

  it should "send the ticket to the model as a structured-output request" in {
    val client = ScriptedLLMClient.always(labels)
    runPipeline(client, List(Input(0, 0, ticket("t1", "I was charged twice"))))

    val call = client.calls.loneElement
    call.lastUserText.getOrElse("") should include("I was charged twice")
    call.options.responseFormat should not be empty
  }

  it should "retry a rate limit in the client and then enrich" in {
    val client = ScriptedLLMClient.sequence(Reply.failure(RateLimitError("scripted", 1.milli)), labels)
    val run    = runPipeline(client, List(Input(0, 0, ticket("t1"))))

    run.result shouldBe Right(())
    client.callCount shouldBe 2
    run.produced.map(_.topic) shouldBe Vector(OutputTopic)
    run.finalCommitted shouldBe Map(0 -> 1L)
  }

  it should "dead-letter a value that is not a ticket, without calling the model, and carry on" in {
    val client = ScriptedLLMClient.always(labels)
    val run    = runPipeline(client, List(Input(0, 0, "not json"), Input(0, 1, ticket("t2"))))

    run.result shouldBe Right(())
    client.callCount shouldBe 1
    run.produced.map(_.topic) shouldBe Vector(DeadLetterTopic, OutputTopic)
    val letter = upickle.default.read[DeadLetter](run.produced.head.value)
    letter.reason shouldBe "decode"
    letter.value shouldBe "not json"
    (letter.topic, letter.partition, letter.offset, letter.key) shouldBe ((InputTopic, 0, 0L, "key-0-0"))
    run.finalCommitted shouldBe Map(0 -> 2L)
  }

  it should "dead-letter a model error that retrying does not fix, without retrying it" in {
    val client = ScriptedLLMClient.respondingTo {
      case c if userText(c).contains("t1") => Reply.failure(AuthenticationError("scripted", "bad key"))
      case c if userText(c).contains("t2") => Reply.text("this is not JSON")
      case _                               => labels
    }
    val run = runPipeline(client, List(Input(0, 0, ticket("t1")), Input(0, 1, ticket("t2")), Input(0, 2, ticket("t3"))))

    run.result shouldBe Right(())
    client.callCount shouldBe 3
    run.produced.map(_.topic) shouldBe Vector(DeadLetterTopic, DeadLetterTopic, OutputTopic)
    val letters = run.produced.take(2).map(p => upickle.default.read[DeadLetter](p.value))
    letters.map(_.reason) shouldBe Vector("llm", "llm")
    letters.head.error should startWith("AuthenticationError")
    letters(1).error should startWith("ValidationError")
    run.finalCommitted shouldBe Map(0 -> 3L)
  }

  it should "stop without producing or committing a record whose transient error outlives the retries" in {
    val client = ScriptedLLMClient.respondingTo {
      case c if userText(c).contains("t2") => Reply.failure(RateLimitError("scripted", 1.milli))
      case _                               => labels
    }
    val run = runPipeline(client, List(Input(0, 0, ticket("t1")), Input(0, 1, ticket("t2")), Input(0, 2, ticket("t3"))))

    run.result.left.map(_.getClass) shouldBe Left(classOf[TicketPipeline.TransientFailure])
    run.result.left.toOption.collect { case f: TicketPipeline.TransientFailure => f.offset } shouldBe Some(1L)
    client.calls.count(c => c.lastUserText.exists(_.contains("t2"))) shouldBe 3 // the policy's three attempts
    run.produced.map(_.sourceOffset) should not contain 1L
    run.committed.flatMap(_.get(0)).foreach(_ should be <= 1L)
  }

  it should "run at most `concurrency` calls at once, and keep input order however long each call takes" in {
    val inFlight    = new AtomicInteger(0)
    val maxInFlight = new AtomicInteger(0)
    // The first ticket is the slowest and the last the fastest, so an unordered pipeline would reverse them.
    val client = new SlowClient(ScriptedLLMClient.always(labels), inFlight, maxInFlight)
    val inputs = (0 until 9).toList.map(i => Input(0, i.toLong, ticket(s"t$i", s"delay ${(9 - i) * 20}")))
    val run    = runPipeline(client, inputs, concurrency = 3)

    run.result shouldBe Right(())
    maxInFlight.get should ((be > 1).and(be <= 3))
    run.produced.map(_.sourceOffset) shouldBe (0L until 9L).toVector
    run.finalCommitted shouldBe Map(0 -> 9L)
    run.commitsFollowTheirProduce shouldBe true
  }
}

object TicketPipelineSpec {

  val InputTopic      = "tickets"
  val OutputTopic     = "tickets-enriched"
  val DeadLetterTopic = "tickets-dead-letter"

  val labels: Reply = Reply.text("""{"category": "Billing", "priority": "High", "summary": "Charged twice."}""")

  def ticket(id: String, text: String = "My card was charged twice"): String =
    upickle.default.write(Ticket(id, text))

  def userText(c: Conversation): String =
    c.messages.reverseIterator.collectFirst { case m: UserMessage => m.content }.getOrElse("")

  final case class Input(partition: Int, offset: Long, value: String)

  enum Event {
    case Produced(topic: String, sourcePartition: Int, sourceOffset: Long, value: String)
    case Committed(offsets: Map[Int, Long])
  }

  final case class Run(result: Either[Throwable, Unit], events: Vector[Event]) {
    def produced: Vector[Event.Produced]  = events.collect { case p: Event.Produced => p }
    def committed: Vector[Map[Int, Long]] = events.collect { case Event.Committed(o) => o }
    def finalCommitted: Map[Int, Long]    = committed.foldLeft(Map.empty[Int, Long])(_ ++ _)

    /** Every commit comes after the output of every record it covers on that partition. */
    def commitsFollowTheirProduce: Boolean =
      events.zipWithIndex.forall {
        case (Event.Committed(offsets), at) =>
          val before = events.take(at).collect { case p: Event.Produced => (p.sourcePartition, p.sourceOffset) }.toSet
          offsets.forall { case (partition, next) => (0L until next).forall(o => before.contains((partition, o))) }
        case _ => true
      }
  }

  private val settings = TicketPipeline.Settings(
    OutputTopic,
    DeadLetterTopic,
    concurrency = 4,
    ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 1.milli),
      circuitBreaker = CircuitBreakerConfig.disabled,
      deadline = Some(30.seconds)
    ),
    commitBatchSize = 2,
    commitInterval = 1.second
  )

  def runPipeline(client: LLMClient, inputs: List[Input], concurrency: Int = 4): Run =
    (for {
      log <- Ref.of[IO, Vector[Event]](Vector.empty)
      records = inputs.map(committable(log, _))
      result <- Stream
        .emits(records)
        .through(TicketPipeline.pipe(client, send(log), settings.copy(concurrency = concurrency)))
        .compile
        .drain
        .attempt
      events <- log.get
    } yield Run(result, events)).unsafeRunSync()

  private def committable(log: Ref[IO, Vector[Event]], in: Input): CommittableConsumerRecord[IO, String, String] =
    CommittableConsumerRecord(
      ConsumerRecord(InputTopic, in.partition, in.offset, s"key-${in.partition}-${in.offset}", in.value),
      CommittableOffset[IO](
        new TopicPartition(InputTopic, in.partition),
        new OffsetAndMetadata(in.offset + 1),
        Some("test-group"),
        offsets => log.update(_ :+ Event.Committed(offsets.map { case (tp, om) => tp.partition -> om.offset }))
      )
    )

  /** Acknowledges each record at once, logging it when acknowledged (the inner effect), as the broker would. */
  private def send(log: Ref[IO, Vector[Event]]): TicketPipeline.Send =
    records =>
      IO.pure {
        val produced = records.toVector.map { r =>
          Event.Produced(
            r.topic,
            header(r, TicketPipeline.SourcePartitionHeader).toInt,
            header(r, TicketPipeline.SourceOffsetHeader).toLong,
            r.value
          )
        }
        log
          .update(_ ++ produced)
          .as(records.map(r => r -> new RecordMetadata(new TopicPartition(r.topic, 0), 0L, 0, 0L, 0, 0)))
      }

  private def header(r: ProducerRecord[String, String], key: String): String =
    r.headers(key).map(_.as[String]).getOrElse(sys.error(s"no $key header"))

  /** Sleeps for the `delay N` in the ticket text, and records how many calls overlap. */
  final class SlowClient(underlying: LLMClient, inFlight: AtomicInteger, maxInFlight: AtomicInteger) extends LLMClient {
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val now = inFlight.incrementAndGet()
      maxInFlight.accumulateAndGet(now, math.max)
      val delay = "delay (\\d+)".r.findFirstMatchIn(userText(conversation)).map(_.group(1).toLong).getOrElse(0L)
      Thread.sleep(delay)
      inFlight.decrementAndGet()
      underlying.complete(conversation, options)
    }
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = underlying.streamComplete(conversation, options, onChunk)
    def getContextWindow(): Int     = underlying.getContextWindow()
    def getReserveCompletion(): Int = underlying.getReserveCompletion()
  }
}
