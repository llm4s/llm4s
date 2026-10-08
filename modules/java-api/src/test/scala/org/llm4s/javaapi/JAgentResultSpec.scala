package org.llm4s.javaapi

import org.llm4s.agent.AgentStatus
import org.llm4s.agent.graph.InterruptId
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.graph.toolloop.{ ApprovalRequest, ApprovalSource, ToolQuestionRequest }
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  ModelUsage,
  SystemMessage,
  TokenUsage,
  ToolCall,
  ToolMessage,
  UsageSummary,
  UserMessage
}
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Optional
import scala.jdk.CollectionConverters.*

import StreamFixtures.*

/**
 * [[JAgentResult]] and what it reaches - [[JAgentStatus]], [[JMessage]], [[JToolCall]],
 * [[JUsageSummary]], [[JModelUsage]] - built from the agent's Scala values: every case, and value
 * semantics.
 */
class JAgentResultSpec extends AnyFlatSpec with Matchers {

  private def pending(kind: AgentStatusKind, status: JAgentStatus) = {
    status.kind shouldBe kind
    status.pending.asScala shouldBe empty
  }

  "JAgentStatus" should "carry a completed turn's answer, and nothing else" in {
    val s = JAgentStatus.of(AgentStatus.Completed("42"))
    pending(AgentStatusKind.COMPLETED, s)
    s.answer shouldBe Optional.of("42")
    s.guardrail shouldBe Optional.empty
    s.reason shouldBe Optional.empty
    s.toString shouldBe "COMPLETED(42)"
  }

  it should "carry a blocked turn's guardrail and reason, and nothing else" in {
    val s = JAgentStatus.of(AgentStatus.Blocked("NoSecrets", "leaks a key"))
    pending(AgentStatusKind.BLOCKED, s)
    s.answer shouldBe Optional.empty
    s.guardrail shouldBe Optional.of("NoSecrets")
    s.reason shouldBe Optional.of("leaks a key")
    s.toString shouldBe "BLOCKED(NoSecrets: leaks a key)"
  }

  it should "carry nothing for a turn that reached its step limit" in {
    val s = JAgentStatus.of(AgentStatus.StepLimitReached)
    pending(AgentStatusKind.STEP_LIMIT_REACHED, s)
    s.answer shouldBe Optional.empty
    s.guardrail shouldBe Optional.empty
    s.reason shouldBe Optional.empty
    s.toString shouldBe "STEP_LIMIT_REACHED"
  }

  it should "list a suspended turn's interrupts as PendingInterrupt.of does, approvals first" in {
    val c = SuspensionFixtures.call("c1", "deploy", "x")
    val suspended = AgentStatus.Suspended(
      Vector(InterruptId("a1") -> ApprovalRequest("m1", c, "why", ApprovalSource.Tool)),
      Vector(InterruptId("q1") -> ToolQuestionRequest("m1", c, ujson.Obj("q" -> 1)))
    )
    val s = JAgentStatus.of(suspended)
    s.kind shouldBe AgentStatusKind.SUSPENDED
    s.pending shouldBe PendingInterrupt.of(suspended)
    s.pending.asScala.map(_.id) shouldBe Seq("a1", "q1")
    s.answer shouldBe Optional.empty
    s.guardrail shouldBe Optional.empty
    s.reason shouldBe Optional.empty
    s.toString shouldBe "SUSPENDED(2 pending)"
    an[UnsupportedOperationException] should be thrownBy s.pending.clear()
  }

  "PendingInterrupt.of" should "list nothing for a status that is not suspended" in {
    PendingInterrupt.of(AgentStatus.Completed("a")).asScala shouldBe empty
    PendingInterrupt.of(AgentStatus.StepLimitReached).asScala shouldBe empty
  }

  "JAgentStatus" should "be a value" in {
    val s = JAgentStatus.of(AgentStatus.Completed("a"))
    s shouldBe JAgentStatus.of(AgentStatus.Completed("a"))
    s.hashCode shouldBe JAgentStatus.of(AgentStatus.Completed("a")).hashCode
    s should not be JAgentStatus.of(AgentStatus.Completed("b"))
    s should not be "COMPLETED(a)"
  }

  "AgentStatusKind" should "be a Java enum with the four cases, in order" in {
    classOf[AgentStatusKind].isEnum shouldBe true
    AgentStatusKind.values().toList.map(_.name) shouldBe List("COMPLETED", "BLOCKED", "STEP_LIMIT_REACHED", "SUSPENDED")
  }

  "JMessage" should "read each role of the history" in {
    val user = JMessage.of(UserMessage("hi"))
    user.role shouldBe JMessageRole.USER
    user.content shouldBe "hi"
    user.toolCalls.asScala shouldBe empty
    user.toolCallId shouldBe Optional.empty
    user.thinking shouldBe Optional.empty
    user.toString shouldBe "USER: hi"

    val system = JMessage.of(SystemMessage("be brief"))
    system.role shouldBe JMessageRole.SYSTEM
    system.content shouldBe "be brief"

    val tool = JMessage.of(ToolMessage("42", "call-1"))
    tool.role shouldBe JMessageRole.TOOL
    tool.content shouldBe "42"
    tool.toolCallId shouldBe Optional.of("call-1")
    tool.toolCalls.asScala shouldBe empty
    tool.thinking shouldBe Optional.empty
  }

  it should "read an assistant message's tool calls, as JSON text, and its reasoning" in {
    val calls = Seq(ToolCall("c1", "add", ujson.Obj("a" -> 1, "b" -> 2)), ToolCall("c2", "now", ujson.Obj()))
    val m     = JMessage.of(AssistantMessage(None, calls).withThinking("adding first"))
    m.role shouldBe JMessageRole.ASSISTANT
    m.content shouldBe ""
    m.toolCallId shouldBe Optional.empty
    m.thinking shouldBe Optional.of("adding first")
    val List(add, now) = m.toolCalls.asScala.toList: @unchecked
    (add.id, add.name, add.argumentsJson) shouldBe (("c1", "add", """{"a":1,"b":2}"""))
    (now.id, now.name, now.argumentsJson) shouldBe (("c2", "now", "{}"))
    add.toString shouldBe """JToolCall(c1, add, {"a":1,"b":2})"""
    an[UnsupportedOperationException] should be thrownBy m.toolCalls.clear()

    val plain = JMessage.of(AssistantMessage(Some("done")))
    plain.content shouldBe "done"
    plain.thinking shouldBe Optional.empty
    plain.toolCalls.asScala shouldBe empty
  }

  it should "be a value, as its tool calls are" in {
    val m = JMessage.of(AssistantMessage(None, Seq(ToolCall("c1", "add", ujson.Obj("a" -> 1)))))
    m shouldBe JMessage.of(AssistantMessage(None, Seq(ToolCall("c1", "add", ujson.Obj("a" -> 1)))))
    m.hashCode shouldBe JMessage.of(AssistantMessage(None, Seq(ToolCall("c1", "add", ujson.Obj("a" -> 1))))).hashCode
    m should not be JMessage.of(AssistantMessage(None, Seq(ToolCall("c1", "add", ujson.Obj("a" -> 2)))))
    m should not be "ASSISTANT: "

    val call = m.toolCalls.get(0)
    call shouldBe JToolCall.of(ToolCall("c1", "add", ujson.Obj("a" -> 1)))
    call.hashCode shouldBe JToolCall.of(ToolCall("c1", "add", ujson.Obj("a" -> 1))).hashCode
    call should not be JToolCall.of(ToolCall("c2", "add", ujson.Obj("a" -> 1)))
    call should not be "c1"
  }

  "JMessageRole" should "be a Java enum with the four roles" in {
    classOf[JMessageRole].isEnum shouldBe true
    JMessageRole.values().toList.map(_.name) shouldBe List("SYSTEM", "USER", "ASSISTANT", "TOOL")
  }

  private def usage = UsageSummary()
    .add("gpt-b", TokenUsage(10, 5, 15, Some(2)), Some(0.25))
    .add("gpt-a", TokenUsage(3, 1, 4), None)
    .add("gpt-b", TokenUsage(1, 1, 2), Some(0.5))

  "JUsageSummary" should "report the totals, and each model's share sorted by model name, in JDK types" in {
    val u = JUsageSummary.of(usage)
    (u.requestCount, u.inputTokens, u.outputTokens, u.thinkingTokens) shouldBe ((3L, 14L, 7L, 2L))
    u.totalCost shouldBe new java.math.BigDecimal("0.75")
    u.byModel.keySet.asScala.toList shouldBe List("gpt-a", "gpt-b")
    val b = u.byModel.get("gpt-b")
    (b.requestCount, b.inputTokens, b.outputTokens, b.thinkingTokens) shouldBe ((2L, 11L, 6L, 2L))
    b.totalCost shouldBe new java.math.BigDecimal("0.75")
    u.byModel.get("gpt-a").totalCost.signum shouldBe 0
    an[UnsupportedOperationException] should be thrownBy u.byModel.clear()
    (u.byModel.getClass.getName should not).startWith("scala.")
    u.toString shouldBe "JUsageSummary(3 requests, 14 in, 7 out, 2 thinking, 0.75 USD)"
    b.toString shouldBe "JModelUsage(2 requests, 11 in, 6 out, 2 thinking, 0.75 USD)"
  }

  it should "be a value, as each model's share is" in {
    val u = JUsageSummary.of(usage)
    u shouldBe JUsageSummary.of(usage)
    u.hashCode shouldBe JUsageSummary.of(usage).hashCode
    u should not be JUsageSummary.of(UsageSummary())
    u should not be "usage"

    val m = JModelUsage.of(ModelUsage(1L, 2L, 3L, 0L, BigDecimal("0.1")))
    m shouldBe JModelUsage.of(ModelUsage(1L, 2L, 3L, 0L, BigDecimal("0.1")))
    m.hashCode shouldBe JModelUsage.of(ModelUsage(1L, 2L, 3L, 0L, BigDecimal("0.1"))).hashCode
    m should not be JModelUsage.of(ModelUsage(1L, 2L, 4L, 0L, BigDecimal("0.1")))
    m should not be "usage"
  }

  private def costing(text: String): Completion =
    Completion(
      "id",
      0L,
      text,
      "m",
      AssistantMessage(Some(text)),
      usage = Some(TokenUsage(7, 3, 10)),
      estimatedCost = Some(0.01)
    )

  "JAgentResult" should "read a completed turn: ids, status, answer, history and usage" in {
    val scala = agentOf(new Scripted(_ => Right(costing("4")), () => Right(costing("4"))))().run("2+2?") match {
      case Right(r) => r
      case Left(e)  => fail(e.message)
    }
    val r = JAgentResult.of(scala)
    r.threadId shouldBe scala.threadId.value
    r.runId shouldBe scala.runId.value
    r.activeAgent shouldBe "test"
    r.status.kind shouldBe AgentStatusKind.COMPLETED
    r.answer() shouldBe Optional.of("4")
    r.messages.asScala.map(m => m.role -> m.content) shouldBe Seq(
      JMessageRole.USER      -> "2+2?",
      JMessageRole.ASSISTANT -> "4"
    )
    an[UnsupportedOperationException] should be thrownBy r.messages.clear()
    (r.messages.getClass.getName should not).startWith("scala.")
    r.usage.requestCount shouldBe 1L
    r.usage.byModel.keySet.asScala shouldBe Set("m")
    r.usage.totalCost shouldBe new java.math.BigDecimal("0.01")

    r shouldBe JAgentResult.of(scala)
    r.hashCode shouldBe JAgentResult.of(scala).hashCode
    r should not be "a result"
    r.toString shouldBe s"JAgentResult(${r.threadId} ${r.runId}: COMPLETED(4), 2 messages)"
  }

  it should "differ for another turn" in {
    val agent = jAgentOf(answering("ok"))()
    val first = agent.run("one").get()
    first should not be agent.continueConversation(first, "two").get()
  }

  it should "read a turn a guardrail blocked" in {
    val agent = jAgentOf(answering("never"))(_.withMiddleware(new GuardrailMiddleware(Seq(new LengthCheck(1, 3)), Nil)))
    val r     = agent.run("far too long a query").get()
    r.status.kind shouldBe AgentStatusKind.BLOCKED
    r.status.guardrail.isPresent shouldBe true
    r.status.reason.isPresent shouldBe true
    r.answer() shouldBe Optional.empty
  }

  it should "read a turn that reached its step limit" in {
    val looping = new Scripted(_ => Right(toolCall), () => Right(toolCall))
    val r       = jAgentOf(looping)(_.withTools(echoTools).withMaxSteps(1)).run("loop").get()
    r.status.kind shouldBe AgentStatusKind.STEP_LIMIT_REACHED
    r.answer() shouldBe Optional.empty
  }

  "a Java caller" should "read a whole result with JDK types only: status, history, tool calls and usage" in {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    def reply(): Either[Nothing, Completion] =
      if (calls.getAndIncrement() == 0) Right(toolCall) else Right(costing("echoed"))
    val agent = jAgentOf(new Scripted(_ => reply(), () => reply()))(_.withTools(echoTools))
    JavaInteropCheck.reading(agent.run("echo hi").get()).asScala.toList shouldBe List(
      "completed:echoed",
      "answer:echoed",
      "thread:false:false:test",
      "user:echo hi",
      """assistant::call-1=echo{"input":"hi"}:-""",
      """tool:call-1:{"echoed":"echoed: hi"}""",
      "assistant:echoed::-",
      "usage:2:10:1",
      "model:m:2:10:1"
    )
  }

  it should "read a blocked turn's guardrail and reason, and a step limit, through the same switch" in {
    val blocked =
      jAgentOf(answering("x"))(_.withMiddleware(new GuardrailMiddleware(Seq(new LengthCheck(1, 3)), Nil)))
        .run("toolong")
    JavaInteropCheck.reading(blocked.get()).asScala.head should startWith("blocked:")
    val looping = new Scripted(_ => Right(toolCall), () => Right(toolCall))
    val limited = jAgentOf(looping)(_.withTools(echoTools).withMaxSteps(1)).run("loop")
    JavaInteropCheck.reading(limited.get()).asScala.head shouldBe "step-limit"
    val suspended = SuspensionFixtures
      .agentOver(
        SuspensionFixtures.scripted(Right(SuspensionFixtures.calling(SuspensionFixtures.call("c1", "deploy", "x"))))
      )
      .run("deploy")
    JavaInteropCheck.reading(suspended.get()).asScala.head shouldBe "suspended:1"
  }

  private def toolCall: Completion = {
    val call = ToolCall("call-1", "echo", ujson.Obj("input" -> "hi"))
    Completion("id", 0L, "", "m", AssistantMessage(None, Seq(call)), List(call))
  }

  private def echoTools: ToolRegistry =
    ToolBuilder[Map[String, Any], JAgentResultSpec.Echo](
      "echo",
      "Echoes the input",
      Schema.`object`[Map[String, Any]]("Echo").withProperty(Schema.property("input", Schema.string("Input")))
    ).withHandler(ext => ext.getString("input").map(s => JAgentResultSpec.Echo(s"echoed: $s")))
      .buildSafe()
      .fold(e => fail(e.message), t => new ToolRegistry(Seq(t)))
}

object JAgentResultSpec {
  final case class Echo(echoed: String)
  given upickle.default.ReadWriter[Echo] = upickle.default.macroRW
}
