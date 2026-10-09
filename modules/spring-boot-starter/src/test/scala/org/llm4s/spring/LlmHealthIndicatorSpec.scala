package org.llm4s.spring

import org.llm4s.javaapi.JLlmClientTestFactory
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

  "LlmHealthIndicator.health()" should "report UP and say it did not probe" in {
    val client    = JLlmClientTestFactory.create(stubLlmClient)
    val indicator = StarterTestSupport.indicator(client)
    val health    = indicator.health()
    health.getStatus shouldBe Status.UP
    health.getDetails.get("probe") shouldBe "disabled"
    health.getDetails.get("provider") shouldBe "openai"
    health.getDetails.get("model") shouldBe "gpt-4o"
  }
}
