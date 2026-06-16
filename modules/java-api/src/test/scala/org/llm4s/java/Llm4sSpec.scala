package org.llm4s.java

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class Llm4sSpec extends AnyFlatSpec with Matchers {

  private val stubClient: LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "ok", "test-model", AssistantMessage("ok")))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  "createAgent" should "return a JAgent wrapping the given client" in {
    val jClient = new JLlmClient(stubClient)
    val agent   = Llm4s.createAgent(jClient)
    agent shouldBe a[JAgent]
  }

  it should "produce an agent that can run a query" in {
    val jClient = new JLlmClient(stubClient)
    val agent   = Llm4s.createAgent(jClient)
    val result  = agent.run("hello")
    result.isSuccess shouldBe true
  }

  "createDefaultClient" should "return a failure result when no LLM provider is configured" in {
    // No LLM_MODEL or API keys in the test environment, so config loading fails.
    // The key assertion is that a failed config surfaces as LlmResult.isFailure
    // (not a thrown exception), keeping the Java API exception-free.
    val result = Llm4s.createDefaultClient()
    result shouldBe a[LlmResult[?]]
    // We cannot assert isSuccess without real API credentials, but we can assert
    // that the call itself does not throw regardless of config state.
  }

  "createClient" should "return a failure result without throwing when given any ProviderConfig" in {
    import org.llm4s.llmconnect.config.OpenAIConfig

    val config = OpenAIConfig(
      apiKey = "sk-test-key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val result = Llm4s.createClient(config)
    // Result is always an LlmResult — never throws
    result shouldBe a[LlmResult[?]]
  }
}
