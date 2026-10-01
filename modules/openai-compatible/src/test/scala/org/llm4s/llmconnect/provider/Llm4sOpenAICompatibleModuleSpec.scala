package org.llm4s.llmconnect.provider

import org.llm4s.config.{ CredentialsRoundTrip, OpenAICompatibleConfigKeys }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.config.ContextWindowResolver
import org.llm4s.llmconnect.model.{ Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.FixtureChatConfig
import org.llm4s.testutil.LocalProviderTestServer.{ openAISseBody, sendSseResponse, withServer }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable.ListBuffer

/**
 * `llm4s-openai-compatible` registers itself, and what it registers works.
 *
 * These are the DeepSeek, Z.ai, OpenRouter, Mistral and Cohere rows of core's `BuiltinProvidersSpec`,
 * which left with the providers (#1132). Mistral's and Cohere's asserted `streaming = false`
 * (#925); on the shared client they stream, plus the generic `openai-compatible` provider and the part
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
    (OpenRouterProvider, "OpenAIConfig", "OpenRouterClient"),
    (MistralProvider, "MistralConfig", "MistralClient"),
    (CohereProvider, "CohereConfig", "CohereClient")
  )

  private val chatIds = expectations.map(_._1.id.asString)

  /** A section carrying every field any of the providers asks for. */
  private def section(descriptor: ProviderDescriptor): NamedProviderConfig =
    NamedProviderConfig(
      provider = descriptor.id,
      model = ModelName("test-model"),
      baseUrl = descriptor.configSpec.defaultBaseUrl.orElse(Some("http://localhost:8000/v1")).map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
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

    "be the only module that supplies them" in {
      // Core held these in `BuiltinProviders` until #1132 deleted it; nothing but this
      // module may supply them now.
      val modules = ProviderRegistry.default.report.modules
      chatIds.foreach { id =>
        modules.filter(_.providerIds.contains(id)).map(_.moduleClass) shouldBe Seq(
          classOf[Llm4sOpenAICompatibleModule].getName
        )
      }
    }

    "be registrable explicitly where discovery cannot run" in {
      val registry = ProviderRegistry.ofModules(new Llm4sOpenAICompatibleModule)

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

    "declare a model lister where the provider has one" in {
      OpenAICompatibleProvider.modelLister shouldBe defined
      DeepSeekProvider.modelLister shouldBe defined
      OpenRouterProvider.modelLister shouldBe defined
      MistralProvider.modelLister shouldBe defined
      // Z.ai and Cohere had no lister in core either.
      ZaiProvider.modelLister shouldBe None
      CohereProvider.modelLister shouldBe None
    }
  }

  "a client built by each openai-compatible descriptor" should {

    "actually stream, not silently fall back to complete()" in {
      expectations.foreach { (descriptor, _, _) =>
        withClue(s"${descriptor.id.asString}: ") {
          withServer("/")(exchange => sendSseResponse(exchange, openAISseBody(Seq("Hi")))) { baseUrl =>
            val client = descriptor
              .buildConfig("test-instance", section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl))))
              .flatMap(config => descriptor.buildClient(config, LlmClientOptions.default))
              .getOrElse(fail(s"${descriptor.id.asString} failed to build a client for the streaming proof"))

            val chunks = ListBuffer.empty[StreamedChunk]
            val result = client.streamComplete(Conversation(Seq(UserMessage("Hello"))), onChunk = chunks += _)

            result.isRight shouldBe true
            chunks should not be empty
          }
        }
      }
    }
  }

  "the llm4s-openai-compatible reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind each vendor's variable to its shared llm4s.credentials key" in {
      val expected = Map(
        DeepSeekProvider   -> OpenAICompatibleConfigKeys.DEEPSEEK_API_KEY,
        ZaiProvider        -> OpenAICompatibleConfigKeys.ZAI_API_KEY,
        OpenRouterProvider -> OpenAICompatibleConfigKeys.OPENROUTER_API_KEY,
        MistralProvider    -> OpenAICompatibleConfigKeys.MISTRAL_API_KEY,
        CohereProvider     -> OpenAICompatibleConfigKeys.COHERE_API_KEY
      )
      expected.foreach { (descriptor, variable) =>
        withClue(s"${descriptor.id.asString}: ") {
          descriptor.configSpec.apiKeyEnv shouldBe Seq(variable)
          CredentialsRoundTrip.chatBindings(descriptor) shouldBe Map(variable -> Right(Some(s"key-from-$variable")))
        }
      }
    }

    "bind nothing for the generic openai-compatible provider, which has no vendor" in {
      OpenAICompatibleProvider.configSpec.apiKeyEnv shouldBe empty
      CredentialsRoundTrip.chatSectionKey(
        "openai-compatible",
        Map("OPENAI_COMPATIBLE_API_KEY" -> "k", "OPENAI_API_KEY" -> "k"),
        """baseUrl = "http://localhost:8000/v1""""
      ) shouldBe Right(None)
    }
  }
