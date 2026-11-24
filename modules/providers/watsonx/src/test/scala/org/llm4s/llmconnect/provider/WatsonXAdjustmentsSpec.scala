package org.llm4s.llmconnect.provider

import org.llm4s.http.HttpResponse
import org.llm4s.llmconnect.config.WatsonXConfig
import org.llm4s.llmconnect.model.*
import org.llm4s.model.ModelRegistryService
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/**
 * RFC 0008: what a request does not send as asked is reported on `Completion.adjustments`, never dropped silently.
 * The chat API's `reasoning_effort` takes `low`, `medium` and `high`, only the gpt-oss models are sent it, and it has
 * no value that turns reasoning off (see the client's Scaladoc); a thinking budget has no counterpart at all.
 */
class WatsonXAdjustmentsSpec extends AnyFunSuite with Matchers with EitherValues:
  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  import StubHttp.*
  import WatsonXTestConfig.config

  private val gptOss = config.copy(model = "openai/gpt-oss-120b")
  private val hi     = Conversation(Seq(UserMessage("Hi")))

  private val reply =
    """{"id":"g","choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}"""

  private def adjustments(cfg: WatsonXConfig, options: CompletionOptions): List[RequestAdjustment] =
    new WatsonXClient(cfg, httpClient = routed(_ => Right(iamToken()), _ => Right(HttpResponse(200, reply))))
      .complete(hi, options)
      .value
      .adjustments

  private def budget = Thinking.budget(2048).fold(e => fail(e.message), identity)

  test("a service tier is reported Dropped, since the chat API has none (#2726)") {
    adjustments(config, CompletionOptions().withServiceTier(ServiceTier.Flex)) shouldBe List(
      RequestAdjustment.Dropped(RequestOption.ServiceTier, "watsonx has no service tier to choose; Flex is not sent")
    )
  }

  test("a level the chat API takes is sent as asked and reported as nothing") {
    adjustments(gptOss, CompletionOptions().withReasoning(ReasoningEffort.Medium)) shouldBe Nil
    adjustments(gptOss, CompletionOptions()) shouldBe Nil
  }

  test("what the request sends is not reported: top_p, the penalties and a response format") {
    val options = CompletionOptions()
      .withTopP(0.5)
      .withPresencePenalty(0.5)
      .withFrequencyPenalty(0.3)
      .withResponseFormat(ResponseFormat.Json)
      .withMaxTokens(64)
    adjustments(gptOss, options) shouldBe Nil
    adjustments(config, options) shouldBe Nil
  }

  test("a level it lacks is reported Adjusted to the one sent: Minimal as low, XHigh and Max as high") {
    Seq(ReasoningEffort.Minimal -> "low", ReasoningEffort.XHigh -> "high", ReasoningEffort.Max -> "high").foreach {
      case (level, sent) =>
        withClue(level.name) {
          adjustments(gptOss, CompletionOptions().withReasoning(level)) match
            case List(RequestAdjustment.Adjusted(RequestOption.Reasoning, requested, actual, _)) =>
              requested shouldBe level.name
              actual shouldBe sent
            case other => fail(s"expected one Adjusted on reasoning, got $other")
        }
    }
  }

  test("Thinking.Off is sent as low and reported, with the reasoning level it overrode") {
    val options = CompletionOptions().withReasoning(ReasoningEffort.High).withThinking(Thinking.Off)
    adjustments(gptOss, options).map(a => (a.option, a.getClass.getSimpleName)) should contain theSameElementsAs
      List(RequestOption.Thinking -> "Adjusted", RequestOption.Reasoning -> "Adjusted")
    adjustments(gptOss, options).collect { case RequestAdjustment.Adjusted(option, requested, sent, _) =>
      (option, requested, sent)
    } should contain theSameElementsAs List(
      (RequestOption.Thinking, "Off", "low"),
      (RequestOption.Reasoning, "high", "low")
    )
  }

  test("a thinking budget has no counterpart and is reported Dropped; Adaptive is NotNeeded, the effort decides") {
    adjustments(gptOss, CompletionOptions().withThinking(budget)) match
      case List(RequestAdjustment.Dropped(RequestOption.Thinking, _)) => succeed
      case other                                                      => fail(s"expected Dropped thinking, got $other")
    adjustments(gptOss, CompletionOptions().withThinking(Thinking.Adaptive)) match
      case List(RequestAdjustment.NotNeeded(RequestOption.Thinking, _)) => succeed
      case other => fail(s"expected NotNeeded thinking, got $other")
  }

  test("a model that is not sent reasoning_effort reports reasoning and thinking Dropped") {
    val options = CompletionOptions().withReasoning(ReasoningEffort.High).withThinking(Thinking.Off)
    adjustments(config, options).map(a => (a.option, a.getClass.getSimpleName)) should contain theSameElementsAs
      List(RequestOption.Reasoning -> "Dropped", RequestOption.Thinking -> "Dropped")
  }

  test("a stream reports the same adjustments as complete") {
    val events =
      """data: {"choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}""" + "\n\n"
    val options = CompletionOptions().withReasoning(ReasoningEffort.Minimal)
    val chunks  = ListBuffer.empty[StreamedChunk]
    val streamed = new WatsonXClient(gptOss, httpClient = streaming(streamOf(bytes(events))))
      .streamComplete(hi, options)(chunks += _)
      .value
    streamed.adjustments shouldBe adjustments(gptOss, options)
    streamed.adjustments should not be empty
  }
