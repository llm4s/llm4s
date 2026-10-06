package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolFunction, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
      created = 1L,
      content = message.content,
      model = model,
      message = message,
      toolCalls = message.toolCalls.toList,
      usage = Some(TokenUsage(promptTokens = prompt, completionTokens = output, totalTokens = prompt + output)),
      estimatedCost = Some(cost)
    )

  private def handoffTo(target: String): AssistantMessage =
    AssistantMessage(None, Seq(ToolCall(s"tc-$target", s"handoff_to_$target", ujson.Obj("reason" -> "delegate"))))

  "Agent" should "accumulate usage and cost across the model calls of a turn" in {
    val model = "fake/model"
    val tool = ToolFunction[ujson.Value, String](
      name = "echo",
      description = "Echo tool",
      schema = Schema.`object`[ujson.Value]("Empty args"),
      handler = _ => Right("ok")
    )
    val client = ScriptedLLMClient.of(
      completion(model, AssistantMessage(None, Seq(ToolCall("tc_1", "echo", ujson.Obj()))), 10, 2, 0.01),
      completion(model, AssistantMessage("done"), 4, 3, 0.02)
    )

    val usage =
      built(Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(tool)))).run("hi").value.usage

    usage.requestCount shouldBe 2L
    usage.inputTokens shouldBe 14L
    usage.outputTokens shouldBe 5L
    usage.totalCost shouldBe BigDecimal("0.03")
    usage.byModel.keySet shouldBe Set(model)
    val perModel = usage.byModel(model)
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
    usage.byModel(model).requestCount shouldBe 2L
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
    result.usage.byModel.keySet shouldBe Set("fake/parent", "fake/child")
    result.usage.byModel("fake/parent").requestCount shouldBe 1L
    result.usage.byModel("fake/child").requestCount shouldBe 1L
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
    result.usage.byModel.keySet shouldBe Set("fake/parent", "fake/a", "fake/b")
    result.usage.byModel.values.map(_.requestCount).toSet shouldBe Set(1L)
  }
}
