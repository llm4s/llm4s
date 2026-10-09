package org.llm4s.llmconnect.provider

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig, OllamaConfig, ProviderTimeouts }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, EmbeddingRequest, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*

/**
 * The `timeouts` block of a section reaches the Ollama chat client and the Ollama embedding provider
 * (#712). Without one the chat client keeps its two-minute request and ten-minute stream timeouts, and
 * embeddings their two minutes.
 *
 * These clients send with the JDK HTTP client, whose timeout bounds the wait for the response to begin:
 * a server that never answers is what ends a call here.
 */
final class OllamaConfiguredTimeoutsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def hello: Conversation = Conversation(Seq(UserMessage("hello")))

  private def config(baseUrl: String, timeouts: ProviderTimeouts): OllamaConfig =
    OllamaConfig(model = "llama3.1", baseUrl = baseUrl, contextWindow = 4096, reserveCompletion = 512)
      .withTimeouts(timeouts)

  // ---- HOCON -> descriptor -> config ----

  private def load(timeouts: String) =
    Llm4sConfig
      .provider(
        ConfigSource.string(
          s"""llm4s.providers.main {
             |  provider = "ollama"
             |  model    = "llama3.1"
             |  baseUrl  = "http://localhost:11434"
             |$timeouts
             |}
             |""".stripMargin
        ),
        "main"
      )
      .value

  "The Ollama descriptor" should "carry the section's timeouts on its config" in {
    load("timeouts { request = 7s, stream = 11s }").timeouts shouldBe
      ProviderTimeouts(Some(7.seconds), Some(11.seconds))
  }

  it should "leave the timeouts unset for a section without the block" in {
    load("").timeouts shouldBe ProviderTimeouts.default
  }

  // ---- config -> client ----

  "OllamaClient" should "use the configured timeouts, and its own defaults without them" in {
    val configured = new OllamaClient(config("http://localhost:1", ProviderTimeouts(Some(7.seconds), Some(11.seconds))))
    configured.requestTimeout shouldBe 7.seconds
    configured.streamTimeout shouldBe 11.seconds

    val default = new OllamaClient(config("http://localhost:1", ProviderTimeouts.default))
    default.requestTimeout shouldBe 120.seconds
    default.streamTimeout shouldBe 10.minutes
    OllamaClient.DefaultRequestTimeout shouldBe 2.minutes
    OllamaClient.DefaultStreamTimeout shouldBe 10.minutes
  }

  it should "take either timeout alone" in {
    val client = new OllamaClient(config("http://localhost:1", ProviderTimeouts(request = Some(3.seconds))))
    client.requestTimeout shouldBe 3.seconds
    client.streamTimeout shouldBe 10.minutes
  }

  // ---- the value reaches the wire ----

  "A configured request timeout" should "end a call to a server that never answers" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val client = new OllamaClient(config(baseUrl, ProviderTimeouts(request = Some(300.millis))))
      try {
        val started = System.nanoTime()
        val result  = client.complete(hello, CompletionOptions())
        val elapsed = (System.nanoTime() - started).nanos

        result.isLeft shouldBe true
        // The default is two minutes; the configured 300ms must be what ended the call.
        elapsed should be < 10.seconds
      } finally client.close()
    }
  }

  "A configured stream timeout" should "end a streamed call to a server that never answers" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val client = new OllamaClient(config(baseUrl, ProviderTimeouts(stream = Some(300.millis))))
      try {
        val started = System.nanoTime()
        val result  = client.streamComplete(hello, CompletionOptions(), _ => ())
        val elapsed = (System.nanoTime() - started).nanos

        result.isLeft shouldBe true
        elapsed should be < 10.seconds
      } finally client.close()
    }
  }

  "A configured request timeout on an embedding section" should "end an embedding call to a server that never answers" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val provider = OllamaEmbeddingProvider.fromConfig(
        EmbeddingProviderConfig(
          baseUrl = baseUrl,
          model = "nomic-embed-text",
          apiKey = "ollama",
          timeouts = ProviderTimeouts(request = Some(300.millis))
        )
      )
      val started = System.nanoTime()
      val result  = provider.embed(EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("nomic-embed-text", 768)))
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      elapsed should be < 10.seconds
    }
  }
}
