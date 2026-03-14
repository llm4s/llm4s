package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import org.llm4s.agent.{ AgentState, AgentStatus }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ SessionId, DirectoryPath, Result }

import java.nio.file.Files
import java.util.UUID

class AssistantAgentCommandsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  private val tempDir    = Files.createTempDirectory("assistant-test-sessions")
  private val emptyTools = ToolRegistry.empty

  override def afterAll(): Unit = {
    // Clean up temp directory
    Files.list(tempDir).forEach(Files.deleteIfExists(_))
    Files.deleteIfExists(tempDir)
  }

  private def mockClient(response: String = "Hello!"): LLMClient = new LLMClient {
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      Right(
        Completion(
          id = "test-id",
          created = 0L,
          content = response,
          model = "test-model",
          message = AssistantMessage(response, toolCalls = List.empty)
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

  private def emptySessionState(): SessionState =
    SessionState(
      agentState = None,
      sessionId = SessionId(UUID.randomUUID().toString),
      sessionDir = DirectoryPath(tempDir.toString)
    )

  private def sessionStateWithMessages(
    messages: Seq[Message],
    status: AgentStatus = AgentStatus.Complete
  ): SessionState = {
    val agentState = AgentState(
      conversation = Conversation(messages),
      tools = emptyTools,
      initialQuery = Some("test"),
      status = status
    )
    emptySessionState().withAgentState(agentState)
  }

  private def assistantAgent(client: LLMClient = mockClient()): AssistantAgent =
    new AssistantAgent(client, emptyTools, tempDir.toString)

  // ========== processInput with commands ==========

  "AssistantAgent.processInput" should "handle /help command" in {
    val agent  = assistantAgent()
    val state  = emptySessionState()
    val result = agent.processInput("/help", state)

    result.isRight shouldBe true
    result.toOption.get._2 should include("/help")
    result.toOption.get._2 should include("/quit")
  }

  it should "handle /sessions command" in {
    val agent  = assistantAgent()
    val state  = emptySessionState()
    val result = agent.processInput("/sessions", state)

    result.isRight shouldBe true
    // Either shows session list or "no sessions found" message
    result.toOption.get._2 should not be empty
  }

  it should "handle /save command with title" in {
    val agent = assistantAgent()
    val state = sessionStateWithMessages(
      Seq(UserMessage("hi"), AssistantMessage("hello"))
    )
    val result = agent.processInput("/save test-session", state)

    result.isRight shouldBe true
    result.toOption.get._2 should include("saved")
  }

  it should "handle unknown slash commands gracefully" in {
    val agent  = assistantAgent()
    val state  = emptySessionState()
    val result = agent.processInput("/unknown", state)

    // Unknown commands are handled via Command.parse which returns Left,
    // but handleCommand wraps it: Left(parseError) => Right((state, parseError.message))
    result.isRight shouldBe true
    result.toOption.get._1 shouldBe state // state unchanged
  }

  // ========== processInput with queries ==========

  it should "process a non-command query and return response" in {
    val agent  = assistantAgent(mockClient("The answer is 42"))
    val state  = emptySessionState()
    val result = agent.processInput("What is the meaning of life?", state)

    result.isRight shouldBe true
    result.toOption.get._2 should include("42")
    result.toOption.get._1.agentState shouldBe defined
  }

  it should "handle multiple sequential queries maintaining state" in {
    val agent = assistantAgent(mockClient("response"))
    val state = emptySessionState()

    val result1 = agent.processInput("first query", state)
    result1.isRight shouldBe true
    val state1 = result1.toOption.get._1

    val result2 = agent.processInput("second query", state1)
    result2.isRight shouldBe true
    val state2 = result2.toOption.get._1

    // Should have accumulated messages
    state2.agentState.get.conversation.messages.size should be > state1.agentState.get.conversation.messages.size
  }

  // ========== extractFinalResponse edge cases ==========

  "AssistantAgent.extractFinalResponse" should "return the most recent assistant message" in {
    val agent = assistantAgent()
    val state = sessionStateWithMessages(
      Seq(
        UserMessage("q1"),
        AssistantMessage("answer 1"),
        UserMessage("q2"),
        AssistantMessage("answer 2")
      )
    )

    agent.extractFinalResponse(state) shouldBe Right("answer 2")
  }

  it should "return Left for conversation with only user messages" in {
    val agent = assistantAgent()
    val state = sessionStateWithMessages(Seq(UserMessage("unanswered")))

    agent.extractFinalResponse(state).isLeft shouldBe true
  }

  // ========== addUserMessage edge cases ==========

  "AssistantAgent.addUserMessage" should "set status to InProgress when adding to existing conversation" in {
    val agent = assistantAgent()
    val state = sessionStateWithMessages(
      Seq(UserMessage("hi"), AssistantMessage("hello")),
      status = AgentStatus.Complete
    )

    val result = agent.addUserMessage("follow-up", state)
    result.isRight shouldBe true
    result.toOption.get.agentState.get.status shouldBe AgentStatus.InProgress
  }

  // ========== runAgentToCompletion with error status ==========

  "AssistantAgent.runAgentToCompletion" should "return immediately for Error status" in {
    val agent = assistantAgent()
    val state = sessionStateWithMessages(
      Seq(UserMessage("q")),
      status = AgentStatus.Failed("some error")
    )

    val result = agent.runAgentToCompletion(state)
    result.isRight shouldBe true
  }
}
