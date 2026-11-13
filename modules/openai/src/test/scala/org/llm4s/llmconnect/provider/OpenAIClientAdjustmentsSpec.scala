package org.llm4s.llmconnect.provider

import com.openai.models.chat.completions.ChatCompletionCreateParams
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, RequestOption, UserMessage }
import org.llm4s.llmconnect.model.RequestAdjustment.Dropped
import org.llm4s.llmconnect.provider.OpenAISdkFixtures.{ chunk, completion => completionOf, stream, transport }
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * RFC 0008 part 1 (#2115): what `OpenAIModelRules` drops from a request, and the sampling options a
 * reasoning model's request leaves out, are reported on the completion, from `complete`, from a faked
 * stream and from a native one.
 */
final class OpenAIClientAdjustmentsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private val hello = Conversation(Seq(UserMessage("hello")))

  private val okResponse =
    """{"id":"c1","created":0,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}"""

  private def config(model: String): OpenAIConfig =
    OpenAIConfig.fromValues(model, Secret("sk-test"), None, "https://example.invalid/v1").value

  /** A client for `model`, and the requests it sends. */
  private def client(model: String): (OpenAIClient, ListBuffer[ChatCompletionCreateParams]) = {
    val sent = ListBuffer.empty[ChatCompletionCreateParams]
    val c = OpenAIClient.forTest(
      model,
      transport(
        complete = params => { sent += params; completionOf(okResponse) },
        streaming = params => {
          sent += params
          stream(
            chunk("""{"id":"c1","created":0,"choices":[{"index":0,"delta":{"role":"assistant","content":"ok"}}]}""")
          )
        }
      ),
      config(model)
    )
    (c, sent)
  }

  private def sendsNoSampling(params: ChatCompletionCreateParams): Unit = {
    params.temperature().isPresent shouldBe false
    params.topP().isPresent shouldBe false
    params.presencePenalty().isPresent shouldBe false
    params.frequencyPenalty().isPresent shouldBe false
  }

  private def notSent(model: String, options: RequestOption*) =
    options.toList.map(Dropped(_, s"$model is a reasoning model, which takes no sampling parameters"))

  // An o-series model takes no top_p, which the transformer drops, and would have its temperature fixed at 1.0;
  // but a reasoning model's request sends no sampling options at all, so that is what is reported, and only for
  // the top_p the caller set: the temperature was left at its default.
  private val oSeries = notSent("o3-mini", RequestOption.TopP)

  "OpenAIClient.complete" should "report the options the model's rules dropped or left out" in {
    val (c, sent) = client("o3-mini")
    c.complete(hello, CompletionOptions(topP = 0.5)).value.adjustments shouldBe oSeries
    sendsNoSampling(sent.head)
  }

  it should "report nothing when every option is sent as requested" in {
    val (c, sent) = client("gpt-4o")
    c.complete(hello, CompletionOptions(topP = 0.5)).value.adjustments shouldBe empty
    sent.head.topP().isPresent shouldBe true
  }

  "OpenAIClient.streamComplete" should "report the same adjustments from a faked stream" in {
    // o-series models do not stream natively, so this is the faked stream
    val (c, _) = client("o3-mini")
    c.streamComplete(hello, CompletionOptions(topP = 0.5), _ => ()).value.adjustments shouldBe oSeries
  }

  it should "report nothing from a native stream whose options were all sent" in {
    val (c, _)     = client("gpt-4o")
    val completion = c.streamComplete(hello, CompletionOptions(topP = 0.5), _ => ()).value
    completion.content shouldBe "ok"
    completion.adjustments shouldBe empty
  }

  // gpt-5 and o4-mini are reasoning models that no OpenAIModelRules rule names, so only the client reports
  "A reasoning model's request" should "report each sampling option it leaves out, from complete and a native stream" in {
    val sampling =
      CompletionOptions(temperature = 0.2, topP = 0.5, presencePenalty = 0.3, frequencyPenalty = 0.3)
    for (model <- List("gpt-5", "o4-mini")) {
      val (c, sent) = client(model)
      val expected = notSent(
        model,
        RequestOption.Temperature,
        RequestOption.TopP,
        RequestOption.PresencePenalty,
        RequestOption.FrequencyPenalty
      )

      withClue(model) {
        c.complete(hello, sampling).value.adjustments shouldBe expected
        c.streamComplete(hello, sampling, _ => ()).value.adjustments shouldBe expected
        sent.foreach(sendsNoSampling)
      }
    }
  }

  it should "report nothing for a default call, though nothing is sent for the defaults" in {
    // o3-mini also has the transformer's report that it moved the default temperature to 1.0 replaced
    for (model <- List("gpt-5", "o3-mini")) {
      val (c, sent) = client(model)
      withClue(model) {
        c.complete(hello, CompletionOptions()).value.adjustments shouldBe empty
        c.streamComplete(hello, CompletionOptions(), _ => ()).value.adjustments shouldBe empty
        sent.foreach(sendsNoSampling)
      }
    }
  }

  it should "report only the sampling options set away from their defaults" in {
    val (c, _) = client("gpt-5")
    c.complete(hello, CompletionOptions(temperature = 0.2)).value.adjustments shouldBe
      notSent("gpt-5", RequestOption.Temperature)
  }

  it should "replace the transformer's adjusted o-series temperature with a drop, since none is sent" in {
    // OpenAIModelRules would send 1.0 in place of 0.2, but the request carries no temperature at all
    val (c, sent) = client("o3-mini")
    c.complete(hello, CompletionOptions(temperature = 0.2)).value.adjustments shouldBe
      notSent("o3-mini", RequestOption.Temperature)
    sendsNoSampling(sent.head)
  }
}
