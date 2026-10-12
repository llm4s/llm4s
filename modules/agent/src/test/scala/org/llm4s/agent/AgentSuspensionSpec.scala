package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling, Text }
import org.llm4s.agent.graph.InterruptId
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome }
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters._

/** Parked turns: approvals from `ApprovalMiddleware`, questions from asking tools, and `resume`. */
class AgentSuspensionSpec extends AnyFlatSpec with Matchers {

  final case class Confirm(prompt: String) derives ReadWriter
  final case class Answer(ok: Boolean) derives ReadWriter

  private val ran = new CopyOnWriteArrayList[String]()

  private val deploy = SpecTools.tool("deploy") { (a, _) =>
    ran.add(a.text)
    ToolOutcome.Success(ujson.Str(s"deployed ${a.text}"))
  }

  private def agentOver(client: ScriptedLLMClient, tools: AgentTool[?]*): Agent =
    built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(tools*))
        .withMiddleware(new ApprovalMiddleware(request => Some(s"review ${request.call.id}")))
    )

  private def approvalsOf(result: AgentResult): Vector[InterruptId] = result.status match {
    case AgentStatus.Suspended(approvals, _, _, _) => approvals.map(_._1)
    case other                                     => fail(s"expected Suspended, got $other")
  }

  "A tool behind ApprovalMiddleware" should "suspend with one approval, and resume completes once approved" in {
    ran.clear()
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "prod")), CompletionFixture.simple("shipped"))
    val agent  = agentOver(client, deploy)

    val parked = agent.run("deploy").value
    val ids    = approvalsOf(parked)
    ids should have size 1
    ran.size shouldBe 0

    val done = agent.resume(parked.threadId, Map(parked.approve(ids.head))).value
    done.answer shouldBe Some("shipped")
    ran.asScala.toVector shouldBe Vector("prod")
  }

  "startResume" should "return a run that can be cancelled" in {
    val entered = new java.util.concurrent.CountDownLatch(1)
    val slow = SpecTools.tool("deploy") { (_, _) =>
      entered.countDown()
      Thread.sleep(60_000) // interrupted by cancel
      ToolOutcome.Success(ujson.Str("never"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "prod")), CompletionFixture.simple("shipped"))
    val agent  = agentOver(client, slow)

    val parked = agent.run("deploy").value
    val run = agent
      .startResume(parked.threadId, Map(parked.approve(approvalsOf(parked).head)))
      .fold(e => fail(e.message), identity)
    entered.await(10, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
    run.cancel()
    run.await().isLeft shouldBe true
    run.status should not be org.llm4s.agent.graph.RunStatus.Running
  }

  it should "stay Suspended with the rest pending when resumed with only some of the approvals" in {
    ran.clear()
    val client = ScriptedLLMClient.of(
      calling(call("c1", "deploy", "one"), call("c2", "deploy", "two")),
      CompletionFixture.simple("both shipped")
    )
    val agent  = agentOver(client, deploy)
    val parked = agent.run("deploy twice").value
    val ids    = approvalsOf(parked)
    ids should have size 2

    val partial = agent.resume(parked.threadId, Map(parked.approve(ids.head))).value
    val left    = approvalsOf(partial)
    left should have size 1
    left shouldBe ids.tail
    partial.answer shouldBe None

    val done = agent.resume(parked.threadId, Map(partial.approve(left.head))).value
    done.answer shouldBe Some("both shipped")
    ran.asScala.toVector.sorted shouldBe Vector("one", "two")
  }

  it should "run an edited call with its new arguments" in {
    ran.clear()
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "staging")), CompletionFixture.simple("ok"))
    val agent  = agentOver(client, deploy)
    val parked = agent.run("deploy").value

    val done =
      agent.resume(parked.threadId, Map(parked.edit(approvalsOf(parked).head, ujson.Obj("text" -> "canary")))).value

    done.answer shouldBe Some("ok")
    ran.asScala.toVector shouldBe Vector("canary")
  }

  it should "not run a rejected call, and tell the model why" in {
    ran.clear()
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy", "prod")), CompletionFixture.simple("understood"))
    val agent  = agentOver(client, deploy)
    val parked = agent.run("deploy").value

    val done = agent.resume(parked.threadId, Map(parked.reject(approvalsOf(parked).head, "not on a Friday"))).value

    done.answer shouldBe Some("understood")
    ran.size shouldBe 0
    done.messages.collect { case t: ToolMessage => t.content }.head should include("Rejected: not on a Friday")
  }

  "An asking tool" should "park a question, and resume with the reply continues the call" in {
    val asker = new AgentTool.Asking[Text, Confirm, Answer](SpecTools.spec("confirm")) {
      def execute(args: Text, context: ToolContext): ToolOutcome = ask(Confirm(s"really ${args.text}?"))
      def resume(args: Text, question: Confirm, answer: Answer, context: ToolContext): ToolOutcome =
        ToolOutcome.Success(ujson.Str(s"${question.prompt} ${answer.ok}"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "confirm", "go")), CompletionFixture.simple("confirmed"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(asker)))

    val parked = agent.run("ask me").value
    val (id, request) = parked.status match {
      case AgentStatus.Suspended(approvals, questions, _, _) =>
        approvals shouldBe empty
        questions should have size 1
        questions.head
      case other => fail(s"expected Suspended, got $other")
    }
    request.call.id shouldBe "c1"
    request.question shouldBe ujson.Obj("prompt" -> "really go?")

    val done = agent.resume(parked.threadId, Map(parked.reply(id, Answer(true)))).value

    done.answer shouldBe Some("confirmed")
    done.messages.collect { case t: ToolMessage => t.content }.head should include("really go? true")
  }
}
