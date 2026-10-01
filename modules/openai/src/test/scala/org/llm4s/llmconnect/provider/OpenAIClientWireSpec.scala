package org.llm4s.llmconnect.provider

import com.openai.azure.AzureUrlPathMode
import com.sun.net.httpserver.HttpExchange
import org.llm4s.error.AuthenticationError
import org.llm4s.error.LLMError
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
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable.ListBuffer

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
        OpenAIConfig.fromValues("gpt-4o", "sk-test", Some("org-123"), s"$baseUrl/v1").value
      val client = OpenAIClient(config).value
      val result = client.complete(hello, CompletionOptions(maxTokens = Some(64)))
      client.close()

      val completion = result.value
      completion.content shouldBe "hi"
      completion.model shouldBe "gpt-4o"
      completion.usage.map(_.totalTokens) shouldBe Some(15)

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
      val config = OpenAIConfig.fromValues("gpt-4o", "sk-test", None, baseUrl).value
      val client = OpenAIClient(config).value
      val result = client.streamComplete(hello, CompletionOptions(), _ => ())
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
      val client = OpenAIClient(OpenAIConfig.fromValues("gpt-5-mini", "sk-test", None, baseUrl).value).value
      val result = client.streamComplete(hello, CompletionOptions().withReasoning(ReasoningEffort.Medium), _ => ())
      client.close()

      val body = seen.get().body
      body("reasoning_effort").str shouldBe "medium"
      body("stream_options")("include_usage").bool shouldBe true
      body.obj.contains("temperature") shouldBe false

      val completion = result.value
      completion.content shouldBe "Hi"
      completion.usage.map(u => (u.promptTokens, u.completionTokens, u.totalTokens, u.thinkingTokens)) shouldBe
        Some((9, 30, 39, Some(20)))
    }
  }

  it should "send reasoning_effort and max_completion_tokens to a reasoning model" in {
    val seen = new AtomicReference[Seen]()
    LocalProviderTestServer.withServer("/") { exchange =>
      capture(seen, exchange)
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi", "o4-mini"))
    } { baseUrl =>
      val client = OpenAIClient(OpenAIConfig.fromValues("o4-mini", "sk-test", None, baseUrl).value).value
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

  it should "map a 401 to an AuthenticationError naming the provider" in {
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes()
      LocalProviderTestServer.sendJsonResponse(
        exchange,
        401,
        """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}"""
      )
    } { baseUrl =>
      val client = OpenAIClient(OpenAIConfig.fromValues("gpt-4o", "sk-bad", None, baseUrl).value).value
      val result = client.complete(hello, CompletionOptions())
      client.close()

      result.left.value shouldBe an[AuthenticationError]
      result.left.value.message should include("openai")
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
      val config = AzureConfig.fromValues("my-deploy", baseUrl, "azure-key", AzureConfig.DEFAULT_API_VERSION).value
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
      val config = AzureConfig.fromValues("team-reasoner", baseUrl, "azure-key", AzureConfig.DEFAULT_API_VERSION).value
      val client = OpenAIClient(config).value
      val result = client.streamComplete(hello, CompletionOptions().withReasoning(ReasoningEffort.Low), _ => ())
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
      val config = AzureConfig.fromValues("my-deploy", s"$baseUrl/", "azure-key", "2024-10-21").value
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
        AzureConfig.fromValues("my-deploy", s"$baseUrl/openai/v1", "azure-key", AzureConfig.DEFAULT_API_VERSION).value
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
      val client = build(baseUrl, LlmClientOptions(metrics, ProviderExchangeLogging.enabled(sink)))
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
      val config = AzureConfig.fromValues("my-deploy", baseUrl, "azure-key", AzureConfig.DEFAULT_API_VERSION).value
      AzureProvider.buildClient(config, options).value
    }
    assertLabelled("azure", reported)
  }

  it should "be requesty for a Requesty client built by its descriptor" in {
    val reported = failOnce { (baseUrl, options) =>
      val config = OpenAIConfig.fromValues("openai/gpt-4o-mini", "rq-key", None, baseUrl).value
      RequestyProvider.buildClient(config, options).value
    }
    assertLabelled("requesty", reported)
  }

  it should "stay openai for an OpenAI client" in {
    val reported = failOnce { (baseUrl, options) =>
      val config = OpenAIConfig.fromValues("gpt-4o", "sk-test", None, baseUrl).value
      OpenAIProvider.buildClient(config, options).value
    }
    assertLabelled("openai", reported)
  }

  it should "name the provider in the already-closed error" in {
    val azure  = AzureConfig.fromValues("my-deploy", "https://r.openai.azure.com", "k", "2024-10-21").value
    val client = OpenAIClient(azure).value
    client.close()
    client.complete(hello, CompletionOptions()).left.value.message should include(
      "Azure OpenAI client for model my-deploy"
    )
  }
}
