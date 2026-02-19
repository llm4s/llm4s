package org.llm4s.llmconnect.middleware

import org.llm4s.error.InvalidInputError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InputSanitizationMiddlewareSpec extends AnyFlatSpec with Matchers {

  class NoOpClient extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))
    override def streamComplete(
      c: Conversation,
      o: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))
    override def getContextWindow(): Int     = 100
    override def getReserveCompletion(): Int = 10
  }

  "InputSanitizationMiddleware" should "allow valid inputs" in {
    val middleware   = new InputSanitizationMiddleware(maxTotalCharacters = 100)
    val client       = middleware.wrap(new NoOpClient)
    val conversation = Conversation(Seq(UserMessage("hello")))

    (client.complete(conversation) should be).a(Symbol("isRight"))
  }

  it should "reject inputs exceeding max length" in {
    val middleware   = new InputSanitizationMiddleware(maxTotalCharacters = 10)
    val client       = middleware.wrap(new NoOpClient)
    val conversation = Conversation(Seq(UserMessage("this is way too long")))

    val result = client.complete(conversation)
    (result should be).a(Symbol("isLeft"))
    result.swap.getOrElse(fail("Expected Left")).shouldBe(a[InvalidInputError])
  }

  it should "reject inputs with forbidden patterns" in {
    val middleware = new InputSanitizationMiddleware(
      forbiddenPatterns = Seq("FORBIDDEN".r)
    )
    val client       = middleware.wrap(new NoOpClient)
    val conversation = Conversation(Seq(UserMessage("This contains FORBIDDEN content")))

    val result = client.complete(conversation)
    (result should be).a(Symbol("isLeft"))
    result.swap.getOrElse(fail("Expected Left")).shouldBe(a[InvalidInputError])
  }
}
