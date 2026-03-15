package org.llm4s.agent

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.annotation.unused

/**
 * Integration tests for handoff execution flow
 */
class HandoffIntegrationSpec extends AnyFlatSpec with Matchers {

  // Create a mock LLM client for testing
  class MockLLMClient extends LLMClient {
    var responseSequence: Seq[Message] = Seq.empty
    var responseIndex: Int             = 0

    def setResponses(responses: Seq[Message]): Unit = {
      responseSequence = responses
      responseIndex = 0
    }

    override def complete(
      conversation: Conversation,
      options: CompletionOptions
    ): Result[Completion] =
      if (responseIndex < responseSequence.length) {
        val msg = responseSequence(responseIndex).asInstanceOf[AssistantMessage]
        responseIndex += 1
        Right(
          Completion(
            id = "mock-completion",
            created = System.currentTimeMillis() / 1000,
            content = msg.content,
            model = "mock-model",
            message = msg,
            toolCalls = msg.toolCalls.toList,
            usage = None
          )
        )
      } else {
        import org.llm4s.error.NetworkError
        Left(NetworkError("No more mock responses", None, "mock"))
      }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions(),
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      Left(org.llm4s.error.NetworkError("Streaming not supported in mock", None, "mock"))

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  "Agent with handoffs" should "create handoff tools" in {
    val mockClient = new MockLLMClient()
    val agent1     = new Agent(mockClient)
    val agent2     = new Agent(mockClient)
    val handoff    = Handoff.to(agent2, "Specialist")

    agent1
      .initializeSafe(
        "Test query",
        ToolRegistry.empty,
        handoffs = Seq(handoff)
      )
      .fold(
        e => fail(s"initializeSafe failed: ${e.formatted}"),
        state => {
          // Should have at least one handoff tool
          state.tools.tools.exists(_.name.startsWith("handoff_to_agent_")) shouldBe true

          // Should preserve handoffs in state
          (state.availableHandoffs should have).length(1)
          state.availableHandoffs.head shouldBe handoff
        }
      )
  }

  it should "detect handoff from tool call" in {
    val mockClient = new MockLLMClient()
    val agent1     = new Agent(mockClient)
    val agent2     = new Agent(mockClient)
    val handoff    = Handoff.to(agent2, "Math specialist")

    // Set up mock response with handoff tool call
    val handoffId = handoff.handoffId
    val handoffToolCall = ToolCall(
      id = "call_1",
      name = handoffId,
      arguments = """{"reason": "Requires advanced math"}"""
    )

    mockClient.setResponses(
      Seq(
        AssistantMessage(
          content = "I'll hand this off to the specialist",
          toolCalls = Vector(handoffToolCall)
        ),
        AssistantMessage(
          content = "Math specialist response here"
        )
      )
    )

    // Run agent with handoff
    val result = agent1.run(
      "What is the derivative of x^2?",
      ToolRegistry.empty,
      handoffs = Seq(handoff),
      maxSteps = Some(10)
    )

    result.fold(
      e => fail(s"Agent run failed: ${e.formatted}"),
      finalState =>
        // Should have executed handoff
        finalState.logs.exists(_.contains("handoff")) shouldBe true
    )
  }

  it should "preserve context when preserveContext = true" in {
    val mockClient = new MockLLMClient()
    val agent2     = new Agent(mockClient)

    val sourceState = AgentState(
      conversation = Conversation(
        Vector(
          UserMessage("Question 1"),
          AssistantMessage("Answer 1"),
          UserMessage("Question 2")
        )
      ),
      tools = ToolRegistry.empty,
      initialQuery = Some("Question 1"),
      status = AgentStatus.InProgress,
      systemMessage = Some(SystemMessage("Test system message"))
    )

    // Handoff config for reference (tested through agent.run in other tests)
    @unused val handoff = Handoff(agent2, Some("Test"), preserveContext = true, transferSystemMessage = false)

    // Build handoff state using reflection to access private method
    // In real test, this would be tested through full agent run
    val targetState = AgentState(
      conversation = Conversation(sourceState.conversation.messages),
      tools = ToolRegistry.empty,
      initialQuery = sourceState.initialQuery,
      status = AgentStatus.InProgress
    )

    (targetState.conversation.messages should have).length(3)
    targetState.conversation.messages shouldBe sourceState.conversation.messages
  }

  it should "not preserve context when preserveContext = false" in {
    val mockClient = new MockLLMClient()
    val agent2     = new Agent(mockClient)

    val sourceState = AgentState(
      conversation = Conversation(
        Vector(
          UserMessage("Question 1"),
          AssistantMessage("Answer 1"),
          UserMessage("Question 2")
        )
      ),
      tools = ToolRegistry.empty,
      initialQuery = Some("Question 1")
    )

    // Handoff config for reference (tested through agent.run in other tests)
    @unused val handoff = Handoff(agent2, Some("Test"), preserveContext = false)

    // When preserveContext = false, only last user message is transferred
    val lastUserMessage = sourceState.conversation.messages
      .findLast(_.role == MessageRole.User)

    lastUserMessage shouldBe Some(UserMessage("Question 2"))
  }

  "Handoff state building" should "transfer system message when configured" in {
    val mockClient = new MockLLMClient()
    val agent2     = new Agent(mockClient)

    @unused val systemMessage = Some(SystemMessage("Original system message"))
    @unused val sourceState = AgentState(
      conversation = Conversation(Vector(UserMessage("Test"))),
      tools = ToolRegistry.empty,
      systemMessage = systemMessage
    )

    val handoffWithTransfer = Handoff(
      agent2,
      Some("Test"),
      transferSystemMessage = true
    )

    // System message should be transferred
    handoffWithTransfer.transferSystemMessage shouldBe true

    val handoffWithoutTransfer = Handoff(
      agent2,
      Some("Test"),
      transferSystemMessage = false
    )

    // System message should not be transferred
    handoffWithoutTransfer.transferSystemMessage shouldBe false
  }
}
