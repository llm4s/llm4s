package org.llm4s.llmconnect.provider

import org.llm4s.error.TimeoutError
import org.llm4s.llmconnect.config.{ BedrockConfig, ProviderTimeouts }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, StreamedChunk, UserMessage }
import org.llm4s.llmconnect.provider.BedrockTestSupport.*
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer.{ holdOpen, withServer }
import org.llm4s.testkit.ProviderTestConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*

/**
 * The `timeouts` block of a section reaches the Bedrock client (#712): `request` becomes the SDK's
 * API-call timeout for `Converse`, and `stream` a deadline on the whole `ConverseStream` call. Without
 * the block the SDK keeps its defaults and a stream has no limit.
 */
final class BedrockConfiguredTimeoutsSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val hello = Conversation(Seq(UserMessage("hello")))

  private def load(timeouts: String): BedrockConfig = {
    given ProviderRegistry = ProviderRegistry.default
    ProviderTestConfig.loadProvider(
      "br",
      s"""llm4s.providers.br {
         |  provider = "bedrock"
         |  model    = "$Model"
         |  region   = "us-east-1"
         |$timeouts
         |}
         |""".stripMargin
    ) match {
      case Right(config: BedrockConfig) => config
      case other                        => fail(s"expected a BedrockConfig, got $other")
    }
  }

  // ---- HOCON -> descriptor -> config ----

  "The Bedrock descriptor" should "carry the section's timeouts on its config" in {
    load("timeouts { request = 7s, stream = 11s }").timeouts shouldBe
      ProviderTimeouts(Some(7.seconds), Some(11.seconds))
  }

  it should "leave the timeouts unset for a section without the block" in {
    load("").timeouts shouldBe ProviderTimeouts.default
  }

  // ---- config -> client ----

  "BedrockClient" should "use the configured timeouts, and leave them unset without them" in {
    val configured =
      new BedrockClient(config("http://localhost:1").withTimeouts(ProviderTimeouts(Some(7.seconds), Some(11.seconds))))
    try {
      configured.requestTimeout shouldBe Some(7.seconds)
      configured.streamTimeout shouldBe Some(11.seconds)
    } finally configured.close()

    val default = new BedrockClient(config("http://localhost:1"))
    try {
      default.requestTimeout shouldBe None
      default.streamTimeout shouldBe None
    } finally default.close()
  }

  it should "accept a sub-millisecond request timeout rather than truncating it to zero" in {
    // The SDK rejects a zero apiCallTimeout, so 500us must reach it as nanoseconds, not as 0ms.
    val client =
      new BedrockClient(config("http://localhost:1").withTimeouts(ProviderTimeouts(Some(500.micros), None)))
    try client.requestTimeout shouldBe Some(500.micros)
    finally client.close()
  }

  // ---- the value reaches the wire ----

  "A configured request timeout" should "end a Converse call to a server that never answers" in {
    withServer("/")(holdOpen) { url =>
      val client = new BedrockClient(config(url).withTimeouts(ProviderTimeouts(request = Some(300.millis))))
      try {
        val started = System.nanoTime()
        val result  = client.complete(hello, CompletionOptions())
        val elapsed = (System.nanoTime() - started).nanos

        result match {
          case Left(e: TimeoutError) => e.timeoutDuration shouldBe 300.millis
          case other                 => fail(s"expected a TimeoutError, got $other")
        }
        elapsed should be < 10.seconds
      } finally client.close()
    }
  }

  "A configured stream timeout" should "end a ConverseStream call that stalls mid-stream" in {
    withServer("/")(
      streamFramesThenHold(_, Seq(eventFrame("messageStart", """{"role":"assistant"}"""), textDelta("Hi")))
    ) { url =>
      val client = new BedrockClient(config(url).withTimeouts(ProviderTimeouts(stream = Some(500.millis))))
      val chunks = ListBuffer.empty[StreamedChunk]
      try {
        val started = System.nanoTime()
        val result  = client.streamComplete(hello, CompletionOptions(), chunks += _)
        val elapsed = (System.nanoTime() - started).nanos

        result match {
          case Left(e: TimeoutError) => e.timeoutDuration shouldBe 500.millis
          case other                 => fail(s"expected a TimeoutError, got $other")
        }
        chunks.flatMap(_.content) shouldBe Seq("Hi")
        elapsed should be < 10.seconds
      } finally client.close()
    }
  }
}
