package org.llm4s.agent

import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AgentUsageTransitionsSpec extends AnyFlatSpec with Matchers {

  "Agent.continueConversation" should "carry usageSummary forward across turns" in {
    val model = "fake/model"

    val completion1 = Completion(
      id = "c1",
      created = 1L,
      content = "first",
      model = model,
      message = AssistantMessage("first"),
      toolCalls = List.empty,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 2, totalTokens = 12)),
      estimatedCost = Some(0.01)
    )

    val completion2 = Completion(
      id = "c2",
      created = 2L,
      content = "second",
      model = model,
      message = AssistantMessage("second"),
      toolCalls = List.empty,
      usage = Some(TokenUsage(promptTokens = 4, completionTokens = 3, totalTokens = 7)),
      estimatedCost = Some(0.02)
    )

    val client = new TwoTurnDeterministicFakeLLMClient(first = completion1, second = completion2)
    val agent  = new Agent(client)

    val state1 = agent.run(
      query = "hi",
      tools = ToolRegistry.empty
    )

    state1.isRight shouldBe true

    val state2 = agent.continueConversation(
      previousState = state1.toOption.get,
      newUserMessage = "follow up"
    )

    state2.isRight shouldBe true

    val finalState = state2.toOption.get

    finalState.usageSummary.requestCount shouldBe 2L
    finalState.usageSummary.inputTokens shouldBe 14L
    finalState.usageSummary.outputTokens shouldBe 5L
    finalState.usageSummary.totalCost shouldBe BigDecimal("0.03")

    finalState.usageSummary.byModel.keySet shouldBe Set(model)
    finalState.usageSummary.byModel(model).requestCount shouldBe 2L
  }

  "Agent handoff" should "merge sub-agent usageSummary into the parent state" in {
    val parentModel = "fake/parent"
    val childModel  = "fake/child"

    val childCompletion = Completion(
      id = "child_c1",
      created = 1L,
      content = "child done",
      model = childModel,
      message = AssistantMessage("child done"),
      toolCalls = List.empty,
      usage = Some(TokenUsage(promptTokens = 3, completionTokens = 4, totalTokens = 7)),
      estimatedCost = Some(0.02)
    )

    val childAgent = new Agent(new DeterministicFakeLLMClient(childCompletion))
    val handoff    = Handoff.to(childAgent, reason = "delegate")

    val handoffToolCall = ToolCall(
      id = "tc_handoff",
      name = handoff.handoffId,
      arguments = ujson.Obj("reason" -> ujson.Str("delegate"))
    )

    val parentCompletion = Completion(
      id = "parent_c1",
      created = 1L,
      content = "",
      model = parentModel,
      message = AssistantMessage(contentOpt = None, toolCalls = Seq(handoffToolCall)),
      toolCalls = List(handoffToolCall),
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 1, totalTokens = 11)),
      estimatedCost = Some(0.01)
    )

    val parentAgent = new Agent(new DeterministicFakeLLMClient(parentCompletion))

    val result = parentAgent.run(
      query = "hi",
      tools = ToolRegistry.empty,
      handoffs = Seq(handoff)
    )

    result.isRight shouldBe true

    val state = result.toOption.get

    state.usageSummary.requestCount shouldBe 2L
    state.usageSummary.totalCost shouldBe BigDecimal("0.03")
    state.usageSummary.byModel.keySet shouldBe Set(parentModel, childModel)

    state.usageSummary.byModel(parentModel).requestCount shouldBe 1L
    state.usageSummary.byModel(childModel).requestCount shouldBe 1L
  }

  it should "accumulate usageSummary correctly across a nested handoff chain" in {
    val parentModel = "fake/parent"
    val aModel      = "fake/a"
    val bModel      = "fake/b"

    val bCompletion = Completion(
      id = "b_c1",
      created = 1L,
      content = "b done",
      model = bModel,
      message = AssistantMessage("b done"),
      toolCalls = List.empty,
      usage = Some(TokenUsage(promptTokens = 1, completionTokens = 1, totalTokens = 2)),
      estimatedCost = Some(0.03)
    )

    val agentB = new Agent(new DeterministicFakeLLMClient(bCompletion))

    val aHandoffToB = Handoff.to(agentB, reason = "delegate to B")

    val aHandoffToolCall = ToolCall(
      id = "tc_handoff_a",
      name = aHandoffToB.handoffId,
      arguments = ujson.Obj("reason" -> ujson.Str("delegate to B"))
    )

    val aCompletion = Completion(
      id = "a_c1",
      created = 1L,
      content = "",
      model = aModel,
      message = AssistantMessage(contentOpt = None, toolCalls = Seq(aHandoffToolCall)),
      toolCalls = List(aHandoffToolCall),
      usage = Some(TokenUsage(promptTokens = 2, completionTokens = 2, totalTokens = 4)),
      estimatedCost = Some(0.02)
    )

    val agentA = new Agent(new DeterministicFakeLLMClient(aCompletion))

    val parentCompletion = Completion(
      id = "p_c1",
      created = 1L,
      content = "parent done",
      model = parentModel,
      message = AssistantMessage("parent done"),
      toolCalls = List.empty,
      usage = Some(TokenUsage(promptTokens = 10, completionTokens = 0, totalTokens = 10)),
      estimatedCost = Some(0.01)
    )

    val parentAgent = new Agent(new DeterministicFakeLLMClient(parentCompletion))

    val parentResult = parentAgent.run(
      query = "hi",
      tools = ToolRegistry.empty
    )

    val aResult = agentA.run(
      query = "hi",
      tools = ToolRegistry.empty,
      handoffs = Seq(aHandoffToB)
    )

    parentResult.isRight shouldBe true
    aResult.isRight shouldBe true

    val parentState = parentResult.toOption.get
    val aState      = aResult.toOption.get

    val state = parentState.copy(
      usageSummary = parentState.usageSummary.merge(aState.usageSummary)
    )

    state.usageSummary.requestCount shouldBe 3L
    state.usageSummary.totalCost shouldBe BigDecimal("0.06")
    state.usageSummary.byModel.keySet shouldBe Set(parentModel, aModel, bModel)

    state.usageSummary.byModel(parentModel).requestCount shouldBe 1L
    state.usageSummary.byModel(aModel).requestCount shouldBe 1L
    state.usageSummary.byModel(bModel).requestCount shouldBe 1L
  }

  it should "not double count usage for a single completion" in {
    val completion =
      Completion(
        id = "c1",
        created = 1L,
        content = "hi",
        model = "test-model",
        message = AssistantMessage("hi"),
        toolCalls = List.empty,
        usage = Some(
          TokenUsage(
            promptTokens = 10,
            completionTokens = 5,
            totalTokens = 15
          )
        ),
        estimatedCost = Some(0.01)
      )

    val agent = new Agent(
      new DeterministicFakeLLMClient(completion)
    )

    val finalState = agent.run(
      query = "hello",
      tools = ToolRegistry.empty
    )

    finalState.isRight shouldBe true
    finalState.toOption.get.usageSummary.requestCount shouldBe 1L
    finalState.toOption.get.usageSummary.totalCost shouldBe BigDecimal("0.01")
  }

}
