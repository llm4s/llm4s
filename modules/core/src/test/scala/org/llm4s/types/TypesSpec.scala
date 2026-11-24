package org.llm4s.types

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ValidationError
import org.llm4s.syntax.toResult

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

/**
 * Comprehensive tests for org.llm4s.types package.
 *
 * Tests cover:
 * - Newtype wrappers (ModelName, ApiKey, ConversationId, etc.)
 * - Validation and smart constructors
 * - Result companion object methods
 * - the Try and Option conversions of org.llm4s.syntax
 */
class TypesSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // HeadroomPercent Tests
  // ==========================================================================

  "HeadroomPercent" should "create valid headroom" in {
    val result = HeadroomPercent.create(0.1)
    result.map(_.asRatio).getOrElse(fail("Expected success")) shouldBe 0.1
  }

  it should "reject invalid headroom" in {
    HeadroomPercent.create(-0.1).isLeft shouldBe true
    HeadroomPercent.create(1.0).isLeft shouldBe true
    HeadroomPercent.create(1.5).isLeft shouldBe true
  }

  it should "accept both ends of the valid range" in {
    HeadroomPercent.create(0.0).map(_.asRatio) shouldBe Right(0.0)
    HeadroomPercent.create(0.999).map(_.asRatio) shouldBe Right(0.999)
  }

  it should "have no constructor that skips validation" in {
    assertDoesNotCompile("val h: HeadroomPercent = 1.5")
    assertDoesNotCompile("HeadroomPercent(1.5)")
  }

  it should "have preset constants" in {
    HeadroomPercent.Zero.asRatio shouldBe 0.0
    HeadroomPercent.Light.asRatio shouldBe 0.05
    HeadroomPercent.Standard.asRatio shouldBe 0.08
    HeadroomPercent.Conservative.asRatio shouldBe 0.15
  }

  // ==========================================================================
  // ContentSize Tests
  // ==========================================================================

  "ContentSize" should "create from string" in {
    val size = ContentSize.fromString("Hello World")
    size.asBytes shouldBe 11
  }

  it should "create from bytes" in {
    val size = ContentSize.fromBytes("Test".getBytes)
    size.asBytes shouldBe 4
  }

  it should "convert to KB and MB" in {
    val size = ContentSize(1024 * 1024)
    size.toKB shouldBe 1024.0
    size.toMB shouldBe 1.0
  }

  it should "check threshold" in {
    val size = ContentSize(1000)
    size.exceedsThreshold(500) shouldBe true
    size.exceedsThreshold(2000) shouldBe false
  }

  // ==========================================================================
  // ArtifactKey Tests
  // ==========================================================================

  "ArtifactKey" should "generate unique keys" in {
    val key1 = ArtifactKey.generate()
    val key2 = ArtifactKey.generate()

    key1.asString should not be key2.asString
  }

  it should "create from content with hash" in {
    val key = ArtifactKey.fromContent("Test content")
    key.asString should startWith("content_")
  }

  it should "create same key for same content" in {
    val key1 = ArtifactKey.fromContent("Same content")
    val key2 = ArtifactKey.fromContent("Same content")

    key1.asString shouldBe key2.asString
  }

  it should "create different keys for different content" in {
    val key1 = ArtifactKey.fromContent("Content 1")
    val key2 = ArtifactKey.fromContent("Content 2")

    key1.asString should not be key2.asString
  }

  // ==========================================================================
  // SemanticBlockId Tests
  // ==========================================================================

  "SemanticBlockId" should "generate short IDs" in {
    val id = SemanticBlockId.generate()
    id.asString.length shouldBe 8
  }

  // ==========================================================================
  // Result Companion Object Tests
  // ==========================================================================

  "Result.success" should "create a Right" in {
    val result = Result.success(42)
    result shouldBe Right(42)
  }

  "Result.failure" should "create a Left" in {
    val error  = ValidationError("test", "error")
    val result = Result.failure[Int](error)
    result shouldBe Left(error)
  }

  "Result.fromOption" should "convert Some to Right" in {
    val error  = ValidationError("test", "missing")
    val result = Result.fromOption(Some(42), error)
    result shouldBe Right(42)
  }

  it should "convert None to Left" in {
    val error  = ValidationError("test", "missing")
    val result = Result.fromOption(None, error)
    result shouldBe Left(error)
  }

  "Result.sequence" should "convert List[Result] to Result[List]" in {
    val results = List(Right(1), Right(2), Right(3))
    val result  = Result.sequence(results)
    result shouldBe Right(List(1, 2, 3))
  }

  it should "fail on first error" in {
    val error   = ValidationError("test", "error")
    val results = List(Right(1), Left(error), Right(3))
    val result  = Result.sequence(results)
    result shouldBe Left(error)
  }

  "Result.traverse" should "map and sequence" in {
    val list   = List(1, 2, 3)
    val result = Result.traverse(list)(n => Right(n * 2))
    result shouldBe Right(List(2, 4, 6))
  }

  it should "stop calling f after the first failure" in {
    val error = ValidationError("test", "error")
    var calls = List.empty[Int]
    val result = Result.traverse(List(1, 2, 3, 4)) { n =>
      calls = calls :+ n
      if (n == 2) Left(error) else Right(n)
    }
    result shouldBe Left(error)
    calls shouldBe List(1, 2)
  }

  it should "return the first of several failures" in {
    val first  = ValidationError("first", "error")
    val second = ValidationError("second", "error")
    Result.traverse(List(1, 2, 3))(n => if (n == 1) Right(n) else if (n == 2) Left(first) else Left(second)) shouldBe
      Left(first)
  }

  it should "handle a large list without overflowing the stack" in {
    Result.traverse((1 to 100000).toList)(n => Right(n)).map(_.size) shouldBe Right(100000)
  }

  "Result.safely" should "wrap successful computation" in {
    val result = Result.safely(42)
    result.getOrElse(fail("Expected success")) shouldBe 42
  }

  it should "wrap failed computation" in {
    val result = Result.safely(throw new RuntimeException("boom"))
    result.isLeft shouldBe true
  }

  "Result.validateAll" should "collect all successes" in {
    val items     = List(1, 2, 3)
    val validator = (n: Int) => Right(n * 2)
    val result    = Result.validateAll(items)(validator)
    result shouldBe Right(List(2, 4, 6))
  }

  it should "collect all errors" in {
    val items = List(1, -1, 2, -2)
    val validator = (n: Int) =>
      if (n > 0) Right(n)
      else Left(ValidationError("number", s"$n is not positive"))

    val result = Result.validateAll(items)(validator)
    result.isLeft shouldBe true
    result.left.getOrElse(fail("Expected failure")) should have size 2
  }

  // ==========================================================================
  // AsyncResult Type Alias Tests
  // ==========================================================================

  "AsyncResult" should "work as Future[Result[A]] type alias" in {
    // AsyncResult is just a type alias, so we test it via Future operations
    val asyncResult: AsyncResult[Int] = Future.successful(Right(42))
    val result                        = Await.result(asyncResult, 1.second)
    result shouldBe Right(42)
  }

  it should "hold failures correctly" in {
    val error                         = ValidationError("test", "error")
    val asyncResult: AsyncResult[Int] = Future.successful(Left(error))
    val result                        = Await.result(asyncResult, 1.second)
    result shouldBe Left(error)
  }

  // ==========================================================================
  // Try.toResult
  // ==========================================================================

  "Try.toResult" should "convert Success to Right" in {
    val t: Try[Int] = Success(42)
    val result      = t.toResult
    result shouldBe Right(42)
  }

  it should "convert Failure to Left" in {
    val t: Try[Int] = Failure(new RuntimeException("boom"))
    val result      = t.toResult
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // Option.toResult
  // ==========================================================================

  "Option.toResult" should "convert Some to Right" in {
    val error  = ValidationError("test", "missing")
    val opt    = Some(42)
    val result = opt.toResult(error)
    result shouldBe Right(42)
  }

  it should "convert None to Left" in {
    val error            = ValidationError("test", "missing")
    val opt: Option[Int] = None
    val result           = opt.toResult(error)
    result shouldBe Left(error)
  }

}
