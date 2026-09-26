package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.config.{ ContextWindowResolver, DeepSeekConfig }
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
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
class Llm4sGeminiModuleSpec extends AnyWordSpec with Matchers:

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
      organization = Some("europe-west4"),
      endpoint = Some("my-gcp-project"),
      apiVersion = None
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

    "not be part of core's built-in set, which no longer ships Gemini or Vertex AI" in {
      ProviderRegistry.builtin.find(ProviderId("gemini")) shouldBe None
      ProviderRegistry.builtin.find(ProviderId("vertexai")) shouldBe None
    }

    "be registrable explicitly where discovery cannot run" in {
      val registry = ProviderRegistry.builtin.withModule(new Llm4sGeminiModule)

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
      val foreign = DeepSeekConfig("k", "deepseek-chat", DeepSeekConfig.DEFAULT_BASE_URL, 128000, 8192)

      expectations.foreach { (descriptor, _, _) =>
        descriptor.buildClient(foreign, LlmClientOptions.default) match
          case Left(error) =>
            error.message should include(s"Invalid config type DeepSeekConfig for provider ${descriptor.id.asString}")
          case Right(client) =>
            fail(s"${descriptor.id.asString} accepted a DeepSeekConfig and built $client")
      }
    }

    "declare streaming, and a model lister for Gemini only" in {
      GeminiProvider.features.streaming shouldBe true
      VertexAIProvider.features.streaming shouldBe true
      GeminiProvider.modelLister shouldBe defined
      VertexAIProvider.modelLister shouldBe None
    }
  }
