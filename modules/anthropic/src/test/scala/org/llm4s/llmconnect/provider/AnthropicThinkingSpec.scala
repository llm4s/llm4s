package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpServer
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model.{
  CompletionOptions,
  Conversation,
  ReasoningEffort,
  RequestAdjustment,
  RequestOption,
  Thinking,
  UserMessage
}
import org.llm4s.llmconnect.provider.AnthropicThinking.{ Mode, Plan }
import org.llm4s.model.{ ModelCapabilities, ModelRegistryService }
import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * RFC 0008's Anthropic mapping of `thinking` and `reasoning` ([[AnthropicThinking]]): the plan for each combination,
 * from real registry capabilities, and the request it makes.
 */
class AnthropicThinkingSpec extends AnyFlatSpec with Matchers {

  private given registry: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def caps(model: String): Option[ModelCapabilities] = registry.lookup(model).toOption.map(_.capabilities)

  private def plan(model: String, options: CompletionOptions, maxTokens: Int = 64000): Plan =
    AnthropicThinking.plan(model, options, maxTokens, caps(model))

  private def budget(tokens: Int): Thinking = Thinking.budget(tokens).toOption.get

  private def high = CompletionOptions().withReasoning(ReasoningEffort.High)

  // -------- reasoning alone: today's request, and the budget-rejecting exception --------

  "A reasoning-only request" should "send the level's budget, as before RFC 0008, to a model that takes one" in {
    plan("claude-sonnet-4-5", high) shouldBe Plan(Some(Mode.Enabled(32768)), None, Nil)
    plan("claude-opus-4-6", CompletionOptions().withReasoning(ReasoningEffort.Low)) shouldBe
      Plan(Some(Mode.Enabled(2048)), None, Nil)
  }

  it should "fit the level's budget below max_tokens without reporting it, as before" in {
    plan("claude-sonnet-4-5", high, maxTokens = 2048) shouldBe Plan(Some(Mode.Enabled(2047)), None, Nil)
  }

  it should "send adaptive thinking at that effort to a model that rejects a budget, and report it" in {
    for (model <- List("claude-opus-4-7", "claude-opus-4-8", "claude-fable-5"))
      withClue(model)(
        plan(model, high) shouldBe Plan(
          Some(Mode.Adaptive),
          Some("high"),
          List(
            RequestAdjustment.Adjusted(
              RequestOption.Reasoning,
              "budget_tokens",
              "output_config.effort",
              "model does not accept a thinking budget"
            )
          )
        )
      )
  }

  it should "send a new level as the effort where the model takes output_config" in {
    plan("claude-opus-4-6", CompletionOptions().withReasoning(ReasoningEffort.Max)) shouldBe
      Plan(Some(Mode.Adaptive), Some("max"), Nil)
    // Opus 4.6 has no xhigh: the nearest it takes, the lower on a tie
    plan("claude-opus-4-6", CompletionOptions().withReasoning(ReasoningEffort.XHigh)).effort shouldBe Some("high")
    // Anthropic has no minimal
    plan("claude-opus-4-6", CompletionOptions().withReasoning(ReasoningEffort.Minimal)).adjustments shouldBe List(
      RequestAdjustment.Adjusted(RequestOption.Reasoning, "minimal", "low", "claude-opus-4-6 has no minimal level")
    )
  }

  it should "think within the new level's budget where the model takes output_config but not adaptive thinking" in {
    plan("claude-opus-4-5", CompletionOptions().withReasoning(ReasoningEffort.Max)) shouldBe
      Plan(
        Some(Mode.Enabled(63999)),
        Some("high"),
        List(
          RequestAdjustment.Adjusted(RequestOption.Reasoning, "max", "high", "claude-opus-4-5 has no max level")
        )
      )
  }

  it should "send nothing when neither is set" in {
    plan("claude-opus-4-7", CompletionOptions()) shouldBe Plan(None, None, Nil)
  }

  // -------- thinking --------

  "Thinking.Off" should "send thinking disabled, with the effort where the model takes one" in {
    plan("claude-opus-4-7", CompletionOptions().withThinking(Thinking.Off)) shouldBe Plan(
      Some(Mode.Disabled),
      None,
      Nil
    )
    plan("claude-opus-4-7", high.withThinking(Thinking.Off)) shouldBe Plan(Some(Mode.Disabled), Some("high"), Nil)
  }

  it should "override a level a model without output_config has nowhere to send" in {
    plan("claude-sonnet-4-5", high.withThinking(Thinking.Off)) shouldBe Plan(
      Some(Mode.Disabled),
      None,
      List(
        RequestAdjustment.Adjusted(
          RequestOption.Reasoning,
          "high",
          "thinking disabled",
          "thinking = Off takes precedence"
        )
      )
    )
  }

  it should "send no thinking to a model that cannot turn it off, and report it" in {
    plan("claude-fable-5", CompletionOptions().withThinking(Thinking.Off)) shouldBe Plan(
      None,
      None,
      List(
        RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Off",
          "the model's default",
          "claude-fable-5 cannot turn thinking off"
        )
      )
    )
  }

  "Thinking.Adaptive" should "send adaptive thinking to a model that takes it, and to one the registry does not know" in {
    plan("claude-sonnet-4-6", CompletionOptions().withThinking(Thinking.Adaptive)) shouldBe
      Plan(Some(Mode.Adaptive), None, Nil)
    AnthropicThinking.plan("claude-opus-9", CompletionOptions().withThinking(Thinking.Adaptive), 4096, None) shouldBe
      Plan(Some(Mode.Adaptive), None, Nil)
  }

  it should "send a budget instead to a known model from before adaptive thinking, and report it" in {
    plan("claude-sonnet-4-5", CompletionOptions().withThinking(Thinking.Adaptive)) shouldBe Plan(
      Some(Mode.Enabled(8192)),
      None,
      List(
        RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Adaptive",
          "Budget(8192)",
          "claude-sonnet-4-5 does not take adaptive thinking"
        )
      )
    )
  }

  it should "drop a level a model without output_config cannot take alongside it" in {
    plan("claude-sonnet-4-5", high.withThinking(Thinking.Adaptive)).adjustments.map(_.option) should contain(
      RequestOption.Reasoning
    )
  }

  "Thinking.Budget" should "send the budget as asked when it fits" in {
    plan("claude-opus-4-6", CompletionOptions().withThinking(budget(16000))) shouldBe
      Plan(Some(Mode.Enabled(16000)), None, Nil)
  }

  it should "fit a budget to [1024, max_tokens) and report it, at both ends" in {
    val reason = "Anthropic takes a budget of at least 1024 and below max_tokens (2048)"
    plan("claude-opus-4-6", CompletionOptions().withThinking(budget(500)), maxTokens = 2048) shouldBe Plan(
      Some(Mode.Enabled(1024)),
      None,
      List(RequestAdjustment.Adjusted(RequestOption.Thinking, "Budget(500)", "Budget(1024)", reason))
    )
    plan("claude-opus-4-6", CompletionOptions().withThinking(budget(2048)), maxTokens = 2048).thinking shouldBe
      Some(Mode.Enabled(2047))
    plan("claude-opus-4-6", CompletionOptions().withThinking(budget(1024)), maxTokens = 2048).adjustments shouldBe Nil
    plan("claude-opus-4-6", CompletionOptions().withThinking(budget(2047)), maxTokens = 2048).adjustments shouldBe Nil
  }

  it should "send adaptive thinking to a model that rejects a budget, and report it" in {
    plan("claude-opus-4-7", CompletionOptions().withThinking(budget(8000))) shouldBe Plan(
      Some(Mode.Adaptive),
      None,
      List(
        RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Budget(8000)",
          "Adaptive",
          "model does not accept a thinking budget"
        )
      )
    )
  }

  "Plan.thinks" should "be true for a budget or adaptive thinking, and false for none or disabled" in {
    Plan(Some(Mode.Enabled(1024)), None, Nil).thinks shouldBe true
    Plan(Some(Mode.Adaptive), None, Nil).thinks shouldBe true
    Plan(Some(Mode.Disabled), None, Nil).thinks shouldBe false
    Plan(None, None, Nil).thinks shouldBe false
  }

  // -------- the request on the wire --------

  private def okReply =
    """{"id":"m","type":"message","role":"assistant","model":"x","content":[{"type":"text","text":"ok"}],
      |"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}""".stripMargin

  /** The request body `complete` sends to `model`, and the completion's adjustments. */
  private def sent(model: String, options: CompletionOptions): (ujson.Value, List[RequestAdjustment]) = {
    val seen   = new AtomicReference[String]()
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/messages",
      exchange => {
        seen.set(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
        val bytes = okReply.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    try {
      val config = AnthropicConfig(
        apiKey = Secret("test-key"),
        model = model,
        baseUrl = s"http://localhost:${server.getAddress.getPort}",
        contextWindow = 200000,
        reserveCompletion = 4096
      )
      val completion = new AnthropicClient(config).complete(Conversation(Seq(UserMessage("hi"))), options)
      (ujson.read(seen.get()), completion.toOption.get.adjustments)
    } finally server.stop(0)
  }

  "AnthropicClient" should "send adaptive thinking and output_config.effort, and no temperature, for the exception" in {
    val (body, adjustments) =
      sent("claude-opus-4-7", CompletionOptions(maxTokens = Some(4096)).withReasoning(ReasoningEffort.High))
    body("thinking") shouldBe ujson.Obj("type" -> "adaptive")
    body("output_config") shouldBe ujson.Obj("effort" -> "high")
    body.obj.keySet should not contain "temperature"
    adjustments.map(_.option) should contain(RequestOption.Reasoning)
  }

  it should "send thinking disabled with the temperature, which a non-thinking request may set" in {
    val (body, adjustments) =
      sent("claude-opus-4-6", CompletionOptions(maxTokens = Some(4096)).withThinking(Thinking.Off))
    body("thinking") shouldBe ujson.Obj("type" -> "disabled")
    body.obj.keySet should contain("temperature")
    body.obj.keySet should not contain "output_config"
    adjustments shouldBe Nil
  }

  it should "send a reasoning-only request to a model that takes a budget exactly as before" in {
    val (body, adjustments) =
      sent("claude-sonnet-4-5", CompletionOptions(maxTokens = Some(4096)).withReasoning(ReasoningEffort.Low))
    body("thinking") shouldBe ujson.Obj("type" -> "enabled", "budget_tokens" -> 2048)
    body.obj.keySet should not contain "output_config"
    body.obj.keySet should not contain "temperature"
    adjustments shouldBe Nil
  }
}
