package org.llm4s.llmconnect.provider

import org.llm4s.security.Secret
import org.llm4s.llmconnect.config.ZaiConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * `CompletionOptions.reasoning` on Z.ai (#1681): sent as `thinking.type` or `reasoning_effort`,
 * whichever the GLM model documents, on both the non-streamed and the streamed request.
 */
class ZaiReasoningEffortSpec extends AnyFlatSpec with Matchers {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val plain = Conversation(Seq(UserMessage("Is 91 prime?")))

  private val call = ToolCall("call-1", "get_weather", ujson.Obj("city" -> "Paris"))

  /** A conversation whose assistant turn replays reasoning, so `clear_thinking` is set too. */
  private val replaying = Conversation(
    Seq(
      UserMessage("Weather in Paris?"),
      AssistantMessage(None, Seq(call)).withThinking("The user wants Paris weather."),
      ToolMessage("sunny", call.id)
    )
  )

  private val efforts = ReasoningEffort.values

  private val off = CompletionOptions().withThinking(Thinking.Off)

  /** The request bodies `complete` and `streamComplete` send to `model` for this conversation and options. */
  private def sentBodies(model: String, conversation: Conversation, options: CompletionOptions): Seq[ujson.Value] = {
    val sent = new AtomicReference[String]()
    def zai(baseUrl: String) =
      new ZaiClient(
        ZaiConfig(
          apiKey = Secret("test-key"),
          model = model,
          baseUrl = baseUrl,
          contextWindow = 128000,
          reserveCompletion = 4096
        )
      )
    withServer("/chat/completions") { exchange =>
      sent.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      sendJsonResponse(exchange, 200, openAICompletion("No.", model))
    }(baseUrl => zai(baseUrl).complete(conversation, options).isRight shouldBe true)
    val completeBody = ujson.read(sent.get)
    withServer("/chat/completions") { exchange =>
      sent.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      sendSseResponse(exchange, openAISseBody(Seq("No."), model))
    }(baseUrl => zai(baseUrl).streamComplete(conversation, options, _ => ()).isRight shouldBe true)
    val streamBody = ujson.read(sent.get)
    completeBody.obj.contains("stream") shouldBe false
    streamBody("stream").bool shouldBe true
    Seq(completeBody, streamBody)
  }

  private def reasoningFields(body: ujson.Value): Map[String, ujson.Value] =
    body.obj.view.filterKeys(Set("thinking", "reasoning_effort")).toMap

  "Z.ai with no reasoning option" should "send neither `thinking` nor `reasoning_effort`, on every model family" in {
    Seq("GLM-4.7", "glm-5.2", "glm-5.3", "glm-4-32b-0414-128k").foreach { model =>
      sentBodies(model, plain, CompletionOptions()).foreach(reasoningFields(_) shouldBe empty)
    }
  }

  "Z.ai on GLM-4.7" should "disable thinking for Thinking.Off, over a level also set" in {
    sentBodies("GLM-4.7", plain, off.withReasoning(ReasoningEffort.High)).foreach { body =>
      reasoningFields(body) shouldBe Map("thinking" -> ujson.Obj("type" -> "disabled"))
    }
    sentBodies("GLM-4.7", plain, off).foreach { body =>
      reasoningFields(body) shouldBe Map("thinking" -> ujson.Obj("type" -> "disabled"))
    }
  }

  it should "send nothing for an effort level: it documents no `reasoning_effort`" in {
    efforts.foreach { effort =>
      sentBodies("GLM-4.7", plain, CompletionOptions().withReasoning(effort)).foreach(reasoningFields(_) shouldBe empty)
    }
  }

  it should "send `thinking` with both `type` and `clear_thinking` when it also replays reasoning" in {
    sentBodies("GLM-4.7", replaying, off).foreach { body =>
      body("thinking") shouldBe ujson.Obj("type" -> "disabled", "clear_thinking" -> false)
      body.obj.keySet should not contain "reasoning_effort"
    }
  }

  "Z.ai on GLM-5.2" should "send each effort's name as `reasoning_effort`, `max` for High and above, and `none` for Thinking.Off" in {
    val expected = Map[ReasoningEffort, String](
      ReasoningEffort.Minimal -> "low",
      ReasoningEffort.Low     -> "low",
      ReasoningEffort.Medium  -> "medium",
      ReasoningEffort.High    -> "max",
      ReasoningEffort.XHigh   -> "max",
      ReasoningEffort.Max     -> "max"
    )
    efforts.foreach { effort =>
      sentBodies("glm-5.2", plain, CompletionOptions().withReasoning(effort)).foreach { body =>
        reasoningFields(body) shouldBe Map("reasoning_effort" -> ujson.Str(expected(effort)))
      }
    }
    sentBodies("glm-5.2", plain, off).foreach(reasoningFields(_) shouldBe Map("reasoning_effort" -> ujson.Str("none")))
  }

  it should "add `clear_thinking` alone when it also replays reasoning" in {
    sentBodies("glm-5.2", replaying, CompletionOptions().withReasoning(ReasoningEffort.Low)).foreach { body =>
      reasoningFields(body) shouldBe Map(
        "reasoning_effort" -> ujson.Str("low"),
        "thinking"         -> ujson.Obj("clear_thinking" -> false)
      )
    }
  }

  "Z.ai on GLM-5.3" should "never disable thinking, which it rejects, and send `low`, `high` or `max`" in {
    val expected = Map[ReasoningEffort, String](
      ReasoningEffort.Minimal -> "low",
      ReasoningEffort.Low     -> "low",
      ReasoningEffort.Medium  -> "high",
      ReasoningEffort.High    -> "max",
      ReasoningEffort.XHigh   -> "max",
      ReasoningEffort.Max     -> "max"
    )
    Seq("glm-5.3", "GLM-5.3-Flash").foreach { model =>
      sentBodies(model, plain, off).foreach(reasoningFields(_) shouldBe Map("reasoning_effort" -> ujson.Str("low")))
      efforts.foreach { effort =>
        sentBodies(model, plain, CompletionOptions().withReasoning(effort)).foreach { body =>
          reasoningFields(body) shouldBe Map("reasoning_effort" -> ujson.Str(expected(effort)))
        }
      }
    }
  }

  it should "send both `reasoning_effort` and `thinking.clear_thinking` when it also replays reasoning" in {
    sentBodies("glm-5.3", replaying, CompletionOptions().withReasoning(ReasoningEffort.High)).foreach { body =>
      reasoningFields(body) shouldBe Map(
        "reasoning_effort" -> ujson.Str("max"),
        "thinking"         -> ujson.Obj("clear_thinking" -> false)
      )
    }
  }

  it should "report that Thinking.Off still thinks, naming the model, and a level it overrode" in {
    ZaiDialect.addReasoning(ujson.Obj(), "glm-5.3", off.withReasoning(ReasoningEffort.Medium)) shouldBe List(
      RequestAdjustment.Adjusted(RequestOption.Thinking, "Off", "low", "glm-5.3 always thinks"),
      RequestAdjustment.Adjusted(RequestOption.Reasoning, "medium", "low", "thinking = Off takes precedence")
    )
  }

  "Z.ai's reports" should "name only the levels it has no Z.ai level for, not its own scale" in {
    ZaiDialect.addReasoning(
      ujson.Obj(),
      "glm-5.2",
      CompletionOptions().withReasoning(ReasoningEffort.High)
    ) shouldBe Nil
    ZaiDialect.addReasoning(ujson.Obj(), "glm-5.2", CompletionOptions().withReasoning(ReasoningEffort.Minimal)) shouldBe
      List(RequestAdjustment.Adjusted(RequestOption.Reasoning, "minimal", "low", "glm-5.2 has no minimal level"))
    ZaiDialect.addReasoning(ujson.Obj(), "glm-5.3", CompletionOptions().withReasoning(ReasoningEffort.XHigh)) shouldBe
      List(RequestAdjustment.Adjusted(RequestOption.Reasoning, "xhigh", "max", "glm-5.3 has no xhigh level"))
  }

  it should "call adaptive thinking not needed on a GLM model, and drop a budget" in {
    ZaiDialect.addReasoning(ujson.Obj(), "glm-4.7", CompletionOptions().withThinking(Thinking.Adaptive)) shouldBe
      List(RequestAdjustment.NotNeeded(RequestOption.Thinking, "glm-4.7 thinks by default"))
    ZaiDialect
      .addReasoning(ujson.Obj(), "glm-4.7", CompletionOptions().withThinking(Thinking.budget(4096).toOption.get))
      .map(_.option) shouldBe List(RequestOption.Thinking)
  }

  "Z.ai on a model without documented thinking" should "send nothing for any effort" in {
    efforts.foreach { effort =>
      sentBodies("glm-4-32b-0414-128k", plain, CompletionOptions().withReasoning(effort))
        .foreach(reasoningFields(_) shouldBe empty)
    }
  }

  "ZaiDialect.family" should "place each documented GLM chat model, case-insensitively" in {
    import ZaiDialect.Family._
    val families = Map(
      "glm-5.3"             -> ForcedThinking,
      "glm-5.3-flash"       -> ForcedThinking,
      "glm-5.3-flashx"      -> ForcedThinking,
      "glm-5.2"             -> Effort,
      "glm-5.1"             -> Toggle,
      "glm-5"               -> Toggle,
      "GLM-4.7"             -> Toggle,
      "glm-4.7-flashx"      -> Toggle,
      "glm-4.6"             -> Toggle,
      "glm-4.6v-flash"      -> Toggle,
      "glm-4.5-air"         -> Toggle,
      "glm-4.5v"            -> Toggle,
      "glm-4-32b-0414-128k" -> Unknown,
      "glm-5.30"            -> Unknown,
      "glm-6"               -> Unknown
    )
    families.foreach { case (model, family) => withClue(model)(ZaiDialect.family(model) shouldBe family) }
  }
}
