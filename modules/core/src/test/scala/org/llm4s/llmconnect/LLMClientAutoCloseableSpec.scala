package org.llm4s.llmconnect

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Success, Failure, Using }

/**
 * Tests that [[LLMClient]] is `AutoCloseable` and works idiomatically
 * with `scala.util.Using` for resource management.
 */
class LLMClientAutoCloseableSpec extends AnyFlatSpec with Matchers {

  private val stubCompletion = Completion(
    id = "test-1",
    created = 0L,
    content = "ok",
    model = "test",
    message = AssistantMessage("ok")
  )

  /** Minimal stub that tracks close() invocations. */
  private class StubClient extends LLMClient {
    var closeCount = 0

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] = Right(stubCompletion)

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = Right(stubCompletion)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
    override def close(): Unit               = closeCount += 1
  }

  "LLMClient" should "be an instance of AutoCloseable" in {
    val client: LLMClient = new StubClient
    client shouldBe a[AutoCloseable]
  }

  it should "work with scala.util.Using for automatic resource management" in {
    val stub   = new StubClient
    val result = Using(stub)(client => client.complete(Conversation(Seq(UserMessage("hello")))))
    result shouldBe Success(Right(stubCompletion))
    stub.closeCount shouldBe 1
  }

  it should "close even if an exception is thrown inside Using" in {
    val stub   = new StubClient
    val result = Using(stub)(_ => throw new RuntimeException("boom"))
    result shouldBe a[Failure[_]]
    stub.closeCount shouldBe 1
  }

  it should "be safe to call close() multiple times" in {
    val stub = new StubClient
    stub.close()
    stub.close()
    stub.closeCount shouldBe 2 // Stub implementations may verify call counts
  }
}
