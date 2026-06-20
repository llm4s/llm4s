package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.XAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for XAIClient closed-state handling.
 *
 * Verifies that:
 * - Operations fail with [[ConfigurationError]] after [[XAIClient.close]] is called.
 * - `close()` is idempotent (safe to call multiple times).
 */
class XAIClientClosedStateTest extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def createTestConfig: XAIConfig = XAIConfig(
    apiKey = "xai-test-key-for-closed-state-testing",
    model = "grok-beta",
    baseUrl = "https://example.invalid",
    contextWindow = 131072,
    reserveCompletion = 4096,
  )

  private def conversation: Conversation = Conversation(Seq(UserMessage("Hello")))

  "XAIClient" should "return ConfigurationError when complete() is called after close()" in {
    val client = new XAIClient(createTestConfig)
    client.close()

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => {
        err shouldBe a[ConfigurationError]
        err.message should include("already closed")
        err.message should include("grok-beta")
      },
      _ => fail("Expected Left(ConfigurationError) after close()")
    )
  }

  it should "return ConfigurationError when streamComplete() is called after close()" in {
    val client = new XAIClient(createTestConfig)
    client.close()

    val result = client.streamComplete(conversation, CompletionOptions(), _ => ())
    result.fold(
      err => {
        err shouldBe a[ConfigurationError]
        err.message should include("already closed")
      },
      _ => fail("Expected Left(ConfigurationError) after close()")
    )
  }

  it should "allow close() to be called multiple times without throwing" in {
    val client = new XAIClient(createTestConfig)
    noException should be thrownBy {
      client.close()
      client.close()
      client.close()
    }
    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err shouldBe a[ConfigurationError],
      _ => fail("Expected Left(ConfigurationError)")
    )
  }

  it should "include model name in the closed error message" in {
    val cfg    = createTestConfig.copy(model = "grok-2-latest")
    val client = new XAIClient(cfg)
    client.close()

    val result = client.complete(conversation, CompletionOptions())
    result.fold(
      err => err.message should include("grok-2-latest"),
      _ => fail("Expected Left(ConfigurationError)")
    )
  }

  it should "return the configured context window before close" in {
    val client = new XAIClient(createTestConfig)
    client.getContextWindow() shouldBe 131072
  }

  it should "return the configured reserve completion before close" in {
    val client = new XAIClient(createTestConfig)
    client.getReserveCompletion() shouldBe 4096
  }
}
