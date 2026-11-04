package org.llm4s.llmconnect.provider

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.LlmClientOptions
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ openAICompletion, sendJsonResponse, withServer }
import org.llm4s.testkit.{ ProviderModuleChecks, ProviderTestConfig }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{ Files, Path }
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** An openai-compatible section's `apiKeyFile` (#2313): a key rewritten in the file is sent without rebuilding the client. */
class OpenAICompatibleApiKeyFileSpec extends AnyWordSpec with Matchers with EitherValues:

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("compatible-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content)

  /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
  private def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

  "an openai-compatible section with apiKeyFile" should {

    "send the key the file holds at each request, and fail a request once the file is gone" in {
      val file = keyFile("sk-openai-first-0001\n")
      val seen = new ConcurrentLinkedQueue[String]()
      withServer("/") { exchange =>
        seen.add(Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("<none>"))
        sendJsonResponse(exchange, 200, openAICompletion("ok"))
      } { baseUrl =>
        given ProviderRegistry = ProviderRegistry.default
        val config = ProviderTestConfig
          .loadProvider(
            "gpt",
            s"""llm4s.providers.gpt {
               |  provider = "openai-compatible", model = "local-model", baseUrl = "$baseUrl"
               |  apiKeyFile = ${quoted(file)}
               |}""".stripMargin
          )
          .value
        given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
        val client                 = OpenAICompatibleProvider.buildClient(config, LlmClientOptions.default).value

        client.complete(hello).isRight shouldBe true
        Files.writeString(file, "sk-openai-rotated-00002")
        client.complete(hello).isRight shouldBe true

        Files.delete(file)
        val error = client.complete(hello).left.value
        error shouldBe a[ConfigurationError]
        error.message should include(file.toString)
        client.close()
      }
      seen.asScala.toList shouldBe List("Bearer sk-openai-first-0001", "Bearer sk-openai-rotated-00002")
    }
  }

  "a deepseek section with apiKeyFile, whose client does not rotate" should {

    "send the key the file held when the section was loaded" in {
      val file = keyFile("sk-deepseek-file-0001\n")
      val seen = new ConcurrentLinkedQueue[String]()
      withServer("/") { exchange =>
        seen.add(Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse("<none>"))
        sendJsonResponse(exchange, 200, openAICompletion("ok"))
      } { baseUrl =>
        given ProviderRegistry = ProviderRegistry.default
        val config = ProviderTestConfig
          .loadProvider(
            "ds",
            s"""llm4s.providers.ds {
               |  provider = "deepseek", model = "deepseek-chat", baseUrl = "$baseUrl"
               |  apiKeyFile = ${quoted(file)}
               |}""".stripMargin
          )
          .value
        config.apiKeyProvider shouldBe None
        given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
        val client                 = DeepSeekProvider.buildClient(config, LlmClientOptions.default).value
        client.complete(hello).isRight shouldBe true
        client.close()
      }
      seen.asScala.toList shouldBe List("Bearer sk-deepseek-file-0001")
    }
  }
