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

/** An openai section's `apiKeyFile` (#2313): a key rewritten in the file is sent without rebuilding the client. */
class OpenAIApiKeyFileSpec extends AnyWordSpec with Matchers with EitherValues:

  private val hello = Conversation(Seq(UserMessage("Hello")))

  private def keyFile(content: String): Path =
    val file = Files.createTempFile("openai-key", ".txt")
    file.toFile.deleteOnExit()
    Files.writeString(file, content)

  /** A HOCON string for `path`: quoted and escaped, since a Windows path holds backslashes. */
  private def quoted(path: Path): String = "\"" + path.toString.replace("\\", "\\\\") + "\""

  "an openai section with apiKeyFile" should {

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
               |  provider = "openai", model = "gpt-4o", baseUrl = "$baseUrl"
               |  apiKeyFile = ${quoted(file)}
               |}""".stripMargin
          )
          .value
        given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
        val client                 = OpenAIProvider.buildClient(config, LlmClientOptions.default).value

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

  "an azure section with apiKeyFile" should {

    "send the key the file holds in the api-key header at each request, without rebuilding the client (#2477)" in {
      val file = keyFile("azure-first-key-0001\n")
      val seen = new ConcurrentLinkedQueue[String]()
      withServer("/") { exchange =>
        seen.add(Option(exchange.getRequestHeaders.getFirst("api-key")).getOrElse("<none>"))
        sendJsonResponse(exchange, 200, openAICompletion("ok"))
      } { baseUrl =>
        given ProviderRegistry = ProviderRegistry.default
        val config = ProviderTestConfig
          .loadProvider(
            "az",
            s"""llm4s.providers.az {
               |  provider = "azure", model = "gpt-4o", endpoint = "$baseUrl"
               |  apiKeyFile = ${quoted(file)}
               |}""".stripMargin
          )
          .value
        config.apiKeyProvider shouldBe defined
        given ModelRegistryService = ProviderModuleChecks.defaultModelRegistry
        val client                 = AzureProvider.buildClient(config, LlmClientOptions.default).value

        client.complete(hello).isRight shouldBe true
        Files.writeString(file, "azure-rotated-key-00002")
        client.complete(hello).isRight shouldBe true

        Files.delete(file)
        val error = client.complete(hello).left.value
        error shouldBe a[ConfigurationError]
        error.message should include(file.toString)
        client.close()
      }
      seen.asScala.toList shouldBe List("azure-first-key-0001", "azure-rotated-key-00002")
    }
  }
