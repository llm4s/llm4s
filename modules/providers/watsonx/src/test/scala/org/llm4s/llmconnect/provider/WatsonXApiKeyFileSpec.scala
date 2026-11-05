package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * A watsonx section's `apiKeyFile` (#2477): a key rewritten in the file is exchanged for a new IAM token at the next
 * request, without rebuilding the client, and a cached token is reused while the key is unchanged.
 */
class WatsonXApiKeyFileSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("watsonx-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content)

  /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
  private def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

  "a watsonx section with apiKeyFile" should {

    "exchange the key the file holds, and a rotated key for a new token at the next request" in {
      val file     = keyFile("wxfirstkey0001\n")
      val exchange = new ConcurrentLinkedQueue[String]() // the apikey each IAM exchange sent
      val bearers  = new ConcurrentLinkedQueue[String]() // the Authorization each chat request sent
      withServer("/") { http =>
        val body = new String(http.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        if http.getRequestURI.getPath.endsWith("/identity/token") then
          val key = body.split('&').collectFirst { case p if p.startsWith("apikey=") => p.drop(7) }.getOrElse("")
          exchange.add(key)
          sendJsonResponse(http, 200, s"""{"access_token":"tok-$key","expires_in":3600}""")
        else
          bearers.add(Option(http.getRequestHeaders.getFirst("Authorization")).getOrElse("<none>"))
          sendJsonResponse(http, 200, StubHttp.chatReply.body)
      } { baseUrl =>
        given ProviderRegistry = ProviderRegistry.default
        val config = ProviderTestConfig
          .loadProvider(
            "wx",
            s"""llm4s.providers.wx {
               |  provider = "watsonx", model = "ibm/granite-4-h-small", projectId = "p-1"
               |  baseUrl = "$baseUrl", iamUrl = "$baseUrl/identity/token"
               |  apiKeyFile = ${quoted(file)}
               |}""".stripMargin
          )
          .value
        config.apiKeyProvider shouldBe defined
        val client = WatsonXProvider.buildClient(config, LlmClientOptions.default).value

        client.complete(hello).isRight shouldBe true
        client.complete(hello).isRight shouldBe true // the key is unchanged: the cached token is reused
        Files.writeString(file, "wxrotatedkey00002")
        client.complete(hello).isRight shouldBe true

        Files.delete(file)
        val error = client.complete(hello).left.value
        error shouldBe a[ConfigurationError]
        error.message should include(file.toString)
        client.close()
      }
      exchange.asScala.toList shouldBe List("wxfirstkey0001", "wxrotatedkey00002")
      bearers.asScala.toList shouldBe
        List("Bearer tok-wxfirstkey0001", "Bearer tok-wxfirstkey0001", "Bearer tok-wxrotatedkey00002")
    }
  }

  "a WatsonXClient whose ApiKeyProvider throws" should {

    "fail the request with an AuthenticationError and exchange nothing" in {
      val throwing = new ApiKeyProvider:
        def current(): Result[ApiKey] = throw new IllegalStateException("vault unreachable")
      val http   = StubHttp.routed(_ => Right(StubHttp.iamToken()), _ => Right(StubHttp.chatReply))
      val config = WatsonXTestConfig.config.withApiKeyProvider(throwing)
      config.toString should include("apiKeyProvider=")
      config.withApiKeyProvider(None).apiKeyProvider shouldBe None
      val client = new WatsonXClient(config, httpClient = http)
      client.complete(hello).left.value shouldBe an[AuthenticationError]
      http.requests shouldBe empty
    }
  }
