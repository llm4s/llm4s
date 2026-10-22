package org.llm4s.javaapi

import org.llm4s.error.NetworkError
import org.llm4s.javaapi.StreamFixtures.*
import org.llm4s.llmconnect.model.{ Conversation, StreamedChunk, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, Flow, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.jdk.CollectionConverters.*

/** `JLlmClient.stream` and `streamAsPublisher` (#1485): a reply's text streamed to Java. */
class JCompletionStreamSpec extends AnyFlatSpec with Matchers {

  /** A listener that records every callback in order. */
  private class Recording extends CompletionStreamListener {
    val events                        = new CopyOnWriteArrayList[String]()
    val ended                         = new CountDownLatch(1)
    def onPartial(text: String): Unit = events.add(s"partial:$text"): Unit
    override def onComplete(completion: JCompletion): Unit = {
      events.add(s"complete:${completion.content()}"); ended.countDown()
    }
    override def onError(error: LlmException): Unit = { events.add(s"error:${error.getKind}"); ended.countDown() }
    def list: List[String]                          = events.asScala.toList
  }

  private def emitting(pieces: Seq[String], end: => org.llm4s.types.Result[org.llm4s.llmconnect.model.Completion]) =
    new Scripted(
      onChunk => { pieces.foreach(p => onChunk(StreamedChunk(id = "c", content = Some(p)))); end },
      () => end
    )

  private def wrap(client: org.llm4s.llmconnect.LLMClient): JLlmClient = Llm4s.wrapClient(client)

  "JLlmClient.stream" should "deliver each piece in order, then the reply once" in {
    val listener = new Recording
    val stream   = wrap(emitting(Seq("Hel", "lo", "", "!"), Right(completion("Hello!")))).stream("hi", listener).get()
    stream.await().get().content() shouldBe "Hello!"
    listener.list shouldBe List("partial:Hel", "partial:lo", "partial:!", "complete:Hello!")
  }

  it should "skip a chunk with no text" in {
    val client = new Scripted(
      onChunk => {
        onChunk(StreamedChunk(id = "c", content = None)); onChunk(StreamedChunk(id = "c", content = Some("a")));
        Right(completion("a"))
      },
      () => Right(completion("a"))
    )
    val listener = new Recording
    wrap(client).stream(Conversation(Seq(UserMessage("q"))), listener).get().await().isSuccess shouldBe true
    listener.list shouldBe List("partial:a", "complete:a")
  }

  it should "report an error mid-stream once, after the pieces delivered" in {
    val listener = new Recording
    val stream =
      wrap(emitting(Seq("a", "b"), Left(NetworkError("down", None, "http://x")))).stream("hi", listener).get()
    stream.await().getError().getKind shouldBe LlmErrorKind.NETWORK
    listener.list shouldBe List("partial:a", "partial:b", "error:NETWORK")
  }

  it should "interrupt the provider on close(), and report the cancellation once" in {
    val parks    = parksOnce(deltas = 1)
    val listener = new Recording
    val stream   = wrap(parks.client).stream("hi", listener).get()
    parks.parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    stream.close()
    parks.unparked.getCount shouldBe 0
    listener.ended.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    listener.list shouldBe List("partial:x", "error:CANCELLED")
    stream.await().getError().getKind shouldBe LlmErrorKind.CANCELLED
    stream.cancel() // a second cancel is harmless
    listener.list should have size 2
  }

  it should "keep the reply of a call that had completed before cancel" in {
    val listener = new Recording
    val stream   = wrap(emitting(Seq("done"), Right(completion("done")))).stream("hi", listener).get()
    stream.await().isSuccess shouldBe true
    stream.cancel()
    listener.list shouldBe List("partial:done", "complete:done")
  }

  it should "stop the call when the listener throws, and report what it threw" in {
    val parks = parksOnce(deltas = 3)
    val seen  = new AtomicInteger(0)
    val ended = new AtomicReference[LlmException]()
    val listener = new CompletionStreamListener {
      def onPartial(text: String): Unit = if (seen.incrementAndGet() == 1) throw new IllegalStateException("ui closed")
      override def onError(error: LlmException): Unit = ended.set(error)
    }
    val result = wrap(parks.client).stream("hi", listener).get().await()
    result.getError().getMessage should include("ui closed")
    ended.get.getMessage should include("ui closed")
    seen.get shouldBe 1
    parks.unparked.getCount shouldBe 0
  }

  it should "refuse to await from inside its own listener, on the provider's callback thread too" in {
    val stream = new AtomicReference[CompletionStream]()
    val gate   = new CountDownLatch(1)
    val inner  = new CopyOnWriteArrayList[String]()
    // the provider calls back on a thread of its own, as some SDKs do
    val client = new Scripted(
      onChunk => {
        gate.await(DeadlineSeconds, TimeUnit.SECONDS)
        val callback = Thread.ofVirtual().start(() => onChunk(StreamedChunk(id = "c", content = Some("x"))))
        callback.join()
        Right(completion("x"))
      },
      () => Right(completion("x"))
    )
    val listener = new CompletionStreamListener {
      def onPartial(text: String): Unit                      = inner.add(stream.get.await().getError().getMessage): Unit
      override def onComplete(completion: JCompletion): Unit = inner.add(stream.get.await().getError().getMessage): Unit
    }
    stream.set(wrap(client).stream("hi", listener).get())
    gate.countDown()
    stream.get.await().isSuccess shouldBe true
    inner.asScala.toList.foreach(_ should include("own listener"))
    inner.size shouldBe 2
  }

  it should "cancel from inside its own listener without waiting on itself" in {
    val parks  = parksOnce(deltas = 1)
    val stream = new AtomicReference[CompletionStream]()
    val gate   = new CountDownLatch(1)
    val listener = new Recording {
      override def onPartial(text: String): Unit = {
        super.onPartial(text)
        gate.await(DeadlineSeconds, TimeUnit.SECONDS)
        stream.get.cancel()
      }
    }
    stream.set(wrap(parks.client).stream("hi", listener).get())
    gate.countDown()
    stream.get.await().getError().getKind shouldBe LlmErrorKind.CANCELLED
    listener.list shouldBe List("partial:x", "error:CANCELLED")
  }

  it should "give up waiting for a provider that ignores its interrupt, and keep the caller's interrupt flag" in {
    val release = new CountDownLatch(1)
    val started = new CountDownLatch(1)
    val client = new Scripted(
      _ => {
        started.countDown()
        while (release.getCount > 0) {
          Thread.interrupted(); java.util.concurrent.locks.LockSupport.parkNanos(10000000L)
        }
        Right(completion("late"))
      },
      () => Right(completion("late"))
    )
    val stream = new CompletionStream(
      FacadeToken.INSTANCE,
      client,
      Conversation(Seq(UserMessage("q"))),
      org.llm4s.llmconnect.model.CompletionOptions(),
      new Recording,
      50L
    )
    started.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    Thread.currentThread().interrupt()
    stream.cancel()
    Thread.interrupted() shouldBe true
    release.countDown()
    stream.await().get().content() shouldBe "late"
  }

  it should "fail await on an interrupted caller, leaving the stream running" in {
    val parks  = parksOnce()
    val stream = wrap(parks.client).stream("hi", new Recording).get()
    parks.parked.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    Thread.currentThread().interrupt()
    stream.await().getError().getKind shouldBe LlmErrorKind.CANCELLED
    Thread.interrupted() shouldBe true
    stream.close()
  }

  it should "take a Java lambda as its listener, with no terminal callbacks" in {
    val pieces                             = new CopyOnWriteArrayList[String]()
    val listener: CompletionStreamListener = text => pieces.add(text): Unit
    wrap(emitting(Seq("a", "b"), Right(completion("ab"))))
      .stream("hi", listener)
      .get()
      .await()
      .get()
      .content() shouldBe "ab"
    wrap(emitting(Seq("c"), Left(NetworkError("down", None, "http://x"))))
      .stream("hi", listener)
      .get()
      .await()
      .isFailure shouldBe true
    pieces.asScala.toList shouldBe List("a", "b", "c")
  }

  it should "still let await return when the listener throws a fatal error" in {
    val listener: CompletionStreamListener = _ => throw new LinkageError("fatal in the listener")
    val result = wrap(emitting(Seq("a"), Right(completion("a")))).stream("hi", listener).get().await()
    result.getError().getMessage should include("failed fatally")
  }

  it should "log, not rethrow, a terminal callback that throws" in {
    val listener = new CompletionStreamListener {
      def onPartial(text: String): Unit                      = ()
      override def onComplete(completion: JCompletion): Unit = throw new IllegalStateException("boom")
    }
    wrap(emitting(Seq("a"), Right(completion("a")))).stream("hi", listener).get().await().isSuccess shouldBe true
  }

  it should "fail, not start, on a null argument" in {
    val client = wrap(answering("x"))
    val l      = new Recording
    client.stream(null: String, l).getError().getMessage should include("query")
    client.stream("q", null).getError().getMessage should include("listener")
    client.stream(null: Conversation, l).getError().getMessage should include("conversation")
    client
      .stream(Conversation(Seq(UserMessage("q"))), null: JCompletionOptions, l)
      .getError()
      .getMessage should include(
      "options"
    )
    client
      .stream(Conversation(Seq(UserMessage("q"))), JCompletionOptions.builder().maxTokens(5).build(), l)
      .get()
      .await()
      .isSuccess shouldBe true
  }

  /** A subscriber that requests `batch` at a time and records what it receives. */
  private class Collecting(batch: Long = Long.MaxValue) extends Flow.Subscriber[String] {
    val items                                   = new CopyOnWriteArrayList[String]()
    val done                                    = new CountDownLatch(1)
    val error                                   = new AtomicReference[Throwable]()
    @volatile var sub: Flow.Subscription        = _
    def onSubscribe(s: Flow.Subscription): Unit = { sub = s; s.request(batch) }
    def onNext(item: String): Unit              = { items.add(item); if (batch != Long.MaxValue) sub.request(batch) }
    def onError(t: Throwable): Unit             = { error.set(t); done.countDown() }
    def onComplete(): Unit                      = done.countDown()
    def awaitDone(): Boolean                    = done.await(DeadlineSeconds, TimeUnit.SECONDS)
  }

  "JLlmClient.streamAsPublisher" should "publish each piece in order, then complete, one call per subscription" in {
    val calls = new AtomicInteger(0)
    val client = new Scripted(
      onChunk => {
        calls.incrementAndGet(); (1 to 200).foreach(i => onChunk(StreamedChunk(id = "c", content = Some(s"$i"))));
        Right(completion("all"))
      },
      () => Right(completion("all"))
    )
    val publisher = wrap(client).streamAsPublisher("hi")
    val slow      = new Collecting(batch = 1)
    publisher.subscribe(slow)
    slow.awaitDone() shouldBe true
    slow.items.asScala.toList shouldBe (1 to 200).map(_.toString).toList
    slow.error.get shouldBe null
    val again = new Collecting
    publisher.subscribe(again)
    again.awaitDone() shouldBe true
    calls.get shouldBe 2
  }

  it should "fail the subscriber with an LlmException when the call fails" in {
    val s = new Collecting
    wrap(emitting(Seq("a"), Left(NetworkError("down", None, "http://x"))))
      .streamAsPublisher(Conversation(Seq(UserMessage("q"))))
      .subscribe(s)
    s.awaitDone() shouldBe true
    s.error.get match {
      case e: LlmException => e.getKind shouldBe LlmErrorKind.NETWORK
      case other           => fail(s"not an LlmException: $other")
    }
  }

  it should "interrupt the call when the subscription is cancelled" in {
    val interrupted = new CountDownLatch(1)
    val client = new Scripted(
      onChunk => {
        var i = 0
        while (!Thread.currentThread().isInterrupted && i < 1_000_000) {
          onChunk(StreamedChunk(id = "c", content = Some("x"))); i += 1
        }
        if (Thread.currentThread().isInterrupted) interrupted.countDown()
        Left(org.llm4s.error.CancelledError("test"))
      },
      () => Right(completion("x"))
    )
    val s = new Collecting(batch = 1) {
      override def onNext(item: String): Unit = {
        items.add(item); if (items.size >= 3) sub.cancel() else sub.request(1)
      }
    }
    wrap(client)
      .streamAsPublisher(Conversation(Seq(UserMessage("q"))), JCompletionOptions.builder().build())
      .subscribe(s)
    interrupted.await(DeadlineSeconds, TimeUnit.SECONDS) shouldBe true
    s.items.size shouldBe 3
  }

  it should "fail at once on subscription for a null argument" in {
    val client = wrap(answering("x"))
    Seq(
      client.streamAsPublisher(null: String),
      client.streamAsPublisher(null: Conversation),
      client.streamAsPublisher(null: Conversation, JCompletionOptions.builder().build()),
      client.streamAsPublisher(Conversation(Seq(UserMessage("q"))), null)
    ).foreach { p =>
      val s = new Collecting
      p.subscribe(s)
      s.awaitDone() shouldBe true
      s.error.get shouldBe a[LlmException]
    }
  }

  "The Java guide's streaming snippet" should "stream a reply and return it" in {
    GuideSnippets
      .streaming(wrap(emitting(Seq("Mon", "ads"), Right(completion("Monads")))), Conversation(Seq(UserMessage("q"))))
      .content() shouldBe "Monads"
  }

  "The streaming methods" should "expose no scala.* type to Java" in {
    val methods =
      classOf[JLlmClient].getMethods.toList.filter(m => m.getName == "stream" || m.getName == "streamAsPublisher")
    methods should have size 6
    methods.flatMap(m => m.getGenericParameterTypes.toList :+ m.getGenericReturnType).map(_.getTypeName).foreach { n =>
      (n should not).startWith("scala.")
    }
  }
}
