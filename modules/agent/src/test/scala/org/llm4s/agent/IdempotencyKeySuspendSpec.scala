package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.{ GraphRuntime, IdempotencyKey, InMemoryCheckpointer, InterruptId, ThreadId }
import org.llm4s.agent.graph.middleware._
import org.llm4s.agent.graph.tool.{ ToolContent, ToolContext, ToolOutcome }
import org.llm4s.agent.graph.toolloop.{ ApprovalRequest, MiddlewareQuestionRequest }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/**
 * A tool call's idempotency key across the suspend points #1704 added (#1821): a call held by a breakpoint before
 * `Tool`, a model message held by a breakpoint after `Model` (whose replayed routes carry each call's key), and a
 * `wrapToolCall` middleware question followed by an approval. Each run keeps the key the call has in a turn that
 * never suspends: the same model call on the same thread, in a store of its own, so of the same claim token.
 */
class IdempotencyKeySuspendSpec extends AnyFlatSpec with Matchers {
  import IdempotencyKeySuspendSpec._

  private val thread = ThreadId("keys")

  /** The key each `deploy` ran with, by its text. */
  final private class Keys {
    val seen = new ConcurrentHashMap[String, Vector[IdempotencyKey]]()
    val deploy = SpecTools.tool("deploy") { (a, context: ToolContext) =>
      seen.merge(a.text, Vector(context.idempotencyKey), _ ++ _)
      ToolOutcome.Success(ToolContent.Json(ujson.Str(s"deployed ${a.text}")))
    }
    def byText: Map[String, Vector[IdempotencyKey]] = seen.asScala.toMap
  }

  private def twoCalls(answer: String): ScriptedLLMClient =
    ScriptedLLMClient.of(
      calling(call("c1", "deploy", "one"), call("c2", "deploy", "two")),
      CompletionFixture.simple(answer)
    )

  private def builder(client: ScriptedLLMClient, keys: Keys): AgentBuilder =
    Agent
      .builder("assistant", client)
      .withTools(SpecTools.set(keys.deploy))
      .withRuntime(GraphRuntime(new InMemoryCheckpointer()))

  /** The keys of the two calls in a turn that never suspends. */
  private def unsuspended(configure: AgentBuilder => AgentBuilder = identity): Map[String, Vector[IdempotencyKey]] =
    val keys = Keys()
    built(configure(builder(twoCalls("done"), keys))).run(thread, "deploy both").value.answer shouldBe Some("done")
    keys.byText

  private def suspended(result: AgentResult): AgentStatus.Suspended = result.status match {
    case s: AgentStatus.Suspended => s
    case other                    => fail(s"expected Suspended, got $other")
  }

  "A call held by a breakpoint before Tool" should "run with the key it has in a turn that is not held" in {
    val expected = unsuspended()
    expected.keySet shouldBe Set("one", "two")
    expected("one") should not be expected("two")

    val keys   = Keys()
    val agent  = built(builder(twoCalls("done"), keys).withInterruptBefore(AgentNode.Tool))
    val parked = agent.run(thread, "deploy both").value
    val held   = suspended(parked).breakpoints.map(_._1)
    held should have size 2
    keys.byText shouldBe empty

    val partly = agent.resume(thread, Map(parked.proceed(held(1)))).value
    keys.byText shouldBe Map("two" -> expected("two"))
    val done = agent.resume(thread, Map(partly.proceed(held.head))).value
    done.answer shouldBe Some("done")
    keys.byText shouldBe expected
  }

  "The calls of a message held by a breakpoint after Model" should "run with their keys, carried by the replayed routes" in {
    val expected = unsuspended()

    val keys   = Keys()
    val agent  = built(builder(twoCalls("done"), keys).withInterruptAfter(AgentNode.Model))
    val parked = agent.run(thread, "deploy both").value
    val held   = suspended(parked).breakpoints.map(_._1)
    held should have size 1
    keys.byText shouldBe empty

    // the routes the held model task recorded are replayed: both calls run, then the model's answer is held
    val second = agent.resume(thread, Map(parked.proceed(held.head))).value
    keys.byText shouldBe expected
    val again = suspended(second).breakpoints.map(_._1)
    agent.resume(thread, Map(second.proceed(again.head))).value.answer shouldBe Some("done")
    keys.byText shouldBe expected
  }

  "A call asked about by a tool wrapper and then approved" should "keep its key through the question and the approval" in {
    val keys     = Keys()
    val asks     = Environment()
    val approval = new ApprovalMiddleware(r => Some(s"check ${r.call.id}"))
    val agent    = built(builder(twoCalls("done"), keys).withMiddleware(asks, approval))
    val parked   = agent.run(thread, "deploy both").value
    val asked: Vector[(InterruptId, MiddlewareQuestionRequest)] = suspended(parked).middlewareQuestions
    asked.map(_._2.call.map(_.call.id)) shouldBe Vector(Some("c1"), Some("c2"))
    val askedKeys =
      asked.map(_._2.call.map(_.idempotencyKey).getOrElse(fail("a tool wrapper's question names its call")))
    askedKeys.distinct should have size 2

    val replies                                           = asked.map((id, _) => parked.reply(id, Info("prod"))).toMap
    val answered                                          = agent.resume(thread, replies).value
    val approvals: Vector[(InterruptId, ApprovalRequest)] = suspended(answered).approvals
    approvals.map(_._2.call.id) shouldBe Vector("c1", "c2")
    approvals.map(_._2.idempotencyKey) shouldBe askedKeys
    keys.byText shouldBe empty

    val done = agent.resume(thread, approvals.map((id, _) => answered.approve(id)).toMap).value
    done.answer shouldBe Some("done")
    keys.byText shouldBe Map("one" -> Vector(askedKeys(0)), "two" -> Vector(askedKeys(1)))
    asks.asked.get shouldBe 2
    // and the key of the same call in a turn that never asks or waits for approval
    keys.byText shouldBe unsuspended(
      _.withMiddleware(Environment(answer = Some(Info("prod"))), new ApprovalMiddleware(_ => None))
    )
  }
}

object IdempotencyKeySuspendSpec {
  final case class Need(what: String) derives ReadWriter
  final case class Info(value: String) derives ReadWriter

  /**
   * Asks which environment each call is for, then runs it; with `answer` set, never asks and runs it at once - the
   * same stack, so the same graph, without the question.
   */
  final class Environment(
    val asked: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger,
    answer: Option[Info] = None
  ) extends AgentMiddleware.Asking[Need, Info] {
    val id: MiddlewareId = MiddlewareId("environment")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      answered(context).map(_.answer).orElse(answer) match {
        case Some(_) => next()
        case None =>
          asked.incrementAndGet()
          askAbout(Need(s"environment for ${request.call.id}"))
      }
  }
}
