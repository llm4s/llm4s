package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.testkit.LocalProviderTestServer.{ holdOpen, sendJsonResponse, streamThenHold, withServer }
import org.llm4s.testkit.{ CredentialsRoundTrip, ProviderModuleChecks, ProviderTestConfig }
import org.llm4s.types.ProviderModelTypes.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-ollama` registers itself, and what it registers works.
 *
 * This is Ollama's row of core's `BuiltinProvidersSpec`, which it left with the
 * provider (#1132), plus the part that only a carved module has to prove: that
 * depending on it is enough - the services entry is found and both halves arrive.
 */
class Llm4sOllamaModuleSpec extends AnyWordSpec with Matchers with ProviderModuleChecks:

  private val section = NamedProviderConfig(
    provider = OllamaProvider.id,
    model = ModelName("llama3.1"),
    baseUrl = Some(BaseUrl("http://localhost:11434")),
    apiKey = None,
  )

  /** One NDJSON chat chunk then a done line, standing in for Ollama's API. */
  private val ndjsonBody: String = Seq(
    """{"message":{"role":"assistant","content":"Hi"},"done":false}""",
    """{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":1,"eval_count":1}"""
  ).mkString("", "\n", "\n")

  private def clientAt(baseUrl: String): LLMClient =
    assertBuildsClient(OllamaProvider, section.withBaseUrl(Some(BaseUrl(baseUrl))))

  "the llm4s-ollama services entry" should {

    "be discovered, the only supplier of ollama for chat and embeddings, and registrable explicitly" in {
      assertModule(new Llm4sOllamaModule)
    }

    "contribute both halves under the id ollama" in {
      val module = new Llm4sOllamaModule
      module.chatProviders shouldBe Seq(OllamaProvider)
      module.embeddingProviders shouldBe Seq(OllamaEmbeddingProvider)
    }
  }

  "OllamaProvider" should {

    "build an OllamaClient from a config section" in {
      assertBuildsClient(OllamaProvider, section).getClass.getSimpleName shouldBe "OllamaClient"
    }

    "load an OllamaConfig from a named section, as an application does" in {
      given ProviderRegistry = ProviderRegistry.default
      ProviderTestConfig
        .loadProvider(
          "local",
          """llm4s.providers.local { provider = "ollama", model = "llama3.1", baseUrl = "http://localhost:11434" }"""
        )
        .map(_.getClass.getSimpleName) shouldBe Right("OllamaConfig")
    }

    "refuse a config belonging to another provider" in {
      assertRefusesForeignConfig(OllamaProvider)
    }

    "declare a model lister" in {
      OllamaProvider.modelLister shouldBe defined
    }
  }

  "a client built by the ollama descriptor" should {

    "actually stream, not silently fall back to complete()" in {
      withServer("/")(exchange => sendJsonResponse(exchange, 200, ndjsonBody)) { baseUrl =>
        assertStreams(clientAt(baseUrl))
      }
    }

    "return CancelledError when a call is interrupted" in {
      withServer("/")(holdOpen)(baseUrl => assertCancelsWhenInterrupted(clientAt(baseUrl)))
    }

    "return CancelledError when a stream is interrupted after its first event" in {
      val firstEvent = ndjsonBody.linesIterator.next() + "\n"
      withServer("/")(streamThenHold(_, firstEvent, "application/x-ndjson")) { baseUrl =>
        assertCancelsStreamWhenInterrupted(clientAt(baseUrl))
      }
    }
  }

  "the llm4s-ollama reference.conf" should {

    given ProviderRegistry = ProviderRegistry.default

    "bind no key, since Ollama takes none" in {
      OllamaProvider.configSpec.apiKeyEnv shouldBe empty
      OllamaEmbeddingProvider.configSpec.apiKeyEnv shouldBe empty
      CredentialsRoundTrip.chatSectionKey("ollama", Map.empty, """baseUrl = "http://localhost:11434"""") shouldBe
        Right(None)
    }
  }
