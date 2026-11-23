package org.llm4s

import org.llm4s.error.{ CancelledError, LLMError, NetworkError, Safety, UnknownError, ValidationError }
import org.llm4s.syntax.{ toLLMError, toResult }
import org.llm4s.types.AsyncResult
import org.scalatest.Inside
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ConnectException
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

/**
 * The conversions to a `Result` in [[org.llm4s.syntax]] (#2485, #2821): `toResult` on a `Try`, an `Option` and a
 * `Future`, and `toLLMError` on a `Throwable`. Each maps an exception through `Safety.fromThrowable`, by its type
 * alone: a message that names an HTTP status is not read.
 */
class ConversionSyntaxSpec extends AnyFlatSpec with Matchers with ScalaFutures with Inside {

  private given ExecutionContext = ExecutionContext.parasitic

  private val error: LLMError = ValidationError("name", "must not be empty")

  "Try.toResult" should "give a Right for a Success" in {
    Try(42).toResult shouldBe Right(42)
  }

  it should "give the mapped exception for a Failure" in {
    val boom = new RuntimeException("boom")
    inside(Try[Int](throw boom).toResult) { case Left(e: UnknownError) =>
      e.message shouldBe "boom"
      (e.cause should be).theSameInstanceAs(boom)
    }
  }

  it should "not read an HTTP status out of the exception's message" in {
    Try[Int](throw new RuntimeException("HTTP 401 Unauthorized")).toResult.left.toOption.get shouldBe an[UnknownError]
    Try[Int](throw new RuntimeException("429: unexpected token")).toResult.left.toOption.get shouldBe an[UnknownError]
  }

  "Option.toResult" should "give a Right for a Some without evaluating the error" in {
    var evaluated = 0
    Option(42).toResult { evaluated += 1; error } shouldBe Right(42)
    evaluated shouldBe 0
  }

  it should "give the error for a None" in {
    Option.empty[Int].toResult(error) shouldBe Left(error)
  }

  "Future.toResult" should "give a Right for a successful future" in {
    val result: AsyncResult[Int] = Future.successful(42).toResult
    result.futureValue shouldBe Right(42)
  }

  it should "give an UnknownError carrying the exception for a plain failure, in a future that does not fail" in {
    val boom = new RuntimeException("boom")
    inside(Future.failed[Int](boom).toResult.futureValue) { case Left(e: UnknownError) =>
      e.message shouldBe "boom"
      (e.cause should be).theSameInstanceAs(boom)
    }
  }

  it should "give a NetworkError for a ConnectException" in {
    val refused = new ConnectException("refused")
    inside(Future.failed[Int](refused).toResult.futureValue) { case Left(e: NetworkError) =>
      e.message shouldBe "Connection failed"
      e.cause shouldBe Some(refused)
    }
  }

  "Throwable.toLLMError" should "be Safety.fromThrowable" in {
    val timeout = new java.net.SocketTimeoutException("timeout")
    timeout.toLLMError shouldBe Safety.fromThrowable(timeout)
    timeout.toLLMError shouldBe a[NetworkError]
    new RuntimeException(new InterruptedException("stop")).toLLMError shouldBe a[CancelledError]
  }
}
