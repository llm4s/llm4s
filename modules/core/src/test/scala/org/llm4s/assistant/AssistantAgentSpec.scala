package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.{ AgentState, AgentStatus }
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ SessionId, DirectoryPath, Result }
import java.util.UUID

class AssistantAgentSpec extends AnyFlatSpec with Matchers {

  // --- Helpers ---

  private val emptyTools = ToolRegistry.empty

  /** Mock LLM client that always returns a fixed assistant response. */
  private def mockClient(response: String = "Hello!"): LLMClient = new LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): Result[Completion] =
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

  /** Mock LLM client that always returns a failure. */
  private def failingClient(msg: String): LLMClient = new LLMClient {
    override def complete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): Result[Completion] =
      Left(UnknownError(msg, new RuntimeException(msg)))
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def emptySessionState(dir: String = "./sessions"): SessionState =
    SessionState(
      agentState = None,
      sessionId = SessionId(UUID.randomUUID().toString),
      sessionDir = DirectoryPath(dir)
    )

  private def sessionStateWithAgent(
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

  private def assistantAgent(client: LLMClient = null.asInstanceOf[LLMClient]): AssistantAgent =
    new AssistantAgent(client, emptyTools, "./sessions")

  // --- addUserMessage ---

  "AssistantAgent.addUserMessage" should "add message to existing conversation (Some branch)" in {
    val state = sessionStateWithAgent(Seq(UserMessage("hi"), AssistantMessage("hello")))
    val agent = assistantAgent()

    val result = agent.addUserMessage("follow-up", state)

    result match {
      case Right(newState) =>
        newState.agentState match {
          case Some(as) =>
            as.conversation.messages.last shouldBe UserMessage("follow-up")
            as.status shouldBe AgentStatus.InProgress
          case None => fail("Expected agentState to be defined")
        }
      case Left(err) => fail(s"Expected Right but got: ${err.message}")
    }
  }

  it should "initialize agent state on first message (None branch)" in {
    val state = emptySessionState()
    val agent = assistantAgent(mockClient())

    val result = agent.addUserMessage("first query", state)

    result match {
      case Right(newState) =>
        newState.agentState match {
          case Some(as) => as.initialQuery shouldBe Some("first query")
          case None     => fail("Expected agentState to be defined")
        }
      case Left(err) => fail(s"Expected Right but got: ${err.message}")
    }
  }

  it should "succeed on initialization with a broken LLM client (no LLM call during init)" in {
    val state        = emptySessionState()
    val brokenClient = failingClient("init failed")
    // initializeSafe does not call the LLM — it only builds the initial AgentState
    // from the query and tool registry. A broken client therefore does not cause init to fail.
    val agent  = assistantAgent(brokenClient)
    val result = agent.addUserMessage("query", state)
    result.isRight shouldBe true
  }

  // --- extractFinalResponse ---

  "AssistantAgent.extractFinalResponse" should "return the last assistant message without tool calls" in {
    val state = sessionStateWithAgent(
      Seq(UserMessage("q"), AssistantMessage("the answer"))
    )
    val agent = assistantAgent()

    agent.extractFinalResponse(state) shouldBe Right("the answer")
  }

  it should "skip tool-call messages and return the last plain assistant message" in {
    val toolCall = ToolCall(id = "tc1", name = "myTool", arguments = ujson.Obj())
    val state = sessionStateWithAgent(
      Seq(
        UserMessage("q"),
        AssistantMessage("", toolCalls = List(toolCall)),
        AssistantMessage("final answer")
      )
    )
    val agent = assistantAgent()

    agent.extractFinalResponse(state) shouldBe Right("final answer")
  }

  it should "return Left when there is no agent state" in {
    val state = emptySessionState()
    val agent = assistantAgent()

    agent.extractFinalResponse(state).isLeft shouldBe true
  }

  it should "return Left when conversation has no plain assistant message" in {
    val toolCall = ToolCall(id = "tc1", name = "myTool", arguments = ujson.Obj())
    val state = sessionStateWithAgent(
      Seq(UserMessage("q"), AssistantMessage("", toolCalls = List(toolCall)))
    )
    val agent = assistantAgent()

    agent.extractFinalResponse(state).isLeft shouldBe true
  }

  // --- runAgentToCompletion ---

  "AssistantAgent.runAgentToCompletion" should "return Left when there is no agent state" in {
    val state = emptySessionState()
    val agent = assistantAgent()

    agent.runAgentToCompletion(state).isLeft shouldBe true
  }

  it should "return immediately when agent status is already Complete" in {
    val state = sessionStateWithAgent(
      Seq(UserMessage("q"), AssistantMessage("done")),
      status = AgentStatus.Complete
    )
    val agent = assistantAgent() // null client — must not be called

    agent.runAgentToCompletion(state).isRight shouldBe true
  }

  it should "run steps and complete when LLM returns a plain response" in {
    val agent       = assistantAgent(mockClient("I am done"))
    val emptyState  = emptySessionState()
    val initialized = agent.addUserMessage("what is 2+2?", emptyState)

    initialized match {
      case Right(stateAfterInit) =>
        val result = agent.runAgentToCompletion(stateAfterInit)
        result match {
          case Right(finalState) =>
            finalState.agentState match {
              case Some(as) => as.status shouldBe AgentStatus.Complete
              case None     => fail("Expected agentState to be defined")
            }
          case Left(err) => fail(s"Expected Right but got: ${err.message}")
        }
      case Left(err) => fail(s"Init failed: ${err.message}")
    }
  }

  it should "return Left when the LLM call fails during step execution" in {
    val agent      = assistantAgent(failingClient("network error"))
    val emptyState = emptySessionState()

    // Initialize (doesn't call LLM)
    agent.addUserMessage("query", emptyState) match {
      case Right(stateAfterInit) =>
        val result = agent.runAgentToCompletion(stateAfterInit)
        result.isLeft shouldBe true
      case Left(err) => fail(s"Init failed: ${err.message}")
    }
  }

  // --- processInput ---

  "AssistantAgent.processInput" should "return empty string for empty input" in {
    val state  = emptySessionState()
    val agent  = assistantAgent()
    val result = agent.processInput("", state)

    result shouldBe Right((state, ""))
  }

  it should "handle slash commands without calling the LLM" in {
    val state  = emptySessionState()
    val agent  = assistantAgent() // null client — must not be called
    val result = agent.processInput("/help", state)

    result match {
      case Right((_, response)) => response should not be empty
      case Left(err)            => fail(s"Expected Right but got: ${err.message}")
    }
  }

  it should "route non-command input to the agent query path" in {
    val agent  = assistantAgent(mockClient("42"))
    val state  = emptySessionState()
    val result = agent.processInput("what is 6x7?", state)

    result match {
      case Right((_, response)) => response should include("42")
      case Left(err)            => fail(s"Expected Right but got: ${err.message}")
    }
  }
}
