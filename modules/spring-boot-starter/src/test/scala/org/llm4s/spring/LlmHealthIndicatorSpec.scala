package org.llm4s.spring

import org.llm4s.java.JLlmClientTestFactory
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.springframework.boot.actuate.health.Status

class LlmHealthIndicatorSpec extends AnyFlatSpec with Matchers {

  private val stubLlmClient: LLMClient = new LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "ok", "m", AssistantMessage("ok")))
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      Right(Completion("id", 0L, "ok", "m", AssistantMessage("ok")))
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  "LlmHealthIndicator.health()" should "report UP when the client is non-null" in {
    val client    = JLlmClientTestFactory.create(stubLlmClient)
    val indicator = new LlmHealthIndicator(client)
    val health    = indicator.health()
    health.getStatus shouldBe Status.UP
    health.getDetails.containsKey("provider") shouldBe true
  }

  it should "report DOWN when the client is null" in {
    val indicator = new LlmHealthIndicator(null)
    val health    = indicator.health()
    health.getStatus shouldBe Status.DOWN
    health.getDetails.containsKey("reason") shouldBe true
  }
}
