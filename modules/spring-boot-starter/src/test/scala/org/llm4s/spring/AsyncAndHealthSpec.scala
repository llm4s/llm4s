package org.llm4s.spring

import org.llm4s.error.APIError
import org.llm4s.javaapi.{ JLlmClient, JLlmClientTestFactory, LlmException }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.{ Bean, Configuration }

import java.time.Duration
import java.util.concurrent.{
  Callable,
  CountDownLatch,
  ExecutionException,
  ExecutorService,
  Executors,
  RejectedExecutionException,
  ThreadPoolExecutor,
  TimeUnit
}
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong, AtomicReference }
import scala.jdk.CollectionConverters._

object AsyncAndHealthSpec {

  /** A provider whose `complete` runs [[behaviour]]; records calls, threads, interrupts and the last request. */
  final class FakeProvider(behaviour: FakeProvider => Result[Completion], echoPrompt: Boolean = false) {
    val calls       = new AtomicInteger(0)
    val interrupts  = new AtomicInteger(0)
    val threads     = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    val entered     = new CountDownLatch(1)
    val release     = new CountDownLatch(1)
    val interrupted = new CountDownLatch(1)
    val lastConv    = new AtomicReference[Conversation]()
    val lastOptions = new AtomicReference[CompletionOptions]()

    /** Blocks until released (returns) or interrupted (counts it, then rethrows). */
    def blockUntilReleasedOrInterrupted(): Unit = {
      entered.countDown()
      if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("provider was never released")
    }

    val llm: LLMClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        calls.incrementAndGet()
        threads.add(Thread.currentThread().getName)
        lastConv.set(c)
        lastOptions.set(o)
        try if (echoPrompt) ok(c.messages.last.content) else behaviour(FakeProvider.this)
        catch {
          case e: InterruptedException =>
            interrupts.incrementAndGet()
            interrupted.countDown()
            throw e
        }
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }

    def client: JLlmClient = JLlmClientTestFactory.create(llm)
  }

  def ok(text: String): Result[Completion] = Right(Completion("id", 0L, text, "m", AssistantMessage(text)))

  def echo: FakeProvider = new FakeProvider(_ => ok("echo"))

  def pool(threads: Int, queue: Int = 1000): ExecutorService = {
    val p = new AsyncProperties
    p.maxThreads = threads
    p.queueCapacity = queue
    Llm4sExecutors.create(p)
  }

  @Configuration
  class CustomExecutorConfig {
    @Bean def llm4sTaskExecutor(): ExecutorService =
      Executors.newSingleThreadExecutor { r =>
        val t = new Thread(r, "custom-llm-thread"); t.setDaemon(true); t
      }
    @Bean def myClient(): JLlmClient = CustomExecutorConfig.provider.client
  }
  object CustomExecutorConfig {
    val provider: FakeProvider = echo
  }

  @Configuration
  class ProbeClientConfig {
    @Bean def myClient(): JLlmClient = ProbeClientConfig.provider.client
  }
  object ProbeClientConfig {
    val provider = new FakeProvider(_ => ok("pong"))
  }
}

class AsyncAndHealthSpec extends AnyFlatSpec with Matchers {
  import AsyncAndHealthSpec._

  private val secs = 10L

  private val secret = "sk-TOPSECRET-1234567890abcdef"

  private val runner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(classOf[Llm4sAutoConfiguration], classOf[LlmActuatorAutoConfiguration]))
    .withPropertyValues("llm4s.provider=ollama", "llm4s.model=m")

  private def withPool[A](p: ExecutorService)(f: ExecutorService => A): A =
    try f(p)
    finally p.shutdownNow()

  // ---- completeAsync -----------------------------------------------------------------------

  "completeAsync" should "return before the provider call finishes, so the caller is never blocked" in {
    val provider = new FakeProvider(p => { p.blockUntilReleasedOrInterrupted(); ok("late") })
    withPool(pool(2)) { ex =>
      val template = new LLM4STemplate(provider.client, ex)
      val future   = template.completeAsync("q") // would not return while the provider blocks if it were synchronous
      future.isDone shouldBe false
      provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
      provider.release.countDown()
      future.get(secs, TimeUnit.SECONDS) shouldBe "late"
    }
  }

  it should "run the blocking call on an executor thread, not the caller's" in {
    val provider = echo
    withPool(pool(2)) { ex =>
      val template = new LLM4STemplate(provider.client, ex)
      template.completeAsync("q").get(secs, TimeUnit.SECONDS) shouldBe "echo"
      template.completeAsync(Conversation(Seq(UserMessage("q")))).get(secs, TimeUnit.SECONDS) shouldBe "echo"
      provider.threads.asScala.foreach(_ should startWith("llm4s-async-"))
      provider.threads.asScala should not contain Thread.currentThread().getName
    }
  }

  it should "complete exceptionally with the LlmException and the original error" in {
    val error    = APIError("openai", "service unavailable")
    val provider = new FakeProvider(_ => Left(error))
    withPool(pool(1)) { ex =>
      val template = new LLM4STemplate(provider.client, ex)
      val e        = intercept[ExecutionException](template.completeAsync("q").get(secs, TimeUnit.SECONDS))
      e.getCause shouldBe a[LlmException]
      e.getCause.asInstanceOf[LlmException].error shouldBe error
    }
  }

  it should "keep the cause when the provider throws instead of returning an error" in {
    val boom     = new IllegalStateException("provider bug")
    val provider = new FakeProvider(_ => throw boom)
    withPool(pool(1)) { ex =>
      val template = new LLM4STemplate(provider.client, ex)
      val e        = intercept[ExecutionException](template.completeAsync("q").get(secs, TimeUnit.SECONDS))
      e.getCause shouldBe a[LlmException]
      (e.getCause.getCause should be).theSameInstanceAs(boom)
    }
  }

  it should "interrupt the provider call exactly once when the future is cancelled with cancel(true)" in {
    val provider = new FakeProvider(p => { p.blockUntilReleasedOrInterrupted(); ok("late") })
    val ex       = pool(1)
    val template = new LLM4STemplate(provider.client, ex)
    val future   = template.completeAsync("q")
    provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
    future.cancel(true) shouldBe true
    provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
    future.isCancelled shouldBe true
    future.cancel(true) // a second cancel must not interrupt again
    ex.shutdown()
    ex.awaitTermination(secs, TimeUnit.SECONDS) shouldBe true
    provider.interrupts.get shouldBe 1
    provider.calls.get shouldBe 1
  }

  it should "not start a queued call that was cancelled before it ran" in {
    val blocker = new FakeProvider(p => { p.blockUntilReleasedOrInterrupted(); ok("first") })
    val second  = echo
    withPool(pool(1)) { ex =>
      val first = new LLM4STemplate(blocker.client, ex).completeAsync("a")
      blocker.entered.await(secs, TimeUnit.SECONDS) shouldBe true
      val queued = new LLM4STemplate(second.client, ex).completeAsync("b")
      queued.cancel(false) shouldBe true
      blocker.release.countDown()
      first.get(secs, TimeUnit.SECONDS) shouldBe "first"
      ex.shutdown()
      ex.awaitTermination(secs, TimeUnit.SECONDS) shouldBe true
      second.calls.get shouldBe 0
    }
  }

  it should "serve 32 concurrent calls on a bounded number of executor threads" in {
    val n        = 32
    val provider = new FakeProvider(_ => ok("unused"), echoPrompt = true)
    withPool(pool(4)) { ex =>
      val template = new LLM4STemplate(provider.client, ex)
      val futures  = (1 to n).map(i => i -> template.completeAsync(s"q$i"))
      futures.foreach { case (i, f) => f.get(secs, TimeUnit.SECONDS) shouldBe s"q$i" }
      provider.calls.get shouldBe n
      provider.threads.size should ((be >= 1).and(be <= 4))
    }
  }

  it should "leave a finished future alone when cancelled afterwards, without touching the executor" in {
    val provider = echo
    withPool(pool(1)) { ex =>
      val future = new LLM4STemplate(provider.client, ex).completeAsync("q")
      future.get(secs, TimeUnit.SECONDS) shouldBe "echo"
      future.cancel(true) shouldBe false
      future.get() shouldBe "echo"
      provider.interrupts.get shouldBe 0
    }
  }

  it should "fail the future (not block) when the executor's queue is full" in {
    val blocker = new FakeProvider(p => { p.blockUntilReleasedOrInterrupted(); ok("x") })
    withPool(pool(1, queue = 1)) { ex =>
      val template = new LLM4STemplate(blocker.client, ex)
      val running  = template.completeAsync("1")
      blocker.entered.await(secs, TimeUnit.SECONDS) shouldBe true
      val queued   = template.completeAsync("2")
      val rejected = template.completeAsync("3")
      rejected.isCompletedExceptionally shouldBe true
      intercept[ExecutionException](rejected.get()).getCause shouldBe a[RejectedExecutionException]
      blocker.release.countDown()
      running.get(secs, TimeUnit.SECONDS) shouldBe "x"
      queued.get(secs, TimeUnit.SECONDS) shouldBe "x"
    }
  }

  // ---- the executor bean -------------------------------------------------------------------

  "the llm4sTaskExecutor bean" should "be shut down, with its threads gone, when the context closes" in {
    val captured = new AtomicReference[ExecutorService]()
    val worker   = new AtomicReference[Thread]()
    runner.run { ctx =>
      val ex = ctx.getBean(Llm4sExecutors.BeanName, classOf[ExecutorService])
      captured.set(ex)
      ex.submit(new Callable[String] {
        override def call(): String = { worker.set(Thread.currentThread()); "warm" }
      }).get(secs, TimeUnit.SECONDS)
      worker.get().isAlive shouldBe true
    }
    val ex = captured.get()
    ex.isShutdown shouldBe true
    ex.awaitTermination(secs, TimeUnit.SECONDS) shouldBe true
    worker.get().join(secs * 1000)
    worker.get().isAlive shouldBe false
  }

  it should "interrupt a call still running at shutdown rather than waiting for it" in {
    val provider = new FakeProvider(p => { p.blockUntilReleasedOrInterrupted(); ok("late") })
    runner
      .withBean("blockingClient", classOf[JLlmClient], () => provider.client)
      .run { ctx =>
        ctx.getBean(classOf[LLM4STemplate]).completeAsync("q")
        provider.entered.await(secs, TimeUnit.SECONDS) shouldBe true
      }
    provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
  }

  it should "be bounded by llm4s.async.* and rejects invalid sizes" in {
    runner.withPropertyValues("llm4s.async.max-threads=3", "llm4s.async.queue-capacity=7").run { ctx =>
      val ex = ctx.getBean(Llm4sExecutors.BeanName, classOf[ExecutorService]).asInstanceOf[ThreadPoolExecutor]
      ex.getMaximumPoolSize shouldBe 3
      ex.getQueue.remainingCapacity() shouldBe 7
    }
    runner.withPropertyValues("llm4s.async.queue-capacity=0").run(ctx => ctx.getStartupFailure should not be null)
    runner.withPropertyValues("llm4s.async.max-threads=0").run { ctx =>
      ctx.getStartupFailure should not be null
      Iterator
        .iterate[Throwable](ctx.getStartupFailure)(_.getCause)
        .takeWhile(_ != null)
        .map(_.getMessage)
        .mkString(
          " | "
        ) should include("llm4s.async.max-threads")
    }
  }

  it should "back off for a user bean of the same name, and the template then uses it" in {
    runner.withUserConfiguration(classOf[CustomExecutorConfig]).run { ctx =>
      ctx.getBeansOfType(classOf[ExecutorService]).keySet.asScala shouldBe Set("llm4sTaskExecutor")
      val template = ctx.getBean(classOf[LLM4STemplate])
      template.completeAsync("q").get(secs, TimeUnit.SECONDS) shouldBe "echo"
      CustomExecutorConfig.provider.threads.asScala shouldBe Set("custom-llm-thread")
    }
  }

  // ---- health indicator --------------------------------------------------------------------

  private def indicator(
    provider: FakeProvider,
    probe: Boolean,
    ex: ExecutorService,
    clock: AtomicLong = new AtomicLong(0),
    ttl: Duration = Duration.ofSeconds(60),
    timeout: Duration = Duration.ofSeconds(5)
  ) = new LlmHealthIndicator(
    provider.client,
    HealthSettings("openai", "gpt-4o", probe, ttl, timeout, Seq(secret)),
    ex,
    () => clock.get()
  )

  "the health indicator" should "report UP with probe=disabled and make no provider call by default" in {
    val provider = echo
    withPool(pool(1)) { ex =>
      val ind = indicator(provider, probe = false, ex)
      (1 to 20).foreach(_ => ind.health())
      val h = ind.health()
      h.getStatus shouldBe Status.UP
      h.getDetails.asScala.toMap shouldBe Map("provider" -> "openai", "model" -> "gpt-4o", "probe" -> "disabled")
      provider.calls.get shouldBe 0
    }
  }

  it should "send exactly one tiny provider call across many concurrent checks within the TTL" in {
    val provider = echo
    withPool(pool(2)) { ex =>
      val ind      = indicator(provider, probe = true, ex)
      val checkers = Executors.newFixedThreadPool(8)
      try {
        val start = new CountDownLatch(1)
        val results = (1 to 8).map { _ =>
          checkers.submit(new Callable[Seq[Status]] {
            override def call(): Seq[Status] = { start.await(); (1 to 25).map(_ => ind.health().getStatus) }
          })
        }
        start.countDown()
        results.flatMap(_.get(secs, TimeUnit.SECONDS)).toSet shouldBe Set(Status.UP)
      } finally checkers.shutdownNow()
      provider.calls.get shouldBe 1
      provider.lastOptions.get().maxTokens shouldBe Some(1)
      provider.lastConv.get().messages.map(_.content) should have size 1
      ind.health().getDetails.get("probe") shouldBe "ok"
    }
  }

  it should "probe again once the TTL has expired, and not before" in {
    val provider = echo
    val clock    = new AtomicLong(0)
    withPool(pool(1)) { ex =>
      val ind = indicator(provider, probe = true, ex, clock, ttl = Duration.ofSeconds(30))
      ind.health(); ind.health()
      provider.calls.get shouldBe 1
      clock.set(Duration.ofSeconds(29).toNanos)
      ind.health()
      provider.calls.get shouldBe 1
      clock.set(Duration.ofSeconds(30).toNanos)
      ind.health()
      provider.calls.get shouldBe 2
    }
  }

  it should "be DOWN with the key redacted when the probe fails" in {
    val provider =
      new FakeProvider(_ => Left(APIError("openai", s"401 for key $secret and token sk-anotherSecret123456")))
    withPool(pool(1)) { ex =>
      val h = indicator(provider, probe = true, ex).health()
      h.getStatus shouldBe Status.DOWN
      h.getDetails.get("probe") shouldBe "failed"
      val error = h.getDetails.get("error").toString
      error should include("401")
      (error should not).include(secret)
      (error should not).include("anotherSecret")
      (h.toString should not).include(secret)
    }
  }

  it should "redact a configured key even when it does not look like a token" in {
    val plain    = "correct-horse-battery"
    val provider = new FakeProvider(_ => Left(APIError("openai", s"rejected credential $plain")))
    withPool(pool(1)) { ex =>
      val ind = new LlmHealthIndicator(
        provider.client,
        HealthSettings("openai", "gpt-4o", true, Duration.ofSeconds(60), Duration.ofSeconds(5), Seq(plain)),
        ex
      )
      val error = ind.health().getDetails.get("error").toString
      error should include("rejected credential")
      (error should not).include(plain)
    }
  }

  it should "be DOWN on timeout, interrupt the provider call, and cache that outcome for the TTL" in {
    val provider = new FakeProvider(p => { p.blockUntilReleasedOrInterrupted(); ok("late") })
    withPool(pool(1)) { ex =>
      val ind = indicator(provider, probe = true, ex, timeout = Duration.ofMillis(100))
      val h   = ind.health()
      h.getStatus shouldBe Status.DOWN
      h.getDetails.get("probe") shouldBe "timeout"
      provider.interrupted.await(secs, TimeUnit.SECONDS) shouldBe true
      ind.health().getStatus shouldBe Status.DOWN
      provider.calls.get shouldBe 1
    }
  }

  it should "be DOWN when the provider throws a fatal error instead of answering, and never print the settings secrets" in {
    // JLlmClient captures a non-fatal throwable and an InterruptedException as failed results; a fatal error
    // (a LinkageError here) still escapes, so the executor's future fails and the indicator describes its cause
    val provider = new FakeProvider(_ => throw new LinkageError(s"stopped $secret"))
    withPool(pool(1)) { ex =>
      val ind = indicator(provider, probe = true, ex)
      val h   = ind.health()
      h.getStatus shouldBe Status.DOWN
      h.getDetails.get("probe") shouldBe "failed"
      val error = h.getDetails.get("error").toString
      error should include("LinkageError")
      (error should not).include(secret)
    }
    (HealthSettings("openai", "m", true, Duration.ofSeconds(1), Duration.ofSeconds(1), Seq(secret)).toString should not)
      .include(secret)
  }

  it should "be DOWN, with the message redacted, when the provider throws InterruptedException (#1591)" in {
    // JLlmClient.complete never throws it: the probe gets a CancelledError result, which is reported like any
    // other failed result (its message names the operation, not the exception's text)
    val provider = new FakeProvider(_ => throw new InterruptedException(s"stopped $secret"))
    withPool(pool(1)) { ex =>
      val h = indicator(provider, probe = true, ex).health()
      h.getStatus shouldBe Status.DOWN
      h.getDetails.get("probe") shouldBe "failed"
      val error = h.getDetails.get("error").toString
      error should include("cancelled")
      (error should not).include(secret)
      provider.calls.get shouldBe 1
    }
  }

  it should "be DOWN, not throw, when the executor refuses the probe" in {
    val provider = echo
    val ex       = pool(1)
    ex.shutdownNow()
    val h = indicator(provider, probe = true, ex).health()
    h.getStatus shouldBe Status.DOWN
    h.getDetails.get("probe") shouldBe "failed"
    provider.calls.get shouldBe 0
  }

  it should "be configured by llm4s.health.* and validate them" in {
    ProbeClientConfig.provider.calls.set(0)
    runner
      .withUserConfiguration(classOf[ProbeClientConfig])
      .withPropertyValues("llm4s.health.probe=true", "llm4s.health.probe-ttl=5m", "llm4s.health.probe-timeout=2s")
      .run { ctx =>
        val p = ctx.getBean(classOf[Llm4sProperties])
        p.health.probe shouldBe true
        p.health.probeTtl shouldBe Duration.ofMinutes(5)
        p.health.probeTimeout shouldBe Duration.ofSeconds(2)
        val ind = ctx.getBean(classOf[LlmHealthIndicator])
        (1 to 5).foreach(_ => ind.health().getStatus shouldBe Status.UP)
        ProbeClientConfig.provider.calls.get shouldBe 1
      }
    runner.withUserConfiguration(classOf[ProbeClientConfig]).withPropertyValues("llm4s.health.probe-timeout=0s").run {
      ctx => ctx.getStartupFailure should not be null
    }
    val defaults = new Llm4sProperties
    defaults.health.probe shouldBe false
    defaults.async.maxThreads shouldBe 16
  }
}
