package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IntegrationSpec extends AnyFlatSpec with Matchers {

  // FakeLogger is now imported from TestHelpers
  import TestHelpers.FakeLogger

  class MockClient extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", System.currentTimeMillis(), "Mock Response", "model", AssistantMessage("Mock Response")))

    override def streamComplete(
      c: Conversation,
      o: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = {
      onChunk(StreamedChunk("id", Some("Mock"), None, None, None))
      onChunk(StreamedChunk("id", Some(" Response"), None, Some("stop"), None))
      Right(Completion("id", System.currentTimeMillis(), "Mock Response", "model", AssistantMessage("Mock Response")))
    }

    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 2048
  }

  "LLMClientPipeline Integration" should "apply multiple middleware correctly" in {
    val logger     = new FakeLogger()
    val baseClient = new MockClient()

    val client = LLMClientPipeline(baseClient)
      .use(new RequestIdMiddleware()) // Innermost
      .use(new LoggingMiddleware(logger = logger))
      .use(new InputSanitizationMiddleware(maxTotalCharacters = 100)) // Outermost
      .build()

    // 1. Valid Request
    val conversation = Conversation(Seq(UserMessage("Hello")))
    val result       = client.complete(conversation)

    result.isRight shouldBe true
    result.map(_.content) shouldBe Right("Mock Response")

    // Verify logging
    logger.debugs.exists(_.contains("Request:")) shouldBe true
    logger.debugs.exists(_.contains("Success")) shouldBe true

    // 2. Invalid Request (Sanitization)
    val invalidConversation = Conversation(Seq(UserMessage("A" * 101)))
    val invalidResult       = client.complete(invalidConversation)

    invalidResult.isLeft shouldBe true
    // invalidResult.left.get shouldBe a [org.llm4s.error.InvalidInputError] // usage deprecated
    invalidResult.swap.getOrElse(fail("Expected failure")).shouldBe(a[org.llm4s.error.InvalidInputError])

    // Logging for failure
    // Sanitization is added AFTER Logging, so it wraps Logging.
    // Sanitization failure returns Left directly; Logging (inner) is not invoked.
    logger.warns.isEmpty shouldBe true
  }
}
