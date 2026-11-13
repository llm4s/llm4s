package org.llm4s.llmconnect.provider

import org.llm4s.security.Secret
import org.llm4s.llmconnect.config.MistralConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * RFC 0008 part 1 (#2115): a `reasoning` the standard dialect does not send is reported as `Dropped`
 * on the completion, instead of being ignored silently. Mistral uses the standard hook, so it stands
 * in for every dialect that does.
 */
class OpenAICompatibleReasoningAdjustmentsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val reasoning = CompletionOptions().withReasoning(ReasoningEffort.High)
  private val hello     = Conversation(Seq(UserMessage("hi")))

  private def dropped(model: String) =
    List(RequestAdjustment.Dropped(RequestOption.Reasoning, s"reasoning effort is not sent to $model by this client"))

  "OpenAICompatibleDialect.addReasoning" should "send nothing and report a set reasoning as dropped by default" in {
    val standard = new OpenAICompatibleDialect {}
    val body     = ujson.Obj("model" -> "m")

    standard.addReasoning(body, "m", reasoning) shouldBe dropped("m")
    body shouldBe ujson.Obj("model" -> "m")
    standard.addReasoning(body, "m", CompletionOptions()) shouldBe empty
  }

  it should "report nothing from the dialects that map reasoning themselves" in {
    ZaiDialect.addReasoning(ujson.Obj(), "glm-5.2", reasoning) shouldBe empty
    XaiDialect.addReasoning(ujson.Obj(), "grok-4.6", reasoning) shouldBe empty
    OpenRouterDialect.addReasoning(ujson.Obj(), "anthropic/claude-sonnet-4", reasoning) shouldBe empty
  }

  "OpenAICompatibleClient" should "put the dialect's report on the completion, from complete and from a stream" in {
    val model = "mistral-small-latest"
    withServer("/v1/chat/completions") { exchange =>
      val request = new String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      if (ujson.read(request).obj.get("stream").exists(_.bool))
        sendSseResponse(exchange, openAISseBody(Seq("Hi"), model))
      else sendJsonResponse(exchange, 200, openAICompletion("Hi", model))
    } { baseUrl =>
      val client = new MistralClient(MistralConfig(Secret("test-key"), model, baseUrl, 128000, 4096))

      client.complete(hello, reasoning).value.adjustments shouldBe dropped(model)
      client.streamComplete(hello, reasoning, _ => ()).value.adjustments shouldBe dropped(model)
      client.complete(hello, CompletionOptions()).value.adjustments shouldBe empty
    }
  }
}
