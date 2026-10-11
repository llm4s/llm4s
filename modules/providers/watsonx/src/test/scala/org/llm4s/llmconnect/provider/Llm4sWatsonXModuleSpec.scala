package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.WatsonXConfig
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
 * `llm4s-watsonx` registers itself, and what it registers works: discovery finds the services
 * entry, the module is the only supplier of `watsonx`, and a config section round-trips to a
 * client that talks to a (local) server - IAM exchange included.
 */
class Llm4sWatsonXModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private val IamPath = "/iam"

  private val tokenBody = """{"access_token":"iam-token","expires_in":3600}"""

  private val sseBody: String = Seq(
    """{"choices":[{"delta":{"content":"Hi"},"finish_reason":null}],"usage":{"prompt_tokens":4,"completion_tokens":1,"total_tokens":5}}""",
    """{"choices":[{"delta":{"content":"!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}"""
  ).map(data => s"id: 1\nevent: message\ndata: $data\n\n").mkString

  private def section(baseUrl: String): NamedProviderConfig =
    NamedProviderConfig(
      provider = WatsonXProvider.id,
      model = ModelName("ibm/granite-13b-instruct-v2"),
      baseUrl = Some(BaseUrl(baseUrl)),
      apiKey = Some(ApiKey("test-key")),
      extras = Map(
        WatsonXProvider.ProjectIdKey -> "project-1",
        WatsonXProvider.IamUrlKey    -> s"$baseUrl$IamPath"
      )
    )

  /** IAM answers on `/iam`; everything else goes to `model`. */
  private def routed(model: HttpExchange => Unit)(exchange: HttpExchange): Unit =
    if exchange.getRequestURI.getPath == IamPath then sendJsonResponse(exchange, 200, tokenBody)
    else model(exchange)

  private def clientAt(baseUrl: String): LLMClient = assertBuildsClient(WatsonXProvider, section(baseUrl))

  "the llm4s-watsonx services entry" should {

    "be discovered, the only supplier of watsonx, and registrable explicitly" in {
      assertModule(new Llm4sWatsonXModule)
    }

    "contribute the chat provider under the id watsonx and no embeddings" in {
      val module = new Llm4sWatsonXModule
      module.chatProviders shouldBe Seq(WatsonXProvider)
      module.embeddingProviders shouldBe empty
      WatsonXProvider.id.asString shouldBe "watsonx"
    }
  }

  "WatsonXProvider" should {

    "build a WatsonXClient from a config section" in {
      assertBuildsClient(WatsonXProvider, section("http://localhost:1")).getClass.getSimpleName shouldBe "WatsonXClient"
    }

    "refuse a config belonging to another provider" in {
      assertRefusesForeignConfig(WatsonXProvider)
    }

    "bind WATSONX_API_KEY to the shared credential" in {
      assertCredentialBindings(WatsonXProvider)
    }

    "load a WatsonXConfig from a named section with the shared key, as an application does" in {
      given ProviderRegistry = ProviderRegistry.default
      val loaded = ProviderTestConfig.loadProvider(
        "wx",
        """llm4s.providers.wx { provider = "watsonx", model = "ibm/granite-13b-instruct-v2", projectId = "p-1" }""",
        env = Map("WATSONX_API_KEY" -> "env-key")
      )
      loaded match
        case Right(config: WatsonXConfig) =>
          config.apiKey shouldBe "env-key"
          config.projectId shouldBe "p-1"
          config.baseUrl shouldBe WatsonXConfig.DEFAULT_BASE_URL
          config.apiVersion shouldBe WatsonXConfig.DEFAULT_API_VERSION
        case other => fail(s"expected a WatsonXConfig, got $other")
    }

    "fail to load a section that names neither a project nor a space" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig
        .loadProvider(
          "wx",
          """llm4s.providers.wx { provider = "watsonx", model = "ibm/granite-13b-instruct-v2" }""",
          env = Map("WATSONX_API_KEY" -> "env-key")
        )
        .left
        .map(_.message) match
        case Left(message) => message should include("projectId or a spaceId")
        case Right(config) => fail(s"expected a configuration error, got $config")
    }
  }

  "a client built by the watsonx descriptor" should {

    "complete through the IAM exchange and the chat endpoint" in {
      val body =
        """{"id":"gen-1","choices":[{"index":0,"message":{"role":"assistant","content":"Hello there"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}"""
      withServer("/")(routed(sendJsonResponse(_, 200, body))) { baseUrl =>
        clientAt(baseUrl).complete(
          org.llm4s.llmconnect.model.Conversation(Seq(org.llm4s.llmconnect.model.UserMessage("Hi")))
        ) match
          case Right(completion) =>
            completion.content shouldBe "Hello there"
            completion.usage.map(_.totalTokens) shouldBe Some(8)
          case Left(error) => fail(error.message)
      }
    }

    "actually stream, not silently fall back to complete()" in {
      withServer("/")(routed(sendSseResponse(_, sseBody)))(baseUrl => assertStreams(clientAt(baseUrl)))
    }

    "return CancelledError when a call is interrupted" in {
      withServer("/")(routed(holdOpen))(baseUrl => assertCancelsWhenInterrupted(clientAt(baseUrl)))
    }

    "return CancelledError when a stream is interrupted after its first event" in {
      val firstEvent = sseBody.split("\n\n").head + "\n\n"
      withServer("/")(routed(streamThenHold(_, firstEvent))) { baseUrl =>
        assertCancelsStreamWhenInterrupted(clientAt(baseUrl))
      }
    }
  }
