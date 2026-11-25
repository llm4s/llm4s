package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
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

    standard.addReasoning(body, ModelName("m"), reasoning) shouldBe dropped("m")
    body shouldBe ujson.Obj("model" -> "m")
    standard.addReasoning(body, ModelName("m"), CompletionOptions()) shouldBe empty
  }

  it should "report nothing from the dialects that map reasoning themselves" in {
    ZaiDialect.addReasoning(ujson.Obj(), ModelName("glm-5.2"), reasoning) shouldBe empty
    XaiDialect.addReasoning(ujson.Obj(), ModelName("grok-4.6"), reasoning) shouldBe empty
    // a level whose budget fits the limit; one cut to a limit the dialect assumed is reported (OpenRouterDialectSpec)
    val fitting = CompletionOptions().withReasoning(ReasoningEffort.Medium)
    OpenRouterDialect.addReasoning(ujson.Obj(), ModelName("anthropic/claude-sonnet-4"), fitting) shouldBe empty
  }

  "OpenAICompatibleClient" should "put the dialect's report on the completion, from complete and from a stream" in {
    val model = "mistral-small-latest"
    withServer("/v1/chat/completions") { exchange =>
      val request = new String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      if (ujson.read(request).obj.get("stream").exists(_.bool))
        sendSseResponse(exchange, openAISseBody(Seq("Hi"), model))
      else sendJsonResponse(exchange, 200, openAICompletion("Hi", model))
    } { baseUrl =>
      val client =
        new MistralClient(MistralConfig(Secret("test-key"), ModelName(model), BaseUrl(baseUrl), 128000, 4096))

      client.complete(hello, reasoning).value.adjustments shouldBe dropped(model)
      client.streamComplete(hello, reasoning)(_ => ()).value.adjustments shouldBe dropped(model)
      client.complete(hello, CompletionOptions()).value.adjustments shouldBe empty
    }
  }

  // #2909: a gateway that serves Claude (LiteLLM, a company proxy) forwards the parameter, and the model answers 400
  it should "leave out a temperature and a top_p the registry says the model rejects, and report them" in {
    def sent(model: String): (ujson.Value, Seq[RequestAdjustment]) = {
      var request: ujson.Value = ujson.Null
      var adjustments          = Seq.empty[RequestAdjustment]
      withServer("/v1/chat/completions") { exchange =>
        request =
          ujson.read(new String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
        sendJsonResponse(exchange, 200, openAICompletion("Hi", model))
      } { baseUrl =>
        val client =
          new MistralClient(MistralConfig(Secret("test-key"), ModelName(model), BaseUrl(baseUrl), 128000, 4096))
        adjustments = client.complete(hello, CompletionOptions().withTemperature(0.2).withTopP(0.9)).value.adjustments
      }
      (request, adjustments)
    }

    val (flagged, dropped) = sent("claude-sonnet-5-5")
    flagged.obj.keySet should contain noneOf ("temperature", "top_p")
    dropped shouldBe List(
      RequestAdjustment.Dropped(RequestOption.Temperature, "temperature not supported for claude-sonnet-5-5"),
      RequestAdjustment.Dropped(RequestOption.TopP, "top_p not supported for claude-sonnet-5-5")
    )

    for (model <- List("claude-sonnet-4-6", "mistral-small-latest", "a-model-no-registry-knows")) {
      val (request, adjustments) = sent(model)
      withClue(model) {
        request("temperature").num shouldBe 0.2
        request("top_p").num shouldBe 0.9
        adjustments shouldBe empty
      }
    }
  }

  it should "report a service tier as dropped, sending none (#2726)" in {
    val model = "mistral-small-latest"
    withServer("/v1/chat/completions") { exchange =>
      val request =
        ujson.read(new String(exchange.getRequestBody.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
      request.obj.contains("service_tier") shouldBe false
      sendJsonResponse(exchange, 200, openAICompletion("Hi", model))
    } { baseUrl =>
      val client =
        new MistralClient(MistralConfig(Secret("test-key"), ModelName(model), BaseUrl(baseUrl), 128000, 4096))
      client.complete(hello, CompletionOptions().withServiceTier(ServiceTier.Flex)).value.adjustments shouldBe List(
        RequestAdjustment.Dropped(RequestOption.ServiceTier, "Mistral has no service tier to choose; Flex is not sent")
      )
    }
  }
}
