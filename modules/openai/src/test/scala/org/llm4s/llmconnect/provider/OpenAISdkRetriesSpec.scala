package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName }
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.auth.IdentitySource
import org.llm4s.llmconnect.config.{ AzureConfig, OpenAIConfig, OpenAIWorkloadIdentity }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/**
 * The OpenAI, Requesty and Azure clients send one request per call: openai-java's own retries (two by default) are
 * off, so retrying is [[ReliableClient]]'s alone, under its policy, deadline, breaker and metrics (#3210).
 */
final class OpenAISdkRetriesSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  private val unavailable =
    """{"error":{"message":"overloaded","type":"server_error","code":"service_unavailable"}}"""

  /** Runs `test` against a server answering every request 503, and returns how many requests it received. */
  private def requestsAgainst503(test: String => Unit): Int = {
    val count = new AtomicInteger
    LocalProviderTestServer.withServer("/") { exchange =>
      exchange.getRequestBody.readAllBytes(): Unit
      count.incrementAndGet(): Unit
      LocalProviderTestServer.sendJsonResponse(exchange, 503, unavailable)
    }(test)
    count.get
  }

  private def openAI(baseUrl: String): LLMClient =
    OpenAIClient(OpenAIConfig.fromValues(ModelName("gpt-4o"), Secret("sk-test"), BaseUrl(baseUrl)).value).value

  private def azure(baseUrl: String): LLMClient =
    OpenAIClient
      .azure(AzureConfig.fromValues(ModelName("gpt-4o"), Secret("az-key"), BaseUrl(baseUrl), "2024-02-01").value)
      .value

  private def oneRequestPerCall(build: String => LLMClient): Unit = {
    requestsAgainst503 { baseUrl =>
      val client = build(baseUrl)
      try client.complete(hello, CompletionOptions()).left.value shouldBe a[ServiceError]
      finally client.close()
    } shouldBe 1
    requestsAgainst503 { baseUrl =>
      val client = build(baseUrl)
      try client.streamComplete(hello, CompletionOptions())(_ => ()).isLeft shouldBe true
      finally client.close()
    } shouldBe 1
  }

  "The OpenAI client" should "send one request for a call the service answers 503, streamed or not" in
    oneRequestPerCall(openAI)

  "The Azure OpenAI client" should "send one request for a call the service answers 503, streamed or not" in
    oneRequestPerCall(azure)

  "The workload-identity OpenAI client" should "send one request for a call the service answers 503" in
    KnownSecrets.isolated {
      // The SDK posts the token exchange to a fixed https://auth.openai.com URL, so it runs over an in-memory HTTP
      // client that answers the exchange and fails every API call with a 503.
      import com.openai.core.http.{ Headers, HttpClient as SdkHttp, HttpRequest, HttpResponse as SdkResponse }
      val calls = new AtomicInteger
      def response(status: Int, text: String): SdkResponse = new SdkResponse {
        def statusCode(): Int  = status
        def headers(): Headers = Headers.builder().put("Content-Type", "application/json").build()
        def body(): java.io.InputStream =
          new java.io.ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        def close(): Unit = ()
      }
      val base = new SdkHttp {
        def execute(request: HttpRequest, options: com.openai.core.RequestOptions): SdkResponse =
          if request.url().startsWith("https://auth.openai.com/") then
            response(
              200,
              """{"access_token":"opaqueOpenAIAccess0002","issued_token_type":"urn:ietf:params:oauth:token-type:""" +
                """access_token","token_type":"Bearer","expires_in":3600}"""
            )
          else
            calls.incrementAndGet(): Unit
            response(503, unavailable)
        def executeAsync(
          request: HttpRequest,
          options: com.openai.core.RequestOptions
        ): java.util.concurrent.CompletableFuture[SdkResponse] =
          java.util.concurrent.CompletableFuture.completedFuture(execute(request, options))
        def close(): Unit = ()
      }
      val wi = OpenAIWorkloadIdentity(IdentitySource.Literal(Secret("eyJ.svid.sig")), "idp_1", "sa_1")
      val config = OpenAIConfig(
        Secret(""),
        ModelName("gpt-4o-mini"),
        None,
        BaseUrl("https://api.openai.com/v1"),
        128000,
        4096,
        workloadIdentity = Some(wi)
      )
      val transport = OpenAIClientTransport.sdk(OpenAIClientTransport.workloadIdentitySdk(config, wi, base))
      val client    = OpenAIClient.forTest("gpt-4o-mini", transport, config)
      try client.complete(hello, CompletionOptions()).left.value shouldBe a[ServiceError]
      finally client.close()
      calls.get shouldBe 1
    }

  "A ReliableClient over the OpenAI client" should "send exactly the attempts its policy allows" in {
    val policy = ReliabilityConfig.default.withRetryPolicy(RetryPolicy.fixedDelay(3, 1.milli))
    requestsAgainst503 { baseUrl =>
      val client = ReliableClient(openAI(baseUrl), "openai", policy)
      try client.complete(hello, CompletionOptions()).left.value shouldBe a[ServiceError]
      finally client.close()
    } shouldBe 3
  }
}
