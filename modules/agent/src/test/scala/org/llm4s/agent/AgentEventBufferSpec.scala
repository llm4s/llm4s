package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class AgentEventBufferSpec extends AnyFlatSpec with Matchers:

  private def durable(seq: Long, event: RunEvent): StreamEvent =
    StreamEvent.Durable(EventRecord("t", seq, "r", None, None, None, Instant.EPOCH, event))

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

  it should "block the listener while full, until taken" in {
    val buffer = AgentEventBuffer(1)
    buffer.listener(durable(1, RunEvent.RunStarted(None, None)))
    val second = new Thread(() => buffer.listener(durable(2, RunEvent.RunCompleted)))
    second.start()
    Thread.sleep(100)
    second.isAlive shouldBe true
    buffer.take()
    second.join(1000)
    second.isAlive shouldBe false
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

  it should "release a listener blocked on a full buffer when closed" in {
    val buffer = AgentEventBuffer(1)
    buffer.listener(durable(1, RunEvent.RunStarted(None, None)))
    val second = new Thread(() => buffer.listener(durable(2, RunEvent.RunCompleted)))
    second.start()
    Thread.sleep(100)
    second.isAlive shouldBe true
    buffer.close()
    second.join(1000)
    second.isAlive shouldBe false
    buffer.take() shouldBe Right(None)
  }

  it should "end the stream with a Left after a ListenerFailed disconnect, once the queue is taken" in {
    val buffer  = AgentEventBuffer(4)
    val started = durable(1, RunEvent.RunStarted(None, None))
    buffer.listener(started)
    buffer.listener(StreamEvent.Disconnected(1L, DisconnectReason.ListenerFailed(new RuntimeException("x"))))
    buffer.take() shouldBe Right(Some(started))
    buffer.take().isLeft shouldBe true
  }
