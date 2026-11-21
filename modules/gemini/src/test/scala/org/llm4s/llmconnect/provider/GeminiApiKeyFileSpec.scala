package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, ConfigurationError }
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.testkit.LocalProviderTestServer.{ sendJsonResponse, withServer }
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.ApiKey
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** A gemini section's `apiKeyFile` (#2477): a key rewritten in the file is sent without rebuilding the client. */
class GeminiApiKeyFileSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private val reply =
    """{"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"},"finishReason":"STOP"}]}"""

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("gemini-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content)

  /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
  private def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

  /** The `key` query parameter of each request the server saw. */
  private def keyParam(rawQuery: String): String =
    Option(rawQuery).toList
      .flatMap(_.split('&'))
      .collectFirst { case param if param.startsWith("key=") => param.drop(4) }
      .getOrElse("<none>")

  "a gemini section with apiKeyFile" should {

    "send the key the file holds at each request, and fail a request once the file is gone" in {
      val file = keyFile("gemini-first-key-0001\n")
      val seen = new ConcurrentLinkedQueue[String]()
      withServer("/") { exchange =>
        seen.add(keyParam(exchange.getRequestURI.getRawQuery))
        sendJsonResponse(exchange, 200, reply)
      } { baseUrl =>
        given ProviderRegistry = ProviderRegistry.default
        val config = ProviderTestConfig
          .loadProvider(
            "gem",
            s"""llm4s.providers.gem {
               |  provider = "gemini", model = "gemini-2.5-flash", baseUrl = "$baseUrl"
               |  apiKeyFile = ${quoted(file)}
               |}""".stripMargin
          )
          .value
        config.apiKeyProvider shouldBe defined
        val client = GeminiProvider.buildClient(config, LlmClientOptions.default).value

        client.complete(hello).isRight shouldBe true
        Files.writeString(file, "gemini-rotated-key-00002")
        client.complete(hello).isRight shouldBe true

        Files.delete(file)
        val error = client.complete(hello).left.value
        error shouldBe a[ConfigurationError]
        error.message should include(file.toString)
        client.close()
      }
      seen.asScala.toList shouldBe List("gemini-first-key-0001", "gemini-rotated-key-00002")
    }
  }

  "a GeminiClient whose ApiKeyProvider throws" should {

    "fail the request with an AuthenticationError and send nothing" in {
      val throwing = new ApiKeyProvider:
        def current(): Result[ApiKey] = throw new IllegalStateException("vault unreachable")
      val seen = new ConcurrentLinkedQueue[String]()
      withServer("/") { exchange =>
        seen.add(exchange.getRequestURI.toString)
        sendJsonResponse(exchange, 200, reply)
      } { baseUrl =>
        val config = GeminiConfig(Secret(""), "gemini-2.5-flash").withBaseUrl(baseUrl).withApiKeyProvider(throwing)
        config.toString should include("apiKeyProvider=")
        config.withApiKeyProvider(None).apiKeyProvider shouldBe None
        val client = GeminiClient(config).value
        client.complete(hello).left.value shouldBe an[AuthenticationError]
        client.streamComplete(hello, onChunk = _ => ()).left.value shouldBe an[AuthenticationError]
        client.close()
      }
      seen.asScala shouldBe empty
    }
  }
