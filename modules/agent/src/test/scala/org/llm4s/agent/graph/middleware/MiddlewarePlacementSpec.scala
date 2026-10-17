package org.llm4s.agent.graph.middleware

import org.llm4s.agent.{ Agent, Handoff, ScriptedLLMClient }
import org.llm4s.agent.harness.{ SummarizationMiddleware, SummaryCompressor, SummaryTrigger, ToolOutputOffload }
import org.llm4s.llmconnect.model.Message
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Stage 3 slice P1 (#1915, design §4.37): `AgentMiddleware.placement`, checked by the stack and the family. */
class MiddlewarePlacementSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val compressor = new SummaryCompressor {
    def compress(
      previous: Option[String],
      messages: Vector[Message],
      maxTokens: Int,
      context: ModelContext
    ): Result[String] = Right("summary")
  }
  private def summarizer = new SummarizationMiddleware(SummaryTrigger.Messages(10), compressor = compressor)

  /** A middleware that must wrap every `Inner`, as `kind` says. */
  final private class Outer(name: String, kind: String => Placement, after: Set[MiddlewareId] = Set.empty)
      extends AgentMiddleware {
    val id                 = MiddlewareId(name)
    override def runsAfter = after
    override def placement(other: AgentMiddleware): Option[Placement] = other match {
      case _: Inner => Some(kind("it must see what the inner one does"))
      case _        => None
    }
  }
  final private class Inner(name: String, before: Set[MiddlewareId] = Set.empty) extends AgentMiddleware {
    val id                  = MiddlewareId(name)
    override def runsBefore = before
  }

  "A stack" should "refuse a summariser inside tool-output-offload, naming both, the reason and the fix" in {
    val error = MiddlewareStack.of(new ToolOutputOffload(), summarizer).left.value
    error.message should include("'summarization' must run outside 'tool-output-offload'")
    error.message should include("register 'summarization' before 'tool-output-offload'")
  }

  it should "accept a summariser outside tool-output-offload" in {
    MiddlewareStack.of(summarizer, new ToolOutputOffload()).value.ordered.map(_.id.value) shouldBe
      Vector("summarization", "tool-output-offload")
  }

  it should "check the order runsBefore and runsAfter arranged, not the registration order" in {
    // registered outer first, but its runsAfter arranges it inside: refused
    val arrangedInside = new Outer("out", Placement.Outside(_), after = Set(MiddlewareId("in")))
    MiddlewareStack.of(arrangedInside, new Inner("in")).isLeft shouldBe true
    // registered inner first, but its runsBefore keeps it outside: refused
    MiddlewareStack
      .of(new Inner("in", before = Set(MiddlewareId("out"))), new Outer("out", Placement.Outside(_)))
      .isLeft shouldBe
      true
    // registered inner first, and the outer arranged outside it by its runsAfter naming nothing: refused, registration
    // order stands
    MiddlewareStack.of(new Inner("in"), new Outer("out", Placement.Outside(_))).isLeft shouldBe true
    // registered inner first, with the outer arranged outside it by the inner's runsAfter: accepted
    final class Placed extends AgentMiddleware {
      val id                 = MiddlewareId("in")
      override def runsAfter = Set(MiddlewareId("out"))
    }
    MiddlewareStack.of(new Placed, new Outer("out", Placement.Outside(_))).value.ordered.map(_.id.value) shouldBe
      Vector("out", "in")
  }

  it should "list every misplaced pair in one error" in {
    val error =
      MiddlewareStack.of(new Inner("a"), new Inner("b"), new Outer("x", Placement.Outside(_))).left.value
    error.message should include("'x' must run outside 'a'")
    error.message should include("'x' must run outside 'b'")
  }

  "A family" should "refuse an OutsideAtBoundary middleware on a handoff target when the root has the other" in {
    val client = ScriptedLLMClient.of()
    val target = Agent.builder("target", client).withMiddleware(new Outer("out", Placement.OutsideAtBoundary(_)))
    val error = Agent
      .builder("root", client)
      .withMiddleware(new Inner("in"))
      .withHandoffs(Handoff.to("target", target, "delegate"))
      .build()
      .left
      .value
    error.message should include("agent 'target': middleware 'out' must run outside 'in'")
    error.message should include("the root agent 'root' has 'in'")
  }

  it should "accept it on the root, and an Outside requirement across agents, whose wrappers do not nest" in {
    val client = ScriptedLLMClient.of()
    val onRoot = Agent
      .builder("root", client)
      .withMiddleware(new Outer("out", Placement.OutsideAtBoundary(_)))
      .withHandoffs(Handoff.to("target", Agent.builder("target", client).withMiddleware(new Inner("in")), "delegate"))
      .build()
    onRoot.isRight shouldBe true
    val outsideOnly = Agent
      .builder("root", client)
      .withMiddleware(new Inner("in"))
      .withHandoffs(
        Handoff.to(
          "target",
          Agent.builder("target", client).withMiddleware(new Outer("out", Placement.Outside(_))),
          "delegate"
        )
      )
      .build()
    outsideOnly.isRight shouldBe true
  }
}
