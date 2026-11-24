package org.llm4s.llmconnect.provider

import org.llm4s.security.Secret
import org.llm4s.config.OpenAIConfigKeys
import org.llm4s.config.NamedProviderConfig
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.contract.LLMClientContractBehaviors
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, stream, transport }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.spi.{ ProviderBuildContext, ProviderConfigContext, ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.ModelRegistryService
import org.llm4s.llmconnect.LLMClient
import org.llm4s.testkit.{
  ContentPartCase,
  CredentialsRoundTrip,
  LocalProviderTestServer,
  ProviderModuleChecks,
  ProviderTestConfig,
  ToolMessageFormat
}
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
      baseUrl = descriptor.configSpec.defaultBaseUrl,
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
      module.providers should contain theSameElementsAs expectations.map(_._1)
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
        descriptor.buildConfig(section(descriptor), ProviderConfigContext.of("test-instance")) match
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
        section(AzureProvider).withExtras(section(AzureProvider).extras - AzureProvider.ApiVersionKey),
        ProviderConfigContext.of("test-instance")
      ) match
        case Right(azure: AzureConfig) => azure.apiVersion shouldBe AzureConfig.DEFAULT_API_VERSION
        case other                     => fail(s"Expected AzureConfig, got $other")
    }

    "build a client from an openai section with auth" in {
      given ProviderRegistry = ProviderRegistry.default
      val section = ProviderTestConfig
        .loadSection(
          "wi",
          """llm4s.providers.wi {
            |  provider = "openai"
            |  model    = "gpt-4o-mini"
            |  auth { identityTokenFile = "/var/run/svid", identityProviderId = "idp_1", serviceAccountId = "sa_1" }
            |}""".stripMargin
        )
        .fold(error => fail(error.message), identity)
      assertBuildsClient(OpenAIProvider, section).getClass.getSimpleName shouldBe "OpenAIClient"
    }

    "refuse a config belonging to another provider" in {
      expectations.foreach((descriptor, _, _) => assertRefusesForeignConfig(descriptor))
    }

    "declare that the SDK ignores a supplied HTTP client (#2341)" in {
      expectations.foreach((descriptor, _, _) => descriptor.features.customHttpClient shouldBe false)
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
        modelName = ModelName("test-model"),
        apiKey = Secret("test-key"),
        organization = None,
        baseUrl = BaseUrl("https://example.invalid/v1")
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
        .fromValues(ModelName("gpt-4o"), Secret("sk-test"), BaseUrl(baseUrl), None)
        .flatMap(OpenAIClient(_)) match
        case Right(client) => client
        case Left(error)   => fail(s"could not build an OpenAIClient: ${error.message}")

    "return CancelledError when a call is interrupted" in {
      LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
        assertCancelsWhenInterrupted(openAIClientAt(baseUrl))
      }
    }

    "send the key its ApiKeyProvider holds at each request, for openai and requesty (#2313)" in {
      val respond = LocalProviderTestServer.sendJsonResponse(_, 200, LocalProviderTestServer.openAICompletion("ok"))
      Seq(OpenAIProvider, RequestyProvider).foreach { descriptor =>
        withClue(s"${descriptor.id.asString}: ") {
          assertHonoursApiKeyProvider(respond) { (baseUrl, keys) =>
            descriptor
              .buildConfig(
                section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl))),
                ProviderConfigContext.of("rotation")
              )
              .flatMap(_.withApiKeyProvider(keys))
              .flatMap(descriptor.build(_, ProviderBuildContext.of()))
              .fold(error => fail(error.message), identity)
          }
        }
      }
    }

    "send the key its ApiKeyProvider holds at each request, for azure (#2477)" in {
      val respond = LocalProviderTestServer.sendJsonResponse(_, 200, LocalProviderTestServer.openAICompletion("ok"))
      assertHonoursApiKeyProvider(respond) { (baseUrl, keys) =>
        val azure = section(AzureProvider)
        AzureProvider
          .buildConfig(
            azure.withExtras(azure.extras.updated(AzureProvider.EndpointKey, baseUrl)),
            ProviderConfigContext.of("rotation")
          )
          .flatMap(_.withApiKeyProvider(keys))
          .flatMap(AzureProvider.build(_, ProviderBuildContext.of()))
          .fold(error => fail(error.message), identity)
      }
    }

    "send the content parts Chat Completions takes and refuse the rest before sending (#1913)" in {
      val supported = Seq(
        ContentPartCase.UserImageUrl,
        ContentPartCase.UserImageInline,
        ContentPartCase.UserPdfInline,
        ContentPartCase.UserPdfFile
      )
      val respond = LocalProviderTestServer.sendJsonResponse(_, 200, LocalProviderTestServer.openAICompletion("done"))
      // a provider's 400 or 413 - a vendor limit llm4s leaves to it - is a typed error with its message
      assertProviderRejectionIsTyped((exchange, status, message) =>
        LocalProviderTestServer.sendJsonResponse(
          exchange,
          status,
          ujson.Obj("error" -> ujson.Obj("message" -> message, "type" -> "invalid_request_error")).render()
        )
      )(openAIClientAt)
      assertContentParts("openai", respond)(supported*)(openAIClientAt)
      assertContentParts("requesty", respond)(supported*) { baseUrl =>
        val config =
          OpenAIConfig.fromValues(ModelName("openai/gpt-4o-mini"), Secret("rq-key"), BaseUrl(baseUrl)).toOption.get
        RequestyProvider.build(config, ProviderBuildContext.of()).toOption.get
      }
      assertContentParts("azure", respond)(supported*) { baseUrl =>
        val config =
          AzureConfig
            .fromValues(ModelName("my-deploy"), Secret("azure-key"), BaseUrl(baseUrl), AzureConfig.DEFAULT_API_VERSION)
            .toOption
            .get
        AzureProvider.build(config, ProviderBuildContext.of()).toOption.get
      }
    }

    "validate OpenAI by its model and Requesty by its listing (#2830)" in {
      def openAIError(exchange: com.sun.net.httpserver.HttpExchange, status: Int, message: String): Unit =
        LocalProviderTestServer.sendJsonResponse(
          exchange,
          status,
          ujson.Obj("error" -> ujson.Obj("message" -> message, "type" -> "invalid_request_error")).render()
        )
      def requestyClientAt(baseUrl: String): LLMClient = {
        val config =
          OpenAIConfig.fromValues(ModelName("openai/gpt-4o-mini"), Secret("rq-key"), BaseUrl(baseUrl)).toOption.get
        RequestyProvider.build(config, ProviderBuildContext.of()).toOption.get
      }
      val found  = """{"id":"gpt-4o","object":"model","created":1,"owned_by":"openai"}"""
      val listed = s"""{"object":"list","data":[$found]}"""
      assertValidates(LocalProviderTestServer.sendJsonResponse(_, 200, found), openAIError)(openAIClientAt)
      assertValidates(LocalProviderTestServer.sendJsonResponse(_, 200, listed), openAIError)(requestyClientAt)

      /** The requests `client` sends from `validate()`, as `METHOD path`, with the bearer each carried. */
      def requests(body: String)(client: String => LLMClient): Seq[(String, String)] = {
        val seen = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
        LocalProviderTestServer.withServer("/") { exchange =>
          seen.add(
            s"${exchange.getRequestMethod} ${exchange.getRequestURI.getPath}" ->
              Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("")
          )
          LocalProviderTestServer.sendJsonResponse(exchange, 200, body)
        } { baseUrl =>
          val built = client(baseUrl)
          built.validate() shouldBe Right(())
          built.close()
        }
        seen.toArray(Array.empty[(String, String)]).toSeq
      }
      requests(found)(openAIClientAt) shouldBe Seq("GET /models/gpt-4o" -> "Bearer sk-test")
      requests(listed)(requestyClientAt) shouldBe Seq("GET /models" -> "Bearer rq-key")

    }

    "validate Azure OpenAI by its resource's model listing, with the api-key a completion carries (#2855)" in {
      def azureError(exchange: com.sun.net.httpserver.HttpExchange, status: Int, message: String): Unit =
        LocalProviderTestServer.sendJsonResponse(
          exchange,
          status,
          ujson.Obj("error" -> ujson.Obj("code" -> status.toString, "message" -> message)).render()
        )
      def azureClientAt(suffix: String)(baseUrl: String): LLMClient = {
        val config = AzureConfig
          .fromValues(
            ModelName("my-deploy"),
            Secret("azure-key"),
            BaseUrl(s"$baseUrl$suffix"),
            AzureConfig.DEFAULT_API_VERSION
          )
          .toOption
          .get
        AzureProvider.build(config, ProviderBuildContext.of()).toOption.get
      }
      val listed = """{"object":"list","data":[{"id":"gpt-4o","object":"model","created_at":1,"status":"succeeded"}]}"""
      assertValidates(LocalProviderTestServer.sendJsonResponse(_, 200, listed), azureError)(azureClientAt(""))

      /** The request `validate()` sends: `METHOD path?query`, and its `api-key` header. */
      def request(suffix: String): Seq[(String, String)] = {
        val seen = new java.util.concurrent.ConcurrentLinkedQueue[(String, String)]()
        LocalProviderTestServer.withServer("/") { exchange =>
          seen.add(
            s"${exchange.getRequestMethod} ${exchange.getRequestURI}" ->
              Option(exchange.getRequestHeaders.getFirst("api-key")).getOrElse("")
          )
          LocalProviderTestServer.sendJsonResponse(exchange, 200, listed)
        } { baseUrl =>
          val built = azureClientAt(suffix)(baseUrl)
          built.validate() shouldBe Right(())
          built.close()
        }
        seen.toArray(Array.empty[(String, String)]).toSeq
      }
      // the data plane's Models - List, and the v1 API's List models
      request("") shouldBe Seq("GET /openai/models?api-version=2025-01-01-preview" -> "azure-key")
      request("/openai/v1") shouldBe Seq("GET /openai/v1/models" -> "azure-key")
    }

    "report how each reply ended, on complete and on a stream alike (RFC 0009)" in {
      assertModelCallContract(OpenAIContractFixtures.replies)(openAIClientAt)
      assertModelCallContract(OpenAIContractFixtures.replies) { baseUrl =>
        val config =
          AzureConfig
            .fromValues(ModelName("my-deploy"), Secret("azure-key"), BaseUrl(baseUrl), AzureConfig.DEFAULT_API_VERSION)
            .toOption
            .get
        AzureProvider.build(config, ProviderBuildContext.of()).toOption.get
      }
    }

    "report Azure's 400 for a prompt its content filter blocked as a ContentFilteredError (#2677)" in {
      val body =
        """{"error":{"message":"The response was filtered","type":null,"param":"prompt","code":"content_filter","status":400}}"""
      LocalProviderTestServer.withServer("/")(LocalProviderTestServer.sendJsonResponse(_, 400, body)) { baseUrl =>
        val config =
          AzureConfig
            .fromValues(ModelName("my-deploy"), Secret("azure-key"), BaseUrl(baseUrl), AzureConfig.DEFAULT_API_VERSION)
            .toOption
            .get
        val client = AzureProvider.build(config, ProviderBuildContext.of()).toOption.get
        client.complete(
          org.llm4s.llmconnect.model.Conversation(Seq(org.llm4s.llmconnect.model.UserMessage("Hi")))
        ) match
          case Left(e: org.llm4s.error.ContentFilteredError) =>
            e.promptBlocked shouldBe true
            e.provider shouldBe "Azure OpenAI"
            e.reason shouldBe "content_filter"
          case other => fail(s"expected a ContentFilteredError, got $other")
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
      assertEmbeddingCredentialBindings(OpenAIEmbeddingProvider, ModelName("text-embedding-3-small"))
    }
  }

  "the openai embedding provider" should {

    "return CancelledError, with the interrupt flag set, when its thread is interrupted mid-request" in {
      LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
        val config =
          EmbeddingProviderConfig(
            providerId = ProviderId("openai"),
            baseUrl = BaseUrl(baseUrl),
            model = ModelName("text-embedding-3-small"),
            apiKey = Some(Secret("test-key"))
          )
        assertEmbeddingCancelsWhenInterrupted(assertBuildsEmbeddingProvider(OpenAIEmbeddingProvider, config))
      }
    }

    "send its requests through the HTTP client a caller supplies, and leave it open (#2811)" in {
      assertEmbeddingUsesSuppliedHttpClient(
        OpenAIEmbeddingProvider,
        EmbeddingProviderConfig(
          providerId = ProviderId("openai"),
          baseUrl = BaseUrl("http://localhost:1"),
          model = ModelName("test-model"),
          apiKey = Some(Secret("test-key"))
        )
      )
    }

    "send the key its ApiKeyProvider holds at each request, so a rotated apiKeyFile is used (#2478)" in {
      assertEmbeddingHonoursApiKeyProvider(
        LocalProviderTestServer.sendJsonResponse(
          _,
          200,
          """{"data":[{"embedding":[0.1,0.2]}],"usage":{"prompt_tokens":1,"total_tokens":1}}"""
        )
      ) { (baseUrl, keys) =>
        val config =
          EmbeddingProviderConfig(
            providerId = ProviderId("openai"),
            baseUrl = BaseUrl(baseUrl),
            model = ModelName("text-embedding-3-small"),
            apiKey = Some(Secret("test-key"))
          )
        assertBuildsEmbeddingProvider(OpenAIEmbeddingProvider, config.withApiKeyProvider(Some(keys)))
      }
    }
  }
