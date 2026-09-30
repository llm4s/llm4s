package org.llm4s.agent

import org.llm4s.llmconnect.model.{ AssistantMessage, Conversation, ToolCall, UserMessage }
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Unit tests for [[HandoffExecutor]].
 *
 * The pure helpers (`detectHandoff`, `buildHandoffState`, `createHandoffTools`)
 * are tested directly without assembling a full Agent or LLM client.
 */
class HandoffExecutorSpec extends AnyFlatSpec with Matchers {

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private def mkAgent(): Agent = new Agent(
    new DeterministicFakeLLMClient(
      org.llm4s.llmconnect.model.Completion(
        id = "test",
        created = 0L,
        content = "done",
        model = "test-model",
        message = AssistantMessage("done", Seq.empty),
        toolCalls = Nil,
        usage = None
      )
    )
  )

  private def handoff(
    agent: Agent,
    reason: Option[String] = Some("specialist"),
    preserveContext: Boolean = true,
    transferSystemMessage: Boolean = false
  ): Handoff =
    Handoff(
      targetAgent = agent,
      transferReason = reason,
      preserveContext = preserveContext,
      transferSystemMessage = transferSystemMessage
    )

  private def handoffToolCall(handoffId: String, reason: String): ToolCall =
    ToolCall(
      id = "tc-1",
      name = handoffId,
      arguments = ujson.Obj("reason" -> ujson.Str(reason))
    )

  private def stateWithHandoffToolCall(h: Handoff, reason: String): AgentState = {
    val tc = handoffToolCall(h.handoffId, reason)
    AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("physics question"),
          AssistantMessage("", Seq(tc))
        )
      ),
      tools = ToolRegistry.empty,
      availableHandoffs = Seq(h)
    )
  }

  private def stateWithAssistantText(text: String, handoffs: Seq[Handoff] = Seq.empty): AgentState =
    AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("q"),
          AssistantMessage(text, Seq.empty)
        )
      ),
      tools = ToolRegistry.empty,
      availableHandoffs = handoffs
    )

  private def stateWithUserMessageOnly(handoffs: Seq[Handoff] = Seq.empty): AgentState =
    AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = ToolRegistry.empty,
      availableHandoffs = handoffs
    )

  // ── detectHandoff ─────────────────────────────────────────────────────────────

  "HandoffExecutor.detectHandoff" should "return None when no assistant message is present" in {
    val state = stateWithUserMessageOnly()
    HandoffExecutor.detectHandoff(state) shouldBe None
  }

  it should "return None when assistant has no tool calls" in {
    val state = stateWithAssistantText("I can answer this myself")
    HandoffExecutor.detectHandoff(state) shouldBe None
  }

  it should "return None when tool calls are present but none match the handoff prefix" in {
    val tc = ToolCall(id = "tc-1", name = "weather_tool", arguments = ujson.Obj("city" -> ujson.Str("London")))
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("q"),
          AssistantMessage("", Seq(tc))
        )
      ),
      tools = ToolRegistry.empty,
      availableHandoffs = Seq.empty
    )
    HandoffExecutor.detectHandoff(state) shouldBe None
  }

  it should "return Some((handoff, reason)) when a matching handoff tool call is found" in {
    val agent = mkAgent()
    val h     = handoff(agent)
    val state = stateWithHandoffToolCall(h, "needs physics expertise")

    val result = HandoffExecutor.detectHandoff(state)
    result shouldBe defined
    val (detectedHandoff, reason) = result.get
    detectedHandoff.handoffId shouldBe h.handoffId
    reason shouldBe "needs physics expertise"
  }

  it should "return None when handoff prefix matches but no matching handoff in availableHandoffs" in {
    val tc =
      ToolCall(id = "tc-1", name = "handoff_to_agent_deadbeef", arguments = ujson.Obj("reason" -> ujson.Str("r")))
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("q"),
          AssistantMessage("", Seq(tc))
        )
      ),
      tools = ToolRegistry.empty,
      availableHandoffs = Seq.empty // no matching handoff
    )
    HandoffExecutor.detectHandoff(state) shouldBe None
  }

  // ── buildHandoffState ─────────────────────────────────────────────────────────

  "HandoffExecutor.buildHandoffState" should "transfer full conversation when preserveContext=true" in {
    val agent = mkAgent()
    val h     = handoff(agent, preserveContext = true)
    val source = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("first q"),
          AssistantMessage("first a", Seq.empty),
          UserMessage("second q")
        )
      ),
      tools = ToolRegistry.empty
    )
    val target = HandoffExecutor.buildHandoffState(source, h, Some("transfer all"))
    target.conversation.messages should have size 3
  }

  it should "transfer only the last user message when preserveContext=false" in {
    val agent = mkAgent()
    val h     = handoff(agent, preserveContext = false)
    val source = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("first q"),
          AssistantMessage("first a", Seq.empty),
          UserMessage("final question")
        )
      ),
      tools = ToolRegistry.empty
    )
    val target = HandoffExecutor.buildHandoffState(source, h, None)
    target.conversation.messages should have size 1
    target.conversation.messages.head.content shouldBe "final question"
  }

  it should "include system message when transferSystemMessage=true" in {
    val agent  = mkAgent()
    val h      = handoff(agent, transferSystemMessage = true)
    val sysMsg = org.llm4s.llmconnect.model.SystemMessage("be a physics expert")
    val source = AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = ToolRegistry.empty,
      systemMessage = Some(sysMsg)
    )
    val target = HandoffExecutor.buildHandoffState(source, h, None)
    target.systemMessage shouldBe defined
    target.systemMessage.get.content shouldBe "be a physics expert"
  }

  it should "clear system message when transferSystemMessage=false" in {
    val agent  = mkAgent()
    val h      = handoff(agent, transferSystemMessage = false)
    val sysMsg = org.llm4s.llmconnect.model.SystemMessage("source system prompt")
    val source = AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = ToolRegistry.empty,
      systemMessage = Some(sysMsg)
    )
    val target = HandoffExecutor.buildHandoffState(source, h, None)
    target.systemMessage shouldBe None
  }

  it should "start with no available handoffs" in {
    val agent = mkAgent()
    val h     = handoff(agent)
    val source = AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = ToolRegistry.empty,
      availableHandoffs = Seq(h)
    )
    val target = HandoffExecutor.buildHandoffState(source, h, None)
    target.availableHandoffs shouldBe empty
  }

  it should "include a handoff log entry" in {
    val agent = mkAgent()
    val h     = handoff(agent)
    val source = AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = ToolRegistry.empty
    )
    val target = HandoffExecutor.buildHandoffState(source, h, Some("physics needed"))
    target.logs should have size 1
    target.logs.head should include("Received handoff")
    target.logs.head should include("physics needed")
  }

  // ── createHandoffTools ────────────────────────────────────────────────────────

  "HandoffExecutor.createHandoffTools" should "return empty seq when no handoffs are provided" in {
    HandoffExecutor.createHandoffTools(Seq.empty) shouldBe Right(Seq.empty)
  }

  it should "create one ToolFunction per handoff" in {
    val agent1 = mkAgent()
    val agent2 = mkAgent()
    val h1     = handoff(agent1, reason = Some("reason A"))
    val h2     = handoff(agent2, reason = Some("reason B"))
    val result = HandoffExecutor.createHandoffTools(Seq(h1, h2))
    result shouldBe a[Right[_, _]]
    result.getOrElse(Seq.empty) should have size 2
  }

  it should "use the handoffId as the tool name" in {
    val agent = mkAgent()
    val h     = handoff(agent)
    HandoffExecutor.createHandoffTools(Seq(h)) match {
      case Right(tools) =>
        tools should have size 1
        tools.head.name shouldBe h.handoffId
      case Left(err) => fail(s"Expected Right but got Left: $err")
    }
  }
}
