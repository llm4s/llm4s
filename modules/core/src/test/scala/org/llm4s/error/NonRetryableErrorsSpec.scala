package org.llm4s.error

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for non-recoverable error types: AuthenticationError, ValidationError,
 * ConfigurationError, ProcessingError, InvalidInputError, NotFoundError,
 * ContextError, TokenizerError, UnknownError, SimpleError
 */
class NonRetryableErrorsSpec extends AnyFlatSpec with Matchers {

  // ============ AuthenticationError ============

  "AuthenticationError" should "create with provider and details" in {
    val error = AuthenticationError(ProviderId("openai"), "Invalid API key")

    error.provider shouldBe ProviderId("openai")
    error.code shouldBe None
    error.message should include("Authentication failed for openai")
    error.message should include("Invalid API key")
  }

  it should "create with error code" in {
    val error = AuthenticationError(ProviderId("anthropic"), "Unauthorized", "401")

    error.code shouldBe Some("401")
    error.context should contain("code" -> "401")
  }

  it should "not be retryable" in {
    val error = AuthenticationError(ProviderId("provider"), "details")

    error.isRetryable shouldBe false
    error shouldBe a[LLMError]
    error.isRetryable shouldBe false
  }

  it should "support pattern matching" in {
    val error = AuthenticationError(ProviderId("openai"), "invalid key", "401")

    error match {
      case AuthenticationError(msg, provider, code) =>
        msg should include("Authentication failed")
        provider shouldBe ProviderId("openai")
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

  it should "not be retryable" in {
    val error = ValidationError("field", "reason")

    error.isRetryable shouldBe false
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

  it should "not be retryable" in {
    val error = ConfigurationError("config error")

    error.isRetryable shouldBe false
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

  it should "not be retryable" in {
    val error = ProcessingError("op", "msg")

    error.isRetryable shouldBe false
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

  it should "not be retryable" in {
    val error = InvalidInputError("field", "value", "reason")

    error.isRetryable shouldBe false
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

  it should "not be retryable" in {
    val error = NotFoundError("not found", "key")

    error.isRetryable shouldBe false
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

  "ContextError" should "create artifact store failed error" in {
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

  it should "not be retryable" in {
    val error = ContextError.artifactStoreFailed("store", "key-1", "disk full")

    error.isRetryable shouldBe false
  }

  it should "support pattern matching" in {
    val error = ContextError.artifactStoreFailed("store", "key-1", "disk full")

    error match {
      case ContextError(msg, contextType, details) =>
        msg should include("Artifact store store failed")
        contextType shouldBe "artifactStore"
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

  it should "not be retryable" in {
    val error = TokenizerError.notAvailable("tokenizer")

    error.isRetryable shouldBe false
  }

  it should "include tokenizer ID in context" in {
    val error = TokenizerError.notAvailable("test-tokenizer")

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

  it should "not be retryable" in {
    val error = UnknownError("unknown", new Exception())

    error.isRetryable shouldBe false
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

  it should "not be retryable" in {
    val error = SimpleError("error")

    error.isRetryable shouldBe false
  }

  it should "have empty context" in {
    val error = SimpleError("error")

    error.context shouldBe empty
  }

  it should "support pattern matching" in {
    val error = SimpleError("test message")

    error match {
      case SimpleError(msg) =>
        msg shouldBe "test message"
      case _ => fail("Pattern matching failed")
    }
  }

  // ============ Not retryable ============

  "An error the caller must fix" should "not be retryable" in {
    val errors: Seq[LLMError] = Seq(
      AuthenticationError(ProviderId("p"), "m"),
      ValidationError("f", "r"),
      ConfigurationError("m"),
      ProcessingError("o", "m"),
      InvalidInputError("f", "v", "r"),
      NotFoundError("m", "k"),
      ContextError.artifactStoreFailed("store", "key-1", "disk full"),
      TokenizerError.notAvailable("t"),
      UnknownError("m", new Exception()),
      SimpleError("m")
    )

    errors.foreach(error => error.isRetryable shouldBe false)
  }

  // ============ LLMError Companion Object ============

  "filterNot(_.isRetryable)" should "keep only the errors that are not retryable" in {
    val errors: List[LLMError] = List(
      AuthenticationError(ProviderId("p"), "m"),
      RateLimitError(ProviderId("p")),
      ValidationError("f", "r")
    )

    val nonRecoverable = errors.filterNot(_.isRetryable)

    nonRecoverable should have size 2
    nonRecoverable.foreach(_.isRetryable shouldBe false)
  }

  // ============ Smart Constructors ============

}
