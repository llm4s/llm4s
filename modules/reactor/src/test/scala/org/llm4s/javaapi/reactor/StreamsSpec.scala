package org.llm4s.javaapi.reactor

import org.llm4s.error.{ CancelledError, NetworkError }
import org.llm4s.javaapi.{ CompletionPublisher, JCompletionOptions, JLlmClient, Llm4s, LlmErrorKind, LlmException }
import org.llm4s.javaapi.StreamFixtures.*
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, Conversation, StreamedChunk, UserMessage }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import reactor.core.publisher.Flux
import reactor.test.StepVerifier

import java.time.Duration
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }

/** `Streams.toFlux` (#1492): a reply's text as a Reactor `Flux`, checked with `StepVerifier`. */
class StreamsSpec extends AnyFlatSpec with Matchers {

  private val Deadline = Duration.ofSeconds(DeadlineSeconds)

  private def wrap(client: LLMClient): JLlmClient = Llm4s.wrapClient(client)

  private def emitting(pieces: Seq[String], end: => Result[Completion]): LLMClient =
    new Scripted(onChunk => { pieces.foreach(p => onChunk(StreamedChunk(id = "c", content = Some(p)))); end }, () => end)

  private val conversation = Conversation(Seq(UserMessage("q")))
  private val options      = JCompletionOptions.builder().temperature(0.1).build()

  /** The three ways in, over the same client. */
  private def fluxes(client: JLlmClient): Seq[Flux[String]] = Seq(
    Streams.toFlux(client, "q"),
    Streams.toFlux(client, conversation),
    Streams.toFlux(client, conversation, options)
  )

  private def expectFailure(flux: Flux[String], kind: LlmErrorKind): Unit =
    StepVerifier
      .create(flux)
      .expectErrorSatisfies {
        case e: LlmException => e.getKind shouldBe kind
        case other           => fail(s"not an LlmException: $other")
      }
      .verify(Deadline): Unit

  "Streams.toFlux" should "emit each piece of the reply in order, then complete" in {
    fluxes(wrap(emitting(Seq("Hel", "lo", "", "!"), Right(completion("Hello!"))))).foreach { flux =>
      StepVerifier.create(flux).expectNext("Hel", "lo", "!").expectComplete().verify(Deadline)
    }
  }

  it should "complete without an item for a reply with no text" in {
    StepVerifier.create(Streams.toFlux(wrap(answering("")), "q")).expectComplete().verify(Deadline): Unit
  }

  it should "be cold: each subscription makes its own provider call" in {
    val calls  = new AtomicInteger(0)
    val client = new Scripted(onChunk => { calls.incrementAndGet(); onChunk(chunk(0)); Right(completion("x")) }, () => Right(completion("x")))
    val flux   = Streams.toFlux(wrap(client), "q")
    calls.get shouldBe 0
    StepVerifier.create(flux).expectNext("x").expectComplete().verify(Deadline)
    StepVerifier.create(flux).expectNext("x").expectComplete().verify(Deadline)
    calls.get shouldBe 2
  }

  it should "end with onError carrying an LlmException of the failure's kind" in {
    fluxes(wrap(emitting(Seq("a"), Left(NetworkError("down", None, "http://x"))))).foreach { flux =>
      StepVerifier
        .create(flux)
        // the publisher's error may overtake a piece still in its buffer (SubmissionPublisher.closeExceptionally)
        .thenConsumeWhile(_ == "a")
        .expectErrorSatisfies {
          case e: LlmException => e.getKind shouldBe LlmErrorKind.NETWORK
          case other           => fail(s"not an LlmException: $other")
        }
        .verify(Deadline)
    }
  }

  it should "interrupt the provider call when the subscription is cancelled" in {
    val interrupted = new CountDownLatch(1)
    val client = new Scripted(
      onChunk => {
        var i = 0
        while (!Thread.currentThread().isInterrupted && i < 1_000_000) { onChunk(chunk(i)); i += 1 }
        if (Thread.currentThread().isInterrupted) interrupted.countDown()
        Left(CancelledError("test"))
      },
      () => Right(completion("x"))
    )
    StepVerifier
      .create(Streams.toFlux(wrap(client), conversation, options), 1)
      .expectNext("x")
      .thenRequest(2)
      .expectNext("x", "x")
      .thenCancel()
      .verify(Deadline)
    interrupted.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
  }

  it should "interrupt the provider call when take(n) has what it needs" in {
    val interrupted = new CountDownLatch(1)
    val client = new Scripted(
      onChunk => {
        var i = 0
        while (!Thread.currentThread().isInterrupted && i < 1_000_000) {
          onChunk(StreamedChunk(id = "c", content = Some(s"$i"))); i += 1
        }
        if (Thread.currentThread().isInterrupted) interrupted.countDown()
        Left(CancelledError("test"))
      },
      () => Right(completion("x"))
    )
    StepVerifier
      .create(Streams.toFlux(wrap(client), "q").take(3))
      .expectNext("0", "1", "2")
      .expectComplete()
      .verify(Deadline)
    interrupted.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
  }

  it should "respect backpressure: no piece before it is requested, and the model held once the buffer is full" in {
    val produced = new AtomicLong(0)
    val total    = 500
    val client = new Scripted(
      onChunk => {
        (1 to total).foreach { i => produced.incrementAndGet(); onChunk(StreamedChunk(id = "c", content = Some(s"$i"))) }
        Right(completion("all"))
      },
      () => Right(completion("all"))
    )
    // the model may run ahead of the subscriber by the publisher's buffer, plus the piece it is blocked submitting
    def heldBack(delivered: Long): Unit =
      produced.get should be <= (delivered + CompletionPublisher.BufferSize + 1)

    val oneAtATime = (1 to 5).foldLeft(
      StepVerifier
        .create(Streams.toFlux(wrap(client), "q"), 0)
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(50))
        .`then`(() => heldBack(0))
    ) { (step, i) =>
      step.thenRequest(1).expectNext(s"$i").`then`(() => heldBack(i.toLong))
    }
    oneAtATime
      .thenRequest(Long.MaxValue)
      .expectNextCount((total - 5).toLong)
      .expectComplete()
      .verify(Deadline)
    produced.get shouldBe total
  }

  it should "fail on subscription, never throw, for a null argument" in {
    val client = wrap(answering("x"))
    Seq(
      Streams.toFlux(null, "q"),
      Streams.toFlux(null, conversation),
      Streams.toFlux(null, conversation, options),
      Streams.toFlux(client, null: String),
      Streams.toFlux(client, null: Conversation),
      Streams.toFlux(client, null: Conversation, options),
      Streams.toFlux(client, conversation, null)
    ).foreach(expectFailure(_, LlmErrorKind.VALIDATION))
  }

  it should "name the missing argument" in {
    StepVerifier
      .create(Streams.toFlux(null, "q"))
      .expectErrorSatisfies(e => e.getMessage should include("client"))
      .verify(Deadline): Unit
  }

  "Streams" should "expose no scala.* type to Java, and be callable as static methods" in {
    val methods = Class.forName("org.llm4s.javaapi.reactor.Streams").getMethods.toList.filter(_.getName == "toFlux")
    methods should have size 3
    methods.foreach(m => java.lang.reflect.Modifier.isStatic(m.getModifiers) shouldBe true)
    methods.flatMap(m => m.getGenericParameterTypes.toList :+ m.getGenericReturnType).map(_.getTypeName).foreach { n =>
      (n should not).startWith("scala.")
    }
  }
}
