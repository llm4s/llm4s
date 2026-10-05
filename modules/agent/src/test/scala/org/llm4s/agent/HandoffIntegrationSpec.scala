package org.llm4s.agent

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

/**
 * Handoffs end to end: the source agent's model calls a handoff tool, the loop ends, and the target agent answers the
 * conversation transferred to it.
 */
class HandoffIntegrationSpec extends AnyFlatSpec with Matchers {

  /** A client that plays `replies` in order and records every conversation and option set it was given. */
  final private class ScriptedClient(replies: AssistantMessage*) extends LLMClient {
    private var index = 0
    val calls         = ArrayBuffer.empty[(Conversation, CompletionOptions)]

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls += ((conversation, options))
      val msg = replies(math.min(index, replies.length - 1))
      index += 1
      Right(
        Completion(
          id = "mock-completion",
          created = 0L,
          content = msg.content,
          model = "mock-model",
          message = msg,
          toolCalls = msg.toolCalls.toList,
          usage = None
        )
      )
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private def handoffCall(handoff: Handoff, reason: String): AssistantMessage =
    AssistantMessage(
      "I'll hand this off to the specialist",
      Seq(ToolCall("call_1", handoff.handoffId, ujson.Obj("reason" -> reason)))
    )

  /** A source agent whose model first answers `first`, then hands off `Question 2`'s turn. */
  private def handedOffThread(handoff: Handoff, source: ScriptedClient): AgentThread = {
    val agent = new Agent(source)
    val result = for {
      turn1 <- agent.run("Question 1", ToolRegistry.empty, handoffs = Seq(handoff))
      turn2 <- agent.continueConversation(turn1, "Question 2", ToolRegistry.empty, handoffs = Seq(handoff))
    } yield turn2
    result.fold(e => fail(s"Agent run failed: ${e.formatted}"), identity)
  }

  "Agent with handoffs" should "offer a handoff tool for each handoff" in {
    val client  = new ScriptedClient(AssistantMessage("hello"))
    val handoff = Handoff.to("specialist", new Agent(client), "Specialist")

    new Agent(client).run("Test query", ToolRegistry.empty, handoffs = Seq(handoff)).isRight shouldBe true

    client.calls.head._2.tools.map(_.name) should contain(handoff.handoffId)
  }

  it should "run the target on the handoff and return the target's thread" in {
    val target  = new ScriptedClient(AssistantMessage("Math specialist response here"))
    val handoff = Handoff.to("specialist", new Agent(target), "Math specialist")
    val source  = new ScriptedClient(handoffCall(handoff, "Requires advanced math"))

    val result = new Agent(source).run(
      "What is the derivative of x^2?",
      ToolRegistry.empty,
      handoffs = Seq(handoff),
      maxSteps = Some(10)
    )

    val thread = result.fold(e => fail(s"Agent run failed: ${e.formatted}"), identity)
    thread.answer shouldBe Some("Math specialist response here")
    source.calls should have size 1 // the source was not asked again
    target.calls should have size 1
    // the handoff request is part of what the thread recorded
    thread.messages
      .collect { case t: ToolMessage => t.content }
      .exists(_.contains("Requires advanced math")) shouldBe true
  }

  it should "give the target no tools, and the source's tools stay with the source" in {
    val target  = new ScriptedClient(AssistantMessage("done"))
    val handoff = Handoff.to("specialist", new Agent(target))
    val source  = new ScriptedClient(handoffCall(handoff, "r"))

    new Agent(source).run("Q", ToolRegistry.empty, handoffs = Seq(handoff)).isRight shouldBe true

    target.calls.head._2.tools shouldBe empty
  }

  it should "transfer the whole conversation when preserveContext = true" in {
    val target  = new ScriptedClient(AssistantMessage("answer from target"))
    val handoff = Handoff("specialist", new Agent(target), Some("Test"), preserveContext = true)
    val source  = new ScriptedClient(AssistantMessage("Answer 1"), handoffCall(handoff, "Test"))

    handedOffThread(handoff, source).answer shouldBe Some("answer from target")

    val seen = target.calls.head._1.messages.filterNot(_.role == MessageRole.System).map(_.content)
    (seen should contain).allOf("Question 1", "Answer 1", "Question 2")
    // the handoff request, and what the tool answered, are transferred with it
    target.calls.head._1.messages.exists {
      case t: ToolMessage => t.content.contains("handoff_requested")
      case _              => false
    } shouldBe true
  }

  it should "transfer only the last user message when preserveContext = false" in {
    val target  = new ScriptedClient(AssistantMessage("answer from target"))
    val handoff = Handoff("specialist", new Agent(target), Some("Test"), preserveContext = false)
    val source  = new ScriptedClient(AssistantMessage("Answer 1"), handoffCall(handoff, "Test"))

    handedOffThread(handoff, source).answer shouldBe Some("answer from target")

    target.calls.head._1.messages.filterNot(_.role == MessageRole.System).map(_.content) shouldBe Seq("Question 2")
  }

  it should "transfer the system message only when transferSystemMessage = true" in {
    def systemSeenByTarget(transfer: Boolean): Option[String] = {
      val target  = new ScriptedClient(AssistantMessage("done"))
      val handoff = Handoff("specialist", new Agent(target), Some("Test"), transferSystemMessage = transfer)
      val source  = new ScriptedClient(handoffCall(handoff, "Test"))
      new Agent(source)
        .run("Q", ToolRegistry.empty, handoffs = Seq(handoff), systemPromptAddition = Some("SOURCE-ONLY-RULE"))
        .isRight shouldBe true
      target.calls.head._1.messages.headOption.filter(_.role == MessageRole.System).map(_.content)
    }

    systemSeenByTarget(transfer = true).getOrElse(fail("Expected the system message")) should include(
      "SOURCE-ONLY-RULE"
    )
    systemSeenByTarget(transfer = false) shouldBe None
  }

  it should "find the handoff by its id among the handoffs of this call, not by a reference to the target" in {
    // two agents behind two ids: the model's request names the id, and only that agent runs
    val physics  = new ScriptedClient(AssistantMessage("physics answer"))
    val poetry   = new ScriptedClient(AssistantMessage("poetry answer"))
    val hPhysics = Handoff.to("physics", new Agent(physics))
    val hPoetry  = Handoff.to("poetry", new Agent(poetry))
    val source   = new ScriptedClient(handoffCall(hPoetry, "wants a poem"))

    val result = new Agent(source).run("Q", ToolRegistry.empty, handoffs = Seq(hPhysics, hPoetry))

    result.map(_.answer) shouldBe Right(Some("poetry answer"))
    physics.calls shouldBe empty
    poetry.calls should have size 1
  }

  it should "take the first handoff when the model asks for two in one reply" in {
    val a  = new ScriptedClient(AssistantMessage("from a"))
    val b  = new ScriptedClient(AssistantMessage("from b"))
    val hA = Handoff.to("a", new Agent(a))
    val hB = Handoff.to("b", new Agent(b))
    val both = AssistantMessage(
      "both",
      Seq(
        ToolCall("c1", hA.handoffId, ujson.Obj("reason" -> "first")),
        ToolCall("c2", hB.handoffId, ujson.Obj("reason" -> "second"))
      )
    )

    val result = new Agent(new ScriptedClient(both)).run("Q", ToolRegistry.empty, handoffs = Seq(hA, hB))

    result.map(_.answer) shouldBe Right(Some("from a"))
    b.calls shouldBe empty
  }
}
