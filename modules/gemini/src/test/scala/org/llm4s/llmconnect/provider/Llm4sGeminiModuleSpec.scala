package org.llm4s.llmconnect.provider

import org.llm4s.config.GeminiConfigKeys
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient, StreamingHttpResponse }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{ ContextWindowResolver, VertexAIConfig }
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendSseResponse, withServer }
import org.llm4s.testkit.{ CredentialsRoundTrip, ProviderModuleChecks }
import org.llm4s.types.ProviderModelTypes.*
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * `llm4s-gemini` registers itself, and what it registers works.
 *
 * These are Gemini's and Vertex AI's rows of core's `BuiltinProvidersSpec`, which left
 * with the providers (#1132), plus the part that only a carved module has to prove: that
 * depending on it is enough - the services entry is found, both descriptors arrive, and
 * the `google` and `vertex` spellings still resolve.
 */
class Llm4sGeminiModuleSpec extends AnyWordSpec with Matchers with MockFactory with ProviderModuleChecks:

  private val registryService         = ProviderModuleChecks.defaultModelRegistry
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  /** One SSE data line, standing in for the Gemini API's streaming response. */
  private val geminiSseBody: String = """data: {"candidates":[{"content":{"parts":[{"text":"Hi"}]}}]}""" + "\n\n"

  /**
   * A `VertexAIClient` backed by a mock HTTP client. Vertex AI's base URL is derived from
   * `location`, not configurable, so unlike Gemini it cannot be pointed at a stub server
   * through the descriptor - the client is built directly, the same way
   * `VertexAIClientHttpSpec` does, stubbing both the oauth2 token endpoint and the GCE
   * metadata fallback so the test is environment-agnostic.
   */
  private def vertexClientWithStubbedAuth(config: VertexAIConfig): VertexAIClient = {
    val tokenBody = """{"access_token":"ya29.test-token","expires_in":3600}"""
    val sseBody   = """data: {"candidates":[{"content":{"parts":[{"text":"Hi"}]}}]}""" + "\n\n"
    val mockHttp  = stub[Llm4sHttpClient]
    (mockHttp.post _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.get _).when(*, *, *, *).returns(Right(HttpResponse(200, tokenBody, Map.empty)))
    (mockHttp.postStream _)
      .when(*, *, *, *)
      .returns(Right(StreamingHttpResponse(200, new ByteArrayInputStream(sseBody.getBytes(StandardCharsets.UTF_8)))))
    new VertexAIClient(config, org.llm4s.metrics.MetricsCollector.noop, ProviderExchangeLogging.Disabled, mockHttp)
  }

  /** Descriptor, the config class it builds, and the client class that config produces. */
  private val expectations: Seq[(ProviderDescriptor, String, String)] = Seq(
    (GeminiProvider, "GeminiConfig", "GeminiClient"),
    (VertexAIProvider, "VertexAIConfig", "VertexAIClient")
  )

  /** A section carrying every field either provider asks for. */
  private def section(descriptor: ProviderDescriptor): NamedProviderConfig =
    NamedProviderConfig(
      provider = descriptor.id,
      model = ModelName("gemini-2.0-flash"),
      baseUrl = descriptor.configSpec.defaultBaseUrl.map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
      extras = Map(VertexAIProvider.ProjectKey -> "my-gcp-project", VertexAIProvider.LocationKey -> "europe-west4")
    )

  "the llm4s-gemini services entry" should {

    "be discovered, the only supplier of gemini and vertexai, and registrable explicitly" in {
      assertModule(new Llm4sGeminiModule)
    }

    "keep the historical provider spellings" in {
      ProviderRegistry.default.canonicalId("google") shouldBe ProviderId("gemini")
      ProviderRegistry.default.canonicalId("vertex") shouldBe ProviderId("vertexai")
    }

    "contribute no embedding provider" in {
      new Llm4sGeminiModule().embeddingProviders shouldBe empty
    }
  }

  "the llm4s-gemini providers" should {

    "build their own config from a config section" in {
      expectations.foreach { (descriptor, configClass, _) =>
        descriptor.buildConfig("test-instance", section(descriptor)) match
          case Right(config) => config.getClass.getSimpleName shouldBe configClass
          case Left(error)   => fail(s"${descriptor.id.asString} failed to build a config: ${error.message}")
      }
    }

    "build their own client from the config they produced" in {
      expectations.foreach { (descriptor, _, clientClass) =>
        assertBuildsClient(descriptor, section(descriptor)).getClass.getSimpleName shouldBe clientClass
      }
    }

    "refuse a config belonging to another provider" in {
      expectations.foreach((descriptor, _, _) => assertRefusesForeignConfig(descriptor))
    }

    "declare a model lister for Gemini only" in {
      GeminiProvider.modelLister shouldBe defined
      VertexAIProvider.modelLister shouldBe None
    }
  }

  "a client built by the gemini descriptor" should {

    "actually stream, not silently fall back to complete()" in {
      withServer("/")(exchange => sendSseResponse(exchange, geminiSseBody)) { baseUrl =>
        assertStreams(
          assertBuildsClient(GeminiProvider, section(GeminiProvider).withBaseUrl(Some(BaseUrl(baseUrl))))
        )
      }
    }
  }

  "a client built by the vertexai descriptor" should {

    "actually stream, not silently fall back to complete()" in {
      // No apiKey: that field doubles as a credential file path for Vertex, and a
      // nonexistent one would fail auth before the mocked HTTP client is ever reached.
      val config = VertexAIProvider.buildConfig("test-instance", section(VertexAIProvider).withApiKey(None)) match
        case Right(c: VertexAIConfig) => c
        case other                    => fail(s"expected a VertexAIConfig, got $other")

      assertStreams(vertexClientWithStubbedAuth(config))
    }
  }

  "the llm4s-gemini reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind GOOGLE_API_KEY and GEMINI_API_KEY to llm4s.credentials.gemini.apiKey" in {
      GeminiProvider.configSpec.apiKeyEnv shouldBe Seq(GeminiConfigKeys.GOOGLE_API_KEY, GeminiConfigKeys.GEMINI_API_KEY)
      assertCredentialBindings(GeminiProvider)
    }

    "prefer GOOGLE_API_KEY when both are set, as Google's SDKs do" in {
      CredentialsRoundTrip.chatSectionKey(
        "gemini",
        Map("GOOGLE_API_KEY" -> "from-google", "GEMINI_API_KEY" -> "from-gemini")
      ) shouldBe Right(Some("from-google"))
    }

    "give the google alias the gemini key" in {
      CredentialsRoundTrip.chatSectionKey("google", Map("GEMINI_API_KEY" -> "from-gemini")) shouldBe
        Right(Some("from-gemini"))
    }

    "bind nothing for Vertex AI, which authenticates with OAuth2" in {
      VertexAIProvider.configSpec.apiKeyEnv shouldBe empty
      CredentialsRoundTrip.chatSectionKey(
        "vertexai",
        Map("GOOGLE_API_KEY" -> "k", "GEMINI_API_KEY" -> "k"),
        """project = "p""""
      ) shouldBe Right(None)
    }
  }
