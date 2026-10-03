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
    CancelledError.isCancellation(new java.io.InterruptedIOException) shouldBe true
    CancelledError.isCancellation(new java.nio.channels.ClosedByInterruptException) shouldBe true
    CancelledError.isCancellation(new RuntimeException(new java.io.IOException(new InterruptedException))) shouldBe true
    CancelledError.isCancellation(new java.net.SocketException("closed")) shouldBe false
  }

  it should "not mistake a SocketTimeoutException, an InterruptedIOException by inheritance, for one" in {
    CancelledError.isCancellation(new java.net.SocketTimeoutException("read timed out")) shouldBe false
    CancelledError.isCancellation(new RuntimeException(new java.net.SocketTimeoutException("t"))) shouldBe false
    CancelledError.isCancellation(
      new java.net.SocketTimeoutException("t").initCause(new InterruptedException)
    ) shouldBe true
  }

  it should "treat any failure as a cancellation while the thread is interrupted" in {
    withFlag(interrupted = true)(CancelledError.isCancellation(new java.net.SocketException("closed")))._1 shouldBe true
  }

  "fromThrowable" should "set the interrupt flag when it reports a cancellation" in {
    val (error, flag) = withFlag(interrupted = false)(CancelledError.fromThrowable(new InterruptedException, "op"))
    error.map(_.operation) shouldBe Some("op")
    flag shouldBe true
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

  "DefaultErrorMapper" should "map an interruption to CancelledError and keep the flag" in {
    val (error, flag) = withFlag(interrupted = false)(DefaultErrorMapper(new java.io.InterruptedIOException("i")))
    error shouldBe a[CancelledError]
    flag shouldBe true
  }

  "ErrorKind.fromLLMError" should "classify a cancellation" in {
    ErrorKind.fromLLMError(CancelledError("op")) shouldBe ErrorKind.Cancelled
  }
}
