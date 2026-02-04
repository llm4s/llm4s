package org.llm4s.eval

import org.llm4s.eval.metrics._
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.testutil.{ FailingMockLLMClient, MockLLMClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EvalMetricSpec extends AnyFlatSpec with Matchers {

  "EvalContext" should "combine contexts into single string" in {
    val context = EvalContext("Test", Seq("Chunk 1", "Chunk 2"), "Answer")
    context.combinedContext shouldBe "Chunk 1\n\nChunk 2"
  }

  it should "detect when expected answer is present" in {
    EvalContext.withExpected("Test", Seq("C"), "A", "E").hasExpectedAnswer shouldBe true
    EvalContext("Test", Seq("C"), "A").hasExpectedAnswer shouldBe false
  }

  "EvalResult" should "format summary correctly" in {
    EvalResult("Test", 0.85, passed = true, "").summary should include("PASS")
    EvalResult("Test", 0.45, passed = false, "").summary should include("FAIL")
  }

  it should "create pass/fail results via factory methods" in {
    EvalResult.pass("T", 0.9, "").passed shouldBe true
    EvalResult.fail("T", 0.3, "").passed shouldBe false
  }

  "Faithfulness" should "pass when score is above threshold" in {
    val result =
      Faithfulness().evaluate(EvalContext("Q", Seq("C"), "A"), new MockLLMClient("SCORE: 0.85\nEXPLANATION: Good"))
    result.isRight shouldBe true
    result.toOption.get.passed shouldBe true
  }

  it should "fail when score is below threshold" in {
    val result =
      Faithfulness().evaluate(EvalContext("Q", Seq("C"), "A"), new MockLLMClient("SCORE: 0.4\nEXPLANATION: Bad"))
    result.toOption.get.passed shouldBe false
  }

  it should "fail with empty context" in {
    val result = Faithfulness().evaluate(EvalContext("Q", Seq.empty, "A"), new MockLLMClient("0.9"))
    result.toOption.get.passed shouldBe false
  }

  it should "include query and context in LLM request" in {
    val client = new MockLLMClient("SCORE: 0.9\nEXPLANATION: OK")
    Faithfulness().evaluate(EvalContext("What is X?", Seq("X is Y"), "Answer"), client)
    val msg = client.lastConversation.get.messages.collectFirst { case m: UserMessage => m }.get
    msg.content should include("What is X?")
    msg.content should include("X is Y")
  }

  it should "parse score-only responses" in {
    Faithfulness().evaluate(EvalContext("Q", Seq("C"), "A"), new MockLLMClient("0.75")).toOption.get.score shouldBe 0.75
  }

  it should "fail gracefully on unparseable response" in {
    Faithfulness().evaluate(EvalContext("Q", Seq("C"), "A"), new MockLLMClient("Cannot evaluate")).isLeft shouldBe true
  }

  "Faithfulness.strict" should "use higher threshold" in {
    Faithfulness.strict
      .evaluate(EvalContext("Q", Seq("C"), "A"), new MockLLMClient("SCORE: 0.85"))
      .toOption
      .get
      .passed shouldBe false
  }

  "AnswerRelevance" should "pass when answer is relevant" in {
    AnswerRelevance()
      .evaluate(EvalContext("Q", Seq("C"), "A"), new MockLLMClient("SCORE: 0.9"))
      .toOption
      .get
      .passed shouldBe true
  }

  it should "fail when answer is irrelevant" in {
    AnswerRelevance()
      .evaluate(EvalContext("Q", Seq("C"), "A"), new MockLLMClient("SCORE: 0.2"))
      .toOption
      .get
      .passed shouldBe false
  }

  it should "include query and answer in LLM request" in {
    val client = new MockLLMClient("SCORE: 0.9")
    AnswerRelevance().evaluate(EvalContext("Test query", Seq("C"), "Test answer"), client)
    val msg = client.lastConversation.get.messages.collectFirst { case m: UserMessage => m }.get
    msg.content should include("Test query")
    msg.content should include("Test answer")
  }

  "ContextPrecision" should "pass when chunks are relevant" in {
    ContextPrecision()
      .evaluate(EvalContext("Q", Seq("C1", "C2"), "A"), new MockLLMClient("SCORE: 0.8"))
      .toOption
      .get
      .passed shouldBe true
  }

  it should "fail when chunks are irrelevant" in {
    ContextPrecision()
      .evaluate(EvalContext("Q", Seq("C"), "A"), new MockLLMClient("SCORE: 0.2"))
      .toOption
      .get
      .passed shouldBe false
  }

  it should "fail with empty context" in {
    ContextPrecision()
      .evaluate(EvalContext("Q", Seq.empty, "A"), new MockLLMClient("0.9"))
      .toOption
      .get
      .passed shouldBe false
  }

  it should "include chunk info in explanation" in {
    val result = ContextPrecision().evaluate(
      EvalContext("Q", Seq("A", "B"), "X"),
      new MockLLMClient("SCORE: 0.67\nRELEVANT_CHUNKS: 1\nEXPLANATION: Test")
    )
    result.toOption.get.explanation should include("Relevant")
  }

  "All metrics" should "propagate LLM client errors" in {
    val client  = new FailingMockLLMClient()
    val context = EvalContext("Q", Seq("C"), "A")
    Faithfulness().evaluate(context, client).isLeft shouldBe true
    AnswerRelevance().evaluate(context, client).isLeft shouldBe true
    ContextPrecision().evaluate(context, client).isLeft shouldBe true
  }
}
