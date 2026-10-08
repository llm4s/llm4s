package org.llm4s.toolapi

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

import java.lang.management.ManagementFactory
import java.util.concurrent.{ CountDownLatch, ExecutorService, ForkJoinPool, Executors, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ Await, ExecutionContext, ExecutionContextExecutorService, Future }
import scala.concurrent.duration._

/**
 * Retry backoff in [[ToolRegistry.executeAsync]] / [[ToolRegistry.executeAll]] (issue #1066).
 *
 * The claim under test: while an asynchronous call waits out its backoff it occupies no thread, so a small
 * execution context keeps serving other work. The two timing tests below are written so that they FAIL on a
 * backoff that sleeps on a pool thread (`blocking(Thread.sleep(...))`), with wide margins on both sides so a
 * slow runner does not flip them.
 */
class ToolRegistryBackoffSpec extends AnyFlatSpec with Matchers {

  final case class Ok(result: Double)
  implicit val okRW: ReadWriter[Ok] = macroRW

  /** A tool whose handler body runs on every call; a throw becomes an ExecutionError (retryable when it is an IO error). */
  private def toolOf(name: String)(body: => Either[String, Ok]): ToolFunction[Map[String, Any], Ok] =
    ToolBuilder[Map[String, Any], Ok](name, s"test tool $name", Schema.`object`[Map[String, Any]]("p"))
      .withHandler(_ => body)
      .buildSafe()
      .fold(e => fail(s"Tool creation failed: ${e.formatted}"), identity)

  /** Fails with a retryable IOException `failures` times, then succeeds; counts every attempt. */
  private def flaky(
    name: String,
    failures: Int,
    attempts: AtomicInteger,
    firstAttempt: CountDownLatch = new CountDownLatch(1)
  ): ToolFunction[Map[String, Any], Ok] =
    toolOf(name) {
      val n = attempts.getAndIncrement()
      firstAttempt.countDown()
      if (n < failures) throw new java.io.IOException(s"transient failure $n")
      Right(Ok(n.toDouble))
    }

  private def request(name: String) = ToolCallRequest(name, ujson.Obj())

  private def retry(maxAttempts: Int, base: FiniteDuration, factor: Double = 1.0) =
    ToolExecutionConfig(retryPolicy = Some(ToolRetryPolicy(maxAttempts, base, factor)))

  private def withPool[A](threads: Int)(f: ExecutionContextExecutorService => A): A = {
    val executor: ExecutorService             = Executors.newFixedThreadPool(threads)
    val pool: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(executor)
    try f(pool)
    finally pool.shutdownNow(): Unit
  }

  private def timed[A](f: => A): (A, Long) = {
    val start = System.nanoTime()
    val a     = f
    (a, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
  }

  // ============ the delay scheduler's thread is never where a continuation runs ============

  "Backoff.after" should "not run an inline continuation on the JVM's delay-scheduler thread" in {
    // `ExecutionContext.parasitic` runs a continuation on whichever thread completes the future. Were the
    // promise completed on the delay scheduler's own thread, a retried attempt under an inline context
    // would occupy the JVM-wide singleton that serves every delayed CompletableFuture, holding back every
    // other backoff in the process (Codex review). The completion is handed to the JDK's default
    // asynchronous executor instead, so the scheduler thread only ever triggers the handoff.
    val threadName = Await.result(
      Backoff.after(10.millis).map(_ => Thread.currentThread().getName)(ExecutionContext.parasitic),
      10.seconds
    )
    (threadName should not).include("CompletableFutureDelayScheduler")
  }

  // ============ the claim: no pool thread is parked during backoff ============

  "ToolRegistry.executeAsync" should "leave its execution-context thread free while a retry waits out its backoff" in {
    val attempts     = new AtomicInteger(0)
    val firstAttempt = new CountDownLatch(1)
    val registry     = new ToolRegistry(Seq(flaky("flaky", 1, attempts, firstAttempt)))
    withPool(1) { pool =>
      val result = registry.executeAsync(request("flaky"), retry(2, 1500.millis))(pool)
      firstAttempt.await(5, TimeUnit.SECONDS) shouldBe true
      // The only pool thread must now be free: a probe submitted during the 1.5 s backoff runs at once. A
      // backoff that sleeps on the pool thread holds the probe back for roughly the whole delay.
      val (probe, probeMs) = timed(Await.result(Future("served")(pool), 10.seconds))
      probe shouldBe "served"
      probeMs should be < 700L
      Await.result(result, 10.seconds).isRight shouldBe true
      attempts.get shouldBe 2
    }
  }

  it should "finish many concurrent retries on a small pool in about one backoff, not one backoff per wave" in {
    val calls    = 6
    val attempts = Vector.fill(calls)(new AtomicInteger(0))
    val registry = new ToolRegistry(attempts.zipWithIndex.map { case (a, i) => flaky(s"t$i", 1, a) })
    withPool(2) { pool =>
      val config = retry(2, 800.millis)
      val (results, elapsedMs) = timed {
        Await.result(
          Future.traverse((0 until calls).toList)(i => registry.executeAsync(request(s"t$i"), config)(pool))(
            implicitly,
            pool
          ),
          30.seconds
        )
      }
      results.forall(_.isRight) shouldBe true
      attempts.map(_.get).forall(_ == 2) shouldBe true
      // Two threads and a 0.8 s sleep per call would take three waves, 2.4 s or more. Scheduled delays overlap:
      // the whole batch is about one backoff plus the attempts themselves.
      elapsedMs should be >= 700L
      elapsedMs should be < 1800L
    }
  }

  it should "not grow the thread count over many retried calls" in {
    val mx     = ManagementFactory.getThreadMXBean
    val calls  = 100
    val tools  = Vector.fill(calls)(new AtomicInteger(0))
    val reg    = new ToolRegistry(tools.zipWithIndex.map { case (a, i) => flaky(s"t$i", 1, a) })
    val config = retry(2, 5.millis)
    withPool(4) { pool =>
      Await.result(
        Future.traverse((0 until calls).toList)(i => reg.executeAsync(request(s"t$i"), config)(pool))(
          implicitly,
          pool
        ),
        30.seconds
      )
      val before = mx.getThreadCount
      Await.result(
        Future.traverse((0 until calls).toList)(i => reg.executeAsync(request(s"t$i"), config)(pool))(
          implicitly,
          pool
        ),
        30.seconds
      )
      // A second identical round after the pool and the delay machinery are warm adds no threads.
      mx.getThreadCount should be <= (before + 5)
    }
  }

  // ============ the retry semantics are unchanged ============

  it should "wait at least the configured backoff between attempts and retry once" in {
    val attempts = new AtomicInteger(0)
    val registry = new ToolRegistry(Seq(flaky("flaky", 1, attempts)))
    withPool(2) { pool =>
      val (result, elapsedMs) =
        timed(Await.result(registry.executeAsync(request("flaky"), retry(2, 300.millis))(pool), 10.seconds))
      result.map(_("result").num) shouldBe Right(1.0)
      attempts.get shouldBe 2
      elapsedMs should be >= 250L
    }
  }

  it should "retry at once when the backoff is zero" in {
    val attempts = new AtomicInteger(0)
    val registry = new ToolRegistry(Seq(flaky("flaky", 2, attempts)))
    withPool(1) { pool =>
      val (result, elapsedMs) =
        timed(Await.result(registry.executeAsync(request("flaky"), retry(3, Duration.Zero))(pool), 10.seconds))
      result.map(_("result").num) shouldBe Right(2.0)
      attempts.get shouldBe 3
      // No delay to wait out: two retries on a single thread finish far inside any real backoff.
      elapsedMs should be < 1500L
    }
  }

  it should "grow the delay exponentially across several retries" in {
    val attempts = new AtomicInteger(0)
    val registry = new ToolRegistry(Seq(flaky("flaky", 3, attempts)))
    withPool(2) { pool =>
      val (result, elapsedMs) = timed(
        Await.result(registry.executeAsync(request("flaky"), retry(4, 50.millis, 2.0))(pool), 10.seconds)
      )
      result.isRight shouldBe true
      attempts.get shouldBe 4
      // 50 + 100 + 200 ms of backoff; a constant or linear schedule (about 150 ms) cannot reach this.
      elapsedMs should be >= 300L
    }
  }

  it should "return the last error once the attempts are exhausted" in {
    val attempts = new AtomicInteger(0)
    val failing  = toolOf("failing") { attempts.incrementAndGet(); throw new java.io.IOException("always") }
    val registry = new ToolRegistry(Seq(failing))
    withPool(2) { pool =>
      val result = Await.result(registry.executeAsync(request("failing"), retry(3, 10.millis))(pool), 10.seconds)
      result.left.toOption.get shouldBe a[ToolCallError.ExecutionError]
      attempts.get shouldBe 3
    }
  }

  it should "not retry, and not wait, on an error that is not retryable" in {
    val attempts = new AtomicInteger(0)
    val failing  = toolOf("failing") { attempts.incrementAndGet(); Left("validation error") }
    val registry = new ToolRegistry(Seq(failing))
    withPool(2) { pool =>
      val (result, elapsedMs) =
        timed(Await.result(registry.executeAsync(request("failing"), retry(3, 5.seconds))(pool), 10.seconds))
      result.left.toOption.get shouldBe a[ToolCallError.HandlerError]
      attempts.get shouldBe 1
      elapsedMs should be < 2000L
    }
  }

  it should "retry after a timeout" in {
    val attempts = new AtomicInteger(0)
    val slowThenFast = toolOf("slow") {
      if (attempts.getAndIncrement() == 0) Thread.sleep(600)
      Right(Ok(1.0))
    }
    val registry = new ToolRegistry(Seq(slowThenFast))
    val config = ToolExecutionConfig(
      timeout = Some(100.millis),
      retryPolicy = Some(ToolRetryPolicy(2, 20.millis))
    )
    withPool(2) { pool =>
      val result = Await.result(registry.executeAsync(request("slow"), config)(pool), 10.seconds)
      result.isRight shouldBe true
      attempts.get shouldBe 2
    }
  }

  it should "return Cancelled without retrying, and leave no interrupt flag on its pool thread" in {
    val attempts = new AtomicInteger(0)
    val cancelled = toolOf("cancelled") {
      attempts.incrementAndGet()
      throw new InterruptedException("stop")
    }
    val registry = new ToolRegistry(Seq(cancelled))
    // One fork-join worker: the probe below runs on the very thread the cancelled attempt used, and a
    // ForkJoinPool (unlike a ThreadPoolExecutor) does not clear a worker's flag between tasks.
    val fj   = new ForkJoinPool(1)
    val pool = ExecutionContext.fromExecutorService(fj)
    try {
      val result = Await.result(registry.executeAsync(request("cancelled"), retry(3, 10.millis))(pool), 10.seconds)
      result shouldBe Left(ToolCallError.Cancelled("cancelled"))
      attempts.get shouldBe 1
      Await.result(Future(Thread.currentThread().isInterrupted)(pool), 10.seconds) shouldBe false
    } finally pool.shutdownNow(): Unit
  }

  // ============ the synchronous path keeps its contract ============

  "ToolRegistry.execute" should "still retry on a single-thread execution context, with and without a timeout" in {
    val plain    = new AtomicInteger(0)
    val timeouts = new AtomicInteger(0)
    val registry = new ToolRegistry(
      Seq(
        flaky("plain", 1, plain),
        toolOf("slow") {
          if (timeouts.getAndIncrement() == 0) Thread.sleep(600)
          Right(Ok(1.0))
        }
      )
    )
    withPool(1) { pool =>
      val viaPool = Future {
        val a = registry.execute(request("plain"), retry(2, 50.millis))(pool)
        val b = registry.execute(
          request("slow"),
          ToolExecutionConfig(timeout = Some(100.millis), retryPolicy = Some(ToolRetryPolicy(2, 20.millis)))
        )(pool)
        (a, b)
      }(pool)
      val (a, b) = Await.result(viaPool, 10.seconds)
      a.isRight shouldBe true
      b.isRight shouldBe true
      plain.get shouldBe 2
      timeouts.get shouldBe 2
    }
  }
}
