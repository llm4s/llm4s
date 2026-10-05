package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.{ AgentContext, AgentThread, ThreadStatus }
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
  private def mockClient(response: String): LLMClient = new LLMClient {
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

  /** A client that answers `response` and records the contents of every conversation it was given. */
  private def recordingClient(response: String, seen: scala.collection.mutable.ArrayBuffer[Seq[String]]): LLMClient =
    new LLMClient {
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
        seen += conversation.messages.map(_.content)
        Right(Completion("id", 0L, response, "m", AssistantMessage(response, toolCalls = List.empty)))
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) = complete(c, o)
      override def getContextWindow(): Int                                                         = 4096
      override def getReserveCompletion(): Int                                                     = 512
    }

  private def emptySessionState(dir: String = "./sessions"): SessionState =
    SessionState(
      thread = None,
      sessionId = SessionId(UUID.randomUUID().toString),
      sessionDir = DirectoryPath(dir)
    )

  private def sessionStateWithAgent(
    messages: Seq[Message],
    status: ThreadStatus = ThreadStatus.Completed
  ): SessionState =
    emptySessionState().withThread(AgentThread(threadId = "test", messages = messages, status = status))

  private def assistantAgent(client: LLMClient = null.asInstanceOf[LLMClient]): AssistantAgent =
    new AssistantAgent(client, emptyTools, "./sessions")

  // --- runTurn ---

  "AssistantAgent.runTurn" should "continue an existing conversation, with the model seeing the earlier turns" in {
    val seen   = scala.collection.mutable.ArrayBuffer.empty[Seq[String]]
    val client = recordingClient("follow-up answer", seen)
    val state  = sessionStateWithAgent(Seq(UserMessage("hi"), AssistantMessage("hello")))

    val result = assistantAgent(client).runTurn("follow-up", state)

    result match {
      case Right(newState) =>
        val thread = newState.thread.getOrElse(fail("Expected thread to be defined"))
        thread.messages.map(_.content) shouldBe Seq("hi", "hello", "follow-up", "follow-up answer")
        thread.status shouldBe ThreadStatus.Completed
        (seen.head should contain).allOf("hi", "hello", "follow-up")
      case Left(err) => fail(s"Expected Right but got: ${err.message}")
    }
  }

  it should "start the conversation on the first message" in {
    val state = emptySessionState()
    val agent = assistantAgent(mockClient("first answer"))

    val result = agent.runTurn("first query", state)

    result match {
      case Right(newState) =>
        val thread = newState.thread.getOrElse(fail("Expected thread to be defined"))
        thread.messages.map(_.content) shouldBe Seq("first query", "first answer")
      case Left(err) => fail(s"Expected Right but got: ${err.message}")
    }
  }

  it should "report a failing model as a SessionError carrying the model's error, and keep the session unchanged" in {
    val state  = emptySessionState()
    val result = assistantAgent(failingClient("network error")).runTurn("query", state)

    result match {
      case Left(err: org.llm4s.error.AssistantError.SessionError) =>
        err.message should include("network error")
        err.llmCause.map(_.message) shouldBe Some("network error")
      case other => fail(s"Expected a SessionError, got $other")
    }
    state.thread shouldBe None
  }

  it should "support an explicit AgentContext" in {
    val agent  = assistantAgent(mockClient("done with context"))
    val result = agent.runTurn("finish this", emptySessionState(), AgentContext(debug = true))

    result.isRight shouldBe true
  }

  it should "cap a turn at the default step limit" in {
    val toolCall = ToolCall("c1", "missing_tool", ujson.Obj())
    val looping = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Right(
          Completion("id", 0L, "", "m", AssistantMessage("", toolCalls = List(toolCall)), toolCalls = List(toolCall))
        )
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) = complete(c, o)
      override def getContextWindow(): Int                                                         = 4096
      override def getReserveCompletion(): Int                                                     = 512
    }

    val result = assistantAgent(looping).runTurn("loop forever", emptySessionState())

    result.map(_.thread.map(_.status)) match {
      case Right(Some(ThreadStatus.Failed(message))) => message should include("step limit")
      case other                                     => fail(s"Expected a Failed thread, got $other")
    }
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

  it should "return Left when there is no thread" in {
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

  it should "return Left for a thread that did not complete, even if it holds an assistant message" in {
    val state = sessionStateWithAgent(
      Seq(UserMessage("q"), AssistantMessage("partial")),
      status = ThreadStatus.Failed("Maximum step limit reached")
    )

    assistantAgent().extractFinalResponse(state).isLeft shouldBe true
  }

  // --- processInput with a constructor-provided context ---

  "AssistantAgent" should "propagate a constructor-provided AgentContext through processInput" in {
    val client = mockClient("done with constructor context")
    val agent =
      new AssistantAgent(client, emptyTools, "./sessions", agentContext = AgentContext(debug = true))
    val emptyState = emptySessionState()

    val result = agent.processInput("hello", emptyState)
    result.isRight shouldBe true
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
