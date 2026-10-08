package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ProviderTimeouts, WatsonXConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.ProviderTestConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * The `timeouts` block of a section reaches the watsonx client (#712). Without one a generation call
 * keeps its two minutes and a streamed one its ten; the IAM exchange keeps its own 30 seconds either way.
 */
final class WatsonXConfiguredTimeoutsSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*

  private val hi = Conversation(Seq(UserMessage("Hi")))

  private def load(timeouts: String): WatsonXConfig = {
    given ProviderRegistry = ProviderRegistry.default
    ProviderTestConfig.loadProvider(
      "wx",
      s"""llm4s.providers.wx {
         |  provider  = "watsonx"
         |  model     = "ibm/granite-13b-instruct-v2"
         |  projectId = "p-1"
         |  apiKey    = "k"
         |$timeouts
         |}
         |""".stripMargin
    ) match {
      case Right(config: WatsonXConfig) => config
      case other                        => fail(s"expected a WatsonXConfig, got $other")
    }
  }

  // ---- HOCON -> descriptor -> config ----

  "The watsonx descriptor" should "carry the section's timeouts on its config" in {
    load("timeouts { request = 7s, stream = 11s }").timeouts shouldBe
      ProviderTimeouts(Some(7.seconds), Some(11.seconds))
  }

  it should "leave the timeouts unset for a section without the block" in {
    load("").timeouts shouldBe ProviderTimeouts.default
  }

  // ---- config -> client ----

  "WatsonXClient" should "use the configured timeouts, and its own defaults without them" in {
    val configured = new WatsonXClient(
      WatsonXTestConfig.config.withTimeouts(ProviderTimeouts(Some(7.seconds), Some(11.seconds))),
      httpClient = new StubHttp(_ => Right(iamToken()))
    )
    configured.requestTimeout shouldBe 7.seconds
    configured.streamTimeout shouldBe 11.seconds

    val default = new WatsonXClient(WatsonXTestConfig.config, httpClient = new StubHttp(_ => Right(iamToken())))
    default.requestTimeout shouldBe 2.minutes
    default.streamTimeout shouldBe 10.minutes
  }

  // ---- the value reaches the wire ----

  "A configured request timeout" should "be the timeout of the generation call, not of the IAM exchange" in {
    val http = routed(_ => Right(iamToken()), _ => Right(generation))
    val client = new WatsonXClient(
      WatsonXTestConfig.config.withTimeouts(ProviderTimeouts(request = Some(7.seconds))),
      httpClient = http
    )
    client.complete(hi, CompletionOptions()).isRight shouldBe true

    http.modelRequests.map(_.timeout) shouldBe Seq(7.seconds)
    http.iamRequests.map(_.timeout) shouldBe Seq(30.seconds)
  }

  "A configured stream timeout" should "be the timeout of the streamed call" in {
    val sse =
      """data: {"results":[{"generated_text":"Hi","generated_token_count":1,"input_token_count":3,"stop_reason":"eos_token"}]}
        |
        |""".stripMargin
    val http = streaming(streamOf(bytes(sse)))
    val client = new WatsonXClient(
      WatsonXTestConfig.config.withTimeouts(ProviderTimeouts(stream = Some(11.seconds))),
      httpClient = http
    )
    client.streamComplete(hi, CompletionOptions(), _ => ()).isRight shouldBe true

    http.modelRequests.filter(_.streaming).map(_.timeout) shouldBe Seq(11.seconds)
  }

  "A client without configured timeouts" should "send its own defaults" in {
    val http   = routed(_ => Right(iamToken()), _ => Right(generation))
    val client = new WatsonXClient(WatsonXTestConfig.config, httpClient = http)
    client.complete(hi, CompletionOptions()).isRight shouldBe true

    http.modelRequests.map(_.timeout) shouldBe Seq(WatsonXClient.DefaultRequestTimeout)
  }
}
