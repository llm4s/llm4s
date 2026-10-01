package org.llm4s.llmconnect.provider

import org.llm4s.config.AnthropicConfigKeys
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.LocalProviderTestServer.{ sendSseResponse, withServer }
import org.llm4s.testkit.{ CredentialsRoundTrip, ProviderModuleChecks, ProviderTestConfig }
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
class Llm4sAnthropicModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  /** One Anthropic-shaped event stream, served by the shared stub SSE server. */
  private val streamingBody: String = {
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
    events.map { case (event, data) => s"event: $event\ndata: $data\n\n" }.mkString
  }

  /** A section carrying every field the provider asks for. */
  private val section: NamedProviderConfig =
    NamedProviderConfig(
      provider = AnthropicProvider.id,
      model = ModelName("claude-sonnet-4-5"),
      baseUrl = AnthropicProvider.configSpec.defaultBaseUrl.map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
    )

  "the llm4s-anthropic services entry" should {

    "be discovered, the only supplier of anthropic, and registrable explicitly" in {
      assertModule(new Llm4sAnthropicModule)
    }

    "contribute no embedding provider" in {
      new Llm4sAnthropicModule().embeddingProviders shouldBe empty
      ProviderRegistry.default.findEmbedding(ProviderId("anthropic")) shouldBe None
    }
  }

  "the llm4s-anthropic provider" should {

    "default its base URL to the Anthropic API" in {
      AnthropicProvider.configSpec.defaultBaseUrl shouldBe Some(AnthropicConfig.DEFAULT_BASE_URL)
    }

    "build an AnthropicClient from a config section" in {
      assertBuildsClient(AnthropicProvider, section).getClass.getSimpleName shouldBe "AnthropicClient"
    }

    "load an AnthropicConfig from a named section, as an application does" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig
        .loadProvider(
          "claude",
          """llm4s.providers.claude { provider = "anthropic", model = "claude-sonnet-4-5" }""",
          Map("ANTHROPIC_API_KEY" -> "sk-ant-test")
        )
        .map(_.getClass.getSimpleName) shouldBe Right("AnthropicConfig")
    }

    "refuse a config belonging to another provider" in {
      assertRefusesForeignConfig(AnthropicProvider)
    }

    "declare a model lister" in {
      AnthropicProvider.modelLister shouldBe defined
    }
  }

  "a client built by the anthropic descriptor" should {

    "actually stream, not silently fall back to complete()" in {
      withServer("/v1/messages")(exchange => sendSseResponse(exchange, streamingBody)) { baseUrl =>
        assertStreams(assertBuildsClient(AnthropicProvider, section.withBaseUrl(Some(BaseUrl(baseUrl)))))
      }
    }
  }

  "the llm4s-anthropic reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind ANTHROPIC_API_KEY to llm4s.credentials.anthropic.apiKey" in {
      AnthropicProvider.configSpec.apiKeyEnv shouldBe Seq(AnthropicConfigKeys.ANTHROPIC_API_KEY)
      assertCredentialBindings(AnthropicProvider)
    }

    "let a section's own key win over ANTHROPIC_API_KEY" in {
      CredentialsRoundTrip.chatSectionKey(
        "anthropic",
        Map("ANTHROPIC_API_KEY" -> "sk-shared", "ANTHROPIC_TEAM_API_KEY" -> "sk-team"),
        "apiKey = ${?ANTHROPIC_TEAM_API_KEY}"
      ) shouldBe Right(Some("sk-team"))
    }
  }
