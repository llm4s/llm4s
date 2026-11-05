package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, RequestOption, UserMessage }
import org.llm4s.llmconnect.model.RequestAdjustment.{ Adjusted, Dropped }
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, completion => completionOf, stream, transport }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * RFC 0008 part 1 (#2115): what `OpenAIModelRules` drops from a request is reported on the completion,
 * from `complete`, from a faked stream and from a native one.
 */
final class OpenAIClientAdjustmentsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val hello = Conversation(Seq(UserMessage("hello")))

  private val okResponse =
    """{"id":"c1","created":0,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}"""

  private def config(model: String): OpenAIConfig =
    OpenAIConfig.fromValues(model, Secret("sk-test"), None, "https://example.invalid/v1").value

  private def client(model: String): OpenAIClient =
    OpenAIClient.forTest(
      model,
      transport(
        complete = _ => completionOf(okResponse),
        streaming = _ =>
          stream(
            chunk("""{"id":"c1","created":0,"choices":[{"index":0,"delta":{"role":"assistant","content":"ok"}}]}""")
          )
      ),
      config(model)
    )

  // an o-series model takes temperature 1 only and no top_p
  private val expected = List(
    Adjusted(RequestOption.Temperature, "0.7", "1.0", "allowed range for o3-mini is 1.0-1.0"),
    Dropped(RequestOption.TopP, "top_p not supported for o3-mini")
  )

  "OpenAIClient.complete" should "report the options the model's rules dropped or adjusted" in {
    client("o3-mini").complete(hello, CompletionOptions(topP = 0.5)).value.adjustments shouldBe expected
  }

  it should "report nothing when every option is sent as requested" in {
    client("gpt-4o").complete(hello, CompletionOptions(topP = 0.5)).value.adjustments shouldBe empty
  }

  "OpenAIClient.streamComplete" should "report the same adjustments from a faked stream" in {
    // o-series models do not stream natively, so this is the faked stream
    client("o3-mini").streamComplete(hello, CompletionOptions(topP = 0.5), _ => ()).value.adjustments shouldBe expected
  }

  it should "report nothing from a native stream whose options were all sent" in {
    val completion = client("gpt-4o").streamComplete(hello, CompletionOptions(topP = 0.5), _ => ()).value
    completion.content shouldBe "ok"
    completion.adjustments shouldBe empty
  }
}
