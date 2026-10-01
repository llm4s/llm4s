package org.llm4s.llmconnect.provider

import org.llm4s.config.{ CredentialsRoundTrip, GeminiConfigKeys }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.config.ContextWindowResolver
import org.llm4s.llmconnect.contract.LLMClientContractBehaviors
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.{ FixtureChatConfig, LocalProviderTestServer }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-gemini` registers itself, and what it registers works.
 *
 * These are Gemini's and Vertex AI's rows of core's `BuiltinProvidersSpec`, which left
 * with the providers (#1132), plus the part that only a carved module has to prove: that
 * depending on it is enough - the services entry is found, both descriptors arrive, and
 * the `google` and `vertex` spellings still resolve.
 */
class Llm4sGeminiModuleSpec extends AnyWordSpec with Matchers with LLMClientContractBehaviors:

  private val registryService         = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

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
      organization = None,
      endpoint = None,
      apiVersion = None,
      extras = Map(VertexAIProvider.ProjectKey -> "my-gcp-project", VertexAIProvider.LocationKey -> "europe-west4")
    )

  "the llm4s-gemini services entry" should {

    "be discovered, contributing gemini and vertexai" in {
      val registry = ProviderRegistry.discover()

      registry.get(ProviderId("gemini")) shouldBe Right(GeminiProvider)
      registry.get(ProviderId("vertexai")) shouldBe Right(VertexAIProvider)
      registry.report.modules.map(_.moduleClass) should contain(classOf[Llm4sGeminiModule].getName)
    }

    "keep the historical provider spellings" in {
      ProviderRegistry.default.canonicalId("google") shouldBe ProviderId("gemini")
      ProviderRegistry.default.canonicalId("vertex") shouldBe ProviderId("vertexai")
    }

    "contribute no embedding provider" in {
      ProviderRegistry.default.findEmbedding(ProviderId("gemini")) shouldBe None
      ProviderRegistry.default.findEmbedding(ProviderId("vertexai")) shouldBe None
    }

    "be the only module that supplies gemini and vertexai" in {
      // Core held these in `BuiltinProviders` until #1132 deleted it; nothing but this
      // module may supply them now.
      val modules = ProviderRegistry.default.report.modules
      Seq("gemini", "vertexai").foreach { id =>
        modules.filter(_.providerIds.contains(id)).map(_.moduleClass) shouldBe Seq(classOf[Llm4sGeminiModule].getName)
      }
    }

    "be registrable explicitly where discovery cannot run" in {
      val registry = ProviderRegistry.ofModules(new Llm4sGeminiModule)

      registry.get(ProviderId("gemini")) shouldBe Right(GeminiProvider)
      registry.get(ProviderId("vertexai")) shouldBe Right(VertexAIProvider)
      registry.canonicalId("google") shouldBe ProviderId("gemini")
      registry.canonicalId("vertex") shouldBe ProviderId("vertexai")
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
        val result =
          descriptor.buildConfig("test-instance", section(descriptor)).flatMap { config =>
            descriptor.buildClient(config, LlmClientOptions.default)
          }

        result.map(_.getClass.getSimpleName) shouldBe Right(clientClass)
      }
    }

    "refuse a config belonging to another provider" in {
      val foreign = FixtureChatConfig("k", "fixture-model")

      expectations.foreach { (descriptor, _, _) =>
        descriptor.buildClient(foreign, LlmClientOptions.default) match
          case Left(error) =>
            error.message should include(
              s"Invalid config type FixtureChatConfig for provider ${descriptor.id.asString}"
            )
          case Right(client) =>
            fail(s"${descriptor.id.asString} accepted a FixtureChatConfig and built $client")
      }
    }

    "declare streaming, and a model lister for Gemini only" in {
      GeminiProvider.features.streaming shouldBe true
      VertexAIProvider.features.streaming shouldBe true
      GeminiProvider.modelLister shouldBe defined
      VertexAIProvider.modelLister shouldBe None
    }

    "actually stream, proving Gemini's declared streaming flag" in {
      val sseBody =
        """data: {"candidates":[{"content":{"parts":[{"text":"Hello"}]}}]}
          |data: {"candidates":[{"content":{"parts":[{"text":" world"}]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}
          |""".stripMargin

      LocalProviderTestServer.withServer("/") { exchange =>
        LocalProviderTestServer.sendSseResponse(exchange, sseBody)
      } { serverUrl =>
        val client =
          GeminiProvider
            .buildConfig("test-instance", section(GeminiProvider).copy(baseUrl = Some(BaseUrl(serverUrl))))
            .flatMap(config => GeminiProvider.buildClient(config, LlmClientOptions.default))
            .getOrElse(fail("gemini failed to build a streaming client"))

        assertHonoursStreaming(client)
        client.close()
      }
      // VertexAI's streaming is proven the same way at the client level, where a fake
      // bearer token and a mocked Llm4sHttpClient stand in for GCP OAuth that a local
      // stub server cannot satisfy: VertexAIClientHttpSpec, "VertexAIClient.streamComplete()
      // should parse SSE lines and accumulate into a Completion".
    }
  }

  "the llm4s-gemini reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind GOOGLE_API_KEY and GEMINI_API_KEY to llm4s.credentials.gemini.apiKey" in {
      GeminiProvider.configSpec.apiKeyEnv shouldBe Seq(GeminiConfigKeys.GOOGLE_API_KEY, GeminiConfigKeys.GEMINI_API_KEY)
      CredentialsRoundTrip.chatBindings(GeminiProvider) shouldBe Map(
        "GOOGLE_API_KEY" -> Right(Some("key-from-GOOGLE_API_KEY")),
        "GEMINI_API_KEY" -> Right(Some("key-from-GEMINI_API_KEY"))
      )
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
