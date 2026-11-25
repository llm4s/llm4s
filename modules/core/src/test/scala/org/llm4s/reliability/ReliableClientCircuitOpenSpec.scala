package org.llm4s.reliability

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.error.{ CircuitOpenError, LLMError, ServiceError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ CountDownLatch, CyclicBarrier, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }
import scala.collection.mutable
import scala.concurrent.duration._

/** What a caller meets while the circuit is open or probing (#2797). */
class ReliableClientCircuitOpenSpec extends AnyFlatSpec with Matchers {

  private val conversation = Conversation(List(UserMessage("hello")))

  private val ok: Result[Completion] = Right(
    Completion("id", Instant.EPOCH, ModelName("model"), AssistantMessage("content"))
  )
  private val down: Result[Completion] = Left(ServiceError(503, "provider-x", "down"))

  final private class StubClient(result: () => Result[Completion]) extends LLMClient {
    val calls = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.incrementAndGet()
      result()
    }

    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def contextWindow: Int     = 4096
    override def reserveCompletion: Int = 512
  }

  private val breaker = ReliabilityConfig.default
    .withRetryPolicy(RetryPolicy.noRetry)
    .withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 1, recoveryTimeout = 30.seconds))

  /** A client whose circuit one failure has opened, at time 0 of `now`. */
  private def opened(now: AtomicLong, result: () => Result[Completion]): (ReliableClient, StubClient) = {
    val underlying = new StubClient(result)
    val client     = ReliableClient(underlying, "provider-x", breaker).withClock(() => Instant.ofEpochMilli(now.get()))
    (client, underlying)
  }

  private def failFast(client: ReliableClient): LLMError = client.complete(conversation).left.toOption.get

  "An open circuit's fast-fail" should "not be retryable, so an outer retry does not repeat it" in {
    val now                  = new AtomicLong(0L)
    val (client, underlying) = opened(now, () => down)
    client.complete(conversation) shouldBe down
    client.currentCircuitState shouldBe CircuitState.Open

    now.set(10000L)
    val fastFail = failFast(client)
    fastFail shouldBe CircuitOpenError.open("provider-x", 20.seconds)
    LLMError.isRecoverable(fastFail) shouldBe true
    fastFail.message should include("Circuit breaker is open for provider-x")
    fastFail.message should include("Retry after 20s")
    RetryPolicy.exponentialBackoff().isRetryable(fastFail) shouldBe false
    org.llm4s.metrics.ErrorKind.fromLLMError(fastFail) shouldBe org.llm4s.metrics.ErrorKind.ServiceError

    val sleeps = mutable.Buffer.empty[FiniteDuration]
    val outer = ReliableClient(
      client,
      "outer",
      ReliabilityConfig.default.withRetryPolicy(RetryPolicy.fixedDelay(maxAttempts = 3, delay = 1.second))
    ).withSleep(sleeps += _)
    outer.complete(conversation).isLeft shouldBe true
    sleeps shouldBe empty
    underlying.calls.get() shouldBe 1
  }

  "A circuit past its recovery time" should "let exactly one of many concurrent callers probe" in {
    val now     = new AtomicLong(0L)
    val probing = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val failed  = new java.util.concurrent.atomic.AtomicBoolean(false)
    val underlying = new StubClient(() =>
      if (failed.compareAndSet(false, true)) down
      else {
        probing.countDown()
        release.await(20, TimeUnit.SECONDS)
        ok
      }
    )
    val client = ReliableClient(underlying, "provider-x", breaker).withClock(() => Instant.ofEpochMilli(now.get()))
    client.complete(conversation) shouldBe down
    now.set(31000L)

    val callers = 16
    val barrier = new CyclicBarrier(callers)
    val results = new java.util.concurrent.ConcurrentLinkedQueue[Result[Completion]]()
    val threads = (1 to callers).map { i =>
      val body: Runnable = () => {
        barrier.await(20, TimeUnit.SECONDS)
        results.add(client.complete(conversation))
        ()
      }
      val t = new Thread(body, s"probe-$i")
      t.start()
      t
    }
    probing.await(20, TimeUnit.SECONDS) shouldBe true
    // every caller but the probe has been turned away before the probe is allowed to finish
    val deadline = System.nanoTime() + 20.seconds.toNanos
    while (results.size() < callers - 1 && System.nanoTime() < deadline) Thread.sleep(5)
    val turnedAway = results.size()
    release.countDown()
    threads.foreach(_.join(20000))

    underlying.calls.get() shouldBe 2 // the failure that opened the circuit, and one probe
    turnedAway shouldBe callers - 1
    results.size() shouldBe callers
    import scala.jdk.CollectionConverters._
    val (probed, refused) = results.asScala.toList.partition(_.isRight)
    probed should have size 1
    refused.map(_.left.toOption.get).distinct shouldBe List(CircuitOpenError.probing("provider-x"))
    CircuitOpenError.probing("provider-x").retryAfter shouldBe Duration.Zero
    RetryPolicy.exponentialBackoff().isRetryable(CircuitOpenError.probing("provider-x")) shouldBe false
  }

  it should "let the next caller probe once a probe has succeeded, and close after successThreshold probes" in {
    val now                  = new AtomicLong(0L)
    var result               = down
    val (client, underlying) = opened(now, () => result)
    client.complete(conversation) shouldBe down
    result = ok
    now.set(31000L)
    client.complete(conversation) shouldBe ok
    client.currentCircuitState shouldBe CircuitState.HalfOpen
    client.complete(conversation) shouldBe ok
    client.currentCircuitState shouldBe CircuitState.Closed
    underlying.calls.get() shouldBe 3
  }

  it should "open again when the probe fails, and probe again after another recovery time" in {
    val now                  = new AtomicLong(0L)
    val (client, underlying) = opened(now, () => down)
    client.complete(conversation) shouldBe down
    now.set(31000L)
    client.complete(conversation) shouldBe down
    client.currentCircuitState shouldBe CircuitState.Open
    failFast(client) shouldBe CircuitOpenError.open("provider-x", 30.seconds)
    now.set(62000L)
    client.complete(conversation) shouldBe down
    underlying.calls.get() shouldBe 3
  }

  "A configuration the client cannot run with" should "fail every call and validate() with its ValidationError (#2797)" in {
    val bad = Seq(
      ReliabilityConfig.default.withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 0)) ->
        "circuitBreaker.failureThreshold",
      ReliabilityConfig.default.withRateLimit(RateLimitConfig(enabled = true, burstCapacity = 0)) ->
        "rateLimit.burstCapacity",
      ReliabilityConfig.default.withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = -1)) ->
        "rateLimit.requestsPerMinute"
    )
    bad.foreach { case (config, field) =>
      val underlying = new StubClient(() => ok)
      val client     = ReliableClient(underlying, "provider-x", config)
      val expected   = config.validate.left.toOption.get
      expected.asInstanceOf[ValidationError].field shouldBe field
      client.complete(conversation) shouldBe Left(expected)
      client.streamComplete(conversation, CompletionOptions())(_ => ()) shouldBe Left(expected)
      client.validate() shouldBe Left(expected)
      underlying.calls.get() shouldBe 0
    }
    // switched off, the client passes calls through and reads none of it
    val passedThrough = new StubClient(() => ok)
    val off = ReliableClient(
      passedThrough,
      "provider-x",
      ReliabilityConfig.disabled.withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 0))
    )
    off.complete(conversation) shouldBe ok
    // the stub has no validation request (#2847), so what passes through is its ValidationUnsupportedError
    off.validate() shouldBe passedThrough.validate()
  }
}
