package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model._
import upickle.default._

/**
 * Comprehensive tests for AgentThread serialization and deserialization.
 *
 * Tests cover:
 * - Basic AgentThread round-trip serialization
 * - CompletionOptions with all fields including reasoning modes
 * - ReasoningEffort serialization
 * - ThreadStatus serialization
 * - Reading JSON written by `AgentState` (backward compatibility)
 * - Message types serialization
 * - Edge cases and error handling
 */
class AgentThreadSerializationSpec extends AnyFlatSpec with Matchers {

  private def roundTrip(thread: AgentThread): AgentThread =
    AgentThread.fromJson(AgentThread.toJson(thread)).fold(e => fail(s"fromJson failed: ${e.message}"), identity)

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
    val thread = AgentThread(
      "t",
      messages = Seq(UserMessage("Hello")),
      completionOptions = CompletionOptions(
        temperature = 0.5,
        topP = 0.9,
        maxTokens = Some(1000),
        presencePenalty = 0.1,
        frequencyPenalty = 0.2
      )
    )

    val loaded = roundTrip(thread)
    loaded.completionOptions.temperature shouldBe 0.5
    loaded.completionOptions.topP shouldBe 0.9
    loaded.completionOptions.maxTokens shouldBe Some(1000)
    loaded.completionOptions.presencePenalty shouldBe 0.1
    loaded.completionOptions.frequencyPenalty shouldBe 0.2
  }

  it should "preserve reasoning field when set" in {
    val thread = AgentThread(
      "t",
      messages = Seq(UserMessage("Complex question")),
      completionOptions = CompletionOptions().withReasoning(ReasoningEffort.High)
    )

    roundTrip(thread).completionOptions.reasoning shouldBe Some(ReasoningEffort.High)
  }

  it should "preserve budgetTokens field when set" in {
    val thread = AgentThread(
      "t",
      messages = Seq(UserMessage("Question")),
      completionOptions = CompletionOptions().withBudgetTokens(16000)
    )

    roundTrip(thread).completionOptions.budgetTokens shouldBe Some(16000)
  }

  it should "preserve both reasoning and budgetTokens together" in {
    val thread = AgentThread(
      "t",
      messages = Seq(UserMessage("Complex question")),
      completionOptions = CompletionOptions().withReasoning(ReasoningEffort.Medium).withBudgetTokens(8000)
    )

    val loaded = roundTrip(thread)
    loaded.completionOptions.reasoning shouldBe Some(ReasoningEffort.Medium)
    loaded.completionOptions.budgetTokens shouldBe Some(8000)
  }

  it should "handle None reasoning as null in JSON" in {
    val thread = AgentThread("t", messages = Seq(UserMessage("Simple question")))

    val json = AgentThread.toJson(thread)
    json("completionOptions")("reasoning") shouldBe ujson.Null
    roundTrip(thread).completionOptions.reasoning shouldBe None
  }

  it should "handle None budgetTokens as null in JSON" in {
    val thread = AgentThread("t", messages = Seq(UserMessage("Question")))

    val json = AgentThread.toJson(thread)
    json("completionOptions")("budgetTokens") shouldBe ujson.Null
    roundTrip(thread).completionOptions.budgetTokens shouldBe None
  }

  it should "not serialize the tools of the options" in {
    val json = AgentThread.toJson(AgentThread("t"))
    json("completionOptions").obj.keySet should not contain "tools"
  }

  // ==========================================================================
  // AgentThread Full Round-Trip Tests
  // ==========================================================================

  "AgentThread serialization" should "round-trip a basic thread" in {
    val thread = AgentThread(
      threadId = "thread-1",
      messages = Seq(UserMessage("Hello"), AssistantMessage("Hi there!")),
      systemMessage = Some(SystemMessage("You are helpful")),
      status = ThreadStatus.Completed
    )

    val loaded = roundTrip(thread)

    loaded.threadId shouldBe "thread-1"
    loaded.messages.length shouldBe 2
    loaded.status shouldBe ThreadStatus.Completed
    loaded.systemMessage.map(_.content) shouldBe Some("You are helpful")
  }

  it should "round-trip a thread with all CompletionOptions including reasoning" in {
    val thread = AgentThread(
      "t",
      messages = Seq(UserMessage("Test")),
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

    val opts = roundTrip(thread).completionOptions

    opts.temperature shouldBe 0.3
    opts.topP shouldBe 0.8
    opts.maxTokens shouldBe Some(2000)
    opts.presencePenalty shouldBe 0.5
    opts.frequencyPenalty shouldBe 0.6
    opts.reasoning shouldBe Some(ReasoningEffort.High)
    opts.budgetTokens shouldBe Some(32000)
  }

  it should "preserve a conversation with tool messages" in {
    val thread = AgentThread(
      "t",
      messages = Seq(
        UserMessage("What's the weather?"),
        AssistantMessage(
          contentOpt = None,
          toolCalls = List(ToolCall("call_123", "get_weather", ujson.Obj("city" -> "London")))
        ),
        ToolMessage("call_123", "Sunny, 20C")
      )
    )

    val msgs = roundTrip(thread).messages

    msgs.length shouldBe 3
    msgs(0) shouldBe a[UserMessage]
    msgs(1) shouldBe a[AssistantMessage]
    msgs(1).asInstanceOf[AssistantMessage].toolCalls.length shouldBe 1
    msgs(2) shouldBe a[ToolMessage]
  }

  it should "preserve the usage, with its per-model breakdown" in {
    val usage = UsageSummary()
      .add("model-a", TokenUsage(10, 5, 15), Some(0.01))
      .add("model-b", TokenUsage(20, 10, 30), Some(0.02))

    val loaded = roundTrip(AgentThread("t", usage = usage)).usage

    loaded shouldBe usage
    loaded.byModel.keySet shouldBe Set("model-a", "model-b")
  }

  it should "preserve a thread suspended on an approval, with its question as JSON" in {
    val on     = Vector(SuspendedOn("3.0", "approval", ujson.Obj("reason" -> "irreversible")))
    val thread = AgentThread("t", messages = Seq(UserMessage("deploy")), status = ThreadStatus.Suspended(on))

    roundTrip(thread).status shouldBe ThreadStatus.Suspended(on)
  }

  it should "not serialize anything live: the JSON holds only data keys" in {
    AgentThread.toJson(AgentThread("t")).obj.keySet shouldBe
      Set("threadId", "conversation", "status", "systemMessage", "completionOptions", "usageSummary")
  }

  // ==========================================================================
  // ThreadStatus Serialization Tests
  // ==========================================================================

  "ThreadStatus serialization" should "round-trip every status" in {
    val statuses: Seq[ThreadStatus] = Seq(
      ThreadStatus.Completed,
      ThreadStatus.Failed("Error message"),
      ThreadStatus.Suspended(Vector.empty),
      ThreadStatus.Suspended(
        Vector(SuspendedOn("1.0", "approval", ujson.Null), SuspendedOn("1.1", "ask/x", ujson.Num(1)))
      )
    )

    statuses.foreach(status => read[ThreadStatus](writeJs[ThreadStatus](status)) shouldBe status)
  }

  it should "keep the error message of a Failed status" in {
    write[ThreadStatus](ThreadStatus.Failed("Something went wrong")) should include("Something went wrong")
  }

  "ThreadStatus.Failed" should "support equality based on the error message" in {
    ThreadStatus.Failed("Error A") shouldBe ThreadStatus.Failed("Error A")
    ThreadStatus.Failed("Error A") should not be ThreadStatus.Failed("Error B")
  }

  // ==========================================================================
  // Backward Compatibility Tests
  // ==========================================================================

  "AgentThread deserialization" should "handle JSON without reasoning field (backward compat)" in {
    // Simulate old JSON format without reasoning fields
    val oldJson = ujson.Obj(
      "conversation"  -> writeJs(Conversation(Seq(UserMessage("Test")))),
      "status"        -> writeJs[ThreadStatus](ThreadStatus.Completed),
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

    val loaded = AgentThread.fromJson(oldJson).fold(e => fail(e.message), identity)

    loaded.completionOptions.reasoning shouldBe None
    loaded.completionOptions.budgetTokens shouldBe None
  }

  // What `AgentState.toJson` wrote, before AgentThread existed.
  private def agentStateJson(status: ujson.Value): ujson.Obj = ujson.Obj(
    "conversation"  -> writeJs(Conversation(Seq(UserMessage("Hello"), AssistantMessage("Hi there")))),
    "initialQuery"  -> "Hello",
    "status"        -> status,
    "logs"          -> ujson.Arr("Initialized", "Completed"),
    "systemMessage" -> "You are helpful",
    "completionOptions" -> ujson.Obj(
      "temperature"      -> 0.4,
      "topP"             -> 1.0,
      "maxTokens"        -> ujson.Null,
      "presencePenalty"  -> 0.0,
      "frequencyPenalty" -> 0.0,
      "reasoning"        -> ujson.Null,
      "budgetTokens"     -> ujson.Null
    ),
    "usageSummary" -> writeJs(UsageSummary().add("m", TokenUsage(1, 2, 3), Some(0.5)))
  )

  it should "read a file written by AgentState, keeping what carries over" in {
    val loaded = AgentThread.fromJson(agentStateJson(ujson.Str("Complete"))).fold(e => fail(e.message), identity)

    loaded.status shouldBe ThreadStatus.Completed
    loaded.messages.map(_.content) shouldBe Seq("Hello", "Hi there")
    loaded.systemMessage.map(_.content) shouldBe Some("You are helpful")
    loaded.completionOptions.temperature shouldBe 0.4
    loaded.usage.requestCount shouldBe 1L
    loaded.threadId should not be empty
  }

  it should "read a Failed status written by AgentState" in {
    val failed = ujson.Obj("type" -> "Failed", "error" -> "Maximum step limit reached")

    AgentThread.fromJson(agentStateJson(failed)).map(_.status) shouldBe Right(
      ThreadStatus.Failed("Maximum step limit reached")
    )
  }

  it should "load an AgentState that was still running as Failed, since it has no finished conversation" in {
    Seq("InProgress", "WaitingForTools").foreach { running =>
      AgentThread.fromJson(agentStateJson(ujson.Str(running))).map(_.status) match {
        case Right(ThreadStatus.Failed(message)) => message should include(running)
        case other                               => fail(s"expected a Failed status for $running, got $other")
      }
    }
  }

  it should "report JSON that is not a thread as an error, not throw" in {
    AgentThread.fromJson(ujson.Obj("nothing" -> "here")).isLeft shouldBe true
    AgentThread.fromJson(ujson.Str("not an object")).isLeft shouldBe true
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
