package org.llm4s.llmconnect.provider

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.{ GeminiConfig, ProviderTimeouts, VertexAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*

/**
 * The `timeouts` block of a section reaches the Gemini and Vertex AI clients (#712). Without one they
 * keep their two-minute request and ten-minute stream timeouts.
 *
 * These clients send with the JDK HTTP client, whose timeout bounds the wait for the response to begin:
 * a server that never answers is what ends a call here.
 */
final class GeminiConfiguredTimeoutsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def hello: Conversation = Conversation(Seq(UserMessage("hello")))

  private def gemini(baseUrl: String, timeouts: ProviderTimeouts): GeminiConfig =
    GeminiConfig("test-key", "gemini-2.0-flash", baseUrl, 1048576, 8192).withTimeouts(timeouts)

  private def vertex(timeouts: ProviderTimeouts): VertexAIConfig =
    VertexAIConfig("my-project", "us-central1", "gemini-2.0-flash", None, 1048576, 8192).withTimeouts(timeouts)

  // ---- HOCON -> descriptor -> config ----

  private def load(provider: String, extra: String, timeouts: String) =
    Llm4sConfig
      .provider(
        ConfigSource.string(
          s"""llm4s.providers.main {
             |  provider = "$provider"
             |  model    = "gemini-2.0-flash"
             |$extra
             |$timeouts
             |}
             |""".stripMargin
        ),
        "main"
      )
      .value

  "The Gemini and Vertex AI descriptors" should "carry the section's timeouts on their configs" in {
    val block    = "timeouts { request = 7s, stream = 11s }"
    val expected = ProviderTimeouts(Some(7.seconds), Some(11.seconds))
    load("gemini", """apiKey = "google-key"""", block).timeouts shouldBe expected
    load("vertexai", """project = "my-project"""", block).timeouts shouldBe expected
  }

  they should "leave the timeouts unset for a section without the block" in {
    load("gemini", """apiKey = "google-key"""", "").timeouts shouldBe ProviderTimeouts.default
  }

  // ---- config -> client ----

  "GeminiClient" should "use the configured timeouts, and its own defaults without them" in {
    val configured = new GeminiClient(gemini("http://localhost:1", ProviderTimeouts(Some(7.seconds), Some(11.seconds))))
    configured.requestTimeout shouldBe 7.seconds
    configured.streamTimeout shouldBe 11.seconds

    val default = new GeminiClient(gemini("http://localhost:1", ProviderTimeouts.default))
    default.requestTimeout shouldBe 120.seconds
    default.streamTimeout shouldBe 10.minutes
    GeminiClient.DefaultRequestTimeout shouldBe 2.minutes
    GeminiClient.DefaultStreamTimeout shouldBe 10.minutes
  }

  "VertexAIClient" should "use the configured timeouts, and its own defaults without them" in {
    val configured = new VertexAIClient(vertex(ProviderTimeouts(Some(7.seconds), Some(11.seconds))))
    configured.requestTimeout shouldBe 7.seconds
    configured.streamTimeout shouldBe 11.seconds

    val default = new VertexAIClient(vertex(ProviderTimeouts.default))
    default.requestTimeout shouldBe 120.seconds
    default.streamTimeout shouldBe 10.minutes
  }

  it should "take either timeout alone" in {
    val client = new VertexAIClient(vertex(ProviderTimeouts(request = Some(3.seconds))))
    client.requestTimeout shouldBe 3.seconds
    client.streamTimeout shouldBe 10.minutes
  }

  // ---- the value reaches the wire ----

  "A configured request timeout" should "end a Gemini call to a server that never answers" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val client  = new GeminiClient(gemini(baseUrl, ProviderTimeouts(request = Some(300.millis))))
      val started = System.nanoTime()
      val result  = client.complete(hello, CompletionOptions())
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      // The default is two minutes; the configured 300ms must be what ended the call.
      elapsed should be < 10.seconds
    }
  }

  "A configured stream timeout" should "end a streamed Gemini call to a server that never answers" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val client  = new GeminiClient(gemini(baseUrl, ProviderTimeouts(stream = Some(300.millis))))
      val started = System.nanoTime()
      val result  = client.streamComplete(hello, CompletionOptions(), _ => ())
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      elapsed should be < 10.seconds
    }
  }
}
