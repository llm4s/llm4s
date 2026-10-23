package org.llm4s.agent

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.graph.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class AgentEventBufferSpec extends AnyFlatSpec with TestTimeLimit with Matchers:

  private def durable(seq: Long, event: RunEvent): StreamEvent =
    StreamEvent.Durable(EventRecord("t", seq, "r", FencingToken(1L), None, None, None, Instant.EPOCH, event))

  private def live(n: Int): StreamEvent =
    StreamEvent.Live("t", "r", FencingToken(1L), "task", "node", "agent.text_delta", 1, ujson.Num(n))

  /** Calls the listener on another thread, failing if it has not returned within a second: it never blocks. */
  private def offer(buffer: AgentEventBuffer, event: StreamEvent): Unit =
    val caller = Thread.ofVirtual().start(() => buffer.listener(event))
    caller.join(1000)
    withClue(s"the listener blocked on $event: ")(caller.isAlive shouldBe false)

  private def errorOf(buffer: AgentEventBuffer): String =
    buffer.take().swap.toOption.map(_.message).getOrElse(fail("expected the hand-over to have failed"))

  private def takeAll(buffer: AgentEventBuffer): Vector[StreamEvent] =
    Iterator.continually(buffer.take()).takeWhile(_.exists(_.isDefined)).flatMap(_.toOption.flatten).toVector

  "AgentEventBuffer" should "hand over events in order and end after the terminal event" in {
    val buffer   = AgentEventBuffer(8)
    val started  = durable(1, RunEvent.RunStarted(None, None))
    val finished = durable(2, RunEvent.RunCompleted)
    buffer.listener(started)
    buffer.listener(finished)
    buffer.take() shouldBe Right(Some(started))
    buffer.take() shouldBe Right(Some(finished))
    buffer.take() shouldBe Right(None)
  }

  it should "fail on a Lagging disconnect" in {
    val buffer = AgentEventBuffer(8)
    buffer.listener(StreamEvent.Disconnected(3L, DisconnectReason.Lagging))
    buffer.take().isLeft shouldBe true
  }

  it should "never block the listener: live events past capacity are dropped and reported as a LiveGap where they were dropped" in {
    val buffer = AgentEventBuffer(2)
    offer(buffer, durable(1, RunEvent.RunStarted(None, None)))
    (1 to 5).foreach(i => offer(buffer, live(i)))
    offer(buffer, durable(2, RunEvent.TaskCompleted))
    offer(buffer, live(6)) // still full: nothing has been taken
    offer(buffer, durable(3, RunEvent.RunCompleted))
    takeAll(buffer) shouldBe Vector(
      durable(1, RunEvent.RunStarted(None, None)),
      live(1),
      live(2),
      StreamEvent.LiveGap(3),
      durable(2, RunEvent.TaskCompleted),
      StreamEvent.LiveGap(1),
      durable(3, RunEvent.RunCompleted)
    )
  }

  it should "queue durable events past the live capacity" in {
    val buffer = AgentEventBuffer(1)
    val events = (1 to 5).map(i => durable(i.toLong, RunEvent.TaskCompleted)) :+ durable(6, RunEvent.RunCompleted)
    events.foreach(offer(buffer, _))
    takeAll(buffer) shouldBe events.toVector
  }

  it should "end with a Left naming the last seq taken once more durable events wait than its durable capacity" in {
    val buffer = AgentEventBuffer(1, durableCapacity = 3)
    offer(buffer, durable(1, RunEvent.RunStarted(None, None)))
    buffer.take() shouldBe Right(Some(durable(1, RunEvent.RunStarted(None, None))))
    (2 to 4).foreach(i => offer(buffer, durable(i.toLong, RunEvent.TaskCompleted)))
    offer(buffer, durable(5, RunEvent.TaskCompleted)) // a fourth waiting: never blocks, ends the hand-over
    offer(buffer, durable(6, RunEvent.RunCompleted))  // ignored after the end
    takeAll(buffer) shouldBe (2 to 4).map(i => durable(i.toLong, RunEvent.TaskCompleted)).toVector
    // the consumer's position when the error is reported - the last queued event it took - not
    // where it was when the bound was crossed (seq 1): resuming from there would repeat 2 to 4
    errorOf(buffer) should endWith("after seq 4")
  }

  it should "name the consumer's position, not the subscription's, for a kernel disconnect" in {
    val buffer = AgentEventBuffer(4)
    (1 to 3).foreach(i => offer(buffer, durable(i.toLong, RunEvent.TaskCompleted)))
    // the kernel's lastSeq counts what its listener returned from, events of other runs included
    offer(buffer, StreamEvent.Disconnected(9L, DisconnectReason.Lagging))
    buffer.take() shouldBe Right(Some(durable(1, RunEvent.TaskCompleted)))
    buffer.take() shouldBe Right(Some(durable(2, RunEvent.TaskCompleted)))
    buffer.take() shouldBe Right(Some(durable(3, RunEvent.TaskCompleted)))
    val message = errorOf(buffer)
    message should include("Lagging")
    message should endWith("after seq 3")
  }

  it should "name the last seq taken, not what a close dropped, once closed after a failure" in {
    val buffer = AgentEventBuffer(4, durableCapacity = 2)
    (1 to 2).foreach(i => offer(buffer, durable(i.toLong, RunEvent.TaskCompleted)))
    buffer.take() shouldBe Right(Some(durable(1, RunEvent.TaskCompleted)))
    (3 to 4).foreach(i => offer(buffer, durable(i.toLong, RunEvent.TaskCompleted))) // 2 and 3 wait; 4 ends it
    buffer.close() // drops 2 and 3, which the consumer never took
    errorOf(buffer) should endWith("after seq 1")
  }

  it should "name seq 0 when the consumer took no durable event" in {
    val buffer = AgentEventBuffer(4)
    offer(buffer, StreamEvent.Disconnected(5L, DisconnectReason.Lagging))
    errorOf(buffer) should endWith("after seq 0")
  }

  it should "count only the durable events still waiting against its durable capacity" in {
    val buffer = AgentEventBuffer(1, durableCapacity = 2)
    val events = (1 to 5).map(i => durable(i.toLong, RunEvent.TaskCompleted)) :+ durable(6, RunEvent.RunCompleted)
    val taken = events.toVector.map { event =>
      offer(buffer, event)
      buffer.take()
    }
    taken shouldBe events.toVector.map(e => Right(Some(e)))
  }

  it should "count the space taken live events free, and add a kernel LiveGap that does not fit to its own count" in {
    val buffer = AgentEventBuffer(1)
    offer(buffer, live(1))
    offer(buffer, StreamEvent.LiveGap(4)) // the kernel's, while full
    offer(buffer, live(2))
    buffer.take() shouldBe Right(Some(live(1)))
    offer(buffer, live(3))
    buffer.end()
    takeAll(buffer) shouldBe Vector(StreamEvent.LiveGap(5), live(3))
  }

  it should "report live events dropped at the end of the run before ending" in {
    val buffer = AgentEventBuffer(1)
    offer(buffer, live(1))
    offer(buffer, live(2))
    offer(buffer, live(3))
    buffer.end()
    takeAll(buffer) shouldBe Vector(live(1), StreamEvent.LiveGap(2))
    buffer.take() shouldBe Right(None)
  }

  /** Waits until `t` is parked - blocked in `take` - so the wake-up, not an early return, is what is tested. */
  private def awaitBlocked(t: Thread): Unit =
    val deadline = System.nanoTime() + 10_000_000_000L
    while t.getState != Thread.State.WAITING && t.getState != Thread.State.TIMED_WAITING && System.nanoTime() < deadline
    do Thread.sleep(1)
    t.getState should (be(Thread.State.WAITING).or(be(Thread.State.TIMED_WAITING)))

  it should "wake a blocked take when closed" in {
    val buffer = AgentEventBuffer(4)
    val result = new AtomicReference[Any](null)
    val taker  = new Thread(() => result.set(buffer.take()))
    taker.start()
    awaitBlocked(taker)
    buffer.close()
    taker.join(10_000)
    result.get shouldBe Right(None)
  }

  it should "end without a terminal event once ended, after what was queued before the end" in {
    val buffer  = AgentEventBuffer(4)
    val started = durable(1, RunEvent.RunStarted(None, None))
    buffer.listener(started)
    buffer.end()
    buffer.take() shouldBe Right(Some(started))
    buffer.take() shouldBe Right(None)
  }

  it should "wake a blocked take when ended" in {
    val buffer = AgentEventBuffer(4)
    val result = new AtomicReference[Any](null)
    val taker  = new Thread(() => result.set(buffer.take()))
    taker.start()
    awaitBlocked(taker)
    buffer.end()
    taker.join(10_000)
    result.get shouldBe Right(None)
  }

  it should "end the stream with a Left after a ListenerFailed disconnect, once the queue is taken" in {
    val buffer  = AgentEventBuffer(4)
    val started = durable(1, RunEvent.RunStarted(None, None))
    buffer.listener(started)
    buffer.listener(StreamEvent.Disconnected(1L, DisconnectReason.ListenerFailed(new RuntimeException("x"))))
    buffer.take() shouldBe Right(Some(started))
    buffer.take().isLeft shouldBe true
  }

  it should "return the disconnect's Left after a close, and Right(None) after a close without one" in {
    val failed = AgentEventBuffer(4)
    failed.listener(StreamEvent.Disconnected(1L, DisconnectReason.Lagging))
    failed.close()
    failed.take().isLeft shouldBe true
    val closed = AgentEventBuffer(4)
    closed.listener(durable(1, RunEvent.RunStarted(None, None)))
    closed.close()
    closed.take() shouldBe Right(None)
  }
