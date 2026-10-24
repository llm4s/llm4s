package org.llm4s.javaapi

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.error.{ CancelledError, NetworkError, ProcessingError }
import org.llm4s.javaapi.StructuredRecords.Address
import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{
  CancellationException,
  CompletableFuture,
  CountDownLatch,
  ExecutionException,
  Executor,
  Executors,
  ForkJoinPool,
  RejectedExecutionException,
  TimeUnit,
  TimeoutException
}
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import java.util.concurrent.locks.LockSupport
import scala.collection.mutable.ArrayBuffer

/**
 * The `*Async` methods of `JLlmClient`, `JAgent` and `JEmbeddingClient` (#2276): the value or the `LlmException` of the
 * blocking call, cancellation that reaches the call, the executor they run on, and calls that run concurrently.
 */
class AsyncCallsSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  private val secs = 20L

  /** The `LlmException` a future failed with. */
  private def failure(future: CompletableFuture[?]): LlmException =
    intercept[ExecutionException](future.get(secs, TimeUnit.SECONDS)).getCause match {
      case e: LlmException => e
      case other           => fail(s"expected an LlmException, got $other")
    }

  /**
   * A client whose call signals `entered`, then blocks until `release` or an interrupt; an interrupt counts down
   * `interrupted` and returns a `CancelledError`, as a provider does.
   */
  final private class Blocking extends LLMClient {
    val entered     = new CountDownLatch(1)
    val release     = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    val calls       = new AtomicInteger
    val threads     = new java.util.concurrent.ConcurrentLinkedQueue[Thread]

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.incrementAndGet()
      threads.add(Thread.currentThread())
      entered.countDown()
      // parks rather than awaits, so an interrupt ends the wait without an InterruptedException, the flag left set
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(secs)
      while (release.getCount > 0 && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline)
        LockSupport.parkNanos(1000000L)
      if (Thread.currentThread().isInterrupted) interrupted.countDown()
      if (release.getCount == 0 && !Thread.currentThread().isInterrupted)
        Right(Completion("id", Instant.EPOCH, "late", "m", AssistantMessage("late")))
      else Left(CancelledError("Blocking"))
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  /** An executor that counts and records the tasks it runs, on a thread of `delegate`. */
  final private class Counting(delegate: Executor) extends Executor {
    val executed                               = new AtomicInteger
    override def execute(task: Runnable): Unit = { executed.incrementAndGet(); delegate.execute(task) }
  }

  // ---- JLlmClient ------------------------------------------------------------------------

  "completeAsync" should "complete the future with the reply, off the caller's thread" in {
    val fake    = FakeLlmClient.replying("4", "5", "6")
    val client  = fake.client()
    val replied = client.completeAsync("What is 2+2?")
    replied.get(secs, TimeUnit.SECONDS) shouldBe "4"
    client.completeAsync(ConversationBuilder.create().user("q").build()).get(secs, TimeUnit.SECONDS) shouldBe "5"
    client
      .completeAsync(ConversationBuilder.create().user("q").build(), JCompletionOptions.builder().maxTokens(5).build())
      .get(secs, TimeUnit.SECONDS) shouldBe "6"
    fake.prompts() shouldBe java.util.List.of("What is 2+2?", "q", "q")
  }

  "completionAsync" should "complete the future with the whole reply" in {
    val client = FakeLlmClient.replying("a", "b", "c").client()
    client.completionAsync("q").get(secs, TimeUnit.SECONDS).content shouldBe "a"
    val conversation = ConversationBuilder.create().user("q").build()
    client.completionAsync(conversation).get(secs, TimeUnit.SECONDS).content shouldBe "b"
    client
      .completionAsync(conversation, JCompletionOptions.builder().build())
      .get(secs, TimeUnit.SECONDS)
      .model shouldBe "fake"
  }

  "completeAsync with a record class" should "complete the future with the record" in {
    val json   = """{"city":"Paris","country":"France"}"""
    val client = FakeLlmClient.replying(json, json, json).client()
    client.completeAsync("Where?", classOf[Address]).get(secs, TimeUnit.SECONDS) shouldBe new Address("Paris", "France")
    val conversation = ConversationBuilder.create().user("Where?").build()
    client.completeAsync(conversation, classOf[Address]).get(secs, TimeUnit.SECONDS).city shouldBe "Paris"
    client
      .completeAsync(conversation, JCompletionOptions.builder().build(), classOf[Address])
      .get(secs, TimeUnit.SECONDS)
      .country shouldBe "France"
  }

  "A failed async call" should "complete the future exceptionally with the LlmException of the blocking call's kind" in {
    LlmErrorKind.values().foreach { kind =>
      val client = FakeLlmClient.builder().failWith(kind, "no").build().client()
      withClue(s"$kind: ")(failure(client.completeAsync("q")).getKind shouldBe kind)
    }
    failure(FakeLlmClient.replying().client().completeAsync(null: String)).getKind shouldBe LlmErrorKind.VALIDATION
    val notAddress = FakeLlmClient.replying("""{"city":1}""").client().completeAsync("q", classOf[Address])
    failure(notAddress).getKind shouldBe LlmErrorKind.VALIDATION
  }

  "cancel(true)" should "interrupt the call in flight, and leave the future cancelled" in {
    val provider = new Blocking
    val client   = Llm4s.wrapClient(provider)
    val future   = client.completeAsync("q")
    provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
    future.cancel(true) shouldBe true
    provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
    future.isCancelled shouldBe true
    intercept[CancellationException](future.get())
    client.close()
  }

  it should "interrupt a call on a ForkJoinPool, whose own Future.cancel(true) interrupts nothing" in {
    val pool     = new ForkJoinPool(1)
    val provider = new Blocking
    val future   = Llm4s.wrapClient(provider).withAsyncExecutor(pool).completeAsync("q")
    provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
    future.cancel(true) shouldBe true
    provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
    pool.shutdown()
  }

  it should "leave the executor's thread uninterrupted for the next call" in {
    val pool = Executors.newSingleThreadExecutor()
    try {
      val provider = new Blocking
      val client   = Llm4s.wrapClient(provider).withAsyncExecutor(pool)
      val first    = client.completeAsync("q")
      provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
      first.cancel(true) shouldBe true
      provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
      val flag = CompletableFuture.supplyAsync(() => Thread.currentThread().isInterrupted, pool)
      flag.get(secs, TimeUnit.SECONDS) shouldBe false
    } finally pool.shutdownNow()
  }

  "cancel(false)" should "not interrupt the call, and a second cancel does nothing" in {
    val provider = new Blocking
    val client   = Llm4s.wrapClient(provider)
    val future   = client.completeAsync("q")
    provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
    future.cancel(false) shouldBe true
    future.cancel(true) // the future is already cancelled: this does not interrupt either
    provider.release.countDown()
    provider.interrupted.getCount shouldBe 1
    client.close()
  }

  "A call cancelled before it started" should "never reach the provider" in {
    val held     = ArrayBuffer.empty[Runnable]
    val holding  = new Executor { def execute(task: Runnable): Unit = held += task }
    val provider = new Blocking
    val future   = Llm4s.wrapClient(provider).withAsyncExecutor(holding).completeAsync("q")
    future.cancel(true) shouldBe true
    held.foreach(_.run())
    held should have size 1
    provider.calls.get shouldBe 0
  }

  "orTimeout" should "fail the future with a TimeoutException and interrupt the call" in {
    val provider = new Blocking
    val client   = Llm4s.wrapClient(provider)
    val future   = client.completeAsync("q")
    provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
    future.orTimeout(1, TimeUnit.MILLISECONDS)
    provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
    intercept[ExecutionException](future.get(secs, TimeUnit.SECONDS)).getCause shouldBe a[TimeoutException]
    client.close()
  }

  "complete(value) from outside" should "interrupt the call, and the future keep that value" in {
    val provider = new Blocking
    val client   = Llm4s.wrapClient(provider)
    val future   = client.completeAsync("q")
    provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
    future.complete("mine") shouldBe true
    provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
    future.complete("again") shouldBe false
    future.completeExceptionally(new IllegalStateException("late")) shouldBe false
    future.get(secs, TimeUnit.SECONDS) shouldBe "mine"
    client.close()
  }

  "A call that throws" should "fail the future with an LlmException of kind OTHER, keeping the cause" in {
    val bug    = new IllegalStateException("bug")
    val future = FacadeAsync.run[String]((r: Runnable) => r.run(), "test")(throw bug)
    val e      = failure(future)
    e.getKind shouldBe LlmErrorKind.OTHER
    (e.getCause should be).theSameInstanceAs(bug)
  }

  "withAsyncExecutor" should "run every async call on the supplied executor, and close() leave it running" in {
    val pool     = Executors.newFixedThreadPool(2, r => new Thread(r, "supplied-pool"))
    val counting = new Counting(pool)
    try {
      val provider = new Blocking
      provider.release.countDown()
      val client = Llm4s.wrapClient(provider).withAsyncExecutor(counting)
      client.completeAsync("a").get(secs, TimeUnit.SECONDS) shouldBe "late"
      client.completionAsync("b").get(secs, TimeUnit.SECONDS).content shouldBe "late"
      counting.executed.get shouldBe 2
      provider.threads.toArray.map(_.asInstanceOf[Thread].getName).toSet shouldBe Set("supplied-pool")
      client.close()
      pool.isShutdown shouldBe false
    } finally pool.shutdownNow()
  }

  it should "refuse a null executor" in {
    val client = FakeLlmClient.replying().client()
    intercept[NullPointerException](client.withAsyncExecutor(null))
  }

  "An executor that refuses the call" should "fail the future at once with an LlmException, its cause the executor's" in {
    val refused  = new RejectedExecutionException("full")
    val refusing = new Executor { def execute(task: Runnable): Unit = throw refused }
    val future   = FakeLlmClient.replying("x").client().withAsyncExecutor(refusing).completeAsync("q")
    future.isCompletedExceptionally shouldBe true
    val e = failure(future)
    e.getKind shouldBe LlmErrorKind.OTHER
    (e.getCause should be).theSameInstanceAs(refused)
  }

  "The default executor" should "run each call on a virtual thread of the client's own" in {
    val provider = new Blocking
    provider.release.countDown()
    val client = Llm4s.wrapClient(provider)
    client.completeAsync("q").get(secs, TimeUnit.SECONDS) shouldBe "late"
    val thread = provider.threads.peek()
    thread.isVirtual shouldBe true
    thread.getName should startWith(FacadeAsync.ThreadPrefix)
    thread should not be Thread.currentThread()
    client.close()
  }

  it should "be closed with the client: a call in flight is interrupted, and a later call fails" in {
    val fake     = FakeLlmClient.replying("unused")
    val provider = new Blocking
    val client   = Llm4s.wrapClient(provider)
    val running  = client.completeAsync("q")
    provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
    client.close()
    provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
    failure(running).getKind shouldBe LlmErrorKind.CANCELLED
    val after = failure(client.completeAsync("again"))
    after.getKind shouldBe LlmErrorKind.OTHER
    after.getCause shouldBe a[RejectedExecutionException]
    provider.calls.get shouldBe 1
    // a FakeLlmClient's client is closed the same way
    fake.client().close()
    failure(fake.client().completeAsync("q")).getCause shouldBe a[RejectedExecutionException]
    fake.callCount() shouldBe 0
  }

  "allOf over several async calls" should "run them concurrently" in {
    val n       = 8
    val arrived = new CountDownLatch(n)
    // every call waits until all n are inside the provider at once: calls made one after another never get there
    val provider = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        arrived.countDown()
        val text = conversation.messages.collect { case u: UserMessage => u.content }.last
        if (arrived.await(secs, TimeUnit.SECONDS))
          Right(Completion("id", Instant.EPOCH, text, "m", AssistantMessage(text)))
        else Left(ProcessingError("concurrency", "the calls did not run at once"))
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }
    val client  = Llm4s.wrapClient(provider)
    val futures = (1 to n).map(i => client.completeAsync(s"q$i"))
    CompletableFuture.allOf(futures*).get(secs, TimeUnit.SECONDS)
    futures.map(_.join()) shouldBe (1 to n).map(i => s"q$i")
    client.close()
  }

  // ---- JAgent ----------------------------------------------------------------------------

  "JAgent.runAsync and continueConversationAsync" should "complete with the turn's result" in {
    val fake  = FakeLlmClient.replying("first", "second")
    val agent = Llm4s.createAgent(fake.client())
    val first = agent.runAsync("Hello").get(secs, TimeUnit.SECONDS)
    first.answer.get shouldBe "first"
    val second = agent.continueConversationAsync(first, "Again").get(secs, TimeUnit.SECONDS)
    second.answer.get shouldBe "second"
    second.threadId shouldBe first.threadId
  }

  "JAgent.resumeAsync and recoverAsync" should "fail as the blocking calls do" in {
    val agent = Llm4s.createAgent(FakeLlmClient.replying().client())
    failure(agent.resumeAsync("no-such-thread", java.util.List.of())).getKind shouldBe
      agent.resume("no-such-thread", java.util.List.of()).getError().getKind
    failure(agent.recoverAsync("no-such-thread")).getKind shouldBe
      agent.recover("no-such-thread").getError().getKind
    failure(agent.runAsync(null)).getKind shouldBe LlmErrorKind.VALIDATION
  }

  "JAgent.runAsync" should "fail with the LlmException the blocking run fails with" in {
    val fake =
      FakeLlmClient.builder().failWith(LlmErrorKind.RATE_LIMIT, "slow").failWith(LlmErrorKind.RATE_LIMIT, "slow")
    val agent   = Llm4s.createAgent(fake.build().client())
    val blocked = agent.run("Hello").getError()
    val async   = failure(agent.runAsync("Hello"))
    async.getKind shouldBe blocked.getKind
    async.getMessage shouldBe blocked.getMessage
  }

  it should "interrupt the turn's model call when cancelled with cancel(true)" in {
    val provider = new Blocking
    val agent    = Llm4s.createAgent(Llm4s.wrapClient(provider))
    val future   = agent.runAsync("Hello")
    provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
    future.cancel(true) shouldBe true
    provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
    future.isCancelled shouldBe true
  }

  it should "run on the executor withAsyncExecutor names" in {
    val pool     = Executors.newSingleThreadExecutor()
    val counting = new Counting(pool)
    try {
      val agent = Llm4s.createAgent(FakeLlmClient.replying("ok").client()).withAsyncExecutor(counting)
      agent.runAsync("Hello").get(secs, TimeUnit.SECONDS).answer.get shouldBe "ok"
      counting.executed.get shouldBe 1
    } finally pool.shutdownNow()
  }

  // ---- JEmbeddingClient ------------------------------------------------------------------

  private def embedder(reply: EmbeddingRequest => Result[EmbeddingResponse]): JEmbeddingClient =
    Llm4s.wrapEmbeddingClient(
      new EmbeddingClient(new EmbeddingProvider {
        def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = reply(request)
      })(using ModelRegistryService.fromModels(Nil)),
      EmbeddingModelConfig("embed-model", 2)
    )

  "JEmbeddingClient.embedAsync" should "complete with one vector per text" in {
    val purposes = new AtomicReference[InputPurpose]()
    val client = embedder { request =>
      purposes.set(request.purpose)
      Right(EmbeddingResponse(embeddings = request.input.map(t => Seq(t.length.toDouble, 1.0))))
    }
    val embeddings = client.embedAsync(java.util.List.of("ab", "abc")).get(secs, TimeUnit.SECONDS)
    embeddings.vectors.size shouldBe 2
    embeddings.vectors.get(1)(0) shouldBe 3.0f
    client.embedAsync(java.util.List.of("q"), JEmbeddingPurpose.QUERY).get(secs, TimeUnit.SECONDS)
    purposes.get shouldBe InputPurpose.Query
  }

  it should "fail with the blocking call's kind, and run on the executor withAsyncExecutor names" in {
    val client = embedder(_ => Left(NetworkError("down", None, "embed")))
    failure(client.embedAsync(java.util.List.of("a"))).getKind shouldBe LlmErrorKind.NETWORK
    failure(client.embedAsync(null)).getKind shouldBe LlmErrorKind.VALIDATION
    val counting = new Counting((r: Runnable) => r.run())
    failure(client.withAsyncExecutor(counting).embedAsync(java.util.List.of("a"))).getKind shouldBe LlmErrorKind.NETWORK
    counting.executed.get shouldBe 1
  }
}
