package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ anthropicMessage, sendJsonResponse, withServer }
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** An anthropic section's `apiKeyFile` (#2313): a key rewritten in the file is sent without rebuilding the client. */
class AnthropicApiKeyFileSpec extends AnyWordSpec with Matchers with EitherValues:

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("anthropic-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content)

  /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
  private def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

  "an anthropic section with apiKeyFile" should {

    "send the key the file holds at each request, and fail a request once the file is gone" in {
      val file = keyFile("sk-ant-first-key-0001\n")
      val seen = new ConcurrentLinkedQueue[String]()
      withServer("/v1/messages") { exchange =>
        seen.add(Option(exchange.getRequestHeaders.getFirst("x-api-key")).getOrElse("<none>"))
        sendJsonResponse(exchange, 200, anthropicMessage("ok"))
      } { baseUrl =>
        given ProviderRegistry = ProviderRegistry.default
        val config = ProviderTestConfig
          .loadProvider(
            "claude",
            s"""llm4s.providers.claude {
               |  provider = "anthropic", model = "claude-sonnet-4-5", baseUrl = "$baseUrl"
               |  apiKeyFile = ${quoted(file)}
               |}""".stripMargin
          )
          .value
        given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
        val client                 = AnthropicProvider.buildClient(config, LlmClientOptions.default).value

        client.complete(hello).isRight shouldBe true
        Files.writeString(file, "sk-ant-rotated-key-00002")
        client.complete(hello).isRight shouldBe true

        Files.delete(file)
        val error = client.complete(hello).left.value
        error shouldBe a[ConfigurationError]
        error.message should include(file.toString)
        client.close()
      }
      seen.asScala.toList shouldBe List("sk-ant-first-key-0001", "sk-ant-rotated-key-00002")
    }
  }
