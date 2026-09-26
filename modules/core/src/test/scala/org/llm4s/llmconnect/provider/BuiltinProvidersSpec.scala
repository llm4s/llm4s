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
 * Round-trips every provider `llm4s-core` registers: config section →
 * `ProviderConfig` → `LLMClient`.
 *
 * Dispatch used to be `match` expressions over a closed `enum`, so the compiler
 * checked that a new provider had been handled everywhere. The registry is a
 * runtime lookup and the compiler cannot; this spec is the replacement for that
 * guarantee, and a new built-in provider must appear in [[expectations]] or
 * fail here. A provider that moves to its own module takes its round trip with it
 * (`Llm4sOllamaModuleSpec` in `llm4s-ollama`, `Llm4sGeminiModuleSpec` in `llm4s-gemini`,
 * `Llm4sAnthropicModuleSpec` in `llm4s-anthropic`, `Llm4sOpenAIModuleSpec` in `llm4s-openai`,
 * `Llm4sOpenAICompatibleModuleSpec` in `llm4s-openai-compatible`).
 */
class BuiltinProvidersSpec extends AnyWordSpec with Matchers:

  private val registryService         = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  /** Descriptor, the config class it builds, and the client class that config produces. */
  private val expectations: Seq[(ProviderDescriptor, String, String)] = Seq(
    (CohereProvider, "CohereConfig", "CohereClient"),
    (MistralProvider, "MistralConfig", "MistralClient")
  )

  /** A section carrying every field any built-in provider asks for. */
  private def section(descriptor: ProviderDescriptor): NamedProviderConfig =
    NamedProviderConfig(
      provider = descriptor.id,
      model = ModelName("test-model"),
      baseUrl = descriptor.configSpec.defaultBaseUrl.map(BaseUrl(_)).orElse(Some(BaseUrl("http://localhost:11434"))),
      apiKey = Some(ApiKey("test-key")),
      organization = Some("test-org"),
      endpoint = Some("test-endpoint"),
      apiVersion = None
    )

  "every provider built into llm4s-core" should {

    "be registered under its own id" in {
      expectations.foreach { (descriptor, _, _) =>
        ProviderRegistry.default.get(descriptor.id) shouldBe Right(descriptor)
      }
    }

    "build its own config from a config section" in {
      expectations.foreach { (descriptor, configClass, _) =>
        descriptor.buildConfig("test-instance", section(descriptor)) match
          case Right(config) => config.getClass.getSimpleName shouldBe configClass
          case Left(error)   => fail(s"${descriptor.id.asString} failed to build a config: ${error.message}")
      }
    }

    "build its own client from the config it produced" in {
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
      // The test fixture's config belongs to no built-in, so every one of them must refuse it.
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

    "declare whether it implements streaming" in {
      // Cohere and Mistral return a Left from streamComplete (#925). The point of putting this
      // in the descriptor is that it is visible without making a call and reading the error.
      CohereProvider.features.streaming shouldBe false
      MistralProvider.features.streaming shouldBe false

      expectations
        .map(_._1)
        .filterNot(descriptor => descriptor == CohereProvider || descriptor == MistralProvider)
        .foreach(descriptor => withClue(s"${descriptor.id.asString}: ")(descriptor.features.streaming shouldBe true))
    }
  }
