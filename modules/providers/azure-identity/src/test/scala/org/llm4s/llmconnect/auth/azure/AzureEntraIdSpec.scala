package org.llm4s.llmconnect.auth.azure

import com.azure.core.credential.{ AccessToken as AzureAccessToken, TokenCredential, TokenRequestContext }
import com.sun.net.httpserver.HttpExchange
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.AuthenticationError
import org.llm4s.llmconnect.config.AzureConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.provider.{ AzureEntraIdSupport, AzureProvider, OpenAIClient }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource
import reactor.core.publisher.Mono

import java.time.{ Clock, Instant, OffsetDateTime, ZoneId, ZoneOffset }
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import scala.jdk.CollectionConverters.*

/**
 * `llm4s-azure-identity` (#2274): tokens from an Azure `TokenCredential` reach Azure OpenAI as the bearer, are cached,
 * and are refreshed before they expire - against a local stub, with a test credential and a clock the spec moves, so
 * no Azure account is involved.
 */
class AzureEntraIdSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  /** A clock the spec sets. */
  final private class TestClock(start: Instant) extends Clock {
    val now                                    = new AtomicReference[Instant](start)
    override def getZone: ZoneId               = ZoneOffset.UTC
    override def withZone(zone: ZoneId): Clock = this
    override def instant(): Instant            = now.get()
    def advance(by: java.time.Duration): Unit  = now.updateAndGet(_.plus(by)): Unit
  }

  /** Issues `entra-1`, `entra-2`, ..., each valid for an hour from the clock's now, and records the scopes asked for. */
  final private class TestCredential(clock: Clock) extends TokenCredential {
    val issued = new AtomicInteger()
    val scopes = new ConcurrentLinkedQueue[String]()
    override def getTokenSync(request: TokenRequestContext): AzureAccessToken =
      scopes.addAll(request.getScopes)
      new AzureAccessToken(
        s"entra-${issued.incrementAndGet()}",
        OffsetDateTime.ofInstant(clock.instant().plusSeconds(3600), ZoneOffset.UTC)
      )
    override def getToken(request: TokenRequestContext): Mono[AzureAccessToken] = Mono.just(getTokenSync(request))
  }

  private val hello = Conversation(Seq(UserMessage("hello")))

  "An Azure OpenAI client with Entra ID tokens" should
    "send the credential's token as the bearer, reuse it, and fetch a new one before it expires" in {
      val authorizations = new ConcurrentLinkedQueue[(Option[String], Option[String])]()
      LocalProviderTestServer.withServer("/") { (exchange: HttpExchange) =>
        exchange.getRequestBody.readAllBytes()
        val headers = exchange.getRequestHeaders
        authorizations.add(Option(headers.getFirst("Authorization")) -> Option(headers.getFirst("api-key")))
        LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi"))
      } { baseUrl =>
        val clock      = new TestClock(Instant.parse("2026-10-25T10:00:00Z"))
        val credential = new TestCredential(clock)
        val tokens     = AzureEntraId.tokenProvider(credential, AzureEntraId.CognitiveServicesScope, clock)
        val client     = OpenAIClient(AzureConfig.keyless(baseUrl, "my-deploy", tokens)).value

        client.complete(hello, CompletionOptions()).value.content shouldBe "hi"
        clock.advance(java.time.Duration.ofMinutes(30))
        client.complete(hello, CompletionOptions()).value.content shouldBe "hi"
        // 56 minutes after the first token: inside the five-minute refresh margin of its expiry.
        clock.advance(java.time.Duration.ofMinutes(26))
        client.complete(hello, CompletionOptions()).value.content shouldBe "hi"
        client.close()

        authorizations.asScala.toList shouldBe List(
          Some("Bearer entra-1") -> None,
          Some("Bearer entra-1") -> None,
          Some("Bearer entra-2") -> None
        )
        credential.issued.get() shouldBe 2
        credential.scopes.asScala.toSet shouldBe Set("https://cognitiveservices.azure.com/.default")
      }
    }

  "AzureEntraId.tokenProvider" should "return a credential's failure as an AuthenticationError with its message" in {
    val unavailable = new TokenCredential {
      override def getToken(request: TokenRequestContext): Mono[AzureAccessToken] =
        Mono.error(new IllegalStateException("ManagedIdentityCredential authentication unavailable"))
      override def getTokenSync(request: TokenRequestContext): AzureAccessToken =
        throw new IllegalStateException("ManagedIdentityCredential authentication unavailable")
    }
    val error = AzureEntraId.tokenProvider(unavailable).token().left.value
    error shouldBe an[AuthenticationError]
    error.message should include("ManagedIdentityCredential authentication unavailable")
    error.message should include(AzureEntraId.CognitiveServicesScope)
  }

  it should "report a credential that returns no token" in {
    val empty = new TokenCredential {
      override def getToken(request: TokenRequestContext): Mono[AzureAccessToken] = Mono.empty()
      override def getTokenSync(request: TokenRequestContext): AzureAccessToken   = null
    }
    AzureEntraId.tokenProvider(empty, "api://custom/.default").token().left.value.message should include(
      "returned no token for api://custom/.default"
    )
  }

  "AzureEntraId.defaultCredential" should "build DefaultAzureCredential without asking it for a token" in {
    AzureEntraId.defaultCredential() should not be null
    AzureEntraId.defaultCredential("00000000-0000-0000-0000-000000000001") should not be null
    AzureEntraId.defaultCredential(" ") should not be null
  }

  "A section with authentication = entra-id" should "load into a keyless AzureConfig through this module" in {
    given ProviderRegistry = ProviderRegistry.of(AzureProvider)
    AzureEntraIdSupport.load().value shouldBe a[DefaultAzureEntraIdSupport]
    val hocon =
      """llm4s.credentials.azure.apiKey = "sk-shared"
        |llm4s.providers.azure-prod {
        |  provider       = azure
        |  model          = gpt-4o
        |  endpoint       = "https://my-resource.openai.azure.com"
        |  authentication = "entra-id"
        |  clientId       = "00000000-0000-0000-0000-000000000001"
        |}
        |""".stripMargin
    val config = Llm4sConfig.provider(ConfigSource.string(hocon), "azure-prod").value.asInstanceOf[AzureConfig]
    config.apiKey shouldBe ""
    config.tokenProvider should not be empty
    config.endpoint shouldBe "https://my-resource.openai.azure.com"
  }
}
