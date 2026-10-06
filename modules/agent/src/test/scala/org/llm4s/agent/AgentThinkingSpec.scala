package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.RunContext
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId }
import org.llm4s.agent.graph.toolloop.{ MessageUpdate, Messages, StoredMessage }
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
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

  private val signedAnswer = AssistantMessage("The weather is sunny.").withThinking(thinking)

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

  // A signed turn is valid only unchanged (Anthropic, Bedrock): every rewrite of a stored message
  // goes through AssistantMessage's setters, which unseal the thinking when they change something.

  private def rewriting(to: String => String) = new AgentMiddleware {
    val id: MiddlewareId                                                         = MiddlewareId("rewrite")
    override def afterAgent(answer: String, context: RunContext): Result[String] = Right(to(answer))
  }

  private def storedAnswer(result: AgentResult) =
    result.messages.collectFirst { case a: AssistantMessage => a }.getOrElse(fail("no assistant message"))

  "An afterAgent answer replacement" should "unseal a signed answer's thinking, keeping its text" in {
    val client = ScriptedLLMClient.of(CompletionFixture.withMessage(signedAnswer))
    val result =
      built(Agent.builder("assistant", client).withMiddleware(rewriting(_ + " (checked)"))).run("Weather?").value

    val stored = storedAnswer(result)
    stored.content shouldBe "The weather is sunny. (checked)"
    stored.hasSealedThinking shouldBe false
    stored.thinking shouldBe Seq(ThinkingBlock.Text("The user wants the weather; call the tool."))
  }

  it should "leave the signed thinking as it is when the answer is unchanged" in {
    val client = ScriptedLLMClient.of(CompletionFixture.withMessage(signedAnswer))
    val result = built(Agent.builder("assistant", client).withMiddleware(rewriting(identity))).run("Weather?").value
    storedAnswer(result) shouldBe signedAnswer
  }

  "A tool-call edit" should "unseal the edited turn's thinking" in {
    val history = Vector(StoredMessage("u", UserMessage("Weather?")), StoredMessage("a", toolTurn))
    val edited  = Messages.key.applyUpdate(history, MessageUpdate.EditToolCall("a", "call-1", ujson.Obj("x" -> 1)))
    val turn = edited.toOption.flatMap(_.lift(1)).map(_.message) match {
      case Some(a: AssistantMessage) => a
      case other                     => fail(s"expected the edited assistant message, got $other")
    }
    turn.toolCalls.map(_.arguments) shouldBe Seq(ujson.Obj("x" -> 1))
    turn.hasSealedThinking shouldBe false
  }

  "ContextPruning's default token counter" should "count an assistant message's thinking and tool calls" in {
    val plain = AssistantMessage("Done.")
    val words = (1 to 1000).map(i => s"w$i").mkString(" ")
    ContextPruning.defaultTokenCounter(plain.withThinking(words)) should be >=
      ContextPruning.defaultTokenCounter(plain) + 1300
    ContextPruning.defaultTokenCounter(plain.withThinking(Seq(ThinkingBlock.Redacted("z" * 400)))) shouldBe
      ContextPruning.defaultTokenCounter(plain) + 100
    ContextPruning.defaultTokenCounter(
      AssistantMessage(None, Seq(ToolCall("c", "t", ujson.Obj("q" -> words))))
    ) should be >= 1300
  }
}
