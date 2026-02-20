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
  // ModelName Tests
  // ==========================================================================

  "ModelName" should "create valid model names" in {
    val result = ModelName("gpt-4")
    result.map(_.value).getOrElse(fail("Expected success")) shouldBe "gpt-4"
  }

  it should "reject invalid model names" in {
    val result = ModelName("invalid model!")
    result.isLeft shouldBe true
  }

  it should "have common constants" in {
    ModelName.GPT_4.value shouldBe "gpt-4"
    ModelName.GPT_4_TURBO.value shouldBe "gpt-4-turbo"
    ModelName.GPT_3_5_TURBO.value shouldBe "gpt-3.5-turbo"
    ModelName.CLAUDE_3_OPUS.value shouldBe "claude-3-opus-20240229"
    ModelName.CLAUDE_3_SONNET.value shouldBe "claude-3-sonnet-20240229"
    ModelName.CLAUDE_3_HAIKU.value shouldBe "claude-3-haiku-20240307"
  }

  it should "report isEmpty and nonEmpty correctly" in {
    ModelName.unsafe("gpt-4").isEmpty shouldBe false
    ModelName.unsafe("gpt-4").nonEmpty shouldBe true
    ModelName.unsafe("  ").isEmpty shouldBe true
    ModelName.unsafe("  ").nonEmpty shouldBe false
  }

  it should "convert to string via toString" in {
    ModelName.GPT_4.toString shouldBe "gpt-4"
  }

  it should "create from string without validation via fromString" in {
    val model = ModelName.fromString("any-model")
    model.value shouldBe "any-model"
  }

  // ==========================================================================
  // ProviderName Tests
  // ==========================================================================

  "ProviderName" should "create valid provider names" in {
    val result = ProviderName.create("OpenAI")
    result.map(_.value).getOrElse(fail("Expected success")) shouldBe "openai"
  }

  it should "reject empty provider names" in {
    val result = ProviderName.create("   ")
    result.isLeft shouldBe true
  }

  it should "normalize to lowercase" in {
    ProviderName("OpenAI").normalized shouldBe "openai"
    ProviderName("ANTHROPIC").normalized shouldBe "anthropic"
  }

  it should "have common constants" in {
    ProviderName.OPENAI.value shouldBe "openai"
    ProviderName.ANTHROPIC.value shouldBe "anthropic"
    ProviderName.AZURE.value shouldBe "azure"
    ProviderName.GOOGLE.value shouldBe "google"
    ProviderName.COHERE.value shouldBe "cohere"
  }

  // ==========================================================================
  // ApiKey Tests
  // ==========================================================================

  "ApiKey" should "hide value in toString" in {
    val key = new ApiKey("sk-1234567890abcdef")
    key.toString shouldBe "ApiKey(***)"
  }

  it should "reveal value when requested" in {
    val key = new ApiKey("sk-1234567890abcdef")
    key.reveal shouldBe "sk-1234567890abcdef"
  }

  it should "mask value showing only first 4 characters" in {
    val key = new ApiKey("sk-1234567890abcdef")
    key.masked shouldBe "sk-1***************"
  }

  it should "validate minimum length" in {
    val result = ApiKey("short")
    result.isLeft shouldBe true

    val validResult = ApiKey("12345678")
    validResult.isRight shouldBe true
  }

  // ==========================================================================
  // ConversationId Tests
  // ==========================================================================

  "ConversationId" should "generate unique IDs" in {
    val id1 = ConversationId.generate()
    val id2 = ConversationId.generate()

    id1.value should not be id2.value
  }

  it should "create valid IDs" in {
    val result = ConversationId.create("conv-123")
    result.map(_.value).getOrElse(fail("Expected success")) shouldBe "conv-123"
  }

  it should "reject empty IDs" in {
    val result = ConversationId.create("   ")
    result.isLeft shouldBe true
    result.left.getOrElse(fail("Expected failure")) shouldBe a[ValidationError]
  }

  it should "trim whitespace" in {
    val result = ConversationId.create("  conv-123  ")
    result.map(_.value).getOrElse(fail("Expected success")) shouldBe "conv-123"
  }

  // ==========================================================================
  // CompletionId Tests
  // ==========================================================================

  "CompletionId" should "generate unique IDs" in {
    val id1 = CompletionId.generate()
    val id2 = CompletionId.generate()

    id1.value should not be id2.value
  }

  it should "create valid IDs" in {
    val result = CompletionId.create("cmpl-123")
    result.isRight shouldBe true
  }

  it should "reject empty IDs" in {
    val result = CompletionId.create("")
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // ToolName Tests
  // ==========================================================================

  "ToolName" should "create valid tool names" in {
    val result = ToolName.create("get_weather")
    result.map(_.value).getOrElse(fail("Expected success")) shouldBe "get_weather"
  }

  it should "reject invalid tool names with special characters" in {
    val result = ToolName.create("get weather!")
    result.isLeft shouldBe true
  }

  it should "validate with isValid method" in {
    ToolName("get_weather").isValid shouldBe true
    ToolName("search-api").isValid shouldBe true
    ToolName("tool123").isValid shouldBe true
    ToolName("invalid tool").isValid shouldBe false
  }

  // ==========================================================================
  // ToolCallId Tests
  // ==========================================================================

  "ToolCallId" should "generate unique IDs" in {
    val id1 = ToolCallId.generate()
    val id2 = ToolCallId.generate()

    id1.value should not be id2.value
  }

  it should "create valid IDs" in {
    val result = ToolCallId.create("call_123")
    result.isRight shouldBe true
  }

  it should "reject empty IDs" in {
    val result = ToolCallId.create("")
    result.isLeft shouldBe true
  }

  // ==========================================================================
  // Url Tests
  // ==========================================================================

  "Url" should "create valid URLs" in {
    val result = Url.create("https://api.openai.com/v1/chat")
    result.isRight shouldBe true
  }

  it should "reject invalid URLs" in {
    val result = Url.create("not a url")
    result.isLeft shouldBe true
  }

  it should "validate with isValid method" in {
    Url("https://example.com").isValid shouldBe true
    Url("http://localhost:8080").isValid shouldBe true
  }

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
  // PaginationInfo Tests
  // ==========================================================================

  "PaginationInfo" should "store pagination data" in {
    val info = PaginationInfo(
      page = 2,
      pageSize = 10,
      totalItems = 45,
      totalPages = 5,
      hasNext = true,
      hasPrevious = true
    )

    info.page shouldBe 2
    info.pageSize shouldBe 10
    info.totalItems shouldBe 45
    info.totalPages shouldBe 5
    info.hasNext shouldBe true
    info.hasPrevious shouldBe true
  }

  // ==========================================================================
  // CompressionTarget Tests
  // ==========================================================================

  "CompressionTarget" should "create valid targets" in {
    val result = CompressionTarget.create(0.5)
    result.map(_.value).getOrElse(fail("Expected success")) shouldBe 0.5
  }

  it should "reject invalid ratios" in {
    CompressionTarget.create(0.0).isLeft shouldBe true
    CompressionTarget.create(-0.5).isLeft shouldBe true
    CompressionTarget.create(1.5).isLeft shouldBe true
  }

  it should "validate with isValid" in {
    CompressionTarget(0.5).isValid shouldBe true
    CompressionTarget(0.0).isValid shouldBe false
    CompressionTarget(1.5).isValid shouldBe false
  }

  it should "have preset constants" in {
    CompressionTarget.Minimal.value shouldBe 0.95
    CompressionTarget.Light.value shouldBe 0.80
    CompressionTarget.Medium.value shouldBe 0.60
    CompressionTarget.Heavy.value shouldBe 0.40
    CompressionTarget.Maximum.value shouldBe 0.20
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
  // TokenEstimate Tests
  // ==========================================================================

  "TokenEstimate" should "store token count" in {
    val estimate = TokenEstimate(100)
    estimate.value shouldBe 100
    estimate.tokens shouldBe 100
  }

  it should "create with accuracy" in {
    val (estimate, accuracy) = TokenEstimate.withAccuracy(100, EstimationAccuracy.High)
    estimate.value shouldBe 100
    accuracy shouldBe EstimationAccuracy.High
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
  // ContextSummary Tests
  // ==========================================================================

  "ContextSummary" should "estimate token length" in {
    val summary = ContextSummary("This is a test summary with several words")
    summary.tokenLength should be > 0
  }

  // ==========================================================================
  // SemanticBlockId Tests
  // ==========================================================================

  "SemanticBlockId" should "generate short IDs" in {
    val id = SemanticBlockId.generate()
    id.value.length shouldBe 8
  }

  // ==========================================================================
  // AgentId Tests
  // ==========================================================================

  "AgentId" should "generate unique IDs" in {
    val id1 = AgentId.generate()
    val id2 = AgentId.generate()

    id1.value should not be id2.value
  }

  // ==========================================================================
  // PlanId Tests
  // ==========================================================================

  "PlanId" should "generate unique IDs" in {
    val id1 = PlanId.generate()
    val id2 = PlanId.generate()

    id1.value should not be id2.value
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

  // ==========================================================================
  // Security Token Tests
  // ==========================================================================

  "JwtToken" should "hide value in toString" in {
    val token = new JwtToken("eyJhbGciOiJIUzI1NiIs...")
    token.toString shouldBe "JwtToken(***)"
  }

  it should "reveal value when requested" in {
    val token = new JwtToken("eyJhbGciOiJIUzI1NiIs...")
    token.reveal shouldBe "eyJhbGciOiJIUzI1NiIs..."
  }

  "OAuthToken" should "hide value in toString" in {
    val token = new OAuthToken("oauth-token-123")
    token.toString shouldBe "OAuthToken(***)"
  }

  it should "reveal value when requested" in {
    val token = new OAuthToken("oauth-token-123")
    token.reveal shouldBe "oauth-token-123"
  }
}
