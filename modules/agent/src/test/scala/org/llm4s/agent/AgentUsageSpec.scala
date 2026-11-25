package org.llm4s.agent

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.call
import org.llm4s.agent.graph.{ RunConfig, ThreadId }
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.agent.guardrails.builtin.JSONValidator
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolFunction, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

/**
 * Usage accumulated on the thread: over a turn's model calls, across turns, and across the agents
 * of a family. Ports the former `AgentUsageIntegrationSpec` and `AgentUsageTransitionsSpec`.
 */
class AgentUsageSpec extends AnyFlatSpec with Matchers {

  private def completion(
    model: String,
    message: AssistantMessage,
    prompt: Int,
    output: Int,
    cost: Double
  ): Completion =
    Completion(
      id = s"$model-${message.content}",
      created = Instant.ofEpochSecond(1L),
      model = ModelName(model),
      message = message,
      usage = Some(TokenUsage(promptTokens = prompt, completionTokens = output, totalTokens = prompt + output)),
      estimatedCost = Some(cost)
    )

  private def handoffTo(target: String): AssistantMessage =
    AssistantMessage(
      None,
      Seq(ToolCall(ToolCallId(s"tc-$target"), s"handoff_to_$target", ujson.Obj("reason" -> "delegate")))
    )

  "Agent" should "accumulate usage and cost across the model calls of a turn" in {
    val model = "fake/model"
    val tool = ToolFunction[String](
      name = "echo",
      description = "Echo tool",
      schema = Schema.`object`[ujson.Value]("Empty args"),
      handler = _ => Right("ok")
    )
    val client = ScriptedLLMClient.of(
      completion(model, AssistantMessage(None, Seq(ToolCall(ToolCallId("tc_1"), "echo", ujson.Obj()))), 10, 2, 0.01),
      completion(model, AssistantMessage("done"), 4, 3, 0.02)
    )

    val usage =
      built(Agent.builder("assistant", client).withTools(ToolRegistry.of(Seq(tool)).toOption.get)).run("hi").value.usage

    usage.requestCount shouldBe 2L
    usage.inputTokens shouldBe 14L
    usage.outputTokens shouldBe 5L
    usage.totalCost shouldBe BigDecimal("0.03")
    usage.byModel.keySet shouldBe Set(ModelName(model))
    val perModel = usage.byModel(ModelName(model))
    perModel.requestCount shouldBe 2L
    perModel.inputTokens shouldBe 14L
    perModel.outputTokens shouldBe 5L
    perModel.totalCost shouldBe BigDecimal("0.03")
  }

  it should "carry usage forward across the turns of a thread" in {
    val model = "fake/model"
    val client = ScriptedLLMClient.of(
      completion(model, AssistantMessage("first"), 10, 2, 0.01),
      completion(model, AssistantMessage("second"), 4, 3, 0.02)
    )
    val agent = plain(client)

    val usage = agent.continueConversation(agent.run("hi").value, "follow up").value.usage

    usage.requestCount shouldBe 2L
    usage.inputTokens shouldBe 14L
    usage.outputTokens shouldBe 5L
    usage.totalCost shouldBe BigDecimal("0.03")
    usage.byModel(ModelName(model)).requestCount shouldBe 2L
  }

  it should "count a single completion once" in {
    val client = ScriptedLLMClient.of(completion("test-model", AssistantMessage("hi"), 10, 5, 0.01))

    val usage = plain(client).run("hello").value.usage

    usage.requestCount shouldBe 1L
    usage.totalCost shouldBe BigDecimal("0.01")
  }

  "A handoff" should "add the target's model calls to the thread's usage, per model" in {
    val child  = ScriptedLLMClient.of(completion("fake/child", AssistantMessage("child done"), 3, 4, 0.02))
    val parent = ScriptedLLMClient.of(completion("fake/parent", handoffTo("child"), 10, 1, 0.01))
    val agent = built(
      Agent.builder("parent", parent).withHandoffs(Handoff.to("child", Agent.builder("child", child), "delegate"))
    )

    val result = agent.run("hi").value

    result.answer shouldBe Some("child done")
    result.usage.requestCount shouldBe 2L
    result.usage.totalCost shouldBe BigDecimal("0.03")
    result.usage.byModel.keySet shouldBe Set(ModelName("fake/parent"), ModelName("fake/child"))
    result.usage.byModel(ModelName("fake/parent")).requestCount shouldBe 1L
    result.usage.byModel(ModelName("fake/child")).requestCount shouldBe 1L
  }

  it should "accumulate usage along a chain of handoffs" in {
    val b = Agent.builder(
      "agent-b",
      ScriptedLLMClient.of(completion("fake/b", AssistantMessage("b done"), 1, 1, 0.03))
    )
    val a = Agent
      .builder("agent-a", ScriptedLLMClient.of(completion("fake/a", handoffTo("agent-b"), 2, 2, 0.02)))
      .withHandoffs(Handoff.to("agent-b", b))
    val root = Agent
      .builder("parent", ScriptedLLMClient.of(completion("fake/parent", handoffTo("agent-a"), 10, 0, 0.01)))
      .withHandoffs(Handoff.to("agent-a", a))

    val result = built(root).run("hi").value

    result.activeAgent.value shouldBe "agent-b"
    result.usage.requestCount shouldBe 3L
    result.usage.totalCost shouldBe BigDecimal("0.06")
    result.usage.byModel.keySet shouldBe Set(ModelName("fake/parent"), ModelName("fake/a"), ModelName("fake/b"))
    result.usage.byModel.values.map(_.requestCount).toSet shouldBe Set(1L)
  }

  // --- AgentResult.runUsage: what the run that just finished used, beside the thread's total (#2922) ---

  private def model = "fake/model"

  private def toolCall(id: String, tool: String, prompt: Int, output: Int, cost: Double): Completion =
    completion(model, AssistantMessage(None, Seq(call(id, tool))), prompt, output, cost)

  private def ok(name: String) =
    SpecTools.tool(name)((_, _) => ToolOutcome.success(ToolContent.Json(ujson.Str("ok"))))

  "AgentResult.runUsage" should "be the thread's usage for the first run of a thread, over every model call of the run" in {
    val client = ScriptedLLMClient.of(
      toolCall("c1", "echo", 10, 2, 0.01),
      completion(model, AssistantMessage("done"), 4, 3, 0.02)
    )
    val result = built(Agent.builder("assistant", client).withToolSet(SpecTools.set(ok("echo")))).run("hi").value

    result.runUsage.requestCount shouldBe 2L
    result.runUsage.inputTokens shouldBe 14L
    result.runUsage.totalCost shouldBe BigDecimal("0.03")
    result.runUsage shouldBe result.usage
  }

  it should "be the second turn's alone when a conversation continues, per model, while usage is the thread's total" in {
    val client = ScriptedLLMClient.of(
      completion("fake/first", AssistantMessage("first"), 10, 2, 0.01),
      completion("fake/second", AssistantMessage("second"), 4, 3, 0.02)
    )
    val agent = plain(client)

    val first  = agent.run("hi").value
    val second = agent.continueConversation(first, "follow up").value

    first.runUsage shouldBe first.usage
    second.usage.requestCount shouldBe 2L
    second.usage.inputTokens shouldBe 14L
    second.runUsage.requestCount shouldBe 1L
    second.runUsage.inputTokens shouldBe 4L
    second.runUsage.outputTokens shouldBe 3L
    second.runUsage.totalCost shouldBe BigDecimal("0.02")
    // the model only the first turn called is not in the second run's breakdown
    second.runUsage.byModel.keySet shouldBe Set(ModelName("fake/second"))
    second.runUsage.byModel(ModelName("fake/second")).requestCount shouldBe 1L
    first.runUsage.merge(second.runUsage) shouldBe second.usage
  }

  it should "be the run's own on a thread seeded with history" in {
    val client  = ScriptedLLMClient.of(completion(model, AssistantMessage("I remember"), 30, 3, 0.05))
    val history = Seq(UserMessage("my name is Ada"), AssistantMessage("Hello Ada"))

    val result = plain(client).run(ThreadId("usage-seeded"), "what is my name?", RunConfig(), history).value

    result.messages should have size 4
    result.runUsage.requestCount shouldBe 1L
    result.runUsage.inputTokens shouldBe 30L
    result.runUsage shouldBe result.usage
  }

  it should "be the resumed run's alone after a suspension" in {
    val client = ScriptedLLMClient.of(
      toolCall("c1", "deploy", 10, 2, 0.01),
      completion(model, AssistantMessage("shipped"), 20, 5, 0.04)
    )
    val agent = built(
      Agent
        .builder("assistant", client)
        .withToolSet(SpecTools.set(ok("deploy")))
        .withMiddleware(new ApprovalMiddleware(request => Some(s"review ${request.call.id}")))
    )

    val parked = agent.run("deploy").value
    val id = parked.status match {
      case AgentStatus.Suspended(suspension) => suspension.approvals.head._1
      case other                             => fail(s"expected Suspended, got $other")
    }
    // the suspended run made the first model call
    parked.runUsage.requestCount shouldBe 1L
    parked.runUsage.inputTokens shouldBe 10L

    val done = agent.resume(parked.threadId, Map(parked.approve(id))).value

    done.answer shouldBe Some("shipped")
    done.usage.requestCount shouldBe 2L
    done.usage.inputTokens shouldBe 30L
    done.runUsage.requestCount shouldBe 1L
    done.runUsage.inputTokens shouldBe 20L
    done.runUsage.outputTokens shouldBe 5L
    done.runUsage.totalCost shouldBe BigDecimal("0.04")
  }

  it should "be the recovering run's alone after a failed run, which keeps what it committed in the thread's total" in {
    val broken = new java.util.concurrent.atomic.AtomicBoolean(true)
    val flaky = SpecTools.tool("flaky") { (_, _) =>
      if (broken.get) ToolOutcome.Fatal(ValidationError("flaky", "backend down"))
      else ToolOutcome.success(ToolContent.Json(ujson.Str("fixed")))
    }
    val client = ScriptedLLMClient.of(
      toolCall("c1", "flaky", 10, 2, 0.01),
      completion(model, AssistantMessage("all done"), 20, 5, 0.04)
    )
    val agent  = built(Agent.builder("assistant", client).withToolSet(SpecTools.set(flaky)))
    val thread = ThreadId("usage-recovered")

    agent.run(thread, "go").isLeft shouldBe true
    broken.set(false)
    val recovered = agent.recover(thread).value

    recovered.answer shouldBe Some("all done")
    // the failed run's model call committed before its tool failed
    recovered.usage.requestCount shouldBe 2L
    recovered.usage.inputTokens shouldBe 30L
    recovered.runUsage.requestCount shouldBe 1L
    recovered.runUsage.inputTokens shouldBe 20L
    recovered.runUsage.totalCost shouldBe BigDecimal("0.04")
  }

  it should "be the streamed run's own, on a thread that already has usage" in {
    val client = ScriptedLLMClient.of(
      completion(model, AssistantMessage("first"), 10, 2, 0.01),
      completion(model, AssistantMessage("second"), 4, 3, 0.02)
    )
    val agent = built(Agent.builder("assistant", client).withStreaming())

    val first    = agent.run("hi").value
    val streamed = agent.stream(first.threadId, "again")(_ => ()).flatMap(_.await()).value

    streamed.answer shouldBe Some("second")
    streamed.usage.requestCount shouldBe 2L
    streamed.runUsage.requestCount shouldBe 1L
    streamed.runUsage.inputTokens shouldBe 4L
    streamed.runUsage.outputTokens shouldBe 3L
  }

  it should "keep the model calls of a turn an output guardrail blocked" in {
    val client = ScriptedLLMClient.of(
      completion(model, AssistantMessage("{}"), 10, 2, 0.01),
      completion(model, AssistantMessage("not json"), 4, 3, 0.02)
    )
    val agent = built(Agent.builder("assistant", client).withOutputGuardrails(JSONValidator()))

    val first   = agent.run("hi").value
    val blocked = agent.continueConversation(first, "again").value

    blocked.status shouldBe a[AgentStatus.Blocked]
    blocked.usage.requestCount shouldBe 2L
    blocked.runUsage.requestCount shouldBe 1L
    blocked.runUsage.inputTokens shouldBe 4L
  }

  it should "add the model calls of a handoff target the run reached" in {
    val child  = ScriptedLLMClient.of(completion("fake/child", AssistantMessage("child done"), 3, 4, 0.02))
    val parent = ScriptedLLMClient.of(completion("fake/parent", handoffTo("child"), 10, 1, 0.01))
    val agent = built(
      Agent.builder("parent", parent).withHandoffs(Handoff.to("child", Agent.builder("child", child), "delegate"))
    )

    val result = agent.run("hi").value

    result.runUsage.byModel.keySet shouldBe Set(ModelName("fake/parent"), ModelName("fake/child"))
    result.runUsage shouldBe result.usage
  }

  "AgentRun.usageSince" should "subtract every total and each model's, leaving out a model with nothing new" in {
    val before = UsageSummary()
      .add(ModelName("a"), TokenUsage(10, 2, 12).withThinkingTokens(1).withCachedTokens(4), Some(BigDecimal("0.01")))
      .add(ModelName("b"), TokenUsage(1, 1, 2), None)
    val own = UsageSummary()
      .add(ModelName("a"), TokenUsage(5, 3, 8).withThinkingTokens(2).withCachedTokens(1), Some(BigDecimal("0.02")))
      .add(ModelName("c"), TokenUsage(7, 7, 14), Some(BigDecimal("0.5")))

    AgentRun.usageSince(before, before.merge(own)) shouldBe own
    AgentRun.usageSince(before, before) shouldBe UsageSummary()
    AgentRun.usageSince(UsageSummary(), own) shouldBe own
  }
}
