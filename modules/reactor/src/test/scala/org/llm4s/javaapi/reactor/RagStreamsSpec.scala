package org.llm4s.javaapi.reactor

import org.llm4s.error.RateLimitError
import org.llm4s.javaapi.{ FacadeInternals, JMessage, LlmErrorKind, LlmException }
import org.llm4s.javaapi.StreamFixtures.*
import org.llm4s.javaapi.rag.{ JRag, RagFakes }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk, SystemMessage }
import org.llm4s.types.Result
import org.reactivestreams.Subscription
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import reactor.core.publisher.{ BaseSubscriber, Flux }
import reactor.test.StepVerifier

import java.time.Duration
import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.jdk.CollectionConverters.*

/** `RagStreams.toFlux` (#2727): a RAG answer's sources, then its pieces, then the answer, as a Reactor `Flux`. */
class RagStreamsSpec extends AnyFlatSpec with Matchers {

  import RagFakes.{ embeddings, llm, withSearchIndex }

  private val Deadline = Duration.ofSeconds(DeadlineSeconds)

  private def streaming(pieces: Seq[String], end: => Result[Completion]): LLMClient =
    new Scripted(
      onChunk => { pieces.foreach(p => onChunk(StreamedChunk(id = "c", content = Some(p)))); end },
      () => end
    )

  /** A pipeline with two documents, answering through `model`. */
  private def withPipeline[A](model: LLMClient)(body: JRag => A): A = {
    val rag = JRag.builder().embeddings(embeddings()).llm(llm(model)).build().get()
    try {
      rag.ingestText("The cat sat on the mat.", "cats").get()
      rag.ingestText("The dog buried a bone in the garden.", "dogs").get()
      body(rag)
    } finally rag.close()
  }

  private val Question = "Where did the cat sit?"

  /** What an event is, in a line: the kind of event and what it carries. */
  private def describe(event: RagAnswerEvent): String = event match {
    case s: RagAnswerEvent.Sources  => s"sources:${s.sources.asScala.map(_.documentId).mkString(",")}"
    case c: RagAnswerEvent.Chunk    => s"chunk:${c.text}"
    case c: RagAnswerEvent.Complete => s"complete:${c.answer.answer}"
  }

  private def expectFailure(flux: Flux[RagAnswerEvent], kind: LlmErrorKind): Unit =
    StepVerifier
      .create(flux)
      .expectErrorSatisfies {
        case e: LlmException => e.getKind shouldBe kind
        case other           => fail(s"not an LlmException: $other")
      }
      .verify(Deadline): Unit

  "RagStreams.toFlux" should "emit the sources, then each piece in order, then the answer, then complete" in
    withPipeline(streaming(Seq("On ", "", "the mat."), Right(completion("On the mat.")))) { rag =>
      val events = RagStreams.toFlux(rag, Question).map(describe).collectList().block(Deadline)
      events.asScala.toList shouldBe List("sources:cats,dogs", "chunk:On ", "chunk:the mat.", "complete:On the mat.")
    }

  it should "answer from at most topK sources" in
    withPipeline(streaming(Seq("x"), Right(completion("x")))) { rag =>
      StepVerifier
        .create(RagStreams.toFlux(rag, Question, 1).map(describe))
        .expectNext("sources:cats", "chunk:x", "complete:x")
        .expectComplete()
        .verify(Deadline): Unit
    }

  it should "send the history to the model, with and without topK, and refuse a SYSTEM message (#2766)" in {
    val sent = new CopyOnWriteArrayList[Conversation]()
    val model = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] = { sent.add(c); Right(completion("x")) }
      def streamComplete(c: Conversation, o: CompletionOptions)(onChunk: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      def contextWindow: Int     = 4096
      def reserveCompletion: Int = 256
    }
    val history = java.util.List.of(JMessage.user("Which animal?"), JMessage.assistant("The cat."))
    withPipeline(model) { rag =>
      RagStreams.toFlux(rag, Question, history).map(describe).collectList().block(Deadline).asScala.last shouldBe
        "complete:x"
      RagStreams.toFlux(rag, Question, 1, history).map(describe).collectList().block(Deadline).asScala.head shouldBe
        "sources:cats"
      sent.asScala.toList.map(_.messages.map(_.content).slice(1, 3)) shouldBe
        List.fill(2)(Seq("Which animal?", "The cat."))
      expectFailure(
        RagStreams.toFlux(rag, Question, java.util.List.of(FacadeInternals.message(SystemMessage("x")))),
        LlmErrorKind.VALIDATION
      )
    }
  }

  it should "be cold: each subscription makes its own search and model call" in {
    val calls = new AtomicInteger(0)
    withPipeline(new Scripted(_ => { calls.incrementAndGet(); Right(completion("x")) }, () => Right(completion("x")))) {
      rag =>
        val flux = RagStreams.toFlux(rag, Question)
        calls.get shouldBe 0
        flux.blockLast(Deadline)
        flux.blockLast(Deadline)
        calls.get shouldBe 2
    }
  }

  it should "end with onError after the sources and the pieces already emitted" in
    withPipeline(streaming(Seq("On "), Left(RateLimitError("openai")))) { rag =>
      StepVerifier
        .create(RagStreams.toFlux(rag, Question).map(describe))
        .expectNext("sources:cats,dogs", "chunk:On ")
        .expectErrorSatisfies {
          case e: LlmException => e.getKind shouldBe LlmErrorKind.RATE_LIMIT
          case other           => fail(s"not an LlmException: $other")
        }
        .verify(Deadline): Unit
    }

  it should "fail with the refusal of a pipeline built with a SearchIndex, emitting nothing (#2716)" in {
    val rag = withSearchIndex(streaming(Seq("x"), Right(completion("x"))))
    try expectFailure(RagStreams.toFlux(rag, Question), LlmErrorKind.CONFIGURATION)
    finally rag.close()
  }

  it should "fail on subscription for a null pipeline, a blank question or a pipeline without a chat client" in {
    expectFailure(RagStreams.toFlux(null, Question), LlmErrorKind.VALIDATION)
    withPipeline(streaming(Nil, Right(completion("x")))) { rag =>
      expectFailure(RagStreams.toFlux(rag, " "), LlmErrorKind.VALIDATION)
      expectFailure(RagStreams.toFlux(rag, Question, 0), LlmErrorKind.VALIDATION)
    }
    val noModel = JRag.builder().embeddings(embeddings()).build().get()
    try expectFailure(RagStreams.toFlux(noModel, Question), LlmErrorKind.CONFIGURATION)
    finally noModel.close()
  }

  it should "interrupt the call when the subscription is cancelled midway" in {
    val model = parksOnce(deltas = 1)
    withPipeline(model.client) { rag =>
      StepVerifier
        .create(RagStreams.toFlux(rag, Question).map(describe).take(2))
        .expectNext("sources:cats,dogs", "chunk:x")
        .expectComplete()
        .verify(Deadline)
      model.unparked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    }
  }

  /** A model that streams `pieces`, recording its thread and counting down `emitting` before the first. */
  final private class Watched(pieces: Seq[String]) {
    val thread   = new AtomicReference[Thread]()
    val emitting = new CountDownLatch(1)
    val ended    = new CountDownLatch(1)
    val client: LLMClient = new Scripted(
      onChunk => {
        thread.set(Thread.currentThread())
        emitting.countDown()
        pieces.foreach(p => onChunk(StreamedChunk(id = "c", content = Some(p))))
        ended.countDown()
        Right(completion(pieces.mkString))
      },
      () => Right(completion(pieces.mkString))
    )

    /** Waits until the call's thread is parked, waiting for demand. */
    def awaitParked(): Unit = {
      emitting.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
      awaitCondition(thread.get.getState == Thread.State.WAITING) shouldBe true
    }
  }

  /** Requests only when told to, recording each event and the end. */
  final private class Manual extends BaseSubscriber[RagAnswerEvent] {
    val events                                                     = new CopyOnWriteArrayList[String]()
    val done                                                       = new CountDownLatch(1)
    override def hookOnSubscribe(subscription: Subscription): Unit = request(1)
    override def hookOnNext(event: RagAnswerEvent): Unit           = events.add(describe(event)): Unit
    override def hookOnComplete(): Unit                            = { events.add("done"); done.countDown() }
  }

  it should "hold the call's thread until the subscriber asks for more, losing nothing" in {
    val model = new Watched(Seq("a", "b"))
    withPipeline(model.client) { rag =>
      val subscriber = new Manual
      RagStreams.toFlux(rag, Question).subscribe(subscriber)
      model.awaitParked() // the sources are out; "a" waits for demand
      subscriber.events.asScala.toList shouldBe List("sources:cats,dogs")
      model.ended.getCount shouldBe 1
      subscriber.request(Long.MaxValue)
      subscriber.done.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
      subscriber.events.asScala.toList shouldBe List("sources:cats,dogs", "chunk:a", "chunk:b", "complete:ab", "done")
    }
  }

  it should "end the wait for demand when the subscription is cancelled, emitting nothing more" in {
    val model = new Watched(Seq("a", "b"))
    withPipeline(model.client) { rag =>
      val subscriber = new Manual
      RagStreams.toFlux(rag, Question).subscribe(subscriber)
      model.awaitParked()
      subscriber.dispose()
      model.ended.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
      subscriber.events.asScala.toList shouldBe List("sources:cats,dogs")
    }
  }
}
