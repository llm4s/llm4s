package org.llm4s.types

import org.llm4s.{ AsyncResult, Result }
import org.llm4s.error.{ AuthenticationError, LLMError, NetworkError, UnknownError, ValidationError }
import org.scalatest.Inside
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ConnectException
import scala.concurrent.{ ExecutionContext, Future }

/**
 * `AsyncResult`'s helpers and `Result.fromFuture` (#2485). A failed future becomes a `Left` through the default error
 * mapper, so the returned future itself never fails: `futureValue` would throw if it did.
 */
class AsyncResultSpec extends AnyFlatSpec with Matchers with ScalaFutures with Inside {

  implicit private val ec: ExecutionContext = ExecutionContext.parasitic

  private val error: LLMError = ValidationError("name", "must not be empty")

  private val fromFutures: Seq[(String, Future[Int] => Future[Result[Int]])] = Seq(
    "AsyncResult.fromFuture" -> (AsyncResult.fromFuture(_)),
    "Result.fromFuture"      -> (Result.fromFuture(_))
  )

  "AsyncResult.success" should "complete at once with a Right" in {
    val future = AsyncResult.success(42)
    future.isCompleted shouldBe true
    future.futureValue shouldBe Right(42)
  }

  "AsyncResult.failure" should "complete at once with a Left of the error" in {
    val future = AsyncResult.failure[Int](error)
    future.isCompleted shouldBe true
    future.futureValue shouldBe Left(error)
  }

  "AsyncResult.fromResult" should "keep a Right unchanged" in {
    val future = AsyncResult.fromResult(Right(42))
    future.isCompleted shouldBe true
    future.futureValue shouldBe Right(42)
  }

  it should "keep a Left unchanged" in {
    val future = AsyncResult.fromResult[Int](Left(error))
    future.isCompleted shouldBe true
    future.futureValue shouldBe Left(error)
  }

  fromFutures.foreach { case (name, fromFuture) =>
    name should "give a Right for a successful future" in {
      fromFuture(Future.successful(42)).futureValue shouldBe Right(42)
    }

    it should "give an UnknownError carrying the exception for a plain failure" in {
      val boom = new RuntimeException("boom")
      inside(fromFuture(Future.failed(boom)).futureValue) { case Left(e: UnknownError) =>
        e.message shouldBe "boom"
        (e.cause should be).theSameInstanceAs(boom)
      }
    }

    it should "give a NetworkError for a ConnectException" in {
      val refused = new ConnectException("refused")
      inside(fromFuture(Future.failed(refused)).futureValue) { case Left(e: NetworkError) =>
        e.message shouldBe "Connection failed"
        e.cause shouldBe Some(refused)
      }
    }

    it should "give an AuthenticationError for an exception naming HTTP 401" in {
      inside(fromFuture(Future.failed(new RuntimeException("HTTP 401 Unauthorized"))).futureValue) {
        case Left(e: AuthenticationError) => e.code shouldBe Some("401")
      }
    }
  }
}
