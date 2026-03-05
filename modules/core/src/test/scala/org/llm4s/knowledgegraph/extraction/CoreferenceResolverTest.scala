package org.llm4s.knowledgegraph.extraction

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.Completion
import org.llm4s.error.ProcessingError

class CoreferenceResolverTest extends AnyFunSuite with Matchers with MockFactory {

  private def makeCompletion(content: String): Completion = Completion(
    id = "test-id",
    content = content,
    model = "test-model",
    toolCalls = Nil,
    created = 1234567890L,
    message = org.llm4s.llmconnect.model.AssistantMessage(Some(content), Nil),
    usage = None
  )

  test("CoreferenceResolver should replace pronouns with explicit entity names") {
    val llmClient = mock[LLMClient]
    val resolver  = new CoreferenceResolver(llmClient)

    val resolvedText = "Steve Jobs founded Apple. Steve Jobs was known for Steve Jobs's vision."

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(resolvedText)))

    val result = resolver.resolve("Steve Jobs founded Apple. He was known for his vision.")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe resolvedText
  }

  test("CoreferenceResolver should return unchanged text when no pronouns exist") {
    val llmClient = mock[LLMClient]
    val resolver  = new CoreferenceResolver(llmClient)

    val originalText = "Alice works at Acme Corp. Bob works at Beta Inc."

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(originalText)))

    val result = resolver.resolve(originalText)

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe originalText
  }

  test("CoreferenceResolver should propagate LLM errors") {
    val llmClient = mock[LLMClient]
    val resolver  = new CoreferenceResolver(llmClient)

    val error = ProcessingError("llm_error", "LLM request failed")
    (llmClient.complete _)
      .expects(*, *)
      .returning(Left(error))

    val result = resolver.resolve("Some text with pronouns.")

    result should be(a[Left[_, _]])
    result.left.toOption.get shouldBe a[ProcessingError]
  }

  test("CoreferenceResolver should handle empty text") {
    val llmClient = mock[LLMClient]
    val resolver  = new CoreferenceResolver(llmClient)

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion("")))

    val result = resolver.resolve("")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe ""
  }

  test("CoreferenceResolver should resolve indirect references") {
    val llmClient = mock[LLMClient]
    val resolver  = new CoreferenceResolver(llmClient)

    val resolvedText = "Google launched a new product. Google announced Google's product at Google's annual conference."

    (llmClient.complete _)
      .expects(*, *)
      .returning(Right(makeCompletion(resolvedText)))

    val result = resolver.resolve("Google launched a new product. The company announced it at their annual conference.")

    result should be(a[Right[_, _]])
    result.toOption.get shouldBe resolvedText
  }
}
