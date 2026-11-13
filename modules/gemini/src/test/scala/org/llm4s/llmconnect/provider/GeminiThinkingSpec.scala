package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort, RequestAdjustment, RequestOption, Thinking }
import org.llm4s.llmconnect.provider.GeminiThinking.{ Family, Plan }
import org.llm4s.model.{ ModelCapabilities, ModelRegistryService }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** RFC 0008's Gemini follow-up: `thinking` and `reasoning` as `thinkingBudget` (Gemini 2.5) or `thinkingLevel` (3+). */
class GeminiThinkingSpec extends AnyFlatSpec with Matchers {

  private given registry: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def caps(model: String): Option[ModelCapabilities] = registry.lookup(model).toOption.map(_.capabilities)

  private def plan(model: String, options: CompletionOptions): Plan = GeminiThinking.plan(model, options, caps(model))

  private def budget(tokens: Int) = Some(ujson.Obj("thinkingBudget" -> tokens))
  private def level(name: String) = Some(ujson.Obj("thinkingLevel" -> name))

  private def off                   = CompletionOptions().withThinking(Thinking.Off)
  private def adaptive              = CompletionOptions().withThinking(Thinking.Adaptive)
  private def budgeted(tokens: Int) = CompletionOptions().withThinking(Thinking.budget(tokens).toOption.get)

  "GeminiThinking.family" should "tell the 2.5 budget models, the 3+ level models and the rest apart" in {
    GeminiThinking.family("gemini-2.5-pro", None) shouldBe Family.Budget(128, 32768, canTurnOff = false)
    GeminiThinking.family("models/gemini-2.5-flash", None) shouldBe Family.Budget(1, 24576, canTurnOff = true)
    GeminiThinking
      .family("gemini-2.5-flash-lite-preview-06-17", None) shouldBe Family.Budget(512, 24576, canTurnOff = true)
    GeminiThinking.family("gemini-3-pro-preview", None) shouldBe
      Family.Level(Set(ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High))
    GeminiThinking.family("gemini-3-flash-preview", None) shouldBe
      Family.Level(Set(ReasoningEffort.Minimal, ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High))
    GeminiThinking.family("gemini-4-hydrogen", None) shouldBe
      Family.Level(Set(ReasoningEffort.Low, ReasoningEffort.Medium, ReasoningEffort.High, ReasoningEffort.XHigh))
    for (
      model <- List("gemini-2.0-flash", "gemini-1.5-pro", "gemini-3-pro-image-preview", "gemini-2.5-flash-preview-tts")
    )
      withClue(model)(GeminiThinking.family(model, None) shouldBe Family.Unmapped)
  }

  it should "take the registry's word that a model does not reason" in {
    GeminiThinking.family("gemini/gemini-2.5-flash-image", caps("gemini/gemini-2.5-flash-image")) shouldBe
      Family.Unmapped
  }

  // -------- Gemini 2.5: thinkingBudget --------

  "A Gemini 2.5 model" should "turn thinking off with a budget of 0 where it can, and make it dynamic with -1" in {
    plan("gemini-2.5-flash", off) shouldBe Plan(budget(0), Nil)
    plan("gemini-2.5-flash-lite", off) shouldBe Plan(budget(0), Nil)
    plan("gemini-2.5-flash", adaptive) shouldBe Plan(budget(-1), Nil)
    plan("gemini-2.5-pro", adaptive) shouldBe Plan(budget(-1), Nil)
  }

  it should "send 2.5 Pro, which cannot turn thinking off, its minimum budget, and report it" in {
    plan("gemini-2.5-pro", off) shouldBe Plan(
      budget(128),
      List(
        RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Off",
          "Budget(128)",
          "gemini-2.5-pro cannot turn thinking off; its minimum budget is sent"
        )
      )
    )
  }

  it should "send a budget that fits as asked, and fit one that does not, reporting it" in {
    plan("gemini-2.5-flash", budgeted(8000)) shouldBe Plan(budget(8000), Nil)
    plan("gemini-2.5-flash", budgeted(100000)) shouldBe Plan(
      budget(24576),
      List(
        RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Budget(100000)",
          "Budget(24576)",
          "gemini-2.5-flash takes a thinking budget from 1 to 24576"
        )
      )
    )
    plan("gemini-2.5-flash-lite", budgeted(100)).thinkingConfig shouldBe budget(512)
  }

  it should "drop a reasoning level, which it has no field for, and send nothing for it" in {
    plan("gemini-2.5-flash", CompletionOptions().withReasoning(ReasoningEffort.High)) shouldBe Plan(
      None,
      List(
        RequestAdjustment.Dropped(
          RequestOption.Reasoning,
          "gemini-2.5-flash takes a thinking budget, not a reasoning level"
        )
      )
    )
  }

  // -------- Gemini 3 and later: thinkingLevel --------

  "A Gemini 3 model" should "send a reasoning level as its thinkingLevel" in {
    plan("gemini-3-pro-preview", CompletionOptions().withReasoning(ReasoningEffort.High)) shouldBe Plan(
      level("HIGH"),
      Nil
    )
    plan("gemini-3-flash-preview", CompletionOptions().withReasoning(ReasoningEffort.Minimal)) shouldBe
      Plan(level("MINIMAL"), Nil)
    plan("gemini-4-hydrogen", CompletionOptions().withReasoning(ReasoningEffort.XHigh)) shouldBe Plan(
      level("XHIGH"),
      Nil
    )
  }

  it should "send a level the model lacks as the nearest it has, and report it" in {
    plan("gemini-3-pro-preview", CompletionOptions().withReasoning(ReasoningEffort.Max)) shouldBe Plan(
      level("HIGH"),
      List(RequestAdjustment.Adjusted(RequestOption.Reasoning, "max", "high", "gemini-3-pro-preview has no max level"))
    )
    plan("gemini-3-pro-preview", CompletionOptions().withReasoning(ReasoningEffort.Minimal)).thinkingConfig shouldBe
      level("LOW")
  }

  it should "send Thinking.Off as the lowest level, reported, over a level also set" in {
    plan("gemini-3-flash-preview", off.withReasoning(ReasoningEffort.High)) shouldBe Plan(
      level("MINIMAL"),
      List(
        RequestAdjustment.Adjusted(
          RequestOption.Thinking,
          "Off",
          "minimal",
          "gemini-3-flash-preview cannot turn thinking off; its lowest thinking level is sent"
        ),
        RequestAdjustment.Adjusted(RequestOption.Reasoning, "high", "minimal", "thinking = Off takes precedence")
      )
    )
    plan("gemini-3-pro-preview", off).thinkingConfig shouldBe level("LOW")
  }

  it should "call adaptive thinking not needed, and drop a budget, sending the level if one is set" in {
    plan("gemini-3-pro-preview", adaptive) shouldBe Plan(
      None,
      List(RequestAdjustment.NotNeeded(RequestOption.Thinking, "gemini-3-pro-preview decides how much to think"))
    )
    val withLevel = plan("gemini-3-pro-preview", budgeted(8000).withReasoning(ReasoningEffort.Low))
    withLevel.thinkingConfig shouldBe level("LOW")
    withLevel.adjustments.map(_.option) shouldBe List(RequestOption.Thinking)
    withLevel.adjustments.head shouldBe a[RequestAdjustment.Dropped]
  }

  // -------- the rest --------

  "Any other model" should "be sent nothing, and report both as dropped, as before" in {
    val both = CompletionOptions().withReasoning(ReasoningEffort.High).withThinking(Thinking.Adaptive)
    val p    = plan("gemini-2.0-flash", both)
    p.thinkingConfig shouldBe None
    p.adjustments.map(_.option) shouldBe List(RequestOption.Reasoning, RequestOption.Thinking)
  }

  "Every model" should "be sent nothing when neither is set" in {
    for (model <- List("gemini-2.5-pro", "gemini-3-pro-preview", "gemini-2.0-flash"))
      withClue(model)(plan(model, CompletionOptions()) shouldBe Plan(None, Nil))
  }
}
