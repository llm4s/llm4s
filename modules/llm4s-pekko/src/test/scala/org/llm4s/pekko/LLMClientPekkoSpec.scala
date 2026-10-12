package org.llm4s.pekko

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.testkit.scaladsl.TestSink
import org.llm4s.error.{ CancelledError, NetworkError, ValidationError }
import org.llm4s.llmconnect.model.StreamedChunk
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration.*

class LLMClientPekkoSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  implicit private val system: ActorSystem = ActorSystem("llm-client-pekko-spec")
  private val ec: ExecutionContext         = ExecutionContext.global

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds): Unit
    super.afterAll()
  }

  private val Patience = 30.seconds

  private def contents(chunks: Seq[StreamedChunk]): Seq[String] = chunks.map(_.id)

  private def chunksClient(n: Int): Fixtures.Scripted =
    new Fixtures.Scripted(onChunk => {
      (0 until n).foreach(i => onChunk(Fixtures.chunk(i)))
      Right(Fixtures.completion)
    })

  "LLMClientPekko.streamComplete" should "emit the provider's chunks in order, then complete" in {
    val chunks = Await.result(
      LLMClientPekko(chunksClient(5)).streamComplete(Fixtures.conversation).runWith(Sink.seq),
      Patience
    )
    contents(chunks) shouldBe (0 until 5).map(i => s"c$i")
  }

  it should "complete an empty stream" in {
    val chunks = Await.result(
      LLMClientPekko(chunksClient(0)).streamComplete(Fixtures.conversation).runWith(Sink.seq),
      Patience
    )
    chunks shouldBe empty
  }

  it should "pass the conversation and the options to the provider" in {
    val seen = new java.util.concurrent.atomic.AtomicReference[(Int, Double)]((-1, Double.NaN))
    val client = new LLMClientStub((conversation, options, _) => {
      seen.set((conversation.messages.size, options.temperature))
      Right(Fixtures.completion)
    })
    val options = org.llm4s.llmconnect.model.CompletionOptions(temperature = 0.25)
    Await.result(LLMClientPekko(client).streamComplete(Fixtures.conversation, options).runWith(Sink.seq), Patience)
    seen.get shouldBe ((1, 0.25))
  }

  it should "emit the chunks received before a failure, then fail with the LLMError" in {
    val error = NetworkError("down", None, "http://x")
    val client = new Fixtures.Scripted(onChunk => {
      onChunk(Fixtures.chunk(0))
      onChunk(Fixtures.chunk(1))
      Left(error)
    })
    val probe = LLMClientPekko(client).streamComplete(Fixtures.conversation).runWith(TestSink[StreamedChunk]())
    probe.within(Patience) {
      probe.request(10)
      probe.expectNext().id shouldBe "c0"
      probe.expectNext().id shouldBe "c1"
      probe.expectError() match {
        case e: LLMException => e.error shouldBe error
        case other           => fail(s"expected an LLMException, got $other")
      }
    }
  }

  it should "fail with the LLMError when the provider fails before any chunk" in {
    val client = new Fixtures.Scripted(_ => Left(NetworkError("down", None, "http://x")))
    val failure = Await.result(
      LLMClientPekko(client).streamComplete(Fixtures.conversation).runWith(Sink.seq).failed,
      Patience
    )
    failure match {
      case e: LLMException => e.error shouldBe a[NetworkError]
      case other           => fail(s"expected an LLMException, got $other")
    }
  }

  it should "fail the stream when the provider throws instead of returning a Left" in {
    final class Thrown extends RuntimeException("thrown")
    val client = new Fixtures.Scripted(_ => throw new Thrown)
    Await.result(
      LLMClientPekko(client).streamComplete(Fixtures.conversation).runWith(Sink.seq).failed,
      Patience
    ) shouldBe a[Thrown]
  }

  it should "block the provider's thread while the buffer is full, and drop nothing" in {
    val n        = 500
    val counting = new Fixtures.Counting(n)
    val probe = LLMClientPekko(counting.client)
      .streamComplete(Fixtures.conversation, bufferSize = 8)
      .runWith(TestSink[StreamedChunk]())
    // no demand: the provider fills the buffer (8 chunks) and blocks starting its 9th
    Fixtures.awaitCondition(counting.entered.get == 9 && Fixtures.isWaiting(counting.thread.get)) shouldBe true
    counting.entered.get shouldBe 9
    counting.finished.get shouldBe false
    probe.within(Patience) {
      probe.request(n.toLong)
      (0 until n).foreach(i => probe.expectNext().id shouldBe s"c$i")
      probe.expectComplete()
    }
    counting.finished.get shouldBe true
    counting.returned.get shouldBe n
  }

  it should "interrupt the provider's thread when the consumer cancels while the provider is parked" in {
    val parks = new Fixtures.ParksAfterFirst
    val probe = LLMClientPekko(parks.client).streamComplete(Fixtures.conversation).runWith(TestSink[StreamedChunk]())
    probe.request(1)
    probe.within(Patience)(probe.expectNext().id) shouldBe "c1"
    parks.started.await(Fixtures.DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    probe.cancel()
    parks.exited.await(Fixtures.PromptSeconds, TimeUnit.SECONDS) shouldBe true
    parks.interruptions.get shouldBe 1
  }

  it should "interrupt the provider's thread when a stage downstream stops early" in {
    val parks = new Fixtures.ParksAfterFirst
    val first = Await.result(
      LLMClientPekko(parks.client).streamComplete(Fixtures.conversation).take(1).runWith(Sink.seq),
      Patience
    )
    contents(first) shouldBe Seq("c1")
    parks.exited.await(Fixtures.PromptSeconds, TimeUnit.SECONDS) shouldBe true
    parks.interruptions.get shouldBe 1
  }

  it should "interrupt a provider blocked on a full buffer when the consumer cancels" in {
    val counting = new Fixtures.Counting(10000)
    val probe = LLMClientPekko(counting.client)
      .streamComplete(Fixtures.conversation, bufferSize = 4)
      .runWith(TestSink[StreamedChunk]())
    Fixtures.awaitCondition(counting.entered.get == 5 && Fixtures.isWaiting(counting.thread.get)) shouldBe true
    probe.cancel()
    counting.exited.await(Fixtures.PromptSeconds, TimeUnit.SECONDS) shouldBe true
    counting.interrupted.get shouldBe true
    counting.finished.get shouldBe false
  }

  it should "end the provider's thread once the stream has completed" in {
    val counting = new Fixtures.Counting(3)
    Await.result(LLMClientPekko(counting.client).streamComplete(Fixtures.conversation).runWith(Sink.seq), Patience)
    counting.exited.await(Fixtures.PromptSeconds, TimeUnit.SECONDS) shouldBe true
    Fixtures.awaitCondition(!counting.thread.get.isAlive) shouldBe true
  }

  it should "call the provider once per materialization, and only when materialized" in {
    val client = chunksClient(2)
    val stream = LLMClientPekko(client).streamComplete(Fixtures.conversation)
    client.streamCalls.get shouldBe 0
    Await.result(stream.runWith(Sink.seq), Patience)
    Await.result(stream.runWith(Sink.seq), Patience)
    client.streamCalls.get shouldBe 2
  }

  it should "fail at once for a buffer below 1" in {
    val client = chunksClient(2)
    val failure = Await.result(
      LLMClientPekko(client).streamComplete(Fixtures.conversation, bufferSize = 0).runWith(Sink.seq).failed,
      Patience
    )
    failure match {
      case e: LLMException => e.error shouldBe a[ValidationError]
      case other           => fail(s"expected an LLMException, got $other")
    }
    client.streamCalls.get shouldBe 0
  }

  "LLMClientPekko.complete" should "return the provider's completion" in {
    val client = new Fixtures.Scripted(onComplete = () => Right(Fixtures.completion))
    Await.result(
      LLMClientPekko(client).complete(Fixtures.conversation)(using ec),
      Patience
    ) shouldBe Fixtures.completion
  }

  it should "fail with the LLMError when the provider returns a Left" in {
    val client  = new Fixtures.Scripted(onComplete = () => Left(CancelledError("test")))
    val failure = Await.result(LLMClientPekko(client).complete(Fixtures.conversation)(using ec).failed, Patience)
    failure match {
      case e: LLMException => e.error shouldBe a[CancelledError]
      case other           => fail(s"expected an LLMException, got $other")
    }
  }

  "LLMClientPekko.agent" should "build an AgentPekko over the client" in {
    val client = new Fixtures.Scripted(onComplete = () => Right(Fixtures.completion))
    LLMClientPekko(client).agent("pekko-agent")() should matchPattern { case Right(_: AgentPekko) => }
  }

  it should "return the builder's error when the agent does not build" in {
    val client = new Fixtures.Scripted()
    LLMClientPekko(client).agent("")() should matchPattern { case Left(_) => }
  }
}
