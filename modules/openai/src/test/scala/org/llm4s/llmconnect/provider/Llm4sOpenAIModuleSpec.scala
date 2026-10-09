package org.llm4s.llmconnect.provider

import org.llm4s.config.OpenAIConfigKeys
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.contract.LLMClientContractBehaviors
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, stream, transport }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.ModelRegistryService
import org.llm4s.llmconnect.LLMClient
import org.llm4s.testkit.{ CredentialsRoundTrip, LocalProviderTestServer, ProviderModuleChecks, ToolMessageFormat }
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
class Llm4sOpenAIModuleSpec extends AnyWordSpec with Matchers with LLMClientContractBehaviors with ProviderModuleChecks:

  private val registryService         = ProviderModuleChecks.defaultModelRegistry
  private given ModelRegistryService  = registryService
  private given ContextWindowResolver = ContextWindowResolver(registryService)

  /** Descriptor, the config class it builds, and the client class that config produces. */
  private val expectations: Seq[(ProviderDescriptor, String, String)] = Seq(
    (OpenAIProvider, "OpenAIConfig", "OpenAIClient"),
    (RequestyProvider, "OpenAIConfig", "OpenAIClient"),
    (AzureProvider, "AzureConfig", "OpenAIClient")
  )

  /** A section carrying every field any of the three providers asks for. */
  private def section(descriptor: ProviderDescriptor): NamedProviderConfig =
    NamedProviderConfig(
      provider = descriptor.id,
      model = ModelName("test-model"),
      baseUrl = descriptor.configSpec.defaultBaseUrl.map(BaseUrl(_)),
      apiKey = Some(ApiKey("test-key")),
      extras = Map(
        "organization"              -> "test-org",
        AzureProvider.EndpointKey   -> "https://test-resource.openai.azure.com",
        AzureProvider.ApiVersionKey -> AzureConfig.DEFAULT_API_VERSION
      )
    )

  "the llm4s-openai services entry" should {

    "be discovered, the only supplier of openai, azure and requesty, and registrable explicitly" in {
      assertModule(new Llm4sOpenAIModule)
    }

    "contribute the three chat providers and the OpenAI embedding provider" in {
      val module = new Llm4sOpenAIModule
      module.chatProviders should contain theSameElementsAs expectations.map(_._1)
      module.embeddingProviders shouldBe Seq(OpenAIEmbeddingProvider)
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
        assertBuildsClient(descriptor, section(descriptor)).getClass.getSimpleName shouldBe clientClass
      }
    }

    "default Azure's API version when the section sets none" in {
      AzureProvider.buildConfig(
        "test-instance",
        section(AzureProvider).withExtras(section(AzureProvider).extras - AzureProvider.ApiVersionKey)
      ) match
        case Right(azure: AzureConfig) => azure.apiVersion shouldBe AzureConfig.DEFAULT_API_VERSION
        case other                     => fail(s"Expected AzureConfig, got $other")
    }

    "refuse a config belonging to another provider" in {
      expectations.foreach((descriptor, _, _) => assertRefusesForeignConfig(descriptor))
    }

    "declare a model lister where the provider has one" in {
      OpenAIProvider.modelLister shouldBe defined
      RequestyProvider.modelLister shouldBe defined
    }
  }

  "a client built by any of the three descriptors" should {
    // OpenAI, Azure and Requesty all build the same OpenAIClient class (see `expectations`
    // above), so one stub-backed client proves the streaming path all three declare.
    val contentChunk = chunk(
      """{"id":"chatcmpl-1","created":0,"choices":[{"index":0,"delta":{"role":"assistant","content":"Hi"}}]}"""
    )
    val stopChunk = chunk(
      """{"id":"chatcmpl-1","created":0,"choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant"}}]}"""
    )
    val config = OpenAIConfig
      .fromValues(
        modelName = "test-model",
        apiKey = "test-key",
        organization = None,
        baseUrl = "https://example.invalid/v1"
      )
      .toOption
      .get

    honoursStreaming(() =>
      OpenAIClient.forTest("test-model", transport(streaming = _ => stream(contentChunk, stopChunk)), config)
    )
  }

  "an OpenAIClient against a local server" should {

    def openAIClientAt(baseUrl: String): LLMClient =
      OpenAIConfig
        .fromValues("gpt-4o", "sk-test", None, baseUrl)
        .flatMap(OpenAIClient(_)) match
        case Right(client) => client
        case Left(error)   => fail(s"could not build an OpenAIClient: ${error.message}")

    "return CancelledError when a call is interrupted" in {
      LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
        assertCancelsWhenInterrupted(openAIClientAt(baseUrl))
      }
    }

    "send exactly one tool message per tool call, keeping every call and result (design 5.3)" in {
      assertOneToolResultPerCall(ToolMessageFormat.OpenAIChat, LocalProviderTestServer.openAICompletion("done"))(
        openAIClientAt
      )
    }

    "return CancelledError when a stream is interrupted after its first event" in {
      val firstEvent = LocalProviderTestServer.openAISseBody(Seq("Hel", "lo")).split("\n\n").head + "\n\n"
      LocalProviderTestServer.withServer("/")(LocalProviderTestServer.streamThenHold(_, firstEvent)) { baseUrl =>
        assertCancelsStreamWhenInterrupted(openAIClientAt(baseUrl))
      }
    }
  }

  "the llm4s-openai reference.conf" should {

    // Discovery, as a user gets it: the module's own reference.conf and services entry.
    given ProviderRegistry = ProviderRegistry.default

    "bind each vendor's variable to its shared llm4s.credentials key" in {
      OpenAIProvider.configSpec.apiKeyEnv shouldBe Seq(OpenAIConfigKeys.OPENAI_API_KEY)
      RequestyProvider.configSpec.apiKeyEnv shouldBe Seq(OpenAIConfigKeys.REQUESTY_API_KEY)
      AzureProvider.configSpec.apiKeyEnv shouldBe Seq(OpenAIConfigKeys.AZURE_OPENAI_API_KEY)

      assertCredentialBindings(OpenAIProvider)
      assertCredentialBindings(RequestyProvider)
      assertCredentialBindings(AzureProvider, """endpoint = "https://test-resource.openai.azure.com"""")
    }

    "keep each vendor's key to its own provider" in {
      // Requesty is OpenAI-compatible, but OPENAI_API_KEY is OpenAI's key, not Requesty's.
      CredentialsRoundTrip.chatSectionKey("requesty", Map("OPENAI_API_KEY" -> "sk-openai")).isLeft shouldBe true
    }

    "no longer bind AZURE_API_KEY, which no SDK reads" in {
      CredentialsRoundTrip
        .chatSectionKey("azure", Map("AZURE_API_KEY" -> "k"), """endpoint = "https://x.openai.azure.com"""")
        .isLeft shouldBe true
    }

    "give OpenAI embeddings the same OPENAI_API_KEY" in {
      OpenAIEmbeddingProvider.configSpec.apiKeyEnv shouldBe Seq(OpenAIConfigKeys.OPENAI_API_KEY)
      assertEmbeddingCredentialBindings(OpenAIEmbeddingProvider, "text-embedding-3-small")
    }
  }

  "the openai embedding provider" should {

    "return CancelledError, with the interrupt flag set, when its thread is interrupted mid-request" in {
      LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
        val config = EmbeddingProviderConfig(baseUrl = baseUrl, model = "text-embedding-3-small", apiKey = "test-key")
        assertEmbeddingCancelsWhenInterrupted(assertBuildsEmbeddingProvider(OpenAIEmbeddingProvider, config))
      }
    }
  }
