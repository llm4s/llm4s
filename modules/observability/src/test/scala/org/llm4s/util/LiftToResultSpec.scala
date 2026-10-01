package org.llm4s.util

import cats.Id
import org.llm4s.error.{ NetworkError, NotFoundError, UnknownError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.util.LiftToResult.ThrowableEither

import scala.util.{ Failure, Success, Try }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LiftToResultSpec extends AnyFlatSpec with Matchers {

  // ---- LiftToResult.idInstance ----

  "LiftToResult.idInstance" should "wrap any value in Right" in {
    LiftToResult.idInstance.apply(42) shouldBe Right(42)
    LiftToResult.idInstance.apply("hello") shouldBe Right("hello")
    LiftToResult.idInstance.apply(true) shouldBe Right(true)
  }

  it should "wrap Unit in Right(())" in {
    LiftToResult.idInstance.apply(()) shouldBe Right(())
  }

  it should "be summoned implicitly for cats.Id" in {
    val lift = implicitly[LiftToResult[Id]]
    lift(99) shouldBe Right(99)
  }

  it should "work as a context bound" in {
    def liftValue[F[_]: LiftToResult, A](fa: F[A]): Result[A] =
      implicitly[LiftToResult[F]].apply(fa)

    liftValue[Id, String]("via-context-bound") shouldBe Right("via-context-bound")
    liftValue[Id, Int](0) shouldBe Right(0)
  }

  // ---- LiftToResult.eitherInstance (ThrowableEither) ----

  "LiftToResult.eitherInstance" should "pass Right through unchanged" in {
    val lift: LiftToResult[ThrowableEither] = LiftToResult.eitherInstance
    lift(Right(42)) shouldBe Right(42)
    lift(Right("ok")) shouldBe Right("ok")
  }

  it should "convert a Left RuntimeException to Left(UnknownError)" in {
    val ex                       = new RuntimeException("boom")
    val fa: ThrowableEither[Int] = Left(ex)
    val result: Result[Int]      = LiftToResult.eitherInstance(fa)
    result match {
      case Left(err: UnknownError) => err.message shouldBe "boom"
      case other                   => fail(s"Expected Left(UnknownError), got: $other")
    }
  }

  it should "convert a Left SocketTimeoutException to Left(NetworkError)" in {
    val ex                       = new java.net.SocketTimeoutException("timed out")
    val fa: ThrowableEither[Int] = Left(ex)
    LiftToResult.eitherInstance(fa) match {
      case Left(_: NetworkError) => succeed
      case other                 => fail(s"Expected Left(NetworkError), got: $other")
    }
  }

  it should "preserve the message from the throwable in the resulting LLMError" in {
    val ex                       = new RuntimeException("specific message")
    val fa: ThrowableEither[Int] = Left(ex)
    LiftToResult.eitherInstance(fa) match {
      case Left(err) => err.message should include("specific message")
      case Right(_)  => fail("Expected Left")
    }
  }

  it should "be summoned implicitly for ThrowableEither" in {
    val lift = implicitly[LiftToResult[ThrowableEither]]
    lift(Right(7)) shouldBe Right(7)
  }

  // ---- LiftToResult.tryInstance ----

  "LiftToResult.tryInstance" should "lift Success to Right" in {
    LiftToResult.tryInstance(Success(42)) shouldBe Right(42)
    LiftToResult.tryInstance(Success("hello")) shouldBe Right("hello")
  }

  it should "lift Failure(RuntimeException) to Left(UnknownError)" in {
    val ex = new RuntimeException("try failed")
    LiftToResult.tryInstance(Failure(ex)) match {
      case Left(err: UnknownError) => err.message shouldBe "try failed"
      case other                   => fail(s"Expected Left(UnknownError), got: $other")
    }
  }

  it should "lift Failure(SocketTimeoutException) to Left(NetworkError)" in {
    val ex = new java.net.SocketTimeoutException("timed out")
    LiftToResult.tryInstance(Failure(ex)) match {
      case Left(_: NetworkError) => succeed
      case other                 => fail(s"Expected Left(NetworkError), got: $other")
    }
  }

  it should "be summoned implicitly for Try" in {
    val lift = implicitly[LiftToResult[Try]]
    lift(Success(99)) shouldBe Right(99)
  }

  it should "work as a context bound for Try" in {
    def liftTry[A](fa: Try[A])(implicit L: LiftToResult[Try]): Result[A] = L(fa)
    liftTry(Success("ok")) shouldBe Right("ok")
    liftTry(Failure(new RuntimeException("oops"))).isLeft shouldBe true
  }

  // ---- LiftToResult.forOption ----

  "LiftToResult.forOption" should "lift Some to Right" in {
    val lift = LiftToResult.forOption(ValidationError("empty", "value"))
    lift(Some(42)) shouldBe Right(42)
    lift(Some("hello")) shouldBe Right("hello")
  }

  it should "lift None to Left with the supplied error" in {
    val onNone = NotFoundError("expected a value but option was empty", "value")
    val lift   = LiftToResult.forOption(onNone)
    lift[Int](None) shouldBe Left(onNone)
  }

  it should "lift None to Left with the same error for every type" in {
    val error = NotFoundError("empty", "key")
    val lift  = LiftToResult.forOption(error)
    lift[Int](None) shouldBe Left(error)
    lift[String](None) shouldBe Left(error)
  }

  it should "evaluate the onNone thunk lazily — only when None is present" in {
    var evaluated = 0
    // The block is passed by-name; it must not be evaluated when Some is lifted.
    val lift = LiftToResult.forOption { evaluated += 1; ValidationError("empty", "v") }

    lift(Some(1)) // fa = Some: toRight short-circuits, block not called
    evaluated shouldBe 0

    lift[Int](None) // fa = None: toRight calls the thunk
    evaluated shouldBe 1
  }
}
