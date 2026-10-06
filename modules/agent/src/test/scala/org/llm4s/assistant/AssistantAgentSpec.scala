package org.llm4s.assistant

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.agent.{ AgentResultFixture, AgentStatus }
import org.llm4s.agent.graph.{ GraphError, RunConfig }
import org.llm4s.error.UnknownError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.{ SessionId, DirectoryPath, Result }

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class AssistantAgentSpec extends AnyFlatSpec with Matchers {

  // --- Helpers ---

  private val emptyTools = ToolRegistry.empty

  /** Mock LLM client answering each call with `responses`, in turn; past the end, the last. */
  private def mockClient(responses: Result[String]*): LLMClient = new LLMClient {
    private val calls = new AtomicInteger(0)
    override def complete(
      conversation: Conversation,
      options: CompletionOptions = CompletionOptions()
    ): Result[Completion] =
      responses(calls.getAndIncrement().min(responses.size - 1)).map { response =>
        Completion(
          id = "test-id",
          created = 0L,
          content = response,
          model = "test-model",
          message = AssistantMessage(response, toolCalls = List.empty)
        )
      }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def answering(response: String): LLMClient = mockClient(Right(response))

  private def failure(msg: String): Result[String] = Left(UnknownError(msg, new RuntimeException(msg)))

  private def emptySessionState(dir: String = "./sessions"): SessionState =
    SessionState(
      threadId = None,
      last = None,
      sessionId = SessionId(UUID.randomUUID().toString),
      sessionDir = DirectoryPath(dir)
    )

  private def assistantAgent(client: LLMClient = null.asInstanceOf[LLMClient]): AssistantAgent =
    new AssistantAgent(client, emptyTools, "./sessions")

  private def ran(agent: AssistantAgent, query: String, state: SessionState): SessionState =
    agent.runTurn(query, state).fold(e => fail(s"turn failed: ${e.message}"), state.withResult)

  // --- runTurn ---

  "AssistantAgent.runTurn" should "run the first query on a new thread" in {
    val result = assistantAgent(answering("first answer")).runTurn("first query", emptySessionState())

    result.map(_.answer) shouldBe Right(Some("first answer"))
    result.map(_.messages) shouldBe Right(Vector(UserMessage("first query"), AssistantMessage("first answer")))
  }

  it should "run later queries on the session's thread" in {
    val agent = assistantAgent(answering("an answer"))
    val first = ran(agent, "one", emptySessionState())
    val next  = ran(agent, "two", first)

    next.threadId shouldBe first.threadId
    next.messages should have size 4
  }

  it should "import a loaded session's messages into a new thread" in {
    val loaded = emptySessionState().copy(history = Vector(UserMessage("my name is Ada"), AssistantMessage("Hi Ada")))
    val state  = ran(assistantAgent(answering("Ada")), "what is my name?", loaded)

    state.messages.take(2) shouldBe loaded.history
    state.messages should have size 4
    state.history shouldBe empty
  }

  it should "continue on a new thread, with the conversation so far, when the session's thread failed" in {
    val agent  = assistantAgent(mockClient(Right("one"), failure("network error"), Right("three")))
    val first  = ran(agent, "first", emptySessionState())
    val failed = agent.runTurn("second", first)

    failed.left.map(_.getClass.getSimpleName) shouldBe Left("NodeFailed")
    val third = ran(agent, "third", first)
    third.threadId should not be first.threadId
    third.messages.collect { case u: UserMessage => u.content } shouldBe Vector("first", "third")
    // the abandoned thread is forgotten: a history import, allowed only into a new thread, succeeds
    agent.agent.flatMap(
      _.run(first.threadId.get, "probe", RunConfig(), Seq(UserMessage("a"), AssistantMessage("b")))
    ) shouldBe a[Right[?, ?]]
  }

  it should "return Left when the model call fails" in {
    val result = assistantAgent(mockClient(failure("network error"))).runTurn("query", emptySessionState())

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[GraphError.NodeFailed]
  }

  // --- extractFinalResponse ---

  "AssistantAgent.extractFinalResponse" should "return the answer of a completed turn" in {
    val state = emptySessionState().withResult(AgentResultFixture(AgentStatus.Completed("the answer")))
    assistantAgent().extractFinalResponse(state) shouldBe Right("the answer")
  }

  it should "describe a guardrail block as the response" in {
    val state = emptySessionState().withResult(AgentResultFixture(AgentStatus.Blocked("NoSecrets", "leaks")))
    assistantAgent().extractFinalResponse(state) shouldBe Right("Blocked by guardrail 'NoSecrets': leaks")
  }

  it should "return Left when there is no turn yet" in {
    assistantAgent().extractFinalResponse(emptySessionState()).isLeft shouldBe true
  }

  it should "return Left when the turn reached its step limit or waits for an approval" in {
    val agent = assistantAgent()
    agent
      .extractFinalResponse(emptySessionState().withResult(AgentResultFixture(AgentStatus.StepLimitReached)))
      .isLeft shouldBe true
    agent
      .extractFinalResponse(
        emptySessionState().withResult(AgentResultFixture(AgentStatus.Suspended(Vector.empty, Vector.empty)))
      )
      .isLeft shouldBe true
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
    val agent  = assistantAgent(answering("42"))
    val state  = emptySessionState()
    val result = agent.processInput("what is 6x7?", state)

    result match {
      case Right((_, response)) => response should include("42")
      case Left(err)            => fail(s"Expected Right but got: ${err.message}")
    }
  }

  it should "keep a turn that ends without an answer in the session, its reason as the response" in {
    val looping = new LLMClient {
      private val call = ToolCall("c", "missing_tool", ujson.Obj())
      override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Right(Completion("loop", 0L, "", "test-model", AssistantMessage(None, Seq(call)), List(call)))
      override def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 512
    }

    val result = assistantAgent(looping).processInput("loop forever", emptySessionState())

    result.isRight shouldBe true
    val (state, response) = result.toOption.get
    state.last.map(_.status) shouldBe Some(AgentStatus.StepLimitReached)
    state.messages.head shouldBe UserMessage("loop forever")
    response should include("step limit")
  }

  it should "report a failed turn as an AssistantError" in {
    val result = assistantAgent(mockClient(failure("network error"))).processInput("hello", emptySessionState())

    result.isLeft shouldBe true
    result.left.toOption.get.message should include("Agent execution failed")
  }
}
