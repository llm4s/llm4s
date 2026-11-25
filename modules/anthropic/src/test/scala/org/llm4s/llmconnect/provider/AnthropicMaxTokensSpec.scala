package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, RequestOption, UserMessage }
import org.llm4s.model.{ ModelMetadata, ModelRegistryService, ModelRegistryTestSupport }
import org.llm4s.security.Secret
import org.llm4s.testkit.{ Applicability, ContractCase, LocalProviderTestServer, RecordedReply }
import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * The `max_tokens` an Anthropic request carries ([[AnthropicMaxTokens]]): the caller's, unchanged, or a default that
 * follows the model. Each request here goes through the real SDK to a local server, so a default the SDK would
 * refuse before sending fails the call.
 */
class AnthropicMaxTokensSpec extends AnyFlatSpec with Matchers {
  import AnthropicMaxTokensSpec.*

  /** The body of the one request `send` makes to `model`, and its result. */
  private def sent(model: String, registry: ModelRegistryService, streaming: Boolean)(
    send: AnthropicClient => Result[Completion]
  ): (ujson.Value, Result[Completion]) = {
    val seen    = new AtomicReference[String]("")
    var result  = Option.empty[Result[Completion]]
    val respond = if (streaming) text.stream else text.complete
    LocalProviderTestServer.withServer("/") { exchange =>
      seen.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      respond(exchange)
    } { baseUrl =>
      val config = AnthropicConfig(
        apiKey = Secret("test-key"),
        model = ModelName(model),
        baseUrl = BaseUrl(baseUrl),
        contextWindow = 200000,
        reserveCompletion = 4096
      )
      val client = new AnthropicClient(config)(using registry)
      result = Some(send(client))
      client.close()
    }
    (ujson.read(seen.get()), result.getOrElse(fail("the call did not run")))
  }

  private def maxTokens(
    model: String,
    options: CompletionOptions,
    streaming: Boolean,
    registry: ModelRegistryService = bundled
  ): Int = {
    val (body, result) = sent(model, registry, streaming) { client =>
      if (streaming) client.streamComplete(hello, options)(_ => ()) else client.complete(hello, options)
    }
    withClue(s"$model, streaming=$streaming: ")(result.isRight shouldBe true)
    body("max_tokens").num.toInt
  }

  private def registryWith(model: String, maxOutput: Int): ModelRegistryService =
    ModelRegistryService.fromModels(
      Seq(ModelMetadata(ModelName(model), ProviderId("anthropic"), maxOutputTokens = Some(maxOutput)))
    )

  "A streamed request without maxTokens" should "ask for the model's maximum output tokens, from the registry" in {
    bundled.lookup(ModelName("claude-sonnet-5-5")).toOption.flatMap(_.maxOutputTokens) shouldBe Some(128000)
    maxTokens("claude-sonnet-5-5", CompletionOptions(), streaming = true) shouldBe 128000
    maxTokens("claude-haiku-4-5", CompletionOptions(), streaming = true) shouldBe 64000
  }

  "A non-streamed request without maxTokens" should "ask for the model's maximum, capped at 16,000" in {
    maxTokens("claude-sonnet-5-5", CompletionOptions(), streaming = false) shouldBe 16000
    maxTokens("claude-haiku-4-5", CompletionOptions(), streaming = false) shouldBe 16000
  }

  it should "ask for a smaller model's own maximum" in {
    val small = registryWith("claude-small", 4096)
    maxTokens("claude-small", CompletionOptions(), streaming = false, small) shouldBe 4096
    maxTokens("claude-small", CompletionOptions(), streaming = true, small) shouldBe 4096
  }

  "A model the registry does not know" should "get the documented fallback, streamed or not" in {
    bundled.lookup(ModelName("claude-next-9")).isLeft shouldBe true
    maxTokens("claude-next-9", CompletionOptions(), streaming = false) shouldBe AnthropicMaxTokens.UnknownModelDefault
    maxTokens("claude-next-9", CompletionOptions(), streaming = true) shouldBe AnthropicMaxTokens.UnknownModelDefault
    AnthropicMaxTokens.UnknownModelDefault shouldBe 8192
  }

  // The SDK's check (RequestOptions.applyDefaultTimeoutFromMaxTokens) would refuse these before sending; in 2.65.0
  // it is skipped, because the SDK client always carries its default timeout. These fail if an upgrade changes that,
  // and AnthropicMaxTokens' non-streaming default has then to be looked at again.
  "The SDK's long-request check" should "refuse no default non-streamed call to the models it limits to 8,192 tokens" in {
    // maxTokens fails when the call does: whatever the bundled registry knows of each, its default is sent
    sdkLimited.foreach(model => maxTokens(model, CompletionOptions(), streaming = false) should be <= 16000)
  }

  it should "not refuse a default non-streamed call when the registry knows such a model with a larger maximum" in {
    sdkLimited.foreach { model =>
      maxTokens(model, CompletionOptions(), streaming = false, registryWith(model, 32000)) shouldBe 16000
    }
  }

  it should "not refuse an explicit maxTokens either, at the model's maximum and non-streamed" in {
    maxTokens("claude-sonnet-5-5", CompletionOptions().withMaxTokens(128000), streaming = false) shouldBe 128000
    maxTokens("claude-opus-4-1@20250805", CompletionOptions().withMaxTokens(32000), streaming = false) shouldBe 32000
  }

  "An explicit maxTokens" should "be sent unchanged, streamed or not, above or below any default" in {
    Seq(1, 1024, 2048, 16001, 20000, 64000, 128000).foreach { asked =>
      val options = CompletionOptions().withMaxTokens(asked)
      maxTokens("claude-sonnet-5-5", options, streaming = false) shouldBe asked
      maxTokens("claude-sonnet-5-5", options, streaming = true) shouldBe asked
      maxTokens("claude-next-9", options, streaming = false) shouldBe asked
    }
  }

  it should "be reported nowhere as adjusted: the default is not an adjustment either" in {
    val (_, result) = sent("claude-sonnet-5-5", bundled, streaming = false)(_.complete(hello, CompletionOptions()))
    result.toOption.get.adjustments.map(_.option) should not contain RequestOption.MaxTokens
  }
}

object AnthropicMaxTokensSpec {

  private val bundled: ModelRegistryService = ModelRegistryTestSupport.defaultService()

  private val text: RecordedReply = AnthropicContractFixtures.replies(ContractCase.CleanText) match {
    case Applicability.Applies(reply) => reply
    case other                        => sys.error(s"CleanText must apply, got $other")
  }

  private val hello = Conversation(Seq(UserMessage("hi")))

  // RequestOptions.MODEL_NONSTREAMING_TOKENS, anthropic-java 2.65.0: each is refused non-streamed above 8,192
  private val sdkLimited = Seq(
    "claude-opus-4-20250514",
    "claude-4-opus-20250514",
    "claude-opus-4-0",
    "anthropic.claude-opus-4-20250514-v1:0",
    "claude-opus-4@20250514",
    "anthropic.claude-opus-4-1-20250805-v1:0",
    "claude-opus-4-1@20250805"
  )
}
