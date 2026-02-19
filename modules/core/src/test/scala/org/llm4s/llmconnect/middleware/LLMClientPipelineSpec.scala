package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable.ArrayBuffer

class LLMClientPipelineSpec extends AnyFlatSpec with Matchers {

  class MockMiddleware(val name: String, log: ArrayBuffer[String]) extends LLMMiddleware {
    override def wrap(client: LLMClient): LLMClient = new MiddlewareClient(client) {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        log += s"$name enter"
        val res = client.complete(conversation, options)
        log += s"$name exit"
        res
      }
    }
  }

  class MockBaseClient(log: ArrayBuffer[String]) extends LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      log += "base"
      Right(
        Completion(
          id = "test-id",
          created = 1234567890L,
          content = "test-content",
          model = "test-model",
          message = AssistantMessage("test-content")
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = ???

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 100
  }

  "LLMClientPipeline" should "apply middleware in correct order" in {
    val log  = ArrayBuffer[String]()
    val base = new MockBaseClient(log)

    val client = LLMClientPipeline(base)
      .use(new MockMiddleware("inner", log))
      .use(new MockMiddleware("outer", log))
      .build()

    client.complete(Conversation(Seq.empty))

    // FIFO order of application:
    // 1. inner is added first, so it wraps base.
    // 2. outer is added second, so it wraps (inner(base)).
    // Execution flow: outer calls inner, inner calls base.

    log.toSeq shouldBe Seq(
      "outer enter",
      "inner enter",
      "base",
      "inner exit",
      "outer exit"
    )
  }

  it should "return middleware names in application order" in {
    val log  = ArrayBuffer[String]()
    val base = new MockBaseClient(log)

    val pipeline = LLMClientPipeline(base)
      .use(new MockMiddleware("one", log))
      .use(new MockMiddleware("two", log))

    pipeline.middlewareNames shouldBe Seq("one", "two")
  }
}
