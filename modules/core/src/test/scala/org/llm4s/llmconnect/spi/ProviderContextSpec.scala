package org.llm4s.llmconnect.spi

import org.llm4s.http.{ HttpRequest, HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, EmbeddingModelConfig, EmbeddingProviderConfig }
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.{ EmbeddingClient, EmbeddingClientOptions, LlmClientOptions }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.atomic.AtomicInteger

/**
 * The context values the descriptors' `buildConfig` and `build` take (#2811): what each carries, and that
 * `EmbeddingClientOptions.httpClient` reaches an embedding provider through `EmbeddingClient.from`.
 */
class ProviderContextSpec extends AnyWordSpec with Matchers with EitherValues:

  private val registry: ModelRegistryService = ModelRegistryService.fromModels(Nil)

  /** Counts the requests sent through it and answers each `200`. */
  final private class CountingHttpClient extends Llm4sHttpClient:
    val sent = new AtomicInteger
    def send(request: HttpRequest): Result[StreamingHttpResponse] =
      sent.incrementAndGet()
      Right(HttpResponse(200, "{}").toStreaming)

  "ProviderConfigContext" should {

    "take the resolver in scope, which with only a registry in scope is the one over it" in {
      given ModelRegistryService = registry
      val context                = ProviderConfigContext.of("main")
      context.providerName shouldBe "main"
      context.contextWindows should not be null
    }

    "carry the resolver and name it is given, and change one at a time" in {
      val first   = ContextWindowResolver(registry)
      val second  = ContextWindowResolver(registry)
      val context = ProviderConfigContext("main", first)
      (context.contextWindows should be).theSameInstanceAs(first)
      context.withProviderName("other").providerName shouldBe "other"
      (context.withProviderName("other").contextWindows should be).theSameInstanceAs(first)
      (context.withContextWindows(second).contextWindows should be).theSameInstanceAs(second)
    }
  }

  "ProviderBuildContext" should {

    "default to the default options and take the registry in scope" in {
      given ModelRegistryService = registry
      val context                = ProviderBuildContext.of()
      context.options shouldBe LlmClientOptions.default
      (context.modelRegistry should be).theSameInstanceAs(registry)
      context.httpClient shouldBe None
    }

    "read metrics, exchange logging and the HTTP client from its options" in {
      val http    = new CountingHttpClient
      val options = LlmClientOptions(metrics = MetricsCollector.noop).withHttpClient(http)
      val context = ProviderBuildContext(options, registry)
      context.metrics shouldBe options.metrics
      context.exchangeLogging shouldBe options.exchangeLogging
      context.httpClient shouldBe Some(http)
      context.withOptions(LlmClientOptions.default).httpClient shouldBe None
      val other = ModelRegistryService.fromModels(Nil)
      (context.withModelRegistry(other).modelRegistry should be).theSameInstanceAs(other)
    }
  }

  "EmbeddingConfigContext" should {

    "have no model override unless given one" in {
      EmbeddingConfigContext().modelOverride shouldBe None
      EmbeddingConfigContext(Some("m")).modelOverride shouldBe Some("m")
      EmbeddingConfigContext().withModelOverride("m").modelOverride shouldBe Some("m")
      EmbeddingConfigContext(Some("m")).withModelOverride(None).modelOverride shouldBe None
    }
  }

  "EmbeddingBuildContext" should {

    "have no HTTP client unless its options supply one" in {
      val http = new CountingHttpClient
      EmbeddingBuildContext().httpClient shouldBe None
      EmbeddingBuildContext().options shouldBe EmbeddingClientOptions.default
      EmbeddingBuildContext(EmbeddingClientOptions().withHttpClient(http)).httpClient shouldBe Some(http)
      EmbeddingBuildContext()
        .withOptions(EmbeddingClientOptions(Some(http)).withHttpClient(None))
        .httpClient shouldBe None
    }
  }

  "EmbeddingClient.from" should {

    /** A provider that sends one request per `embed` through the client its context supplies. */
    object HttpEmbeddings extends EmbeddingProviderDescriptor:
      val id: ProviderId                  = ProviderId("httpembeddings")
      val configSpec: EmbeddingConfigSpec = EmbeddingConfigSpec()
      def build(config: EmbeddingProviderConfig, context: EmbeddingBuildContext): Result[EmbeddingProvider] =
        Right(
          new EmbeddingProvider:
            def embed(request: EmbeddingRequest): Result[EmbeddingResponse] =
              context.httpClient.foreach(_.post(config.baseUrl, Map.empty, "{}"))
              Right(EmbeddingResponse(embeddings = request.input.map(_ => Vector(0.5))))
        )

    given ModelRegistryService = registry
    given ProviderRegistry     = ProviderRegistry.ofEmbeddings(HttpEmbeddings)

    val config  = EmbeddingProviderConfig("https://httpembeddings.invalid", "m", Secret("k"))
    val request = EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("m", 1))

    "hand the caller's HTTP client to the provider (#2811)" in {
      val http   = new CountingHttpClient
      val client = EmbeddingClient.from("httpembeddings", config, EmbeddingClientOptions().withHttpClient(http))
      client.value.embed(request).isRight shouldBe true
      http.sent.get shouldBe 1
    }
  }
