package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph.InterruptId
import org.llm4s.agent.graph.middleware.{ ApprovalMiddleware, PermissionMiddleware, PermissionRule }
import org.llm4s.agent.graph.tool.{ AgentTool, Operation, ToolOutcome, ToolResource }
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

/** Tool permissions (#1903): ordered rules over each call's decoded resources, deny if unmatched. */
class ToolPermissionsSpec extends AnyFlatSpec with Matchers {

  private val rules = Seq(
    PermissionRule.deny(Operation.Write, "/etc/**"),
    PermissionRule.ask(Operation.Write, "/prod/**"),
    PermissionRule.allow(Operation.Write, "/tmp/**")
  )

  private def writer(ran: CopyOnWriteArrayList[String]) =
    AgentTool(SpecTools.spec("write").withResources(t => Vector(ToolResource(Operation.Write, t.text)))) { (t, _) =>
      ran.add(t.text)
      ToolOutcome.Success(ujson.Str(s"wrote ${t.text}"))
    }

  private def agentOver(client: ScriptedLLMClient, tools: AgentTool[?]*) =
    built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(tools*))
        .withMiddleware(new PermissionMiddleware(rules))
    )

  private def toolResults(result: AgentResult): Vector[String] =
    result.messages.collect { case t: ToolMessage => t.content }.toVector

  private def approvalsOf(result: AgentResult): Vector[InterruptId] = result.status match {
    case AgentStatus.Suspended(approvals, _, _, _) => approvals.map(_._1)
    case other                                     => fail(s"expected Suspended, got $other")
  }

  "A call" should "run when its resource is allowed and be denied, unrun, when a rule denies it" in {
    val ran = new CopyOnWriteArrayList[String]()
    val client = ScriptedLLMClient.of(
      calling(call("c1", "write", "/tmp/a"), call("c2", "write", "/etc/passwd"), call("c3", "write", "/home/x")),
      CompletionFixture.simple("done")
    )
    val result = agentOver(client, writer(ran)).run("go").value
    ran.asScala.toVector shouldBe Vector("/tmp/a")
    toolResults(result) shouldBe Vector(
      "wrote /tmp/a",
      """{"error":"Denied: write /etc/passwd"}""",
      """{"error":"Denied: write /home/x"}""" // no rule matches
    )
  }

  "A tool without resources" should "be checked as (Invoke, its name), and denied unless a rule allows it" in {
    val ran = new AtomicInteger()
    val plain = SpecTools.tool("lookup") { (_, _) =>
      ran.incrementAndGet(); ToolOutcome.Success(ujson.Str("ok"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "lookup")), CompletionFixture.simple("done"))
    toolResults(agentOver(client, plain).run("go").value) shouldBe Vector("""{"error":"Denied: invoke lookup"}""")
    ran.get shouldBe 0

    val allowing = built(
      Agent
        .builder("assistant", ScriptedLLMClient.of(calling(call("c1", "lookup")), CompletionFixture.simple("done")))
        .withTools(SpecTools.set(plain))
        .withMiddleware(new PermissionMiddleware(Seq(PermissionRule.allow(Operation.Invoke, "lookup"))))
    )
    toolResults(allowing.run("go").value) shouldBe Vector("ok")
    ran.get shouldBe 1
  }

  "An Ask" should "suspend for approval, and the approved call run exactly once" in {
    val ran    = new CopyOnWriteArrayList[String]()
    val client = ScriptedLLMClient.of(calling(call("c1", "write", "/prod/app")), CompletionFixture.simple("shipped"))
    val agent  = agentOver(client, writer(ran))

    val parked = agent.run("go").value
    val ids    = approvalsOf(parked)
    ids should have size 1
    ran.size shouldBe 0

    val done = agent.resume(parked.threadId, Map(parked.approve(ids.head))).value
    done.answer shouldBe Some("shipped")
    ran.asScala.toVector shouldBe Vector("/prod/app")
  }

  "An edited approval" should "be checked again: a deny after the edit still denies" in {
    val ran    = new CopyOnWriteArrayList[String]()
    val client = ScriptedLLMClient.of(calling(call("c1", "write", "/prod/app")), CompletionFixture.simple("ok"))
    val agent  = agentOver(client, writer(ran))
    val parked = agent.run("go").value

    val done =
      agent.resume(parked.threadId, Map(parked.edit(approvalsOf(parked).head, ujson.Obj("text" -> "/etc/x")))).value
    ran.size shouldBe 0
    toolResults(done) shouldBe Vector("""{"error":"Denied: write /etc/x"}""")
  }

  it should "run an edit to another Ask resource as approved" in {
    val ran    = new CopyOnWriteArrayList[String]()
    val client = ScriptedLLMClient.of(calling(call("c1", "write", "/prod/app")), CompletionFixture.simple("ok"))
    val agent  = agentOver(client, writer(ran))
    val parked = agent.run("go").value

    agent.resume(parked.threadId, Map(parked.edit(approvalsOf(parked).head, ujson.Obj("text" -> "/prod/web")))).value
    ran.asScala.toVector shouldBe Vector("/prod/web")
  }

  "Rules" should "be evaluated after schema validation and decoding" in {
    val seen = new AtomicInteger()
    val tool = AgentTool(SpecTools.spec("write").withResources { t =>
      seen.incrementAndGet(); Vector(ToolResource(Operation.Write, t.text))
    })((_, _) => ToolOutcome.Success(ujson.Str("never")))
    val client = ScriptedLLMClient.of(
      calling(ToolCall("c1", "write", ujson.Obj("other" -> 1))),
      CompletionFixture.simple("done")
    )
    toolResults(agentOver(client, tool).run("go").value).head should include("Invalid arguments for 'write'")
    seen.get shouldBe 0
  }

  "A resource function that throws" should "refuse the call as invalid arguments" in {
    val tool =
      AgentTool(SpecTools.spec("write").withResources(_ => throw new IllegalStateException("bad path")))((_, _) =>
        ToolOutcome.Success(ujson.Str("never"))
      )
    val client = ScriptedLLMClient.of(calling(call("c1", "write", "/tmp/a")), CompletionFixture.simple("done"))
    toolResults(agentOver(client, tool).run("go").value).head should include("bad path")
  }

  "An approval from another middleware" should "satisfy the call's Ask: approval is one flag per call" in {
    val ran = new CopyOnWriteArrayList[String]()
    val client =
      ScriptedLLMClient.of(calling(call("c1", "write", "/prod/app")), CompletionFixture.simple("shipped"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(SpecTools.set(writer(ran)))
        .withMiddleware(new ApprovalMiddleware(_ => Some("review")), new PermissionMiddleware(rules))
    )
    val parked = agent.run("go").value
    val ids    = approvalsOf(parked)
    ids should have size 1
    agent.resume(parked.threadId, Map(parked.approve(ids.head))).value.answer shouldBe Some("shipped")
    ran.asScala.toVector shouldBe Vector("/prod/app")
  }
}
