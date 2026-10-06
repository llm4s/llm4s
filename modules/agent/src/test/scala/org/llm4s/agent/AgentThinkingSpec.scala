package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * A model's thinking stays in the thread (#1381): the tool loop stores the completion's message
 * as it came back, thinking included, so the model call after a tool call - and every later turn,
 * which reads the history back from the checkpoint - sends it to the client, which replays it
 * where its provider accepts it.
 */
class AgentThinkingSpec extends AnyFlatSpec with Matchers {

  private val weather = ToolBuilder[Map[String, Any], ujson.Value](
    "weather",
    "Reports the weather",
    Schema.`object`[Map[String, Any]]("Weather parameters")
  ).withHandler(_ => Right(ujson.Str("sunny")))
    .buildSafe()
    .fold(e => fail(e.formatted), identity)

  private val call = ToolCall("call-1", "weather", ujson.Obj())

  private val thinking = Seq(
    ThinkingBlock.Text("The user wants the weather; call the tool.", Some("sig-1")),
    ThinkingBlock.Redacted("opaque")
  )

  private val toolTurn = AssistantMessage(None, Seq(call), thinking)

  private def agent(client: ScriptedLLMClient, streaming: Boolean = false) = {
    val builder = Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(weather)))
    built(if (streaming) builder.withStreaming() else builder)
  }

  "An agent run" should "send the tool-call turn's thinking, unchanged, in the model call after the tool result" in {
    val client = ScriptedLLMClient.of(CompletionFixture.withMessage(toolTurn), CompletionFixture.simple("Sunny."))
    val result = agent(client).run("Weather?").value

    result.status shouldBe AgentStatus.Completed("Sunny.")
    client.sent(1) shouldBe Vector(UserMessage("Weather?"), toolTurn, ToolMessage("sunny", "call-1"))
    result.messages.collectFirst { case a: AssistantMessage if a.hasToolCalls => a.thinking } shouldBe Some(thinking)
  }

  it should "keep it when streaming, where the client returns the accumulated message" in {
    val client = ScriptedLLMClient.of(CompletionFixture.withMessage(toolTurn), CompletionFixture.simple("Sunny."))
    agent(client, streaming = true).run("Weather?").value
    client.sent(1).collectFirst { case a: AssistantMessage => a.thinking } shouldBe Some(thinking)
  }

  it should "keep it in the thread's history for the next turn, read back from the checkpoint" in {
    val client = ScriptedLLMClient.of(
      CompletionFixture.withMessage(toolTurn),
      CompletionFixture.simple("Sunny."),
      CompletionFixture.simple("You're welcome.")
    )
    val a     = agent(client)
    val first = a.run("Weather?").value
    a.continueConversation(first, "Thanks").value

    client.sent(2).collectFirst { case m: AssistantMessage if m.hasToolCalls => m.thinking } shouldBe Some(thinking)
  }
}
