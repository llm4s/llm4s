package org.llm4s.llmconnect.provider

import org.llm4s.config.{ AnthropicConfigKeys, CredentialsRoundTrip }
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.config.{ AnthropicConfig, ContextWindowResolver }
import org.llm4s.llmconnect.contract.LLMClientContractBehaviors
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.{ FixtureChatConfig, LocalProviderTestServer }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-anthropic` registers itself, and what it registers works.
 *
 * This is Anthropic's row of core's `BuiltinProvidersSpec`, which left with the provider
 * (#1132), plus the part that only a carved module has to prove: that depending on it is
 * enough - the services entry is found and the descriptor arrives.
 */
class Llm4sAnthropicModuleSpec extends AnyWordSpec with Matchers with LLMClientContractBehaviors:

  private val registryService         = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  /** A section carrying every field the provider asks for. */
  private val section: NamedProviderConfig =
    NamedProviderConfig(
      provider = AnthropicProvider.id,
      model = ModelName("claude-sonnet-4-5"),
      baseUrl = AnthropicProvider.configSpec.defaultBaseUrl.map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
      organization = None,
      endpoint = None,
      apiVersion = None
    )

  "the llm4s-anthropic services entry" should {

    "be discovered, contributing anthropic" in {
      val registry = ProviderRegistry.discover()

      registry.get(ProviderId("anthropic")) shouldBe Right(AnthropicProvider)
      registry.report.modules.map(_.moduleClass) should contain(classOf[Llm4sAnthropicModule].getName)
    }

    "contribute no embedding provider" in {
      ProviderRegistry.default.findEmbedding(ProviderId("anthropic")) shouldBe None
    }

    "be the only module that supplies anthropic" in {
      // Core held these in `BuiltinProviders` until #1132 deleted it; nothing but this
      // module may supply them now.
      val modules = ProviderRegistry.default.report.modules
      Seq("anthropic").foreach { id =>
        modules.filter(_.providerIds.contains(id)).map(_.moduleClass) shouldBe Seq(
          classOf[Llm4sAnthropicModule].getName
        )
      }
    }

    "be registrable explicitly where discovery cannot run" in {
      val registry = ProviderRegistry.ofModules(new Llm4sAnthropicModule)

      registry.get(ProviderId("anthropic")) shouldBe Right(AnthropicProvider)
    }
  }

  "the llm4s-anthropic provider" should {

    "default its base URL to the Anthropic API" in {
      AnthropicProvider.configSpec.defaultBaseUrl shouldBe Some(AnthropicConfig.DEFAULT_BASE_URL)
    }

    "build an AnthropicConfig and an AnthropicClient from a config section" in {
      val result =
        AnthropicProvider.buildConfig("test-instance", section).flatMap { config =>
          config.getClass.getSimpleName shouldBe "AnthropicConfig"
          AnthropicProvider.buildClient(config, LlmClientOptions.default)
        }

      result.map(_.getClass.getSimpleName) shouldBe Right("AnthropicClient")
    }

    "refuse a config belonging to another provider" in {
      val foreign = FixtureChatConfig("k", "fixture-model")

      AnthropicProvider.buildClient(foreign, LlmClientOptions.default) match
        case Left(error) => error.message should include("Invalid config type FixtureChatConfig for provider anthropic")
        case Right(client) => fail(s"anthropic accepted a FixtureChatConfig and built $client")
    }

    "declare a model lister" in {
      AnthropicProvider.modelLister shouldBe defined
    }

    "actually stream, proving the declared streaming flag" in {
      AnthropicProvider.features.streaming shouldBe true

      val events = Seq(
        "message_start" -> (
          """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",""" +
            """"content":[],"model":"claude-sonnet-4-5","stop_reason":null,"stop_sequence":null,""" +
            """"usage":{"input_tokens":8,"output_tokens":0}}}"""
        ),
        "content_block_start" ->
          """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" ->
          """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}""",
        "content_block_stop" -> """{"type":"content_block_stop","index":0}""",
        "message_delta" -> (
          """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},""" +
            """"usage":{"output_tokens":1}}"""
        ),
        "message_stop" -> """{"type":"message_stop"}"""
      )
      val body = events.map { case (event, data) => s"event: $event\ndata: $data\n\n" }.mkString

      LocalProviderTestServer.withServer("/v1/messages") { exchange =>
        LocalProviderTestServer.sendSseResponse(exchange, body)
      } { baseUrl =>
        val client =
          AnthropicProvider
            .buildConfig("test-instance", section.copy(baseUrl = Some(BaseUrl(baseUrl))))
            .flatMap(config => AnthropicProvider.buildClient(config, LlmClientOptions.default))
            .getOrElse(fail("anthropic failed to build a streaming client"))

        assertHonoursStreaming(client)
        client.close()
      }
    }
  }

  "the llm4s-anthropic reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind ANTHROPIC_API_KEY to llm4s.credentials.anthropic.apiKey" in {
      AnthropicProvider.configSpec.apiKeyEnv shouldBe Seq(AnthropicConfigKeys.ANTHROPIC_API_KEY)
      CredentialsRoundTrip.chatBindings(AnthropicProvider) shouldBe
        Map("ANTHROPIC_API_KEY" -> Right(Some("key-from-ANTHROPIC_API_KEY")))
    }

    "let a section's own key win over ANTHROPIC_API_KEY" in {
      CredentialsRoundTrip.chatSectionKey(
        "anthropic",
        Map("ANTHROPIC_API_KEY" -> "sk-shared", "ANTHROPIC_TEAM_API_KEY" -> "sk-team"),
        "apiKey = ${?ANTHROPIC_TEAM_API_KEY}"
      ) shouldBe Right(Some("sk-team"))
    }
  }
