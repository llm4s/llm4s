package org.llm4s.reliability

import org.llm4s.error.{ ConfigurationError, TimeoutError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.{ DeepSeekConfig, ProviderConfig }
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

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

  private given ModelRegistryService = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get

  private val deepSeekConfig =
    DeepSeekConfig("sk-test", "deepseek-chat", DeepSeekConfig.DEFAULT_BASE_URL, 128000, 8192)

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
  // ReliableProviders.wrap - rate limiting composes with retry
  // ==========================================================================

  class AlwaysTimingOutClient extends LLMClient {
    val callCount = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      callCount.incrementAndGet()
      Left(TimeoutError("slow", 1.second, "complete"))
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  "ReliableProviders.wrap" should "consult the rate limiter on every retry, not just the first attempt" in {
    // Burst of 1 with no refill: the first attempt drains the bucket, so every
    // retry after it must be rejected by the rate limiter before it ever reaches
    // the underlying client - proving the limiter sits inside the retry loop.
    val failingClient = new AlwaysTimingOutClient
    val config = ReliabilityConfig.default
      .withRateLimit(RateLimitConfig(enabled = true, requestsPerMinute = 0, burstCapacity = 1))
      .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))

    val wrapped = ReliableProviders.wrap(failingClient, "test-provider", config)
    val result  = wrapped.complete(Conversation(List(UserMessage("hello"))))

    result.isLeft shouldBe true
    failingClient.callCount.get() shouldBe 1
  }

  it should "reach the underlying client on every attempt when rate limiting is disabled" in {
    val failingClient = new AlwaysTimingOutClient
    val config = ReliabilityConfig.default
      .withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.millis))

    val wrapped = ReliableProviders.wrap(failingClient, "test-provider", config)
    val result  = wrapped.complete(Conversation(List(UserMessage("hello"))))

    result.isLeft shouldBe true
    failingClient.callCount.get() shouldBe 3
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

  // ==========================================================================
  // ReliableProviders.wrap(config) - the registry-routed replacement for the
  // seven per-provider factories removed in #1131.
  // ==========================================================================

  "ReliableProviders.wrap(config)" should "build and wrap the client the config names" in {
    ReliableProviders.wrap(deepSeekConfig) match {
      case Right(client) => client shouldBe a[ReliableClient]
      case Left(error)   => fail(s"Expected a reliable DeepSeek client, got: ${error.message}")
    }
  }

  it should "accept a reliability config and a metrics collector" in {
    val result = ReliableProviders.wrap(deepSeekConfig, ReliabilityConfig.aggressive, MetricsCollector.noop)
    result.map(_.getClass.getSimpleName) shouldBe Right("ReliableClient")
  }

  it should "report an unregistered provider rather than building anything" in {
    // A config from a provider module that is not on this classpath.
    val unregistered = new ProviderConfig {
      val providerId: ProviderId                   = ProviderId("moonbeam")
      val model: String                            = "v1"
      val contextWindow: Int                       = 4096
      val reserveCompletion: Int                   = 512
      def endpointUrl: Option[String]              = None
      def withModel(model: String): ProviderConfig = this
    }

    ReliableProviders.wrap(unregistered) match {
      case Left(error: ConfigurationError) => error.message should include("Provider 'moonbeam' is not registered")
      case other                           => fail(s"Expected an unregistered-provider error, got: $other")
    }
  }

  it should "use a registry passed explicitly" in {
    // An empty registry resolves nothing, which is how a caller proves the
    // registry is the only thing deciding what can be built.
    given ProviderRegistry = ProviderRegistry.of()

    ReliableProviders.wrap(deepSeekConfig).isLeft shouldBe true
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
