package org.llm4s.error

import org.llm4s.core.safety.DefaultErrorMapper
import org.llm4s.metrics.ErrorKind
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CancelledErrorSpec extends AnyFlatSpec with Matchers {

  /** Runs `f` with the flag as given, returning its result and whether the flag was set after. */
  private def withFlag[A](interrupted: Boolean)(f: => A): (A, Boolean) = {
    if interrupted then Thread.currentThread().interrupt()
    val a = f
    (a, Thread.interrupted()) // reads and clears, so no test leaks a flag
  }

  "CancelledError" should "be non-recoverable" in {
    LLMError.isRecoverable(CancelledError("op")) shouldBe false
    CancelledError("op").message shouldBe "op was cancelled"
  }

  "isCancellation" should "recognise interruption exceptions anywhere in the cause chain" in {
    CancelledError.isCancellation(new InterruptedException) shouldBe true
    CancelledError.isCancellation(new java.nio.channels.ClosedByInterruptException) shouldBe true
    CancelledError.isCancellation(new RuntimeException(new java.io.IOException(new InterruptedException))) shouldBe true
    CancelledError.isCancellation(
      new RuntimeException(new java.nio.channels.ClosedByInterruptException)
    ) shouldBe true
    CancelledError.isCancellation(new java.net.SocketException("closed")) shouldBe false
  }

  it should "count a bare InterruptedIOException only with the flag set or an InterruptedException beneath it" in {
    // OkHttp reports its call timeout as InterruptedIOException("timeout"): a timeout, not a cancellation
    CancelledError.isCancellation(new java.io.InterruptedIOException("timeout")) shouldBe false
    CancelledError.isCancellation(new RuntimeException(new java.io.InterruptedIOException("timeout"))) shouldBe false
    CancelledError.isCancellation(new java.net.SocketTimeoutException("read timed out")) shouldBe false
    CancelledError.isCancellation(new RuntimeException(new java.net.SocketTimeoutException("t"))) shouldBe false
    CancelledError.isCancellation(
      new java.io.InterruptedIOException("interrupted").initCause(new InterruptedException)
    ) shouldBe true
    CancelledError.isCancellation(
      new java.net.SocketTimeoutException("t").initCause(new InterruptedException)
    ) shouldBe true
    withFlag(interrupted = true)(
      CancelledError.isCancellation(new java.io.InterruptedIOException("interrupted"))
    )._1 shouldBe true
  }

  it should "treat any failure as a cancellation while the thread is interrupted" in {
    withFlag(interrupted = true)(CancelledError.isCancellation(new java.net.SocketException("closed")))._1 shouldBe true
  }

  it should "terminate on a cause cycle" in {
    val a = new RuntimeException("a")
    val b = new RuntimeException("b", a)
    a.initCause(b) // a -> b -> a
    CancelledError.isCancellation(a) shouldBe false
    val c = new RuntimeException("c")
    val d = new RuntimeException("d", c)
    c.initCause(d)
    CancelledError.isCancellation(new RuntimeException(new InterruptedException("i").initCause(c))) shouldBe true
  }

  "fromThrowable" should "classify a cancellation without setting the interrupt flag" in {
    val (error, flag) = withFlag(interrupted = false)(CancelledError.fromThrowable(new InterruptedException, "op"))
    error.map(_.operation) shouldBe Some("op")
    flag shouldBe false
    withFlag(interrupted = false)(CancelledError.fromThrowable(new IllegalStateException, "op")) shouldBe (None, false)
  }

  "whenInterrupted" should "turn a failure into a cancellation only while interrupted" in {
    val failed: org.llm4s.types.Result[Int] = Left(NetworkError("reset", None, "x"))
    withFlag(interrupted = false)(CancelledError.whenInterrupted(failed, "op"))._1 shouldBe failed
    val (cancelled, flag) = withFlag(interrupted = true)(CancelledError.whenInterrupted(failed, "op"))
    cancelled.left.toOption.get shouldBe a[CancelledError]
    flag shouldBe true
    withFlag(interrupted = true)(CancelledError.whenInterrupted(Right(1), "op"))._1 shouldBe Right(1)
  }

  "attempt" should "catch a thrown InterruptedException as a cancellation and keep the flag" in {
    val (result, flag) =
      withFlag(interrupted = false)(CancelledError.attempt[Int]("op")(throw new InterruptedException))
    result.left.toOption.get shouldBe a[CancelledError]
    flag shouldBe true
  }

  it should "let other exceptions propagate" in {
    an[IllegalStateException] should be thrownBy CancelledError.attempt[Int]("op")(throw new IllegalStateException)
  }

  "catchInterrupt" should "return Left for an InterruptedException, Right for a value, and propagate others" in {
    val e = new InterruptedException
    CancelledError.catchInterrupt(throw e) shouldBe Left(e)
    CancelledError.catchInterrupt(42) shouldBe Right(42)
    an[IllegalStateException] should be thrownBy CancelledError.catchInterrupt(throw new IllegalStateException)
  }

  "DefaultErrorMapper" should "map an interruption to CancelledError without setting the flag" in {
    val (error, flag) = withFlag(interrupted = false)(DefaultErrorMapper(new InterruptedException("i")))
    error shouldBe a[CancelledError]
    flag shouldBe false
  }

  it should "keep the flag it found set" in {
    val (error, flag) = withFlag(interrupted = true)(DefaultErrorMapper(new java.net.SocketException("closed")))
    error shouldBe a[CancelledError]
    flag shouldBe true
  }

  it should "not report OkHttp's call timeout as a cancellation" in {
    val (error, flag) =
      withFlag(interrupted = false)(
        DefaultErrorMapper(new RuntimeException(new java.io.InterruptedIOException("timeout")))
      )
    error should not be a[CancelledError]
    flag shouldBe false
  }

  "Safety.future.fromFuture" should "map an interrupted Future to CancelledError and leave the mapping thread's flag clear" in {
    import scala.concurrent.{ Await, ExecutionContext, Future }
    import scala.concurrent.duration._
    // One fork-join worker, so every callback below runs on the thread that did the mapping. Unlike
    // a ThreadPoolExecutor, a ForkJoinPool does not clear a worker's flag between tasks.
    val pool               = new java.util.concurrent.ForkJoinPool(1)
    given ExecutionContext = ExecutionContext.fromExecutorService(pool)
    // Scala boxes a failed Future's InterruptedException as ExecutionException(cause = IE)
    val failed: Future[Int] = Future.failed(new InterruptedException("cancelled"))
    val mapped              = org.llm4s.core.safety.Safety.future.fromFuture(failed)
    val flagAfter           = mapped.map(_ => Thread.currentThread().isInterrupted)
    val result              = Await.result(mapped, 5.seconds)
    val flag                = Await.result(flagAfter, 5.seconds)
    pool.shutdownNow(): Unit
    result.left.toOption.get shouldBe a[CancelledError]
    flag shouldBe false
  }

  "ErrorKind.fromLLMError" should "classify a cancellation" in {
    ErrorKind.fromLLMError(CancelledError("op")) shouldBe ErrorKind.Cancelled
  }
}
