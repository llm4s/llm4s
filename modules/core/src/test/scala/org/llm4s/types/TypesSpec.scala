package org.llm4s.types

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.error.ValidationError
import org.llm4s.Result

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
 * - TryOps, OptionOps, FutureOps conversions
 */
class TypesSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // FilePath Tests
  // ==========================================================================

  "FilePath" should "extract extension" in {
    FilePath("/path/to/file.txt").extension shouldBe Some("txt")
    FilePath("/path/to/file.scala").extension shouldBe Some("scala")
    FilePath("/path/to/file").extension shouldBe None
    FilePath("/path/to/.hidden").extension shouldBe Some("hidden")
  }

  it should "convert to string via toString" in {
    FilePath("/path/to/file.txt").toString shouldBe "/path/to/file.txt"
  }

  // ==========================================================================
  // HeadroomPercent Tests
  // ==========================================================================

  "HeadroomPercent" should "create valid headroom" in {
    val result = HeadroomPercent.create(0.1)
    result.map(_.value).getOrElse(fail("Expected success")) shouldBe 0.1
  }

  it should "reject invalid headroom" in {
    HeadroomPercent.create(-0.1).isLeft shouldBe true
    HeadroomPercent.create(1.0).isLeft shouldBe true
    HeadroomPercent.create(1.5).isLeft shouldBe true
  }

  it should "validate with isValid" in {
    HeadroomPercent(0.1).isValid shouldBe true
    HeadroomPercent(0.0).isValid shouldBe true
    HeadroomPercent(1.0).isValid shouldBe false
  }

  it should "have preset constants" in {
    HeadroomPercent.None.value shouldBe 0.0
    HeadroomPercent.Light.value shouldBe 0.05
    HeadroomPercent.Standard.value shouldBe 0.08
    HeadroomPercent.Conservative.value shouldBe 0.15
  }

  it should "convert to string" in {
    HeadroomPercent(0.15).toString shouldBe "15.0%"
  }

  it should "provide asRatio" in {
    HeadroomPercent(0.15).asRatio shouldBe 0.15
  }

  // ==========================================================================
  // ContentSize Tests
  // ==========================================================================

  "ContentSize" should "create from string" in {
    val size = ContentSize.fromString("Hello World")
    size.bytes shouldBe 11
  }

  it should "create from bytes" in {
    val size = ContentSize.fromBytes("Test".getBytes)
    size.bytes shouldBe 4
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

  it should "convert to string" in {
    ContentSize(1024).toString shouldBe "1024B"
  }

  // ==========================================================================
  // ArtifactKey Tests
  // ==========================================================================

  "ArtifactKey" should "generate unique keys" in {
    val key1 = ArtifactKey.generate()
    val key2 = ArtifactKey.generate()

    key1.value should not be key2.value
  }

  it should "create from content with hash" in {
    val key = ArtifactKey.fromContent("Test content")
    key.value should startWith("content_")
  }

  it should "create same key for same content" in {
    val key1 = ArtifactKey.fromContent("Same content")
    val key2 = ArtifactKey.fromContent("Same content")

    key1.value shouldBe key2.value
  }

  it should "create different keys for different content" in {
    val key1 = ArtifactKey.fromContent("Content 1")
    val key2 = ArtifactKey.fromContent("Content 2")

    key1.value should not be key2.value
  }

  // ==========================================================================
  // SemanticBlockId Tests
  // ==========================================================================

  "SemanticBlockId" should "generate short IDs" in {
    val id = SemanticBlockId.generate()
    id.value.length shouldBe 8
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

  "Result.combine" should "combine two results into tuple" in {
    val result = Result.combine(Right(1), Right("a"))
    result shouldBe Right((1, "a"))
  }

  it should "fail if first fails" in {
    val error  = ValidationError("test", "error")
    val result = Result.combine(Left(error), Right("a"))
    result shouldBe Left(error)
  }

  it should "combine three results" in {
    val result = Result.combine(Right(1), Right("a"), Right(true))
    result shouldBe Right((1, "a", true))
  }

  "Result.safely" should "wrap successful computation" in {
    val result = Result.safely(42)
    result.getOrElse(fail("Expected success")) shouldBe 42
  }

  it should "wrap failed computation" in {
    val result = Result.safely(throw new RuntimeException("boom"))
    result.isLeft shouldBe true
  }

  "Result.fromBoolean" should "return success for true" in {
    val error  = ValidationError("test", "error")
    val result = Result.fromBoolean(condition = true, error)
    result shouldBe Right(())
  }

  it should "return failure for false" in {
    val error  = ValidationError("test", "error")
    val result = Result.fromBoolean(condition = false, error)
    result shouldBe Left(error)
  }

  "Result.fromBooleanWithValue" should "return value for true" in {
    val error  = ValidationError("test", "error")
    val result = Result.fromBooleanWithValue(condition = true, 42, error)
    result shouldBe Right(42)
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
  // TryOps Tests
  // ==========================================================================

  "TryOps" should "convert Success to Right" in {
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
  // OptionOps Tests
  // ==========================================================================

  "OptionOps" should "convert Some to Right" in {
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
