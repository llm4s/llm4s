package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.{ GuardrailAction, ScoringJudgeClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The RAG judge guardrails read a score as `LLMGuardrail` does (#3234): one plain number from 0 to 1, never clamped,
 * so a judge that answers on another scale, or not in the structure asked for, never lets content through.
 */
class RAGJudgeScoreSpec extends AnyFlatSpec with Matchers {

  private val context = RAGContext("What is photosynthesis?", Seq("Plants convert sunlight to energy."))

  private def grounding(reply: String, onFail: GuardrailAction = GuardrailAction.Block) =
    new GroundingGuardrail(new ScoringJudgeClient(reply), threshold = 0.7, onFail = onFail)
      .validateWithContext("Plants convert sunlight.", context)

  private val scaled = Seq("2/10", "30%", "85", "1.5", "-0.5", "0,9", "0.9, mostly")

  "GroundingGuardrail" should "refuse a score on another scale instead of reading its digits (#3234)" in {
    scaled.foreach { score =>
      withClue(s"SCORE: $score -> ") {
        grounding(s"SCORE: $score\nGROUNDED: YES\nUNGROUNDED_CLAIMS: NONE\nEXPLANATION: x").isLeft shouldBe true
      }
    }
  }

  it should "refuse a reply without the structure it asks for, never reading a number out of it (#3234)" in {
    grounding("Not grounded: 1 claim is unsupported").isLeft shouldBe true
    grounding("1").isLeft shouldBe true
    grounding("SCORE: 0.9\nEXPLANATION: no grounded line").isLeft shouldBe true
  }

  it should "still read a plain score, labelled and wrapped as LLMGuardrail accepts (#3234)" in {
    grounding("SCORE: 0.9\nGROUNDED: YES\nUNGROUNDED_CLAIMS: NONE") shouldBe Right("Plants convert sunlight.")
    grounding("SCORE: **0.7**\nGROUNDED: YES") shouldBe Right("Plants convert sunlight.")
    grounding("SCORE: 0.69\nGROUNDED: YES").isLeft shouldBe true
  }

  it should "block with no chunks under Fix, as Fix blocks a low score (#3234)" in {
    new GroundingGuardrail(new ScoringJudgeClient("SCORE: 1\nGROUNDED: YES"), onFail = GuardrailAction.Fix)
      .validateWithContext("answer", RAGContext("q", Seq.empty))
      .isLeft shouldBe true
  }

  "ContextRelevanceGuardrail" should "refuse a scaled overall or chunk score (#3234)" in {
    def relevance(reply: String) =
      ContextRelevanceGuardrail(new ScoringJudgeClient(reply), threshold = 0.5).validateWithContext("r", context)
    relevance("OVERALL_SCORE: 3/10\nCHUNK_SCORES: 0.9").isLeft shouldBe true
    relevance("OVERALL_SCORE: 0.9\nCHUNK_SCORES: 9/10").isLeft shouldBe true
    relevance("Relevant: 1 chunk").isLeft shouldBe true
    relevance("OVERALL_SCORE: 0.9\nCHUNK_SCORES: 0.9") shouldBe Right("r")
  }

  "SourceAttributionGuardrail" should "refuse a scaled or missing attribution score (#3234)" in {
    val sourced = RAGContext.withSources("q", Seq("Content from doc A"), Seq("Document A"))
    def attribution(reply: String) =
      SourceAttributionGuardrail(new ScoringJudgeClient(reply), minAttributionScore = 0.5)
        .validateWithContext("According to Document A", sourced)
    attribution("HAS_ATTRIBUTIONS: YES\nATTRIBUTION_SCORE: 8/10").isLeft shouldBe true
    attribution("HAS_ATTRIBUTIONS: YES").isLeft shouldBe true
    attribution("HAS_ATTRIBUTIONS: YES\nATTRIBUTION_SCORE: 0.8") shouldBe Right("According to Document A")
  }

  "TopicBoundaryGuardrail" should "refuse a scaled or missing relevance score (#3234)" in {
    def topic(reply: String) =
      TopicBoundaryGuardrail(new ScoringJudgeClient(reply), Seq("scala"), threshold = 0.5).validate("pattern matching?")
    topic("IS_ON_TOPIC: YES\nRELEVANCE_SCORE: 9/10").isLeft shouldBe true
    topic("IS_ON_TOPIC: YES").isLeft shouldBe true
    topic("IS_ON_TOPIC: YES\nRELEVANCE_SCORE: 0.9") shouldBe Right("pattern matching?")
  }
}
