package org.llm4s.java

import org.llm4s.error.{ APIError, LLMError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JLlmClientSpec extends AnyFlatSpec with Matchers {

  private def successClient(content: String): LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, content, "test-model", AssistantMessage(content)))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def errorClient(error: LLMError): LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Left(error)
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = Left(error)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private val apiError: LLMError = APIError("test-provider", "service unavailable")

  "complete(String)" should "return the assistant text on success" in {
    val client = new JLlmClient(successClient("4"))
    val result = client.complete("What is 2+2?")
    result.isSuccess shouldBe true
    result.get() shouldBe "4"
  }

  it should "return a failure result when the underlying client fails" in {
    val client = new JLlmClient(errorClient(apiError))
    val result = client.complete("hello")
    result.isFailure shouldBe true
    result.getError().error shouldBe apiError
  }

  "complete(Conversation)" should "return the assistant text on success" in {
    val conv   = ConversationBuilder.create().user("ping").build()
    val client = new JLlmClient(successClient("pong"))
    val result = client.complete(conv)
    result.isSuccess shouldBe true
    result.get() shouldBe "pong"
  }

  it should "return a failure result when the underlying client fails" in {
    val conv   = ConversationBuilder.create().user("ping").build()
    val client = new JLlmClient(errorClient(apiError))
    val result = client.complete(conv)
    result.isFailure shouldBe true
  }

  "complete(Conversation, CompletionOptions)" should "pass options to the underlying client" in {
    var capturedOptions: Option[CompletionOptions] = None
    val capturingClient = new LLMClient {
      override def complete(
        conversation: Conversation,
        options: CompletionOptions
      ): Result[Completion] = {
        capturedOptions = Some(options)
        Right(Completion("id", 0L, "ok", "test-model", AssistantMessage("ok")))
      }
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }

    val options = CompletionOptions(temperature = 0.5)
    val conv    = ConversationBuilder.create().user("test").build()
    val client  = new JLlmClient(capturingClient)
    client.complete(conv, options)

    capturedOptions shouldBe Some(options)
  }

  "close()" should "delegate to the underlying client" in {
    var closed = false
    val trackingClient = new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Right(Completion("id", 0L, "", "m", AssistantMessage("")))
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
      override def close(): Unit               = closed = true
    }

    val client = new JLlmClient(trackingClient)
    client.close()
    closed shouldBe true
  }
}
