package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.model.{ CompletionOptions, PromptCache, ReasoningEffort, ResponseFormat, Thinking }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CompletionCacheKeySpec extends AnyFlatSpec with Matchers {

  "CompletionCacheKey" should "decide every CompletionOptions field: in the key, or listed as excluded" in {
    val fields   = CompletionOptions().productElementNames.toSet
    val inTheKey = CompletionCacheKey.of(CompletionOptions()).productElementNames.toSet
    withClue("a new CompletionOptions field must join CompletionCacheKey or CompletionCacheKey.Excluded: ") {
      (fields -- inTheKey -- CompletionCacheKey.Excluded) shouldBe empty
    }
    (inTheKey -- fields) shouldBe empty
    (inTheKey & CompletionCacheKey.Excluded) shouldBe empty
  }

  it should "be equal for equal options" in {
    val options = CompletionOptions(temperature = 0.2, maxTokens = Some(64))
      .withReasoning(ReasoningEffort.High)
      .withThinking(Thinking.Adaptive)
    CompletionCacheKey.of(options) shouldBe CompletionCacheKey.of(options.withTemperature(0.2))
  }

  it should "differ when any field that changes the answer differs" in {
    val base = CompletionOptions()
    val changed = List(
      base.withTemperature(0.0),
      base.withTopP(0.5),
      base.withMaxTokens(10),
      base.withPresencePenalty(0.5),
      base.withFrequencyPenalty(0.5),
      base.withReasoning(ReasoningEffort.High),
      base.withThinking(Thinking.Off),
      base.withThinking(Thinking.budget(2048).toOption.get),
      base.withResponseFormat(ResponseFormat.Json)
    )
    changed.foreach(options =>
      withClue(options)(CompletionCacheKey.of(options) should not be CompletionCacheKey.of(base))
    )
    CompletionCacheKey.of(base.withThinking(Thinking.Off)) should not be CompletionCacheKey.of(
      base.withThinking(Thinking.Adaptive)
    )
  }

  it should "leave out promptCache, which changes what a call costs and not what it returns" in {
    CompletionCacheKey.Excluded shouldBe Set("promptCache")
    CompletionCacheKey.of(CompletionOptions().withPromptCache(PromptCache.default)) shouldBe
      CompletionCacheKey.of(CompletionOptions())
  }
}
