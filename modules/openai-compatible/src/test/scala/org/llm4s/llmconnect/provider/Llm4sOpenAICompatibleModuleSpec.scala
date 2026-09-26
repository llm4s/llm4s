package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.config.ContextWindowResolver
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-openai-compatible` registers itself, and what it registers works.
 *
 * These are the DeepSeek, Z.ai and OpenRouter rows of core's `BuiltinProvidersSpec`, which
 * left with the providers (#1132), plus the generic `openai-compatible` provider and the part
 * that only a carved module has to prove: that depending on it is enough - the services
 * entry is found and the descriptors arrive.
 */
class Llm4sOpenAICompatibleModuleSpec extends AnyWordSpec with Matchers:

  private val registryService         = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  /** Descriptor, the config class it builds, and the client class that config produces. */
  private val expectations: Seq[(ProviderDescriptor, String, String)] = Seq(
    (OpenAICompatibleProvider, "OpenAICompatibleConfig", "OpenAICompatibleClient"),
    (DeepSeekProvider, "DeepSeekConfig", "DeepSeekClient"),
    (ZaiProvider, "ZaiConfig", "ZaiClient"),
    (OpenRouterProvider, "OpenAIConfig", "OpenRouterClient")
  )

  private val chatIds = expectations.map(_._1.id.asString)

  /** A section carrying every field any of the providers asks for. */
  private def section(descriptor: ProviderDescriptor): NamedProviderConfig =
    NamedProviderConfig(
      provider = descriptor.id,
      model = ModelName("test-model"),
      baseUrl = descriptor.configSpec.defaultBaseUrl.orElse(Some("http://localhost:8000/v1")).map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
      organization = None,
      endpoint = None,
      apiVersion = None
    )

  "the llm4s-openai-compatible services entry" should {

    "be discovered, contributing every provider the module holds" in {
      val registry = ProviderRegistry.discover()

      expectations.foreach((descriptor, _, _) => registry.get(descriptor.id) shouldBe Right(descriptor))
      registry.report.modules.map(_.moduleClass) should contain(classOf[Llm4sOpenAICompatibleModule].getName)
    }

    "contribute no embedding providers" in {
      new Llm4sOpenAICompatibleModule().embeddingProviders shouldBe empty
    }

    "not be part of core's built-in set, which no longer ships them" in {
      chatIds.foreach(id => ProviderRegistry.builtin.find(ProviderId(id)) shouldBe None)
    }

    "be registrable explicitly where discovery cannot run" in {
      val registry = ProviderRegistry.builtin.withModule(new Llm4sOpenAICompatibleModule)

      expectations.foreach((descriptor, _, _) => registry.get(descriptor.id) shouldBe Right(descriptor))
    }
  }

  "the llm4s-openai-compatible providers" should {

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
      OpenAICompatibleProvider.modelLister shouldBe defined
      DeepSeekProvider.modelLister shouldBe defined
      OpenRouterProvider.modelLister shouldBe defined
      // Z.ai had no lister in core either.
      ZaiProvider.modelLister shouldBe None
    }
  }
