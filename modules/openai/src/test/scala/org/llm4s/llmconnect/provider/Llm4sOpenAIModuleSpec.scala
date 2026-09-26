package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver }
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-openai` registers itself, and what it registers works.
 *
 * These are the OpenAI, Requesty and Azure rows of core's `BuiltinProvidersSpec`, which left
 * with the providers (#1132), plus the part that only a carved module has to prove: that
 * depending on it is enough - the services entry is found and the descriptors arrive.
 */
class Llm4sOpenAIModuleSpec extends AnyWordSpec with Matchers:

  private val registryService         = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  /** Descriptor, the config class it builds, and the client class that config produces. */
  private val expectations: Seq[(ProviderDescriptor, String, String)] = Seq(
    (OpenAIProvider, "OpenAIConfig", "OpenAIClient"),
    (RequestyProvider, "OpenAIConfig", "OpenAIClient"),
    (AzureProvider, "AzureConfig", "OpenAIClient")
  )

  private val chatIds = Seq("openai", "azure", "requesty")

  /** A section carrying every field any of the three providers asks for. */
  private def section(descriptor: ProviderDescriptor): NamedProviderConfig =
    NamedProviderConfig(
      provider = descriptor.id,
      model = ModelName("test-model"),
      baseUrl = descriptor.configSpec.defaultBaseUrl.map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
      organization = Some("test-org"),
      endpoint = Some("https://test-resource.openai.azure.com"),
      apiVersion = Some(AzureConfig.DEFAULT_API_VERSION)
    )

  "the llm4s-openai services entry" should {

    "be discovered, contributing openai, azure and requesty" in {
      val registry = ProviderRegistry.discover()

      expectations.foreach((descriptor, _, _) => registry.get(descriptor.id) shouldBe Right(descriptor))
      registry.report.modules.map(_.moduleClass) should contain(classOf[Llm4sOpenAIModule].getName)
    }

    "contribute the OpenAI embedding provider under the same id as the chat provider" in {
      // One id naming both a chat and an embedding provider - the overlap without containment
      // that is why the two descriptors are separate traits.
      ProviderRegistry.default.findEmbedding(ProviderId("openai")) shouldBe Some(OpenAIEmbeddingProvider)
      ProviderRegistry.default.ids should contain("openai")
      ProviderRegistry.default.embeddingIds should contain("openai")
    }

    "contribute no embedding provider for azure or requesty" in {
      ProviderRegistry.default.findEmbedding(ProviderId("azure")) shouldBe None
      ProviderRegistry.default.findEmbedding(ProviderId("requesty")) shouldBe None
    }

    "not be part of core's built-in set, which no longer ships them" in {
      chatIds.foreach(id => ProviderRegistry.builtin.find(ProviderId(id)) shouldBe None)
      ProviderRegistry.builtin.findEmbedding(ProviderId("openai")) shouldBe None
    }

    "be registrable explicitly where discovery cannot run" in {
      val registry = ProviderRegistry.builtin.withModule(new Llm4sOpenAIModule)

      expectations.foreach((descriptor, _, _) => registry.get(descriptor.id) shouldBe Right(descriptor))
      registry.findEmbedding(ProviderId("openai")) shouldBe Some(OpenAIEmbeddingProvider)
    }
  }

  "the llm4s-openai providers" should {

    "default their base URLs to the OpenAI API and the Requesty router" in {
      OpenAIProvider.configSpec.defaultBaseUrl shouldBe Some(OpenAIProvider.DEFAULT_BASE_URL)
      RequestyProvider.configSpec.defaultBaseUrl shouldBe Some(RequestyProvider.DEFAULT_BASE_URL)
      AzureProvider.configSpec.defaultBaseUrl shouldBe None
    }

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

        result match
          case Right(client) => client.getClass.getSimpleName shouldBe clientClass
          case Left(error)   => fail(s"${descriptor.id.asString} failed to build a client: ${error.message}")
      }
    }

    "default Azure's API version when the section sets none" in {
      AzureProvider.buildConfig("test-instance", section(AzureProvider).copy(apiVersion = None)) match
        case Right(azure: AzureConfig) => azure.apiVersion shouldBe AzureConfig.DEFAULT_API_VERSION
        case other                     => fail(s"Expected AzureConfig, got $other")
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

    "declare streaming, and a model lister where the provider has one" in {
      expectations.foreach { (descriptor, _, _) =>
        withClue(s"${descriptor.id.asString}: ")(descriptor.features.streaming shouldBe true)
      }
      OpenAIProvider.modelLister shouldBe defined
      RequestyProvider.modelLister shouldBe defined
    }
  }
