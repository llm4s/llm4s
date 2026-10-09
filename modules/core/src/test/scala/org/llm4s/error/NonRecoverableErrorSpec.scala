package org.llm4s.error

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for non-recoverable error types: AuthenticationError, ValidationError,
 * ConfigurationError, ProcessingError, InvalidInputError, NotFoundError,
 * ContextError, TokenizerError, UnknownError, SimpleError
 */
class NonRecoverableErrorSpec extends AnyFlatSpec with Matchers {

  // ============ AuthenticationError ============

  "AuthenticationError" should "create with provider and details" in {
    val error = AuthenticationError("openai", "Invalid API key")

    error.provider shouldBe "openai"
    error.code shouldBe None
    error.message should include("Authentication failed for openai")
    error.message should include("Invalid API key")
  }

  it should "create with error code" in {
    val error = AuthenticationError("anthropic", "Unauthorized", "401")

    error.code shouldBe Some("401")
    error.context should contain("code" -> "401")
  }

  it should "be a NonRecoverableError" in {
    val error = AuthenticationError("provider", "details")

    error shouldBe a[NonRecoverableError]
    error shouldBe a[LLMError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "create unauthorized error" in {
    val error = AuthenticationError.unauthorized("openai")

    error.provider shouldBe "openai"
    error.code shouldBe Some("401")
    error.message should include("Unauthorized access")
  }

  it should "create invalid API key error" in {
    val error = AuthenticationError.invalidApiKey("anthropic")

    error.provider shouldBe "anthropic"
    error.code shouldBe Some("INVALID_KEY")
    error.message should include("Invalid API key")
  }

  it should "support pattern matching" in {
    val error = AuthenticationError("openai", "invalid key", "401")

    error match {
      case AuthenticationError(msg, provider, code) =>
        msg should include("Authentication failed")
        provider shouldBe "openai"
        code shouldBe Some("401")
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ ValidationError ============

  "ValidationError" should "create with single reason" in {
    val error = ValidationError("email", "invalid format")

    error.field shouldBe "email"
    error.violations shouldBe List("invalid format")
    error.message should include("Invalid email")
  }

  it should "create with multiple violations" in {
    val error = ValidationError("password", List("too short", "no uppercase", "no numbers"))

    error.field shouldBe "password"
    error.violations should have size 3
    error.violations should contain("too short")
    error.violations should contain("no uppercase")
    error.violations should contain("no numbers")
  }

  it should "be a NonRecoverableError" in {
    val error = ValidationError("field", "reason")

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "create required field error" in {
    val error = ValidationError.required("username")

    error.field shouldBe "username"
    error.violations should contain("required")
    error.message should include("required")
  }

  it should "create invalid field error" in {
    val error = ValidationError.invalid("age", "must be positive")

    error.field shouldBe "age"
    error.violations should contain("must be positive")
  }

  it should "support adding violations" in {
    val error = ValidationError("field", "first")
      .withViolation("second")
      .withViolations(List("third", "fourth"))

    error.violations shouldBe List("first", "second", "third", "fourth")
  }

  it should "include violations in context" in {
    val error = ValidationError("field", List("error1", "error2"))

    error.context should contain("field" -> "field")
    (error.context should contain).key("violations")
    error.context("violations") should include("error1")
    error.context("violations") should include("error2")
  }

  it should "support pattern matching" in {
    val error = ValidationError("email", List("invalid", "too long"))

    error match {
      case ValidationError(msg, field, violations) =>
        msg should include("Invalid email")
        field shouldBe "email"
        violations should have size 2
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ ConfigurationError ============

  "ConfigurationError" should "create with message" in {
    val error = ConfigurationError("Invalid configuration")

    error.message shouldBe "Invalid configuration"
    error.missingKeys shouldBe empty
  }

  it should "create with missing keys" in {
    val error = ConfigurationError("Missing required keys", List("API_KEY", "MODEL_NAME"))

    error.missingKeys should contain("API_KEY")
    error.missingKeys should contain("MODEL_NAME")
    (error.context should contain).key("missingKeys")
    error.context("missingKeys") should include("API_KEY")
  }

  it should "be a NonRecoverableError" in {
    val error = ConfigurationError("config error")

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "not include missingKeys in context when empty" in {
    val error = ConfigurationError("error", List.empty)

    error.context should not contain key("missingKeys")
  }

  it should "support pattern matching" in {
    val error = ConfigurationError("error", List("KEY1", "KEY2"))

    error match {
      case ConfigurationError(msg, missingKeys) =>
        msg shouldBe "error"
        missingKeys should have size 2
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ ProcessingError ============

  "ProcessingError" should "create with operation and message" in {
    val error = ProcessingError("image-resize", "Failed to resize image")

    error.operation shouldBe "image-resize"
    error.message should include("Processing failed during image-resize")
    error.cause shouldBe None
  }

  it should "create with cause" in {
    val cause = new IllegalArgumentException("Invalid dimensions")
    val error = ProcessingError("resize", "failed", Some(cause))

    error.cause shouldBe Some(cause)
    error.context should contain("cause" -> "Invalid dimensions")
    error.formatted should include("cause=Invalid dimensions")
  }

  it should "name the cause's class in the context when the cause has no message" in {
    // Throwable#getMessage is null for an exception built without one (#1556)
    val error = ProcessingError("op", "msg", Some(new RuntimeException()))

    error.context should contain("cause" -> "java.lang.RuntimeException")
    error.context.values.foreach(v => v should not be null)
    error.context.values should not contain "null"
    (error.formatted should not).include("cause=null")
    error.formatted should include("cause=java.lang.RuntimeException")
  }

  it should "name the cause's class in the context when the cause's message is empty" in {
    val error = ProcessingError("op", "msg", Some(new IllegalStateException("")))

    error.context should contain("cause" -> "java.lang.IllegalStateException")
  }

  it should "hold no cause entry in the context when there is no cause" in {
    val error = ProcessingError("op", "msg", None)

    error.context shouldBe Map("operation" -> "op")
    (error.formatted should not).include("cause=")
  }

  it should "be a NonRecoverableError" in {
    val error = ProcessingError("op", "msg")

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "create audio-specific errors" in {
    val resample   = ProcessingError.audioResample("Invalid sample rate")
    val conversion = ProcessingError.audioConversion("Unsupported format")
    val trimming   = ProcessingError.audioTrimming("Invalid time range")
    val validation = ProcessingError.audioValidation("File too large")

    resample.operation shouldBe "audio-resample"
    conversion.operation shouldBe "audio-conversion"
    trimming.operation shouldBe "audio-trimming"
    validation.operation shouldBe "audio-validation"
  }

  it should "support pattern matching" in {
    val error = ProcessingError("op", "msg", None)

    error match {
      case ProcessingError(msg, operation, cause) =>
        msg should include("Processing failed")
        operation shouldBe "op"
        cause shouldBe None
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ InvalidInputError ============

  "InvalidInputError" should "create with field, value, and reason" in {
    val error = InvalidInputError("width", "abc", "must be a number")

    error.field shouldBe "width"
    error.value shouldBe "abc"
    error.reason shouldBe "must be a number"
    error.message should include("Invalid input for width")
  }

  it should "be a NonRecoverableError" in {
    val error = InvalidInputError("field", "value", "reason")

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "include all fields in context" in {
    val error = InvalidInputError("temperature", "2.5", "must be between 0 and 1")

    error.context should contain("field" -> "temperature")
    error.context should contain("value" -> "2.5")
    error.context should contain("reason" -> "must be between 0 and 1")
  }

  it should "support pattern matching" in {
    val error = InvalidInputError("field", "value", "reason")

    error match {
      case InvalidInputError(msg, field, value, reason) =>
        msg should include("Invalid input")
        field shouldBe "field"
        value shouldBe "value"
        reason shouldBe "reason"
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ NotFoundError ============

  "NotFoundError" should "create with message and key" in {
    val error = NotFoundError("API key not found", "OPENAI_API_KEY")

    error.message shouldBe "API key not found"
    error.key shouldBe "OPENAI_API_KEY"
  }

  it should "be a NonRecoverableError" in {
    val error = NotFoundError("not found", "key")

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "include key in context" in {
    val error = NotFoundError("Model not found", "MODEL_NAME")

    error.context should contain("key" -> "MODEL_NAME")
  }

  it should "support pattern matching" in {
    val error = NotFoundError("not found", "key")

    error match {
      case NotFoundError(message, key) =>
        message shouldBe "not found"
        key shouldBe "key"
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ ContextError ============

  "ContextError" should "create token budget exceeded error" in {
    val error = ContextError.tokenBudgetExceeded(5000, 4096)

    error.contextType shouldBe "tokenBudget"
    error.message should include("5000 tokens > 4096 budget")
    error.details should contain("currentTokens" -> "5000")
    error.details should contain("budget" -> "4096")
  }

  it should "create invalid trimming error" in {
    val error = ContextError.invalidTrimming("Cannot trim required messages")

    error.contextType shouldBe "trimming"
    error.message should include("Invalid trimming operation")
  }

  it should "create conversation too large error" in {
    val error = ContextError.conversationTooLarge(100, 10)

    error.contextType shouldBe "conversationSize"
    error.details should contain("messageCount" -> "100")
    error.details should contain("minRequired" -> "10")
  }

  it should "create empty result error" in {
    val error = ContextError.emptyResult("summarization")

    error.contextType shouldBe "emptyResult"
    error.message should include("empty conversation")
  }

  it should "create semantic blocking failed error" in {
    val error = ContextError.semanticBlockingFailed("Too few messages")

    error.contextType shouldBe "semanticBlocking"
  }

  it should "create summarization failed error" in {
    val error = ContextError.summarizationFailed(5, "LLM error")

    error.contextType shouldBe "summarization"
    error.details should contain("blockCount" -> "5")
  }

  it should "create compression failed error" in {
    val error = ContextError.compressionFailed("deterministic", "No tool outputs")

    error.contextType shouldBe "compression"
    error.details should contain("strategy" -> "deterministic")
  }

  it should "create pipeline failed error" in {
    val error = ContextError.contextPipelineFailed("step2", "Timeout")

    error.contextType shouldBe "pipeline"
    error.details should contain("failedStep" -> "step2")
  }

  it should "create tool compression failed error" in {
    val error = ContextError.toolCompressionFailed("tool-123", "Invalid JSON")

    error.contextType shouldBe "toolCompression"
    error.details should contain("toolCallId" -> "tool-123")
  }

  it should "create externalization failed error" in {
    val error = ContextError.externalizationFailed("image", 1024000L, "Storage full")

    error.contextType shouldBe "externalization"
    error.details should contain("contentType" -> "image")
    error.details should contain("size" -> "1024000")
  }

  it should "create artifact store failed error" in {
    val error = ContextError.artifactStoreFailed("write", "key-123", "Permission denied")

    error.contextType shouldBe "artifactStore"
    error.details should contain("operation" -> "write")
    error.details should contain("key" -> "key-123")
  }

  it should "create schema compression failed error" in {
    val error = ContextError.schemaCompressionFailed("JSON", "Invalid schema")

    error.contextType shouldBe "schemaCompression"
    error.details should contain("schema" -> "JSON")
  }

  it should "create LLM compression failed error" in {
    val error = ContextError.llmCompressionFailed("gpt-4", "Rate limited")

    error.contextType shouldBe "llmCompression"
    error.details should contain("modelName" -> "gpt-4")
  }

  it should "create token estimation failed error" in {
    val error = ContextError.tokenEstimationFailed("long content here", "Tokenizer unavailable")

    error.contextType shouldBe "tokenEstimation"
    (error.details should contain).key("contentLength")
  }

  it should "be a NonRecoverableError" in {
    val error = ContextError.tokenBudgetExceeded(100, 50)

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "support pattern matching" in {
    val error = ContextError.tokenBudgetExceeded(100, 50)

    error match {
      case ContextError(msg, contextType, details) =>
        msg should include("Token budget exceeded")
        contextType shouldBe "tokenBudget"
        details should not be empty
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ TokenizerError ============

  "TokenizerError" should "create not available error" in {
    val error = TokenizerError.notAvailable("gpt-4-tokenizer")

    error.tokenizerId shouldBe "gpt-4-tokenizer"
    error.message should include("Tokenizer not available")
  }

  it should "create not found error" in {
    val error = TokenizerError.notFound("unknown-tokenizer")

    error.tokenizerId shouldBe "unknown-tokenizer"
    error.message should include("Tokenizer not found")
  }

  it should "be a NonRecoverableError" in {
    val error = TokenizerError.notAvailable("tokenizer")

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "include tokenizer ID in context" in {
    val error = TokenizerError.notFound("test-tokenizer")

    error.context should contain("tokenizerId" -> "test-tokenizer")
  }

  it should "support pattern matching" in {
    val error = TokenizerError.notAvailable("tokenizer-123")

    error match {
      case TokenizerError(msg, tokenizerId) =>
        msg should include("not available")
        tokenizerId shouldBe "tokenizer-123"
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ UnknownError ============

  "UnknownError" should "create with message and cause" in {
    val cause = new RuntimeException("Unexpected failure")
    val error = UnknownError("Something went wrong", cause)

    error.message shouldBe "Something went wrong"
    error.cause shouldBe cause
  }

  it should "be a NonRecoverableError" in {
    val error = UnknownError("unknown", new Exception())

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "include exception details in context" in {
    val cause = new IllegalStateException("Bad state")
    val error = UnknownError("error", cause)

    error.context should contain("exceptionType" -> "IllegalStateException")
    (error.context should contain).key("stackTrace")
    error.context("stackTrace") should not be empty
  }

  it should "support pattern matching" in {
    val cause = new Exception("test")
    val error = UnknownError("message", cause)

    error match {
      case UnknownError(msg, c) =>
        msg shouldBe "message"
        c shouldBe cause
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ SimpleError ============

  "SimpleError" should "create with just message" in {
    val error = SimpleError("Simple error message")

    error.message shouldBe "Simple error message"
  }

  it should "be a NonRecoverableError" in {
    val error = SimpleError("error")

    error shouldBe a[NonRecoverableError]
    LLMError.isRecoverable(error) shouldBe false
  }

  it should "have empty context" in {
    val error = SimpleError("error")

    error.context shouldBe empty
  }

  it should "support pattern matching" in {
    val error = SimpleError("test message")

    error match {
      case SimpleError(msg, context) =>
        msg shouldBe "test message"
        context shouldBe empty
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ NonRecoverableError Trait ============

  "NonRecoverableError" should "always report isRecoverable as false" in {
    val errors: Seq[NonRecoverableError] = Seq(
      AuthenticationError("p", "m"),
      ValidationError("f", "r"),
      ConfigurationError("m"),
      ProcessingError("o", "m"),
      InvalidInputError("f", "v", "r"),
      NotFoundError("m", "k"),
      ContextError.tokenBudgetExceeded(100, 50),
      TokenizerError.notFound("t"),
      UnknownError("m", new Exception()),
      SimpleError("m")
    )

    errors.foreach(error => LLMError.isRecoverable(error) shouldBe false)
  }

  // ============ LLMError Companion Object ============

  "LLMError.nonRecoverableErrors" should "filter only non-recoverable errors" in {
    val errors: List[LLMError] = List(
      APIError("p", "m"),
      AuthenticationError("p", "m"),
      RateLimitError("p"),
      ValidationError("f", "r")
    )

    val nonRecoverable = LLMError.nonRecoverableErrors(errors)

    nonRecoverable should have size 2
    nonRecoverable.foreach(_ shouldBe a[NonRecoverableError])
  }

  // ============ Smart Constructors ============

  "LLMError smart constructors" should "create processing error" in {
    val error = LLMError.processingFailed("resize", "Failed", None)

    error shouldBe a[ProcessingError]
    error.operation shouldBe "resize"
  }

  it should "create invalid image input error" in {
    val error = LLMError.invalidImageInput("width", "abc", "must be numeric")

    error shouldBe a[InvalidInputError]
    error.field shouldBe "width"
    error.value shouldBe "abc"
    error.reason shouldBe "must be numeric"
  }

  it should "create API call failed error" in {
    val error = LLMError.apiCallFailed("openai", "timeout", Some(504), None)

    error shouldBe a[APIError]
    error.provider shouldBe "openai"
    error.statusCode shouldBe Some(504)
  }
}
