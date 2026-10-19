package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling, Text }
import org.llm4s.agent.graph.{ EffectKey, GraphError, ReplayGuard }
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContent, ToolContext, ToolOutcome }
import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.{ ConcurrentLinkedQueue, CopyOnWriteArrayList }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.jdk.CollectionConverters._

/**
 * A tool call's effect identity names its phase (design §4.24): `execute`, and every run of it - an approval
 * re-run, `recover` - is one phase; each answered `resume` is another, keyed by the phase that asked, the
 * question and the answer.
 */
class AgentToolEffectPhaseSpec extends AnyFlatSpec with Matchers {

  final case class Confirm(prompt: String) derives ReadWriter
  final case class Answer(ok: Boolean) derives ReadWriter

  /** An asking tool whose `execute` and every `resume` add one to a counter, under the same operation and target. */
  final private class Counting(asks: Int, failResumeOnce: Boolean = false) {
    val applied  = new AtomicInteger()
    val keys     = new ConcurrentLinkedQueue[EffectKey]()
    val failing  = new AtomicBoolean(failResumeOnce)
    val answered = new CopyOnWriteArrayList[String]()
    private def add(context: ToolContext): Either[org.llm4s.error.LLMError, Int] = {
      val key = context.effectKey("counter.add", "the-counter")
      keys.add(key)
      ReplayGuard.unverifiable[Int](context.run, key, idempotent = false)(Right(applied.incrementAndGet()))
    }
    val tool: AgentTool[Text] = new AgentTool.Asking[Text, Confirm, Answer](SpecTools.spec("confirm")) {
      def execute(args: Text, context: ToolContext): ToolOutcome =
        add(context).fold(ToolOutcome.Fatal(_), _ => ask(Confirm("sure?")))
      def resume(args: Text, question: Confirm, answer: Answer, context: ToolContext): ToolOutcome =
        add(context) match {
          case Left(error) => ToolOutcome.Fatal(error)
          case Right(_) if failing.getAndSet(false) =>
            ToolOutcome.Fatal(ProcessingError("confirm", "down after its effect"))
          case Right(n) =>
            answered.add(question.prompt)
            // the same question again, answered the same: still a phase of its own
            if answered.size < asks then ask(Confirm("sure?")) else ToolOutcome.Success(ToolContent.Json(ujson.Num(n)))
        }
    }
  }

  private def questionId(result: AgentResult) = result.status match {
    case AgentStatus.Suspended(_, questions, _, _, _) => questions.head._1
    case other                                        => fail(s"expected Suspended, got $other")
  }

  private def agentWith(tool: AgentTool[?]): Agent =
    built(
      Agent
        .builder(
          "assistant",
          ScriptedLLMClient.of(calling(call("c1", "confirm", "go")), CompletionFixture.simple("done"))
        )
        .withTools(SpecTools.set(tool))
    )

  "An answered resume" should "be an effect of its own, never replaying execute's" in {
    val counting = new Counting(asks = 1)
    val agent    = agentWith(counting.tool)
    val parked   = agent.run("go").value
    counting.applied.get shouldBe 1
    val done = agent.resume(parked.threadId, Map(parked.reply(questionId(parked), Answer(true)))).value
    done.answer shouldBe Some("done")
    counting.applied.get shouldBe 2
    counting.keys.asScala.toVector.distinct should have size 2
  }

  it should "give each of several questions in sequence its own phase, the same question and answer included" in {
    val counting = new Counting(asks = 3)
    val agent    = agentWith(counting.tool)
    val result = (1 to 3).foldLeft(agent.run("go").value) { (parked, _) =>
      agent.resume(parked.threadId, Map(parked.reply(questionId(parked), Answer(true)))).value
    }
    result.answer shouldBe Some("done")
    counting.applied.get shouldBe 4
    counting.keys.asScala.toVector.distinct should have size 4
  }

  it should "keep its phase when a failed resume is recovered, replaying its effect" in {
    val counting = new Counting(asks = 1, failResumeOnce = true)
    val agent    = agentWith(counting.tool)
    val parked   = agent.run("go").value
    agent.resume(parked.threadId, Map(parked.reply(questionId(parked), Answer(true)))).isLeft shouldBe true
    counting.applied.get shouldBe 2
    agent.recover(parked.threadId).value.answer shouldBe Some("done")
    counting.applied.get shouldBe 2
    counting.keys.asScala.toVector.distinct should have size 2
  }

  "A re-run of execute after approval" should "keep execute's key, replaying its effect" in {
    val applied = new AtomicInteger()
    val keys    = new ConcurrentLinkedQueue[EffectKey]()
    val tool = SpecTools.tool("confirm") { (_, context) =>
      val key = context.effectKey("counter.add", "the-counter")
      keys.add(key)
      ReplayGuard.unverifiable[Int](context.run, key, idempotent = false)(Right(applied.incrementAndGet())) match {
        case Left(r: GraphError.EffectReplayRefused) => ToolOutcome.Error(r.message)
        case Left(error)                             => ToolOutcome.Fatal(error)
        case Right(n) if !context.approved           => ToolOutcome.NeedsApproval(s"applied $n")
        case Right(n)                                => ToolOutcome.Success(ToolContent.Json(ujson.Num(n)))
      }
    }
    val agent  = agentWith(tool)
    val parked = agent.run("go").value
    val id = parked.status match {
      case AgentStatus.Suspended(approvals, _, _, _, _) => approvals.head._1
      case other                                        => fail(s"expected Suspended, got $other")
    }
    agent.resume(parked.threadId, Map(parked.approve(id))).value.answer shouldBe Some("done")
    applied.get shouldBe 1
    keys.asScala.toVector.distinct should have size 1
    parked.messages.collect { case t: ToolMessage => t.content } shouldBe empty
  }
}
