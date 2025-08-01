package org.llm4s.llmconnect

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.AssistantMessage
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues.{ convertEitherToValuable, convertLeftProjectionToValuable }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnhancedClientAdapterSpec extends AnyFlatSpec with Matchers with MockFactory {

  "LLMClientAdapter" should "convert legacy client errors to enhanced errors" in {
    val mockLegacyClient = mock[LLMClient]
    val adapter          = new LLMClientAdapter(mockLegacyClient)

    val conversation = model.Conversation(Seq(model.UserMessage("test")))
    val legacyError  = model.AuthenticationError("auth failed")

    (mockLegacyClient.complete _)
      .expects(conversation, *)
      .returning(Left(legacyError))

    val result = adapter.complete(conversation)

    result should be(a[Left[_, _]])
    result.left.value shouldBe a[LLMError.AuthenticationError]
    result.left.value.message shouldBe "auth failed"
  }

  it should "pass through successful responses unchanged" in {
    val mockLegacyClient = mock[LLMClient]
    val adapter          = new LLMClientAdapter(mockLegacyClient)

    val conversation = model.Conversation(Seq(model.UserMessage("test")))
    val completion   = model.Completion(id = "comp-123", created = 1234567890L, message = AssistantMessage())

    (mockLegacyClient.complete _)
      .expects(conversation, *)
      .returning(Right(completion))

    val result = adapter.complete(conversation)

    result should be(a[Right[_, _]])
    result.value shouldBe completion
  }
}
