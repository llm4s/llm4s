package org.llm4s.ox

import _root_.ox.{ fork, supervised }
import _root_.ox.channels.BufferCapacity
import org.llm4s.error.{ CancelledError, SimpleError }
import org.llm4s.llmconnect.model.StreamedChunk
import org.llm4s.ox.Clients.*
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.concurrent.{ Signaler, ThreadSignaler, TimeLimitedTests }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Minutes, Span }

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.util.Try

class StreamFlowSpec extends AnyFlatSpec with Matchers with TimeLimitedTests {

  override def timeLimit: Span               = Span(2, Minutes)
  override val defaultTestSignaler: Signaler = ThreadSignaler

  "streamFlow" should "deliver the provider's chunks in order" in {
    val client = ScriptedLLMClient.sequence(Reply.text("abcdef")).withChunkSize(1)
    client.streamFlow(conversation).textDeltas.runToList() shouldBe List("a", "b", "c", "d", "e", "f")
    client.lastCall.map(_.streamed) shouldBe Some(true)
  }

  it should "run the provider call on a virtual thread, not the consumer's" in {
    val recording = new Recording(ScriptedLLMClient.sequence(Reply.text("abc")))
    recording.streamFlow(conversation).runDrain()
    val producer = recording.thread.get().getOrElse(fail("the provider was never called"))
    producer.isVirtual shouldBe true
    (producer should not).be(theSameInstanceAs(Thread.currentThread()))
  }

  it should "make one provider call per run" in {
    val client = ScriptedLLMClient.always(Reply.text("hi"))
    val flow   = client.streamFlow(conversation)
    flow.textDeltas.runToList() shouldBe List("hi")
    flow.textDeltas.runToList() shouldBe List("hi")
    client.callCount shouldBe 2
  }

  it should "interrupt the provider call when the consumer stops early, and leave no thread running" in {
    given BufferCapacity = BufferCapacity(1)
    val recording        = new Recording(ScriptedLLMClient.sequence(Reply.text("x" * 5000)).withChunkSize(1))
    recording.streamFlow(conversation).take(2).runToList().flatMap(_.content) shouldBe List("x", "x")
    recording.outcome.get() match {
      case Some(Left(_: CancelledError)) => succeed
      case other                         => fail(s"the provider call was not cancelled: $other")
    }
    recording.thread.get().exists(_.isAlive) shouldBe false
  }

  it should "interrupt the provider call when the consumer's scope ends" in {
    val parking   = new Parking(Seq(StreamedChunk("id", Some("first"))))
    val recording = new Recording(parking)
    val received  = new CountDownLatch(1)
    supervised {
      fork(recording.streamFlow(conversation).runForeach(_ => received.countDown())): Unit
      received.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    }
    recording.outcome.get() match {
      case Some(Left(_: CancelledError)) => succeed
      case other                         => fail(s"the provider call was not cancelled: $other")
    }
    recording.thread.get().exists(_.isAlive) shouldBe false
  }

  it should "refuse every later chunk at once when the provider ignores the interrupt" in {
    given BufferCapacity = BufferCapacity(1)
    val deaf             = new Deaf(total = 10000)
    deaf.streamFlow(conversation).take(1).runToList().flatMap(_.content) shouldBe List("x")
    deaf.offered.get() shouldBe 10000
    deaf.delivered.get() should be < 10
  }

  it should "fail with an LLMException carrying the provider's LLMError, after the chunks before it" in {
    val error  = SimpleError("connection reset mid-stream")
    val chunks = Seq(StreamedChunk("id", Some("a")), StreamedChunk("id", Some("b")))
    val client = new FailingAfter(chunks, error)
    val seen   = List.newBuilder[String]
    val failed = Try(client.streamFlow(conversation).textDeltas.runForeach(seen += _))
    seen.result() shouldBe List("a", "b")
    failed.failed.toOption match {
      case Some(e: LLMException) =>
        (e.error should be).theSameInstanceAs(error)
        e.getMessage shouldBe error.message
      case other => fail(s"expected an LLMException, got $other")
    }
  }

  it should "fail with the exception a provider throws" in {
    val boom   = new IllegalStateException("provider bug")
    val failed = Try(new Throwing(Seq(StreamedChunk("id", Some("a"))), boom).streamFlow(conversation).runDrain())
    failed.failed.toOption.map(_.getMessage) shouldBe Some("provider bug")
  }

  "textDeltas" should "keep only the chunks that carry text" in {
    val client = ScriptedLLMClient.sequence(Reply.text("hi").withToolCall("lookup", """{"q": 1}"""))
    client.streamFlow(conversation).textDeltas.runToList() shouldBe List("hi")
  }

  "accumulate" should "return the completion the chunks describe" in {
    val client = ScriptedLLMClient.sequence(Reply.text("hello world").withToolCall("lookup", """{"q": 1}"""))
    val result = client.withChunkSize(3).streamFlow(conversation).accumulate
    result.map(_.content) shouldBe Right("hello world")
    result.map(_.message.toolCalls.map(_.name)) shouldBe Right(Seq("lookup"))
  }

  it should "return the provider's LLMError as a Left" in {
    val error = SimpleError("stream refused")
    ScriptedLLMClient.sequence(Reply.failure(error)).streamFlow(conversation).accumulate shouldBe Left(error)
  }

  it should "propagate an exception that carries no LLMError" in {
    val boom = new IllegalStateException("provider bug")
    val thrown = intercept[IllegalStateException](
      new Throwing(Nil, boom).streamFlow(conversation).accumulate
    )
    thrown.getMessage shouldBe "provider bug"
  }
}
