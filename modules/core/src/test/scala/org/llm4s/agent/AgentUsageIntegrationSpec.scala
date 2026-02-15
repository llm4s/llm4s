package org.llm4s.agent

import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolFunction }
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class AgentUsageIntegrationSpec extends AnyFlatSpec with Matchers {

  "Agent" should "accumulate usage and cost into AgentState.usageSummary across multiple completions" in {
    val model = "fake/model"

    val tool = ToolFunction[ujson.Value, String](
      name = "echo",
      description = "Echo tool",
      schema = Schema.`object`[ujson.Value]("Empty args"),
      handler = _ => Right("ok")
    )

    val tools = new ToolRegistry(Seq(tool))

    val toolCall = ToolCall(
      id = "tc_1",
      name = "echo",
      arguments = ujson.Null
    )

    val completion1 = Completion(
      id = "c1",
      created = 1L,
      content = "",
      model = model,
      message = AssistantMessage(contentOpt = None, toolCalls = Seq(toolCall)),
      toolCalls = List(toolCall),
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 2, totalTokens = 12)),
      estimatedCost = Some(0.01)
    )

    val completion2 = Completion(
      id = "c2",
      created = 2L,
      content = "done",
      model = model,
      message = AssistantMessage("done"),
      toolCalls = List.empty,
      usage = Some(TokenUsage(promptTokens = 4, completionTokens = 3, totalTokens = 7)),
      estimatedCost = Some(0.02)
    )

    val client = new TwoTurnDeterministicFakeLLMClient(first = completion1, second = completion2)
    val agent  = new Agent(client)

    val finalState = agent.run(
      query = "hi",
      tools = tools
    )

    finalState.isRight shouldBe true

    val state = finalState.toOption.get

    state.usageSummary.requestCount shouldBe 2L
    state.usageSummary.inputTokens shouldBe 14L
    state.usageSummary.outputTokens shouldBe 5L
    state.usageSummary.totalCost shouldBe BigDecimal("0.03")

    state.usageSummary.byModel.keySet shouldBe Set(model)

    val perModel = state.usageSummary.byModel(model)
    perModel.requestCount shouldBe 2L
    perModel.inputTokens shouldBe 14L
    perModel.outputTokens shouldBe 5L
    perModel.totalCost shouldBe BigDecimal("0.03")
  }
}
