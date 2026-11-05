package org.llm4s.samples.tapir

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.tapir.EndpointTools
import org.llm4s.testkit.{ Reply, ScriptedLLMClient }
import org.llm4s.types.Result
import sttp.client3.testing.SttpBackendStub
import sttp.client3.{ Identity, UriContext }
import sttp.tapir._
import sttp.tapir.server.stub.TapirStubInterpreter

/**
 * Two endpoints of an order service, served in-process by tapir's stub interpreter, offered to an agent as tools
 * with `EndpointTools` (docs/guide/tapir-tools.md).
 *
 * The model is scripted (`ScriptedLLMClient`), so the example needs no API key and no network: it looks an order up,
 * then cancels it, which suspends the run for approval because `POST` changes something; the example approves it.
 *
 * A `main` method, not `extends App`: the tools run on the agent's threads, and an `App` object's fields are set in its
 * static initialiser, which those threads would wait on while `main` waits for them.
 *
 * {{{
 * sbt "samples/runMain org.llm4s.samples.tapir.TapirEndpointToolsExample"
 * }}}
 */
object TapirEndpointToolsExample {

  def main(args: Array[String]): Unit = {

    val getOrder =
      endpoint.get
        .in("orders" / path[String]("id").description("The order's id"))
        .out(stringBody)
        .errorOut(stringBody)
        .description("Look up an order by id")

    val cancelOrder =
      endpoint.post
        .in("orders" / path[String]("id") / "cancel")
        .out(stringBody)
        .errorOut(stringBody)
        .description("Cancel an order")

    val orders = Map("42" -> """{"id":"42","status":"open","total":9.5}""")

    val backend =
      TapirStubInterpreter(SttpBackendStub.synchronous)
        .whenServerEndpointRunLogic(getOrder.serverLogic[Identity](id => orders.get(id).toRight(s"no order $id")))
        .whenServerEndpointRunLogic(
          cancelOrder.serverLogic[Identity](id =>
            orders.get(id).map(_ => s"""{"id":"$id","status":"cancelled"}""").toRight(s"no order $id")
          )
        )
        .backend()

    val tools = Seq(EndpointTools.tool("get_order", getOrder), EndpointTools.tool("cancel_order", cancelOrder))

    val model = ScriptedLLMClient.sequence(
      Reply.toolCall("get_order", """{"id": "42"}"""),
      Reply.toolCall("cancel_order", """{"id": "42"}"""),
      Reply.text("Order 42 (9.50) is cancelled.")
    )

    val gated    = EndpointTools.requiringApproval(tools*)
    val approval = new ApprovalMiddleware(call => Option.when(gated(call.spec.name))("this changes an order"))

    def approveAll(agent: Agent, result: AgentResult): Result[AgentResult] = result.status match {
      case suspended: AgentStatus.Suspended =>
        suspended.approvals.foreach((_, request) => println(s"Approving: $request"))
        agent.resume(result.threadId, suspended.approvals.map((id, _) => result.approve(id)).toMap)
      case _ => Right(result)
    }

    val outcome = for {
      registry <- EndpointTools.registry(uri"http://orders.test", backend, tools*)
      agent    <- Agent.builder("orders", model).withTools(registry).withMiddleware(approval).build()
      first    <- agent.run("Cancel order 42 and tell me what it cost.")
      done     <- approveAll(agent, first)
    } yield done

    outcome match {
      case Right(result) =>
        println(s"Tool calls: ${model.calls.size - 1}")
        println(s"Answer: ${result.answer.getOrElse("(none)")}")
      case Left(error) =>
        println(s"Failed: ${error.message}")
    }

  }
}
