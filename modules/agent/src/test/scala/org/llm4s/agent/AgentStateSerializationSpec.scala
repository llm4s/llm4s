package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import upickle.default._

/**
 * Comprehensive tests for AgentState serialization and deserialization.
 *
 * Tests cover:
 * - Basic AgentState round-trip serialization
 * - CompletionOptions with all fields including reasoning modes
 * - ReasoningEffort serialization
 * - Message types serialization
 * - Edge cases and error handling
 */
class AgentStateSerializationSpec extends AnyFlatSpec with Matchers {

  // Helper to create empty tool registry for tests
  private val emptyTools = new ToolRegistry(Seq.empty)

  // ==========================================================================
  // ReasoningEffort Serialization Tests
  // ==========================================================================

  "ReasoningEffort serialization" should "serialize None to 'none'" in {
    val json = writeJs[ReasoningEffort](ReasoningEffort.None)
    json.str shouldBe "none"
  }

  it should "serialize Low to 'low'" in {
    val json = writeJs[ReasoningEffort](ReasoningEffort.Low)
    json.str shouldBe "low"
  }

  it should "serialize Medium to 'medium'" in {
    val json = writeJs[ReasoningEffort](ReasoningEffort.Medium)
    json.str shouldBe "medium"
  }

  it should "serialize High to 'high'" in {
    val json = writeJs[ReasoningEffort](ReasoningEffort.High)
    json.str shouldBe "high"
  }

  it should "deserialize 'none' to None" in {
    val result = read[ReasoningEffort](ujson.Str("none"))
    result shouldBe ReasoningEffort.None
  }

  it should "deserialize 'low' to Low" in {
    val result = read[ReasoningEffort](ujson.Str("low"))
    result shouldBe ReasoningEffort.Low
  }

  it should "deserialize 'medium' to Medium" in {
    val result = read[ReasoningEffort](ujson.Str("medium"))
    result shouldBe ReasoningEffort.Medium
  }

  it should "deserialize 'high' to High" in {
    val result = read[ReasoningEffort](ujson.Str("high"))
    result shouldBe ReasoningEffort.High
  }

  it should "round-trip all ReasoningEffort values" in {
    ReasoningEffort.values.foreach { effort =>
      val json   = writeJs(effort)
      val result = read[ReasoningEffort](json)
      result shouldBe effort
    }
  }

  // upickle 4.4 routes reads through a tracing visitor, so the reader's
  // IllegalArgumentException surfaces as the cause of the thrown exception.
  it should "throw on invalid string" in {
    val ex = intercept[Exception](read[ReasoningEffort](ujson.Str("invalid")))
    ex.getCause shouldBe an[IllegalArgumentException]
  }

  it should "throw on non-string value" in {
    val ex = intercept[Exception](read[ReasoningEffort](ujson.Num(42)))
    ex.getCause shouldBe an[IllegalArgumentException]
  }

  // ==========================================================================
  // CompletionOptions Serialization Tests
  // ==========================================================================

  "CompletionOptions serialization" should "preserve all basic fields" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Hello"))),
      tools = emptyTools,
      completionOptions = CompletionOptions(
        temperature = 0.5,
        topP = 0.9,
        maxTokens = Some(1000),
        presencePenalty = 0.1,
        frequencyPenalty = 0.2
      )
    )

    val json   = AgentState.toJson(state)
    val loaded = AgentState.fromJson(json, emptyTools)

    loaded.isRight shouldBe true
    val loadedState = loaded.toOption.get
    loadedState.completionOptions.temperature shouldBe 0.5
    loadedState.completionOptions.topP shouldBe 0.9
    loadedState.completionOptions.maxTokens shouldBe Some(1000)
    loadedState.completionOptions.presencePenalty shouldBe 0.1
    loadedState.completionOptions.frequencyPenalty shouldBe 0.2
  }

  it should "preserve reasoning field when set" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Complex question"))),
      tools = emptyTools,
      completionOptions = CompletionOptions().withReasoning(ReasoningEffort.High)
    )

    val json   = AgentState.toJson(state)
    val loaded = AgentState.fromJson(json, emptyTools)

    loaded.isRight shouldBe true
    val loadedState = loaded.toOption.get
    loadedState.completionOptions.reasoning shouldBe Some(ReasoningEffort.High)
  }

  it should "preserve budgetTokens field when set" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Question"))),
      tools = emptyTools,
      completionOptions = CompletionOptions().withBudgetTokens(16000)
    )

    val json   = AgentState.toJson(state)
    val loaded = AgentState.fromJson(json, emptyTools)

    loaded.isRight shouldBe true
    val loadedState = loaded.toOption.get
    loadedState.completionOptions.budgetTokens shouldBe Some(16000)
  }

  it should "preserve both reasoning and budgetTokens together" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Complex question"))),
      tools = emptyTools,
      completionOptions = CompletionOptions()
        .withReasoning(ReasoningEffort.Medium)
        .withBudgetTokens(8000)
    )

    val json   = AgentState.toJson(state)
    val loaded = AgentState.fromJson(json, emptyTools)

    loaded.isRight shouldBe true
    val loadedState = loaded.toOption.get
    loadedState.completionOptions.reasoning shouldBe Some(ReasoningEffort.Medium)
    loadedState.completionOptions.budgetTokens shouldBe Some(8000)
  }

  it should "handle None reasoning as null in JSON" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Simple question"))),
      tools = emptyTools,
      completionOptions = CompletionOptions() // No reasoning set
    )

    val json = AgentState.toJson(state)
    json("completionOptions")("reasoning") shouldBe ujson.Null

    val loaded = AgentState.fromJson(json, emptyTools)
    loaded.isRight shouldBe true
    loaded.toOption.get.completionOptions.reasoning shouldBe None
  }

  it should "handle None budgetTokens as null in JSON" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Question"))),
      tools = emptyTools,
      completionOptions = CompletionOptions() // No budgetTokens set
    )

    val json = AgentState.toJson(state)
    json("completionOptions")("budgetTokens") shouldBe ujson.Null

    val loaded = AgentState.fromJson(json, emptyTools)
    loaded.isRight shouldBe true
    loaded.toOption.get.completionOptions.budgetTokens shouldBe None
  }

  // ==========================================================================
  // AgentState Full Round-Trip Tests
  // ==========================================================================

  "AgentState serialization" should "round-trip basic state" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("Hello"),
          AssistantMessage("Hi there!")
        )
      ),
      tools = emptyTools,
      initialQuery = Some("Hello"),
      status = AgentStatus.Complete,
      logs = Seq("Step 1", "Step 2"),
      systemMessage = Some(SystemMessage("You are helpful"))
    )

    val json   = AgentState.toJson(state)
    val loaded = AgentState.fromJson(json, emptyTools)

    loaded.isRight shouldBe true
    val loadedState = loaded.toOption.get

    loadedState.conversation.messages.length shouldBe 2
    loadedState.initialQuery shouldBe Some("Hello")
    loadedState.status shouldBe AgentStatus.Complete
    loadedState.logs shouldBe Seq("Step 1", "Step 2")
    loadedState.systemMessage.map(_.content) shouldBe Some("You are helpful")
  }

  it should "round-trip state with all CompletionOptions including reasoning" in {
    val state = AgentState(
      conversation = Conversation(Seq(UserMessage("Test"))),
      tools = emptyTools,
      completionOptions = CompletionOptions(
        temperature = 0.3,
        topP = 0.8,
        maxTokens = Some(2000),
        presencePenalty = 0.5,
        frequencyPenalty = 0.6,
        reasoning = Some(ReasoningEffort.High),
        budgetTokens = Some(32000)
      )
    )

    val json   = AgentState.toJson(state)
    val loaded = AgentState.fromJson(json, emptyTools)

    loaded.isRight shouldBe true
    val opts = loaded.toOption.get.completionOptions

    opts.temperature shouldBe 0.3
    opts.topP shouldBe 0.8
    opts.maxTokens shouldBe Some(2000)
    opts.presencePenalty shouldBe 0.5
    opts.frequencyPenalty shouldBe 0.6
    opts.reasoning shouldBe Some(ReasoningEffort.High)
    opts.budgetTokens shouldBe Some(32000)
  }

  it should "preserve conversation with tool messages" in {
    val state = AgentState(
      conversation = Conversation(
        Seq(
          UserMessage("What's the weather?"),
          AssistantMessage(
            contentOpt = None,
            toolCalls = List(ToolCall("call_123", "get_weather", ujson.Obj("city" -> "London")))
          ),
          ToolMessage("call_123", "Sunny, 20C")
        )
      ),
      tools = emptyTools
    )

    val json   = AgentState.toJson(state)
    val loaded = AgentState.fromJson(json, emptyTools)

    loaded.isRight shouldBe true
    val msgs = loaded.toOption.get.conversation.messages

    msgs.length shouldBe 3
    msgs(0) shouldBe a[UserMessage]
    msgs(1) shouldBe a[AssistantMessage]
    msgs(1).asInstanceOf[AssistantMessage].toolCalls.length shouldBe 1
    msgs(2) shouldBe a[ToolMessage]
  }

  // ==========================================================================
  // AgentStatus Serialization Tests
  // ==========================================================================

  "AgentStatus serialization" should "serialize InProgress" in {
    val json = writeJs[AgentStatus](AgentStatus.InProgress)
    json.str shouldBe "InProgress"
  }

  it should "serialize WaitingForTools" in {
    val json = writeJs[AgentStatus](AgentStatus.WaitingForTools)
    json.str shouldBe "WaitingForTools"
  }

  it should "serialize Complete" in {
    val json = writeJs[AgentStatus](AgentStatus.Complete)
    json.str shouldBe "Complete"
  }

  it should "serialize Failed with error message" in {
    val json = writeJs[AgentStatus](AgentStatus.Failed("Something went wrong"))
    json("type").str shouldBe "Failed"
    json("error").str shouldBe "Something went wrong"
  }

  it should "round-trip all basic statuses" in {
    val statuses: Seq[AgentStatus] = Seq(
      AgentStatus.InProgress,
      AgentStatus.WaitingForTools,
      AgentStatus.Complete,
      AgentStatus.Failed("Error message")
    )

    statuses.foreach { status =>
      val json   = writeJs[AgentStatus](status)
      val loaded = read[AgentStatus](json)

      status match {
        case failed: AgentStatus.Failed =>
          loaded shouldBe a[AgentStatus.Failed]
          loaded.asInstanceOf[AgentStatus.Failed].error shouldBe failed.error
        case _ =>
          loaded shouldBe status
      }
    }
  }

  // ==========================================================================
  // Backward Compatibility Tests
  // ==========================================================================

  "AgentState deserialization" should "handle JSON without reasoning field (backward compat)" in {
    // Simulate old JSON format without reasoning fields
    val oldJson = ujson.Obj(
      "conversation"  -> writeJs(Conversation(Seq(UserMessage("Test")))),
      "initialQuery"  -> ujson.Null,
      "status"        -> ujson.Str("Complete"),
      "logs"          -> ujson.Arr(),
      "systemMessage" -> ujson.Null,
      "completionOptions" -> ujson.Obj(
        "temperature"      -> 0.7,
        "topP"             -> 1.0,
        "maxTokens"        -> ujson.Null,
        "presencePenalty"  -> 0.0,
        "frequencyPenalty" -> 0.0
        // Note: no reasoning or budgetTokens fields
      )
    )

    val loaded = AgentState.fromJson(oldJson, emptyTools)

    loaded.isRight shouldBe true
    val opts = loaded.toOption.get.completionOptions
    opts.reasoning shouldBe None
    opts.budgetTokens shouldBe None
  }

  // ==========================================================================
  // Message Serialization Tests
  // ==========================================================================

  "Message serialization" should "round-trip UserMessage" in {
    val msg    = UserMessage("Hello world")
    val json   = writeJs(msg: Message)
    val loaded = read[Message](json)

    loaded shouldBe msg
  }

  it should "round-trip SystemMessage" in {
    val msg    = SystemMessage("You are helpful")
    val json   = writeJs(msg: Message)
    val loaded = read[Message](json)

    loaded shouldBe msg
  }

  it should "round-trip AssistantMessage with content" in {
    val msg    = AssistantMessage("Here's the answer")
    val json   = writeJs(msg: Message)
    val loaded = read[Message](json)

    loaded shouldBe msg
  }

  it should "round-trip AssistantMessage with tool calls" in {
    val msg = AssistantMessage(
      contentOpt = Some("Let me check"),
      toolCalls = List(
        ToolCall("id1", "search", ujson.Obj("query" -> "test")),
        ToolCall("id2", "calculate", ujson.Obj("x" -> 5))
      )
    )
    val json   = writeJs(msg: Message)
    val loaded = read[Message](json)

    loaded shouldBe a[AssistantMessage]
    val loadedMsg = loaded.asInstanceOf[AssistantMessage]
    loadedMsg.contentOpt shouldBe Some("Let me check")
    loadedMsg.toolCalls.length shouldBe 2
  }

  it should "round-trip ToolMessage" in {
    val msg    = ToolMessage("Result data", "call_123") // (content, toolCallId)
    val json   = writeJs(msg: Message)
    val loaded = read[Message](json)

    loaded shouldBe msg
  }

  // ==========================================================================
  // Conversation Serialization Tests
  // ==========================================================================

  "Conversation serialization" should "round-trip empty conversation" in {
    val conv   = Conversation(Seq.empty)
    val json   = writeJs(conv)
    val loaded = read[Conversation](json)

    loaded.messages shouldBe empty
  }

  it should "round-trip multi-message conversation" in {
    val conv = Conversation(
      Seq(
        SystemMessage("System prompt"),
        UserMessage("Question 1"),
        AssistantMessage("Answer 1"),
        UserMessage("Question 2"),
        AssistantMessage("Answer 2")
      )
    )

    val json   = writeJs(conv)
    val loaded = read[Conversation](json)

    loaded.messages.length shouldBe 5
    loaded.messages(0) shouldBe a[SystemMessage]
    loaded.messages(1) shouldBe a[UserMessage]
    loaded.messages(2) shouldBe a[AssistantMessage]
  }
}
