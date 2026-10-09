package org.llm4s.llmconnect.provider

import org.llm4s.config.OpenAICompatibleConfigKeys
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.ContextWindowResolver
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.testkit.LocalProviderTestServer.{
  holdOpen,
  openAISseBody,
  sendSseResponse,
  streamThenHold,
  withServer
}
import org.llm4s.testkit.{ CredentialsRoundTrip, ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-openai-compatible` registers itself, and what it registers works.
 *
 * These are the DeepSeek, Z.ai, OpenRouter, Mistral and Cohere rows of core's `BuiltinProvidersSpec`,
 * which left with the providers (#1132). Mistral's and Cohere's asserted `streaming = false`
 * (#925); on the shared client they stream, plus the generic `openai-compatible` provider and the part
 * that only a carved module has to prove: that depending on it is enough - the services
 * entry is found and the descriptors arrive.
 */
class Llm4sOpenAICompatibleModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private val registryService         = ProviderModuleChecks.defaultModelRegistry
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

  /** A section carrying every field any of the providers asks for. */
  private def section(descriptor: ProviderDescriptor): NamedProviderConfig =
    NamedProviderConfig(
      provider = descriptor.id,
      model = ModelName("test-model"),
      baseUrl = descriptor.configSpec.defaultBaseUrl.orElse(Some("http://localhost:8000/v1")).map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
    )

  "the llm4s-openai-compatible services entry" should {

    "be discovered, the only supplier of every provider the module holds, and registrable explicitly" in {
      assertModule(new Llm4sOpenAICompatibleModule)
    }

    "contribute exactly the expected providers, and no embedding providers" in {
      val module = new Llm4sOpenAICompatibleModule
      module.chatProviders should contain theSameElementsAs expectations.map(_._1)
      module.embeddingProviders shouldBe empty
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
        assertBuildsClient(descriptor, section(descriptor)).getClass.getSimpleName shouldBe clientClass
      }
    }

    "load the generic provider entirely from a named section, as an application does" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig
        .loadProvider(
          "local-vllm",
          """llm4s.providers.local-vllm {
            |  provider = "openai-compatible"
            |  model    = "qwen2.5"
            |  baseUrl  = "http://localhost:8000/v1"
            |}""".stripMargin
        )
        .map(_.getClass.getSimpleName) shouldBe Right("OpenAICompatibleConfig")
    }

    "build a client from a section with auth" in {
      given ProviderRegistry = ProviderRegistry.default
      val section = ProviderTestConfig
        .loadSection(
          "dbx",
          """llm4s.providers.dbx {
            |  provider = "openai-compatible"
            |  model    = "m"
            |  baseUrl  = "https://ws.example/serving-endpoints"
            |  auth { identityTokenFile = "/var/run/svid", tokenUrl = "https://ws.example/oidc/v1/token" }
            |}""".stripMargin
        )
        .fold(error => fail(error.message), identity)
      assertBuildsClient(OpenAICompatibleProvider, section).getClass.getSimpleName shouldBe "OpenAICompatibleClient"
    }

    "refuse a config belonging to another provider" in {
      expectations.foreach((descriptor, _, _) => assertRefusesForeignConfig(descriptor))
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
            assertStreams(assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl)))))
          }
        }
      }
    }
  }

  "a client built by each openai-compatible descriptor, against a server that stalls" should {

    "return CancelledError when a call is interrupted" in {
      expectations.foreach { (descriptor, _, _) =>
        withClue(s"${descriptor.id.asString}: ") {
          withServer("/")(holdOpen) { baseUrl =>
            assertCancelsWhenInterrupted(
              assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl))))
            )
          }
        }
      }
    }

    "return CancelledError when a stream is interrupted after its first event" in {
      val firstEvent = openAISseBody(Seq("Hel", "lo")).split("\n\n").head + "\n\n"
      expectations.foreach { (descriptor, _, _) =>
        withClue(s"${descriptor.id.asString}: ") {
          withServer("/")(streamThenHold(_, firstEvent)) { baseUrl =>
            assertCancelsStreamWhenInterrupted(
              assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl))))
            )
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
          assertCredentialBindings(descriptor)
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
