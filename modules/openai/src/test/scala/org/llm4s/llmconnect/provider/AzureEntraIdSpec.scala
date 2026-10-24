package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.llmconnect.auth.AccessTokenProvider
import org.llm4s.llmconnect.config.{
  AzureConfig,
  ContextWindowResolver,
  EmbeddingModelConfig,
  EmbeddingProviderConfig,
  ProviderConfig
}
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, EmbeddingRequest, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

/**
 * Keyless Microsoft Entra ID authentication for Azure OpenAI in `llm4s-openai` (#2274), with no Azure SDK: a section
 * with `authentication = "entra-id"`, and a config or embedding provider given an `AccessTokenProvider`. The
 * `DefaultAzureCredential` side is `llm4s-azure-identity`'s own spec.
 */
class AzureEntraIdSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)
  private given ProviderRegistry          = ProviderRegistry.of(AzureProvider)

  /** Hands out `token-1`, `token-2`, ... - a new one after each `invalidate` of the current one. */
  final private class CountingTokens extends AccessTokenProvider {
    private val issued          = new AtomicInteger(1)
    val invalidated             = new ConcurrentLinkedQueue[String]()
    def token(): Result[String] = Right(s"token-${issued.get()}")
    def invalidate(rejected: String): Unit =
      invalidated.add(rejected)
      if (rejected == s"token-${issued.get()}") issued.incrementAndGet(): Unit
  }

  private val hello = Conversation(Seq(UserMessage("hello")))

  private val sharedKey = "llm4s.credentials.azure.apiKey = \"sk-shared\"\n"

  private def section(body: String) =
    s"""llm4s.providers.azure-prod { provider = azure, model = gpt-4o, endpoint = "https://r.openai.azure.com", $body }
       |""".stripMargin

  private def load(hocon: String): Result[ProviderConfig] =
    Llm4sConfig.provider(ConfigSource.string(hocon), "azure-prod")

  private val missingModule = "org.llm4s::llm4s-azure-identity"

  "An Azure section with authentication = entra-id" should "need no apiKey, and take no shared key" in {
    // Validation passes - no key is asked for - and building the config reaches Entra ID, which needs
    // llm4s-azure-identity: llm4s-openai alone does not bring the Azure SDK onto the classpath.
    an[ClassNotFoundException] should be thrownBy Class.forName("com.azure.identity.DefaultAzureCredentialBuilder")
    val error = load(section("""authentication = "entra-id"""")).left.value
    error shouldBe a[ConfigurationError]
    error.message should include(missingModule)
    load(sharedKey + section("""authentication = "Entra-ID", clientId = "mi-1"""")).left.value.message should include(
      missingModule
    )
  }

  it should "be refused when it also sets an apiKey" in {
    load(section("""authentication = "entra-id", apiKey = "k"""")).left.value.message should include(
      "sets both apiKey and authentication = \"entra-id\""
    )
  }

  "An Azure section" should "still need a key with the default authentication" in {
    load(section("")).left.value.message should include("AZURE_OPENAI_API_KEY")
    val config = load(sharedKey + section("")).value.asInstanceOf[AzureConfig]
    config.apiKey shouldBe "sk-shared"
    config.tokenProvider shouldBe None
  }

  it should "refuse an unknown authentication, and a clientId without entra-id" in {
    load(sharedKey + section("""authentication = "oauth"""")).left.value.message should include(
      "must be \"api-key\" or \"entra-id\""
    )
    load(sharedKey + section("""authentication = "API-KEY", clientId = "mi-1"""")).left.value.message should include(
      "clientId applies only with authentication = \"entra-id\""
    )
  }

  "AzureConfig with a token provider" should "send Authorization: Bearer <token> and no api-key header" in {
    val seen = new ConcurrentLinkedQueue[(Option[String], Option[String])]()
    LocalProviderTestServer.withServer("/") { (exchange: HttpExchange) =>
      exchange.getRequestBody.readAllBytes()
      val headers = exchange.getRequestHeaders
      seen.add(Option(headers.getFirst("Authorization")) -> Option(headers.getFirst("api-key")))
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi", "gpt-4o"))
    } { baseUrl =>
      val tokens = new CountingTokens
      val config = AzureConfig.fromTokenProvider("my-deploy", baseUrl, tokens, AzureConfig.DEFAULT_API_VERSION).value
      config.apiKey shouldBe ""
      config.toString should include("auth=entra-id")
      val client = OpenAIClient(config).value
      client.complete(hello, CompletionOptions()).value.content shouldBe "hi"
      client.close()
      seen.asScala.toList shouldBe List(Some("Bearer token-1") -> None)
    }
  }

  it should "drop a token the service rejects with a 401, so the next request carries a new one" in {
    val authorizations = new ConcurrentLinkedQueue[String]()
    LocalProviderTestServer.withServer("/") { (exchange: HttpExchange) =>
      exchange.getRequestBody.readAllBytes()
      val authorization = exchange.getRequestHeaders.getFirst("Authorization")
      authorizations.add(authorization)
      if (authorization == "Bearer token-1")
        LocalProviderTestServer.sendJsonResponse(exchange, 401, """{"error":{"message":"expired","code":"401"}}""")
      else
        LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi"))
    } { baseUrl =>
      val tokens = new CountingTokens
      val client = OpenAIClient(AzureConfig.keyless(baseUrl, "my-deploy", tokens)).value
      client.complete(hello, CompletionOptions()).left.value shouldBe an[AuthenticationError]
      client.complete(hello, CompletionOptions()).value.content shouldBe "hi"
      client.close()
      tokens.invalidated.asScala.toList shouldBe List("token-1")
      authorizations.asScala.toList shouldBe List("Bearer token-1", "Bearer token-2")
    }
  }

  it should "stream with the bearer token, and drop one a streamed request is refused with" in {
    val authorizations = new ConcurrentLinkedQueue[String]()
    LocalProviderTestServer.withServer("/") { (exchange: HttpExchange) =>
      exchange.getRequestBody.readAllBytes()
      val authorization = exchange.getRequestHeaders.getFirst("Authorization")
      authorizations.add(authorization)
      if (authorization == "Bearer token-1")
        LocalProviderTestServer.sendJsonResponse(exchange, 401, """{"error":{"message":"expired","code":"401"}}""")
      else LocalProviderTestServer.sendSseResponse(exchange, LocalProviderTestServer.openAISseBody(Seq("he", "llo")))
    } { baseUrl =>
      val tokens = new CountingTokens
      val client = OpenAIClient(AzureConfig.keyless(baseUrl, "my-deploy", tokens)).value
      client.streamComplete(hello, CompletionOptions(), _ => ()).isLeft shouldBe true
      client.streamComplete(hello, CompletionOptions(), _ => ()).value.content shouldBe "hello"
      client.close()
      tokens.invalidated.asScala.toList shouldBe List("token-1")
      authorizations.asScala.toList shouldBe List("Bearer token-1", "Bearer token-2")
    }
  }

  it should "return the token provider's failure as it was, before any request" in {
    val requests = new AtomicInteger()
    LocalProviderTestServer.withServer("/") { (exchange: HttpExchange) =>
      requests.incrementAndGet()
      LocalProviderTestServer.sendJsonResponse(exchange, 200, LocalProviderTestServer.openAICompletion("hi"))
    } { baseUrl =>
      val failing = new AccessTokenProvider {
        def token(): Result[String]            = Left(AuthenticationError("azure", "no managed identity"))
        def invalidate(rejected: String): Unit = ()
      }
      val client = OpenAIClient(AzureConfig.keyless(baseUrl, "my-deploy", failing)).value
      val error  = client.complete(hello, CompletionOptions()).left.value
      client.close()
      error shouldBe an[AuthenticationError]
      error.message should include("no managed identity")
      requests.get() shouldBe 0
    }
  }

  "AzureConfig.withTokenProvider" should "clear the API key, and None go back to it" in {
    val keyed   = AzureConfig("https://r.openai.azure.com", "k", "gpt-4o")
    val tokens  = new CountingTokens
    val keyless = keyed.withTokenProvider(tokens)
    keyless.apiKey shouldBe ""
    keyless.tokenProvider shouldBe Some(tokens)
    keyless.withTokenProvider(None).tokenProvider shouldBe None
    keyed.toString should include("apiKey=")
    AzureConfig("e", "k", "m", "v", 1000, 100, keyed.timeouts).tokenProvider shouldBe None
    AzureConfig("e", "k", "m", "v", 1000, 100, keyed.timeouts, Some(tokens)).apiKey shouldBe ""
  }

  "OpenAIEmbeddingProvider with a token provider" should "send each token as the bearer and drop a rejected one" in {
    val authorizations = new ConcurrentLinkedQueue[String]()
    val reply          = """{"data":[{"embedding":[0.1,0.2]}],"usage":{"prompt_tokens":1,"total_tokens":1}}"""
    LocalProviderTestServer.withServer("/openai/v1/embeddings") { (exchange: HttpExchange) =>
      exchange.getRequestBody.readAllBytes()
      val authorization = exchange.getRequestHeaders.getFirst("Authorization")
      authorizations.add(authorization)
      if (authorization == "Bearer token-1") LocalProviderTestServer.sendJsonResponse(exchange, 401, "{}")
      else LocalProviderTestServer.sendJsonResponse(exchange, 200, reply)
    } { baseUrl =>
      val tokens   = new CountingTokens
      val config   = EmbeddingProviderConfig(s"$baseUrl/openai/v1", "my-embedding-deployment", "")
      val provider = OpenAIEmbeddingProvider.fromConfig(config, tokens)
      val request  = EmbeddingRequest(Seq("a"), EmbeddingModelConfig("my-embedding-deployment", 2))
      provider.embed(request).isLeft shouldBe true
      provider.embed(request).map(_.embeddings) shouldBe Right(Seq(Vector(0.1, 0.2)))
      tokens.invalidated.asScala.toList shouldBe List("token-1")
      authorizations.asScala.toList shouldBe List("Bearer token-1", "Bearer token-2")
    }
  }
}
