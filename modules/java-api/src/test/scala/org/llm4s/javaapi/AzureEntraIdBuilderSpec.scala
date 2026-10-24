package org.llm4s.javaapi

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.auth.AccessTokenProvider
import org.llm4s.testkit.LocalProviderTestServer.{ openAICompletion, sendJsonResponse, withServer }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** `Llm4s.azureOpenAi()` without an API key (#2274): `tokenProvider(...)` and `entraId()`. */
class AzureEntraIdBuilderSpec extends AnyFlatSpec with Matchers {

  private val tokens = new AccessTokenProvider {
    def token(): Result[String]            = Right("entra-token")
    def invalidate(rejected: String): Unit = ()
  }

  "AzureOpenAiClientBuilder.tokenProvider" should "authenticate with the provider's bearer token and no API key" in {
    val seen = new ConcurrentLinkedQueue[(Option[String], Option[String])]()
    withServer("/") { (exchange: HttpExchange) =>
      exchange.getRequestBody.readAllBytes()
      val headers = exchange.getRequestHeaders
      seen.add(Option(headers.getFirst("Authorization")) -> Option(headers.getFirst("api-key")))
      sendJsonResponse(exchange, 200, openAICompletion("hi from azure"))
    } { baseUrl =>
      val client = Llm4s.azureOpenAi().endpoint(baseUrl).tokenProvider(tokens).model("my-deployment").build()
      client.isSuccess shouldBe true
      client.get().complete("hello").get() shouldBe "hi from azure"
      client.get().close()
      seen.asScala.toList shouldBe List(Some("Bearer entra-token") -> None)
    }
  }

  "AzureOpenAiClientBuilder.entraId" should "need llm4s-azure-identity, failing with kind CONFIGURATION without it" in {
    val result = Llm4s.azureOpenAi().endpoint("https://r.openai.azure.com").entraId("mi-1").model("d").build()
    result.getError().getKind shouldBe LlmErrorKind.CONFIGURATION
    result.getError().getMessage should include("llm4s-azure-identity")
  }

  it should "still require an endpoint and a model" in {
    Llm4s.azureOpenAi().entraId().model("d").build().getError().getMessage shouldBe "Field 'endpoint' is required"
    Llm4s.azureOpenAi().endpoint("https://r").tokenProvider(tokens).build().getError().getMessage shouldBe
      "Field 'model' is required"
  }

  "The builder's toString" should "name the authentication, each choice replacing the other" in {
    val base = Llm4s.azureOpenAi().model("d")
    base.entraId().toString shouldBe "AzureOpenAiClientBuilder(model=d, auth=entra-id)"
    base.entraId("mi-1").toString shouldBe "AzureOpenAiClientBuilder(model=d, auth=entra-id(clientId=mi-1))"
    base.entraId().tokenProvider(tokens).toString shouldBe "AzureOpenAiClientBuilder(model=d, auth=token-provider)"
    base.tokenProvider(tokens).entraId().toString shouldBe "AzureOpenAiClientBuilder(model=d, auth=entra-id)"
  }
}
