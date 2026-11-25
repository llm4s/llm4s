package org.llm4s.javaapi.rag

import org.llm4s.testutil.TestTimeLimit
import org.llm4s.types.ProviderModelTypes.ProviderId

import org.llm4s.error.{ CancelledError, RateLimitError }
import org.llm4s.javaapi.{ LlmErrorKind, LlmException }
import org.llm4s.javaapi.StreamFixtures.*
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.rag.{ RAGAnswerResult, RAGSearchResult }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.locks.LockSupport
import scala.jdk.CollectionConverters.*

/**
 * `JRag.askStream` (#2727): the sources first, then the answer's pieces, then the answer or the error, on a thread of
 * the stream's own, cancelled by interrupting it.
 */
class JRagAnswerStreamSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  import RagFakes.*

  /** Records every callback, in order, as text; `onEvent` runs after each is recorded. */
  private class Recorder(onEvent: (Recorder, String) => Unit = (_, _) => ()) extends JRagStreamListener {
    val events                              = new CopyOnWriteArrayList[String]()
    val sources                             = new AtomicReference[java.util.List[JRagSource]]()
    val ended                               = new CountDownLatch(1)
    val error                               = new AtomicReference[LlmException]()
    private def record(event: String): Unit = { events.add(event); onEvent(this, event) }
    override def onSources(s: java.util.List[JRagSource]): Unit = {
      sources.set(s)
      record(s"sources:${s.asScala.map(_.documentId).mkString(",")}")
    }
    override def onChunk(text: String): Unit = record(s"chunk:$text")
    override def onComplete(answer: JRagAnswer): Unit = {
      record(s"complete:${answer.answer}")
      ended.countDown()
    }
    override def onError(e: LlmException): Unit = {
      error.set(e)
      record(s"error:${e.getKind}")
      ended.countDown()
    }
    def list: List[String] = events.asScala.toList
    def awaitEnd(): Unit   = ended.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
  }

  /** A model that streams `pieces`, then ends with `end`. */
  private def streaming(pieces: Seq[String], end: => Result[Completion]): LLMClient =
    new Scripted(
      onChunk => { pieces.foreach(p => onChunk(StreamedChunk(id = "c", contentDelta = Some(p)))); end },
      () => end
    )

  private def answerOf(text: String): Result[Completion] =
    Right(completion(text).withUsage(TokenUsage(12, 3, 15)))

  /** A pipeline with two documents, answering through `model`. */
  private def pipeline(model: LLMClient): JRag = {
    val rag = JRag.builder().embeddings(embeddings()).llm(llm(model)).build().get()
    rag.ingestText("The cat sat on the mat.", "cats").get()
    rag.ingestText("The dog buried a bone in the garden.", "dogs").get()
    rag
  }

  private def stream(rag: JRag, listener: JRagStreamListener, question: String = "Where did the cat sit?") =
    rag.askStream(question, listener).get()

  "JRag.askStream" should "deliver the sources, then each piece in order, then the answer once" in {
    val rag = pipeline(streaming(Seq("On ", "", "the mat."), answerOf("On the mat.")))
    try {
      val listener = new Recorder()
      val answer   = stream(rag, listener).await().get()
      listener.list shouldBe List("sources:cats,dogs", "chunk:On ", "chunk:the mat.", "complete:On the mat.")
      answer.answer shouldBe "On the mat."
      answer.question shouldBe "Where did the cat sit?"
      answer.sources shouldBe listener.sources.get
      answer.sources.get(0).content shouldBe "The cat sat on the mat."
      answer.usage.get.totalTokens shouldBe 15
    } finally rag.close()
  }

  it should "answer from at most topK sources" in {
    val rag = pipeline(streaming(Seq("x"), answerOf("x")))
    try {
      val listener = new Recorder()
      rag.askStream("Where did the cat sit?", 1, listener).get().await().get().sources.size shouldBe 1
      listener.list.head shouldBe "sources:cats"
    } finally rag.close()
  }

  it should "report a failure of the model once, after the sources and the pieces already delivered" in {
    val rag = pipeline(streaming(Seq("On "), Left(RateLimitError(ProviderId("openai")))))
    try {
      val listener = new Recorder()
      val result   = stream(rag, listener).await()
      listener.list shouldBe List("sources:cats,dogs", "chunk:On ", "error:RATE_LIMIT")
      result.isFailure shouldBe true
      result.getError().getKind shouldBe LlmErrorKind.RATE_LIMIT
    } finally rag.close()
  }

  it should "report a failure of the search with no sources, and never ask the model" in {
    val asked = new AtomicBoolean(false)
    val rag = JRag
      .builder()
      .embeddings(failingEmbeddings(RateLimitError(ProviderId("voyage"))))
      .llm(llm(new Scripted(_ => { asked.set(true); answerOf("x") }, () => answerOf("x"))))
      .build()
      .get()
    try {
      val listener = new Recorder()
      stream(rag, listener).await().getError().getKind shouldBe LlmErrorKind.RATE_LIMIT
      listener.list shouldBe List("error:RATE_LIMIT")
      asked.get shouldBe false
    } finally rag.close()
  }

  it should "interrupt the model on cancel() midway, and report the cancellation once, with no piece after it" in {
    val model = parksOnce(deltas = 1)
    val rag   = pipeline(model.client)
    try {
      val listener = new Recorder()
      val s        = stream(rag, listener)
      model.parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
      s.cancel()
      // cancel() returns once the listener has heard
      listener.list shouldBe List("sources:cats,dogs", "chunk:x", "error:CANCELLED")
      model.unparked.getCount shouldBe 0
      s.await().getError().getKind shouldBe LlmErrorKind.CANCELLED
      s.cancel() // a second cancel changes nothing
      listener.list should have size 3
    } finally rag.close()
  }

  it should "cancel on close(), so try-with-resources stops a call its body leaves" in {
    val model = parksOnce(deltas = 1)
    val rag   = pipeline(model.client)
    try {
      val listener = new Recorder()
      val s        = stream(rag, listener)
      try model.parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
      finally s.close()
      listener.list.last shouldBe "error:CANCELLED"
    } finally rag.close()
  }

  it should "stop during the search, before the sources, without asking the model" in {
    val searching = new CountDownLatch(1)
    val asked     = new AtomicBoolean(false)
    val parking = new WordEmbeddings(Some { request =>
      if (request.purpose == InputPurpose.Query) { searching.countDown(); parkUntilInterrupted(): Unit }
      Right(EmbeddingResponse(request.input.map(vector)))
    })
    val rag = JRag
      .builder()
      .embeddings(embeddings(parking))
      .llm(llm(new Scripted(_ => { asked.set(true); answerOf("x") }, () => answerOf("x"))))
      .build()
      .get()
    try {
      rag.ingestText("The cat sat on the mat.", "cats").get()
      val listener = new Recorder()
      val s        = stream(rag, listener)
      searching.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
      s.cancel()
      listener.list shouldBe List("error:CANCELLED")
      asked.get shouldBe false
    } finally rag.close()
  }

  it should "keep the answer of a call that had completed before cancel" in {
    val rag = pipeline(streaming(Seq("x"), answerOf("x")))
    try {
      val listener = new Recorder()
      val s        = stream(rag, listener)
      listener.awaitEnd()
      s.cancel()
      s.await().get().answer shouldBe "x"
      listener.list.last shouldBe "complete:x"
    } finally rag.close()
  }

  it should "stop the model when the listener throws from onChunk, and report what it threw" in {
    val model = parksOnce(deltas = 2)
    val rag   = pipeline(model.client)
    try {
      val listener =
        new Recorder((_, event) => if (event.startsWith("chunk")) throw new IllegalStateException("ui gone"))
      val result = stream(rag, listener).await()
      listener.list shouldBe List("sources:cats,dogs", "chunk:x", s"error:${result.getError().getKind}")
      result.getError().getMessage should include("ui gone")
      model.unparked.getCount shouldBe 0
    } finally rag.close()
  }

  it should "not ask the model when the listener throws from onSources" in {
    val asked = new AtomicBoolean(false)
    val rag   = pipeline(new Scripted(_ => { asked.set(true); answerOf("x") }, () => answerOf("x")))
    try {
      val listener = new Recorder((_, event) => if (event.startsWith("sources")) throw new IllegalStateException("bad"))
      val result   = stream(rag, listener).await()
      result.getError().getMessage should include("bad")
      listener.list shouldBe List("sources:cats,dogs", s"error:${result.getError().getKind}")
      asked.get shouldBe false
    } finally rag.close()
  }

  it should "refuse to await from its own listener, and cancel from it without waiting on itself" in {
    val model   = parksOnce(deltas = 1)
    val rag     = pipeline(model.client)
    val awaited = new AtomicReference[String]()
    val own     = new AtomicReference[JRagAnswerStream]()
    val ready   = new CountDownLatch(1)
    try {
      val listener = new Recorder((_, event) =>
        if (event.startsWith("chunk")) {
          ready.await(DeadlineSeconds, TimeUnit.SECONDS): Unit
          val r = own.get.await()
          awaited.set(s"${r.getError().getKind}: ${r.getError().getMessage}")
          own.get.cancel()
        }
      )
      own.set(stream(rag, listener))
      ready.countDown()
      listener.awaitEnd()
      awaited.get should startWith("VALIDATION")
      awaited.get should include("own listener")
      listener.list.last shouldBe "error:CANCELLED"
    } finally rag.close()
  }

  it should "fail await on an interrupted caller, leaving the stream running" in {
    val model = parksOnce(deltas = 1)
    val rag   = pipeline(model.client)
    try {
      val listener = new Recorder()
      val s        = stream(rag, listener)
      Thread.currentThread().interrupt()
      val result = s.await()
      Thread.interrupted() shouldBe true
      result.getError().getKind shouldBe LlmErrorKind.CANCELLED
      listener.ended.getCount shouldBe 1
      s.cancel()
      listener.list.last shouldBe "error:CANCELLED"
    } finally rag.close()
  }

  it should "give up waiting for a call that ignores its interrupt, and keep the caller's interrupt flag" in {
    val release  = new CountDownLatch(1)
    val entered  = new CountDownLatch(1)
    val listener = new Recorder()
    val call: (Seq[RAGSearchResult] => Unit, StreamedChunk => Unit) => Result[RAGAnswerResult] = (onSources, _) => {
      onSources(Seq.empty)
      entered.countDown()
      // ignores the interrupt until released
      while (release.getCount > 0) { Thread.interrupted(): Unit; LockSupport.parkNanos(1000000L) }
      Right(RAGAnswerResult("late", "q", Seq.empty, None))
    }
    val s = new JRagAnswerStream(RagFacadeToken.INSTANCE, call, listener, 50L)
    entered.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    Thread.currentThread().interrupt()
    s.cancel()
    Thread.interrupted() shouldBe true // the caller's flag is kept
    listener.ended.getCount shouldBe 1
    release.countDown()
    listener.awaitEnd()
    // the call answered despite the cancel, so its answer is kept
    listener.list shouldBe List("sources:", "complete:late")
  }

  it should "still let await return when the listener throws a fatal error" in {
    val rag = pipeline(streaming(Seq("x"), answerOf("x")))
    try {
      val listener = new Recorder((_, event) => if (event.startsWith("sources")) throw new LinkageError("fatal"))
      val result   = stream(rag, listener).await()
      result.getError().getKind shouldBe LlmErrorKind.VALIDATION
      result.getError().getMessage should include("failed fatally")
    } finally rag.close()
  }

  it should "log, not rethrow, a terminal callback that throws" in {
    val rag = pipeline(streaming(Seq("x"), answerOf("x")))
    try {
      val listener = new Recorder((_, event) => if (event.startsWith("complete")) throw new IllegalStateException("x"))
      stream(rag, listener).await().get().answer shouldBe "x"
    } finally rag.close()
  }

  it should "take a Java lambda as its listener, with no other callbacks" in {
    val rag = pipeline(streaming(Seq("a", "b"), answerOf("ab")))
    try {
      val pieces = new CopyOnWriteArrayList[String]()
      JRagCheck.streamPieces(rag, "Where did the cat sit?", pieces).await().get().answer shouldBe "ab"
      pieces.asScala.toList shouldBe List("a", "b")
    } finally rag.close()
    val failing = pipeline(streaming(Seq("a"), Left(RateLimitError(ProviderId("openai")))))
    try {
      val pieces = new CopyOnWriteArrayList[String]()
      JRagCheck.streamPieces(failing, "Where did the cat sit?", pieces).await().getError().getKind shouldBe
        LlmErrorKind.RATE_LIMIT
      pieces.asScala.toList shouldBe List("a")
    } finally failing.close()
  }

  it should "fail, not start, on a bad argument or a pipeline without a chat client" in {
    val rag = pipeline(streaming(Nil, answerOf("x")))
    try {
      val listener = new Recorder()
      rag.askStream(null, listener).getError().getKind shouldBe LlmErrorKind.VALIDATION
      rag.askStream(" ", listener).getError().getKind shouldBe LlmErrorKind.VALIDATION
      rag.askStream("q", null).getError().getKind shouldBe LlmErrorKind.VALIDATION
      rag.askStream("q", 0, listener).getError().getKind shouldBe LlmErrorKind.VALIDATION
      listener.list shouldBe empty
    } finally rag.close()
    val noModel = JRag.builder().embeddings(embeddings()).build().get()
    try noModel.askStream("q", new Recorder()).getError().getKind shouldBe LlmErrorKind.CONFIGURATION
    finally noModel.close()
  }

  it should "report the refusal of a pipeline built with a SearchIndex to onError, with no sources (#2716)" in {
    val asked = new AtomicBoolean(false)
    val model = new Scripted(_ => { asked.set(true); answerOf("x") }, () => answerOf("x"))
    val rag   = withSearchIndex(model)
    try {
      val listener = new Recorder()
      val result   = stream(rag, listener).await()
      listener.list shouldBe List("error:CONFIGURATION")
      listener.error.get.getMessage should include("queryWithPermissionsAndAnswerStreaming")
      result.getError().getKind shouldBe LlmErrorKind.CONFIGURATION
      asked.get shouldBe false
    } finally rag.close()
  }

  it should "be built only by this package" in {
    val thrown = intercept[UnsupportedOperationException](
      new JRagAnswerStream(null, (_, _) => Left(CancelledError("x")), new Recorder(), 0L)
    )
    thrown.getMessage should include("JRagAnswerStream")
  }

  "The streaming methods" should "expose no scala.* type to Java" in {
    val methods = classOf[JRag].getMethods.toList.filter(_.getName == "askStream") ++
      classOf[JRagAnswerStream].getMethods.toList.filter(m => Set("await", "cancel", "close")(m.getName)) ++
      classOf[JRagStreamListener].getMethods.toList
    methods.size should be >= 9
    methods.flatMap(m => m.getGenericParameterTypes.toList :+ m.getGenericReturnType).map(_.getTypeName).foreach { n =>
      (n should not).startWith("scala.")
    }
  }
}
