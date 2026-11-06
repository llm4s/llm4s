package org.llm4s.tapir

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.llmconnect.model.ToolMessage
import org.llm4s.tapir.Fixtures._
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{ Identity, UriContext }
import sttp.tapir.server.stub.TapirStubInterpreter

/** An agent calling tapir endpoints served in-process by tapir's stub interpreter: no network. */
class EndpointToolsAgentSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val orders = Map("42" -> Order("42", 9.5))

  @volatile private var cancelled = Vector.empty[String]

  private val backend =
    TapirStubInterpreter(SttpBackendStub.synchronous)
      .whenServerEndpointRunLogic(
        getOrder.serverLogic[Identity](id => orders.get(id).toRight(ApiError(s"no order $id")))
      )
      .whenServerEndpointRunLogic(
        cancelOrder.serverLogic[Identity] { id =>
          cancelled = cancelled :+ id
          orders.get(id).toRight(ApiError(s"no order $id"))
        }
      )
      .backend()

  private val getTool    = EndpointTools.tool("get_order", getOrder)
  private val cancelTool = EndpointTools.tool("cancel_order", cancelOrder)
  private val tools      = EndpointTools.registry(uri"http://orders.test", backend, getTool, cancelTool)

  "an agent" should "call an endpoint as a tool and read its output" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_order", """{"id":"42"}"""),
      Reply.text("Order 42 totals 9.5.")
    )
    val result = (for {
      registry <- tools
      agent    <- Agent.builder("orders", client).withTools(registry).build()
      result   <- agent.run("What does order 42 total?")
    } yield result).value

    result.answer shouldBe Some("Order 42 totals 9.5.")
    val toolResults = client.calls(1).messages.collect { case t: ToolMessage => ujson.read(t.content) }
    toolResults shouldBe Seq(ujson.Obj("id" -> "42", "total" -> 9.5))
  }

  it should "read the endpoint's error output as a tool error" in {
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("get_order", """{"id":"7"}"""),
      Reply.text("There is no order 7.")
    )
    val result = (for {
      registry <- tools
      agent    <- Agent.builder("orders", client).withTools(registry).build()
      result   <- agent.run("What does order 7 total?")
    } yield result).value

    result.answer shouldBe Some("There is no order 7.")
    client
      .calls(1)
      .messages
      .collect { case t: ToolMessage => ujson.read(t.content)("error").str }
      .mkString should include(
      """HTTP 400: {"message":"no order 7"}"""
    )
  }

  it should "ask before calling an endpoint that requires approval, and call it once approved" in {
    val gated = EndpointTools.requiringApproval(getTool, cancelTool)
    val client = ScriptedLLMClient.sequence(
      Reply.toolCall("cancel_order", """{"id":"42"}"""),
      Reply.text("Order 42 is cancelled.")
    )
    val approval = new ApprovalMiddleware(call => Option.when(gated(call.spec.name))("this changes an order"))
    val outcome = for {
      registry  <- tools
      agent     <- Agent.builder("orders", client).withTools(registry).withMiddleware(approval).build()
      suspended <- agent.run("Cancel order 42")
    } yield (agent, suspended)
    val (agent, suspended) = outcome.value

    suspended.status shouldBe a[AgentStatus.Suspended]
    cancelled shouldBe empty

    val answers = suspended.status match {
      case s: AgentStatus.Suspended => s.approvals.map((id, _) => suspended.approve(id)).toMap
      case other                    => fail(s"expected a suspension, got $other")
    }
    val resumed = agent.resume(suspended.threadId, answers).value
    resumed.answer shouldBe Some("Order 42 is cancelled.")
    cancelled shouldBe Vector("42")
  }
}
