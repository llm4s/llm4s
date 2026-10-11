package org.llm4s.javaapi

import org.llm4s.error._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.util.Optional
import java.util.concurrent.{ CountDownLatch, Executors, ExecutionException, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

/** Null-safety, lambda-exception, lifecycle and cause-extraction behaviour for Java callers. */
class RobustnessSpec extends AnyFlatSpec with Matchers {

  private val error: LLMError = ValidationError("boom", "field")

  private def client(
    onComplete: (Conversation, CompletionOptions) => Result[Completion],
    onClose: () => Unit = () => ()
  ): LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = onComplete(c, o)
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
    override def close(): Unit               = onClose()
  }

  private def ok(text: String): Result[Completion] =
    Right(Completion("id", 0L, text, "m", AssistantMessage(text)))

  // ---- LlmResult ---------------------------------------------------------------------------

  "toOptional" should "be empty (not throw) when the success value is null" in {
    LlmResult.success[String](null).toOptional shouldBe Optional.empty[String]()
  }

  it should "be empty when map produces null" in {
    LlmResult.success("x").map[String](_ => null).toOptional shouldBe Optional.empty[String]()
  }

  "map" should "let an exception thrown by the function propagate (it is not folded into a failure)" in {
    val boom = new IllegalStateException("lambda")
    val ex   = intercept[IllegalStateException](LlmResult.success(1).map[Int](_ => throw boom))
    (ex should be).theSameInstanceAs(boom)
  }

  it should "invoke the function exactly once per call" in {
    val calls = new AtomicInteger
    LlmResult
      .success(1)
      .map[Int] { n =>
        calls.incrementAndGet(); n
      }
      .get() shouldBe 1
    calls.get shouldBe 1
  }

  it should "not invoke the function on a failure" in {
    var called = false
    val r = LlmResult.failure[Int](error).map[Int] { n =>
      called = true; n
    }
    called shouldBe false
    r.isFailure shouldBe true
  }

  "ifSuccess / ifFailure" should "propagate exceptions from the callback and not swallow them" in {
    intercept[IllegalArgumentException](LlmResult.success(1).ifSuccess(_ => throw new IllegalArgumentException("a")))
    intercept[IllegalArgumentException](
      LlmResult.failure[Int](error).ifFailure(_ => throw new IllegalArgumentException("b"))
    )
  }

  "toCompletableFuture" should "be already completed on the calling thread, so callbacks run synchronously on it" in {
    val caller = Thread.currentThread()
    var thenOn = Option.empty[Thread]
    val cf     = LlmResult.success("v").toCompletableFuture
    val composed = cf.thenApply[String] { v =>
      thenOn = Some(Thread.currentThread()); v
    }
    composed.isDone shouldBe true
    thenOn shouldBe Some(caller)
  }

  it should "fail with an ExecutionException whose cause is the LlmException carrying the error" in {
    val cf = LlmResult.failure[String](error).toCompletableFuture
    cf.isDone shouldBe true
    val ex = intercept[ExecutionException](cf.get())
    ex.getCause shouldBe a[LlmException]
    ex.getCause.asInstanceOf[LlmException].error shouldBe error
  }

  "getError" should "return a fresh but equivalent exception on every call" in {
    val r = LlmResult.failure[String](error)
    (r.getError() should not).be(theSameInstanceAs(r.getError()))
    r.getError().error shouldBe r.getError().error
  }

  // ---- LlmException cause extraction -------------------------------------------------------

  "LlmException.getCause" should "expose the Throwable of a NetworkError (Option field)" in {
    val io = new IOException("socket closed")
    val ex = new LlmException(NetworkError("down", Some(io), "https://x"))
    (ex.getCause should be).theSameInstanceAs(io)
  }

  it should "expose the Throwable of a ProcessingError" in {
    val t = new RuntimeException("inner")
    (new LlmException(ProcessingError("op", "failed", Some(t))).getCause should be).theSameInstanceAs(t)
  }

  it should "expose the Throwable of an ExecutionError" in {
    val t = new RuntimeException("inner")
    (new LlmException(ExecutionError("cmd", "failed", None, Some(t))).getCause should be).theSameInstanceAs(t)
  }

  it should "expose the Throwable of an UnknownError (plain Throwable field)" in {
    val t = new RuntimeException("inner")
    (new LlmException(UnknownError("failed", t)).getCause should be).theSameInstanceAs(t)
  }

  it should "be null when the error carries no Throwable" in {
    new LlmException(error).getCause shouldBe null
    new LlmException(NetworkError("down", None, "https://x")).getCause shouldBe null
  }

  it should "not recurse or hang on a cause that is cyclic (a -> b -> a)" in {
    val a = new RuntimeException("a")
    val b = new RuntimeException("b", a)
    a.initCause(b)
    val ex = new LlmException(NetworkError("down", Some(a), "https://x"))
    (ex.getCause should be).theSameInstanceAs(a)
    // Rendering the stack trace of a cyclic chain must terminate (the JDK guards cycles).
    noException should be thrownBy ex.getStackTrace
  }

  it should "tolerate an error with a null message" in {
    val nullMessage = new LLMError {
      override def message: String              = null
      override def productArity: Int            = 0
      override def productElement(n: Int): Any  = throw new IndexOutOfBoundsException
      override def canEqual(that: Any): Boolean = true
    }
    val ex = new LlmException(nullMessage)
    ex.getMessage shouldBe null
    ex.getCause shouldBe null
  }

  // ---- lifecycle ---------------------------------------------------------------------------

  "JLlmClient.close" should "delegate every time and be harmless to call twice" in {
    val closes = new AtomicInteger
    val c      = new JLlmClient(client((_, _) => ok("x"), () => { closes.incrementAndGet(); () }))
    c.close()
    c.close()
    closes.get shouldBe 2
  }

  it should "work with try-with-resources semantics (AutoCloseable)" in {
    val closes = new AtomicInteger
    val c      = new JLlmClient(client((_, _) => ok("x"), () => { closes.incrementAndGet(); () }))
    (c: AutoCloseable).close()
    closes.get shouldBe 1
  }

  it should "propagate an exception thrown by the underlying close" in {
    val c = new JLlmClient(client((_, _) => ok("x"), () => throw new IllegalStateException("close failed")))
    intercept[IllegalStateException](c.close()).getMessage shouldBe "close failed"
  }

  "use after close" should "be governed by the underlying client (the wrapper adds no guard)" in {
    var closed = false
    val c = new JLlmClient(
      client(
        (_, _) => if (closed) Left(ValidationError("client", "closed")) else ok("alive"),
        () => closed = true
      )
    )
    c.complete("a").get() shouldBe "alive"
    c.close()
    c.complete("b").isFailure shouldBe true
  }

  // ---- null safety for Java callers --------------------------------------------------------

  "JLlmClient.complete" should "return a failure (not throw) for a null query" in {
    val c = new JLlmClient(client((_, _) => ok("x")))
    val r = c.complete(null: String)
    r.isFailure shouldBe true
    r.getError().error shouldBe a[ValidationError]
  }

  it should "return a failure for a null conversation" in {
    val c = new JLlmClient(client((_, _) => ok("x")))
    c.complete(null: Conversation).isFailure shouldBe true
    c.complete(null: Conversation, CompletionOptions()).isFailure shouldBe true
  }

  it should "return a failure for null options" in {
    val c = new JLlmClient(client((_, _) => ok("x")))
    c.complete(ConversationBuilder.create().user("hi").build(), null: CompletionOptions).isFailure shouldBe true
  }

  it should "turn an exception thrown by the underlying client into a failed LlmResult" in {
    val cause = new IllegalStateException("provider blew up")
    val c     = new JLlmClient(client((_, _) => throw cause))
    val r     = c.complete("hi")
    r.isFailure shouldBe true
    (r.getError().getCause should be).theSameInstanceAs(cause)
  }

  "JAgent.run" should "return a failure for a null query" in {
    val agent = Llm4s.createAgent(new JLlmClient(client((_, _) => ok("x"))))
    agent.run(null: String).isFailure shouldBe true
  }

  "Llm4s" should "reject a null client or null tools when creating an agent, with a clear message" in {
    val ex = intercept[NullPointerException](Llm4s.createAgent(null))
    ex.getMessage should include("client")
    val noTools = intercept[NullPointerException](Llm4s.createAgent(new JLlmClient(client((_, _) => ok("x"))), null))
    noTools.getMessage should include("tools")
  }

  it should "return a failure for a null provider config" in {
    Llm4s.createClient(null).isFailure shouldBe true
  }

  "ConversationBuilder" should "reject null content fast, naming the role" in {
    intercept[NullPointerException](ConversationBuilder.create().user(null)).getMessage should include("user")
    intercept[NullPointerException](ConversationBuilder.create().system(null)).getMessage should include("system")
    intercept[NullPointerException](ConversationBuilder.create().assistant(null)).getMessage should include(
      "assistant"
    )
  }

  it should "keep every message, in call order, whichever role method is called last" in {
    val conv = ConversationBuilder.create().user("u1").system("s").assistant("a").user("u2").system("s2").build()
    conv.messages.map(m => m.role.name -> m.content) shouldBe Seq(
      "user"      -> "u1",
      "system"    -> "s",
      "assistant" -> "a",
      "user"      -> "u2",
      "system"    -> "s2"
    )
  }

  it should "be immutable: deriving from a shared builder never changes it" in {
    val base = ConversationBuilder.create().system("s")
    val a    = base.user("a")
    val b    = base.user("b")
    base.build().messages.map(_.content) shouldBe Seq("s")
    a.build().messages.map(_.content) shouldBe Seq("s", "a")
    b.build().messages.map(_.content) shouldBe Seq("s", "b")
  }

  it should "stay consistent when one builder is shared by many threads" in {
    val base    = ConversationBuilder.create().system("shared")
    val threads = 16
    val pool    = Executors.newFixedThreadPool(threads)
    val start   = new CountDownLatch(1)
    try {
      val futures = (0 until threads).map { i =>
        pool.submit(new java.util.concurrent.Callable[Seq[String]] {
          override def call(): Seq[String] = {
            start.await()
            var last: Seq[String] = Nil
            var n                 = 0
            while (n < 500) {
              last = base.user(s"u$i").assistant(s"a$i").build().messages.map(_.content)
              n += 1
            }
            last
          }
        })
      }
      start.countDown()
      futures.zipWithIndex.foreach { case (f, i) =>
        f.get(30, TimeUnit.SECONDS) shouldBe Seq("shared", s"u$i", s"a$i")
      }
      base.build().messages.map(_.content) shouldBe Seq("shared")
    } finally pool.shutdownNow()
  }
}
