package org.llm4s.eval

import org.llm4s.eval.metrics._
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EvaluatorSpec extends AnyFlatSpec with Matchers {

  class MockLLMClient(response: String) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(
        Completion(
          id = "test-id",
          created = System.currentTimeMillis(),
          content = response,
          model = "test-model",
          message = AssistantMessage(response),
          usage = Some(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150))
        )
      )

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  class FailingMockLLMClient extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(NetworkError("Mock error", None, "mock://test"))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  val goodResponse = "SCORE: 0.85\nEXPLANATION: Test"

  "Evaluator.evaluate" should "return EvalResult on success" in {
    val result =
      new Evaluator(new MockLLMClient(goodResponse)).evaluate(Faithfulness(), EvalContext("Q", Seq("C"), "A"))
    result.isRight shouldBe true
    result.toOption.get.score shouldBe 0.85
  }

  it should "propagate errors from metric" in {
    new Evaluator(new FailingMockLLMClient())
      .evaluate(Faithfulness(), EvalContext("Q", Seq("C"), "A"))
      .isLeft shouldBe true
  }

  "Evaluator.evaluateAll" should "evaluate all metrics" in {
    val result = new Evaluator(new MockLLMClient(goodResponse))
      .evaluateAll(Seq(Faithfulness(), AnswerRelevance()), EvalContext("Q", Seq("C"), "A"))
    result.isRight shouldBe true
    result.toOption.get should have size 2
  }

  it should "fail on first error" in {
    new Evaluator(new FailingMockLLMClient())
      .evaluateAll(Seq(Faithfulness(), AnswerRelevance()), EvalContext("Q", Seq("C"), "A"))
      .isLeft shouldBe true
  }

  "Evaluator.apply" should "create an evaluator" in {
    val client    = new MockLLMClient(goodResponse)
    val evaluator = Evaluator(client)
    evaluator shouldBe a[Evaluator]
    evaluator.llmClient shouldBe client
  }
}
