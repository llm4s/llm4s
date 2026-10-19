package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.{ GraphError, RunBudgets, RunConfig, ThreadId }
import org.llm4s.agent.graph.tool.{ ToolContent, ToolOutcome }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicBoolean

/**
 * `RunBudgets.maxTokens` (design 4.43): before each model call the loop counts the run's tokens - the thread's
 * usage since the run was admitted, plus the current task's - and at the budget ends the turn `TokenLimitReached`
 * without calling the model. Every scripted completion here uses 30 tokens (10 in, 20 out).
 */
class AgentTokenBudgetSpec extends AnyFlatSpec with Matchers {

  private val thread = ThreadId("token-budget")

  private def limited(tokens: Long): RunConfig = RunConfig().withBudgets(RunBudgets.default.withMaxTokens(tokens))

  private def tokens(usage: UsageSummary): Long = usage.inputTokens + usage.outputTokens

  private val echo = SpecTools.tool("echo")((a, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str(a.text))))

  "A token budget" should "end the turn TokenLimitReached before the next model call, without calling the model" in {
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("never sent"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    val result = agent.run(thread, "go", limited(30)).value

    result.status shouldBe AgentStatus.TokenLimitReached
    result.answer shouldBe None
    client.callCount shouldBe 1
    tokens(result.usage) shouldBe 30L
    // the batch the last call asked for still ran and is in the history: the turn ends at the next model call
    result.messages.collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1")
  }

  it should "let a run overshoot by the call in flight, which is never interrupted" in {
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("never sent"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    val result = agent.run(thread, "go", limited(1)).value

    result.status shouldBe AgentStatus.TokenLimitReached
    client.callCount shouldBe 1
    tokens(result.usage) shouldBe 30L // 29 over the budget of 1: the one call admitted under it
  }

  it should "complete a turn that answers within the budget" in {
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    agent.run(thread, "go", limited(31)).value.answer shouldBe Some("done")
    client.callCount shouldBe 2
  }

  it should "count only the run's own tokens, not the thread's earlier turns" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("first"), CompletionFixture.simple("second"))
    val agent  = plain(client)

    agent.run(thread, "one").value.answer shouldBe Some("first")
    val second = agent.run(thread, "two", limited(30)).value

    second.answer shouldBe Some("second")
    tokens(second.usage) shouldBe 60L // the thread's usage still accumulates over both turns
  }

  it should "be a completed turn: the next turn, with its own budget, runs normally" in {
    val client = ScriptedLLMClient.of(calling(call("c1", "echo")), CompletionFixture.simple("next turn"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    val stopped = agent.run(thread, "go", limited(30)).value
    stopped.status shouldBe AgentStatus.TokenLimitReached

    agent.continueConversation(stopped, "again").value.answer shouldBe Some("next turn")
  }

  it should "hold in a recovered run, counted from the recover's admission, and the status survives it" in {
    val broken = new AtomicBoolean(true)
    val flaky = SpecTools.tool("flaky") { (_, _) =>
      if (broken.get) ToolOutcome.Fatal(ValidationError("flaky", "backend down"))
      else ToolOutcome.Success(ToolContent.Json(ujson.Str("fixed")))
    }
    val client = ScriptedLLMClient.of(
      calling(call("c1", "flaky")),
      calling(call("c2", "echo")),
      CompletionFixture.simple("never sent")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo, flaky)))

    // the first run's one call (30 tokens) is under its budget; its tool then fails the run
    cause(agent.run(thread, "go", limited(31)).error) shouldBe a[GraphError.ToolFailed]
    client.callCount shouldBe 1

    // the recover's budget counts from its admission: the earlier 30 tokens are not this run's, so it calls once
    broken.set(false)
    val recovered = agent.recover(thread, limited(30)).value
    recovered.status shouldBe AgentStatus.TokenLimitReached
    client.callCount shouldBe 2
    tokens(recovered.usage) shouldBe 60L
    recovered.messages.collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1", "c2")
  }

  it should "not limit a run without one" in {
    val client = ScriptedLLMClient.of(
      calling(call("c1", "echo")),
      calling(call("c2", "echo")),
      CompletionFixture.simple("unlimited")
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(echo)))

    agent.run(thread, "go").value.answer shouldBe Some("unlimited")
  }
}
