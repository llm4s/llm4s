package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class AgentEventBufferSpec extends AnyFlatSpec with Matchers:

  private def durable(seq: Long, event: RunEvent): StreamEvent =
    StreamEvent.Durable(EventRecord("t", seq, "r", None, None, None, Instant.EPOCH, event))

  private def live(n: Int): StreamEvent =
    StreamEvent.Live("t", "r", "task", "node", "agent.text_delta", 1, ujson.Num(n))

  /** Calls the listener on another thread, failing if it has not returned within a second: it never blocks. */
  private def offer(buffer: AgentEventBuffer, event: StreamEvent): Unit =
    val caller = Thread.ofVirtual().start(() => buffer.listener(event))
    caller.join(1000)
    withClue(s"the listener blocked on $event: ")(caller.isAlive shouldBe false)

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

  it should "always queue durable events, past capacity too" in {
    val buffer = AgentEventBuffer(1)
    val events = (1 to 5).map(i => durable(i.toLong, RunEvent.TaskCompleted)) :+ durable(6, RunEvent.RunCompleted)
    events.foreach(offer(buffer, _))
    takeAll(buffer) shouldBe events.toVector
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

  it should "wake a blocked take when closed" in {
    val buffer = AgentEventBuffer(4)
    val result = new AtomicReference[Any](null)
    val taker  = new Thread(() => result.set(buffer.take()))
    taker.start()
    Thread.sleep(50)
    buffer.close()
    taker.join(1000)
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
    Thread.sleep(50)
    buffer.end()
    taker.join(1000)
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
