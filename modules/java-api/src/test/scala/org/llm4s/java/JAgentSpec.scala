package org.llm4s.java

import org.llm4s.agent.AgentStatus
import org.llm4s.error.{ APIError, LLMError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class JAgentSpec extends AnyFlatSpec with Matchers {

  private def completingClient(answer: String): LLMClient = new LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      Right(
        Completion(
          id = "test-id",
          created = 0L,
          content = answer,
          model = "test-model",
          message = AssistantMessage(answer),
          toolCalls = List.empty
        )
      )
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def failingClient(error: LLMError): LLMClient = new LLMClient {
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

  "run(String)" should "return a successful AgentState when the LLM completes normally" in {
    val agent  = Llm4s.createAgent(new JLlmClient(completingClient("42")))
    val result = agent.run("What is 6*7?")
    result.isSuccess shouldBe true
    val state = result.get()
    state.status shouldBe AgentStatus.Complete
  }

  it should "return the LLM answer in the final conversation" in {
    val agent  = Llm4s.createAgent(new JLlmClient(completingClient("Paris")))
    val result = agent.run("Capital of France?")
    result.isSuccess shouldBe true
    val lastMessage = result.get().conversation.messages.last
    lastMessage.content shouldBe "Paris"
  }

  it should "return a failure result when the underlying LLM call fails" in {
    val error  = APIError("test-provider", "timeout")
    val agent  = Llm4s.createAgent(new JLlmClient(failingClient(error)))
    val result = agent.run("hello")
    result.isFailure shouldBe true
  }

  "run(String, ToolRegistry)" should "succeed with an empty tool registry" in {
    val agent  = Llm4s.createAgent(new JLlmClient(completingClient("done")))
    val result = agent.run("query", ToolRegistry.empty)
    result.isSuccess shouldBe true
  }

  it should "return failure when the LLM fails, even with tools provided" in {
    val error  = APIError("test-provider", "server error")
    val agent  = Llm4s.createAgent(new JLlmClient(failingClient(error)))
    val result = agent.run("query", ToolRegistry.empty)
    result.isFailure shouldBe true
    result.getError().error shouldBe error
  }
}
