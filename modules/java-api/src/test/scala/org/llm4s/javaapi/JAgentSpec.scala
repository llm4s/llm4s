package org.llm4s.javaapi

import org.llm4s.agent.AgentStatus
import org.llm4s.agent.graph.GraphError
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

  "run(String)" should "return a completed AgentResult when the LLM completes normally" in {
    val agent  = Llm4s.createAgent(new JLlmClient(completingClient("42")))
    val result = agent.run("What is 6*7?")
    result.isSuccess shouldBe true
    result.get().status shouldBe AgentStatus.Completed("42")
  }

  it should "return the LLM answer as the last message of the conversation" in {
    val agent  = Llm4s.createAgent(new JLlmClient(completingClient("Paris")))
    val result = agent.run("Capital of France?")
    result.isSuccess shouldBe true
    result.get().messages.last.content shouldBe "Paris"
    result.get().answer shouldBe Some("Paris")
  }

  it should "return a failure result when the underlying LLM call fails" in {
    val error  = APIError("test-provider", "timeout")
    val agent  = Llm4s.createAgent(new JLlmClient(failingClient(error)))
    val result = agent.run("hello")
    result.isFailure shouldBe true
  }

  "createAgent(client, tools)" should "succeed with an empty tool registry" in {
    val agent  = Llm4s.createAgent(new JLlmClient(completingClient("done")), ToolRegistry.empty)
    val result = agent.run("query")
    result.isSuccess shouldBe true
  }

  it should "return failure when the LLM fails, even with tools provided" in {
    val error  = APIError("test-provider", "server error")
    val agent  = Llm4s.createAgent(new JLlmClient(failingClient(error)), ToolRegistry.empty)
    val result = agent.run("query")
    result.isFailure shouldBe true
    result.getError().error shouldBe a[GraphError.NodeFailed]
  }

  "continueConversation" should "run the next turn on the same conversation" in {
    val agent  = Llm4s.createAgent(new JLlmClient(completingClient("ok")))
    val first  = agent.run("one").get()
    val second = agent.continueConversation(first, "two").get()
    second.threadId shouldBe first.threadId
    second.messages.map(_.content) shouldBe Vector("one", "ok", "two", "ok")
  }

  it should "return a failure for a null previous result or query" in {
    val agent = Llm4s.createAgent(new JLlmClient(completingClient("ok")))
    val first = agent.run("one").get()
    agent.continueConversation(null, "two").isFailure shouldBe true
    agent.continueConversation(first, null).isFailure shouldBe true
  }

  "forget" should "remove the conversation, so its thread starts afresh" in {
    val agent = Llm4s.createAgent(new JLlmClient(completingClient("ok")))
    val first = agent.run("one").get()
    agent.forget(first).isSuccess shouldBe true
    agent.continueConversation(first, "two").get().messages.map(_.content) shouldBe Vector("two", "ok")
    agent.forget(null).isFailure shouldBe true
  }
}
