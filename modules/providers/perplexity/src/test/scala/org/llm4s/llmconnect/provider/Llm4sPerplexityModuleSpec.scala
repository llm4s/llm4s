package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.PerplexityConfig
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.LocalProviderTestServer.{
  holdOpen,
  sendJsonResponse,
  sendSseResponse,
  streamThenHold,
  withServer
}
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-perplexity` registers itself, and what it registers works: discovery finds the services entry, the module
 * is the only supplier of `perplexity`, `PERPLEXITY_API_KEY` reaches a section, and a section round-trips to a client
 * that talks to a (local) server over the real HTTP path.
 */
class Llm4sPerplexityModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  import PerplexityFixtures.{ *, given }

  private val reply = response(s"[$searchResults,${textMessage("Hello there [web:1]")}]")

  private val sseText = sseBody(created, delta("Hi"), delta("!"), completedEvent(response(s"[${textMessage("Hi!")}]")))

  private def section(baseUrl: String, extras: Map[String, String] = Map.empty): NamedProviderConfig =
    NamedProviderConfig(
      provider = PerplexityProvider.id,
      model = ModelName("perplexity/sonar"),
      baseUrl = Some(BaseUrl(baseUrl)),
      apiKey = Some(ApiKey("test-key")),
      extras = extras
    )

  private def clientAt(baseUrl: String): LLMClient = assertBuildsClient(PerplexityProvider, section(baseUrl))

  "the llm4s-perplexity services entry" should {

    "be discovered, the only supplier of perplexity, and registrable explicitly" in {
      assertModule(new Llm4sPerplexityModule)
    }

    "contribute the chat provider under the id perplexity and no embeddings" in {
      val module = new Llm4sPerplexityModule
      module.chatProviders shouldBe Seq(PerplexityProvider)
      module.embeddingProviders shouldBe empty
      PerplexityProvider.id.asString shouldBe "perplexity"
    }
  }

  "PerplexityProvider" should {

    "build a PerplexityClient from a config section" in {
      assertBuildsClient(PerplexityProvider, section("http://localhost:1")).getClass.getSimpleName shouldBe
        "PerplexityClient"
    }

    "refuse a config belonging to another provider" in {
      assertRefusesForeignConfig(PerplexityProvider)
    }

    "bind PERPLEXITY_API_KEY to the shared credential" in {
      assertCredentialBindings(PerplexityProvider)
    }

    "load a PerplexityConfig from a named section with the shared key, as an application does" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig.loadProvider(
        "pplx",
        """llm4s.providers.pplx { provider = "perplexity", model = "perplexity/sonar" }""",
        env = Map("PERPLEXITY_API_KEY" -> "env-key")
      ) match
        case Right(config: PerplexityConfig) =>
          config.apiKey.reveal shouldBe "env-key"
          config.model shouldBe "perplexity/sonar"
          config.baseUrl shouldBe PerplexityConfig.DEFAULT_BASE_URL
          config.agentUrl shouldBe "https://api.perplexity.ai/v1/agent"
          config.webSearch shouldBe true
        case other => fail(s"expected a PerplexityConfig, got $other")
    }

    "read webSearch, contextWindow and reserveCompletion from the section" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig.loadProvider(
        "pplx",
        """llm4s.providers.pplx {
          |  provider = "perplexity", model = "openai/gpt-5.6-sol", apiKey = "k"
          |  webSearch = false, contextWindow = 400000, reserveCompletion = 8000
          |}""".stripMargin
      ) match
        case Right(config: PerplexityConfig) =>
          config.webSearch shouldBe false
          config.contextWindow shouldBe 400000
          config.reserveCompletion shouldBe 8000
        case other => fail(s"expected a PerplexityConfig, got $other")
    }

    "name the section and key of a bad extra" in {
      given ProviderRegistry = ProviderRegistry.default
      def error(extra: String): String =
        ProviderTestConfig
          .loadProvider(
            "pplx",
            s"""llm4s.providers.pplx { provider = "perplexity", model = "perplexity/sonar", apiKey = "k", $extra }"""
          )
          .fold(_.message, config => fail(s"expected an error, got $config"))
      error("webSearch = maybe") should include("llm4s.providers.pplx.webSearch")
      error("contextWindow = 0") should include("llm4s.providers.pplx.contextWindow")
      error("reserveCompletion = -1") should include("llm4s.providers.pplx.reserveCompletion")
      error("contextWindow = 100, reserveCompletion = 100") should include("llm4s.providers.pplx.reserveCompletion")
      error("""baseUrl = "http://api.perplexity.ai"""") should include("llm4s.providers.pplx.baseUrl")
    }

    "fail to load a section without a key, naming PERPLEXITY_API_KEY" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig
        .loadProvider("pplx", """llm4s.providers.pplx { provider = "perplexity", model = "perplexity/sonar" }""")
        .fold(_.message, config => fail(s"expected an error, got $config")) should include(
        "PERPLEXITY_API_KEY"
      )
    }
  }

  "a client built by the perplexity descriptor" should {

    "complete through the Agent API endpoint, with citations" in {
      val seenPath = new java.util.concurrent.atomic.AtomicReference[String]("")
      withServer("/") { exchange =>
        seenPath.set(exchange.getRequestURI.getPath)
        sendJsonResponse(exchange, 200, reply)
      } { baseUrl =>
        clientAt(baseUrl).complete(Conversation(Seq(UserMessage("Hi")))) match
          case Right(completion) =>
            completion.content shouldBe "Hello there [web:1]"
            completion.citations.map(_.url) shouldBe List("https://a.example/1", "https://b.example/2")
            completion.usage.map(_.totalTokens) shouldBe Some(17)
          case Left(error) => fail(error.message)
      }
      seenPath.get shouldBe "/v1/agent"
    }

    "send text, and refuse every other content part before sending" in {
      assertProviderRejectionIsTyped((exchange, status, message) =>
        sendJsonResponse(exchange, status, ujson.Obj("error" -> ujson.Obj("message" -> message)).render())
      )(clientAt)
      assertContentParts("perplexity", sendJsonResponse(_, 200, response(s"[${textMessage("ok")}]")))()(clientAt)
    }

    "send the key its ApiKeyProvider holds at each request" in {
      assertHonoursApiKeyProvider(sendJsonResponse(_, 200, response(s"[${textMessage("ok")}]"))) { (baseUrl, keys) =>
        new PerplexityClient(config(baseUrl = baseUrl).withApiKeyProvider(keys))
      }
    }

    "actually stream, not silently fall back to complete()" in {
      withServer("/")(sendSseResponse(_, sseText))(baseUrl => assertStreams(clientAt(baseUrl)))
    }

    "return CancelledError when a call is interrupted" in {
      withServer("/")(holdOpen)(baseUrl => assertCancelsWhenInterrupted(clientAt(baseUrl)))
    }

    "return CancelledError when a stream is interrupted after its first event" in {
      withServer("/")(streamThenHold(_, sseBody(delta("Hi")))) { baseUrl =>
        assertCancelsStreamWhenInterrupted(clientAt(baseUrl))
      }
    }
  }
