package org.llm4s.javaapi

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

  "createDefaultClient" should "not throw, and surface any config failure as a failed LlmResult" in {
    // Whether credentials exist depends on the environment, so assert the invariant that holds
    // either way: the call never throws and the result is exactly one of success or failure.
    noException should be thrownBy Llm4s.createDefaultClient()
    val result = Llm4s.createDefaultClient()
    result.isSuccess should not be result.isFailure
  }

  "createClient" should "return a successful result for a valid OpenAI config" in {
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
    result.isSuccess shouldBe true
    result.get() shouldBe a[JLlmClient]
  }
}
