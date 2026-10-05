package org.llm4s.agent

import org.llm4s.agent.graph.toolloop.{ HandoffRequest, ToolLoop }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{
  AssistantMessage,
  Completion,
  Conversation,
  CompletionOptions,
  StreamedChunk,
  ToolCall
}
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/**
 * The tools that make handoffs callable: their validation, their names, and what calling one records. A handoff
 * is identified by its stable id; a tool that merely has a handoff-like name is not one.
 */
class HandoffToolsSpec extends AnyFlatSpec with Matchers {

  private def mkAgent(): Agent = new Agent(
    new DeterministicFakeLLMClient(
      Completion(
        id = "test",
        created = 0L,
        content = "done",
        model = "test-model",
        message = AssistantMessage("done", Seq.empty),
        toolCalls = Nil,
        usage = None
      )
    )
  )

  private def handoff(
    agent: Agent,
    id: String = "target",
    reason: Option[String] = Some("specialist")
  ): Handoff =
    Handoff(id = id, targetAgent = agent, transferReason = reason)

  "HandoffTools.create" should "return no tools when no handoffs are provided" in {
    HandoffTools.create(Seq.empty, Set.empty).map(_.size) shouldBe Right(0)
  }

  it should "create one tool per handoff" in {
    val result = HandoffTools.create(
      Seq(handoff(mkAgent(), "a", Some("reason A")), handoff(mkAgent(), "b", Some("reason B"))),
      Set.empty
    )
    result.map(_.size) shouldBe Right(2)
  }

  it should "use the handoffId as the tool name, and describe the reason" in {
    val h = handoff(mkAgent(), reason = Some("physics expertise"))
    HandoffTools.create(Seq(h), Set.empty) match {
      case Right(tools) =>
        tools should have size 1
        tools.head.spec.name shouldBe h.handoffId
        tools.head.spec.description should include("physics expertise")
      case Left(err) => fail(s"Expected Right but got Left: $err")
    }
  }

  it should "return a ValidationError for a directly constructed handoff with an invalid id" in {
    val result = HandoffTools.create(Seq(handoff(mkAgent(), id = "bad id")), Set.empty)
    result.left.map(_.isInstanceOf[ValidationError]) shouldBe Left(true)
    result.left.map(_.message) shouldBe Left("Invalid handoffs: invalid handoff ids: 'bad id'")
  }

  it should "report invalid and duplicate ids together, each quoted, in one error" in {
    val agent = mkAgent()
    val result = HandoffTools.create(
      Seq(handoff(agent, id = "b"), handoff(agent, id = "bad id"), handoff(agent, id = "a"), handoff(agent, id = "b")),
      Set.empty
    )
    result.left.map {
      case v: ValidationError => v.violations
      case other              => fail(s"expected a ValidationError, got $other")
    } shouldBe Left(List("invalid handoff ids: 'bad id'", "duplicate handoff ids: 'b'"))
  }

  it should "refuse a handoff whose tool name is already a registered tool, quoted, in the same error" in {
    val agent = mkAgent()
    val result = HandoffTools.create(
      Seq(handoff(agent, id = "support"), handoff(agent, id = "bad id")),
      Set("handoff_to_support", "other")
    )
    result.left.map {
      case v: ValidationError => v.violations
      case other              => fail(s"expected a ValidationError, got $other")
    } shouldBe Left(
      List("invalid handoff ids: 'bad id'", "handoff tool names already registered as tools: 'handoff_to_support'")
    )
  }

  "A handoff tool" should "be named handoff_to_<id> and declare the handoff key it writes" in {
    val h    = handoff(mkAgent(), id = "physics")
    val tool = HandoffTools.create(Seq(h), Set.empty).fold(e => fail(e.message), _.head)

    tool.spec.name shouldBe "handoff_to_physics"
    tool.writes should contain(ToolLoop.handoff)
    HandoffRequest("physics", "needs a physicist") shouldBe HandoffRequest(h.id, "needs a physicist")
  }

  // The handoff result a model sees, and the request the loop records, are exercised end to end in HandoffIntegrationSpec.

  "Agent.run" should "refuse a handoff clashing with a registered tool before any model call" in {
    val calls = new AtomicInteger(0)
    val inner = mkAgent()
    val client = new org.llm4s.llmconnect.LLMClient {
      def complete(c: Conversation, o: CompletionOptions) = {
        calls.incrementAndGet()
        Right(Completion("t", 0L, "done", "m", AssistantMessage("done", Seq.empty), Nil, None))
      }
      def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) = complete(c, o)
      def getContextWindow(): Int                                                         = 1000
      def getReserveCompletion(): Int                                                     = 100
    }
    val clash = ToolBuilder[Map[String, Any], String](
      "handoff_to_support",
      "d",
      Schema.`object`[Map[String, Any]]("p")
    ).withHandler(_ => Right("x")).buildSafe().toOption.get

    val result = new Agent(client).run("hi", new ToolRegistry(Seq(clash)), handoffs = Seq(handoff(inner, "support")))

    result.left.map(_.message).left.getOrElse("") should include("'handoff_to_support'")
    calls.get shouldBe 0
  }

  it should "not hand off to anything when the model calls a user tool that only has a handoff-like name" in {
    val target = new AtomicInteger(0)
    val targetAg = new Agent(new org.llm4s.llmconnect.LLMClient {
      def complete(c: Conversation, o: CompletionOptions) = {
        target.incrementAndGet()
        Right(Completion("t", 0L, "from target", "m", AssistantMessage("from target"), Nil, None))
      }
      def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) = complete(c, o)
      def getContextWindow(): Int                                                         = 1000
      def getReserveCompletion(): Int                                                     = 100
    })
    val offered = handoff(targetAg, "offered")
    val userTool = ToolBuilder[Map[String, Any], String](
      "handoff_to_other",
      "A user tool whose name looks like a handoff",
      Schema.`object`[Map[String, Any]]("p")
    ).withHandler(_ => Right("user tool ran")).buildSafe().toOption.get
    val replies = Iterator(
      AssistantMessage("", Seq(ToolCall("c1", "handoff_to_other", ujson.Obj()))),
      AssistantMessage("final answer from the source")
    )
    val source = new org.llm4s.llmconnect.LLMClient {
      def complete(c: Conversation, o: CompletionOptions) = {
        val m = replies.next()
        Right(Completion("t", 0L, m.content, "m", m, m.toolCalls.toList, None))
      }
      def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) = complete(c, o)
      def getContextWindow(): Int                                                         = 1000
      def getReserveCompletion(): Int                                                     = 100
    }

    val thread = new Agent(source)
      .run("hi", new ToolRegistry(Seq(userTool)), handoffs = Seq(offered))
      .fold(e => fail(e.message), identity)

    thread.answer shouldBe Some("final answer from the source")
    target.get shouldBe 0
  }
}
