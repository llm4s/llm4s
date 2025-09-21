package org.llm4s.error

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Comprehensive tests to LLMError
 */
class LLMErrorSpec extends AnyWordSpec with Matchers {

  "LLMError" should {

    "use smart constructors and unapply extractors correctly" in {
      // Test both construction and pattern matching work
      val error = AuthenticationError("openai", "invalid key", "401")

      // Construction works
      error.provider shouldBe "openai"
      error.code shouldBe Some("401")

      // Pattern matching works with unapply extractor
      error match {
        case AuthenticationError(message, provider, code) =>
          message should include("Authentication failed")
          provider shouldBe "openai"
          code shouldBe Some("401")
        case _ => fail("Pattern matching failed")
      }
    }

    "support pattern matching for all error types" in {
      val rateError       = RateLimitError("anthropic", 30L)
      val validationError = ValidationError("email", List("too short", "invalid"))
      val networkError    = NetworkError("timeout", None, "https://api.example.com")

      // Test pattern matching works for all types
      rateError match {
        case RateLimitError(_, retryAfter, provider) =>
          provider shouldBe "anthropic"
          retryAfter shouldBe Some(30L)
        case _ => fail("RateLimitError pattern matching failed")
      }

      validationError match {
        case ValidationError(_, field, violations) =>
          field shouldBe "email"
          violations should contain("too short")
        case _ => fail("ValidationError pattern matching failed")
      }

      networkError match {
        case NetworkError(_, cause, endpoint) =>
          endpoint shouldBe "https://api.example.com"
          cause shouldBe None
        case _ => fail("NetworkError pattern matching failed")
      }
    }

    "use smart constructors for RateLimitError with cleaner call sites" in {
      // Test simplified construction
      val error1 = RateLimitError("openai")
      error1.provider shouldBe "openai"
      error1.retryAfter shouldBe None

      val error2 = RateLimitError("anthropic", 60L)
      error2.retryAfter shouldBe Some(60L)
      error2.context should contain("retryAfter" -> "60")
    }

    "properly categorize errors with trait-based inheritance" in {
      // Test compile-time type safety
      val authError       = AuthenticationError("provider", "test")
      val rateError       = RateLimitError("provider")
      val validationError = ValidationError("field", "invalid")

      // Type-level assertions
      authError shouldBe a[NonRecoverableError]
      rateError shouldBe a[RecoverableError]
      validationError shouldBe a[NonRecoverableError]

      // Runtime checks for backward compatibility
      LLMError.isRecoverable(authError) shouldBe false
      LLMError.isRecoverable(rateError) shouldBe true
      LLMError.isRecoverable(validationError) shouldBe false
    }

    "use improved context map construction patterns" in {
      // Test improved patterns
      val errorWithViolations = ValidationError("email", List("too short", "invalid format"))
      (errorWithViolations.context should contain).key("field")
      (errorWithViolations.context should contain).key("violations")
      errorWithViolations.context("violations") should include("too short")

      val errorSingleViolation = ValidationError("password", "too weak")
      (errorSingleViolation.context should contain).key("field")
      (errorSingleViolation.context should contain).key("violations")

      val configError = ConfigurationError("Missing keys", List("api_key", "model"))
      (configError.context should contain).key("missingKeys")

      val configErrorNoKeys = ConfigurationError("Generic config error", List.empty)
      configErrorNoKeys.context should not contain key
    }

    "provide type-safe error filtering" in {
      // Test new type-safe methods
      val errors: List[LLMError] = List(
        AuthenticationError("provider1", "test"),
        RateLimitError("provider2"),
        ValidationError("field", "invalid"),
        NetworkError("timeout", None, "https://api.example.com")
      )

      val recoverable    = LLMError.recoverableErrors(errors)
      val nonRecoverable = LLMError.nonRecoverableErrors(errors)

      recoverable should have size 2    // RateLimitError, NetworkError
      nonRecoverable should have size 2 // AuthenticationError, ValidationError
    }

    "integrate with cats Show" in {
      // Test cats integration
      val error = AuthenticationError("openai", "invalid key")
      val shown = error.show

      shown should include("AuthenticationError")
      shown should include("Authentication failed for openai")
      shown should include("invalid key[provider=openai]")
    }

    "maintain backward compatibility" in {
      // Ensure old patterns still work
      val error = ValidationError("field", "reason")

      // Old isRecoverable method should still work (with deprecation warning)
      error.isRecoverable shouldBe false

      // Formatted method should work
      error.formatted should include("ValidationError")
      error.formatted should include("Invalid field: reason[field=field,violations=reason]")
    }

    "create AuthenticationError with smart constructors" in {
      // Test the amended smart constructors
      val error1 = AuthenticationError("openai", "invalid key")
      error1.provider shouldBe "openai"
      error1.message should include("Authentication failed")

      val error2 = AuthenticationError("openai", "invalid key", "401")
      error2.code shouldBe Some("401")
    }

    "properly categorize errors as recoverable/non-recoverable" in {
      val authError = AuthenticationError("openai", "test")
      val rateError = RateLimitError("openai")

      // Type-level checks
      authError shouldBe a[NonRecoverableError]
      rateError shouldBe a[RecoverableError]

      // Runtime checks (for backward compatibility)
      LLMError.isRecoverable(authError) shouldBe false
      LLMError.isRecoverable(rateError) shouldBe true
    }

    "construct context maps correctly per PR review" in {
      // Test the context map construction
      val errorWithViolations = ValidationError("field", List("error1", "error2"))
      (errorWithViolations.context should contain).key("violations")

      val errorWithoutViolations = ValidationError("field", "single error")
      (errorWithoutViolations.context should contain).key("field")
    }

    "use smart constructors for rate limiting" in {
      // Test smart constructors
      val error1 = RateLimitError("openai")
      error1.retryAfter shouldBe None

      val error2 = RateLimitError("openai", 60L)
      error2.retryAfter shouldBe Some(60L)
      error2.context should contain("retryAfter" -> "60")
    }
  }

  "Type-safe error filtering" should {
    "separate recoverable from non-recoverable errors" in {
      val errors: List[LLMError] = List(
        AuthenticationError("provider", "test"),
        RateLimitError("provider"),
        ValidationError("field", "invalid")
      )

      val recoverable    = LLMError.recoverableErrors(errors)
      val nonRecoverable = LLMError.nonRecoverableErrors(errors)

      recoverable should have size 1
      nonRecoverable should have size 2
    }
  }
}
