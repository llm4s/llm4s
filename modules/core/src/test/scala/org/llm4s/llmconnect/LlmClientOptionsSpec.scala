package org.llm4s.llmconnect

import org.scalatest.EitherValues
import java.time.Instant

import org.llm4s.http.{ HttpNetworkSettings, Llm4sHttpClient }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.{ FixtureChatConfig, FixtureChatProvider }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class LlmClientOptionsSpec extends AnyFunSuite with Matchers with EitherValues {
  private val registryService        = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService = registryService

  test("LlmClientOptions.default keeps exchange logging disabled") {
    LlmClientOptions.default.exchangeLogging shouldBe ProviderExchangeLogging.Disabled
  }

  test("ProviderExchangeLogging.enabled stores the provided sink") {
    val sink    = ProviderExchangeSink.noop
    val enabled = ProviderExchangeLogging.enabled(sink)

    enabled shouldBe ProviderExchangeLogging.Enabled(sink)
  }

  test("LLMConnect.getClient accepts explicit options for config-driven construction") {
    // This used an OpenRouter OpenAIConfig until OpenRouter and OpenAIConfig left core (#1132).
    val cfg = FixtureChatConfig
      .fromValues(
        model = "fixture-model",
        apiKey = "sk-test",
        baseUrl = FixtureChatProvider.DefaultBaseUrl
      )
      .value
    val options = LlmClientOptions(
      exchangeLogging = ProviderExchangeLogging.enabled(ProviderExchangeSink.noop)
    )

    val res = LLMConnect.getClient(cfg, options)

    res.isRight shouldBe true
  }

  test("LLMConnect.getClient accepts explicit options for provider-checked construction") {
    val cfg = FixtureChatConfig
      .fromValues(
        model = "fixture-model",
        apiKey = "sk-test",
        baseUrl = FixtureChatProvider.DefaultBaseUrl
      )
      .value
    val options = LlmClientOptions(
      exchangeLogging = ProviderExchangeLogging.enabled(ProviderExchangeSink.noop)
    )

    val res = LLMConnect.getClient(FixtureChatProvider.id, cfg, options)

    res.isRight shouldBe true
  }

  test("ProviderExchange carries timing, outcome, and correlation metadata") {
    val startedAt   = Instant.parse("2026-03-22T10:00:00Z")
    val completedAt = Instant.parse("2026-03-22T10:00:02Z")

    val exchange = ProviderExchange(
      exchangeId = "ex-1",
      provider = "ollama",
      model = Some("gemma3:27b"),
      requestId = Some("req-1"),
      correlationId = Some("corr-1"),
      startedAt = startedAt,
      completedAt = completedAt,
      duration = 2000.millis,
      outcome = ProviderExchangeOutcome.Success,
      requestBody = """{"prompt":"hello"}""",
      responseBody = Some("""{"response":"hi"}"""),
      errorMessage = None
    )

    exchange.duration shouldBe 2000.millis
    exchange.outcome shouldBe ProviderExchangeOutcome.Success
    exchange.requestId shouldBe Some("req-1")
    exchange.correlationId shouldBe Some("corr-1")
  }

  // ---- growth-prone construction (#2346) ----

  test("LlmClientOptions.default has no metrics and no exchange logging") {
    LlmClientOptions.default shouldBe LlmClientOptions()
    LlmClientOptions.default.metrics shouldBe org.llm4s.metrics.MetricsCollector.noop
  }

  test("LlmClientOptions' setters each change one field") {
    val logging = ProviderExchangeLogging.enabled(ProviderExchangeSink.noop)
    val metrics = new org.llm4s.metrics.MetricsCollector {
      override def observeRequest(
        provider: String,
        model: String,
        outcome: org.llm4s.metrics.Outcome,
        duration: FiniteDuration
      ): Unit = ()
      override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = ()
      override def recordCost(provider: String, model: String, costUsd: Double): Unit                      = ()
    }

    val adjusted = LlmClientOptions.default.withMetrics(metrics).withExchangeLogging(logging)

    adjusted shouldBe LlmClientOptions(metrics = metrics, exchangeLogging = logging)
    adjusted.withExchangeLogging(ProviderExchangeLogging.Disabled) shouldBe LlmClientOptions(metrics = metrics)
  }

  test("LlmClientOptions.default supplies no HTTP client, so each provider builds its own (#2341)") {
    LlmClientOptions.default.httpClient shouldBe None
    LlmClientOptions(MetricsCollector.noop, ProviderExchangeLogging.Disabled).httpClient shouldBe None
  }

  test("withHttpClient sets, replaces and clears the supplied HTTP client") {
    val first  = Llm4sHttpClient.create()
    val second = Llm4sHttpClient.create()

    val options = LlmClientOptions.default.withHttpClient(first)
    options.httpClient.map(_ eq first) shouldBe Some(true)
    options.withHttpClient(Some(second)).httpClient.map(_ eq second) shouldBe Some(true)
    options.withHttpClient(None) shouldBe LlmClientOptions.default
    LlmClientOptions(httpClient = Some(first)) shouldBe options
  }

  test("a provider closes only the HTTP client it built itself, never a supplied one") {
    var closed = 0
    val supplied = new Llm4sHttpClient {
      def get(url: String, headers: Map[String, String], params: Map[String, String], timeout: FiniteDuration) =
        ???
      def post(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration)           = ???
      def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: FiniteDuration) = ???
      def postMultipart(
        url: String,
        headers: Map[String, String],
        parts: Seq[org.llm4s.http.MultipartPart],
        timeout: FiniteDuration
      ) = ???
      def put(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration)        = ???
      def delete(url: String, headers: Map[String, String], timeout: FiniteDuration)                   = ???
      def postRaw(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration)    = ???
      def postStream(url: String, headers: Map[String, String], body: String, timeout: FiniteDuration) = ???
      override def close(): Unit                                                                       = closed += 1
    }

    val chosen = Llm4sHttpClient.suppliedOrFromConfig(Some(supplied))
    (chosen should be).theSameInstanceAs(supplied)
    (Llm4sHttpClient.resolve(chosen, HttpNetworkSettings.default) should be).theSameInstanceAs(supplied)
    Llm4sHttpClient.closeIfOwned(chosen, supplied)
    closed shouldBe 0

    val fromConfig = Llm4sHttpClient.suppliedOrFromConfig(None)
    (fromConfig should be).theSameInstanceAs(Llm4sHttpClient.FromConfig)
    Llm4sHttpClient.closeIfOwned(fromConfig, supplied)
    closed shouldBe 1
  }

  test("LlmClientOptions keeps its constructor and copy private") {
    assertTypeError("new LlmClientOptions(org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled)")
    assertTypeError("LlmClientOptions.default.copy(exchangeLogging = ProviderExchangeLogging.Disabled)")
  }
}
