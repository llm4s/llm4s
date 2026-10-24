package org.llm4s.deploy

import org.llm4s.agent.service.{ CancelTarget, EntryId, QueueEntry }
import org.llm4s.testutil.TestTimeLimit
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

/**
 * A run's event stream ends promptly when its entry closes (#2396), with the default `events.wait` (15 seconds): closing
 * an entry commits no event, so a stream that waited only for commits would send `end` up to that long after the close.
 * Each case follows an entry over [[AgentRoutes.EventStream]] on a thread of its own and measures from the close to the
 * stream's end: a run that completes, one cancelled while it runs, and one an `interrupt` submission stops.
 */
class EventStreamEndSpec extends AnyFlatSpec with TestTimeLimit with Matchers with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(20, Millis))

  /** How soon after its entry closes a stream must end: far below the 15-second wait. */
  private val EndBound = 2.seconds

  private def thread(): String = s"e-${java.util.UUID.randomUUID().toString.take(8)}"

  private def entryId(reply: Reply): EntryId = {
    withClue(reply)(reply.status shouldBe 202)
    AgentService.entryIdOf(reply.body("entryId").str).toOption.get
  }

  /** Entry `id`'s stream, written on a thread of its own, and when it ended (`System.nanoTime`). */
  final private class Followed(fixture: ServiceFixture, id: EntryId) {
    private val out     = new ByteArrayOutputStream()
    private val endedAt = new AtomicLong(0L)
    private val writer = new Thread(() => {
      new AgentRoutes.EventStream(fixture.service, fixture.config, None, id, 0L).writeBytesTo(out)
      endedAt.set(System.nanoTime())
    })
    writer.setDaemon(true)
    writer.start()

    /** Waits for the stream to end and answers its last event's name and data, and how long after `closedAt` it ended. */
    def end(closedAt: Long): (String, ujson.Value, FiniteDuration) = {
      writer.join(EndBound.toMillis + 5000L)
      withClue("the stream is still open: ")(writer.isAlive shouldBe false)
      val last = new String(out.toByteArray, StandardCharsets.UTF_8).split("\n\n").toVector.last
      val fields = last.linesIterator.map { line =>
        val (k, v) = line.span(_ != ':')
        k -> v.drop(1).trim
      }.toMap
      (fields("event"), ujson.read(fields("data")), (endedAt.get() - closedAt).nanos)
    }
  }

  /** Waits for entry `id` to close and answers it and when the wait saw the close (`System.nanoTime`). */
  private def closed(fixture: ServiceFixture, id: EntryId): (QueueEntry, Long) = {
    val entry = fixture.submitter.await(None, id, 30.seconds).toOption.get
    val at    = System.nanoTime()
    entry.isOpen shouldBe false
    (entry, at)
  }

  /** Waits until a worker has taken entry `id` and started its run. */
  private def running(fixture: ServiceFixture, id: EntryId): Unit =
    eventually(fixture.submitter.entry(None, id).toOption.get.attempts should not be empty)

  private def withFixture(toolDelay: FiniteDuration)(test: ServiceFixture => Unit): Unit = {
    val fixture = new ServiceFixture(toolDelay = toolDelay, eventsWait = AgentServiceConfig.default.eventsWait)
    fixture.config.eventsWait shouldBe 15.seconds
    try test(fixture)
    finally fixture.stop()
  }

  "a run's event stream" should "send end promptly once the run completes and its entry closes" in
    withFixture(toolDelay = 300.millis) { fixture =>
      val id                   = entryId(fixture.service.submit(None, thread(), """{"query": "done soon"}""", None))
      val followed             = new Followed(fixture, id)
      val (_, at)              = closed(fixture, id)
      val (event, data, after) = followed.end(at)
      event shouldBe "end"
      data("state").str shouldBe "done"
      after should be < EndBound
    }

  it should "send end promptly once its entry is cancelled while it runs" in
    withFixture(toolDelay = 5.seconds) { fixture =>
      val id       = entryId(fixture.service.submit(None, thread(), """{"query": "cancel me"}""", None))
      val followed = new Followed(fixture, id)
      running(fixture, id)
      fixture.submitter.cancel(None, CancelTarget.Entry(id)).isRight shouldBe true
      val (_, at)              = closed(fixture, id)
      val (event, data, after) = followed.end(at)
      event shouldBe "end"
      data("state").str shouldBe "cancelled"
      after should be < EndBound
    }

  it should "send end promptly once its entry is interrupted by a later submission" in
    withFixture(toolDelay = 1.second) { fixture =>
      val t        = thread()
      val id       = entryId(fixture.service.submit(None, t, """{"query": "interrupt me"}""", None))
      val followed = new Followed(fixture, id)
      running(fixture, id)
      entryId(fixture.service.submit(None, t, """{"query": "instead", "policy": "interrupt"}""", None)): Unit
      val (_, at)              = closed(fixture, id)
      val (event, data, after) = followed.end(at)
      event shouldBe "end"
      data("state").str shouldBe "interrupted"
      after should be < EndBound
    }
}
