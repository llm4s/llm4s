package org.llm4s.syntax

import org.llm4s.error.{ LLMError, NotFoundError, ValidationError }
import org.llm4s.syntax.syntax._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The `Result` extension methods in [[org.llm4s.syntax.syntax.ResultOps]].
 *
 * `Either` has its own `getOrElse` and `orElse` members, and a member always wins over an extension method of the same
 * name, so `result.getOrElse(...)` and `result.orElse(...)` never reach `ResultOps` even with the import in scope. The
 * tests for those two therefore call `ResultOps(...)` directly, and one test pins that the member is what an ordinary
 * call selects.
 */
class ResultSyntaxSpec extends AnyFlatSpec with Matchers {

  private val invalid: LLMError  = ValidationError("field", "message")
  private val notFound: LLMError = NotFoundError("missing", "key")

  private def ok(value: Int): Result[Int]          = Right(value)
  private def failed(error: LLMError): Result[Int] = Left(error)

  "ResultOps.getOrElse" should "return the value of a Right and the default of a Left" in {
    ResultOps(ok(1)).getOrElse(2) shouldBe 1
    ResultOps(failed(invalid)).getOrElse(2) shouldBe 2
  }

  it should "not evaluate the default for a Right" in {
    var evaluated = 0
    ResultOps(ok(1)).getOrElse { evaluated += 1; 2 } shouldBe 1
    evaluated shouldBe 0
  }

  "result.getOrElse with the syntax import" should "select Either's own member, which behaves the same" in {
    // Either#getOrElse is a member, so the extension is never chosen; both return the same values.
    ok(1).getOrElse(2) shouldBe ResultOps(ok(1)).getOrElse(2)
    failed(invalid).getOrElse(2) shouldBe ResultOps(failed(invalid)).getOrElse(2)
  }

  "ResultOps.orElse" should "return a Right unchanged without evaluating the alternative" in {
    var evaluated = 0
    ResultOps(ok(1)).orElse { evaluated += 1; ok(2) } shouldBe Right(1)
    evaluated shouldBe 0
  }

  it should "return the alternative for a Left, whether it is a Right or another Left" in {
    var evaluated = 0
    ResultOps(failed(invalid)).orElse { evaluated += 1; ok(2) } shouldBe Right(2)
    evaluated shouldBe 1
    ResultOps(failed(invalid)).orElse(failed(notFound)) shouldBe Left(notFound)
  }

  "mapError" should "change the error of a Left" in {
    failed(invalid).mapError(_ => notFound) shouldBe Left(notFound)
  }

  it should "leave a Right untouched and not call the function" in {
    var calls = 0
    ok(1).mapError { e =>
      calls += 1; e
    } shouldBe Right(1)
    calls shouldBe 0
  }

  "recover" should "turn a Left whose error matches into a Right" in {
    failed(invalid).recover { case _: ValidationError => 7 } shouldBe Right(7)
  }

  it should "keep the original Left when the error does not match" in {
    failed(invalid).recover { case _: NotFoundError => 7 } shouldBe Left(invalid)
  }

  it should "leave a Right untouched and not call the partial function" in {
    var calls = 0
    ok(1).recover { case _ => calls += 1; 7 } shouldBe Right(1)
    calls shouldBe 0
  }

  "recoverWith" should "replace a matching Left with the Right the partial function returns" in {
    failed(invalid).recoverWith { case _: ValidationError => ok(7) } shouldBe Right(7)
  }

  it should "replace a matching Left with the new Left the partial function returns" in {
    failed(invalid).recoverWith { case _: ValidationError => failed(notFound) } shouldBe Left(notFound)
  }

  it should "keep the original Left when the error does not match" in {
    failed(invalid).recoverWith { case _: NotFoundError => ok(7) } shouldBe Left(invalid)
  }

  it should "leave a Right untouched and not call the partial function" in {
    var calls = 0
    ok(1).recoverWith { case _ => calls += 1; ok(7) } shouldBe Right(1)
    calls shouldBe 0
  }

  "tap" should "run the effect once with the value of a Right and return the same result" in {
    var seen   = List.empty[Int]
    val result = ok(1)
    result.tap(v => seen = v :: seen) shouldBe theSameInstanceAs(result)
    seen shouldBe List(1)
  }

  it should "never run the effect for a Left" in {
    var calls = 0
    failed(invalid).tap(_ => calls += 1) shouldBe Left(invalid)
    calls shouldBe 0
  }

  "tapError" should "run the effect once with the error of a Left and return the same result" in {
    var seen   = List.empty[LLMError]
    val result = failed(invalid)
    result.tapError(e => seen = e :: seen) shouldBe theSameInstanceAs(result)
    seen shouldBe List(invalid)
  }

  it should "never run the effect for a Right" in {
    var calls = 0
    ok(1).tapError(_ => calls += 1) shouldBe Right(1)
    calls shouldBe 0
  }
}
