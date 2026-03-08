package org.llm4s.reliability

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.MetricsCollector
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReliableProvidersSpec extends AnyFlatSpec with Matchers {

  class MockLLMClient extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(
        Completion(
          id = "test",
          created = 0L,
          content = "response",
          model = "test",
          message = AssistantMessage("response")
        )
      )

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private val mockClient = new MockLLMClient

  // ==========================================================================
  // ReliableProviders.wrap
  // ==========================================================================

  "ReliableProviders.wrap" should "wrap client with default config" in {
    val wrapped = ReliableProviders.wrap(mockClient, "test-provider")
    wrapped shouldBe a[ReliableClient]
    val result = wrapped.complete(Conversation(List(UserMessage("hello"))))
    result.isRight shouldBe true
  }

  it should "wrap client with custom config" in {
    val wrapped = ReliableProviders.wrap(
      mockClient,
      "test-provider",
      ReliabilityConfig.aggressive
    )
    wrapped shouldBe a[ReliableClient]
  }

  it should "wrap client with metrics" in {
    val wrapped = ReliableProviders.wrap(
      mockClient,
      "test-provider",
      metrics = Some(MetricsCollector.noop)
    )
    wrapped shouldBe a[ReliableClient]
  }

  it should "delegate getContextWindow to underlying" in {
    val wrapped = ReliableProviders.wrap(mockClient, "test-provider")
    wrapped.getContextWindow() shouldBe 4096
  }

  it should "delegate getReserveCompletion to underlying" in {
    val wrapped = ReliableProviders.wrap(mockClient, "test-provider")
    wrapped.getReserveCompletion() shouldBe 512
  }

  // ==========================================================================
  // ReliabilitySyntax
  // ==========================================================================

  "ReliabilitySyntax" should "add withReliability() to LLMClient" in {
    import ReliabilitySyntax._
    val wrapped = mockClient.withReliability()
    wrapped shouldBe a[ReliableClient]
  }

  it should "add withReliability(providerName) to LLMClient" in {
    import ReliabilitySyntax._
    val wrapped = mockClient.withReliability("my-provider")
    wrapped shouldBe a[ReliableClient]
  }

  it should "add withReliability(providerName, config) to LLMClient" in {
    import ReliabilitySyntax._
    val wrapped = mockClient.withReliability("my-provider", ReliabilityConfig.conservative)
    wrapped shouldBe a[ReliableClient]
  }

  it should "add withReliability(providerName, config, metrics) to LLMClient" in {
    import ReliabilitySyntax._
    val wrapped = mockClient.withReliability("my-provider", ReliabilityConfig.default, MetricsCollector.noop)
    wrapped shouldBe a[ReliableClient]
  }

  it should "produce a working client" in {
    import ReliabilitySyntax._
    val wrapped = mockClient.withReliability("test")
    val result  = wrapped.complete(Conversation(List(UserMessage("test"))))
    result.isRight shouldBe true
    result.toOption.get.content shouldBe "response"
  }

  // ==========================================================================
  // ReliableClient companion object
  // ==========================================================================

  "ReliableClient.apply(client)" should "create with default config" in {
    val reliable = ReliableClient(mockClient)
    reliable shouldBe a[ReliableClient]
    reliable.currentCircuitState shouldBe CircuitState.Closed
  }

  "ReliableClient.apply(client, config)" should "create with custom config" in {
    val reliable = ReliableClient(mockClient, ReliabilityConfig.aggressive)
    reliable shouldBe a[ReliableClient]
  }

  "ReliableClient.apply(client, config, collector)" should "create with metrics" in {
    val reliable = ReliableClient(mockClient, ReliabilityConfig.default, MetricsCollector.noop)
    reliable shouldBe a[ReliableClient]
  }

  "ReliableClient.withProviderName" should "create with explicit provider name" in {
    val reliable = ReliableClient.withProviderName(mockClient, "custom-provider")
    reliable shouldBe a[ReliableClient]
  }

  it should "accept custom config and collector" in {
    val reliable = ReliableClient.withProviderName(
      mockClient,
      "custom-provider",
      ReliabilityConfig.conservative,
      Some(MetricsCollector.noop)
    )
    reliable shouldBe a[ReliableClient]
  }
}
