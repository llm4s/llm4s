package org.llm4s.agent.orchestration

import ch.qos.logback.classic.{ Level, Logger => LBLogger }
import org.llm4s.agent.{ Agent, CompletionFixture, Handoff, NTurnFakeLLMClient }
import org.llm4s.error.NetworkError
import org.llm4s.llmconnect.model.{ AssistantMessage, ToolMessage }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.scalatest.Outcome
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import upickle.default.{ macroRW, ReadWriter }

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Real `Agent`s (tool calling, handoffs) running as nodes of an orchestrated DAG, and failure
 * propagation out of a node (issue #999).
 *
 * Pure-function DAG shapes (linear, parallel, diamond, timing) are already covered by
 * PlanRunnerSpec and IntegrationSpec, so they are not repeated here.
 */
class OrchestratorIntegrationSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  implicit val ec: ExecutionContext                    = ExecutionContext.global
  implicit override val patienceConfig: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  override def withFixture(test: NoArgTest): Outcome = {
    val loggers = Seq(
      "org.llm4s.agent.orchestration.TypedAgent$",
      "org.llm4s.agent.orchestration.PlanRunner",
      "org.llm4s.agent.orchestration.Policies$"
    ).map(LoggerFactory.getLogger(_).asInstanceOf[LBLogger])
    val previous = loggers.map(_.getLevel)
    loggers.foreach(_.setLevel(Level.OFF))
    try super.withFixture(test)
    finally loggers.zip(previous).foreach { case (l, lv) => l.setLevel(lv) }
  }

  final case class AdditionResult(sum: Double)
  object AdditionResult {
    implicit val rw: ReadWriter[AdditionResult] = macroRW
  }

  private def adderTool(invocations: AtomicInteger): ToolFunction[Map[String, Any], AdditionResult] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Adder parameters")
      .withRequiredField("a", Schema.number("First operand"))
      .withRequiredField("b", Schema.number("Second operand"))
    ToolBuilder[Map[String, Any], AdditionResult]("adder", "Adds two numbers", schema)
      .withHandler { extractor =>
        for {
          a <- extractor.getDouble("a")
          b <- extractor.getDouble("b")
        } yield {
          invocations.incrementAndGet()
          AdditionResult(a + b)
        }
      }
      .buildSafe()
      .fold(e => fail(s"adder tool failed to build: $e"), identity)
  }

  "An Agent used as a DAG node" should "run its tool and feed the tool result to the downstream node" in {
    val invocations = new AtomicInteger(0)
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall("adder", ujson.Obj("a" -> 3, "b" -> 4)),
      CompletionFixture.simple("done")
    )

    // The node returns the content of the tool message the agent recorded, so the assertion
    // depends on the tool really having executed rather than on the canned model reply.
    val agentNode = TypedAgent.fromFuture[String, String]("tool-caller") { query =>
      Future {
        new Agent(client)
          .run(query, new ToolRegistry(Seq(adderTool(invocations))), maxSteps = Some(5))
          .map(_.conversation.messages.collect { case m: ToolMessage => m.content }.mkString("|"))
      }
    }
    val downstream = TypedAgent.fromFunction[String, String]("downstream")(s => Right(s"downstream:$s"))

    val toolCaller = Node("tool-caller", agentNode)
    val after      = Node("downstream", downstream)
    val plan       = Plan.builder.addNode(toolCaller).addNode(after).addEdge(Edge("e", toolCaller, after)).build

    whenReady(PlanRunner().execute(plan, Map("tool-caller" -> "What is 3 + 4?"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      invocations.get() shouldBe 1
      outputs("tool-caller").asInstanceOf[String] should include("7")
      outputs("downstream").asInstanceOf[String] shouldBe s"downstream:${outputs("tool-caller")}"
    }
  }

  it should "resolve a handoff to a specialist and pass the specialist's answer downstream" in {
    val specialist = new Agent(new NTurnFakeLLMClient(CompletionFixture.simple("Specialist answer: 42")))
    val handoff    = Handoff.to(specialist, "Math specialist")
    val primary = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall(handoff.handoffId, ujson.Obj("reason" -> "Needs specialist"), "call_handoff")
    )

    val handoffNode = TypedAgent.fromFuture[String, String]("handoff-agent") { query =>
      Future {
        new Agent(primary)
          .run(query, ToolRegistry.empty, handoffs = Seq(handoff), maxSteps = Some(10))
          .map(_.conversation.messages.collect { case m: AssistantMessage => m.content }.filter(_.nonEmpty).last)
      }
    }
    val downstream = TypedAgent.fromFunction[String, String]("post-handoff")(s => Right(s"received:$s"))

    val n1   = Node("handoff-agent", handoffNode)
    val n2   = Node("post-handoff", downstream)
    val plan = Plan.builder.addNode(n1).addNode(n2).addEdge(Edge("e", n1, n2)).build

    whenReady(PlanRunner().execute(plan, Map("handoff-agent" -> "What is the answer?"))) { result =>
      val outputs = result.fold(e => fail(s"plan failed: $e"), identity)
      outputs("handoff-agent") shouldBe "Specialist answer: 42"
      outputs("post-handoff") shouldBe "received:Specialist answer: 42"
    }
  }

  "A failing DAG node" should "return its NetworkError unchanged and never run downstream nodes" in {
    val original           = NetworkError("Connection refused", None, "http://internal-service")
    val downstreamExecuted = new AtomicBoolean(false)

    val failing = TypedAgent.fromFunction[String, String]("failing")(_ => Left(original))
    val downstream = TypedAgent.fromFunction[String, String]("downstream") { _ =>
      downstreamExecuted.set(true)
      Right("unreachable")
    }

    val n1   = Node("failing", failing)
    val n2   = Node("downstream", downstream)
    val plan = Plan.builder.addNode(n1).addNode(n2).addEdge(Edge("e", n1, n2)).build

    whenReady(PlanRunner().execute(plan, Map("failing" -> "trigger"))) { result =>
      result.fold(identity, o => fail(s"expected failure, got $o")) shouldBe original
      downstreamExecuted.get() shouldBe false
    }
  }
}
