package org.llm4s.pekko

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{ Sink, Source }
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.llm4s.error.{ LLMError, NetworkError, ValidationError }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The stage under both adapters, driven by producers that record what happens to them: order, ending,
 * backpressure, cancellation, and the producer's thread.
 */
class BlockingSourceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  implicit private val system: ActorSystem = ActorSystem("blocking-source-spec")

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds): Unit
    super.afterAll()
  }

  private val Patience = 30.seconds

  /** Emits `items`, then ends as `end` says; records its thread, how many emits it started, and its abandons. */
  final private class Emits[T](items: Seq[T], end: => Either[LLMError, Unit] = Right(())) extends Producer[T] {
    val thread: AtomicReference[Thread] = new AtomicReference[Thread](null)
    val started: AtomicInteger          = new AtomicInteger(0)
    val abandoned: AtomicInteger        = new AtomicInteger(0)
    def run(emitter: Emitter[T]): Either[LLMError, Unit] = {
      thread.set(Thread.currentThread())
      items.foreach { item =>
        started.incrementAndGet()
        emitter.emit(item)
      }
      end
    }
    def abandon(): Unit = abandoned.incrementAndGet(): Unit
  }

  /** Emits nothing and parks until it is interrupted, recording that it was. */
  final private class ParksInRun extends Producer[Int] {
    val thread: AtomicReference[Thread] = new AtomicReference[Thread](null)
    val interrupted: AtomicBoolean      = new AtomicBoolean(false)
    val abandoned: AtomicInteger        = new AtomicInteger(0)
    def run(emitter: Emitter[Int]): Either[LLMError, Unit] = {
      thread.set(Thread.currentThread())
      interrupted.set(Fixtures.parkUntilInterrupted())
      Right(())
    }
    def abandon(): Unit = abandoned.incrementAndGet(): Unit
  }

  private def source[T](capacity: Int, producer: => Producer[T]): Source[T, org.apache.pekko.NotUsed] =
    BlockingSource("blocking-source-test", capacity)(() => producer)

  private def ended(thread: Thread): Boolean = Fixtures.awaitCondition(!thread.isAlive)

  "BlockingSource" should "emit the producer's items in order, then complete" in {
    val producer = new Emits(1 to 20)
    val probe    = source(4, producer).runWith(TestSink[Int]())
    probe.within(Patience) {
      probe.request(20)
      (1 to 20).foreach(i => probe.expectNext(i))
      probe.expectComplete()
    }
  }

  it should "complete an empty stream without an element" in {
    val probe = source(4, new Emits(Seq.empty[Int])).runWith(TestSink[Int]())
    probe.within(Patience) {
      probe.request(1)
      probe.expectComplete()
    }
  }

  it should "complete exactly once, with nothing after it" in {
    val probe = source(4, new Emits(Seq(1))).runWith(TestSink[Int]())
    probe.within(Patience) {
      probe.request(5)
      probe.expectNext(1)
      probe.expectComplete()
    }
    probe.expectNoMessage(200.millis)
  }

  it should "deliver every item emitted before a failure, then fail with the producer's LLMError" in {
    val error    = NetworkError("down", None, "http://x")
    val producer = new Emits(Seq(1, 2, 3), Left(error))
    val probe    = source(8, producer).runWith(TestSink[Int]())
    probe.within(Patience) {
      probe.request(10)
      probe.expectNext(1, 2, 3)
      val failure = probe.expectError()
      failure match {
        case e: LLMException => e.error shouldBe error
        case other           => fail(s"expected an LLMException, got $other")
      }
    }
  }

  it should "fail the stream when the producer throws, instead of hanging" in {
    final class Boom extends RuntimeException("boom")
    val producer = new Producer[Int] {
      def run(emitter: Emitter[Int]): Either[LLMError, Unit] = { emitter.emit(1); throw new Boom }
      def abandon(): Unit                                    = ()
    }
    val probe = source(4, producer).runWith(TestSink[Int]())
    probe.within(Patience) {
      probe.request(5)
      probe.expectNext(1)
      probe.expectError() shouldBe a[Boom]
    }
  }

  it should "fail the stream when the producer dies of a fatal error" in {
    final class Fatal extends Error("fatal")
    val producer = new Producer[Int] {
      def run(emitter: Emitter[Int]): Either[LLMError, Unit] = throw new Fatal
      def abandon(): Unit                                    = ()
    }
    val probe = source(4, producer).runWith(TestSink[Int]())
    probe.within(Patience) {
      probe.request(1)
      probe.expectError() shouldBe a[Fatal]
    }
  }

  it should "hold the producer when the buffer is full and the consumer takes nothing, and drop nothing" in {
    val producer = new Emits(1 to 100)
    val probe    = source(4, producer).runWith(TestSink[Int]())
    // no demand yet: the producer fills the buffer (4 items) and blocks starting its 5th emit
    Fixtures.awaitCondition(producer.started.get == 5 && Fixtures.isWaiting(producer.thread.get)) shouldBe true
    producer.started.get shouldBe 5
    probe.within(Patience) {
      probe.request(100)
      (1 to 100).foreach(i => probe.expectNext(i))
      probe.expectComplete()
    }
    producer.started.get shouldBe 100
  }

  it should "keep the producer to the buffer's size ahead of a slow consumer" in {
    val producer = new Emits(1 to 100)
    val probe    = source(4, producer).runWith(TestSink[Int]())
    probe.within(Patience) {
      probe.request(2)
      probe.expectNext(1, 2)
    }
    // two taken, so at most 2 + 4 are past their emit; the producer is blocked starting the 7th
    Fixtures.awaitCondition(producer.started.get == 7 && Fixtures.isWaiting(producer.thread.get)) shouldBe true
    producer.started.get shouldBe 7
    probe.cancel()
    ended(producer.thread.get) shouldBe true
  }

  it should "interrupt a producer held by a full buffer when the consumer cancels, and abandon it once" in {
    val producer = new Emits(1 to 100)
    val probe    = source(2, producer).runWith(TestSink[Int]())
    Fixtures.awaitCondition(producer.started.get == 3 && Fixtures.isWaiting(producer.thread.get)) shouldBe true
    probe.cancel()
    ended(producer.thread.get) shouldBe true
    Fixtures.awaitCondition(producer.abandoned.get == 1) shouldBe true
    producer.started.get shouldBe 3
  }

  it should "interrupt a producer that is not emitting when the consumer cancels" in {
    val producer = new ParksInRun
    val probe    = source(2, producer).runWith(TestSink[Int]())
    probe.request(1)
    Fixtures.awaitCondition(Fixtures.isWaiting(producer.thread.get)) shouldBe true
    probe.cancel()
    ended(producer.thread.get) shouldBe true
    producer.interrupted.get shouldBe true
    Fixtures.awaitCondition(producer.abandoned.get == 1) shouldBe true
  }

  it should "stop the producer when a stage downstream finishes early" in {
    val producer = new Emits(1 to 100)
    val taken    = source(4, producer).take(3).runWith(Sink.seq)
    Await.result(taken, Patience) shouldBe Seq(1, 2, 3)
    ended(producer.thread.get) shouldBe true
    Fixtures.awaitCondition(producer.abandoned.get == 1) shouldBe true
  }

  it should "abandon the producer once when the stream completes, and end its thread" in {
    val producer = new Emits(Seq(1, 2))
    Await.result(source(4, producer).runWith(Sink.seq), Patience) shouldBe Seq(1, 2)
    Fixtures.awaitCondition(producer.abandoned.get == 1) shouldBe true
    ended(producer.thread.get) shouldBe true
    producer.abandoned.get shouldBe 1
  }

  it should "abandon the producer once when the stream fails" in {
    val producer = new Emits(Seq(1), Left(ValidationError("x", "bad")))
    Await.result(source(4, producer).runWith(Sink.seq).failed, Patience) shouldBe a[LLMException]
    Fixtures.awaitCondition(producer.abandoned.get == 1) shouldBe true
  }

  it should "run its producer on a daemon thread of its own, not a stream dispatcher thread" in {
    val producer = new Emits(Seq(1))
    Await.result(source(4, producer).runWith(Sink.seq), Patience)
    val thread = producer.thread.get
    thread.isDaemon shouldBe true
    thread.getName should startWith("blocking-source-test")
    (thread.getName should not).include("dispatcher")
  }

  it should "start a new producer for every materialization" in {
    val created = new AtomicInteger(0)
    val s = BlockingSource("blocking-source-test", 4) { () =>
      created.incrementAndGet(); new Emits(Seq(7, 8))
    }
    Await.result(s.runWith(Sink.seq), Patience) shouldBe Seq(7, 8)
    Await.result(s.runWith(Sink.seq), Patience) shouldBe Seq(7, 8)
    created.get shouldBe 2
  }

  it should "not start a producer before the stream is materialized" in {
    val created = new AtomicInteger(0)
    BlockingSource("blocking-source-test", 4) { () =>
      created.incrementAndGet(); new Emits(Seq(1))
    }
    created.get shouldBe 0
  }

  it should "fail at once, without a producer, for a buffer below 1" in {
    val created = new AtomicInteger(0)
    val s = BlockingSource("blocking-source-test", 0) { () =>
      created.incrementAndGet(); new Emits(Seq(1))
    }
    Await.result(s.runWith(Sink.seq).failed, Patience) match {
      case e: LLMException => e.error shouldBe a[ValidationError]
      case other           => fail(s"expected an LLMException, got $other")
    }
    created.get shouldBe 0
  }
}
