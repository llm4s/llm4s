package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ DeepSeekConfig, OpenAICompatibleConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.*

/**
 * Request timeouts on the shared client (#1132 follow-up).
 *
 * `complete` used to send with no timeout, as the old `DeepSeekClient`, `ZaiClient` and
 * `OpenRouterClient` did (#912), so an endpoint that accepted the connection and never answered
 * hung the caller for good. It now carries the two-minute default the old Mistral and Cohere
 * clients used; streaming keeps its five minutes.
 */
class OpenAICompatibleTimeoutSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def settings(baseUrl: String) =
    OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", baseUrl, None))

  "OpenAICompatibleClient" should "default to a two-minute request timeout and a five-minute stream timeout" in {
    OpenAICompatibleClient.RequestTimeout shouldBe 2.minutes
    OpenAICompatibleClient.StreamTimeout shouldBe 5.minutes
  }

  it should "use those timeouts for its requests" in {
    // That they reach the wire is shown below, against a server that never answers
    val client = new OpenAICompatibleClient(settings("http://localhost:1/v1"), OpenAICompatibleDialect.Standard)
    client.requestTimeout shouldBe OpenAICompatibleClient.RequestTimeout
    client.streamTimeout shouldBe OpenAICompatibleClient.StreamTimeout
  }

  it should "give every dialect's client the same timeouts" in {
    val deepSeek = new DeepSeekClient(DeepSeekConfig("k", "deepseek-chat", "http://localhost:1", 128000, 8192))
    deepSeek.requestTimeout shouldBe OpenAICompatibleClient.RequestTimeout
    deepSeek.streamTimeout shouldBe OpenAICompatibleClient.StreamTimeout
  }

  /** A client whose timeouts are short enough to exercise in a test. */
  private def impatientClient(baseUrl: String) =
    new OpenAICompatibleClient(settings(baseUrl), OpenAICompatibleDialect.Standard) {
      override protected[provider] def requestTimeout: FiniteDuration = 300.millis
      override protected[provider] def streamTimeout: FiniteDuration  = 300.millis
    }

  /** Runs `test` against a server that accepts each request and never answers it. */
  private def withSilentServer(test: String => Any): Unit = {
    // Released before the server stops, so stopping it does not wait on a parked handler.
    val release = new CountDownLatch(1)
    withServer("/chat/completions")(_ => release.await(10, TimeUnit.SECONDS)) { baseUrl =>
      try test(baseUrl)
      finally release.countDown()
    }
  }

  it should "fail complete once the request timeout passes, rather than wait for ever" in {
    withSilentServer { baseUrl =>
      val started = System.nanoTime()
      val result  = impatientClient(baseUrl).complete(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      result.left.value.message.toLowerCase should include("timed out")
      elapsed should be < 5.seconds
    }
  }

  it should "fail streamComplete once the stream timeout passes" in {
    withSilentServer { baseUrl =>
      val result =
        impatientClient(baseUrl).streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
      result.isLeft shouldBe true
    }
  }
}
