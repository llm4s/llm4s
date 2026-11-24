package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import com.openai.azure.AzureUrlPathMode
import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.AuthenticationError
import org.llm4s.error.LLMError
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.llmconnect.{
  LLMClient,
  LlmClientOptions,
  ProviderExchange,
  ProviderExchangeLogging,
  ProviderExchangeSink
}
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ReasoningEffort, UserMessage }
import org.llm4s.metrics.MockMetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import org.llm4s.llmconnect.spi.ProviderBuildContext

/**
 * `OpenAIClient` over the real `openai-java` transport, against a local HTTP server: the URL,
 * headers and body each provider sends, and the SDK's SSE parsing end to end.
 *
 * These pin down what the move off the Azure SDK (#1132) must keep: OpenAI and Requesty post
 * to `<baseUrl>/chat/completions` with a bearer key, and Azure to
 * `<endpoint>/openai/deployments/<deployment>/chat/completions?api-version=...` with an
 * `api-key` header, whatever the endpoint's host name.
 */
final class OpenAIClientWireSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  final private case class Seen(
    method: String,
    path: String,
    query: Option[String],
    authorization: Option[String],
    apiKey: Option[String],
    organization: Option[String],
    body: ujson.Value
  )

  private def capture(into: AtomicReference[Seen], exchange: HttpExchange): Unit = {
    val headers = exchange.getRequestHeaders
    val body    = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
    into.set(
      Seen(
        method = exchange.getRequestMethod,
        path = exchange.getRequestURI.getPath,
        query = Option(exchange.getRequestURI.getQuery),
        authorization = Option(headers.getFirst("Authorization")),
        apiKey = Option(headers.getFirst("api-key")),
        organization = Option(headers.getFirst("OpenAI-Organization")),
        body = ujson.read(body)
      )
    )
  }

  private val hello = Conversation(Seq(UserMessage("hello")))

  "OpenAIClient for OpenAI" should "post to <baseUrl>/chat/completions with a bearer key and the organisation" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi", "gpt-4o"))
    } { baseUrl =>
      val config =
        OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("sk-test"), BaseUrl(s"$baseUrl/v1"), Some("org-123")).value
      val client = OpenAIClient(config).value
      val result = client.complete(hello, CompletionOptions(maxTokens = Some(64)))
      client.close()

      val completion = result.value
      completion.content shouldBe "hi"
      completion.model shouldBe ModelName("gpt-4o")
      completion.usage.map(_.totalTokens) shouldBe Some(15)
      completion.created shouldBe Instant.ofEpochSecond(1700000000L) // the wire's epoch seconds

      val request = seen.get()
      request.method shouldBe "POST"
      request.path shouldBe "/v1/chat/completions"
      request.query shouldBe None
      request.authorization shouldBe Some("Bearer sk-test")
      request.apiKey shouldBe None
      request.organization shouldBe Some("org-123")
      request.body("model").str shouldBe "gpt-4o"
      request.body("messages")(0)("role").str shouldBe "user"
      request.body("messages")(0)("content").str shouldBe "hello"
      request.body("max_tokens").num shouldBe 64
      request.body.obj.contains("stream") shouldBe false
    }
  }

  it should "stream over SSE, reassembling a tool call split across events" in {
    val seen = new AtomicReference[Seen]()
    val events = Seq(
      """{"id":"c1","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]}}]}""",
      """{"id":"c1","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}}]}""",
      """{"id":"c1","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Paris\"}"}}]}}]}""",
      """{"id":"c1","created":0,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}"""
    )
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendSseResponse(
        exchange,
        (events.map(e => s"data: $e") :+ "data: [DONE]").mkString("", "\n\n", "\n\n")
      )
    } { baseUrl =>
      val config = OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("sk-test"), BaseUrl(baseUrl)).value
      val client = OpenAIClient(config).value
      val result = client.streamComplete(hello, CompletionOptions())(_ => ())
      client.close()

      seen.get().body("stream").bool shouldBe true
      seen.get().body("stream_options")("include_usage").bool shouldBe true
      seen.get().organization shouldBe None
      val completion = result.value
      completion.id shouldBe "c1"
      completion.toolCalls.map(tc => (tc.id, tc.name, tc.arguments)) shouldBe
        List(("call_1", "get_weather", ujson.Obj("city" -> "Paris")))
      completion.usage.map(_.totalTokens) shouldBe Some(7)
    }
  }

  it should "read usage from a final SSE event with no choices" in {
    val events = Seq(
      """{"id":"c2","created":0,"model":"gpt-5-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}""",
      """{"id":"c2","created":0,"model":"gpt-5-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":null}""",
      """{"id":"c2","created":0,"model":"gpt-5-mini","choices":[],"usage":{"prompt_tokens":9,"completion_tokens":30,"total_tokens":39,"completion_tokens_details":{"reasoning_tokens":20}}}"""
    )
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendSseResponse(
        exchange,
        (events.map(e => s"data: $e") :+ "data: [DONE]").mkString("", "\n\n", "\n\n")
      )
    } { baseUrl =>
      val client =
        OpenAIClient(OpenAIConfig.fromValues(ModelName("gpt-5-mini"), Secret("sk-test"), BaseUrl(baseUrl)).value).value
      val result = client.streamComplete(hello, CompletionOptions().withReasoning(ReasoningEffort.Medium))(_ => ())
      client.close()

      val body = seen.get().body
      body("reasoning_effort").str shouldBe "medium"
      body("stream_options")("include_usage").bool shouldBe true
      body.obj.contains("temperature") shouldBe false

      val completion = result.value
      completion.content shouldBe "Hi"
      completion.usage.map(u => (u.promptTokens, u.completionTokens, u.totalTokens, u.thinkingTokens)) shouldBe
        Some((9, 10, 39, Some(20)))
    }
  }

  it should "send reasoning_effort and max_completion_tokens to a reasoning model" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi", "o4-mini"))
    } { baseUrl =>
      val client =
        OpenAIClient(OpenAIConfig.fromValues(ModelName("o4-mini"), Secret("sk-test"), BaseUrl(baseUrl)).value).value
      client.complete(hello, CompletionOptions(maxTokens = Some(256)).withReasoning(ReasoningEffort.High)).value
      client.close()

      val body = seen.get().body
      body("reasoning_effort").str shouldBe "high"
      body("max_completion_tokens").num shouldBe 256
      body.obj.contains("max_tokens") shouldBe false
      body.obj.contains("temperature") shouldBe false
      body.obj.contains("stream_options") shouldBe false
    }
  }

  private lazy val noopTool =
    org.llm4s.toolapi
      .ToolBuilder[String](
        "noop",
        "Does nothing",
        org.llm4s.toolapi.Schema.`object`[Map[String, Any]]("No parameters")
      )
      .withHandler(_ => Right("ok"))
      .build()
      .fold(e => fail(e.formatted), identity)

  it should "send gpt-6-luna a request with tools at reasoning_effort none, and report it (#2886)" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        200,
        LocalProviderTestServer.openAICompletion("hi", "gpt-6-luna")
      )
    } { baseUrl =>
      val client =
        OpenAIClient(OpenAIConfig.fromValues(ModelName("gpt-6-luna"), Secret("sk-test"), BaseUrl(baseUrl)).value).value
      val options =
        CompletionOptions(maxTokens = Some(256)).withTools(Seq(noopTool)).withReasoning(ReasoningEffort.High)
      val completion = client.complete(hello, options).value
      client.close()

      val body = seen.get().body
      body("reasoning_effort").str shouldBe "none"
      body("max_completion_tokens").num shouldBe 256
      body.obj.contains("max_tokens") shouldBe false
      body("tools").arr should have size 1
      completion.adjustments.map(_.option) should contain(org.llm4s.llmconnect.model.RequestOption.Reasoning)
    }
  }

  it should "stream to a router-prefixed gpt-6-luna with tools at reasoning_effort none, and report it (#2886)" in {
    val seen = new AtomicReference[Seen]()
    val events = Seq(
      """{"id":"l1","created":0,"model":"gpt-6-luna","choices":[{"index":0,"delta":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}""",
      """{"id":"l1","created":0,"model":"gpt-6-luna","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}"""
    )
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendSseResponse(
        exchange,
        (events.map(e => s"data: $e") :+ "data: [DONE]").mkString("", "\n\n", "\n\n")
      )
    } { baseUrl =>
      // Requesty's form of the id: the same OpenAIClient and rules, behind a router prefix
      val client = OpenAIClient(
        OpenAIConfig.fromValues(ModelName("openai/gpt-6-luna"), Secret("sk-test"), BaseUrl(baseUrl)).value
      ).value
      val options =
        CompletionOptions(maxTokens = Some(128)).withTools(Seq(noopTool)).withReasoning(ReasoningEffort.Medium)
      val completion = client.streamComplete(hello, options)(_ => ()).value
      client.close()

      val body = seen.get().body
      body("stream").bool shouldBe true
      body("reasoning_effort").str shouldBe "none"
      body("max_completion_tokens").num shouldBe 128
      body.obj.contains("max_tokens") shouldBe false
      completion.content shouldBe "hi"
      completion.adjustments.map(_.option) should contain(org.llm4s.llmconnect.model.RequestOption.Reasoning)
    }
  }

  it should "send an Azure deployment named gpt-6-luna a request with tools at reasoning_effort none (#2886)" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        200,
        LocalProviderTestServer.openAICompletion("hi", "gpt-6-luna")
      )
    } { baseUrl =>
      val config = AzureConfig
        .fromValues(ModelName("gpt-6-luna"), Secret("azure-key"), BaseUrl(baseUrl), AzureConfig.DEFAULT_API_VERSION)
        .value
      val client     = OpenAIClient(config).value
      val completion = client.complete(hello, CompletionOptions().withTools(Seq(noopTool))).value
      client.close()

      val request = seen.get()
      request.path shouldBe "/openai/deployments/gpt-6-luna/chat/completions"
      request.body("reasoning_effort").str shouldBe "none"
      completion.adjustments.map(_.option) should contain(org.llm4s.llmconnect.model.RequestOption.Reasoning)
    }
  }

  it should "refuse a request with tools to gpt-6.1-terra before sending it (#2886)" in {
    val requests = new java.util.concurrent.atomic.AtomicInteger()
    LocalProviderTestServer.withServer("/") { exchange =>
      requests.incrementAndGet()
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi", "x"))
    } { baseUrl =>
      val client = OpenAIClient(
        OpenAIConfig.fromValues(ModelName("gpt-6.1-terra"), Secret("sk-test"), BaseUrl(baseUrl)).value
      ).value
      val options = CompletionOptions().withTools(Seq(noopTool))
      val sync    = client.complete(hello, options)
      val stream  = client.streamComplete(hello, options)(_ => ())
      client.close()

      Seq(sync, stream).foreach(_.left.value.message should include("Responses API"))
      requests.get() shouldBe 0
    }
  }

  it should "map a 401 to an AuthenticationError naming the provider" in {
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes()
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        401,
        """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}"""
      )
    } { baseUrl =>
      val client =
        OpenAIClient(OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("sk-bad"), BaseUrl(baseUrl)).value).value
      val result = client.complete(hello, CompletionOptions())
      client.close()

      result.left.value shouldBe an[AuthenticationError]
      result.left.value.message should include("openai")
    }
  }

  it should "not repeat the configured key, or a credential field, from the error body" in KnownSecrets.isolated {
    val key = "opaque-openai-key-1"
    Secret(key)
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes()
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        401,
        s"""{"error":{"message":"Incorrect API key provided: $key","type":"invalid_request_error",""" +
          s""""code":"invalid_api_key"},"access_token":"opaque-access-tok-2"}"""
      )
    } { baseUrl =>
      val client = OpenAIClient(OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret(key), BaseUrl(baseUrl)).value).value
      val result = client.complete(hello, CompletionOptions())
      client.close()

      result.left.value shouldBe an[AuthenticationError]
      result.left.value.message should include("Incorrect API key provided")
      (result.left.value.message should not).include(key)
      (result.left.value.message should not).include("opaque-access-tok-2")
    }
  }

  "OpenAIClient for Azure" should "post to the deployment path with an api-key header and api-version" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi", "gpt-4o"))
    } { baseUrl =>
      // A localhost endpoint: not an *.openai.azure.com host, so this also proves the client
      // treats a custom-domain endpoint as Azure, as the Azure SDK did.
      val config =
        AzureConfig
          .fromValues(ModelName("my-deploy"), Secret("azure-key"), BaseUrl(baseUrl), AzureConfig.DEFAULT_API_VERSION)
          .value
      val client = OpenAIClient(config).value
      val result = client.complete(hello, CompletionOptions())
      client.close()

      result.value.content shouldBe "hi"
      val request = seen.get()
      request.path shouldBe "/openai/deployments/my-deploy/chat/completions"
      request.query shouldBe Some("api-version=2025-01-01-preview")
      request.apiKey shouldBe Some("azure-key")
      request.authorization shouldBe None
    }
  }

  it should "stream from a deployment with reasoning_effort and stream_options on the deployment path" in {
    val seen = new AtomicReference[Seen]()
    val events = Seq(
      """{"id":"a1","created":0,"model":"o4-mini","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
      """{"id":"a1","created":0,"model":"o4-mini","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":6,"total_tokens":11}}"""
    )
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendSseResponse(
        exchange,
        (events.map(e => s"data: $e") :+ "data: [DONE]").mkString("", "\n\n", "\n\n")
      )
    } { baseUrl =>
      // "team-reasoner" names no model, so asking for reasoning is what makes the client send it.
      val config =
        AzureConfig
          .fromValues(
            ModelName("team-reasoner"),
            Secret("azure-key"),
            BaseUrl(baseUrl),
            AzureConfig.DEFAULT_API_VERSION
          )
          .value
      val client = OpenAIClient(config).value
      val result = client.streamComplete(hello, CompletionOptions().withReasoning(ReasoningEffort.Low))(_ => ())
      client.close()

      val request = seen.get()
      request.path shouldBe "/openai/deployments/team-reasoner/chat/completions"
      request.body("reasoning_effort").str shouldBe "low"
      request.body("stream_options")("include_usage").bool shouldBe true
      result.value.usage.map(_.totalTokens) shouldBe Some(11)
    }
  }

  it should "accept the api-version in its wire form as well as the Azure SDK's constant name" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi"))
    } { baseUrl =>
      val config =
        AzureConfig.fromValues(ModelName("my-deploy"), Secret("azure-key"), BaseUrl(s"$baseUrl/"), "2024-10-21").value
      val client = OpenAIClient(config).value
      client.complete(hello, CompletionOptions()).isRight shouldBe true
      client.close()

      seen.get().path shouldBe "/openai/deployments/my-deploy/chat/completions"
      seen.get().query shouldBe Some("api-version=2024-10-21")
    }
  }

  it should "use Azure's unified v1 API for an endpoint ending in /openai/v1, without the default api-version" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi"))
    } { baseUrl =>
      val config =
        AzureConfig
          .fromValues(
            ModelName("my-deploy"),
            Secret("azure-key"),
            BaseUrl(s"$baseUrl/openai/v1"),
            AzureConfig.DEFAULT_API_VERSION
          )
          .value
      val client = OpenAIClient(config).value
      client.complete(hello, CompletionOptions()).isRight shouldBe true
      client.close()

      seen.get().path shouldBe "/openai/v1/chat/completions"
      seen.get().query shouldBe None
      seen.get().body("model").str shouldBe "my-deploy"
      seen.get().apiKey shouldBe Some("azure-key")
    }
  }

  "OpenAIClientTransport.azureServiceVersion" should "map the Azure SDK's constant names to wire values" in {
    OpenAIClientTransport.azureServiceVersion("V2025_01_01_PREVIEW").value() shouldBe "2025-01-01-preview"
    OpenAIClientTransport.azureServiceVersion("V2024_06_01").value() shouldBe "2024-06-01"
    OpenAIClientTransport.azureServiceVersion(" 2024-02-15-preview ").value() shouldBe "2024-02-15-preview"
    OpenAIClientTransport.azureServiceVersion("preview").value() shouldBe "preview"
  }

  "OpenAIClientTransport.azureUrlPathMode" should "choose the unified API only for an /openai/v1 endpoint" in {
    OpenAIClientTransport.azureUrlPathMode("https://r.openai.azure.com") shouldBe AzureUrlPathMode.LEGACY
    OpenAIClientTransport.azureUrlPathMode("https://gw.example.com/") shouldBe AzureUrlPathMode.LEGACY
    OpenAIClientTransport.azureUrlPathMode("https://r.openai.azure.com/openai/v1/") shouldBe AzureUrlPathMode.UNIFIED
  }

  // Each provider's errors, metrics and exchange log carry its own name, not "openai" (#1209
  // review): Azure's from `AzureConfig.providerId`, Requesty's from its descriptor, so even a
  // Requesty `OpenAIConfig` built by hand, whose `providerId` is inferred as `openai`, is labelled
  // `requesty`.

  private def unauthorized =
    """{"error":{"message":"Access denied","type":"invalid_request_error","code":"401"}}"""

  final private case class Reported(error: LLMError, metrics: MockMetricsCollector, exchanges: List[ProviderExchange])

  /** Builds a client through `build`, makes one call that fails with a 401, and returns what was reported. */
  private def failOnce(build: (String, LlmClientOptions) => LLMClient): Reported = {
    val metrics  = new MockMetricsCollector
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit = recorded += exchange
    val error = new AtomicReference[LLMError]()
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes()
      LocalProviderTestServer.sendJsonResponse(exchange, 401, unauthorized)
    } { baseUrl =>
      val client = build(baseUrl, LlmClientOptions(metrics, ProviderExchangeLogging.Enabled(sink)))
      error.set(client.complete(hello, CompletionOptions()).left.value)
      client.close()
    }
    Reported(error.get(), metrics, recorded.toList)
  }

  private def assertLabelled(provider: String, reported: Reported): Unit = {
    reported.error shouldBe an[AuthenticationError]
    reported.error.asInstanceOf[AuthenticationError].provider shouldBe provider
    reported.error.context("provider") shouldBe provider
    reported.metrics.requestCalls.map(_._1).toSeq shouldBe Seq(provider)
    reported.exchanges.map(_.provider) shouldBe List(provider)
  }

  "OpenAIClient's provider label" should "be azure for an Azure client, in errors, metrics and the exchange log" in {
    val reported = failOnce { (baseUrl, options) =>
      val config =
        AzureConfig
          .fromValues(ModelName("my-deploy"), Secret("azure-key"), BaseUrl(baseUrl), AzureConfig.DEFAULT_API_VERSION)
          .value
      AzureProvider.build(config, ProviderBuildContext.of(options)).value
    }
    assertLabelled("azure", reported)
  }

  it should "be requesty for a Requesty client built by its descriptor" in {
    val reported = failOnce { (baseUrl, options) =>
      val config = OpenAIConfig.fromValues(ModelName("openai/gpt-4o-mini"), Secret("rq-key"), BaseUrl(baseUrl)).value
      RequestyProvider.build(config, ProviderBuildContext.of(options)).value
    }
    assertLabelled("requesty", reported)
  }

  it should "stay openai for an OpenAI client" in {
    val reported = failOnce { (baseUrl, options) =>
      val config = OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("sk-test"), BaseUrl(baseUrl)).value
      OpenAIProvider.build(config, ProviderBuildContext.of(options)).value
    }
    assertLabelled("openai", reported)
  }

  "OpenAIClient.mapError" should "carry a 429's Retry-After from the SDK exception into the RateLimitError" in {
    val limited = com.openai.errors.RateLimitException
      .builder()
      .headers(com.openai.core.http.Headers.builder().put("Retry-After", "5").build())
      .build()
    OpenAIClient.mapError(limited, "openai") match {
      case err: org.llm4s.error.RateLimitError => err.retryDelay shouldBe Some(5.seconds)
      case other                               => fail(s"Expected RateLimitError, got: $other")
    }
  }

  "OpenAIClient's provider label" should "name the provider in the already-closed error" in {
    val azure = AzureConfig
      .fromValues(ModelName("my-deploy"), Secret("k"), BaseUrl("https://r.openai.azure.com"), "2024-10-21")
      .value
    val client = OpenAIClient(azure).value
    client.close()
    client.complete(hello, CompletionOptions()).left.value.message should include(
      "Azure OpenAI client for model my-deploy"
    )
  }
}
