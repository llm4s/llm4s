package org.llm4s.spring

import org.llm4s.error.APIError
import org.llm4s.java.{ ConversationBuilder, JLlmClientTestFactory, LlmException }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ExecutionException

class LLM4STemplateSpec extends AnyFlatSpec with Matchers {

  private def stubClient(response: Result[Completion]): LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = response
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      response
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def successTemplate(text: String) = new LLM4STemplate(
    JLlmClientTestFactory.create(
      stubClient(Right(Completion("id", 0L, text, "m", AssistantMessage(text))))
    )
  )

  private val failingTemplate = new LLM4STemplate(
    JLlmClientTestFactory.create(
      stubClient(Left(APIError("openai", "service unavailable")))
    )
  )

  "complete(String)" should "return the completion text on success" in {
    successTemplate("hello").complete("say hi") shouldBe "hello"
  }

  it should "throw LlmException when the client fails" in {
    an[LlmException] should be thrownBy failingTemplate.complete("query")
  }

  "complete(Conversation)" should "return the completion text on success" in {
    val conv = ConversationBuilder.create().user("test").build()
    successTemplate("answer").complete(conv) shouldBe "answer"
  }

  it should "throw LlmException when the client fails" in {
    val conv = ConversationBuilder.create().user("test").build()
    an[LlmException] should be thrownBy failingTemplate.complete(conv)
  }

  "complete(Conversation, CompletionOptions)" should "forward options to the client" in {
    val conv    = ConversationBuilder.create().user("q").build()
    val options = CompletionOptions(temperature = 0.5)
    successTemplate("result").complete(conv, options) shouldBe "result"
  }

  "tryComplete(String)" should "return LlmResult wrapping the text on success" in {
    val result = successTemplate("ok").tryComplete("query")
    result.isSuccess shouldBe true
    result.get() shouldBe "ok"
  }

  it should "return a failure LlmResult without throwing" in {
    val result = failingTemplate.tryComplete("query")
    result.isFailure shouldBe true
  }

  "tryComplete(Conversation)" should "return LlmResult on success" in {
    val conv = ConversationBuilder.create().user("q").build()
    successTemplate("ok").tryComplete(conv).isSuccess shouldBe true
  }

  "completeAsync(String)" should "complete the future with the text on success" in {
    successTemplate("async-ok").completeAsync("query").get() shouldBe "async-ok"
  }

  it should "complete the future exceptionally on failure" in {
    val future = failingTemplate.completeAsync("query")
    an[ExecutionException] should be thrownBy future.get()
  }

  "completeAsync(Conversation)" should "complete the future on success" in {
    val conv = ConversationBuilder.create().user("q").build()
    successTemplate("done").completeAsync(conv).get() shouldBe "done"
  }
}
