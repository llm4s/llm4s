package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.agent.graph.toolloop.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolHints }
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

object ApprovalMiddlewareFixtures {
  final case class Env(env: String) derives ReadWriter
}

class ApprovalMiddlewareSpec extends AnyFlatSpec with Matchers with EitherValues {
  import ApprovalMiddlewareFixtures.*

  final private class ScriptedModel(turns: (Vector[Message] => AssistantMessage)*) extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] = {
      val turn = seen.size
      seen.add(messages)
      turns.lift(turn).map(play => Right(play(messages))).getOrElse(Left(ValidationError("model", s"no turn $turn")))
    }
  }

  private def call(id: String, name: String, env: String): Vector[Message] => AssistantMessage =
    _ => AssistantMessage(None, Seq(ToolCall(id, name, ujson.Obj("env" -> env))))

  private val summarise: Vector[Message] => AssistantMessage = history =>
    AssistantMessage("done: " + history.collect { case t: ToolMessage => t.content }.mkString(" | "))

  private val runs = new CopyOnWriteArrayList[String]()

  private def tool(name: String, hints: ToolHints): AgentTool[Env] =
    AgentTool(
      AgentToolSpec[Env](
        name,
        s"The $name tool",
        Schema.`object`[Env](name).withRequiredField("env", Schema.string("env"))
      )
        .withHints(hints)
    ) { (a, _) =>
      runs.add(s"$name:${a.env}")
      ToolOutcome.Success(ujson.Str(s"ran $name"))
    }

  private val lookup = tool("lookup", ToolHints().withReadOnly(true))
  private val deploy = tool("deploy", ToolHints.default)

  private def build(model: ModelStep, mw: ApprovalMiddleware) =
    ToolLoop.build("assistant", "v1", model, ToolSet.of(lookup, deploy).value, Seq(mw)).value

  "ApprovalMiddleware.unlessReadOnly" should "run a read-only tool without suspending" in {
    runs.clear()
    val model       = ScriptedModel(call("c1", "lookup", "x"), summarise)
    val l           = build(model, ApprovalMiddleware.unlessReadOnly)
    val (_, answer) = runInMemory(l.graph, "go").completed
    answer shouldBe "done: ran lookup"
    runs.asScala.toVector shouldBe Vector("lookup:x")
  }

  it should "suspend a default-hinted tool, run it once on Approve and record Rejected on Reject" in {
    runs.clear()
    val model    = ScriptedModel(call("c1", "deploy", "prod"), summarise)
    val l        = build(model, ApprovalMiddleware.unlessReadOnly)
    val first    = runInMemory(l.graph, "go").suspended
    val requests = l.requests(first).value
    requests.map((_, r) => (r.source, r.reason)) shouldBe
      Vector((ApprovalSource.Middleware(MiddlewareId("approval")), "tool 'deploy' is not read-only"))
    runs.size shouldBe 0
    val (id, _) = requests.head
    val (_, answer) =
      drive(l.graph, l.graph.resume(first.execution, l.answers(id -> ApprovalDecision.Approve)).value).completed
    answer shouldBe "done: ran deploy"
    runs.asScala.toVector shouldBe Vector("deploy:prod")

    runs.clear()
    val model2   = ScriptedModel(call("c1", "deploy", "prod"), summarise)
    val l2       = build(model2, ApprovalMiddleware.unlessReadOnly)
    val first2   = runInMemory(l2.graph, "go").suspended
    val (id2, _) = l2.requests(first2).value.head
    val rejected = ApprovalDecision.Reject("no")
    drive(l2.graph, l2.graph.resume(first2.execution, l2.answers(id2 -> rejected)).value).completed
    runs.size shouldBe 0
    model2.seen.get(1).collect { case t: ToolMessage => ujson.read(t.content)("error").str } shouldBe Vector(
      "Rejected: no"
    )
  }

  it should "never suspend when a custom requires returns None" in {
    runs.clear()
    val model = ScriptedModel(call("c1", "deploy", "prod"), summarise)
    val l     = build(model, new ApprovalMiddleware(_ => None))
    runInMemory(l.graph, "go").completed
    runs.asScala.toVector shouldBe Vector("deploy:prod")
  }
}
