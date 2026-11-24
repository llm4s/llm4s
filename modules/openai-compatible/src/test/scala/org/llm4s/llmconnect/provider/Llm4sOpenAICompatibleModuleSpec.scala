package org.llm4s.llmconnect.provider

import org.llm4s.config.OpenAICompatibleConfigKeys
import org.llm4s.config.NamedProviderConfig
import org.llm4s.llmconnect.config.ContextWindowResolver
import org.llm4s.llmconnect.spi.{ ProviderBuildContext, ProviderConfigContext, ProviderDescriptor, ProviderRegistry }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{
  holdOpen,
  openAICompletion,
  openAISseBody,
  sendJsonResponse,
  streamThenHold,
  withServer
}
import org.llm4s.testkit.{
  ContentPartCase,
  CredentialsRoundTrip,
  ProviderModuleChecks,
  ProviderTestConfig,
  ToolMessageFormat
}
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-openai-compatible` registers itself, and what it registers works.
 *
 * These are the DeepSeek, Z.ai, OpenRouter, Mistral and Cohere rows (xAI joined in #1023) of core's `BuiltinProvidersSpec`,
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
    (CohereProvider, "CohereConfig", "CohereClient"),
    (XaiProvider, "XaiConfig", "XaiClient")
  )

  /** A section carrying every field any of the providers asks for. */
  private def section(descriptor: ProviderDescriptor): NamedProviderConfig =
    NamedProviderConfig(
      provider = descriptor.id,
      model = ModelName("test-model"),
      baseUrl = descriptor.configSpec.defaultBaseUrl.orElse(Some(BaseUrl("http://localhost:8000/v1"))),
      apiKey = Some(ApiKey("test-key")),
    )

  "the llm4s-openai-compatible services entry" should {

    "be discovered, the only supplier of every provider the module holds, and registrable explicitly" in {
      assertModule(new Llm4sOpenAICompatibleModule)
    }

    "contribute exactly the expected providers, and no embedding providers" in {
      val module = new Llm4sOpenAICompatibleModule
      module.providers should contain theSameElementsAs expectations.map(_._1)
      module.embeddingProviders shouldBe empty
    }
  }

  "the llm4s-openai-compatible providers" should {

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

    "send complete and streamComplete through a supplied HTTP client, and leave it open (#2341)" in {
      expectations.foreach((descriptor, _, _) => assertUsesSuppliedHttpClient(descriptor, section(descriptor)))
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
      XaiProvider.modelLister shouldBe defined
      // Z.ai and Cohere had no lister in core either.
      ZaiProvider.modelLister shouldBe None
      CohereProvider.modelLister shouldBe None
    }
  }

  "a client built by each openai-compatible descriptor" should {

    "send the key its ApiKeyProvider holds at each request (#2313, #2477)" in {
      given ModelRegistryService = registryService
      expectations.map(_._1).foreach { descriptor =>
        withClue(s"${descriptor.id.asString}: ") {
          assertHonoursApiKeyProvider(sendJsonResponse(_, 200, openAICompletion("done"))) { (baseUrl, keys) =>
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
  }

  "a client built by each openai-compatible descriptor" should {

    "report how each reply ended, on complete and on a stream alike, and really stream (RFC 0009)" in {
      expectations.foreach { (descriptor, _, _) =>
        withClue(s"${descriptor.id.asString}: ") {
          assertModelCallContract(ChatCompletionsContractFixtures.replies) { baseUrl =>
            assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl))))
          }
        }
      }
    }
  }

  "a client built by each openai-compatible descriptor" should {

    "send exactly one tool message per tool call, keeping every call and result (design 5.3)" in {
      expectations.foreach { (descriptor, _, _) =>
        withClue(s"${descriptor.id.asString}: ") {
          assertOneToolResultPerCall(ToolMessageFormat.OpenAIChat, openAICompletion("done")) { baseUrl =>
            assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl))))
          }
        }
      }
    }
  }

  "a client built by each openai-compatible descriptor" should {

    "send the content parts its dialect takes and refuse the rest before sending (#1913)" in {
      val images = Seq(ContentPartCase.UserImageUrl, ContentPartCase.UserImageInline)
      val supported: Map[ProviderDescriptor, Seq[ContentPartCase]] = Map(
        OpenAICompatibleProvider -> images,
        DeepSeekProvider         -> Nil,
        ZaiProvider              -> images,
        OpenRouterProvider       -> (images ++ Seq(ContentPartCase.UserPdfUrl, ContentPartCase.UserPdfInline)),
        MistralProvider          -> (images :+ ContentPartCase.UserPdfUrl),
        CohereProvider           -> images,
        XaiProvider              -> images
      )
      expectations.foreach { (descriptor, _, _) =>
        withClue(s"${descriptor.id.asString}: ") {
          // a provider's 400 or 413 - a vendor limit llm4s leaves to it - is a typed error with its message
          assertProviderRejectionIsTyped((exchange, status, message) =>
            sendJsonResponse(exchange, status, ujson.Obj("error" -> ujson.Obj("message" -> message)).render())
          )(baseUrl => assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl)))))
          assertContentParts(descriptor.id.asString, sendJsonResponse(_, 200, openAICompletion("done")))(
            supported(descriptor)*
          )(baseUrl => assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl)))))
        }
      }
    }
  }

  "validate() on a client built by each openai-compatible descriptor" should {

    /** The path, after the base URL, of each descriptor's validation request; `None` for one with no such request. */
    val validation: Map[ProviderDescriptor, Option[String]] = Map(
      OpenAICompatibleProvider -> Some("/models"),
      DeepSeekProvider         -> Some("/models"),
      XaiProvider              -> Some("/models"),
      MistralProvider          -> Some("/models"),
      OpenRouterProvider       -> Some("/key"), // its /models answers without a key
      ZaiProvider              -> None,
      // Cohere's native Models API, beside the Compatibility API the completions go to: it looks the model up
      CohereProvider -> Some("/models/test-model")
    )

    "check the endpoint and the key with one request, or say that it cannot (#2830)" in {
      validation.keySet shouldBe expectations.map(_._1).toSet
      validation.foreach { (descriptor, path) =>
        withClue(s"${descriptor.id.asString}: ") {
          def client(baseUrl: String) =
            assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(baseUrl))))
          path match
            case None => assertValidationUnsupported(client("http://localhost:1"))
            case Some(_) =>
              assertValidates(
                sendJsonResponse(_, 200, """{"object":"list","data":[]}"""),
                (exchange, status, message) =>
                  sendJsonResponse(exchange, status, ujson.Obj("error" -> ujson.Obj("message" -> message)).render())
              )(client)
        }
      }
    }

    "send a GET to the dialect's path, carrying the key a completion carries" in {
      validation.collect { case (descriptor, Some(path)) => descriptor -> path }.foreach { (descriptor, path) =>
        withClue(s"${descriptor.id.asString}: ") {
          val seen = new java.util.concurrent.ConcurrentLinkedQueue[(String, String, String)]()
          withServer("/") { exchange =>
            seen.add(
              (
                exchange.getRequestMethod,
                exchange.getRequestURI.getPath,
                Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("")
              )
            )
            sendJsonResponse(exchange, 200, """{"data":[]}""")
          } { baseUrl =>
            val client = assertBuildsClient(descriptor, section(descriptor).withBaseUrl(Some(BaseUrl(s"$baseUrl/v1"))))
            client.validate() shouldBe Right(())
            client.close()
          }
          seen.toArray.toSeq shouldBe Seq(("GET", s"/v1$path", "Bearer test-key"))
        }
      }
    }

    "report a key xAI refuses with a 400 as an AuthenticationError, from validate() and from a completion (#2855)" in {
      import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
      // xAI's reply to a wrong key, as api.x.ai sent it on 2026-11-24
      val refused = ujson
        .Obj(
          "code"  -> "invalid-argument",
          "error" -> "Incorrect API key provided. You can obtain an API key from https://console.x.ai."
        )
        .render()
      withServer("/")(sendJsonResponse(_, 400, refused)) { baseUrl =>
        val client = assertBuildsClient(XaiProvider, section(XaiProvider).withBaseUrl(Some(BaseUrl(baseUrl))))
        client.validate() should matchPattern { case Left(_: org.llm4s.error.AuthenticationError) => }
        client.complete(Conversation(Seq(UserMessage("hi")))) should matchPattern {
          case Left(_: org.llm4s.error.AuthenticationError) =>
        }
        client.streamComplete(Conversation(Seq(UserMessage("hi"))))(_ => ()) should matchPattern {
          case Left(_: org.llm4s.error.AuthenticationError) =>
        }
        client.close()
      }
      // any other 400 is still the request's fault
      val other = """{"code":"invalid-argument","error":"Unknown parameter"}"""
      withServer("/")(sendJsonResponse(_, 400, other)) { baseUrl =>
        val client = assertBuildsClient(XaiProvider, section(XaiProvider).withBaseUrl(Some(BaseUrl(baseUrl))))
        client.validate() should matchPattern { case Left(_: org.llm4s.error.ValidationError) => }
        client.close()
      }
    }

    "report a model Cohere does not have as the ServiceError 404 its Models API answers with (#2855)" in {
      withServer("/")(sendJsonResponse(_, 404, """{"message":"model 'test-model' not found"}""")) { baseUrl =>
        val client = assertBuildsClient(CohereProvider, section(CohereProvider).withBaseUrl(Some(BaseUrl(baseUrl))))
        client.validate() should matchPattern {
          case Left(error: org.llm4s.error.ServiceError) if error.status == 404 =>
        }
        client.close()
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
        CohereProvider     -> OpenAICompatibleConfigKeys.COHERE_API_KEY,
        XaiProvider        -> OpenAICompatibleConfigKeys.XAI_API_KEY
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
