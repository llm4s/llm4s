package org.llm4s.error

import org.llm4s.{ error, llmconnect }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Compatibility tests ensuring error bridge works correctly
 */

class ErrorBridgeSpec extends AnyFlatSpec with Matchers {

  "ErrorBridge" should "convert legacy AuthenticationError to core error" in {
    val legacyError = llmconnect.model.AuthenticationError("auth failed")
    val coreError   = ErrorBridge.toCore(legacyError)

    coreError shouldBe a[error.LLMError.AuthenticationError]
    coreError.message shouldBe "auth failed"
    coreError.context should contain("provider" -> "unknown")
  }

  it should "convert legacy RateLimitError to core error" in {
    val legacyError = llmconnect.model.RateLimitError("rate limited")
    val coreError   = ErrorBridge.toCore(legacyError)

    coreError shouldBe a[error.LLMError.RateLimitError]
    coreError.message shouldBe "rate limited"
    coreError.isRecoverable shouldBe true
  }

  it should "convert core errors back to legacy errors" in {
    val coreError   = error.LLMError.AuthenticationError("auth failed", "openai")
    val legacyError = ErrorBridge.toLegacy(coreError)

    legacyError shouldBe a[llmconnect.model.AuthenticationError]
    legacyError.message shouldBe "auth failed"
  }

  it should "preserve error semantics in round-trip conversion" in {
    val originalLegacy = llmconnect.model.ServiceError("service error", 500)
    val convertedCore  = ErrorBridge.toCore(originalLegacy)
    val convertedBack  = ErrorBridge.toLegacy(convertedCore)

    convertedBack shouldBe a[llmconnect.model.ServiceError]
    convertedBack.message shouldBe originalLegacy.message
  }
}
