package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LoggingMiddlewareSpec extends AnyFlatSpec with Matchers {

  // FakeLogger is now imported from TestHelpers
  import TestHelpers.FakeLogger

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

  class FailingClient extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Left(org.llm4s.error.NetworkError("boom", None, "endpoint"))
    override def streamComplete(
      c: Conversation,
      o: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = ???
    override def getContextWindow(): Int     = 100
    override def getReserveCompletion(): Int = 10
  }

  "LoggingMiddleware" should "log requests and successful responses" in {
    val logger     = new FakeLogger()
    val middleware = new LoggingMiddleware(logger = logger)
    val client     = middleware.wrap(new NoOpClient)

    client.complete(Conversation(Seq.empty))

    logger.debugs.size should be >= 2 // Request + Response
    logger.debugs.exists(_.contains("Request:")) shouldBe true
    logger.debugs.exists(_.contains("Success")) shouldBe true
  }

  it should "log failures as warnings" in {
    val logger     = new FakeLogger()
    val middleware = new LoggingMiddleware(logger = logger)
    val client     = middleware.wrap(new FailingClient)

    client.complete(Conversation(Seq.empty))

    logger.debugs.exists(_.contains("Request:")) shouldBe true
    logger.warns.size shouldBe 1
    logger.warns.head should include("Failed")
    logger.warns.head should include("boom")
  }
}
